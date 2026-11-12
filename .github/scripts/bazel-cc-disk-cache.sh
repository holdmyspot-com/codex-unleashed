#!/usr/bin/env bash
set -euo pipefail

# Keeps the compiled C++ actions of a Bazel build, chiefly V8, in a Bazel disk cache stored in GHCR. Upstream hides the
# cost of compiling V8 from source behind its BuildBuddy cache, which this repository does not have, so every Bazel lint
# job compiled V8 twice (for the target and the exec configuration), about three hours on a macOS runner. The whole disk
# cache of a lint job would be 34-110 GB, so the build marks every action except C++ compiles, archives and links
# `no-cache` (see the workflow) and only those reach this cache.
#
# usage: bazel-cc-disk-cache.sh <restore|save|exists> <source-directory> <cache-directory> <repository> <upstream-ref>
#                               <lane> [shared-lane]
#   restore  pulls the cache for <lane> into <cache-directory>: the exact tag for the C++ inputs of <source-directory>,
#            otherwise the lane's most recent save. With <shared-lane>, it also pulls that lane the same way and adds
#            its entries, so one disk cache serves both. A miss or a failed pull of either lane leaves that lane's
#            entries out, and the build compiles them.
#   save     pushes <cache-directory> under the exact tag, also naming it the lane's most recent save. Entries that
#            restore took from <shared-lane> are removed from <cache-directory> first, because that lane already holds
#            them. It skips the upload when restore found the exact tag and the build added nothing to the cache.
#   exists   reports whether the lane's exact tag is in GHCR; <cache-directory> is not used.
# Bazel checks every cache entry by the digest of its action and inputs, so a stale entry is never used, only unread.
# The exact tag hashes the platform (Bazel version, runner OS, architecture and image, the lane, and on macOS and Windows
# the identity of the image's own C++ compiler and SDK, which Bazel does not hash), the committed root files
# (MODULE.bazel, MODULE.bazel.lock, .bazelrc, defs.bzl and the rest), and the third_party and patches trees. The fallback
# tag hashes the platform only. Inputs are read from the HEAD commit, so restore and save compute the same tags.
# On Windows, VCToolsInstallDir, WindowsSDKVersion and UCRTVersion must name the MSVC tools and SDK that the build uses.
# GITHUB_TOKEN authenticates to ghcr.io. GITHUB_OUTPUT receives hit=<exact|recent|miss> for <lane> from restore and
# exists=<true|false> from exists; exists reports false, with a warning, when GHCR cannot answer.
# Exit status: 0 on success, including a miss or a skipped save; 2 for invalid arguments or a missing Windows compiler
# identity; otherwise the failing tool's.
if [[ $# != 6 && $# != 7 ]]; then
  printf 'usage: %s <restore|save|exists> <source-directory> <cache-directory> <repository> <upstream-ref> <lane> [shared-lane]\n' \
    "$0" >&2
  exit 2
fi
operation="$1"
source_directory="$2"
cache_directory="$3"
repository="$4"
upstream_ref="$5"
lane="$6"
shared_lane="${7:-}"
for name in "$lane" ${shared_lane:+"$shared_lane"}; do
  if [[ ! "$name" =~ ^[A-Za-z0-9_-]{1,40}$ ]]; then
    printf 'Lane must be 1-40 characters of [A-Za-z0-9_-]: %s\n' "$name" >&2
    exit 2
  fi
done
cache_script="$(dirname "${BASH_SOURCE[0]}")/ghcr-cargo-target-cache.sh"
restored_marker="$cache_directory.restored"
# Relative paths of the entries that restore took from the shared lane, one per line.
shared_entries="$cache_directory.shared-entries"

# Prints the identity of the C++ compiler and SDK that the runner image supplies and Bazel does not hash.
#
# Environment: RUNNER_OS selects the platform. On Windows, VCToolsInstallDir, WindowsSDKVersion and UCRTVersion name the
#   MSVC tools and SDK.
# Output: nothing on Linux, whose C++ toolchain is a Bazel module pinned by MODULE.bazel.lock; on macOS, the selected
#   developer directory, SDK version and compiler version; on Windows, the three variables.
# Exit status: 0 on success; 2 when a Windows variable is empty; otherwise the failing tool's.
compiler_identity() {
  case "$RUNNER_OS" in
    macOS)
      xcode-select -p
      xcrun --show-sdk-version
      xcrun clang --version | head -n 1
      ;;
    Windows)
      local name
      for name in VCToolsInstallDir WindowsSDKVersion UCRTVersion; do
        if [[ -z "${!name:-}" ]]; then
          printf '%s must name the MSVC tools and SDK that the build uses\n' "$name" >&2
          return 2
        fi
        printf '%s=%s\n' "$name" "${!name}"
      done
      ;;
  esac
}

