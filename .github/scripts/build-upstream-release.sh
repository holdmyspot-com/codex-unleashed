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

case "$TARGET" in
  *-linux-musl)
    cargo build --target "$TARGET" --release --timings --bin bwrap 2>&1 | tee "$evidence/bwrap-build.log"
    helper="$CARGO_TARGET_DIR/$TARGET/release/bwrap"
    strip --strip-debug --strip-unneeded "$helper"
    CODEX_BWRAP_SHA256="$(sha256sum "$helper" | cut -d ' ' -f 1)"
    export CODEX_BWRAP_SHA256
    ;;
esac

STABLE_GIT_COMMIT="$(git rev-parse HEAD)"
export STABLE_GIT_COMMIT
rustc -vV > "$evidence/rust-toolchain.txt"
cargo --version >> "$evidence/rust-toolchain.txt"
bundles=(primary app-server)
case "$TARGET" in
  *-windows-msvc)
    bundles+=(helpers)
    ;;
esac

for bundle in "${bundles[@]}"; do
  case "$bundle" in
    primary)
      build_args=(--bin codex --bin codex-code-mode-host --bin codex-responses-api-proxy)
      ;;
    app-server)
      build_args=(--bin codex-app-server --bin codex-code-mode-host)
      ;;
    helpers)
      build_args=(--bin codex-windows-sandbox-setup --bin codex-windows-sandbox-service --bin codex-command-runner)
      ;;
  esac
  cargo build --target "$TARGET" --release --timings "${build_args[@]}" 2>&1 | tee "$evidence/cargo-build-$bundle.log"
done
