#!/usr/bin/env bash
# Exercises the producer's target mapping, resource staging, identity checks and failure cleanup.
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PRODUCER="$SCRIPT_DIR/codex-voice-runtime.sh"
# The isolated fixture root owns test checkouts and is removed by this test process at exit.
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/codex-voice-runtime-tests.XXXXXX")"
if [[ "${VOICE_TEST_KEEP_ROOT:-0}" == 1 ]]; then
  trap 'printf "FIXTURE_EVIDENCE_ROOT=%s\\n" "$TEST_ROOT"' EXIT
else
  trap 'rm -rf -- "$TEST_ROOT"' EXIT
fi
# The fake checkout and helper use this fixed full commit as their valid identity.
EXPECTED_COMMIT=1111111111111111111111111111111111111111
# The fake Git boundary returns this distinct commit when a source identity change is required.
CHANGED_COMMIT=2222222222222222222222222222222222222222
# The active fixture checkout created by make_fixture and passed to one producer run.
FIXTURE_ROOT=

# Stops the fixture with a message that identifies the failed behavior.
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

# Checks an exact value produced by the producer command or its fixture.
assert_equal() {
  local expected="$1"
  local actual="$2"
  local description="$3"

  if [[ "$actual" != "$expected" ]]; then
    fail "$description: expected '$expected', got '$actual'"
  fi
}

# Requires a regular output file written by the producer.
assert_file() {
  local path="$1"
  local description="$2"

  if [[ ! -f "$path" ]]; then
    fail "$description: missing file $path"
  fi
}

# Requires a producer-owned path to be absent after a failed run.
assert_absent() {
  local path="$1"
  local description="$2"

  if [[ -e "$path" ]]; then
    fail "$description: unexpected path $path"
  fi
  if [[ -L "$path" ]]; then
    fail "$description: unexpected path $path"
  fi
}

# Requires one exact argument to reach the fake Bazel boundary as one value.
# Arguments:
# - The complete expected argument, without the log's angle-bracket delimiters.
# Exit status:
# - 0 when the fake Bazel wrapper received the argument intact; otherwise exits with a diagnostic.
assert_build_argument() {
  local argument="$1"

  if grep -Fxq -- "<$argument>" "$TEST_ROOT/build-arguments.txt"; then :; else
    fail "Bazel argument was missing or split: $argument"
  fi
}

# Requires one exact argument to reach the executable-provider query as one value.
assert_query_argument() {
  local argument="$1"

  if grep -Fxq -- "<$argument>" "$TEST_ROOT/query-arguments.txt"; then :; else
    fail "Bazel cquery argument was missing or split: $argument"
  fi
}

# Creates a local upstream checkout and fake native tool boundaries for one case.
make_fixture() {
  local case_name="$1"
  local root="$TEST_ROOT/$case_name upstream"
  local bin="$root/fake-bin"

  FIXTURE_ROOT="$root"
  mkdir -p "$bin" "$root/.github/scripts" "$root/third_party/voice" "$root/codex-rs/voice-host"
  cat > "$bin/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -C && "${3:-}" == rev-parse ]]; then
  if [[ -n "${FAKE_GIT_CHANGE_MARKER:-}" && -f "$FAKE_GIT_CHANGE_MARKER" ]]; then
    printf '%s\n' "$FAKE_CHANGED_COMMIT"
  else
    printf '%s\n' "$FAKE_GIT_COMMIT"
  fi
  exit 0
fi
exec /usr/bin/git "$@"
FAKE_GIT
  cat > "$bin/uname" <<'FAKE_UNAME'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == -m ]]; then
  printf '%s\n' "$FAKE_UNAME_MACHINE"
  exit 0
fi
exec /usr/bin/uname "$@"
FAKE_UNAME
  cat > "$bin/python" <<'FAKE_PYTHON'
