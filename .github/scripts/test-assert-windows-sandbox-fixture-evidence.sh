#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/assert-windows-sandbox-fixture-evidence.sh"
work="$(mktemp -d "${TMPDIR:-${RUNNER_TEMP:-/tmp}}/windows-sandbox-fixture-evidence-XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/valid" "$work/candidate"
helpers=(
  copies_missing_helper permission_denied_rejects_directory_destination
  permission_denied_rejects_stale_helper reuses_helper_while_windows_denies_replacement
  reuses_identical_helper_without_opening_it_for_write
  sharing_violation_accepts_identical_concurrent_publication
  sharing_violation_checks_bytes_beyond_first_buffer sharing_violation_rejects_different_helper
  sharing_violation_rejects_missing_helper unrelated_copy_failure_is_preserved
)
legacy=(
  legacy_non_tty_powershell_interrupt_terminates_process
  legacy_capture_emits_output_and_preserves_descendant_after_normal_exit
  legacy_capture_cancellation_terminates_descendants_without_timeout
  legacy_tty_job_terminates_and_preserves_descendants
  legacy_tty_powershell_emits_output_and_accepts_input
)
for gate in helpers elevated legacy; do
  names=()
  case "$gate" in
    helpers) for name in "${helpers[@]}"; do names+=("helper_staging_tests::$name"); done ;;
    elevated) names=(windows_elevated_unified_exec_enforces_large_recursive_deny_reads) ;;
    legacy) for name in "${legacy[@]}"; do names+=("unified_exec::tests::$name"); done ;;
  esac
  {
    printf 'running %s tests\n' "${#names[@]}"
    for name in "${names[@]}"; do printf 'test %s ... ok\n' "$name"; done
    printf '\nsuccesses:\n'
    for name in "${names[@]}"; do
      printf '\n---- %s stdout ----\n' "$name"
      if [[ "$gate" == legacy ]]; then
        printf 'windows fixture acceptance: powershell_arch=x86_64\n'
      else
        printf 'windows fixture acceptance: helper_arch=x86_64\n'
      fi
    done
    if [[ "$gate" == legacy ]]; then
      printf 'windows fixture acceptance: tty_phase=Terminate\nwindows fixture acceptance: tty_phase=Preserve\n'
    fi
    printf '\ntest result: ok. %s passed; 0 failed; 0 ignored; 0 measured; 70 filtered out; finished in 1.00s\n' "${#names[@]}"
  } > "$work/valid/$gate.log"
  printf '0\n' > "$work/valid/$gate.status"
done
bash "$checker" "$work/valid" x86_64
for gate in helpers elevated legacy; do
  sed 's/x86_64/aarch64/g;s/$/\r/' "$work/valid/$gate.log" > "$work/candidate/$gate.log"
  cp "$work/valid/$gate.status" "$work/candidate/$gate.status"
done
bash "$checker" "$work/candidate" aarch64
reset_candidate() { cp "$work/valid/"* "$work/candidate/"; }
reject() {
  if bash "$checker" "$work/candidate" x86_64 > "$work/result" 2>&1; then
    printf 'Incomplete fixture evidence accepted: %s\n' "$1" >&2
    exit 1
  fi
}
checks=0
for gate in helpers elevated legacy; do
  # Each test result is independently necessary, including the Windows-only lock test.
  while IFS= read -r name; do
    reset_candidate
    awk -v name="$name" '$0 != name' "$work/valid/$gate.log" > "$work/candidate/$gate.log"
    reject "missing $name"
    checks=$((checks + 1))
  done < <(sed -n '/^test .* \.\.\. ok$/p' "$work/valid/$gate.log")
  for mutation in \
    '/^test result:/d' \
    's/0 ignored/1 ignored/' \
    's/0 failed/1 failed/' \
    's/ passed;/ filtered;/' \
    's/_arch=x86_64/_arch=aarch64/' \
    '/^windows fixture acceptance:/d' \
    '/^test result:/p'; do
    reset_candidate
    sed "$mutation" "$work/valid/$gate.log" > "$work/candidate/$gate.log"
    reject "$gate mutation $mutation"
    checks=$((checks + 1))
  done
  reset_candidate
  printf '1\n' > "$work/candidate/$gate.status"
  reject "$gate nonzero wrapper"
  reset_candidate
  printf 'test unselected_test ... ok\n' >> "$work/candidate/$gate.log"
  reject "$gate extra test"
  reset_candidate
  printf 'skipping fixture because prerequisite is absent\n' >> "$work/candidate/$gate.log"
  reject "$gate skip"
  checks=$((checks + 3))
done
for mutation in \
  '0,/^windows fixture acceptance: powershell_arch=/ { /^windows fixture acceptance: powershell_arch=/d; }' \
  '/powershell_arch/p' \
  '/tty_phase=Terminate/d' \
  '/tty_phase=Preserve/d' \
  '/tty_phase=Terminate/p' \
  's/tty_phase=Terminate/tty_phase=Preserve/'; do
  reset_candidate
  sed "$mutation" "$work/valid/legacy.log" > "$work/candidate/legacy.log"
  reject "legacy body proof mutation $mutation"
  checks=$((checks + 1))
done
reset_candidate
printf 'running 0 tests\ntest result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 75 filtered out; finished in 0.00s\n' > "$work/candidate/legacy.log"
reject 'zero selected tests'
printf 'Windows sandbox fixture evidence checks passed: two valid transcripts, %s rejected mutations and zero selection.\n' "$checks"
