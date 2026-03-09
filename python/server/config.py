"""Server configuration.

All settings can be overridden via environment variables.
"""

import os


class Config:
    # Serial port to the MCU
    SERIAL_DEVICE: str = os.getenv("R2D2_SERIAL", "/dev/ttyS2")
    SERIAL_BAUD: int = int(os.getenv("R2D2_BAUD", "115200"))

    # WebSocket server (phone-app compatible)
    WS_HOST: str = os.getenv("R2D2_WS_HOST", "0.0.0.0")
    WS_PORT: int = int(os.getenv("R2D2_WS_PORT", "8765"))

    # REST API (for Home Assistant and other integrations)
    API_HOST: str = os.getenv("R2D2_API_HOST", "0.0.0.0")
    API_PORT: int = int(os.getenv("R2D2_API_PORT", "8080"))

    # API security — set this in production
    API_KEY: str = os.getenv("R2D2_API_KEY", "")

    # Paired client storage
    PAIRED_CLIENTS_FILE: str = os.getenv("R2D2_CLIENTS", "paired_clients.json")

    # Camera / MJPEG streaming
    CAMERA_DEVICE: str  = os.getenv("R2D2_CAMERA", "/dev/video0")
    CAMERA_BACKEND: str = os.getenv("R2D2_CAMERA_BACKEND", "haar")   # haar | lbp | mediapipe
    CAMERA_ROTATION: int = int(os.getenv("R2D2_CAMERA_ROTATION", "270"))
    CAMERA_FPS: int     = int(os.getenv("R2D2_CAMERA_FPS", "10"))
    CAMERA_ENABLED: bool = os.getenv("R2D2_CAMERA_ENABLED", "true").lower() == "true"

    MJPEG_HOST: str = os.getenv("R2D2_MJPEG_HOST", "0.0.0.0")
    MJPEG_PORT: int = int(os.getenv("R2D2_MJPEG_PORT", "12121"))
    MJPEG_FPS:  int = int(os.getenv("R2D2_MJPEG_FPS", "10"))

    # Logging
    LOG_LEVEL: str = os.getenv("R2D2_LOG_LEVEL", "INFO")
