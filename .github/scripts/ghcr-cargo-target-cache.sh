#!/usr/bin/env bash
set -euo pipefail

# Transfer compiled Cargo outputs for an exact build-input tag. Partial operations
# use a separate reference and never publish a verified-cache reference. Pulls
# return 3 for a missing reference; invalid arguments return 2, and other failures
# retain the failing tool's status. RUNNER_TEMP owns transient archive storage.
if [[ $# != 5 ]]; then
  printf 'usage: %s <pull|push|pull-partial|push-partial> <repository> <tag> <target-directory> <upstream-tag>\n' "$0" >&2
  exit 2
fi
operation="$1"
repository="$2"
tag="$3"
target_directory="$4"
upstream_tag="$5"
if [[ ! "$tag" =~ ^cargo-v3-([A-Za-z0-9_-]+)-([0-9a-f]{64})$ ]]; then
  printf 'Cargo cache tag must include its target and exact build-input digest: %s\n' "$tag" >&2
  exit 2
fi
cache_target="${BASH_REMATCH[1]}"
cache_state=verified
case "$operation" in
  pull|push) ;;
  pull-partial|push-partial)
    operation="${operation%-partial}"
    cache_state=partial
    tag="$tag-partial"
    ;;
  *) printf 'Unsupported Cargo cache operation: %s\n' "$operation" >&2; exit 2 ;;
esac
case "$OSTYPE" in
  msys*|cygwin*) target_directory="$(cygpath -u -- "$target_directory")" ;;
esac
archive_tar=tar
if command -v gtar >/dev/null 2>&1; then
  archive_tar=gtar
fi
: "${RUNNER_TEMP:?RUNNER_TEMP must name owned cache-transfer storage}"
mkdir -p "$target_directory"
archive_directory="$(mktemp -d "$RUNNER_TEMP/codex-ghcr-cache.XXXXXX")"
trap 'rm -rf -- "$archive_directory"' EXIT
reference="$repository:$tag"
archive="$archive_directory/cargo-target.tar.zst"
transfer_started=$SECONDS
printf 'Cache operation=%s state=%s reference=%s\n' "$operation" "$cache_state" "$reference"
df -Pk "$target_directory" "$archive_directory"

case "$operation" in
  pull)
    set +e
    digest="$(oras resolve "$reference" 2> "$archive_directory/resolve.stderr")"
    result=$?
    set -e
    if [[ "$result" != 0 ]]; then
      cat "$archive_directory/resolve.stderr" >&2
      if grep -Eq 'not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found' "$archive_directory/resolve.stderr"; then
        printf 'cache_result=miss\n'
        exit 3
      fi
      exit "$result"
    fi
    oras pull "$repository@$digest" --output "$archive_directory"
    du -sk "$archive"
    "$archive_tar" --zstd -xf - -C "$target_directory" < "$archive"
    # Preserve dependency hard links by extracting before removing executable aliases.
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
    printf 'cache_result=hit digest=%s\n' "$digest"
    ;;
  push)
    du -sk "$target_directory"
    "$archive_tar" --format=pax --zstd -cf - -C "$target_directory" . > "$archive"
    du -sk "$archive"
    (
      cd "$archive_directory"
      # Keep the release association used by the existing retention policy.
      cache_identity="$(printf '%s' "$tag" | sha256sum | cut -d ' ' -f 1)"
      release_reference="$repository:cargo-release-$cache_identity-$upstream_tag"
      oras push "$release_reference" \
        --artifact-type application/vnd.codex-unleashed.cargo-target.v1 \
        'cargo-target.tar.zst:application/vnd.codex-unleashed.cargo-target.tar+zstd'
      oras tag "$release_reference" "$tag"
    )
    printf 'cache_result=saved\n'
    ;;
esac
df -Pk "$target_directory" "$archive_directory"
printf 'cache_transfer_seconds=%s\n' "$((SECONDS-transfer_started))"
