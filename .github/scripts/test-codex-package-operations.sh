#!/usr/bin/env bash

set -euo pipefail
TEST_PYTHON="$(command -v python3)"
TEST_ROOT=''

cleanup_test_root() {
  if [[ -n "$TEST_ROOT" ]]; then
    rm -rf -- "$TEST_ROOT"
  fi
}

# Tests package selection, builds, archive handoffs, and failures through the production shell entry point.
# Fixture tools establish the shell's arguments and ordering, not native build or archive output.

# Fails the shell suite with the supplied diagnostic.
#
# Arguments:
#   Description: The assertion failure message.
# Output: Writes the failure message to standard error.
# Exit status: 1.
fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

# Compares two exact strings and reports the assertion label on mismatch.
#
# Arguments:
#   Description: Assertion label, expected string, and observed string.
# Exit status: 0 when the strings match; otherwise terminates the test suite.
assert_equal() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$expected" != "$actual" ]]; then
    printf '%s\nexpected: <%s>\nactual:   <%s>\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

# Asserts that an operation rejects an unsupported build selection before work begins.
#
# Arguments:
#   Description: Option name and unsupported option value.
# Exit status: 0 when status 2 is returned.
assert_invalid_selection() {
  local option="$1"
  local value="$2"
  local status
  if [[ "$option" == --build-mode ]]; then
    if bash "$operations_script" select-binaries \
      --runner-os Linux --build-mode "$value" --build-system cargo >/dev/null 2>&1; then
      fail "Invalid selection was accepted: ${option} ${value}"
    else
      status=$?
    fi
  elif bash "$operations_script" select-binaries \
    --runner-os Linux --build-mode release --build-system "$value" >/dev/null 2>&1; then
    fail "Invalid selection was accepted: ${option} ${value}"
  else
    status=$?
  fi
  assert_equal "${option} ${value} status" 2 "$status"
}

# Reads one NUL-delimited argv file and compares it with expected arguments.
#
# Arguments:
#   Description: Record path followed by expected argument strings.
# Exit status: 0 when the exact argument vector matches.
assert_argv() {
  local record_path="$1"
  shift
  "$TEST_PYTHON" - "$record_path" "$@" <<'PY'
import pathlib
import sys

record = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
if record[-1] != b"":
    raise SystemExit(f"argv record lacks its final NUL: {sys.argv[1]}")
actual = [item.decode() for item in record[:-1]]
expected = sys.argv[2:]
if actual != expected:
    raise SystemExit(f"argv mismatch for {sys.argv[1]}: expected {expected!r}; got {actual!r}")
PY
}

# Installs fixture executables for the build operation.
#
# Arguments:
#   Description: Fixture bin directory and fixture log directory.
# Side effects: Creates cargo, strip, and git shell fixtures.
# Exit status: 0 when all fixtures are written.
install_build_fixtures() {
  local fixture_bin="$1"
  local fixture_logs="$2"
  mkdir -p "$fixture_bin" "$fixture_logs"
  cat > "${fixture_bin}/cargo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
count_file="${FIXTURE_LOG_DIR}/cargo.count"
count=0
if [[ -f "$count_file" ]]; then read -r count < "$count_file"; fi
count=$((count + 1))
printf '%s\n' "$count" > "$count_file"
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/cargo.${count}.argv"
if [[ "$1" == build && "$*" == *"--bin bwrap"* ]]; then
  mkdir -p "${CARGO_TARGET_DIR}/${CARGO_TARGET}/release"
  printf 'before-strip' > "${CARGO_TARGET_DIR}/${CARGO_TARGET}/release/bwrap"
fi
if [[ "$1" == build && "$*" != *"--bin bwrap"* ]]; then
  printf '%s\n' "${CODEX_BWRAP_SHA256-absent}" "${CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO-absent}" "${CARGO_PROFILE_RELEASE_OPT_LEVEL-absent}" "${STABLE_GIT_COMMIT-absent}" "${LIBSQLITE3_FLAGS-absent}" > "${FIXTURE_LOG_DIR}/cargo.final-env"
fi
if [[ "${CARGO_FAIL_STATUS-}" != '' && "$1" == build && "$*" != *"--bin bwrap"* ]]; then exit "$CARGO_FAIL_STATUS"; fi
STUB
  cat > "${fixture_bin}/strip" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/strip.argv"
printf '%s' '-stripped' >> "$3"
STUB
  cat > "${fixture_bin}/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/git.argv"
printf 'fixture-commit-123\n'
STUB
  chmod +x "${fixture_bin}/cargo" "${fixture_bin}/strip" "${fixture_bin}/git"
}

