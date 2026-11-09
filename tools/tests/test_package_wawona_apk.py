from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import package_wawona_apk


class WawonaApkPackagerTests(unittest.TestCase):
    def test_parses_dt_needed_and_soname(self) -> None:
        output = """
 0x0000000000000001 (NEEDED) Shared library: [libwayland-server.so]
 0x0000000000000001 (NEEDED) Shared library: [liblog.so]
 0x000000000000000e (SONAME) Library soname: [libwawona.so]
"""
        needed, soname = package_wawona_apk.parse_dynamic_dependencies(output)
        self.assertEqual(["libwayland-server.so", "liblog.so"], needed)
        self.assertEqual("libwawona.so", soname)

    def test_only_packs_roots_and_recursive_native_dependencies(self) -> None:
        libraries = {
            "libwawona.so": b"wawona",
            "libEGL_angle.so": b"angle-egl",
            "libGLESv2_angle.so": b"angle-gles",
            "libwayland-server.so": b"wayland",
            "libxkbcommon.so": b"xkb",
            "libsshpass_bin.so": b"unrelated-executable",
        }
        dependencies = {
            "libwawona.so": (["libwayland-server.so", "liblog.so"], "libwawona.so"),
            "libEGL_angle.so": (["libGLESv2_angle.so", "libandroid.so"], "libEGL_angle.so"),
            "libGLESv2_angle.so": (["libandroid.so"], "libGLESv2_angle.so"),
            "libwayland-server.so": (["libxkbcommon.so"], "libwayland-server.so"),
            "libxkbcommon.so": (["libc.so"], "libxkbcommon.so"),
        }

        selected = package_wawona_apk.collect_native_closure(
            libraries,
            lambda filename, _data: dependencies[filename],
        )

        self.assertEqual(
            {
                "libwawona.so",
                "libEGL_angle.so",
                "libGLESv2_angle.so",
                "libwayland-server.so",
                "libxkbcommon.so",
            },
            set(selected),
        )
        self.assertNotIn("libsshpass_bin.so", selected)

    def test_rejects_unresolved_non_system_dependency(self) -> None:
        libraries = {
            "libwawona.so": b"wawona",
            "libEGL_angle.so": b"angle-egl",
            "libGLESv2_angle.so": b"angle-gles",
        }
        dependencies = {
            "libwawona.so": (["libmissing-private.so"], "libwawona.so"),
            "libEGL_angle.so": ([], "libEGL_angle.so"),
            "libGLESv2_angle.so": ([], "libGLESv2_angle.so"),
        }
        with self.assertRaisesRegex(ValueError, "unresolved native dependency"):
            package_wawona_apk.collect_native_closure(
                libraries,
                lambda filename, _data: dependencies[filename],
            )

    def test_accepts_android_system_dependencies_without_bundling_them(self) -> None:
        libraries = {
            "libwawona.so": b"wawona",
            "libEGL_angle.so": b"angle-egl",
            "libGLESv2_angle.so": b"angle-gles",
        }
        dependencies = {
            "libwawona.so": (["libandroid.so", "libvulkan.so"], "libwawona.so"),
            "libEGL_angle.so": (["libandroid.so"], "libEGL_angle.so"),
            "libGLESv2_angle.so": (["libandroid.so"], "libGLESv2_angle.so"),
        }
        selected = package_wawona_apk.collect_native_closure(
            libraries,
            lambda filename, _data: dependencies[filename],
        )
        self.assertEqual(set(libraries), set(selected))

    def test_rejects_unpinned_upstream_revision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "empty.apk"
            with zipfile.ZipFile(apk, "w"):
                pass
            with self.assertRaisesRegex(ValueError, "does not match the reviewed pin"):
                package_wawona_apk.package_apk(
                    apk,
                    Path(temporary) / "out",
                    "main",
                    "llvm-readelf",
                )

    def test_asset_extraction_preserves_only_allowlisted_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "wawona.apk"
            output = Path(temporary) / "generated-assets"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/xkb/rules/evdev", "rules")
                archive.writestr("assets/fonts/truetype/DejaVu.ttf", "font")
                archive.writestr("assets/weston/sign_close.png", "icon")
                archive.writestr("assets/about/README.txt", "not needed")

            with zipfile.ZipFile(apk) as archive:
                copied = package_wawona_apk.extract_assets(archive, output)

            self.assertIn("wawona/xkb/rules/evdev", copied)
            self.assertIn("wawona/fonts/truetype/DejaVu.ttf", copied)
            self.assertIn("wawona/weston/sign_close.png", copied)
            self.assertFalse((output / "wawona/about/README.txt").exists())
            self.assertEqual("rules", (output / "wawona/xkb/rules/evdev").read_text())

    def test_rejects_asset_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "wawona.apk"
            output = Path(temporary) / "generated-assets"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/xkb/../../escape", "bad")
                archive.writestr("assets/fonts/truetype/font.ttf", "font")

            with zipfile.ZipFile(apk) as archive:
                with self.assertRaisesRegex(ValueError, "unsafe APK entry path"):
                    package_wawona_apk.extract_assets(archive, output)

    def test_requires_xkb_and_font_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "wawona.apk"
            output = Path(temporary) / "generated-assets"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/fonts/truetype/font.ttf", "font")

            with zipfile.ZipFile(apk) as archive:
                with self.assertRaisesRegex(ValueError, "assets/xkb"):
                    package_wawona_apk.extract_assets(archive, output)


if __name__ == "__main__":
    unittest.main()
