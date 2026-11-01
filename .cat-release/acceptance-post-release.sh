#!/usr/bin/env bash
# Run unchanged release outcome guards on the owned original Linux worker.
# Test fixture only; argument resumed or warm names immutable acceptance evidence.
# Require original CP3_ROOT/TARGET/cache environment and a successful release phase.
# Verify both documented native CLI variants, pristine fixed upstream and permitted
# Cargo.lock synchronization; record actual source freshness and publish verified
# full output cache. Preserve raw command output and propagate every failure.
# Existing inherited Python lock/source guards are D8 baseline dependencies; this
# fixture does not claim the independently owned D1 Java migration has run.
set -euo pipefail
[[ $# == 1 ]] || exit 2
case "$1" in resumed|warm) phase="$1";; *) exit 2;; esac
: "${CP3_ROOT:?Owned original worker root is required}"
[[ "$CARGO_TARGET_DIR" == "$CP3_ROOT/upstream/codex-rs/target" ]] || exit 2
cd "$CP3_ROOT/upstream/codex-rs"
evidence="$CP3_ROOT/baseline-evidence"
phase_root="$evidence/release-acceptance/$phase-post"
[[ ! -e "$phase_root" ]] || exit 2
mkdir "$phase_root"
output="$CARGO_TARGET_DIR/$TARGET/release"
"$output/codex" --version | tee "$phase_root/codex-version.log"
"$output/codex-app-server" --version | tee "$phase_root/app-server-version.log"
test "$(git rev-parse HEAD)" = dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4
git -C "$GITHUB_WORKSPACE" diff --exit-code HEAD -- . ':(exclude)codex-rs/Cargo.lock' > "$phase_root/source-verification.log"
python3 "$CP3_ROOT/test-verify-cargo-lock.py" "$CP3_ROOT/verify-cargo-lock.py" "$phase_root/lock-guard-fixture" > "$phase_root/lock-guard-test.log" 2>&1
cargo metadata --no-deps --format-version 1 > "$phase_root/workspace-metadata.json"
python3 "$CP3_ROOT/verify-cargo-lock.py" "$evidence/upstream-Cargo.lock" Cargo.lock "$phase_root/workspace-metadata.json" | tee "$phase_root/lock-verification.json"
bash "$CP3_ROOT/source-cache-build/runtime/bin/codex-tooling" clean-cached-release-binaries --record-source-inputs "$GITHUB_WORKSPACE/codex-rs" "$TARGET" > "$phase_root/source-cache-record.log" 2>&1
CARGO_CACHE_STAGE=final bash "$CP3_ROOT/ghcr-cargo-target-cache.sh" push "$CARGO_CACHE_REPOSITORY" "$CACHE_TAG" "$CARGO_TARGET_DIR" "$(cat "$evidence/upstream-ref")" "$CACHE_SEED_TAG" | tee "$phase_root/compiled-cache-save.log"
git -C "$GITHUB_WORKSPACE" status --porcelain=v1 > "$phase_root/final-git-status.log"
if [[ "$phase" == warm ]]; then
  diff -u "$evidence/release-acceptance/resumed-output-sha256.txt" "$evidence/release-acceptance/warm-output-sha256.txt" > "$phase_root/output-identity.log"
fi
printf 'phase=%s guards_smoke_record_cache_status=0\n' "$phase" | tee "$phase_root/status.log"
