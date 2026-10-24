#!/usr/bin/env bash
# Exercise complete evidence, missing proof, and actual archived setup skips.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/assert-windows-lifecycle-evidence.sh"
work_root="$(mktemp -d "${TMPDIR:-${RUNNER_TEMP:-/tmp}}/windows-lifecycle-evidence-XXXXXX")"
trap 'rm -rf -- "$work_root"' EXIT

reject() {
  if bash "$checker" "$1" > "$work_root/result" 2>&1; then
    printf 'Incomplete evidence was accepted: %s\n' "$2" >&2
    exit 1
  fi
}

normal=unified_exec::tests::legacy_capture_emits_output_and_preserves_descendant_after_normal_exit
cancel=unified_exec::tests::legacy_capture_cancellation_terminates_descendants_without_timeout
tty=unified_exec::tests::legacy_tty_job_terminates_and_preserves_descendants
{
  printf 'running 3 tests\n'
  for name in "$normal" "$cancel"; do
    cancelled=false
    [[ "$name" != "$cancel" ]] || cancelled=true
    printf 'windows lifecycle started: %s\n' "$name"
    printf 'capture root wait ended: elapsed=2.5s timed_out=false cancelled=%s\n' "$cancelled"
    printf 'capture joining stdout: elapsed=2.5001s\n'
    printf 'capture stdout reader ended: elapsed=2.51s bytes=28\n'
    printf 'capture joining stderr: elapsed=2.511s\n'
    printf 'capture stderr reader ended: elapsed=2.52s bytes=0\n'
    printf 'capture readers joined: elapsed=2.521s\n'
    printf 'windows lifecycle completed: %s elapsed=2.6s\n' "$name"
    printf 'test %s ... ok\n' "$name"
  done
  printf 'windows lifecycle started: %s\n' "$tty"
  printf 'windows lifecycle tty phase completed: Terminate elapsed=9.5ms\n'
  printf 'windows lifecycle tty phase completed: Preserve elapsed=97µs\n'
  printf 'windows lifecycle completed: %s elapsed=4s\n' "$tty"
  printf 'test %s ... ok\n' "$tty"
  printf 'test result: ok. 3 passed; 0 failed; 0 ignored; 0 measured; 278 filtered out; finished in 9.20s\n'
} > "$work_root/complete"
bash "$checker" "$work_root/complete"
sed 's/$/\r/' "$work_root/complete" > "$work_root/crlf"
bash "$checker" "$work_root/crlf"

