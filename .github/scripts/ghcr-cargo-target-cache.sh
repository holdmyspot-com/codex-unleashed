#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "usage: $0 <pull|push|prune> <repository> <tag> <target-directory> <upstream-tag>" >&2
  exit 2
}

[[ $# -eq 5 ]] || usage

operation="$1"
repository="$2"
tag="$3"
target_directory="$4"
upstream_tag="$5"
reference="${repository}:${tag}"

# Match the GNU tar used by dependency-cache restore on macOS.
archive_tar=tar
if command -v gtar >/dev/null 2>&1; then
  archive_tar=gtar
fi

# Prunes obsolete and untagged package versions while preserving retained release aliases.
# Environment:
#   CODEX_UNLEASHED_TOOLING: Java tooling launcher, required for stable release discovery.
#   GH_TOKEN: Authentication used by the GitHub CLI.
#   GITHUB_API_URL: GitHub API base URL; defaults to https://api.github.com.
# Output:
#   Deletion progress on stdout and command failures on stderr.
# Effects:
#   Deletes obsolete GHCR package versions identified by the configured repository.
# Exit:
#   Zero on success; nonzero when release discovery, inventory lookup, or deletion fails.
prune_old_tags() {
  # Release-qualified aliases associate shared compiler caches with the
  # upstream versions that use them. Deleting a package version removes all
  # its tags, so preserve a manifest if either retained release references it.
  command -v gh >/dev/null || {
    echo "GitHub CLI is required to prune GHCR package versions" >&2
    return 1
  }
  repository_path="${repository#ghcr.io/}"
  package_owner="${repository_path%%/*}"
  package_name="${repository_path#*/}"
  package_endpoint="${GITHUB_API_URL:-https://api.github.com}/orgs/${package_owner}/packages/container/${package_name}/versions"

  : "${CODEX_UNLEASHED_TOOLING:?CODEX_UNLEASHED_TOOLING is required; run setup-tooling first}"
  retained_releases="$("$CODEX_UNLEASHED_TOOLING" stable-releases)"

  stale_version_ids="$(
    gh api --paginate "$package_endpoint" --jq '
      .[]
      | [.id, ((.metadata.container.tags // []) | join(","))]
      | @tsv
    ' | CODEX_CACHE_RETAINED_RELEASES="$retained_releases" awk -F '\t' '
      BEGIN { release_count = split(ENVIRON["CODEX_CACHE_RETAINED_RELEASES"], releases, "\n") }
      {
        has_cargo = 0
        has_current = 0
        tag_count = split($2, tags, ",")
        for (tag_index = 1; tag_index <= tag_count; tag_index++) {
          if (tags[tag_index] ~ /^cargo-/) {
            has_cargo = 1
            for (release_index = 1; release_index <= release_count; release_index++) {
              retained_suffix = "-" releases[release_index]
              if (length(tags[tag_index]) >= length(retained_suffix) &&
                  substr(tags[tag_index], length(tags[tag_index]) - length(retained_suffix) + 1) == retained_suffix) {
                has_current = 1
              }
            }
          }
        }
        # A package version may carry several tags when two pushes have the
        # same manifest. Never delete such a version if it also carries a
        # retained-release tag; deleting a package version deletes all its tags.
        # Oras can leave an untagged package version behind when a tag is
        # moved to a newer manifest. This cache repository has no useful
        # untagged content, so remove those orphaned versions as well.
        if (($2 == "") || (has_cargo && !has_current)) print $1
      }
    ' | sort -u
  )"

  while IFS= read -r version_id; do
    [[ -n "$version_id" ]] || continue
    echo "Deleting stale GHCR package version ${version_id}"
    gh api --method DELETE "${package_endpoint}/${version_id}"
  done <<< "$stale_version_ids"
}

case "$operation" in
  pull)
    if [[ "$tag" =~ ^cargo-v2-([A-Za-z0-9_-]+)-(off|deterministic)-([0-9a-f]{64})$ ]]; then
      cache_target="${BASH_REMATCH[1]}"
    else
      cache_target="${tag#cargo-}"
      cache_target="${cache_target%-"${upstream_tag}"}"
      if [[ -z "$cache_target" || ! "$cache_target" =~ ^[A-Za-z0-9_-]+$ || "$tag" != "cargo-${cache_target}-${upstream_tag}" ]]; then
        echo "Cargo cache tag does not identify a target for ${upstream_tag}: ${tag}" >&2
        exit 2
      fi
    fi

    mkdir -p "$target_directory"
    archive_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/codex-ghcr-cache.XXXXXX")"
    trap 'rm -rf "$archive_directory"' EXIT
    if ! oras pull "$reference" \
      --allow-path-traversal \
      --output "$archive_directory"; then
      [[ "$tag" == cargo-v2-* ]] || exit 1
      legacy_reference="${repository}:cargo-${cache_target}-${upstream_tag}"
      echo "Compatible cache restore failed; trying release cache ${legacy_reference}"
      oras pull "$legacy_reference" \
        --allow-path-traversal \
        --output "$archive_directory"
    fi
    # Extract before removing release outputs: dependency entries can be hard
    # links to those executables. Excluding their targets breaks extraction.
    # Stream the archive so GNU tar never interprets a Windows drive letter
    # in its filename as a remote-host specification.
    "$archive_tar" --zstd -xf - -C "$target_directory" < "$archive_directory/cargo-target.tar.zst"
    # Reuse compiled dependencies, but rebuild release executables and symbols
    # from the current patch set and build metadata, including matching dSYMs.
    release_dir="$target_directory/$cache_target/release"
    if [[ -d "$release_dir" ]]; then
      for artifact in "$release_dir"/codex* "$release_dir"/bwrap*; do
        [[ -e "$artifact" || -L "$artifact" ]] || continue
        if [[ -d "$artifact" ]]; then
          [[ "$artifact" == *.dSYM ]] || continue
          rm -r -- "$artifact"
        else
          rm -- "$artifact"
        fi
      done
    fi
    ;;
  push)
    [[ -d "$target_directory" ]] || {
      echo "Cargo target directory does not exist: $target_directory" >&2
      exit 1
    }
    archive_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/codex-ghcr-cache.XXXXXX")"
    trap 'rm -rf "$archive_directory"' EXIT
    archive="$archive_directory/cargo-target.tar.zst"
    "$archive_tar" --zstd -cf - -C "$target_directory" . > "$archive"
    (
      cd "$archive_directory"
      # Publish the release association first so concurrent pruning cannot
      # mistake a new shared cache for an obsolete, unassociated manifest.
      cache_identity="$(printf '%s' "$tag" | sha256sum | cut -d ' ' -f 1)"
      release_reference="${repository}:cargo-release-${cache_identity}-${upstream_tag}"
      oras push "$release_reference" \
        --disable-path-validation \
        --artifact-type application/vnd.codex-unleashed.cargo-target.v1 \
        "cargo-target.tar.zst:application/vnd.codex-unleashed.cargo-target.tar+zstd"
      oras tag "$release_reference" "$tag"
    )

    prune_old_tags
    ;;
  prune)
    prune_old_tags
    ;;
  *)
    usage
    ;;
esac
