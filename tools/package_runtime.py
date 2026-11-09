#!/usr/bin/env python3
"""Create a deterministic, SHA-256-indexed RamenWine runtime ZIP package.

The input directory must already contain binaries built for the requested
backend. This tool does not download, build, patch, or execute Wine components.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import stat
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable

MAX_ENTRIES = 100_000
MAX_TOTAL_BYTES = 64 * 1024 * 1024 * 1024
CHUNK_SIZE = 256 * 1024
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

BACKENDS = {
    "bionic-arm64ec-fex",
    "bionic-x64-box64",
    "glibc-proot-box64",
}


@dataclass(frozen=True)
class PackageFile:
    relative_path: str
    source_path: Path
    sha256: str
    executable: bool
    size_bytes: int


@dataclass(frozen=True)
class PackageSymlink:
    relative_path: str
    target: str



@dataclass(frozen=True)
class Elf64Info:
    machine: int
    interpreter: str | None
    needed: tuple[str, ...] = ()
    rpath: tuple[str, ...] = ()
    runpath: tuple[str, ...] = ()
    load_alignment_segments: tuple[tuple[int, int, int], ...] = ()


ELF_MACHINE_AARCH64 = 183
ELF_MACHINE_X86_64 = 62
ELF_PT_LOAD = 1
ANDROID_MIN_ELF_PAGE_SIZE = 16 * 1024
ELF_PT_DYNAMIC = 2
ELF_PT_INTERP = 3
ELF_DT_NULL = 0
ELF_DT_NEEDED = 1
ELF_DT_STRTAB = 5
ELF_DT_STRSZ = 10
ELF_DT_RPATH = 15
ELF_DT_RUNPATH = 29
PE_MACHINE_AMD64 = 0x8664
PE_MACHINE_I386 = 0x014C

# Public Android NDK libraries available to an app at the project's minSdk 26.
# Anything else needed by the Bionic PRoot host must be shipped under host/lib.
ANDROID_PUBLIC_SYSTEM_LIBRARIES = frozenset({
    "libaaudio.so",
    "libandroid.so",
    "libcamera2ndk.so",
    "libc.so",
    "libdl.so",
    "libEGL.so",
    "libGLESv2.so",
    "libjnigraphics.so",
    "liblog.so",
    "libm.so",
    "libmediandk.so",
    "libnativewindow.so",
    "libOpenSLES.so",
    "libvulkan.so",
    "libz.so",
})


def resolve_guest_file(rootfs: Path, guest_path: str) -> Path:
    """Resolve a guest absolute path, interpreting absolute symlinks inside rootfs."""
    if not guest_path.startswith("/") or "\\" in guest_path or "\x00" in guest_path:
        raise ValueError(f"unsafe guest path: {guest_path!r}")

    root_real = rootfs.resolve(strict=True)
    pending = list(PurePosixPath(guest_path).parts[1:])
    resolved_parts: list[str] = []
    followed_links = 0

    while pending:
        part = pending.pop(0)
        if part in ("", "."):
            continue
        if part == "..":
            if not resolved_parts:
                raise ValueError(f"guest symlink escapes rootfs: {guest_path}")
            resolved_parts.pop()
            continue

        candidate = root_real.joinpath(*resolved_parts, part)
        try:
            metadata = candidate.lstat()
        except OSError as error:
            raise ValueError(f"required guest path is missing: {guest_path}") from error

        if stat.S_ISLNK(metadata.st_mode):
            followed_links += 1
            if followed_links > 40:
                raise ValueError(f"too many guest symlinks while resolving: {guest_path}")
            target = os.readlink(candidate)
            if not target or "\\" in target or "\x00" in target:
                raise ValueError(f"invalid guest symlink target: {guest_path}")
            target_path = PurePosixPath(target)
            if target_path.is_absolute():
                resolved_parts = []
                target_parts = list(target_path.parts[1:])
            else:
                target_parts = list(target_path.parts)
            pending = target_parts + pending
            continue

        if pending and not stat.S_ISDIR(metadata.st_mode):
            raise ValueError(f"non-directory component in guest path: {guest_path}")
        resolved_parts.append(part)

    result = root_real.joinpath(*resolved_parts)
    try:
        metadata = result.stat()
    except OSError as error:
        raise ValueError(f"required guest path is missing: {guest_path}") from error
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError(f"required guest path is not a regular file: {guest_path}")
    return result


def read_elf64(path: Path, label: str) -> Elf64Info:
    """Read ELF64 ABI, interpreter and DT_NEEDED/RPATH metadata without host tools."""
    try:
        file_size = path.stat().st_size
        with path.open("rb") as stream:
            ident = stream.read(16)
            if len(ident) != 16 or ident[:4] != b"\x7fELF":
                raise ValueError(f"{label} is not an ELF file: {path}")
            if ident[4] != 2:
                raise ValueError(f"{label} must be ELF64: {path}")
            if ident[5] != 1:
                raise ValueError(f"{label} must be little-endian ELF: {path}")

            header = ident + stream.read(48)
            if len(header) != 64:
                raise ValueError(f"{label} has a truncated ELF header: {path}")
            fields = struct.unpack_from("<HHIQQQIHHHHHH", header, 16)
            machine = fields[1]
            program_header_offset = fields[4]
            program_header_size = fields[8]
            program_header_count = fields[9]
            if program_header_count > 4096:
                raise ValueError(f"{label} has an unreasonable ELF program-header count.")
            if program_header_count and program_header_size < 56:
                raise ValueError(f"{label} has an invalid ELF program-header size.")

            interpreter: str | None = None
            load_segments: list[tuple[int, int, int]] = []
            load_alignment_segments: list[tuple[int, int, int]] = []
            dynamic_segment: tuple[int, int] | None = None
            for index in range(program_header_count):
                offset = program_header_offset + index * program_header_size
                if offset < 64 or offset + 56 > file_size:
                    raise ValueError(f"{label} has a truncated ELF program header.")
                stream.seek(offset)
                entry = stream.read(56)
                if len(entry) != 56:
                    raise ValueError(f"{label} has a truncated ELF program header.")
                values = struct.unpack("<IIQQQQQQ", entry)
                segment_type = values[0]
                segment_offset = values[2]
                virtual_address = values[3]
                segment_file_size = values[5]

                if segment_type == ELF_PT_INTERP:
                    if interpreter is not None:
                        raise ValueError(f"{label} has multiple PT_INTERP segments.")
                    if (
                        segment_file_size < 2
                        or segment_file_size > 4096
                        or segment_offset + segment_file_size > file_size
                    ):
                        raise ValueError(f"{label} has an invalid PT_INTERP segment.")
                    stream.seek(segment_offset)
                    raw_interpreter = stream.read(segment_file_size)
                    if (
                        len(raw_interpreter) != segment_file_size
                        or not raw_interpreter.endswith(b"\x00")
                        or b"\x00" in raw_interpreter[:-1]
                    ):
                        raise ValueError(f"{label} has a malformed PT_INTERP string.")
                    interpreter = raw_interpreter[:-1].decode("ascii")
                    if not interpreter.startswith("/"):
                        raise ValueError(f"{label} has a non-absolute ELF interpreter.")
                elif segment_type == ELF_PT_LOAD:
                    if segment_offset + segment_file_size > file_size:
                        raise ValueError(f"{label} has a truncated ELF load segment.")
                    load_segments.append((segment_offset, virtual_address, segment_file_size))
                    load_alignment_segments.append((segment_offset, virtual_address, values[7]))
                elif segment_type == ELF_PT_DYNAMIC:
                    if dynamic_segment is not None:
                        raise ValueError(f"{label} has multiple PT_DYNAMIC segments.")
                    if (
                        segment_file_size > 16 * 1024 * 1024
                        or segment_file_size % 16 != 0
                        or segment_offset + segment_file_size > file_size
                    ):
                        raise ValueError(f"{label} has an invalid PT_DYNAMIC segment.")
                    dynamic_segment = (segment_offset, segment_file_size)

            needed: list[str] = []
            rpath: list[str] = []
            runpath: list[str] = []
            if dynamic_segment is not None:
                dynamic_offset, dynamic_size = dynamic_segment
                stream.seek(dynamic_offset)
                dynamic_data = stream.read(dynamic_size)
                if len(dynamic_data) != dynamic_size:
                    raise ValueError(f"{label} has a truncated PT_DYNAMIC segment.")

                dynamic_tags: list[tuple[int, int]] = []
                for offset in range(0, dynamic_size, 16):
                    tag, value = struct.unpack_from("<qQ", dynamic_data, offset)
                    if tag == ELF_DT_NULL:
                        break
                    dynamic_tags.append((tag, value))

                has_string_references = any(
                    tag in (ELF_DT_NEEDED, ELF_DT_RPATH, ELF_DT_RUNPATH)
                    for tag, _ in dynamic_tags
                )
                if has_string_references:
                    string_table_address = next(
                        (value for tag, value in dynamic_tags if tag == ELF_DT_STRTAB),
                        None,
                    )
                    string_table_size = next(
                        (value for tag, value in dynamic_tags if tag == ELF_DT_STRSZ),
                        None,
                    )
                    if (
                        string_table_address is None
                        or string_table_size is None
                        or string_table_size < 1
                        or string_table_size > 64 * 1024 * 1024
                    ):
                        raise ValueError(f"{label} has an invalid dynamic string table.")

                    string_table_offset: int | None = None
                    for file_offset, virtual_address, segment_size in load_segments:
                        if (
                            string_table_address >= virtual_address
                            and string_table_address + string_table_size
                            <= virtual_address + segment_size
                        ):
                            string_table_offset = (
                                file_offset + string_table_address - virtual_address
                            )
                            break
                    if (
                        string_table_offset is None
                        or string_table_offset + string_table_size > file_size
                    ):
                        raise ValueError(f"{label} has an unmapped dynamic string table.")

                    stream.seek(string_table_offset)
                    string_table = stream.read(string_table_size)
                    if len(string_table) != string_table_size:
                        raise ValueError(f"{label} has a truncated dynamic string table.")

                    def dynamic_string(string_offset: int) -> str:
                        if string_offset >= len(string_table):
                            raise ValueError(f"{label} has an out-of-range dynamic string.")
                        terminator = string_table.find(b"\x00", string_offset)
                        if terminator < 0:
                            raise ValueError(f"{label} has an unterminated dynamic string.")
                        try:
                            return string_table[string_offset:terminator].decode("utf-8")
                        except UnicodeDecodeError as error:
                            raise ValueError(
                                f"{label} has a non-UTF-8 dynamic string."
                            ) from error

                    for tag, value in dynamic_tags:
                        if tag == ELF_DT_NEEDED:
                            dependency = dynamic_string(value)
                            if not dependency:
                                raise ValueError(f"{label} has an empty DT_NEEDED entry.")
                            needed.append(dependency)
                        elif tag == ELF_DT_RPATH:
                            rpath.extend(filter(None, dynamic_string(value).split(":")))
                        elif tag == ELF_DT_RUNPATH:
                            runpath.extend(filter(None, dynamic_string(value).split(":")))

            return Elf64Info(
                machine=machine,
                interpreter=interpreter,
                needed=tuple(needed),
                rpath=tuple(rpath),
                runpath=tuple(runpath),
                load_alignment_segments=tuple(load_alignment_segments),
            )
    except (OSError, struct.error, UnicodeDecodeError) as error:
        raise ValueError(f"could not inspect ELF file for {label}: {path}") from error


def require_android_16kb_elf_alignment(path: Path, label: str) -> None:
    """Require Android-host ELF load segments to be compatible with 16 KiB pages."""
    info = read_elf64(path, label)
    if not info.load_alignment_segments:
        raise ValueError(f"{label} has no ELF PT_LOAD segment.")

    page_size = ANDROID_MIN_ELF_PAGE_SIZE
    for file_offset, virtual_address, alignment in info.load_alignment_segments:
        if (
            alignment < page_size
            or alignment % page_size != 0
            or alignment & (alignment - 1) != 0
        ):
            raise ValueError(
                f"{label} is not 16 KB page-size compatible: "
                f"PT_LOAD p_align is 0x{alignment:x}, expected at least 0x{page_size:x}."
            )
        if file_offset % alignment != virtual_address % alignment:
            raise ValueError(
                f"{label} has an invalid ELF PT_LOAD alignment: "
                "file offset and virtual address are not congruent."
            )


def _expand_elf_search_paths(
    values: tuple[str, ...],
    origin: str,
    label: str,
) -> list[str]:
    expanded: list[str] = []
    for value in values:
        path = value.replace("${ORIGIN}", origin).replace("$ORIGIN", origin)
        if "$" in path:
            raise ValueError(
                f"{label} uses an unsupported dynamic-library path token: {value}"
            )
        if not path.startswith("/"):
            path = posixpath.join(origin, path)
        normalized = posixpath.normpath(path)
        if not normalized.startswith("/") or normalized == "/.." or normalized.startswith("/../"):
            raise ValueError(f"{label} has an unsafe ELF library search path: {value}")
        if normalized not in expanded:
            expanded.append(normalized)
    return expanded


def _resolve_elf_dependency(
    rootfs: Path,
    dependency: str,
    origin: str,
    search_directories: list[str],
) -> Path | None:
    if not dependency or "\x00" in dependency or "\\" in dependency:
        raise ValueError(f"invalid ELF dependency name: {dependency!r}")

    if "/" in dependency:
        candidate = dependency
        if not candidate.startswith("/"):
            candidate = posixpath.join(origin, candidate)
        return resolve_guest_file(rootfs, posixpath.normpath(candidate))

    for directory in search_directories:
        candidate = directory.rstrip("/") + "/" + dependency
        try:
            return resolve_guest_file(rootfs, candidate)
        except ValueError as error:
            if "required guest path is missing" in str(error):
                continue
            raise
    return None


def validate_elf_dependency_closure(
    rootfs: Path,
    entry_points: list[str],
    expected_machine: int,
    search_paths: list[str],
    label: str,
) -> int:
    """Verify every direct and transitive ELF dependency stays inside rootfs and matches its ABI.

    Search paths mirror the explicit guest environment used by RamenWine. The
    Ubuntu rootfs is assembled by extracting packages (not running dpkg), so
    the check deliberately does not depend on a generated ld.so.cache.
    """
    queue: list[tuple[Path, list[str]]] = []
    for guest_path in entry_points:
        queue.append((resolve_guest_file(rootfs, guest_path), [guest_path]))

    root_real = rootfs.resolve(strict=True)
    visited: set[Path] = set()
    while queue:
        path, chain = queue.pop(0)
        real_path = path.resolve(strict=True)
        if real_path in visited:
            continue
        try:
            relative_path = real_path.relative_to(root_real).as_posix()
        except ValueError as error:
            raise ValueError(f"{label} ELF dependency escapes the guest rootfs: {path}") from error
        visited.add(real_path)

        info = read_elf64(real_path, label + " ELF " + relative_path)
        if info.machine != expected_machine:
            raise ValueError(
                f"{label} ELF dependency has the wrong machine: {relative_path}; "
                f"expected {expected_machine}, got {info.machine}"
            )

        origin = "/" + posixpath.dirname(relative_path)
        object_label = label + " ELF " + relative_path
        own_search = _expand_elf_search_paths(info.rpath, origin, object_label)
        own_search += [
            path for path in _expand_elf_search_paths(info.runpath, origin, object_label)
            if path not in own_search
        ]
        own_search += [path for path in search_paths if path not in own_search]

        if info.interpreter is not None:
            try:
                interpreter = resolve_guest_file(rootfs, info.interpreter)
            except ValueError as error:
                raise ValueError(
                    f"{object_label} is missing its ELF interpreter {info.interpreter}"
                ) from error
            queue.append((interpreter, chain + ["PT_INTERP " + info.interpreter]))

        for dependency in info.needed:
            # The kernel supplies the VDSO; it is not a package file.
            if dependency in ("linux-vdso.so.1", "linux-gate.so.1"):
                continue
            resolved = _resolve_elf_dependency(rootfs, dependency, origin, own_search)
            if resolved is None:
                raise ValueError(
                    f"{object_label} has an unresolved ELF dependency {dependency!r}; "
                    f"dependency chain: {' -> '.join(chain + [dependency])}"
                )
            queue.append((resolved, chain + [dependency]))

    return len(visited)

def validate_box64_x64_library_bundle(
    rootfs: Path,
    relative_directory: str,
) -> list[str]:
    """Validate every shipped Box64 x86-64 shared library and its symlink target.

    Box64's bundle contains libraries loaded on demand as guest applications
    request wrapped APIs. Checking only the three libraries used by Wine's
    loader misses wrong-ABI or escaping libraries that would fail much later.
    """
    root_real = rootfs.resolve(strict=True)
    directory = rootfs.joinpath(*PurePosixPath(relative_directory).parts)
    try:
        real_directory = directory.resolve(strict=True)
        real_directory.relative_to(root_real)
    except (OSError, ValueError) as error:
        raise ValueError(
            "Box64 x86-64 library directory is missing or escapes the guest rootfs."
        ) from error
    if not real_directory.is_dir():
        raise ValueError("Box64 x86-64 library path is not a directory.")

    required_libraries = (
        "libstdc++.so.6",
        "libgcc_s.so.1",
        "libunwind.so.8",
    )
    guest_directory = "/" + PurePosixPath(relative_directory).as_posix()
    for name in required_libraries:
        library = resolve_guest_file(rootfs, guest_directory + "/" + name)
        try:
            library.relative_to(real_directory)
        except ValueError as error:
            raise ValueError(
                "Required Box64 x86-64 library escapes its bundle: " + name
            ) from error
        require_elf64(
            library,
            "Box64 bundled x86-64 library " + name,
            ELF_MACHINE_X86_64,
        )

    inspected: set[Path] = set()
    for current, child_directories, filenames in os.walk(
        real_directory, followlinks=False
    ):
        child_directories[:] = sorted(
            name
            for name in child_directories
            if not (Path(current) / name).is_symlink()
        )
        for filename in sorted(filenames):
            if not filename.startswith("lib") or ".so" not in filename:
                continue
            candidate = Path(current) / filename
            guest_path = "/" + candidate.relative_to(root_real).as_posix()
            resolved = resolve_guest_file(rootfs, guest_path)
            try:
                resolved.relative_to(real_directory)
            except ValueError as error:
                raise ValueError(
                    "Box64 shared library escapes its x86-64 bundle: " + filename
                ) from error
            if resolved in inspected:
                continue
            require_elf64(
                resolved,
                "Box64 bundled x86-64 library " + filename,
                ELF_MACHINE_X86_64,
            )
            inspected.add(resolved)

    if not inspected:
        raise ValueError("Box64 x86-64 library bundle contains no shared libraries.")

    # Return canonical guest paths for every distinct library so the caller can
    # validate DT_NEEDED recursively, including libraries Box64 may dlopen only
    # when a game requests a particular wrapped API.
    return sorted(
        "/" + path.relative_to(root_real).as_posix()
        for path in inspected
    )


def require_elf64(
    path: Path,
    label: str,
    expected_machine: int,
    expected_interpreter: str | None = None,
) -> Elf64Info:
    info = read_elf64(path, label)
    if info.machine != expected_machine:
        raise ValueError(
            f"{label} has the wrong ELF machine: expected {expected_machine}, got {info.machine}"
        )
    if expected_interpreter is not None and info.interpreter != expected_interpreter:
        raise ValueError(
            f"{label} must use interpreter {expected_interpreter}, got {info.interpreter!r}"
        )
    return info


def require_executable(path: Path, label: str) -> None:
    if not path.is_file() or not os.access(path, os.X_OK):
        raise ValueError(f"{label} is missing or not executable: {path}")


def require_pe_machine(path: Path, label: str, expected_machine: int) -> None:
    try:
        file_size = path.stat().st_size
        with path.open("rb") as stream:
            dos_header = stream.read(64)
            if len(dos_header) != 64 or dos_header[:2] != b"MZ":
                raise ValueError(f"{label} is not a PE image: {path}")
            pe_offset = struct.unpack_from("<I", dos_header, 0x3C)[0]
            if pe_offset < 64 or pe_offset + 6 > file_size:
                raise ValueError(f"{label} has an invalid PE header offset: {path}")
            stream.seek(pe_offset)
            pe_header = stream.read(6)
            if len(pe_header) != 6 or pe_header[:4] != b"PE\x00\x00":
                raise ValueError(f"{label} has no PE signature: {path}")
            machine = struct.unpack_from("<H", pe_header, 4)[0]
            if machine != expected_machine:
                raise ValueError(
                    f"{label} has the wrong PE machine: expected 0x{expected_machine:04x}, "
                    f"got 0x{machine:04x}"
                )
    except (OSError, struct.error) as error:
        raise ValueError(f"could not inspect PE file for {label}: {path}") from error


def require_pe_amd64(path: Path, label: str) -> None:
    require_pe_machine(path, label, PE_MACHINE_AMD64)


def require_pe_i386(path: Path, label: str) -> None:
    require_pe_machine(path, label, PE_MACHINE_I386)


def validate_wine_pe_directory(
    rootfs: Path,
    relative_directory: str,
    expected_machine: int,
    label: str,
) -> int:
    """Require a complete Wine PE directory and validate every executable image's ABI."""
    root_real = rootfs.resolve(strict=True)
    directory = rootfs.joinpath(*PurePosixPath(relative_directory).parts)
    try:
        real_directory = directory.resolve(strict=True)
        real_directory.relative_to(root_real)
    except (OSError, ValueError) as error:
        raise ValueError(
            f"{label} PE directory is missing or escapes the guest rootfs: "
            f"{relative_directory}"
        ) from error
    if not real_directory.is_dir():
        raise ValueError(f"{label} PE path is not a directory: {relative_directory}")

    guest_directory = "/" + PurePosixPath(relative_directory).as_posix()
    ntdll = resolve_guest_file(rootfs, guest_directory + "/ntdll.dll")
    require_pe_machine(ntdll, label + " ntdll.dll", expected_machine)

    pe_extensions = {".com", ".cpl", ".dll", ".drv", ".exe", ".ocx", ".scr", ".sys"}
    inspected = 0
    for current_directory, child_directories, filenames in os.walk(
        real_directory, followlinks=False
    ):
        child_directories[:] = sorted(
            name
            for name in child_directories
            if not (Path(current_directory) / name).is_symlink()
        )
        for filename in sorted(filenames):
            candidate = Path(current_directory) / filename
            if candidate.suffix.lower() not in pe_extensions:
                continue
            guest_path = "/" + candidate.relative_to(root_real).as_posix()
            resolved = resolve_guest_file(rootfs, guest_path)
            require_pe_machine(
                resolved,
                f"{label} PE component {candidate.name}",
                expected_machine,
            )
            inspected += 1

    if inspected == 0:
        raise ValueError(f"{label} PE directory contains no Windows PE components.")
    return inspected


