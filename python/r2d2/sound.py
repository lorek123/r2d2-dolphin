"""Sound playback for R2D2.

Mirrors Android SoundPlayer: plays MP3 files from the ``python/sounds/``
directory using ``mpg123`` (preferred) or ``ffplay`` as a fallback.
Playback is non-blocking; an optional completion callback fires when the
sound finishes.

Sound ID → filename mapping is taken verbatim from SoundPlayer.java.
"""

import logging
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Callable, Optional

logger = logging.getLogger(__name__)

_SOUNDS_DIR = Path(__file__).parent.parent / "sounds"

# Sound ID → filename  (matches SoundPlayer.getSoundEffectRawId)
_SOUND_FILES: dict[int, str] = {
    0:   "pulling_it_together.mp3",
    1:   "sing_song_response.mp3",
    2:   "abrupt_burst.mp3",
    3:   "alarmed_thrill.mp3",
    4:   "building_freak_out.mp3",
    5:   "curt_reply.mp3",
    6:   "danger_danger.mp3",
    7:   "happiness_confirmation.mp3",
    8:   "happy_three_chirp.mp3",
    9:   "lonely_hello.mp3",
    10:  "lonely_singing.mp3",
    11:  "nagging_whine.mp3",
    12:  "short_raspberry.mp3",
    13:  "startled_three_tone.mp3",
    14:  "startled_whoop.mp3",
    15:  "stifled_laugh.mp3",
    16:  "uncertain_two_tone.mp3",
    17:  "unconvinced_grumbling.mp3",
    18:  "upset_two_tone.mp3",
    100: "starwar01_right_02.mp3",
    101: "starwar03_right_02.mp3",
    301: "angle.mp3",
    302: "stark.mp3",
}

CompletionCallback = Callable[[], None]


def _find_player() -> Optional[list[str]]:
    """Return the command prefix for an available audio player, or None."""
    for cmd in ("mpg123", "ffplay", "aplay"):
        if shutil.which(cmd):
            if cmd == "mpg123":
                return ["mpg123", "-q"]
            if cmd == "ffplay":
                return ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"]
            # aplay doesn't support mp3 natively but may via plugins
            return ["aplay", "-q"]
    return None


_PLAYER_CMD = _find_player()


class SoundPlayer:
    """Non-blocking MP3 player using a system audio command.

    Mirrors Android SoundPlayer behaviour:
    - ``play(id, interrupt=True)`` starts playback, killing any current sound
      if ``interrupt`` is True.
    - ``stop()`` kills current playback immediately.
    - ``mute`` property suppresses all playback.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._proc: Optional[subprocess.Popen] = None
        self._watcher: Optional[threading.Thread] = None
        self.mute: bool = False

        if _PLAYER_CMD is None:
            logger.warning(
                "No audio player found (mpg123/ffplay). "
                "Install mpg123 for sound playback: apt install mpg123"
            )

    def play(
        self,
        sound_id: int,
        interrupt: bool = True,
        on_complete: Optional[CompletionCallback] = None,
    ) -> None:
        """Play a sound by ID.

        Args:
            sound_id:    Sound ID from the SoundPlayer constants.
            interrupt:   Kill current sound if one is playing.
            on_complete: Optional callback fired when playback finishes.
        """
        if self.mute or _PLAYER_CMD is None:
            if on_complete:
                threading.Thread(target=on_complete, daemon=True).start()
            return

        filename = _SOUND_FILES.get(sound_id)
        if filename is None:
            logger.warning("Unknown sound ID: %d", sound_id)
            return

        path = _SOUNDS_DIR / filename
        if not path.exists():
            logger.warning("Sound file missing: %s", path)
            return

        with self._lock:
            if self._proc is not None:
                if not interrupt:
                    return  # already playing and we must not interrupt
                self._kill_current()

            try:
                self._proc = subprocess.Popen(
                    _PLAYER_CMD + [str(path)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            except FileNotFoundError:
                logger.error("Audio player not found: %s", _PLAYER_CMD[0])
                return

        logger.debug("Playing sound %d: %s", sound_id, filename)

        self._watcher = threading.Thread(
            target=self._watch,
            args=(self._proc, on_complete),
            daemon=True,
            name=f"sound-{sound_id}",
        )
        self._watcher.start()

    def stop(self) -> None:
        """Stop current playback immediately."""
        with self._lock:
            self._kill_current()

    def pause(self) -> None:
        """Alias for stop (no pause/resume in subprocess model)."""
        self.stop()

    @property
    def is_playing(self) -> bool:
        with self._lock:
            return self._proc is not None and self._proc.poll() is None

    # ------------------------------------------------------------------

    def _kill_current(self) -> None:
        """Kill the current player process (must be called under self._lock)."""
        if self._proc is not None:
            try:
                self._proc.terminate()
            except Exception:
                pass
            self._proc = None

    def _watch(
        self,
        proc: subprocess.Popen,
        on_complete: Optional[CompletionCallback],
    ) -> None:
        """Wait for playback to finish, then fire the callback."""
        proc.wait()
        with self._lock:
            if self._proc is proc:
                self._proc = None
        if on_complete:
            try:
                on_complete()
            except Exception:
                logger.exception("Error in sound completion callback")
