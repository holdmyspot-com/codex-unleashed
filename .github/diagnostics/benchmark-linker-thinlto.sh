#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?}"
: "${CARGO_TARGET_DIR:?}"
: "${UPSTREAM_TARGET:?}"
test "$UPSTREAM_TARGET" = x86_64-unknown-linux-musl

profile_output="$RUNNER_TEMP/codex-compiler-profile"
mkdir -p "$profile_output" "$CARGO_TARGET_DIR/lld-thinlto-cache"
compiler_sysroot="$(rustc --print sysroot)"
compiler_host="$(rustc -vV | sed -n 's/^host: //p')"
bundled_linker="$compiler_sysroot/lib/rustlib/$compiler_host/bin/rust-lld"
test -x "$bundled_linker"
"$bundled_linker" -flavor gnu --version > "$profile_output/linker-version.txt"
rustc -vV > "$profile_output/compiler-version.txt"

export PATCHES_ALREADY_APPLIED=1 SKIP_UPSTREAM_TESTS=1 SKIP_ARTIFACT_COLLECTION=1
export CARGO_INCREMENTAL=1
export CFLAGS="${CFLAGS:-} -fno-lto"
export CXXFLAGS="${CXXFLAGS:-} -fno-lto"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER="$bundled_linker"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="${RUSTFLAGS:-} -Clinker-plugin-lto -Clinker-flavor=ld.lld -Clink-self-contained=yes -Clink-arg=--lto-O3 -Clink-arg=--thinlto-cache-dir=$CARGO_TARGET_DIR/lld-thinlto-cache -Clink-arg=--time-trace=$profile_output/linker-time-trace.json -Clink-arg=--time-trace-granularity=1000"
unset RUSTFLAGS
printf '%s\n' "$CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS" > "$profile_output/rustflags.txt"
build=(bash .github/scripts/run-memory-scheduled-build.sh ./scripts/build-release.sh upstream
  "$RUNNER_TEMP/linker-profile-build-output")

for phase in seed warm; do
  touch upstream/codex-rs/cli/src/main.rs

  date +%s.%N > "$profile_output/$phase-start.txt"
  rm -f "$profile_output/linker-time-trace.json"
  printf '%s: starting %s build with linker-managed ThinLTO\n' "$(date -u +%FT%TZ)" "$phase"
  /usr/bin/time -v -o "$profile_output/$phase-resources.txt" "${build[@]}" \
    2>&1 | tee "$profile_output/$phase.log" || {
    cat "$profile_output/$phase.log" >&2
    exit 1
  }
  date +%s.%N > "$profile_output/$phase-end.txt"
  test -s "$profile_output/linker-time-trace.json"
  cp "$profile_output/linker-time-trace.json" "$profile_output/$phase-linker-time-trace.json"
  printf '%s: completed %s build\n' "$(date -u +%FT%TZ)" "$phase"
  cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$profile_output/$phase-cargo-timing.html"
  "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --version > "$profile_output/$phase-version.txt"
  stat --printf='%s\n' "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" > "$profile_output/$phase-binary-size.txt"
  find "$CARGO_TARGET_DIR/lld-thinlto-cache" -type f -name 'llvmcache-*' \
    -printf '%f %i %s %T@\n' | sort > "$profile_output/$phase-cache.txt"
  test -s "$profile_output/$phase-cache.txt"

  if [[ "$phase" == seed && "${PROFILE_SEED_CACHE_ENABLED:-false}" == true && "${PROFILE_SEED_CACHE_HIT:-false}" != true ]]; then
    printf '%s: saving seed build and module cache to GHCR\n' "$(date -u +%FT%TZ)"
    bash .github/scripts/ghcr-cargo-target-cache.sh push \
      "${PROFILE_SEED_CACHE_REPOSITORY:?}" "${PROFILE_SEED_CACHE_TAG:?}" \
      "$CARGO_TARGET_DIR" "${PROFILE_UPSTREAM_TAG:?}" 2>&1 | tee "$profile_output/seed-cache-save.log" || {
      cat "$profile_output/seed-cache-save.log" >&2
      exit 1
    }
  fi
done

cmp "$profile_output/seed-version.txt" "$profile_output/warm-version.txt"
du -sk "$CARGO_TARGET_DIR/lld-thinlto-cache" > "$profile_output/linker-cache-size-kib.txt"
bash .github/diagnostics/benchmark-linker-source-change.sh
