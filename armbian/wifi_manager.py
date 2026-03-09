#!/usr/bin/env python3
"""
R2D2 WiFi Manager
-----------------
Runs as r2d2-portal.service on every boot.

Primary mode (always active)
  R2D2 creates its own AP: "R2D2-XXXXXX" (open, no password)
  r2d2.service starts immediately — original Android app connects to 192.168.4.1

Optional home-WiFi mode (via captive portal)
  The portal at http://192.168.4.1/ lets you configure a home WiFi network.
  If the AP6212 chip supports concurrent AP+STA (it does under kernel 6.x via
  the "uap0" virtual interface), both the R2D2 AP and the home network stay up
  simultaneously.  If concurrent mode fails the home credentials are saved for
  later and R2D2 stays in AP-only mode.

State machine
  AP_ONLY   AP up, r2d2 server running. Default at boot.
  AP_STA    AP up + connected to home WiFi (concurrent).
  STATION   Connected to home WiFi only (concurrent failed; user opted in).

Environment variables
  R2D2_WIFI_IFACE        wlan0        primary wireless interface
  R2D2_AP_IFACE          uap0         virtual AP interface (concurrent mode)
  R2D2_AP_CHANNEL        6
  R2D2_AP_IP             192.168.4.1
  R2D2_PORTAL_PORT       80
  R2D2_MONITOR_INTERVAL  30           seconds between link checks
  R2D2_CREDS_FILE        /etc/r2d2/home_wifi.conf  saved home WiFi
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Optional

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

IFACE            = os.getenv("R2D2_WIFI_IFACE",       "wlan0")
AP_IFACE         = os.getenv("R2D2_AP_IFACE",         "uap0")
AP_CHANNEL       = os.getenv("R2D2_AP_CHANNEL",       "6")
AP_IP            = os.getenv("R2D2_AP_IP",            "192.168.4.1")
PORTAL_PORT      = int(os.getenv("R2D2_PORTAL_PORT",  "80"))
MONITOR_INTERVAL = int(os.getenv("R2D2_MONITOR_INTERVAL", "30"))
CREDS_FILE       = Path(os.getenv("R2D2_CREDS_FILE",  "/etc/r2d2/home_wifi.conf"))

AP_CON_NAME      = "r2d2-ap"
HOME_CON_PREFIX  = "r2d2-home"
NM_DNSMASQ_DIR   = Path("/etc/NetworkManager/dnsmasq-shared.d")
NM_DNSMASQ_CONF  = NM_DNSMASQ_DIR / "r2d2-portal.conf"
R2D2_SERVICE     = "r2d2.service"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [wifi_manager] %(levelname)s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("wifi_manager")


# ---------------------------------------------------------------------------
# Shared state (read by portal API handlers)
# ---------------------------------------------------------------------------

state: dict = {
    "ap_ssid":      "",
    "ap_up":        False,
    "home_ssid":    None,   # str if connected to home WiFi
    "home_ip":      None,
    "connecting":   False,
    "last_result":  None,   # {"success": bool, "message": str}
}


# ---------------------------------------------------------------------------
# Shell helpers
# ---------------------------------------------------------------------------

def _run(cmd: list[str], check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, check=check,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        text=True,
    )


# ---------------------------------------------------------------------------
# Hardware / NM queries
# ---------------------------------------------------------------------------

def get_mac(iface: str = IFACE) -> str:
    try:
        return Path(f"/sys/class/net/{iface}/address").read_text().strip().replace(":", "").upper()[-6:]
    except Exception:
        return "R2D2XX"


def iface_exists(iface: str) -> bool:
    return Path(f"/sys/class/net/{iface}").exists()


def is_connected_sta() -> bool:
    """True if IFACE (station side) is connected to a non-AP network."""
    try:
        r = _run(["nmcli", "-t", "-f", "DEVICE,STATE,CONNECTION", "dev", "status"])
        for line in r.stdout.splitlines():
            parts = line.split(":")
            if len(parts) >= 3 and parts[0] == IFACE and parts[1] == "connected":
                con = parts[2]
                if con and con != AP_CON_NAME:
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


def scan_networks() -> list[dict]:
    try:
        _run(["nmcli", "dev", "wifi", "rescan", "ifname", IFACE], check=False)
        time.sleep(2)
        r = _run([
            "nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY,IN-USE",
            "dev", "wifi", "list", "ifname", IFACE,
        ])
        seen: dict[str, dict] = {}
        for line in r.stdout.splitlines():
            parts = line.split(":")
            if len(parts) < 4:
                continue
            ssid, signal, security, in_use = parts[0], parts[1], parts[2], parts[3]
            ssid = ssid.strip()
            if not ssid or ssid == state["ap_ssid"]:
                continue
            try:
                sig = int(signal)
            except ValueError:
                sig = 0
            if ssid not in seen or sig > seen[ssid]["signal"]:
                seen[ssid] = {
                    "ssid": ssid,
                    "signal": sig,
                    "security": bool(security.strip()),
                    "in_use": in_use.strip() == "*",
                }
        return sorted(seen.values(), key=lambda x: -x["signal"])
    except Exception as e:
        log.warning("scan failed: %s", e)
        return []


# ---------------------------------------------------------------------------
# AP management
# ---------------------------------------------------------------------------

def _install_captive_redirect() -> None:
    """Tell NM's dnsmasq to redirect all DNS queries to the portal IP."""
    NM_DNSMASQ_DIR.mkdir(parents=True, exist_ok=True)
    NM_DNSMASQ_CONF.write_text(f"address=/#/{AP_IP}\n")