def validate_android_proot_host_dependency_closure(host_root: Path) -> int:
    """Resolve PRoot's complete Bionic ELF dependency closure."""
    host_root = host_root.resolve(strict=True)
    if not host_root.is_dir():
        raise ValueError("PRoot host payload is not a directory.")

    library_directory = (host_root / "lib").resolve(strict=True)
    try:
        library_directory.relative_to(host_root)
    except ValueError as error:
        raise ValueError("PRoot host library directory escapes the host payload.") from error

    libraries: dict[str, Path] = {}
    for candidate in sorted(library_directory.iterdir(), key=lambda item: item.name):
        if not candidate.name.startswith("lib") or ".so" not in candidate.name:
            continue
        try:
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(library_directory)
        except (OSError, ValueError) as error:
            raise ValueError(
                f"PRoot host library is missing or escapes host/lib: {candidate.name}"
            ) from error
        if not resolved.is_file():
            raise ValueError(f"PRoot host library is not a regular file: {candidate.name}")
        libraries[candidate.name] = candidate

    entry_points = (
        (host_root / "bin" / "proot", "PRoot executable"),
        (host_root / "libexec" / "proot" / "loader", "PRoot ELF loader"),
    )
    queue: list[tuple[Path, list[str]]] = [
        (path, [label]) for path, label in entry_points
    ]
    visited: set[Path] = set()

    while queue:
        path, chain = queue.pop(0)
        try:
            real_path = path.resolve(strict=True)
            real_path.relative_to(host_root)
        except (OSError, ValueError) as error:
            raise ValueError(
                "PRoot host dependency is missing or escapes the host payload: "
                + str(path)
            ) from error
        if real_path in visited:
            continue
        visited.add(real_path)

        info = read_elf64(real_path, "Android PRoot host ELF " + real_path.name)
        if info.machine != ELF_MACHINE_AARCH64:
            raise ValueError(
                "Android PRoot host ELF dependency has the wrong machine: "
                + real_path.name
            )
        if info.interpreter is not None and info.interpreter != "/system/bin/linker64":
            raise ValueError(
                f"Android PRoot host ELF {real_path.name} uses an unexpected interpreter: "
                f"{info.interpreter}"
            )

        for dependency in info.needed:
            if dependency in ANDROID_PUBLIC_SYSTEM_LIBRARIES:
                continue
            bundled = libraries.get(dependency)
            if bundled is None:
                raise ValueError(
                    f"unresolved Android PRoot host dependency {dependency!r}; "
                    f"dependency chain: {' -> '.join(chain + [dependency])}"
                )
            queue.append((bundled, chain + [dependency]))

    return len(visited)


