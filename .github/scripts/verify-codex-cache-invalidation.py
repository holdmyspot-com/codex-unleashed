#!/usr/bin/env python3
"""Check CLI environment-input invalidation after a successful cached release build.

Run from pristine codex-rs with TARGET, CARGO_TARGET_DIR and RUNNER_TEMP set.
Requires warm codex outputs and the Linux bwrap helper. Builds with a changed
CODEX_BUILD_COMMIT, checks the running CLI's doctor report, then repeats the warm
build. Cargo must reuse every non-CLI compilation. Reports remain under
baseline-evidence; changed
outputs must never be published to a shared cache. Any failed check exits nonzero
and can leave diagnostic outputs in the owned Cargo directory.
"""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    """Run the three build comparisons and retain their artifacts and doctor report."""
    target = os.environ["TARGET"]
    output = Path(os.environ["CARGO_TARGET_DIR"]) / target / "release"
    helper_digest = hashlib.sha256((output / "bwrap").read_bytes()).hexdigest()
    evidence = Path(os.environ["RUNNER_TEMP"]) / "baseline-evidence" / "cache-invalidation"
    evidence.mkdir(parents=True)
    cli_manifest = (Path.cwd() / "cli" / "Cargo.toml").resolve()
    environment = os.environ.copy()
    environment["CODEX_BWRAP_SHA256"] = helper_digest
    environment["STABLE_GIT_COMMIT"] = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True
    ).strip()
    original_commit = environment.get("CODEX_BUILD_COMMIT")
    marker = "cache-invalidation-" + environment["STABLE_GIT_COMMIT"]
    if original_commit == marker:
        raise RuntimeError("CODEX_BUILD_COMMIT already equals the diagnostic value")

    def build(label, commit):
        """Build codex with the supplied compile-time commit value and return rebuilt artifact messages.

        A None commit removes CODEX_BUILD_COMMIT. Retain Cargo JSON messages and
        stderr under label in the evidence directory. Require exactly one codex
        artifact and no rebuilt artifact outside the CLI package; raise RuntimeError
        when either check fails. Nonzero Cargo status raises CalledProcessError."""
        build_environment = environment.copy()
        if commit is None:
            build_environment.pop("CODEX_BUILD_COMMIT", None)
        else:
            build_environment["CODEX_BUILD_COMMIT"] = commit
        command = ["cargo", "build", "--target", target, "--release", "--timings",
                   "--bin", "codex", "--message-format=json-render-diagnostics"]
        print(f"Checking {label}", flush=True)
        with (evidence / f"{label}.jsonl").open("w") as stdout:
            with (evidence / f"{label}.log").open("w") as stderr:
                subprocess.run(command, env=build_environment, stdout=stdout,
                               stderr=stderr, check=True)
        messages = [json.loads(line) for line in (evidence / f"{label}.jsonl").read_text().splitlines()]
        artifacts = [message for message in messages if message["reason"] == "compiler-artifact"]
        binaries = [artifact for artifact in artifacts if artifact["target"]["name"] == "codex"]
        if len(binaries) != 1:
            raise RuntimeError(f"{label}: expected one codex artifact, found {len(binaries)}")
        rebuilt = [artifact for artifact in artifacts if artifact["fresh"] is False]
        dependencies = [artifact["target"]["name"] for artifact in rebuilt
                        if Path(artifact["manifest_path"]).resolve() != cli_manifest]
        if dependencies:
            raise RuntimeError(f"{label}: non-CLI compilations were rebuilt: {dependencies}")
        return rebuilt

    def doctor(label):
        """Run the built CLI doctor command with an isolated temporary CODEX_HOME.

        Retain JSON and stderr under label, remove the temporary home and return
        the runtime.provenance commit prefixed with "commit: ". Accept doctor status
        0 or 1; raise RuntimeError for another status. Report parsing, missing-field
        and process-launch failures to the caller."""
        with tempfile.TemporaryDirectory(prefix="doctor-home-", dir=evidence) as home:
            doctor_environment = environment.copy()
            doctor_environment["CODEX_HOME"] = home
            with (evidence / f"{label}-doctor.json").open("w") as stdout:
                with (evidence / f"{label}-doctor.log").open("w") as stderr:
                    result = subprocess.run([str(output / "codex"), "doctor", "--json"],
                                            env=doctor_environment, stdout=stdout, stderr=stderr)
        if result.returncode not in (0, 1):
            raise RuntimeError(f"{label}: doctor exited {result.returncode}; inspect its retained log")
        report = json.loads((evidence / f"{label}-doctor.json").read_text())
        provenance = report["checks"]["runtime.provenance"]
        return "commit: " + provenance["details"]["commit"]

    if build("original-warm", original_commit):
        raise RuntimeError("Original inputs are not warm; inspect original-warm artifacts before testing invalidation")
    if len(build("changed", marker)) == 0:
        raise RuntimeError("Changing CODEX_BUILD_COMMIT did not rebuild the CLI")
    if doctor("changed") != "commit: " + marker:
        raise RuntimeError("The running changed CLI does not report the diagnostic commit")
    if build("changed-warm", marker):
        raise RuntimeError("Repeating the changed input rebuilt CLI artifacts")
    receipt = {"target": target, "original_build_commit": original_commit,
               "changed_provenance": "commit: " + marker, "non_cli_rebuilds": 0,
               "changed_warm_rebuilds": 0, "status": "passed"}
    (evidence / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()