# Exercises the shared selection and Cargo build operations with a path containing spaces.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates fixture tools and build operation records.
# Exit status: 0 when outputs, environment inheritance, command order, and failure boundaries match.
test_build_operation() {
  local test_root="$1"
  local fixture_bin="${test_root}/fixture tools"
  local fixture_logs="${test_root}/fixture logs"
  local target_dir="${test_root}/cargo target"
  local result_dir="${test_root}/build result"
  local target=x86_64-unknown-linux-musl
  local selected expected_digest actual_digest status
  install_build_fixtures "$fixture_bin" "$fixture_logs"

  selected="$(bash "$operations_script" select-binaries --runner-os Linux --build-mode release --build-system cargo)"
  assert_equal 'Linux release binary selection' \
    'codex codex-app-server codex-code-mode-host codex-exec-server' "$selected"
  selected="$(bash "$operations_script" select-binaries --runner-os macOS --build-mode debug --build-system bazel)"
  assert_equal 'macOS debug binary selection' \
    'codex codex-app-server codex-code-mode-host codex-exec-server' "$selected"
  selected="$(bash "$operations_script" select-binaries --runner-os Windows --build-mode release --build-system cargo)"
  assert_equal 'Windows release binary selection' \
    'codex codex-app-server codex-code-mode-host codex-exec-server codex-command-runner codex-windows-sandbox-setup' "$selected"
  selected="$(bash "$operations_script" select-binaries --runner-os Windows --build-mode debug --build-system bazel)"
  assert_equal 'Windows debug binary selection' \
    'codex codex-app-server codex-code-mode-host codex-exec-server codex-command-runner codex-windows-sandbox-setup' "$selected"
  assert_invalid_selection --build-mode fast
  assert_invalid_selection --build-system make

  assert_equal 'Linux release profile' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=off' \
    "$(bash "$operations_script" profile-env --target x86_64-unknown-linux-musl --build-mode release)"
  assert_equal 'macOS release profile' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed' \
    "$(bash "$operations_script" profile-env --target aarch64-apple-darwin --build-mode release)"
  assert_equal 'Windows debug profile' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=off
CARGO_PROFILE_RELEASE_OPT_LEVEL=0
CARGO_PROFILE_RELEASE_LTO=off
CARGO_PROFILE_RELEASE_CODEGEN_UNITS=256' \
    "$(bash "$operations_script" profile-env --target x86_64-pc-windows-msvc --build-mode debug)"
  assert_equal 'macOS debug profile' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed
CARGO_PROFILE_RELEASE_OPT_LEVEL=0
CARGO_PROFILE_RELEASE_LTO=off
CARGO_PROFILE_RELEASE_CODEGEN_UNITS=256' \
    "$(bash "$operations_script" profile-env --target aarch64-apple-darwin --build-mode debug)"

  export PATH="${fixture_bin}:$PATH"
  export FIXTURE_LOG_DIR="$fixture_logs"
  export CARGO_TARGET="$target"
  export CARGO_TARGET_DIR="$target_dir"
  export CARGO_BUILD_JOBS=2
  export RUSTFLAGS='-C link-arg=/fixture path/arg'
  export CODEX_UNLEASHED_BUILD_NUMBER=123
  export RUNNER_TEMP="${test_root}/runner temp"
  bash "$operations_script" build-binaries \
    --target "$target" --runner-os Linux --build-mode debug \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server' \
    --target-dir "$target_dir" --result-dir "$result_dir"
  expected_digest="$(sha256sum "${target_dir}/${target}/release/bwrap" | awk '{print $1}')"
  actual_digest="$(sed -n '1p' "${fixture_logs}/cargo.final-env")"
  assert_equal 'exported stripped bwrap digest' "$expected_digest" "$actual_digest"
  assert_equal 'exported release split setting' off "$(sed -n '2p' "${fixture_logs}/cargo.final-env")"
  assert_equal 'exported debug profile setting' 0 "$(sed -n '3p' "${fixture_logs}/cargo.final-env")"
  assert_equal 'exported source commit' fixture-commit-123 "$(sed -n '4p' "${fixture_logs}/cargo.final-env")"
  assert_equal 'non-Windows SQLite compatibility setting' absent "$(sed -n '5p' "${fixture_logs}/cargo.final-env")"
  assert_equal 'source commit result' fixture-commit-123 "$(cat "${result_dir}/source-commit")"
  assert_equal 'completion marker' true "$(cat "${result_dir}/completed")"
  assert_equal 'profile record' 'CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=off
CARGO_PROFILE_RELEASE_OPT_LEVEL=0
CARGO_PROFILE_RELEASE_LTO=off
CARGO_PROFILE_RELEASE_CODEGEN_UNITS=256' "$(cat "${result_dir}/profile.env")"
  assert_equal 'export record' "$(cat "${result_dir}/profile.env")
CODEX_BWRAP_SHA256=${expected_digest}" "$(cat "${result_dir}/export.env")"
  assert_argv "${result_dir}/commands/0001.argv" cargo build --target "$target" --release --timings --bin bwrap
  assert_argv "${result_dir}/commands/0002.argv" strip --strip-debug --strip-unneeded "${target_dir}/${target}/release/bwrap"
  assert_argv "${result_dir}/commands/0005.argv" cargo build --target "$target" --release --timings \
    --bin codex --bin codex-app-server --bin codex-code-mode-host --bin codex-exec-server
  assert_argv "${fixture_logs}/cargo.2.argv" build --target "$target" --release --timings \
    --bin codex --bin codex-app-server --bin codex-code-mode-host --bin codex-exec-server
  python3 - "${result_dir}/environment.nul" "$expected_digest" <<'PY'
import pathlib
import sys

parts = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
values = dict(zip(parts[0:-1:2], parts[1:-1:2]))
assert values[b"RUSTFLAGS"] == b"-C link-arg=/fixture path/arg"
assert values[b"CARGO_BUILD_JOBS"] == b"2"
assert values[b"CODEX_BWRAP_SHA256"] == sys.argv[2].encode()
assert values[b"STABLE_GIT_COMMIT"] == b"fixture-commit-123"
assert b"GH_TOKEN" not in values
PY

  result_dir="${test_root}/Windows build result"
  bash "$operations_script" build-binaries \
    --target x86_64-pc-windows-msvc --runner-os Windows --build-mode release \
    --binaries 'codex codex-app-server' --target-dir "$target_dir" --result-dir "$result_dir"
  assert_equal 'Windows SQLite compatibility setting' SQLITE_DISABLE_INTRINSIC \
    "$(sed -n '5p' "${fixture_logs}/cargo.final-env")"

  result_dir="${test_root}/failed build result"
  export CARGO_FAIL_STATUS=37
  if bash "$operations_script" build-binaries \
    --target x86_64-unknown-linux-musl --runner-os Linux --build-mode release \
    --binaries 'codex codex-app-server' --target-dir "$target_dir" --result-dir "$result_dir"; then
    fail 'Failed package Cargo command unexpectedly succeeded'
  else
    status=$?
  fi
  assert_equal 'preserved package Cargo failure status' 37 "$status"
  [[ ! -e "${result_dir}/completed" ]] || fail 'Failed build published completed marker'
}

