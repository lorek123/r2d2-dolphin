"""
Improv WiFi BLE GATT server
https://www.improv-wifi.com/ble/

Advertises the Improv service over Bluetooth LE.
When the user visits improv-wifi.com in Chrome and connects, they can
send WiFi credentials which are forwarded to the connect_callback.

Requires: bless (pip), bluez (apt)
"""

from __future__ import annotations

import asyncio
import logging
from typing import Callable, Optional

try:
    from bless import (
        BlessServer,
        GATTCharacteristicProperties as Props,
        GATTAttributePermissions as Perms,
    )
    HAS_BLESS = True
except ImportError:
    HAS_BLESS = False

log = logging.getLogger("improv")

# ---------------------------------------------------------------------------
# Improv WiFi BLE UUIDs (fixed by spec)
# ---------------------------------------------------------------------------
SVC         = "00467768-6228-2272-4663-277478268000"
CHR_STATE   = "00467768-6228-2272-4663-277478268001"  # read + notify
CHR_ERROR   = "00467768-6228-2272-4663-277478268002"  # read + notify
CHR_CMD     = "00467768-6228-2272-4663-277478268003"  # write
CHR_RESULT  = "00467768-6228-2272-4663-277478268004"  # read + notify
CHR_CAPS    = "00467768-6228-2272-4663-277478268005"  # read

# States
ST_AUTH         = 0x02   # Authorized (we skip the authorization step)
ST_PROVISIONING = 0x03
ST_PROVISIONED  = 0x04

# Errors
ERR_NONE         = 0x00
ERR_INVALID_RPC  = 0x01
ERR_UNKNOWN_CMD  = 0x02
ERR_CANT_CONNECT = 0x03

# RPC commands
CMD_WIFI     = 0x01
CMD_IDENTIFY = 0x02
CMD_INFO     = 0x03

ConnectCallback = Callable[[str, str], None]   # (ssid, password)


def _checksum(data: bytes | bytearray) -> int:
    return sum(data) & 0xFF


def _rpc_result(cmd: int, success: bool, url: str = "") -> bytearray:
    url_b = url.encode()
    body = bytes([cmd, 0x01 if success else 0x00, len(url_b)]) + url_b
    return bytearray(body + bytes([_checksum(body)]))


