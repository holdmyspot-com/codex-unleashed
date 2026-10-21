#!/usr/bin/env bash
# Runs one rustc invocation under the compiler scheduler that CompilerSchedulerService hosts.
#
# Cargo invokes this script as RUSTC_WRAPPER with the compiler and its arguments. Invocations that compile a crate
# wait for the scheduler's permission, run in their own process group so that the scheduler can pause, resume or kill
# the whole compiler, and report their exit status. Queries such as "rustc -vV" or "--print" run immediately.
set -u

crate=
previous=
for argument in "$@"; do
  case "$argument" in
    --print | --print=*)
      crate=
      break
      ;;
  esac
  if [[ "$previous" == "--crate-name" ]]; then
    crate="$argument"
  fi
  previous="$argument"
done
if [[ -z "$crate" ]]; then
  exec "$@"
fi

directory="$CODEX_COMPILER_SCHEDULER_DIR"
reply=
output="$(mktemp -d "$directory/output.XXXXXXXXXX")" || exit 1
trap 'rm -rf -- "$output"; rm -f -- "$reply"' EXIT
reply="$(mktemp -u "$directory/reply.XXXXXXXXXX")"
mkfifo -m 600 "$reply"
# Opening the reply FIFO for reading and writing returns at once, so the scheduler's open for writing never waits.
exec 3<>"$reply"
id=$$
process_group=

send() {
  printf '%s\n' "$*" > "$directory/requests"
}

# Ends the compiler and tells the scheduler, so that an interrupted build never leaves the scheduler waiting for it.
end() {
  if [[ -n "$process_group" ]]; then
    kill -s KILL -- "-$process_group" 2>/dev/null
  fi
  rm -f "$reply"
  send exited "$id" "$1"
  exit "$1"
}
trap 'end 129' HUP
trap 'end 130' INT
trap 'end 143' TERM

send request "$id" "$reply" "$crate"
read_reply() {
  read -r verb adjustment <&3
  # The scheduler has opened the reply FIFO once it replies, so its name is no longer needed.
  rm -f "$reply"
}
read_reply
while :; do
  if [[ "$verb" != "start" ]]; then
    echo "compiler scheduler: unexpected reply \"$verb\" while $crate waited to start" >&2
    end 1
  fi
  # setsid execs the compiler in place because a background job does not lead its process group, so the job's
  # process identifier also identifies the compiler's new process group.
  # Cargo treats metadata output as dependency completion. Discard an aborted attempt's output so that a retry
  # cannot report the same completion twice.
  setsid "$@" <&0 >"$output/stdout" 2>"$output/stderr" &
  process_group=$!
  # Newer compilers are likelier victims if the kernel must still kill one.
  printf '%s\n' "$adjustment" 2>/dev/null > "/proc/$process_group/oom_score_adj"
  send started "$id" "$process_group"
  # Bash reports a killed job on standard error; the scheduler's log already explains why it killed the compiler.
  wait "$process_group" 2>/dev/null
  status=$?
  process_group=
  send exited "$id" "$status"
  if [[ "$status" -ne 137 ]]; then
    cat "$output/stdout"
    cat "$output/stderr" >&2
    exit "$status"
  fi
  # The scheduler replies "retry" and later "start" to rerun a compiler that it killed, or "fail" once a rerun is
  # killed as well, and explains either decision in its own log.
  read_reply
  if [[ "$verb" != "retry" ]]; then
    cat "$output/stdout"
    cat "$output/stderr" >&2
    exit "$status"
  fi
  read_reply
done