# Exercises archive argument forwarding and failure ordering using explicit fixture script paths.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates a copied minimal source fixture and archive result records.
# Exit status: 0 when exact tool arguments and early failure behavior match.
test_archive_operation() {
  local test_root="$1"
  local fixture_root="${test_root}/source fixture with spaces"
  local fixture_logs="${test_root}/archive logs"
  local target_dir="${test_root}/archive target"
  local result_dir="${test_root}/archive result"
  local windows_target=aarch64-pc-windows-msvc windows_release binary underscored_pdb status
  mkdir -p "${fixture_root}/.github/scripts" "${fixture_root}/scripts" \
    "${target_dir}/x86_64-unknown-linux-musl/release" "$fixture_logs"
  cat > "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/symbols.argv"
printf '%s\n' "${CODEX_UNLEASHED_BUILD_NUMBER-unset}" > "${FIXTURE_LOG_DIR}/symbols.env"
if [[ "${SYMBOL_FAIL_STATUS-}" != '' ]]; then exit "$SYMBOL_FAIL_STATUS"; fi
STUB
  cat > "${fixture_root}/scripts/build_codex_package.py" <<'STUB'
import os
import pathlib
import sys

count_path = pathlib.Path(os.environ["FIXTURE_LOG_DIR"]) / "package.count"
count = int(count_path.read_text() or "0") + 1 if count_path.exists() else 1
count_path.write_text(str(count))
(pathlib.Path(os.environ["FIXTURE_LOG_DIR"]) / f"package.{count}.argv").write_bytes(
    b"\0".join(value.encode() for value in sys.argv[1:]) + b"\0"
)
print(os.environ.get("CODEX_UNLEASHED_BUILD_NUMBER", "unset"), file=open(
    pathlib.Path(os.environ["FIXTURE_LOG_DIR"]) / f"package.{count}.env", "w"
))
STUB
  chmod +x "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh"
  export FIXTURE_LOG_DIR="$fixture_logs"
  export CODEX_UNLEASHED_BUILD_NUMBER=456
  bash "$operations_script" archive-packages \
    --target x86_64-unknown-linux-musl --runner-os Linux \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server' \
    --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
    --runner-temp "${test_root}/archive runner temp" --package-version 1.2.3 --result-dir "$result_dir"
  assert_equal 'archive completion marker' true "$(cat "${result_dir}/completed")"
  assert_argv "${result_dir}/commands/0001.argv" bash \
    "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" \
    --target x86_64-unknown-linux-musl --artifact-name x86_64-unknown-linux-musl \
    --release-dir "${target_dir}/x86_64-unknown-linux-musl/release" \
    --archive-dir "${test_root}/symbols-dist/x86_64-unknown-linux-musl" \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server'
  assert_argv "${fixture_logs}/symbols.argv" \
    --target x86_64-unknown-linux-musl --artifact-name x86_64-unknown-linux-musl \
    --release-dir "${target_dir}/x86_64-unknown-linux-musl/release" \
    --archive-dir "${test_root}/symbols-dist/x86_64-unknown-linux-musl" \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server'
  assert_equal 'symbol tool inherited build identity' 456 "$(cat "${fixture_logs}/symbols.env")"
  assert_argv "${fixture_logs}/package.1.argv" \
    --target x86_64-unknown-linux-musl \
    --code-mode-host-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex-code-mode-host" \
    --exec-server-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex-exec-server" \
    --cargo-profile release --package-version 1.2.3 --force --bwrap-bin \
    "${target_dir}/x86_64-unknown-linux-musl/release/bwrap" \
    --variant codex --entrypoint-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex" \
    --package-dir "${test_root}/archive runner temp/codex-package-x86_64-unknown-linux-musl" \
    --archive-output "${test_root}/dist/x86_64-unknown-linux-musl/codex-package-x86_64-unknown-linux-musl.tar.gz" \
    --archive-output "${test_root}/dist/x86_64-unknown-linux-musl/codex-package-x86_64-unknown-linux-musl.tar.zst"
  assert_argv "${fixture_logs}/package.2.argv" \
    --target x86_64-unknown-linux-musl \
    --code-mode-host-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex-code-mode-host" \
    --exec-server-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex-exec-server" \
    --cargo-profile release --package-version 1.2.3 --force --bwrap-bin \
    "${target_dir}/x86_64-unknown-linux-musl/release/bwrap" \
    --variant codex-app-server --entrypoint-bin "${target_dir}/x86_64-unknown-linux-musl/release/codex-app-server" \
    --package-dir "${test_root}/archive runner temp/codex-app-server-package-x86_64-unknown-linux-musl" \
    --archive-output "${test_root}/dist/x86_64-unknown-linux-musl/codex-app-server-package-x86_64-unknown-linux-musl.tar.gz" \
    --archive-output "${test_root}/dist/x86_64-unknown-linux-musl/codex-app-server-package-x86_64-unknown-linux-musl.tar.zst"

  windows_release="${target_dir}/${windows_target}/release"
  mkdir -p "$windows_release"
  for binary in codex codex-app-server codex-code-mode-host codex-exec-server \
    codex-command-runner codex-windows-sandbox-setup; do
    underscored_pdb="${binary//-/_}"
    printf '%s' "$binary symbols" > "${windows_release}/${underscored_pdb}.pdb"
  done
  result_dir="${test_root}/Windows archive result"
  bash "$operations_script" archive-packages \
    --target "$windows_target" --runner-os Windows \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server codex-command-runner codex-windows-sandbox-setup' \
    --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
    --runner-temp "${test_root}/archive runner temp" --package-version 1.2.3 --result-dir "$result_dir"
  for binary in codex codex-app-server codex-code-mode-host codex-exec-server \
    codex-command-runner codex-windows-sandbox-setup; do
    underscored_pdb="${binary//-/_}"
    [[ -f "${windows_release}/${binary}.pdb" ]] || fail "PDB name was not normalized for ${binary}"
    [[ -f "${windows_release}/${underscored_pdb}.pdb" ]] || fail "Source PDB was removed for ${binary}"
  done
  assert_argv "${fixture_logs}/package.3.argv" \
    --target "$windows_target" \
    --code-mode-host-bin "${windows_release}/codex-code-mode-host.exe" \
    --exec-server-bin "${windows_release}/codex-exec-server.exe" \
    --cargo-profile release --package-version 1.2.3 --force \
    --codex-command-runner-bin "${windows_release}/codex-command-runner.exe" \
    --codex-windows-sandbox-setup-bin "${windows_release}/codex-windows-sandbox-setup.exe" \
    --variant codex --entrypoint-bin "${windows_release}/codex.exe" \
    --package-dir "${test_root}/archive runner temp/codex-package-${windows_target}" \
    --archive-output "${test_root}/dist/${windows_target}/codex-package-${windows_target}.tar.gz" \
    --archive-output "${test_root}/dist/${windows_target}/codex-package-${windows_target}.tar.zst"
  assert_argv "${fixture_logs}/package.4.argv" \
    --target "$windows_target" \
    --code-mode-host-bin "${windows_release}/codex-code-mode-host.exe" \
    --exec-server-bin "${windows_release}/codex-exec-server.exe" \
    --cargo-profile release --package-version 1.2.3 --force \
    --codex-command-runner-bin "${windows_release}/codex-command-runner.exe" \
    --codex-windows-sandbox-setup-bin "${windows_release}/codex-windows-sandbox-setup.exe" \
    --variant codex-app-server --entrypoint-bin "${windows_release}/codex-app-server.exe" \
    --package-dir "${test_root}/archive runner temp/codex-app-server-package-${windows_target}" \
    --archive-output "${test_root}/dist/${windows_target}/codex-app-server-package-${windows_target}.tar.gz" \
    --archive-output "${test_root}/dist/${windows_target}/codex-app-server-package-${windows_target}.tar.zst"

  result_dir="${test_root}/failed archive result"
  export SYMBOL_FAIL_STATUS=29
  if bash "$operations_script" archive-packages \
    --target x86_64-unknown-linux-musl --runner-os Linux \
    --binaries 'codex codex-app-server' --target-dir "$target_dir" --source-root "$fixture_root" \
    --workspace "$test_root" --runner-temp "$test_root" --package-version 1.2.3 --result-dir "$result_dir"; then
    fail 'Failed symbol operation unexpectedly succeeded'
  else
    status=$?
  fi
  assert_equal 'preserved symbol failure status' 29 "$status"
  [[ ! -e "${result_dir}/completed" ]] || fail 'Failed archive published completed marker'
  assert_equal 'package commands prevented after symbol failure' 1 "$(find "${result_dir}/commands" -type f | wc -l | tr -d ' ')"
}