# Exact launch lines retained from run40050251809, Windows2022 job120748927511.
# Rust Debug strings contain escaped Windows paths and encoded child commands.
cat > "$work_root/launch-lines" <<'WINDOWS_2022_LAUNCHES'
windows lifecycle tty launch: lifecycle=Terminate parent_command="[System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpqufzlB\\parent-entered', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpqufzlB\\parent-before-spawn', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); try { Start-Process -WindowStyle Hidden -FilePath 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' -ArgumentList '-NoProfile','-EncodedCommand','WwBTAHkAcwB0AGUAbQAuAEkATwAuAEYAaQBsAGUAXQA6ADoAVwByAGkAdABlAEEAbABsAFQAZQB4AHQAKAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAHEAdQBmAHoAbABCAFwAYwBoAGkAbABkAC0AZQBuAHQAZQByAGUAZAAnACwAIABbAFMAeQBzAHQAZQBtAC4ARABhAHQAZQBUAGkAbQBlAF0AOgA6AFUAdABjAE4AbwB3AC4AVABvAFMAdAByAGkAbgBnACgAJwBPACcAKQAgACsAIAAnACAAcABpAGQAPQAnACAAKwAgACQAUABJAEQAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBMAGkAdABlAHIAYQBsAFAAYQB0AGgAIAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAHEAdQBmAHoAbABCAFwAZABlAHMAYwBlAG4AZABhAG4AdAAtAHMAdABhAHIAdABlAGQALgBwAGUAbgBkAGkAbgBnACcAIAAtAFYAYQBsAHUAZQAgACQAUABJAEQAOwAgAE0AbwB2AGUALQBJAHQAZQBtACAALQBMAGkAdABlAHIAYQBsAFAAYQB0AGgAIAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAHEAdQBmAHoAbABCAFwAZABlAHMAYwBlAG4AZABhAG4AdAAtAHMAdABhAHIAdABlAGQALgBwAGUAbgBkAGkAbgBnACcAIAAtAEQAZQBzAHQAaQBuAGEAdABpAG8AbgAgACcARAA6AFwAYQBcAF8AdABlAG0AcABcAC4AdABtAHAAcQB1AGYAegBsAEIAXABkAGUAcwBjAGUAbgBkAGEAbgB0AC0AcwB0AGEAcgB0AGUAZAAnADsAIABTAHQAYQByAHQALQBTAGwAZQBlAHAAIAAtAFMAZQBjAG8AbgBkAHMAIAAzADAA' -RedirectStandardOutput 'D:\\a\\_temp\\.tmpqufzlB\\descendant.stdout' -RedirectStandardError 'D:\\a\\_temp\\.tmpqufzlB\\descendant.stderr'; [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpqufzlB\\parent-after-spawn', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); Start-Sleep -Seconds 30 } catch { [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpqufzlB\\parent-launch-error', [string]::Join([System.Environment]::NewLine, @('phase=parent-command', $_.ToString(), $_.Exception.ToString(), $_.FullyQualifiedErrorId, $_.CategoryInfo.ToString(), $_.InvocationInfo.PositionMessage, $_.ScriptStackTrace))); throw }" child_command="[System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpqufzlB\\child-entered', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); Set-Content -LiteralPath 'D:\\a\\_temp\\.tmpqufzlB\\descendant-started.pending' -Value $PID; Move-Item -LiteralPath 'D:\\a\\_temp\\.tmpqufzlB\\descendant-started.pending' -Destination 'D:\\a\\_temp\\.tmpqufzlB\\descendant-started'; Start-Sleep -Seconds 30"
windows lifecycle tty launch: lifecycle=Preserve parent_command="[System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpjq8g0l\\parent-entered', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpjq8g0l\\parent-before-spawn', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); try { Start-Process -WindowStyle Hidden -FilePath 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' -ArgumentList '-NoProfile','-EncodedCommand','WwBTAHkAcwB0AGUAbQAuAEkATwAuAEYAaQBsAGUAXQA6ADoAVwByAGkAdABlAEEAbABsAFQAZQB4AHQAKAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAGoAcQA4AGcAMABsAFwAYwBoAGkAbABkAC0AZQBuAHQAZQByAGUAZAAnACwAIABbAFMAeQBzAHQAZQBtAC4ARABhAHQAZQBUAGkAbQBlAF0AOgA6AFUAdABjAE4AbwB3AC4AVABvAFMAdAByAGkAbgBnACgAJwBPACcAKQAgACsAIAAnACAAcABpAGQAPQAnACAAKwAgACQAUABJAEQAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBMAGkAdABlAHIAYQBsAFAAYQB0AGgAIAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAGoAcQA4AGcAMABsAFwAZABlAHMAYwBlAG4AZABhAG4AdAAtAHMAdABhAHIAdABlAGQALgBwAGUAbgBkAGkAbgBnACcAIAAtAFYAYQBsAHUAZQAgACQAUABJAEQAOwAgAE0AbwB2AGUALQBJAHQAZQBtACAALQBMAGkAdABlAHIAYQBsAFAAYQB0AGgAIAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAGoAcQA4AGcAMABsAFwAZABlAHMAYwBlAG4AZABhAG4AdAAtAHMAdABhAHIAdABlAGQALgBwAGUAbgBkAGkAbgBnACcAIAAtAEQAZQBzAHQAaQBuAGEAdABpAG8AbgAgACcARAA6AFwAYQBcAF8AdABlAG0AcABcAC4AdABtAHAAagBxADgAZwAwAGwAXABkAGUAcwBjAGUAbgBkAGEAbgB0AC0AcwB0AGEAcgB0AGUAZAAnADsAIAAkAGQAZQBhAGQAbABpAG4AZQA9ACgARwBlAHQALQBEAGEAdABlACkALgBBAGQAZABTAGUAYwBvAG4AZABzACgAMwAwACkAOwAgAHcAaABpAGwAZQAgACgALQBuAG8AdAAgACgAVABlAHMAdAAtAFAAYQB0AGgAIAAtAEwAaQB0AGUAcgBhAGwAUABhAHQAaAAgACcARAA6AFwAYQBcAF8AdABlAG0AcABcAC4AdABtAHAAagBxADgAZwAwAGwAXAByAGUAbABlAGEAcwBlAC0AZABlAHMAYwBlAG4AZABhAG4AdAAnACkAKQAgAHsAIABpAGYAIAAoACgARwBlAHQALQBEAGEAdABlACkAIAAtAGcAZQAgACQAZABlAGEAZABsAGkAbgBlACkAIAB7ACAAZQB4AGkAdAAgADMAIAB9ADsAIABTAHQAYQByAHQALQBTAGwAZQBlAHAAIAAtAE0AaQBsAGwAaQBzAGUAYwBvAG4AZABzACAAMgA1ACAAfQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBMAGkAdABlAHIAYQBsAFAAYQB0AGgAIAAnAEQAOgBcAGEAXABfAHQAZQBtAHAAXAAuAHQAbQBwAGoAcQA4AGcAMABsAFwAZABlAHMAYwBlAG4AZABhAG4AdAAtAHMAdQByAHYAaQB2AGUAZAAnACAALQBWAGEAbAB1AGUAIABzAHUAcgB2AGkAdgBlAGQA' -RedirectStandardOutput 'D:\\a\\_temp\\.tmpjq8g0l\\descendant.stdout' -RedirectStandardError 'D:\\a\\_temp\\.tmpjq8g0l\\descendant.stderr'; [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpjq8g0l\\parent-after-spawn', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); while (-not (Test-Path -LiteralPath 'D:\\a\\_temp\\.tmpjq8g0l\\descendant-started')) { Start-Sleep -Milliseconds 25 } } catch { [System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpjq8g0l\\parent-launch-error', [string]::Join([System.Environment]::NewLine, @('phase=parent-command', $_.ToString(), $_.Exception.ToString(), $_.FullyQualifiedErrorId, $_.CategoryInfo.ToString(), $_.InvocationInfo.PositionMessage, $_.ScriptStackTrace))); throw }" child_command="[System.IO.File]::WriteAllText('D:\\a\\_temp\\.tmpjq8g0l\\child-entered', [System.DateTime]::UtcNow.ToString('O') + ' pid=' + $PID); Set-Content -LiteralPath 'D:\\a\\_temp\\.tmpjq8g0l\\descendant-started.pending' -Value $PID; Move-Item -LiteralPath 'D:\\a\\_temp\\.tmpjq8g0l\\descendant-started.pending' -Destination 'D:\\a\\_temp\\.tmpjq8g0l\\descendant-started'; $deadline=(Get-Date).AddSeconds(30); while (-not (Test-Path -LiteralPath 'D:\\a\\_temp\\.tmpjq8g0l\\release-descendant')) { if ((Get-Date) -ge $deadline) { exit 3 }; Start-Sleep -Milliseconds 25 }; Set-Content -LiteralPath 'D:\\a\\_temp\\.tmpjq8g0l\\descendant-survived' -Value survived"
WINDOWS_2022_LAUNCHES
mapfile -t launch_lines < "$work_root/launch-lines"
while IFS= read -r line; do
  case "$line" in
    'windows lifecycle tty phase completed: Terminate '*) printf '%s\n' "${launch_lines[0]}" ;;
    'windows lifecycle tty phase completed: Preserve '*) printf '%s\n' "${launch_lines[1]}" ;;
  esac
  printf '%s\n' "$line"
