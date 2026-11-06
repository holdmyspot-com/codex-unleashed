#!/usr/bin/env bash
# Stage the retained-worker debug action and wait for its authenticated client.
# Usage: release-debug-session.sh prepare ACTION_SOURCE CHECKOUT EVIDENCE
#        release-debug-session.sh wait-for-client EVIDENCE
# Preparation stages the committed local action in an absent, checkout-local
# ignored directory, without modifying tracked upstream files. It rejects an existing
# action or client-ready signal. The evidence directory must be an existing absolute
# job-owned directory. The client creates EVIDENCE/client-ready through authenticated
# SSH; wait-for-client waits up to 600 seconds. Staged action files live through the
# action's post-session cleanup and disappear with the worker checkout.
set -euo pipefail

# Reject path values that cannot be written as one GitHub environment record.
# Arguments: exact caller-supplied pathname.
# Errors: an empty path or newline-bearing path is rejected before publication.
# Exit status: zero for one representable nonempty path.
require_path_record() {
  [[ -n "$1" && "$1" != *$'\n'* && "$1" != *$'\r'* ]]
}

# Convert a debug root from a Windows runner record to its Git Bash pathname.
# Arguments: one nonempty path record; stdout is the corresponding shell pathname.
# Exit status: preserves cygpath errors; other hosts preserve the original value.
debug_path() {
  require_path_record "$1"
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s\n' "$1"; fi
}

if [[ "${1:-}" == prepare ]]; then
  [[ $# == 4 ]] || { printf 'prepare requires action source, checkout and evidence roots.\n' >&2; exit 2; }
  source="$(debug_path "$2")"; checkout="$(debug_path "$3")"; evidence="$(debug_path "$4")"
  for path in "$source" "$checkout" "$evidence"; do
    require_path_record "$path"
    [[ "$path" == /* && -d "$path" && ! -L "$path" ]] || {
      printf 'Debug roots must be existing absolute nonsymlink directories.\n' >&2; exit 2;
    }
  done
  repository_root="$(git -C "$checkout" rev-parse --show-toplevel)"
  [[ "$(debug_path "$repository_root")" == "$checkout" ]] || {
    printf 'Debug checkout must be the exact repository root.\n' >&2; exit 2;
  }
  action="$checkout/.github/actions/debug-failed-runner"
  if [[ -e "$action" || -L "$action" || -e "$evidence/client-ready" || -L "$evidence/client-ready" ]]; then
    printf 'Debug action or readiness already exists; preserve the original state.\n' >&2
    exit 2
  fi
  for parent in "$checkout/.github" "$checkout/.github/actions"; do
    if [[ -L "$parent" || ( -e "$parent" && ! -d "$parent" ) ]]; then
      printf 'Debug action parent must be a nonsymlink directory.\n' >&2; exit 2
    fi
  done
  mkdir -p "$checkout/.github/actions"
  mkdir "$action"
  cp -R "$source/." "$action/"
  exclude="$(git -C "$checkout" rev-parse --git-path info/exclude)"
  if [[ "$exclude" != /* ]]; then exclude="$checkout/$exclude"; fi
  printf '\n/.github/actions/debug-failed-runner/\n' >> "$exclude"
  printf 'Connect with the supplied SSH key, then create %s/client-ready to start release commands.\n' "$evidence"
elif [[ "${1:-}" == wait-for-client ]]; then
  [[ $# == 2 ]] || { printf 'wait-for-client requires the evidence root.\n' >&2; exit 2; }
  evidence="$(debug_path "$2")"
  require_path_record "$evidence"
  [[ "$evidence" == /* && -d "$evidence" && ! -L "$evidence" ]] || {
    printf 'Evidence must be an existing absolute nonsymlink directory.\n' >&2; exit 2;
  }
  deadline=$((SECONDS + 600))
  while [[ ! -f "$evidence/client-ready" || -L "$evidence/client-ready" ]]; do
    if [[ -e "$evidence/client-ready" || -L "$evidence/client-ready" ]]; then
      printf 'Client readiness must be a regular nonsymlink file.\n' >&2; exit 2
    fi
    if (( SECONDS >= deadline )); then
      printf 'Authenticated client did not publish readiness within 600 seconds.\n' >&2; exit 1
    fi
    sleep 1
  done
else
  printf 'Usage: %s prepare ACTION_SOURCE CHECKOUT EVIDENCE | wait-for-client EVIDENCE\n' "$0" >&2
  exit 2
fi
