#!/bin/bash
# =============================================================================
# Armbian customize-image.sh
# Runs INSIDE the ARM64 chroot during image build.
# Internet is available here (fetches packages from Debian mirrors).
#
# Environment provided by Armbian build system:
#   $RELEASE, $BOARD, $BRANCH, $LINUXFAMILY
# =============================================================================

echo "[r2d2] Starting R2D2 customization for ${BOARD:-unknown} / ${RELEASE:-unknown}"

set -euo pipefail

R2D2_DIR="/opt/r2d2"
R2D2_USER="r2d2"
VENV="$R2D2_DIR/venv"

# Fail fast with a clear message if the overlay wasn't applied correctly
if [[ ! -f "$R2D2_DIR/python/requirements.txt" ]]; then
    echo "[r2d2] FATAL: $R2D2_DIR/python/requirements.txt not found."
    echo "[r2d2]        The overlay was not applied correctly before this script ran."
    ls "$R2D2_DIR" 2>/dev/null || echo "(directory missing)"
    exit 1
fi

# ---------------------------------------------------------------------------
# 1. System packages
# ---------------------------------------------------------------------------
echo "[r2d2] Installing system packages..."

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq

# Core + NetworkManager (replaces the default systemd-networkd on this board)
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
# 2. NetworkManager takes over from systemd-networkd
#    (orangepizeroplus2-h5 defaults to systemd-networkd in Armbian)
# ---------------------------------------------------------------------------
echo "[r2d2] Configuring NetworkManager as primary network stack..."
systemctl disable systemd-networkd            2>/dev/null || true
systemctl disable systemd-networkd-wait-online 2>/dev/null || true
systemctl enable  NetworkManager              2>/dev/null || true
# NM config (dns=dnsmasq for hotspot) was placed by overlay
mkdir -p /etc/NetworkManager/dnsmasq-shared.d

# ---------------------------------------------------------------------------
# 3. Create r2d2 system user
# ---------------------------------------------------------------------------
echo "[r2d2] Creating system user: $R2D2_USER"
if ! id "$R2D2_USER" &>/dev/null; then
    useradd -r -s /usr/sbin/nologin -G dialout,video,audio,bluetooth "$R2D2_USER"
fi

# ---------------------------------------------------------------------------
# 4. Python virtual environment + dependencies
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
# 5. Ownership
# ---------------------------------------------------------------------------
chown -R "$R2D2_USER:$R2D2_USER" "$R2D2_DIR"
# wifi_manager.py and improv.py run as root (nmcli + systemctl)
chown root:root "$R2D2_DIR/armbian/wifi_manager.py"
chown root:root "$R2D2_DIR/armbian/improv.py"

# ---------------------------------------------------------------------------
# 6. Hostname (r2d2.local via avahi)
# ---------------------------------------------------------------------------
echo "r2d2" > /etc/hostname
grep -q "r2d2" /etc/hosts \
    || echo "127.0.1.1	r2d2" >> /etc/hosts

# ---------------------------------------------------------------------------
# 7. Enable systemd services
# ---------------------------------------------------------------------------
echo "[r2d2] Enabling systemd services..."
# Service files were placed by overlay → /etc/systemd/system/
systemctl enable r2d2-portal.service
systemctl enable avahi-daemon.service
systemctl enable bluetooth.service
# r2d2.service is NOT enabled — started by wifi_manager.py at runtime

# ---------------------------------------------------------------------------
# 8. Serial port — ensure ttyS2 is not grabbed by getty
# ---------------------------------------------------------------------------
systemctl disable serial-getty@ttyS2.service 2>/dev/null || true

echo "[r2d2] Customization complete."