#!/usr/bin/env bash
set -euo pipefail
script="$1"
shift
case "$script" in
  */release_runtime.py)
    operation="$1"
    shift
    target=
    source=
    output=
    while (($#)); do
      case "$1" in
        --target) target="$2"; shift 2 ;;
        --source) source="$2"; shift 2 ;;
        --output) output="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    if [[ "$operation" == stage ]]; then
      if [[ "${FAKE_STAGE_STATUS:-0}" != 0 ]]; then
        mkdir -p "$output"
        printf 'injected stage failure\n' >&2
        exit "$FAKE_STAGE_STATUS"
      fi
      if [[ "$target" == *-pc-windows-msvc ]]; then
        mkdir -p "$output/bin"
        cp "$source/bin/gstfixture.dll" "$output/bin/gstfixture.dll"
        printf '{"schemaVersion":1,"target":"%s","sourceCommit":"%s","developmentOnly":true,"libraries":[{"path":"bin/gstfixture.dll","sha256":"fixture"}],"plugins":["bin/gstfixture.dll"]}\n' "$target" "$FAKE_GIT_COMMIT" > "$output/runtime.json"
      else
        mkdir -p "$output/lib"
        cp "$source/lib/libfixture.so" "$output/lib/libfixture.so"
        printf '{"schemaVersion":1,"target":"%s","sourceCommit":"%s","developmentOnly":true,"libraries":[{"path":"lib/libfixture.so","sha256":"fixture"}],"plugins":["lib/libfixture.so"]}\n' "$target" "$FAKE_GIT_COMMIT" > "$output/runtime.json"
      fi
      printf 'fake staged %s\n' "$target"
      exit 0
    fi
    if [[ "$operation" == seal ]]; then
      if [[ "${FAKE_SEAL_STATUS:-0}" != 0 ]]; then
        printf 'injected seal failure\n' >&2
        exit "$FAKE_SEAL_STATUS"
      fi
      sed 's/"developmentOnly":true/"developmentOnly":false,"distribution":"publicRelease"/' "$output/runtime.json" > "$output/runtime.json.new"
      mv "$output/runtime.json.new" "$output/runtime.json"
      printf 'fake sealed %s\n' "$target"
      exit 0
    fi
    printf 'unexpected release_runtime operation: %s\n' "$operation" >&2
    exit 90
    ;;
  */windows_crt.py)
    if [[ "${FAKE_CRT_STATUS:-0}" != 0 ]]; then
      printf 'injected CRT identity failure\n' >&2
      exit "$FAKE_CRT_STATUS"
    fi
    root=
    while (($#)); do
      case "$1" in --root) root="$2"; shift 2 ;; *) shift ;; esac
    done
    mkdir -p "$root/bin"
    printf 'fixture CRT\n' > "$root/bin/vcruntime140.dll"
    exit 0
    ;;
  */voice-cygwin-inputs.py)
    if [[ "${FAKE_WINDOWS_PACKAGES_OK:-1}" == 1 ]]; then exit 0; fi
    printf 'injected Windows package identity mismatch\n' >&2
    exit 91
    ;;
  *)
    printf 'unexpected Python interface: %s\n' "$script" >&2
    exit 92
    ;;
esac
FAKE_PYTHON
  cat > "$root/.github/scripts/run-bazel-ci.sh" <<'FAKE_BUILD'
#!/usr/bin/env bash
set -euo pipefail
printf '<%s>\n' "$@" >> "$FAKE_BUILD_ARGUMENTS"
args=("$@")
separator=-1
for index in "${!args[@]}"; do
  if [[ "${args[$index]}" == -- ]]; then separator="$index"; break; fi
done
if ((separator < 0)); then
  printf 'outer Bazel wrapper separator was missing\n' >&2
  exit 96
fi
inner=("${args[@]:separator+1}")
command="${inner[0]:-}"
if [[ "$command" == cquery ]]; then
  printf '<%s>\n' "${args[@]:0:separator}" >> "$FAKE_QUERY_RUNNER_ARGUMENTS"
