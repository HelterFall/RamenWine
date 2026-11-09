from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

spec = importlib.util.spec_from_file_location(
    "build_glibc_runtime",
    TOOLS_DIR / "build_glibc_runtime.py",
)
assert spec is not None and spec.loader is not None
build_glibc_runtime = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = build_glibc_runtime
spec.loader.exec_module(build_glibc_runtime)


class BuildGlibcRuntimeTests(unittest.TestCase):
    def test_accepts_current_pinned_snapshot_and_runtime_version(self) -> None:
        build_glibc_runtime.validate_arguments(
            "20261109T000000Z",
            "ubuntu24.04.5-wine11.0-box64-v0.4.4",
            2,
        )

    def test_rejects_latest_instead_of_an_exact_snapshot(self) -> None:
        with self.assertRaisesRegex(ValueError, "snapshot"):
            build_glibc_runtime.validate_arguments("latest", "runtime-1", 2)

    def test_rejects_path_like_runtime_version(self) -> None:
        with self.assertRaisesRegex(ValueError, "version"):
            build_glibc_runtime.validate_arguments(
                "20261109T000000Z", "../runtime", 2
            )

    def test_rejects_excessive_parallel_build_jobs(self) -> None:
        with self.assertRaisesRegex(ValueError, "jobs"):
            build_glibc_runtime.validate_arguments(
                "20261109T000000Z", "runtime-1", 33
            )

    def test_host_build_uses_only_official_noble_amd64_sources(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keyring = root / "ubuntu-archive-keyring.gpg"
            keyring.write_bytes(b"test keyring")
            options = build_glibc_runtime.prepare_host_apt(
                root / "host-apt",
                keyring=keyring,
            )
            sources = (root / "host-apt" / "sources.list").read_text(encoding="utf-8")

            self.assertIn("archive.ubuntu.com/ubuntu noble", sources)
            self.assertIn("security.ubuntu.com/ubuntu noble-security", sources)
            self.assertNotIn("ports.ubuntu.com", sources)
            self.assertNotIn("ppa.launchpad", sources)
            self.assertIn("APT::Architecture=amd64", options)
            self.assertIn("Dir::Etc::sourceparts=-", options)

    def test_runtime_package_sources_cover_arm64_and_amd64(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keyring = root / "ubuntu-archive-keyring.gpg"
            keyring.write_bytes(b"test keyring")
            archives, options = build_glibc_runtime.prepare_apt_root(
                root / "apt",
                keyring=keyring,
            )
            sources = (root / "apt" / "sources.list").read_text(encoding="utf-8")

            self.assertIn("arch=amd64", sources)
            self.assertIn("arch=arm64", sources)
            self.assertIn("archive.ubuntu.com/ubuntu", sources)
            self.assertIn("ports.ubuntu.com/ubuntu-ports", sources)
            self.assertTrue((archives / "partial").is_dir())
            self.assertIn("APT::Architecture=amd64", options)
            self.assertIn("APT::Architectures::=arm64", options)

    def test_source_pins_and_wow64_wayland_build_options_are_explicit(self) -> None:
        self.assertEqual(
            build_glibc_runtime.BOX64_COMMIT,
            "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
        )
        self.assertEqual(
            build_glibc_runtime.WINE_SHA256,
            "c07a6857933c1fc60dff5448d79f39c92481c1e9db5aa628db9d0358446e0701",
        )
        self.assertIn(
            "--enable-archs=i386,x86_64",
            build_glibc_runtime.WINE_CONFIGURE_FLAGS,
        )
        self.assertIn("--with-wayland", build_glibc_runtime.WINE_CONFIGURE_FLAGS)
        self.assertIn("--without-x", build_glibc_runtime.WINE_CONFIGURE_FLAGS)
        self.assertIn("libwine:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("coreutils:arm64", build_glibc_runtime.RUNTIME_PACKAGES)


if __name__ == "__main__":
    unittest.main()
