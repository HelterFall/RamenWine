from __future__ import annotations

from pathlib import Path
import struct
import tempfile
import unittest

from select_wineboot import is_amd64_pe32_plus, select_wineboot


def pe_executable(machine: int, magic: int) -> bytes:
    data = bytearray(0x100)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3C, 0x40)
    data[0x40:0x44] = b"PE\0\0"
    struct.pack_into("<H", data, 0x44, machine)
    struct.pack_into("<H", data, 0x58, magic)
    return bytes(data)


class WinebootSelectionTests(unittest.TestCase):
    def test_accepts_amd64_pe32_plus(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "wineboot.exe"
            path.write_bytes(pe_executable(0x8664, 0x20B))
            self.assertTrue(is_amd64_pe32_plus(path))

    def test_rejects_i386_pe32_even_when_it_is_found_first(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrong = root / "lib/wine/i386-windows/wineboot.exe"
            right = root / "lib/wine/x86_64-windows/wineboot.exe"
            wrong.parent.mkdir(parents=True)
            right.parent.mkdir(parents=True)
            wrong.write_bytes(pe_executable(0x14C, 0x10B))
            right.write_bytes(pe_executable(0x8664, 0x20B))

            self.assertFalse(is_amd64_pe32_plus(wrong))
            self.assertEqual(right, select_wineboot(root))

    def test_rejects_amd64_pe32_instead_of_pe32_plus(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "wineboot.exe"
            path.write_bytes(pe_executable(0x8664, 0x10B))
            self.assertFalse(is_amd64_pe32_plus(path))

    def test_returns_none_when_distribution_has_no_wineboot(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            self.assertIsNone(select_wineboot(Path(temporary)))


if __name__ == "__main__":
    unittest.main()
