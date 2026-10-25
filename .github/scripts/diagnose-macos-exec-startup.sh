#!/usr/bin/env bash
set -euo pipefail

main() (
  if [[ $# != 3 ]]; then
    printf 'Usage: %s EXECUTABLE OUTPUT_DIRECTORY DYLD_STATISTICS\n' "$0" >&2
    exit 2
  fi
  local executable="$1" output="$2" dyld_statistics="$3"
  if [[ "$executable" != /* || ! -x "$executable" ]]; then
    printf 'Executable must be an absolute path to an executable file: %s\n' "$executable" >&2
    exit 2
  fi
  if [[ "$dyld_statistics" != true && "$dyld_statistics" != false ]]; then
    printf 'DYLD_STATISTICS must be true or false.\n' >&2
    exit 2
  fi
  if [[ "$output" != /* ]]; then
    printf 'Output directory must be an absolute path: %s\n' "$output" >&2
    exit 2
  fi
  mkdir "$output"
  local scratch
  scratch="$(mktemp -d "$output/work-XXXXXX")"
  trap 'rm -rf -- "$scratch"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  local iteration mode home cwd status result=0
  local modes=(normal)
  if [[ "$dyld_statistics" == true ]]; then
    modes+=(dyld)
  fi
  printf 'mode\titeration\texit_status\n' > "$output/status.tsv"
  for mode in "${modes[@]}"; do
    local iterations=(1 2 3 4 5 6)
    if [[ "$mode" == dyld ]]; then
      iterations=(1)
    fi
    for iteration in "${iterations[@]}"; do
      home="$scratch/$mode-$iteration/home"
      cwd="$scratch/$mode-$iteration/cwd"
      mkdir -p "$home" "$cwd"
      printf 'Timing archived codex-exec --help: mode=%s iteration=%s\n' "$mode" "$iteration"
      date -u '+%Y-%m-%dT%H:%M:%SZ' > "$output/$mode-$iteration.started"
      local environment=(env -u CODEX_ACCESS_TOKEN -u CODEX_API_KEY -u OPENAI_API_KEY
        -u DYLD_PRINT_STATISTICS "HOME=$home" "CODEX_HOME=$home" "CODEX_SQLITE_HOME=$home")
      if [[ "$mode" == dyld ]]; then
        environment+=(DYLD_PRINT_STATISTICS=1)
      fi
      if (
        cd "$cwd"
        {
          time -p "${environment[@]}" "$executable" --help \
            > "$output/$mode-$iteration.stdout" 2> "$output/$mode-$iteration.stderr"
        } 2> "$output/$mode-$iteration.time"
      ); then
        status=0
      else
        status=$?
        result="$status"
      fi
      printf '%s\t%s\t%s\n' "$mode" "$iteration" "$status" >> "$output/status.tsv"
      printf 'Completed archived help launch: mode=%s iteration=%s exit=%s\n' "$mode" "$iteration" "$status"
      cat "$output/$mode-$iteration.time"
    done
  done
  exit "$result"
)

main "$@"
