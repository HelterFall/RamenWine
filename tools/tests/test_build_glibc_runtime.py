from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

import package_runtime

spec = importlib.util.spec_from_file_location(
    "build_glibc_runtime",
    TOOLS_DIR / "build_glibc_runtime.py",
)
assert spec is not None and spec.loader is not None
build_glibc_runtime = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = build_glibc_runtime
spec.loader.exec_module(build_glibc_runtime)


class BuildGlibcRuntimeTests(unittest.TestCase):
    def _make_proot_host_with_provenance(self, root: Path) -> Path:
        host = root / "proot-host"
        for relative, content in (
            ("bin/proot", b"pinned-proot"),
            ("libexec/proot/loader", b"pinned-loader"),
            ("lib/libtalloc.so.2", b"talloc"),
            ("lib/libandroid-shmem.so", b"android-shmem"),
        ):
            path = host / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

        files = {
            "proot": "host/bin/proot",
            "loader": "host/libexec/proot/loader",
            "libraries": [
                "host/lib/libandroid-shmem.so",
                "host/lib/libtalloc.so.2",
            ],
        }
        staged_hashes = {
            "bin/proot": build_glibc_runtime.sha256_file(host / "bin" / "proot"),
            "libexec/proot/loader": build_glibc_runtime.sha256_file(
                host / "libexec" / "proot" / "loader"
            ),
            "lib/libandroid-shmem.so": build_glibc_runtime.sha256_file(
                host / "lib" / "libandroid-shmem.so"
            ),
            "lib/libtalloc.so.2": build_glibc_runtime.sha256_file(
                host / "lib" / "libtalloc.so.2"
            ),
        }
        assembler = build_glibc_runtime.assemble_proot_host
        provenance = {
            "formatVersion": 1,
            "target": "android-bionic-arm64",
            "termuxPackagesCommit": assembler.TERMUX_PACKAGES_COMMIT,
            "androidNdkVersion": assembler.TERMUX_NDK_VERSION,
            "androidNdkArchiveSha256": assembler.TERMUX_NDK_ARCHIVE_SHA256,
            "androidSdkCommandLineToolsRevision": assembler.TERMUX_SDK_COMMANDLINE_TOOLS_REVISION,
            "androidSdkCommandLineToolsSha256": assembler.TERMUX_SDK_COMMANDLINE_TOOLS_SHA256,
            "androidBuildToolsVersion": assembler.TERMUX_ANDROID_BUILD_TOOLS_VERSION,
            "packageSources": {
                name: {
                    "version": version,
                    "architecture": "aarch64",
                    "debSha256": "a" * 64,
                    "sourceCommit": assembler.TERMUX_PACKAGES_COMMIT,
                }
                for name, version in assembler.EXPECTED_PACKAGES.items()
            },
            "files": files,
            "stagedSha256": staged_hashes,
        }
        (host / "PROVENANCE.json").write_text(
            json.dumps(provenance, indent=2) + "\n",
            encoding="utf-8",
        )
        return host

    def test_accepts_exact_pinned_proot_host_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            host = self._make_proot_host_with_provenance(Path(temporary))
            build_glibc_runtime.validate_proot_host_provenance(host)

    def test_rejects_proot_host_from_different_termux_revision(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            host = self._make_proot_host_with_provenance(Path(temporary))
            manifest_path = host / "PROVENANCE.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["termuxPackagesCommit"] = "0" * 40
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "termuxPackagesCommit"):
                build_glibc_runtime.validate_proot_host_provenance(host)

    def test_rejects_symlinked_proot_host_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            host = self._make_proot_host_with_provenance(root)
            manifest = host / "PROVENANCE.json"
            external_manifest = root / "external-provenance.json"
            external_manifest.write_bytes(manifest.read_bytes())
            manifest.unlink()
            manifest.symlink_to(external_manifest)

            with self.assertRaisesRegex(ValueError, "must be a regular"):
                build_glibc_runtime.validate_proot_host_provenance(host)

    def test_rejects_modified_proot_host_binary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            host = self._make_proot_host_with_provenance(Path(temporary))
            with (host / "bin" / "proot").open("ab") as output:
                output.write(b"tampered")

            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                build_glibc_runtime.validate_proot_host_provenance(host)

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
            host = self._make_proot_host_with_provenance(root)
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
                    build_glibc_runtime,
                    "validate_proot_host_provenance",
                    wraps=build_glibc_runtime.validate_proot_host_provenance,
                ) as validate_provenance,
                patch.object(
                    build_glibc_runtime.package_runtime,
                    "validate_android_proot_host_payload",
                    side_effect=ValueError("unresolved Android PRoot host dependency"),
                ) as validate_host,
                patch.object(build_glibc_runtime, "install_host_packages") as install_host,
            ):
                with self.assertRaisesRegex(ValueError, "unresolved Android PRoot host dependency"):
                    build_glibc_runtime.build(args)

            validate_provenance.assert_called_once_with(host.resolve())
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

    def test_builds_and_records_both_wayland_probe_architectures(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            rootfs = root / "rootfs"
            rootfs.mkdir()
            work = root / "work"
            work.mkdir()
            commands: list[list[str]] = []

            def fake_run(command: list[str], **kwargs: object) -> None:
                commands.append(command)
                output = Path(command[command.index("-o") + 1])
                machine = (
                    package_runtime.PE_MACHINE_AMD64
                    if command[0] == "x86_64-w64-mingw32-gcc"
                    else package_runtime.PE_MACHINE_I386
                )
                output.parent.mkdir(parents=True, exist_ok=True)
                data = bytearray(0x86)
                data[:2] = b"MZ"
                data[0x3C:0x40] = (0x80).to_bytes(4, "little")
                data[0x80:0x84] = b"PE\\x00\\x00"
                data[0x84:0x86] = machine.to_bytes(2, "little")
                output.write_bytes(data)

            with patch.object(build_glibc_runtime, "run", side_effect=fake_run):
                result = build_glibc_runtime.build_wayland_probe(rootfs, work)

            self.assertEqual(
                ["x86_64-w64-mingw32-gcc", "i686-w64-mingw32-gcc"],
                [command[0] for command in commands],
            )
            self.assertTrue(all("-mwindows" in command for command in commands))
            self.assertEqual(
                ["x86_64", "i386"],
                [str(item["architecture"]) for item in result["binaries"]],
            )
            self.assertTrue(
                (rootfs / "usr/lib/ramenwine/probes/wayland-probe-x86_64.exe").is_file()
            )
            self.assertTrue(
                (rootfs / "usr/lib/ramenwine/probes/wayland-probe-i386.exe").is_file()
            )
            self.assertEqual(
                build_glibc_runtime.sha256_file(
                    Path(__file__).resolve().parents[1] / "wayland_probe.c"
                ),
                result["sourceSha256"],
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
        for package in (
            "libwayland-client0:arm64",
            "libwayland-cursor0:arm64",
            "libwayland-egl1:arm64",
            "libxkbcommon0:arm64",
            "libxkbregistry0:arm64",
        ):
            self.assertIn(package, build_glibc_runtime.RUNTIME_PACKAGES)
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
