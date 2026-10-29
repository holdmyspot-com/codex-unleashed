#!/usr/bin/env python3
"""Exercise release stage ordering, checkpointing and failure propagation in Bash."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ReleaseStagesTest(unittest.TestCase):
    """Check platform-specific release command ordering, checkpoint failures and reuse of real Cargo outputs."""
    def run_build(self, target, fail_binary="", fail_checkpoint=""):
        """Run the release script with isolated Cargo and checkpoint fixture processes.

        target selects platform-specific binaries. fail_binary and fail_checkpoint
        select fixture operations that exit 37 and 43 respectively; empty strings
        disable injected failures. Return the process result and ordered build/
        checkpoint events, and remove the owned fixture after reading its events."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            programs = root / "bin"
            programs.mkdir()
            events = root / "events.jsonl"
            cargo = programs / "cargo"
            cargo.write_text("""#!/usr/bin/env python3
import json, os, pathlib, shutil, sys
arguments = sys.argv[1:]
if arguments == ['--version']:
    print('cargo fixture')
    sys.exit(0)
binary = arguments[arguments.index('--bin') + 1]
with open(os.environ['EVENTS'], 'a') as log:
    log.write(json.dumps(['build', arguments]) + '\\n')
if binary == os.environ['FAIL_BINARY']:
    sys.exit(37)
if binary == 'bwrap':
    destination = pathlib.Path(os.environ['CARGO_TARGET_DIR']) / os.environ['TARGET'] / 'release' / binary
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile('/bin/true', destination)
    os.utime(destination, ns=(1700000000123456789, 1700000000123456789))
""")
            cargo.chmod(0o755)
            checkpoint = root / "checkpoint"
            checkpoint.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
with open(os.environ['EVENTS'], 'a') as log:
    log.write(json.dumps(['checkpoint', sys.argv[1]]) + '\\n')
if sys.argv[1] == 'bwrap':
    executable = pathlib.Path(os.environ['CARGO_TARGET_DIR']) / os.environ['TARGET'] / 'release' / 'bwrap'
    assert executable.stat().st_mtime_ns == 1700000000123456789, 'stripping refreshed the cached executable timestamp'
if sys.argv[1] == os.environ['FAIL_CHECKPOINT']:
    sys.exit(43)
