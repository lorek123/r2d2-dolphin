"""LED state machine — automatically sets LEDs based on robot state.

Mirrors Android LEDLightController. LED values are MCU-side mode codes
(small integers), NOT raw RGB values.

Front LED (r, b channels) — reflects app mode / charging state:
    READY (0):          r=2, b=2
    PAIR (1):           r=1, b=3
    POWER_OFF (2):      r=5, b=1
    BATTERY_LOW (3):    r=2, b=1
    SLEEP (4):          r=1, b=1
    CHARGING (5):       r=3, b=3
    CHARGED (6):        r=1, b=2
    PATROL (102):       r=3, b=4
    PAIR_FAIL (103):    r=3, b=1
    FACE_DETECT (105):  r=1, b=2

Back LED (y, g channels) — reflects network / voice state:
    WITH_WIFI (20):     g=2, y=1
    WITHOUT_WIFI (21):  g=5, y=1
    POWER_OFF (22):     g=1, y=1
    SLEEP_LAN (26):     g=3, y=1
    VOICE_ACTIVE (202): g=3, y=4
"""

import logging
import threading
from typing import Callable

from .models import AppMode, ChargingStatus, RobotStatus

logger = logging.getLogger(__name__)

LOW_BATTERY_THRESHOLD = 15  # percent

# ---- Front LED mode constants ----
_F_READY      = 0
_F_PAIR       = 1
_F_POWER_OFF  = 2
_F_BATT_LOW   = 3
_F_SLEEP      = 4
_F_CHARGING   = 5
_F_CHARGED    = 6
_F_PATROL     = 102
_F_PAIR_FAIL  = 103
_F_FACE       = 105
_F_UNCHANGED  = -1

# ---- Back LED mode constants ----
_B_WITH_NET   = 20
_B_NO_NET     = 21
_B_POWER_OFF  = 22
_B_SLEEP      = 26
_B_VOICE      = 202
_B_UNCHANGED  = -1

# ---- Value tables ----
_FRONT_VALUES: dict[int, tuple[int, int]] = {
    _F_READY:     (2, 2),
    _F_PAIR:      (1, 3),
    _F_POWER_OFF: (5, 1),
    _F_BATT_LOW:  (2, 1),
    _F_SLEEP:     (1, 1),
    _F_CHARGING:  (3, 3),
    _F_CHARGED:   (1, 2),
    _F_PATROL:    (3, 4),
    _F_PAIR_FAIL: (3, 1),
    _F_FACE:      (1, 2),
}

_BACK_VALUES: dict[int, tuple[int, int]] = {
    _B_WITH_NET:  (2, 1),
    _B_NO_NET:    (5, 1),
    _B_POWER_OFF: (1, 1),
    _B_SLEEP:     (3, 1),
    _B_VOICE:     (3, 4),
}


class LEDController:
    """Automatic LED state machine.

    Args:
        send_led:      Function that sends ``led(r, b, y, g)`` to the MCU.
        get_status:    Callable returning the current :class:`RobotStatus`.
        get_app_mode:  Callable returning the current :class:`AppMode`.
        get_voice_active: Callable returning bool — True if voice recognition running.
    """

    def __init__(
        self,
        send_led: Callable[[int, int, int, int], None],
        get_status: Callable[[], RobotStatus],
        get_app_mode: Callable[[], AppMode],
        get_voice_active: Callable[[], bool],
    ) -> None:
        self._send_led = send_led
        self._get_status = get_status
        self._get_app_mode = get_app_mode
        self._get_voice_active = get_voice_active
        self._front_mode = _F_UNCHANGED
        self._back_mode = _B_UNCHANGED
        self._power_off = False
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def restore(self) -> None:
        """Recalculate and apply both front and back LEDs from current state."""
        self._change(self._front_base(), self._back_base())

    def restore_front(self) -> None:
        self._change(self._front_base(), _B_UNCHANGED)

    def restore_back(self) -> None:
        self._change(_F_UNCHANGED, self._back_base())

    def set_pair_fail(self) -> None:
        """Flash pair-fail LED for 2 seconds then restore."""
        self._change(_F_PAIR_FAIL, _B_UNCHANGED)
        t = threading.Timer(2.0, self.restore_front)
        t.daemon = True
        t.start()

    def set_power_off(self) -> None:
        self._power_off = True
        self._change(_F_POWER_OFF, _B_POWER_OFF)

    def on_charging_start(self) -> None:
        self._change(_F_CHARGING, _B_UNCHANGED)

    def on_charged(self) -> None:
        self._change(_F_CHARGED, _B_UNCHANGED)

    def on_charging_stop(self) -> None:
        self.restore_front()

    def on_face_detect_start(self) -> None:
        self._change(_F_FACE, _B_UNCHANGED)

    def on_face_detect_stop(self) -> None:
        self.restore_front()

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _front_base(self) -> int:
        app_mode = self._get_app_mode()
        status = self._get_status()

        if app_mode == AppMode.PATROL:
            return _F_PATROL
        if app_mode == AppMode.PAIR:
            return _F_PAIR
        if status.charging_status == ChargingStatus.FULLY_CHARGED:
            return _F_CHARGED
        if status.charging_status == ChargingStatus.CHARGING:
            return _F_CHARGING
        if status.battery <= LOW_BATTERY_THRESHOLD:
            return _F_BATT_LOW
        if app_mode == AppMode.SLEEP:
            return _F_SLEEP
        return _F_READY

    def _back_base(self) -> int:
        app_mode = self._get_app_mode()
        if app_mode == AppMode.SLEEP:
            return _B_SLEEP
        if self._get_voice_active():
            return _B_VOICE
        # Python server always has network access (it's running on Linux)
        return _B_WITH_NET

    def _change(self, front: int, back: int) -> None:
        with self._lock:
            if self._power_off and front != _F_POWER_OFF:
                return  # locked in power-off state

            # Skip unchanged channels
            if front == self._front_mode:
                front = _F_UNCHANGED
            if back == self._back_mode:
                back = _B_UNCHANGED
            if front == _F_UNCHANGED and back == _B_UNCHANGED:
                return

            if front != _F_UNCHANGED:
                self._front_mode = front
            if back != _B_UNCHANGED:
                self._back_mode = back

        r_val, b_val = _FRONT_VALUES.get(
            self._front_mode, (0, 0)
        ) if front != _F_UNCHANGED else (-1, -1)

        g_val, y_val = _BACK_VALUES.get(
            self._back_mode, (0, 0)
        ) if back != _B_UNCHANGED else (-1, -1)

        logger.debug(
            "LED: front=%d(r=%d,b=%d) back=%d(g=%d,y=%d)",
            self._front_mode, r_val, b_val,
            self._back_mode, g_val, y_val,
        )
        try:
            self._send_led(r_val, b_val, y_val, g_val)
        except Exception:
            logger.exception("Error sending LED command")
