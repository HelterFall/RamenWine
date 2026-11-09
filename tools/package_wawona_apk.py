#!/usr/bin/env python3
"""Extract the pinned Wawona Android compositor from its upstream APK.

Only libwawona.so and its recursive DT_NEEDED closure are bundled. This avoids
shipping unrelated app payloads (for example shell/SSH executables) in RamenWine.
The APK must be built from the exact Wawona revision documented by this tool.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from collections.abc import Iterator, Mapping
from pathlib import Path, PurePosixPath
from typing import Callable

PINNED_WAWONA_REVISION = "d298ddd25726fec0977285aa5f57181110c966a6"
PINNED_WAWONA_ARTIFACT_SHA256 = (
    "04000a9b31c0125f24b7aecfa3f9bbbc3a51cad7e8fa0ba7207f2e553e7be953"
)
PINNED_WAWONA_APK_SHA256 = (
    "60006df2d5839e6ae3d7137170bbff093ab4f42b74b192f226b293cfee291f37"
)
PINNED_WAWONA_ARTIFACT_ID = 13377766663
PINNED_WAWONA_WORKFLOW_RUN = 41941778312
APK_LIBRARY_ROOT = "lib/arm64-v8a/"
APK_ASSET_ROOTS = (
    "xkb",
    "fonts",
    "weston",
    "vulkan/icd.d",
)
ROOT_LIBRARIES = (
    "libwawona.so",
    "libEGL_angle.so",
    "libGLESv2_angle.so",
)
OPTIONAL_ROOT_LIBRARIES = (
    # Wawona's JNI SHM demo wrapper calls dlopen() on this library by name;
    # it is not a DT_NEEDED dependency of libwawona.so.
    "libweston_simple_shm.so",
    # Wawona can use this as its bundled offscreen ICD for nested clients.
    # It is not a substitute for Android's host Vulkan WSI driver.
    "libvk_swiftshader.so",
)
SYSTEM_LIBRARIES = {
    "libaaudio.so",
    "libandroid.so",
    "libbinder_ndk.so",
    "libc.so",
    "libcamera2ndk.so",
    "libdl.so",
    "libEGL.so",
    "libGLESv1_CM.so",
    "libGLESv2.so",
    "libjnigraphics.so",
    "liblog.so",
    "libm.so",
    "libmediandk.so",
    "libnativewindow.so",
    "libOpenSLES.so",
    "libvulkan.so",
    "libz.so",
}
MAX_ASSET_FILES = 100_000
MAX_ASSET_BYTES = 128 * 1024 * 1024
MAX_SINGLE_ASSET_BYTES = 32 * 1024 * 1024
MAX_SINGLE_LIBRARY_BYTES = 128 * 1024 * 1024
NEEDED_RE = re.compile(r"\(NEEDED\).*Shared library: \[([^\]]+)\]")
SONAME_RE = re.compile(r"\(SONAME\).*Library soname: \[([^\]]+)\]")
ELF_CLASS_RE = re.compile(r"Class:\s*ELF64")
ELF_MACHINE_RE = re.compile(r"Machine:\s*AArch64", re.IGNORECASE)
ANDROID_MIN_ELF_PAGE_SIZE = 16 * 1024


def safe_archive_path(name: str) -> str:
    if (
        not name
        or name.startswith("/")
        or "\\" in name
        or "\x00" in name
        or any(ord(char) < 32 for char in name)
    ):
        raise ValueError(f"unsafe APK entry path: {name!r}")
    path = PurePosixPath(name.rstrip("/"))
    if path.is_absolute() or not path.parts:
        raise ValueError(f"unsafe APK entry path: {name!r}")
    if any(part in ("", ".", "..") or len(part) > 240 for part in path.parts):
        raise ValueError(f"unsafe APK entry path: {name!r}")
    return path.as_posix()


def validate_arm64_elf_header(readelf_output: str, filename: str) -> None:
    if not ELF_CLASS_RE.search(readelf_output):
        raise ValueError(f"{filename} is not an ELF64 shared library.")
    if not ELF_MACHINE_RE.search(readelf_output):
        raise ValueError(f"{filename} is not an AArch64 ELF binary.")


def validate_arm64_elf_16kb_alignment(readelf_output: str, filename: str) -> None:
    """Require every bundled Android ARM64 PT_LOAD segment to support 16 KiB pages."""
    segments: list[tuple[int, int, int]] = []
    for line in readelf_output.splitlines():
        fields = line.split()
        if not fields or fields[0] != "LOAD":
            continue
        if len(fields) < 8:
            raise ValueError(f"{filename} has an invalid ELF PT_LOAD header.")
        try:
            file_offset = int(fields[1], 0)
            virtual_address = int(fields[2], 0)
            alignment = int(fields[-1], 0)
        except ValueError as error:
            raise ValueError(f"{filename} has an invalid ELF PT_LOAD alignment.") from error
        segments.append((file_offset, virtual_address, alignment))

    if not segments:
        raise ValueError(f"{filename} has no ELF PT_LOAD segments.")

    page_size = ANDROID_MIN_ELF_PAGE_SIZE
    for file_offset, virtual_address, alignment in segments:
        if (
            alignment < page_size
            or alignment % page_size != 0
            or alignment & (alignment - 1) != 0
        ):
            raise ValueError(
                f"{filename} is not 16 KB page-size compatible: "
                f"PT_LOAD p_align is 0x{alignment:x}, expected at least 0x{page_size:x}."
            )
        if file_offset % alignment != virtual_address % alignment:
            raise ValueError(
                f"{filename} has an invalid ELF PT_LOAD alignment: "
                "file offset and virtual address are not congruent."
            )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_verified_pinned_artifact(artifact_zip: Path, destination: Path) -> Path:
    """Extract only the reviewed APK from the known upstream Actions artifact."""
    if not artifact_zip.is_file():
        raise ValueError(f"Wawona product artifact ZIP does not exist: {artifact_zip}")
    actual_artifact_sha = sha256_file(artifact_zip)
    if actual_artifact_sha != PINNED_WAWONA_ARTIFACT_SHA256:
        raise ValueError(
            "Wawona product artifact SHA-256 does not match the reviewed upstream artifact."
        )

    destination.mkdir(parents=True, exist_ok=True)
    apk_path = destination / "Wawona.apk"
    with zipfile.ZipFile(artifact_zip) as archive:
        entries: dict[str, zipfile.ZipInfo] = {}
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = safe_archive_path(info.filename)
            if name not in ("Wawona.apk", "product-manifest.json"):
                raise ValueError(f"unexpected file in pinned Wawona artifact: {name}")
            if name in entries:
                raise ValueError(f"duplicate file in pinned Wawona artifact: {name}")
            entries[name] = info

        if set(entries) != {"Wawona.apk", "product-manifest.json"}:
            raise ValueError("pinned Wawona artifact is missing its APK or product manifest.")

        manifest_info = entries["product-manifest.json"]
        if manifest_info.file_size > 64 * 1024:
            raise ValueError("Wawona product manifest is unexpectedly large.")
        try:
            manifest = json.loads(archive.read(manifest_info).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError("Wawona product manifest is invalid JSON.") from error

        expected_manifest = {
            "attr": "wawona-android",
            "sha": PINNED_WAWONA_REVISION,
            "artifact": "product-android-apk",
        }
        for key, expected in expected_manifest.items():
            if manifest.get(key) != expected:
                raise ValueError(f"Wawona product manifest mismatch for {key}.")

        apk_info = entries["Wawona.apk"]
        if apk_info.file_size <= 0 or apk_info.file_size > 256 * 1024 * 1024:
            raise ValueError("Wawona APK size is outside the accepted range.")
        with archive.open(apk_info) as source, apk_path.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)

    if sha256_file(apk_path) != PINNED_WAWONA_APK_SHA256:
        apk_path.unlink(missing_ok=True)
        raise ValueError("Wawona APK SHA-256 does not match the reviewed pinned build.")
    return apk_path


def parse_dynamic_dependencies(readelf_output: str) -> tuple[list[str], str | None]:
    needed = NEEDED_RE.findall(readelf_output)
    soname_match = SONAME_RE.search(readelf_output)
    return needed, soname_match.group(1) if soname_match else None


def collect_native_closure(
    apk_libraries: Mapping[str, bytes],
    read_dependencies: Callable[[str, bytes], tuple[list[str], str | None]],
) -> dict[str, bytes]:
    """Return only the root libraries and their APK-resident NEEDED closure."""
    by_name: dict[str, str] = {}
    for filename in apk_libraries:
        if (
            PurePosixPath(filename).name != filename
            or not (filename.endswith(".so") or ".so." in filename)
        ):
            raise ValueError(f"invalid native library filename: {filename!r}")
        folded = filename.casefold()
        if folded in by_name:
            raise ValueError(f"duplicate native library name ignoring case: {filename}")
        by_name[folded] = filename

    soname_to_file: dict[str, str] = {}
    metadata: dict[str, tuple[list[str], str | None]] = {}

    def inspect(filename: str) -> tuple[list[str], str | None]:
        if filename not in metadata:
            metadata[filename] = read_dependencies(filename, apk_libraries[filename])
            needed, soname = metadata[filename]
            if soname:
                folded_soname = soname.casefold()
                existing = soname_to_file.get(folded_soname)
                if existing is not None and existing != filename:
                    raise ValueError(f"multiple APK libraries provide SONAME {soname}")
                soname_to_file[folded_soname] = filename
        return metadata[filename]

    selected: dict[str, bytes] = {}
    pending = list(ROOT_LIBRARIES)
    pending.extend(
        name for name in OPTIONAL_ROOT_LIBRARIES if name.casefold() in by_name
    )
    inspected: set[str] = set()

    while pending:
        requested = pending.pop()
        filename = by_name.get(requested.casefold()) or soname_to_file.get(requested.casefold())
        if filename is None:
            if requested in SYSTEM_LIBRARIES:
                continue
            # APK filenames normally equal SONAMEs. If not, inspect remaining
            # entries once so a renamed library can still be matched by SONAME.
            for candidate in apk_libraries:
                inspect(candidate)
            filename = soname_to_file.get(requested.casefold())
        if filename is None:
            if requested in SYSTEM_LIBRARIES:
                continue
            raise ValueError(f"unresolved native dependency: {requested}")
        if filename in inspected:
            continue

        inspected.add(filename)
        selected[filename] = apk_libraries[filename]
        dependencies, _ = inspect(filename)
        pending.extend(dependencies)

    return dict(sorted(selected.items(), key=lambda item: item[0].casefold()))


def make_readelf_reader(readelf: str) -> Callable[[str, bytes], tuple[list[str], str | None]]:
    def read_dependencies(filename: str, data: bytes) -> tuple[list[str], str | None]:
        with tempfile.NamedTemporaryFile(prefix="ramenwine-", suffix=".so") as temp:
            temp.write(data)
            temp.flush()
            result = subprocess.run(
                [readelf, "--wide", "--file-header", "--program-headers", "--dynamic", temp.name],
                check=False,
                capture_output=True,
                text=True,
            )
        if result.returncode != 0:
            raise ValueError(
                f"llvm-readelf failed for {filename}: {result.stderr.strip()}"
            )
        validate_arm64_elf_header(result.stdout, filename)
        validate_arm64_elf_16kb_alignment(result.stdout, filename)
        return parse_dynamic_dependencies(result.stdout)

    return read_dependencies


class ApkNativeLibraries(Mapping[str, bytes]):
    """Lazy view over APK .so entries; large unrelated helper binaries stay unread."""

    def __init__(self, archive: zipfile.ZipFile):
        self._archive = archive
        self._entries: dict[str, zipfile.ZipInfo] = {}
        seen: set[str] = set()
        for info in archive.infolist():
            if not info.filename.startswith(APK_LIBRARY_ROOT) or info.is_dir():
                continue
            relative = safe_archive_path(info.filename)
            if not relative.startswith(APK_LIBRARY_ROOT):
                raise ValueError(f"native entry escaped ABI directory: {info.filename}")
            filename = relative[len(APK_LIBRARY_ROOT):]
            if "/" in filename or not (filename.endswith(".so") or ".so." in filename):
                continue
            folded = filename.casefold()
            if folded in seen:
                raise ValueError(f"duplicate APK native library: {filename}")
            seen.add(folded)
            # APKs can also contain standalone tools disguised with a .so
            # suffix. Do not read those into memory unless they are within a
            # reasonable shared-object size; if a required DT_NEEDED edge points
            # to one, dependency resolution fails closed with its exact name.
            if info.file_size > MAX_SINGLE_LIBRARY_BYTES:
                continue
            self._entries[filename] = info

    def __getitem__(self, filename: str) -> bytes:
        return self._archive.read(self._entries[filename])

    def __contains__(self, filename: object) -> bool:
        return filename in self._entries

    def __iter__(self) -> Iterator[str]:
        return iter(self._entries)

    def __len__(self) -> int:
        return len(self._entries)


def apk_native_libraries(archive: zipfile.ZipFile) -> ApkNativeLibraries:
    return ApkNativeLibraries(archive)


def extract_assets(archive: zipfile.ZipFile, destination: Path) -> list[str]:
    wanted = tuple("assets/" + root + "/" for root in APK_ASSET_ROOTS)
    selected = [
        info for info in archive.infolist()
        if not info.is_dir() and info.filename.startswith(wanted)
    ]
    if not any(info.filename.startswith("assets/xkb/") for info in selected):
        raise ValueError("Wawona APK is missing the required assets/xkb tree.")
    if not any(info.filename.startswith("assets/fonts/") for info in selected):
        raise ValueError("Wawona APK is missing the required assets/fonts tree.")

    seen: set[str] = set()
    total = 0
    output_paths: list[str] = []
    for info in selected:
        safe_name = safe_archive_path(info.filename)
        relative = safe_name.removeprefix("assets/")
        target_relative = safe_archive_path("wawona/" + relative)
        folded = target_relative.casefold()
        if folded in seen:
            raise ValueError(f"duplicate Wawona asset path: {target_relative}")
        seen.add(folded)

        if info.file_size < 0 or info.file_size > MAX_SINGLE_ASSET_BYTES:
            raise ValueError(f"Wawona asset exceeds per-file size limit: {relative}")
        total += info.file_size
        if total > MAX_ASSET_BYTES or len(selected) > MAX_ASSET_FILES:
            raise ValueError("Wawona asset payload exceeds extraction limits.")

        target = destination / PurePosixPath(target_relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        data = archive.read(info)
        if len(data) != info.file_size:
            raise ValueError(f"truncated APK asset: {relative}")
        target.write_bytes(data)
        output_paths.append(target_relative)

    return sorted(output_paths, key=str.casefold)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hash_extracted_assets(asset_root: Path, asset_paths: list[str]) -> dict[str, str]:
    """Build the install-time integrity map for the extracted Wawona assets."""
    hashes: dict[str, str] = {}
    for relative_path in asset_paths:
        safe_path = safe_archive_path(relative_path)
        if safe_path != relative_path or not relative_path.startswith("wawona/"):
            raise ValueError("invalid extracted Wawona asset path: " + relative_path)
        if relative_path in hashes:
            raise ValueError("duplicate extracted Wawona asset path: " + relative_path)
        path = asset_root / PurePosixPath(relative_path)
        if not path.is_file():
            raise ValueError("extracted Wawona asset is missing: " + relative_path)
        hashes[relative_path] = sha256_file(path)
    return dict(sorted(hashes.items(), key=lambda item: item[0].casefold()))


def package_apk(
    apk_path: Path,
    output_dir: Path,
    revision: str,
    readelf: str,
) -> None:
    if revision != PINNED_WAWONA_REVISION:
        raise ValueError(
            "Wawona revision does not match the reviewed pin "
            f"({PINNED_WAWONA_REVISION}). Review the upstream diff before changing it."
        )
    if not apk_path.is_file():
        raise ValueError(f"Wawona APK does not exist: {apk_path}")

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="wawona-stage-", dir=output_dir.parent))
    try:
        with zipfile.ZipFile(apk_path) as archive:
            libraries = apk_native_libraries(archive)
            selected = collect_native_closure(libraries, make_readelf_reader(readelf))
            assets = extract_assets(archive, staging / "assets")

        jni_root = staging / "jniLibs" / "arm64-v8a"
        jni_root.mkdir(parents=True, exist_ok=True)
        for filename, data in selected.items():
            (jni_root / filename).write_bytes(data)

        provenance = {
            "upstream": "https://github.com/Wawona/Wawona",
            "revision": revision,
            "apkSha256": sha256_file(apk_path),
            "abi": "arm64-v8a",
            "androidMinElfPageSizeBytes": ANDROID_MIN_ELF_PAGE_SIZE,
            "rootLibraries": list(ROOT_LIBRARIES),
            "optionalRootLibraries": [
                name for name in OPTIONAL_ROOT_LIBRARIES
                if name in selected
            ],
            "bundledLibraries": {
                filename: sha256_bytes(data) for filename, data in selected.items()
            },
            "assets": assets,
            "assetSha256": hash_extracted_assets(staging / "assets", assets),
            "note": (
                "Only libwawona.so, its DT_NEEDED closure, ANGLE entry libraries, "
                "and selected compositor assets are included. This is not a complete "
                "Wawona application or a claim of successful device rendering."
            ),
        }
        (staging / "assets" / "wawona" / "provenance.json").write_text(
            json.dumps(provenance, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        if output_dir.exists():
            shutil.rmtree(output_dir)
        os.replace(staging, output_dir)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--apk",
        type=Path,
        help="APK built from the pinned Wawona revision",
    )
    source.add_argument(
        "--artifact-zip",
        type=Path,
        help="reviewed upstream product-android-apk ZIP (its ZIP, manifest and APK hashes are pinned)",
    )
    parser.add_argument("--readelf", required=True, help="NDK llvm-readelf executable")
    parser.add_argument(
        "--revision",
        required=True,
        help=f"Must equal the reviewed upstream commit {PINNED_WAWONA_REVISION}",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/build/generated/wawona"),
        help="Generated Gradle source directory",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.artifact_zip is not None:
            with tempfile.TemporaryDirectory(prefix="wawona-artifact-") as temporary:
                apk_path = extract_verified_pinned_artifact(
                    args.artifact_zip,
                    Path(temporary),
                )
                package_apk(apk_path, args.output, args.revision, args.readelf)
        else:
            package_apk(args.apk, args.output, args.revision, args.readelf)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Wawona packaging failed: {error}", file=os.sys.stderr)
        return 1
    print(f"Wawona compositor staged under {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
