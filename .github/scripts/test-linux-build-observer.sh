#!/usr/bin/env bash
set -euo pipefail

script_directory="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d "${TMPDIR:?TMPDIR must identify the test output owner}/linux-build-observer-XXXXXX")"
cleanup() {
  local pid_file
  for pid_file in "$fixture"/*.pid; do
    if [[ -f "$pid_file" ]]; then
      kill "$(cat "$pid_file")" 2>/dev/null || true
    fi
  done
  rm -rf -- "$fixture"
}
trap cleanup EXIT

mkdir "$fixture/bin"
cat > "$fixture/bin/sudo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$$" > "$OBSERVER_FIXTURE/$2.pid"
exec sleep 600
STUB
chmod +x "$fixture/bin/sudo"

status=0
PATH="$fixture/bin:$PATH" OBSERVER_FIXTURE="$fixture" \
  bash "$script_directory/observe-linux-build.sh" bash -c 'sleep 1; exit 17' \
  > "$fixture/output" 2>&1 || status="$?"
if [[ "$status" -ne 17 ]]; then
  cat "$fixture/output" >&2
  printf 'Expected child status 17; got %s\n' "$status" >&2
  exit 1
fi
for observer in dmesg journalctl; do
  if [[ ! -s "$fixture/$observer.pid" ]]; then
    cat "$fixture/output" >&2
    printf 'Observer did not start: %s\n' "$observer" >&2
    exit 1
  fi
  if kill -0 "$(cat "$fixture/$observer.pid")" 2>/dev/null; then
    printf 'Observer leaked after build exit: %s\n' "$observer" >&2
    exit 1
  fi
done
if ! grep -Fq 'Build resources at' "$fixture/output"; then
  cat "$fixture/output" >&2
  exit 1
fi

# shellcheck source=observe-linux-build.sh
source "$script_directory/observe-linux-build.sh"
cat > "$fixture/reader" <<'READER'
#!/usr/bin/env bash
case "$1" in
  success) cat "$2" ;;
  gone) rm -- "$2"; echo 'resource disappeared' >&2; exit 1 ;;
  failure) echo 'permission denied' >&2; exit 13 ;;
esac
READER
printf 'compiler memory\n' > "$fixture/resource"
read_process_resource "$fixture/resource" bash "$fixture/reader" success > "$fixture/out" 2> "$fixture/err"
test "$(cat "$fixture/out")" = 'compiler memory'
test ! -s "$fixture/err"

status=0
read_process_resource "$fixture/resource" bash "$fixture/reader" failure > "$fixture/out" 2> "$fixture/err" || status=$?
test "$status" -eq 13
test ! -s "$fixture/out"
test "$(cat "$fixture/err")" = 'permission denied'

status=0
read_process_resource "$fixture/resource" bash "$fixture/reader" gone > "$fixture/out" 2> "$fixture/err" || status=$?
test "$status" -eq 1
test ! -s "$fixture/out"
if [[ -s "$fixture/err" ]]; then
  cat "$fixture/err" >&2
  echo 'An exited process must not produce a resource-read diagnostic' >&2
  exit 1
fi
