"""Exercise package output bundle validation through its CLI."""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest

TAG = "cargo-v3-x86_64-unknown-linux-musl-pkg-" + "1" * 64
OCI_DIGEST = "sha256:" + "2" * 64
TRANSPORT = Path(__file__).with_name("package-final-output-cache.sh")
REFERENCE = os.environ.get("PACKAGE_FINAL_OUTPUT_PYTHON_REFERENCE")


class FinalOutputBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        launcher = os.environ.get("CODEX_TOOLING")
        if not launcher or not Path(launcher).is_file():
            raise RuntimeError("Set CODEX_TOOLING to the generated tooling launcher")
        cls.launcher = str(Path(launcher).resolve())
        cls.java = ["bash", cls.launcher, "package-final-output-bundle"]

    def fixture_env(self):
        return dict(os.environ, CODEX_TOOLING=self.launcher, CACHE_TAG=TAG,
                    TARGET="x86_64-unknown-linux-musl", GITHUB_REPOSITORY="example/repo",
                    GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1", PACKAGE_FINAL_SOURCE="3" * 40)

    def test_transport_miss_saved_hit_and_corrupt_blob(self):
        target = "x86_64-unknown-linux-musl"
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            repo = base / "source"
            repo.mkdir()
            subprocess.run(["git", "init", "-q", repo], check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com",
                            "commit", "-qm", "fixture", "--allow-empty"], cwd=repo, check=True)
            source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
            operations = repo / ".github/scripts"
            operations.mkdir(parents=True)
            (operations / "codex-package-operations.sh").write_text("printf 'codex\\n'\n")
            metadata = base / "manifest-patch-repo/.github"
            (metadata / "scripts").mkdir(parents=True)
            (metadata / "package-final-output-cache-package").write_text("cache\n")
            result = base / "result"
            result.mkdir()
            for name, value in {
                "completed": "true", "format-version": "1", "operation": "build_binaries",
                "build-mode": "release", "target": target, "runner-os": "Linux",
                "source-commit": source, "binaries": "codex",
            }.items():
                (result / name).write_text(value + "\n")
            cargo = base / "cargo"
            release = cargo / target / "release"
            release.mkdir(parents=True)
            for name in ("codex", "bwrap"):
                shutil.copy2(shutil.which("true") or "/usr/bin/true", release / name)
            fake_bin = base / "bin"
            fake_bin.mkdir()
            oras = fake_bin / "oras"
            oras.write_text('''#!/usr/bin/env python3
import hashlib, json, os
from pathlib import Path
import shutil, sys

args = sys.argv[1:]
registry = Path(os.environ["FAKE_REGISTRY"])
blob = registry / "bundle"
manifest = registry / "manifest"

def digest(path):
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()

if args[0] == "login":
    assert sys.stdin.read() == "fixture-token"
    Path(args[args.index("--registry-config") + 1]).write_text("{}")
else:
    assert "GHCR_TOKEN" not in os.environ
    if args[0] == "push":
        reference, archive = args[-2:]
        exact = "cargo-v3-x86_64-unknown-linux-musl-pkg-final-v1-" + "1" * 64
        release = "cargo-release-" + hashlib.sha256(exact.encode()).hexdigest() + "-rust-v0.167.0"
        assert reference == "ghcr.io/example/cache:" + release + "," + exact, reference
        name, media = archive.split(":")
        shutil.copyfile(name, blob)
        manifest.write_text(json.dumps({
            "schemaVersion": 2, "artifactType": args[args.index("--artifact-type") + 1],
            "layers": [{"mediaType": media, "digest": digest(blob), "size": blob.stat().st_size}],
        }))
        pushed_digest = digest(manifest)
        print(json.dumps({"digest": pushed_digest, "reference": "ghcr.io/example/cache@" + pushed_digest}))
    elif args[0] == "resolve":
        if not manifest.exists():
            sys.exit("MANIFEST_UNKNOWN")
        print(digest(manifest))
    else:
        content = manifest if args[0] == "manifest" else blob
        shutil.copyfile(content, args[args.index("--output") + 1])
''')
            oras.chmod(0o755)
            registry = base / "registry"
            registry.mkdir()
            output = base / "output"
            env = dict(self.fixture_env(), RUNNER_OS="Linux",
                       RUNNER_TEMP=str(base), GITHUB_REPOSITORY_OWNER="example",
                       GITHUB_ACTOR="tester", GITHUB_OUTPUT=str(output),
                       CARGO_TARGET_DIR=str(cargo), CODEX_PACKAGE_BUILD_RESULT=str(result),
                       UPSTREAM_TAG="rust-v0.167.0", FAKE_REGISTRY=str(registry), PATH=str(fake_bin) + ":" + os.environ["PATH"])

            def run(operation):
                output.write_text("")
                token = {} if operation == "capture" else {"GHCR_TOKEN": "fixture-token"}
                completed = subprocess.run(["bash", TRANSPORT.resolve(), operation], cwd=repo,
                                           env=env | token, capture_output=True, text=True)
                self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
                return completed.stdout, dict(line.split("=", 1) for line in output.read_text().splitlines())

            self.assertEqual(run("restore")[1], {"result": "miss"})
            self.assertIn("final_output_cache_result=captured", run("capture")[0])
            (release / "codex").write_bytes(b"stripped fixture")
            saved, outputs = run("publish")
            self.assertEqual(outputs, {"result": "saved"})
            restored, outputs = run("restore")
            self.assertEqual(outputs["result"], "hit")
            self.assertIn("final_output_cache_producer_run_id=123 producer_run_attempt=1", restored)
            self.assertEqual(saved.split("digest=")[1].split()[0], restored.split("digest=")[1].split()[0])
            self.assertTrue(Path(outputs["path"]).is_relative_to(base))
            self.assertTrue((Path(outputs["path"]) / "bin/codex").read_bytes().startswith(b"\x7fELF"))
            self.assertEqual((release / "codex").read_bytes(), b"stripped fixture")
            blob = registry / "bundle"
            blob.write_bytes(b"x" + blob.read_bytes()[1:])
            self.assertEqual(run("restore")[1], {"result": "unavailable"})

    def test_roundtrip_and_invalid_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            release = base / "release"
            release.mkdir()
            for name in ("bwrap", "codex"):
                shutil.copy2(shutil.which("true") or "/usr/bin/true", release / name)
            env = self.fixture_env()
            archive = base / "final-outputs.tar.gz"
            target = base / "restored"

            def run(operation, *args):
                return subprocess.run([*self.java, operation, archive, *args],
                                      env=env, capture_output=True, text=True)

            completed = run("capture", release, "bwrap", "codex")
            self.assertEqual(completed.returncode, 0, completed.stderr)
            completed = run("restore", target, OCI_DIGEST, "bwrap", "codex")
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual((target / "bin/codex").read_bytes(), (release / "codex").read_bytes())
            self.assertIn(OCI_DIGEST, (target / "provenance.json").read_text())
            installed = base / "installed"
            install = [
                *self.java, "install", "--restored-dir", target,
                "--release-dir", installed, "--dependency-tag", TAG,
                "--source-commit", "3" * 40, "--binaries", "bwrap", "codex",
            ]
            completed = subprocess.run(install, env=env, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual((installed / "codex").read_bytes(), (release / "codex").read_bytes())
            self.assertNotEqual(subprocess.run(install, env=env, capture_output=True).returncode, 0)
            (target / "bin/codex").write_bytes(b"corrupted")
            shutil.rmtree(installed)
            self.assertNotEqual(subprocess.run(install, env=env, capture_output=True).returncode, 0)
            self.assertFalse(installed.exists())
            self.assertNotEqual(run("restore", base / "wrong-key", OCI_DIGEST, "bwrap", "codex",
                                    "extra").returncode, 0)

            bad = base / "bad.tar.gz"
            with tarfile.open(archive, "r:gz") as original, tarfile.open(bad, "w:gz") as modified:
                for member in original:
                    modified.addfile(member, original.extractfile(member))
                rogue = tarfile.TarInfo("bin/../../outside")
                rogue.type = tarfile.SYMTYPE
                rogue.linkname = "/usr/bin/true"
                modified.addfile(rogue)
            bad.replace(archive)
            self.assertNotEqual(run("restore", base / "invalid", OCI_DIGEST, "bwrap", "codex").returncode, 0)
            self.assertFalse((base / "invalid").exists())
            self.assertFalse((base / "outside").exists())

    @unittest.skipUnless(REFERENCE, "Set PACKAGE_FINAL_OUTPUT_PYTHON_REFERENCE to the frozen e1ece66 script")
    def test_java_and_python_exchange_bundle_and_provenance(self):
        frozen_python = [sys.executable, str(Path(REFERENCE).resolve())]
        env = self.fixture_env()
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            release = base / "release"
            release.mkdir()
            shutil.copy2(shutil.which("true") or "/usr/bin/true", release / "bwrap")
            shutil.copy2(shutil.which("false") or "/usr/bin/false", release / "codex")
            (release / "bwrap").chmod(0o750)
            (release / "codex").chmod(0o755)
            names = ["bwrap", "codex"]

            def run(command, *arguments):
                completed = subprocess.run([*command, *arguments], env=env, capture_output=True, text=True)
                self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)

            bundle_contents = []
            provenance = []
            for label, writer, reader in (
                ("java", self.java, frozen_python),
                ("python", frozen_python, self.java),
            ):
                with self.subTest(writer=label):
                    archive = base / f"{label}.tar.gz"
                    restored = base / f"{label}-restored"
                    installed = base / f"{label}-installed"
                    run(writer, "capture", archive, release, *names)
                    with tarfile.open(archive, "r:gz") as tar:
                        bundle_contents.append({
                            member.name: (member.type, member.mode, tar.extractfile(member).read())
                            for member in tar
                        })
                    run(reader, "restore", archive, restored, OCI_DIGEST, *names)
                    provenance.append(json.loads((restored / "provenance.json").read_text()))
                    run(writer, "install", "--restored-dir", restored, "--release-dir", installed,
                        "--dependency-tag", TAG, "--source-commit", env["PACKAGE_FINAL_SOURCE"], "--binaries", *names)
                    for name in names:
                        self.assertEqual((installed / name).read_bytes(), (release / name).read_bytes())
                        self.assertEqual(stat.S_IMODE((installed / name).stat().st_mode),
                                         stat.S_IMODE((release / name).stat().st_mode))

            def semantic_bundle(contents):
                return {
                    name: (kind, mode, json.loads(data) if name == "manifest.json" else data)
                    for name, (kind, mode, data) in contents.items()
                }

            self.assertEqual(semantic_bundle(bundle_contents[0]), semantic_bundle(bundle_contents[1]))
            self.assertEqual(provenance[0], provenance[1])


if __name__ == "__main__":
    unittest.main()
