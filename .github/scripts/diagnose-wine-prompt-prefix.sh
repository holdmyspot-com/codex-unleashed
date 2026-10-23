#!/usr/bin/env bash
set -euo pipefail

project_root="${1:?project root is required}"
if [[ "${BUILDBUDDY_CI_TARGET:?}" != x86_64-unknown-linux-gnu ]]; then
  printf 'Unsupported Wine prompt-prefix diagnostic target: %s\n' "$BUILDBUDDY_CI_TARGET" >&2
  exit 2
fi

cd "$project_root/upstream"
diagnostic_patch="$project_root/.github/scripts/diagnose-wine-prompt-prefix.patch"
artifacts="${BUILDBUDDY_ARTIFACTS_DIRECTORY:?}"
mkdir -p "$artifacts"
sources=(codex-rs/app-server/tests/prompt_prefix.rs codex-rs/app-server/tests/common/test_app_server.rs)
{
  printf 'release_commit=%s\n' "$GITHUB_SHA"
  printf 'upstream_commit=%s\n' "$(git rev-parse HEAD)"
  sha256sum "$diagnostic_patch" "${sources[@]}"
} > "$artifacts/wine-prompt-prefix-identities.txt"
sha256sum "${sources[@]}" > "$artifacts/wine-prompt-prefix-before.sha256"

patch_applied=0
restore_diagnostic_patch() {
  local command_status=$? restore_status=0
  local status=$command_status
  trap - EXIT
  if [[ "$patch_applied" == 1 ]]; then
    git apply --reverse --check "$diagnostic_patch" && git apply --reverse "$diagnostic_patch" || restore_status=$?
    if [[ "$restore_status" == 0 ]]; then
      sha256sum --check --status "$artifacts/wine-prompt-prefix-before.sha256" || restore_status=$?
    fi
    if [[ "$restore_status" != 0 ]]; then
      printf 'Failed to restore the Wine diagnostic sources; status=%s\n' "$restore_status" >&2
      if [[ "$status" == 0 ]]; then
        status=$restore_status
      fi
    fi
  fi
  printf 'command_exit=%s\nrestore_exit=%s\nfinal_exit=%s\n' "$command_status" "$restore_status" "$status" \
    > "$artifacts/wine-prompt-prefix-result.txt"
  exit "$status"
}

git apply --check "$diagnostic_patch"
trap restore_diagnostic_patch EXIT
git apply "$diagnostic_patch"
patch_applied=1
sha256sum "${sources[@]}" >> "$artifacts/wine-prompt-prefix-identities.txt"

"$project_root/.github/scripts/run-bazel-ci.sh" --print-failed-action-summary --print-failed-test-logs -- \
  test --nocache_test_results --test_output=all \
  --test_arg=prompt_prefix_matches_release --test_arg=--exact --test_arg=--nocapture \
  "--build_metadata=COMMIT_SHA=$GITHUB_SHA" -- //codex-rs/app-server:app-server-prompt_prefix-wine-exec-test
