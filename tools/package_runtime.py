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
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable

from audit_runtime_elf import ElfAuditError, audit_rootfs

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
    if not target or "\\" in target or "\x00" in target or any(ord(ch) < 32 for ch in target):
        raise ValueError(f"invalid symlink target for {link_path}: {target!r}")

    root_real = root.resolve(strict=True)
    relative_link = link_path.relative_to(root_real).as_posix()
    domain = relative_link.split("/", 1)[0]
    domain_root = root_real / domain if domain in {"rootfs", "host"} else root_real

    if target.startswith("/"):
        if domain == "host":
            raise ValueError(
                f"absolute host symlinks are not allowed: {link_path} -> {target}"
            )
        absolute_root = domain_root if domain == "rootfs" else root_real
        candidate = absolute_root / target.lstrip("/")
    else:
        candidate = link_path.parent / target

    # Absolute Linux links in rootfs use the guest root, not the package root.
    # Host links must remain inside the Android-host payload.
    resolved = candidate.resolve(strict=False)
    try:
        resolved.relative_to(domain_root)
    except ValueError as exc:
        raise ValueError(f"symlink escapes its runtime domain: {link_path} -> {target}") from exc

    probe = resolved
    while not probe.exists() and not probe.is_symlink():
        parent = probe.parent
        if parent == probe:
            raise ValueError(f"symlink target has no existing parent: {link_path} -> {target}")
        probe = parent

    try:
        real_probe = probe.resolve(strict=True)
        real_probe.relative_to(domain_root)
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


def resolve_manifest_file(
    requested_path: str,
    files_by_path: dict[str, PackageFile],
    symlinks_by_path: dict[str, PackageSymlink],
) -> PackageFile | None:
    current = requested_path
    visited: set[str] = set()

    for _ in range(40):
        if current in visited:
            return None
        visited.add(current)
        file_entry = files_by_path.get(current)
        if file_entry is not None:
            return file_entry
        link = symlinks_by_path.get(current)
        if link is None:
            return None

        domain = current.split("/", 1)[0]
        target = link.target
        if not target or "\\" in target or "\x00" in target or any(ord(ch) < 32 for ch in target):
            return None
        if target.startswith("/") and domain == "host":
            return None

        if target.startswith("/"):
            parts = (["rootfs"] if domain == "rootfs" else []) + target.lstrip("/").split("/")
        else:
            parts = current.split("/")[:-1] + target.split("/")

        normalized: list[str] = []
        for part in parts:
            if part in ("", "."):
                continue
            if part == "..":
                if not normalized:
                    return None
                normalized.pop()
            else:
                normalized.append(part)
        if not normalized:
            return None
        if domain in {"rootfs", "host"} and normalized[0] != domain:
            return None
        current = "/".join(normalized)
    return None


def read_elf_identity(path: Path) -> tuple[int, int, int] | None:
    with path.open("rb") as stream:
        header = stream.read(20)
    if (
        len(header) < 20 or
        header[:4] != b"\x7fELF" or
        header[4] != 2 or
        header[5] != 1
    ):
        return None
    machine = int.from_bytes(header[18:20], "little")
    return (header[4], header[5], machine)


def is_x86_64_pe32_plus(path: Path) -> bool:
    with path.open("rb") as stream:
        dos_header = stream.read(64)
        if len(dos_header) < 64 or dos_header[:2] != b"MZ":
            return False
        pe_offset = int.from_bytes(dos_header[0x3C:0x40], "little")
        if pe_offset < 64 or pe_offset > 16 * 1024 * 1024:
            return False
        stream.seek(pe_offset)
        pe_header = stream.read(26)

    return (
        len(pe_header) == 26 and
        pe_header[:4] == b"PE\x00\x00" and
        int.from_bytes(pe_header[4:6], "little") == 0x8664 and
        int.from_bytes(pe_header[24:26], "little") == 0x20B
    )


def validate_backend_payload(
    backend: str,
    files: Iterable[PackageFile],
    symlinks: Iterable[PackageSymlink],
    runtime_root: Path | None = None,
) -> None:
    if backend != "glibc-proot-box64":
        return

    file_items = list(files)
    link_items = list(symlinks)
    files_by_path = {item.relative_path: item for item in file_items}
    links_by_path = {item.relative_path: item for item in link_items}

    required_executables = (
        ("host/bin/proot", 183, "AArch64 Android-host PRoot"),
        ("rootfs/usr/bin/env", 183, "AArch64 guest env"),
        ("rootfs/usr/bin/box64", 183, "AArch64 Box64"),
        ("rootfs/usr/bin/wine", 62, "x86-64 Wine loader for Box64"),
    )
    for path, expected_machine, label in required_executables:
        item = resolve_manifest_file(path, files_by_path, links_by_path)
        if item is None:
            raise ValueError(f"runtime is missing required executable: {path}")
        if not item.executable:
            raise ValueError(f"runtime file is not executable: {path}")
        identity = read_elf_identity(item.source_path)
        if identity != (2, 1, expected_machine):
            raise ValueError(
                f"{label} must be a 64-bit little-endian ELF with machine "
                f"{expected_machine}; got {identity!r} at {path}"
            )

    wineboot_candidates = (
        "rootfs/usr/lib/wine/x86_64-windows/wineboot.exe",
        "rootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe",
    )
    wineboot = next(
        (
            resolve_manifest_file(path, files_by_path, links_by_path)
            for path in wineboot_candidates
            if resolve_manifest_file(path, files_by_path, links_by_path) is not None
        ),
        None,
    )
    if wineboot is None:
        raise ValueError("runtime is missing Wine prefix initializer (wineboot.exe)")
    if not is_x86_64_pe32_plus(wineboot.source_path):
        raise ValueError("Wine prefix initializer must be an x86-64 PE32+ executable")

    if runtime_root is not None:
        try:
            report = audit_rootfs(runtime_root / "rootfs")
        except (OSError, ElfAuditError) as error:
            raise ValueError(f"could not audit guest ELF dependencies: {error}") from error
        if report["issues"]:
            details = "; ".join(
                f"{item['file']}: {item['kind']} ({item['detail']})"
                for item in report["issues"][:20]
            )
            remaining = len(report["issues"]) - 20
            if remaining > 0:
                details += f"; and {remaining} more issue(s)"
            raise ValueError("guest rootfs has unresolved ELF dependencies: " + details)


def make_manifest(
    backend: str,
    version: str,
    files: Iterable[PackageFile],
    symlinks: Iterable[PackageSymlink],
    runtime_root: Path | None = None,
) -> bytes:
    files = list(files)
    symlinks = list(symlinks)
    if backend not in BACKENDS:
        raise ValueError(f"unsupported runtime backend: {backend}")
    validate_backend_payload(backend, files, symlinks, runtime_root=runtime_root)
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

        manifest = make_manifest(
            args.backend,
            args.version,
            files,
            symlinks,
            runtime_root=root_real,
        )
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
