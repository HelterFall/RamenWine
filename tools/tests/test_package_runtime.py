from __future__ import annotations

import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import package_runtime



def package_runtime_test_dynamic_elf64(machine: int, needed: str) -> bytes:
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


class RuntimePackagerTests(unittest.TestCase):
    def write_elf64(self, path: Path, machine: int) -> None:
        header = bytearray(64)
        header[:4] = b"\x7fELF"
        header[4:7] = bytes((2, 1, 1))
        struct.pack_into("<H", header, 18, machine)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(header)
        path.chmod(0o755)

    def write_pe32_plus_amd64(self, path: Path) -> None:
        header = bytearray(256)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 0x3C, 0x80)
        header[0x80:0x84] = b"PE\x00\x00"
        struct.pack_into("<H", header, 0x84, 0x8664)
        struct.pack_into("<H", header, 0x98, 0x20B)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(header)

    def write_pe32_i386(self, path: Path) -> None:
        header = bytearray(256)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 0x3C, 0x80)
        header[0x80:0x84] = b"PE\x00\x00"
        struct.pack_into("<H", header, 0x84, 0x014C)
        struct.pack_into("<H", header, 0x98, 0x010B)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(header)

    def create_glibc_runtime(self, root: Path) -> None:
        for relative, machine in (
            ("host/bin/proot", 183),
            ("rootfs/usr/bin/env", 183),
            ("rootfs/usr/bin/dash", 183),
            ("rootfs/usr/bin/box64", 183),
            ("rootfs/usr/bin/wine", 62),
            ("rootfs/usr/bin/wineserver", 62),
        ):
            self.write_elf64(root / relative, machine)

        (root / "rootfs/usr/bin/sh").symlink_to("dash")
        (root / "rootfs/bin").symlink_to("usr/bin", target_is_directory=True)

        wrapper = root / "rootfs/opt/ramenwine-tools/run-wine-command.sh"
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        wrapper_source = Path(__file__).resolve().parents[1] / "run-wine-command.sh"
        wrapper.write_bytes(wrapper_source.read_bytes())
        wrapper.chmod(0o755)

        self.write_pe32_plus_amd64(
            root / "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe"
        )
        self.write_pe32_plus_amd64(
            root / "rootfs/opt/ramenwine-tests/ramenwine-gdi-smoke.exe"
        )
        relay = root / "host/bin/directaudio-relay"
        self.write_elf64(relay, 183)
        with relay.open("ab") as stream:
            stream.write(b"libaaudio.so")
        self.write_elf64(
            root / "rootfs/opt/ramenwine-audio/lib/wine/x86_64-unix/winedirectaudio.so",
            62,
        )
        self.write_pe32_plus_amd64(
            root / "rootfs/opt/ramenwine-audio/lib/wine/x86_64-windows/winedirectaudio.drv"
        )
        self.write_pe32_i386(
            root / "rootfs/opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv"
        )
        self.write_elf64(
            root / "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so",
            62,
        )

    def test_creates_manifest_and_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)

            library = root / "host" / "lib" / "libwine.so.1"
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(b"library")
            (root / "host" / "lib" / "libwine.so").symlink_to("libwine.so.1")

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
                self.assertIn("payload/host/bin/proot", archive.namelist())
                self.assertIn("payload/host/lib/libwine.so.1", archive.namelist())

                parsed = json.loads(archive.read("runtime-manifest.json"))
                self.assertEqual(parsed["backendId"], "glibc-proot-box64")
                self.assertTrue(parsed["files"][0]["sha256"])
                self.assertEqual(
                    parsed["symlinks"],
                    [
                        {"path": "host/lib/libwine.so", "target": "libwine.so.1"},
                        {"path": "rootfs/bin", "target": "usr/bin"},
                        {"path": "rootfs/usr/bin/sh", "target": "dash"},
                    ],
                )


    def test_glibc_manifest_accepts_debian_merged_usr_shell_links(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            manifest = package_runtime.make_manifest(
                "glibc-proot-box64", "test-merged-usr", files, symlinks
            )

            parsed = json.loads(manifest)
            link_targets = {
                item["path"]: item["target"] for item in parsed["symlinks"]
            }
            self.assertEqual("usr/bin", link_targets["rootfs/bin"])
            self.assertEqual("dash", link_targets["rootfs/usr/bin/sh"])

    def test_glibc_manifest_rejects_missing_wineserver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            (root / "rootfs/usr/bin/wineserver").unlink()

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(
                ValueError, "rootfs/usr/bin/wineserver"
            ):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_rejects_missing_wine_shutdown_wrapper(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            (root / "rootfs/opt/ramenwine-tools/run-wine-command.sh").unlink()

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "run-wine-command.sh"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_wine_shutdown_wrapper_kills_and_waits_for_wineserver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            wrapper = root / "rootfs/opt/ramenwine-tools/run-wine-command.sh"
            contents = wrapper.read_text(encoding="utf-8")

            self.assertIn("/usr/bin/box64 /usr/bin/wineserver -k", contents)
            self.assertIn("/usr/bin/box64 /usr/bin/wineserver -w", contents)
            self.assertIn("wine_status", contents)

    def test_absolute_rootfs_symlink_uses_guest_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            target = root / "rootfs/usr/lib/wine/wineboot.exe"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"wineboot")
            link = root / "rootfs/usr/lib/wine/wineboot-link.exe"
            link.symlink_to("/usr/lib/wine/wineboot.exe")

            resolved = package_runtime.resolve_symlink_inside(
                root, link, "/usr/lib/wine/wineboot.exe"
            )

            self.assertEqual(target, resolved)

    def test_rootfs_absolute_symlink_stays_in_guest_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            guest_binary = root / "rootfs/host/bin/proot"
            host_binary = root / "host/bin/proot"
            guest_binary.parent.mkdir(parents=True)
            host_binary.parent.mkdir(parents=True)
            guest_binary.write_bytes(b"guest executable")
            host_binary.write_bytes(b"Android-host executable")
            link = root / "rootfs/bin/guest-proot"
            link.parent.mkdir(parents=True)

            resolved = package_runtime.resolve_symlink_inside(
                root, link, "/host/bin/proot"
            )

            self.assertEqual(guest_binary, resolved)
            self.assertNotEqual(host_binary, resolved)

    def test_resolves_chained_guest_absolute_symlinks_without_host_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            guest = root / "rootfs"
            rmt_link = guest / "etc/rmt"
            mt_link = guest / "usr/sbin/rmt"
            target = guest / "usr/bin/mt"
            rmt_link.parent.mkdir(parents=True)
            mt_link.parent.mkdir(parents=True)
            target.parent.mkdir(parents=True)
            target.write_bytes(b"guest mt")

            rmt_link.symlink_to("/usr/sbin/rmt")
            mt_link.symlink_to("/usr/bin/mt")

            resolved = package_runtime.resolve_symlink_inside(
                root, rmt_link, "/usr/sbin/rmt"
            )

            self.assertEqual(target, resolved)

    def test_rejects_backslash_and_nul_in_symlink_targets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            link = root / "link"

            with self.assertRaises(ValueError):
                package_runtime.resolve_symlink_inside(root, link, r"..\outside")
            with self.assertRaises(ValueError):
                package_runtime.resolve_symlink_inside(root, link, "bad\x00target")


    def test_glibc_manifest_requires_x86_64_wine_wayland_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            (root / "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so").unlink()

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "Wine Wayland driver"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_rejects_wrong_architecture_wine_wayland_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            self.write_elf64(
                root / "rootfs/opt/ramenwine-wine/x86_64-unix/winewayland.so",
                183,
            )

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "Wine Wayland driver"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_rejects_arm64_wine_loader(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            self.write_elf64(root / "rootfs/usr/bin/wine", 183)

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "x86-64 Wine loader"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )


    def test_glibc_manifest_does_not_require_placeholder_host_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)

            manifest = package_runtime.make_manifest(
                "glibc-proot-box64", "test-1", files, symlinks
            )

            parsed = json.loads(manifest)
            self.assertEqual(parsed["backendId"], "glibc-proot-box64")
            self.assertFalse(any(item["path"].startswith("host/lib/") for item in parsed["files"]))


    def test_glibc_packager_rejects_missing_guest_elf_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            box64 = root / "rootfs/usr/bin/box64"
            box64.write_bytes(
                package_runtime_test_dynamic_elf64(183, "libmissing-runtime.so.1")
            )
            box64.chmod(0o755)

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "unresolved ELF dependencies"):
                package_runtime.make_manifest(
                    "glibc-proot-box64",
                    "test-1",
                    files,
                    symlinks,
                    runtime_root=root,
                )

    def test_glibc_manifest_rejects_missing_directaudio_32_bit_driver(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            (root / "rootfs/opt/ramenwine-audio/lib/wine/i386-windows/winedirectaudio.drv").unlink()
            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "i386 PE32"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_rejects_wrong_directaudio_relay_architecture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            self.write_elf64(root / "host/bin/directaudio-relay", 62)
            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "AArch64"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_rejects_relay_without_aaudio_dependency(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)
            relay = root / "host/bin/directaudio-relay"
            relay.write_bytes(relay.read_bytes().replace(b"libaaudio.so", b"libbroken.so"))
            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)
            with self.assertRaisesRegex(ValueError, "libaaudio.so"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
                )

    def test_glibc_manifest_requires_launch_components(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)

            with self.assertRaisesRegex(ValueError, "required executable"):
                package_runtime.make_manifest(
                    "glibc-proot-box64", "test-1", files, symlinks
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
            (root / "A.dll").write_text("one", encoding="utf-8")
            (root / "a.dll").write_text("two", encoding="utf-8")

            with self.assertRaises(ValueError):
                package_runtime.scan_runtime(root, 1024 * 1024)

    def test_scans_file_with_comma_in_name(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = "rootfs/usr/share/i18n/charmaps/ISO_8859-1,GL.gz"
            file_path = root / relative
            file_path.parent.mkdir(parents=True)
            file_path.write_bytes(b"locale charmap")

            files, symlinks = package_runtime.scan_runtime(root, 1024 * 1024)

            self.assertEqual([relative], [item.relative_path for item in files])
            self.assertEqual([], symlinks)

    def test_rejects_path_traversal(self) -> None:
        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("../outside")

        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("bin/../../outside")


if __name__ == "__main__":
    unittest.main()
