import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / ".github/scripts/normalize-source-timestamps.py"


class SourceTimestampsTest(unittest.TestCase):
    def test_normalized_checkout_retains_cargo_library_cache(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-source-timestamp-test-") as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "Cargo.toml").write_text(
                '[package]\nname="timestamp-demo"\nversion="0.1.0"\nedition="2021"\n',
                encoding="utf-8",
            )
            source = root / "src/lib.rs"
            source.write_text("pub fn value() -> u8 { 7 }\n", encoding="utf-8")
            git_metadata = root / ".git" / "objects"
            git_metadata.mkdir(parents=True)
            sentinel = git_metadata / "sentinel"
            sentinel.write_text("metadata", encoding="utf-8")
            prior_git_time = sentinel.stat().st_mtime_ns
            environment = os.environ.copy()
            environment["CARGO_TARGET_DIR"] = str(root / "target")

            def normalize() -> None:
                subprocess.run(["python3", str(SCRIPT), str(root), "1000000000"],
                               check=True, capture_output=True)

            normalize()
            command = ["cargo", "build", "--offline", "--release", "--message-format=json"]
            subprocess.run(command, cwd=root, env=environment, check=True, capture_output=True)
            os.utime(source, (time.time() + 10, time.time() + 10))
            normalize()
            result = subprocess.run(command, cwd=root, env=environment, check=True,
                                    capture_output=True, text=True)
            artifacts = [message for line in result.stdout.splitlines()
                         if (message := json.loads(line)).get("reason") == "compiler-artifact"]
            self.assertTrue(artifacts)
            self.assertTrue(all(message["fresh"] for message in artifacts))
            self.assertEqual(source.stat().st_mtime, 1000000000)
            self.assertEqual((root / "src").stat().st_mtime, 1000000000)
            self.assertEqual(sentinel.stat().st_mtime_ns, prior_git_time)
            shutil.rmtree(root / ".git")
            git_pointer = root / ".git"
            git_pointer.write_text("gitdir: elsewhere\n", encoding="utf-8")
            pointer_time = git_pointer.stat().st_mtime_ns
            normalize()
            self.assertEqual(git_pointer.stat().st_mtime_ns, pointer_time)
