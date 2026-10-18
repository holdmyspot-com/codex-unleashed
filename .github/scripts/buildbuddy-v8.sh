#!/usr/bin/env bash
# Builds and stages Linux V8 release artifacts; native GNU smoke tests run on matching hosts.
set -euo pipefail

case "$BUILDBUDDY_CI_TARGET" in
  x86_64-unknown-linux-gnu) platform=linux_amd64; v8_cpu=x64 ;;
  x86_64-unknown-linux-musl) platform=linux_amd64_musl; v8_cpu=x64 ;;
  aarch64-unknown-linux-gnu) platform=linux_arm64; v8_cpu=arm64 ;;
  aarch64-unknown-linux-musl) platform=linux_arm64_musl; v8_cpu=arm64 ;;
  *) printf 'Unsupported remote V8 target: %s\n' "$BUILDBUDDY_CI_TARGET" >&2; exit 2 ;;
esac
pair_kind=release_pair
stage_flags=(--bazel-config v8-release-compat)
build_flags=(--config=v8-release-compat)
cargo_flags=()
if [[ "$BUILDBUDDY_CI_TASK" == v8-sandbox* ]]; then
  pair_kind=sandbox_release_pair
  stage_flags=(--sandbox)
  build_flags=()
  cargo_flags=(--features sandbox)
fi
if [[ "$BUILDBUDDY_CI_TASK" == *-opt ]]; then
  build_flags+=(-c opt)
  stage_flags+=(--compilation-mode opt)
fi

cd upstream
pair_target="//third_party/v8:rusty_v8_${pair_kind}_${BUILDBUDDY_CI_TARGET//-/_}"
"$CODEX_UNLEASHED_TOOLING" run-bazel-with-buildbuddy --noexperimental_remote_repo_contents_cache build \
  "--platforms=@llvm//platforms:$platform" --config=rusty-v8-upstream-libcxx \
  "--config=v8-target-$v8_cpu" "$pair_target" "--build_metadata=COMMIT_SHA=$(git rev-parse HEAD)" \
  "${build_flags[@]}" --config=ci-v8 --remote_download_outputs=toplevel
stage_directory="${BUILDBUDDY_ARTIFACTS_DIRECTORY:?}/v8-dist"
"$CODEX_UNLEASHED_TOOLING" rusty-v8-bazel . stage-release-pair \
  --platform "$platform" --target "$BUILDBUDDY_CI_TARGET" --output-dir "$stage_directory" \
  --bazel-config ci-v8 --bazel-config "v8-target-$v8_cpu" "${stage_flags[@]}"

if [[ "$BUILDBUDDY_CI_TARGET" == x86_64-unknown-linux-gnu ]]; then
  archive="$(find "$stage_directory" -maxdepth 1 -type f -name 'librusty_v8_*.a.gz' -print -quit)"
  binding="$(find "$stage_directory" -maxdepth 1 -type f -name 'src_binding_*.rs' -print -quit)"
  if [[ -z "$archive" || -z "$binding" ]]; then
    printf 'Missing staged archive or binding for %s.\n' "$BUILDBUDDY_CI_TARGET" >&2
    exit 1
  fi
  smoke_target="$(mktemp -d "$RUNNER_TEMP/v8-cargo-smoke-XXXXXX")"
  (
    trap 'rm -rf -- "$smoke_target"' EXIT
    cd codex-rs
    CARGO_TARGET_DIR="$smoke_target" RUSTUP_TOOLCHAIN=stable RUSTY_V8_ARCHIVE="$archive" \
      RUSTY_V8_SRC_BINDING_PATH="$binding" cargo test -p codex-v8-poc "${cargo_flags[@]}"
  )
fi
