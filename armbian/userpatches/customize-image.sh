#!/bin/bash
# =============================================================================
# Armbian customize-image.sh
# Runs INSIDE the ARM64 chroot during image build.
# Internet is available here (fetches packages from Debian mirrors).
#
# Environment provided by Armbian build system:
#   $RELEASE, $BOARD, $BRANCH, $LINUXFAMILY
#   $SDCARD  — path to the rootfs being built (host-side path)
# =============================================================================

echo "[r2d2] Starting R2D2 customization for $BOARD / $RELEASE"

set -euo pipefail

R2D2_DIR="/opt/r2d2"
R2D2_USER="r2d2"
VENV="$R2D2_DIR/venv"

# ---------------------------------------------------------------------------
# 1. System packages
# ---------------------------------------------------------------------------
echo "[r2d2] Installing system packages..."

export DEBIAN_FRONTEND=noninteractive

apt-get update -qq

# Core
apt-get install -y --no-install-recommends \
    python3 python3-pip python3-venv python3-dev \
    network-manager \
    git curl rsync \
    avahi-daemon \
    build-essential

# OpenCV — prefer system package (avoids heavy pip compile on ARM)
apt-get install -y --no-install-recommends \
    libopencv-dev python3-opencv \
    v4l-utils || echo "[r2d2] WARN: opencv/v4l2 not available, will use pip"

# Media / audio
apt-get install -y --no-install-recommends \
    ffmpeg alsa-utils mpg123 libportaudio2 || true

# Julius ASR (optional, may not be in Bookworm repo)
apt-get install -y --no-install-recommends julius julius-dev 2>/dev/null \
    || echo "[r2d2] INFO: Julius not in apt — skipping (install from source if needed)"

# Dnsmasq (used by NM for hotspot shared mode)
apt-get install -y --no-install-recommends dnsmasq-base

echo "[r2d2] System packages installed."

# ---------------------------------------------------------------------------
# 2. Create r2d2 system user
# ---------------------------------------------------------------------------
echo "[r2d2] Creating system user: $R2D2_USER"
if ! id "$R2D2_USER" &>/dev/null; then
    useradd -r -s /usr/sbin/nologin -G dialout,video,audio "$R2D2_USER"
fi

# ---------------------------------------------------------------------------
# 3. Python virtual environment + dependencies
# ---------------------------------------------------------------------------
echo "[r2d2] Creating Python venv at $VENV..."

python3 -m venv "$VENV"
"$VENV/bin/pip" install --upgrade pip -q

# Install with pip (opencv-python-headless skipped if system opencv is present)
if python3 -c "import cv2" &>/dev/null 2>&1; then
    echo "[r2d2] System OpenCV found — installing without opencv-python-headless"
    grep -v '^opencv' "$R2D2_DIR/python/requirements.txt" \
        | "$VENV/bin/pip" install -q -r /dev/stdin
else
    echo "[r2d2] No system OpenCV — installing opencv-python-headless via pip"
    "$VENV/bin/pip" install -q -r "$R2D2_DIR/python/requirements.txt"
fi

# Activate venv's OpenCV can see system libopencv if needed
echo "$R2D2_DIR/venv/lib/python3.*/site-packages" | xargs -I{} sh -c \
    'ls /usr/lib/python3/dist-packages/cv2*.so 2>/dev/null | xargs -I@ ln -sf @ {}/' \
    2>/dev/null || true

echo "[r2d2] Python dependencies installed."

# ---------------------------------------------------------------------------
# 4. Ownership
# ---------------------------------------------------------------------------
chown -R "$R2D2_USER:$R2D2_USER" "$R2D2_DIR"
# wifi_manager.py must stay root-owned (uses nmcli + systemctl)
chown root:root "$R2D2_DIR/armbian/wifi_manager.py"

# ---------------------------------------------------------------------------
# 5. Hostname (r2d2.local via avahi)
# ---------------------------------------------------------------------------
echo "r2d2" > /etc/hostname
sed -i 's/127\.0\.1\.1.*/127.0.1.1\tr2d2/' /etc/hosts 2>/dev/null \
    || echo "127.0.1.1	r2d2" >> /etc/hosts

# ---------------------------------------------------------------------------
# 6. Enable systemd services
# ---------------------------------------------------------------------------
# Services and static config were copied via overlay/ before this script ran.
echo "[r2d2] Enabling systemd services..."
systemctl enable r2d2-portal.service
systemctl enable avahi-daemon.service
# r2d2.service is NOT enabled here — started by wifi_manager.py at runtime

# ---------------------------------------------------------------------------
# 7. NetworkManager — use dnsmasq for hotspot DNS (captive portal)
# ---------------------------------------------------------------------------
# /etc/NetworkManager/conf.d/r2d2.conf was already placed by overlay.
# Reload not needed in chroot; NM will pick it up on first boot.

# Ensure dnsmasq-shared.d directory exists for runtime redirect file
mkdir -p /etc/NetworkManager/dnsmasq-shared.d

# ---------------------------------------------------------------------------
# 8. Serial port — ensure ttyS2 is not grabbed by getty
# ---------------------------------------------------------------------------
systemctl disable serial-getty@ttyS2.service 2>/dev/null || true
# udev rule was placed by overlay: /etc/udev/rules.d/99-r2d2-serial.rules

# ---------------------------------------------------------------------------
# 9. First-boot: expand filesystem (Armbian handles this automatically)
# ---------------------------------------------------------------------------

echo "[r2d2] Customization complete."
