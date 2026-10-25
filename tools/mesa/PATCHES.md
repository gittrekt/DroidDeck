# Mesa PanVK Drivers & Patches

## Overview
This tool builds Mesa's **PanVK** Vulkan ICD driver (`libvulkan_panfrost.so`) for ARM Mali GPUs in DroidDeck's rootless Linux container environment.

## Target Architectures
Mesa's Panfrost driver supports multiple generations of ARM Mali hardware:

1. **CSF (Command Stream Frontend / Arm 5th Gen):**
   - Architectures: v10 / v11 (e.g., Mali-G615, Mali-G715, Mali-G720).
   - Target Device: Motorola Edge 2025 (*Oulu*, MediaTek Dimensity 7400 Ultra, Mali-G615 MC2).
   - Linux DRM Driver: `panthor` (or legacy CSF kernel drivers).
   - Build Flag: `PANVK_TARGET=csf`

2. **Valhall:**
   - Architecture: v9 JM (e.g., Mali-G57, Mali-G77, Mali-G78, Mali-G68, Mali-G710).
   - Linux DRM Driver: `panfrost`.
   - Build Flag: `PANVK_TARGET=valhall`

3. **Bifrost:**
   - Architecture: v6/v7/v8 JM (e.g., Mali-G31, Mali-G51, Mali-G52, Mali-G71, Mali-G72, Mali-G76).
   - Linux DRM Driver: `panfrost`.
   - Build Flag: `PANVK_TARGET=bifrost`

4. **Unified:**
   - Build Flag: `PANVK_TARGET=all`

## Runtime Environment Overrides
Because PanVK on certain Mali hardware generations is under active development upstream, Mesa may require the following environment variable to bypass initial experimental hardware checks if running on unvalidated chip revisions:
```bash
export PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1
```
For diagnostic debug output, you can set:
```bash
export PANVK_DEBUG=trace,sync
```

## Patch Management: `patches/{gpu or common indicator}`

Patches are organized into subdirectories under `tools/mesa/patches/` by GPU or common indicator to keep device-specific quirks clean, modular, and maintainable.

### Directory Layout
```
tools/mesa/patches/
├── common/                  # Shared patches across all targets (WSI fallbacks, rootless PRoot compatibility)
├── mali-g615/               # Mali-G615 / MT6878 / CSF v10+ specific patches (Motorola Edge 2025)
├── valhall/                 # Mali Valhall v9 JM patches (G57, G77, G78, G68, G710)
├── bifrost/                 # Mali Bifrost JM patches (G31, G51, G52, G71, G72, G76)
└── <custom-gpu>/            # Community device or GPU patch profiles
```

### Resolution & Application Order
1. Any top-level patches in `tools/mesa/patches/*.patch` are applied first.
2. The patch profile list specified by `MESA_PATCHES` (or `--patches`) is traversed in order.
   - For `PANVK_TARGET=csf` or `mali-g615`, the default profile is: `common,mali-g615,csf`.
   - For other targets, the default profile is: `common,${PANVK_TARGET}`.
3. Within each subdirectory, patches are applied in alphabetical order (`0001-*.patch`, `0002-*.patch`).

### Adding Support for a New Device or GPU
1. Create a directory under `tools/mesa/patches/` named after the GPU or device:
   ```bash
   mkdir -p tools/mesa/patches/mali-g720
   ```
2. Add your `.patch` files inside:
   ```bash
   tools/mesa/patches/mali-g720/0001-fix-tiler-heap.patch
   ```
3. Build using the new target or patch profile:
   ```bash
   tools/mesa/build.sh --target csf --patches common,mali-g720
   # or
   PANVK_TARGET=csf MESA_PATCHES=common,mali-g720 tools/mesa/build.sh
   ```
