#!/usr/bin/env bash
# Submits the checked-out revision, streams its logs, and returns the remote task's exit status.
set -euo pipefail
: "${BUILDBUDDY_API_KEY:?BuildBuddy execution requires BUILDBUDDY_API_KEY}"
: "${BUILDBUDDY_CI_TASK:?BUILDBUDDY_CI_TASK is required}"
: "${BUILDBUDDY_CI_TARGET:?BUILDBUDDY_CI_TARGET is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"

# Secret headers avoid publishing credentials in the action's ordinary environment metadata.
invocation_file="$RUNNER_TEMP/buildbuddy-invocation-id"
artifact_directory="$RUNNER_TEMP/buildbuddy-artifacts"
mkdir -p "$artifact_directory"
# Runtime inputs need a fresh snapshot even on branches that retain their first snapshot.
runtime_identity="$(bash .github/scripts/buildbuddy-tooling-source-key.sh)"
set +e
bb remote \
  "--invocation_id_file=$invocation_file" \
  --os=linux --arch=amd64 \
  "--run_from_commit=$GITHUB_SHA" \
  "--run_from_branch=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:?}}" \
  --runner_exec_properties=remote-snapshot-save-policy=none-available \
  --runner_exec_properties=snapshot-read-policy=local-first \
  --runner_exec_properties=max-stale-fallback-snapshot-age=24h \
  "--runner_exec_properties=codex-ci-task=$BUILDBUDDY_CI_TASK" \
  "--runner_exec_properties=codex-ci-target=$BUILDBUDDY_CI_TARGET" \
  "--runner_exec_properties=codex-ci-runtime=$runtime_identity" \
  "--remote_run_header=x-buildbuddy-platform.secret-env-overrides=BUILDBUDDY_API_KEY=$BUILDBUDDY_API_KEY" \
  --env=GIT_REPO_DEFAULT_BRANCH=main \
  --env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 \
  --env=BUILDBUDDY_CI_TASK --env=BUILDBUDDY_CI_TARGET --env=UPSTREAM_REF \
  --env=GITHUB_ACTIONS --env=GITHUB_SHA --env=GITHUB_REPOSITORY --env=GITHUB_RUN_ID \
  --script='bash .github/scripts/buildbuddy-ci.sh'
task_status=$?
set -e

if [[ ! -s "$invocation_file" ]]; then
  printf 'BuildBuddy did not return an invocation ID (exit %s).\n' "$task_status" >&2
  if [[ "$task_status" != 0 ]]; then
    exit "$task_status"
  fi
  exit 1
fi
invocation_id="$(cat "$invocation_file")"
printf 'BuildBuddy invocation: https://app.buildbuddy.io/invocation/%s\n' "$invocation_id"
download_status=0
bb download artifacts "$invocation_id" "--output_directory=$artifact_directory" || download_status=$?
if [[ "$task_status" != 0 ]]; then
  exit "$task_status"
fi
if [[ "$download_status" != 0 ]]; then
  exit "$download_status"
fi

mapfile -t manifests < <(find "$artifact_directory" -name artifact-manifest.sha256 -type f)
if [[ ${#manifests[@]} != 1 ]]; then
  printf 'Expected one remote artifact manifest; found %s.\n' "${#manifests[@]}" >&2
  exit 1
fi
result_directory="$(dirname "${manifests[0]}")"
(cd "$result_directory" && sha256sum --check artifact-manifest.sha256)
printf '%s\n%s\n%s\n0\n' "$GITHUB_SHA" "$BUILDBUDDY_CI_TASK" "$BUILDBUDDY_CI_TARGET" \
  > "$RUNNER_TEMP/expected-buildbuddy-result.txt"
cmp "$RUNNER_TEMP/expected-buildbuddy-result.txt" "$result_directory/result.txt"
printf 'artifact-directory=%s\n' "$result_directory" >> "$GITHUB_OUTPUT"
