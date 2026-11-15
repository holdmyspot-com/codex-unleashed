#!/usr/bin/env bash
# Build and stage same-commit native voice resources for one primary release target.
set -Eeuo pipefail

# The producer-created result directory is the only path cleaned after a failed run.
RESULT_DIR=
# This flag distinguishes a producer-owned result from a caller-owned preexisting path.
RESULT_CREATED=0

# Reports the command form and exits when an invocation cannot be used.
# Exit status:
# - 2 because the invocation omitted, repeated, or added an unsupported argument.
usage_error() {
  printf 'Usage: %s build --upstream-root ABSOLUTE_PATH --target TARGET --build-commit COMMIT --result-dir ABSOLUTE_PATH\n' "$0" >&2
  exit 2
}

# Reports an input or execution failure and leaves cleanup to the exit handler.
# Arguments:
# - A confirmed input or execution failure that tells the caller what to correct or inspect.
# Exit status:
# - 1 for the reported producer failure.
fail() {
  printf 'codex-voice-runtime: %s\n' "$1" >&2
  exit 1
}

# Removes partial output from a producer-owned result and keeps its evidence directory.
# Arguments:
# - The shell exit status that caused producer shutdown.
# Side effects:
# - Removes partial release/, verification/, and completed paths only when this invocation created the result directory.
# - Reports the retained evidence path after a failed producer-owned run.
# Exit status:
# - The same status that caused producer shutdown.
on_exit() {
  local status="$1"

  trap - EXIT
  if [[ "$status" != 0 ]]; then
    if [[ "$RESULT_CREATED" == 1 ]]; then
      rm -rf -- "$RESULT_DIR/release" "$RESULT_DIR/verification" "$RESULT_DIR/completed"
      printf 'evidence retained at %s/evidence\n' "$RESULT_DIR" >&2
    fi
  fi
  exit "$status"
}

# Maps one supported application release target to its same-runner native voice build.
# Arguments:
# - Application target: One primary release target supported by the native runtime build.
# Output:
# - Voice target, Bazel runtime suffix, required runner label, runner OS, host architecture, and helper extension separated by tabs.
# Exit status:
# - 0 when the application target is supported; 1 otherwise.
target_details() {
  local application_target="$1"

  case "$application_target" in
    x86_64-unknown-linux-musl) printf 'x86_64-unknown-linux-gnu\tlinux_x86_64\tubuntu-24.04\tLinux\tx86_64\t\n' ;;
    aarch64-unknown-linux-musl) printf 'aarch64-unknown-linux-gnu\tlinux_aarch64\tubuntu-24.04-arm\tLinux\taarch64\t\n' ;;
    x86_64-apple-darwin) printf 'x86_64-apple-darwin\tmacos_x86_64\tmacos-15-intel\tmacOS\tx86_64\t\n' ;;
    aarch64-apple-darwin) printf 'aarch64-apple-darwin\tmacos_aarch64\tmacos-15\tmacOS\tarm64\t\n' ;;
    x86_64-pc-windows-msvc) printf 'x86_64-pc-windows-msvc\twindows_x86_64\twindows-2025\tWindows\tAMD64\t.exe\n' ;;
    aarch64-pc-windows-msvc) printf 'aarch64-pc-windows-msvc\twindows_aarch64\twindows-11-arm\tWindows\tARM64\t.exe\n' ;;
    *) return 1 ;;
  esac
}

# Verifies that the selected application target is running on its declared native host.
# Arguments:
# - Runner OS, observed host architecture, expected runner OS, and expected host architecture.
# Exit status:
# - 0 when the runner matches the selected target; otherwise exits with a diagnostic.
check_runner_identity() {
  local runner_os="$1"
  local actual_architecture="$2"
  local expected_runner_os="$3"
  local expected_architecture="$4"

  if [[ "$runner_os" == "$expected_runner_os" ]]; then :; else
    fail "target requires runner OS $expected_runner_os; current runner is '${runner_os:-unset}'"
  fi
  if [[ "$actual_architecture" == "$expected_architecture" ]]; then :; else
    fail "target requires host architecture $expected_architecture; current host is '${actual_architecture:-unset}'"
  fi
}

