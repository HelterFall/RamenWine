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
    "build_full_runtime",
    TOOLS_DIR / "build_full_runtime.py",
)
assert spec is not None and spec.loader is not None
build_full_runtime = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = build_full_runtime
spec.loader.exec_module(build_full_runtime)


class BuildFullRuntimeTests(unittest.TestCase):
    def test_rejects_work_directory_with_existing_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            work.mkdir()
            (work / "keep.txt").write_text("keep")
            with self.assertRaisesRegex(ValueError, "must be empty"):
                build_full_runtime.validate_paths(work, root / "runtime.zip")

    def test_rejects_existing_runtime_zip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "runtime.zip"
            output.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "already exists"):
                build_full_runtime.validate_paths(root / "work", output)

    def test_rejects_runtime_zip_inside_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = root / "work"
            work.mkdir()
            with self.assertRaisesRegex(ValueError, "separate and non-overlapping"):
                build_full_runtime.validate_paths(work, work / "runtime.zip")

    def test_accepts_empty_workspace_and_new_external_zip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work, output = build_full_runtime.validate_paths(
                root / "work",
                root / "artifacts" / "runtime.zip",
            )
            self.assertEqual(root.resolve() / "work", work)
            self.assertEqual(root.resolve() / "artifacts" / "runtime.zip", output)

    def test_runs_both_stages_in_order_and_forwards_build_options(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            work = (root / "work").resolve()
            output = (root / "artifacts" / "runtime.zip").resolve()
            commands: list[list[str]] = []

            def fake_run(command: list[str]) -> None:
                commands.append(command)
                if command[1] == str(build_full_runtime.PROOT_BUILDER):
                    (work / "proot-artifact" / "host").mkdir(parents=True)
                elif command[1] == str(build_full_runtime.GLIBC_BUILDER):
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(b"runtime zip fixture")

            args = type("Args", (), {
                "work_dir": work,
                "output": output,
                "snapshot": "20261109T000000Z",
                "version": "test-runtime",
                "jobs": 3,
                "no_arm_dynarec": True,
                "skip_host_dependencies": True,
            })()

            with (
                patch.object(build_full_runtime.build_proot_host, "require_build_host"),
                patch.object(build_full_runtime.build_proot_host, "require_tools"),
                patch.object(build_full_runtime, "run", side_effect=fake_run),
            ):
                result = build_full_runtime.build(args)

            self.assertEqual(output.resolve(), result)
            self.assertEqual(2, len(commands))
            self.assertEqual(str(build_full_runtime.PROOT_BUILDER), commands[0][1])
            self.assertEqual(str(build_full_runtime.GLIBC_BUILDER), commands[1][1])
            self.assertIn(str(work / "proot-artifact" / "host"), commands[1])
            self.assertIn("--snapshot", commands[1])
            self.assertIn("20261109T000000Z", commands[1])
            self.assertIn("--version", commands[1])
            self.assertIn("test-runtime", commands[1])
            self.assertIn("--jobs", commands[1])
            self.assertIn("3", commands[1])
            self.assertIn("--no-arm-dynarec", commands[1])
            self.assertIn("--skip-host-dependencies", commands[1])


if __name__ == "__main__":
    unittest.main()
