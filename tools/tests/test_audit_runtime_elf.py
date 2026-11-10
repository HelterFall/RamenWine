from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import audit_runtime_elf


def elf64(machine: int) -> bytes:
    data = bytearray(64)
    data[:4] = b"\x7fELF"
    data[4:7] = bytes((2, 1, 1))
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        data,
        16,
        2, machine, 1, 0, 0, 0, 0, 64, 56, 0, 0, 0, 0
    )
    return bytes(data)


def dynamic_elf64(machine: int, needed: str) -> bytes:
    data = bytearray(768)
    data[:4] = b"\x7fELF"
    data[4:7] = bytes((2, 1, 1))
    struct.pack_into("<HHIQQQIHHHHHH", data, 16,
                     2, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)

    struct.pack_into("<IIQQQQQQ", data, 64,
                     1, 4, 0, 0x400000, 0, len(data), len(data), 0x1000)
    dynamic_offset = 0x100
    string_offset = 0x200
    string_table = b"\x00" + needed.encode("utf-8") + b"\x00"
    entries = (
        (5, 0x400000 + string_offset),
        (10, len(string_table)),
        (1, 1),
        (0, 0),
    )
    for index, (tag, value) in enumerate(entries):
        struct.pack_into("<qQ", data, dynamic_offset + index * 16, tag, value)
    struct.pack_into("<IIQQQQQQ", data, 120,
                     2, 6, dynamic_offset, 0x400000 + dynamic_offset,
                     0, len(entries) * 16, len(entries) * 16, 8)
    data[string_offset:string_offset + len(string_table)] = string_table
    return bytes(data)