# Installs fixture tools for the primary package's voice staging and archive handoff.
#
# Arguments:
#   Description: Fixture source, executable, and log directories.
# Side effects: Creates fixture tools and the voice staging launcher.
# Exit status: 0 when all fixtures are installed.
install_voice_archive_fixtures() {
  local fixture_root="$1"
  local fixture_bin="$2"
  local fixture_logs="$3"
  mkdir -p "${fixture_root}/.github/scripts" "${fixture_root}/scripts" "$fixture_bin" "$fixture_logs"
  cat > "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/symbols.argv"
STUB
  cat > "${fixture_bin}/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/git.argv"
[[ "$1" == -C ]] || exit 93
[[ "$3" == rev-parse ]] || exit 94
[[ "$4" == HEAD ]] || exit 95
printf '%s\n' "$OBSERVED_SOURCE_COMMIT"
STUB
  cat > "${fixture_bin}/python3" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
count_file="${FIXTURE_LOG_DIR}/python.count"
count=0
if [[ -f "$count_file" ]]; then read -r count < "$count_file"; fi
count=$((count + 1))
printf '%s\n' "$count" > "$count_file"
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/python.${count}.argv"

if [[ "$1" == "${FIXTURE_SOURCE_ROOT}/scripts/build_codex_package.py" ]]; then
  shift
  package_dir=''
  package_variant=''
  archive_outputs=()
  while (($#)); do
    case "$1" in
      --package-dir) package_dir="$2"; shift 2 ;;
      --variant) package_variant="$2"; shift 2 ;;
      --archive-output) archive_outputs+=("$2"); shift 2 ;;
      *) shift ;;
    esac
  done
  mkdir -p "$package_dir"
  printf '%s\n' "$package_variant" > "${package_dir}/variant"
  for archive_path in "${archive_outputs[@]}"; do
    mkdir -p "$(dirname "$archive_path")"
    printf 'app-server archive\n' > "$archive_path"
  done
