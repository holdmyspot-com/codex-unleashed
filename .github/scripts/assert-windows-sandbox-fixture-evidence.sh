#!/usr/bin/env bash
# Accept only the exact native helper and PowerShell fixture evidence.
set -euo pipefail

if [[ $# -ne 2 || ( "$2" != x86_64 && "$2" != aarch64 ) ]]; then
  echo "Usage: $0 EVIDENCE_DIRECTORY {x86_64|aarch64}" >&2
  exit 2
fi
root="$1"
arch="$2"
fail() { printf 'Windows sandbox fixture evidence: %s\n' "$*" >&2; exit 1; }
helpers=(
  copies_missing_helper
  permission_denied_rejects_directory_destination
  permission_denied_rejects_stale_helper
  reuses_helper_while_windows_denies_replacement
  reuses_identical_helper_without_opening_it_for_write
  sharing_violation_accepts_identical_concurrent_publication
  sharing_violation_checks_bytes_beyond_first_buffer
  sharing_violation_rejects_different_helper
  sharing_violation_rejects_missing_helper
  unrelated_copy_failure_is_preserved
)
legacy=(
  legacy_non_tty_powershell_interrupt_terminates_process
  legacy_capture_emits_output_and_preserves_descendant_after_normal_exit
  legacy_capture_cancellation_terminates_descendants_without_timeout
  legacy_tty_job_terminates_and_preserves_descendants
  legacy_tty_powershell_emits_output_and_accepts_input
)
for gate in helpers elevated legacy; do
  [[ -f "$root/$gate.log" && -f "$root/$gate.status" ]] || fail "missing $gate receipt"
  [[ "$(cat "$root/$gate.status")" == 0 ]] || fail "$gate wrapper failed"
  mapfile -t lines < <(tr -d '\r' < "$root/$gate.log")
  expected=()
  case "$gate" in
    helpers) for name in "${helpers[@]}"; do expected+=("helper_staging_tests::$name"); done ;;
    elevated) expected=(windows_elevated_unified_exec_enforces_large_recursive_deny_reads) ;;
    legacy) for name in "${legacy[@]}"; do expected+=("unified_exec::tests::$name"); done ;;
  esac
  declare -A seen=()
  summary=0
  powershell=0
  helper_arch=0
  terminate=0
  preserve=0
  pass_pattern='^test ([a-zA-Z0-9_:]+) \.\.\. ok$'
  summary_pattern="^test result: ok[.] ${#expected[@]} passed; 0 failed; 0 ignored; 0 measured; [0-9]+ filtered out; finished in [0-9]+([.][0-9]+)?s$"
  for line in "${lines[@]}"; do
    case "$line" in
      'skipping '*|'Skipping '*|'skip:'*) fail "early return: $line" ;;
      'test result: '*)
        [[ "$line" =~ $summary_pattern ]] || fail "unexpected $gate summary: $line"
        summary=$((summary + 1))
        ;;
      'test '* )
        [[ "$line" =~ $pass_pattern ]] || fail "non-passing $gate test: $line"
        name="${BASH_REMATCH[1]}"
        found=0
        for required in "${expected[@]}"; do [[ "$name" != "$required" ]] || found=1; done
        [[ "$found" == 1 && "${seen[$name]:-0}" == 0 ]] || fail "unexpected or duplicate $gate test: $name"
        seen["$name"]=1
        ;;
      'windows fixture acceptance: powershell_arch='*)
        [[ "$gate" == legacy && "$line" == "windows fixture acceptance: powershell_arch=$arch" ]] || fail "wrong PowerShell architecture: $line"
        powershell=$((powershell + 1))
        ;;
      'windows fixture acceptance: helper_arch='*)
        [[ "$gate" != legacy && "$line" == "windows fixture acceptance: helper_arch=$arch" ]] || fail "wrong helper architecture: $line"
        helper_arch=$((helper_arch + 1))
        ;;
      'windows fixture acceptance: tty_phase=Terminate')
        [[ "$gate" == legacy && "$terminate" == 0 && "$preserve" == 0 ]] || fail 'duplicate or misplaced Terminate phase'
        terminate=1
        ;;
      'windows fixture acceptance: tty_phase=Preserve')
        [[ "$gate" == legacy && "$terminate" == 1 && "$preserve" == 0 ]] || fail 'duplicate or misplaced Preserve phase'
        preserve=1
        ;;
      'windows fixture acceptance: '*) fail "unknown marker: $line" ;;
    esac
  done
  [[ "$summary" == 1 ]] || fail "$gate has no unique successful summary"
  for name in "${expected[@]}"; do [[ "${seen[$name]:-0}" == 1 ]] || fail "missing $gate test: $name"; done
  if [[ "$gate" == legacy ]]; then
    [[ "$powershell" == 5 && "$terminate" == 1 && "$preserve" == 1 ]] || fail 'missing PowerShell body or TTY phase proof'
  else
    (( helper_arch >= ${#expected[@]} )) || fail "missing $gate compiled architecture proof"
  fi
  unset seen
done
printf 'Windows sandbox fixture evidence accepted: %s, ten helper tests, elevated regression, five PowerShell tests and both TTY phases.\n' "$arch"
