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
            self.assertEqual({"aarch64": 1}, report["architectures"])


if __name__ == "__main__":
    unittest.main()
