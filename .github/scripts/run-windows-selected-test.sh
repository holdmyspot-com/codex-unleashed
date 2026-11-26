#!/usr/bin/env bash
# Builds the selected Windows nextest archive or runs the selected tests from one.
#
# Usage: run-windows-selected-test.sh archive|run ARCHIVE_FILE
#
# archive builds the selected packages into ARCHIVE_FILE. run executes the selection from ARCHIVE_FILE, or runs the
# selected PowerShell script, which has no archive and needs no ARCHIVE_FILE. A run covers only the hash partition
# SHARD_INDEX of SHARD_COUNT when SHARD_COUNT is above 1.
#
# Environment: GITHUB_WORKSPACE (holds the upstream checkout), RUNNER_TEMP, TARGET, TEST_PACKAGE (packages or
# --workspace), TEST_FILTER, TEST_SCRIPT, STRESS_COUNT, SHARD_INDEX and SHARD_COUNT (both optional).
set -euo pipefail

mode=${1:?mode: archive or run}
archive=${2:-}

if [[ -n "$TEST_SCRIPT" ]]; then
  [[ "$mode" == run ]] || { printf 'A script selection has no archive to build.\n' >&2; exit 1; }
  cd "$GITHUB_WORKSPACE/upstream"
  pwsh -NoProfile -NonInteractive -File "$TEST_SCRIPT"
  exit
fi

: "${archive:?archive file}"
cd "$GITHUB_WORKSPACE/upstream/codex-rs"
archive_windows=$(cygpath -w "$archive")

case "$mode" in
  archive)
    build_args=(cargo nextest archive --target "$TARGET" --cargo-profile ci-test)
    if [[ "$TEST_PACKAGE" == --workspace ]]; then
      # voice-host links GStreamer through pkg-config, which hosted Windows runners cannot provide; the
      # native voice runtime is covered by the Bazel and release builds.
      build_args+=(--workspace --exclude codex-voice-host)
    else
      read -r -a packages <<< "$TEST_PACKAGE"
      for package in "${packages[@]}"; do build_args+=(-p "$package"); done
    fi
    mkdir -p "$(dirname "$archive")"
    "${build_args[@]}" --archive-file "$archive_windows"
    ;;
  run)
    # Keep passing tests' output in the JUnit report, where the skip check reads it.
    printf '%s\n' '[profile.default.junit]' 'store-success-output = true' 'store-failure-output = true' \
      > "$RUNNER_TEMP/nextest-keep-output.toml"
    run_args=(cargo nextest run --archive-file "$archive_windows" --workspace-remap "$(cygpath -w "$GITHUB_WORKSPACE/upstream/codex-rs")"
      --test-threads 8 --no-fail-fast
      --tool-config-file "keep-output:$(cygpath -w "$RUNNER_TEMP/nextest-keep-output.toml")")
    if [[ -n "$TEST_FILTER" ]]; then run_args+=(-E "$TEST_FILTER"); fi
    if (( ${SHARD_COUNT:-1} > 1 )); then run_args+=(--partition "hash:${SHARD_INDEX:?shard index}/${SHARD_COUNT}"); fi
    if [[ -n "$STRESS_COUNT" ]]; then
      # nextest 0.9.103 exits with only the last stress iteration's status when fail-fast is off, so a stress
      # run keeps fail-fast: the first failing iteration ends the run with a failing status.
      filtered=()
      for argument in "${run_args[@]}"; do [[ "$argument" == --no-fail-fast ]] || filtered+=("$argument"); done
      run_args=("${filtered[@]}" --stress-count "$STRESS_COUNT" --retries 0)
      # Load every CPU only while the tests run, not while the archive builds.
      for _ in $(seq "$(nproc)"); do yes > /dev/null & done
      trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
    fi
    "${run_args[@]}"
    ;;
  *)
    printf 'Unknown mode %s.\n' "$mode" >&2
    exit 1
    ;;
esac
