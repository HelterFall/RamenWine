from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import package_wawona_apk


class WawonaApkPackagerTests(unittest.TestCase):
    def test_includes_versioned_shared_library_sonames(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "wawona.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libwawona.so", b"wawona")
                archive.writestr("lib/arm64-v8a/libEGL_angle.so", b"angle-egl")
                archive.writestr("lib/arm64-v8a/libGLESv2_angle.so", b"angle-gles")
                archive.writestr("lib/arm64-v8a/libwayland-server.so.0", b"wayland")

            with zipfile.ZipFile(apk) as archive:
                libraries = package_wawona_apk.apk_native_libraries(archive)
                self.assertIn("libwayland-server.so.0", libraries)

                dependencies = {
                    "libwawona.so": (["libwayland-server.so.0"], "libwawona.so"),
                    "libEGL_angle.so": ([], "libEGL_angle.so"),
                    "libGLESv2_angle.so": ([], "libGLESv2_angle.so"),
                    "libwayland-server.so.0": (["liblog.so"], "libwayland-server.so.0"),
                }
                selected = package_wawona_apk.collect_native_closure(
                    libraries,
                    lambda filename, _data: dependencies[filename],
                )
                self.assertIn("libwayland-server.so.0", selected)

    def test_accepts_elf64_aarch64_header(self) -> None:
        package_wawona_apk.validate_arm64_elf_header(
            """
ELF Header:
  Class:                             ELF64
  Machine:                           AArch64
""",
            "libwawona.so",
        )

    def test_accepts_16kb_aligned_arm64_load_segments(self) -> None:
        output = """
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x4000
  LOAD           0x004000 0x0000000000004000 0x0000000000004000 0x001000 0x001000 RW  0x4000
"""
        package_wawona_apk.validate_arm64_elf_16kb_alignment(output, "libwawona.so")

    def test_rejects_4kb_arm64_load_segments(self) -> None:
        output = """
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x1000
"""
        with self.assertRaisesRegex(ValueError, "not 16 KB page-size compatible"):
            package_wawona_apk.validate_arm64_elf_16kb_alignment(
                output,
                "libwawona.so",
            )

    def test_rejects_misaligned_arm64_load_segment(self) -> None:
        output = """
  LOAD           0x001000 0x0000000000004000 0x0000000000004000 0x001000 0x001000 R E 0x4000
"""
        with self.assertRaisesRegex(ValueError, "not congruent"):
            package_wawona_apk.validate_arm64_elf_16kb_alignment(
                output,
                "libwawona.so",
            )

    def test_rejects_arm64_elf_without_load_segments(self) -> None:
        with self.assertRaisesRegex(ValueError, "no ELF PT_LOAD segments"):
            package_wawona_apk.validate_arm64_elf_16kb_alignment(
                "There are no program headers.",
                "libwawona.so",
            )

    def test_rejects_non_arm64_elf_header(self) -> None:
        with self.assertRaisesRegex(ValueError, "not an AArch64 ELF binary"):
            package_wawona_apk.validate_arm64_elf_header(
                """
ELF Header:
  Class:                             ELF64
  Machine:                           Advanced Micro Devices X86-64
""",
                "libwawona.so",
            )


    def test_accepts_all_jni_exports_required_by_the_kotlin_adapter(self) -> None:
        output = "\\n".join(
            f"  {index}: 0000000000000000 0 FUNC GLOBAL DEFAULT 12 "
            f"{package_wawona_apk.WAWONA_JNI_CLASS_PREFIX}{method}"
            for index, method in enumerate(
                package_wawona_apk.REQUIRED_WAWONA_JNI_METHODS,
                start=1,
            )
        )
        package_wawona_apk.validate_wawona_jni_exports(output, "libwawona.so")

    def test_rejects_missing_wawona_jni_export_before_packaging(self) -> None:
        output = (
            "  1: 0000000000000000 0 FUNC GLOBAL DEFAULT 12 "
            + package_wawona_apk.WAWONA_JNI_CLASS_PREFIX
            + "nativeInit\\n"
        )
        with self.assertRaisesRegex(
            ValueError,
            "missing required Wawona JNI exports: nativeApplyHostKeyLevels",
        ):
            package_wawona_apk.validate_wawona_jni_exports(output, "libwawona.so")

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

    def test_keeps_dlopen_only_weston_shm_smoke_client(self) -> None:
        libraries = {
            "libwawona.so": b"wawona",
            "libEGL_angle.so": b"angle-egl",
            "libGLESv2_angle.so": b"angle-gles",
            "libweston_simple_shm.so": b"weston-shm",
        }
        dependencies = {
            "libwawona.so": ([], "libwawona.so"),
            "libEGL_angle.so": ([], "libEGL_angle.so"),
            "libGLESv2_angle.so": ([], "libGLESv2_angle.so"),
            "libweston_simple_shm.so": (
                ["libandroid.so", "liblog.so", "libm.so"],
                "libweston_simple_shm.so",
            ),
        }
        selected = package_wawona_apk.collect_native_closure(
            libraries,
            lambda filename, _data: dependencies[filename],
        )
        self.assertIn("libweston_simple_shm.so", selected)

    def test_keeps_optional_swiftshader_icd_when_present(self) -> None:
        libraries = {
            "libwawona.so": b"wawona",
            "libEGL_angle.so": b"angle-egl",
            "libGLESv2_angle.so": b"angle-gles",
            "libvk_swiftshader.so": b"swiftshader",
        }
        dependencies = {
            "libwawona.so": ([], "libwawona.so"),
            "libEGL_angle.so": ([], "libEGL_angle.so"),
            "libGLESv2_angle.so": ([], "libGLESv2_angle.so"),
            "libvk_swiftshader.so": (["libandroid.so"], "libvk_swiftshader.so"),
        }
        selected = package_wawona_apk.collect_native_closure(
            libraries,
            lambda filename, _data: dependencies[filename],
        )
        self.assertIn("libvk_swiftshader.so", selected)

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

    def _write_pinned_product_artifact(self, path: Path, revision: str | None = None) -> None:
        manifest = {
            "attr": "wawona-android",
            "sha": revision or package_wawona_apk.PINNED_WAWONA_REVISION,
            "outPath": "/nix/store/pinned-wawona-android",
            "artifact": "product-android-apk",
        }
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Wawona.apk", b"pinned-apk-fixture")
            archive.writestr(
                "product-manifest.json",
                json.dumps(manifest),
            )

    def test_extracts_only_the_exact_pinned_product_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "product-android-apk.zip"
            destination = root / "extracted"
            self._write_pinned_product_artifact(artifact)

            def fixture_sha(path: Path) -> str:
                return (
                    package_wawona_apk.PINNED_WAWONA_ARTIFACT_SHA256
                    if path == artifact
                    else package_wawona_apk.PINNED_WAWONA_APK_SHA256
                )

            with patch.object(package_wawona_apk, "sha256_file", side_effect=fixture_sha):
                apk = package_wawona_apk.extract_verified_pinned_artifact(
                    artifact,
                    destination,
                )

            self.assertEqual(b"pinned-apk-fixture", apk.read_bytes())
            self.assertEqual({"Wawona.apk"}, {path.name for path in destination.iterdir()})

    def test_rejects_changed_upstream_product_artifact_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "product-android-apk.zip"
            self._write_pinned_product_artifact(artifact)
            with patch.object(package_wawona_apk, "sha256_file", return_value="0" * 64):
                with self.assertRaisesRegex(ValueError, "artifact SHA-256"):
                    package_wawona_apk.extract_verified_pinned_artifact(
                        artifact,
                        root / "extracted",
                    )

    def test_rejects_product_manifest_from_another_revision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "product-android-apk.zip"
            self._write_pinned_product_artifact(artifact, revision="0" * 40)
            with patch.object(
                package_wawona_apk,
                "sha256_file",
                return_value=package_wawona_apk.PINNED_WAWONA_ARTIFACT_SHA256,
            ):
                with self.assertRaisesRegex(ValueError, "manifest mismatch for sha"):
                    package_wawona_apk.extract_verified_pinned_artifact(
                        artifact,
                        root / "extracted",
                    )

    def test_rejects_changed_apk_inside_pinned_product_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "product-android-apk.zip"
            self._write_pinned_product_artifact(artifact)

            def fixture_sha(path: Path) -> str:
                if path == artifact:
                    return package_wawona_apk.PINNED_WAWONA_ARTIFACT_SHA256
                return "0" * 64

            with patch.object(package_wawona_apk, "sha256_file", side_effect=fixture_sha):
                with self.assertRaisesRegex(ValueError, "APK SHA-256"):
                    package_wawona_apk.extract_verified_pinned_artifact(
                        artifact,
                        root / "extracted",
                    )

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


    def test_readelf_reader_checks_exported_jni_methods_on_core_library(self) -> None:
        from types import SimpleNamespace

        commands = []
        methods = package_wawona_apk.REQUIRED_WAWONA_JNI_METHODS
        dynamic_symbols = "\\n".join(
            f"  {index}: 0000000000000000 0 FUNC GLOBAL DEFAULT 12 "
            f"{package_wawona_apk.WAWONA_JNI_CLASS_PREFIX}{method}"
            for index, method in enumerate(methods, start=1)
        )
        elf_metadata = """
ELF Header:
  Class:                             ELF64
  Machine:                           AArch64
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x001000 0x001000 R E 0x4000
"""

        def fake_readelf(command, **_kwargs):
            commands.append(command)
            output = (
                elf_metadata + "\\n" + dynamic_symbols
                if "--dyn-syms" in command
                else elf_metadata
            )
            return SimpleNamespace(returncode=0, stdout=output, stderr="")

        with patch.object(package_wawona_apk.subprocess, "run", side_effect=fake_readelf):
            reader = package_wawona_apk.make_readelf_reader("llvm-readelf")
            reader("libwawona.so", b"fixture-elf")

        self.assertTrue(any("--dyn-syms" in command for command in commands))

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

    def test_hashes_each_extracted_asset_for_android_integrity_checks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            asset = root / "wawona" / "xkb" / "rules" / "evdev"
            asset.parent.mkdir(parents=True)
            asset.write_bytes(b"wayland keymap rules")

            hashes = package_wawona_apk.hash_extracted_assets(
                root,
                ["wawona/xkb/rules/evdev"],
            )

            self.assertEqual(
                {"wawona/xkb/rules/evdev": package_wawona_apk.sha256_file(asset)},
                hashes,
            )

    def test_rejects_missing_file_in_extracted_asset_hash_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "extracted Wawona asset is missing"):
                package_wawona_apk.hash_extracted_assets(
                    Path(temporary),
                    ["wawona/fonts/truetype/missing.ttf"],
                )

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
