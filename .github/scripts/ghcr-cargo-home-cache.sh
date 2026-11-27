#!/usr/bin/env bash
set -euo pipefail

# Transfer Cargo's download state (registry index, downloaded crates and git database) under a
# cargo-home-v1 tag keyed by the OS, the architecture and the hash of codex-rs/Cargo.lock as it is when the
# command runs, so run it after the patches are applied. Restore falls back to the newest saved entry for the same
# OS and architecture (the -latest tag) on a miss. Save pushes only when no entry exists for the exact key, and
# moves -latest to it. Extracted sources (registry/src, git/checkouts) and bin are not stored: Cargo re-extracts the
# sources from the stored state. Every outcome exits 0: an unavailable cache costs time, never the build.
# Requires oras to be logged in, CARGO_CACHE_REPOSITORY, CODEX_REPO_ROOT and RUNNER_TEMP in the environment.
if [[ $# != 1 || ( "$1" != restore && "$1" != save ) ]]; then
  printf 'usage: %s <restore|save>\n' "$0" >&2
  exit 2
fi
operation="$1"
: "${CARGO_CACHE_REPOSITORY:?Set the GHCR cache repository}"
: "${CODEX_REPO_ROOT:?Set the source checkout}"
: "${RUNNER_TEMP:?RUNNER_TEMP must name owned cache-transfer storage}"

cargo_home="${CARGO_HOME:-}"
if [[ -z "$cargo_home" ]]; then
  case "$OSTYPE" in
    msys*|cygwin*) cargo_home="$(cygpath -u -- "$USERPROFILE")/.cargo" ;;
    *) cargo_home="$HOME/.cargo" ;;
  esac
fi
case "$OSTYPE" in
  msys*|cygwin*) cargo_home="$(cygpath -u -- "$cargo_home")" ;;
esac
archive_tar=tar
if command -v gtar > /dev/null 2>&1; then
  archive_tar=gtar
fi
if command -v sha256sum > /dev/null 2>&1; then
  lock_digest="$(sha256sum "$CODEX_REPO_ROOT/codex-rs/Cargo.lock" | cut -d ' ' -f 1)"
else
  lock_digest="$(shasum -a 256 "$CODEX_REPO_ROOT/codex-rs/Cargo.lock" | cut -d ' ' -f 1)"
fi
family="cargo-home-v1-$(printf '%s-%s' "$RUNNER_OS" "$RUNNER_ARCH" | LC_ALL=C tr '[:upper:]' '[:lower:]')"
exact="$family-$lock_digest"
latest="$family-latest"
state_paths=(registry/index registry/cache git/db)

work_directory="$(mktemp -d "$RUNNER_TEMP/codex-cargo-home.XXXXXX")"
trap 'rm -rf -- "$work_directory"' EXIT
archive="$work_directory/cargo-home.tar.zst"

resolve() {
  oras resolve "$CARGO_CACHE_REPOSITORY:$1" 2> /dev/null
}

soft() {
  printf 'Cargo home cache %s failed (exit %s); continuing without it.\n' "$operation" "$?" >&2
  exit 0
}
trap soft ERR

case "$operation" in
  restore)
    mkdir -p "$cargo_home"
    for tag in "$exact" "$latest"; do
      if digest="$(resolve "$tag")"; then
        ( cd "$work_directory" && oras pull "$CARGO_CACHE_REPOSITORY@$digest" --output . )
        "$archive_tar" --zstd -xf - -C "$cargo_home" < "$archive"
        printf 'cargo_home_cache=hit tag=%s digest=%s\n' "$tag" "$digest"
        exit 0
      fi
    done
    printf 'cargo_home_cache=miss exact=%s\n' "$exact"
    ;;
  save)
    if resolve "$exact" > /dev/null; then
      printf 'cargo_home_cache=present tag=%s\n' "$exact"
      exit 0
    fi
    existing=()
    for path in "${state_paths[@]}"; do
      if [[ -d "$cargo_home/$path" ]]; then existing+=("$path"); fi
    done
    if (( ${#existing[@]} == 0 )); then
      printf 'cargo_home_cache=empty home=%s\n' "$cargo_home"
      exit 0
    fi
    "$archive_tar" --format=pax --zstd -cf - -C "$cargo_home" "${existing[@]}" > "$archive"
    du -sk "$archive"
    ( cd "$work_directory" && oras push "$CARGO_CACHE_REPOSITORY:$exact,$latest" \
      --artifact-type application/vnd.codex-unleashed.cargo-home.v1 \
      'cargo-home.tar.zst:application/vnd.codex-unleashed.cargo-home.tar+zstd' )
    printf 'cargo_home_cache=saved tag=%s\n' "$exact"
    ;;
esac
