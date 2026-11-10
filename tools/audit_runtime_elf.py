#!/usr/bin/env python3
"""Inspect ELF loaders and shared-library dependencies inside a guest rootfs.

This is a static diagnostic. It never executes files from the inspected rootfs.
It checks the ARM64 glibc guest and x86-64 Wine libraries only; the Android/
Bionic PRoot host payload is supplied by the Android OS separately.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
EM_X86_64 = 62
EM_AARCH64 = 183
PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_RPATH = 15
DT_RUNPATH = 29
MAX_PROGRAM_HEADERS = 4096
MAX_DYNAMIC_BYTES = 16 * 1024 * 1024
MAX_STRING_TABLE_BYTES = 64 * 1024 * 1024


class ElfAuditError(ValueError):
    pass


@dataclass(frozen=True)
class ElfInfo:
    path: Path
    machine: int
    interpreter: str | None
    needed: tuple[str, ...]
    rpath: tuple[str, ...]
    runpath: tuple[str, ...]


def _read_exact(stream, offset: int, length: int, file_size: int, label: str) -> bytes:
    if offset < 0 or length < 0 or offset + length > file_size:
        raise ElfAuditError(f"{label} lies outside the ELF file")
    stream.seek(offset)
    data = stream.read(length)
    if len(data) != length:
        raise ElfAuditError(f"short read while reading {label}")
    return data


def read_elf(path: Path) -> ElfInfo | None:
    """Return ELF metadata, None for non-ELF files, or raise for malformed ELF."""
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            file_size = stream.tell()
            if file_size < 4:
                return None

            stream.seek(0)
            ident = stream.read(16)
            if ident[:4] != ELF_MAGIC:
                return None
            if len(ident) != 16:
                raise ElfAuditError("truncated ELF identification")
            if ident[4] != ELFCLASS64:
                raise ElfAuditError(f"unsupported ELF class {ident[4]} (64-bit required)")
            if ident[5] != ELFDATA2LSB:
                raise ElfAuditError(f"unsupported ELF byte order {ident[5]} (little-endian required)")

            header = _read_exact(stream, 16, 48, file_size, "ELF64 header")
            (
                _elf_type,
                machine,
                _version,
                _entry,
                program_header_offset,
                _section_header_offset,
                _flags,
                header_size,
                program_header_size,
                program_header_count,
                _section_header_size,
                _section_header_count,
                _section_name_index,
            ) = struct.unpack("<HHIQQQIHHHHHH", header)

            if header_size < 64 or program_header_count > MAX_PROGRAM_HEADERS:
                raise ElfAuditError("invalid ELF header or excessive program-header count")
            if program_header_count and program_header_size < 56:
                raise ElfAuditError("ELF64 program-header entries are too small")

            load_segments: list[tuple[int, int, int]] = []
            dynamic_segment: tuple[int, int] | None = None
            interpreter: str | None = None

            for index in range(program_header_count):
                offset = program_header_offset + index * program_header_size
                raw = _read_exact(stream, offset, 56, file_size, "ELF program header")
                (
                    segment_type,
                    _segment_flags,
                    segment_offset,
                    virtual_address,
                    _physical_address,
                    file_bytes,
                    _memory_bytes,
                    _alignment,
                ) = struct.unpack("<IIQQQQQQ", raw)

                if segment_type == PT_LOAD:
                    load_segments.append((virtual_address, file_bytes, segment_offset))
                elif segment_type == PT_DYNAMIC:
                    if file_bytes > MAX_DYNAMIC_BYTES:
                        raise ElfAuditError("ELF dynamic table is unexpectedly large")
                    dynamic_segment = (segment_offset, file_bytes)
                elif segment_type == PT_INTERP:
                    if file_bytes < 2 or file_bytes > 4096:
                        raise ElfAuditError("invalid ELF interpreter path length")
                    raw_interpreter = _read_exact(
                        stream, segment_offset, file_bytes, file_size, "ELF interpreter"
                    )
                    interpreter = raw_interpreter.split(b"\x00", 1)[0].decode(
                        "utf-8", errors="strict"
                    )

            if dynamic_segment is None:
                return ElfInfo(path, machine, interpreter, (), (), ())

            dynamic_offset, dynamic_size = dynamic_segment
            if dynamic_size % 16:
                raise ElfAuditError("ELF dynamic table is not entry-aligned")
            dynamic_data = _read_exact(
                stream, dynamic_offset, dynamic_size, file_size, "ELF dynamic table"
            )

            string_table_address: int | None = None
            string_table_size: int | None = None
            needed_offsets: list[int] = []
            rpath_offsets: list[int] = []
            runpath_offsets: list[int] = []

            for position in range(0, len(dynamic_data), 16):
                tag, value = struct.unpack_from("<qQ", dynamic_data, position)
                if tag == DT_NULL:
                    break
                if tag == DT_NEEDED:
                    needed_offsets.append(value)
                elif tag == DT_STRTAB:
                    string_table_address = value
                elif tag == DT_STRSZ:
                    string_table_size = value
                elif tag == DT_RPATH:
                    rpath_offsets.append(value)
                elif tag == DT_RUNPATH:
                    runpath_offsets.append(value)

            if not (needed_offsets or rpath_offsets or runpath_offsets):
                return ElfInfo(path, machine, interpreter, (), (), ())
            if string_table_address is None or string_table_size is None:
                raise ElfAuditError("ELF has dynamic strings but no DT_STRTAB/DT_STRSZ")
            if string_table_size > MAX_STRING_TABLE_BYTES:
                raise ElfAuditError("ELF string table is unexpectedly large")

            string_table_offset = None
            for virtual_start, file_bytes, file_start in load_segments:
                if virtual_start <= string_table_address < virtual_start + file_bytes:
                    string_table_offset = file_start + string_table_address - virtual_start
                    break
            if string_table_offset is None:
                raise ElfAuditError("ELF DT_STRTAB is not mapped by a PT_LOAD segment")

            string_table = _read_exact(
                stream,
                string_table_offset,
                string_table_size,
                file_size,
                "ELF dynamic string table",
            )

            def get_string(value: int) -> str:
                if value < 0 or value >= len(string_table):
                    raise ElfAuditError("ELF dynamic string offset is out of range")
                end = string_table.find(b"\x00", value)
                if end < 0:
                    raise ElfAuditError("ELF dynamic string is not NUL-terminated")
                return string_table[value:end].decode("utf-8", errors="strict")

            needed = tuple(get_string(offset) for offset in needed_offsets)
            rpath = tuple(get_string(offset) for offset in rpath_offsets)
            runpath = tuple(get_string(offset) for offset in runpath_offsets)
            return ElfInfo(path, machine, interpreter, needed, rpath, runpath)
    except (OSError, UnicodeDecodeError, struct.error) as error:
        raise ElfAuditError(str(error)) from error


def _architecture(machine: int) -> str:
    return {
        EM_AARCH64: "aarch64",
        EM_X86_64: "x86_64",
    }.get(machine, f"unsupported-machine-{machine}")


def _resolve_guest_path(root: Path, candidate: Path) -> Path | None:
    """Resolve a path using Linux guest-root semantics without host-root escapes."""
    try:
        relative = candidate.relative_to(root)
    except ValueError:
        return None

    resolved_parts: list[str] = []
    pending = list(relative.parts)
    visited_states: set[tuple[tuple[str, ...], tuple[str, ...]]] = set()

    while pending:
        component = pending.pop(0)
        if component in {"", "."}:
            continue
        if component == "..":
            if not resolved_parts:
                return None
            resolved_parts.pop()
            continue

        resolved_parts.append(component)
        link = root.joinpath(*resolved_parts)
        if not link.is_symlink():
            continue

        state = (tuple(resolved_parts), tuple(pending))
        if state in visited_states:
            return None
        visited_states.add(state)

        try:
            target = os.readlink(link)
        except OSError:
            return None

        resolved_parts.pop()
        if target.startswith("/"):
            resolved_parts = []
            pending = target.lstrip("/").split("/") + pending
        else:
            pending = target.split("/") + pending

    resolved = root.joinpath(*resolved_parts)
    try:
        resolved.relative_to(root)
    except ValueError:
        return None
    return resolved


def _inside(root: Path, candidate: Path) -> Path | None:
    try:
        root_real = root.resolve(strict=True)
        resolved = _resolve_guest_path(root_real, candidate)
        return resolved if resolved is not None and resolved.is_file() else None
    except (OSError, ValueError, RuntimeError):
        return None


def _expanded_rpath_directories(
    root: Path, origin: Path, value: str, architecture: str
) -> list[Path]:
    triplet = "x86_64-linux-gnu" if architecture == "x86_64" else "aarch64-linux-gnu"
    library_tokens = ("lib64", f"lib/{triplet}", "lib")
    results: list[Path] = []

    for entry in value.split(":"):
        if not entry:
            continue

        has_origin = "$ORIGIN" in entry or "${ORIGIN}" in entry
        expanded = entry.replace("${ORIGIN}", str(origin)).replace("$ORIGIN", str(origin))
        expanded = expanded.replace("${PLATFORM}", architecture).replace("$PLATFORM", architecture)
        guest_absolute = entry.startswith("/") and not has_origin

        token_values = library_tokens if "$LIB" in expanded or "${LIB}" in expanded else ("",)
        for library_token in token_values:
            candidate_text = expanded
            if library_token:
                candidate_text = candidate_text.replace("${LIB}", library_token).replace("$LIB", library_token)
            candidate = Path(candidate_text)
            if guest_absolute:
                results.append(root / candidate.as_posix().lstrip("/"))
            elif candidate.is_absolute():
                results.append(candidate)
            else:
                results.append(origin / candidate)
    return results


def _library_directories(root: Path, info: ElfInfo) -> list[Path]:
    architecture = _architecture(info.machine)
    origin = info.path.parent
    directories: list[Path] = []

    for value in info.rpath + info.runpath:
        directories.extend(_expanded_rpath_directories(root, origin, value, architecture))

    # Box64's configured module search path includes Wine's x86_64-unix
    # directory. Debian also keeps private DSOs beside consumers (for example
    # systemd/libsystemd-core and libsystemd-shared). Treat co-located files as
    # available, but still validate their ELF machine below.
    directories.append(origin)

    triplet = "x86_64-linux-gnu" if architecture == "x86_64" else "aarch64-linux-gnu"
    for relative in (
        f"lib/{triplet}",
        f"usr/lib/{triplet}",
        "lib64",
        "usr/lib64",
        "lib",
        "usr/lib",
        "usr/local/lib",
    ):
        directories.append(root / relative)
    return directories


def _dependency_candidates(root: Path, info: ElfInfo, soname: str) -> list[Path]:
    if soname == "linux-vdso.so.1":
        return []

    if "/" in soname:
        candidate = Path(soname)
        if candidate.is_absolute():
            return [root / candidate.as_posix().lstrip("/")]
        return [info.path.parent / candidate]

    return [directory / soname for directory in _library_directories(root, info)]


def _dependency_status(root: Path, info: ElfInfo, soname: str) -> str:
    if soname == "linux-vdso.so.1":
        return "ok"

    saw_wrong_architecture = False
    for candidate in _dependency_candidates(root, info, soname):
        resolved = _inside(root, candidate)
        if resolved is None:
            continue
        try:
            library_info = read_elf(resolved)
        except ElfAuditError:
            continue
        if library_info is None:
            continue
        if library_info.machine == info.machine:
            return "ok"
        saw_wrong_architecture = True

    return "wrong-architecture" if saw_wrong_architecture else "missing"


def _dependency_exists(root: Path, info: ElfInfo, soname: str) -> bool:
    return _dependency_status(root, info, soname) == "ok"


def audit_rootfs(rootfs: Path) -> dict:
    root = rootfs.resolve(strict=True)
    if not root.is_dir():
        raise ElfAuditError("rootfs path is not a directory")

    elf_files: list[ElfInfo] = []
    issues: list[dict[str, str]] = []
    architectures: dict[str, int] = {}

    for current, directory_names, file_names in os.walk(root, followlinks=False):
        current_path = Path(current)
        directory_names[:] = [
            name for name in directory_names
            if not (current_path / name).is_symlink()
        ]

        for name in file_names:
            path = current_path / name
            if path.is_symlink() or not path.is_file():
                continue
            try:
                info = read_elf(path)
            except ElfAuditError as error:
                try:
                    with path.open("rb") as stream:
                        looks_like_elf = stream.read(4) == ELF_MAGIC
                except OSError:
                    looks_like_elf = False
                if looks_like_elf:
                    issues.append({
                        "kind": "malformed-elf",
                        "file": path.relative_to(root).as_posix(),
                        "detail": str(error),
                    })
                continue

            if info is None:
                continue

            architecture = _architecture(info.machine)
            if architecture.startswith("unsupported-"):
                issues.append({
                    "kind": "unsupported-architecture",
                    "file": path.relative_to(root).as_posix(),
                    "detail": f"ELF machine {info.machine} is not part of this backend",
                })
                continue

            architectures[architecture] = architectures.get(architecture, 0) + 1
            elf_files.append(info)

    for info in elf_files:
        relative = info.path.relative_to(root).as_posix()
        architecture = _architecture(info.machine)

        if info.interpreter:
            if not info.interpreter.startswith("/"):
                issues.append({
                    "kind": "invalid-interpreter",
                    "file": relative,
                    "detail": f"PT_INTERP is not an absolute guest path: {info.interpreter}",
                })
            else:
                loader = _inside(root, root / info.interpreter.lstrip("/"))
                if loader is None:
                    issues.append({
                        "kind": "missing-interpreter",
                        "file": relative,
                        "detail": info.interpreter,
                    })
                else:
                    try:
                        loader_info = read_elf(loader)
                    except ElfAuditError:
                        loader_info = None
                    if loader_info is None or loader_info.machine != info.machine:
                        issues.append({
                            "kind": "interpreter-architecture",
                            "file": relative,
                            "detail": f"{info.interpreter} does not match {architecture}",
                        })

        for soname in info.needed:
            dependency_status = _dependency_status(root, info, soname)
            if dependency_status == "missing":
                issues.append({
                    "kind": "missing-library",
                    "file": relative,
                    "detail": soname,
                })
            elif dependency_status == "wrong-architecture":
                issues.append({
                    "kind": "library-architecture",
                    "file": relative,
                    "detail": f"{soname} does not match {_architecture(info.machine)}",
                })

    return {
        "rootfs": str(root),
        "elfCount": len(elf_files),
        "architectures": dict(sorted(architectures.items())),
        "issues": issues,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", required=True, type=Path)
    parser.add_argument(
        "--strict", action="store_true",
        help="return exit code 1 when any issue is found"
    )
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        report = audit_rootfs(args.rootfs)
    except (OSError, ElfAuditError) as error:
        print(f"ELF audit failed: {error}", file=sys.stderr)
        return 2

    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(f"Rootfs: {report['rootfs']}")
        print(f"ELF files: {report['elfCount']}")
        print("Architectures: " + ", ".join(
            f"{name}={count}" for name, count in report["architectures"].items()
        ))
        for issue in report["issues"]:
            print(
                f"{issue['kind']}: {issue['file']}: {issue['detail']}",
                file=sys.stderr,
            )
        if not report["issues"]:
            print("No missing interpreters or direct shared-library dependencies found.")

    return 1 if args.strict and report["issues"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
