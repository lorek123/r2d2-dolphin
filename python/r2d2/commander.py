"""Command builders and sender for the R2D2 MCU serial protocol.

Each method builds the appropriate JSON payload and writes it to the
serial port. Methods that are blocked during charging return False
immediately without sending. All others return True on successful send.
"""

import logging
from typing import Optional

from .models import RobotStatus
from .serial_port import SerialPort

logger = logging.getLogger(__name__)


class Commander:
    """Translates high-level robot actions into serial JSON commands."""

    def __init__(self, serial_port: SerialPort, status: Optional[RobotStatus] = None) -> None:
        self._serial = serial_port
        # Shared status reference so Commander can check charging state.
        # The caller (R2D2 class) keeps this up-to-date from gin messages.
        self._status = status or RobotStatus()

    @property
    def status(self) -> RobotStatus:
        return self._status

    @status.setter
    def status(self, value: RobotStatus) -> None:
        self._status = value

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _send(self, payload: dict) -> bool:
        return self._serial.send(payload)

    def _blocked_when_charging(self) -> bool:
        if self._status.is_charging:
            logger.debug("Command blocked: robot is charging")
            return True
        return False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def software_ready(self) -> bool:
        """Signal MCU that the software stack is up."""
        return self._send({"cmd": "ready"})

    def reset_watchdog(self) -> bool:
        """Reset the MCU watchdog timer."""
        return self._send({"cmd": "reset-wdt"})

    def request_status(self) -> bool:
        """Ask the MCU to send a full gin status message."""
        return self._send({"cmd": "gin"})

    def power_off(self) -> bool:
        """Command the MCU to power off."""
        return self._send({"cmd": "shut-down"})

    def debug(self) -> bool:
        return self._send({"cmd": "debug"})

    # ------------------------------------------------------------------
    # Movement
    # ------------------------------------------------------------------

    def move(self, power: int, angle: int) -> bool:
        """Drive the robot.

        Args:
            power: Motor power (0 = stop).
            angle: Direction in degrees. 0 = forward, 180 = backward.
        """
        if self._blocked_when_charging():
            return False
        return self._send({"cmd": "move", "power": power, "angle": angle})

    def stop(self) -> bool:
        """Stop all movement."""
        return self.move(0, 0)

    # ------------------------------------------------------------------
    # Head
    # ------------------------------------------------------------------

    def move_head_angle(self, angle: int) -> bool:
        """Rotate head to an absolute angle."""
        if self._blocked_when_charging():
            return False
        return self._send({"cmd": "head-angle", "angle": angle})

    def head_shift(self, angle: int) -> bool:
        """Shift head by a relative angle (used by face tracking, ±5° typical)."""
        if self._blocked_when_charging():
            return False
        return self._send({"cmd": "head-shift", "angle": angle})

    def move_head_direction(self, direction: int) -> bool:
        """Move head in a continuous direction."""
        if self._blocked_when_charging():
            return False
        return self._send({"cmd": "head-dir", "dir": direction})

    # ------------------------------------------------------------------
    # MCU modes
    # ------------------------------------------------------------------

    def mode(self, mode: int) -> bool:
        """Send a mode command directly to the MCU.

        Args:
            mode: MCU mode integer (0=stop, 2=turn-around, 3=left,
                  4=right, 5=forward, 9=patrol, 12=walk-circle, etc.)
        """
        return self._send({"cmd": "mode", "mode": mode})

    # ------------------------------------------------------------------
    # Accessories
    # ------------------------------------------------------------------

    def projector(self, mode: int) -> bool:
        """Control the projector. 0=off, 1=projector1, 2=projector2."""
        return self._send({"cmd": "projector", "mode": mode})

    def arm(self, power: int) -> bool:
        """Extend (1) or retract (0) the arm."""
        return self._send({"cmd": "arm", "power": power})

    def lightsaber(self, power: int) -> bool:
        """Enable (1) or disable (0) the lightsaber."""
        return self._send({"cmd": "lightsaber", "power": power})

    # ------------------------------------------------------------------
    # Lights & display
    # ------------------------------------------------------------------

    def led(
        self,
        r: int = -1,
        b: int = -1,
        y: int = -1,
        g: int = -1,
    ) -> bool:
        """Set LED colors. Pass -1 to leave a channel unchanged.

        Args:
            r: Red LED value.
            b: Blue LED value.
            y: Yellow LED value.
            g: Green LED value.
        """
        payload: dict = {"cmd": "led"}
        if r != -1:
            payload["r"] = r
        if b != -1:
            payload["b"] = b
        if y != -1:
            payload["y"] = y
        if g != -1:
            payload["g"] = g
        return self._send(payload)

    def lcd(self, s: int = -1, l: int = -1) -> bool:
        """Control LCD panels.

        Args:
            s: Short LCD. 1=off, 2=on. -1 = no change.
            l: Long LCD. 1=off, 2=on. -1 = no change.
        """
        payload: dict = {"cmd": "lcd"}
        if s != -1:
            payload["s"] = s
        if l != -1:
            payload["l"] = l
        return self._send(payload)

    # ------------------------------------------------------------------
    # Motor power tuning
    # ------------------------------------------------------------------

    def set_head_power(self, power: int) -> bool:
        """Set head rotation motor power level."""
        return self._send({"cmd": "d-head-power", "power": power})

    def set_leg_power(self, power: int) -> bool:
        """Set drive motor power level."""
        return self._send({"cmd": "d-leg-power", "power": power})