class ImprovServer:
    """
    Improv WiFi BLE GATT server.

    Usage::

        async def on_creds(ssid, password):
            ok, ip = await connect_wifi(ssid, password)
            return ok, f"http://{ip}:8080"

        srv = ImprovServer("R2D2-AABBCC", on_creds)
        await srv.start()
        # ... run forever ...
        await srv.stop()
    """

    def __init__(
        self,
        device_name: str,
        connect_callback: Callable[[str, str], asyncio.Future],
    ) -> None:
        if not HAS_BLESS:
            raise RuntimeError(
                "bless is not installed. Run: pip install bless"
            )
        self._name     = device_name
        self._callback = connect_callback
        self._server: Optional[BlessServer] = None
        self._state    = ST_AUTH
        self._error    = ERR_NONE
        self._result   = bytearray()
        self._loop     = asyncio.get_event_loop()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def start(self) -> None:
        self._server = BlessServer(name=self._name, loop=self._loop)
        self._server.read_request_func  = self._on_read
        self._server.write_request_func = self._on_write

        gatt = {
            SVC: {
                CHR_STATE: {
                    "Properties": Props.read | Props.notify,
                    "Permissions": Perms.readable,
                    "Value": bytearray([self._state]),
                },
                CHR_ERROR: {
                    "Properties": Props.read | Props.notify,
                    "Permissions": Perms.readable,
                    "Value": bytearray([self._error]),
                },
                CHR_CMD: {
                    "Properties": Props.write | Props.write_without_response,
                    "Permissions": Perms.writeable,
                    "Value": bytearray(),
                },
                CHR_RESULT: {
                    "Properties": Props.read | Props.notify,
                    "Permissions": Perms.readable,
                    "Value": bytearray(),
                },
                CHR_CAPS: {
                    "Properties": Props.read,
                    "Permissions": Perms.readable,
                    "Value": bytearray([0x00]),  # identify not implemented
                },
            }
        }

        await self._server.add_gatt(gatt)
        await self._server.start()
        log.info("Improv BLE advertising as '%s'", self._name)

    async def stop(self) -> None:
        if self._server:
            await self._server.stop()
            log.info("Improv BLE stopped")

    def set_provisioned(self, url: str) -> None:
        """Call after successful WiFi connection."""
        self._set_state(ST_PROVISIONED)
        self._result = _rpc_result(CMD_WIFI, True, url)
        self._notify(CHR_RESULT, self._result)

    def set_error(self, code: int) -> None:
        self._error = code
        self._notify(CHR_ERROR, bytearray([code]))
        self._set_state(ST_AUTH)   # reset to authorized so user can retry

    # ------------------------------------------------------------------
    # GATT callbacks
    # ------------------------------------------------------------------

    def _on_read(self, characteristic, **_):
        uid = str(characteristic.uuid)
        if uid == CHR_STATE:
            return bytearray([self._state])
        if uid == CHR_ERROR:
            return bytearray([self._error])
        if uid == CHR_RESULT:
            return self._result
        if uid == CHR_CAPS:
            return bytearray([0x00])
        return bytearray()

    def _on_write(self, characteristic, value: bytearray, **_):
        uid = str(characteristic.uuid)
        if uid != CHR_CMD:
            return

        data = bytes(value)
        if len(data) < 3:
            self.set_error(ERR_INVALID_RPC)
            return

        cmd      = data[0]
        length   = data[1]
        payload  = data[2:2 + length]
        checksum = data[2 + length] if len(data) > 2 + length else 0

        if _checksum(data[:2 + length]) != checksum:
            log.warning("Improv: bad checksum")
            self.set_error(ERR_INVALID_RPC)
            return

        if cmd == CMD_WIFI:
            self._loop.create_task(self._handle_wifi(payload))
        elif cmd == CMD_IDENTIFY:
            log.info("Improv: identify request")
        elif cmd == CMD_INFO:
            self._send_device_info()
        else:
            self.set_error(ERR_UNKNOWN_CMD)

    # ------------------------------------------------------------------
    # Command handlers
    # ------------------------------------------------------------------

    async def _handle_wifi(self, payload: bytes) -> None:
        try:
            idx  = 0
            slen = payload[idx]; idx += 1
            ssid = payload[idx:idx + slen].decode(); idx += slen
            plen = payload[idx]; idx += 1
            pwd  = payload[idx:idx + plen].decode()
        except Exception:
            self.set_error(ERR_INVALID_RPC)
            return

        log.info("Improv: connecting to SSID '%s'", ssid)
        self._set_state(ST_PROVISIONING)
        self._error = ERR_NONE

        try:
            success, url = await self._callback(ssid, pwd)
        except Exception as e:
            log.error("Improv: connect callback raised: %s", e)
            self.set_error(ERR_CANT_CONNECT)
            return

        if success:
            self.set_provisioned(url)
            log.info("Improv: provisioned — %s", url)
        else:
            self.set_error(ERR_CANT_CONNECT)
            log.warning("Improv: connection failed")

    def _send_device_info(self) -> None:
        firmware  = b"r2d2-server"
        fw_ver    = b"1.0.0"
        hw_ver    = b"orangepi-zero-plus2-h5"
        device_id = self._name.encode()
        body = (
            bytes([len(firmware)]) + firmware +
            bytes([len(fw_ver)])   + fw_ver   +
            bytes([len(hw_ver)])   + hw_ver   +
            bytes([len(device_id)]) + device_id
        )
        result = bytearray(
            [CMD_INFO, 0x01, len(body)] + list(body) + [_checksum(bytes([CMD_INFO, 0x01, len(body)]) + body)]
        )
        self._notify(CHR_RESULT, result)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _set_state(self, state: int) -> None:
        self._state = state
        self._notify(CHR_STATE, bytearray([state]))

    def _notify(self, char_uuid: str, value: bytearray) -> None:
        if not self._server:
            return
        try:
            char = self._server.get_characteristic(char_uuid)
            if char:
                char.value = value
                self._server.update_value(SVC, char_uuid)
        except Exception as e:
            log.debug("notify %s: %s", char_uuid, e)
