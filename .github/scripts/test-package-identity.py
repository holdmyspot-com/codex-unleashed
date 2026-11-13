#!/usr/bin/env python3
"""Exercise source and compiled-package identity checks with controlled CLI results."""

from contextlib import redirect_stderr
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("package-identity.py")


def load():
    """Import the package identity operation selected by SCRIPT."""
    spec = importlib.util.spec_from_file_location("package_identity", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PackageIdentityTest(unittest.TestCase):
    """Identity checks interpret source metadata and CLI responses before writing proof."""

    def setUp(self):
        self.module = load()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.version = "0.167.0"
        self.build = "17"
        self.banner = "codex-cli 0.167.0+17\n"
        self.info = ("Codex Unleashed build information\n\nCodex CLI version: 0.167.0+17\n"
                     "Build number:      17\nUpstream version:  0.167.0\nProvided by:       Codex Unleashed\n")

    def source(self, version):
        """Create an isolated source manifest with the supplied workspace version."""
        path = self.root / "codex-rs" / "Cargo.toml"
        path.parent.mkdir()
        path.write_text(f'[workspace.package]\nversion = "{version}"\n', encoding="utf-8")
        return path

    def package(self, entrypoint="bin/codex", layout_version=1):
        """Create metadata matching upstream layout.py; the entrypoint is never executed in unit tests."""
        metadata = dict(layoutVersion=layout_version, version="0.167.0+17", target="x86_64-unknown-linux-musl",
                        variant="codex", entrypoint=entrypoint, execServer="bin/codex-exec-server",
                        resourcesDir="codex-resources", pathDir="codex-path")
        (self.root / "codex-package.json").write_text(json.dumps(metadata), encoding="utf-8")
        executable = self.root / entrypoint
        if entrypoint in ("bin/codex", "bin/codex.exe"):
            executable.parent.mkdir(exist_ok=True)
            executable.write_text("unit fixture, not executable", encoding="utf-8")
        return executable

    def run_binary(self, banner=None, info=None, failed=None):
        """Supply documented process responses and return the production request and identity decision."""
        calls = []
        def invoke(executable, flag):
            calls.append((executable, flag))
            output = (self.banner if banner is None else banner) if flag == "--version" else (
                self.info if info is None else info)
            return subprocess.CompletedProcess([str(executable), flag], 1 if flag == failed else 0, output, "")
        result = self.module.verify_binary(self.root, self.version, self.build, invoke)
        return result, calls

    def test_source_version_matches_without_rewrite(self):
        manifest = self.source(self.version)
        before = manifest.read_bytes()
        self.module.verify_source(self.root, self.version, self.build)
        self.assertEqual(manifest.read_bytes(), before)

    def test_source_version_mismatch_fails_without_rewrite(self):
        manifest = self.source("0.0.0")
        before = manifest.read_bytes()
        with self.assertRaisesRegex(ValueError, "workspace.package.version"):
            self.module.verify_source(self.root, self.version, self.build)
        self.assertEqual(manifest.read_bytes(), before)

    def test_build_must_be_ascii_decimal_digits(self):
        self.source(self.version)
        for build in ("", "-1", "+1", "1.0", "1\n", "١"):
            with self.subTest(build=build), self.assertRaisesRegex(ValueError, "build"):
                self.module.verify_source(self.root, self.version, build)

    def test_binary_uses_metadata_entrypoint_and_returns_original_banner(self):
        executable = self.package("bin/codex.exe")
        banner, calls = self.run_binary()
        self.assertEqual(banner, self.banner)
        self.assertEqual(calls, [(executable, "--version"), (executable, "--build-info")])

    def test_banner_is_exact_with_at_most_one_final_newline(self):
        self.package()
        for banner in (self.banner.rstrip("\n"), self.banner, self.banner.replace("\n", "\r\n")):
            with self.subTest(banner=banner):
                self.assertEqual(self.run_binary(banner=banner)[0], banner)
        for banner in ("prefix " + self.banner, self.banner + "\n", self.banner.replace("+17", "+18"),
                       self.banner.rstrip("\n") + " ", "codex-cli 0.167.0\n"):
            with self.subTest(banner=banner), self.assertRaisesRegex(ValueError, "--version"):
                self.run_binary(banner=banner)

    def test_build_info_fields_match_with_alignment_whitespace(self):
        self.package()
        self.assertEqual(self.run_binary(info=self.info.replace(":      ", ":\t"))[0], self.banner)
        for old, new in (("Codex CLI version: 0.167.0+17", "Codex CLI version: 0.167.0+18"),
                         ("Build number:      17", "Build number:      18"),
                         ("Upstream version:  0.167.0", "Upstream version:  0.0.0"),
                         ("Provided by:       Codex Unleashed", "Provided by:       OpenAI")):
            with self.subTest(field=old), self.assertRaisesRegex(ValueError, "--build-info"):
                self.run_binary(info=self.info.replace(old, new))

    def test_missing_or_duplicate_required_build_field_fails(self):
        self.package()
        for info in (self.info.replace("Build number:      17\n", ""), self.info + "Build number: 17\n"):
            with self.subTest(info=info), self.assertRaisesRegex(ValueError, "--build-info"):
                self.run_binary(info=info)

    def test_failed_cli_process_fails_validation(self):
        self.package()
        for flag in ("--version", "--build-info"):
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, "exit code 1"):
                self.run_binary(failed=flag)

    def test_invalid_layout_or_entrypoint_fails_before_execution(self):
        for layout, entrypoint in ((2, "bin/codex"), (1.0, "bin/codex"), (True, "bin/codex"),
                                   (1, "../codex"), (1, "/bin/codex"), (1, "bin/codex-app-server")):
            with self.subTest(layout=layout, entrypoint=entrypoint):
                self.package(entrypoint, layout)
                with self.assertRaises(ValueError):
                    self.module.verify_binary(self.root, self.version, self.build,
                                              lambda *args: self.fail("invalid metadata invoked a process"))

    def test_source_command_runs_the_source_version_check(self):
        self.source("0.0.0")
        with redirect_stderr(io.StringIO()):
            result = self.module.main(["source", "--source-root", str(self.root), "--version", self.version,
                                       "--build", self.build])
        self.assertEqual(result, 1)

    @unittest.skipIf(sys.platform == "win32", "Python shebang fixture exercises POSIX executable wiring")
    def test_command_invokes_the_packaged_entrypoint_across_a_real_process_boundary(self):
        """A Python stand-in records actual entrypoint invocations; native Codex remains a remote check."""
        executable = self.package()
        trace = self.root / "invocations.txt"
        executable.write_text("#!" + sys.executable + "\n" +
                              "import pathlib, sys\n" +
                              f"with pathlib.Path({str(trace)!r}).open('a') as trace: trace.write(sys.argv[1] + '\\n')\n" +
                              f"sys.stdout.write({self.banner!r} if sys.argv[1] == '--version' else {self.info!r})\n",
                              encoding="utf-8")
        executable.chmod(0o755)
        output = self.root / "proof.txt"
        result = subprocess.run([sys.executable, "-I", str(SCRIPT), "binary", "--package-dir", str(self.root),
                                 "--version", self.version, "--build", self.build, "--output", str(output)],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(trace.read_text(encoding="utf-8").splitlines(), ["--version", "--build-info"])
        self.assertTrue(output.is_file())

    @unittest.skipIf(sys.platform == "win32", "Python shebang fixture exercises POSIX executable wiring")
    def test_process_capture_preserves_raw_newline_forms(self):
        """Capturing process stdout must retain a bare CR so exact-banner validation can reject it."""
        executable = self.package()
        executable.write_text("#!" + sys.executable + "\nimport sys\nsys.stdout.buffer.write(b'codex-cli 0.167.0+17\\r')\n",
                              encoding="utf-8")
        executable.chmod(0o755)
        result = self.module.run_cli(executable, "--version")
        self.assertEqual(result.stdout, "codex-cli 0.167.0+17\r")

    def test_command_writes_proof_only_after_all_validation(self):
        self.package()
        output = self.root / "version.txt"
        output.write_text("existing proof", encoding="utf-8")
        def failed(executable, flag):
            return subprocess.CompletedProcess([], 1, "", "failed")
        with redirect_stderr(io.StringIO()):
            result = self.module.main(["binary", "--package-dir", str(self.root), "--version", self.version,
                                       "--build", self.build, "--output", str(output)], invoke=failed)
        self.assertEqual(result, 1)
        self.assertEqual(output.read_text(encoding="utf-8"), "existing proof")
        def success(executable, flag):
            return subprocess.CompletedProcess([], 0, self.banner if flag == "--version" else self.info, "")
        self.assertEqual(self.module.main(["binary", "--package-dir", str(self.root), "--version", self.version,
                                          "--build", self.build, "--output", str(output)], invoke=success), 0)
        self.assertEqual(output.read_bytes(), self.banner.encode())


if __name__ == "__main__":
    unittest.main()
