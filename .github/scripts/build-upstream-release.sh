#!/usr/bin/env bash
# Build the selected native upstream release bundles with default features.
# Run from codex-rs with TARGET, CARGO_TARGET_DIR and RUNNER_TEMP configured.
# Cargo failures propagate; logs and toolchain identity go to baseline-evidence.
set -euo pipefail

: "${TARGET:?Set the release target}"
: "${CARGO_TARGET_DIR:?Set the owned Cargo output directory}"
: "${RUNNER_TEMP:?Set the owned runner temporary directory}"
evidence="$RUNNER_TEMP/baseline-evidence"
mkdir -p "$evidence"

checkpoint() {
  if [[ -n "${RELEASE_CHECKPOINT_COMMAND:-}" ]]; then
    "$RELEASE_CHECKPOINT_COMMAND" "$1"
  fi
}

case "$TARGET" in
  *-linux-musl)
    started=$SECONDS
    cargo build --target "$TARGET" --release --timings --bin bwrap 2>&1 | tee "$evidence/bwrap-build.log"
    helper="$CARGO_TARGET_DIR/$TARGET/release/bwrap"
    strip --preserve-dates --strip-debug --strip-unneeded "$helper"
    CODEX_BWRAP_SHA256="$(sha256sum "$helper" | cut -d ' ' -f 1)"
    export CODEX_BWRAP_SHA256
    printf 'binary=bwrap build_seconds=%s\n' "$((SECONDS-started))" | tee -a "$evidence/build-stage-timings.log"
    checkpoint bwrap
    ;;
esac

STABLE_GIT_COMMIT="$(git rev-parse HEAD)"
export STABLE_GIT_COMMIT
rustc -vV > "$evidence/rust-toolchain.txt"
cargo --version >> "$evidence/rust-toolchain.txt"
binaries=(codex-responses-api-proxy codex-code-mode-host codex codex-app-server)
case "$TARGET" in
  *-windows-msvc)
    binaries+=(codex-windows-sandbox-setup codex-windows-sandbox-service codex-command-runner)
    ;;
esac

for binary in "${binaries[@]}"; do
  started=$SECONDS
  cargo build --target "$TARGET" --release --timings --bin "$binary" 2>&1 | tee "$evidence/cargo-build-$binary.log"
  printf 'binary=%s build_seconds=%s\n' "$binary" "$((SECONDS-started))" | tee -a "$evidence/build-stage-timings.log"
  checkpoint "$binary"
done
