#!/usr/bin/env bash
# Rebuilds the changed CLI with four ThinLTO workers using the preceding source-change benchmark's starting cache.
# Requires its warm/changed cache inventories, changed executable, inherited release-build environment, and source.
# Writes worker4 timing, trace, cache, and binary-identity evidence under RUNNER_TEMP/codex-compiler-profile.
# Exits nonzero on a failed build, an incomplete cache restoration, or different executable bytes.
set -euo pipefail

: "${RUNNER_TEMP:?}"
: "${CARGO_TARGET_DIR:?}"
: "${UPSTREAM_TARGET:?}"
test "$UPSTREAM_TARGET" = x86_64-unknown-linux-musl

export PROFILE_OUTPUT="$RUNNER_TEMP/codex-compiler-profile"
module_cache="$CARGO_TARGET_DIR/lld-thinlto-cache"
binary="$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex"
source_file=upstream/codex-rs/cli/src/main.rs

# Cache entries are immutable; discard only names added by the changed build, retaining the original warm set.
awk '{print $1}' "$PROFILE_OUTPUT/warm-cache.txt" | LC_ALL=C sort > "$PROFILE_OUTPUT/worker4-starting-cache-names.txt"
awk '{print $1}' "$PROFILE_OUTPUT/changed-cache.txt" | LC_ALL=C sort > "$PROFILE_OUTPUT/worker4-changed-cache-names.txt"
LC_ALL=C comm -13 "$PROFILE_OUTPUT/worker4-starting-cache-names.txt" \
  "$PROFILE_OUTPUT/worker4-changed-cache-names.txt" > "$PROFILE_OUTPUT/worker4-discarded-cache-names.txt"
while IFS= read -r cache_entry; do
  [[ "$cache_entry" =~ ^llvmcache-[0-9A-Fa-f]+$ ]]
  rm -- "$module_cache/$cache_entry"
done < "$PROFILE_OUTPUT/worker4-discarded-cache-names.txt"
find "$module_cache" -type f -name 'llvmcache-*' -printf '%f\n' | LC_ALL=C sort \
  > "$PROFILE_OUTPUT/worker4-restored-cache-names.txt"
cmp "$PROFILE_OUTPUT/worker4-starting-cache-names.txt" "$PROFILE_OUTPUT/worker4-restored-cache-names.txt"

sha256sum "$binary" | cut -d ' ' -f 1 > "$PROFILE_OUTPUT/worker4-before-binary-sha256.txt"
sha256sum "$source_file" > "$PROFILE_OUTPUT/worker4-before-source-sha256.txt"
PROFILE_REAL_RUSTC="$(command -v rustc)"
export PROFILE_REAL_RUSTC
export RUSTC="$GITHUB_WORKSPACE/.github/diagnostics/benchmark-linker-worker-rustc.sh"
chmod +x "$RUSTC"

# Only the final executable receives a scheduling option; dependencies keep the same compiler arguments and outputs.
touch "$source_file"
rm -f "$PROFILE_OUTPUT/linker-time-trace.json"
date +%s.%N > "$PROFILE_OUTPUT/worker4-start.txt"
printf '%s: rebuilding the same help-text change with four ThinLTO workers\n' "$(date -u +%FT%TZ)"
/usr/bin/time -v -o "$PROFILE_OUTPUT/worker4-resources.txt" \
  bash .github/scripts/run-memory-scheduled-build.sh ./scripts/build-release.sh upstream \
    "$RUNNER_TEMP/linker-profile-build-output" 2>&1 | tee "$PROFILE_OUTPUT/worker4.log"
date +%s.%N > "$PROFILE_OUTPUT/worker4-end.txt"
test -s "$PROFILE_OUTPUT/worker4-linker-option.txt"
test -s "$PROFILE_OUTPUT/linker-time-trace.json"
cp "$PROFILE_OUTPUT/linker-time-trace.json" "$PROFILE_OUTPUT/worker4-linker-time-trace.json"
cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$PROFILE_OUTPUT/worker4-cargo-timing.html"
find "$module_cache" -type f -name 'llvmcache-*' -printf '%f %i %s %T@\n' | LC_ALL=C sort \
  > "$PROFILE_OUTPUT/worker4-cache.txt"

sha256sum "$binary" | cut -d ' ' -f 1 > "$PROFILE_OUTPUT/worker4-after-binary-sha256.txt"
sha256sum "$source_file" > "$PROFILE_OUTPUT/worker4-after-source-sha256.txt"
cmp "$PROFILE_OUTPUT/worker4-before-source-sha256.txt" "$PROFILE_OUTPUT/worker4-after-source-sha256.txt"
cmp "$PROFILE_OUTPUT/worker4-before-binary-sha256.txt" "$PROFILE_OUTPUT/worker4-after-binary-sha256.txt"
printf '%s: four-worker rebuild preserves the changed executable byte for byte\n' "$(date -u +%FT%TZ)"
