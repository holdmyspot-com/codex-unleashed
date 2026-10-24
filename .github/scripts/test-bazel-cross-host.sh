#!/usr/bin/env bash
# Checks cross-build argv for Linux and Windows runner configurations.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_root="$(mktemp -d "${TMPDIR:-${RUNNER_TEMP:-/tmp}}/bazel-cross-host-XXXXXX")"
trap 'rm -rf -- "$work_root"' EXIT

# This captures the launched argv; it does not simulate Bazel's platform resolution.
cat > "$work_root/capture-command" <<'CAPTURE'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$CAPTURE_ARGUMENTS"
CAPTURE
chmod +x "$work_root/capture-command"
for client_host in Linux Windows; do
  RUNNER_OS="$client_host" BUILDBUDDY_API_KEY=command-construction-fixture \
    CODEX_BAZEL_WINDOWS_PATH='C:/Windows/System32' \
    CODEX_UNLEASHED_TOOLING="$work_root/capture-command" CAPTURE_ARGUMENTS="$work_root/arguments" \
    bash "$project_root/.github/scripts/run-bazel-ci.sh" --windows-cross-compile -- build -- //:windows_target

  for expected in --config=ci-windows-cross --host_platform=//:rbe --shell_executable=/bin/bash --noremote_local_fallback \
    --features=-dynamic_link_msvcrt --host_features=-dynamic_link_msvcrt; do
    if ! grep -Fxq -- "$expected" "$work_root/arguments"; then
      printf '%s client cross-build did not pass %s to Bazel.\n' "$client_host" "$expected" >&2
      exit 1
    fi
  done
done

for host_binding in AMD64:ARM64 ARM64:AMD64 AMD64:; do
  process_architecture="${host_binding%%:*}"
  native_architecture="${host_binding#*:}"
  expected_architecture="${native_architecture:-$process_architecture}"
  RUNNER_OS=Windows BUILDBUDDY_API_KEY='' PROCESSOR_ARCHITECTURE="$process_architecture" \
    VOICE_WINDOWS_HOST_ARCH="$native_architecture" \
    CODEX_BAZEL_WINDOWS_PATH='C:/Windows/System32' \
    CODEX_UNLEASHED_TOOLING="$work_root/capture-command" CAPTURE_ARGUMENTS="$work_root/arguments" \
    bash "$project_root/.github/scripts/run-bazel-ci.sh" -- build -- //:windows_target

  for option in action_env host_action_env; do
    expected="--${option}=PROCESSOR_ARCHITECTURE=${expected_architecture}"
    if ! grep -Fxq -- "$expected" "$work_root/arguments"; then
      printf 'Windows host binding %s did not pass %s to Bazel.\n' "$host_binding" "$expected" >&2
      exit 1
    fi
  done
done

for client_host in Linux macOS Windows; do
  RUNNER_OS="$client_host" BUILDBUDDY_API_KEY=command-construction-fixture \
    CODEX_BAZEL_WINDOWS_PATH='C:/Windows/System32' \
    CODEX_UNLEASHED_TOOLING="$work_root/capture-command" CAPTURE_ARGUMENTS="$work_root/arguments" \
    bash "$project_root/.github/scripts/run-bazel-ci.sh" -- build -- //:native_target

  if grep -Fxq -- --noexperimental_remote_cache_chunking "$work_root/arguments"; then
    if [[ "$client_host" != macOS ]]; then
      printf '%s unexpectedly disables remote cache chunking.\n' "$client_host" >&2
      exit 1
    fi
  elif [[ "$client_host" == macOS ]]; then
    printf 'macOS does not disable the heap-exhausting cache chunking path.\n' >&2
    exit 1
  fi
done