fi
if [[ "$command" == build ]]; then
  suffix=
  packaged=0
  installed=0
  for argument in "${inner[@]}"; do
    case "$argument" in
      //third_party/voice:native_runtime_*) suffix="${argument#//third_party/voice:native_runtime_}" ;;
      //codex-rs/voice-host:voice-host-packaged_runtime-test) packaged=1 ;;
      //codex-rs/voice-host:voice-host-installed_package_runtime-test) installed=1 ;;
      *-test-bin) printf 'nonexistent test-bin target requested: %s\n' "$argument" >&2; exit 97 ;;
    esac
  done
  if [[ -z "$suffix" || "$packaged" != 1 || "$installed" != 1 ]]; then
    printf 'build did not request runtime and both direct rust_test targets\n' >&2
    exit 98
  fi
  if [[ -n "${FAKE_CHANGE_COMMIT_AFTER_BUILD:-}" ]]; then : > "$FAKE_GIT_CHANGE_MARKER"; fi
  runtime="bazel-bin/third_party/voice/native_runtime_$suffix"
  case "$suffix" in
    linux_x86_64) voice_target=x86_64-unknown-linux-gnu ;;
    linux_aarch64) voice_target=aarch64-unknown-linux-gnu ;;
    macos_x86_64) voice_target=x86_64-apple-darwin ;;
    macos_aarch64) voice_target=aarch64-apple-darwin ;;
    windows_x86_64) voice_target=x86_64-pc-windows-msvc ;;
    windows_aarch64) voice_target=aarch64-pc-windows-msvc ;;
    *) printf 'unknown fixture runtime suffix: %s\n' "$suffix" >&2; exit 95 ;;
  esac
  if [[ "$suffix" == windows_* ]]; then
    mkdir -p "$runtime/bin"
    printf 'fixture DLL\n' > "$runtime/bin/gstfixture.dll"
  else
    mkdir -p "$runtime/lib"
    printf 'fixture shared library\n' > "$runtime/lib/libfixture.so"
  fi
  printf '{"target": "%s", "sourceCommit": "%s"}\n' "$voice_target" "$FAKE_GIT_COMMIT" > "$runtime/runtime.json"
  mkdir -p bazel-bin/codex-rs/voice-host
  extension=
  if [[ "$suffix" == windows_* ]]; then extension=.exe; fi
  if [[ "${FAKE_MISSING_RESOURCE:-}" != helper ]]; then
    if [[ "$suffix" == windows_* ]]; then helper=bazel-bin/codex-rs/voice-host/codex-voice-host.exe
    else helper=bazel-bin/codex-rs/voice-host/codex-voice-host; fi
    cat > "$helper" <<'HELPER'
#!/bin/bash
set -euo pipefail
if [[ "${1:-}" == --build-commit ]]; then
  printf '%s\n' "${LD_LIBRARY_PATH-}" "${DYLD_LIBRARY_PATH-}" "$PATH" > "$FAKE_HELPER_LOADER_ENVIRONMENT"
  printf '%s\n' "$FAKE_HELPER_COMMIT"
  exit 0
fi
exit 94
HELPER
    chmod +x "$helper"
  fi
  if [[ "${FAKE_MISSING_RESOURCE:-}" == runtime ]]; then rm -rf -- "$runtime"; fi
  if [[ "${FAKE_BUILD_STATUS:-0}" != 0 ]]; then
    printf 'injected build failure\n' >&2
    exit "$FAKE_BUILD_STATUS"
  fi
  exit 0
fi
if [[ "$command" == cquery ]]; then
  printf '<%s>\n' "${args[@]}" >> "$FAKE_QUERY_ARGUMENTS"
  expression="--starlark:expr=providers(target)['DefaultInfo'].files_to_run.executable.path"
  if [[ "${inner[0]:-}" != cquery ]]; then
    printf 'cquery command was missing\n' >&2
    exit 99
  fi
  output_count=0
  expression_count=0
  separator_index=-1
  for index in "${!inner[@]}"; do
    case "${inner[$index]}" in
      --output=starlark) output_count=$((output_count + 1)) ;;
      "$expression") expression_count=$((expression_count + 1)) ;;
      --) if ((index > 0)); then separator_index="$index"; fi ;;
    esac
  done
  if [[ "$output_count" != 1 || "$expression_count" != 1 || "$separator_index" -lt 0 || "$(( ${#inner[@]} - separator_index ))" != 2 ]]; then
    printf 'cquery argv did not match the executable provider contract\n' >&2
    exit 99
  fi
  if [[ "${inner[separator_index-2]:-}" != --output=starlark || "${inner[separator_index-1]:-}" != "$expression" ]]; then
    printf 'provider query output and expression argv order was incorrect\n' >&2
    exit 99
  fi
  label="${inner[separator_index+1]}"
  case "$label" in
    //codex-rs/voice-host:voice-host-packaged_runtime-test) kind=packaged ;;
    //codex-rs/voice-host:voice-host-installed_package_runtime-test) kind=installed ;;
    *) printf 'unexpected cquery target: %s\n' "$label" >&2; exit 100 ;;
  esac
  mode="${FAKE_QUERY_MODE:-success}"
  if [[ "${FAKE_QUERY_KIND:-}" != "$kind" && "$mode" != success ]]; then mode=success; fi
  if [[ "$mode" == provider-failure ]]; then printf 'injected cquery failure\n' >&2; exit 72; fi
  extension=
  if [[ "$RUNNER_OS" == Windows ]]; then extension=.exe; fi
  path="bazel-bin/codex-rs/voice-host/query\\ outputs/$kind runtime test$extension"
  case "$mode" in
    empty) exit 0 ;;
    multiple) printf '%s\n%s\n' "$path" "bazel-bin/codex-rs/voice-host/second output$extension"; exit 0 ;;
    missing) printf '%s\n' "$path"; exit 0 ;;
    non-executable) mkdir -p "${path%/*}"; printf '#!/usr/bin/env bash\nexit 0\n' > "$path"; printf '%s\n' "$path"; exit 0 ;;
    success) ;;
    *) printf 'unknown fake cquery mode: %s\n' "$mode" >&2; exit 101 ;;
  esac
  mkdir -p "${path%/*}"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$path"
  chmod +x "$path"
  if [[ "${FAKE_QUERY_ABSOLUTE:-0}" == 1 ]]; then path="$(pwd -P)/$path"; fi
  printf '%s\n' "$path"
  exit 0
