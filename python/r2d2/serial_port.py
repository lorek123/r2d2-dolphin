"""Serial port communication with the R2D2 MCU.

Handles the physical UART connection to /dev/ttyS2 at 115200 baud.
Runs a background reader thread that parses newline-delimited JSON
messages coming from the MCU and dispatches them to registered callbacks.
"""

import json
import logging
import threading
from typing import Callable, Optional

import serial

logger = logging.getLogger(__name__)

MessageCallback = Callable[[dict], None]


class SerialPort:
    """Thread-safe UART wrapper for the R2D2 MCU serial protocol.

    Usage::

        def on_status(msg):
            print(msg)

        port = SerialPort("/dev/ttyS2")
        port.add_callback(on_status)
        port.open()
        port.send({"cmd": "ready"})
        ...
        port.close()
    """

    def __init__(
        self,
        device: str = "/dev/ttyS2",
        baud_rate: int = 115200,
    ) -> None:
        self._device = device
        self._baud_rate = baud_rate
        self._serial: Optional[serial.Serial] = None
        self._lock = threading.Lock()
        self._callbacks: list[MessageCallback] = []
        self._reader_thread: Optional[threading.Thread] = None
        self._running = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def open(self) -> None:
        """Open the serial port and start the background reader thread."""
        if self._serial and self._serial.is_open:
            return
        self._serial = serial.Serial(
            port=self._device,
            baudrate=self._baud_rate,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1.0,
        )
        logger.info("Opened serial port %s @ %d baud", self._device, self._baud_rate)
        self._running = True
        self._reader_thread = threading.Thread(
            target=self._reader_loop, name="serial-reader", daemon=True
        )
        self._reader_thread.start()

    def close(self) -> None:
        """Stop the reader thread and close the serial port."""
        self._running = False
        if self._reader_thread:
            self._reader_thread.join(timeout=2.0)
        if self._serial and self._serial.is_open:
            self._serial.close()
            logger.info("Closed serial port %s", self._device)

    @property
    def is_open(self) -> bool:
        return bool(self._serial and self._serial.is_open)

    # ------------------------------------------------------------------
    # Sending
    # ------------------------------------------------------------------

    def send(self, message: dict) -> bool:
        """Serialize *message* as JSON and write it to the serial port.

        Returns True on success, False on error.
        """
        if not self.is_open:
            logger.warning("send() called but port is not open")
            return False
        try:
            data = json.dumps(message, separators=(",", ":")) + "\n"
            with self._lock:
                self._serial.write(data.encode("utf-8"))
            logger.debug("TX: %s", data.rstrip())
            return True
        except serial.SerialException as exc:
            logger.error("Serial write error: %s", exc)
            return False

    # ------------------------------------------------------------------
    # Receiving
    # ------------------------------------------------------------------

    def add_callback(self, callback: MessageCallback) -> None:
        """Register a callable to be invoked for every MCU message received."""
        self._callbacks.append(callback)

    def remove_callback(self, callback: MessageCallback) -> None:
        self._callbacks.remove(callback)

    def _reader_loop(self) -> None:
        """Background thread: read lines from MCU, parse JSON, dispatch."""
        buffer = b""
        while self._running:
            try:
                chunk = self._serial.read(256)
                if not chunk:
                    continue
                buffer += chunk
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    self._dispatch(line.decode("utf-8", errors="replace").strip())
            except serial.SerialException as exc:
                if self._running:
                    logger.error("Serial read error: %s", exc)
                break
            except Exception as exc:
                logger.exception("Unexpected error in reader loop: %s", exc)

    def _dispatch(self, raw: str) -> None:
        if not raw:
            return
        logger.debug("RX: %s", raw)
        try:
            message = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("Invalid JSON from MCU: %r", raw)
            return
        for cb in self._callbacks:
            try:
                cb(message)
            except Exception:
                logger.exception("Error in serial callback")
