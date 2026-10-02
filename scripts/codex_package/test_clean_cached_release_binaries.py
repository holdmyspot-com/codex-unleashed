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

    def test_changed_source_identity_rebuilds_normalized_library(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-source-identity-test-") as directory:
            root = Path(directory) / "workspace"
            root.mkdir()
            (root / "src").mkdir()
            (root / "Cargo.toml").write_text(
                '[package]\nname="source-demo"\nversion="0.1.0"\nedition="2021"\n'
                '[workspace]\nmembers=["."]\nexclude=["dependency"]\n'
                '[dependencies]\ncached-dependency={path="../dependency"}\n',
                encoding="utf-8",
            )
            dependency = Path(directory) / "dependency"
            (dependency / "src").mkdir(parents=True)
            (dependency / "Cargo.toml").write_text(
                '[package]\nname="cached-dependency"\nversion="0.1.0"\nedition="2021"\n',
                encoding="utf-8",
            )
            (dependency / "src/lib.rs").write_text("pub fn value() {}\n", encoding="utf-8")
            source = root / "src/lib.rs"
            source.write_text("pub fn value() -> u8 { 7 }\n", encoding="utf-8")
            (root / "src/main.rs").write_text(
                'fn main() { println!("{}", source_demo::value()); }\n', encoding="utf-8"
            )
            target = subprocess.check_output(["rustc", "-vV"], text=True).split("host: ")[1].splitlines()[0]
            environment = os.environ.copy()
            environment["CARGO_TARGET_DIR"] = str(root / "target")
            environment["CARGO_CACHE_SOURCE_ID"] = "upstream:patch-one"
            metadata = json.loads(subprocess.check_output(
                ["cargo", "metadata", "--no-deps", "--format-version", "1"], cwd=root, env=environment, text=True))
            self.assertEqual(len(metadata["workspace_members"]), 1, metadata["workspace_members"])
            cleanup = ["python3", str(SCRIPT), str(root), target, "source-demo"]
            subprocess.run(cleanup, env=environment, check=True, capture_output=True)
            os.utime(source, (1000000000, 1000000000))
            build = ["cargo", "build", "--offline", "--release", "--target", target,
                     "--message-format=json"]
            subprocess.run(build, cwd=root, env=environment, check=True, capture_output=True)
            source.write_text("pub fn value() -> u8 { 9 }\n", encoding="utf-8")
            os.utime(source, (1000000000, 1000000000))
            environment["CARGO_CACHE_SOURCE_ID"] = "upstream:patch-two"
            subprocess.run(cleanup, env=environment, check=True, capture_output=True)
            result = subprocess.run(build, cwd=root, env=environment, check=True,
                                    capture_output=True, text=True)
            libraries = [message for line in result.stdout.splitlines()
                         if (message := json.loads(line)).get("reason") == "compiler-artifact"
                         and message["target"]["name"] == "source_demo"]
            self.assertTrue(libraries)
            self.assertTrue(all(not message["fresh"] for message in libraries))
            dependencies = [message for line in result.stdout.splitlines()
                            if (message := json.loads(line)).get("reason") == "compiler-artifact"
                            and message["target"]["name"] == "cached_dependency"]
            self.assertTrue(dependencies)
            self.assertTrue(all(message["fresh"] for message in dependencies), result.stderr)
            binary = root / "target" / target / "release" / ("source-demo.exe" if os.name == "nt" else "source-demo")
            self.assertEqual(subprocess.check_output([str(binary)], text=True).strip(), "9")
            subprocess.run(cleanup, env=environment, check=True, capture_output=True)
            result = subprocess.run(build, cwd=root, env=environment, check=True,
                                    capture_output=True, text=True)
            libraries = [message for line in result.stdout.splitlines()
                         if (message := json.loads(line)).get("reason") == "compiler-artifact"
                         and message["target"]["name"] == "source_demo"]
            self.assertTrue(all(message["fresh"] for message in libraries))

    def test_failed_source_invalidation_keeps_previous_identity_for_retry(self) -> None:
        with tempfile.TemporaryDirectory(prefix="codex-source-identity-test-") as directory:
            root = Path(directory)
            target_directory = root / "target"
            identity = target_directory / ".codex-source-identities" / "test-target"
            identity.parent.mkdir(parents=True)
            identity.write_text("old-inputs", encoding="utf-8")
            metadata = {
                "target_directory": str(target_directory), "workspace_members": ["demo-id"],
                "packages": [{"id": "demo-id", "name": "demo", "targets": [
                    {"name": "demo", "kind": ["bin"]}, {"name": "demo", "kind": ["lib"]}]}],
            }
            cargo = root / "cargo"
            cargo.write_text(
                "#!/usr/bin/env python3\nimport json, os, sys\n"
                f"metadata = {metadata!r}\n"
                "if sys.argv[1] == 'metadata': print(json.dumps(metadata))\n"
                "elif sys.argv[1] == 'clean': sys.exit(int(os.environ['FAIL_CLEAN']))\n"
                "else: sys.exit(2)\n", encoding="utf-8",
            )
            cargo.chmod(0o755)
            environment = os.environ.copy()
            environment.update(CARGO=str(cargo), CARGO_CACHE_SOURCE_ID="new-inputs", FAIL_CLEAN="1")
            command = ["python3", str(SCRIPT), str(root), "test-target", "demo"]
            result = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(identity.read_text(encoding="utf-8"), "old-inputs")
            environment["FAIL_CLEAN"] = "0"
            subprocess.run(command, env=environment, check=True, capture_output=True)
            self.assertEqual(identity.read_text(encoding="utf-8"), "new-inputs")

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
