#!/usr/bin/env bash
set -euo pipefail

# Transfer compiled Cargo outputs under an exact cargo-v3 tag. Push accepts an
# optional cargo-seed-v1 tag for the same target, attaching it to the same manifest;
# pull accepts either tag family. Callers must run Cargo and verification after
# every restore. Partial operations append -partial to both references and never
# update their successful counterparts. A protected-release alias accompanies
# every upload. Pull returns 3 for a miss and invalid arguments return 2; other
# failures retain the tool status. RUNNER_TEMP owns transient archive storage.
# CARGO_CACHE_PARTS=1 selects build-step groups under -parts-v2 tags, reading
# earlier -parts-v1 groups or legacy archives on a miss. CARGO_CACHE_STAGE names
# the step owning new outputs; an unset stage defaults to bootstrap. It requires
# cargo-cache-parts.py and Python 3.11+. Unchanged groups require no archive uploads.
if [[ $# != 5 && $# != 6 ]]; then
  printf 'usage: %s <pull|push|pull-partial|push-partial> <repository> <tag> <target-directory> <upstream-tag> [compatible-seed-tag]\n' "$0" >&2
  exit 2
fi
operation="$1"
repository="$2"
tag="$3"
target_directory="$4"
upstream_tag="$5"
seed_tag="${6:-}"
if [[ "$tag" =~ ^cargo-v3-([A-Za-z0-9_-]+)-([0-9a-f]{64})$ ]]; then
  cache_target="${BASH_REMATCH[1]}"
elif [[ "$tag" =~ ^cargo-seed-v1-([A-Za-z0-9_-]+)-([0-9a-f]{64})$ ]]; then
  cache_target="${BASH_REMATCH[1]}"
  case "$operation" in
    pull|pull-partial) ;;
    *) printf 'Compatible seed tags are pull references; push requires an exact cargo-v3 tag.\n' >&2; exit 2 ;;
  esac
else
  printf 'Cargo cache tag must contain a supported family, target and 64-character digest: %s\n' "$tag" >&2
  exit 2
fi
if [[ $# == 6 ]]; then
  case "$operation" in
    push|push-partial) ;;
    *) printf 'A compatible seed alias is accepted only when pushing outputs.\n' >&2; exit 2 ;;
  esac
  if [[ ! "$seed_tag" =~ ^cargo-seed-v1-$cache_target-([0-9a-f]{64})$ ]]; then
    printf 'Compatible seed alias must name target %s and a 64-character digest: %s\n' "$cache_target" "$seed_tag" >&2
    exit 2
  fi
fi

if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
  tag="$tag-parts-v2"
  if [[ -n "$seed_tag" ]]; then
    seed_tag="$seed_tag-parts-v2"
  fi
fi

cache_state=verified
case "$operation" in
  pull|push) ;;
  pull-partial|push-partial)
    operation="${operation%-partial}"
    cache_state=partial
    tag="$tag-partial"
    if [[ -n "$seed_tag" ]]; then
      seed_tag="$seed_tag-partial"
    fi
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
target_directory="$(cd -- "$target_directory" && pwd)"
archive_directory="$(mktemp -d "$RUNNER_TEMP/codex-ghcr-cache.XXXXXX")"
trap 'rm -rf -- "$archive_directory"' EXIT
reference="$repository:$tag"
archive="$archive_directory/cargo-target.tar.zst"
transfer_started=$SECONDS
printf 'Cache operation=%s state=%s reference=%s\n' "$operation" "$cache_state" "$reference"
df -Pk "$target_directory" "$archive_directory"

case "$operation" in
  pull)
    if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
      script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
      for candidate in "$reference" "$repository:${tag/-parts-v2/-parts-v1}" "$repository:${tag/-parts-v2/}"; do
        set +e
        python3 "$script_root/cargo-cache-parts.py" pull "$repository" "$candidate" "$candidate" "$target_directory" "$archive_directory"
        result=$?
        set -e
        case "$result" in
          0) printf 'cache_transfer_seconds=%s\n' "$((SECONDS-transfer_started))"; exit 0 ;;
          3) ;;
          *) exit "$result" ;;
        esac
      done
      printf 'cache_result=miss\n'
      exit 3
    fi
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
    printf 'cache_result=hit digest=%s\n' "$digest"
    ;;
  push)
    du -sk "$target_directory"
    if [[ "${CARGO_CACHE_PARTS:-}" != 1 ]]; then
      "$archive_tar" --format=pax --zstd -cf - -C "$target_directory" . > "$archive"
      du -sk "$archive"
    fi
    script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
    (
      cd "$archive_directory"
      # Keep the release association used by the existing retention policy.
      cache_identity="$(printf '%s' "$tag" | sha256sum | cut -d ' ' -f 1)"
      release_reference="$repository:cargo-release-$cache_identity-$upstream_tag"
      if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
        python3 "$script_root/cargo-cache-parts.py" push "$repository" "$release_reference" "$reference" "$target_directory" "$archive_directory"
      else
        oras push "$release_reference" \
          --artifact-type application/vnd.codex-unleashed.cargo-target.v1 \
          'cargo-target.tar.zst:application/vnd.codex-unleashed.cargo-target.tar+zstd'
      fi
      aliases=("$tag")
      if [[ -n "$seed_tag" ]]; then
        aliases+=("$seed_tag")
      fi
      oras tag "$release_reference" "${aliases[@]}"
    )
    printf 'cache_result=saved\n'
    ;;
esac
df -Pk "$target_directory" "$archive_directory"
printf 'cache_transfer_seconds=%s\n' "$((SECONDS-transfer_started))"
