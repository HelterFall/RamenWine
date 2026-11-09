#!/usr/bin/env python3
"""Assemble an app-private Android/Bionic PRoot host payload from Termux .deb files.

The pinned Termux build is performed by build-proot-host-runtime.yml. This tool
extracts PRoot, its unbundled ELF loader, and the PRoot shared-library closure.
"""
from __future__ import annotations

import argparse
import email
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import package_runtime

TERMUX_PACKAGES_COMMIT = "29a6879366dad92650fb7db7719c5a4b1b9d55f1"
TERMUX_PREFIX = Path("data/data/com.termux/files/usr")
EXPECTED_PACKAGES = {
    "proot": "5.1.107.102",
    "libtalloc": "2.5.0",
    "libandroid-shmem": "0.7",
}
TERMUX_NDK_VERSION = "30"
TERMUX_SDK_COMMANDLINE_TOOLS_REVISION = "9123335"
TERMUX_ANDROID_BUILD_TOOLS_VERSION = "37.0.0"
TERMUX_SDK_COMMANDLINE_TOOLS_SHA256 = "0bebf59339eaa534f4217f8aa0972d14dc49e7207be225511073c661ae01da0a"
TERMUX_NDK_ARCHIVE_SHA256 = "753611f410d002cfcd3f3dc2ef49aad532089d3180b436c060a90bf0fcb64df2"


