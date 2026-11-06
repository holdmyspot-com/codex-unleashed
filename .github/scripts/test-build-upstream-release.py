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
    def run_build(self, target, fail_binary="", fail_checkpoint="", packages=None,
                  fail_packages=False):
        """Run the release script with isolated Cargo and checkpoint fixture processes.

        target selects platform-specific binaries. fail_binary and fail_checkpoint
        select fixture operations that exit 37 and 43 respectively; empty strings
        disable injected failures. packages, when not None, sets RELEASE_PACKAGES. fail_packages
        makes a package build leave an empty and a nonempty library in deps and exit 37. Return the process result and
        ordered build/checkpoint events, keep the package library listing, if any, in self.package_libraries, and remove
        the owned fixture after reading them."""
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
binary = arguments[arguments.index('--bin') + 1] if '--bin' in arguments else None
with open(os.environ['EVENTS'], 'a') as log:
    log.write(json.dumps(['build', arguments]) + '\\n')
if binary == os.environ['FAIL_BINARY']:
    sys.exit(37)
if os.environ['FAIL_PACKAGES'] and '-p' in arguments:
    deps = pathlib.Path(os.environ['CARGO_TARGET_DIR']) / os.environ['TARGET'] / 'release' / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    (deps / 'libcodex_features-1.rmeta').write_bytes(b'')
    (deps / 'libcodex_features-1.rlib').write_bytes(b'12345')
    (deps / 'codex_features-1.d').write_bytes(b'ignored')
    sys.exit(37)
if binary == 'bwrap':
    destination = pathlib.Path(os.environ['CARGO_TARGET_DIR']) / os.environ['TARGET'] / 'release' / binary
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile('/bin/true', destination)
    os.utime(destination, ns=(1700000000123456789, 1700000000123456789))
""")
            cargo.chmod(0o755)
            rustc = programs / "rustc"
            rustc.write_text("#!/usr/bin/env python3\nprint('rustc release-stage fixture')\n")
            rustc.chmod(0o755)
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
                               FAIL_PACKAGES="1" if fail_packages else "",
                               RELEASE_CHECKPOINT_COMMAND=checkpoint.as_posix())
            environment.pop("RELEASE_PACKAGES", None)
            if packages is not None:
                environment["RELEASE_PACKAGES"] = packages
            script = Path(__file__).with_name("build-upstream-release.sh")
            result = subprocess.run([shutil.which("bash"), script.as_posix()], env=environment, capture_output=True, text=True)
            if packages is None:
                self.assertTrue(events.exists(), result.stdout + result.stderr)
            recorded = [json.loads(line) for line in events.read_text().splitlines()] if events.exists() else []
            listing = root / "baseline-evidence" / "package-libraries.txt"
            self.package_libraries = listing.read_text() if listing.exists() else None
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

    def test_package_selection_builds_only_those_crates_once_verbosely_without_checkpoints(self):
        """
        A package run makes one verbose Cargo build of exactly the listed crates and saves no release stage;
        verbose output records each failing compiler command line.
        """
        result, events = self.run_build("x86_64-apple-darwin",
                                        packages="tikv-jemalloc-sys  aws-lc-sys@0.30.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = ["build", "--target", "x86_64-apple-darwin", "--release", "--timings", "--verbose",
                     "-p", "tikv-jemalloc-sys", "-p", "aws-lc-sys@0.30.0"]
        self.assertEqual(events, [["build", arguments]])

    def test_failed_package_build_records_library_sizes(self):
        """
        A failed package build keeps Cargo's status and records each library in deps with its size, so an empty
        library that a dependent could not load shows in the evidence.
        """
        result, events = self.run_build("x86_64-apple-darwin", packages="codex-config",
                                        fail_packages=True)
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertIsNotNone(self.package_libraries, result.stderr)
        rows = [line.split(" ", 2) for line in self.package_libraries.splitlines()]
        self.assertEqual([(size, Path(path).name) for size, _, path in rows],
                         [("5", "libcodex_features-1.rlib"), ("0", "libcodex_features-1.rmeta")])
        for _, modified, _ in rows:
            self.assertRegex(modified, r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{9}Z$")

    def test_successful_package_build_records_no_library_listing(self):
        """A package build that succeeds writes no library listing."""
        result, events = self.run_build("x86_64-apple-darwin", packages="codex-config")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNone(self.package_libraries)

    def test_package_selection_rejects_option_or_shell_text(self):
        """A package entry that is not a plain package specification fails with status 2 before any compilation."""
        for packages in ("--release", "a;b", "$(id)", " "):
            with self.subTest(packages=packages):
                result, events = self.run_build("x86_64-apple-darwin", packages=packages)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertEqual(events, [])

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
