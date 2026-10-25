#!/usr/bin/env bash
# Host runner script to build Mesa PanVK drivers inside an Arch Linux ARM container.
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)

# Load defaults from release.env
if [[ -f "${repo_root}/tools/mesa/release.env" ]]; then
  # shellcheck source=/dev/null
  . "${repo_root}/tools/mesa/release.env"
fi

MESA_TAG=${MESA_TAG:-mesa-24.3.4}
MESA_REPO=${MESA_REPO:-https://gitlab.freedesktop.org/mesa/mesa.git}
VULKAN_DRIVERS=${VULKAN_DRIVERS:-panfrost}
PANVK_TARGET=${PANVK_TARGET:-csf}
MESA_PATCHES=${MESA_PATCHES:-}
EXTRA_MESON_ARGS=${EXTRA_MESON_ARGS:-}

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Builds Mesa PanVK Vulkan drivers for DroidDeck inside an Arch Linux ARM Docker container.

Options:
  --target <target>     GPU generation target (csf, valhall, bifrost, all) [default: ${PANVK_TARGET}]
  --drivers <drivers>   Meson vulkan-drivers value (e.g. panfrost, freedreno) [default: ${VULKAN_DRIVERS}]
  --patches <list>      Comma-separated patch subdirectories under tools/mesa/patches (e.g. common,mali-g615)
  --version <tag>       Mesa git tag or branch to clone [default: ${MESA_TAG}]
  --repo <url>          Mesa git repository URL [default: ${MESA_REPO}]
  --extra <args>        Extra Meson setup arguments [default: "${EXTRA_MESON_ARGS}"]
  -h, --help            Show this help message

Environment variables:
  PANVK_TARGET          Same as --target
  VULKAN_DRIVERS        Same as --drivers
  MESA_PATCHES          Same as --patches
  MESA_TAG              Same as --version
  MESA_REPO             Same as --repo
  EXTRA_MESON_ARGS      Same as --extra

Examples:
  # Default: build PanVK for Mali-G615 (CSF) with common + mali-g615 patches
  $(basename "$0")

  # Build for Valhall with custom patch sets:
  $(basename "$0") --target valhall --patches common,valhall

  # Build with specific device patches:
  MESA_PATCHES=common,mali-g615 $(basename "$0")
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      PANVK_TARGET="$2"
      shift 2
      ;;
    --drivers)
      VULKAN_DRIVERS="$2"
      shift 2
      ;;
    --patches)
      MESA_PATCHES="$2"
      shift 2
      ;;
    --version)
      MESA_TAG="$2"
      shift 2
      ;;
    --repo)
      MESA_REPO="$2"
      shift 2
      ;;
    --extra)
      EXTRA_MESON_ARGS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      ;;
  esac
done

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is required to build Mesa PanVK drivers." >&2
  exit 1
fi

echo "=== Mesa PanVK Build Configuration ==="
echo "  Repository:     ${MESA_REPO}"
echo "  Version/Tag:    ${MESA_TAG}"
echo "  Vulkan Drivers: ${VULKAN_DRIVERS}"
echo "  PanVK Target:   ${PANVK_TARGET}"
echo "  Patch Profiles: ${MESA_PATCHES:-auto (common,${PANVK_TARGET})}"
echo "  Extra Meson:    ${EXTRA_MESON_ARGS}"
echo "======================================"

DOCKER_IMAGE=${DROIDDECK_ARCH_IMAGE:-menci/archlinuxarm:base-devel}

docker run --rm \
  -e MESA_TAG="${MESA_TAG}" \
  -e MESA_REPO="${MESA_REPO}" \
  -e VULKAN_DRIVERS="${VULKAN_DRIVERS}" \
  -e PANVK_TARGET="${PANVK_TARGET}" \
  -e MESA_PATCHES="${MESA_PATCHES}" \
  -e EXTRA_MESON_ARGS="${EXTRA_MESON_ARGS}" \
  -v "${repo_root}:/work" \
  -w /work \
  "${DOCKER_IMAGE}" \
  bash tools/mesa/build-in-arch.sh

if [[ -d "${repo_root}/out" ]]; then
  # Restore permissions if run on Linux host
  if command -v id >/dev/null 2>&1; then
    docker run --rm -v "${repo_root}:/work" -w /work "${DOCKER_IMAGE}" chown -R "$(id -u):$(id -g)" out mesa-src 2>/dev/null || true
  fi
fi

echo "Build complete. Artifacts located in ${repo_root}/out/"
