#!/usr/bin/env python3
"""Exercise the invalidation check with real Cargo and an isolated diagnostic CLI.

The fixture models only the compile-time commit and doctor JSON contract inspected
by the checker; hosted Codex execution remains a separate required check.
"""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class CacheInvalidationCheckTest(unittest.TestCase):
    """Check acceptance of fresh output and rejection of incorrect runtime provenance."""

    def run_fixture(self, reports_compile_time_commit):
        """Run the checker against real Cargo; select correct or deliberately stale provenance."""
        checker = Path(__file__).with_name("verify-codex-cache-invalidation.py").resolve()
        with tempfile.TemporaryDirectory(prefix="invalidation-check-", dir=os.environ["TMPDIR"]) as directory:
            root = Path(directory)
            workspace = root / "workspace"
            source = workspace / "cli" / "src"
            source.mkdir(parents=True)
            dependency = workspace / "dependency"
            (dependency / "src").mkdir(parents=True)
            (workspace / "Cargo.toml").write_text('[workspace]\nmembers = ["cli", "dependency"]\nresolver = "2"\n')
            (source.parent / "Cargo.toml").write_text(
                '[package]\nname = "codex-cli"\nversion = "0.1.0"\nedition = "2021"\n'
                '[[bin]]\nname = "codex"\npath = "src/main.rs"\n'
                '[dependencies]\ncache-dependency = { path = "../dependency" }\n'
            )
            (dependency / "Cargo.toml").write_text(
                '[package]\nname = "cache-dependency"\nversion = "0.1.0"\nedition = "2021"\n'
            )
            (dependency / "src" / "lib.rs").write_text('pub fn prefix() -> &\'static str { "commit: " }\n')
            value = 'option_env!("CODEX_BUILD_COMMIT").unwrap_or("unknown")'
            if reports_compile_time_commit is False:
                value = '"unknown"'
            (source / "main.rs").write_text(
                'const _: Option<&str> = option_env!("CODEX_BUILD_COMMIT");\n'
                'fn main() { println!("{{\\"checks\\":[{{\\"id\\":\\"runtime.provenance\\",'
                '\\"details\\":[\\"{}{}\\"]}}]}}", cache_dependency::prefix(), ' + value + '); }\n'
            )
            for command in (["git", "init", "--quiet"],
                            ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                             "commit", "--quiet", "--allow-empty", "-m", "Fixture"]):
                subprocess.run(command, cwd=workspace, check=True, capture_output=True)
            host = subprocess.check_output(["rustc", "-vV"], text=True)
            target = next(line.removeprefix("host: ") for line in host.splitlines() if line.startswith("host: "))
            environment = os.environ.copy()
            environment.pop("CODEX_BUILD_COMMIT", None)
            environment.pop("RUSTY_V8_SRC_BINDING_PATH", None)
            environment["TARGET"] = target
            environment["CARGO_TARGET_DIR"] = str(root / "target")
            environment["RUNNER_TEMP"] = str(root / "runner")
            subprocess.run(["cargo", "build", "--offline", "--release", "--target", target, "--bin", "codex"],
                           cwd=workspace, env=environment, check=True, capture_output=True)
            (root / "target" / target / "release" / "bwrap").write_bytes(b"fixture helper digest input")
            result = subprocess.run(["python3", str(checker)], cwd=workspace, env=environment,
                                    capture_output=True, text=True)
            evidence = root / "runner" / "baseline-evidence" / "cache-invalidation"
            if reports_compile_time_commit:
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                receipt = json.loads((evidence / "verification.json").read_text())
                self.assertIsNone(receipt["original_build_commit"])
                self.assertEqual(receipt["status"], "passed")
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("does not report the diagnostic commit", result.stderr)
                self.assertFalse((evidence / "verification.json").exists())

    def test_changed_input_has_correct_runtime_value_and_reuses_dependencies(self):
        """Accept changed CLI output with warm dependencies and unchanged-input reuse."""
        self.run_fixture(True)

    def test_rebuilt_cli_with_stale_runtime_value_is_rejected(self):
        """Reject a CLI that recompiles but reports the old value after the input changes."""
        self.run_fixture(False)


if __name__ == "__main__":
    unittest.main()