done < "$work_root/complete" > "$work_root/staged"
bash "$checker" "$work_root/staged"
sed 's/$/\r/' "$work_root/staged" > "$work_root/staged-crlf"
bash "$checker" "$work_root/staged-crlf"
for mutation in \
  's/windows lifecycle tty launch:/windows lifecycle unknown launch:/' \
  's/lifecycle=Terminate parent_command=/lifecycle=Other parent_command=/' \
  's/ parent_command="/ parent_command=/' \
  's/ child_command="/ child_command=/' \
  '/windows lifecycle tty launch:/s/$/ trailing/' \
  '/windows lifecycle tty launch:/p'; do
  sed "$mutation" "$work_root/staged" > "$work_root/bad-launch"
  reject "$work_root/bad-launch" "invalid staged launch: $mutation"
done
{ cat "$work_root/complete"; cat "$work_root/launch-lines"; } > "$work_root/misplaced-launch"
reject "$work_root/misplaced-launch" 'launch outside TTY test'
sed 's/lifecycle=Terminate parent_command=/lifecycle=Preserve parent_command=/' "$work_root/staged" > "$work_root/early-preserve-launch"
reject "$work_root/early-preserve-launch" 'Preserve launch before Terminate phase'

# Every required line carries independent proof, including both TTY phases.
line_number=0
missing_checks=0
mapfile -t complete_lines < "$work_root/complete"
for line in "${complete_lines[@]}"; do
  line_number=$((line_number + 1))
  [[ "$line" == 'running 3 tests' ]] && continue
  sed "${line_number}d" "$work_root/complete" > "$work_root/missing"
  reject "$work_root/missing" "missing line $line_number: $line"
  missing_checks=$((missing_checks + 1))
