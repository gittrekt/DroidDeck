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

## Patch Management
Any patches placed in `tools/mesa/patches/*.patch` are applied in alphabetical order to the Mesa source tree before running `meson setup`.
When adding a patch:
- Prefix the file name with a sequence number, e.g., `0001-mali-g615-csf-quirk.patch`.
- Document the motivation and upstream tracking issue in this file.
