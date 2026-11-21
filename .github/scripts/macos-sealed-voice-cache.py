#!/usr/bin/env python3
"""Store and independently verify an exact native voice build for one macOS target."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile


HELPER = "release/codex-voice-host"
TEST_BINARIES = (
    "verification/packaged-runtime-test-bin",
    "verification/installed-package-runtime-test-bin",
)
REQUIRED = {HELPER, "release/runtime/runtime.json", *TEST_BINARIES, "evidence/release_files.json", "completed"}
DIGEST = re.compile(r"[0-9a-f]{64}")
MAX_TRANSPORT_BYTES = 128 * 1024 * 1024
FILE_MODES = {0o444, 0o555, 0o644, 0o755}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open("rb") as data:
        return hashlib.file_digest(data, "sha256").hexdigest()


def output(*command):
    return subprocess.check_output(command, text=True).strip()


def native_inputs(args):
    environment_names = ("ImageOS", "ImageVersion", "RUNNER_OS", "RUNNER_ARCH", "SOURCE_DATE_EPOCH",
                         "MACOSX_DEPLOYMENT_TARGET", "SDKROOT", "DEVELOPER_DIR", "CC", "CXX",
                         "CFLAGS", "CXXFLAGS", "RUSTFLAGS", "BAZELISK_BASE_URL", "USE_BAZEL_VERSION")
    return {
        "fork_commit": os.environ["GITHUB_SHA"],
        "target": args.target,
        "source_commit": output("git", "rev-parse", "HEAD"),
        "source_tree": output("git", "rev-parse", "HEAD^{tree}"),
        "version": args.version,
        "environment": {name: os.environ.get(name) for name in environment_names},
        "bazel_pin": Path(".bazelversion").read_text().strip(),
        "xcode": output("xcodebuild", "-version"),
        "sdk_path": output("xcrun", "--sdk", "macosx", "--show-sdk-path"),
        "sdk_version": output("xcrun", "--sdk", "macosx", "--show-sdk-version"),
        "sdk_build": output("xcrun", "--sdk", "macosx", "--show-sdk-build-version"),
        "clang": output("xcrun", "--sdk", "macosx", "clang", "--version"),
        "rust": output("rustc", "-vV"),
        "python": output("python", "--version"),
        "source_path": str(Path.cwd()),
    }


def file_record(path):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode), f"Expected regular file: {path}")
    mode = stat.S_IMODE(info.st_mode)
    require(mode in FILE_MODES, f"Unsupported mode for {path}: {mode:o}")
    return {"bytes": info.st_size, "mode": mode, "sha256": sha256(path)}


def inventory(result):
    selected = set(REQUIRED)
    runtime = result / "release/runtime"
    for path in runtime.rglob("*"):
        if not stat.S_ISDIR(path.lstat().st_mode):
            selected.add(path.relative_to(result).as_posix())
    return {name: file_record(result / name) for name in sorted(selected)}


def validate_producer_result(result, inputs):
    produced = json.loads((result / "evidence/release_files.json").read_text())
    require(produced["format_version"] == 1 and produced["source_commit"] == inputs["source_commit"] and
            produced["app_target"] == produced["voice_target"] == inputs["target"],
            "Original producer identity differs")
    files = inventory(result)
    observed = {name.removeprefix("release/"): (record["bytes"], record["sha256"])
                for name, record in files.items() if name.startswith("release/")}
    reported = {item["path"]: (item["size"], item["sha256"]) for item in produced["files"]}
    require(len(reported) == len(produced["files"]) and reported == observed,
            "Original producer inventory differs from the restored bytes")
    return files


def package_voice_fixture(args, inputs, result, evidence):
    architecture = {"aarch64-apple-darwin": "arm64", "x86_64-apple-darwin": "x86_64"}[args.target]
    dylibs = sorted((result / "release/runtime").rglob("*.dylib"))
    require(bool(dylibs), "Sealed macOS bundle has no native libraries")
    for binary in [result / name for name in (HELPER, *TEST_BINARIES)] + dylibs:
        require(output("xcrun", "lipo", "-archs", str(binary)) == architecture,
                f"Unexpected native architecture for {binary}")
    evidence.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="voice-cache-native-fixture-", dir=os.environ["RUNNER_TEMP"]) as tmp:
        package = Path(tmp) / "staging"
        (package / "bin").mkdir(parents=True)
        (package / "codex-path").mkdir()
        for name in ("codex", "codex-exec-server"):
            path = package / "bin" / name
            path.write_text("#!/bin/sh\nexit 97\n")
            path.chmod(0o755)
        (package / "codex-package.json").write_text(json.dumps({
            "layoutVersion": 1, "target": args.target, "version": args.version, "variant": "codex",
            "entrypoint": "bin/codex", "execServer": "bin/codex-exec-server",
            "resourcesDir": "codex-resources", "pathDir": "codex-path",
        }))
        common = ["--package-dir", str(package), "--upstream-root", str(Path.cwd()), "--target", args.target,
                  "--build-commit", inputs["source_commit"], "--package-version", args.version]
        command = ["bash", os.environ["CODEX_TOOLING"], "voice-runtime"]
        subprocess.run(command + ["stage", "--release-dir", str(result / "release")] + common, check=True)
        # The release archive writer normalizes executable modes. Exercise the
        # same path so the relocated test sees the permissions of shipped files.
        archive_path = Path(tmp) / "fixture.tar.gz"
        build_env = {**os.environ, "PYTHONPATH": str(Path.cwd() / "scripts"), "CODEX_REPO_ROOT": str(Path.cwd())}
        subprocess.run([sys.executable, "-m", "codex_package.archive", "write", "--package-dir", str(package),
                        "--archive-output", str(archive_path)], check=True, env=build_env)
        extracted = Path(tmp) / "extracted package"
        extracted.mkdir()
        with tarfile.open(archive_path, "r:gz") as archive:
            archive.extractall(extracted, filter="data")
        common[1] = str(extracted)
        subprocess.run(command + ["verify", "--test-binary", str(result / TEST_BINARIES[0]),
                                  "--installed-test-binary", str(result / TEST_BINARIES[1]),
                                  "--evidence-dir", str(evidence)] + common, check=True)


def producer():
    return {
        "repository": os.environ["GITHUB_REPOSITORY"], "workflow_ref": os.environ["GITHUB_WORKFLOW_REF"],
        "run_id": os.environ["GITHUB_RUN_ID"], "run_attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
    }


def save_bundle(args, inputs, result):
    manifest = {"format": 1, "producer": producer(), "inputs": inputs,
                "files": validate_producer_result(result, inputs)}
    args.cache.mkdir()
    with tarfile.open(args.cache / "voice.tar.gz", "w:gz", dereference=True) as archive:
        for name in manifest["files"]:
            archive.add(result / name, arcname=name, recursive=False)
    manifest["archive_sha256"] = sha256(args.cache / "voice.tar.gz")
    (args.cache / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def restore_bundle(args, inputs, result, approved):
    manifest_file = args.cache / "manifest.json"
    archive_file = args.cache / "voice.tar.gz"
    require(sha256(manifest_file) == approved["manifest_sha256"],
            "Voice cache manifest digest differs from the approved producer digest")
    manifest = json.loads(manifest_file.read_text())
    expected = {**producer(), "run_id": approved["run_id"], "run_attempt": approved["run_attempt"]}
    require(manifest["format"] == 1 and manifest["producer"] == expected and manifest["inputs"] == inputs,
            "Voice cache producer or native input identity differs")
    require(sha256(archive_file) == manifest["archive_sha256"], "Voice cache archive digest differs")
    files = manifest["files"]
    require(REQUIRED <= files.keys(), "Incomplete voice cache inventory")
    with tempfile.TemporaryDirectory(prefix="voice-cache-restore-", dir=result.parent) as tmp:
        staging = Path(tmp)
        seen = set()
        with tarfile.open(archive_file, "r:gz") as archive:
            for member in archive:
                name = member.name
                path = PurePosixPath(name)
                require(name in files and name not in seen and member.isfile() and
                        not path.is_absolute() and ".." not in path.parts and path.as_posix() == name and
                        (name in REQUIRED or name.startswith("release/runtime/")),
                        f"Unsupported or unexpected voice cache member {name}")
                record = files[name]
                require(member.size == record["bytes"] and member.mode in FILE_MODES and
                        member.mode == record["mode"], f"Unexpected size or mode for {name}")
                destination = staging / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, destination.open("xb") as target:
                    shutil.copyfileobj(source, target)
                destination.chmod(member.mode)
                require(file_record(destination) == record, f"Restored bytes differ for {name}")
                seen.add(name)
        require(seen == files.keys() and validate_producer_result(staging, inputs) == files,
                "Voice cache contents differ from original producer inventory")
        package_voice_fixture(args, inputs, staging, args.evidence / "native-fixture")
        report = {
            "producer": manifest["producer"], "consumer": producer(), "inputs": manifest["inputs"],
            "artifact_id": approved["artifact_id"], "artifact_sha256": approved["artifact_sha256"],
            "manifest_sha256": approved["manifest_sha256"], "archive_sha256": manifest["archive_sha256"],
        }
        (staging / "evidence/voice-cache-origin.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        os.rename(staging, result)


def get_json(endpoint):
    return json.loads(subprocess.check_output(["gh", "api", endpoint], timeout=20))


def select_artifact(name, artifacts, current, inputs, branch, repository_id):
    """Accept an immutable artifact from this exact release workflow and fork head."""
    for artifact in artifacts:
        origin = artifact.get("workflow_run") or {}
        digest = artifact.get("digest") or ""
        if (artifact.get("name") == name and not artifact.get("expired") and
            0 < artifact.get("size_in_bytes", 0) <= MAX_TRANSPORT_BYTES and
            digest.startswith("sha256:") and DIGEST.fullmatch(digest[7:]) and
            origin.get("head_repository_id") == origin.get("repository_id") == repository_id and
            origin.get("head_branch") == branch and origin.get("head_sha") == inputs["fork_commit"] and
            str(origin.get("id")) != current["run_id"]):
            origin_run = get_json(f"repos/{current['repository']}/actions/runs/{int(origin['id'])}")
            if (origin_run["path"] == ".github/workflows/upstream-auto-release.yml" and
                origin_run["head_sha"] == inputs["fork_commit"] and origin_run["head_branch"] == branch and
                origin_run["head_repository"]["full_name"] == current["repository"] and
                origin_run["event"] in ("schedule", "workflow_dispatch") and
                origin_run["status"] == "completed" and origin_run["conclusion"] == "success"):
                return artifact, origin_run
    return None


def fetch(args, inputs):
    # Preserve the voice producer's configuration before the package builder
    # sets up its potentially different compiler and archive tools.
    args.inputs.write_text(json.dumps(inputs, sort_keys=True) + "\n")
    current = producer()
    branch = os.environ["GITHUB_REF_NAME"]
    identity = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()
    name = f"sealed-voice-v1-{args.target}-{identity}"
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output_file:
        output_file.write(f"name={name}\n")
    endpoint = f"repos/{current['repository']}/actions/artifacts?per_page=20&name={name}"
    selected = select_artifact(name, get_json(endpoint)["artifacts"], current, inputs,
                               branch, int(os.environ["GITHUB_REPOSITORY_ID"]))
    if selected is None:
        return
    artifact, origin = selected
    with tempfile.TemporaryDirectory(prefix="voice-cache-fetch-", dir=args.cache.parent) as tmp:
        scratch = Path(tmp)
        archive = scratch / "artifact.zip"
        endpoint = f"repos/{current['repository']}/actions/artifacts/{int(artifact['id'])}/zip"
        with archive.open("wb") as stream:
            subprocess.run(["gh", "api", endpoint], stdout=stream, stderr=subprocess.DEVNULL, timeout=45, check=True)
        require(archive.stat().st_size == artifact["size_in_bytes"] and
                "sha256:" + sha256(archive) == artifact["digest"], "Voice artifact transport digest differs")
        with zipfile.ZipFile(archive) as bundle:
            sizes = {"manifest.json": 65536, "voice.tar.gz": MAX_TRANSPORT_BYTES}
            require(sorted(bundle.namelist()) == sorted(sizes), "Unexpected voice artifact archive entries")
            for name, limit in sizes.items():
                require(0 < bundle.getinfo(name).file_size <= limit, f"Invalid voice artifact member {name}")
                with bundle.open(name) as source, (scratch / name).open("xb") as target:
                    shutil.copyfileobj(source, target)
        archive.unlink()
        approved = {
            "run_id": str(origin["id"]), "run_attempt": origin["run_attempt"],
            "artifact_id": artifact["id"], "artifact_sha256": artifact["digest"][7:],
            "manifest_sha256": sha256(scratch / "manifest.json"),
        }
        (scratch / "approved.json").write_text(json.dumps(approved) + "\n")
        os.rename(scratch, args.cache)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("fetch", "restore", "save"))
    parser.add_argument("--target", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--inputs", type=Path)
    parser.add_argument("--result", type=Path)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.operation == "save":
        inputs = json.loads(args.inputs.read_text())
        require(inputs["fork_commit"] == os.environ["GITHUB_SHA"] and
                inputs["source_commit"] == output("git", "rev-parse", "HEAD") and
                inputs["source_tree"] == output("git", "rev-parse", "HEAD^{tree}") and
                inputs["target"] == args.target and inputs["version"] == args.version,
                "Voice producer source identity changed before saving")
    else:
        inputs = native_inputs(args)
    require(inputs["environment"]["RUNNER_OS"] == "macOS" and inputs["environment"]["ImageVersion"],
            "A sealed voice cache requires an identified native macOS image")
    if args.operation == "fetch":
        fetch(args, inputs)
    elif args.operation == "restore":
        require(not os.path.lexists(args.result), "Voice cache restore requires an empty destination")
        approved = json.loads((args.cache / "approved.json").read_text())
        restore_bundle(args, inputs, args.result, approved)
    else:
        save_bundle(args, inputs, args.result)


if __name__ == "__main__":
    main()
