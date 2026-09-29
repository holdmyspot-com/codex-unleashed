import subprocess
import sys
import unittest
from pathlib import Path


CLI = Path(__file__).resolve().parents[2] / ".github" / "scripts" / "release-cache-keys.py"
FINGERPRINT = "a" * 64


def run_cli(*args):
    return subprocess.run(
        [sys.executable, str(CLI), *args], capture_output=True, text=True, check=False
    )


def values(result):
    return dict(line.split("=", 1) for line in result.stdout.splitlines())


class ReleaseCacheKeysTests(unittest.TestCase):
    def invoke(self, target="x86_64-unknown-linux-gnu", fingerprint=FINGERPRINT,
               version="1.2.3", mode=None):
        args = ["--target", target, "--compiler-fingerprint", fingerprint,
                "--v8-version", version]
        if mode is not None:
            args += ["--mode", mode]
        return run_cli(*args)

    def test_default_off_emits_four_reusable_identities(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values(result), {
            "cargo_download_key": "codex-release-downloads-v5-x86_64-unknown-linux-gnu",
            "cargo_target_tag": f"cargo-v2-x86_64-unknown-linux-gnu-off-{FINGERPRINT}",
            "rusty_v8_key": "rusty-v8-v2-x86_64-unknown-linux-gnu-1.2.3",
            "rusty_v8_version": "1.2.3",
        })
        self.assertEqual(len(result.stdout.splitlines()), 4)

    def test_deterministic_mode_is_accepted_and_separates_compiled_cache(self):
        result = self.invoke(mode="deterministic")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values(result)["cargo_target_tag"],
                         f"cargo-v2-x86_64-unknown-linux-gnu-deterministic-{FINGERPRINT}")

    def test_invalid_mode_target_fingerprint_and_v8_version_have_no_stdout(self):
        invalid = [
            self.invoke(mode="fast"),
            self.invoke(target="bad/target"),
            self.invoke(target="single"),
            self.invoke(fingerprint=FINGERPRINT.upper()),
            self.invoke(fingerprint="a" * 63),
            self.invoke(version="../1.2"),
            self.invoke(version=""),
        ]
        for result in invalid:
            with self.subTest(stderr=result.stderr):
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stdout, "")

    def test_identity_reuse_and_isolation_across_inputs(self):
        base = values(self.invoke())
        changed_target = values(self.invoke(target="aarch64-unknown-linux-gnu"))
        changed_compiler = values(self.invoke(fingerprint="b" * 64))
        changed_mode = values(self.invoke(mode="deterministic"))
        changed_v8 = values(self.invoke(version="9.8.7"))

        for changed in (changed_compiler, changed_mode, changed_v8):
            self.assertEqual(changed["cargo_download_key"], base["cargo_download_key"])
        self.assertNotEqual(changed_target["cargo_download_key"], base["cargo_download_key"])
        self.assertNotEqual(changed_target["cargo_target_tag"], base["cargo_target_tag"])
        self.assertNotEqual(changed_compiler["cargo_target_tag"], base["cargo_target_tag"])
        self.assertNotEqual(changed_mode["cargo_target_tag"], base["cargo_target_tag"])
        self.assertNotEqual(changed_v8["rusty_v8_key"], base["rusty_v8_key"])
        self.assertEqual(changed_compiler["rusty_v8_key"], base["rusty_v8_key"])
        self.assertEqual(changed_mode["rusty_v8_key"], base["rusty_v8_key"])
        self.assertEqual(changed_target["rusty_v8_key"],
                         "rusty-v8-v2-aarch64-unknown-linux-gnu-1.2.3")


if __name__ == "__main__":
    unittest.main()