done

sed 's/timed_out=false/timed_out=true/' "$work_root/complete" > "$work_root/timed-out"
reject "$work_root/timed-out" 'unexpected capture timeout'
sed 's/cancelled=true/cancelled=false/' "$work_root/complete" > "$work_root/not-cancelled"
reject "$work_root/not-cancelled" 'cancellation outcome absent'
sed 's/elapsed=2.5s/elapsed=unknown/' "$work_root/complete" > "$work_root/malformed"
reject "$work_root/malformed" 'malformed timestamp'
sed 's/3 passed; 0 failed/2 passed; 1 failed/' "$work_root/complete" > "$work_root/failed"
reject "$work_root/failed" 'failed libtest result'
sed '2p' "$work_root/complete" > "$work_root/duplicate"
reject "$work_root/duplicate" 'duplicate start'
sed '/capture readers joined/p' "$work_root/complete" > "$work_root/duplicate-timing"
reject "$work_root/duplicate-timing" 'duplicate capture completion'
sed '/tty phase completed: Preserve/p' "$work_root/complete" > "$work_root/duplicate-phase"
reject "$work_root/duplicate-phase" 'duplicate TTY phase'
{ cat "$work_root/complete"; printf 'skipping cancellation regression test: PowerShell 7 is not installed\n'; } > "$work_root/skipped"
reject "$work_root/skipped" 'setup skip despite successful summary'

real_skips=0
for log in "$@"; do
  [[ -f "$log" ]] || { printf 'Archived console is missing: %s\n' "$log" >&2; exit 1; }
  grep -Fq 'PowerShell 7 is not installed' "$log" || { printf 'Archived console has no setup skip: %s\n' "$log" >&2; exit 1; }
  reject "$log" "archived skipped console: $log"
  real_skips=$((real_skips + 1))
done
printf 'Evidence regression passed: four valid encodings, %s missing-proof cases, existing invalid cases and eight launch rejection cases, %s archived skips.\n' "$missing_checks" "$real_skips"
