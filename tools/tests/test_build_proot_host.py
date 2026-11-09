from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS_DIR = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "build_proot_host",
    TOOLS_DIR / "build_proot_host.py",
)
assert spec is not None and spec.loader is not None
build_proot_host = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = build_proot_host
spec.loader.exec_module(build_proot_host)


def make_pinned_proot_source(root: Path) -> Path:
    files = {
        "src/cli/proot.h": "\\n".join(
            f'.name = "{option}"' for option in build_proot_host.REQUIRED_PROOT_CLI_OPTIONS
        ) + "\\nstatic int handle_option_kill_on_exit(Tracee *, const Cli *, const char *);\\n",
        "src/cli/proot.c": "tracee->killall_on_exit = true;\\n",
        "src/execve/enter.c": 'getenv("PROOT_LOADER");\\nPROOT_UNBUNDLE_LOADER\\n',
        "src/path/temp.c": 'getenv("PROOT_TMP_DIR");\\n',
        "packages/proot/build.sh": (
            'TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"\\n'
            'TERMUX_PKG_EXTRA_MAKE_ARGS="-C src PROOT_WITH_LIBANDROID_SHMEM=true"\\n'
            'export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot\\n'
        ),
    }
    for relative, content in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    return root


class BuildProotHostTests(unittest.TestCase):
    def test_accepts_exact_pinned_proot_cli_and_termux_recipe_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = make_pinned_proot_source(Path(temporary))
            build_proot_host.validate_pinned_proot_source_contract(source)

    def test_rejects_missing_pinned_proot_cli_option(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = make_pinned_proot_source(Path(temporary))
            header = source / "src/cli/proot.h"
            header.write_text(
                header.read_text(encoding="utf-8").replace(
                    '.name = "--kill-on-exit"\\n', ""
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "does not define.*--kill-on-exit"):
                build_proot_host.validate_pinned_proot_source_contract(source)

    def test_rejects_termux_recipe_without_android_shmem(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = make_pinned_proot_source(Path(temporary))
            recipe = source / "packages/proot/build.sh"
            recipe.write_text(
                recipe.read_text(encoding="utf-8").replace(
                    'PROOT_WITH_LIBANDROID_SHMEM=true', "PROOT_WITH_LIBANDROID_SHMEM=false"
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "required Android build setting"):
                build_proot_host.validate_pinned_proot_source_contract(source)

    def test_requires_fuse_device_before_starting_docker(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fake_fuse = Path(temporary) / "fuse"
            with (
                patch.object(build_proot_host, "FUSE_DEVICE", fake_fuse),
                patch.object(build_proot_host.shutil, "which", return_value="/usr/bin/tool"),
                patch.object(build_proot_host, "run") as run_command,
            ):
                with self.assertRaisesRegex(ValueError, "requires /dev/fuse"):
                    build_proot_host.require_tools()

            run_command.assert_not_called()

    def test_rejects_overlapping_work_and_output_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            work.mkdir()
            with self.assertRaisesRegex(ValueError, "separate, non-overlapping"):
                build_proot_host.validate_paths(work, work / "output")

    def test_rejects_non_empty_work_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            work.mkdir()
            (work / "keep.txt").write_text("do not overwrite")
            with self.assertRaisesRegex(ValueError, "must be empty"):
                build_proot_host.validate_paths(work, root / "output")

    def test_rejects_non_empty_output_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "output"
            output.mkdir()
            (output / "keep.txt").write_text("do not overwrite")
            with self.assertRaisesRegex(ValueError, "must not exist or must be empty"):
                build_proot_host.validate_paths(root / "work", output)

    def test_accepts_separate_empty_build_directories(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work, output = build_proot_host.validate_paths(
                root / "work",
                root / "artifacts" / "proot-host",
            )
            self.assertEqual(root / "work", work)
            self.assertEqual(root / "artifacts" / "proot-host", output)

    def test_build_uses_pinned_termux_revision_and_assembles_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            output = root / "output"
            commands: list[tuple[list[str], Path | None, dict[str, str] | None]] = []

            def record_run(command, cwd=None, extra_env=None):
                commands.append((command, cwd, extra_env))
                if len(command) > 1 and command[1] == str(build_proot_host.ASSEMBLER):
                    (output / "host").mkdir(parents=True, exist_ok=True)

            def record_subprocess(command, **kwargs):
                if command[:3] == ["git", "-C", str(work / "termux-packages")]:
                    return type("Completed", (), {
                        "stdout": build_proot_host.TERMUX_PACKAGES_COMMIT + "\n"
                    })()
                return type("Completed", (), {"stdout": "", "stderr": ""})()

            original_assembler = build_proot_host.ASSEMBLER
            try:
                with (
                    patch.object(build_proot_host, "require_build_host"),
                    patch.object(build_proot_host, "require_tools"),
                    patch.object(build_proot_host, "run", side_effect=record_run),
                    patch.object(build_proot_host.subprocess, "run", side_effect=record_subprocess),
                ):
                    result = build_proot_host.build(
                        type("Args", (), {"work_dir": work, "output_dir": output})()
                    )
            finally:
                build_proot_host.ASSEMBLER = original_assembler

            self.assertEqual(output / "host", result)
            self.assertTrue(any(
                command[:4] == ["git", "-C", str(work / "termux-packages"), "fetch"]
                and build_proot_host.TERMUX_PACKAGES_COMMIT in command
                for command, _, _ in commands
            ))
            self.assertTrue(any(command[:2] == ["docker", "build"] for command, _, _ in commands))
            self.assertTrue(any(
                command[0] == "bash" and "./build-package.sh" in command
                and command[-1] == "proot"
                for command, _, _ in commands
            ))
            self.assertTrue(any(
                command[1] == str(build_proot_host.ASSEMBLER)
                for command, _, _ in commands
                if len(command) > 1
            ))
            self.assertTrue(any(
                env is not None and env.get("CONTAINER_NAME", "").startswith("ramenwine-proot-host-")
                for _, _, env in commands
            ))


if __name__ == "__main__":
    unittest.main()
