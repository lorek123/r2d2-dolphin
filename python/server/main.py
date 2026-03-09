"""Entry point — starts the REST API and WebSocket servers concurrently.

Usage::

    python -m server.main

Or with environment overrides::

    R2D2_SERIAL=/dev/ttyS2 R2D2_API_PORT=8080 R2D2_WS_PORT=8765 python -m server.main
"""

import asyncio
import logging
import signal
import sys

import uvicorn

from r2d2 import R2D2
from r2d2.camera import FaceDetector

from .api import create_app
from .config import Config
from .mjpeg import MJPEGServer
from .paired_clients import PairedClientsStore
from .websocket_server import WebSocketServer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


async def main() -> None:
    config = Config()
    logging.getLogger().setLevel(config.LOG_LEVEL)

    # Shared robot instance
    robot = R2D2(config.SERIAL_DEVICE, config.SERIAL_BAUD)
    robot.open()
    logger.info("R2D2 connected on %s", config.SERIAL_DEVICE)

    # Paired clients persistent store
    store = PairedClientsStore(config.PAIRED_CLIENTS_FILE)

    # Face detector + MJPEG stream
    detector = FaceDetector(
        device=config.CAMERA_DEVICE,
        rotation=config.CAMERA_ROTATION,
        backend=config.CAMERA_BACKEND,
        fps=config.CAMERA_FPS,
    )
    robot.set_face_detector(detector)
    if config.CAMERA_ENABLED:
        robot.start_face_detection()
        logger.info("Face detection enabled (device=%s backend=%s)",
                    config.CAMERA_DEVICE, config.CAMERA_BACKEND)

    mjpeg_server = MJPEGServer(detector, config)

    # REST API
    fastapi_app = create_app(robot, config)
    api_config = uvicorn.Config(
        fastapi_app,
        host=config.API_HOST,
        port=config.API_PORT,
        log_level="warning",
    )
    api_server = uvicorn.Server(api_config)

    # WebSocket server
    ws_server = WebSocketServer(robot, store, config)

    # Graceful shutdown on SIGTERM/SIGINT
    loop = asyncio.get_running_loop()

    def _shutdown(*_):
        logger.info("Shutting down…")
        robot.stop()
        robot.close()
        loop.stop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, _shutdown)

    logger.info("Starting REST API on http://%s:%d", config.API_HOST, config.API_PORT)
    logger.info("Starting WebSocket on ws://%s:%d", config.WS_HOST, config.WS_PORT)

    await asyncio.gather(
        api_server.serve(),
        ws_server.serve(),
        mjpeg_server.serve(),
    )


if __name__ == "__main__":
    asyncio.run(main())
