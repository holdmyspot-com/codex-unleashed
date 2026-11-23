#!/usr/bin/env bash
set -euo pipefail

# Lets the Bazel jobs of this repository read and write BuildBuddy's remote cache when the repository secret
# BUILDBUDDY_API_KEY is available, and leaves them on the GHCR disk caches alone when it is not.
#
# usage: buildbuddy-cache.sh configure <source-directory>
#        buildbuddy-cache.sh finish <source-directory> [<evidence-directory>...]
#   configure  appends the remote cache settings to <source-directory>/user.bazelrc, which upstream's .bazelrc imports and
#              .gitignore excludes. It does nothing, and exits 0, when BUILDBUDDY_API_KEY is empty or when the run is not
#              trusted (see below).
#   finish     removes the key from <source-directory>/user.bazelrc, then replaces any copy of the key found in the
#              <evidence-directory> trees with *** and exits 1 if a copy remains. Run it before uploading evidence.
#
# BUILDBUDDY_API_KEY is the only input that carries the key, and only these two steps receive it. The upstream wrapper
# (.github/scripts/run_bazel_with_buildbuddy.py) is never given it: with a key, the wrapper adds upstream's ci-* configs,
# which require remote execution, and runs tests in upstream's container image instead of on the native host that the
# test baselines describe. This script therefore selects remote caching and the remote downloader only, on the generic
# BuildBuddy host. The wrapper's own trust check, is_trusted_upstream_run, selects between upstream's OpenAI tenant and
# that generic host; it is false for this repository and must stay so, because this key is not valid on the OpenAI tenant.
#
# Trust gate: configure writes the key only for a push, workflow_dispatch or schedule run in TRUSTED_REPOSITORY. A
# pull_request run never gets it; the workflows do not run on pull requests and GitHub withholds repository secrets from
# fork pull requests, so this check is a second barrier. The key must be alphanumeric, so that it cannot add rc lines.
# Exit status: 0 on success or when nothing is configured; 1 when the key is malformed or a copy of it remains in an
# evidence directory; 2 for invalid arguments.
TRUSTED_REPOSITORY="holdmyspot-com/codex-unleashed"
REMOTE_ENDPOINT="grpcs://remote.buildbuddy.io"

if [[ $# -lt 2 ]]; then
  printf 'usage: %s <configure|finish> <source-directory> [<evidence-directory>...]\n' "$0" >&2
  exit 2
fi
operation="$1"
source_directory="$2"
shift 2
rc_file="$source_directory/user.bazelrc"

configure() {
  if [[ -z "${BUILDBUDDY_API_KEY:-}" ]]; then
    printf 'No BuildBuddy key is available; Bazel uses only the GHCR disk caches.\n'
    return 0
  fi
  case "${GITHUB_EVENT_NAME:-}" in
    push | workflow_dispatch | schedule) ;;
    *)
      printf '::warning::Withholding the BuildBuddy key from a %s run.\n' "${GITHUB_EVENT_NAME:-unknown}"
      return 0
      ;;
  esac
  if [[ "${GITHUB_ACTIONS:-}" != true || "${GITHUB_REPOSITORY:-}" != "$TRUSTED_REPOSITORY" ]]; then
    printf '::warning::Withholding the BuildBuddy key from a run outside %s.\n' "$TRUSTED_REPOSITORY"
    return 0
  fi
  if [[ ! "$BUILDBUDDY_API_KEY" =~ ^[A-Za-z0-9_-]+$ ]]; then
    printf '::error::BUILDBUDDY_API_KEY is not a plain alphanumeric key.\n' >&2
    return 1
  fi
  (
    umask 077
    {
      printf 'common --remote_cache=%s\n' "$REMOTE_ENDPOINT"
      printf 'common --experimental_remote_downloader=%s\n' "$REMOTE_ENDPOINT"
      printf 'common --remote_header=x-buildbuddy-api-key=%s\n' "$BUILDBUDDY_API_KEY"
    } >> "$rc_file"
  )
  printf 'BuildBuddy remote cache configured for Bazel (remote execution is not used).\n'
}

finish() {
  if [[ -f "$rc_file" ]]; then
    kept="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildbuddy-rc.XXXXXX")"
    grep -v 'x-buildbuddy-api-key' "$rc_file" > "$kept" || true
    cat "$kept" > "$rc_file"
    rm -f -- "$kept"
  fi
  if [[ -z "${BUILDBUDDY_API_KEY:-}" ]]; then
    return 0
  fi
  key_file="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/buildbuddy-key.XXXXXX")"
  trap 'rm -f -- "$key_file"' RETURN
  printf '%s' "$BUILDBUDDY_API_KEY" > "$key_file"
  local directory file leaked=0
  for directory in "$@"; do
    [[ -d "$directory" ]] || continue
    while IFS= read -r file; do
      printf '::warning::Redacting the BuildBuddy key from %s\n' "$file"
      perl -pi -e 's/\Q$ENV{BUILDBUDDY_API_KEY}\E/***/g' "$file"
    done < <(grep -rlaF -f "$key_file" -- "$directory" || true)
    if [[ -n "$(grep -rlaF -f "$key_file" -- "$directory" || true)" ]]; then
      leaked=1
    fi
  done
  if (( leaked )); then
    printf '::error::A copy of the BuildBuddy key remains in the evidence.\n' >&2
    return 1
  fi
}

case "$operation" in
  configure) configure ;;
  finish) finish "$@" ;;
  *)
    printf 'Unknown operation %s\n' "$operation" >&2
    exit 2
    ;;
esac
