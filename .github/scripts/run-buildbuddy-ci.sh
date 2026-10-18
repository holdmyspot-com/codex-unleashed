#!/usr/bin/env bash
# Submits the checked-out revision, streams its logs, and returns the remote task's exit status.
set -euo pipefail
: "${BUILDBUDDY_API_KEY:?BuildBuddy execution requires BUILDBUDDY_API_KEY}"
: "${BUILDBUDDY_CI_TASK:?BUILDBUDDY_CI_TASK is required}"
: "${BUILDBUDDY_CI_TARGET:?BUILDBUDDY_CI_TARGET is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"

# Secret headers avoid publishing credentials in the action's ordinary environment metadata.
exec bb remote \
  --os=linux --arch=amd64 \
  "--run_from_commit=$GITHUB_SHA" \
  "--run_from_branch=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:?}}" \
  --runner_exec_properties=remote-snapshot-save-policy=none-available \
  --runner_exec_properties=snapshot-read-policy=local-first \
  --runner_exec_properties=max-stale-fallback-snapshot-age=24h \
  "--runner_exec_properties=codex-ci-task=$BUILDBUDDY_CI_TASK" \
  "--runner_exec_properties=codex-ci-target=$BUILDBUDDY_CI_TARGET" \
  "--remote_run_header=x-buildbuddy-platform.secret-env-overrides=BUILDBUDDY_API_KEY=$BUILDBUDDY_API_KEY" \
  --env=GIT_REPO_DEFAULT_BRANCH=main \
  --env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 \
  --env=BUILDBUDDY_CI_TASK --env=BUILDBUDDY_CI_TARGET --env=UPSTREAM_REF \
  --env=GITHUB_SHA --env=GITHUB_REPOSITORY --env=GITHUB_RUN_ID \
  --script='bash .github/scripts/buildbuddy-ci.sh'
