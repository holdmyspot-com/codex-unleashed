#!/usr/bin/env bash
# Applies the downstream patches to the checked-out upstream for a Windows test job.
#
# Environment: DOWNSTREAM_PATCHES (space-separated repository paths, in order, possibly empty), RED_RESTORE and
# RED_PATCH_COUNT (a red phase needs at least that many applied patches), GITHUB_WORKSPACE (the restart checkout, which
# holds the patches, with the upstream checkout in its upstream directory).
set -euo pipefail

# A pasted multi-line input may carry CRs, which would end up in the last path of each line.
DOWNSTREAM_PATCHES=${DOWNSTREAM_PATCHES//$'\r'/}
read -r -d '' -a patch_paths <<< "$DOWNSTREAM_PATCHES" || true
# Snapshot files and nested patch files contain intended trailing spaces; tell git am they are not whitespace errors.
printf '%s\n' '*.snap -whitespace' '*.patch -whitespace' >> "$(git rev-parse --git-path info/attributes)"
# Upstream's CI tests main, whose workspace version is 0.0.0; a stable tag's release commit changes only that
# version. Test the same source, so version strings in snapshots and fixtures match what upstream tested.
perl -pi -e '$w = /^\[workspace\.package\]\r?$/ ? 1 : /^\[/ ? 0 : $w;' \
  -e 's/^version = "[^"]+"(\r?)$/version = "0.0.0"$1/ if $w' upstream/codex-rs/Cargo.toml
if ! git -C upstream diff --quiet -- codex-rs/Cargo.toml; then
  git -C upstream -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
    commit -q -m "Test with upstream's development version 0.0.0" -- codex-rs/Cargo.toml
fi
for patch in ${patch_paths[@]+"${patch_paths[@]}"}; do
  sha256sum "$patch"
  git -C upstream -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
    am --committer-date-is-author-date "$GITHUB_WORKSPACE/$patch"
done
if [[ -n "$RED_RESTORE" ]] && (( ${#patch_paths[@]} < RED_PATCH_COUNT )); then
  printf 'red_patch_count %s exceeds the %s applied patches.\n' "$RED_PATCH_COUNT" "${#patch_paths[@]}" >&2
  exit 1
fi
