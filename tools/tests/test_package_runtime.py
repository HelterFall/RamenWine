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
        load_alignment: int | None = None,
    ) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        if load_alignment is None:
            load_alignment = (
                0x4000
                if machine == package_runtime.ELF_MACHINE_AARCH64
                else 0x1000
            )
        ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
        interpreter_bytes = (
            interpreter.encode("ascii") + b"\x00"
            if interpreter is not None else b""
        )
        phoff = 64
        phnum = 1 + (1 if interpreter is not None else 0)
        interpreter_offset = phoff + phnum * 56
        file_size = interpreter_offset + len(interpreter_bytes)
        header = struct.pack(
            "<16sHHIQQQIHHHHHH",
            ident, 2, machine, 1, 0, phoff, 0, 0, 64, 56, phnum, 0, 0, 0,
        )
        load_segment = struct.pack(
            "<IIQQQQQQ",
            package_runtime.ELF_PT_LOAD,
            5,
            0,
            load_alignment,
            load_alignment,
            file_size,
            file_size,
            load_alignment,
        )
        data = header + load_segment
        if interpreter is not None:
            interpreter_segment = struct.pack(
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
            data += interpreter_segment + interpreter_bytes
        path.write_bytes(data)
        path.chmod(0o755 if executable else 0o644)
        return path

    @staticmethod
    def _write_elf64_dynamic(
        path: Path,
        machine: int,
        needed: tuple[str, ...] = (),
        rpath: str | None = None,
        runpath: str | None = None,
        interpreter: str | None = None,
        executable: bool = False,
        load_alignment: int | None = None,
    ) -> Path:
        """Write a tiny ELF64 fixture with PT_DYNAMIC and an optional PT_INTERP."""
        path.parent.mkdir(parents=True, exist_ok=True)
        if load_alignment is None:
            load_alignment = (
                0x4000
                if machine == package_runtime.ELF_MACHINE_AARCH64
                else 0x1000
            )
        load_virtual_address = load_alignment
        strings = bytearray(b"\x00")

        def add_string(value: str) -> int:
            offset = len(strings)
            strings.extend(value.encode("utf-8") + b"\x00")
            return offset

        dynamic_tags: list[tuple[int, int]] = [
            (package_runtime.ELF_DT_STRTAB, 0),
            (package_runtime.ELF_DT_STRSZ, 0),
        ]
        dynamic_tags.extend(
            (package_runtime.ELF_DT_NEEDED, add_string(name))
            for name in needed
        )
        if rpath is not None:
            dynamic_tags.append((package_runtime.ELF_DT_RPATH, add_string(rpath)))
        if runpath is not None:
            dynamic_tags.append((package_runtime.ELF_DT_RUNPATH, add_string(runpath)))
        dynamic_tags.append((package_runtime.ELF_DT_NULL, 0))

        program_header_count = 3 if interpreter is not None else 2
        dynamic_offset = 64 + program_header_count * 56
        dynamic_data = b"".join(
            struct.pack("<qQ", tag, value) for tag, value in dynamic_tags
        )
        string_table_offset = dynamic_offset + len(dynamic_data)
        dynamic_tags[0] = (
            package_runtime.ELF_DT_STRTAB,
            load_virtual_address + string_table_offset,
        )
        dynamic_tags[1] = (
            package_runtime.ELF_DT_STRSZ,
            len(strings),
        )
        dynamic_data = b"".join(
            struct.pack("<qQ", tag, value) for tag, value in dynamic_tags
        )
        string_table_offset = dynamic_offset + len(dynamic_data)
        interpreter_bytes = (
            interpreter.encode("ascii") + b"\x00"
            if interpreter is not None else b""
        )
        interpreter_offset = string_table_offset + len(strings)
        file_size = interpreter_offset + len(interpreter_bytes)

        ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
        header = struct.pack(
            "<16sHHIQQQIHHHHHH",
            ident, 3, machine, 1, 0, 64, 0, 0, 64, 56,
            program_header_count, 0, 0, 0,
        )
        load_segment = struct.pack(
            "<IIQQQQQQ",
            package_runtime.ELF_PT_LOAD,
            6,
            0,
            load_virtual_address,
            load_virtual_address,
            file_size,
            file_size,
            load_alignment,
        )
        dynamic_segment = struct.pack(
            "<IIQQQQQQ",
            package_runtime.ELF_PT_DYNAMIC,
            4,
            dynamic_offset,
            load_virtual_address + dynamic_offset,
            load_virtual_address + dynamic_offset,
            len(dynamic_data),
            len(dynamic_data),
            8,
        )
        data = header + load_segment + dynamic_segment
        if interpreter is not None:
            interpreter_segment = struct.pack(
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
            data += interpreter_segment
        data += dynamic_data + strings + interpreter_bytes
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

    @staticmethod
    def _write_pe32(path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        data = bytearray(0x80 + 6)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x80)
        data[0x80:0x84] = b"PE\x00\x00"
        struct.pack_into("<H", data, 0x84, package_runtime.PE_MACHINE_I386)
        path.write_bytes(data)
        path.chmod(0o644)
        return path

    def _make_glibc_backend(self, root: Path) -> Path:
        backend = root / "backend"
        host_lib = backend / "host" / "lib"
        host_lib.mkdir(parents=True)
        talloc_real = self._write_elf64(
            host_lib / "libtalloc.so.2.5.0",
            package_runtime.ELF_MACHINE_AARCH64,
        )
        (host_lib / "libtalloc.so.2").symlink_to(talloc_real.name)
        self._write_elf64(
            host_lib / "libandroid-shmem.so",
            package_runtime.ELF_MACHINE_AARCH64,
        )
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
        for library_name in (
            "libstdc++.so.6",
            "libgcc_s.so.1",
            "libunwind.so.8",
        ):
            self._write_elf64(
                rootfs / "usr" / "lib" / "box64-x86_64-linux-gnu" / library_name,
                package_runtime.ELF_MACHINE_X86_64,
            )
        for library_name in (
            "libwayland-client.so.0",
            "libwayland-cursor.so.0",
            "libwayland-egl.so.1",
            "libxkbcommon.so.0",
            "libxkbregistry.so.0",
        ):
            self._write_elf64(
                rootfs / "usr" / "lib" / "x86_64-linux-gnu" / library_name,
                package_runtime.ELF_MACHINE_X86_64,
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
        self._write_pe32(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "i386-windows" / "winewayland.drv",
        )
        self._write_pe64(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "x86_64-windows" / "ntdll.dll",
        )
        self._write_pe32(
            rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
            / "i386-windows" / "ntdll.dll",
        )
        for relative_path in (
            "rules/evdev",
            "keycodes/evdev",
            "symbols/us",
        ):
            xkb_file = rootfs / "usr" / "share" / "X11" / "xkb" / relative_path
            xkb_file.parent.mkdir(parents=True, exist_ok=True)
            xkb_file.write_text("fixture", encoding="utf-8")
        return backend

    def test_validates_hybrid_arm64_glibc_and_x64_wine_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_missing_wow64_i386_ntdll(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            (backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu" / "wine"
             / "i386-windows" / "ntdll.dll").unlink()

            with self.assertRaisesRegex(ValueError, "required guest path is missing"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_mixed_pe_architecture_inside_wow64_i386_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            wrong_module = (
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "i386-windows" / "kernel32.dll"
            )
            self._write_pe64(wrong_module)

            with self.assertRaisesRegex(ValueError, "wrong PE machine"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_mixed_pe_architecture_inside_wow64_x64_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            wrong_module = (
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "x86_64-windows" / "kernel32.dll"
            )
            self._write_pe32(wrong_module)

            with self.assertRaisesRegex(ValueError, "wrong PE machine"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_resolves_transitive_elf_dependencies_and_origin_runpath(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            app = rootfs / "usr" / "bin" / "app"
            first = rootfs / "opt" / "ramen" / "libfirst.so.1"
            second = rootfs / "opt" / "ramen" / "libsecond.so.2"
            self._write_elf64_dynamic(
                app,
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libfirst.so.1",),
                runpath="$ORIGIN/../../opt/ramen",
                executable=True,
            )
            self._write_elf64_dynamic(
                first,
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libsecond.so.2",),
            )
            self._write_elf64_dynamic(second, package_runtime.ELF_MACHINE_AARCH64)

            inspected = package_runtime.validate_elf_dependency_closure(
                rootfs,
                ["/usr/bin/app"],
                package_runtime.ELF_MACHINE_AARCH64,
                ["/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu"],
                "ARM64 fixture",
            )

            self.assertEqual(3, inspected)

    def test_rejects_missing_transitive_elf_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            app = rootfs / "usr" / "bin" / "app"
            self._write_elf64_dynamic(
                app,
                package_runtime.ELF_MACHINE_X86_64,
                needed=("libnot-installed.so.1",),
                executable=True,
            )

            with self.assertRaisesRegex(
                ValueError,
                "unresolved ELF dependency.*libnot-installed.so.1",
            ):
                package_runtime.validate_elf_dependency_closure(
                    rootfs,
                    ["/usr/bin/app"],
                    package_runtime.ELF_MACHINE_X86_64,
                    ["/lib/x86_64-linux-gnu"],
                    "x86-64 fixture",
                )

    def test_rejects_wrong_architecture_in_elf_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            app = rootfs / "usr" / "bin" / "app"
            wrong_library = rootfs / "usr" / "lib" / "x86_64-linux-gnu" / "libwrong.so.1"
            self._write_elf64_dynamic(
                app,
                package_runtime.ELF_MACHINE_X86_64,
                needed=("libwrong.so.1",),
                executable=True,
            )
            self._write_elf64_dynamic(
                wrong_library,
                package_runtime.ELF_MACHINE_AARCH64,
            )

            with self.assertRaisesRegex(ValueError, "wrong machine"):
                package_runtime.validate_elf_dependency_closure(
                    rootfs,
                    ["/usr/bin/app"],
                    package_runtime.ELF_MACHINE_X86_64,
                    ["/usr/lib/x86_64-linux-gnu"],
                    "x86-64 fixture",
                )

    def test_rejects_runtime_missing_required_xkb_keymap_data(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            (backend / "rootfs" / "usr" / "share" / "X11" / "xkb"
             / "rules" / "evdev").unlink()
            with self.assertRaisesRegex(ValueError, "required guest path is missing"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_proot_host_library_directory_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self._make_glibc_backend(root)
            host_lib = backend / "host" / "lib"
            external_lib = root / "external-lib"
            host_lib.rename(external_lib)
            host_lib.symlink_to(external_lib, target_is_directory=True)

            with self.assertRaisesRegex(ValueError, "PRoot host library directory.*escapes"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_proot_host_directory_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self._make_glibc_backend(root)
            host_root = backend / "host"
            external_host = root / "external-host"
            host_root.rename(external_host)
            host_root.symlink_to(external_host, target_is_directory=True)

            with self.assertRaisesRegex(ValueError, "PRoot host directory.*escapes"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_proot_host_library_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = self._make_glibc_backend(root)
            host_lib = backend / "host" / "lib"
            outside = root / "outside.so"
            self._write_elf64(outside, package_runtime.ELF_MACHINE_AARCH64)
            library = host_lib / "libandroid-shmem.so"
            library.unlink()
            library.symlink_to(outside)

            with self.assertRaisesRegex(ValueError, "escapes host/lib"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_runtime_missing_proot_host_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            (backend / "host" / "lib" / "libandroid-shmem.so").unlink()
            with self.assertRaisesRegex(
                ValueError,
                "required PRoot host library is missing",
            ):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_4k_aligned_android_proot_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_elf64(
                backend / "host" / "bin" / "proot",
                package_runtime.ELF_MACHINE_AARCH64,
                "/system/bin/linker64",
                executable=True,
                load_alignment=0x1000,
            )

            with self.assertRaisesRegex(ValueError, "not 16 KB page-size compatible"):
                package_runtime.validate_android_proot_host_payload(backend / "host")

    def test_accepts_16k_aligned_android_proot_host(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self.assertGreater(
                package_runtime.validate_android_proot_host_payload(backend / "host"),
                0,
            )

    def test_rejects_4k_aligned_android_proot_shared_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_elf64(
                backend / "host" / "lib" / "libandroid-shmem.so",
                package_runtime.ELF_MACHINE_AARCH64,
                load_alignment=0x1000,
            )

            with self.assertRaisesRegex(ValueError, "not 16 KB page-size compatible"):
                package_runtime.validate_android_proot_host_payload(backend / "host")

    def test_rejects_wrong_architecture_for_proot_host_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            library = backend / "host" / "lib" / "libandroid-shmem.so"
            self._write_elf64(library, package_runtime.ELF_MACHINE_X86_64)
            with self.assertRaisesRegex(ValueError, "wrong ELF machine"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_validates_complete_android_proot_host_dependency_closure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            host_lib = backend / "host" / "lib"
            self._write_elf64_dynamic(
                backend / "host" / "bin" / "proot",
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libtalloc.so.2", "libandroid-shmem.so", "liblog.so"),
                interpreter="/system/bin/linker64",
                executable=True,
            )
            self._write_elf64_dynamic(
                host_lib / "libtalloc.so.2.5.0",
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libc.so",),
            )
            self._write_elf64_dynamic(
                host_lib / "libandroid-shmem.so",
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libandroid.so",),
            )

            package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_unresolved_transitive_android_proot_host_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            host_lib = backend / "host" / "lib"
            self._write_elf64_dynamic(
                backend / "host" / "bin" / "proot",
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libtalloc.so.2",),
                interpreter="/system/bin/linker64",
                executable=True,
            )
            self._write_elf64_dynamic(
                host_lib / "libtalloc.so.2.5.0",
                package_runtime.ELF_MACHINE_AARCH64,
                needed=("libmissing-host.so.1",),
            )

            with self.assertRaisesRegex(
                ValueError,
                "unresolved Android PRoot host dependency.*libmissing-host.so.1",
            ):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_runtime_missing_box64_x64_emulation_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            (backend / "rootfs" / "usr" / "lib" / "box64-x86_64-linux-gnu"
             / "libunwind.so.8").unlink()
            with self.assertRaisesRegex(ValueError, "required guest path is missing"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_runtime_missing_i386_wine_wayland_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            driver = (
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "i386-windows" / "winewayland.drv"
            )
            driver.unlink()
            with self.assertRaisesRegex(ValueError, "required guest path is missing"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_wrong_architecture_for_i386_wine_wayland_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            self._write_pe64(
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "wine" / "i386-windows" / "winewayland.drv",
            )
            with self.assertRaisesRegex(ValueError, "wrong PE machine"):
                package_runtime.validate_glibc_proot_box64(backend)

    def test_rejects_runtime_missing_wayland_xkb_library(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backend = self._make_glibc_backend(Path(temporary))
            missing_library = (
                backend / "rootfs" / "usr" / "lib" / "x86_64-linux-gnu"
                / "libxkbregistry.so.0"
            )
            missing_library.unlink()
            with self.assertRaisesRegex(ValueError, "required guest path is missing"):
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
