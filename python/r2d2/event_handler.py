"""High-level event handler with sequenced job queue.

Mirrors the Android EventHandler: jobs are queued as (delay, callable)
pairs and executed in order. A new sequence always cancels the previous one.

This also implements the higher-level named behaviors (dance, patrol, etc.)
that are composed from primitive commander calls.
"""

import logging
import threading
import time
from typing import Callable, Optional

from .commander import Commander
from .models import RobotMode
from .sound import SoundPlayer

logger = logging.getLogger(__name__)

Job = tuple[float, Callable[[], None]]  # (delay_seconds, action)


class EventHandler:
    """Sequenced job queue for ordered, timed robot actions.

    Usage::

        eh = EventHandler(commander, sound_player)
        eh.dance()           # queues a multi-step dance sequence
        eh.move(50, 0)       # immediately cancels dance and moves forward
    """

    def __init__(self, commander: Commander, sound: Optional[SoundPlayer] = None) -> None:
        self._cmd = commander
        self._sound = sound or SoundPlayer()
        self._lock = threading.Lock()
        self._cancel_event = threading.Event()
        self._runner: Optional[threading.Thread] = None
        self._move_sound_timer: Optional[threading.Timer] = None
        self._move_sound_active = False
        self._on_wake: Optional[Callable[[], None]] = None

    def set_wake_callback(self, callback: Callable[[], None]) -> None:
        """Register a function called on every executed job (to reset sleep timer)."""
        self._on_wake = callback

    # ------------------------------------------------------------------
    # Core queue machinery
    # ------------------------------------------------------------------

    def _run_sequence(self, jobs: list[Job], cancel: threading.Event) -> None:
        for delay, action in jobs:
            if cancel.is_set():
                logger.debug("Sequence cancelled")
                return
            if delay > 0:
                deadline = time.monotonic() + delay
                while time.monotonic() < deadline:
                    if cancel.is_set():
                        return
                    time.sleep(0.02)
            if not cancel.is_set():
                if self._on_wake:
                    try:
                        self._on_wake()
                    except Exception:
                        pass
                try:
                    action()
                except Exception:
                    logger.exception("Error executing job")

    def _start(self, jobs: list[Job]) -> None:
        """Cancel any running sequence and start a new one."""
        with self._lock:
            self._cancel_event.set()
            if self._runner and self._runner.is_alive():
                self._runner.join(timeout=0.5)
            self._cancel_event = threading.Event()
            cancel = self._cancel_event
        self._runner = threading.Thread(
            target=self._run_sequence,
            args=(jobs, cancel),
            name="event-seq",
            daemon=True,
        )
        self._runner.start()

    def stop(self) -> None:
        """Cancel any running sequence and stop the robot."""
        with self._lock:
            self._cancel_event.set()
        self._cmd.mode(0)

    # ------------------------------------------------------------------
    # Primitive helpers used by sequences
    # ------------------------------------------------------------------

    def _head(self, angle: int) -> Callable[[], None]:
        return lambda: self._cmd.move_head_angle(angle)

    def _mode(self, mode: int) -> Callable[[], None]:
        return lambda: self._cmd.mode(mode)

    def _sound_job(self, sound_id: int) -> Callable[[], None]:
        return lambda: self._sound.play(sound_id, interrupt=True)

    # ------------------------------------------------------------------
    # Real-time direct control (no sequencing, immediate)
    # ------------------------------------------------------------------

    def move(self, power: int, angle: int) -> None:
        """Immediately move the robot. Cancels any running sequence."""
        self.stop()
        self._cmd.move(power, angle)
        if angle in (0, 180) and power > 0:
            self._reset_move_sound_timer()

    def move_head(self, angle: int) -> None:
        self.stop()
        self._cmd.move_head_angle(angle)

    def shift_head(self, angle: int) -> None:
        self.stop()
        self._cmd.head_shift(angle)

    def move_head_dir(self, direction: int) -> None:
        self.stop()
        self._cmd.move_head_direction(direction)

    def led(self, r: int, b: int, y: int, g: int) -> None:
        self.stop()
        self._cmd.led(r, b, y, g)

    def lcd(self, s: int, l: int) -> None:
        self.stop()
        self._cmd.lcd(s, l)

    def reset_mcu(self) -> None:
        self._cmd.reset_watchdog()

    def change_head_power(self, power: int) -> None:
        self._cmd.set_head_power(power)

    def change_leg_power(self, power: int) -> None:
        self._cmd.set_leg_power(power)

    # ------------------------------------------------------------------
    # Movement sound scheduling (mimics Android MovingTimerTask)
    # ------------------------------------------------------------------

    def _reset_move_sound_timer(self) -> None:
        if self._move_sound_timer:
            self._move_sound_timer.cancel()
        self._move_sound_timer = threading.Timer(1.0, self._stop_move_sounds)
        self._move_sound_timer.daemon = True
        self._move_sound_timer.start()

    def _stop_move_sounds(self) -> None:
        self._move_sound_active = False

    # ------------------------------------------------------------------
    # Named behavior sequences
    # ------------------------------------------------------------------

    def shake_head(self) -> None:
        self._start([
            (0,    self._sound_job(7)),
            (0,    self._head(-45)),
            (0.6,  self._head(45)),
            (0.6,  self._head(-45)),
            (0.6,  self._head(45)),
            (0.6,  self._head(0)),
        ])

    def voice_wake_up(self) -> None:
        self._start([
            (0,    self._sound_job(8)),
            (0,    self._head(-40)),
            (0.6,  self._head(40)),
            (0.6,  self._head(0)),
        ])

    def dance(self) -> None:
        jobs: list[Job] = []
        for _ in range(4):
            jobs += [
                (0,    self._sound_job(1)),
                (0,    self._mode(3)),
                (0,    self._head(-40)),
                (0.6,  self._mode(2)),
                (0.6,  self._head(40)),
                (0.6,  self._mode(3)),
                (0.6,  self._head(-40)),
                (0.6,  self._head(40)),
                (0.6,  self._head(0)),
                (0.6,  self._mode(2)),
                (0.6,  lambda: None),
            ]
        self._start(jobs)

    def who_are_you(self) -> None:
        self._start([
            (0,    self._sound_job(0)),
            (0,    self._head(-40)),
            (0.6,  self._head(40)),
            (0.6,  self._head(0)),
        ])

    def turn_around(self) -> None:
        self._start([
            (0,   self._sound_job(7)),
            (0,   self._mode(2)),
        ])

    def turn_left(self) -> None:
        self._start([
            (0,   self._sound_job(7)),
            (0,   self._mode(3)),
        ])

    def turn_right(self) -> None:
        self._start([
            (0,   self._sound_job(7)),
            (0,   self._mode(4)),
        ])

    def go_forward(self) -> None:
        self._start([
            (0,   self._sound_job(7)),
            (0,   self._mode(5)),
        ])

    def walk_circle(self) -> None:
        self._start([
            (0,   self._sound_job(7)),
            (0.7, self._mode(12)),
        ])

    def flash_front_lcd(self) -> None:
        self._start([(0, self._mode(13))])

    def flash_back_lcd(self) -> None:
        self._start([(0, self._mode(14))])

    def mode_stop(self) -> None:
        self._start([
            (0, self._sound_job(7)),
            (0, self._mode(0)),
        ])

    def make_some_noise(self) -> None:
        self._sound.play(6, interrupt=True)

    def not_recognize(self) -> None:
        self._start([(0, self._sound_job(12))])

    def angle_secret(self) -> None:
        self._start([(0, self._sound_job(301))])

    def stark_secret(self) -> None:
        self._start([(0, self._sound_job(302))])

    def short_lcd(self, current_short: bool, current_long: bool) -> None:
        """Toggle the short LCD panel (mirrors Android shortLCD)."""
        s = 1 if current_short else 2   # toggle: if on→off, if off→on
        l = 2 if current_long else 1    # keep long panel state
        self._start([
            (0, lambda: self._cmd.projector(0)),
            (0, self._sound_job(7)),
            (0, lambda: self._cmd.lcd(s, l)),
        ])

    def long_lcd(self, current_short: bool, current_long: bool) -> None:
        """Toggle the long LCD panel (mirrors Android longLCD)."""
        s = 2 if current_short else 1   # keep short panel state
        l = 1 if current_long else 2    # toggle: if on→off, if off→on
        self._start([
            (0, lambda: self._cmd.projector(0)),
            (0, self._sound_job(7)),
            (0, lambda: self._cmd.lcd(s, l)),
        ])

    def lightsaber(self, power: int) -> None:
        self._start([
            (0, lambda: self._cmd.projector(0)),
            (0, self._sound_job(7)),
            (0, lambda: self._cmd.lightsaber(power)),
        ])

    def arm(self, power: int) -> None:
        self._start([
            (0, lambda: self._cmd.projector(0)),
            (0, self._sound_job(7)),
            (0, lambda: self._cmd.arm(power)),
        ])

    def projector_mode(self, mode: int, on_complete: Optional[Callable[[], None]] = None) -> None:
        if mode == 0:
            self._start([
                (0, lambda: self._cmd.projector(0)),
            ])
        else:
            sound_id = 100 if mode == 1 else 101
            def _play_and_callback():
                self._sound.play(sound_id, interrupt=True, on_complete=on_complete)
            self._start([
                (0, lambda: self._cmd.projector(mode)),
                (0, _play_and_callback),
            ])

    def patrol(self, enable: bool) -> None:
        if enable:
            self._start([
                (0, lambda: self._cmd.projector(0)),
                (0, self._sound_job(7)),
                (0, self._mode(9)),
            ])
        else:
            self._start([(0, self._mode(0))])

    def go_to_sleep(self) -> None:
        """Called by SleepController when going to sleep (LED only, no movement)."""
        pass  # LED is handled by LEDController; no MCU action needed

    # ------------------------------------------------------------------
    # High-level mode dispatcher (mirrors Android EventHandler.mode())
    # ------------------------------------------------------------------

    def dispatch_mode(self, mode: int) -> None:
        """Dispatch a high-level mode number to the appropriate behavior."""
        status = self._cmd.status
        dispatch = {
            RobotMode.STOP:            self.mode_stop,
            RobotMode.VOICE_WAKE:      self.voice_wake_up,
            RobotMode.TURN_AROUND:     self.turn_around,
            RobotMode.TURN_LEFT:       self.turn_left,
            RobotMode.TURN_RIGHT:      self.turn_right,
            RobotMode.GO_FORWARD:      self.go_forward,
            RobotMode.LIGHTSABER:      lambda: self.lightsaber(0 if status.lightsaber else 1),
            RobotMode.WHO_ARE_YOU:     self.who_are_you,
            8:                         self.not_recognize,
            RobotMode.PATROL:          lambda: self.patrol(True),
            RobotMode.DANCE:           self.dance,
            RobotMode.WALK_CIRCLE:     self.walk_circle,
            RobotMode.FLASH_FRONT_LCD: self.flash_front_lcd,
            RobotMode.FLASH_BACK_LCD:  self.flash_back_lcd,
            RobotMode.SHAKE_HEAD:      self.shake_head,
            RobotMode.ARM:             lambda: self.arm(0 if status.arm else 1),
            RobotMode.SHORT_LCD:       lambda: self.short_lcd(status.lcd_short, status.lcd_long),
            RobotMode.LONG_LCD:        lambda: self.long_lcd(status.lcd_short, status.lcd_long),
            RobotMode.PROJECTOR_1:     lambda: self.projector_mode(0 if status.projector == 1 else 1),
            RobotMode.PROJECTOR_2:     lambda: self.projector_mode(0 if status.projector == 2 else 2),
            11:                        self.not_recognize,
        }
        action = dispatch.get(mode)
        if action:
            action()
        else:
            logger.warning("Unknown mode: %d", mode)
