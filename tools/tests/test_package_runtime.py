from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import package_runtime


class RuntimePackagerTests(unittest.TestCase):
    def create_glibc_runtime(self, root: Path) -> None:
        for relative in (
            "host/bin/proot",
            "rootfs/usr/bin/env",
            "rootfs/usr/bin/box64",
            "rootfs/usr/bin/wine",
        ):
            executable = root / relative
            executable.parent.mkdir(parents=True, exist_ok=True)
            executable.write_bytes(b"test executable")
            executable.chmod(0o755)

        host_library = root / "host/lib/libproot.so"
        host_library.parent.mkdir(parents=True, exist_ok=True)
        host_library.write_bytes(b"host library")

        wineboot = root / "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe"
        wineboot.parent.mkdir(parents=True, exist_ok=True)
        wineboot.write_bytes(b"wineboot")

    def test_creates_manifest_and_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            self.create_glibc_runtime(root)

            library = root / "host" / "lib" / "libwine.so.1"
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
                    [{"path": "host/lib/libwine.so", "target": "libwine.so.1"}],
                )


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

    def test_rootfs_symlink_cannot_escape_into_host_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            (root / "rootfs/bin").mkdir(parents=True)
            (root / "host/bin").mkdir(parents=True)
            (root / "host/bin/proot").write_bytes(b"host executable")
            link = root / "rootfs/bin/escape"

            with self.assertRaises(ValueError):
                package_runtime.resolve_symlink_inside(root, link, "/host/bin/proot")

    def test_rejects_backslash_and_nul_in_symlink_targets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            link = root / "link"

            with self.assertRaises(ValueError):
                package_runtime.resolve_symlink_inside(root, link, r"..\outside")
            with self.assertRaises(ValueError):
                package_runtime.resolve_symlink_inside(root, link, "bad\x00target")

    def test_glibc_manifest_requires_launch_components(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            executable = root / "host/bin/proot"
            executable.parent.mkdir(parents=True)
            executable.write_bytes(b"proot")
            executable.chmod(0o755)
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

    def test_rejects_path_traversal(self) -> None:
        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("../outside")

        with self.assertRaises(ValueError):
            package_runtime.safe_relative_path("bin/../../outside")


if __name__ == "__main__":
    unittest.main()
