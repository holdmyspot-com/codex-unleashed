#!/usr/bin/env bash
# Save a completed release binary's Cargo outputs under partial cache aliases.
# Run from codex-rs with one supported release binary name; invalid arguments exit 2.
# GITHUB_WORKSPACE names the pristine upstream checkout. RUNNER_TEMP must contain
# verify-cargo-lock.py, ghcr-cargo-target-cache.sh and baseline-evidence records
# upstream-commit, upstream-ref and upstream-Cargo.lock from the release workflow.
# CARGO_CACHE_REPOSITORY, CACHE_TAG, CACHE_SEED_TAG and CARGO_TARGET_DIR select
# the registry, exact/compatible aliases and idle Cargo outputs to save.
# Require the recorded commit, unchanged tracked sources except Cargo.lock and
# permitted workspace-version lockfile synchronization before uploading.
# Write metadata, verification receipts, transfer logs and elapsed seconds under
# baseline-evidence; propagate guard, Cargo, verifier and upload failures.
set -euo pipefail

if [[ $# != 1 ]]; then
  printf 'usage: %s <completed-release-binary>\n' "$0" >&2
  exit 2
fi
binary="$1"
case "$binary" in
  bwrap|codex|codex-code-mode-host|codex-responses-api-proxy|codex-app-server|codex-windows-sandbox-setup|codex-windows-sandbox-service|codex-command-runner) ;;
  *) printf 'Unknown release binary: %s\n' "$binary" >&2; exit 2 ;;
esac

: "${GITHUB_WORKSPACE:?Set the pristine upstream checkout root}"
: "${RUNNER_TEMP:?Set the owned evidence directory}"
: "${CARGO_CACHE_REPOSITORY:?Set the private cache repository}"
: "${CACHE_TAG:?Set the exact cache identity}"
: "${CACHE_SEED_TAG:?Set the compatible cache identity}"
: "${CARGO_TARGET_DIR:?Set the owned Cargo output directory}"
evidence="$RUNNER_TEMP/baseline-evidence"
started=$SECONDS
test "$(git rev-parse HEAD)" = "$(cat "$evidence/upstream-commit")"
git -C "$GITHUB_WORKSPACE" diff --exit-code HEAD -- . ':(exclude)codex-rs/Cargo.lock'
cargo metadata --no-deps --format-version 1 > "$evidence/workspace-metadata-$binary.json"
python3 "$RUNNER_TEMP/verify-cargo-lock.py" \
  "$evidence/upstream-Cargo.lock" Cargo.lock "$evidence/workspace-metadata-$binary.json" \
  > "$evidence/lock-verification-$binary.json"
CARGO_CACHE_STAGE="$binary" bash "$RUNNER_TEMP/ghcr-cargo-target-cache.sh" push-partial \
  "$CARGO_CACHE_REPOSITORY" "$CACHE_TAG" "$CARGO_TARGET_DIR" \
  "$(cat "$evidence/upstream-ref")" "$CACHE_SEED_TAG" \
  2>&1 | tee "$evidence/checkpoint-$binary.log"
printf 'binary=%s checkpoint_seconds=%s\n' "$binary" "$((SECONDS-started))" \
  | tee -a "$evidence/build-stage-timings.log"
