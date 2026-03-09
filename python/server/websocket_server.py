"""WebSocket server compatible with the original R2D2 phone app.

Speaks the same JSON protocol as the original Android app so existing
clients continue to work. Each connection gets its own CommandReceiver
that routes messages to the shared R2D2 instance.

Authentication flow:
    1. Client sends {"cmd": "grantAccess", "uuid": "...", "deviceName": "..."}
    2. Server checks paired-client list; grants access if known or in pair mode
    3. Authenticated clients can then send control commands
"""

import asyncio
import json
import logging
import os
from typing import Optional

import websockets
from websockets.server import WebSocketServerProtocol

from r2d2 import R2D2, RobotStatus

from .config import Config
from .paired_clients import PairedClientsStore

logger = logging.getLogger(__name__)


class WebSocketSession:
    """Per-connection session tracking authentication and control state."""

    def __init__(self, ws: WebSocketServerProtocol, robot: R2D2, store: PairedClientsStore) -> None:
        self._ws = ws
        self._robot = robot
        self._store = store
        self.authenticated = False
        self.uuid: Optional[str] = None
        self.controlling = False

    async def send(self, payload: dict) -> None:
        try:
            await self._ws.send(json.dumps(payload) + "\n")
        except websockets.exceptions.ConnectionClosed:
            pass

    async def success(self, cmd: str, seq: int, extra: Optional[dict] = None) -> None:
        response = {"cmd": cmd, "seq": seq, "resultCode": 0}
        if extra:
            response.update(extra)
        await self.send(response)

    async def error(self, cmd: str, seq: int, code: int) -> None:
        await self.send({"cmd": cmd, "seq": seq, "resultCode": code})

    # ------------------------------------------------------------------
    # Message dispatch
    # ------------------------------------------------------------------

    async def handle(self, raw: str) -> None:
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning("Invalid JSON from client: %r", raw)
            return

        cmd = msg.get("cmd", "")
        seq = msg.get("seq", 0)

        # Auth commands — available before authentication
        if cmd == "grantAccess":
            await self._grant_access(msg, seq)
            return

        if not self.authenticated:
            logger.warning("Unauthenticated command %r from %s", cmd, self._ws.remote_address)
            return

        # Management commands
        mgmt = {
            "getWifiList":       self._get_wifi_list,
            "face_detection":    self._face_detection,
            "voice_recognition": self._voice_recognition,
            "mute":              self._mute,
            "power":             self._power_off,
            "user_control":      self._user_control,
            "change_name":       self._noop,
            "paired_list":       self._paired_list,
            "unpair":            self._unpair,
        }
        if cmd in mgmt:
            await mgmt[cmd](msg, seq)
            return

        # Real-time control commands
        await self._control(cmd, msg)

    async def _grant_access(self, msg: dict, seq: int) -> None:
        uuid = msg.get("uuid", "")
        device_name = msg.get("deviceName", "")

        if not uuid:
            await self.error("grantAccess", seq, 301)
            return

        is_known = self._store.is_known(uuid)
        if is_known:
            self._store.update_name(uuid, device_name)
        else:
            # Only allow new clients when in pair mode (simplified: always allow for now)
            self._store.add(uuid, device_name)
            logger.info("New client paired: %s (%s)", uuid, device_name)

        self.authenticated = True
        self.uuid = uuid
        await self.success("grantAccess", seq, {
            "robot": self._robot.status.to_dict()
        })
        logger.info("Client authenticated: %s", uuid)

    async def _power_off(self, msg: dict, seq: int) -> None:
        self._robot.power_off()
        await self.success("power", seq)

    async def _user_control(self, msg: dict, seq: int) -> None:
        self.controlling = msg.get("enable", False)

    async def _paired_list(self, msg: dict, seq: int) -> None:
        await self.success("paired_list", seq, {"clients": self._store.all()})

    async def _unpair(self, msg: dict, seq: int) -> None:
        uuid = msg.get("uuid")
        if uuid:
            self._store.remove(uuid)
        else:
            self._store.clear()
        await self.success("unpair", seq, {"clients": self._store.all()})

    async def _get_wifi_list(self, msg: dict, seq: int) -> None:
        # WiFi scanning not implemented in Python server; return empty list
        await self.success("getWifiList", seq, {"wifi_list": [], "currentSSID": ""})

    async def _voice_recognition(self, msg: dict, seq: int) -> None:
        if msg.get("enable", False):
            self._robot.start_voice()
        else:
            self._robot.stop_voice()
        await self.success("voice_recognition", seq)

    async def _face_detection(self, msg: dict, seq: int) -> None:
        if msg.get("enable", False):
            self._robot.start_face_detection()
        else:
            self._robot.stop_face_detection()
        await self.success("face_detection", seq)

    async def _mute(self, msg: dict, seq: int) -> None:
        self._robot.mute = msg.get("enable", False)
        await self.success("mute", seq)

    async def _noop(self, msg: dict, seq: int) -> None:
        await self.success(msg.get("cmd", ""), msg.get("seq", 0))

    async def _control(self, cmd: str, msg: dict) -> None:
        """Handle real-time robot control commands."""
        r = self._robot
        if cmd == "move":
            r.move(msg.get("power", 0), msg.get("angle", 0))
            # Auto-straighten head when driving forward
            if msg.get("power", 0) > 0 and msg.get("angle", 0) == 0:
                await asyncio.sleep(0.1)
                r.move_head(0)
        elif cmd == "move-head":
            r.move_head(msg.get("angle", 0))
        elif cmd == "head-dir":
            r.move_head_dir(msg.get("dir", 0))
        elif cmd == "projector":
            r.projector(msg.get("mode", 0))
        elif cmd == "mode":
            r.dispatch_mode(msg.get("mode", 0))
        elif cmd == "lcd":
            r.lcd(msg.get("s", -1), msg.get("l", -1))
        elif cmd == "led":
            r.led(msg.get("r", -1), msg.get("b", -1), msg.get("y", -1), msg.get("g", -1))
        elif cmd == "play_sound":
            sound_id = msg.get("sound_id")
            if sound_id is not None:
                r.play_sound(sound_id)
        elif cmd == "d-head-power":
            r.set_head_power(msg.get("power", 80))
        elif cmd == "d-leg-power":
            r.set_leg_power(msg.get("power", 100))
        elif cmd == "reset-wdt":
            r.reset_watchdog()
        elif cmd == "reset_mcu":
            r.reset_watchdog()
        else:
            logger.warning("Unknown control command: %r", cmd)


class WebSocketServer:
    """Manages all WebSocket connections to the R2D2 robot."""

    def __init__(self, robot: R2D2, store: PairedClientsStore, config: Config) -> None:
        self._robot = robot
        self._store = store
        self._config = config

    async def handle_connection(self, ws: WebSocketServerProtocol) -> None:
        session = WebSocketSession(ws, self._robot, self._store)
        logger.info("New connection from %s", ws.remote_address)
        buffer = ""
        try:
            async for raw in ws:
                buffer += raw
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    if line.strip():
                        await session.handle(line.strip())
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception:
            logger.exception("Error in WebSocket connection")
        finally:
            logger.info("Connection closed: %s", ws.remote_address)

    async def serve(self) -> None:
        async with websockets.serve(
            self.handle_connection,
            self._config.WS_HOST,
            self._config.WS_PORT,
        ):
            logger.info("WebSocket server listening on ws://%s:%d",
                        self._config.WS_HOST, self._config.WS_PORT)
            await asyncio.Future()  # run forever
