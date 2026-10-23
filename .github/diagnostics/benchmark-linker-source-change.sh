#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?}"
: "${CARGO_TARGET_DIR:?}"
: "${UPSTREAM_TARGET:?}"
: "${CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER:?}"
: "${CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS:?}"
test "$UPSTREAM_TARGET" = x86_64-unknown-linux-musl
profile_output="$RUNNER_TEMP/codex-compiler-profile"
source_file=upstream/codex-rs/cli/src/main.rs
test "$(grep -Fc '/// Show Codex Unleashed build information.' "$source_file")" = 1
cp "$source_file" "$profile_output/cli-main-before-change.rs"
trap 'cp "$profile_output/cli-main-before-change.rs" "$source_file"' EXIT
sed -i 's/Show Codex Unleashed build information\./Show Codex Unleashed build information (module-cache verification)./' "$source_file"
grep -Fq '/// Show Codex Unleashed build information (module-cache verification).' "$source_file"

"$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --help > "$profile_output/help-before-change.txt"
date +%s.%N > "$profile_output/changed-start.txt"
printf '%s: rebuilding after CLI help-text change\n' "$(date -u +%FT%TZ)"
/usr/bin/time -v -o "$profile_output/changed-resources.txt" \
  bash .github/scripts/run-memory-scheduled-build.sh ./scripts/build-release.sh upstream \
    "$RUNNER_TEMP/linker-profile-build-output" 2>&1 | tee "$profile_output/changed.log" | \
    awk '/Heartbeat:|Finished|Compiling|error:|warning:|^Running|^Skipping/ { print; fflush() }' || {
  cat "$profile_output/changed.log" >&2
  exit 1
}
date +%s.%N > "$profile_output/changed-end.txt"
"$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --help > "$profile_output/help-after-change.txt"
grep -Fq '(module-cache verification)' "$profile_output/help-after-change.txt"
cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$profile_output/changed-cargo-timing.html"
find "$CARGO_TARGET_DIR/lld-thinlto-cache" -type f -name 'llvmcache-*' \
  -printf '%f %i %s %T@\n' | sort > "$profile_output/changed-cache.txt"
test -s "$profile_output/changed-cache.txt"
comm -12 "$profile_output/warm-cache.txt" "$profile_output/changed-cache.txt" \
  > "$profile_output/unchanged-cache-entries.txt"
comm -13 "$profile_output/warm-cache.txt" "$profile_output/changed-cache.txt" \
  > "$profile_output/rebuilt-cache-entries.txt"
test -s "$profile_output/unchanged-cache-entries.txt"
test -s "$profile_output/rebuilt-cache-entries.txt"
wc -l "$profile_output/unchanged-cache-entries.txt" "$profile_output/rebuilt-cache-entries.txt"
