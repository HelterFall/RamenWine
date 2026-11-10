from __future__ import annotations

import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import import_wawona_android


class WawonaReleaseImporterTests(unittest.TestCase):
    def test_pins_the_official_prebuilt_release_asset(self) -> None:
        self.assertEqual(import_wawona_android.PINNED_RELEASE_TAG, "v26.8.12")
        self.assertEqual(import_wawona_android.PINNED_SOURCE_VERSION, "26.8.12")
        self.assertEqual(
            import_wawona_android.PINNED_REVISION,
            "75ffe2982bd7d99b29b7e743c4f70a9becf61f86",
        )
        self.assertEqual(
            import_wawona_android.PINNED_APK_SHA256,
            "f9a3f82ac9c6e47701622f9f3d9be355cb549438e34c49760c7ac9b528dc5570",
        )
        self.assertTrue(import_wawona_android.PINNED_APK_URL.endswith(
            "/v26.8.12/Wawona-26.8.12.apk"
        ))

    def test_jni_symbol_guard_matches_every_native_method_in_the_ramenwine_wrapper(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        wrapper = repository_root / (
            "app/src/main/kotlin/com/aspauldingcode/wawona/WawonaNative.kt"
        )
        declared_methods = set(
            re.findall(
                r"\\bexternal\\s+fun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(",
                wrapper.read_text(encoding="utf-8"),
            )
        )
        required_methods = {
            symbol.rsplit("_", 1)[-1]
            for symbol in import_wawona_android.REQUIRED_JNI_SYMBOLS
        }
        self.assertTrue(declared_methods, "The Wawona Kotlin wrapper declares no JNI methods.")
        self.assertEqual(declared_methods, required_methods)

    def test_accepts_arm64_elf_and_rejects_x86_64(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            arm64 = Path(temporary) / "libwawona.so"
            header = bytearray(64)
            header[:7] = b"\x7fELF\x02\x01\x01"
            struct.pack_into("<H", header, 18, 183)
            arm64.write_bytes(header)
            import_wawona_android.check_arm64_elf(arm64)

            x86_64 = Path(temporary) / "libwrong.so"
            struct.pack_into("<H", header, 18, 62)
            x86_64.write_bytes(header)
            with self.assertRaisesRegex(ValueError, "not AArch64"):
                import_wawona_android.check_arm64_elf(x86_64)

    def test_requires_all_jni_exports_used_by_the_adapter(self) -> None:
        output = "\n".join(
            f"  {index}: 00000000 0 FUNC GLOBAL DEFAULT 12 {symbol}"
            for index, symbol in enumerate(sorted(import_wawona_android.REQUIRED_JNI_SYMBOLS))
        )
        self.assertEqual(
            import_wawona_android.missing_required_jni_symbols(output),
            [],
        )
        partial = output.replace(
            "Java_com_aspauldingcode_wawona_WawonaNative_nativeDestroySurface",
            "Java_com_aspauldingcode_wawona_WawonaNative_nativeNotExported",
        )
        self.assertEqual(
            import_wawona_android.missing_required_jni_symbols(partial),
            ["Java_com_aspauldingcode_wawona_WawonaNative_nativeDestroySurface"],
        )

    def test_rejects_unsafe_archive_paths(self) -> None:
        for path in ("../outside", "/absolute", r"a\b", "a/../b"):
            with self.subTest(path=path):
                with self.assertRaises(ValueError):
                    import_wawona_android.safe_member_name(path)


if __name__ == "__main__":
    unittest.main()