""")
            checkpoint.chmod(0o755)
            environment = dict(os.environ, PATH=str(programs) + os.pathsep + os.environ["PATH"],
                               TARGET=target, CARGO_TARGET_DIR=str(root / "target"),
                               RUNNER_TEMP=str(root), EVENTS=str(events),
                               FAIL_BINARY=fail_binary, FAIL_CHECKPOINT=fail_checkpoint,
                               RELEASE_CHECKPOINT_COMMAND=checkpoint.as_posix())
            script = Path(__file__).with_name("build-upstream-release.sh")
            result = subprocess.run([shutil.which("bash"), script.as_posix()], env=environment, capture_output=True, text=True)
            self.assertTrue(events.exists(), result.stdout + result.stderr)
            recorded = [json.loads(line) for line in events.read_text().splitlines()]
            return result, recorded

    def test_each_platform_checkpoints_each_executable_once(self):
        """The release script builds each selected platform binary with default features and checkpoints it once, in order."""
        common = ["codex-responses-api-proxy", "codex-code-mode-host", "codex", "codex-app-server"]
        cases = {
            "x86_64-apple-darwin": common,
            "x86_64-pc-windows-msvc": common + ["codex-windows-sandbox-setup", "codex-windows-sandbox-service", "codex-command-runner"]
        }
        if sys.platform == "linux":
            cases["x86_64-unknown-linux-musl"] = ["bwrap"] + common
        for target, binaries in cases.items():
            with self.subTest(target=target):
                result, events = self.run_build(target)
                self.assertEqual(result.returncode, 0, result.stderr)
                expected = []
                for binary in binaries:
                    expected.extend([["build", ["build", "--target", target, "--release", "--timings", "--bin", binary]],
                                     ["checkpoint", binary]])
                self.assertEqual(events, expected)

    def test_compiler_failure_keeps_earlier_checkpoints_and_stops(self):
        """A Cargo fixture failure preserves earlier checkpoint events, propagates status 37 and stops later release stages."""
        result, events = self.run_build("x86_64-apple-darwin", fail_binary="codex")
        self.assertEqual(result.returncode, 37)
        self.assertEqual([event[1] for event in events if event[0] == "checkpoint"],
                         ["codex-responses-api-proxy", "codex-code-mode-host"])
        self.assertEqual(len(events), 5)

    def test_checkpoint_failure_stops_before_next_build(self):
        """A checkpoint fixture failure propagates status 43 before the next release build starts."""
        result, events = self.run_build("x86_64-apple-darwin", fail_checkpoint="codex-code-mode-host")
        self.assertEqual(result.returncode, 43)
        self.assertEqual(len(events), 4)

    def test_real_cargo_reuses_bundle_outputs_and_runs_each_binary(self):
        """After bundled Cargo builds, the release script performs no further compilation and every fixture binary prints 7."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shared = root / "shared"
            (shared / "src").mkdir(parents=True)
            (shared / "Cargo.toml").write_text(
                '[package]\nname="shared"\nversion="0.1.0"\nedition="2021"\n'
                '[features]\na=[]\nb=[]\n')
            (shared / "src/lib.rs").write_text('pub fn value()->u8 {7}\n')
            binaries = {"cli": "codex", "host": "codex-code-mode-host",
                        "proxy": "codex-responses-api-proxy", "server": "codex-app-server"}
            if sys.platform == "win32":
                binaries.update({"setup": "codex-windows-sandbox-setup", "service": "codex-windows-sandbox-service",
                                 "runner": "codex-command-runner"})
            (root / "Cargo.toml").write_text(
                '[workspace]\nresolver="2"\nmembers=' + json.dumps(["shared", *binaries]) + '\n')
            for package, binary in binaries.items():
                member = root / package
                (member / "src").mkdir(parents=True)
                feature = "a" if package == "cli" else "b"
                (member / "Cargo.toml").write_text(
                    f'[package]\nname="{package}"\nversion="0.1.0"\nedition="2021"\n'
                    f'[[bin]]\nname="{binary}"\npath="src/main.rs"\n'
                    f'[dependencies]\nshared={{path="../shared", features=["{feature}"]}}\n')
                (member / "src/main.rs").write_text('fn main(){println!("{}",shared::value());}\n')
            environment = dict(os.environ, CARGO_NET_OFFLINE="true", CARGO_TARGET_DIR=str(root / "target"),
                               RUNNER_TEMP=str(root), GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                               GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")
            environment.pop("RELEASE_CHECKPOINT_COMMAND", None)

            def command(arguments):
                """Run a command in the fixture workspace and environment, assert status zero, and return its captured text output."""
                result = subprocess.run(arguments, cwd=root, env=environment, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                return result

            command(["git", "init", "--quiet"])
            command(["git", "add", "Cargo.toml", "shared", *binaries])
            command(["git", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "Cargo feature fixture"])
            identity = command(["rustc", "-vV"]).stdout
            environment["TARGET"] = identity.split("host: ", 1)[1].splitlines()[0]
            build = ["cargo", "build", "--release", "--target", environment["TARGET"]]
            command(build + ["--bin", "codex", "--bin", "codex-code-mode-host", "--bin", "codex-responses-api-proxy"])
            command(build + ["--bin", "codex-app-server", "--bin", "codex-code-mode-host"])
            if sys.platform == "win32":
                command(build + ["--bin", "codex-windows-sandbox-setup", "--bin", "codex-windows-sandbox-service",
                                 "--bin", "codex-command-runner"])
            script = Path(__file__).with_name("build-upstream-release.sh").resolve()
            staged = command([shutil.which("bash"), script.as_posix()])
            self.assertNotIn("Compiling ", staged.stdout + staged.stderr)
            suffix = ".exe" if sys.platform == "win32" else ""
            for binary in binaries.values():
                executable = root / "target" / environment["TARGET"] / "release" / (binary + suffix)
                self.assertEqual(command([str(executable)]).stdout.strip(), "7")


if __name__ == "__main__":
    unittest.main()
