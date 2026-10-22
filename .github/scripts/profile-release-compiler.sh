#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?}"
: "${CODEX_UNLEASHED_TOOLING:?}"
: "${UPSTREAM_TARGET:?}"
if [[ "$UPSTREAM_TARGET" != x86_64-unknown-linux-musl ]]; then
  echo 'This diagnostic requires the Linux x64 musl release target.' >&2
  exit 2
fi

export PROFILE_OUTPUT="$RUNNER_TEMP/codex-compiler-profile"
mkdir -p "$PROFILE_OUTPUT"
export PATCHES_ALREADY_APPLIED=1 SKIP_UPSTREAM_TESTS=1 SKIP_ARTIFACT_COLLECTION=1
export CARGO_INCREMENTAL=1
build=(bash .github/scripts/run-memory-scheduled-build.sh ./scripts/build-release.sh upstream \
  "$RUNNER_TEMP/compiler-profile-build-output")
date +%s.%N > "$PROFILE_OUTPUT/seed-start.txt"
printf '%s: starting seed build\n' "$(date -u +%FT%TZ)"
"${build[@]}" 2>&1 | tee "$PROFILE_OUTPUT/seed.log" || {
  cat "$PROFILE_OUTPUT/seed.log" >&2
  exit 1
}
date +%s.%N > "$PROFILE_OUTPUT/seed-end.txt"
printf '%s: completed seed build\n' "$(date -u +%FT%TZ)"
cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$PROFILE_OUTPUT/seed-cargo-timing.html"
"$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --version > "$PROFILE_OUTPUT/seed-version.txt"
test -n "$(find "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/incremental" -type f -print -quit)"
if [[ "${PROFILE_SEED_CACHE_ENABLED:-false}" == true && "${PROFILE_SEED_CACHE_HIT:-false}" != true ]]; then
  printf '%s: saving seed build to GHCR\n' "$(date -u +%FT%TZ)"
  bash .github/scripts/ghcr-cargo-target-cache.sh push \
    "${PROFILE_SEED_CACHE_REPOSITORY:?}" "${PROFILE_SEED_CACHE_TAG:?}" \
    "$CARGO_TARGET_DIR" "${PROFILE_UPSTREAM_TAG:?}" 2>&1 | tee "$PROFILE_OUTPUT/seed-cache-save.log" || {
    cat "$PROFILE_OUTPUT/seed-cache-save.log" >&2
    exit 1
  }
fi

PROFILE_REAL_RUSTC="$(command -v rustc)"
export PROFILE_REAL_RUSTC
export PROFILE_REAL_LINKER="${CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER:?}"
export PROFILE_LINKER_WRAPPER="$GITHUB_WORKSPACE/.github/diagnostics/profile-release-linker.sh"
export RUSTC="$GITHUB_WORKSPACE/.github/diagnostics/profile-release-rustc.sh"
chmod +x "$RUSTC" "$PROFILE_LINKER_WRAPPER"
"$PROFILE_REAL_RUSTC" -vV > "$PROFILE_OUTPUT/compiler-version.txt"
"$PROFILE_REAL_LINKER" --version > "$PROFILE_OUTPUT/linker-driver-version.txt"
touch upstream/codex-rs/cli/src/main.rs
printf '%s: starting instrumented compiler and linker rebuild\n' "$(date -u +%FT%TZ)"
"${build[@]}" 2>&1 | tee "$PROFILE_OUTPUT/build.log" || {
  cat "$PROFILE_OUTPUT/build.log" >&2
  exit 1
}
printf '%s: completed instrumented compiler and linker rebuild\n' "$(date -u +%FT%TZ)"
cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$PROFILE_OUTPUT/cargo-timing.html"
"$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --version > "$PROFILE_OUTPUT/binary-version.txt"
test -n "$(find "$PROFILE_OUTPUT" -path '*/linker.*/resources.txt' -print -quit)"
grep -Rq 'LLVM_thinlto' "$PROFILE_OUTPUT"
grep -Rq 'run_linker' "$PROFILE_OUTPUT"