def _remove_captive_redirect() -> None:
    NM_DNSMASQ_CONF.unlink(missing_ok=True)


def start_ap(ssid: str) -> bool:
    """
    Try concurrent AP+STA using the uap0 virtual interface first.
    Fall back to AP-only on wlan0 if uap0 is not available.
    """
    # Remove any stale AP connection
    _run(["nmcli", "con", "delete", AP_CON_NAME], check=False)

    ap_iface = AP_IFACE if iface_exists(AP_IFACE) else IFACE

    try:
        _run([
            "nmcli", "con", "add",
            "con-name",          AP_CON_NAME,
            "type",              "wifi",
            "ifname",            ap_iface,
            "ssid",              ssid,
            "wifi.mode",         "ap",
            "wifi.band",         "bg",
            "wifi.channel",      AP_CHANNEL,
            "wifi-sec.key-mgmt", "none",
            "ipv4.method",       "shared",
            "ipv4.addresses",    f"{AP_IP}/24",
            "connection.autoconnect", "no",
        ])
        _run(["nmcli", "con", "up", AP_CON_NAME])
        _install_captive_redirect()
        state["ap_up"] = True
        log.info("AP started: ssid=%s  iface=%s  ip=%s", ssid, ap_iface, AP_IP)
        return True
    except subprocess.CalledProcessError as e:
        log.error("AP start failed: %s", e.stderr)
        return False


def stop_ap() -> None:
    _run(["nmcli", "con", "delete", AP_CON_NAME], check=False)
    _remove_captive_redirect()
    state["ap_up"] = False
    log.info("AP stopped")


# ---------------------------------------------------------------------------
# Home WiFi connection
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


def forget_home_wifi() -> None:
    """Remove saved credentials and any active home connection."""
    CREDS_FILE.unlink(missing_ok=True)
    try:
        r = _run(["nmcli", "-t", "-f", "NAME,TYPE", "con", "show"])
        for line in r.stdout.splitlines():
            name, _, ctype = line.partition(":")
            if "wifi" in ctype and name.startswith(HOME_CON_PREFIX):
                _run(["nmcli", "con", "delete", name], check=False)
    except Exception:
        pass
    state["home_ssid"] = None
    state["home_ip"] = None
    log.info("Home WiFi forgotten")


def connect_home(ssid: str, password: str) -> tuple[bool, str]:
    """
    Connect wlan0 to a home network.
    Works in concurrent mode (AP on uap0) or AP-only (AP paused briefly).
    """
    # Delete any stale home profile for this SSID
    _run(["nmcli", "con", "delete", f"{HOME_CON_PREFIX}-{ssid}"], check=False)

    cmd = [
        "nmcli", "dev", "wifi", "connect", ssid,
        "ifname", IFACE,
        "name", f"{HOME_CON_PREFIX}-{ssid}",
    ]
    if password:
        cmd += ["password", password]

    try:
        r = _run(cmd, check=False)
        if r.returncode == 0 and "successfully activated" in r.stdout:
            ip = get_sta_ip() or "unknown"
            save_creds(ssid, password)
            state["home_ssid"] = ssid
            state["home_ip"] = ip
            return True, f"Connected to {ssid} — IP: {ip}"
        err = (r.stderr or r.stdout).strip().splitlines()[-1] if (r.stderr or r.stdout).strip() else "Unknown error"
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
def stop_r2d2()    -> None: log.info("Stopping %s",   R2D2_SERVICE); _svc("stop")
def restart_r2d2() -> None: log.info("Restarting %s", R2D2_SERVICE); _svc("restart")


