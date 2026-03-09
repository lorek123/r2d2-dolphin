"""High-level R2D2 interface.

This is the main entry point for controlling the robot. It wires together
the serial port, commander, event handler, sound player, sleep controller,
and LED controller.

Usage::

    robot = R2D2()
    robot.open()
    robot.move(50, 0)       # forward at 50% power
    robot.shake_head()
    robot.close()

Or as a context manager::

    with R2D2() as robot:
        robot.move(50, 0)
        time.sleep(2)
        robot.stop()
"""

import logging
import threading
from typing import Callable, Optional

from .camera import FaceDetector
from .commander import Commander
from .event_handler import EventHandler
from .led_controller import LEDController
from .models import AppMode, ChargingStatus, RobotStatus
from .serial_port import SerialPort
from .sleep import SleepController
from .sound import SoundPlayer
from .voice import JuliusRecognizer, VoiceCommand, bind_to_robot

logger = logging.getLogger(__name__)

StatusCallback = Callable[[RobotStatus], None]


class R2D2:
    """Top-level robot controller.

    Args:
        serial_device: Path to the serial port device.
        baud_rate: Serial baud rate (default 115200).
    """

    def __init__(
        self,
        serial_device: str = "/dev/ttyS2",
        baud_rate: int = 115200,
    ) -> None:
        self._serial = SerialPort(serial_device, baud_rate)
        self._status = RobotStatus()
        self._app_mode = AppMode.READY
        self._commander = Commander(self._serial, self._status)

        self._sound = SoundPlayer()
        self._events = EventHandler(self._commander, self._sound)

        self._sleep = SleepController(
            on_sleep=self._on_sleep,
            on_wake=self._on_wake,
        )
        self._led = LEDController(
            send_led=self._commander.led,
            get_status=lambda: self._status,
            get_app_mode=lambda: self._app_mode,
            get_voice_active=lambda: self._voice is not None,
        )

        self._events.set_wake_callback(self._sleep.wake)

        self._status_callbacks: list[StatusCallback] = []
        self._voice: Optional[JuliusRecognizer] = None
        self._voice_enabled: bool = False
        self._face: Optional[FaceDetector] = None

        self._serial.add_callback(self._on_serial_message)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def open(self) -> None:
        """Open the serial port and announce readiness to the MCU."""
        self._serial.open()
        self._commander.software_ready()
        self._led.restore()
        logger.info("R2D2 controller ready")

    def close(self) -> None:
        """Stop all activity and close the serial port."""
        self.stop_face_detection()
        self.stop_voice()
        self._sleep.stop()
        self._events.stop()
        self._serial.close()
        logger.info("R2D2 controller closed")

    def __enter__(self) -> "R2D2":
        self.open()
        return self

    def __exit__(self, *_) -> None:
        self.close()

    # ------------------------------------------------------------------
    # Activity / sleep
    # ------------------------------------------------------------------

    def wake(self) -> None:
        """Signal any activity — resets the inactivity sleep timer."""
        self._sleep.wake()

    def _on_sleep(self) -> None:
        self._app_mode = AppMode.SLEEP
        self._events.go_to_sleep()
        if self._voice_enabled:
            self._stop_voice_internal()
        if self._face and self._face.is_running:
            self._face.stop()
        self._led.restore()
        logger.info("Robot sleeping")

    def _on_wake(self) -> None:
        self._app_mode = AppMode.READY
        self._led.restore()
        if self._voice_enabled:
            self._start_voice_internal()
        if self._face and not self._face.is_running:
            self._face.start()
        logger.info("Robot awake")

    # ------------------------------------------------------------------
    # Status
    # ------------------------------------------------------------------

    @property
    def status(self) -> RobotStatus:
        """Last known robot status received from the MCU."""
        return self._status

    @property
    def app_mode(self) -> AppMode:
        return self._app_mode

    def add_status_callback(self, callback: StatusCallback) -> None:
        """Register a function to be called whenever the robot status updates."""
        self._status_callbacks.append(callback)

    def remove_status_callback(self, callback: StatusCallback) -> None:
        self._status_callbacks.remove(callback)

    def request_status(self) -> None:
        """Ask the MCU to send a full status update immediately."""
        self._commander.request_status()

    # ------------------------------------------------------------------
    # Movement (direct control)
    # ------------------------------------------------------------------

    def move(self, power: int, angle: int) -> None:
        """Drive the robot.

        Args:
            power: Motor power (0 = stop).
            angle: Direction in degrees. 0 = forward, 180 = backward.
        """
        self.wake()
        self._events.move(power, angle)

    def stop(self) -> None:
        """Stop all movement and cancel any running sequence."""
        self._events.stop()

    def move_head(self, angle: int) -> None:
        """Rotate head to an absolute angle in degrees."""
        self.wake()
        self._events.move_head(angle)

    def shift_head(self, angle: int) -> None:
        """Shift head by a relative angle."""
        self.wake()
        self._events.shift_head(angle)

    def move_head_dir(self, direction: int) -> None:
        """Move head in a continuous direction."""
        self.wake()
        self._events.move_head_dir(direction)

    # ------------------------------------------------------------------
    # Named behaviors
    # ------------------------------------------------------------------

    def shake_head(self) -> None:
        self.wake()
        self._events.shake_head()

    def dance(self) -> None:
        self.wake()
        self._events.dance()

    def who_are_you(self) -> None:
        self.wake()
        self._events.who_are_you()

    def turn_around(self) -> None:
        self.wake()
        self._events.turn_around()

    def turn_left(self) -> None:
        self.wake()
        self._events.turn_left()

    def turn_right(self) -> None:
        self.wake()
        self._events.turn_right()

    def go_forward(self) -> None:
        self.wake()
        self._events.go_forward()

    def walk_circle(self) -> None:
        self.wake()
        self._events.walk_circle()

    def make_some_noise(self) -> None:
        self.wake()
        self._events.make_some_noise()

    def patrol(self, enable: bool = True) -> None:
        """Start or stop autonomous patrol mode."""
        self.wake()
        if enable:
            self._app_mode = AppMode.PATROL
            self._sleep.stop_timer()
        else:
            self._app_mode = AppMode.READY
            self._sleep.restart_timer()
        self._events.patrol(enable)
        self._led.restore()

    def dispatch_mode(self, mode: int) -> None:
        """Dispatch a high-level mode integer (see RobotMode enum)."""
        self.wake()
        self._events.dispatch_mode(mode)

    # ------------------------------------------------------------------
    # Accessories
    # ------------------------------------------------------------------

    def lightsaber(self, on: bool) -> None:
        self.wake()
        self._events.lightsaber(1 if on else 0)

    def arm(self, extended: bool) -> None:
        self.wake()
        self._events.arm(1 if extended else 0)

    def projector(self, mode: int) -> None:
        """Set projector mode. 0=off, 1=projector1, 2=projector2."""
        self.wake()
        if mode in (1, 2):
            self._stop_voice_internal()
            def _on_projector_done():
                if self._voice_enabled:
                    self._start_voice_internal()
            self._events.projector_mode(mode, on_complete=_on_projector_done)
        else:
            self._events.projector_mode(0)

    # ------------------------------------------------------------------
    # Lights & display
    # ------------------------------------------------------------------

    def led(self, r: int = -1, b: int = -1, y: int = -1, g: int = -1) -> None:
        """Set LED channels directly. -1 = leave unchanged."""
        self._events.led(r, b, y, g)

    def lcd(self, short: int = -1, long: int = -1) -> None:
        """Control LCD panels. 1=off, 2=on, -1=no change."""
        self._events.lcd(short, long)

    # ------------------------------------------------------------------
    # Motor tuning
    # ------------------------------------------------------------------

    def set_head_power(self, power: int) -> None:
        self._commander.set_head_power(power)

    def set_leg_power(self, power: int) -> None:
        self._commander.set_leg_power(power)

    # ------------------------------------------------------------------
    # System
    # ------------------------------------------------------------------

    def power_off(self) -> None:
        self._led.set_power_off()
        self._commander.power_off()

    def reset_watchdog(self) -> None:
        self._commander.reset_watchdog()

    def debug(self) -> None:
        self._commander.debug()

    # ------------------------------------------------------------------
    # Voice recognition
    # ------------------------------------------------------------------

    def start_voice(self, wake_timeout: float = JuliusRecognizer.WAKE_TIMEOUT) -> None:
        """Start Julius voice recognition wired to this robot instance.

        Requires ``julius`` to be installed on the system.
        """
        self._voice_enabled = True
        if not self._sleep.is_sleeping:
            self._start_voice_internal(wake_timeout)

    def stop_voice(self) -> None:
        """Stop voice recognition if running."""
        self._voice_enabled = False
        self._stop_voice_internal()

    def _start_voice_internal(self, wake_timeout: float = JuliusRecognizer.WAKE_TIMEOUT) -> None:
        if self._voice is not None:
            return
        self._voice = JuliusRecognizer(lambda _: None, wake_timeout=wake_timeout)
        bind_to_robot(self._voice, self)
        self._voice.start()
        self._led.restore()

    def _stop_voice_internal(self) -> None:
        if self._voice is not None:
            self._voice.stop()
            self._voice = None
            self._led.restore()

    # ------------------------------------------------------------------
    # Face detection
    # ------------------------------------------------------------------

    def set_face_detector(self, detector: FaceDetector) -> None:
        """Attach an external FaceDetector instance (created by the server layer).

        The detector is wired to this robot for head tracking, LED, and sound.
        Call before :meth:`start_face_detection`.
        """
        self._face = detector
        detector.set_robot(self)

    def start_face_detection(self) -> None:
        """Start face detection if a detector has been attached."""
        if self._face is None:
            logger.warning("No FaceDetector attached — call set_face_detector() first")
            return
        if not self._sleep.is_sleeping:
            self._face.start()

    def stop_face_detection(self) -> None:
        """Stop face detection if running."""
        if self._face and self._face.is_running:
            self._face.stop()

    @property
    def face_detected(self) -> bool:
        return self._face is not None and self._face.face_detected

    @property
    def face_count(self) -> int:
        return self._face.face_count if self._face else 0

    # ------------------------------------------------------------------
    # Sound
    # ------------------------------------------------------------------

    def play_sound(self, sound_id: int, interrupt: bool = True) -> None:
        """Play a sound by ID (see SoundPlayer constants)."""
        self._sound.play(sound_id, interrupt=interrupt)

    @property
    def mute(self) -> bool:
        return self._sound.mute

    @mute.setter
    def mute(self, value: bool) -> None:
        self._sound.mute = value

    # ------------------------------------------------------------------
    # MCU message handling
    # ------------------------------------------------------------------

    def _on_serial_message(self, message: dict) -> None:
        cmd = message.get("cmd")
        if cmd == "gin":
            self._handle_gin(message)
        elif cmd == "ready":
            logger.info("MCU is ready")
        elif cmd == "play_sound":
            sound_id = message.get("sound_id")
            if sound_id is not None:
                self._sound.play(sound_id, interrupt=False)
        elif cmd == "btn":
            self._handle_button(message.get("value", 0))
        else:
            logger.debug("Unknown MCU message: %s", message)

    def _handle_gin(self, data: dict) -> None:
        prev_charging = self._status.charging_status
        self._status = RobotStatus.from_gin(data)
        self._commander.status = self._status

        # Update LED on charging state change
        if self._status.charging_status != prev_charging:
            if self._status.charging_status == ChargingStatus.CHARGING:
                self._led.on_charging_start()
            elif self._status.charging_status == ChargingStatus.FULLY_CHARGED:
                self._led.on_charged()
            else:
                self._led.on_charging_stop()

        logger.debug("Status update: battery=%d%%, charging=%s",
                     self._status.battery, self._status.charging_status)
        for cb in self._status_callbacks:
            try:
                cb(self._status)
            except Exception:
                logger.exception("Error in status callback")

    def _handle_button(self, value: int) -> None:
        """Handle physical button presses reported by the MCU."""
        logger.info("Button pressed: %d", value)
        self.wake()
        if value == 1:
            self.power_off()
        elif value == 3:
            # Toggle pair mode — handled by server layer
            pass
        elif value == 4:
            self.lightsaber(not self._status.lightsaber)
        elif value == 5:
            self.arm(not self._status.arm)
        elif value == 6:
            self.patrol(True)
