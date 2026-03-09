#!/usr/bin/env python3
"""
R2D2 WiFi Manager
-----------------
Runs as r2d2-portal.service on every boot.

Primary mode (always active)
  R2D2 creates its own AP "R2D2-XXXXXX" — original Android app connects here.
  r2d2.service starts immediately regardless of home WiFi status.

Home WiFi provisioning
  Uses Improv WiFi over BLE (https://www.improv-wifi.com/ble/).
  Open https://www.improv-wifi.com/ in Chrome, click "Connect",
  pick R2D2-XXXXXX, enter your WiFi credentials. No captive portal needed.

  If home WiFi credentials are already saved, the board also connects to
  the home network on boot (concurrent AP+STA via uap0 if supported).

Environment variables
  R2D2_WIFI_IFACE        wlan0        primary wireless interface
  R2D2_AP_IFACE          uap0         virtual AP iface (concurrent mode)
  R2D2_AP_CHANNEL        6
  R2D2_AP_IP             192.168.4.1
  R2D2_MONITOR_INTERVAL  30           seconds between link-health checks
  R2D2_CREDS_FILE        /etc/r2d2/home_wifi.conf
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import time
from pathlib import Path
from typing import Optional

from improv import ImprovServer

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

IFACE            = os.getenv("R2D2_WIFI_IFACE",           "wlan0")
AP_IFACE         = os.getenv("R2D2_AP_IFACE",             "uap0")
AP_CHANNEL       = os.getenv("R2D2_AP_CHANNEL",           "6")
AP_IP            = os.getenv("R2D2_AP_IP",                "192.168.4.1")
MONITOR_INTERVAL = int(os.getenv("R2D2_MONITOR_INTERVAL", "30"))
CREDS_FILE       = Path(os.getenv("R2D2_CREDS_FILE",      "/etc/r2d2/home_wifi.conf"))

AP_CON_NAME      = "r2d2-ap"
HOME_CON_PREFIX  = "r2d2-home"
NM_DNSMASQ_DIR   = Path("/etc/NetworkManager/dnsmasq-shared.d")
R2D2_SERVICE     = "r2d2.service"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [wifi_manager] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("wifi_manager")


# ---------------------------------------------------------------------------
# nmcli helpers
# ---------------------------------------------------------------------------

def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, check=check,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )


def get_mac(iface: str = IFACE) -> str:
    try:
        return Path(f"/sys/class/net/{iface}/address").read_text().strip().replace(":", "").upper()[-6:]
    except Exception:
        return "R2D2XX"


def iface_exists(iface: str) -> bool:
    return Path(f"/sys/class/net/{iface}").exists()


def is_connected_sta() -> bool:
    try:
        r = _run(["nmcli", "-t", "-f", "DEVICE,STATE,CONNECTION", "dev", "status"])
        for line in r.stdout.splitlines():
            dev, _, rest = line.partition(":")
            state, _, con = rest.partition(":")
            if dev == IFACE and state == "connected" and con and con != AP_CON_NAME:
                return True
    except Exception:
        pass
    return False


def get_sta_ip() -> Optional[str]:
    try:
        r = _run(["nmcli", "-t", "-f", "IP4.ADDRESS", "dev", "show", IFACE])
        for line in r.stdout.splitlines():
            if "IP4.ADDRESS" in line:
                return line.split(":")[1].split("/")[0].strip() or None
    except Exception:
        pass
    return None


# ---------------------------------------------------------------------------
# AP management
# ---------------------------------------------------------------------------

def start_ap(ssid: str) -> bool:
    _run(["nmcli", "con", "delete", AP_CON_NAME], check=False)
    ap_iface = AP_IFACE if iface_exists(AP_IFACE) else IFACE
    try:
        _run([
            "nmcli", "con", "add",
            "con-name", AP_CON_NAME, "type", "wifi", "ifname", ap_iface,
            "ssid", ssid, "wifi.mode", "ap", "wifi.band", "bg",
            "wifi.channel", AP_CHANNEL, "wifi-sec.key-mgmt", "none",
            "ipv4.method", "shared", "ipv4.addresses", f"{AP_IP}/24",
            "connection.autoconnect", "no",
        ])
        _run(["nmcli", "con", "up", AP_CON_NAME])
        log.info("AP started: ssid=%s  iface=%s  ip=%s", ssid, ap_iface, AP_IP)
        return True
    except subprocess.CalledProcessError as e:
        log.error("AP start failed: %s", e.stderr)
        return False


def stop_ap() -> None:
    _run(["nmcli", "con", "delete", AP_CON_NAME], check=False)


# ---------------------------------------------------------------------------
# Home WiFi
# ---------------------------------------------------------------------------

def save_creds(ssid: str, password: str) -> None:
    CREDS_FILE.parent.mkdir(parents=True, exist_ok=True)
    CREDS_FILE.write_text(json.dumps({"ssid": ssid, "password": password}))
    CREDS_FILE.chmod(0o600)


def load_creds() -> Optional[dict]:
    try:
        return json.loads(CREDS_FILE.read_text())
    except Exception:
        return None


def connect_home(ssid: str, password: str) -> tuple[bool, str]:
    _run(["nmcli", "con", "delete", f"{HOME_CON_PREFIX}-{ssid}"], check=False)
    cmd = [
        "nmcli", "dev", "wifi", "connect", ssid,
        "ifname", IFACE, "name", f"{HOME_CON_PREFIX}-{ssid}",
    ]
    if password:
        cmd += ["password", password]
    try:
        r = _run(cmd + [], check=False)
        if r.returncode == 0 and "successfully activated" in r.stdout:
            ip = get_sta_ip() or "unknown"
            save_creds(ssid, password)
            return True, ip
        err = (r.stderr or r.stdout).strip().splitlines()[-1]
        return False, err
    except Exception as e:
        return False, str(e)


# ---------------------------------------------------------------------------
# r2d2.service control
# ---------------------------------------------------------------------------

def _svc(action: str) -> None:
    try:
        _run(["systemctl", action, R2D2_SERVICE], check=False)
    except Exception as e:
        log.warning("systemctl %s %s: %s", action, R2D2_SERVICE, e)

def start_r2d2()   -> None: log.info("Starting %s",   R2D2_SERVICE); _svc("start")
def restart_r2d2() -> None: log.info("Restarting %s", R2D2_SERVICE); _svc("restart")
def stop_r2d2()    -> None: log.info("Stopping %s",   R2D2_SERVICE); _svc("stop")


# ---------------------------------------------------------------------------
# Main manager loop
# ---------------------------------------------------------------------------

async def manager_loop(ap_ssid: str) -> None:
    await asyncio.sleep(5)   # let NM settle after boot

    # Always start AP first — original app connects here
    start_ap(ap_ssid)

    # Always start r2d2 server immediately
    start_r2d2()

    # Try to reconnect to saved home WiFi (concurrent AP+STA)
    creds = load_creds()
    if creds:
        log.info("Saved home WiFi '%s' found — attempting concurrent connection", creds["ssid"])
        loop = asyncio.get_event_loop()
        success, result = await loop.run_in_executor(None, connect_home, creds["ssid"], creds["password"])
        if success:
            log.info("Concurrent AP+STA active — home ip=%s", result)
        else:
            log.warning("Home WiFi reconnect failed: %s (AP-only mode continues)", result)

    # Monitor home WiFi link
    was_home = is_connected_sta()
    while True:
        await asyncio.sleep(MONITOR_INTERVAL)
        is_home = is_connected_sta()
        if is_home and not was_home:
            log.info("Home WiFi link restored, ip=%s", get_sta_ip())
        elif not is_home and was_home:
            log.warning("Home WiFi link lost")
        was_home = is_home


# ---------------------------------------------------------------------------
# Improv callback — called when user sends WiFi credentials via BLE
# ---------------------------------------------------------------------------

async def improv_connect(ssid: str, password: str) -> tuple[bool, str]:
    """Called by ImprovServer when credentials are received over BLE."""
    log.info("Improv: connecting to '%s'", ssid)
    loop = asyncio.get_event_loop()
    success, result = await loop.run_in_executor(None, connect_home, ssid, password)
    if success:
        ip = result
        url = f"http://r2d2.local:8080"
        log.info("Improv: connected — ip=%s", ip)
        return True, url
    else:
        log.warning("Improv: connect failed: %s", result)
        return False, ""


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

async def main() -> None:
    ap_ssid = f"R2D2-{get_mac()}"
    log.info("Starting R2D2 WiFi manager — AP SSID: %s", ap_ssid)

    improv = ImprovServer(ap_ssid, improv_connect)

    try:
        await improv.start()
    except Exception as e:
        log.error("Improv BLE failed to start: %s — continuing without BLE provisioning", e)
        improv = None

    try:
        await manager_loop(ap_ssid)
    finally:
        if improv:
            await improv.stop()


if __name__ == "__main__":
    asyncio.run(main())
