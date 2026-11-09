from __future__ import annotations

import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import package_runtime


class RuntimePackagerTests(unittest.TestCase):

    @staticmethod
    def _write_elf64(
        path: Path,
        machine: int,
        interpreter: str | None = None,
        executable: bool = False,
    ) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
        if interpreter is None:
            phoff = 0
            phnum = 0
            data = struct.pack(
                "<16sHHIQQQIHHHHHH",
                ident, 2, machine, 1, 0, phoff, 0, 0, 64, 56, phnum, 0, 0, 0,
            )
        else:
            interpreter_bytes = interpreter.encode("ascii") + b"\x00"
            phoff = 64
            phnum = 1
            interpreter_offset = phoff + 56
            header = struct.pack(
                "<16sHHIQQQIHHHHHH",
                ident, 2, machine, 1, 0, phoff, 0, 0, 64, 56, phnum, 0, 0, 0,
            )
            program_header = struct.pack(
                "<IIQQQQQQ",
                package_runtime.ELF_PT_INTERP,
                4,
                interpreter_offset,
                0,
                0,
                len(interpreter_bytes),
                len(interpreter_bytes),
                1,
            )
            data = header + program_header + interpreter_bytes
        path.write_bytes(data)
        path.chmod(0o755 if executable else 0o644)
        return path

    @staticmethod
    def _write_pe64(path: Path, machine: int = package_runtime.PE_MACHINE_AMD64) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        data = bytearray(0x80 + 6)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x80)
        data[0x80:0x84] = b"PE\x00\x00"
        struct.pack_into("<H", data, 0x84, machine)
        path.write_bytes(data)
        path.chmod(0o644)
        return path

    def _make_glibc_backend(self, root: Path) -> Path:
        backend = root / "backend"
        (backend / "host" / "lib").mkdir(parents=True)
        self._write_elf64(
            backend / "host" / "bin" / "proot",
            package_runtime.ELF_MACHINE_AARCH64,
            "/system/bin/linker64",
            executable=True,
        )
        self._write_elf64(
            backend / "host" / "libexec" / "proot" / "loader",
            package_runtime.ELF_MACHINE_AARCH64,
            executable=True,
        )

        rootfs = backend / "rootfs"
        self._write_elf64(
            rootfs / "lib" / "ld-linux-aarch64.so.1",
            package_runtime.ELF_MACHINE_AARCH64,
        )
        self._write_elf64(
            rootfs / "lib64" / "ld-linux-x86-64.so.2",
            package_runtime.ELF_MACHINE_X86_64,
        )
        self._write_elf64(
            rootfs / "usr" / "bin" / "env",
            package_runtime.ELF_MACHINE_AARCH64,
            "/lib/ld-linux-aarch64.so.1",
            executable=True,
        )
        self._write_elf64(
            rootfs / "usr" / "bin" / "box64",
            package_runtime.ELF_MACHINE_AARCH64,
            "/lib/ld-linux-aarch64.so.1",
            executable=True,
        )
        wine = self._write_elf64(
            rootfs / "usr" / "bin" / "wine",
            package_runtime.ELF_MACHINE_X86_64,
            "/lib64/ld-linux-x86-64.so.2",
            executable=True,
        )
        self._write_pe64(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "x86_64-windows" / "wineboot.exe",
        )
        self._write_elf64(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "x86_64-unix" / "winewayland.so",
            package_runtime.ELF_MACHINE_X86_64,
        )
        self._write_pe64(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "x86_64-windows" / "winewayland.drv",
        )
        return backend

    def test_validates_hybrid_arm64_glibc_and_x64_wine_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_runtime_missing_proot_loader(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            (backend / "host" / "libexec" / "proot" / "loader").unlink()
            with self.assertRaisesRegex(ValueError, "PRoot ELF loader is missing"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_box64_built_for_android_bionic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            rootfs = backend / "rootfs"
            self._write_elf64(
                rootfs / "usr" / "bin" / "box64",
                package_runtime.ELF_MACHINE_AARCH64,
                "/system/bin/linker64",
                executable=True,
            )
            with self.assertRaisesRegex(ValueError, "Box64 guest executable must use interpreter"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_wrong_architecture_for_wine_wayland_unix_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_elf64(
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "x86_64-unix" / "winewayland.so",
                package_runtime.ELF_MACHINE_AARCH64,
            )
            with self.assertRaisesRegex(ValueError, "Wine Wayland Unix driver has the wrong ELF machine"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_resolves_absolute_loader_symlink_inside_guest_rootfs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            rootfs = backend / "rootfs"
            loader_link = rootfs / "lib64" / "ld-linux-x86-64.so.2"
            loader_link.unlink()
            self._write_elf64(
                rootfs / "lib" / "x86_64-linux-gnu" / "ld-2.38.so",
                package_runtime.ELF_MACHINE_X86_64,
            )
            loader_link.symlink_to("/lib/x86_64-linux-gnu/ld-2.38.so")
            package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_non_amd64_wineboot_pe_program(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_pe64(
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "x86_64-windows" / "wineboot.exe",
                machine=0x014C,
            )
            with self.assertRaisesRegex(ValueError, "wineboot PE program must target AMD64"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_non_amd64_wine_wayland_pe_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_pe64(
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "x86_64-windows" / "winewayland.drv",
                machine=0x014C,
            )
            with self.assertRaisesRegex(ValueError, "must target AMD64"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_creates_manifest_and_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            (root / "bin").mkdir(parents=True)
            (root / "lib").mkdir()
            executable = root / "bin" / "wine"
            executable.write_bytes(b"wine-test-binary")
            executable.chmod(0o755)

            library = root / "lib" / "libwine.so.1"
            library.write_bytes(b"library")
            (root / "lib" / "libwine.so").symlink_to("libwine.so.1")

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            manifest = package_runtime.make_manifest(
                "glibc-proot-box64",
                "test-1",
                files,
                symlinks,
            )
            output = Path(temporary) / "runtime.zip"
            package_runtime.write_zip(output, manifest, files)

            with zipfile.ZipFile(output) as archive:
                self.assertIn("runtime-manifest.json", archive.namelist())
                self.assertIn("payload/bin/wine", archive.namelist())
                self.assertIn("payload/lib/libwine.so.1", archive.namelist())

                parsed = json.loads(archive.read("runtime-manifest.json"))
                self.assertEqual(parsed["backendId"], "glibc-proot-box64")
                self.assertTrue(parsed["files"][0]["sha256"])
                self.assertEqual(
                    parsed["symlinks"],
                    [{"path": "lib/libwine.so", "target": "libwine.so.1"}],
                )

    def test_rejects_symlink_that_escapes_runtime_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            outside = Path(temporary) / "outside"
            outside.write_text("outside", encoding="utf-8")
            (root / "escape").symlink_to("../outside")

            with self.assertRaises(ValueError):
                package_runtime.scan_runtime(root, 1024 * 1024)

    def test_allows_symlink_to_future_virtual_mount_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            (root / "proc").mkdir(parents=True)
            (root / "dev").mkdir()
            (root / "bin").mkdir()
            (root / "bin" / "wine").write_bytes(b"wine")
            (root / "dev" / "fd").symlink_to("/proc/self/fd")

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)

            self.assertEqual(["bin/wine"], [item.relative_path for item in files])
            self.assertEqual(
                [("dev/fd", "/proc/self/fd")],
                [(item.relative_path, item.target) for item in symlinks],
            )

    def test_rejects_case_colliding_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backing_file = root / "A.dll"
            backing_file.write_text("one", encoding="utf-8")

            class FakeDirEntry:
                def __init__(self, name: str, path: Path) -> None:
                    self.name = name
                    self.path = str(path)

                def stat(self, follow_symlinks: bool = False):
                    # Reuse one real file so this test works on both
                    # case-sensitive and case-insensitive filesystems.
                    return backing_file.stat(follow_symlinks=follow_symlinks)

            entries = [
                FakeDirEntry("A.dll", root / "A.dll"),
                FakeDirEntry("a.dll", root / "a.dll"),
            ]
            with patch("package_runtime.os.scandir", return_value=entries):
                with self.assertRaises(ValueError):
                    package_runtime.scan_runtime(root, 1024 * 1024)

    def test_rejects_path_traversal(self) -> None:
        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("../outside")

        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("bin/../../outside")


if __name__ == "__main__":
    unittest.main()
