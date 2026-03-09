"""Data models and enumerations for the R2D2 protocol."""

from dataclasses import dataclass, field
from enum import IntEnum
from typing import Optional


class RobotMode(IntEnum):
    STOP = 0
    VOICE_WAKE = 1
    TURN_AROUND = 2
    TURN_LEFT = 3
    TURN_RIGHT = 4
    GO_FORWARD = 5
    LIGHTSABER = 6
    WHO_ARE_YOU = 7
    PATROL = 9
    DANCE = 10
    WALK_CIRCLE = 12
    FLASH_FRONT_LCD = 13
    FLASH_BACK_LCD = 14
    SHAKE_HEAD = 15
    ARM = 16
    SHORT_LCD = 17
    LONG_LCD = 18
    PROJECTOR_1 = 19
    PROJECTOR_2 = 20


class ChargingStatus(IntEnum):
    NOT_CHARGING = 0
    CHARGING = 1
    FULLY_CHARGED = 2


class AppMode(IntEnum):
    """High-level application state (mirrors Android ModeController)."""
    READY = 1
    SLEEP = 2
    PAIR = 3
    PATROL = 4
    USER_CONTROL = 5


@dataclass
class RobotStatus:
    """Robot status as reported by the MCU via 'gin' message."""
    battery: int = 0
    charging_status: ChargingStatus = ChargingStatus.NOT_CHARGING
    lightsaber: bool = False
    arm: bool = False
    projector: int = 0        # 0=off, 1=projector1, 2=projector2
    mode: int = 0
    head: int = 0
    lcd_short: bool = False   # lcd_s >= 2
    lcd_long: bool = False    # lcd_l >= 2
    status: int = 0
    error: str = ""

    @classmethod
    def from_gin(cls, data: dict) -> "RobotStatus":
        return cls(
            battery=data.get("batt", 0),
            charging_status=ChargingStatus(data.get("charging-status", 0)),
            lightsaber=data.get("lightsaber", 0) == 1,
            arm=data.get("arm", 0) == 1,
            projector=data.get("projector", 0),
            mode=data.get("mode", 0),
            head=data.get("head", 0),
            lcd_short=data.get("lcd_s", 1) >= 2,
            lcd_long=data.get("lcd_l", 1) >= 2,
            status=data.get("status", 0),
            error=data.get("error", ""),
        )

    @property
    def is_charging(self) -> bool:
        return self.charging_status != ChargingStatus.NOT_CHARGING

    def to_dict(self) -> dict:
        return {
            "battery": self.battery,
            "charging_status": int(self.charging_status),
            "lightsaber": self.lightsaber,
            "arm": self.arm,
            "projector": self.projector,
            "mode": self.mode,
            "head": self.head,
            "lcd_short": self.lcd_short,
            "lcd_long": self.lcd_long,
            "status": self.status,
            "error": self.error,
        }