# ---------------------------------------------------------------------------
# Captive portal web app
# ---------------------------------------------------------------------------

app = FastAPI(docs_url=None, redoc_url=None)

_CAPTIVE_URLS = {
    "/generate_204", "/hotspot-detect.html", "/library/test/success.html",
    "/connecttest.txt", "/ncsi.txt", "/redirect", "/canonical.html", "/success.txt",
}


@app.middleware("http")
async def captive_redirect(request: Request, call_next):
    path = request.url.path
    if path in _CAPTIVE_URLS:
        return RedirectResponse("/", status_code=302)
    return await call_next(request)


@app.get("/", response_class=HTMLResponse)
async def portal_page(_: Request) -> HTMLResponse:
    return HTMLResponse(_PORTAL_HTML.replace("__AP_SSID__", state["ap_ssid"]))


@app.get("/api/scan")
async def api_scan() -> JSONResponse:
    nets = await asyncio.get_event_loop().run_in_executor(None, scan_networks)
    return JSONResponse(nets)


@app.post("/api/connect")
async def api_connect(request: Request) -> JSONResponse:
    if state["connecting"]:
        return JSONResponse({"status": "busy", "message": "Already connecting"})
    data = await request.json()
    ssid     = data.get("ssid", "").strip()
    password = data.get("password", "").strip()
    if not ssid:
        return JSONResponse({"status": "error", "message": "SSID required"}, status_code=400)

    state["connecting"] = True
    state["last_result"] = None

    async def _do() -> None:
        loop = asyncio.get_event_loop()
        success, msg = await loop.run_in_executor(None, connect_home, ssid, password)
        state["connecting"] = False
        state["last_result"] = {"success": success, "message": msg}
        if success:
            log.info("Home WiFi connected via portal: %s  %s", ssid, msg)
        else:
            log.warning("Home WiFi connect failed: %s", msg)

    asyncio.create_task(_do())
    return JSONResponse({"status": "connecting", "message": f"Connecting to {ssid}..."})


@app.post("/api/forget")
async def api_forget(_: Request) -> JSONResponse:
    await asyncio.get_event_loop().run_in_executor(None, forget_home_wifi)
    return JSONResponse({"status": "ok", "message": "Home WiFi forgotten"})


@app.get("/api/status")
async def api_status() -> JSONResponse:
    return JSONResponse({
        "ap":   {"up": state["ap_up"],    "ssid": state["ap_ssid"], "ip": AP_IP},
        "home": {"connected": bool(state["home_ssid"]), "ssid": state["home_ssid"], "ip": state["home_ip"]},
        "connecting":  state["connecting"],
        "last_result": state["last_result"],
    })


# ---------------------------------------------------------------------------
# Portal HTML
# ---------------------------------------------------------------------------

_PORTAL_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>R2D2 Setup</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;background:#0a0a1a;color:#e0e0f0;
     min-height:100vh;display:flex;flex-direction:column;align-items:center;
     justify-content:center;padding:16px}
.card{background:#111128;border:1px solid #2a2a5a;border-radius:12px;padding:24px;
      width:100%;max-width:460px;box-shadow:0 8px 32px rgba(0,100,255,.12)}
