#!/usr/bin/env bash
# Reject successful libtest setup skips and incomplete lifecycle diagnostics.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 CONSOLE_LOG" >&2
  exit 2
fi

fail() {
  printf 'Windows lifecycle evidence: %s\n' "$*" >&2
  exit 1
}

[[ -f "$1" ]] || fail "console log is missing: $1"
capture_normal=unified_exec::tests::legacy_capture_emits_output_and_preserves_descendant_after_normal_exit
capture_cancel=unified_exec::tests::legacy_capture_cancellation_terminates_descendants_without_timeout
tty=unified_exec::tests::legacy_tty_job_terminates_and_preserves_descendants
tests=("$capture_normal" "$capture_cancel" "$tty")
declare -A known started completed passed capture phases launches
for name in "${tests[@]}"; do
  known["$name"]=1
done
current=
summary_count=0
duration='[0-9]+([.][0-9]+)?(ns|µs|us|ms|s)'
completed_pattern="^windows lifecycle completed: ([a-zA-Z0-9_:]+) elapsed=(${duration})$"
phase_pattern="^windows lifecycle tty phase completed: (Terminate|Preserve) elapsed=(${duration})$"
# Each command is one Rust Debug string: embedded quotes/backslashes are escaped.
launch_pattern='^windows lifecycle tty launch: lifecycle=(Terminate|Preserve) parent_command="([^"\\]|\\.)+" child_command="([^"\\]|\\.)+"$'
passed_pattern='^test ([a-zA-Z0-9_:]+) \.\.\. ok$'
summary_pattern='^test result: ok[.] 3 passed; 0 failed; 0 ignored; 0 measured; [0-9]+ filtered out; finished in [0-9]+([.][0-9]+)?s$'

while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  case "$line" in
    'skipping '*) fail "test returned early: $line" ;;
    'windows lifecycle started: '*)
      name="${line#'windows lifecycle started: '}"
      [[ "${known[$name]:-0}" == 1 ]] || fail "unknown started test: $name"
      [[ -z "$current" && "${started[$name]:-0}" == 0 ]] || fail "duplicate or overlapping start: $name"
      started["$name"]=1
      current="$name"
      ;;
    'windows lifecycle completed: '*)
      [[ "$line" =~ $completed_pattern ]] || fail "malformed completion: $line"
      name="${BASH_REMATCH[1]}"
      [[ "$current" == "$name" && "${completed[$name]:-0}" == 0 ]] || fail "completion without unique start: $name"
      completed["$name"]=1
      current=
      ;;
    'windows lifecycle tty phase completed: '*)
      [[ "$current" == "$tty" && "$line" =~ $phase_pattern ]] || fail "malformed or misplaced TTY phase: $line"
      phase="${BASH_REMATCH[1]}"
      [[ "${phases[$phase]:-0}" == 0 ]] || fail "duplicate TTY phase: $phase"
      if [[ "$phase" == Preserve ]]; then
        [[ "${phases[Terminate]:-0}" == 1 ]] || fail "TTY preservation preceded termination coverage"
      fi
      phases["$phase"]=1
      ;;
    'windows lifecycle tty launch: '*)
      [[ "$current" == "$tty" && "$line" =~ $launch_pattern ]] || fail "malformed or misplaced TTY launch: $line"
      phase="${BASH_REMATCH[1]}"
      [[ "${launches[$phase]:-0}" == 0 && "${phases[$phase]:-0}" == 0 ]] || fail "duplicate or completed TTY launch: $phase"
      if [[ "$phase" == Preserve ]]; then
        [[ "${phases[Terminate]:-0}" == 1 ]] || fail "TTY preservation launch preceded termination coverage"
      fi
      launches["$phase"]=1
      ;;
    'windows lifecycle '*) fail "unknown lifecycle marker: $line" ;;
    'capture root wait ended: '*|'capture stdout reader ended: '*|'capture stderr reader ended: '*|'capture joining stdout: '*|'capture joining stderr: '*|'capture readers joined: '*)
      [[ "$current" == "$capture_normal" || "$current" == "$capture_cancel" ]] || fail "capture timestamp outside capture test: $line"
      stage="${line%%: *}"
      suffix="${line#*: }"
      case "$stage" in
        'capture root wait ended')
          cancelled=false
          [[ "$current" != "$capture_cancel" ]] || cancelled=true
          pattern="^elapsed=(${duration}) timed_out=false cancelled=${cancelled}$"
          ;;
        'capture stdout reader ended'|'capture stderr reader ended') pattern="^elapsed=(${duration}) bytes=[0-9]+$" ;;
        *) pattern="^elapsed=(${duration})$" ;;
      esac
      [[ "$suffix" =~ $pattern ]] || fail "malformed capture timestamp or unexpected outcome: $line"
      key="$current:$stage"
      [[ "${capture[$key]:-0}" == 0 ]] || fail "duplicate capture timestamp: $key"
      capture["$key"]=1
      ;;
    'test unified_exec::tests::legacy_'*)
      [[ "$line" =~ $passed_pattern ]] || fail "test did not pass: $line"
      name="${BASH_REMATCH[1]}"
      [[ "${known[$name]:-0}" == 1 && "${passed[$name]:-0}" == 0 ]] || fail "unexpected or duplicate test result: $name"
      passed["$name"]=1
      ;;
    'test result: '*)
      [[ "$line" =~ $summary_pattern ]] || fail "unexpected libtest result: $line"
      summary_count=$((summary_count + 1))
      ;;
  esac
done < "$1"

[[ -z "$current" && "$summary_count" == 1 ]] || fail "incomplete test or missing unique successful summary"
for name in "${tests[@]}"; do
  [[ "${started[$name]:-0}" == 1 && "${completed[$name]:-0}" == 1 && "${passed[$name]:-0}" == 1 ]] || fail "missing start, completion or passed result: $name"
done
for name in "$capture_normal" "$capture_cancel"; do
  for stage in 'capture root wait ended' 'capture stdout reader ended' 'capture stderr reader ended' 'capture joining stdout' 'capture joining stderr' 'capture readers joined'; do
    [[ "${capture[$name:$stage]:-0}" == 1 ]] || fail "missing capture timing: $name: $stage"
  done
done
[[ "${phases[Terminate]:-0}" == 1 && "${phases[Preserve]:-0}" == 1 ]] || fail "missing TTY phase completion"
printf 'Windows lifecycle evidence accepted: three tests, both TTY phases and both capture timing sets.\n'
