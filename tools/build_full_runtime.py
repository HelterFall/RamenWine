#!/usr/bin/env python3
"""Build the complete PRoot + Ubuntu/glibc + Box64 + Wine 11 runtime locally."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_glibc_runtime
import build_proot_host


PROOT_BUILDER = Path(__file__).with_name("build_proot_host.py")
GLIBC_BUILDER = Path(__file__).with_name("build_glibc_runtime.py")


def validate_paths(work_dir: Path, output_zip: Path) -> tuple[Path, Path]:
    """Refuse symlinks, pre-existing work and output paths, and overlapping paths."""
    if work_dir.is_symlink():
        raise ValueError("work-dir must not be a symlink.")
    if output_zip.is_symlink():
        raise ValueError("output ZIP must not be a symlink.")

    work = work_dir.resolve(strict=False)
    output = output_zip.resolve(strict=False)
    if work == output or work in output.parents or output in work.parents:
        raise ValueError("work-dir and output ZIP must be separate and non-overlapping.")

    if work.exists():
        if not work.is_dir():
            raise ValueError("work-dir must be a directory.")
        if any(work.iterdir()):
            raise ValueError("work-dir must be empty; existing files will not be overwritten.")

    if output.exists():
        raise ValueError("output ZIP already exists; choose a new output path.")

    return work, output


def run(command: list[str]) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def build(args: argparse.Namespace) -> Path:
    # Validate all stage-two options before the Docker/PRoot stage can consume
    # time or leave a partially populated workspace.
    build_glibc_runtime.validate_arguments(args.snapshot, args.version, args.jobs)

    # Run the same platform and Docker checks as the standalone Android-host
    # builder before creating any workspace files.
    build_proot_host.require_build_host()
    build_proot_host.require_tools()
    work, output = validate_paths(args.work_dir, args.output)
    work.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)

    proot_work = work / "proot-build"
    proot_artifact = work / "proot-artifact"
    runtime_work = work / "glibc-runtime-build"
    host_payload = proot_artifact / "host"

    print("=== Stage 1/2: Android/Bionic PRoot host ===", flush=True)
    run([
        sys.executable,
        str(PROOT_BUILDER),
        "--work-dir",
        str(proot_work),
        "--output-dir",
        str(proot_artifact),
    ])
    if not host_payload.is_dir():
        raise ValueError("PRoot build completed without a validated host payload.")

    print("=== Stage 2/2: Ubuntu ARM64 + Box64 + Wine 11 WoW64 ===", flush=True)
    command = [
        sys.executable,
        str(GLIBC_BUILDER),
        "--proot-host",
        str(host_payload),
        "--work-dir",
        str(runtime_work),
        "--output",
        str(output),
        "--snapshot",
        args.snapshot,
        "--version",
        args.version,
        "--jobs",
        str(args.jobs),
    ]
    if args.no_arm_dynarec:
        command.append("--no-arm-dynarec")
    if args.skip_host_dependencies:
        command.append("--skip-host-dependencies")
    run(command)

    if not output.is_file() or output.stat().st_size <= 0:
        raise ValueError("The full runtime builder did not create a non-empty ZIP package.")

    print("Complete runtime package created:", output, flush=True)
    print("Build workspace retained for inspection:", work, flush=True)
    return output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--snapshot", default=build_glibc_runtime.DEFAULT_SNAPSHOT)
    parser.add_argument(
        "--version",
        default="ubuntu24.04.5-wine11.0-box64-v0.4.4",
    )
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--no-arm-dynarec", action="store_true")
    parser.add_argument("--skip-host-dependencies", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        build(args)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("Full runtime build failed:", error, file=sys.stderr)
        print("The build workspace is retained when it was created.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
