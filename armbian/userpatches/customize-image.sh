#!/bin/bash
# =============================================================================
# Armbian customize-image.sh
# Runs INSIDE the ARM64 chroot during image build.
# Internet is available here (fetches packages from Debian mirrors).
#
# Positional args from Armbian build system (newer versions):
#   $1=RELEASE  $2=LINUXFAMILY  $3=BOARD  $4=BUILD_DESKTOP
# Also available as env vars in some versions.
# =============================================================================

RELEASE="${RELEASE:-${1:-unknown}}"
BOARD="${BOARD:-${3:-unknown}}"

echo "[r2d2] Starting R2D2 customization for ${BOARD} / ${RELEASE}"

set -euo pipefail

R2D2_REPO="https://github.com/lorek123/r2d2-dolphin.git"
R2D2_DIR="/opt/r2d2"
R2D2_USER="r2d2"
VENV="$R2D2_DIR/venv"

# ---------------------------------------------------------------------------
# 1. System packages
# ---------------------------------------------------------------------------
echo "[r2d2] Installing system packages..."

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq

# Core + git (needed to clone repo) + NetworkManager
apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv python3-dev \
    network-manager \
    dnsmasq-base \
    git curl rsync \
    avahi-daemon

# Bluetooth (for Improv WiFi BLE provisioning)
apt-get install -y --no-install-recommends \
    bluez bluetooth \
    python3-dbus libdbus-1-dev libglib2.0-dev

# OpenCV — prefer system package (avoids heavy pip compile on ARM)
apt-get install -y --no-install-recommends \
    libopencv-dev python3-opencv v4l-utils \
    || echo "[r2d2] WARN: opencv/v4l2 not available, will use pip"

# Media / audio
apt-get install -y --no-install-recommends \
    ffmpeg alsa-utils mpg123 libportaudio2 || true

# Julius ASR (optional — may not be in Bookworm)
apt-get install -y --no-install-recommends julius julius-dev 2>/dev/null \
    || echo "[r2d2] INFO: Julius not in apt — skipping"

echo "[r2d2] System packages installed."

# ---------------------------------------------------------------------------
# 2. Clone R2D2 code from GitHub
# ---------------------------------------------------------------------------
echo "[r2d2] Cloning r2d2-dolphin repo to $R2D2_DIR..."
rm -rf "$R2D2_DIR"
GIT_TERMINAL_PROMPT=0 git clone --depth=1 "$R2D2_REPO" "$R2D2_DIR"
echo "[r2d2] Clone complete."

# Install systemd service files from repo
cp "$R2D2_DIR/armbian/systemd/r2d2.service"        /etc/systemd/system/
cp "$R2D2_DIR/armbian/systemd/r2d2-portal.service" /etc/systemd/system/

# ---------------------------------------------------------------------------
# 3. NetworkManager takes over from systemd-networkd
#    (orangepizeroplus2-h5 defaults to systemd-networkd in Armbian)
# ---------------------------------------------------------------------------
echo "[r2d2] Configuring NetworkManager as primary network stack..."
systemctl disable systemd-networkd            2>/dev/null || true
systemctl disable systemd-networkd-wait-online 2>/dev/null || true
systemctl enable  NetworkManager              2>/dev/null || true
mkdir -p /etc/NetworkManager/dnsmasq-shared.d
# NM config (dns=dnsmasq for hotspot) placed by overlay
# /etc/NetworkManager/conf.d/r2d2.conf

# ---------------------------------------------------------------------------
# 4. Create r2d2 system user
# ---------------------------------------------------------------------------
echo "[r2d2] Creating system user: $R2D2_USER"
if ! id "$R2D2_USER" &>/dev/null; then
    useradd -r -s /usr/sbin/nologin -G dialout,video,audio,bluetooth "$R2D2_USER"
fi

# ---------------------------------------------------------------------------
# 5. Python virtual environment + dependencies
# ---------------------------------------------------------------------------
echo "[r2d2] Creating Python venv at $VENV..."

python3 -m venv "$VENV"
"$VENV/bin/pip" install --upgrade pip -q

# Skip opencv-python-headless if system opencv is already installed
if python3 -c "import cv2" &>/dev/null 2>&1; then
    echo "[r2d2] System OpenCV found — skipping opencv-python-headless"
    grep -v '^opencv' "$R2D2_DIR/python/requirements.txt" \
        | "$VENV/bin/pip" install -q -r /dev/stdin
else
    echo "[r2d2] No system OpenCV — installing opencv-python-headless via pip"
    "$VENV/bin/pip" install -q -r "$R2D2_DIR/python/requirements.txt"
fi

# BLE provisioning (Improv WiFi)
"$VENV/bin/pip" install -q bless

echo "[r2d2] Python dependencies installed."

# ---------------------------------------------------------------------------
# 6. Ownership
# ---------------------------------------------------------------------------
chown -R "$R2D2_USER:$R2D2_USER" "$R2D2_DIR"
# wifi_manager.py and improv.py run as root (nmcli + systemctl)
chown root:root "$R2D2_DIR/armbian/wifi_manager.py"
chown root:root "$R2D2_DIR/armbian/improv.py"

# ---------------------------------------------------------------------------
# 7. Hostname (r2d2.local via avahi)
# ---------------------------------------------------------------------------
echo "r2d2" > /etc/hostname
grep -q "r2d2" /etc/hosts \
    || echo "127.0.1.1	r2d2" >> /etc/hosts

# ---------------------------------------------------------------------------
# 8. Enable systemd services
# ---------------------------------------------------------------------------
echo "[r2d2] Enabling systemd services..."
systemctl enable r2d2-portal.service
systemctl enable avahi-daemon.service
systemctl enable bluetooth.service
# r2d2.service is NOT enabled — started by wifi_manager.py at runtime

# ---------------------------------------------------------------------------
# 9. Serial port — ensure ttyS2 is not grabbed by getty
# ---------------------------------------------------------------------------
systemctl disable serial-getty@ttyS2.service 2>/dev/null || true

echo "[r2d2] Customization complete."
