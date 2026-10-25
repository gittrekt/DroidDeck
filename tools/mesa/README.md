# Mesa PanVK Build Tool

A modular, containerized build and packaging system for compiling open-source Mesa Vulkan drivers (PanVK for ARM Mali and Freedreno/Turnip for Qualcomm Adreno) targeting DroidDeck's rootless Linux container environment.

---

## Features

- **Isolated & Reproducible:** Builds inside an Arch Linux ARM container (`menci/archlinuxarm:base-devel`) via Docker with rolling-release toolchains.
- **Minimal & Lean:** Configures Meson to compile only the targeted Vulkan ICD and NIR compiler, stripping debug bloat for maximum gaming performance and small bundle size.
- **Dual Output Packaging:** Automatically creates:
  1. `panvk-<target>-<version>.zip`: Ready to import in DroidDeck (`Settings -> GPU drivers -> Import Linux driver...`).
  2. `panvk-<target>-<version>.tzst` & `.sha256`: Suitable for container sysroot overlays and GitHub release assets.
- **Extensible Patch Profiles:** Clean directory structure (`patches/<gpu>/`) allowing community members to contribute and maintain device-specific patches without merge conflicts.

---

## Quick Start

### 1. Default Build (Mali-G615 / Motorola Edge 2025)
Run the host runner script from the repository root:
```bash
tools/mesa/build.sh
```
This builds Mesa 24.3.4 with PanVK CSF, applies `patches/common/` and `patches/mali-g615/`, and outputs packages to `out/`:
- `out/panvk-csf-24.3.4.zip`
- `out/panvk-csf-24.3.4.tzst`
- `out/panvk-csf-24.3.4.tzst.sha256`

### 2. Push & Import onto Device
```bash
# Push zip to the device's Download directory
adb push out/panvk-csf-24.3.4.zip /sdcard/Download/
```
In DroidDeck on your phone:
1. Open **Settings** $\rightarrow$ **GPU drivers**.
2. Tap **Import Linux driver...**
3. Select `panvk-csf-24.3.4.zip` from your Downloads folder.
4. Select the imported PanVK driver and launch your session.

---

## Adding Support for Other Devices / GPUs

The build system is designed to make adding support for other phones, SoCs, and Mali GPU models straightforward.

### Step 1: Identify Your Device's GPU & Architecture
Connect your device over ADB and inspect the GPU device node and kernel driver:
```bash
# Check GPU character device
adb shell ls -l /dev/mali0

# Check active kernel modules
adb shell lsmod | grep -i mali
```
Determine the GPU architecture:
| Family | Architecture | Example GPUs | Meson Target |
|---|---|---|---|
| **Arm 5th Gen (CSF)** | v10 / v11 (Command Stream Frontend) | Mali-G615, Mali-G715, Mali-G720, Mali-G925 | `--target csf` |
| **Valhall** | v9 JM (Job Manager) | Mali-G57, Mali-G77, Mali-G78, Mali-G68, Mali-G710 | `--target valhall` |
| **Bifrost** | v6 / v7 / v8 JM (Job Manager) | Mali-G31, Mali-G51, Mali-G52, Mali-G71, Mali-G72, Mali-G76 | `--target bifrost` |
| **Unified** | All Mali generations | All Mali GPUs | `--target all` |

### Step 2: Create a Patch Subdirectory
Create a new directory under `tools/mesa/patches/` named after the GPU or device:
```bash
mkdir -p tools/mesa/patches/mali-g720
```

Add your sequential `.patch` files to this folder:
```
tools/mesa/patches/mali-g720/
├── 0001-add-g720-model-id.patch
└── 0002-adjust-tiler-heap.patch
```

> **Note:** Common patches that apply to all devices (such as WSI linear swapchain fallbacks or rootless PRoot compatibility fixes) should be placed in `tools/mesa/patches/common/`.

### Step 3: Test the Build Locally
Invoke `tools/mesa/build.sh` targeting your GPU and patch directory:
```bash
# Example: Build for Mali-G720 using common and g720 patches
tools/mesa/build.sh --target csf --patches common,mali-g720
```

You can also override the upstream Mesa tag or pass additional Meson flags:
```bash
# Example: Build bleeding-edge Mesa 25.0 branch with custom patches
tools/mesa/build.sh \
  --version mesa-25.0.0 \
  --target csf \
  --patches common,mali-g720 \
  --extra "-Dpanfrost-archs=v10,v11"
```

### Step 4: Verify the Output Driver Package
Run the automated packaging test suite to ensure the build meets DroidDeck's binary and manifest schema requirements:
```bash
python3 tools/tests/test_mesa_packaging.py
```

Inspect the generated zip package:
```bash
unzip -l out/panvk-*.zip
```
Ensure it contains:
- `libvulkan_panfrost.so` (verified AArch64 little-endian ELF linked to `libc.so.6`)
- `icd.json`
- `meta.json`
- `README.md`

### Step 5: Document & Submit a Pull Request
1. Document the purpose of your patches, the target device/SoC, and any relevant tracking issues in [`tools/mesa/PATCHES.md`](./PATCHES.md).
2. Commit your new patch directory:
   ```bash
   git add tools/mesa/patches/<your-gpu>/ tools/mesa/PATCHES.md
   git commit -m "tools/mesa: add support patches for <your-gpu>"
   ```
3. Open a Pull Request!

---

## Build Options Reference

| CLI Option | Environment Variable | Default | Description |
|---|---|---|---|
| `--target <target>` | `PANVK_TARGET` | `csf` | Target GPU generation (`csf`, `valhall`, `bifrost`, `all`) |
| `--drivers <list>` | `VULKAN_DRIVERS` | `panfrost` | Meson `-Dvulkan-drivers` argument (e.g. `panfrost`, `freedreno`) |
| `--patches <list>` | `MESA_PATCHES` | `auto` | Comma-separated patch subdirectories under `patches/` to apply |
| `--version <tag>` | `MESA_TAG` | `mesa-24.3.4` | Mesa Git release tag or branch to clone |
| `--repo <url>` | `MESA_REPO` | Upstream GitLab | Git repository URL for Mesa source code |
| `--extra <args>` | `EXTRA_MESON_ARGS` | `""` | Arbitrary additional arguments forwarded to `meson setup` |

---

## Building in GitHub Actions (CI)

This repository includes [`.github/workflows/build-mesa.yml`](../../.github/workflows/build-mesa.yml) which executes on ARM64 runners (`ubuntu-24.04-arm`).

To run a build in CI:
1. Navigate to the **Actions** tab on GitHub.
2. Select **Build Mesa PanVK (Mali Vulkan)**.
3. Click **Run workflow** and configure:
   - **Target architecture:** `csf`, `valhall`, `bifrost`, or `all`
   - **Vulkan drivers:** `panfrost`
   - **Patch profiles:** e.g., `common,mali-g615`
   - **Release tag:** (Optional) Provide a tag to automatically publish artifacts to GitHub Releases.
4. Download the compiled zip and tzst packages from the completed workflow run artifacts.

---

## Troubleshooting & Debugging

If the driver crashes or fails to initialize on your device:
1. **Bypass Experimental HW Check:** Some newer Mali CSF chip revisions require an environment override:
   ```bash
   export PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1
   ```
2. **Enable PanVK Tracing:**
   ```bash
   export PANVK_DEBUG=trace,sync,dump
   ```
3. **Inspect Session Log:**
   Check the DroidDeck session log on device or via ADB:
   ```bash
   adb logcat -s DroidDeck:V LinuxVulkanDriver:V
   ```