fi
printf 'unexpected Bazel command: %s\n' "$command" >&2
exit 102
FAKE_BUILD
  chmod +x "$bin/git" "$bin/uname" "$bin/python" "$root/.github/scripts/run-bazel-ci.sh"
}

# Runs the producer and prints its captured boundary output for retained evidence.
run_producer() {
  local root="$1"
  local target="$2"
  local runner_os="$3"
  local machine="$4"
  local commit="$5"
  local result="$6"
  shift 6
  local status=0
  local processor_architecture=AMD64
  local log_root="$TEST_ROOT/logs"
  local evidence_log

  if [[ "$machine" == aarch64 ]]; then
    processor_architecture=ARM64
  fi
  mkdir -p "$log_root"
  PATH="$root/fake-bin:$PATH" \
  RUNNER_OS="$runner_os" \
  FAKE_UNAME_MACHINE="$machine" \
  FAKE_GIT_COMMIT="$EXPECTED_COMMIT" \
  FAKE_CHANGED_COMMIT="$CHANGED_COMMIT" \
  FAKE_HELPER_COMMIT="$commit" \
  FAKE_HELPER_LOADER_ENVIRONMENT="$TEST_ROOT/helper-loader-environment.txt" \
  LD_LIBRARY_PATH="$root" \
  DYLD_LIBRARY_PATH="$root" \
  FAKE_BUILD_ARGUMENTS="$TEST_ROOT/build-arguments.txt" \
  FAKE_QUERY_ARGUMENTS="$TEST_ROOT/query-arguments.txt" \
  FAKE_QUERY_RUNNER_ARGUMENTS="$TEST_ROOT/query-runner-arguments.txt" \
  FAKE_QUERY_ABSOLUTE="${FAKE_QUERY_ABSOLUTE:-0}" \
  FAKE_GIT_CHANGE_MARKER="$TEST_ROOT/git-changed" \
  FAKE_WINDOWS_PACKAGES_OK="${FAKE_WINDOWS_PACKAGES_OK:-1}" \
  SystemRoot="${SystemRoot:-/fixture/windows}" \
  PROCESSOR_ARCHITECTURE="$processor_architecture" \
  env "$@" bash "$PRODUCER" build --upstream-root "$root" --target "$target" --build-commit "$commit" --result-dir "$result" \
    > "$log_root/$target.stdout" 2> "$log_root/$target.stderr" || status=$?
  printf 'CASE target=%s status=%s\n' "$target" "$status"
  sed 's/^/stdout: /' "$log_root/$target.stdout"
  sed 's/^/stderr: /' "$log_root/$target.stderr"
  if [[ -d "$result/evidence" ]]; then
    for evidence_log in "$result/evidence"/*.log; do
      if [[ -f "$evidence_log" ]]; then
        printf 'producer-evidence %s:\n' "${evidence_log##*/}"
        sed 's/^/producer-evidence: /' "$evidence_log"
      fi
    done
  fi
  return "$status"
}

# Confirms failed runs leave no usable release tree while preserving owned evidence.
assert_failed_result_cleanup() {
  local result="$1"
  assert_absent "$result/completed" 'failed producer completion marker'
  assert_absent "$result/release" 'failed producer release tree'
  assert_absent "$result/verification" 'failed producer verification tree'
  assert_file "$result/evidence/build.stdout.log" 'failed producer build log'
  assert_file "$result/evidence/build.stderr.log" 'failed producer error log'
}