class RuntimeElfAuditTests(unittest.TestCase):
    def test_reports_missing_soname_in_guest_rootfs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            executable = rootfs / "usr/bin/box64"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(dynamic_elf64(183, "libnot-installed.so.1"))

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertTrue(any(
                issue["kind"] == "missing-library" and
                issue["detail"] == "libnot-installed.so.1"
                for issue in report["issues"]
            ))

    def test_guest_absolute_rpath_is_resolved_under_rootfs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            binary = rootfs / "usr/bin/box64"
            binary.parent.mkdir(parents=True)
            binary.write_bytes(dynamic_elf64(183, "libdummy.so"))
            info = audit_runtime_elf.ElfInfo(
                path=binary,
                machine=183,
                interpreter=None,
                needed=("libdummy.so",),
                rpath=("/usr/local/runtime-libs",),
                runpath=(),
            )
            lib = rootfs / "usr/local/runtime-libs/libdummy.so"
            lib.parent.mkdir(parents=True)
            lib.write_bytes(elf64(183))

            self.assertTrue(
                audit_runtime_elf._dependency_exists(rootfs, info, "libdummy.so")
            )


    def test_dependency_resolves_guest_absolute_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            executable = rootfs / "usr/bin/box64"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(dynamic_elf64(183, "libguest-test.so.1"))

            library_dir = rootfs / "usr/lib/aarch64-linux-gnu"
            library_dir.mkdir(parents=True)
            real_library = library_dir / "libguest-test-real.so.1"
            real_library.write_bytes(elf64(183))
            guest_link = library_dir / "libguest-test.so.1"
            guest_link.symlink_to("/usr/lib/aarch64-linux-gnu/libguest-test-real.so.1")

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertFalse(any(
                issue["kind"] == "missing-library" and
                issue["detail"] == "libguest-test.so.1"
                for issue in report["issues"]
            ))
            self.assertEqual(real_library, audit_runtime_elf._inside(rootfs, guest_link))

    def test_guest_path_resolver_rejects_symlink_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            link = rootfs / "usr/lib/libescape.so"
            link.parent.mkdir(parents=True)
            link.symlink_to("/../../outside/libescape.so")

            self.assertIsNone(audit_runtime_elf._inside(rootfs, link))

    def test_resolves_wine_unix_modules_from_their_architecture_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            wine_unix = rootfs / "opt/ramenwine-wine/x86_64-unix"
            wine_unix.mkdir(parents=True)
            module = wine_unix / "mountmgr.so"
            module.write_bytes(dynamic_elf64(62, "ntdll.so"))
            ntdll = wine_unix / "ntdll.so"
            ntdll.write_bytes(elf64(62))

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertFalse(any(
                issue["kind"] == "missing-library" and
                issue["detail"] == "ntdll.so"
                for issue in report["issues"]
            ))

    def test_resolves_private_systemd_library_next_to_consumer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            systemd_dir = rootfs / "usr/lib/aarch64-linux-gnu/systemd"
            systemd_dir.mkdir(parents=True)
            core = systemd_dir / "libsystemd-core-257.so"
            shared = systemd_dir / "libsystemd-shared-257.so"
            core.write_bytes(dynamic_elf64(183, "libsystemd-shared-257.so"))
            shared.write_bytes(elf64(183))

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertFalse(any(
                issue["kind"] == "missing-library" and
                issue["detail"] == "libsystemd-shared-257.so"
                for issue in report["issues"]
            ))

    def test_optional_wine_integrations_are_reported_as_warnings(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            wine_unix = rootfs / "opt/ramenwine-wine/x86_64-unix"
            wine_unix.mkdir(parents=True)
            (wine_unix / "sane.so").write_bytes(dynamic_elf64(62, "libsane.so.1"))
            (wine_unix / "wpcap.so").write_bytes(dynamic_elf64(62, "libpcap.so.1"))
            (wine_unix / "winedmo.so").write_bytes(
                dynamic_elf64(
                    62,
                    "libavutil.so.58",
                    "libavformat.so.60",
                    "libavcodec.so.60",
                )
            )
            (wine_unix / "wineopenxr.so").write_bytes(
                dynamic_elf64(62, "libopenxr_loader.so.1")
            )

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertEqual([], report["issues"])
            warnings = {
                (item["file"], item["detail"])
                for item in report["optionalIssues"]
            }
            self.assertEqual(
                {
                    ("opt/ramenwine-wine/x86_64-unix/sane.so", "libsane.so.1"),
                    ("opt/ramenwine-wine/x86_64-unix/wpcap.so", "libpcap.so.1"),
                    ("opt/ramenwine-wine/x86_64-unix/winedmo.so", "libavutil.so.58"),
                    ("opt/ramenwine-wine/x86_64-unix/winedmo.so", "libavformat.so.60"),
                    ("opt/ramenwine-wine/x86_64-unix/winedmo.so", "libavcodec.so.60"),
                    ("opt/ramenwine-wine/x86_64-unix/wineopenxr.so", "libopenxr_loader.so.1"),
                },
                warnings,
            )

    def test_unlisted_missing_wine_dependency_remains_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            wine_unix = rootfs / "opt/ramenwine-wine/x86_64-unix"
            wine_unix.mkdir(parents=True)
            (wine_unix / "winex11.so").write_bytes(
                dynamic_elf64(62, "librequired-wine-dependency.so")
            )

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertTrue(any(
                item["file"] == "opt/ramenwine-wine/x86_64-unix/winex11.so" and
                item["detail"] == "librequired-wine-dependency.so"
                for item in report["issues"]
            ))

    def test_rejects_library_with_wrong_elf_machine(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            executable = rootfs / "usr/bin/wine"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(dynamic_elf64(62, "libwine-dependency.so.1"))

            library = rootfs / "usr/lib/x86_64-linux-gnu/libwine-dependency.so.1"
            library.parent.mkdir(parents=True)
            library.write_bytes(dynamic_elf64(183, "libwrong-arch-transitive.so"))

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertTrue(any(
                issue["kind"] == "library-architecture" and
                "libwine-dependency.so.1" in issue["detail"]
                for issue in report["issues"]
            ))

    def test_resolves_dependencies_from_matching_architecture_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            rootfs = Path(temporary) / "rootfs"
            executable = rootfs / "usr/bin/box64"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(dynamic_elf64(183, "libbox-test.so.1"))
            library = rootfs / "usr/lib/aarch64-linux-gnu/libbox-test.so.1"
            library.parent.mkdir(parents=True)
            library.write_bytes(elf64(183))

            report = audit_runtime_elf.audit_rootfs(rootfs)

            self.assertFalse(any(
                issue["kind"] == "missing-library"
                for issue in report["issues"]
            ))
            self.assertEqual({"aarch64": 2}, report["architectures"])


if __name__ == "__main__":
    unittest.main()
