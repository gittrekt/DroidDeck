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

pacman -Sy --noconfirm --needed \
  git zstd binutils cmake ninja meson \
  python python-mako python-yaml python-pyparsing python-packaging \
  bison flex pkgconf \
  libdrm wayland wayland-protocols \
  libx11 libxcb libxrandr libxshmfence \
  clang libclc llvm spirv-llvm-translator

mkdir -p out

git config --global --add safe.directory '*' || true

CURRENT_REPO=""
if [ -d mesa-src/.git ]; then
  CURRENT_REPO=$(git -C mesa-src config --get remote.origin.url 2>/dev/null || true)
fi

if [ -d mesa-src/.git ] && [ "$CURRENT_REPO" = "$MESA_REPO" ]; then
  echo "Reusing existing mesa-src from ${MESA_REPO}, resetting to ${MESA_TAG}..."
  git -C mesa-src fetch --depth 1 origin "$MESA_TAG" 2>/dev/null || true
  git -C mesa-src checkout -f "$MESA_TAG" 2>/dev/null || git -C mesa-src reset --hard "$MESA_TAG"
  git -C mesa-src clean -fd
else
  echo "Cloning Mesa ${MESA_TAG} from ${MESA_REPO}..."
  rm -rf mesa-src
  git clone --depth 1 --branch "$MESA_TAG" "$MESA_REPO" mesa-src
fi

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
    if patch -d mesa-src -p1 --dry-run < "$p" > /dev/null 2>&1; then
      echo "Applying top-level patch: $(basename "$p")"
      patch -d mesa-src -p1 --no-backup-if-mismatch < "$p"
    else
      echo "Notice: $(basename "$p") does not apply cleanly to this Mesa tree; skipping."
    fi
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
        if patch -d mesa-src -p1 --dry-run < "$p" > /dev/null 2>&1; then
          echo "  Applying: $(basename "$p")"
          patch -d mesa-src -p1 --no-backup-if-mismatch < "$p"
        else
          echo "  Notice: $(basename "$p") does not apply cleanly to this Mesa tree; skipping."
        fi
      done
    fi
  fi
done

echo "Configuring Mesa build for Vulkan drivers: ${VULKAN_DRIVERS} (Target: ${PANVK_TARGET})..."
MESON_OPTS=(
  --prefix=/usr
  --buildtype=release
  --strip
  -Db_ndebug=true
  -Dvulkan-drivers="$VULKAN_DRIVERS"
  -Dgallium-drivers=
  -Dplatforms=wayland,x11
  -Dglx=disabled
  -Degl=disabled
  -Dgbm=disabled
  -Dgles1=disabled
  -Dgles2=disabled
  -Dshared-glapi=disabled
  -Dtools=
)

if [ -f mesa-src/meson.options ] && grep -q "panfrost-kmds" mesa-src/meson.options; then
  MESON_OPTS+=("-Dpanfrost-kmds=panfrost,panthor,kbase")
fi

# Word splitting for EXTRA_MESON_ARGS is intentional
# shellcheck disable=SC2086
if [ -d mesa-src/build/meson-private ]; then
  meson setup --reconfigure "${MESON_OPTS[@]}" $EXTRA_MESON_ARGS mesa-src/build mesa-src || {
    echo "Reconfiguration failed, wiping build dir and retrying fresh setup..."
    rm -rf mesa-src/build
    meson setup "${MESON_OPTS[@]}" $EXTRA_MESON_ARGS mesa-src/build mesa-src
  }
else
  meson setup "${MESON_OPTS[@]}" $EXTRA_MESON_ARGS mesa-src/build mesa-src
fi

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
