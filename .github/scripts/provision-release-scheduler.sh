#!/usr/bin/env bash
# Install or release one job-exclusive scheduler runtime, toolchain and native authority.
# Usage: provision-release-scheduler.sh install RUNTIME TOOLCHAIN FRIDA [LIBCGROUP]
#        provision-release-scheduler.sh cleanup ROOT
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

# Run one filesystem administration operation with the platform's existing authority.
# Arguments: exact executable and separate arguments, without shell interpretation.
# Environment: RUNNER_OS selects supported privilege behavior.
# Side effects: executes the selected operation, preserving its actual status.
# Exit status: the operation's status.
admin() {
  if [[ "$RUNNER_OS" == Windows ]]; then "$@"; else sudo -n "$@"; fi
}

if [[ "${1:-}" == install ]]; then
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
  printf 'Usage: %s install RUNTIME TOOLCHAIN FRIDA [LIBCGROUP] | cleanup ROOT\n' "$0" >&2
  exit 2
fi