elif [[ "$1" == -m ]]; then
  [[ "$2" == codex_package.archive ]] || exit 92
  [[ "$3" == write ]] || exit 92
  printf '%s\n' "${PYTHONPATH-}" > "${FIXTURE_LOG_DIR}/archive.pythonpath"
  shift 3
  package_dir=''
  archive_path=''
  while (($#)); do
    case "$1" in
      --package-dir) package_dir="$2"; shift 2 ;;
      --archive-output) archive_path="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  [[ -f "${package_dir}/voice-runtime/.stage-marker" ]] || exit 91
  archive_count_file="${FIXTURE_LOG_DIR}/archive.count"
  archive_count=0
  if [[ -f "$archive_count_file" ]]; then read -r archive_count < "$archive_count_file"; fi
  archive_count=$((archive_count + 1))
  printf '%s\n' "$archive_count" > "$archive_count_file"
  mkdir -p "$(dirname "$archive_path")"
  printf 'voice package archive\n' > "$archive_path"
  if [[ "${ARCHIVE_FAIL_INDEX-}" == "$archive_count" ]]; then exit "${ARCHIVE_FAIL_STATUS:-31}"; fi
else
  exit 92
fi
STUB
  cat > "${fixture_root}/tooling launcher" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FIXTURE_LOG_DIR}/launcher.argv"
[[ "$1" == voice-runtime ]] || exit 96
[[ "$2" == stage ]] || exit 97
shift 2
package_dir=''
while (($#)); do
  case "$1" in
    --package-dir) package_dir="$2"; shift 2 ;;
    *) shift ;;
  esac
done
mkdir -p "${package_dir}/voice-runtime"
printf 'staged voice resources\n' > "${package_dir}/voice-runtime/.stage-marker"
if [[ "${STAGE_FAIL_STATUS-}" != '' ]]; then exit "$STAGE_FAIL_STATUS"; fi
STUB
  chmod +x "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" \
    "${fixture_bin}/git" "${fixture_bin}/python3" "${fixture_root}/tooling launcher"
}

# Runs the production archive operation with one complete primary voice handoff.
#
# Arguments:
#   Description: Test root, fixture source, target directory, runner temp, result directory, and source commit.
# Exit status: The production archive operation's status.
run_voice_archive_operation() {
  local test_root="$1"
  local fixture_root="$2"
  local target_dir="$3"
  local runner_temp="$4"
  local result_dir="$5"
  local voice_commit="$6"
  local target=x86_64-unknown-linux-musl

  bash "$operations_script" archive-packages \
    --target "$target" --runner-os Linux \
    --binaries 'codex codex-app-server codex-code-mode-host codex-exec-server' \
    --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
    --runner-temp "$runner_temp" --package-version 1.2.3 --result-dir "$result_dir" \
    --voice-release-dir "${target_dir}/${target}/release" --voice-build-commit "$voice_commit" \
    --tooling-launcher "${fixture_root}/tooling launcher"
}

# Rejects every partial or duplicate voice option set before archive work starts.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates validation inputs and captures argument errors.
# Exit status: 0 when the dispatcher rejects malformed voice option sets before creating results.
test_archive_voice_argument_validation() {
  local test_root="$1"
  local fixture_root="${test_root}/voice argument source"
  local target_dir="${test_root}/voice argument target"
  local runner_temp="${test_root}/voice argument runner temp"
  local result_dir error_file status case_index=0 option_index omitted_index supplied_index duplicate_index
  local voice_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local expected_partial='Voice options must be supplied together: --voice-release-dir, --voice-build-commit, --tooling-launcher'
  local expected_duplicate
  local -a voice_options args operation_arguments
  mkdir -p "${fixture_root}/.github/scripts" "${fixture_root}/scripts"
  voice_options=(
    --voice-release-dir "${target_dir}/x86_64-unknown-linux-musl/release"
    --voice-build-commit "$voice_commit"
    --tooling-launcher "${fixture_root}/tooling launcher"
  )
  error_file="${test_root}/voice argument error"

  for supplied_index in 0 1 2; do
    args=("${voice_options[$((supplied_index * 2))]}" "${voice_options[$((supplied_index * 2 + 1))]}")
    result_dir="${test_root}/partial-single-result-${case_index}"
    operation_arguments=(
      --target x86_64-unknown-linux-musl --runner-os Linux
      --binaries 'codex codex-app-server' --target-dir "$target_dir" --source-root "$fixture_root"
      --workspace "$test_root" --runner-temp "$runner_temp" --package-version 1.2.3
      --result-dir "$result_dir"
    )
    if bash "$operations_script" archive-packages "${operation_arguments[@]}" "${args[@]}" > /dev/null 2> "$error_file"; then
      fail 'Dispatcher accepted a single voice option'
    else
      status=$?
    fi
    assert_equal 'single voice option rejection status' 2 "$status"
    assert_equal 'single voice option rejection message' "$expected_partial" "$(cat "$error_file")"
    [[ ! -e "$result_dir" ]] || fail 'Single voice option rejection created operation results'
    case_index=$((case_index + 1))
  done

  for omitted_index in 0 1 2; do
    args=()
    for option_index in 0 1 2; do
      if (( option_index == omitted_index )); then
        continue
      fi
      args+=("${voice_options[$((option_index * 2))]}" "${voice_options[$((option_index * 2 + 1))]}")
    done
    result_dir="${test_root}/partial-double-result-${case_index}"
    if bash "$operations_script" archive-packages \
      --target x86_64-unknown-linux-musl --runner-os Linux --binaries 'codex codex-app-server' \
      --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
      --runner-temp "$runner_temp" --package-version 1.2.3 --result-dir "$result_dir" \
      "${args[@]}" > /dev/null 2> "$error_file"; then
      fail 'Dispatcher accepted a partial voice option triple'
    else
      status=$?
    fi
    assert_equal 'partial voice triple rejection status' 2 "$status"
    assert_equal 'partial voice triple rejection message' "$expected_partial" "$(cat "$error_file")"
    [[ ! -e "$result_dir" ]] || fail 'Partial voice triple rejection created operation results'
    case_index=$((case_index + 1))
  done

  for duplicate_index in 0 1 2; do
    args=("${voice_options[@]}" "${voice_options[$((duplicate_index * 2))]}" \
      "${voice_options[$((duplicate_index * 2 + 1))]}")
    result_dir="${test_root}/duplicate-result-${case_index}"
    expected_duplicate="Option ${voice_options[$((duplicate_index * 2))]} was provided more than once"
    if bash "$operations_script" archive-packages \
      --target x86_64-unknown-linux-musl --runner-os Linux --binaries 'codex codex-app-server' \
      --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
      --runner-temp "$runner_temp" --package-version 1.2.3 --result-dir "$result_dir" \
      "${args[@]}" > /dev/null 2> "$error_file"; then
      fail 'Dispatcher accepted a duplicate voice option'
    else
      status=$?
    fi
    assert_equal 'duplicate voice option rejection status' 2 "$status"
    assert_equal 'duplicate voice option rejection message' "$expected_duplicate" "$(cat "$error_file")"
    [[ ! -e "$result_dir" ]] || fail 'Duplicate voice option rejection created operation results'
    case_index=$((case_index + 1))
  done
}

