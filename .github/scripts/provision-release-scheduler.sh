#!/usr/bin/env bash
# Install or release one job-exclusive scheduler runtime, toolchain and native authority.
# Usage: provision-release-scheduler.sh install RUNTIME TOOLCHAIN FRIDA [LIBCGROUP]
#        provision-release-scheduler.sh cleanup ROOT
#        provision-release-scheduler.sh prepare-debug-session ACTION_SOURCE CHECKOUT EVIDENCE
#        provision-release-scheduler.sh wait-for-client EVIDENCE
# Debug preparation stages the committed local action in an absent, checkout-local
# ignored directory, without modifying tracked upstream files. It rejects an existing
# action or client-ready signal. The evidence directory must be an existing absolute
# job-owned directory. The client creates EVIDENCE/client-ready through authenticated
# SSH; wait-for-client waits up to 600 seconds. Staged action files live through the
# action's post-session cleanup and disappear with the worker checkout.
# RUNNER_OS selects Linux, macOS or Windows; RUNNER_TEMP owns Windows installation.
# GITHUB_ENV receives only the exclusive root and original Unix workload credentials.
# Unix installation requires noninteractive sudo; copies become root-owned and protected.
# Windows follows the existing caller-identity contract and acquires no foreign handles
# or new elevation policy. Its runtime is copied into an exclusive job-owned directory.
# Cleanup refuses a retained-owner marker. Linux additionally requires every owned
# cgroup empty before removing only those empty directories and installation files.
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

# Run one filesystem administration operation with the platform's existing authority.
# Arguments: exact executable and separate arguments, without shell interpretation.
# Environment: RUNNER_OS selects supported privilege behavior.
# Side effects: executes the selected operation, preserving its actual status.
# Exit status: the operation's status.
admin() {
  if [[ "$RUNNER_OS" == Windows ]]; then "$@"; else sudo -n "$@"; fi
}

