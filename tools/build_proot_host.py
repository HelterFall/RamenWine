#!/usr/bin/env python3
"""Build and validate the pinned Android/Bionic PRoot host locally with Docker."""

from __future__ import annotations

import argparse
import hashlib
import json
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


REQUIRED_PROOT_CLI_OPTIONS = (
    "--rootfs",
    "--bind",
    "--pwd",
    "--kill-on-exit",
    "--link2symlink",
    "--sysvipc",
)

PROOT_SIGTERM_PATCH_ID = "ramenwine-proot-sigterm-cleanup-v1"
PROOT_SIGTERM_PATCH_MARKER = "# RAMENWINE_PROOT_SIGTERM_CLEANUP_V1"
PROOT_SIGTERM_RECIPE_PATCH = r"""    # RAMENWINE_PROOT_SIGTERM_CLEANUP_V1
    # Android Process.destroy() sends SIGTERM. Validate the extracted pinned
    # PRoot source before adapting its event loop to clean up every tracee.
    cli_header="$TERMUX_PKG_SRCDIR/src/cli/proot.h"
    cli_source="$TERMUX_PKG_SRCDIR/src/cli/proot.c"
    loader_source="$TERMUX_PKG_SRCDIR/src/execve/enter.c"
    temp_source="$TERMUX_PKG_SRCDIR/src/path/temp.c"
    event_source="$TERMUX_PKG_SRCDIR/src/tracee/event.c"
    for required_option in --rootfs --bind --pwd --kill-on-exit --link2symlink --sysvipc; do
        if ! grep -Fq ".name = \"$required_option\"" "$cli_header"; then
            echo "Pinned PRoot source is missing CLI option: $required_option" >&2
            return 1
        fi
    done
    grep -Fq 'static int handle_option_kill_on_exit' "$cli_header" || return 1
    grep -Fq 'tracee->killall_on_exit = true;' "$cli_source" || return 1
    grep -Fq 'getenv("PROOT_LOADER")' "$loader_source" || return 1
    grep -Fq 'PROOT_UNBUNDLE_LOADER' "$loader_source" || return 1
    grep -Fq 'getenv("PROOT_TMP_DIR")' "$temp_source" || return 1
    if ! grep -Fq 'case SIGQUIT:' "$event_source"; then
        echo "Unexpected pinned PRoot signal switch; refusing to patch it." >&2
        return 1
    fi
    if ! grep -Fq 'if (signum != SIGQUIT)' "$event_source"; then
        echo "Unexpected pinned PRoot SIGQUIT exit condition; refusing to patch it." >&2
        return 1
    fi
    if grep -Fq 'case SIGTERM:' "$event_source" ||
       grep -Fq 'if (signum != SIGQUIT && signum != SIGTERM)' "$event_source"; then
        echo "Pinned PRoot source already contains a partial SIGTERM patch." >&2
        return 1
    fi
    sed -i '/^[[:space:]]*case SIGQUIT:$/a\
        case SIGTERM:' "$event_source" || return 1
    sed -i 's/if (signum != SIGQUIT)/if (signum != SIGQUIT \&\& signum != SIGTERM)/' "$event_source" || return 1
    grep -Fq 'case SIGTERM:' "$event_source" || return 1
    grep -Fq 'if (signum != SIGQUIT && signum != SIGTERM)' "$event_source" || return 1
"""



def patch_pinned_proot_recipe(source: Path) -> bool:
    """Patch the pinned Termux recipe so SIGTERM reaps Wine/Box64 tracees."""
    recipe_path = source / "packages" / "proot" / "build.sh"
    if not recipe_path.is_file() or recipe_path.is_symlink():
        raise ValueError("Pinned Termux PRoot build recipe is missing or unsafe.")

    recipe = recipe_path.read_text(encoding="utf-8")
    marker_present = PROOT_SIGTERM_PATCH_MARKER in recipe
    required_tokens = (
        'event_source="$TERMUX_PKG_SRCDIR/src/tracee/event.c"',
        "/^[[:space:]]*case SIGQUIT:$/a",
        "case SIGTERM:",
        "if (signum != SIGQUIT && signum != SIGTERM)",
    )
    if marker_present:
        missing = [token for token in required_tokens if token not in recipe]
        if missing:
            raise ValueError(
                "Pinned PRoot recipe contains an incomplete SIGTERM patch: "
                + ", ".join(missing)
            )
        return False
    if any(token in recipe for token in required_tokens):
        raise ValueError("Pinned PRoot recipe contains an unmarked or partial downstream patch.")

    start_token = "termux_step_pre_configure() {"
    if recipe.count(start_token) != 1:
        raise ValueError("Pinned Termux PRoot recipe has an unexpected pre-configure layout.")
    start = recipe.index(start_token)
    end = recipe.find("\n}", start)
    if end < 0:
        raise ValueError("Pinned Termux PRoot pre-configure function is not terminated.")
    function_body = recipe[start:end]
    if function_body.count("CPPFLAGS+=") != 1:
        raise ValueError(
            "Pinned Termux PRoot pre-configure function changed; refusing to patch it."
        )

    patched = (
        recipe[:end]
        + "\n"
        + PROOT_SIGTERM_RECIPE_PATCH.rstrip()
        + recipe[end:]
    )
    recipe_path.write_text(patched, encoding="utf-8")
    return True


