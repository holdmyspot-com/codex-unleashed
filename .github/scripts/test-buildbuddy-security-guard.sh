#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name the owned test temporary directory}/buildbuddy-security-guard-XXXXXX")"
finish() {
  local status=$?
  if [[ "$status" != 0 ]]; then
    local diagnostic
    while IFS= read -r -d '' diagnostic; do
      printf '\n%s:\n' "$diagnostic" >&2
      cat "$diagnostic" >&2
    done < <(find "$fixtures" -type f \( -name output.log -o -name calls.txt -o -name expected.txt -o -name result.txt \) -print0 | sort -z)
  fi
  rm -rf -- "$fixtures"
  exit "$status"
}
trap finish EXIT

for scenario in guard-pass guard-failure missing-guard; do
  case_root="$fixtures/$scenario"
  checkout="$case_root/checkout"
  mkdir -p "$checkout/.github/scripts" "$checkout/upstream/scripts" \
    "$checkout/upstream/codex-rs" "$case_root/bin" "$case_root/managed/bin" \
    "$case_root/runner-temp" "$case_root/artifacts"
  git init --quiet "$checkout"
  printf '*\n' > "$checkout/.git/info/exclude"
  cp "$project_root/.github/scripts/buildbuddy-ci.sh" "$checkout/.github/scripts/buildbuddy-ci.sh"
  cat > "$checkout/.github/scripts/buildbuddy-bootstrap.sh" <<'STUB'
project_root="$PWD"
export CI_BUILD_ROOT="$FIXTURE_CASE_ROOT/managed"
export PATH="$FIXTURE_CASE_ROOT/bin:$PATH"
STUB
  printf ':\n' > "$checkout/.github/scripts/buildbuddy-prepare-upstream.sh"
  for tool in rustup pnpm; do
    printf '#!/usr/bin/env bash\nexit 0\n' > "$case_root/bin/$tool"
    chmod +x "$case_root/bin/$tool"
  done
  printf '#!/usr/bin/env bash\nexit 99\n' > "$case_root/managed/bin/cargo-deny"
  chmod +x "$case_root/managed/bin/cargo-deny"
  cat > "$case_root/bin/cargo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'audit'
  printf ' <%s>' "$@"
  printf '\naudit-cwd <%s>\n' "$PWD"
} >> "$FIXTURE_CALLS"
STUB
  chmod +x "$case_root/bin/cargo"
  expected_status=0
  guard_status=0
  if [[ "$scenario" == guard-failure ]]; then
    expected_status=17
    guard_status=17
  elif [[ "$scenario" == missing-guard ]]; then
    expected_status=127
  fi
  if [[ "$scenario" != missing-guard ]]; then
    cat > "$checkout/upstream/scripts/check-crypto-bigint-backport.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
{
  printf 'guard'
  printf ' <%s>' "$@"
  printf '\nguard-tmpdir <%s>\n' "$TMPDIR"
} >> "$FIXTURE_CALLS"
exit "$FIXTURE_GUARD_STATUS"
STUB
  fi
  : > "$case_root/calls.txt"
  : > "$case_root/expected.txt"
  if [[ "$scenario" != missing-guard ]]; then
    printf 'guard <%s>\nguard-tmpdir <%s>\n' "$checkout/upstream" "$case_root/runner-temp" \
      >> "$case_root/expected.txt"
  fi
  if [[ "$scenario" == guard-pass ]]; then
    printf '%s\naudit-cwd <%s>\n' \
      'audit <deny> <check> <--deny> <unmatched-source> <--deny> <license-not-encountered> <--deny> <unused-wrapper> <--deny> <advisory-not-detected>' \
      "$checkout/upstream/codex-rs" >> "$case_root/expected.txt"
  fi

  status=0
  (
    cd "$checkout"
    export BUILDBUDDY_CI_TASK=cargo-deny BUILDBUDDY_CI_TARGET=x86_64-unknown-linux-gnu
    export BUILDBUDDY_ARTIFACTS_DIRECTORY="$case_root/artifacts" GITHUB_SHA=security-guard-fixture
    export RUNNER_TEMP="$case_root/runner-temp" FIXTURE_CASE_ROOT="$case_root"
    export FIXTURE_CALLS="$case_root/calls.txt" FIXTURE_GUARD_STATUS="$guard_status"
    unset BAZEL_REPOSITORY_CACHE
    bash .github/scripts/buildbuddy-ci.sh
  ) > "$case_root/output.log" 2>&1 || status=$?
  if [[ "$status" != "$expected_status" ]]; then
    printf '%s: expected task exit %s, got %s\n' "$scenario" "$expected_status" "$status" >&2
    exit 1
  fi
  if [[ "$(tail -n 1 "$case_root/artifacts/result.txt")" != "$expected_status" ]]; then
    printf '%s: artifact receipt lost the terminal status\n' "$scenario" >&2
    exit 1
  fi
  if cmp -s "$case_root/expected.txt" "$case_root/calls.txt"; then
    :
  else
    printf '%s: source guard/audit order, arguments or temporary directory differ\n' "$scenario" >&2
    exit 1
  fi
  (cd "$case_root/artifacts" && sha256sum --check --status artifact-manifest.sha256)
done
