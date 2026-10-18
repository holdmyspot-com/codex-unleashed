#!/usr/bin/env bash
# Runs the selected CI task inside a hosted BuildBuddy Linux VM.
set -euo pipefail

case "${BUILDBUDDY_CI_TASK:?BUILDBUDDY_CI_TASK is required}" in
  probe)
    printf 'Runner platform: %s\n' "$(uname -sm)"
    for tool in bash curl jq git rustup cargo node java python3 just uv bazel sudo; do
      if command -v "$tool"; then
        :
      else
        printf 'Not preinstalled: %s\n' "$tool"
      fi
    done
    printf 'Remote runner probe completed.\n'
    ;;
  *)
    printf 'Unsupported BuildBuddy CI task: %s\n' "$BUILDBUDDY_CI_TASK" >&2
    exit 2
    ;;
esac
