#!/usr/bin/env bash
# Verifies archive cleanup at the hosted task's exit boundary.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name the owned test temporary directory}/buildbuddy-cleanup-XXXXXX")"
trap 'rm -rf -- "$fixtures"' EXIT

for scenario in success task-failure foreign-cache cleanup-failure task-and-cleanup-failure; do
  case_root="$fixtures/$scenario"
  checkout="$case_root/checkout"
  managed_root="$case_root/codex-unleashed-ci"
  archive_cache="$managed_root/bazel-repository-cache"
  mkdir -p "$checkout/.github/scripts" "$archive_cache" "$managed_root/b" \
    "$managed_root/bazel-repo-contents-cache" "$case_root/artifacts"
  git init --quiet "$checkout"
  printf '*\n' > "$checkout/.git/info/exclude"
  cp "$project_root/.github/scripts/buildbuddy-ci.sh" "$checkout/.github/scripts/buildbuddy-ci.sh"
  printf 'archive\n' > "$archive_cache/download"
  printf 'analysis\n' > "$managed_root/b/server-state"
  printf 'repository\n' > "$managed_root/bazel-repo-contents-cache/source"

  task=probe
  expected_status=0
  selected_cache="$archive_cache"
  task_path="$PATH"
  if [[ "$scenario" == task-failure || "$scenario" == task-and-cleanup-failure ]]; then
    task=unsupported-cleanup-test
    expected_status=2
  fi
  if [[ "$scenario" == foreign-cache ]]; then
    selected_cache="$case_root/foreign-cache"
    mkdir -p "$selected_cache"
    printf 'foreign\n' > "$selected_cache/download"
  fi
  if [[ "$scenario" == cleanup-failure || "$scenario" == task-and-cleanup-failure ]]; then
    # Inject a deletion failure so status handling is independent of the runner's UID.
    mkdir -p "$case_root/bin"
    printf '#!/usr/bin/env bash\nexit 1\n' > "$case_root/bin/rm"
    chmod +x "$case_root/bin/rm"
    task_path="$case_root/bin:$PATH"
    if [[ "$expected_status" == 0 ]]; then
      expected_status=1
    fi
  fi

  status=0
  (
    cd "$checkout"
    export BUILDBUDDY_CI_TASK="$task" BUILDBUDDY_CI_TARGET=x86_64-unknown-linux-gnu
    export BUILDBUDDY_ARTIFACTS_DIRECTORY="$case_root/artifacts" GITHUB_SHA=cleanup-fixture
    export BAZEL_REPOSITORY_CACHE="$selected_cache"
    export PATH="$task_path"
    bash .github/scripts/buildbuddy-ci.sh
  ) > "$case_root/output.log" 2>&1 || status=$?
  if [[ "$status" != "$expected_status" ]]; then
    printf '%s: expected exit %s, got %s\n' "$scenario" "$expected_status" "$status" >&2
    cat "$case_root/output.log" >&2
    exit 1
  fi
  if [[ "$(tail -n 1 "$case_root/artifacts/result.txt")" != "$expected_status" ]]; then
    printf '%s: artifact receipt lost the terminal status\n' "$scenario" >&2
    exit 1
  fi
  if [[ "$scenario" == success || "$scenario" == task-failure ]]; then
    if [[ -e "$archive_cache" ]]; then
      printf '%s: downloaded archives remain after the task exits\n' "$scenario" >&2
      exit 1
    fi
  elif [[ "$scenario" == foreign-cache && ! -f "$selected_cache/download" ]]; then
    printf 'Cleanup removed an unmanaged cache\n' >&2
    exit 1
  fi
  if [[ ! -f "$managed_root/b/server-state" || ! -f "$managed_root/bazel-repo-contents-cache/source" ]]; then
    printf '%s: cleanup removed reusable Bazel state\n' "$scenario" >&2
    exit 1
  fi
  (cd "$case_root/artifacts" && sha256sum --check --status artifact-manifest.sha256)
done