# Rejects a mismatched source, missing launcher, or preexisting primary output before package work.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates fixture tools, validation inputs, and operation records.
# Exit status: 0 when voice preflight rejects each invalid source or output state.
test_archive_voice_preflight() {
  local test_root="$1"
  local fixture_root="${test_root}/voice preflight source"
  local fixture_bin="${test_root}/voice preflight tools"
  local fixture_logs="${test_root}/voice preflight logs"
  local target=x86_64-unknown-linux-musl
  local target_dir="${test_root}/voice preflight target"
  local runner_temp="${test_root}/voice preflight runner temp"
  local result_dir error_file status case_index=0 extension archive_path
  local voice_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local other_commit=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  local archive_dir="${test_root}/dist/${target}"
  install_voice_archive_fixtures "$fixture_root" "$fixture_bin" "$fixture_logs"
  export PATH="${fixture_bin}:$PATH"
  export FIXTURE_SOURCE_ROOT="$fixture_root"
  error_file="${test_root}/voice preflight error"

  export FIXTURE_LOG_DIR="${test_root}/missing launcher logs"
  mkdir -p "$FIXTURE_LOG_DIR"
  rm -f -- "${fixture_root}/tooling launcher"
  if run_voice_archive_operation "$test_root" "$fixture_root" "$target_dir" "$runner_temp" \
    "${test_root}/missing launcher result" "$voice_commit" > /dev/null 2> "$error_file"; then
    fail 'Archive operation accepted a missing tooling launcher'
  else
    status=$?
  fi
  assert_equal 'missing tooling launcher rejection status' 2 "$status"
  assert_equal 'missing tooling launcher rejection message' \
    "Tooling launcher is not a file: ${fixture_root}/tooling launcher" "$(cat "$error_file")"
  [[ ! -e "${test_root}/missing launcher result" ]] || fail 'Missing launcher rejection created operation results'
  [[ ! -e "${FIXTURE_LOG_DIR}/git.argv" ]] || fail 'Missing launcher rejection ran a process'

  install_voice_archive_fixtures "$fixture_root" "$fixture_bin" "$fixture_logs"
  export FIXTURE_LOG_DIR="${test_root}/source mismatch logs"
  mkdir -p "$FIXTURE_LOG_DIR"
  export OBSERVED_SOURCE_COMMIT="$other_commit"
  result_dir="${test_root}/source mismatch result"
  if run_voice_archive_operation "$test_root" "$fixture_root" "$target_dir" "$runner_temp" \
    "$result_dir" "$voice_commit" > /dev/null 2> "$error_file"; then
    fail 'Archive operation accepted a source commit mismatch'
  else
    status=$?
  fi
  assert_equal 'source commit mismatch rejection status' 1 "$status"
  assert_equal 'source commit mismatch diagnostic' \
    "Voice build commit does not match source HEAD: expected ${voice_commit}, observed ${other_commit}" \
    "$(cat "$error_file")"
  [[ -e "${result_dir}/commands/0001.argv" ]] || fail 'Source identity command was not recorded'
  [[ ! -e "${result_dir}/commands/0002.argv" ]] || fail 'Source mismatch allowed package work to start'
  [[ ! -e "${FIXTURE_LOG_DIR}/symbols.argv" ]] || fail 'Source mismatch invoked symbol preparation'
  [[ ! -e "${FIXTURE_LOG_DIR}/python.count" ]] || fail 'Source mismatch invoked package Python'

  export OBSERVED_SOURCE_COMMIT="$voice_commit"
  for extension in gz zst; do
    archive_path="${archive_dir}/codex-package-${target}.tar.${extension}"
    mkdir -p "$archive_dir"
    printf 'preexisting archive %s\n' "$extension" > "$archive_path"
    export FIXTURE_LOG_DIR="${test_root}/preexisting ${extension} logs"
    mkdir -p "$FIXTURE_LOG_DIR"
    result_dir="${test_root}/preexisting ${extension} result"
    if run_voice_archive_operation "$test_root" "$fixture_root" "$target_dir" "$runner_temp" \
      "$result_dir" "$voice_commit" > /dev/null 2> "$error_file"; then
      fail "Archive operation replaced a preexisting primary .tar.${extension} output"
    else
      status=$?
    fi
    assert_equal "preexisting .tar.${extension} rejection status" 2 "$status"
    assert_equal "preexisting .tar.${extension} rejection message" \
      "Primary voice archive output already exists: ${archive_path}" "$(cat "$error_file")"
    assert_equal "preexisting .tar.${extension} bytes" "preexisting archive ${extension}" "$(cat "$archive_path")"
    [[ ! -e "$result_dir" ]] || fail "Preexisting .tar.${extension} rejection created operation results"
    [[ ! -e "${FIXTURE_LOG_DIR}/git.argv" ]] || fail "Preexisting .tar.${extension} rejection ran a process"
    rm -f -- "$archive_path"
    case_index=$((case_index + 1))
  done
  (( case_index == 2 )) || fail 'Preexisting output cases were not both exercised'
}

