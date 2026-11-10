#!/usr/bin/env python3
"""Import arm64-v8a libraries and support assets from a pinned Wawona APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import stat
import struct
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

PINNED_REVISION = "dd745564ffe4e28628c9cf2525c620bcfd4ebed5"
REQUIRED_LIBRARIES = {
    "libEGL_angle.so",
    "libGLESv2_angle.so",
    "libwawona.so",
}
ASSET_ROOTS = ("xkb", "fonts", "weston")
MAX_ENTRIES = 100_000
MAX_TOTAL_BYTES = 768 * 1024 * 1024
CHUNK_SIZE = 256 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_SIZE)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def safe_member_name(name: str) -> str:
    if not name or "\\" in name or "\x00" in name or name.startswith("/"):
        raise ValueError(f"unsafe APK entry path: {name!r}")
    path = PurePosixPath(name)
    if path.is_absolute() or path.as_posix() != name:
        raise ValueError(f"unsafe or non-normalized APK entry path: {name!r}")
    if any(part in ("", ".", "..") for part in path.parts):
        raise ValueError(f"unsafe APK entry path: {name!r}")
    return path.as_posix()


def is_supported_native_library(name: str) -> bool:
    return name.endswith(".so") or ".so." in name


def check_arm64_elf(path: Path) -> None:
    with path.open("rb") as stream:
        header = stream.read(20)
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise ValueError(f"native library is not an ELF file: {path.name}")
    if header[4] != 2 or header[5] != 1:
        raise ValueError(f"native library is not ELF64 little-endian: {path.name}")
    machine = struct.unpack_from("<H", header, 18)[0]
    if machine != 183:
        raise ValueError(f"native library is not AArch64 (e_machine={machine}): {path.name}")


def extract_member(
    archive: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    destination: Path,
    total_bytes: list[int],
) -> str:
    safe_member_name(info.filename)
    mode = (info.external_attr >> 16) & 0xFFFF
    if stat.S_ISLNK(mode):
        raise ValueError(f"symbolic links are not accepted in APK entries: {info.filename}")
    if info.file_size < 0:
        raise ValueError(f"invalid APK entry size: {info.filename}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    copied = 0
    with archive.open(info, "r") as source, destination.open("wb") as target:
        while True:
            block = source.read(CHUNK_SIZE)
            if not block:
                break
            copied += len(block)
            total_bytes[0] += len(block)
            if copied > MAX_TOTAL_BYTES or total_bytes[0] > MAX_TOTAL_BYTES:
                raise ValueError("Wawona APK payload exceeds the importer size limit")
            digest.update(block)
            target.write(block)

    if copied != info.file_size:
        raise ValueError(f"APK entry size mismatch: {info.filename}")
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--source-version", required=True)
    parser.add_argument("--app-main", type=Path, required=True)
    args = parser.parse_args()

    try:
        if args.source_revision != PINNED_REVISION:
            raise ValueError("source revision does not match the pinned Wawona commit")
        if not args.source_version or len(args.source_version) > 64:
            raise ValueError("invalid Wawona source version")
        apk = args.apk.resolve(strict=True)
        app_main = args.app_main.resolve(strict=True)
        if not app_main.is_dir():
            raise ValueError("--app-main must be an existing Android app src/main directory")

        apk_hash = sha256_file(apk)
        native_manifest: dict[str, str] = {}
        asset_manifest: dict[str, str] = {}
        seen_paths: set[str] = set()
        total_bytes = [0]

        with tempfile.TemporaryDirectory(prefix="ramenwine-wawona-import-") as temp:
            stage = Path(temp)
            staged_libs = stage / "jniLibs" / "arm64-v8a"
            staged_assets = stage / "assets" / "wawona"
            native_count = 0

            with zipfile.ZipFile(apk, "r") as archive:
                for info in archive.infolist():
                    if info.is_dir():
                        continue
                    name = safe_member_name(info.filename)
                    folded = name.casefold()
                    if folded in seen_paths:
                        raise ValueError(f"duplicate APK entry ignoring case: {name}")
                    seen_paths.add(folded)
                    if len(seen_paths) > MAX_ENTRIES:
                        raise ValueError("Wawona APK contains too many entries")

                    if name.startswith("lib/arm64-v8a/"):
                        relative = name.removeprefix("lib/arm64-v8a/")
                        if "/" in relative or not is_supported_native_library(relative):
                            continue
                        destination = staged_libs / relative
                        digest = extract_member(archive, info, destination, total_bytes)
                        check_arm64_elf(destination)
                        native_manifest[relative] = digest
                        native_count += 1
                        continue

                    if name.startswith("assets/"):
                        relative = name.removeprefix("assets/")
                        root_name = relative.split("/", 1)[0]
                        if root_name not in ASSET_ROOTS or "/" not in relative:
                            continue
                        asset_path = "wawona/" + relative
                        destination = staged_assets / relative
                        digest = extract_member(archive, info, destination, total_bytes)
                        asset_manifest[asset_path] = digest

            missing_libs = REQUIRED_LIBRARIES - native_manifest.keys()
            if missing_libs:
                raise ValueError("APK is missing required native libraries: " +
                                 ", ".join(sorted(missing_libs)))
            if not native_count:
                raise ValueError("APK contains no arm64-v8a native libraries")
            for root_name in ASSET_ROOTS:
                if not any(path.startswith(f"wawona/{root_name}/") for path in asset_manifest):
                    raise ValueError(f"APK is missing required {root_name} support assets")

            manifest = {
                "schemaVersion": 1,
                "sourceRepository": "https://github.com/Wawona/Wawona",
                "sourceRevision": args.source_revision,
                "sourceVersion": args.source_version,
                "apkSha256": apk_hash,
                "nativeLibraries": dict(sorted(native_manifest.items())),
                "assets": dict(sorted(asset_manifest.items())),
            }
            (staged_assets / "provenance.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                encoding="utf-8"
            )

            target_libs = app_main / "jniLibs" / "arm64-v8a"
            target_assets = app_main / "assets" / "wawona"
            target_libs.mkdir(parents=True, exist_ok=True)
            target_assets.parent.mkdir(parents=True, exist_ok=True)

            old_manifest = target_assets / "provenance.json"
            old_native_names: set[str] = set()
            if old_manifest.is_file():
                try:
                    previous = json.loads(old_manifest.read_text(encoding="utf-8"))
                    old_native_names = set(previous.get("nativeLibraries", {}).keys())
                except (OSError, ValueError, TypeError):
                    old_native_names = set()

            for old_name in old_native_names:
                if old_name not in native_manifest:
                    (target_libs / old_name).unlink(missing_ok=True)
            for library in staged_libs.iterdir():
                shutil.copy2(library, target_libs / library.name)

            if target_assets.exists():
                shutil.rmtree(target_assets)
            shutil.copytree(staged_assets, target_assets)

        print(f"Imported Wawona {args.source_version} ({args.source_revision})")
        print(f"Native libraries: {len(native_manifest)}")
        print(f"Support asset files: {len(asset_manifest)}")
        print(f"APK SHA-256: {apk_hash}")
        print(f"Destination: {app_main}")
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Wawona import error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
