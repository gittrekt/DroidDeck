#!/usr/bin/bash
# Runs inside an Arch Linux ARM container (menci/archlinuxarm:base-devel) on an arm64 runner or docker host.
set -euxo pipefail

WORK=/work
cd "$WORK"

# Source default configuration if present
if [ -f tools/mesa/release.env ]; then
  # shellcheck source=/dev/null
  . tools/mesa/release.env
fi

MESA_TAG=${MESA_TAG:-mesa-24.3.4}
MESA_REPO=${MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}
VULKAN_DRIVERS=${VULKAN_DRIVERS:-panfrost}
PANVK_TARGET=${PANVK_TARGET:-csf}
EXTRA_MESON_ARGS=${EXTRA_MESON_ARGS:-}

grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist

pacman -Syu --noconfirm --needed \
  git zstd binutils cmake ninja meson \
  python python-mako python-yaml python-pyparsing \
  bison flex pkgconf \
  libdrm wayland wayland-protocols \
  libx11 libxcb libxrandr libxshmfence

rm -rf mesa-src out
mkdir -p out

echo "Cloning Mesa ${MESA_TAG} from ${MESA_REPO}..."
git clone -q --depth 1 --branch "$MESA_TAG" "$MESA_REPO" mesa-src

# Determine patch profiles to apply (defaults to common + target GPU)
if [ -z "${MESA_PATCHES:-}" ]; then
  case "$PANVK_TARGET" in
    csf|mali-g615|g615)
      MESA_PATCHES="common,mali-g615,csf"
      ;;
    *)
      MESA_PATCHES="common,${PANVK_TARGET}"
      ;;
  esac
fi

# 1. Apply top-level fallback patches if any exist
if compgen -G "tools/mesa/patches/*.patch" > /dev/null; then
  for p in tools/mesa/patches/*.patch; do
    echo "Applying top-level patch: $(basename "$p")"
    patch -d mesa-src -p1 --no-backup-if-mismatch < "$p"
  done
fi

# 2. Apply structured patches from tools/mesa/patches/{gpu or common indicator}
IFS=',' read -ra PROFILES <<< "$MESA_PATCHES"
for profile in "${PROFILES[@]}"; do
  profile_clean=$(echo "$profile" | tr -d ' ')
  profile_dir="tools/mesa/patches/${profile_clean}"
  if [ -d "$profile_dir" ]; then
    if compgen -G "${profile_dir}/*.patch" > /dev/null; then
      echo "=== Applying patches for [${profile_clean}] ==="
      for p in "${profile_dir}"/*.patch; do
        echo "  Applying: $(basename "$p")"
        patch -d mesa-src -p1 --no-backup-if-mismatch < "$p"
      done
    fi
  fi
done

echo "Configuring Mesa build for Vulkan drivers: ${VULKAN_DRIVERS} (Target: ${PANVK_TARGET})..."
# Word splitting for EXTRA_MESON_ARGS is intentional
# shellcheck disable=SC2086
meson setup mesa-src/build mesa-src \
  --prefix=/usr \
  --buildtype=release \
  --strip \
  -Db_ndebug=true \
  -Dvulkan-drivers="$VULKAN_DRIVERS" \
  -Dgallium-drivers= \
  -Dplatforms=wayland,x11 \
  -Dglx=disabled \
  -Degl=disabled \
  -Dgbm=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dshared-glapi=disabled \
  -Dtools= \
  $EXTRA_MESON_ARGS

echo "Compiling Mesa..."
ninja -C mesa-src/build

echo "Packaging driver bundle..."
VERSION_NUM="${MESA_TAG#mesa-}"
python3 tools/mesa/package.py \
  --target "$PANVK_TARGET" \
  --version "$VERSION_NUM" \
  --drivers "$VULKAN_DRIVERS" \
  --build-dir mesa-src/build \
  --out-dir out

echo "Build and packaging completed successfully:"
ls -lh out/panvk-*
