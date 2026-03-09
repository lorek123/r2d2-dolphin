"""MJPEG streaming HTTP server.

Serves camera frames from FaceDetector over plain asyncio HTTP.
Compatible with Home Assistant's generic camera integration.

HA configuration (configuration.yaml):

    camera:
      - platform: generic
        still_image_url: http://R2D2_IP:12121/snapshot
        stream_source: http://R2D2_IP:12121/stream
        name: R2D2 Camera

Port 12121 matches the original APK's StreamingServer port.
"""

import asyncio
import logging

from .config import Config

logger = logging.getLogger(__name__)

_BOUNDARY = b"frame"

_STREAM_HEADERS = (
    b"HTTP/1.1 200 OK\r\n"
    b"Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
    b"Cache-Control: no-cache\r\n"
    b"Connection: close\r\n"
    b"\r\n"
)
_NO_FRAME   = b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
_NOT_FOUND  = b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"


class MJPEGServer:
    """Asyncio HTTP server serving /stream and /snapshot endpoints.

    Args:
        detector: A :class:`~r2d2.camera.FaceDetector` instance.
        config:   Server configuration (MJPEG_HOST, MJPEG_PORT, MJPEG_FPS).
    """

    def __init__(self, detector, config: Config) -> None:
        self._detector = detector
        self._config = config

    async def handle(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            request_line = await asyncio.wait_for(reader.readline(), timeout=5.0)
        except asyncio.TimeoutError:
            writer.close()
            return

        path = ""
        parts = request_line.decode(errors="replace").split()
        if len(parts) >= 2:
            path = parts[1].split("?")[0]

        if path == "/stream":
            await self._serve_stream(writer)
        elif path == "/snapshot":
            await self._serve_snapshot(writer)
        else:
            writer.write(_NOT_FOUND)
            await writer.drain()

        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass

    async def _serve_stream(self, writer: asyncio.StreamWriter) -> None:
        writer.write(_STREAM_HEADERS)
        await writer.drain()
        interval = 1.0 / self._config.MJPEG_FPS
        try:
            while True:
                frame = self._detector.get_frame()
                if frame:
                    part = (
                        b"--frame\r\n"
                        b"Content-Type: image/jpeg\r\n"
                        b"Content-Length: " + str(len(frame)).encode() + b"\r\n"
                        b"\r\n" + frame + b"\r\n"
                    )
                    writer.write(part)
                    await writer.drain()
                await asyncio.sleep(interval)
        except (ConnectionResetError, BrokenPipeError):
            pass

    async def _serve_snapshot(self, writer: asyncio.StreamWriter) -> None:
        frame = self._detector.get_frame()
        if not frame:
            writer.write(_NO_FRAME)
            await writer.drain()
            return
        response = (
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Type: image/jpeg\r\n"
            b"Content-Length: " + str(len(frame)).encode() + b"\r\n"
            b"\r\n" + frame
        )
        writer.write(response)
        await writer.drain()

    async def serve(self) -> None:
        server = await asyncio.start_server(
            self.handle,
            self._config.MJPEG_HOST,
            self._config.MJPEG_PORT,
        )
        logger.info(
            "MJPEG server on http://%s:%d  (/stream  /snapshot)",
            self._config.MJPEG_HOST,
            self._config.MJPEG_PORT,
        )
        async with server:
            await server.serve_forever()
