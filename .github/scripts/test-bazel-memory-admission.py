#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def verify_admission(bazel, temporary_directory):
    """Check estimated-memory admission using the supplied Bazel 9.2.0 executable.

    Creates and cleans a workspace under temporary_directory. Requires two
    600-MB estimates to run serially within a 1000-MB budget, then requires a
    1200-MB estimate to fail before producing output. Prints both successful
    checks; assertions, command errors and filesystem errors propagate.
    This checks declared resource estimates, without allocating those amounts."""
    version = subprocess.run(
        [str(bazel), "--version"], check=True, capture_output=True, text=True
    ).stdout.strip()
    assert version == "bazel 9.2.0", f"Expected the pinned Bazel 9.2.0, received {version}"

    with tempfile.TemporaryDirectory(prefix="bazel-memory-admission-", dir=temporary_directory) as owned:
        root = Path(owned)
        workspace = root / "workspace"
        workspace.mkdir()
        (workspace / "MODULE.bazel").write_text('module(name = "memory_admission_probe")\n')
        (workspace / "worker.py").write_text(
            "from pathlib import Path\n"
            "import sys\n"
            "import time\n"
            "start = time.monotonic_ns()\n"
            "time.sleep(0.2)\n"
            "end = time.monotonic_ns()\n"
            "Path(sys.argv[1]).write_text(str(start) + '\\n' + str(end) + '\\n')\n"
        )
        (workspace / "probe.bzl").write_text('''
def _resources(os, inputs):
    """Return one CPU and a 600-MB memory estimate; os and inputs are unused."""
    return {"cpu": 1, "memory": 600}

def _oversized_resources(os, inputs):
    """Return one CPU and a 1200-MB memory estimate; os and inputs are unused."""
    return {"cpu": 1, "memory": 1200}

def _impl(ctx):
    """Create a timed worker action using the selected 600-MB or 1200-MB estimate.

    ctx.attr.python names its executable; ctx.file.worker names its source.
    Returns DefaultInfo containing the generated start/end timestamp file."""
    output = ctx.actions.declare_file(ctx.label.name + ".txt")
    resources = _resources
    if ctx.attr.oversized:
        resources = _oversized_resources
    ctx.actions.run(
        executable = ctx.attr.python,
        arguments = [ctx.file.worker.path, output.path],
        inputs = [ctx.file.worker],
        outputs = [output],
        resource_set = resources,
    )
    return [DefaultInfo(files = depset([output]))]

probe_action = rule(implementation = _impl, attrs = {
    "oversized": attr.bool(),
    "python": attr.string(mandatory = True),
    "worker": attr.label(allow_single_file = True, default = "worker.py"),
})
''')
        python = json.dumps(sys.executable)
        (workspace / "BUILD.bazel").write_text(
            'load(":probe.bzl", "probe_action")\n'
            f'probe_action(name = "first", python = {python})\n'
            f'probe_action(name = "second", python = {python})\n'
            f'probe_action(name = "oversized", oversized = True, python = {python})\n'
        )
        command = [
            str(bazel), "--ignore_all_rc_files", "--batch",
            f"--output_user_root={root / 'output'}", "build", "--jobs=2",
            "--local_resources=cpu=2", "--local_resources=memory=1000",
            "--experimental_action_resource_set",
            "--noallow_one_action_on_resource_unavailable",
            "--incompatible_autoload_externally=",
        ]
        normal = subprocess.run(
            command + ["//:first", "//:second"], cwd=workspace,
            capture_output=True, text=True,
        )
        assert normal.returncode == 0, normal.stdout + normal.stderr
        intervals = sorted(
            tuple(map(int, (workspace / "bazel-bin" / f"{name}.txt").read_text().split()))
            for name in ("first", "second")
        )
        actions_serialized = intervals[0][1] <= intervals[1][0]
        assert actions_serialized, f"600-MB action estimates exceeded the 1000-MB budget: {intervals}"
        print(f"estimated_memory_admission_serialized={actions_serialized}")

        oversized = subprocess.run(
            command + ["//:oversized"], cwd=workspace, capture_output=True, text=True,
        )
        assert oversized.returncode == 1, oversized.stdout + oversized.stderr
        assert "`memory` resources are not enough" in oversized.stderr, oversized.stderr
        assert (workspace / "bazel-bin" / "oversized.txt").exists() is False
        print("oversized_action_refused_before_execution=True")


def main():
    """Parse --bazel and --temporary-directory, resolve the executable and check memory admission."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--bazel", type=Path, required=True)
    parser.add_argument("--temporary-directory", type=Path, required=True)
    arguments = parser.parse_args()
    verify_admission(arguments.bazel.resolve(strict=True), arguments.temporary_directory)


if __name__ == "__main__":
    main()
