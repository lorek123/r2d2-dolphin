"""Julius-based voice recognition for R2D2.

Wraps the Julius speech recogniser as a subprocess and implements the same
two-phase state machine as the original Android app:

    WAIT  → (wake word detected) → WAKE  → robot responds to commands
    WAKE  → (15 s silence)       → WAIT  → back to listening for wake word

The acoustic model and grammar files shipped in ``python/voice/`` are the
original Japanese JNAS model and grammars extracted from the APK.

Requirements:
    Julius must be installed on the system (``apt install julius``).

Usage::

    from r2d2.voice import JuliusRecognizer, VoiceCommand

    def on_cmd(cmd: VoiceCommand):
        print("Heard:", cmd)

    rec = JuliusRecognizer(on_cmd)
    rec.start()
    ...
    rec.stop()
"""

import enum
import logging
import re
import subprocess
import threading
from pathlib import Path
from typing import Callable, Optional

logger = logging.getLogger(__name__)

# Directory containing command.jconf, grammar/, model/
_VOICE_DIR = Path(__file__).parent.parent / "voice"


class VoiceCommand(enum.Enum):
    WAKE_UP       = "wake_up"
    TURN_LEFT     = "turn_left"
    TURN_RIGHT    = "turn_right"
    TURN_AROUND   = "turn_around"
    GO_FORWARD    = "go_forward"
    SHAKE_HEAD    = "shake_head"
    WALK_A_CIRCLE = "walk_a_circle"
    DANCE         = "dance"
    MAKE_SOME_NOISE = "make_some_noise"
    WHO_ARE_YOU   = "who_are_you"
    SKY_WALKER    = "sky_walker"
    PRINCESS_LEIA = "princess_leia"
    LIGHT_SABER   = "light_saber"
    ARMS          = "arms"
    PATROL        = "patrol"
    STOP          = "stop"


# Julius strips brackets from dict word names before storing them.
# The grammar automaton produces sequences of word names that Julius
# concatenates in the CALLBACK_RESULT handler (confirmed via Ghidra).
# These strings match the ``ja/arrays.xml`` resource entries verbatim.
#
# Format: Julius output token(s) joined with no separator → VoiceCommand
_COMMAND_MAP: dict[str, VoiceCommand] = {
    # --- Wake words (ar2d2 grammar) ---
    "2":   VoiceCommand.WAKE_UP,
    "y":   VoiceCommand.WAKE_UP,
    "y2":  VoiceCommand.WAKE_UP,
    "m":   VoiceCommand.WAKE_UP,
    "m2":  VoiceCommand.WAKE_UP,
    # --- Movement (cr2d2 / br2d2 grammar) ---
    "l":   VoiceCommand.TURN_LEFT,
    "lt":  VoiceCommand.TURN_LEFT,
    "r":   VoiceCommand.TURN_RIGHT,
    "rt":  VoiceCommand.TURN_RIGHT,
    "t":   VoiceCommand.TURN_AROUND,
    "f":   VoiceCommand.GO_FORWARD,
    "fn":  VoiceCommand.GO_FORWARD,
    "fnf": VoiceCommand.GO_FORWARD,
    "h":   VoiceCommand.SHAKE_HEAD,
    "hs":  VoiceCommand.SHAKE_HEAD,
    "c":   VoiceCommand.WALK_A_CIRCLE,
    # --- Behaviors ---
    "d":   VoiceCommand.DANCE,
    "a":   VoiceCommand.ARMS,
    "li":  VoiceCommand.LIGHT_SABER,
    "pt":  VoiceCommand.PATROL,
    "ptm": VoiceCommand.PATROL,
    "p":   VoiceCommand.PATROL,
}

# Silence tokens Julius emits — filtered out before matching
_SILENCE = {"silB", "silE", "s", "/s"}


class _State(enum.Enum):
    WAIT = "wait"   # only wake-word grammar active
    WAKE = "wake"   # full command grammar active