# Runs one fake native build and checks its mapped targets and sealed output files.
check_target_mapping() {
  local target="$1"
  local runner_os="$2"
  local machine="$3"
  local expected_voice="$4"
  local expected_suffix="$5"
  local expected_runner="$6"
  local expected_helper="$7"
  local processor_architecture=AMD64
  local name="mapping-${target}"
  local root
  local result="$TEST_ROOT/$name result"
  local loader_search_path

  if [[ "$machine" == aarch64 ]]; then
    processor_architecture=ARM64
  fi
  make_fixture "$name"
  root="$FIXTURE_ROOT"
  rm -f -- "$TEST_ROOT/git-changed" "$TEST_ROOT/build-arguments.txt" "$TEST_ROOT/query-arguments.txt" "$TEST_ROOT/query-runner-arguments.txt"
  if [[ "$runner_os" == Windows ]]; then
    mkdir -p "$root/windows-tools/evidence" "$root/windows-tools/pkgconf-image" "$root/windows-tools/cygwin"
    printf '{"target": "%s", "pkgconf_version": "3.0.6"}\n' "$target" > "$root/windows-tools/tools.json"
    printf '{"schemaVersion": 1, "target": "%s", "cygwinArchitecture": "x86_64"}\n' "$target" > "$root/windows-tools/voice-tools.json"
    printf 'fixture package inventory\n' > "$root/windows-tools/evidence/packages.txt"
    export VOICE_WINDOWS_BAZEL_REPOSITORY="$root/windows-tools"
  else
    unset VOICE_WINDOWS_BAZEL_REPOSITORY || true
  fi
  run_producer "$root" "$target" "$runner_os" "$machine" "$EXPECTED_COMMIT" "$result" || fail "producer rejected valid target $target"
  assert_file "$TEST_ROOT/helper-loader-environment.txt" "captured staged helper loader environment for $target"
  case "$runner_os" in
    Linux)
      assert_equal "$result/release/runtime/lib" "$(sed -n '1p' "$TEST_ROOT/helper-loader-environment.txt")" "Linux staged helper loader directory for $target"
      assert_equal "$root" "$(sed -n '2p' "$TEST_ROOT/helper-loader-environment.txt")" "unselected macOS loader environment for $target"
      ;;
    macOS)
      assert_equal "$root" "$(sed -n '1p' "$TEST_ROOT/helper-loader-environment.txt")" "unselected Linux loader environment for $target"
      assert_equal "$result/release/runtime/lib" "$(sed -n '2p' "$TEST_ROOT/helper-loader-environment.txt")" "macOS staged helper loader directory for $target"
      ;;
    Windows)
      loader_search_path="$(sed -n '3p' "$TEST_ROOT/helper-loader-environment.txt")"
      assert_equal "$result/release/runtime/bin" "$loader_search_path" "Windows staged helper loader directory for $target"
      assert_equal "$root" "$(sed -n '1p' "$TEST_ROOT/helper-loader-environment.txt")" "unselected Linux loader environment for $target"
      assert_equal "$root" "$(sed -n '2p' "$TEST_ROOT/helper-loader-environment.txt")" "unselected macOS loader environment for $target"
      ;;
  esac
  assert_file "$result/completed" "completed result for $target"
  assert_equal "$target" "$(cat "$result/evidence/app_target.txt")" "application target for $target"
  assert_equal "$expected_voice" "$(cat "$result/evidence/voice_target.txt")" "voice target for $target"
  assert_equal "$expected_runner" "$(cat "$result/evidence/runner_label.txt")" "native runner mapping for $target"
  assert_file "$result/release/$expected_helper" "copied helper for $target"
  if [[ -x "$result/release/$expected_helper" ]]; then :; else
    fail "copied helper is not executable for $target"
  fi
  assert_file "$result/verification/packaged-runtime-test-bin${expected_helper##*codex-voice-host}" "packaged runtime test for $target"
  assert_file "$result/verification/installed-package-runtime-test-bin${expected_helper##*codex-voice-host}" "installed package test for $target"
  if [[ -x "$result/verification/packaged-runtime-test-bin${expected_helper##*codex-voice-host}" ]]; then :; else
    fail "packaged runtime test is not executable for $target"
  fi
  if [[ -x "$result/verification/installed-package-runtime-test-bin${expected_helper##*codex-voice-host}" ]]; then :; else
    fail "installed package test is not executable for $target"
  fi
  assert_file "$result/release/runtime/runtime.json" "sealed runtime for $target"
  assert_file "$result/evidence/release_files.json" "release file manifest for $target"
  if grep -Fq -- "\"source_commit\": \"$EXPECTED_COMMIT\"" "$result/evidence/release_files.json"; then :; else
    fail "release file manifest source identity for $target"
  fi
  if grep -Fq -- "\"app_target\": \"$target\"" "$result/evidence/release_files.json"; then :; else
    fail "release file manifest application target for $target"
  fi
  if grep -Fq -- "\"voice_target\": \"$expected_voice\"" "$result/evidence/release_files.json"; then :; else
    fail "release file manifest voice target for $target"
  fi
  sed -n 's/.*"path": "\([^"]*\)".*/\1/p' "$result/evidence/release_files.json" > "$TEST_ROOT/$target release-paths.txt"
  if LC_ALL=C sort -c "$TEST_ROOT/$target release-paths.txt"; then :; else
    fail "release file manifest entries are not sorted for $target"
  fi
  if [[ "$runner_os" == Windows ]]; then
    assert_build_argument --windows-msvc-host-platform
    assert_query_argument --windows-msvc-host-platform
    assert_build_argument --platforms=//:local_windows_msvc
    assert_query_argument --platforms=//:local_windows_msvc
    assert_build_argument '--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows,@local_config_cc//:cc-toolchain-arm64_windows,//third_party/voice:windows_pkg_config_toolchain,//third_party/voice:windows_cmake_toolchain'
    assert_query_argument '--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows,@local_config_cc//:cc-toolchain-arm64_windows,//third_party/voice:windows_pkg_config_toolchain,//third_party/voice:windows_cmake_toolchain'
    assert_build_argument --repo_env=BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=0
    assert_query_argument --repo_env=BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=0
    assert_build_argument --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1
    assert_query_argument --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1
    assert_build_argument "--inject_repository=voice_windows_tools=$root/windows-tools"
    assert_query_argument "--inject_repository=voice_windows_tools=$root/windows-tools"
    assert_build_argument --//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools
    assert_query_argument --//third_party/voice:windows_installed_tools=@voice_windows_tools//:tools
    assert_build_argument '--action_env=SystemRoot=/fixture/windows'
    assert_query_argument '--action_env=SystemRoot=/fixture/windows'
    assert_build_argument '--host_action_env=SystemRoot=/fixture/windows'
    assert_query_argument '--host_action_env=SystemRoot=/fixture/windows'
    assert_build_argument "--action_env=PROCESSOR_ARCHITECTURE=$processor_architecture"
    assert_query_argument "--action_env=PROCESSOR_ARCHITECTURE=$processor_architecture"
    assert_build_argument "--host_action_env=PROCESSOR_ARCHITECTURE=$processor_architecture"
    assert_query_argument "--host_action_env=PROCESSOR_ARCHITECTURE=$processor_architecture"
    assert_build_argument --workspace_status_command=./scripts/workspace-status.cmd
    assert_query_argument --workspace_status_command=./scripts/workspace-status.cmd
  fi
  if [[ "$runner_os" == macOS ]]; then
    assert_build_argument --macos_minimum_os=14.0
    assert_query_argument --macos_minimum_os=14.0
  fi
  if [[ "$runner_os" == Linux ]]; then
    assert_build_argument --workspace_status_command=./scripts/workspace-status.sh
    assert_query_argument --workspace_status_command=./scripts/workspace-status.sh
  fi
  if grep -Fq -- "//third_party/voice:native_runtime_$expected_suffix" "$TEST_ROOT/build-arguments.txt"; then :; else
    fail "Bazel runtime target mapping for $target"
  fi
  assert_build_argument //codex-rs/voice-host:voice-host-packaged_runtime-test
  assert_build_argument //codex-rs/voice-host:voice-host-installed_package_runtime-test
  assert_build_argument --remote-download-toplevel
  assert_build_argument --print-failed-action-summary
  assert_query_argument --remote-download-toplevel
  assert_query_argument --print-failed-action-summary
  if grep -Fxq -- '<--query-stdout>' "$TEST_ROOT/query-runner-arguments.txt"; then :; else
    fail "voice executable query did not select machine-readable runner output"
  fi
  assert_build_argument --config=release
  assert_query_argument --config=release
  assert_query_argument --noshow_progress
  assert_query_argument --ui_event_filters=-info
  assert_query_argument --output=starlark
  assert_query_argument "--starlark:expr=providers(target)['DefaultInfo'].files_to_run.executable.path"
  assert_query_argument //codex-rs/voice-host:voice-host-packaged_runtime-test
  assert_query_argument //codex-rs/voice-host:voice-host-installed_package_runtime-test
  if grep -Fq -- 'query\ outputs/' "$result/evidence/packaged-test-output.stdout.log"; then :; else
    fail "query did not preserve a path containing spaces and a backslash for $target"
  fi
}