def record_proot_patch_provenance(host_payload: Path, recipe_path: Path) -> None:
    """Record the exact downstream PRoot patch that changed signal handling."""
    provenance_path = host_payload / "PROVENANCE.json"
    if not provenance_path.is_file() or provenance_path.is_symlink():
        raise ValueError("PRoot host payload is missing its provenance manifest.")
    try:
        metadata = json.loads(provenance_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError("PRoot host provenance manifest is invalid.") from error
    if not isinstance(metadata, dict) or metadata.get("target") != "android-bionic-arm64":
        raise ValueError("PRoot host provenance manifest has an unexpected target.")

    package_root = recipe_path.parents[2]
    metadata["downstreamPatches"] = {
        "prootTermination": {
            "id": PROOT_SIGTERM_PATCH_ID,
            "file": recipe_path.relative_to(package_root).as_posix(),
            "recipeSha256": hashlib.sha256(recipe_path.read_bytes()).hexdigest(),
            "behavior": (
                "Treat SIGTERM from Android Process.destroy() as a graceful PRoot "
                "shutdown request: kill all tracees and let the event loop reap them."
            ),
        }
    }
    temporary = provenance_path.with_name(provenance_path.name + ".tmp")
    temporary.write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, provenance_path)


def validate_pinned_proot_source_contract(source: Path) -> None:
    """Validate the pinned Termux recipe; its PRoot archive is checked in pre-configure."""
    recipe_path = source / "packages" / "proot" / "build.sh"
    if not recipe_path.is_file() or recipe_path.is_symlink():
        raise ValueError("Pinned Termux PRoot build recipe is missing or unsafe.")
    package_recipe = recipe_path.read_text(encoding="utf-8")

    required_recipe_values = (
        'TERMUX_PKG_VERSION="5.1.107.102"',
        "TERMUX_PKG_SRCURL=https://github.com/termux/proot/archive/v${TERMUX_PKG_VERSION}.zip",
        "TERMUX_PKG_SHA256=30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852",
        'TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"',
        'TERMUX_PKG_EXTRA_MAKE_ARGS="-C src PROOT_WITH_LIBANDROID_SHMEM=true"',
        "export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot",
    )
    for required in required_recipe_values:
        if required not in package_recipe:
            raise ValueError("Pinned Termux PRoot package recipe is missing a required Android build setting: " + required)

    required_patch_values = (
        PROOT_SIGTERM_PATCH_MARKER,
        'cli_header="$TERMUX_PKG_SRCDIR/src/cli/proot.h"',
        'event_source="$TERMUX_PKG_SRCDIR/src/tracee/event.c"',
        "for required_option in --rootfs --bind --pwd --kill-on-exit --link2symlink --sysvipc; do",
        'grep -Fq \'tracee->killall_on_exit = true;\' "$cli_source"',
        'grep -Fq \'getenv("PROOT_LOADER")\' "$loader_source"',
        'grep -Fq \'getenv("PROOT_TMP_DIR")\' "$temp_source"',
        "/^[[:space:]]*case SIGQUIT:$/a",
        "case SIGTERM:",
        "if (signum != SIGQUIT && signum != SIGTERM)",
    )
    for required in required_patch_values:
        if required not in package_recipe:
            raise ValueError("Pinned Termux PRoot source/cleanup validation is missing: " + required)


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

    # Android Process.destroy() sends SIGTERM; patch the pinned PRoot recipe
    # to route it through the upstream tracee cleanup handler.
    patch_pinned_proot_recipe(source)

    # Confirm the pinned source and patched recipe still provide the command
    # contract used by the Android runtime.
    validate_pinned_proot_source_contract(source)

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
    record_proot_patch_provenance(host_payload, source / "packages/proot" / "build.sh")
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
