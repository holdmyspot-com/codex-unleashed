#!/usr/bin/env python3
"""Exercise a bundled freshness helper against real, offline Cargo builds.

Arguments are an assembled runtime directory, a retained evidence directory and
the explicit Bash executable used to launch the packaged script.
Each invocation owns and removes its Cargo fixture; command logs and the JSON
receipt remain in the evidence directory for the caller to collect.
Optional cache transport and repository arguments also round-trip partial outputs
before retrying. The caller owns removal of the resulting registry artifact;
partial-cache-reference.txt records its reference before upload.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def verify(runtime, evidence, bash, cache_transport, cache_repository):
    """Verify reuse, content invalidation and recording refusal through the CLI."""
    runtime = runtime.resolve()
    bash = bash.resolve(strict=True)
    assert bash.is_file(), bash
    if cache_transport:
        cache_transport = cache_transport.resolve(strict=True)
        assert cache_transport.is_file(), cache_transport
    evidence = evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    launcher = runtime / "bin" / "codex-tooling"
    assert launcher.is_file(), launcher
    assert (runtime / ".complete").is_file(), runtime

    with tempfile.TemporaryDirectory(prefix="runtime-fixture-", dir=evidence) as temporary:
        fixture = Path(temporary)
        workspace = fixture / "workspace"
        (workspace / "src").mkdir(parents=True)
        (workspace / "Cargo.toml").write_text(
            '[package]\nname="cache-demo"\nversion="0.1.0"\nedition="2021"\n'
            '[workspace]\n[dependencies]\nv8={path="../v8"}\n', encoding="utf-8")
        (workspace / "src/lib.rs").write_text(
            "pub fn value()->u8 {v8::value()}\n", encoding="utf-8")
        (workspace / "src/main.rs").write_text(
            'fn main(){println!("{}",cache_demo::value());}\n', encoding="utf-8")
        v8 = fixture / "v8"
        (v8 / "src").mkdir(parents=True)
        (v8 / "Cargo.toml").write_text(
            '[package]\nname="v8"\nversion="0.0.1"\nedition="2021"\n', encoding="utf-8")
        (v8 / "src/lib.rs").write_text(
            'include!(env!("RUSTY_V8_SRC_BINDING_PATH"));\n', encoding="utf-8")
        binding = fixture / "verified-binding.rs"
        binding.write_text("pub fn value()->u8 {7}\n", encoding="utf-8")
        for name in ["tmp", "cargo", "target"]:
            (fixture / name).mkdir()
        environment = os.environ.copy()
        environment.update({
            "CARGO_HOME": str(fixture / "cargo"),
            "CARGO_TARGET_DIR": str(fixture / "target"),
            "CARGO_NET_OFFLINE": "true",
            "TMPDIR": str(fixture / "tmp"),
            "TMP": str(fixture / "tmp"),
            "TEMP": str(fixture / "tmp"),
            "RUNNER_TEMP": str(fixture / "tmp"),
            "GIT_CEILING_DIRECTORIES": str(fixture),
            "RUSTY_V8_SRC_BINDING_PATH": str(binding),
        })

        def command(arguments, label, expected=0):
            result = subprocess.run(arguments, cwd=workspace, env=environment,
                                    capture_output=True, text=True)
            (evidence / (label + ".log")).write_text(
                result.stdout + result.stderr, encoding="utf-8")
            assert result.returncode == expected, (
                label, result.returncode, result.stdout, result.stderr)
            return result

        command(["git", "init", "--quiet"], "git-init")
        command(["git", "add", "Cargo.toml", "src"], "git-add")
        rust = command(["rustc", "-vV"], "rust-identity").stdout
        target = rust.split("host: ", 1)[1].splitlines()[0]
        cli = [str(bash), launcher.as_posix(), "clean-cached-release-binaries"]
        command([str(bash), "--version"], "bash-identity")
        command(cli + ["missing-workspace", "../outside", "codex"], "invalid-target", 2)

        def prepare(label):
            command(cli + [str(workspace), target, "cache-demo"], label)

        def record(label, expected=0):
            return command(cli + ["--record-source-inputs", str(workspace), target], label, expected)

        def build(label, expected=0):
            result = command(["cargo", "build", "--release", "--target", target,
                              "--message-format=json"], label, expected)
            rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
            return {row["target"]["name"]: row["fresh"] for row in rows
                    if row.get("reason") == "compiler-artifact" and row["target"]["kind"] == ["lib"]}

        main_source = workspace / "src/main.rs"
        valid_main = main_source.read_text(encoding="utf-8")
        main_source.write_text('compile_error!("controlled executable failure");\nfn main() {}\n',
                               encoding="utf-8")
        prepare("cold-prepare")
        cold = build("cold-failed-build", 101)
        assert cold == {"v8": False, "cache_demo": False}, cold
        partial_reference = None
        if cache_transport:
            identity = hashlib.sha256(("runtime-partial-fixture:" + str(fixture)).encode()).hexdigest()
            tag = "cargo-v3-" + target + "-" + identity
            partial_reference = cache_repository + ":" + tag + "-partial"
            (evidence / "partial-cache-reference.txt").write_text(
                partial_reference + "\n", encoding="utf-8")
            transfer = [str(bash), cache_transport.as_posix()]
            transfer_inputs = [cache_repository, tag, str(fixture / "target"), "rust-v0.164.0"]
            command(transfer + ["push-partial"] + transfer_inputs, "partial-cache-upload")
            shutil.rmtree(fixture / "target")
            (fixture / "target").mkdir()
            command(transfer + ["pull"] + transfer_inputs, "verified-cache-absent", 3)
            command(transfer + ["pull-partial"] + transfer_inputs, "partial-cache-download")

        main_source.write_text(valid_main, encoding="utf-8")
        before_retry = binding.stat().st_mtime_ns
        os.utime(binding, ns=(before_retry + 100_000_000_000, before_retry + 100_000_000_000))
        prepare("partial-retry-prepare")
        partial = build("partial-retry-build")
        assert partial == {"v8": True, "cache_demo": True}, partial
        assert binding.stat().st_mtime_ns == before_retry
        assert not (fixture / "target/.codex-source-inputs" / (target + ".json")).exists()
        record("cold-record")
        original = binding.stat().st_mtime_ns
        for file in [binding, workspace / "src/lib.rs"]:
            timestamp = file.stat().st_mtime_ns + 100_000_000_000
            os.utime(file, ns=(timestamp, timestamp))
        prepare("warm-prepare")
        warm = build("warm-build")
        assert warm == {"v8": True, "cache_demo": True}, warm
        assert binding.stat().st_mtime_ns == original
        record("warm-record")

        binding.write_text("pub fn value()->u8 {9}\n", encoding="utf-8")
        os.utime(binding, ns=(original, original))
        prepare("changed-prepare")
        changed = build("changed-build")
        assert changed == {"v8": False, "cache_demo": False}, changed
        executable = "cache-demo.exe" if os.name == "nt" else "cache-demo"
        output = command([str(fixture / "target" / target / "release" / executable)],
                         "changed-output").stdout.strip()
        assert output == "9", output
        record("changed-record")
        prepare("drift-prepare")
        build("drift-build")
        binding.write_text("pub fn value()->u8 {11}\n", encoding="utf-8")
        failure = record("drift-record", 1)
        assert "refusing cache snapshot" in failure.stderr

        receipt = {"target": target, "runtime": str(runtime), "rust": rust,
                   "cold_library_fresh": cold, "partial_retry_library_fresh": partial,
                   "warm_library_fresh": warm,
                   "changed_library_fresh": changed, "changed_executable_output": output,
                   "partial_cache_reference": partial_reference,
                   "invalid_target_exit": 2, "content_drift_record_exit": 1}
    receipt["fixture_removed"] = not fixture.exists()
    assert receipt["fixture_removed"]
    (evidence / "runtime-verification.json").write_text(
        json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, indent=2))


def main():
    """Parse paths and run the packaged-runtime acceptance fixture."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("bash", type=Path)
    parser.add_argument("--cache-transport", type=Path)
    parser.add_argument("--cache-repository")
    arguments = parser.parse_args()
    if (arguments.cache_transport is None) != (arguments.cache_repository is None):
        parser.error("--cache-transport and --cache-repository must be supplied together")
    verify(arguments.runtime, arguments.evidence, arguments.bash,
           arguments.cache_transport, arguments.cache_repository)


if __name__ == "__main__":
    main()