# Runs a failure case and checks that its result directory contains evidence only.
check_failed_case() {
  local name="$1"
  local target="$2"
  local runner_os="$3"
  local machine="$4"
  shift 4
  local root
  local result="$TEST_ROOT/$name result"

  make_fixture "$name"
  root="$FIXTURE_ROOT"
  rm -f -- "$TEST_ROOT/git-changed" "$TEST_ROOT/build-arguments.txt" "$TEST_ROOT/query-arguments.txt" "$TEST_ROOT/query-runner-arguments.txt"
  if run_producer "$root" "$target" "$runner_os" "$machine" "$EXPECTED_COMMIT" "$result" "$@"; then
    fail "$name unexpectedly succeeded"
  fi
  assert_failed_result_cleanup "$result"
}

# Runs a preflight failure and requires that no result directory is created.
check_query_failure_case() {
  local name="$1"
  local mode="$2"
  local result="$TEST_ROOT/$name result"

  check_failed_case "$name" x86_64-unknown-linux-musl Linux x86_64 "FAKE_QUERY_KIND=packaged" "FAKE_QUERY_MODE=$mode"
  assert_file "$result/evidence/packaged-test-output.stdout.log" "$name query stdout"
  assert_file "$result/evidence/packaged-test-output.stderr.log" "$name query stderr"
}

