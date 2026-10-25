"""Compare clean dependency output and Windows test discovery at native boundaries."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

source, evidence, patches, policy = (Path(arg).resolve() for arg in sys.argv[1:])
evidence.mkdir(parents=True, exist_ok=True)
voice_arguments = [
    "--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows,@local_config_cc//:cc-toolchain-arm64_windows,//third_party/voice:windows_pkg_config_toolchain,//third_party/voice:windows_cmake_toolchain",
    "--repo_env=BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=0",
    f"--inject_repository=voice_windows_tools={os.environ['VOICE_WINDOWS_BAZEL_REPOSITORY']}",
    "--//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools",
    f"--action_env=SystemRoot={os.environ['VOICE_WINDOWS_SYSTEM_ROOT']}",
    f"--host_action_env=SystemRoot={os.environ['VOICE_WINDOWS_SYSTEM_ROOT']}",
    f"--action_env=PROCESSOR_ARCHITECTURE={os.environ['VOICE_WINDOWS_HOST_ARCH']}",
    f"--host_action_env=PROCESSOR_ARCHITECTURE={os.environ['VOICE_WINDOWS_HOST_ARCH']}",
]
wrapper = [
    os.environ["BASELINE_BASH"],
    ".github/scripts/run-bazel-ci.sh",
    "--print-failed-action-summary",
    "--print-failed-test-logs",
    "--windows-cross-compile",
    "--remote-download-toplevel",
    "--",
]
common_arguments = [
    "--disk_cache=",
    "--repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1",
    "--skip_incompatible_explicit_targets",
    *voice_arguments,
]
results = {}


def source_diff():
    return subprocess.check_output(["git", "diff", "--binary"], cwd=source)


def run_phase(name, arguments, target):
    command = [*wrapper, *arguments, "--", target]
    environment = dict(
        os.environ,
        CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR=str(evidence / "execution-logs" / name),
    )
    with (evidence / f"{name}.log").open("wb") as log:
        result = subprocess.run(
            command,
            cwd=source,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    results[name] = {"command": command, "exit_code": result.returncode}
    (evidence / "phases.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"{name}: exit {result.returncode}", flush=True)
    return result.returncode


clean_changed_paths = subprocess.check_output(
    ["git", "diff", "--name-only"], cwd=source, text=True
).splitlines()
assert set(clean_changed_paths) <= {"codex-rs/Cargo.lock"}, clean_changed_paths
clean_before = source_diff()
(evidence / "clean-source-before.diff").write_bytes(clean_before)
(evidence / "host-pathext.json").write_text(
    json.dumps({"PATHEXT": os.environ.get("PATHEXT")}, indent=2) + "\n"
)
clean_status = run_phase(
    "clean-opus",
    ["build", *common_arguments],
    "@@rules_rs++crate+crates__opusic-sys-0.7.5//:_bs",
)
clean_after = source_diff()
(evidence / "clean-source-after.diff").write_bytes(clean_after)
assert clean_before == clean_after, "Clean dependency build changed tracked source"

output_base = Path(os.environ["BAZEL_OUTPUT_BASE"])
headers = []
for output in sorted(
    output_base.glob("execroot/_main/bazel-out/*/bin/external/*opusic-sys*/_bs.out_dir")
):
    for header in sorted((output / "include" / "opus").glob("*.h")):
        metadata = header.lstat()
        headers.append(
            {
                "path": header.relative_to(output_base).as_posix(),
                "exists": header.exists(),
                "symlink": header.is_symlink(),
                "link_target": os.readlink(header) if header.is_symlink() else None,
                "file_attributes": getattr(metadata, "st_file_attributes", None),
                "reparse_tag": getattr(metadata, "st_reparse_tag", None),
            }
        )
    for name in ("CMakeCache.txt", "cmake_install.cmake", "install_manifest.txt"):
        for input_file in sorted(output.glob(f"**/{name}")):
            destination = (
                evidence / "clean-opus-install" / input_file.relative_to(output_base)
            )
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(input_file, destination)
(evidence / "clean-opus-headers.json").write_text(
    json.dumps({"path_base": "BAZEL_OUTPUT_BASE", "headers": headers}, indent=2) + "\n"
)

for directory in (patches, policy):
    selected = sorted(directory.glob("*.patch"))
    assert selected, f"No baseline patches found in {directory}"
    for patch in selected:
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=source, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=source, check=True)
subprocess.run(["git", "diff", "--check"], cwd=source, check=True)
applied_repairs = subprocess.check_output(
    ["git", "diff", "--binary", "--", ".", ":(exclude)codex-rs/Cargo.lock"],
    cwd=source,
)
(evidence / "applied-check-repairs.diff").write_bytes(applied_repairs)
baseline_before = source_diff()
(evidence / "baseline-source-before.diff").write_bytes(baseline_before)
test_arguments = [
    "test",
    *common_arguments,
    "--test_tag_filters=-argument-comment-lint",
    "--test_output=all",
    "--nocache_test_results",
    "--test_arg=startup_and_doctor_do_not_execute_path_helpers",
]
target = "//codex-rs/cli:cli-doctor_path_safety-test"
original_status = run_phase("doctor-original", test_arguments, target)
candidate_status = run_phase(
    "doctor-pathext", [*test_arguments, "--test_env=PATHEXT"], target
)
baseline_after = source_diff()
(evidence / "baseline-source-after.diff").write_bytes(baseline_after)
assert baseline_before == baseline_after, "Discovery comparison changed tracked source"
original_log = re.sub(
    rb"\x1b\[[0-9;]*m", b"", (evidence / "doctor-original.log").read_bytes()
)
candidate_log = re.sub(
    rb"\x1b\[[0-9;]*m", b"", (evidence / "doctor-pathext.log").read_bytes()
)
checks = {
    "clean_opus_build_passes": clean_status == 0,
    "clean_opus_headers_recorded": bool(headers),
    "original_discovery_regression_reproduced": (
        original_status != 0
        and b"startup_and_doctor_do_not_execute_path_helpers ... FAILED" in original_log
        and b"git executable not found" in original_log
    ),
    "candidate_original_assertions_pass": (
        candidate_status == 0
        and b"startup_and_doctor_do_not_execute_path_helpers ... ok" in candidate_log
        and b"test result: ok. 1 passed; 0 failed" in candidate_log
    ),
}
(evidence / "verification.json").write_text(
    json.dumps(
        {
            "checks": checks,
            "clean_source_diff_sha256": hashlib.sha256(clean_before).hexdigest(),
            "baseline_source_diff_sha256": hashlib.sha256(baseline_before).hexdigest(),
        },
        indent=2,
    )
    + "\n"
)
if not all(checks.values()):
    raise RuntimeError(f"Native comparison has unmet gates: {checks}")