# Confirms that the checked-out upstream commit still matches the requested build identity.
# Arguments:
# - Absolute upstream checkout and expected full lowercase commit identifier.
# Exit status:
# - 0 when HEAD resolves to the expected commit; otherwise exits with a diagnostic.
check_source_identity() {
  local upstream_root="$1"
  local expected_commit="$2"
  local actual_commit

  if actual_commit="$(git -C "$upstream_root" rev-parse --verify 'HEAD^{commit}')"; then :; else
    fail "cannot resolve HEAD in upstream checkout $upstream_root"
  fi
  if [[ "$actual_commit" != "$expected_commit" ]]; then
    fail "upstream HEAD changed: expected $expected_commit, found $actual_commit"
  fi
}

# Confirms the prepared Windows tool repository belongs to the selected target and pinned package set.
# Arguments:
# - Absolute upstream checkout and selected voice target.
# - Absolute repository path exported by setup-voice-windows.ps1.
# Exit status:
# - 0 when generated metadata and installed package inventory match the selected target; otherwise exits with a diagnostic.
check_windows_prerequisites() {
  local upstream_root="$1"
  local voice_target="$2"
  local tools_root="$3"
  local tools_metadata="$tools_root/tools.json"
  local bazel_metadata="$tools_root/voice-tools.json"
  local package_inventory="$tools_root/evidence/packages.txt"

  if [[ ! -d "$tools_root" ]]; then
    fail "Windows build-tool repository is missing: $tools_root"
  fi
  if [[ ! -f "$tools_metadata" ]]; then
    fail "Windows build-tool identity file is missing: $tools_metadata"
  fi
  if [[ ! -f "$bazel_metadata" ]]; then
    fail "Windows Bazel repository identity file is missing: $bazel_metadata"
  fi
  if [[ ! -f "$package_inventory" ]]; then
    fail "Windows installed-package inventory is missing: $package_inventory"
  fi
  if grep -Fq -- "\"target\": \"$voice_target\"" "$tools_metadata"; then :; else
    fail "Windows setup target does not match $voice_target in $tools_metadata"
  fi
  if grep -Fq -- "\"target\": \"$voice_target\"" "$bazel_metadata"; then :; else
    fail "Windows Bazel repository target does not match $voice_target in $bazel_metadata"
  fi
  if grep -Fq -- '"schemaVersion": 1' "$bazel_metadata"; then :; else
    fail "Windows Bazel repository schema is not version 1 in $bazel_metadata"
  fi
  if grep -Fq -- '"cygwinArchitecture": "x86_64"' "$bazel_metadata"; then :; else
    fail "Windows Cygwin host architecture is not x86_64 in $bazel_metadata"
  fi
  if grep -Fq -- '"pkgconf_version": "3.0.6"' "$tools_metadata"; then :; else
    fail "Windows pkgconf version is not 3.0.6 in $tools_metadata"
  fi
  if python "$upstream_root/.github/scripts/voice-cygwin-inputs.py" check-installed --inventory "$package_inventory"; then :; else
    fail "Windows installed Cygwin packages do not match the pinned snapshot"
  fi
}

# Runs a command with separate retained standard-output and standard-error logs.
# Arguments:
# - Description used in the failure diagnostic, stdout log path, stderr log path, then command and arguments.
# Exit status:
# - 0 when the command succeeds; otherwise exits after reporting its status and log location.
run_logged() {
  local description="$1"
  local stdout_log="$2"
  local stderr_log="$3"
  local status
  shift 3

  if "$@" > "$stdout_log" 2> "$stderr_log"; then
    return 0
  else
    status="$?"
    fail "$description failed with status $status; see $stdout_log and $stderr_log"
  fi
}