h1{font-size:1.4rem;color:#7fb3ff;margin-bottom:4px}
.sub{font-size:.82rem;color:#888;margin-bottom:20px}
.section{margin-top:20px;padding-top:16px;border-top:1px solid #1e1e40}
h2{font-size:.95rem;color:#aaa;margin-bottom:12px;text-transform:uppercase;
   letter-spacing:.05em}
label{display:block;font-size:.8rem;color:#aaa;margin-bottom:4px}
input,select{width:100%;padding:9px 12px;border-radius:8px;border:1px solid #2a2a5a;
             background:#0d0d22;color:#e0e0f0;font-size:.92rem;margin-bottom:10px}
input:focus{outline:none;border-color:#4a6aff}
.btn{width:100%;padding:11px;border-radius:8px;border:none;cursor:pointer;
     font-size:.95rem;font-weight:600;transition:background .15s}
.btn-primary{background:#4a6aff;color:#fff}.btn-primary:hover{background:#5a7aff}
.btn-secondary{background:#1a1a3a;color:#7fb3ff;border:1px solid #2a2a5a;margin-top:8px}
.btn-secondary:hover{background:#2a2a5a}
.btn-danger{background:#3a1a1a;color:#ff7f7f;border:1px solid #6a2a2a;margin-top:8px}
.btn-danger:hover{background:#4a2a2a}
button:disabled{opacity:.4;cursor:not-allowed}
.pill{display:inline-block;padding:3px 10px;border-radius:20px;font-size:.75rem;font-weight:600}
.pill-ok{background:#0a2a0a;color:#7fff7f;border:1px solid #2a6a2a}
.pill-off{background:#1a1a1a;color:#666;border:1px solid #333}
.net-list{margin-bottom:10px;max-height:190px;overflow-y:auto;
          border:1px solid #2a2a5a;border-radius:8px}
.net-item{display:flex;align-items:center;padding:9px 12px;cursor:pointer;
          border-bottom:1px solid #1a1a3a;transition:background .15s}
.net-item:last-child{border-bottom:none}
.net-item:hover,.net-item.sel{background:#1a2a4a}
.net-name{flex:1;font-size:.9rem}
.net-sig{font-size:.72rem;color:#888;margin-right:8px}
.status{margin-top:12px;padding:9px 13px;border-radius:8px;font-size:.86rem;display:none}
.ok{background:#0a2a0a;border:1px solid #2a6a2a;color:#7fff7f}
.err{background:#2a0a0a;border:1px solid #6a2a2a;color:#ff7f7f}
.info{background:#0a1a2a;border:1px solid #2a4a6a;color:#7fbfff}
.spinner{display:inline-block;width:12px;height:12px;border:2px solid currentColor;
         border-top-color:transparent;border-radius:50%;
         animation:spin .7s linear infinite;vertical-align:middle;margin-right:5px}
@keyframes spin{to{transform:rotate(360deg)}}
.endpoints{margin-top:8px;font-size:.8rem;color:#888;line-height:1.7}
.endpoints code{color:#7fb3ff;background:#0d0d22;padding:1px 5px;border-radius:4px}
</style>
</head>
<body>
<div class="card">
  <h1>R2D2 Control</h1>
  <p class="sub">Direct AP: <strong>__AP_SSID__</strong> &mdash; no home WiFi required</p>

  <!-- AP status -->
  <div id="apStatus"></div>

  <!-- Endpoints -->
  <div class="endpoints" id="endpoints">
    Connect your app to:<br>
    WebSocket &nbsp;<code>ws://192.168.4.1:8765</code><br>
    REST API &nbsp;&nbsp;<code>http://192.168.4.1:8080</code><br>
    Camera &nbsp;&nbsp;&nbsp;<code>http://192.168.4.1:12121/stream</code>
  </div>

  <!-- Optional home WiFi -->
  <div class="section">
    <h2>Home WiFi <span class="pill pill-off" id="homePill">not set</span></h2>
    <p class="sub" style="margin-bottom:12px">Optional — for Home Assistant integration</p>

    <div id="homeConnected" style="display:none">
      Connected to <strong id="homeSsid"></strong> &mdash; <span id="homeIp"></span>
      <button class="btn btn-danger" onclick="forget()">Forget home WiFi</button>
    </div>

    <div id="homeSetup">
      <button class="btn btn-secondary" onclick="scan()">Scan for networks</button>
      <div class="net-list" id="netList" style="margin-top:8px;display:none"></div>

      <label for="ssid" style="margin-top:8px">Network name (SSID)</label>
      <input id="ssid" type="text" placeholder="Select above or type" autocomplete="off">
      <label for="pass">Password</label>
      <input id="pass" type="password" placeholder="Leave empty for open networks">
      <button class="btn btn-primary" id="connectBtn" onclick="connectHome()">Connect to home WiFi</button>
    </div>

    <div class="status" id="status"></div>
  </div>
</div>

<script>
let polling = false;

async function loadStatus() {
  const s = await (await fetch('/api/status')).json();
  // home section
  if (s.home.connected) {
    document.getElementById('homePill').textContent = 'connected';
    document.getElementById('homePill').className = 'pill pill-ok';
    document.getElementById('homeSsid').textContent = s.home.ssid;
    document.getElementById('homeIp').textContent = s.home.ip;
    document.getElementById('homeConnected').style.display = '';
    document.getElementById('homeSetup').style.display = 'none';
  } else {
    document.getElementById('homePill').textContent = 'not set';
    document.getElementById('homePill').className = 'pill pill-off';
    document.getElementById('homeConnected').style.display = 'none';
    document.getElementById('homeSetup').style.display = '';
  }
}

async function scan() {
  showStatus('info','<span class="spinner"></span>Scanning...');
  const nets = await (await fetch('/api/scan')).json();
  const list = document.getElementById('netList');
  list.style.display = '';
  if (!nets.length) {
    list.innerHTML = '<div class="net-item" style="color:#666;cursor:default">No networks found</div>';
    showStatus('err','No networks found.');
    return;
  }
  list.innerHTML = nets.map(n =>
    `<div class="net-item" onclick="sel(this,'${esc(n.ssid)}')">
       <span class="net-name">${esc(n.ssid)}</span>
       <span class="net-sig">${n.signal}%</span>
       <span>${n.security?'&#128274;':'&#128275;'}</span>
     </div>`).join('');
  hideStatus();
}

function sel(el, ssid) {
  document.querySelectorAll('.net-item').forEach(e=>e.classList.remove('sel'));
  el.classList.add('sel');
  document.getElementById('ssid').value = ssid;
  document.getElementById('pass').focus();
}

async function connectHome() {
  const ssid = document.getElementById('ssid').value.trim();
  const pass = document.getElementById('pass').value;
  if (!ssid) { showStatus('err','Enter a network name.'); return; }
  document.getElementById('connectBtn').disabled = true;
  showStatus('info',`<span class="spinner"></span>Connecting to ${esc(ssid)}...`);
  await fetch('/api/connect',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({ssid,password:pass})});
  polling = true;
  poll();
}

async function poll() {
  if (!polling) return;
  try {
    const s = await (await fetch('/api/status')).json();
    if (!s.connecting) {
      polling = false;
      document.getElementById('connectBtn').disabled = false;
      if (s.last_result?.success) {
        showStatus('ok',`Connected to home WiFi! R2D2 also reachable at <strong>${s.home.ip}</strong>`);
        await loadStatus();
      } else {
        showStatus('err','Failed: ' + esc(s.last_result?.message || 'unknown'));
      }
      return;
    }
  } catch(e) { /* interface switching */ }
  setTimeout(poll, 1500);
}

async function forget() {
  if (!confirm('Forget home WiFi?')) return;
  await fetch('/api/forget',{method:'POST'});
  await loadStatus();
}

function showStatus(t,m){const e=document.getElementById('status');e.className='status '+t;e.innerHTML=m;e.style.display='';}
function hideStatus(){document.getElementById('status').style.display='none';}
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}

loadStatus();
</script>
</body>
</html>
"""


# ---------------------------------------------------------------------------
# Manager loop
# ---------------------------------------------------------------------------

async def manager_loop() -> None:
    await asyncio.sleep(5)  # let NM settle after boot

    ap_ssid = f"R2D2-{get_mac()}"
    state["ap_ssid"] = ap_ssid

    # Always start AP first — this is the primary mode
    log.info("Starting AP (primary mode): %s", ap_ssid)
    start_ap(ap_ssid)

    # Always start r2d2 server immediately
    start_r2d2()

    # Try to connect to saved home WiFi (concurrent mode)
    creds = load_creds()
    if creds:
        log.info("Saved home WiFi found (%s), attempting concurrent connection", creds["ssid"])
        loop = asyncio.get_event_loop()
        success, msg = await loop.run_in_executor(None, connect_home, creds["ssid"], creds["password"])
        if success:
            log.info("Concurrent AP+STA active: AP=%s  Home=%s  ip=%s", ap_ssid, creds["ssid"], state["home_ip"])
        else:
            log.warning("Concurrent home WiFi failed: %s (AP-only mode continues)", msg)

    # Monitor loop — just watch the home WiFi link; AP always stays up
    await _monitor_loop()


async def _monitor_loop() -> None:
    log.info("Monitoring (interval=%ds)", MONITOR_INTERVAL)
    was_home = bool(state["home_ssid"])
    while True:
        await asyncio.sleep(MONITOR_INTERVAL)
        is_home = is_connected_sta()
        if is_home and not was_home:
            ip = get_sta_ip()
            state["home_ip"] = ip
            log.info("Home WiFi restored: ip=%s", ip)
        elif not is_home and was_home:
            state["home_ip"] = None
            log.warning("Home WiFi link lost")
        was_home = is_home


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

async def main() -> None:
    config = uvicorn.Config(
        app,
        host="0.0.0.0",   # listen on all interfaces (AP + station)
        port=PORTAL_PORT,
        log_level="warning",
        access_log=False,
    )
    server = uvicorn.Server(config)
    await asyncio.gather(manager_loop(), server.serve())


if __name__ == "__main__":
    asyncio.run(main())
