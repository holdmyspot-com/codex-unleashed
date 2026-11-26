#!/usr/bin/env bash
# Steps that the archive and shard jobs of baseline-macos-tests.yml share, so that both build and run the same selection.
#
# Usage: macos-nextest.sh apply-patches
#        macos-nextest.sh build ARCHIVE
#        macos-nextest.sh run ARCHIVE [SHARD COUNT]
#   apply-patches  makes the checkout test upstream's development version, then applies DOWNSTREAM_PATCHES with git am
#   build          archives the selected tests into ARCHIVE, then frees the executables the build left in the target
#                  directory so that extracting the archive for a run in the same job fits on the disk
#   run            runs the selected tests from ARCHIVE; with COUNT above 1, only the tests of partition SHARD of COUNT
#
# Environment: GITHUB_WORKSPACE (the upstream checkout), RUNNER_TEMP, TARGET, TEST_PACKAGE (empty or --workspace selects
# every package), TEST_FILTER, STRESS_COUNT, and for apply-patches DOWNSTREAM_PATCHES, RED_RESTORE, RED_PATCH_COUNT and
# GITHUB_OUTPUT. apply-patches expects the patches copied to $RUNNER_TEMP/downstream-patches and the evidence directory
# $RUNNER_TEMP/baseline-macos-nextest-evidence to exist; it runs in the checkout.
# Exit nonzero on any failure; run exits with nextest's status.

set -euo pipefail

apply_patches() {
  local evidence="$RUNNER_TEMP/baseline-macos-nextest-evidence"
  : > "$evidence/applied-patches.txt"
  local patch_paths=()
  read -r -d '' -a patch_paths <<< "$DOWNSTREAM_PATCHES" || true
  # Snapshot files and nested patch files contain intended trailing spaces; tell git am they are not whitespace errors.
  printf '%s\n' '*.snap -whitespace' '*.patch -whitespace' >> "$(git rev-parse --git-path info/attributes)"
  # Upstream's CI tests main, whose workspace version is 0.0.0; a stable tag's release commit changes only that
  # version. Test the same source, so version strings in snapshots and fixtures match what upstream tested.
  perl -pi -e '$w = /^\[workspace\.package\]\r?$/ ? 1 : /^\[/ ? 0 : $w;' \
    -e 's/^version = "[^"]+"(\r?)$/version = "0.0.0"$1/ if $w' codex-rs/Cargo.toml
  if ! git diff --quiet -- codex-rs/Cargo.toml; then
    git -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
      commit -q -m "Test with upstream's development version 0.0.0" -- codex-rs/Cargo.toml
  fi
  local patch
  for patch in ${patch_paths[@]+"${patch_paths[@]}"}; do
    printf '%s  %s\n' "$(shasum -a 256 < "$RUNNER_TEMP/downstream-$patch" | cut -d' ' -f1)" "$patch" | tee -a "$evidence/applied-patches.txt"
    git -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
      am --committer-date-is-author-date "$RUNNER_TEMP/downstream-$patch"
  done
  printf 'commit=%s\n' "$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"
}

build_archive() {
  local archive="$1"
  local args=(cargo nextest archive --target "$TARGET" --cargo-profile ci-test)
  if [[ -z "$TEST_PACKAGE" || "$TEST_PACKAGE" == --workspace ]]; then
    args+=(--workspace)
  else
    local packages=() package
    read -r -a packages <<< "$TEST_PACKAGE"
    for package in "${packages[@]}"; do args+=(-p "$package"); done
  fi
  cd "$GITHUB_WORKSPACE/codex-rs"
  mkdir -p "$(dirname "$archive")"
  "${args[@]}" --archive-file "$archive"
  # The archive carries every test executable, and extracting it for a run needs that space again. Free the executables
  # the build left in the target directory (library outputs stay for the compiled-state cache); the aarch64 runner
  # otherwise ran out of disk extracting the archive.
  find "${CARGO_TARGET_DIR:-$GITHUB_WORKSPACE/codex-rs/target}/$TARGET/ci-test" -maxdepth 2 -type f -perm -u+x ! -name '*.*' -delete
}

run_archive() {
  local archive="$1" shard="${2:-1}" count="${3:-1}"
  # Keep passing tests' output in the JUnit report, where the skip check reads it.
  printf '%s\n' '[profile.default.junit]' 'store-success-output = true' 'store-failure-output = true' \
    > "$RUNNER_TEMP/nextest-keep-output.toml"
  local args=(cargo nextest run --archive-file "$archive" --workspace-remap "$GITHUB_WORKSPACE/codex-rs"
    --tool-config-file "keep-output:$RUNNER_TEMP/nextest-keep-output.toml")
  if [[ -n "$STRESS_COUNT" ]]; then
    # Without fail-fast, nextest exits with only the last stress iteration's status, so a failure in an
    # earlier iteration would pass the run.
    args+=(--stress-count "$STRESS_COUNT" --retries 0)
  else
    args+=(--no-fail-fast)
  fi
  if [[ -n "$TEST_FILTER" ]]; then args+=(-E "$TEST_FILTER"); fi
  if (( count > 1 )); then args+=(--partition "hash:$shard/$count"); fi
  cd "$GITHUB_WORKSPACE/codex-rs"
  if [[ -n "$STRESS_COUNT" ]]; then
    # Load every CPU only while the tests run, not while the archive builds.
    for _ in $(seq "$(sysctl -n hw.logicalcpu)"); do yes > /dev/null & done
    trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
  fi
  "${args[@]}"
}

case "${1:?usage: macos-nextest.sh apply-patches|build ARCHIVE|run ARCHIVE [SHARD COUNT]}" in
  apply-patches) apply_patches ;;
  build) build_archive "${2:?archive path}" ;;
  run) run_archive "${2:?archive path}" "${3:-1}" "${4:-1}" ;;
  *) printf 'Unknown command: %s\n' "$1" >&2; exit 2 ;;
esac
