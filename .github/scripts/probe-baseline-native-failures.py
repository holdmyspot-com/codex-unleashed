"""Record controlled native diagnostics while restoring every temporary source edit."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys

source = Path(sys.argv[1]).resolve()
evidence = Path(sys.argv[2]).resolve()
evidence.mkdir(parents=True, exist_ok=True)
exec_source = source / "codex-rs/exec-server/src/shell_snapshot.rs"
package_source = source / "codex-rs/cli/tests/app_server_daemon/package.rs"
originals = {path: path.read_bytes() for path in (exec_source, package_source)}
exec_filter = (
    "test(shell_snapshot_concurrent_replays_keep_independent_readers::blocked_descriptor_path)"
    " | test(shell_snapshot_v2_capture_failure_falls_back_and_retries::remote_pipe_recovery)"
    " | test(shell_snapshot_v2_capture_failure_falls_back_and_retries::remote_tty_recovery)"
    " | test(shell_snapshot_v2_filters_profile_exports_and_stays_in_memory::remote_sandbox)"
)
results = {}


def run_phase(name, expression):
    command = [
        "cargo-nextest",
        "nextest",
        "run",
        "--workspace",
        "--target",
        "x86_64-unknown-linux-gnu",
        "--cargo-profile",
        "ci-test",
        "--no-fail-fast",
        "--success-output",
        "immediate",
        "-E",
        expression,
    ]
    with (evidence / f"{name}.log").open("wb") as log:
        result = subprocess.run(
            command, cwd=source / "codex-rs", stdout=log, stderr=subprocess.STDOUT
        )
    results[name] = {"exit_code": result.returncode, "command": command}
    (evidence / "phases.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"{name}: exit {result.returncode}", flush=True)
    if result.returncode not in (0, 100):
        raise RuntimeError(
            f"{name} did not reach a test outcome: exit {result.returncode}"
        )
    return result.returncode


try:
    run_phase(
        "original-five",
        exec_filter + " | test(packaged_daemon_bootstrap_seeds_local_package)",
    )

    text = originals[exec_source].decode()
    needle = "            telemetry.shell_snapshot_captured("
    assert text.count(needle) == 1
    text = text.replace(
        needle,
        """            eprintln!(
                "BASELINE_SNAPSHOT_CAPTURE reason={:?} file_source={:?}",
                result.as_ref().err().map(|(reason, _)| reason),
                result.as_ref().ok().map(|snapshot| snapshot.file_source),
            );
"""
        + needle,
    )
    needle = "        if params.argv.len() < 3"
    assert text.count(needle) == 1
    text = text.replace(
        needle,
        """        eprintln!(
            "BASELINE_SNAPSHOT_ELIGIBILITY argc={} shell_matches={} flag_matches={} command_suffix_matches={}",
            params.argv.len(),
            params.argv.first() == Some(&request.shell.path),
            params.argv.get(1).map(String::as_str) == Some("-lc"),
            prepared.command.ends_with(&params.argv),
        );
"""
        + needle,
    )
    exec_source.write_text(text)
    run_phase("snapshot-diagnostics", exec_filter)
    exec_source.write_bytes(originals[exec_source])

    text = originals[package_source].decode()
    needle = '"version": env!("CARGO_PKG_VERSION"), "target": target, "entrypoint": "bin/codex"'
    assert text.count(needle) == 1
    package_source.write_text(
        text.replace(
            needle, '"version": "0.0.0", "target": target, "entrypoint": "bin/codex"'
        )
    )
    run_phase(
        "development-package-counterfactual",
        "binary_id(codex-cli::app_server_daemon_package)",
    )
finally:
    for path, original in originals.items():
        path.write_bytes(original)
        assert path.read_bytes() == original
    (evidence / "source-restored.json").write_text(
        json.dumps(
            {
                str(path.relative_to(source)): hashlib.sha256(data).hexdigest()
                for path, data in originals.items()
            },
            indent=2,
        )
        + "\n"
    )
    print("temporary source edits restored", flush=True)
