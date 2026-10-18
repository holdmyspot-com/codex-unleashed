#!/usr/bin/env bash
# Keeps patched sources at a stable path so unchanged inputs retain Bazel analysis state.
set -euo pipefail

upstream_ref="${UPSTREAM_REF:-$(cat .github/upstream-ref)}"
upstream="$CI_BUILD_ROOT/upstream"
if [[ ! -d "$upstream/.git" ]]; then
  git init --quiet --initial-branch=main "$upstream"
  git -C "$upstream" remote add origin https://github.com/openai/codex.git
fi
git -C "$upstream" fetch --depth 1 origin "$upstream_ref"
upstream_commit="$(git -C "$upstream" rev-parse 'FETCH_HEAD^{commit}')"
patch_identity="$(git ls-files -z patches scripts/apply-patches.sh | sort -z | xargs -0 sha256sum | sha256sum | cut -d ' ' -f 1)"
source_identity="$upstream_commit:$patch_identity"
source_marker="$CI_BUILD_ROOT/upstream-prepared"
if [[ ! -f "$source_marker" ]] || [[ "$(cat "$source_marker")" != "$source_identity" ]]; then
  rm -f -- "$source_marker"
  git -C "$upstream" reset --hard "$upstream_commit"
  git -C "$upstream" clean -ffdx
  bash scripts/apply-patches.sh "$upstream"
  (cd "$upstream/codex-rs" && rustup toolchain install --no-update --no-self-update && cargo metadata --format-version 1 > /dev/null)
  printf '%s\n' "$source_identity" > "$source_marker"
  printf 'Patched upstream: prepared %s.\n' "$source_identity"
else
  printf 'Patched upstream: reusing %s.\n' "$source_identity"
fi
ln -s "$upstream" upstream
export CARGO_TARGET_DIR="$CI_BUILD_ROOT/cargo-target"
export GITHUB_WORKSPACE="$project_root"
export RUNNER_OS=Linux
export GITHUB_JOB="$BUILDBUDDY_CI_TASK"
export BAZEL_OUTPUT_USER_ROOT="$CI_BUILD_ROOT/b"
export BAZEL_REPOSITORY_CACHE="$CI_BUILD_ROOT/bazel-repository-cache"
export BAZEL_DISK_CACHE="$CI_BUILD_ROOT/bazel-disk-cache"
export BAZEL_DISK_CACHE_MAX_SIZE=768M
export BAZEL_DISK_CACHE_MAX_AGE=14d
export BAZEL_REPO_CONTENTS_CACHE="$CI_BUILD_ROOT/bazel-repo-contents-cache"
export CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR="${BUILDBUDDY_ARTIFACTS_DIRECTORY:?}/bazel-execution-logs"
mkdir -p "$CODEX_BAZEL_EXECUTION_LOG_COMPACT_DIR"
