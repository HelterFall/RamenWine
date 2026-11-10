#!/usr/bin/env python3
"""Select the AMD64 PE32+ wineboot.exe from a Wine distribution tree."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


def is_amd64_pe32_plus(path: Path) -> bool:
    """Return whether path is a structurally valid AMD64 PE32+ executable."""
    try:
        with path.open("rb") as stream:
            dos_header = stream.read(64)
            if len(dos_header) < 64 or dos_header[:2] != b"MZ":
                return False
            pe_offset = int.from_bytes(dos_header[0x3C:0x40], "little")
            if pe_offset < 64 or pe_offset > 16 * 1024 * 1024:
                return False
            stream.seek(pe_offset)
            header = stream.read(26)
    except OSError:
        return False

    return (
        len(header) == 26
        and header[:4] == b"PE\0\0"
        and int.from_bytes(header[4:6], "little") == 0x8664
        and int.from_bytes(header[24:26], "little") == 0x20B
    )


def select_wineboot(root: Path) -> Path | None:
    """Choose an AMD64 PE32+ wineboot, preferring the x86-64 Wine DLL tree."""
    candidates = (
        path for path in root.rglob("wineboot.exe")
        if path.is_file() and is_amd64_pe32_plus(path)
    )
    valid = list(candidates)
    valid.sort(
        key=lambda path: (
            0 if "x86_64-windows" in path.parts else 1,
            len(path.parts),
            path.as_posix(),
        )
    )
    return valid[0] if valid else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wine_root", type=Path)
    args = parser.parse_args()

    if not args.wine_root.is_dir():
        print(f"Wine distribution directory does not exist: {args.wine_root}", file=sys.stderr)
        return 2

    selected = select_wineboot(args.wine_root)
    if selected is None:
        print("No AMD64 PE32+ wineboot.exe found in the Wine distribution.", file=sys.stderr)
        return 1

    print(selected)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