# Builds identical Bazel configuration arguments for build and executable-provider queries.
# Arguments: command, runner OS, Windows repository, SystemRoot, and host architecture.
configure_bazel_arguments() {
  local command="$1"
  local runner_os="$2"
  local windows_tools_root="$3"
  local system_root="$4"
  local windows_architecture="$5"
  local status_command=./scripts/workspace-status.sh
  wrapper_args=(--remote-download-toplevel --print-failed-action-summary)
  bazel_args=("$command" --config=release)

  if [[ "$runner_os" == macOS ]]; then
    bazel_args+=(--macos_minimum_os=14.0)
  fi
  if [[ "$runner_os" == Windows ]]; then
    status_command=./scripts/workspace-status.cmd
    wrapper_args+=(--windows-msvc-host-platform)
    bazel_args+=(
      --platforms=//:local_windows_msvc
      '--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows,@local_config_cc//:cc-toolchain-arm64_windows,//third_party/voice:windows_pkg_config_toolchain,//third_party/voice:windows_cmake_toolchain'
      --repo_env=BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=0
      --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1
      "--inject_repository=voice_windows_tools=$windows_tools_root"
      --//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools
      "--action_env=SystemRoot=$system_root"
      "--host_action_env=SystemRoot=$system_root"
      "--action_env=PROCESSOR_ARCHITECTURE=$windows_architecture"
      "--host_action_env=PROCESSOR_ARCHITECTURE=$windows_architecture"
    )
  fi
  bazel_args+=("--workspace_status_command=$status_command")
}

# Builds the four same-commit native voice outputs with the selected upstream Bazel wrapper.
# Arguments: upstream root, evidence directory, runtime suffix, runner OS and host-specific configuration.
run_native_build() {
  local upstream_root="$1"
  local evidence_root="$2"
  local runtime_suffix="$3"
  local runner_os="$4"
  local windows_tools_root="$5"
  local system_root="$6"
  local windows_architecture="$7"
  local run_bazel="$upstream_root/.github/scripts/run-bazel-ci.sh"
  local helper_target=//codex-rs/voice-host:codex-voice-host
  local -a wrapper_args=()
  local -a bazel_args=()

  if [[ ! -x "$run_bazel" ]]; then
    fail "upstream Bazel CI wrapper is missing or not executable: $run_bazel"
  fi
  configure_bazel_arguments build "$runner_os" "$windows_tools_root" "$system_root" "$windows_architecture"
  bazel_args+=(--)
  bazel_args+=(
    "//third_party/voice:native_runtime_$runtime_suffix"
    "$helper_target"
    //codex-rs/voice-host:voice-host-packaged_runtime-test
    //codex-rs/voice-host:voice-host-installed_package_runtime-test
  )

  (
    trap - EXIT
    cd -- "$upstream_root"
    run_logged 'native voice Bazel build' "$evidence_root/build.stdout.log" "$evidence_root/build.stderr.log" "$run_bazel" "${wrapper_args[@]}" -- "${bazel_args[@]}"
  )
}