def validate_android_proot_host_payload(host_root: Path) -> int:
    """Validate the complete app-private PRoot host before a long guest build."""
    host_root = host_root.resolve(strict=True)
    if not host_root.is_dir():
        raise ValueError("PRoot host payload is not a directory.")

    library_directory = host_root / "lib"
    try:
        library_root = library_directory.resolve(strict=True)
        library_root.relative_to(host_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot host library directory is missing or escapes the host payload.") from error
    if not library_root.is_dir():
        raise ValueError("PRoot host library directory is not a directory.")

    libraries = [
        path for path in sorted(library_root.iterdir(), key=lambda item: item.name)
        if path.name.startswith("lib") and ".so" in path.name
    ]
    if not libraries:
        raise ValueError("PRoot host shared-library payload is empty.")

    for library in libraries:
        try:
            real_library = library.resolve(strict=True)
            real_library.relative_to(library_root)
        except (OSError, ValueError) as error:
            raise ValueError(
                f"PRoot host shared library is missing or escapes host/lib: {library.name}"
            ) from error
        require_elf64(
            real_library,
            "PRoot host shared library " + library.name,
            ELF_MACHINE_AARCH64,
        )
        require_android_16kb_elf_alignment(
            real_library,
            "PRoot host shared library " + library.name,
        )

    for required_library in ("libtalloc.so.2", "libandroid-shmem.so"):
        required_path = library_directory / required_library
        try:
            required_real = required_path.resolve(strict=True)
            required_real.relative_to(library_root)
        except (OSError, ValueError) as error:
            raise ValueError(
                f"required PRoot host library is missing or escapes host/lib: {required_library}"
            ) from error

    proot_path = host_root / "bin" / "proot"
    loader_path = host_root / "libexec" / "proot" / "loader"
    for path, label in (
        (proot_path, "PRoot host executable"),
        (loader_path, "PRoot ELF loader"),
    ):
        try:
            real_path = path.resolve(strict=True)
            real_path.relative_to(host_root)
        except (OSError, ValueError) as error:
            raise ValueError(f"{label} is missing or escapes the host payload.") from error
        require_executable(real_path, label)
        require_elf64(
            real_path,
            label,
            ELF_MACHINE_AARCH64,
            "/system/bin/linker64" if label == "PRoot host executable" else None,
        )
        require_android_16kb_elf_alignment(real_path, label)

    return validate_android_proot_host_dependency_closure(host_root)


PROOT_TERMUX_PACKAGES_COMMIT = "29a6879366dad92650fb7db7719c5a4b1b9d55f1"
PROOT_SIGTERM_PATCH_ID = "ramenwine-proot-sigterm-cleanup-v1"


def validate_proot_shutdown_patch_provenance(host_root: Path) -> None:
    """Reject PRoot host binaries not built with the Android SIGTERM cleanup patch."""
    host_root = host_root.resolve(strict=True)
    manifest_path = host_root / "PROVENANCE.json"
    try:
        manifest_real = manifest_path.resolve(strict=True)
        manifest_real.relative_to(host_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot host SIGTERM cleanup patch provenance is missing or unsafe.") from error

    try:
        metadata = json.loads(manifest_real.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("PRoot host SIGTERM cleanup patch provenance is invalid.") from error
    if (
        not isinstance(metadata, dict)
        or metadata.get("formatVersion") != 1
        or metadata.get("target") != "android-bionic-arm64"
        or metadata.get("termuxPackagesCommit") != PROOT_TERMUX_PACKAGES_COMMIT
    ):
        raise ValueError("PRoot host provenance does not match the pinned Android build.")

    patches = metadata.get("downstreamPatches")
    patch = patches.get("prootTermination") if isinstance(patches, dict) else None
    if not isinstance(patch, dict) or patch.get("id") != PROOT_SIGTERM_PATCH_ID:
        raise ValueError(
            "PRoot host was not built with the required SIGTERM tracee-cleanup patch."
        )

    recipe_hash = patch.get("recipeSha256")
    binary_hash = patch.get("binarySha256")
    if (
        patch.get("file") != "packages/proot/build.sh"
        or not isinstance(recipe_hash, str)
        or len(recipe_hash) != 64
        or any(character not in "0123456789abcdef" for character in recipe_hash)
        or not isinstance(binary_hash, str)
        or len(binary_hash) != 64
        or any(character not in "0123456789abcdef" for character in binary_hash)
    ):
        raise ValueError("PRoot SIGTERM cleanup patch provenance hash is malformed.")

    proot_path = host_root / "bin" / "proot"
    try:
        proot_real = proot_path.resolve(strict=True)
        proot_real.relative_to(host_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot executable is missing or escapes its host payload.") from error
    actual_binary_hash = hashlib.sha256(proot_real.read_bytes()).hexdigest()
    if actual_binary_hash != binary_hash:
        raise ValueError("PRoot executable does not match its SIGTERM patch provenance hash.")


def validate_glibc_proot_box64(root: Path) -> None:
    """Fail closed unless the prepared hybrid ARM64/x86-64 runtime has valid ABIs."""
    backend_root = root.resolve(strict=True)
    if not backend_root.is_dir():
        raise ValueError("runtime backend root must be a directory")

    # Keep the host directory itself inside the package. Checking only host/lib
    # would allow a symlinked host root to make validation read external files.
    host_root = backend_root / "host"
    try:
        host_root_real = host_root.resolve(strict=True)
        host_root_real.relative_to(backend_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot host directory is missing or escapes the backend.") from error

    # Reuse the same strict PRoot host checks in the builder's early preflight
    # and in the final runtime ZIP validation.
    validate_android_proot_host_payload(host_root_real)
    validate_proot_shutdown_patch_provenance(host_root_real)

    rootfs = backend_root / "rootfs"
    try:
        rootfs = rootfs.resolve(strict=True)
        rootfs.relative_to(backend_root)
    except (OSError, ValueError) as error:
        raise ValueError("ARM64 Linux/glibc rootfs directory is missing or escapes the backend.") from error
    if not rootfs.is_dir():
        raise ValueError("ARM64 Linux/glibc rootfs directory is not a directory.")

    arm64_loader = resolve_guest_file(rootfs, "/lib/ld-linux-aarch64.so.1")
    require_elf64(arm64_loader, "ARM64 glibc loader", ELF_MACHINE_AARCH64)

    x64_loader = resolve_guest_file(rootfs, "/lib64/ld-linux-x86-64.so.2")
    require_elf64(x64_loader, "x86-64 glibc loader", ELF_MACHINE_X86_64)

    guest_env = resolve_guest_file(rootfs, "/usr/bin/env")
    require_executable(guest_env, "guest env executable")
    require_elf64(
        guest_env,
        "guest env executable",
        ELF_MACHINE_AARCH64,
        "/lib/ld-linux-aarch64.so.1",
    )

    box64 = resolve_guest_file(rootfs, "/usr/bin/box64")
    require_executable(box64, "Box64 guest executable")
    require_elf64(
        box64,
        "Box64 guest executable",
        ELF_MACHINE_AARCH64,
        "/lib/ld-linux-aarch64.so.1",
    )

    box64_library_directory = "/usr/lib/box64-x86_64-linux-gnu"
    box64_x64_library_entry_points = validate_box64_x64_library_bundle(
        rootfs,
        box64_library_directory,
    )

    wine = resolve_guest_file(rootfs, "/usr/bin/wine")
    require_executable(wine, "Wine unified loader")
    require_elf64(
        wine,
        "Wine unified loader",
        ELF_MACHINE_X86_64,
        "/lib64/ld-linux-x86-64.so.2",
    )

    # Wine starts this separate Linux process for prefix and session
    # management. Its ELF dependencies are not necessarily a subset of the
    # loader's dependency closure, so validate it as an independent entry.
    wineserver = resolve_guest_file(rootfs, "/usr/bin/wineserver")
    require_executable(wineserver, "Wine server")
    require_elf64(
        wineserver,
        "Wine server",
        ELF_MACHINE_X86_64,
        "/lib64/ld-linux-x86-64.so.2",
    )

    # The Linux x86-64 loader starts the preloader from the native unix
    # directory. Wine 11 enables this component on Linux hosts with a known
    # architecture, and its own makefile installs it beside the Unix loader.
    wine_preloader = resolve_guest_file(
        rootfs,
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/wine-preloader",
    )
    require_executable(wine_preloader, "Wine x86-64 preloader")
    require_elf64(
        wine_preloader,
        "Wine x86-64 preloader",
        ELF_MACHINE_X86_64,
    )

    wineboot = resolve_guest_file(
        rootfs,
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
    )
    require_pe_amd64(wineboot, "wineboot PE program")

    wayland_unix = resolve_guest_file(
        rootfs,
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/winewayland.so",
    )
    require_elf64(wayland_unix, "Wine Wayland Unix driver", ELF_MACHINE_X86_64)

    wayland_pe = resolve_guest_file(
        rootfs,
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/winewayland.drv",
    )
    require_pe_amd64(wayland_pe, "Wine x86-64 Wayland PE driver")

    wayland_i386_pe = resolve_guest_file(
        rootfs,
        "/usr/lib/x86_64-linux-gnu/wine/i386-windows/winewayland.drv",
    )
    require_pe_i386(wayland_i386_pe, "Wine 32-bit Wayland PE driver")

    for relative_path, validator, label in (
        (
            "/usr/lib/ramenwine/probes/wayland-probe-x86_64.exe",
            require_pe_amd64,
            "AMD64 Wayland probe",
        ),
        (
            "/usr/lib/ramenwine/probes/wayland-probe-i386.exe",
            require_pe_i386,
            "i386 Wayland probe",
        ),
    ):
        validator(resolve_guest_file(rootfs, relative_path), label)

    wine_pe_root = "/usr/lib/x86_64-linux-gnu/wine"
    validate_wine_pe_directory(
        rootfs,
        wine_pe_root + "/x86_64-windows",
        PE_MACHINE_AMD64,
        "Wine x86-64",
    )
    validate_wine_pe_directory(
        rootfs,
        wine_pe_root + "/i386-windows",
        PE_MACHINE_I386,
        "Wine i386 WoW64",
    )

    for library_name in (
        "libwayland-client.so.0",
        "libwayland-cursor.so.0",
        "libwayland-egl.so.1",
        "libxkbcommon.so.0",
        "libxkbregistry.so.0",
    ):
        library = resolve_guest_file(
            rootfs,
            "/usr/lib/x86_64-linux-gnu/" + library_name,
        )
        require_elf64(
            library,
            "Wine Wayland dependency " + library_name,
            ELF_MACHINE_X86_64,
        )

    # The pinned Box64 build has native wrappers for these Wayland/XKB APIs.
    # Their ARM64 implementations must be present and ABI-correct so the
    # wrappers do not silently fall back to emulating those system libraries.
    arm64_wrapped_wayland_libraries = (
        ("libwayland-client.so.0", "Box64 ARM64 Wayland client wrapper target"),
        ("libwayland-cursor.so.0", "Box64 ARM64 Wayland cursor wrapper target"),
        ("libwayland-egl.so.1", "Box64 ARM64 Wayland EGL wrapper target"),
        ("libxkbcommon.so.0", "Box64 ARM64 XKB common wrapper target"),
        ("libxkbregistry.so.0", "Box64 ARM64 XKB registry wrapper target"),
    )
    arm64_wrapper_entry_points: list[str] = []
    for library_name, label in arm64_wrapped_wayland_libraries:
        guest_path = "/usr/lib/aarch64-linux-gnu/" + library_name
        library = resolve_guest_file(rootfs, guest_path)
        require_elf64(library, label, ELF_MACHINE_AARCH64)
        arm64_wrapper_entry_points.append(guest_path)

    # Verify the actual dynamic-linker dependency closure, not just a handful
    # of library filenames. The extracted rootfs has no post-install ld cache,
    # so these paths must cover every library required by the launch chain.
    validate_elf_dependency_closure(
        rootfs,
        ["/usr/bin/env", "/usr/bin/box64", *arm64_wrapper_entry_points],
        ELF_MACHINE_AARCH64,
        ["/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib", "/usr/lib"],
        "ARM64 Box64 runtime",
    )
    x64_entry_points = [
        "/usr/bin/wine",
        "/usr/bin/wineserver",
        "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/wine-preloader",
        "/" + wayland_unix.relative_to(rootfs.resolve()).as_posix(),
        *box64_x64_library_entry_points,
    ]
    unix_driver_directory = wayland_unix.parent
    for directory, child_directories, filenames in os.walk(unix_driver_directory):
        child_directories[:] = sorted(
            name for name in child_directories
            if not (Path(directory) / name).is_symlink()
        )
        for filename in sorted(filenames):
            candidate = Path(directory) / filename
            try:
                relative_candidate = candidate.relative_to(rootfs.resolve()).as_posix()
                resolved_candidate = resolve_guest_file(rootfs, "/" + relative_candidate)
                with resolved_candidate.open("rb") as stream:
                    is_elf = stream.read(4) == b"\x7fELF"
            except (OSError, ValueError):
                is_elf = False
                resolved_candidate = None
            if is_elf and resolved_candidate is not None:
                relative = resolved_candidate.relative_to(rootfs.resolve()).as_posix()
                guest_path = "/" + relative
                if guest_path not in x64_entry_points:
                    x64_entry_points.append(guest_path)

    validate_elf_dependency_closure(
        rootfs,
        x64_entry_points,
        ELF_MACHINE_X86_64,
        [
            "/lib/x86_64-linux-gnu",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/x86_64-linux-gnu/wine",
            "/usr/lib/x86_64-linux-gnu/wine/x86_64-unix",
            "/usr/lib/box64-x86_64-linux-gnu",
            "/lib64",
            "/usr/lib64",
        ],
        "Wine WoW64 runtime",
    )

    for xkb_path in (
        "/usr/share/X11/xkb/rules/evdev",
        "/usr/share/X11/xkb/keycodes/evdev",
        "/usr/share/X11/xkb/symbols/us",
    ):
        resolve_guest_file(rootfs, xkb_path)



def safe_relative_path(value: str) -> str:
    """Apply the same path constraints as the Android runtime installer."""
    if not value or value.startswith("/") or "\\" in value or "\x00" in value or "," in value:
        raise ValueError(f"unsafe relative path: {value!r}")

    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts:
        raise ValueError(f"unsafe relative path: {value!r}")

    for part in path.parts:
        if part in ("", ".", "..") or len(part) > 240 or any(ord(ch) < 32 for ch in part):
            raise ValueError(f"unsafe path segment in: {value!r}")

    normalized = path.as_posix()
    if normalized != value:
        raise ValueError(f"path is not normalized: {value!r}")
    return normalized


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_SIZE)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def resolve_symlink_inside(root: Path, link_path: Path, target: str) -> Path:
    if not target or "\\\\" in target or "\\x00" in target or any(ord(ch) < 32 for ch in target):
        raise ValueError(f"invalid symlink target for {link_path}: {target!r}")

    root_real = root.resolve(strict=True)
    if target.startswith("/"):
        candidate = root_real / target.lstrip("/")
    else:
        candidate = link_path.parent / target

    # Normalize existing symlinks, but allow a missing tail under virtual
    # mount points such as /proc/self/fd that PRoot supplies at runtime.
    resolved = candidate.resolve(strict=False)
    try:
        resolved.relative_to(root_real)
    except ValueError as exc:
        raise ValueError(f"symlink escapes runtime root: {link_path} -> {target}") from exc

    probe = resolved
    while not probe.exists() and not probe.is_symlink():
        parent = probe.parent
        if parent == probe:
            raise ValueError(f"symlink target has no existing parent: {link_path} -> {target}")
        probe = parent

    try:
        real_probe = probe.resolve(strict=True)
        real_probe.relative_to(root_real)
    except (OSError, ValueError, RuntimeError) as exc:
        raise ValueError(
            f"symlink escapes runtime root or has a broken parent: {link_path} -> {target}"
        ) from exc

    return resolved


def scan_runtime(root: Path, max_bytes: int) -> tuple[list[PackageFile], list[PackageSymlink]]:
    root = root.resolve(strict=True)
    if not root.is_dir():
        raise ValueError("runtime root must be a directory")

    files: list[PackageFile] = []
    symlinks: list[PackageSymlink] = []
    seen_paths: set[str] = set()
    total_bytes = 0
    pending = [root]

    while pending:
        directory = pending.pop()
        entries = sorted(os.scandir(directory), key=lambda item: item.name.casefold(), reverse=True)
        for entry in entries:
            source = Path(entry.path)
            relative = source.relative_to(root).as_posix()
            relative = safe_relative_path(relative)
            folded = relative.casefold()
            if folded in seen_paths:
                raise ValueError(f"duplicate path ignoring case: {relative}")
            seen_paths.add(folded)

            metadata = entry.stat(follow_symlinks=False)
            if stat.S_ISLNK(metadata.st_mode):
                link_target = os.readlink(source)
                resolve_symlink_inside(root, source, link_target)
                symlinks.append(PackageSymlink(relative, link_target))
                if len(files) + len(symlinks) > MAX_ENTRIES:
                    raise ValueError(f"runtime payload exceeds {MAX_ENTRIES} entries")
                continue

            if stat.S_ISDIR(metadata.st_mode):
                pending.append(source)
                continue

            if not stat.S_ISREG(metadata.st_mode):
                raise ValueError(f"unsupported special file: {relative}")

            size = metadata.st_size
            if size < 0:
                raise ValueError(f"invalid file size: {relative}")
            total_bytes += size
            if total_bytes > max_bytes or total_bytes > MAX_TOTAL_BYTES:
                raise ValueError("runtime payload exceeds configured uncompressed size limit")

            files.append(
                PackageFile(
                    relative_path=relative,
                    source_path=source,
                    sha256=sha256_file(source),
                    executable=bool(metadata.st_mode & 0o111),
                    size_bytes=size,
                )
            )

            if len(files) + len(symlinks) > MAX_ENTRIES:
                raise ValueError(f"runtime payload exceeds {MAX_ENTRIES} entries")

    files.sort(key=lambda item: item.relative_path.casefold())
    symlinks.sort(key=lambda item: item.relative_path.casefold())
    return files, symlinks


def make_manifest(
    backend: str,
    version: str,
    files: Iterable[PackageFile],
    symlinks: Iterable[PackageSymlink],
) -> bytes:
    manifest = {
        "schemaVersion": 1,
        "backendId": backend,
        "hostAbi": "arm64-v8a",
        "runtimeVersion": version,
        "files": [
            {
                "path": item.relative_path,
                "sha256": item.sha256,
                "executable": item.executable,
            }
            for item in files
        ],
        "symlinks": [
            {"path": item.relative_path, "target": item.target}
            for item in symlinks
        ],
    }
    return (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_zip(output: Path, manifest: bytes, files: list[PackageFile]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_output = output.with_name(output.name + ".tmp")
    if temp_output.exists():
        temp_output.unlink()

    try:
        with zipfile.ZipFile(
            temp_output,
            "w",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=6,
            strict_timestamps=True,
        ) as archive:
            manifest_info = zipfile.ZipInfo("runtime-manifest.json", ZIP_TIMESTAMP)
            manifest_info.compress_type = zipfile.ZIP_DEFLATED
            manifest_info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(manifest_info, manifest)

            for item in files:
                info = zipfile.ZipInfo("payload/" + item.relative_path, ZIP_TIMESTAMP)
                mode = 0o755 if item.executable else 0o644
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | mode) << 16

                digest = hashlib.sha256()
                with item.source_path.open("rb") as source, archive.open(info, "w") as target:
                    while True:
                        block = source.read(CHUNK_SIZE)
                        if not block:
                            break
                        digest.update(block)
                        target.write(block)

                if digest.hexdigest() != item.sha256:
                    raise ValueError(
                        f"source changed while packaging: {item.relative_path}"
                    )

        os.replace(temp_output, output)
    except Exception:
        if temp_output.exists():
            temp_output.unlink()
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path, help="Prepared backend directory")
    parser.add_argument("--backend", required=True, choices=sorted(BACKENDS))
    parser.add_argument("--version", required=True, help="Short package version/build ID")
    parser.add_argument("--output", required=True, type=Path, help="Output ZIP path")
    parser.add_argument(
        "--max-bytes",
        type=int,
        default=MAX_TOTAL_BYTES,
        help="Maximum total uncompressed payload size (default: 64 GiB)",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if not args.version or len(args.version) > 128:
            raise ValueError("version must contain 1..128 characters")
        if any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._+-" for ch in args.version):
            raise ValueError("version contains unsupported characters")
        if args.max_bytes <= 0 or args.max_bytes > MAX_TOTAL_BYTES:
            raise ValueError("max-bytes must be between 1 byte and 64 GiB")

        root_real = args.root.resolve(strict=True)
        if args.backend == "glibc-proot-box64":
            validate_glibc_proot_box64(root_real)

        output_resolved = args.output.resolve(strict=False)
        try:
            output_resolved.relative_to(root_real)
        except ValueError:
            pass
        else:
            raise ValueError("output ZIP must be outside the runtime root")

        files, symlinks = scan_runtime(root_real, args.max_bytes)
        if not files:
            raise ValueError("runtime root contains no regular files")

        manifest = make_manifest(args.backend, args.version, files, symlinks)
        write_zip(args.output, manifest, files)

        total_bytes = sum(item.size_bytes for item in files)
        print(f"Created: {args.output}")
        print(f"Backend: {args.backend}")
        print(f"Version: {args.version}")
        print(f"Files: {len(files)}")
        print(f"Symlinks: {len(symlinks)}")
        print(f"Uncompressed bytes: {total_bytes}")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"package error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
