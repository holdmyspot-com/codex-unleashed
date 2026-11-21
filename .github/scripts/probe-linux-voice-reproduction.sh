#!/usr/bin/env bash
# Rebuild and seal the package's voice payload twice, retaining only diagnostics.
# Run from the patched source after the normal voice-build prerequisites.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_root="$(pwd -P)"
test "$RUNNER_OS:$RUNNER_ARCH:$TARGET" = Linux:X64:x86_64-unknown-linux-musl
test -z "$(git status --porcelain --untracked-files=no)"
build_commit="$(git rev-parse HEAD)"
work="$(mktemp -d "$RUNNER_TEMP/voice-reproduction.XXXXXX")"
reports="$RUNNER_TEMP/voice-reproduction-evidence"
mkdir "$reports"

export VOICE_PROBE_BAZEL
VOICE_PROBE_BAZEL="$(command -v "${CODEX_BAZEL_BIN:-bazel}")"
unset BUILDBUDDY_API_KEY
export CODEX_BAZEL_BIN="$work/bazel"
cat > "$CODEX_BAZEL_BIN" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
arguments=()
while (( $# )); do
  arguments+=("$1")
  if [[ "$1" == build ]]; then
    shift
    # Keep input repository caching but prevent every compiled-output cache.
    arguments+=(--disk_cache= --remote_cache= --remote_executor=)
    break
  fi
  shift
done
exec "$VOICE_PROBE_BAZEL" "${arguments[@]}" "$@"
SHIM
chmod +x "$CODEX_BAZEL_BIN"

# Shut down only the probe's Bazel server. Keep the outputs on the worker until
# runner cleanup so a retained session can inspect an incomplete experiment.
for attempt in first second; do
  export BAZEL_OUTPUT_USER_ROOT="$work/bazel-$attempt"
  result="$work/voice-$attempt"
  test ! -e "$BAZEL_OUTPUT_USER_ROOT"
  status=0
  bash .github/scripts/codex-voice-runtime.sh build --upstream-root "$source_root" \
    --target "$TARGET" --build-commit "$build_commit" --result-dir "$result" || status=$?
  if (( status == 0 )); then
    python3 "$script_dir/voice-elf-reproduction-evidence.py" "$source_root/bazel-bin" "$result/release" \
      > "$work/$attempt.json" || status=$?
    if (( status == 0 )); then mv -- "$work/$attempt.json" "$reports/$attempt.json"; fi
  fi
  "$VOICE_PROBE_BAZEL" --output_user_root="$BAZEL_OUTPUT_USER_ROOT" shutdown
  if (( status != 0 )); then exit "$status"; fi
  test "$(git rev-parse HEAD)" = "$build_commit"
  test -z "$(git status --porcelain --untracked-files=no)"
done
if cmp -s "$reports/first.json" "$reports/second.json"; then
  printf 'comparison=identical\n' >> "$GITHUB_STEP_SUMMARY"
else
  printf 'comparison=changed\n' >> "$GITHUB_STEP_SUMMARY"
fi
