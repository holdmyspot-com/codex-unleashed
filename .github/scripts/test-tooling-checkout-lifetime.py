"""Verify tooling setup arguments, Maven resolution retries and launcher exports with fake build tools."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCRIPT = REPOSITORY_ROOT / ".github/scripts/setup-tooling.sh"
TOOLING_LAUNCHER_RELATIVE = Path("runtime") / "bin" / "codex-tooling"
WINDOWS_TOOLING_INPUT = r"C:\workspace root with spaces"
WINDOWS_BASH_OUTPUT = r"C:\Program Files\Git\usr\bin\bash.exe"


def write_executable(path: Path, content: str) -> None:
    """Write an owned executable fixture used as Maven or cygpath."""
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def create_fake_maven(path: Path) -> None:
    """Create a Maven stand-in that records inputs and writes the expected launcher."""
    write_executable(
        path,
        """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

arguments = sys.argv[1:]
record = {
    "arguments": arguments,
    "tmpdir": os.environ.get("TMPDIR"),
    "tmp": os.environ.get("TMP"),
    "temp": os.environ.get("TEMP"),
}
Path(os.environ["FAKE_MAVEN_LOG"]).write_text(json.dumps(record), encoding="utf-8")
counter = Path(os.environ["FAKE_MAVEN_COUNTER"])
calls = int(counter.read_text(encoding="utf-8")) if counter.exists() else 0
counter.write_text(str(calls + 1), encoding="utf-8")
repository = Path(next(value for value in arguments if value.startswith("-Dmaven.repo.local=")).split("=", 1)[1])
if calls < int(os.environ["FAKE_MAVEN_RESOLUTION_FAILURES"]):
    # A failed lookup leaves a negative-cache marker that the next attempt must not inherit.
    (repository / "stale.lastUpdated").write_text("negative cache", encoding="utf-8")
    print("[ERROR] Could not find artifact org.apache.maven.plugins:maven-clean-plugin:jar:3.5.0 in central", file=sys.stderr)
    raise SystemExit(1)
if (repository / "stale.lastUpdated").exists():
    raise SystemExit("a retry reused the failed attempt's repository")
build_argument = next(value for value in arguments if value.startswith("-Dtooling.build.directory="))
build_root = Path(build_argument.split("=", 1)[1])
launcher = build_root / os.environ["FAKE_LAUNCHER_RELATIVE"]
launcher.parent.mkdir(parents=True, exist_ok=True)
launcher.write_text("fake launcher\\n", encoding="utf-8")
raise SystemExit(int(os.environ["FAKE_MAVEN_STATUS"]))
""",
    )


def create_fake_cygpath(path: Path) -> None:
    """Create a deterministic path-conversion stand-in for the Windows branch."""
    write_executable(
        path,
        """#!/usr/bin/env python3
import json
import os
import sys

arguments = sys.argv[1:]
if len(arguments) != 2:
    raise SystemExit(2)
mode, source = arguments
if mode == "-u" and source == os.environ["FAKE_WINDOWS_TOOLING_INPUT"]:
    converted = os.environ["FAKE_POSIX_TOOLING_ROOT"]
elif mode == "-w" and source == os.environ["FAKE_BASH_PATH"]:
    converted = os.environ["FAKE_WINDOWS_BASH_OUTPUT"]
elif mode == "-w" and source == os.path.join(os.environ["RUNNER_TEMP"], "codex-tooling", "build", os.environ["FAKE_LAUNCHER_RELATIVE"]):
    converted = os.environ["FAKE_WINDOWS_LAUNCHER_OUTPUT"]
elif mode == "-w" and source == os.path.join(os.environ["RUNNER_TEMP"], "codex-tooling"):
    converted = os.environ["FAKE_WINDOWS_ROOT_OUTPUT"]
else:
    raise SystemExit("unexpected cygpath input: " + repr(arguments))
with open(os.environ["FAKE_CYGPATH_LOG"], "a", encoding="utf-8") as stream:
    stream.write(json.dumps({"arguments": arguments, "output": converted}) + "\\n")
