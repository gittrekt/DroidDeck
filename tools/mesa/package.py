#!/usr/bin/env python3
"""Validates and packages Mesa Vulkan drivers (PanVK) for DroidDeck."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
import zipfile

DEFAULT_TARGET = "csf"
DEFAULT_DRIVERS = "panfrost"
FIXED_TIMESTAMP = (2026, 1, 1, 0, 0, 0)


def validate_elf(data: bytes, platform: str = "linux") -> None:
    """Validates that data represents an AArch64 little-endian ELF64 binary."""
    if len(data) < 64:
        raise ValueError(f"{platform}: library file too small for ELF header ({len(data)} bytes)")
    if data[:4] != b"\x7fELF":
        raise ValueError(f"{platform}: invalid ELF magic")
    if data[4] != 2:
        raise ValueError(f"{platform}: expected ELFCLASS64 (64-bit), got class {data[4]}")
    if data[5] != 1:
        raise ValueError(f"{platform}: expected little-endian (ELFDATA2LSB), got {data[5]}")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 183:
        raise ValueError(f"{platform}: expected AArch64 (machine 183), got machine {machine}")

    if platform == "linux":
        if b"libc.so.6\x00" not in data:
            raise ValueError(f"{platform}: expected glibc libc.so.6 reference in library")
        if b"/system/bin/linker64\x00" in data:
            raise ValueError(f"{platform}: binary appears to be an Android Bionic library, not glibc")


def generate_meta(target: str, version: str, library_name: str) -> dict:
    """Generates DroidDeck driver metadata dictionary."""
    target_clean = target.lower().strip()
    target_info = {
        "csf": (
            "PanVK Mali-G615 (CSF)",
            "PanVK Mesa Vulkan driver for Arm 5th Gen (CSF v10+) Mali-G615/G715/G720",
        ),
        "mali-g615": (
            "PanVK Mali-G615 (CSF)",
            "PanVK Mesa Vulkan driver for Arm 5th Gen (CSF v10+) Mali-G615/G715/G720",
        ),
        "g615": (
            "PanVK Mali-G615 (CSF)",
            "PanVK Mesa Vulkan driver for Arm 5th Gen (CSF v10+) Mali-G615/G715/G720",
        ),
        "valhall": (
            "PanVK Mali Valhall",
            "PanVK Mesa Vulkan driver for Arm Mali Valhall (v9 JM) GPUs (G57/G77/G78)",
        ),
        "bifrost": (
            "PanVK Mali Bifrost",
            "PanVK Mesa Vulkan driver for Arm Mali Bifrost GPUs (G31/G51/G52/G71/G72/G76)",
        ),
        "all": (
            "PanVK Mali Unified",
            "PanVK Mesa Vulkan driver for Arm Mali GPUs (Unified)",
        ),
    }

    name, desc = target_info.get(
        target_clean,
        (f"PanVK Mali Driver ({target})", f"PanVK Mesa Vulkan driver for {target}"),
    )

    return {
        "schemaVersion": 1,
        "name": name,
        "description": desc,
        "author": "Mesa / DroidDeck",
        "packageVersion": version,
        "vendor": "Mesa",
        "driverVersion": version,
        "minApi": 28,
        "libraryName": library_name,
        "target": target_clean,
    }


def generate_icd(library_path: str = "./libvulkan_panfrost.so") -> dict:
    """Generates standard Vulkan ICD manifest."""
    return {
        "file_format_version": "1.0.0",
        "ICD": {
            "library_path": library_path,
            "api_version": "1.3.0",
        },
    }


def generate_readme(meta: dict, target: str) -> str:
    """Generates user-facing README included in the driver archive."""
    return f"""# {meta['name']}

Mesa PanVK Vulkan Driver for DroidDeck Linux Container.
Version: {meta['packageVersion']}
Target: {target}
Library: {meta['libraryName']}

## Notes
- Built for ARM64 Linux (glibc libc.so.6).
- If your device GPU is experimental upstream, you may need to export:
    PAN_I_WANT_A_BROKEN_VULKAN_DRIVER=1
