from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_invalid_proot_host_is_rejected_before_guest_build_setup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            host = root / "proot-host"
            (host / "bin").mkdir(parents=True)
            (host / "libexec" / "proot").mkdir(parents=True)
            (host / "lib").mkdir()
            (host / "bin" / "proot").write_bytes(b"fixture")
            (host / "libexec" / "proot" / "loader").write_bytes(b"fixture")
            work = root / "work"
            work.mkdir()
            args = type("Args", (), {
                "snapshot": "20261109T000000Z",
                "version": "runtime-1",
                "jobs": 2,
                "work_dir": work,
                "proot_host": host,
                "skip_host_dependencies": False,
                "no_arm_dynarec": False,
                "output": root / "runtime.zip",
            })()

            with (
                patch.object(build_glibc_runtime, "require_build_host"),
                patch.object(
                    build_glibc_runtime.package_runtime,
                    "validate_android_proot_host_payload",
                    side_effect=ValueError("unresolved Android PRoot host dependency"),
                ) as validate_host,
                patch.object(build_glibc_runtime, "install_host_packages") as install_host,
            ):
                with self.assertRaisesRegex(ValueError, "unresolved Android PRoot host dependency"):
                    build_glibc_runtime.build(args)

            validate_host.assert_called_once_with(host.resolve())
            install_host.assert_not_called()
            self.assertFalse((work / "backend").exists())

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
            self.assertTrue(all(
                "snapshot=enable" in line
                for line in sources.splitlines()
                if line.startswith("deb ")
            ))
            self.assertNotIn("ports.ubuntu.com", sources)
            self.assertNotIn("ppa.launchpad", sources)
            self.assertIn("APT::Architecture=amd64", options)
            self.assertIn("Dir::Etc::sourceparts=-", options)

    def test_host_packages_include_complete_wayland_build_tools(self) -> None:
        self.assertIn("libwayland-dev", build_glibc_runtime.HOST_PACKAGES)
        self.assertIn("libwayland-bin", build_glibc_runtime.HOST_PACKAGES)
        self.assertIn("libxkbcommon-dev", build_glibc_runtime.HOST_PACKAGES)
        self.assertIn("libxkbregistry-dev", build_glibc_runtime.HOST_PACKAGES)
        self.assertIn("wayland-scanner", build_glibc_runtime.REQUIRED_TOOLS)

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
            self.assertTrue(all(
                "snapshot=enable" in line
                for line in sources.splitlines()
                if line.startswith("deb ")
            ))
            self.assertIn("archive.ubuntu.com/ubuntu", sources)
            self.assertIn("ports.ubuntu.com/ubuntu-ports", sources)
            self.assertTrue((archives / "partial").is_dir())
            self.assertIn("APT::Architecture=amd64", options)
            self.assertIn("APT::Architectures::=arm64", options)


    def test_records_libwine_as_dependency_seed_without_extracting_it(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archives = root / "apt" / "archives"
            archives.mkdir(parents=True)
            rootfs = root / "rootfs"
            rootfs.mkdir()
            libc_deb = archives / "libc6_2.39.deb"
            wine_deb = archives / "libwine_9.0.deb"
            libc_deb.write_bytes(b"libc package fixture")
            wine_deb.write_bytes(b"Ubuntu Wine package fixture")
            metadata = {
                libc_deb.name: {
                    "Package": "libc6",
                    "Version": "2.39-0ubuntu8",
                    "Architecture": "arm64",
                },
                wine_deb.name: {
                    "Package": "libwine",
                    "Version": "9.0~repack-4build3",
                    "Architecture": "amd64",
                },
            }
            executed: list[list[str]] = []

            def fake_output(command: list[str]) -> str:
                return metadata[Path(command[2]).name][command[-1]]

            with (
                patch.object(
                    build_glibc_runtime,
                    "prepare_apt_root",
                    return_value=(archives, ["-o", "Dir::State::test"]),
                ),
                patch.object(
                    build_glibc_runtime,
                    "run",
                    side_effect=lambda command, **kwargs: executed.append(command),
                ),
                patch.object(
                    build_glibc_runtime,
                    "output",
                    side_effect=fake_output,
                ),
                patch.object(
                    build_glibc_runtime,
                    "sha256_file",
                    return_value="a" * 64,
                ),
            ):
                provenance = build_glibc_runtime.download_and_extract_guest_packages(
                    rootfs,
                    root / "apt",
                    "20261109T000000Z",
                )

            self.assertEqual(
                {"libc6", "libwine"},
                {str(item["name"]) for item in provenance},
            )
            extracted = {
                str(item["name"]): item["extracted"]
                for item in provenance
            }
            self.assertTrue(extracted["libc6"])
            self.assertFalse(extracted["libwine"])
            self.assertTrue(
                any(
                    command[:2] == ["dpkg-deb", "-x"] and command[2] == str(libc_deb)
                    for command in executed
                )
            )
            self.assertFalse(
                any(
                    command[:2] == ["dpkg-deb", "-x"] and command[2] == str(wine_deb)
                    for command in executed
                )
            )

    def test_box64_bundle_script_cleanup_is_scoped_to_its_build_directory(self) -> None:
        source = (
            "rm -rf /tmp/box64-bundle.*\n"
            "dir_tmp=\"$(mktemp -d /tmp/box64-bundle.XXXXXX)\"\n"
        )
        safe = build_glibc_runtime.make_box64_bundle_script_safe(source)

        self.assertNotIn("rm -rf /tmp/box64-bundle.*", safe)
        self.assertIn('[ -z "${dir_tmp:-}" ] || rm -rf "${dir_tmp}"', safe)
        self.assertIn('mktemp -d "${TMPDIR:-/tmp}/box64-bundle.XXXXXX"', safe)

    def test_source_pins_and_wow64_wayland_build_options_are_explicit(self) -> None:
        self.assertEqual(
            build_glibc_runtime.BOX64_COMMIT,
            "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
        )
        self.assertIn("libstdc++.so.6", build_glibc_runtime.BOX64_REQUIRED_X64_LIBRARIES)
        self.assertIn("libunwind.so.8", build_glibc_runtime.BOX64_REQUIRED_X64_LIBRARIES)
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
        self.assertIn("zlib1g:arm64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("zlib1g:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("libwayland-client0:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("libwayland-cursor0:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("libxkbcommon0:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("libxkbregistry0:amd64", build_glibc_runtime.RUNTIME_PACKAGES)
        self.assertIn("xkb-data", build_glibc_runtime.RUNTIME_PACKAGES)
        for package in (
            "libdbus-1-3:amd64",
            "libgnutls30t64:amd64",
            "libopenal1:amd64",
            "libsdl2-2.0-0:amd64",
            "libldap2:amd64",
            "libmpg123-0t64:amd64",
            "liblcms2-2:amd64",
            "libgcrypt20:amd64",
            "libgsm1:amd64",
            "libjpeg-turbo8:amd64",
            "libpng16-16t64:amd64",
            "libtiff6:amd64",
            "libwebp7:amd64",
            "libxml2:amd64",
            "libxslt1.1:amd64",
            "libzstd1:amd64",
            "libavcodec60:amd64",
            "libavformat60:amd64",
            "libavutil58:amd64",
            "libswscale7:amd64",
            "libswresample4:amd64",
        ):
            self.assertIn(package, build_glibc_runtime.RUNTIME_PACKAGES)


if __name__ == "__main__":
    unittest.main()