check_preflight_failure() {
  local name="$1"
  local target="$2"
  local runner_os="$3"
  local machine="$4"
  local commit="$5"
  shift 5
  local root
  local result="$TEST_ROOT/$name result"

  make_fixture "$name"
  root="$FIXTURE_ROOT"
  rm -f -- "$TEST_ROOT/git-changed" "$TEST_ROOT/build-arguments.txt" "$TEST_ROOT/query-arguments.txt"
  if run_producer "$root" "$target" "$runner_os" "$machine" "$commit" "$result" "$@"; then
    fail "$name unexpectedly succeeded"
  fi
  assert_absent "$result" "$name result directory"
  if [[ -s "$TEST_ROOT/build-arguments.txt" ]]; then
    fail "$name invoked Bazel before preflight rejected it"
  fi
}

# The producer command maps all six application targets to native runtime targets and host runners.
FAKE_QUERY_ABSOLUTE=1 check_target_mapping x86_64-unknown-linux-musl Linux x86_64 x86_64-unknown-linux-gnu linux_x86_64 ubuntu-24.04 codex-voice-host
check_target_mapping aarch64-unknown-linux-musl Linux aarch64 aarch64-unknown-linux-gnu linux_aarch64 ubuntu-24.04-arm codex-voice-host
check_target_mapping x86_64-apple-darwin macOS x86_64 x86_64-apple-darwin macos_x86_64 macos-15-intel codex-voice-host
check_target_mapping aarch64-apple-darwin macOS arm64 aarch64-apple-darwin macos_aarch64 macos-15 codex-voice-host
check_target_mapping x86_64-pc-windows-msvc Windows x86_64 x86_64-pc-windows-msvc windows_x86_64 windows-2025 codex-voice-host.exe
check_target_mapping aarch64-pc-windows-msvc Windows aarch64 aarch64-pc-windows-msvc windows_aarch64 windows-11-arm codex-voice-host.exe

# The producer rejects invalid targets and source identities before creating state or invoking Bazel.
check_preflight_failure unsupported-target x86_64-wasm32-wasi Linux x86_64 "$EXPECTED_COMMIT"
check_failed_case changed-source-before x86_64-unknown-linux-musl Linux x86_64 FAKE_GIT_COMMIT="$CHANGED_COMMIT"

