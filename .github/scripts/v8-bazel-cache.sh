#!/usr/bin/env bash
set -euo pipefail

# Shares the compiled V8 actions of a Bazel build through GHCR. Upstream builds V8 from source in Bazel and hides the cost
# behind its BuildBuddy cache, which this repository does not have. A seed run builds only the V8 targets into an empty
# disk cache and saves it; consumer runs restore it and read from it without writing back.
#
# usage: v8-bazel-cache.sh <restore|save> <source-directory> <cache-directory> <repository> <upstream-ref>
#   restore  pulls the cache for the V8 inputs of <source-directory> into <cache-directory>, then appends
#            user.bazelrc to <source-directory> so every Bazel command reads that cache and uploads nothing
#   save     pushes <cache-directory> under the same tag
# The tag is cargo-v3-bazel-v8-<runner arch>-<sha256 of the Bazel version, the runner, and the committed MODULE.bazel,
# MODULE.bazel.lock, .bazelrc and third_party/v8>. The inputs are read from the HEAD commit, not the working tree, so a
# seed that saves after its build and a consumer that restores before its build compute the same tag even if Bazel has
# rewritten a tracked file in between (the original seed hashed the working tree after building and produced a tag that
# no consumer ever computed). Save refuses a cache directory under 100 MiB: that is a build that ran no V8 action (the
# original seed saved 8 KiB).
# GITHUB_TOKEN authenticates to ghcr.io; GITHUB_OUTPUT receives hit=<true|false> from restore and tag= from both.
# Exit status: 0 on success, including a restore miss; 2 for invalid arguments; 4 when save finds a near-empty cache;
# otherwise the failing tool's status.
if [[ $# != 5 ]]; then
  printf 'usage: %s <restore|save> <source-directory> <cache-directory> <repository> <upstream-ref>\n' "$0" >&2
  exit 2
fi
operation="$1"
source_directory="$2"
cache_directory="$3"
repository="$4"
upstream_ref="$5"
cache_script="$(dirname "${BASH_SOURCE[0]}")/ghcr-cargo-target-cache.sh"

digest="$(
  cd "$source_directory"
  {
    cat .bazelversion
    printf '%s\n' "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:-}"
    git rev-parse HEAD:MODULE.bazel HEAD:MODULE.bazel.lock HEAD:.bazelrc
    git ls-tree -r HEAD third_party/v8
  } | sha256sum | cut -d' ' -f1
)"
tag="cargo-v3-bazel-v8-${RUNNER_ARCH,,}-$digest"
printf 'tag=%s\n' "$tag" >> "$GITHUB_OUTPUT"
printf '%s' "$GITHUB_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin

case "$operation" in
  restore)
    mkdir -p "$cache_directory"
    set +e
    bash "$cache_script" pull "$repository" "$tag" "$cache_directory" "$upstream_ref"
    status=$?
    set -e
    case "$status" in
      0) printf 'hit=true\n' >> "$GITHUB_OUTPUT" ;;
      3) printf 'hit=false\n' >> "$GITHUB_OUTPUT" ;;
      *) exit "$status" ;;
    esac
    # The CI configuration empties --disk_cache, so name the directory for both the default and the ci configuration.
    {
      printf 'common --disk_cache=%s\n' "$cache_directory"
      printf 'common:ci --disk_cache=%s\n' "$cache_directory"
      printf 'common --remote_upload_local_results=false\n'
    } >> "$source_directory/user.bazelrc"
    du -sh "$cache_directory"
    ;;
  save)
    size_kib="$(du -sk "$cache_directory" | cut -f1)"
    if (( size_kib < 102400 )); then
      printf 'Refusing to save a %s KiB cache: the build wrote no substantial V8 actions into %s\n' "$size_kib" "$cache_directory" >&2
      exit 4
    fi
    bash "$cache_script" push "$repository" "$tag" "$cache_directory" "$upstream_ref"
    ;;
  *)
    printf 'Unknown operation %s\n' "$operation" >&2
    exit 2
    ;;
esac
