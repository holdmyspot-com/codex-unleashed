#!/usr/bin/env bash
# Exercise actual scheduled release, authenticated completed-work restore and warm reuse.
# Test fixture only: run on the original owned worker after production provisioning.
# Arguments: checkpoint, restore, resumed or warm. CP3_ROOT and all original release
# environment inputs must be set. Preserve all valid outputs and propagate failures.
# checkpoint expects the existing one-shot acceptance checkpoint to return 97 only
# after the genuine bwrap partial-cache publication succeeds. restore retains the
# original target tree before an authenticated matching pull into its canonical path;
# any failure keeps both trees for owner recovery. resumed/warm require Cargo zero.
# Write phase logs, actual statuses/elapsed seconds and exact useful-output hashes;
# never delete the retained original target or repair source/dependency inputs.
set -euo pipefail
[[ $# == 1 ]] || exit 2
: "${CP3_ROOT:?Owned original worker root is required}"
: "${CARGO_TARGET_DIR:?Canonical Cargo target directory is required}"
: "${TARGET:?Target is required}"
[[ "$CARGO_TARGET_DIR" == "$CP3_ROOT/upstream/codex-rs/target" ]] || exit 2
[[ "$TARGET" == x86_64-unknown-linux-musl ]] || exit 2
phase="$1"
case "$phase" in checkpoint|restore|resumed|warm) ;; *) exit 2;; esac
cd "$CP3_ROOT/upstream/codex-rs"
evidence="$CP3_ROOT/baseline-evidence"
mkdir -p "$evidence/release-acceptance"
log="$evidence/release-acceptance/$phase.log"
[[ ! -e "$log" ]] || { printf 'Refusing to overwrite phase evidence: %s\n' "$phase" >&2; exit 2; }
started=$SECONDS
if [[ "$phase" == restore ]]; then
  test -e "$CP3_ROOT/checkpoint-failure-observed"
  original="$CP3_ROOT/target-before-authenticated-restore"
  [[ ! -e "$original" && ! -L "$original" ]] || exit 2
  sha256sum "$CARGO_TARGET_DIR/$TARGET/release/bwrap" > "$evidence/release-acceptance/bwrap-before-restore.sha256"
  mv "$CARGO_TARGET_DIR" "$original"
  set +e
  bash "$CP3_ROOT/ghcr-cargo-target-cache.sh" pull-partial "$CARGO_CACHE_REPOSITORY" "$CACHE_TAG" "$CARGO_TARGET_DIR" "$(cat "$evidence/upstream-ref")" > "$log" 2>&1
  status=$?
  set -e
  printf 'phase=%s status=%s elapsed_seconds=%s\n' "$phase" "$status" "$((SECONDS-started))" | tee -a "$evidence/release-acceptance/phase-statuses.log"
  [[ "$status" == 0 ]] || exit "$status"
  sha256sum --check "$evidence/release-acceptance/bwrap-before-restore.sha256" | tee "$evidence/release-acceptance/bwrap-restored-check.log"
  bash "$CP3_ROOT/source-cache-build/runtime/bin/codex-tooling" clean-cached-release-binaries "$CP3_ROOT/upstream/codex-rs" "$TARGET" codex codex-code-mode-host codex-responses-api-proxy codex-app-server bwrap > "$evidence/release-acceptance/restored-source-prepare.log" 2>&1
  exit 0
fi
if [[ "$phase" == checkpoint ]]; then
  test ! -e "$CP3_ROOT/checkpoint-failure-observed"
else
  test -e "$CP3_ROOT/checkpoint-failure-observed"
  test -d "$CP3_ROOT/target-before-authenticated-restore"
fi
before=0
if [[ -f "$evidence/build-stage-timings.log" ]]; then before=$(wc -l < "$evidence/build-stage-timings.log"); fi
set +e
env -u INPUT_UPTERM-VERSION -u INPUT_WAIT-TIMEOUT-MINUTES -u INPUT_LIMIT-ACCESS-TO-ACTOR -u INPUT_KNOWN-HOSTS -u INPUT_LIMIT-ACCESS-TO-USERS -u INPUT_UPTERM-SERVER bash "$CP3_ROOT/build-upstream-release.sh" > "$log" 2>&1
status=$?
set -e
printf 'phase=%s status=%s elapsed_seconds=%s\n' "$phase" "$status" "$((SECONDS-started))" | tee -a "$evidence/release-acceptance/phase-statuses.log"
if [[ -f "$evidence/build-stage-timings.log" ]]; then tail -n "+$((before+1))" "$evidence/build-stage-timings.log" > "$evidence/release-acceptance/$phase-stage-timings.log"; fi
if [[ "$phase" == checkpoint ]]; then
  [[ "$status" == 97 ]] || { [[ "$status" != 0 ]] && exit "$status"; exit 1; }
  test -e "$CP3_ROOT/checkpoint-failure-observed"
  test -s "$evidence/checkpoint-bwrap.log"
  test -x "$CARGO_TARGET_DIR/$TARGET/release/bwrap"
else
  [[ "$status" == 0 ]] || exit "$status"
  for binary in bwrap codex-responses-api-proxy codex-code-mode-host codex codex-app-server; do
    test -x "$CARGO_TARGET_DIR/$TARGET/release/$binary"
    sha256sum "$CARGO_TARGET_DIR/$TARGET/release/$binary"
  done > "$evidence/release-acceptance/$phase-output-sha256.txt"
fi
