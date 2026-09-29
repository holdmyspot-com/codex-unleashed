import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / ".github" / "scripts" / "clean-cached-release-binaries.py"


class CleanCachedReleaseBinariesTest(unittest.TestCase):
    def test_real_cargo_clean_preserves_dependency_artifact(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-cargo-clean-test-") as directory:
            root = Path(directory)
            shared = root / "shared"
            release = root / "release"
            (shared / "src").mkdir(parents=True)
            (release / "src").mkdir(parents=True)
            (root / "Cargo.toml").write_text(
                '[workspace]\nmembers = ["shared", "release"]\nresolver = "2"\n',
                encoding="utf-8",
            )
            (shared / "Cargo.toml").write_text(
                '[package]\nname = "shared-library"\nversion = "0.1.0"\nedition = "2021"\n',
                encoding="utf-8",
            )
            (shared / "src" / "lib.rs").write_text("pub fn value() -> u8 { 7 }\n", encoding="utf-8")
            (release / "Cargo.toml").write_text(
                '[package]\nname = "release-demo"\nversion = "0.1.0"\nedition = "2021"\n'
                '[dependencies]\nshared-library = { path = "../shared" }\n',
                encoding="utf-8",
            )
            (release / "src" / "main.rs").write_text(
                'fn main() { println!("{}", shared_library::value()); }\n',
                encoding="utf-8",
            )
            target = subprocess.check_output(["rustc", "-vV"], text=True).split("host: ", 1)[1].splitlines()[0]
            environment = os.environ.copy()
            environment["CARGO_TARGET_DIR"] = str(root / "target")
            subprocess.run(
                ["cargo", "build", "--offline", "--release", "--target", target, "-p", "release-demo"],
                cwd=root,
                env=environment,
                check=True,
                capture_output=True,
            )
            release_dir = root / "target" / target / "release"
            binary = release_dir / "release-demo"
            dependency_artifacts = list((release_dir / "deps").glob("libshared_library-*.rlib"))
            self.assertTrue(binary.exists())
            self.assertTrue(dependency_artifacts)

            subprocess.run(
                ["python3", str(SCRIPT), str(root), target, "release-demo"],
                env=environment,
                check=True,
                capture_output=True,
            )

            self.assertFalse(binary.exists())
            self.assertTrue(all(path.exists() for path in dependency_artifacts))

    def test_cleans_packages_owning_requested_binaries(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-cargo-clean-test-") as directory:
            root = Path(directory)
            workspace = root / "workspace"
            workspace.mkdir()
            metadata = {
                "packages": [
                    {"name": "codex-cli", "targets": [{"name": "codex", "kind": ["bin"]}]},
                    {"name": "codex-responses-api-proxy", "targets": [{"name": "codex-responses-api-proxy", "kind": ["bin"]}]},
                    {"name": "shared-dependency", "targets": [{"name": "shared-dependency", "kind": ["lib"]}]},
                ]
            }
            cargo = root / "cargo"
            cargo.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                f"metadata = {metadata!r}\n"
                "if sys.argv[1] == 'metadata': print(json.dumps(metadata))\n"
                "elif sys.argv[1] == 'clean':\n"
                "    with open(os.environ['CLEAN_LOG'], 'a') as output: output.write(json.dumps(sys.argv[2:]) + '\\n')\n"
                "else: sys.exit(2)\n",
                encoding="utf-8",
            )
            cargo.chmod(0o755)
            log = root / "clean.jsonl"
            environment = os.environ.copy()
            environment.update(CARGO=str(cargo), CLEAN_LOG=str(log))

            result = subprocess.run(
                ["python3", str(SCRIPT), str(workspace), "aarch64-apple-darwin", "codex", "codex-responses-api-proxy"],
                env=environment,
                capture_output=True,
                text=True,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            args = json.loads(log.read_text(encoding="utf-8"))
            self.assertEqual(args[:4], ["--release", "--target", "aarch64-apple-darwin", "--manifest-path"])
            self.assertEqual(args[4], str(workspace / "Cargo.toml"))
            self.assertEqual(args[5:], ["-p", "codex-cli", "-p", "codex-responses-api-proxy"])

    def test_rejects_binary_absent_from_workspace(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-cargo-clean-test-") as directory:
            root = Path(directory)
            workspace = root / "workspace"
            workspace.mkdir()
            cargo = root / "cargo"
            cargo.write_text(
                "#!/usr/bin/env python3\n"
                "import json, sys\n"
                "if sys.argv[1] == 'metadata': print(json.dumps({'packages': []}))\n"
                "else: raise AssertionError('unexpected clean')\n",
                encoding="utf-8",
            )
            cargo.chmod(0o755)
            environment = os.environ.copy()
            environment["CARGO"] = str(cargo)

            result = subprocess.run(
                ["python3", str(SCRIPT), str(workspace), "aarch64-apple-darwin", "missing"],
                env=environment,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("missing", result.stderr)


if __name__ == "__main__":
    unittest.main()
