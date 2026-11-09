#!/usr/bin/env python3
"""Build and validate the pinned Android/Bionic PRoot host locally with Docker."""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

TERMUX_REPOSITORY = "https://github.com/termux/termux-packages.git"
TERMUX_PACKAGES_COMMIT = "29a6879366dad92650fb7db7719c5a4b1b9d55f1"
BUILDER_IMAGE = "ramenwine-termux-package-builder:" + TERMUX_PACKAGES_COMMIT
ASSEMBLER = Path(__file__).with_name("assemble_proot_host.py")
FUSE_DEVICE = Path("/dev/fuse")


def run(
    command: list[str],
    cwd: Path | None = None,
    extra_env: dict[str, str] | None = None,
) -> None:
    print("+", " ".join(command), flush=True)
    environment = os.environ.copy()
    if extra_env:
        environment.update(extra_env)
    subprocess.run(command, cwd=cwd, env=environment, check=True)


def require_build_host() -> None:
    if platform.system() != "Linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise ValueError("The Termux Android PRoot builder requires an x86-64 Linux host.")
    try:
        release = platform.freedesktop_os_release()
    except (AttributeError, OSError):
        release = {}
    if release.get("ID") != "ubuntu" or release.get("VERSION_ID") != "24.04":
        raise ValueError("The Termux Android PRoot builder requires Ubuntu 24.04 x86-64.")


def require_tools() -> None:
    missing = [
        name for name in ("git", "docker", "dpkg-deb")
        if shutil.which(name) is None
    ]
    if missing:
        raise ValueError("Required host tools are missing: " + ", ".join(missing))
    if not FUSE_DEVICE.exists():
        raise ValueError(
            "The Termux Docker builder requires /dev/fuse on the Ubuntu build host."
        )
    run(["docker", "info"])


def validate_paths(work_dir: Path, output_dir: Path) -> tuple[Path, Path]:
    if work_dir.is_symlink():
        raise ValueError("work-dir must not be a symlink.")
    if output_dir.is_symlink():
        raise ValueError("output-dir must not be a symlink.")

    work = work_dir.resolve(strict=False)
    output = output_dir.resolve(strict=False)

    if work == output or work in output.parents or output in work.parents:
        raise ValueError("work-dir and output-dir must be separate, non-overlapping directories.")

    if work.exists():
        if not work.is_dir():
            raise ValueError("work-dir must be a directory.")
        if any(work.iterdir()):
            raise ValueError("work-dir must be empty; choose a new directory to protect existing files.")

    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            raise ValueError("output-dir must not exist or must be an empty directory.")

    return work, output


def build(args: argparse.Namespace) -> Path:
    require_build_host()
    require_tools()
    work, output = validate_paths(args.work_dir, args.output_dir)
    work.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)

    source = work / "termux-packages"
    run(["git", "init", str(source)])
    run(["git", "-C", str(source), "remote", "add", "origin", TERMUX_REPOSITORY])
    run([
        "git", "-C", str(source), "fetch", "--depth=1",
        "origin", TERMUX_PACKAGES_COMMIT,
    ])
    run(["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"])

    revision = subprocess.run(
        ["git", "-C", str(source), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if revision != TERMUX_PACKAGES_COMMIT:
        raise ValueError(
            "Termux checkout does not match the pinned source commit: " + revision
        )

    container_name = "ramenwine-proot-host-" + uuid.uuid4().hex[:12]
    try:
        run([
            "docker", "build",
            "--file", str(source / "scripts" / "Dockerfile"),
            "--tag", BUILDER_IMAGE,
            str(source / "scripts"),
        ])
        run(
            [
                "bash",
                str(source / "scripts" / "run-docker.sh"),
                "./build-package.sh",
                "-a",
                "aarch64",
                "proot",
            ],
            cwd=source,
            extra_env={
                "TERMUX_BUILDER_IMAGE_NAME": BUILDER_IMAGE,
                "CONTAINER_NAME": container_name,
            },
        )
    finally:
        # run-docker.sh leaves its detached builder container alive for reuse.
        # This build owns a unique name, so it is safe to remove unconditionally.
        subprocess.run(
            ["docker", "rm", "--force", container_name],
            check=False,
            capture_output=True,
            text=True,
        )

    packages_dir = source / "output"
    run([
        sys.executable,
        str(ASSEMBLER),
        "--packages-dir",
        str(packages_dir),
        "--output-dir",
        str(output),
    ])
    host_payload = output / "host"
    if not host_payload.is_dir():
        raise ValueError("The PRoot assembler did not create its host payload.")
    print("Validated Android/Bionic PRoot host payload:", host_payload)
    return host_payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        build(args)
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("PRoot host build failed:", error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
