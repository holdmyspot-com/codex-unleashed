#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" != 1 ]]; then
  printf 'Usage: %s <prepared-upstream-root>\n' "$0" >&2
  exit 2
fi
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
upstream_root="$(cd "$1" && pwd)"
package_cache="${CI_BUILD_ROOT:?CI_BUILD_ROOT is required}/argument-comment-lint"
tool_root="$package_cache/tools-6.1.0"
toolchain=nightly-2026-08-20

# Upstream preparation removes the shared native toolchain and registry; package checks retain their own downloads.
export CARGO_HOME="$package_cache/cargo"
export RUSTUP_HOME="$package_cache/rustup"
export CARGO_TARGET_DIR="$package_cache/target"
export DYLINT_DRIVER_PATH="$package_cache/drivers"
export RUSTUP_TOOLCHAIN="$toolchain"
export RUST_MIN_STACK=8388608
export PYTHONPYCACHEPREFIX="${RUNNER_TEMP:?RUNNER_TEMP is required}/argument-comment-lint-pycache"
export PATH="$tool_root/bin:$PATH"
mkdir -p "$CARGO_HOME" "$RUSTUP_HOME" "$DYLINT_DRIVER_PATH" "$PYTHONPYCACHEPREFIX"

rustup toolchain install "$toolchain" \
  --profile minimal \
  --component llvm-tools-preview \
  --component rustc-dev \
  --component rust-src \
  --no-self-update
for package in cargo-dylint dylint-link; do
  if [[ ! -x "$tool_root/bin/$package" ]]; then
    bash "$project_root/.github/scripts/run-memory-scheduled-build.sh" \
      cargo "+$toolchain" install --locked "$package" --version 6.1.0 --root "$tool_root"
  fi
done

cd "$upstream_root"
python3 -m py_compile tools/argument-comment-lint/wrapper_common.py tools/argument-comment-lint/run.py \
  tools/argument-comment-lint/run-prebuilt-linter.py tools/argument-comment-lint/test_wrapper_common.py
python3 -m unittest discover -s tools/argument-comment-lint -p 'test_*.py'

cd tools/argument-comment-lint
bash "$project_root/.github/scripts/run-memory-scheduled-build.sh" cargo "+$toolchain" test
