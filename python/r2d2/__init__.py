"""r2d2 — Python library for controlling the R2D2 NanoPi robot.

Quick start::

    from r2d2 import R2D2

    with R2D2("/dev/ttyS2") as robot:
        robot.move(50, 0)   # forward
        time.sleep(2)
        robot.stop()
"""

from .camera import FaceDetector
from .led_controller import LEDController
from .models import AppMode, ChargingStatus, RobotMode, RobotStatus
from .r2d2 import R2D2
from .sleep import SleepController
from .sound import SoundPlayer
from .voice import JuliusRecognizer, VoiceCommand, bind_to_robot

__all__ = [
    "R2D2",
    "RobotMode", "AppMode", "ChargingStatus", "RobotStatus",
    "JuliusRecognizer", "VoiceCommand", "bind_to_robot",
    "SoundPlayer", "SleepController", "LEDController",
    "FaceDetector",
]
