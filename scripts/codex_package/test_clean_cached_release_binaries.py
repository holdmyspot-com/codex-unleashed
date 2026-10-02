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
                '[package]\nname = "shared-library"\nversion = "0.1.0"\nedition = "2021"\n'
                '[[bin]]\nname = "shared_library"\npath = "src/other.rs"\n',
                encoding="utf-8",
            )
            (shared / "src" / "lib.rs").write_text("pub fn value() -> u8 { 7 }\n", encoding="utf-8")
            (shared / "src" / "main.rs").write_text(
                'fn main() { println!("{}", shared_library::value()); }\n', encoding="utf-8"
            )
            (shared / "src" / "other.rs").write_text("fn main() {}\n", encoding="utf-8")
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
                ["cargo", "build", "--offline", "--release", "--target", target, "--workspace"],
                cwd=root,
                env=environment,
                check=True,
                capture_output=True,
            )
            release_dir = root / "target" / target / "release"
            executable_suffix = ".exe" if os.name == "nt" else ""
            binary = release_dir / ("release-demo" + executable_suffix)
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

            # The helper package also owns a library used by another binary.
            helper = release_dir / ("shared-library" + executable_suffix)
            self.assertTrue(helper.exists())
            markers = sorted(str(path.relative_to(release_dir))
                             for path in (release_dir / ".fingerprint").glob("*/bin-*"))
            subprocess.run(
                ["python3", str(SCRIPT), str(root), target, "shared-library"],
                env=environment, check=True, capture_output=True,
            )
            self.assertFalse(helper.exists())
            self.assertTrue(all(path.exists() for path in dependency_artifacts),
                            f"A requested binary's library must survive cleanup; markers: {markers}")

            rebuilt = subprocess.run(
                ["cargo", "build", "--offline", "--release", "--target", target,
                 "--workspace", "--message-format=json"],
                cwd=root, env=environment, check=True, capture_output=True, text=True,
            )
            messages = [json.loads(line) for line in rebuilt.stdout.splitlines()]
            artifacts = [message for message in messages
                         if message.get("reason") == "compiler-artifact"]
            library = [artifact for artifact in artifacts if artifact["target"]["kind"] == ["lib"]]
            binaries = [artifact for artifact in artifacts if artifact["target"]["kind"] == ["bin"]]
            self.assertTrue(library)
            self.assertTrue(all(artifact["fresh"] for artifact in library))
            freshness = {artifact["target"]["name"]: artifact["fresh"] for artifact in binaries}
            self.assertEqual(freshness, {"release-demo": False, "shared-library": False,
                                         "shared_library": True})

    def test_rejects_target_outside_cache_directory(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-cargo-clean-test-") as directory:
            root = Path(directory)
            (root / "src").mkdir()
            (root / "Cargo.toml").write_text(
                '[package]\nname = "demo"\nversion = "0.1.0"\nedition = "2021"\n',
                encoding="utf-8",
            )
            (root / "src" / "lib.rs").write_text("pub fn value() {}\n", encoding="utf-8")
            (root / "src" / "main.rs").write_text("fn main() {}\n", encoding="utf-8")
            outside = root / "outside" / "release"
            marker = outside / ".fingerprint" / "demo-unit" / "bin-demo"
            marker.parent.mkdir(parents=True)
            marker.write_text("unrelated cache", encoding="utf-8")
            binary = outside / "demo"
            binary.write_text("unrelated binary", encoding="utf-8")
            environment = os.environ.copy()
            environment["CARGO_TARGET_DIR"] = str(root / "target")

            for target in ("../outside", "../outside.json"):
                with self.subTest(target=target):
                    result = subprocess.run(
                        ["python3", str(SCRIPT), str(root), target, "demo"],
                        env=environment, capture_output=True, text=True,
                    )

                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("Invalid Cargo target directory name", result.stderr)
                    self.assertEqual(marker.read_text(encoding="utf-8"), "unrelated cache")
                    self.assertEqual(binary.read_text(encoding="utf-8"), "unrelated binary")

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
