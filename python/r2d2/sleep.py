"""Sleep controller — inactivity timer that puts the robot to sleep.

Mirrors Android SleepController:
- A 3-minute inactivity timer fires ``on_sleep`` when it expires.
- Any call to ``wake()`` resets the timer; if the robot was asleep it also
  fires ``on_wake``.
- ``stop_timer()`` suspends the timer (used during patrol/pair modes).
- ``restart_timer()`` resumes it.
"""

import logging
import threading
from typing import Callable

logger = logging.getLogger(__name__)

OnSleepCallback = Callable[[], None]
OnWakeCallback = Callable[[], None]


class SleepController:
    """Inactivity-based sleep state machine.

    Args:
        on_sleep:     Called when the robot transitions to sleep.
        on_wake:      Called when the robot wakes from sleep.
        sleep_timeout: Inactivity timeout in seconds (default 180 = 3 min).
    """

    SLEEP_TIMEOUT = 180.0

    def __init__(
        self,
        on_sleep: OnSleepCallback,
        on_wake: OnWakeCallback,
        sleep_timeout: float = SLEEP_TIMEOUT,
    ) -> None:
        self._on_sleep = on_sleep
        self._on_wake = on_wake
        self._timeout = sleep_timeout
        self._is_sleeping = False
        self._timer: threading.Timer | None = None
        self._lock = threading.Lock()
        self.restart_timer()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def is_sleeping(self) -> bool:
        return self._is_sleeping

    def wake(self) -> None:
        """Signal activity — resets the sleep timer.

        If the robot was sleeping, fires on_wake and restarts the timer.
        """
        with self._lock:
            was_sleeping = self._is_sleeping
            self._is_sleeping = False
        self.restart_timer()
        if was_sleeping:
            logger.info("Robot woke up")
            try:
                self._on_wake()
            except Exception:
                logger.exception("Error in on_wake callback")

    def stop_timer(self) -> None:
        """Suspend the sleep timer (e.g. during patrol or pair mode)."""
        with self._lock:
            if self._timer:
                self._timer.cancel()
                self._timer = None

    def restart_timer(self) -> None:
        """Reset the inactivity countdown."""
        with self._lock:
            if self._timer:
                self._timer.cancel()
            self._timer = threading.Timer(self._timeout, self._do_sleep)
            self._timer.daemon = True
            self._timer.start()

    def stop(self) -> None:
        """Permanently stop the controller (on robot shutdown)."""
        self.stop_timer()

    # ------------------------------------------------------------------

    def _do_sleep(self) -> None:
        with self._lock:
            if self._is_sleeping:
                return
            self._is_sleeping = True
        logger.info("Robot going to sleep (inactivity timeout)")
        try:
            self._on_sleep()
        except Exception:
            logger.exception("Error in on_sleep callback")
