from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import fetch_runtime_sources


class RuntimeSourceFetchTests(unittest.TestCase):
    def valid_lock(self) -> dict:
        return {
            "schemaVersion": 1,
            "profile": "glibc-proot-box64",
            "sources": [
                {
                    "name": "box64",
                    "type": "git",
                    "url": "https://github.com/ptitSeb/box64.git",
                    "commit": "c" * 40,
                },
                {
                    "name": "wine",
                    "type": "archive",
                    "url": "https://example.invalid/wine.tar.xz",
                    "filename": "wine.tar.xz",
                    "sha256": "a" * 64,
                },
            ],
        }

    def write_lock(self, directory: Path, value: dict) -> Path:
        path = directory / "lock.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_accepts_https_sources_with_pinned_revisions(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lock_path = self.write_lock(Path(temporary), self.valid_lock())
            lock = fetch_runtime_sources.load_lock(lock_path)
            self.assertEqual(lock["profile"], "glibc-proot-box64")
            self.assertEqual(len(lock["sources"]), 2)

    def test_rejects_git_source_without_full_commit_pin(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lock = self.valid_lock()
            lock["sources"][0]["commit"] = "main"
            with self.assertRaises(fetch_runtime_sources.SourceFetchError):
                fetch_runtime_sources.load_lock(self.write_lock(Path(temporary), lock))

    def test_rejects_non_https_source(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lock = self.valid_lock()
            lock["sources"][1]["url"] = "http://example.invalid/wine.tar.xz"
            with self.assertRaises(fetch_runtime_sources.SourceFetchError):
                fetch_runtime_sources.load_lock(self.write_lock(Path(temporary), lock))

    def test_rejects_archive_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lock = self.valid_lock()
            lock["sources"][1]["filename"] = "../wine.tar.xz"
            with self.assertRaises(fetch_runtime_sources.SourceFetchError):
                fetch_runtime_sources.load_lock(self.write_lock(Path(temporary), lock))

    def test_rejects_malformed_sha256(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            lock = self.valid_lock()
            lock["sources"][1]["sha256"] = "not-a-hash"
            with self.assertRaises(fetch_runtime_sources.SourceFetchError):
                fetch_runtime_sources.load_lock(self.write_lock(Path(temporary), lock))

    def test_sha256_file_matches_standard_digest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.bin"
            path.write_bytes(b"ramenwine")
            self.assertEqual(
                hashlib.sha256(b"ramenwine").hexdigest(),
                fetch_runtime_sources.sha256_file(path),
            )


if __name__ == "__main__":
    unittest.main()
