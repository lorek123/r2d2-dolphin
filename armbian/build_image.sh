#!/usr/bin/env bash
# =============================================================================
# R2D2 Armbian Image Builder
# =============================================================================
# Runs on your x86-64 PC (Ubuntu 22.04 / Debian 12 recommended).
# Produces a ready-to-flash .img with all packages and code pre-installed.
# Internet is used HERE (on the build machine), not on the device.
#
# Requirements:
#   sudo apt install git dialog u-boot-tools
#   docker  (recommended, avoids host toolchain requirements)
#        OR native Ubuntu 22.04 host
#
# Usage:
#   bash armbian/build_image.sh [--no-docker]
# =============================================================================
set -euo pipefail

BOARD="orangepizeroplus2-h5"
RELEASE="bookworm"          # Debian 12
BRANCH="current"            # latest mainline kernel
BUILD_MINIMAL="yes"
BUILD_DESKTOP="no"
KERNEL_CONFIGURE="no"
COMPRESS="sha,xz"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$REPO_DIR/.armbian-build"
USERPATCHES_SRC="$REPO_DIR/armbian/userpatches"
USERPATCHES_DST="$BUILD_DIR/userpatches"
OVERLAY_DST="$USERPATCHES_DST/overlay"

USE_DOCKER=yes
[[ "${1:-}" == "--no-docker" ]] && USE_DOCKER=no

info()  { echo -e "\033[1;34m[INFO]\033[0m  $*"; }
ok()    { echo -e "\033[1;32m[ OK ]\033[0m  $*"; }
warn()  { echo -e "\033[1;33m[WARN]\033[0m  $*"; }
die()   { echo -e "\033[1;31m[FAIL]\033[0m  $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Clone / update Armbian build system
# ---------------------------------------------------------------------------
if [[ ! -d "$BUILD_DIR/.git" ]]; then
    info "Cloning Armbian build system → $BUILD_DIR"
    git clone --depth=1 https://github.com/armbian/build.git "$BUILD_DIR"
else
    info "Updating Armbian build system"
    git -C "$BUILD_DIR" pull --ff-only || warn "git pull failed — using existing checkout"
fi

# ---------------------------------------------------------------------------
# 2. Prepare userpatches directory
# ---------------------------------------------------------------------------
info "Preparing userpatches..."
mkdir -p "$USERPATCHES_DST"

# Copy our customize-image.sh
cp "$USERPATCHES_SRC/customize-image.sh" "$USERPATCHES_DST/customize-image.sh"
chmod +x "$USERPATCHES_DST/customize-image.sh"

# ---------------------------------------------------------------------------
# 3. Build the overlay  (files that will be copied verbatim into the rootfs)
# ---------------------------------------------------------------------------
info "Building overlay..."

# Static overlay files (from userpatches/overlay/ in our repo)
rsync -a "$USERPATCHES_SRC/overlay/" "$OVERLAY_DST/"

# R2D2 application code → /opt/r2d2/
R2D2_OVERLAY="$OVERLAY_DST/opt/r2d2"
mkdir -p "$R2D2_OVERLAY"

rsync -a \
    --exclude='.git' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='.armbian-build' \
    --exclude='jadx_gradle' \
    --exclude='resources' \
    "$REPO_DIR/" "$R2D2_OVERLAY/"

# Resources (sounds etc.) — large, copy separately
if [[ -d "$REPO_DIR/resources" ]]; then
    info "Copying resources (sounds, cascades)..."
    rsync -a --exclude='*.apk' "$REPO_DIR/resources/" "$R2D2_OVERLAY/resources/"
fi

# Systemd services
mkdir -p "$OVERLAY_DST/etc/systemd/system"
cp "$REPO_DIR/armbian/systemd/r2d2.service"        "$OVERLAY_DST/etc/systemd/system/"
cp "$REPO_DIR/armbian/systemd/r2d2-portal.service" "$OVERLAY_DST/etc/systemd/system/"

ok "Overlay ready at $OVERLAY_DST"

# ---------------------------------------------------------------------------
# 4. Run Armbian compile.sh
# ---------------------------------------------------------------------------
info "Starting Armbian build for $BOARD ($RELEASE / $BRANCH)..."
info "This will take 20-60 minutes on first run (kernel + rootfs compilation)."
info "Subsequent builds are much faster due to caching."
echo ""

COMPILE_ARGS=(
    "BOARD=$BOARD"
    "RELEASE=$RELEASE"
    "BRANCH=$BRANCH"
    "BUILD_MINIMAL=$BUILD_MINIMAL"
    "BUILD_DESKTOP=$BUILD_DESKTOP"
    "KERNEL_CONFIGURE=$KERNEL_CONFIGURE"
    "COMPRESS_OUTPUTIMAGE=$COMPRESS"
    "EXPERT=yes"
)
[[ "$USE_DOCKER" == "yes" ]] && COMPILE_ARGS+=("USE_DOCKER=yes")

cd "$BUILD_DIR"
sudo ./compile.sh "${COMPILE_ARGS[@]}"

# ---------------------------------------------------------------------------
# 5. Report output
# ---------------------------------------------------------------------------
echo ""
IMAGE=$(find "$BUILD_DIR/output/images" -name "Armbian_*${BOARD}*.img*" -newer "$BUILD_DIR/compile.sh" 2>/dev/null | head -1)
if [[ -n "$IMAGE" ]]; then
    ok "============================================================"
    ok " Image ready:"
    ok "   $IMAGE"
    ok ""
    ok " Flash with:"
    ok "   sudo dd if=\"$IMAGE\" of=/dev/sdX bs=4M status=progress conv=fsync"
    ok "   (or use Balena Etcher)"
    ok "============================================================"
else
    warn "Could not find output image — check $BUILD_DIR/output/images/"
fi
