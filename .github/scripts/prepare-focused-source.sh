#!/usr/bin/env bash
# Reconstruct the focused CP9 candidate in a checkout of the pinned upstream.
# Arguments are the absolute fork and upstream checkout paths. Requires CODEX_TOOLING.
set -euo pipefail
fork="$1"
source="$2"
pin=086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a
expected_tree=cc614fb48e016692917662d6436a0115d4d9c540

actual_pin="$(git -C "$source" rev-parse HEAD)"
if [[ "$actual_pin" != "$pin" ]]; then
  printf 'Expected upstream %s, found %s\n' "$pin" "$actual_pin" >&2
  exit 1
fi

patch_list="$(bash "$CODEX_TOOLING" upstream-auto-release patches --readme "$fork/patches/README.md")"
read -r -a patch_paths <<< "$patch_list"
git_dir="$(git -C "$source" rev-parse --absolute-git-dir)"
mkdir -p "$git_dir/info"
printf '%s\n' '*.snap -whitespace' '*.patch -whitespace' >> "$git_dir/info/attributes"
for patch in "${patch_paths[@]}"; do
  git -C "$source" -c core.longpaths=true -c user.name='CP9 focused verification' \
    -c user.email='cp9-focused@users.noreply.github.com' \
    am --committer-date-is-author-date "$fork/$patch"
done

actual_tree="$(git -C "$source" rev-parse HEAD^{tree})"
if [[ "$actual_tree" != "$expected_tree" ]]; then
  printf 'Expected source tree %s, found %s\n' "$expected_tree" "$actual_tree" >&2
  exit 1
fi
printf 'Verified %s patch inputs and source tree %s\n' "${#patch_paths[@]}" "$actual_tree"
bash "$CODEX_TOOLING" align-workspace-lock --source-root "$source"