# Preserves failure status, records cleanup, and removes only newly emitted primary archives.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates fixture tools, staged files, partial archives, and operation records.
# Exit status: 0 when stage and both archive writer failures leave no primary archive pair.
test_archive_voice_failures() {
  local test_root="$1"
  local fixture_root="${test_root}/voice failure source"
  local fixture_bin="${test_root}/voice failure tools"
  local fixture_logs="${test_root}/voice failure logs"
  local target=x86_64-unknown-linux-musl
  local target_dir="${test_root}/voice failure target"
  local runner_temp="${test_root}/voice failure runner temp"
  local archive_dir="${test_root}/dist/${target}"
  local voice_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local status failure_index result_dir log_dir expected_count cleanup_command
  local archive_gzip="${archive_dir}/codex-package-${target}.tar.gz"
  local archive_zstd="${archive_dir}/codex-package-${target}.tar.zst"
  install_voice_archive_fixtures "$fixture_root" "$fixture_bin" "$fixture_logs"
  export PATH="${fixture_bin}:$PATH"
  export FIXTURE_SOURCE_ROOT="$fixture_root"
  export OBSERVED_SOURCE_COMMIT="$voice_commit"

  log_dir="${test_root}/stage failure logs"
  mkdir -p "$log_dir"
  export FIXTURE_LOG_DIR="$log_dir"
  export STAGE_FAIL_STATUS=23
  result_dir="${test_root}/stage failure result"
  if run_voice_archive_operation "$test_root" "$fixture_root" "$target_dir" "$runner_temp" \
    "$result_dir" "$voice_commit" > /dev/null 2>&1; then
    fail 'Archive operation accepted a failed voice stage'
  else
    status=$?
  fi
  assert_equal 'voice stage failure status' 23 "$status"
  [[ ! -e "${result_dir}/completed" ]] || fail 'Failed voice stage published completed'
  [[ ! -e "$archive_gzip" ]] || fail 'Failed voice stage left a gzip primary archive'
  [[ ! -e "$archive_zstd" ]] || fail 'Failed voice stage left a zstd primary archive'
  [[ ! -e "${result_dir}/commands/0005.argv" ]] || fail 'Failed voice stage started archive writing'
  unset STAGE_FAIL_STATUS

  for failure_index in 1 2; do
    log_dir="${test_root}/archive writer failure ${failure_index} logs"
    mkdir -p "$log_dir"
    export FIXTURE_LOG_DIR="$log_dir"
    export ARCHIVE_FAIL_INDEX="$failure_index"
    export ARCHIVE_FAIL_STATUS=31
    result_dir="${test_root}/archive writer failure ${failure_index} result"
    if run_voice_archive_operation "$test_root" "$fixture_root" "$target_dir" "$runner_temp" \
      "$result_dir" "$voice_commit" > /dev/null 2>&1; then
      fail "Archive operation accepted failure from writer ${failure_index}"
    else
      status=$?
    fi
    assert_equal "archive writer ${failure_index} failure status" 31 "$status"
    [[ ! -e "${result_dir}/completed" ]] || fail "Failed archive writer ${failure_index} published completed"
    [[ ! -e "$archive_gzip" ]] || fail "Failed archive writer ${failure_index} left a gzip primary archive"
    [[ ! -e "$archive_zstd" ]] || fail "Failed archive writer ${failure_index} left a zstd primary archive"
    if (( failure_index == 1 )); then
      expected_count=6
    else
      expected_count=7
    fi
    assert_equal "archive writer ${failure_index} recorded commands" "$expected_count" \
      "$(find "${result_dir}/commands" -type f | wc -l | tr -d ' ')"
    cleanup_command="${result_dir}/commands/$(printf '%04d' "$expected_count").argv"
    assert_argv "$cleanup_command" rm -f -- "$archive_gzip" "$archive_zstd"
  done
  unset ARCHIVE_FAIL_INDEX ARCHIVE_FAIL_STATUS
}

