from __future__ import annotations

import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_elf_alignment import ElfAlignmentError, load_segment_alignments


def make_elf64(
    load_alignment: int,
    *,
    virtual_address: int = 0x4000,
    load_memory_size: int = 0x1000,
    relro: tuple[int, int] | None = None,
) -> bytes:
    program_header_count = 2 if relro is not None else 1
    data = bytearray(64 + 56 * program_header_count)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into(
        "<HHIQQQIHHHHHH",
        data,
        16,
        3, 183, 1, 0, 64, 0, 0, 64, 56, program_header_count, 0, 0, 0,
    )
    struct.pack_into(
        "<IIQQQQQQ",
        data,
        64,
        1, 6, 0, virtual_address, virtual_address, len(data), load_memory_size,
        load_alignment,
    )
    if relro is not None:
        struct.pack_into(
            "<IIQQQQQQ",
            data,
            120,
            0x6474E552, 4, 0, relro[0], relro[0], relro[1], relro[1], 1,
        )
    return bytes(data)


class ElfAlignmentTests(unittest.TestCase):
    def test_accepts_16kb_load_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "libaligned.so"
            path.write_bytes(make_elf64(0x4000))
            self.assertEqual([0x4000], load_segment_alignments(path))

    def test_accepts_larger_power_of_two_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "libaligned.so"
            path.write_bytes(make_elf64(0x10000, virtual_address=0x10000))
            self.assertEqual([0x10000], load_segment_alignments(path))

    def test_rejects_4kb_load_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "libunaligned.so"
            path.write_bytes(make_elf64(0x1000))
            with self.assertRaisesRegex(ElfAlignmentError, "p_align=0x1000"):
                load_segment_alignments(path)

    def test_rejects_non_congruent_load_segment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "libunaligned.so"
            path.write_bytes(make_elf64(0x4000, virtual_address=0x5000))
            with self.assertRaisesRegex(ElfAlignmentError, "not congruent"):
                load_segment_alignments(path)

    def test_rejects_non_elf_and_truncated_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "not-elf"
            path.write_bytes(b"not an elf")
            with self.assertRaisesRegex(ElfAlignmentError, "truncated ELF header"):
                load_segment_alignments(path)

    def test_accepts_page_aligned_relro_end(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "librelro.so"
            path.write_bytes(
                make_elf64(
                    0x4000,
                    load_memory_size=0x8000,
                    relro=(0x8000, 0x4000),
                )
            )
            self.assertEqual([0x4000], load_segment_alignments(path))

    def test_accepts_relro_at_end_of_its_load_segment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "librelro.so"
            path.write_bytes(
                make_elf64(
                    0x4000,
                    load_memory_size=0x1000,
                    relro=(0x4000, 0x1000),
                )
            )
            self.assertEqual([0x4000], load_segment_alignments(path))

    def test_rejects_unaligned_relro_with_writable_data_after_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "librelro.so"
            path.write_bytes(
                make_elf64(
                    0x4000,
                    load_memory_size=0x4000,
                    relro=(0x4000, 0x1000),
                )
            )
            with self.assertRaisesRegex(ElfAlignmentError, "GNU_RELRO end"):
                load_segment_alignments(path)

    def test_rejects_invalid_minimum_alignment(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "lib.so"
            path.write_bytes(make_elf64(0x4000))
            with self.assertRaisesRegex(ValueError, "power of two"):
                load_segment_alignments(path, 12)


if __name__ == "__main__":
    unittest.main()