# Prints the exact and fallback tags of a lane, separated by a space.
#
# Arguments: the lane.
# Environment: RUNNER_OS, RUNNER_ARCH and ImageOS describe the runner; compiler_identity lists its other inputs.
# Output: the exact tag, a space and the fallback tag.
# Exit status: 0 on success; otherwise the failing tool's.
lane_tags() {
  local name="$1" platform identity exact_digest recent_digest
  # Callers run this in a command substitution, where errexit is off: return each failure explicitly.
  identity="$(compiler_identity)" || return
  platform="$(cat "$source_directory/.bazelversion")" || return
  platform="$(printf '%s\n' "$platform" "$RUNNER_OS" "$RUNNER_ARCH" "${ImageOS:-}" "$name" "$identity")"
  recent_digest="$(printf '%s' "$platform" | sha256sum | cut -d' ' -f1)" || return
  exact_digest="$(
    set -e
    cd "$source_directory"
    {
      printf '%s\n' "$platform"
      git ls-tree HEAD | awk '$2 == "blob"'
      git ls-tree -r HEAD third_party patches
    } | sha256sum | cut -d' ' -f1
  )" || return
  printf 'cargo-v3-bazel-cc-%s-%s cargo-seed-v2-bazel-cc-%s-%s\n' "$name" "$exact_digest" "$name" "$recent_digest"
}

# Pulls a lane's exact tag, otherwise its fallback tag, into an empty directory.
#
# Arguments: the lane, then the directory, which this replaces with an empty one first.
# Output: hit=exact or hit=recent for the tag that was pulled, or hit=miss when neither was, in which case the directory
#   is left empty. The transport's progress, and a GitHub warning for each failed pull, go to standard error.
# Exit status: 0 on success, including a miss; otherwise the failing tool's.
pull_lane() {
  local name="$1" directory="$2" tags exact recent candidate status
  # Callers run this in a command substitution, where errexit is off: return each failure explicitly.
  tags="$(lane_tags "$name")" || return
  read -r exact recent <<< "$tags"
  for candidate in "$exact:exact" "$recent:recent"; do
    rm -rf -- "${directory:?}" || return
    mkdir -p "$directory" || return
    status=0
    bash "$cache_script" pull "$repository" "${candidate%:*}" "$directory" "$upstream_ref" >&2 || status=$?
    if [[ "$status" == 0 ]]; then
      printf 'hit=%s\n' "${candidate##*:}"
      return 0
    fi
    if [[ "$status" != 3 ]]; then
      printf '::warning::Pulling the C++ action cache %s failed with status %s; trying the next candidate\n' \
        "${candidate%:*}" "$status" >&2
    fi
  done
  # A failed pull can leave part of an archive behind; the build then starts from an empty directory.
  rm -rf -- "${directory:?}" || return
  mkdir -p "$directory" || return
  printf 'hit=miss\n'
}

printf '%s' "$GITHUB_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin
tags="$(lane_tags "$lane")"
read -r exact_tag recent_tag <<< "$tags"

case "$operation" in
  restore)
    rm -f -- "$restored_marker" "$shared_entries"
    hit="$(pull_lane "$lane" "$cache_directory")"
    hit="${hit#hit=}"
    : > "$shared_entries"
    if [[ -n "$shared_lane" ]]; then
      shared_directory="$cache_directory.shared"
      shared_hit="$(pull_lane "$shared_lane" "$shared_directory")"
      # Disk cache entries are named by their content digest, so an entry already present is the same entry.
      (cd "$shared_directory" && find . -type f) > "$shared_entries"
      while IFS= read -r entry; do
        if [[ ! -e "$cache_directory/$entry" ]]; then
          mkdir -p "$(dirname "$cache_directory/$entry")"
          mv -- "$shared_directory/$entry" "$cache_directory/$entry"
        fi
      done < "$shared_entries"
      rm -rf -- "${shared_directory:?}"
      printf 'C++ action cache for %s: %s, %s entries\n' "$shared_lane" "${shared_hit#hit=}" "$(wc -l < "$shared_entries" | tr -d ' ')"
    fi
    printf '%s\n' "$hit" > "$restored_marker"
    printf 'hit=%s\n' "$hit" >> "$GITHUB_OUTPUT"
    printf 'C++ action cache for %s: %s, %s\n' "$lane" "$hit" "$(du -sh "$cache_directory" | cut -f1)"
    ;;
  save)
    removed=0
    while IFS= read -r entry; do
      if [[ -e "$cache_directory/$entry" ]]; then
        rm -f -- "$cache_directory/$entry"
        removed=$((removed + 1))
      fi
    done < "$shared_entries"
    new_entries="$(find "$cache_directory" -type f -newer "$restored_marker" | wc -l | tr -d ' ')"
    printf 'C++ action cache for %s: %s new entries, %s shared entries left out, %s in all\n' \
      "$lane" "$new_entries" "$removed" "$(du -sh "$cache_directory" | cut -f1)"
    if [[ "$(cat "$restored_marker")" == exact && "$new_entries" == 0 ]]; then
      printf 'The exact cache was restored and the build added nothing; skipping the upload\n'
      exit 0
    fi
    bash "$cache_script" push "$repository" "$exact_tag" "$cache_directory" "$upstream_ref" "$recent_tag"
    ;;
  exists)
    status=0
    resolve_output="$(oras resolve "$repository:$exact_tag" 2>&1)" || status=$?
    if [[ "$status" == 0 ]]; then
      exists=true
    else
      exists=false
      if ! grep -Eq 'not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found' <<< "$resolve_output"; then
        printf '::warning::Could not check for the C++ action cache %s: %s\n' "$exact_tag" "$resolve_output"
      fi
    fi
    printf 'exists=%s\n' "$exists" >> "$GITHUB_OUTPUT"
    printf 'C++ action cache %s for %s: exists=%s\n' "$exact_tag" "$lane" "$exists"
    ;;
  *)
    printf 'Unknown operation %s\n' "$operation" >&2
    exit 2
    ;;
esac