# Stages voice resources in the canonical primary package before both archive writes.
#
# Arguments:
#   Description: Temporary test root.
# Side effects: Creates fixture tools, staged package files, archive files, and operation records.
# Exit status: 0 when the primary voice route and unchanged app-server route match the contract.
test_archive_operation_with_voice() {
  local test_root="$1"
  local fixture_root="${test_root}/voice source with spaces"
  local fixture_bin="${test_root}/voice fixture tools"
  local fixture_logs="${test_root}/voice fixture logs"
  local target=x86_64-unknown-linux-musl
  local target_dir="${test_root}/voice target"
  local runner_temp="${test_root}/voice runner temp"
  local result_dir="${test_root}/voice archive result"
  local package_dir="${runner_temp}/codex-package-${target}"
  local archive_dir="${test_root}/dist/${target}"
  local voice_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local selected_binaries='codex codex-app-server codex-code-mode-host codex-exec-server'
  local voice_archive_gzip="${archive_dir}/codex-package-${target}.tar.gz"
  local voice_archive_zstd="${archive_dir}/codex-package-${target}.tar.zst"
  install_voice_archive_fixtures "$fixture_root" "$fixture_bin" "$fixture_logs"
  export PATH="${fixture_bin}:$PATH"
  export FIXTURE_LOG_DIR="$fixture_logs"
  export FIXTURE_SOURCE_ROOT="$fixture_root"
  export OBSERVED_SOURCE_COMMIT="$voice_commit"
  bash "$operations_script" archive-packages \
    --target "$target" --runner-os Linux --binaries "$selected_binaries" \
    --target-dir "$target_dir" --source-root "$fixture_root" --workspace "$test_root" \
    --runner-temp "$runner_temp" --package-version 1.2.3 --result-dir "$result_dir" \
    --voice-release-dir "${target_dir}/${target}/release" --voice-build-commit "$voice_commit" \
    --tooling-launcher "${fixture_root}/tooling launcher"

  [[ -f "${package_dir}/voice-runtime/.stage-marker" ]] || fail 'Voice stage did not write the primary package marker'
  assert_argv "${result_dir}/commands/0001.argv" git -C "$fixture_root" rev-parse HEAD
  assert_argv "${result_dir}/commands/0002.argv" bash \
    "${fixture_root}/.github/scripts/archive-release-symbols-and-strip-binaries.sh" \
    --target "$target" --artifact-name "$target" --release-dir "${target_dir}/${target}/release" \
    --archive-dir "${test_root}/symbols-dist/${target}" --binaries "$selected_binaries"
  assert_argv "${result_dir}/commands/0003.argv" python3 \
    "${fixture_root}/scripts/build_codex_package.py" \
    --target "$target" --code-mode-host-bin "${target_dir}/${target}/release/codex-code-mode-host" \
    --exec-server-bin "${target_dir}/${target}/release/codex-exec-server" \
    --cargo-profile release --package-version 1.2.3 --force --bwrap-bin \
    "${target_dir}/${target}/release/bwrap" --variant codex \
    --entrypoint-bin "${target_dir}/${target}/release/codex" --package-dir "$package_dir"
  assert_argv "${result_dir}/commands/0004.argv" bash "${fixture_root}/tooling launcher" \
    voice-runtime stage --release-dir "${target_dir}/${target}/release" --package-dir "$package_dir" \
    --upstream-root "$fixture_root" --target "$target" --build-commit "$voice_commit" \
    --package-version 1.2.3
  assert_argv "${result_dir}/commands/0005.argv" python3 -m codex_package.archive write \
    --package-dir "$package_dir" --archive-output "$voice_archive_gzip"
  assert_argv "${result_dir}/commands/0006.argv" python3 -m codex_package.archive write \
    --package-dir "$package_dir" --archive-output "$voice_archive_zstd"
  assert_argv "${result_dir}/commands/0007.argv" python3 \
    "${fixture_root}/scripts/build_codex_package.py" \
    --target "$target" --code-mode-host-bin "${target_dir}/${target}/release/codex-code-mode-host" \
    --exec-server-bin "${target_dir}/${target}/release/codex-exec-server" \
    --cargo-profile release --package-version 1.2.3 --force --bwrap-bin \
    "${target_dir}/${target}/release/bwrap" --variant codex-app-server \
    --entrypoint-bin "${target_dir}/${target}/release/codex-app-server" \
    --package-dir "${runner_temp}/codex-app-server-package-${target}" \
    --archive-output "${archive_dir}/codex-app-server-package-${target}.tar.gz" \
    --archive-output "${archive_dir}/codex-app-server-package-${target}.tar.zst"
  [[ -f "$voice_archive_gzip" ]] || fail 'Primary gzip archive was not written'
  [[ -f "$voice_archive_zstd" ]] || fail 'Primary zstd archive was not written'
  assert_equal 'voice archive command count' 2 "$(cat "${fixture_logs}/archive.count")"
  [[ "$(cat "${fixture_logs}/archive.pythonpath")" == "${fixture_root}/scripts"* ]] || \
    fail 'Voice archive writer did not receive the upstream Python module root'
  assert_equal 'completed marker published after package operations' true "$(cat "${result_dir}/completed")"
}

# Runs all focused package operation checks in one isolated temporary tree.
#
# Arguments:
#   Description: None.
# Side effects: Creates and removes temporary fixtures.
# Exit status: 0 when all checks pass.
main() {
  local source_root operations_script test_root
  source_root="$(cd "$(dirname "$0")/../.." && pwd)"
  operations_script="${source_root}/.github/scripts/codex-package-operations.sh"
  test_root="$(mktemp -d "${TMPDIR:-/tmp}/codex package operations.XXXXXX")"
  TEST_ROOT="$test_root"
  trap cleanup_test_root EXIT
  test_build_operation "$test_root"
  test_archive_operation "$test_root"
  test_archive_voice_argument_validation "$test_root"
  test_archive_voice_preflight "$test_root"
  test_archive_voice_failures "$test_root"
  test_archive_operation_with_voice "$test_root"
  printf 'Package operation selection, profile, build, archive, and failure-boundary checks passed.\n'
  rm -rf -- "$test_root"
  TEST_ROOT=''
  trap - EXIT
}

main "$@"