- In DroidDeck, import this zip via Settings -> GPU drivers -> Import Linux driver...
"""


def write_deterministic_zip(output_path: Path, entries: dict) -> None:
    """Writes files to a zip archive with deterministic metadata and safe paths."""
    for name in entries.keys():
        if ".." in name or name.startswith("/") or "\\" in name:
            raise ValueError(f"Unsafe zip entry path: {name}")

    parent = output_path.parent
    parent.mkdir(parents=True, exist_ok=True)

    temp_file = tempfile.NamedTemporaryFile(
        dir=parent, prefix=f".{output_path.name}.", delete=False
    )
    temp_path = Path(temp_file.name)
    temp_file.close()

    try:
        with zipfile.ZipFile(temp_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for name in sorted(entries.keys()):
                data = entries[name]
                if isinstance(data, str):
                    data = data.encode("utf-8")
                zinfo = zipfile.ZipInfo(filename=name, date_time=FIXED_TIMESTAMP)
                zinfo.external_attr = (stat.S_IFREG | 0o644) << 16
                zf.writestr(zinfo, data)
        temp_path.replace(output_path)
    except Exception:
        if temp_path.exists():
            temp_path.unlink()
        raise


def write_tzst_archive(output_path: Path, entries: dict) -> None:
    """Creates a .tar archive and compresses it using zstd."""
    import tarfile
    import time

    parent = output_path.parent
    parent.mkdir(parents=True, exist_ok=True)

    temp_tar = tempfile.NamedTemporaryFile(
        dir=parent, prefix=f".{output_path.stem}.tar.", delete=False
    )
    tar_path = Path(temp_tar.name)
    temp_tar.close()

    fixed_epoch = int(time.mktime(FIXED_TIMESTAMP + (0, 0, -1)))

    try:
        with tarfile.open(tar_path, "w") as tf:
            for name in sorted(entries.keys()):
                if ".." in name or name.startswith("/") or "\\" in name:
                    raise ValueError(f"Unsafe tar entry path: {name}")
                data = entries[name]
                if isinstance(data, str):
                    data = data.encode("utf-8")
                ti = tarfile.TarInfo(name=name)
                ti.size = len(data)
                ti.mtime = fixed_epoch
                ti.mode = 0o644
                ti.uid = 0
                ti.gid = 0
                ti.uname = "root"
                ti.gname = "root"
                import io
                tf.addfile(ti, io.BytesIO(data))

        temp_zst = tempfile.NamedTemporaryFile(
            dir=parent, prefix=f".{output_path.name}.", delete=False
        )
        zst_path = Path(temp_zst.name)
        temp_zst.close()

        # Compress tar using zstd CLI
        subprocess.run(
            ["zstd", "-f", "-q", "-19", str(tar_path), "-o", str(zst_path)],
            check=True,
        )
        zst_path.replace(output_path)

        # Write sha256 checksum
        sha256 = hashlib.sha256(output_path.read_bytes()).hexdigest()
        sha_path = output_path.with_name(f"{output_path.name}.sha256")
        sha_path.write_text(f"{sha256}  {output_path.name}\n", encoding="utf-8")
    finally:
        if tar_path.exists():
            tar_path.unlink()


def package_driver(
    target: str,
    version: str,
    library_bytes: bytes,
    library_name: str,
    out_dir: Path,
    manifest_name: str = "panfrost_icd.aarch64.json",
) -> tuple:
    """Validates and packages driver into .zip and .tzst."""
    validate_elf(library_bytes, platform="linux")

    meta = generate_meta(target=target, version=version, library_name=library_name)
    meta_bytes = json.dumps(meta, indent=2).encode("utf-8") + b"\n"

    icd = generate_icd(library_path=f"./{library_name}")
    icd_bytes = json.dumps(icd, indent=2).encode("utf-8") + b"\n"

    readme_bytes = generate_readme(meta, target).encode("utf-8")

    # 1. Zip package for DroidDeck UI import
    zip_entries = {
        library_name: library_bytes,
        "icd.json": icd_bytes,
        manifest_name: icd_bytes,
        "meta.json": meta_bytes,
        "README.md": readme_bytes,
    }

    target_suffix = target.lower().strip()
    zip_file = out_dir / f"panvk-{target_suffix}-{version}.zip"
    write_deterministic_zip(zip_file, zip_entries)

    # 2. Tar.zst package for release assets / container sysroot
    container_icd = generate_icd(library_path=f"/usr/lib/{library_name}")
    container_icd_bytes = json.dumps(container_icd, indent=2).encode("utf-8") + b"\n"

    tzst_entries = {
        f"usr/lib/{library_name}": library_bytes,
        f"usr/share/vulkan/icd.d/{manifest_name}": container_icd_bytes,
        "meta.json": meta_bytes,
        "README.md": readme_bytes,
    }

    tzst_file = out_dir / f"panvk-{target_suffix}-{version}.tzst"
    write_tzst_archive(tzst_file, tzst_entries)

    return zip_file, tzst_file


def locate_built_library(build_dir: Path, driver: str) -> tuple:
    """Locates the built Vulkan ICD shared library and manifest within the Meson build directory."""
    candidates = [
        (
            build_dir / "src" / driver / "vulkan" / f"libvulkan_{driver}.so",
            f"{driver}_icd.aarch64.json",
        ),
        (
            build_dir / "src" / "panfrost" / "vulkan" / "libvulkan_panfrost.so",
            "panfrost_icd.aarch64.json",
        ),
    ]

    for lib_path, manifest in candidates:
        if lib_path.is_file():
            return lib_path, manifest

    # Fallback search in build tree
    for p in build_dir.glob("**/libvulkan_*.so"):
        if p.is_file() and not p.is_symlink():
            name = p.stem.replace("libvulkan_", "")
            return p, f"{name}_icd.aarch64.json"

    raise FileNotFoundError(f"Could not locate built libvulkan_{driver}.so in {build_dir}")


def main():
    parser = argparse.ArgumentParser(description="Package Mesa PanVK Vulkan driver for DroidDeck")
    parser.add_argument("--target", default=DEFAULT_TARGET, help="Architecture target: csf, valhall, bifrost, all")
    parser.add_argument("--version", required=True, help="Driver version string (e.g. 24.3.4)")
    parser.add_argument("--drivers", default=DEFAULT_DRIVERS, help="Built driver names (e.g. panfrost)")
    parser.add_argument("--build-dir", type=Path, default=Path("build"), help="Meson build directory")
    parser.add_argument("--out-dir", type=Path, default=Path("out"), help="Output directory")
    parser.add_argument("--library-file", type=Path, default=None, help="Direct path to built .so")

    args = parser.parse_args()

    out_dir = args.out_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.library_file:
        lib_path = args.library_file.resolve()
        manifest_name = f"{args.drivers.split(',')[0]}_icd.aarch64.json"
    else:
        lib_path, manifest_name = locate_built_library(args.build_dir.resolve(), args.drivers.split(",")[0])

    print(f"Reading built library: {lib_path}")
    lib_bytes = lib_path.read_bytes()
    lib_name = lib_path.name

    print(f"Packaging PanVK for target={args.target}, version={args.version}...")
    zip_path, tzst_path = package_driver(
        target=args.target,
        version=args.version,
        library_bytes=lib_bytes,
        library_name=lib_name,
        out_dir=out_dir,
        manifest_name=manifest_name,
    )

    print(f"Successfully created:")
    print(f"  Zip package:  {zip_path} ({zip_path.stat().st_size} bytes)")
    print(f"  Tzst package: {tzst_path} ({tzst_path.stat().st_size} bytes)")
    print(f"  SHA256:       {tzst_path.with_name(tzst_path.name + '.sha256')}")


if __name__ == "__main__":
    main()