class JuliusRecognizer:
    """Spawn Julius as a subprocess and dispatch recognised voice commands.

    Args:
        on_command: Called with a :class:`VoiceCommand` on each recognition.
        voice_dir:  Directory with Julius assets. Defaults to ``python/voice/``.
        wake_timeout: Seconds of silence before returning to WAIT state.
    """

    WAKE_TIMEOUT = 15.0

    def __init__(
        self,
        on_command: Callable[[VoiceCommand], None],
        voice_dir: Optional[Path] = None,
        wake_timeout: float = WAKE_TIMEOUT,
    ) -> None:
        self._on_command = on_command
        self._voice_dir = voice_dir or _VOICE_DIR
        self._wake_timeout = wake_timeout

        self._state = _State.WAIT
        self._proc: Optional[subprocess.Popen] = None
        self._reader: Optional[threading.Thread] = None
        self._timer: Optional[threading.Timer] = None
        self._running = False
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start listening for the wake word."""
        self._running = True
        self._launch(_State.WAIT)
        logger.info("Voice recognition started (WAIT state)")

    def stop(self) -> None:
        """Stop recognition and clean up."""
        self._running = False
        self._cancel_timer()
        self._kill()
        logger.info("Voice recognition stopped")

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _launch(self, state: _State) -> None:
        self._kill()
        self._state = state
        jconf = "wake_command.jconf" if state == _State.WAIT else "command.jconf"
        jconf_path = self._voice_dir / jconf
        logger.debug("Julius starting with %s", jconf)
        self._proc = subprocess.Popen(
            ["julius", "-C", str(jconf_path)],
            cwd=str(self._voice_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        self._reader = threading.Thread(
            target=self._read_loop, args=(self._proc,), daemon=True
        )
        self._reader.start()

    def _kill(self) -> None:
        if self._proc:
            try:
                self._proc.terminate()
                self._proc.wait(timeout=2)
            except Exception:
                pass
            self._proc = None

    def _cancel_timer(self) -> None:
        if self._timer:
            self._timer.cancel()
            self._timer = None

    def _read_loop(self, proc: subprocess.Popen) -> None:
        """Parse Julius stdout in a background thread."""
        try:
            for line in proc.stdout:
                if not self._running:
                    break
                self._parse_line(line.strip())
        except Exception:
            pass

    def _parse_line(self, line: str) -> None:
        # Julius emits either:
        #   pass1_best: word1 word2 ...   (1-pass mode, from -1pass flag)
        #   sentence1:  word1 word2 ...   (full search result)
        if not (line.startswith("pass1_best:") or line.startswith("sentence")):
            return

        parts = line.split(":", 1)
        if len(parts) < 2:
            return

        tokens = [t for t in parts[1].split() if t not in _SILENCE]
        if not tokens:
            return

        phrase = "".join(tokens)
        logger.debug("Julius phrase: %r (state=%s)", phrase, self._state.value)

        cmd = self._match(phrase)
        if cmd is None:
            return

        with self._lock:
            if self._state == _State.WAIT:
                if cmd == VoiceCommand.WAKE_UP:
                    logger.info("Wake word detected — switching to WAKE")
                    self._cancel_timer()
                    self._launch(_State.WAKE)
                    self._timer = threading.Timer(self._wake_timeout, self._on_timeout)
                    self._timer.start()
            else:
                if cmd == VoiceCommand.WAKE_UP:
                    # Re-wake resets the timeout
                    self._cancel_timer()
                    self._timer = threading.Timer(self._wake_timeout, self._on_timeout)
                    self._timer.start()
                else:
                    self._cancel_timer()
                    self._timer = threading.Timer(self._wake_timeout, self._on_timeout)
                    self._timer.start()
                    try:
                        self._on_command(cmd)
                    except Exception:
                        logger.exception("Error in voice command callback")

    def _match(self, phrase: str) -> Optional[VoiceCommand]:
        """Match a phrase using startswith, mirroring the Java indexOf(cmd)==0 logic."""
        for key, cmd in _COMMAND_MAP.items():
            if phrase.startswith(key):
                return cmd
        return None

    def _on_timeout(self) -> None:
        if self._running:
            logger.info("Wake timeout — returning to WAIT state")
            with self._lock:
                self._launch(_State.WAIT)


def bind_to_robot(recognizer: JuliusRecognizer, robot) -> None:
    """Wire a JuliusRecognizer to an R2D2 instance.

    Replaces the recognizer's ``on_command`` callback with one that calls
    the appropriate robot method for each VoiceCommand.

    Args:
        recognizer: A :class:`JuliusRecognizer` instance (not yet started).
        robot:      An :class:`r2d2.R2D2` instance.
    """
    _ACTION_MAP = {
        VoiceCommand.TURN_LEFT:     robot.turn_left,
        VoiceCommand.TURN_RIGHT:    robot.turn_right,
        VoiceCommand.TURN_AROUND:   robot.turn_around,
        VoiceCommand.GO_FORWARD:    robot.go_forward,
        VoiceCommand.SHAKE_HEAD:    robot.shake_head,
        VoiceCommand.WALK_A_CIRCLE: robot.walk_circle,
        VoiceCommand.DANCE:         robot.dance,
        VoiceCommand.WHO_ARE_YOU:   robot.who_are_you,
        VoiceCommand.LIGHT_SABER:   lambda: robot.lightsaber(True),
        VoiceCommand.ARMS:          lambda: robot.arm(True),
        VoiceCommand.PATROL:        lambda: robot.patrol(True),
        VoiceCommand.STOP:          robot.stop,
        VoiceCommand.SKY_WALKER:    lambda: robot.projector(2),
        VoiceCommand.PRINCESS_LEIA: lambda: robot.projector(1),
        VoiceCommand.MAKE_SOME_NOISE: robot.dance,  # closest equivalent
        VoiceCommand.WAKE_UP:       lambda: None,   # handled internally
    }

    def _dispatch(cmd: VoiceCommand) -> None:
        action = _ACTION_MAP.get(cmd)
        if action:
            logger.info("Voice command: %s", cmd.value)
            action()

    recognizer._on_command = _dispatch
