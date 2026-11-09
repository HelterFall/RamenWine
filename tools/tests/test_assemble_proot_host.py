from __future__ import annotations

import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assemble_proot_host


def write_elf64(
    path: Path,
    machine: int,
    interpreter: str | None = None,
    executable: bool = False,
) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    ident = b"\x7fELF" + bytes((2, 1, 1, 0)) + bytes(8)
    if interpreter is None:
        data = struct.pack(
            "<16sHHIQQQIHHHHHH",
            ident, 2, machine, 1, 0, 0, 0, 0, 64, 56, 0, 0, 0, 0,
        )
    else:
        raw = interpreter.encode("ascii") + b"\x00"
        header = struct.pack(
            "<16sHHIQQQIHHHHHH",
            ident, 2, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0,
        )
        segment = struct.pack(
            "<IIQQQQQQ",
            3, 4, 120, 0, 0, len(raw), len(raw), 1,
        )
        data = header + segment + raw
    path.write_bytes(data)
    path.chmod(0o755 if executable else 0o644)
    return path


class ProotHostAssemblyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.extracted: dict[str, Path] = {
            name: self.root / "packages" / name
            for name in assemble_proot_host.EXPECTED_PACKAGES
        }
        for package_root in self.extracted.values():
            package_root.mkdir(parents=True)
        self.output = self.root / "out"

        prefix = Path("data/data/com.termux/files/usr")
        proot_prefix = self.extracted["proot"] / prefix
        write_elf64(proot_prefix / "bin/proot", 183, "/system/bin/linker64", True)
        write_elf64(proot_prefix / "libexec/proot/loader", 183, executable=True)

        talloc_prefix = self.extracted["libtalloc"] / prefix
        write_elf64(talloc_prefix / "lib/libtalloc.so.2.5.0", 183)
        (talloc_prefix / "lib/libtalloc.so.2").symlink_to("libtalloc.so.2.5.0")

        shmem_prefix = self.extracted["libandroid-shmem"] / prefix
        write_elf64(shmem_prefix / "lib/libandroid-shmem.so", 183)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_finds_only_exact_pinned_aarch64_package_versions(self) -> None:
        packages_dir = self.root / "debs"
        packages_dir.mkdir()
        metadata: dict[str, tuple[str, str, str]] = {}
        for name, version in assemble_proot_host.EXPECTED_PACKAGES.items():
            package_file = packages_dir / f"{name}.deb"
            package_file.write_bytes(f"fixture:{name}:{version}".encode("utf-8"))
            metadata[package_file.name] = (name, version, "aarch64")

        def fake_metadata(path: Path) -> tuple[str, str, str]:
            return metadata[path.name]

        with patch("assemble_proot_host.read_deb_metadata", side_effect=fake_metadata):
            found = assemble_proot_host.find_required_debs(packages_dir)

        self.assertEqual(set(assemble_proot_host.EXPECTED_PACKAGES), set(found))
        for package in found.values():
            self.assertEqual("aarch64", package.architecture)
            self.assertEqual(64, len(package.sha256))

    def test_rejects_wrong_pinned_package_version(self) -> None:
        packages_dir = self.root / "debs"
        packages_dir.mkdir()
        package_file = packages_dir / "proot.deb"
        package_file.write_bytes(b"fixture")
        with patch(
            "assemble_proot_host.read_deb_metadata",
            return_value=("proot", "5.1.107.101", "aarch64"),
        ):
            with self.assertRaisesRegex(ValueError, "version mismatch"):
                assemble_proot_host.find_required_debs(packages_dir)

    def test_stages_proot_loader_and_android_shared_libraries(self) -> None:
        host = assemble_proot_host.stage_host_payload(
            self.extracted, self.output, {"test": {"version": "fixture"}}
        )
        self.assertTrue((host / "bin/proot").is_file())
        self.assertTrue((host / "libexec/proot/loader").is_file())
        self.assertTrue((host / "lib/libandroid-shmem.so").is_file())
        self.assertEqual(
            "libtalloc.so.2.5.0",
            (host / "lib/libtalloc.so.2").readlink().as_posix(),
        )
        self.assertTrue((host / "PROVENANCE.json").is_file())
        metadata = json.loads((host / "PROVENANCE.json").read_text(encoding="utf-8"))
        self.assertEqual("android-bionic-arm64", metadata["target"])
        self.assertEqual(
            assemble_proot_host.TERMUX_PACKAGES_COMMIT,
            metadata["termuxPackagesCommit"],
        )
        self.assertEqual("30", metadata["androidNdkVersion"])
        self.assertEqual(
            "753611f410d002cfcd3f3dc2ef49aad532089d3180b436c060a90bf0fcb64df2",
            metadata["androidNdkArchiveSha256"],
        )
        self.assertEqual("9123335", metadata["androidSdkCommandLineToolsRevision"])

    def test_rejects_missing_proot_loader(self) -> None:
        loader = (
            self.extracted["proot"]
            / "data/data/com.termux/files/usr/libexec/proot/loader"
        )
        loader.unlink()
        with self.assertRaisesRegex(ValueError, "required package file is missing"):
            assemble_proot_host.stage_host_payload(self.extracted, self.output, {})

    def test_rejects_shared_library_symlink_escape(self) -> None:
        talloc_lib = (
            self.extracted["libtalloc"]
            / "data/data/com.termux/files/usr/lib"
        )
        (talloc_lib / "libtalloc.so.2").unlink()
        (talloc_lib / "libtalloc.so.2").symlink_to("../../../../../../outside.so")
        with self.assertRaisesRegex(ValueError, "escapes extracted package"):
            assemble_proot_host.stage_host_payload(self.extracted, self.output, {})

    def test_rejects_non_arm64_shared_library(self) -> None:
        shmem_lib = (
            self.extracted["libandroid-shmem"]
            / "data/data/com.termux/files/usr/lib/libandroid-shmem.so"
        )
        write_elf64(shmem_lib, 62)
        with self.assertRaisesRegex(ValueError, "wrong ELF machine"):
            assemble_proot_host.stage_host_payload(self.extracted, self.output, {})

    def test_host_dependency_failure_leaves_no_partial_output(self) -> None:
        self.output.mkdir()
        with patch.object(
            assemble_proot_host.package_runtime,
            "validate_android_proot_host_payload",
            side_effect=ValueError("unresolved Android PRoot host dependency"),
        ) as validate_host:
            with self.assertRaisesRegex(ValueError, "unresolved Android PRoot host dependency"):
                assemble_proot_host.stage_host_payload(
                    self.extracted,
                    self.output,
                    {"test": {"version": "fixture"}},
                )

        validate_host.assert_called_once()
        self.assertEqual([], list(self.output.iterdir()))
        self.assertEqual([], list(self.root.glob(".out.staging-*")))

    def test_refuses_to_overwrite_nonempty_output(self) -> None:
        self.output.mkdir()
        (self.output / "keep.txt").write_text("leave unchanged", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "must be empty"):
            assemble_proot_host.stage_host_payload(self.extracted, self.output, {})


if __name__ == "__main__":
    unittest.main()