# Queries one built rust_test executable using the exact build configuration and preserves the provider result.
query_native_executable() {
  local upstream_root="$1"
  local evidence_root="$2"
  local runtime_suffix="$3"
  local runner_os="$4"
  local windows_tools_root="$5"
  local system_root="$6"
  local windows_architecture="$7"
  local target_label="$8"
  local output_name="$9"
  local role="${10}"
  local run_bazel="$upstream_root/.github/scripts/run-bazel-ci.sh"
  local stdout_log="$evidence_root/$output_name.stdout.log"
  local stderr_log="$evidence_root/$output_name.stderr.log"
  local path_file="$evidence_root/$output_name.path"
  local resolved_path
  local -a wrapper_args=()
  local -a bazel_args=()

  configure_bazel_arguments cquery "$runner_os" "$windows_tools_root" "$system_root" "$windows_architecture"
  wrapper_args+=(--query-stdout)
  bazel_args+=(
    --noshow_progress
    --ui_event_filters=-info
    --output=starlark
    "--starlark:expr=providers(target)['DefaultInfo'].files_to_run.executable.path"
    --
    "$target_label"
  )
  (
    trap - EXIT
    cd -- "$upstream_root"
    run_logged "$role Bazel executable query" "$stdout_log" "$stderr_log" "$run_bazel" "${wrapper_args[@]}" -- "${bazel_args[@]}"
  )
  if python3 - "$stdout_log" "$path_file" <<'PY'
from pathlib import Path
import sys
raw = Path(sys.argv[1]).read_bytes()
if raw.endswith(b"\r\n"):
    raw = raw[:-2]
elif raw.endswith(b"\n"):
    raw = raw[:-1]
if not raw or b"\r" in raw or b"\n" in raw:
    print("query must return exactly one nonempty executable path line", file=sys.stderr)
    raise SystemExit(1)
try:
    text = raw.decode("utf-8")
except UnicodeDecodeError as error:
    print(f"query output path is not UTF-8: {error}", file=sys.stderr)
    raise SystemExit(1)
Path(sys.argv[2]).write_text(text + "\n", encoding="utf-8")
PY
  then :; else
    fail "$role Bazel executable query returned an empty or multi-line path; see $stdout_log and $stderr_log"
  fi
  IFS= read -r resolved_path < "$path_file"
  if [[ "$resolved_path" == /* ]]; then :; else
    resolved_path="$upstream_root/$resolved_path"
  fi
  require_build_file "$resolved_path" "$role"
  if [[ -x "$resolved_path" ]]; then :; else
    fail "$role is not executable: $resolved_path"
  fi
  QUERY_EXECUTABLE="$resolved_path"
}

# Requires a regular build output before any release files are staged.
# Arguments:
# - Build output path and its caller-readable role.
# Exit status:
# - 0 when the output is a file; otherwise exits with its missing-resource diagnostic.
require_build_file() {
  local path="$1"
  local role="$2"

  if [[ ! -f "$path" ]]; then
    fail "native build did not produce $role: $path"
  fi
}

# Writes a sorted hash inventory for every helper and sealed runtime file under release/.
# Arguments:
# - Release directory, evidence directory, and the three build identity values.
# Side effects:
# - Writes evidence/release_files.json with paths relative to release/.
# Exit status:
# - 0 when every release file has a supported relative path and digest; otherwise exits with a diagnostic.
write_release_file_inventory() {
  local release_root="$1"
  local evidence_root="$2"
  local build_commit="$3"
  local application_target="$4"
  local voice_target="$5"
  local relative_path
  local file_path
  local size
  local hash_output
  local file_hash
  local separator=
  local -a release_paths=()

  while IFS= read -r relative_path; do
    release_paths+=("$relative_path")
  done < <(cd -- "$release_root" && find . -type f -print | LC_ALL=C sort)
  if [[ "${#release_paths[@]}" == 0 ]]; then
    fail "release directory has no files: $release_root"
  fi
  {
    printf '{\n  "format_version": 1,\n  "source_commit": "%s",\n  "app_target": "%s",\n  "voice_target": "%s",\n  "files": [\n' "$build_commit" "$application_target" "$voice_target"
    for relative_path in "${release_paths[@]}"; do
      relative_path="${relative_path#./}"
      case "$relative_path" in
        '' | *[!A-Za-z0-9._/-]*) fail "release file path cannot be represented safely: $relative_path" ;;
      esac
      case "/$relative_path/" in
        */../*) fail "release file path escapes the release directory: $relative_path" ;;
      esac
      file_path="$release_root/$relative_path"
      size="$(wc -c < "$file_path" | tr -d '[:space:]')"
      if command -v sha256sum >/dev/null 2>&1; then
        hash_output="$(sha256sum -- "$file_path")"
      elif command -v shasum >/dev/null 2>&1; then
        hash_output="$(shasum -a 256 "$file_path")"
      else
        fail 'no SHA-256 command is available (expected sha256sum or shasum)'
      fi
      file_hash="${hash_output:0:64}"
      if [[ "$file_hash" =~ ^[0-9a-f]{64}$ ]]; then :; else
        fail "SHA-256 command returned an invalid digest for $relative_path"
      fi
      printf '%s    {"path": "%s", "size": %s, "sha256": "%s"}' "$separator" "$relative_path" "$size" "$file_hash"
      printf '\n'
      separator=,
    done
    printf '  ]\n}\n'
  } > "$evidence_root/release_files.json"
}

# Validates CLI inputs and creates one exclusively owned same-commit native voice result.
# Arguments:
# - build --upstream-root absolute checkout --target application target --build-commit full source commit --result-dir absolute fresh output path.
# Environment:
# - RUNNER_OS and the host architecture identify the native runner.
# - Windows additionally reads SystemRoot, PROCESSOR_ARCHITECTURE, and VOICE_WINDOWS_BAZEL_REPOSITORY from the prepared setup step.
# Side effects:
# - Creates the result directory, retained evidence, staged helper/runtime, two native test executables, and completed marker on success.
# - On failure after exclusive result creation, removes release/, verification/, and completed while retaining evidence/.
# Exit status:
# - 0 when native outputs are staged, sealed, identified, and inventoried; 1 for build or resource failures; 2 for invalid arguments.
main() {
  trap 'on_exit "$?"' EXIT
  local operation="${1:-}"
  local upstream_root=
  local application_target=
  local build_commit=
  local result_dir=
  local has_upstream_root=0
  local has_target=0
  local has_build_commit=0
  local has_result_dir=0
  local target_mapping
  local voice_target
  local runtime_suffix
  local expected_runner_label
  local expected_runner_os
  local expected_architecture
  local helper_extension
  local runner_os="${RUNNER_OS:-}"
  local actual_architecture
  local system_root=
  local windows_architecture=
  local tools_root=
  local result_parent
  local helper_source
  local runtime_source
  local packaged_test_source
  local installed_test_source
  local release_root
  local verification_root
  local evidence_root
  local helper_commit
  local helper_bytes
  local helper_name
  local helper_loader_variable
  local helper_loader_directory
  local argument

  if [[ "$operation" == build ]]; then :; else
    usage_error
  fi
  shift
  while (($#)); do
    argument="$1"
    case "$argument" in
      --upstream-root)
        if [[ "$has_upstream_root" == 1 ]]; then usage_error; fi
        if [[ "$#" -lt 2 ]]; then usage_error; fi
        if [[ -n "${2:-}" ]]; then :; else usage_error; fi
        has_upstream_root=1
        upstream_root="$2"
        shift 2
        ;;
      --target)
        if [[ "$has_target" == 1 ]]; then usage_error; fi
        if [[ "$#" -lt 2 ]]; then usage_error; fi
        if [[ -n "${2:-}" ]]; then :; else usage_error; fi
        has_target=1
        application_target="$2"
        shift 2
        ;;
      --build-commit)
        if [[ "$has_build_commit" == 1 ]]; then usage_error; fi
        if [[ "$#" -lt 2 ]]; then usage_error; fi
        if [[ -n "${2:-}" ]]; then :; else usage_error; fi
        has_build_commit=1
        build_commit="$2"
        shift 2
        ;;
      --result-dir)
        if [[ "$has_result_dir" == 1 ]]; then usage_error; fi
        if [[ "$#" -lt 2 ]]; then usage_error; fi
        if [[ -n "${2:-}" ]]; then :; else usage_error; fi
        has_result_dir=1
        result_dir="$2"
        shift 2
        ;;
      *) usage_error ;;
    esac
  done
  if [[ "$has_upstream_root" == 1 ]]; then :; else usage_error; fi
  if [[ "$has_target" == 1 ]]; then :; else usage_error; fi
  if [[ "$has_build_commit" == 1 ]]; then :; else usage_error; fi
  if [[ "$has_result_dir" == 1 ]]; then :; else usage_error; fi
  if [[ "$upstream_root" == /* ]]; then :; else
    fail "upstream root must be an existing absolute directory: $upstream_root"
  fi
  if [[ -d "$upstream_root" ]]; then :; else
    fail "upstream root must be an existing absolute directory: $upstream_root"
  fi
  if [[ "$result_dir" == /* ]]; then :; else
    fail "result directory must be an absolute path: $result_dir"
  fi
  if [[ "$build_commit" =~ ^[0-9a-f]{40}$ ]]; then :; else
    fail "build commit must be a full lowercase 40-character Git commit identifier: $build_commit"
  fi
  if target_mapping="$(target_details "$application_target")"; then :; else
    fail "unsupported primary application target: $application_target"
  fi
  IFS=$'\t' read -r voice_target runtime_suffix expected_runner_label expected_runner_os expected_architecture helper_extension <<< "$target_mapping"
  case "$runner_os" in
    Linux | macOS)
    if actual_architecture="$(uname -m)"; then :; else
      fail 'cannot identify the native runner architecture with uname -m'
    fi
      ;;
    Windows)
    actual_architecture="${PROCESSOR_ARCHITECTURE:-}"
    system_root="${SystemRoot:-}"
    windows_architecture="$actual_architecture"
    if [[ -z "$system_root" ]]; then
      fail 'Windows native build requires the actual nonempty SystemRoot environment value'
    fi
    if [[ -z "${VOICE_WINDOWS_BAZEL_REPOSITORY:-}" ]]; then
      fail 'Windows native build requires VOICE_WINDOWS_BAZEL_REPOSITORY from setup-voice-windows.ps1'
    fi
    tools_root="$VOICE_WINDOWS_BAZEL_REPOSITORY"
      ;;
    *) fail "unsupported or missing GitHub runner OS: '${runner_os:-unset}'" ;;
  esac
  check_runner_identity "$runner_os" "$actual_architecture" "$expected_runner_os" "$expected_architecture"
  upstream_root="$(cd -- "$upstream_root" && pwd -P)"
  result_parent="$(dirname -- "$result_dir")"
  if [[ -d "$result_parent" ]]; then :; else
    fail "result directory parent must already exist: $result_parent"
  fi
  result_dir="$(cd -- "$result_parent" && pwd -P)/$(basename -- "$result_dir")"
  if [[ -e "$result_dir" ]]; then
    fail "result directory already exists and is caller-owned: $result_dir"
  fi
  if [[ -L "$result_dir" ]]; then
    fail "result directory already exists as a symbolic link: $result_dir"
  fi
  RESULT_DIR="$result_dir"
  if mkdir -- "$RESULT_DIR"; then :; else
    fail "cannot exclusively create result directory: $RESULT_DIR"
  fi
  RESULT_CREATED=1
  evidence_root="$RESULT_DIR/evidence"
  release_root="$RESULT_DIR/release"
  verification_root="$RESULT_DIR/verification"
  mkdir -- "$evidence_root"
  : > "$evidence_root/build.stdout.log"
  : > "$evidence_root/build.stderr.log"
  printf '%s\n' "$build_commit" > "$evidence_root/source_commit.txt"
  printf '%s\n' "$application_target" > "$evidence_root/app_target.txt"
  printf '%s\n' "$voice_target" > "$evidence_root/voice_target.txt"
  printf '%s\n' "$expected_runner_label" > "$evidence_root/runner_label.txt"

  check_source_identity "$upstream_root" "$build_commit"
  if [[ "$runner_os" == Windows ]]; then
    check_windows_prerequisites "$upstream_root" "$voice_target" "$tools_root"
  fi
  run_native_build "$upstream_root" "$evidence_root" "$runtime_suffix" "$runner_os" "$tools_root" "$system_root" "$windows_architecture"
  check_source_identity "$upstream_root" "$build_commit"

  helper_name="codex-voice-host$helper_extension"
  helper_source="$upstream_root/bazel-bin/codex-rs/voice-host/$helper_name"
  runtime_source="$upstream_root/bazel-bin/third_party/voice/native_runtime_$runtime_suffix"
  query_native_executable "$upstream_root" "$evidence_root" "$runtime_suffix" "$runner_os" "$tools_root" "$system_root" "$windows_architecture" \
    //codex-rs/voice-host:voice-host-packaged_runtime-test packaged-test-output 'packaged runtime test executable'
  packaged_test_source="$QUERY_EXECUTABLE"
  query_native_executable "$upstream_root" "$evidence_root" "$runtime_suffix" "$runner_os" "$tools_root" "$system_root" "$windows_architecture" \
    //codex-rs/voice-host:voice-host-installed_package_runtime-test installed-test-output 'installed-package runtime test executable'
  installed_test_source="$QUERY_EXECUTABLE"
  require_build_file "$helper_source" 'voice-host helper'
  require_build_file "$runtime_source/runtime.json" 'voice runtime receipt'
  if [[ -d "$runtime_source" ]]; then :; else
    fail "native build did not produce runtime directory: $runtime_source"
  fi
  if [[ -x "$helper_source" ]]; then :; else
    fail "native voice-host helper is not executable: $helper_source"
  fi
  if grep -Fq -- "\"sourceCommit\": \"$build_commit\"" "$runtime_source/runtime.json"; then :; else
    fail "voice runtime receipt sourceCommit does not match $build_commit"
  fi
  mkdir -- "$release_root" "$verification_root"
  cp -p -- "$helper_source" "$release_root/$helper_name"
  cp -p -- "$packaged_test_source" "$verification_root/packaged-runtime-test-bin$helper_extension"
  cp -p -- "$installed_test_source" "$verification_root/installed-package-runtime-test-bin$helper_extension"

  run_logged 'voice runtime staging' "$evidence_root/stage.stdout.log" "$evidence_root/stage.stderr.log" \
    python "$upstream_root/third_party/voice/release_runtime.py" stage --target "$voice_target" --source "$runtime_source" --output "$release_root/runtime"
  if [[ "$runner_os" == Windows ]]; then
    run_logged 'Windows CRT staging' "$evidence_root/windows-crt.stdout.log" "$evidence_root/windows-crt.stderr.log" \
      python "$upstream_root/third_party/voice/windows_crt.py" --root "$release_root/runtime" --target "$voice_target" --helper "$release_root/$helper_name"
  fi
  run_logged 'voice runtime sealing' "$evidence_root/seal.stdout.log" "$evidence_root/seal.stderr.log" \
    python "$upstream_root/third_party/voice/release_runtime.py" seal --target "$voice_target" --output "$release_root/runtime"
  case "$runner_os" in
    Linux)
      # Linux RUNPATH $ORIGIN/../lib resolves beside release/, while staging puts dependencies under release/runtime/lib.
      helper_loader_variable=LD_LIBRARY_PATH
      helper_loader_directory="$release_root/runtime/lib"
      ;;
    macOS)
      helper_loader_variable=DYLD_LIBRARY_PATH
      helper_loader_directory="$release_root/runtime/lib"
      ;;
    Windows)
      helper_loader_variable=PATH
      helper_loader_directory="$release_root/runtime/bin"
      ;;
    *)
      fail "unsupported host operating system for staged voice-host helper loader: $runner_os"
      ;;
  esac
  if env "$helper_loader_variable=$helper_loader_directory" "$release_root/$helper_name" --build-commit | head -c 129 > "$evidence_root/helper-build-commit.txt"; then :; else
    fail 'staged voice-host helper failed its bounded --build-commit identity check'
  fi
  helper_bytes="$(wc -c < "$evidence_root/helper-build-commit.txt" | tr -d '[:space:]')"
  if [[ "$helper_bytes" != 41 ]]; then
    fail "staged voice-host helper returned $helper_bytes identity bytes; expected one 40-character commit and newline"
  fi
  IFS= read -r helper_commit < "$evidence_root/helper-build-commit.txt"
  if [[ "$helper_commit" == "$build_commit" ]]; then :; else
    fail "staged voice-host helper reports build commit '${helper_commit:-empty}', expected $build_commit"
  fi
  write_release_file_inventory "$release_root" "$evidence_root" "$build_commit" "$application_target" "$voice_target"
  check_source_identity "$upstream_root" "$build_commit"
  printf 'native voice producer complete\n' > "$RESULT_DIR/completed"
  RESULT_CREATED=0
}

# Keeps the CLI-selected producer operation inside its cleanup boundary.
# Arguments:
# - The producer operation and its command-line arguments.
# Exit status:
# - 0 when the requested producer operation completes; otherwise reports the failure status.
main "$@"
