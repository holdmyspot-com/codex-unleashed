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
