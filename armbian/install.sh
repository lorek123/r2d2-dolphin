#!/usr/bin/env bash
# =============================================================================
# R2D2 Armbian Setup Script
# Tested on: Armbian Bookworm (Debian 12) for Orange Pi Zero Plus 2 H5
#
# Run as root:
#   curl -fsSL https://... | bash
# or:
#   sudo bash /path/to/install.sh
# =============================================================================
set -euo pipefail

R2D2_DIR="/opt/r2d2"
R2D2_USER="r2d2"
VENV="$R2D2_DIR/venv"
PYTHON="python3"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
die()   { echo -e "\033[1;31m[FAIL]\033[0m  $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Run as root (sudo bash install.sh)"

# ---------------------------------------------------------------------------
# 1. System packages
# ---------------------------------------------------------------------------
info "Installing system packages..."
apt-get update -qq
apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv python3-dev \
    network-manager \
    git curl rsync \
    libopencv-dev python3-opencv \
    v4l-utils \
    ffmpeg \
    alsa-utils mpg123 \
    avahi-daemon \
    build-essential libportaudio2

# Julius (voice recognition) - optional, skip if not needed
if apt-cache show julius &>/dev/null 2>&1; then
    apt-get install -y --no-install-recommends julius julius-dev || warn "julius not available, skipping"
else
    warn "Julius not in apt — install from source if needed (see docs/PROTOCOL.md)"
fi

ok "System packages installed"

# ---------------------------------------------------------------------------
# 2. Create r2d2 user
# ---------------------------------------------------------------------------
if ! id "$R2D2_USER" &>/dev/null; then
    info "Creating user: $R2D2_USER"
    useradd -r -s /usr/sbin/nologin -G dialout,video,audio "$R2D2_USER"
fi

# ---------------------------------------------------------------------------
# 3. Copy application files
# ---------------------------------------------------------------------------
info "Copying R2D2 files to $R2D2_DIR..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"

mkdir -p "$R2D2_DIR"
rsync -a --exclude='.git' --exclude='__pycache__' --exclude='*.pyc' \
    "$REPO_DIR/" "$R2D2_DIR/"

chown -R "$R2D2_USER:$R2D2_USER" "$R2D2_DIR"
# wifi_manager.py runs as root (needs nmcli hotspot), so make it root-owned
chown root:root "$R2D2_DIR/armbian/wifi_manager.py" 2>/dev/null || true

ok "Files copied to $R2D2_DIR"

# ---------------------------------------------------------------------------
# 4. Python virtual environment
# ---------------------------------------------------------------------------
info "Creating Python venv at $VENV..."
$PYTHON -m venv "$VENV"
"$VENV/bin/pip" install --upgrade pip -q
"$VENV/bin/pip" install -r "$R2D2_DIR/python/requirements.txt" -q

ok "Python dependencies installed"

# ---------------------------------------------------------------------------
# 5. Serial port permissions
# ---------------------------------------------------------------------------
info "Configuring serial port /dev/ttyS2..."
# Armbian: set up udev rule for persistent permissions
cat > /etc/udev/rules.d/99-r2d2-serial.rules <<'EOF'
# R2D2 MCU serial port
KERNEL=="ttyS2", MODE="0666", GROUP="dialout"
EOF
udevadm control --reload-rules
udevadm trigger

ok "Serial port configured"

# ---------------------------------------------------------------------------
# 6. Camera permissions
# ---------------------------------------------------------------------------
cat > /etc/udev/rules.d/99-r2d2-camera.rules <<'EOF'
# R2D2 camera
KERNEL=="video[0-9]*", MODE="0664", GROUP="video"
EOF
udevadm control --reload-rules

# ---------------------------------------------------------------------------
# 7. NetworkManager — enable dnsmasq shared mode (for captive portal)
# ---------------------------------------------------------------------------
info "Configuring NetworkManager for captive portal..."
NM_CONF="/etc/NetworkManager/conf.d/r2d2.conf"
cat > "$NM_CONF" <<'EOF'
[main]
# Use dnsmasq for DNS in shared (hotspot) connections
dns=dnsmasq

[connection]
# Automatically use dnsmasq when creating shared connections
wifi.backend=wpa_supplicant
EOF

# The per-hotspot DNS redirect is written at runtime by wifi_manager.py
mkdir -p /etc/NetworkManager/dnsmasq-shared.d
systemctl reload NetworkManager 2>/dev/null || true

ok "NetworkManager configured"

# ---------------------------------------------------------------------------
# 8. mDNS / Avahi hostname
# ---------------------------------------------------------------------------
info "Setting hostname to r2d2..."
hostnamectl set-hostname r2d2
# Avahi will advertise r2d2.local automatically
systemctl enable --now avahi-daemon

ok "Hostname: r2d2  (reachable as r2d2.local on your network)"

# ---------------------------------------------------------------------------
# 9. Config file
# ---------------------------------------------------------------------------
info "Creating /etc/r2d2/config.env..."
mkdir -p /etc/r2d2
if [[ ! -f /etc/r2d2/config.env ]]; then
    cat > /etc/r2d2/config.env <<'EOF'
# R2D2 server configuration
# Edit as needed, then: sudo systemctl restart r2d2

R2D2_SERIAL_PORT=/dev/ttyS2
R2D2_SERIAL_BAUD=115200

R2D2_WS_HOST=0.0.0.0
R2D2_WS_PORT=8765

R2D2_API_HOST=0.0.0.0
R2D2_API_PORT=8080

R2D2_CAMERA=/dev/video0
R2D2_CAMERA_BACKEND=haar
R2D2_CAMERA_ROTATION=270
R2D2_CAMERA_FPS=10
R2D2_CAMERA_ENABLED=true

R2D2_MJPEG_HOST=0.0.0.0
R2D2_MJPEG_PORT=12121
R2D2_MJPEG_FPS=10

R2D2_SOUNDS_DIR=/opt/r2d2/resources/assets/sounds
EOF
    ok "Created /etc/r2d2/config.env"
else
    warn "/etc/r2d2/config.env already exists — not overwritten"
fi

# ---------------------------------------------------------------------------
# 10. Systemd services
# ---------------------------------------------------------------------------
info "Installing systemd services..."
SVCDIR="$R2D2_DIR/armbian/systemd"

cp "$SVCDIR/r2d2-portal.service" /etc/systemd/system/
cp "$SVCDIR/r2d2.service"        /etc/systemd/system/

systemctl daemon-reload
systemctl enable r2d2-portal.service
# r2d2.service is NOT enabled — started/stopped by wifi_manager.py
# Enable manually if you always want it to start (e.g. wired connection):
#   sudo systemctl enable r2d2.service

ok "Systemd services installed"
ok ""
ok "============================================================"
ok " R2D2 setup complete!"
ok ""
ok " On next reboot (or now with 'sudo systemctl start r2d2-portal'):"
ok "   1. If no WiFi configured: connect to 'R2D2-XXXXXX' AP"
ok "      and open http://192.168.4.1/ to configure WiFi"
ok "   2. Once connected: r2d2 server starts automatically"
ok ""
ok " Endpoints (once on your network):"
ok "   WebSocket  ws://r2d2.local:8765"
ok "   REST API   http://r2d2.local:8080"
ok "   MJPEG cam  http://r2d2.local:12121/stream"
ok "============================================================"
