#!/usr/bin/env python3
"""Unit tests for Mesa PanVK packaging and ELF validation tool."""

import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location(
    "package", Path(__file__).resolve().parents[1] / "mesa" / "package.py"
)
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


def make_mock_elf(
    class_byte: int = 2,
    endian_byte: int = 1,
    machine_id: int = 183,
    libc_bytes: bytes = b"libc.so.6\x00",
    extra: bytes = b"",
) -> bytes:
    """Creates a mock ELF header for testing."""
    elf = bytearray(64)
    elf[:4] = b"\x7fELF"
    elf[4] = class_byte
    elf[5] = endian_byte
    elf[6] = 1  # version
    struct.pack_into("<H", elf, 18, machine_id)
    return bytes(elf) + libc_bytes + extra


class MesaPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.valid_elf = make_mock_elf()

    def test_validate_elf_accepts_valid_aarch64_glibc_binary(self):
        # Should not raise any exception
        package.validate_elf(self.valid_elf, platform="linux")

    def test_validate_elf_rejects_truncated_data(self):
        with self.assertRaisesRegex(ValueError, "too small"):
            package.validate_elf(b"\x7fELF", platform="linux")

    def test_validate_elf_rejects_invalid_magic(self):
        bad_magic = b"NOPE" + self.valid_elf[4:]
        with self.assertRaisesRegex(ValueError, "invalid ELF magic"):
            package.validate_elf(bad_magic, platform="linux")

    def test_validate_elf_rejects_32bit_class(self):
        elf_32 = make_mock_elf(class_byte=1)
        with self.assertRaisesRegex(ValueError, "expected ELFCLASS64"):
            package.validate_elf(elf_32, platform="linux")

    def test_validate_elf_rejects_big_endian(self):
        elf_be = make_mock_elf(endian_byte=2)
        with self.assertRaisesRegex(ValueError, "expected little-endian"):
            package.validate_elf(elf_be, platform="linux")

    def test_validate_elf_rejects_wrong_machine(self):
        # Machine 62 = x86_64, 40 = ARM32
        for bad_machine in (62, 40, 3):
            with self.subTest(machine=bad_machine):
                elf_wrong_mach = make_mock_elf(machine_id=bad_machine)
                with self.assertRaisesRegex(ValueError, "expected AArch64"):
                    package.validate_elf(elf_wrong_mach, platform="linux")

    def test_validate_elf_rejects_missing_glibc(self):
        elf_no_glibc = make_mock_elf(libc_bytes=b"somelib.so\x00")
        with self.assertRaisesRegex(ValueError, "expected glibc libc.so.6"):
            package.validate_elf(elf_no_glibc, platform="linux")

    def test_validate_elf_rejects_bionic_android_binary(self):
        elf_bionic = make_mock_elf(extra=b"/system/bin/linker64\x00")
        with self.assertRaisesRegex(ValueError, "Android Bionic library"):
            package.validate_elf(elf_bionic, platform="linux")

    def test_generate_meta_supports_multiple_gpu_targets(self):
        targets = {
            "csf": "PanVK Mali-G615 (CSF)",
            "mali-g615": "PanVK Mali-G615 (CSF)",
            "g615": "PanVK Mali-G615 (CSF)",
            "valhall": "PanVK Mali Valhall",
            "bifrost": "PanVK Mali Bifrost",
            "all": "PanVK Mali Unified",
            "custom-gpu": "PanVK Mali Driver (custom-gpu)",
        }
        for target, expected_name in targets.items():
            with self.subTest(target=target):
                meta = package.generate_meta(target=target, version="24.3.4", library_name="libvulkan_panfrost.so")
                self.assertEqual(meta["name"], expected_name)
                self.assertEqual(meta["packageVersion"], "24.3.4")
                self.assertEqual(meta["driverVersion"], "24.3.4")
                self.assertEqual(meta["libraryName"], "libvulkan_panfrost.so")
                self.assertEqual(meta["minApi"], 28)
                self.assertEqual(meta["schemaVersion"], 1)

    def test_generate_icd_structure(self):
        icd = package.generate_icd("./libvulkan_panfrost.so")
        self.assertEqual(icd["file_format_version"], "1.0.0")
        self.assertEqual(icd["ICD"]["library_path"], "./libvulkan_panfrost.so")
        self.assertEqual(icd["ICD"]["api_version"], "1.3.0")

    def test_write_deterministic_zip_is_reproducible_and_safe(self):
        entries = {
            "libvulkan_panfrost.so": self.valid_elf,
            "meta.json": b'{"name": "test"}',
        }
        out1 = self.root / "test1.zip"
        out2 = self.root / "test2.zip"
        package.write_deterministic_zip(out1, entries)
        package.write_deterministic_zip(out2, entries)

        # Both runs must produce bit-for-bit identical hashes
        self.assertEqual(hashlib.sha256(out1.read_bytes()).hexdigest(), hashlib.sha256(out2.read_bytes()).hexdigest())

        # Check zip traversal security
        for bad_path in ("../escape.so", "/abs/path.so", "sub\\path.so"):
            with self.subTest(path=bad_path):
                bad_entries = {bad_path: b"evil"}
                with self.assertRaisesRegex(ValueError, "Unsafe zip entry path"):
                    package.write_deterministic_zip(self.root / "bad.zip", bad_entries)

    def test_write_tzst_archive_creates_archive_and_sha256(self):
        entries = {
            "usr/lib/libvulkan_panfrost.so": self.valid_elf,
            "meta.json": b'{"name": "test"}',
        }
        tzst_path = self.root / "driver.tzst"
        package.write_tzst_archive(tzst_path, entries)

        self.assertTrue(tzst_path.is_file())
        sha_path = self.root / "driver.tzst.sha256"
        self.assertTrue(sha_path.is_file())

        expected_hash = hashlib.sha256(tzst_path.read_bytes()).hexdigest()
        self.assertIn(expected_hash, sha_path.read_text())

    def test_package_driver_end_to_end(self):
        out_dir = self.root / "out"
        zip_path, tzst_path = package.package_driver(
            target="csf",
            version="24.3.4",
            library_bytes=self.valid_elf,
            library_name="libvulkan_panfrost.so",
            out_dir=out_dir,
        )

        self.assertEqual(zip_path.name, "panvk-csf-24.3.4.zip")
        self.assertEqual(tzst_path.name, "panvk-csf-24.3.4.tzst")
        self.assertTrue(zip_path.exists())
        self.assertTrue(tzst_path.exists())

        # Verify zip contents for DroidDeck
        with zipfile.ZipFile(zip_path, "r") as zf:
            names = zf.namelist()
            self.assertIn("libvulkan_panfrost.so", names)
            self.assertIn("icd.json", names)
            self.assertIn("panfrost_icd.aarch64.json", names)
            self.assertIn("meta.json", names)
            self.assertIn("README.md", names)

            # Check meta.json schema and fields
            meta = json.loads(zf.read("meta.json").decode("utf-8"))
            self.assertEqual(meta["name"], "PanVK Mali-G615 (CSF)")
            self.assertEqual(meta["packageVersion"], "24.3.4")
            self.assertEqual(meta["libraryName"], "libvulkan_panfrost.so")

            # Check icd.json
            icd = json.loads(zf.read("icd.json").decode("utf-8"))
            self.assertEqual(icd["ICD"]["library_path"], "./libvulkan_panfrost.so")

    def test_locate_built_library(self):
        build_dir = self.root / "build"
        lib_dir = build_dir / "src" / "panfrost" / "vulkan"
        lib_dir.mkdir(parents=True)
        lib_file = lib_dir / "libvulkan_panfrost.so"
        lib_file.write_bytes(self.valid_elf)

        located, manifest = package.locate_built_library(build_dir, "panfrost")
        self.assertEqual(located, lib_file)
        self.assertEqual(manifest, "panfrost_icd.aarch64.json")

    def test_locate_built_library_missing_raises_error(self):
        empty_dir = self.root / "empty_build"
        empty_dir.mkdir()
        with self.assertRaises(FileNotFoundError):
            package.locate_built_library(empty_dir, "panfrost")


if __name__ == "__main__":
    unittest.main()