print(converted)
""",
    )


def run_case(script: Path, operating_system: str, maven_status: int = 0, resolution_failures: int = 0,
             expected_calls: int = 1) -> None:
    """Run the setup script with controlled paths and fake external tools.

    The first ``resolution_failures`` Maven calls fail with an artifact-resolution error; later calls exit with
    ``maven_status``. ``expected_calls`` is the number of Maven calls the script must make."""
    with tempfile.TemporaryDirectory(prefix="tooling setup fixture with spaces ") as directory:
        fixture_root = Path(directory)
        runner_temp = fixture_root / "runner temp with spaces"
        tooling_root = fixture_root / "tooling checkout with spaces"
        binary_directory = fixture_root / "fake tools"
        runner_temp.mkdir()
        (tooling_root / "tooling/cli").mkdir(parents=True)
        (tooling_root / "tooling/cli/pom.xml").write_text("<project/>\n", encoding="utf-8")
        binary_directory.mkdir()

        maven_log = fixture_root / "maven invocation.json"
        cygpath_log = fixture_root / "cygpath invocations.jsonl"
        github_environment = fixture_root / "GitHub environment file"
        github_environment.write_text("", encoding="utf-8")
        create_fake_maven(binary_directory / "mvn")
        create_fake_cygpath(binary_directory / "cygpath")

        controlled_path = f"{binary_directory}:/usr/bin:/bin"
        bash_path = shutil.which("bash", path=controlled_path)
        if bash_path is None:
            raise AssertionError("Bash was not found in the test's controlled PATH")
        if operating_system == "Windows":
            tooling_root_argument = WINDOWS_TOOLING_INPUT
        else:
            tooling_root_argument = str(tooling_root)

        environment = {
            "PATH": controlled_path,
            "RUNNER_TEMP": str(runner_temp),
            "RUNNER_OS": operating_system,
            "TOOLING_ROOT": tooling_root_argument,
            "GITHUB_ENV": str(github_environment),
            "FAKE_MAVEN_LOG": str(maven_log),
            "FAKE_CYGPATH_LOG": str(cygpath_log),
            "FAKE_MAVEN_STATUS": str(maven_status),
            "FAKE_MAVEN_COUNTER": str(fixture_root / "maven calls"),
            "FAKE_MAVEN_RESOLUTION_FAILURES": str(resolution_failures),
            "MAVEN_RETRY_DELAY_SECONDS": "0",
            "FAKE_LAUNCHER_RELATIVE": str(TOOLING_LAUNCHER_RELATIVE),
            "FAKE_BASH_PATH": bash_path,
            "FAKE_WINDOWS_TOOLING_INPUT": WINDOWS_TOOLING_INPUT,
            "FAKE_POSIX_TOOLING_ROOT": str(tooling_root),
            "FAKE_WINDOWS_BASH_OUTPUT": WINDOWS_BASH_OUTPUT,
            "FAKE_WINDOWS_ROOT_OUTPUT": r"C:\runner temp\codex-tooling",
            "FAKE_WINDOWS_LAUNCHER_OUTPUT": (
                r"C:\runner temp\codex-tooling\build\runtime\bin\codex-tooling"
            ),
        }
        completed = subprocess.run(
            ["/bin/bash", str(script)],
            capture_output=True,
            check=False,
            cwd=fixture_root,
            env=environment,
            text=True,
        )

        expected_root = runner_temp / "codex-tooling"
        expected_launcher = expected_root / "build" / TOOLING_LAUNCHER_RELATIVE
        expected_maven_pom = (
            Path(environment["FAKE_POSIX_TOOLING_ROOT"])
            if operating_system == "Windows"
            else tooling_root
        ) / "tooling/cli/pom.xml"
        expected_maven_arguments = [
            "-q",
            "-B",
            "-ntp",
            "-U",
            "-f",
            str(expected_maven_pom),
            "-Pruntime",
            "-DskipTests",
            f"-Dtooling.build.directory={expected_root}/build",
            f"-Dmaven.repo.local={expected_root}/maven",
            f"-Djava.io.tmpdir={expected_root}/tmp",
            "package",
        ]
        if not maven_log.is_file():
            raise AssertionError(
                f"{operating_system} setup did not invoke Maven: exit={completed.returncode}; "
                f"stdout={completed.stdout!r}; stderr={completed.stderr!r}"
            )
        call_count = int((fixture_root / "maven calls").read_text(encoding="utf-8"))
        if call_count != expected_calls:
            raise AssertionError(f"{operating_system} setup ran Maven {call_count} times, expected {expected_calls}")
        maven_record = json.loads(maven_log.read_text(encoding="utf-8"))
        expected_tmp = str(expected_root / "tmp")
        if maven_record != {
            "arguments": expected_maven_arguments,
            "tmpdir": expected_tmp,
            "tmp": expected_tmp,
            "temp": expected_tmp,
        }:
            raise AssertionError(f"{operating_system} Maven invocation differed: {maven_record!r}")

        if maven_status:
            if completed.returncode != maven_status:
                raise AssertionError(
                    f"Maven failure was not propagated: expected={maven_status}, "
                    f"got={completed.returncode}, stderr={completed.stderr!r}"
                )
            if github_environment.read_text(encoding="utf-8"):
                raise AssertionError("launcher exports were written after Maven failed")
            print(f"PASS {operating_system} Maven failure propagation: exit={completed.returncode}, calls={call_count}")
            return

        if completed.returncode != 0:
            raise AssertionError(
                f"{operating_system} setup failed: exit={completed.returncode}; "
                f"stdout={completed.stdout!r}; stderr={completed.stderr!r}"
            )
        if not expected_launcher.is_file():
            raise AssertionError(f"fake Maven launcher was not created: {expected_launcher}")

        if operating_system == "Windows":
            expected_exports = [
                f"CODEX_TOOLING={environment['FAKE_WINDOWS_LAUNCHER_OUTPUT']}",
                f"CODEX_TOOLING_BASH={WINDOWS_BASH_OUTPUT}",
                f"CODEX_TOOLING_ROOT={environment['FAKE_WINDOWS_ROOT_OUTPUT']}",
            ]
            cygpath_calls = [
                json.loads(line)
                for line in cygpath_log.read_text(encoding="utf-8").splitlines()
            ]
            expected_cygpath_sources = [
                ["-u", WINDOWS_TOOLING_INPUT],
                ["-w", str(expected_launcher)],
                ["-w", bash_path],
                ["-w", str(expected_root)],
            ]
            if [call["arguments"] for call in cygpath_calls] != expected_cygpath_sources:
                raise AssertionError(f"Windows cygpath calls differed: {cygpath_calls!r}")
        else:
            expected_exports = [
                f"CODEX_TOOLING={expected_launcher}",
                f"CODEX_TOOLING_BASH={bash_path}",
                f"CODEX_TOOLING_ROOT={expected_root}",
            ]
        actual_exports = github_environment.read_text(encoding="utf-8").splitlines()
        if actual_exports != expected_exports:
            raise AssertionError(f"{operating_system} launcher exports differed: {actual_exports!r}")
        print(f"PASS {operating_system} setup argv, temporary paths and launcher exports")


def main() -> None:
    """Verify successful Linux and simulated Windows setup, Maven failure propagation and resolution retries."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, default=DEFAULT_SCRIPT)
    arguments = parser.parse_args()
    script = arguments.script.resolve()
    if not script.is_file():
        raise SystemExit(f"setup script does not exist: {script}")
    run_case(script, "Linux")
    run_case(script, "Windows")
    run_case(script, "Linux", maven_status=23)
    run_case(script, "Linux", resolution_failures=2, expected_calls=3)
    run_case(script, "Linux", maven_status=1, resolution_failures=3, expected_calls=3)


if __name__ == "__main__":
    main()
