#!/usr/bin/env bash
set -euo pipefail

project_root="${1:?Pass the absolute project checkout root}"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name owned test storage}/memory-scheduled-build-XXXXXX")"
trap 'rm -rf -- "$fixtures"' EXIT

for scenario in manager-unavailable active degraded denied-sudo missing-systemd-run missing-systemctl missing-sudo non-linux scope-failure child-failure; do
  root="$fixtures/$scenario"
  mkdir -p "$root/bin" "$root/working directory"
  for tool in bash env id uname; do
    ln -s "$(command -v "$tool")" "$root/bin/$tool"
  done
  cat > "$root/bin/sudo" <<'SUDO'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n ]]
shift
if [[ "$1" == true ]]; then
  printf 'sudo-check\n' >> "$EVENT_LOG"
  [[ "$SCENARIO" != denied-sudo ]]
  exit
fi
if [[ "$1" == systemctl ]]; then printf 'sudo-manager-probe\n' >> "$EVENT_LOG"; fi
if [[ "$1" == --preserve-env ]]; then shift; fi
exec "$@"
SUDO
  cat > "$root/bin/systemctl" <<'SYSTEMCTL'
#!/usr/bin/env bash
set -euo pipefail
printf 'manager-probe\n' >> "$EVENT_LOG"
[[ "$*" == 'show --property=SystemState --value' ]]
if [[ "$SCENARIO" == manager-unavailable ]]; then
  printf 'Failed to create bus connection: Host is down\n' >&2
  exit 69
fi
if [[ "$SCENARIO" == degraded ]]; then printf 'degraded\n'; else printf 'running\n'; fi
SYSTEMCTL
  cat > "$root/bin/systemd-run" <<'SYSTEMD_RUN'
#!/usr/bin/env bash
set -euo pipefail
printf 'scope\n' >> "$EVENT_LOG"
printf '%s\0' "$@" > "$SCOPE_ARGUMENTS"
if [[ "$SCENARIO" == manager-unavailable ]]; then
  printf 'Failed to create bus connection: Host is down\n' >&2
  exit 69
fi
while [[ "$1" != -- ]]; do shift; done
shift
if [[ "$SCENARIO" == scope-failure ]]; then
  "$@"
  exit 71
fi
exec "$@"
SYSTEMD_RUN
  cat > "$root/bin/tooling" <<'TOOLING'
#!/usr/bin/env bash
set -euo pipefail
printf 'scheduler\n' >> "$EVENT_LOG"
[[ "$1" == schedule-compilers && "$2" == -- ]]
shift 2
exec "$@"
TOOLING
  cat > "$root/bin/child" <<'CHILD'
#!/usr/bin/env bash
set -euo pipefail
printf 'child\n' >> "$EVENT_LOG"
printf '%s\0' "$@" > "$CHILD_ARGUMENTS"
printf '%s\0' "$TOKEN" "$PWD" "$HOME" > "$CHILD_ENVIRONMENT"
if [[ "$SCENARIO" == child-failure ]]; then exit 23; fi
CHILD
  chmod +x "$root/bin/sudo" "$root/bin/systemctl" "$root/bin/systemd-run" "$root/bin/tooling" "$root/bin/child"
  case "$scenario" in
    missing-systemd-run) rm "$root/bin/systemd-run" ;;
    missing-systemctl) rm "$root/bin/systemctl" ;;
    missing-sudo) rm "$root/bin/sudo" ;;
  esac
  runner_os=Linux
  if [[ "$scenario" == non-linux ]]; then runner_os=Darwin; fi
  status=0
  (cd "$root/working directory" && env PATH="$root/bin" RUNNER_OS="$runner_os" \
    CODEX_UNLEASHED_TOOLING="$root/bin/tooling" SCENARIO="$scenario" EVENT_LOG="$root/events" \
    SCOPE_ARGUMENTS="$root/scope-arguments" CHILD_ARGUMENTS="$root/child-arguments" \
    CHILD_ENVIRONMENT="$root/child-environment" TOKEN='value with spaces = literal' \
    bash "$project_root/.github/scripts/run-memory-scheduled-build.sh" \
    "$root/bin/child" 'first argument' '' "\$literal" '*.rs' '--flag=a b') \
    > "$root/output" 2>&1 || status=$?
  expected_status=0
  case "$scenario" in
    scope-failure) expected_status=71 ;;
    child-failure) expected_status=23 ;;
  esac
  if [[ "$status" != "$expected_status" ]]; then
    printf '%s: expected exit %s, received %s\n' "$scenario" "$expected_status" "$status" >&2
    cat "$root/output" >&2
    exit 1
  fi
  case "$scenario" in
    manager-unavailable) expected_events=$'sudo-check\nsudo-manager-probe\nmanager-probe\nscheduler\nchild' ;;
    active|degraded|scope-failure|child-failure) expected_events=$'sudo-check\nsudo-manager-probe\nmanager-probe\nscope\nscheduler\nchild' ;;
    denied-sudo) expected_events=$'sudo-check\nscheduler\nchild' ;;
    *) expected_events=$'scheduler\nchild' ;;
  esac
  if [[ "$(cat "$root/events")" != "$expected_events" ]]; then
    printf '%s: unexpected scope, scheduler, or child execution\n' "$scenario" >&2
    cat "$root/events" >&2
    exit 1
  fi
  printf '%s\0' 'first argument' '' "\$literal" '*.rs' '--flag=a b' > "$root/expected-arguments"
  cmp "$root/expected-arguments" "$root/child-arguments"
  printf '%s\0' 'value with spaces = literal' "$root/working directory" "$HOME" > "$root/expected-environment"
  cmp "$root/expected-environment" "$root/child-environment"
  case "$scenario" in
    active|degraded|scope-failure|child-failure)
      printf '%s\0' --scope --quiet --collect "--uid=$(id -u)" "--gid=$(id -g)" \
        --property=MemorySwapMax=0 --property=OOMPolicy=continue -- env "PATH=$root/bin" "HOME=$HOME" \
        "$root/bin/tooling" schedule-compilers -- "$root/bin/child" \
        'first argument' '' "\$literal" '*.rs' '--flag=a b' > "$root/expected-scope-arguments"
      cmp "$root/expected-scope-arguments" "$root/scope-arguments"
      ;;
    manager-unavailable)
      if ! grep -q 'systemd manager is unavailable' "$root/output"; then
        printf 'Manager fallback does not name its unavailable manager\n' >&2
        cat "$root/output" >&2
        exit 1
      fi
      ;;
    denied-sudo|missing-*)
      grep -q 'Running without a memory scope:' "$root/output"
      ;;
  esac
done
