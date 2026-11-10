#!/usr/bin/env python3
"""Fetch checksum-pinned runtime inputs for RamenWine.

This stage only acquires sources and a pinned Wine distribution archive.
It does not compile PRoot/Box64, create a Linux rootfs, or produce an installable
RamenWine runtime ZIP.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_LOCK = SCRIPT_DIR / "runtime-sources.lock.json"
DEFAULT_OUTPUT = SCRIPT_DIR.parent / "build" / "runtime-sources"
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
CHUNK_BYTES = 1024 * 1024
GIT_COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SAFE_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]{0,80}$")


class SourceFetchError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK_BYTES)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def run_git(arguments: list[str], *, cwd: Path | None = None) -> str:
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=cwd,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=900,
        )
    except FileNotFoundError as error:
        raise SourceFetchError("Git is required to fetch pinned source repositories.") from error
    except subprocess.TimeoutExpired as error:
        raise SourceFetchError("Git operation timed out: " + " ".join(arguments[:3])) from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "").strip()
        raise SourceFetchError(
            "Git operation failed: " + " ".join(arguments[:3]) +
            (": " + detail[-2000:] if detail else "")
        ) from error
    return result.stdout.strip()


def load_lock(path: Path) -> dict[str, Any]:
    try:
        lock = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceFetchError(f"Could not read source lock: {path}") from error

    if lock.get("schemaVersion") != 1 or lock.get("profile") != "glibc-proot-box64":
        raise SourceFetchError("Unsupported runtime source lock schema/profile.")

    sources = lock.get("sources")
    if not isinstance(sources, list) or not sources:
        raise SourceFetchError("The source lock contains no source entries.")

    seen: set[str] = set()
    for entry in sources:
        if not isinstance(entry, dict):
            raise SourceFetchError("Invalid source entry.")
        name = entry.get("name")
        source_type = entry.get("type")
        url = entry.get("url")
        if not isinstance(name, str) or not SAFE_NAME_PATTERN.fullmatch(name):
            raise SourceFetchError(f"Invalid source name: {name!r}")
        if name in seen:
            raise SourceFetchError(f"Duplicate source name: {name}")
        seen.add(name)
        if not isinstance(url, str) or not url.startswith("https://"):
            raise SourceFetchError(f"Source URL must use HTTPS: {name}")
        if source_type == "git":
            commit = entry.get("commit")
            if not isinstance(commit, str) or not GIT_COMMIT_PATTERN.fullmatch(commit):
                raise SourceFetchError(f"Invalid pinned Git commit for {name}")
        elif source_type == "archive":
            filename = entry.get("filename")
            digest = entry.get("sha256")
            if (
                not isinstance(filename, str) or
                Path(filename).name != filename or
                not SAFE_NAME_PATTERN.fullmatch(filename.replace(".tar.gz", ".tar-gz").replace(".tar.xz", ".tar-xz"))
            ):
                raise SourceFetchError(f"Invalid archive filename for {name}")
            if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
                raise SourceFetchError(f"Invalid SHA-256 for {name}")
        else:
            raise SourceFetchError(f"Unsupported source type for {name}: {source_type!r}")
    return lock


def download_archive(entry: dict[str, Any], destination: Path) -> dict[str, Any]:
    expected_digest = entry["sha256"]
    partial = destination.with_name(destination.name + ".part")
    destination.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    total = 0

    request = urllib.request.Request(
        entry["url"],
        headers={"User-Agent": "RamenWine-runtime-source-fetcher/1"},
    )

    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            while True:
                block = response.read(CHUNK_BYTES)
                if not block:
                    break
                total += len(block)
                if total > MAX_ARCHIVE_BYTES:
                    raise SourceFetchError(
                        f"{entry['name']} exceeds the {MAX_ARCHIVE_BYTES}-byte download limit."
                    )
                digest.update(block)
                output.write(block)
            output.flush()
            os.fsync(output.fileno())
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        partial.unlink(missing_ok=True)
        raise SourceFetchError(f"Could not download {entry['name']}: {error}") from error
    except SourceFetchError:
        partial.unlink(missing_ok=True)
        raise

    actual_digest = digest.hexdigest()
    if actual_digest != expected_digest:
        partial.unlink(missing_ok=True)
        raise SourceFetchError(
            f"SHA-256 mismatch for {entry['name']}: expected {expected_digest}, got {actual_digest}"
        )

    os.replace(partial, destination)
    return {
        "name": entry["name"],
        "type": "archive",
        "filename": destination.name,
        "sha256": actual_digest,
        "sizeBytes": total,
        "url": entry["url"],
    }


def checkout_repository(entry: dict[str, Any], destination: Path) -> dict[str, Any]:
    if destination.exists():
        shutil.rmtree(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)

    run_git(["clone", "--filter=blob:none", "--no-checkout", entry["url"], str(destination)])
    run_git(["fetch", "--depth=1", "origin", entry["commit"]], cwd=destination)
    run_git(["checkout", "--quiet", "--detach", "FETCH_HEAD"], cwd=destination)

    actual_commit = run_git(["rev-parse", "HEAD"], cwd=destination)
    if actual_commit != entry["commit"]:
        raise SourceFetchError(
            f"Git revision mismatch for {entry['name']}: "
            f"expected {entry['commit']}, got {actual_commit}"
        )

    return {
        "name": entry["name"],
        "type": "git",
        "commit": actual_commit,
        "url": entry["url"],
    }


def fetch_sources(lock_path: Path, output: Path) -> Path:
    lock = load_lock(lock_path)
    output.mkdir(parents=True, exist_ok=True)
    source_root = output / "sources"
    archive_root = output / "archives"
    source_root.mkdir(exist_ok=True)
    archive_root.mkdir(exist_ok=True)

    fetched: list[dict[str, Any]] = []
    for entry in lock["sources"]:
        name = entry["name"]
        print(f"Fetching pinned input: {name}", flush=True)
        if entry["type"] == "git":
            item = checkout_repository(entry, source_root / name)
        else:
            item = download_archive(entry, archive_root / entry["filename"])
        fetched.append(item)

    lock_digest = sha256_file(lock_path)
    provenance = {
        "schemaVersion": 1,
        "profile": lock["profile"],
        "sourceLockSha256": lock_digest,
        "toolchain": lock.get("toolchain", {}),
        "sources": fetched,
    }
    provenance_path = output / "fetched-sources.json"
    temporary = provenance_path.with_name(provenance_path.name + ".tmp")
    temporary.write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, provenance_path)
    return provenance_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        path = fetch_sources(args.lock, args.output)
    except (SourceFetchError, OSError) as error:
        print(f"runtime source fetch failed: {error}", file=sys.stderr)
        return 2

    print(f"Pinned inputs are ready: {args.output}")
    print(f"Provenance: {path}")
    print("No binaries were built and no runtime ZIP was created.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
