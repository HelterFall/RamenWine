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


ELF_MACHINE_AARCH64 = 183
ELF_MACHINE_X86_64 = 62
ELF_PT_INTERP = 3
PE_MACHINE_AMD64 = 0x8664
PE_MACHINE_I386 = 0x014C


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
    """Read the ELF64 machine and PT_INTERP fields without external tools."""
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

            interpreter: str | None = None
            if program_header_count:
                if program_header_size < 56:
                    raise ValueError(f"{label} has an invalid ELF program-header size.")
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
                    if segment_type != ELF_PT_INTERP:
                        continue
                    if interpreter is not None:
                        raise ValueError(f"{label} has multiple PT_INTERP segments.")
                    interpreter_offset = values[2]
                    interpreter_size = values[5]
                    if (
                        interpreter_size < 2
                        or interpreter_size > 4096
                        or interpreter_offset + interpreter_size > file_size
                    ):
                        raise ValueError(f"{label} has an invalid PT_INTERP segment.")
                    stream.seek(interpreter_offset)
                    raw_interpreter = stream.read(interpreter_size)
                    if (
                        len(raw_interpreter) != interpreter_size
                        or not raw_interpreter.endswith(b"\x00")
                        or b"\x00" in raw_interpreter[:-1]
                    ):
                        raise ValueError(f"{label} has a malformed PT_INTERP string.")
                    interpreter = raw_interpreter[:-1].decode("ascii")
                    if not interpreter.startswith("/"):
                        raise ValueError(f"{label} has a non-absolute ELF interpreter.")
            return Elf64Info(machine=machine, interpreter=interpreter)
    except (OSError, struct.error, UnicodeDecodeError) as error:
        raise ValueError(f"could not inspect ELF file for {label}: {path}") from error


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
            if len(pe_header) != 6 or pe_header[:4] != b"PE\\x00\\x00":
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

def validate_glibc_proot_box64(root: Path) -> None:
    """Fail closed unless the prepared hybrid ARM64/x86-64 runtime has valid ABIs."""
    backend_root = root.resolve(strict=True)
    if not backend_root.is_dir():
        raise ValueError("runtime backend root must be a directory")

    host_library_directory = backend_root / "host" / "lib"
    if not host_library_directory.is_dir():
        raise ValueError("PRoot host library directory is missing.")

    proot_path = backend_root / "host" / "bin" / "proot"
    try:
        proot_real = proot_path.resolve(strict=True)
        proot_real.relative_to(backend_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot host executable is missing or escapes the backend.") from error
    require_executable(proot_real, "PRoot host executable")
    require_elf64(
        proot_real,
        "PRoot host executable",
        ELF_MACHINE_AARCH64,
        "/system/bin/linker64",
    )

    proot_loader_path = backend_root / "host" / "libexec" / "proot" / "loader"
    try:
        proot_loader_real = proot_loader_path.resolve(strict=True)
        proot_loader_real.relative_to(backend_root)
    except (OSError, ValueError) as error:
        raise ValueError("PRoot ELF loader is missing or escapes the backend.") from error
    require_executable(proot_loader_real, "PRoot ELF loader")
    require_elf64(proot_loader_real, "PRoot ELF loader", ELF_MACHINE_AARCH64)

    rootfs = backend_root / "rootfs"
    if not rootfs.is_dir():
        raise ValueError("ARM64 Linux/glibc rootfs directory is missing.")

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
    for library_name in (
        "libstdc++.so.6",
        "libgcc_s.so.1",
        "libunwind.so.8",
    ):
        library = resolve_guest_file(
            rootfs,
            box64_library_directory + "/" + library_name,
        )
        require_elf64(
            library,
            "Box64 bundled x86-64 library " + library_name,
            ELF_MACHINE_X86_64,
        )

    wine = resolve_guest_file(rootfs, "/usr/bin/wine")
    require_executable(wine, "Wine unified loader")
    require_elf64(
        wine,
        "Wine unified loader",
        ELF_MACHINE_X86_64,
        "/lib64/ld-linux-x86-64.so.2",
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
