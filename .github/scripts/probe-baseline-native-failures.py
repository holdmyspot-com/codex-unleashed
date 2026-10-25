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
fixture_source = source / "codex-rs/exec-server/tests/exec_process/shell_snapshot.rs"
originals = {path: path.read_bytes() for path in (exec_source, fixture_source)}
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
    run_phase("original-snapshot-failures", exec_filter)

    fixture = originals[fixture_source].decode()
    needle = '                "{output:?}"\n'
    assert fixture.count(needle) == 2
    fixture_source.write_text(
        fixture.replace(
            needle,
            '                "output={output:?} stderr={errors:?} status={status:?} closed={closed}"\n',
        )
    )
    text = originals[exec_source].decode()
    needle = "            telemetry.shell_snapshot_captured("
    assert text.count(needle) == 1
    text = text.replace(
        needle,
        """            eprintln!(
                "BASELINE_SNAPSHOT_CAPTURE failure={:?} file_source={:?}",
                result.as_ref().err(),
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
    needle = ".stderr(Stdio::null());"
    assert text.count(needle) == 1
    text = text.replace(needle, ".stderr(Stdio::inherit());")
    exec_source.write_text(text)
    run_phase("snapshot-diagnostics", exec_filter)

    needle = '        telemetry.shell_snapshot_command(wait, availability, "used");'
    assert text.count(needle) == 1
    exec_source.write_text(
        text.replace(
            needle,
            """        if shell_type == ShellType::Bash {
            prepared
                .command
                .insert(shell_start + 1, "--norc".to_string());
        }

"""
            + needle,
        )
    )
    if run_phase("bash-no-rc-counterfactual", exec_filter) != 0:
        raise RuntimeError("Bash replay candidate still fails the four original cases")
    if (
        run_phase(
            "bash-no-rc-integration-regressions",
            "binary_id(codex-exec-server::exec_process) & test(shell_snapshot)",
        )
        != 0
    ):
        raise RuntimeError(
            "Bash replay candidate fails related integration regressions"
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
