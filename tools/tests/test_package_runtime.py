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
