#!/usr/bin/env python3
"""Validate 16-KB ELF PT_LOAD alignment without executing the inspected binary."""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
ELF64_HEADER_SIZE = 64
ELF64_PROGRAM_HEADER_SIZE = 56
DEFAULT_MIN_ALIGNMENT = 16 * 1024


class ElfAlignmentError(ValueError):
    """The input is not a valid 16-KB-compatible ELF64 image."""


def load_segment_alignments(
    path: Path,
    minimum_alignment: int = DEFAULT_MIN_ALIGNMENT,
) -> list[int]:
    """Return PT_LOAD p_align values, failing if any are below the requested size."""
    if minimum_alignment <= 0 or minimum_alignment & (minimum_alignment - 1):
        raise ValueError("minimum alignment must be a positive power of two")

    try:
        with path.open("rb") as stream:
            stream.seek(0, 2)
            file_size = stream.tell()
            if file_size < ELF64_HEADER_SIZE:
                raise ElfAlignmentError(f"{path}: truncated ELF header")

            stream.seek(0)
            header = stream.read(ELF64_HEADER_SIZE)
            if len(header) != ELF64_HEADER_SIZE or header[:4] != ELF_MAGIC:
                raise ElfAlignmentError(f"{path}: not an ELF file")
            if header[4] != ELFCLASS64:
                raise ElfAlignmentError(f"{path}: ELF64 is required")
            if header[5] != ELFDATA2LSB:
                raise ElfAlignmentError(f"{path}: little-endian ELF is required")

            fields = struct.unpack_from("<HHIQQQIHHHHHH", header, 16)
            program_header_offset = fields[4]
            program_header_size = fields[8]
            program_header_count = fields[9]
            if program_header_count <= 0:
                raise ElfAlignmentError(f"{path}: no program headers")
            if program_header_size < ELF64_PROGRAM_HEADER_SIZE:
                raise ElfAlignmentError(f"{path}: ELF64 program headers are too small")
            program_headers_end = (
                program_header_offset + program_header_size * program_header_count
            )
            if program_header_offset < ELF64_HEADER_SIZE or program_headers_end > file_size:
                raise ElfAlignmentError(f"{path}: program headers lie outside the file")

            alignments: list[int] = []
            load_ranges: list[tuple[int, int]] = []
            relro_ranges: list[tuple[int, int]] = []
            for index in range(program_header_count):
                offset = program_header_offset + index * program_header_size
                stream.seek(offset)
                raw = stream.read(ELF64_PROGRAM_HEADER_SIZE)
                if len(raw) != ELF64_PROGRAM_HEADER_SIZE:
                    raise ElfAlignmentError(f"{path}: truncated program header {index}")
                (
                    segment_type,
                    _flags,
                    segment_offset,
                    virtual_address,
                    _physical_address,
                    _file_size,
                    _memory_size,
                    alignment,
                ) = struct.unpack("<IIQQQQQQ", raw)
                if segment_type == PT_GNU_RELRO:
                    relro_ranges.append((virtual_address, _memory_size))
                    continue
                if segment_type != PT_LOAD:
                    continue
                load_ranges.append((virtual_address, virtual_address + _memory_size))
                if alignment < minimum_alignment:
                    raise ElfAlignmentError(
                        f"{path}: PT_LOAD #{len(alignments) + 1} has p_align=0x{alignment:x}; "
                        f"minimum is 0x{minimum_alignment:x}"
                    )
                if alignment & (alignment - 1):
                    raise ElfAlignmentError(
                        f"{path}: PT_LOAD #{len(alignments) + 1} p_align is not a power of two"
                    )
                if alignment > 1 and segment_offset % alignment != virtual_address % alignment:
                    raise ElfAlignmentError(
                        f"{path}: PT_LOAD #{len(alignments) + 1} file offset and virtual "
                        f"address are not congruent at p_align=0x{alignment:x}"
                    )
                alignments.append(alignment)

            if not alignments:
                raise ElfAlignmentError(f"{path}: no PT_LOAD segments")

            for relro_start, relro_size in relro_ranges:
                relro_end = relro_start + relro_size
                if relro_end % minimum_alignment == 0:
                    continue
                # Android also accepts a non-page-aligned RELRO end when the
                # containing LOAD segment ends at exactly the same address,
                # leaving no writable .data/.bss after RELRO in that page.
                if any(
                    load_start <= relro_start and load_end == relro_end
                    for load_start, load_end in load_ranges
                ):
                    continue
                raise ElfAlignmentError(
                    f"{path}: GNU_RELRO end 0x{relro_end:x} is not aligned to "
                    f"0x{minimum_alignment:x} and writable LOAD data follows it"
                )
            return alignments
    except OSError as error:
        raise ElfAlignmentError(f"{path}: {error}") from error


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument(
        "--minimum-alignment",
        type=lambda value: int(value, 0),
        default=DEFAULT_MIN_ALIGNMENT,
    )
    args = parser.parse_args()

    failed = False
    for path in args.files:
        try:
            alignments = load_segment_alignments(path, args.minimum_alignment)
        except (ElfAlignmentError, ValueError) as error:
            print(f"UNALIGNED: {error}", file=sys.stderr)
            failed = True
        else:
            rendered = ", ".join(f"0x{value:x}" for value in alignments)
            print(f"ALIGNED: {path}: PT_LOAD p_align=[{rendered}]")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
