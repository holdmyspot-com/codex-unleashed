#!/usr/bin/env bash
# Exercises tooling setup's fail-before-compilation behavior with controlled util-linux command results.
set -euo pipefail

project_root="${1:?Pass the project checkout root}"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name owned test storage}/ci-memory-XXXXXX")"
trap 'rm -rf -- "$fixtures"' EXIT

for scenario in disable-failure remaining-swap success warm-runtime; do
  root="$fixtures/$scenario"
  mkdir -p "$root/.github/actions/setup-tooling" "$root/.github/scripts" "$root/tooling/bin" "$root/bin"
  cp "$project_root/.github/actions/setup-tooling/setup.sh" "$root/.github/actions/setup-tooling/setup.sh"
  if [[ -f "$project_root/.github/scripts/disable-ci-swap.sh" ]]; then
    cp "$project_root/.github/scripts/disable-ci-swap.sh" "$root/.github/scripts/disable-ci-swap.sh"
  fi
  cat > "$root/bin/sudo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'swapoff\n' >> "$EVENT_LOG"
if [[ "$*" != '-n swapoff --all' ]]; then exit 91; fi
exit "$SWAP_DISABLE_STATUS"
STUB
  cat > "$root/bin/swapon" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'swapon\n' >> "$EVENT_LOG"
if [[ "$SWAP_REMAINS" == true ]]; then printf '/swapfile\n'; fi
STUB
  cat > "$root/tooling/mvnw" <<'STUB'
#!/usr/bin/env bash
printf 'compile\n' >> "$EVENT_LOG"
STUB
  cat > "$root/tooling/bin/codex-tooling" <<'STUB'
#!/usr/bin/env bash
printf 'launcher\n' >> "$EVENT_LOG"
printf '1\n'
STUB
  chmod +x "$root/bin/sudo" "$root/bin/swapon" "$root/tooling/mvnw" "$root/tooling/bin/codex-tooling"
  disable_status=0
  remains=false
  cache_hit=false
  expected_status=0
  case "$scenario" in
    disable-failure) disable_status=1; expected_status=1 ;;
    remaining-swap) remains=true; expected_status=1 ;;
    warm-runtime) cache_hit=true ;;
  esac
  status=0
  env PATH="$root/bin:$PATH" RUNNER_OS=Linux \
    GITHUB_ACTION_PATH="$root/.github/actions/setup-tooling" \
    GITHUB_OUTPUT="$root/outputs" GITHUB_ENV="$root/environment" \
    EVENT_LOG="$root/events" SWAP_DISABLE_STATUS="$disable_status" SWAP_REMAINS="$remains" \
    TOOLING_RUNTIME_CACHE_HIT="$cache_hit" \
    bash "$root/.github/actions/setup-tooling/setup.sh" > "$root/output" 2>&1 || status=$?
  if [[ "$status" != "$expected_status" ]]; then
    printf '%s: expected status %s, got %s\n' "$scenario" "$expected_status" "$status" >&2
    cat "$root/output" >&2
    exit 1
  fi
  if [[ "$expected_status" != 0 ]]; then
    if [[ -f "$root/events" ]] && grep -Eq '^(compile|launcher)$' "$root/events"; then
      printf '%s: build continued after RAM-only setup failed\n' "$scenario" >&2
      exit 1
    fi
  else
    expected_events=$'swapoff\nswapon\ncompile\nlauncher'
    if [[ "$cache_hit" == true ]]; then expected_events=$'swapoff\nswapon\nlauncher'; fi
    if [[ "$(cat "$root/events")" != "$expected_events" ]]; then
      printf '%s: swap verification did not precede the build/probe\n' "$scenario" >&2
      cat "$root/events" >&2
      exit 1
    fi
  fi
done
