"""Exercise the sealed voice workflow's result paths through its Bash producer."""

from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github/workflows/upstream-auto-release.yml"
WINDOWS_TARGET = "x86_64-pc-windows-msvc"
RESULT_DIRECTORY_ERROR = "result directory must be an absolute path"
BUILD_COMMIT_ERROR = "build commit must be a full lowercase 40-character Git commit identifier"


def extract_voice_result_assignments() -> str:
    """Return the actual result-parent and result-directory assignments from the voice step."""
    lines = WORKFLOW_PATH.read_text(encoding="utf-8").splitlines()
    step_start = lines.index("      - name: Build the sealed voice runtime for this patched source")
    step_end = next(
        (index for index in range(step_start + 1, len(lines)) if lines[index].startswith("      - name: ")),
        len(lines),
    )
    assignments = []
    for line in lines[step_start:step_end]:
        if not line.startswith("          "):
            continue
        shell_line = line[10:]
        name, separator, _ = shell_line.partition("=")
        if separator and name in {"result_parent", "result_dir"}:
            assignments.append(shell_line)

    names = [line.partition("=")[0] for line in assignments]
    if names != ["result_parent", "result_dir"]:
        raise AssertionError(f"voice workflow result assignments were not found in order: {names!r}")
    return "\n".join(assignments)


def run_voice_result_assignments(assignments: str, runner_temp: str, cwd: Path) -> tuple[str, str]:
    """Execute extracted workflow assignments and return their parent and result paths."""
    script = f"set -euo pipefail\n{assignments}\nprintf '%s\\n%s\\n' \"$result_parent\" \"$result_dir\"\n"
    completed = subprocess.run(
        ["bash", "-c", script],
        cwd=cwd,
        env={"RUNNER_TEMP": runner_temp, "TARGET": WINDOWS_TARGET},
        capture_output=True,
        check=False,
        text=True,
    )
    if completed.returncode != 0:
        raise AssertionError(
            "executing the extracted workflow assignments failed: "
            f"exit={completed.returncode}\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
        )
    parent, result_directory = completed.stdout.splitlines()
    return parent, result_directory


def run_producer(
    producer: Path,
    result_directory: str,
    build_commit: str = "invalid-probe",
    extra_environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    """Invoke the unchanged producer with inputs that stop before build or result creation."""
    return subprocess.run(
        [
            "bash",
            str(producer),
            "build",
            "--upstream-root",
            "/tmp",
            "--target",
            WINDOWS_TARGET,
            "--build-commit",
            build_commit,
            "--result-dir",
            result_directory,
        ],
        capture_output=True,
        check=False,
        env={"PATH": "/usr/bin:/bin", **(extra_environment or {})},
        text=True,
    )


def assert_diagnostic(
    label: str, completed: subprocess.CompletedProcess[str], expected: str
) -> None:
    """Require the producer to reach the diagnostic associated with the supplied input."""
    output = completed.stdout + completed.stderr
    if completed.returncode == 0 or expected not in output:
        raise AssertionError(
            f"{label}: expected exit failure containing {expected!r}; got exit={completed.returncode}"
            f"\nstdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
        )
    print(f"PASS {label}: exit={completed.returncode}; {expected}")


def main() -> None:
    """Run workflow-path and producer-validation cases using an absolute producer path."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--producer", required=True, type=Path)
    arguments = parser.parse_args()
    producer = arguments.producer
    if not producer.is_absolute() or not producer.is_file():
        raise SystemExit("--producer must name an existing absolute script path")

    assignments = extract_voice_result_assignments()
    with tempfile.TemporaryDirectory(prefix="voice result parent with spaces ") as temporary:
        owned_root = Path(temporary)
        spaced_parent = owned_root / "runner temp with spaces"
        spaced_parent.mkdir()
        parent, result_directory = run_voice_result_assignments(
            assignments, str(spaced_parent), owned_root
        )
        expected_result = spaced_parent.resolve() / f"voice-build-{WINDOWS_TARGET}"
        if Path(parent) != spaced_parent.resolve() or Path(result_directory) != expected_result:
            raise AssertionError(
                f"spaced parent was not preserved physically: parent={parent!r}, "
                f"result_directory={result_directory!r}"
            )
        assert_diagnostic("absolute parent with spaces", run_producer(producer, result_directory), BUILD_COMMIT_ERROR)
        if expected_result.exists():
            raise AssertionError("producer created a result directory before build-commit validation")

        drive_spelled_parent = owned_root / r"D:\a\_temp"
        drive_spelled_parent.mkdir()
        physical_drive_parent = drive_spelled_parent.resolve()
        parent, result_directory = run_voice_result_assignments(
            assignments, drive_spelled_parent.name, owned_root
        )
        expected_drive_result = physical_drive_parent / f"voice-build-{WINDOWS_TARGET}"
        drive_probe = run_producer(producer, result_directory)
        assert_diagnostic("drive-spelled workflow parent", drive_probe, BUILD_COMMIT_ERROR)
        if Path(parent) != physical_drive_parent or Path(result_directory) != expected_drive_result:
            raise AssertionError(
                "drive-spelled parent was not normalized to its physical POSIX path: "
                f"parent={parent!r}, result_directory={result_directory!r}"
            )
        if expected_drive_result.exists():
            raise AssertionError("producer created a result directory before build-commit validation")

        assert_diagnostic(
            "ordinary relative result path",
            run_producer(producer, "voice-build-relative"),
            RESULT_DIRECTORY_ERROR,
        )
        assert_diagnostic(
            "drive-relative result path",
            run_producer(producer, "D:voice-build-relative"),
            RESULT_DIRECTORY_ERROR,
        )

        windows_environment = {
            "RUNNER_OS": "Windows",
            "SystemRoot": r"C:\Windows",
            "PROCESSOR_ARCHITECTURE": "AMD64",
            "VOICE_WINDOWS_BAZEL_REPOSITORY": str(owned_root),
        }
        valid_build_commit = "a" * 40
        missing_parent = owned_root / "missing parent"
        missing_result = missing_parent / "voice-build-missing-parent"
        assert_diagnostic(
            "missing result parent",
            run_producer(
                producer,
                str(missing_result),
                valid_build_commit,
                windows_environment,
            ),
            "result directory parent must already exist",
        )
        if missing_parent.exists():
            raise AssertionError("producer created a missing result parent")

        existing_result = spaced_parent / "caller-owned result"
        existing_result.mkdir()
        marker = existing_result / "caller-owned marker"
        marker.write_text("keep", encoding="utf-8")
        assert_diagnostic(
            "pre-existing result directory",
            run_producer(
                producer,
                str(existing_result),
                valid_build_commit,
                windows_environment,
            ),
            "result directory already exists and is caller-owned",
        )
        if marker.read_text(encoding="utf-8") != "keep":
            raise AssertionError("producer changed caller-owned result contents")


if __name__ == "__main__":
    main()
