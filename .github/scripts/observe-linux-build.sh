#!/usr/bin/env bash
set -euo pipefail

# Reads one transient process resource with the supplied reader.
# Arguments: resource path, reader command and its options.
# Output: resource contents.
# Errors: reports reader errors while the resource still exists.
# Exit status: the reader's status.
read_process_resource() {
  local path="$1"
  shift
  local contents
  local status=0
  contents=$("$@" "$path" 2>&1) || status=$?
  if [[ "$status" -eq 0 ]]; then
    printf '%s\n' "$contents"
  elif [[ -e "$path" ]]; then
    printf '%s\n' "$contents" >&2
  fi
  return "$status"
}

# Reads the crate name from a compiler's NUL-delimited command line.
# Arguments: command-line resource path.
# Output: crate=<name> when --crate-name is present.
# Exit status: awk's status.
read_compiler_crate() {
  awk 'previous == "--crate-name" { print "crate=" $0 } { previous = $0 }' RS='\0' "$1"
}

sample_build_resources() {
  while true; do
    printf '\nBuild resources at %s\n' "$(date -u +%FT%TZ)"
    free -m
    df -h .
    df -i .
    ps -eo pid,ppid,pgid,rss,%cpu,comm --sort=-rss | sed -n '1,16p'
    awk '/^(oom_kill|allocstall.*|pgmajfault|pswpin|pswpout) / { print }' /proc/vmstat

    local compiler_pid
    local compiler_cgroup
    local resource_path
    for compiler_pid in $(ps -eo pid,comm | awk '$2 == "rustc" { print $1 }'); do
      printf 'Compiler PID %s\n' "$compiler_pid"
      if [[ -r "/proc/$compiler_pid/cmdline" ]]; then
        read_process_resource "/proc/$compiler_pid/cmdline" read_compiler_crate || continue
      fi
      if [[ -r "/proc/$compiler_pid/status" ]]; then
        read_process_resource "/proc/$compiler_pid/status" awk \
          '/^(VmPeak|VmRSS|VmSwap|Threads):/ { print }' || continue
      fi
      if [[ -r "/proc/$compiler_pid/oom_score" ]]; then
        printf 'oom_score='
        read_process_resource "/proc/$compiler_pid/oom_score" cat || continue
      fi
      if [[ -r "/proc/$compiler_pid/cgroup" ]]; then
        local cgroup_contents
        cgroup_contents=$(read_process_resource "/proc/$compiler_pid/cgroup" cat) || continue
        printf '%s\n' "$cgroup_contents"
        compiler_cgroup=$(awk -F: '$1 == "0" && $2 == "" { print $3 }' <<< "$cgroup_contents")
        if [[ -n "$compiler_cgroup" ]]; then
          for resource_path in memory.current memory.max memory.events memory.pressure; do
            if [[ -r "/sys/fs/cgroup$compiler_cgroup/$resource_path" ]]; then
              printf '%s: ' "$resource_path"
              read_process_resource "/sys/fs/cgroup$compiler_cgroup/$resource_path" cat || continue
            fi
          done
        fi
      fi
    done

    for resource_path in /proc/pressure/memory /sys/fs/cgroup/memory.current \
      /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.events; do
      if [[ -r "$resource_path" ]]; then
        printf '%s: ' "$resource_path"
        read_process_resource "$resource_path" cat || continue
      fi
    done
    sleep 15
  done
}

stop_resource_observers() {
  local observer_pid
  for observer_pid in $(jobs -pr); do
    kill -TERM -- "-${observer_pid}" 2>/dev/null || true
    wait "$observer_pid" 2>/dev/null || true
  done
}

observe_build() {
  if [[ "$#" -eq 0 ]]; then
    echo 'Usage: observe-linux-build.sh <command> [args...]' >&2
    return 2
  fi

  trap stop_resource_observers EXIT
  export -f sample_build_resources read_process_resource read_compiler_crate
  setsid bash -c sample_build_resources &
  setsid sudo -n dmesg --follow --time-format iso &
  setsid sudo -n journalctl --follow --no-pager --output short-iso-precise \
    --unit systemd-oomd.service &

  "$@"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  observe_build "$@"
fi
