#!/usr/bin/env bash
set -euo pipefail

# Keeps the compiled C++ actions of a Bazel build, chiefly V8, in a Bazel disk cache stored in GHCR. Upstream hides the
# cost of compiling V8 from source behind its BuildBuddy cache, which this repository does not have, so every Bazel lint
# job compiled V8 twice (for the target and the exec configuration), about three hours on a macOS runner. The whole disk
# cache of a lint job would be 34-110 GB, so the build marks every action except C++ compiles, archives and links
# `no-cache` (see the workflow) and only those reach this cache.
#
# usage: bazel-cc-disk-cache.sh <restore|save> <source-directory> <cache-directory> <repository> <upstream-ref> <lane>
#   restore  pulls the cache for <lane> into <cache-directory>: the exact tag for the C++ inputs of <source-directory>,
#            otherwise the lane's most recent save. A miss or a failed pull leaves an empty directory and the build
#            runs cold.
#   save     pushes <cache-directory> under the exact tag, also naming it the lane's most recent save. It skips the
#            upload when restore found the exact tag and the build added nothing to the cache.
# Bazel checks every cache entry by the digest of its action and inputs, so a stale entry is never used, only unread.
# The exact tag hashes the platform (Bazel version, runner OS, architecture and image; on macOS and Windows also the
# image version, because their C++ toolchains are the image's own compilers, which Bazel does not hash), the lane, and
# the committed MODULE.bazel, MODULE.bazel.lock, .bazelrc and third_party tree. The fallback tag hashes the platform and
# lane only. Inputs are read from the HEAD commit, so restore and save compute the same tags.
# GITHUB_TOKEN authenticates to ghcr.io; GITHUB_OUTPUT receives hit=<exact|recent|miss> from restore.
# Exit status: 0 on success, including a miss or a skipped save; 2 for invalid arguments; otherwise the failing tool's.
if [[ $# != 6 ]]; then
  printf 'usage: %s <restore|save> <source-directory> <cache-directory> <repository> <upstream-ref> <lane>\n' "$0" >&2
  exit 2
fi
operation="$1"
source_directory="$2"
cache_directory="$3"
repository="$4"
upstream_ref="$5"
lane="$6"
if [[ ! "$lane" =~ ^[A-Za-z0-9_-]{1,40}$ ]]; then
  printf 'Lane must be 1-40 characters of [A-Za-z0-9_-]: %s\n' "$lane" >&2
  exit 2
fi
cache_script="$(dirname "${BASH_SOURCE[0]}")/ghcr-cargo-target-cache.sh"
restored_marker="$cache_directory.restored"
cache_target="bazel-cc-$lane"

platform="$(
  cat "$source_directory/.bazelversion"
  printf '%s\n' "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:-}" "$lane"
  if [[ "$RUNNER_OS" != Linux ]]; then
    printf '%s\n' "${ImageVersion:-}"
  fi
)"
recent_digest="$(printf '%s' "$platform" | sha256sum | cut -d' ' -f1)"
exact_digest="$(
  cd "$source_directory"
  {
    printf '%s\n' "$platform"
    git rev-parse HEAD:MODULE.bazel HEAD:MODULE.bazel.lock HEAD:.bazelrc
    git ls-tree -r HEAD third_party
  } | sha256sum | cut -d' ' -f1
)"
exact_tag="cargo-v3-$cache_target-$exact_digest"
recent_tag="cargo-seed-v2-$cache_target-$recent_digest"
printf '%s' "$GITHUB_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin

case "$operation" in
  restore)
    rm -rf -- "${cache_directory:?}" "$restored_marker"
    mkdir -p "$cache_directory"
    hit=miss
    for candidate in "$exact_tag:exact" "$recent_tag:recent"; do
      set +e
      bash "$cache_script" pull "$repository" "${candidate%:*}" "$cache_directory" "$upstream_ref"
      status=$?
      set -e
      if [[ "$status" == 0 ]]; then
        hit="${candidate##*:}"
        break
      fi
      # A failed pull can leave part of an archive behind; start the next candidate, or the cold build, empty.
      rm -rf -- "${cache_directory:?}"
      mkdir -p "$cache_directory"
      if [[ "$status" != 3 ]]; then
        printf '::warning::Pulling the C++ action cache %s failed with status %s; trying the next candidate\n' "${candidate%:*}" "$status"
      fi
    done
    printf '%s\n' "$hit" > "$restored_marker"
    printf 'hit=%s\n' "$hit" >> "$GITHUB_OUTPUT"
    printf 'C++ action cache for %s: %s, %s\n' "$lane" "$hit" "$(du -sh "$cache_directory" | cut -f1)"
    ;;
  save)
    new_entries="$(find "$cache_directory" -type f -newer "$restored_marker" | wc -l | tr -d ' ')"
    printf 'C++ action cache for %s: %s new entries, %s in all\n' "$lane" "$new_entries" "$(du -sh "$cache_directory" | cut -f1)"
    if [[ "$(cat "$restored_marker")" == exact && "$new_entries" == 0 ]]; then
      printf 'The exact cache was restored and the build added nothing; skipping the upload\n'
      exit 0
    fi
    bash "$cache_script" push "$repository" "$exact_tag" "$cache_directory" "$upstream_ref" "$recent_tag"
    ;;
  *)
    printf 'Unknown operation %s\n' "$operation" >&2
    exit 2
    ;;
esac