# The producer rejects missing helper, runtime, or native test outputs after a successful build.
check_failed_case missing-helper x86_64-unknown-linux-musl Linux x86_64 FAKE_MISSING_RESOURCE=helper
check_failed_case missing-runtime x86_64-unknown-linux-musl Linux x86_64 FAKE_MISSING_RESOURCE=runtime

# Executable-provider query failures preserve both query streams and remove partial release output.
check_query_failure_case failed-provider-query provider-failure
check_query_failure_case empty-provider-output empty
check_query_failure_case multiple-provider-output multiple
check_query_failure_case missing-provider-executable missing
check_query_failure_case non-executable-provider-output non-executable

# The producer removes partial build, stage, seal, and post-build identity results while keeping logs.
check_failed_case changed-source-after x86_64-unknown-linux-musl Linux x86_64 FAKE_CHANGE_COMMIT_AFTER_BUILD=1
check_failed_case failed-build x86_64-unknown-linux-musl Linux x86_64 FAKE_BUILD_STATUS=31
check_failed_case failed-stage x86_64-unknown-linux-musl Linux x86_64 FAKE_STAGE_STATUS=32
check_failed_case failed-seal x86_64-unknown-linux-musl Linux x86_64 FAKE_SEAL_STATUS=33
check_failed_case changed-helper-identity x86_64-unknown-linux-musl Linux x86_64 FAKE_HELPER_COMMIT="$CHANGED_COMMIT"

# The producer rejects a Windows prerequisite repository whose generated target identity differs.
make_fixture wrong-windows-identity
windows_root="$FIXTURE_ROOT"
mkdir -p "$windows_root/windows-tools/evidence" "$windows_root/windows-tools/pkgconf-image" "$windows_root/windows-tools/cygwin"
printf '{"target": "x86_64-pc-windows-msvc", "pkgconf_version": "3.0.6"}\n' > "$windows_root/windows-tools/tools.json"
printf '{"schemaVersion": 1, "target": "x86_64-pc-windows-msvc", "cygwinArchitecture": "x86_64"}\n' > "$windows_root/windows-tools/voice-tools.json"
printf 'fixture package inventory\n' > "$windows_root/windows-tools/evidence/packages.txt"
export VOICE_WINDOWS_BAZEL_REPOSITORY="$windows_root/windows-tools"
check_failed_case wrong-windows-identity aarch64-pc-windows-msvc Windows aarch64
unset VOICE_WINDOWS_BAZEL_REPOSITORY

# The producer rejects a Windows prerequisite package inventory that differs from the pinned snapshot.
make_fixture wrong-windows-package-identity
windows_root="$FIXTURE_ROOT"
mkdir -p "$windows_root/windows-tools/evidence" "$windows_root/windows-tools/pkgconf-image" "$windows_root/windows-tools/cygwin"
printf '{"target": "x86_64-pc-windows-msvc", "pkgconf_version": "3.0.6"}\n' > "$windows_root/windows-tools/tools.json"
printf '{"schemaVersion": 1, "target": "x86_64-pc-windows-msvc", "cygwinArchitecture": "x86_64"}\n' > "$windows_root/windows-tools/voice-tools.json"
printf 'fixture package inventory\n' > "$windows_root/windows-tools/evidence/packages.txt"
export VOICE_WINDOWS_BAZEL_REPOSITORY="$windows_root/windows-tools"
check_failed_case wrong-windows-package-identity x86_64-pc-windows-msvc Windows x86_64 FAKE_WINDOWS_PACKAGES_OK=0
unset VOICE_WINDOWS_BAZEL_REPOSITORY

# The producer preserves a caller-owned result path when it already exists.
make_fixture preexisting-result
existing_root="$FIXTURE_ROOT"
existing_result="$TEST_ROOT/preexisting-result result"
mkdir -p "$existing_result"
printf 'caller-owned\n' > "$existing_result/sentinel"
if run_producer "$existing_root" x86_64-unknown-linux-musl Linux x86_64 "$EXPECTED_COMMIT" "$existing_result"; then
  fail 'producer accepted a preexisting result directory'
fi
assert_equal caller-owned "$(cat "$existing_result/sentinel")" 'preexisting result sentinel'
assert_absent "$existing_result/completed" 'preexisting result completion marker'

printf 'PASS: native voice producer fixture cases\n'
