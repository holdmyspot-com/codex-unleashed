#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_root="$(mktemp -d "${TMPDIR:?}/bazel-msvc-pathmap-XXXXXX")"
trap 'rm -rf -- "$work_root"' EXIT

cat > "$work_root/capture-command" <<'CAPTURE'
#!/usr/bin/env bash
for argument in "$@"; do
  if [[ "$argument" == info ]]; then
    printf '%s\r\n' "$FIXTURE_EXECUTION_ROOT"
    exit 0
  fi
done

printf '%s\n' "$@" > "$CAPTURE_ARGUMENTS"
CAPTURE
chmod +x "$work_root/capture-command"

for execution_root in 'D:\o\execroot\_main' 'D:/build root/execroot/_main' 'D:/build,root/execroot/_main'; do
  RUNNER_OS=Windows BUILDBUDDY_API_KEY=command-construction-fixture \
    CODEX_BAZEL_WINDOWS_PATH='C:/Windows/System32' \
    CODEX_UNLEASHED_TOOLING="$work_root/capture-command" \
    CAPTURE_ARGUMENTS="$work_root/arguments" FIXTURE_EXECUTION_ROOT="$execution_root" \
    bash "$project_root/.github/scripts/run-bazel-ci.sh" --windows-msvc-host-platform -- build -- //:native_target

  native_root="${execution_root//\\//}"
  native_root="${native_root//,/\\,}"
  if grep -Eq -- '^--(host_)?copt=/(pathmap:|experimental:deterministic)' "$work_root/arguments"; then
    echo 'Native build exposed MSVC-only flags to independently selected Cargo C compilers.' >&2
    exit 1
  fi
  for expected in "--per_file_copt=.*@/experimental:deterministic,/pathmap:${native_root}=." \
    "--host_per_file_copt=.*@/experimental:deterministic,/pathmap:${native_root}=."; do
    if ! grep -Fxq -- "$expected" "$work_root/arguments"; then
      printf 'Native MSVC build did not pass %s to Bazel.\n' "$expected" >&2
      exit 1
    fi
  done
done

for client_host in Linux Windows; do
  RUNNER_OS="$client_host" BUILDBUDDY_API_KEY=command-construction-fixture \
    CODEX_BAZEL_WINDOWS_PATH='C:/Windows/System32' \
    CODEX_UNLEASHED_TOOLING="$work_root/capture-command" \
    CAPTURE_ARGUMENTS="$work_root/arguments" FIXTURE_EXECUTION_ROOT='D:/o/execroot/_main' \
    bash "$project_root/.github/scripts/run-bazel-ci.sh" --windows-cross-compile -- build -- //:cross_target
  if grep -Eq -- '^--(host_)?(copt=/|per_file_copt=.*@/)(pathmap:|experimental:deterministic)' "$work_root/arguments"; then
    printf '%s cross-build received native MSVC path-mapping flags.\n' "$client_host" >&2
    exit 1
  fi
done
