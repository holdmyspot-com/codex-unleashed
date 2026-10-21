#!/usr/bin/env bash
set -euo pipefail

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
        awk 'previous == "--crate-name" { print "crate=" $0 } { previous = $0 }' \
          RS='\0' "/proc/$compiler_pid/cmdline" || continue
      fi
      if [[ -r "/proc/$compiler_pid/status" ]]; then
        awk '/^(VmPeak|VmRSS|VmSwap|Threads):/ { print }' "/proc/$compiler_pid/status" || continue
      fi
      if [[ -r "/proc/$compiler_pid/oom_score" ]]; then
        printf 'oom_score='
        cat "/proc/$compiler_pid/oom_score" || continue
      fi
      if [[ -r "/proc/$compiler_pid/cgroup" ]]; then
        cat "/proc/$compiler_pid/cgroup" || continue
        compiler_cgroup="$(awk -F: '$1 == "0" && $2 == "" { print $3 }' "/proc/$compiler_pid/cgroup")" || continue
        if [[ -n "$compiler_cgroup" ]]; then
          for resource_path in memory.current memory.max memory.events memory.pressure; do
            if [[ -r "/sys/fs/cgroup$compiler_cgroup/$resource_path" ]]; then
              printf '%s: ' "$resource_path"
              cat "/sys/fs/cgroup$compiler_cgroup/$resource_path" || continue
            fi
          done
        fi
      fi
    done

    for resource_path in /proc/pressure/memory /sys/fs/cgroup/memory.current \
      /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.events; do
      if [[ -r "$resource_path" ]]; then
        printf '%s: ' "$resource_path"
        cat "$resource_path"
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
  export -f sample_build_resources
  setsid bash -c sample_build_resources &
  setsid sudo -n dmesg --follow --time-format iso &
  setsid sudo -n journalctl --follow --no-pager --output short-iso-precise \
    --unit systemd-oomd.service &

  "$@"
}

observe_build "$@"
