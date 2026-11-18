#!/usr/bin/env bash
# Exercise verify-release.sh argument forwarding, configuration errors, and exit status at its shell boundary.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
verify_script="$script_dir/verify-release.sh"
temp_dir=$(mktemp -d)
trap 'rm -rf -- "$temp_dir"' EXIT

if "$verify_script" >"$temp_dir/no-args.stdout" 2>"$temp_dir/no-args.stderr"; then
  no_args_status=0
else
  no_args_status=$?
fi
if [[ $no_args_status != 2 ]] || ! grep -Fq 'Usage: verify-release.sh' "$temp_dir/no-args.stderr"; then
  printf '%s\n' 'FAIL: no arguments must print usage and exit 2' >&2
  cat "$temp_dir/no-args.stderr" >&2
  exit 1
fi

if (unset CODEX_TOOLING; "$verify_script" "$temp_dir/release" --patch-repo "$temp_dir/patch repo" --upstream-source "$temp_dir/upstream source" >"$temp_dir/missing.stdout" 2>"$temp_dir/missing.stderr"); then
  missing_status=0
else
  missing_status=$?
fi
if [[ $missing_status != 2 ]] || ! grep -Fq 'CODEX_TOOLING' "$temp_dir/missing.stderr"; then
  printf '%s\n' 'FAIL: missing CODEX_TOOLING must explain the configuration error and exit 2' >&2
  cat "$temp_dir/missing.stderr" >&2
  exit 1
fi

if CODEX_TOOLING='' "$verify_script" "$temp_dir/release" --patch-repo "$temp_dir/patch repo" --upstream-source "$temp_dir/upstream source" >"$temp_dir/empty.stdout" 2>"$temp_dir/empty.stderr"; then
  empty_status=0
else
  empty_status=$?
fi
if [[ $empty_status != 2 ]] || ! grep -Fq 'CODEX_TOOLING' "$temp_dir/empty.stderr"; then
  printf '%s\n' 'FAIL: empty CODEX_TOOLING must explain the configuration error and exit 2' >&2
  cat "$temp_dir/empty.stderr" >&2
  exit 1
fi

cat > "$temp_dir/launcher" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "$CAPTURE_FILE"
exit "$STANDIN_STATUS"
LAUNCHER
chmod +x "$temp_dir/launcher"
release_dir="$temp_dir/release bundle"
if CODEX_TOOLING="$temp_dir/launcher" CAPTURE_FILE="$temp_dir/argv" STANDIN_STATUS=37 \
  "$verify_script" "$release_dir" --patch-repo "$temp_dir/patch repo" --upstream-source "$temp_dir/upstream source" --verify-attestations; then
  child_status=0
else
  child_status=$?
fi
printf '%s\0' release-manifest verify --release-dir "$release_dir" \
  --patch-repo "$temp_dir/patch repo" --upstream-source "$temp_dir/upstream source" --verify-attestations \
  > "$temp_dir/expected-argv"
if [[ $child_status != 37 ]] || ! cmp -s "$temp_dir/expected-argv" "$temp_dir/argv"; then
  printf '%s\n' 'FAIL: launcher must receive the expected argument boundaries and its exit status must propagate' >&2
  printf 'Observed child status: %s\n' "$child_status" >&2
  exit 1
fi

printf '%s\n' 'PASS: verify-release.sh usage, launcher configuration, argument forwarding, and exit status'
