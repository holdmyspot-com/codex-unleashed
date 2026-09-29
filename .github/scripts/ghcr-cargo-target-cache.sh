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

prune_old_tags() {
  # Compatible compiler/target caches span upstream releases. Preserve all
  # tagged v2 caches and the current legacy release during migration; remove
  # orphaned manifests and obsolete legacy entries.
  # GHCR does not implement the OCI manifest-delete operation. Delete the
  # corresponding GitHub Packages version instead; this removes its tags and
  # works for private as well as public cache packages.
  command -v gh >/dev/null || {
    echo "GitHub CLI is required to prune GHCR package versions" >&2
    return 1
  }
  repository_path="${repository#ghcr.io/}"
  package_owner="${repository_path%%/*}"
  package_name="${repository_path#*/}"
  package_endpoint="${GITHUB_API_URL:-https://api.github.com}/orgs/${package_owner}/packages/container/${package_name}/versions"

  stale_version_ids="$(
    gh api --paginate "$package_endpoint" --jq '
      .[]
      | [.id, ((.metadata.container.tags // []) | join(","))]
      | @tsv
    ' | awk -F '\t' -v current_suffix="-$upstream_tag" '
      {
        has_cargo = 0
        has_current = 0
        tag_count = split($2, tags, ",")
        for (tag_index = 1; tag_index <= tag_count; tag_index++) {
          if (tags[tag_index] ~ /^cargo-/) {
            has_cargo = 1
            if (tags[tag_index] ~ /^cargo-v2-/) has_current = 1
            if (length(tags[tag_index]) >= length(current_suffix) &&
                substr(tags[tag_index], length(tags[tag_index]) - length(current_suffix) + 1) == current_suffix) {
              has_current = 1
            }
          }
        }
        # A package version may carry several tags when two pushes have the
        # same manifest. Never delete such a version if it also carries a
        # current-release tag; deleting a package version deletes all its tags.
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
    # Reuse compiled dependencies, but rebuild release executables and their
    # symbols from the current patch set and build metadata. Cached executables
    # can otherwise survive a Cargo build without the matching macOS dSYM.
    tar --zstd -xf "$archive_directory/cargo-target.tar.zst" -C "$target_directory" \
      --exclude='./*/release/codex*' \
      --exclude='./*/release/bwrap*'
    # Enforce the same result when tar matches exclusions differently or an
    # earlier cache layer already placed release outputs in the target tree.
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
    tar --zstd -cf "$archive" -C "$target_directory" .
    (
      cd "$archive_directory"
      oras push "$reference" \
        --disable-path-validation \
        --artifact-type application/vnd.codex-unleashed.cargo-target.v1 \
        "cargo-target.tar.zst:application/vnd.codex-unleashed.cargo-target.tar+zstd"
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