@dataclass(frozen=True)
class DebPackage:
    name: str
    version: str
    architecture: str
    path: Path
    sha256: str


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_deb_metadata(path: Path) -> tuple[str, str, str]:
    try:
        result = subprocess.run(
            ["dpkg-deb", "-f", str(path)],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError(f"could not read Debian package metadata: {path}") from error

    message = email.message_from_string(result.stdout)
    name = message.get("Package")
    version = message.get("Version")
    architecture = message.get("Architecture")
    if not name or not version or not architecture:
        raise ValueError(f"Debian package metadata is incomplete: {path}")
    return name, version, architecture


def find_required_debs(packages_dir: Path) -> dict[str, DebPackage]:
    if not packages_dir.is_dir():
        raise ValueError(f"Termux package output directory does not exist: {packages_dir}")

    found: dict[str, DebPackage] = {}
    for deb in sorted(packages_dir.glob("*.deb")):
        name, version, architecture = read_deb_metadata(deb)
        if name not in EXPECTED_PACKAGES:
            continue
        if name in found:
            raise ValueError(f"multiple output packages found for {name}")
        expected_version = EXPECTED_PACKAGES[name]
        if version != expected_version:
            raise ValueError(
                f"{name} package version mismatch: expected {expected_version}, got {version}"
            )
        if architecture != "aarch64":
            raise ValueError(
                f"{name} package architecture mismatch: expected aarch64, got {architecture}"
            )
        found[name] = DebPackage(
            name=name,
            version=version,
            architecture=architecture,
            path=deb,
            sha256=sha256_file(deb),
        )

    missing = sorted(set(EXPECTED_PACKAGES) - set(found))
    if missing:
        raise ValueError(f"Termux build output is missing packages: {', '.join(missing)}")
    return found


def safe_copy_regular_or_relative_symlink(source: Path, destination: Path, root: Path) -> None:
    try:
        source.lstat()
    except OSError as error:
        raise ValueError(f"required package file is missing: {source}") from error

    if source.is_symlink():
        target = os.readlink(source)
        if not target or os.path.isabs(target) or "\\" in target or "\x00" in target:
            raise ValueError(f"unsafe absolute or invalid shared-library symlink: {source} -> {target!r}")
        # Resolve the candidate before requiring it to exist: an escaping
        # symlink may intentionally point to a non-existent path outside the
        # package root, and must still be rejected as an escape.
        resolved = (source.parent / target).resolve(strict=False)
        try:
            resolved.relative_to(root.resolve(strict=True))
        except ValueError as error:
            raise ValueError(f"shared-library symlink escapes extracted package: {source}") from error
        if not resolved.is_file():
            raise ValueError(f"shared-library symlink does not resolve to a file: {source}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.symlink_to(target)
        return

    if not source.is_file():
        raise ValueError(f"unsupported package payload entry: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination, follow_symlinks=False)


def package_prefix(extracted_root: Path, package_name: str) -> Path:
    prefix = extracted_root / TERMUX_PREFIX
    if not prefix.is_dir():
        raise ValueError(
            f"{package_name} package does not contain expected Termux prefix {TERMUX_PREFIX}"
        )
    return prefix


def stage_host_payload(
    extracted_roots: dict[str, Path],
    output_dir: Path,
    provenance: dict[str, object],
) -> Path:
    missing = sorted(set(EXPECTED_PACKAGES) - set(extracted_roots))
    if missing:
        raise ValueError(f"missing extracted Termux packages: {', '.join(missing)}")

    output_dir = output_dir.absolute()
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    if output_dir.is_symlink():
        raise ValueError(f"host payload output directory must not be a symlink: {output_dir}")
    if output_dir.exists() and (
        not output_dir.is_dir() or any(output_dir.iterdir())
    ):
        raise ValueError(f"host payload output directory must be empty: {output_dir}")

    # Build beside the destination and rename only after every ELF/dependency
    # check and provenance write succeeds. A failed assembly leaves no partial
    # artifact that would block a clean retry.
    staging = Path(tempfile.mkdtemp(
        prefix="." + output_dir.name + ".staging-",
        dir=output_dir.parent,
    ))
    try:
        prefixes = {
            name: package_prefix(root, name)
            for name, root in extracted_roots.items()
        }

        proot_source = prefixes["proot"] / "bin" / "proot"
        loader_source = prefixes["proot"] / "libexec" / "proot" / "loader"
        host_bin = staging / "host" / "bin"
        host_loader = staging / "host" / "libexec" / "proot"
        host_lib = staging / "host" / "lib"

        safe_copy_regular_or_relative_symlink(
            proot_source, host_bin / "proot", prefixes["proot"]
        )
        safe_copy_regular_or_relative_symlink(
            loader_source, host_loader / "loader", prefixes["proot"]
        )

        copied_libraries: set[str] = set()
        for prefix in prefixes.values():
            source_lib = prefix / "lib"
            if not source_lib.is_dir():
                continue
            for source in sorted(source_lib.iterdir(), key=lambda item: item.name):
                if not source.name.startswith("lib") or ".so" not in source.name:
                    continue
                destination = host_lib / source.name
                if destination.exists() or destination.is_symlink():
                    raise ValueError(f"duplicate shared-library payload entry: {source.name}")
                safe_copy_regular_or_relative_symlink(source, destination, prefix)
                copied_libraries.add(source.name)

        if not any(name.startswith("libtalloc.so") for name in copied_libraries):
            raise ValueError("Termux libtalloc package did not provide a shared library")
        if not any(name.startswith("libandroid-shmem.so") for name in copied_libraries):
            raise ValueError("Termux libandroid-shmem package did not provide a shared library")

        staged_host = staging / "host"
        staged_proot = staged_host / "bin" / "proot"
        staged_loader = staged_host / "libexec" / "proot" / "loader"
        package_runtime.require_executable(staged_proot, "PRoot host executable")
        package_runtime.require_elf64(
            staged_proot,
            "PRoot host executable",
            package_runtime.ELF_MACHINE_AARCH64,
            "/system/bin/linker64",
        )
        package_runtime.require_executable(staged_loader, "PRoot ELF loader")
        package_runtime.require_elf64(
            staged_loader,
            "PRoot ELF loader",
            package_runtime.ELF_MACHINE_AARCH64,
        )

        for name in sorted(copied_libraries):
            package_runtime.require_elf64(
                (staged_host / "lib" / name).resolve(strict=True),
                f"PRoot host shared library {name}",
                package_runtime.ELF_MACHINE_AARCH64,
            )

        # Validate the real DT_NEEDED closure at the point this host package is
        # created, not only later when a full Wine guest build consumes it.
        package_runtime.validate_android_proot_host_payload(staged_host)

        metadata = {
            "formatVersion": 1,
            "target": "android-bionic-arm64",
            "termuxPackagesCommit": TERMUX_PACKAGES_COMMIT,
            "androidNdkVersion": TERMUX_NDK_VERSION,
            "androidNdkArchiveSha256": TERMUX_NDK_ARCHIVE_SHA256,
            "androidSdkCommandLineToolsRevision": TERMUX_SDK_COMMANDLINE_TOOLS_REVISION,
            "androidSdkCommandLineToolsSha256": TERMUX_SDK_COMMANDLINE_TOOLS_SHA256,
            "androidBuildToolsVersion": TERMUX_ANDROID_BUILD_TOOLS_VERSION,
            "packageSources": provenance,
            "files": {
                "proot": "host/bin/proot",
                "loader": "host/libexec/proot/loader",
                "libraries": [f"host/lib/{name}" for name in sorted(copied_libraries)],
            },
            "stagedSha256": {
                "bin/proot": sha256_file(staged_proot),
                "libexec/proot/loader": sha256_file(staged_loader),
                **{
                    f"lib/{name}": sha256_file(staged_host / "lib" / name)
                    for name in sorted(copied_libraries)
                },
            },
        }
        provenance_path = staged_host / "PROVENANCE.json"
        provenance_path.write_text(
            json.dumps(metadata, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        if output_dir.exists():
            output_dir.rmdir()
        os.replace(staging, output_dir)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise

    return output_dir / "host"

def assemble(packages_dir: Path, output_dir: Path) -> Path:
    packages = find_required_debs(packages_dir)
    provenance: dict[str, object] = {
        name: {
            "version": package.version,
            "architecture": package.architecture,
            "debSha256": package.sha256,
            "sourceCommit": TERMUX_PACKAGES_COMMIT,
        }
        for name, package in sorted(packages.items())
    }

    with tempfile.TemporaryDirectory(prefix="ramenwine-termux-host-") as temporary:
        extracted: dict[str, Path] = {}
        for name, package in packages.items():
            package_root = Path(temporary) / name
            package_root.mkdir(parents=True)
            try:
                subprocess.run(
                    ["dpkg-deb", "-x", str(package.path), str(package_root)],
                    check=True,
                    capture_output=True,
                    text=True,
                )
            except (OSError, subprocess.CalledProcessError) as error:
                raise ValueError(f"could not extract Termux package {name}") from error
            extracted[name] = package_root

        return stage_host_payload(extracted, output_dir, provenance)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    try:
        host_dir = assemble(args.packages_dir, args.output_dir)
    except (OSError, ValueError) as error:
        print(f"runtime host assembly failed: {error}", file=sys.stderr)
        return 1

    print(f"Validated Android/Bionic PRoot host payload: {host_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