if [[ "${1:-}" == prepare-debug-session ]]; then
  [[ $# == 4 ]] || { printf 'prepare-debug-session requires action source, checkout and evidence roots.\n' >&2; exit 2; }
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
elif [[ "${1:-}" == install ]]; then
  [[ $# == 4 || $# == 5 ]] || { printf 'install requires runtime, toolchain and native library inputs.\n' >&2; exit 2; }
  : "${GITHUB_ENV:?Set the workflow environment output}"
  runtime_input="$2"; toolchain_input="$3"; frida_input="$4"
  for path in "$runtime_input" "$toolchain_input" "$frida_input"; do require_path_record "$path"; done
  [[ -d "$runtime_input" && -d "$toolchain_input" && -f "$frida_input" ]]
  case "$RUNNER_OS" in
    Linux | macOS)
      uid="$(id -u)"; gid="$(id -g)"
      [[ "$uid" != 0 && "$gid" != 0 ]]
      parent=/var/lib
      if [[ "$RUNNER_OS" == macOS ]]; then parent=/Library; fi
      # Authenticate the existing namespace before creating any administrator-owned path.
      bash "$runtime_input/bin/codex-tooling" validate-release-installation --parent "$parent"
      root="$(sudo -n mktemp -d "$parent/codex-release-scheduler-XXXXXXXX")"
      ;;
    Windows)
      : "${RUNNER_TEMP:?Set the job-owned native temporary root}"
      temporary_root="$RUNNER_TEMP"
      if command -v cygpath >/dev/null 2>&1; then temporary_root="$(cygpath -u "$temporary_root")"; fi
      root="$(mktemp -d "$temporary_root/codex-release-scheduler-XXXXXXXX")"
      uid=0; gid=0
      ;;
    *) printf 'Unsupported scheduler installation platform.\n' >&2; exit 2 ;;
  esac
  require_path_record "$root"
  complete=false
  group=''
  # Installation has not launched consumers; a partial copy has no live borrowers.
  trap 'if [[ "$complete" != true ]]; then if [[ -n "$group" ]] && ! sudo -n rmdir "$group"; then printf "Partial containment removal failed; retain installation.\n" >&2; else admin rm -rf -- "$root"; fi; fi' EXIT
  admin chmod 755 "$root"
  admin mkdir "$root/runtime" "$root/toolchain" "$root/native" "$root/scratch" "$root/history"
  admin cp -pR "$runtime_input/." "$root/runtime/"
  admin cp -pR "$toolchain_input/." "$root/toolchain/"
  frida_name=frida
  if [[ "$RUNNER_OS" == Windows ]]; then frida_name=frida.dll; fi
  admin cp -L "$frida_input" "$root/native/$frida_name"
  if [[ "$RUNNER_OS" == Linux ]]; then
    [[ $# == 5 && -f "$5" ]]
    admin cp -L "$5" "$root/native/libcgroup.so"
    group="/sys/fs/cgroup/$(basename "$root")"
    # The mount root is exempt from cgroup-v2's no-internal-process constraint.
    printf '+memory\n' | sudo -n tee /sys/fs/cgroup/cgroup.subtree_control >/dev/null
    sudo -n mkdir "$group"
    printf '+memory\n' | sudo -n tee "$group/cgroup.subtree_control" >/dev/null
    printf '%s\n' "$group" | sudo -n tee "$root/cgroup-parent" >/dev/null
  fi
  printf 'version=1\nplatform=%s\n' "$RUNNER_OS" | admin tee "$root/installation" >/dev/null
  if [[ "$RUNNER_OS" != Windows ]]; then
    sudo -n chown -R 0:0 "$root"
    sudo -n chmod -R go-w "$root"
    sudo -n chmod 755 "$root" "$root/runtime" "$root/toolchain" "$root/native" "$root/scratch" "$root/history"
  fi
  printf 'RELEASE_SCHEDULER_ROOT=%s\nRELEASE_SCHEDULER_UID=%s\nRELEASE_SCHEDULER_GID=%s\n' "$root" "$uid" "$gid" >> "$GITHUB_ENV"
  complete=true
elif [[ "${1:-}" == cleanup ]]; then
  [[ $# == 2 ]] || exit 2
  root="$2"
  require_path_record "$root"
  [[ -f "$root/installation" && ! -L "$root" ]]
  case "$RUNNER_OS:$root" in
    Linux:/var/lib/codex-release-scheduler-* | macOS:/Library/codex-release-scheduler-*)
      parent=/var/lib
      if [[ "$RUNNER_OS" == macOS ]]; then parent=/Library; fi
      [[ "$(dirname -- "$root")" == "$parent" ]]
      bash "$RUNNER_TEMP/source-cache-build/runtime/bin/codex-tooling" validate-release-installation "$root"
      ;;
    Windows:*)
      temporary_root="$RUNNER_TEMP"
      if command -v cygpath >/dev/null 2>&1; then temporary_root="$(cygpath -u "$temporary_root")"; fi
      [[ "$(dirname -- "$root")" == "$temporary_root" && "$(basename -- "$root")" == codex-release-scheduler-* ]]
      ;;
    *) printf 'Installation is outside the owned platform namespace.\n' >&2; exit 2 ;;
  esac
  if [[ -e "$root/retained-owner" ]]; then
    printf 'Native ownership was not proven closed; retain the installation for terminal worker cleanup.\n' >&2
    exit 1
  fi
  if [[ "$RUNNER_OS" == Linux ]]; then
    group="$(cat "$root/cgroup-parent")"
    [[ "$group" == "/sys/fs/cgroup/$(basename "$root")" ]]
    # Never remove a containment while any original descendant still has a member.
    memberships="$(sudo -n find "$group" -name cgroup.procs -type f)"
    while IFS= read -r membership; do
      members="$(sudo -n cat "$membership")"
      [[ -z "$members" ]] || { printf 'Owned containment is still populated.\n' >&2; exit 1; }
    done <<< "$memberships"
    directories="$(sudo -n find "$group" -depth -type d)"
    while IFS= read -r directory; do sudo -n rmdir "$directory"; done <<< "$directories"
  fi
  admin rm -rf -- "$root"
else
  printf 'Usage: %s install RUNTIME TOOLCHAIN FRIDA [LIBCGROUP] | cleanup ROOT | prepare-debug-session ACTION_SOURCE CHECKOUT EVIDENCE | wait-for-client EVIDENCE\n' "$0" >&2
  exit 2
fi
