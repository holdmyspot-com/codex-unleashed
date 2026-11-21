#!/usr/bin/env bash
# Exercise the diagnostic with fixture ELF files and an isolated Bazel launcher.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"
mkdir -p .github/scripts runner
export RUNNER_TEMP="$fixture/runner" GITHUB_STEP_SUMMARY="$fixture/summary"
export RUNNER_OS=Linux RUNNER_ARCH=X64 TARGET=x86_64-unknown-linux-musl
export CODEX_BAZEL_BIN="$fixture/fixture-bazel"
cat > "$CODEX_BAZEL_BIN" <<'BAZEL'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --output_user_root=* ]]
if [[ "$2" == build ]]; then
  [[ "$*" == *" --disk_cache= --remote_cache= --remote_executor="* ]]
  mkdir "${1#*=}"
else
  test "$2" = shutdown
  test -d "${1#*=}"
fi
BAZEL
chmod +x "$CODEX_BAZEL_BIN"

cat > .github/scripts/codex-voice-runtime.sh <<'VOICE'
#!/usr/bin/env bash
set -euo pipefail
test "$1" = build
test "$8" = --result-dir
result="$9"
"$CODEX_BAZEL_BIN" "--output_user_root=$BAZEL_OUTPUT_USER_ROOT" build --config=release
voice=bazel-bin/third_party/voice
projected="$voice/native_runtime_linux_x86_64"
mkdir -p "$voice/native_prefix_linux_x86_64" "$projected/lib" "$result/release/runtime/lib" \
  bazel-bin/codex-rs/voice-host "$result/prefix/lib"
cp /bin/true "$result/prefix/lib/libfixture.so.1"
cp /bin/true "$projected/lib/libfixture.so.1"
cp /bin/true "$result/release/runtime/lib/libfixture.so.1"
cp /bin/true bazel-bin/codex-rs/voice-host/codex-voice-host
cp /bin/true "$result/release/codex-voice-host"
printf '{"libraries":[{"path":"lib/libfixture.so.1"}]}\n' > "$result/release/runtime/runtime.json"
cp "$result/release/runtime/runtime.json" "$projected/runtime.json"
tar -cf "$voice/native_prefix_linux_x86_64/prefix.tar" -C "$result/prefix" lib
if [[ "${PROBE_FIXTURE_DRIFT:-}" == true && "$BAZEL_OUTPUT_USER_ROOT" == *bazel-second ]]; then
  printf 'changed\n' >> "$result/release/runtime/lib/libfixture.so.1"
fi
VOICE
git init -q
git add .github
git -c user.name=fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -qm fixture

bash "$script_dir/probe-linux-voice-reproduction.sh"
reports="$RUNNER_TEMP/voice-reproduction-evidence"
cmp "$reports/first.json" "$reports/second.json"
jq -e '.linked["lib/libfixture.so.1"].sections[".text"] | length == 64' "$reports/first.json" >/dev/null
grep -qx comparison=identical "$GITHUB_STEP_SUMMARY"
mv "$reports" "$fixture/identical"
PROBE_FIXTURE_DRIFT=true bash "$script_dir/probe-linux-voice-reproduction.sh"
test "$(jq -r .linked "$reports/first.json")" = "$(jq -r .linked "$reports/second.json")"
test "$(jq -r .projected "$reports/first.json")" = "$(jq -r .projected "$reports/second.json")"
test "$(jq -r .sealed "$reports/first.json")" != "$(jq -r .sealed "$reports/second.json")"
grep -qx comparison=changed "$GITHUB_STEP_SUMMARY"
