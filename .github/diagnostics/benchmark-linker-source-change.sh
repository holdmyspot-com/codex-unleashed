#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?}"
: "${CARGO_TARGET_DIR:?}"
: "${UPSTREAM_TARGET:?}"
test "$UPSTREAM_TARGET" = x86_64-unknown-linux-musl
profile_output="$RUNNER_TEMP/codex-compiler-profile"
source_file=upstream/codex-rs/cli/src/main.rs
test "$(grep -c '^/// Codex CLI$' "$source_file")" = 1
cp "$source_file" "$profile_output/cli-main-before-change.rs"
trap 'cp "$profile_output/cli-main-before-change.rs" "$source_file"' EXIT
sed -i 's/^\/\/\/ Codex CLI$/\/\/\/ Codex CLI module-cache verification/' "$source_file"

compiler_sysroot="$(rustc --print sysroot)"
compiler_host="$(rustc -vV | sed -n 's/^host: //p')"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER="$compiler_sysroot/lib/rustlib/$compiler_host/bin/rust-lld"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS
CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="$(cat "$profile_output/rustflags.txt")"
unset RUSTFLAGS
export CARGO_INCREMENTAL=1 CFLAGS="${CFLAGS:-} -fno-lto" CXXFLAGS="${CXXFLAGS:-} -fno-lto"
export PATCHES_ALREADY_APPLIED=1 SKIP_UPSTREAM_TESTS=1 SKIP_ARTIFACT_COLLECTION=1

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
grep -Fq 'Codex CLI module-cache verification' "$profile_output/help-after-change.txt"
cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$profile_output/changed-cargo-timing.html"
find "$CARGO_TARGET_DIR/lld-thinlto-cache" -type f -name 'llvmcache-*' \
  -printf '%f %i %s %T@\n' | sort > "$profile_output/changed-cache.txt"
test -s "$profile_output/changed-cache.txt"
