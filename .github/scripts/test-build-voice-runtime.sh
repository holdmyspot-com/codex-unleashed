#!/usr/bin/env bash
# Exercises target selection and release staging with the source-owned voice validators.
# Arguments: upstream checkout containing third_party/voice; fixture parent directory.
# The fixture contains receipt-valid dummy bytes, not executable native libraries.
set -euo pipefail
script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
producer="$script_dir/build-voice-runtime.sh"
[[ $# == 2 ]] || { echo 'Usage: test-build-voice-runtime.sh <upstream-checkout> <fixture-parent>' >&2; exit 2; }
source_checkout="$(CDPATH='' cd -- "$1" && pwd)"
fixture="$(mktemp -d "$2/voice-runtime-test-XXXXXX")"
# Preserves a failed check's status and diagnostics while removing all fixture files.
cleanup_fixture() {
  local result=$? log
  if (( result != 0 )); then
    for log in "$fixture/stdout" "$fixture/stderr"; do
      [[ ! -f "$log" ]] || cat "$log" >&2
    done
  fi
  rm -rf -- "$fixture"
  exit "$result"
}
trap cleanup_fixture EXIT

matrix="$(bash "$producer" matrix)"
jq -e 'length == 6 and ([.[].target] | unique | length == 6)' <<< "$matrix" >/dev/null
while IFS=$'\t' read -r target runner voice_target prefix helper; do
  selected="$(bash "$producer" matrix "$target")"
  jq -e --arg target "$target" --arg runner "$runner" --arg voice "$voice_target" \
    --arg prefix "$prefix" --arg helper "$helper" \
    'length == 1 and .[0] == {target:$target,runner:$runner,voice_target:$voice,prefix:$prefix,helper:$helper}' \
    <<< "$selected" >/dev/null
done <<'TARGETS'
x86_64-unknown-linux-musl	ubuntu-24.04	x86_64-unknown-linux-gnu	linux_x86_64	codex-voice-host
aarch64-unknown-linux-musl	ubuntu-24.04-arm	aarch64-unknown-linux-gnu	linux_aarch64	codex-voice-host
x86_64-apple-darwin	macos-15-intel	x86_64-apple-darwin	macos_x86_64	codex-voice-host
aarch64-apple-darwin	macos-15	aarch64-apple-darwin	macos_aarch64	codex-voice-host
x86_64-pc-windows-msvc	windows-2022	x86_64-pc-windows-msvc	windows_x86_64	codex-voice-host.exe
aarch64-pc-windows-msvc	windows-11-arm	aarch64-pc-windows-msvc	windows_aarch64	codex-voice-host.exe
TARGETS
for invalid in x86_64-unknown-linux-gnu unknown X86_64-unknown-linux-musl; do
  if bash "$producer" matrix "$invalid" > "$fixture/stdout" 2> "$fixture/stderr"; then
    echo "Unsupported app target accepted: $invalid" >&2
    exit 1
  fi
  [[ -s "$fixture/stderr" && ! -s "$fixture/stdout" ]]
done

for invalid in '' unknown; do
  if bash "$producer" package "$fixture" "$invalid" "$fixture/invalid-output" \
    1111111111111111111111111111111111111111 > "$fixture/stdout" 2> "$fixture/stderr"; then
    echo 'Invalid app target accepted by packaging' >&2
    exit 1
  fi
  [[ ! -e "$fixture/invalid-output" ]]
done

mkdir -p "$fixture/upstream/third_party/voice" "$fixture/upstream/bazel-bin/codex-rs/voice-host"
cp "$source_checkout/third_party/voice/"{runtime.py,package_runtime.py,release_runtime.py,sources.json} \
  "$fixture/upstream/third_party/voice/"
# These libraries model Bazel's prepared output; the real source validator checks their receipts.
sha256=(sha256sum)
if ! command -v sha256sum >/dev/null 2>&1; then
  sha256=(shasum -a 256)
fi
source_manifest_sha256="$("${sha256[@]}" "$fixture/upstream/third_party/voice/sources.json" | awk '{print $1}')"
# Plugin and required-library fixture inputs match the pinned source's runtime.py.
plugin_names='["app","audioconvert","audioresample","coreelements","opus","rtp","rtpmanager"]'
while IFS=$'\t' read -r target prefix plugin_directory plugin_suffix required_library; do
  runtime="$fixture/upstream/bazel-bin/third_party/voice/native_runtime_$prefix"
  mkdir -p "$runtime"
  plugin_prefix=libgst
  if [[ "$target" == *-pc-windows-msvc ]]; then plugin_prefix=gst; fi
  plugins="$(jq -c --arg directory "$plugin_directory" --arg suffix "$plugin_suffix" \
    --arg prefix "$plugin_prefix" 'map($directory + "/" + $prefix + . + $suffix) | sort' <<< "$plugin_names")"
  records='[]'
  while IFS= read -r name; do
    library="$runtime/$name"
    mkdir -p "$(dirname -- "$library")"
    printf '%s' 'fixture native library' > "$library"
    library_sha256="$("${sha256[@]}" "$library" | awk '{print $1}')"
    records="$(jq -c --arg path "$name" --arg sha256 "$library_sha256" \
      '. + [{path:$path,sha256:$sha256}]' <<< "$records")"
  done < <(jq -r --arg required "$required_library" '. + [$required] | .[]' <<< "$plugins")
  jq -n --arg target "$target" --arg source_hash "$source_manifest_sha256" \
    --argjson libraries "$records" --argjson plugins "$plugins" \
    '{schemaVersion:1,developmentOnly:true,target:$target,sourceManifestSha256:$source_hash,
      sourceCommit:"1111111111111111111111111111111111111111",libraries:$libraries,plugins:$plugins}' \
    > "$runtime/runtime.json"
done <<'RUNTIMES'
x86_64-unknown-linux-gnu	linux_x86_64	lib/gstreamer-1.0	.so	lib/libgio-2.0.so.0
aarch64-apple-darwin	macos_aarch64	plugins	.dylib	lib/libgio-2.0.0.dylib
x86_64-pc-windows-msvc	windows_x86_64	bin	.dll	bin/gio-2.0-0.dll
aarch64-pc-windows-msvc	windows_aarch64	bin	.dll	bin/gio-2.0-0.dll
RUNTIMES
printf '%s' 'fixture helper' > "$fixture/upstream/bazel-bin/codex-rs/voice-host/codex-voice-host"
for target in x86_64-unknown-linux-musl aarch64-apple-darwin; do
  bash "$producer" package "$fixture/upstream" "$target" "$fixture/$target" 1111111111111111111111111111111111111111
  mkdir "$fixture/extracted-$target"
  tar -xzf "$fixture/$target/voice-runtime-$target.tar.gz" -C "$fixture/extracted-$target"
  [[ "$(cat "$fixture/extracted-$target/codex-voice-host")" == 'fixture helper' ]]
  jq -e '.developmentOnly == false and .distribution == "publicRelease"' \
    "$fixture/extracted-$target/runtime/runtime.json" >/dev/null
done

# A CRT-updater process double checks the shell's staged-file ownership, not native CRT loading.
mkdir "$fixture/crt-tools"
VOICE_FIXTURE_PYTHON="$(command -v python3)"
export VOICE_FIXTURE_PYTHON
cat > "$fixture/crt-tools/python" <<'CRT_PYTHON'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" != */windows_crt.py ]]; then exec "$VOICE_FIXTURE_PYTHON" "$@"; fi
[[ "$2" == --root && "$4" == --target && "$6" == --helper ]]
receipt="$3/runtime.json"
[[ -w "$receipt" ]] || { echo 'Staged CRT receipt is read-only' >&2; exit 28; }
if [[ "${VOICE_FIXTURE_CRT_FAILURE:-}" == 1 ]]; then
  echo 'Controlled CRT updater failure' >&2
  exit 29
fi
updated="$(jq '.crtFixtureUpdated = true' "$receipt")"
printf '%s\n' "$updated" > "$receipt"
CRT_PYTHON
chmod +x "$fixture/crt-tools/python"
printf '%s' 'fixture Windows helper' > "$fixture/upstream/bazel-bin/codex-rs/voice-host/codex-voice-host.exe"
for prefix in windows_x86_64 windows_aarch64; do
  target=x86_64-pc-windows-msvc
  if [[ "$prefix" == windows_aarch64 ]]; then target=aarch64-pc-windows-msvc; fi
  receipt="$fixture/upstream/bazel-bin/third_party/voice/native_runtime_$prefix/runtime.json"
  cp "$receipt" "$fixture/original-$prefix.json"
  chmod a-w "$receipt"
  [[ -z "$(find "$receipt" -prune \( -perm -0200 -o -perm -0020 -o -perm -0002 \) -print)" ]]
  PATH="$fixture/crt-tools:$PATH" bash "$producer" package "$fixture/upstream" "$target" \
    "$fixture/crt-$prefix" 1111111111111111111111111111111111111111 > "$fixture/stdout" 2> "$fixture/stderr"
  cmp "$receipt" "$fixture/original-$prefix.json"
  [[ -z "$(find "$receipt" -prune \( -perm -0200 -o -perm -0020 -o -perm -0002 \) -print)" ]]
  mkdir "$fixture/crt-extracted-$prefix"
  tar -xzf "$fixture/crt-$prefix/voice-runtime-$target.tar.gz" -C "$fixture/crt-extracted-$prefix"
  jq -e '.crtFixtureUpdated == true and .developmentOnly == false and .distribution == "publicRelease"' \
    "$fixture/crt-extracted-$prefix/runtime/runtime.json" >/dev/null
  if PATH="$fixture/crt-tools:$PATH" VOICE_FIXTURE_CRT_FAILURE=1 bash "$producer" package \
    "$fixture/upstream" "$target" "$fixture/crt-failure-$prefix" \
    1111111111111111111111111111111111111111 > "$fixture/stdout" 2> "$fixture/stderr"; then
    echo 'Packaging accepted failed CRT update' >&2
    exit 1
  fi
  grep -Fq 'Controlled CRT updater failure' "$fixture/stderr"
  [[ ! -e "$fixture/crt-failure-$prefix/voice-runtime-$target.tar.gz" ]]
  [[ -z "$(find "$fixture/crt-$prefix" "$fixture/crt-failure-$prefix" -name '.voice-stage-*' -print)" ]]
  cmp "$receipt" "$fixture/original-$prefix.json"
  [[ -z "$(find "$receipt" -prune \( -perm -0200 -o -perm -0020 -o -perm -0002 \) -print)" ]]
  chmod u+w "$receipt"
done

if bash "$producer" package "$fixture/upstream" x86_64-unknown-linux-musl "$fixture/wrong-commit" \
  2222222222222222222222222222222222222222 > "$fixture/stdout" 2> "$fixture/stderr"; then
  echo 'Mismatched source commit accepted' >&2
  exit 1
fi
[[ ! -e "$fixture/wrong-commit/voice-runtime-x86_64-unknown-linux-musl.tar.gz" ]]
printf 'tampered' >> "$fixture/upstream/bazel-bin/third_party/voice/native_runtime_linux_x86_64/lib/libgio-2.0.so.0"
if bash "$producer" package "$fixture/upstream" x86_64-unknown-linux-musl "$fixture/tampered" \
  1111111111111111111111111111111111111111 > "$fixture/stdout" 2> "$fixture/stderr"; then
  echo 'Tampered runtime accepted' >&2
  exit 1
fi
[[ ! -e "$fixture/tampered/voice-runtime-x86_64-unknown-linux-musl.tar.gz" ]]

# Script doubles verify invocation and publication boundaries, not native loading.
printf '%s' 'fixture native library' > "$fixture/upstream/bazel-bin/third_party/voice/native_runtime_linux_x86_64/lib/libgio-2.0.so.0"
mkdir -p "$fixture/tools" "$fixture/project/.github/scripts" "$fixture/upstream/codex-rs"
cp "$producer" "$fixture/project/.github/scripts/build-voice-runtime.sh"
cat > "$fixture/upstream/bazel-bin/codex-rs/voice-host/codex-voice-host" <<'HELPER'
#!/usr/bin/env bash
set -euo pipefail
# shipping-helper-fixture: distinguish the archived helper from any fallback binary.
[[ $# == 1 && "$1" == --build-commit ]]
[[ "$0" == *'/identity package/codex-resources/voice/bin/codex-voice-host'* ]]
[[ -f "$(dirname -- "$0")/../runtime.json" ]]
if [[ "${VOICE_FIXTURE_FAILURE:-}" == identity ]]; then
  echo wrong-shipping-identity
else
  echo 1111111111111111111111111111111111111111
fi
HELPER
cat > "$fixture/native-test" <<'TEST'
#!/usr/bin/env bash
set -euo pipefail
[[ ! ${RUNFILES_MANIFEST_ONLY+x} && ! ${RUNFILES_MANIFEST_FILE+x} && ! ${RUNFILES_DIR+x} ]]
name=relocated_runtime_initializes_closes_and_rejects_duplicate_initialization
[[ $# == 4 && "$1" == --ignored && "$2" == --exact && "$3" == "$name" ]]
helper="$(printenv CARGO_BIN_EXE_codex-voice-host)"
runtime="$CODEX_TEST_VOICE_RUNTIME"
if [[ "${VOICE_FIXTURE_WINDOWS:-}" == 1 ]]; then
  [[ "$helper" == WINDOWS:* && "$runtime" == WINDOWS:* ]]
  helper="${helper#WINDOWS:}"
  runtime="${runtime#WINDOWS:}"
fi
[[ "$helper" == *'/identity package/codex-resources/voice/bin/codex-voice-host'* ]]
[[ -f "$runtime/runtime.json" ]]
grep -Fq shipping-helper-fixture "$helper"
[[ "$("$helper" --build-commit)" == 1111111111111111111111111111111111111111 ]]
printf '%s\n' "$4" >> "$VOICE_FIXTURE_LOG"
case "$4" in
  --list)
    if [[ "${VOICE_FIXTURE_FAILURE:-}" != missing ]]; then
      printf '%s: test\n' "$name"
    fi
    ;;
  --nocapture)
    if [[ "${VOICE_FIXTURE_FAILURE:-}" == test ]]; then
      echo fixture-native-load-failed >&2
      exit 19
    fi
    ;;
  *) exit 2 ;;
esac
TEST
cat > "$fixture/tools/cygpath" <<'CYGPATH'
#!/usr/bin/env bash
set -euo pipefail
[[ $# == 2 && "$1" == -am ]]
printf '%s\n' "$2" >> "$VOICE_FIXTURE_PATH_LOG"
printf 'WINDOWS:%s\n' "$2"
CYGPATH
cat > "$fixture/tools/git" <<'GIT'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'rev-parse HEAD' ]]
echo 1111111111111111111111111111111111111111
GIT
cat > "$fixture/tools/cargo" <<'CARGO'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'update --workspace' ]]
CARGO
cat > "$fixture/project/.github/scripts/run-bazel-ci.sh" <<'BAZEL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$VOICE_FIXTURE_BUILD_LOG"
if [[ "${VOICE_FIXTURE_BUILD_ONLY:-}" == 1 ]]; then
  exit 23
fi
BAZEL
chmod +x "$fixture/native-test" "$fixture/tools/"* "$fixture/upstream/bazel-bin/codex-rs/voice-host/codex-voice-host"
cp "$fixture/native-test" "$fixture/upstream/bazel-bin/codex-rs/voice-host/voice-host-packaged_runtime-test-bin"
export PATH="$fixture/tools:$PATH"
export VOICE_FIXTURE_LOG="$fixture/native-invocations" VOICE_FIXTURE_PATH_LOG="$fixture/path-conversions"
export VOICE_FIXTURE_BUILD_LOG="$fixture/build-arguments"
export RUNFILES_MANIFEST_ONLY=1 RUNFILES_MANIFEST_FILE=unusable RUNFILES_DIR=unusable
commit=1111111111111111111111111111111111111111
bash "$producer" package "$fixture/upstream" x86_64-unknown-linux-musl "$fixture/native" "$commit"
archive="$fixture/native/voice-runtime-x86_64-unknown-linux-musl.tar.gz"
for target in x86_64-unknown-linux-musl aarch64-unknown-linux-musl x86_64-apple-darwin aarch64-apple-darwin; do
  bash "$producer" verify "$archive" "$target" "$fixture/native-test" "$commit" > "$fixture/stdout" 2> "$fixture/stderr"
  [[ ! -s "$fixture/stdout" && ! -s "$fixture/stderr" ]]
done
mkdir "$fixture/windows-archive"
tar -xzf "$archive" -C "$fixture/windows-archive"
mv "$fixture/windows-archive/codex-voice-host" "$fixture/windows-archive/codex-voice-host.exe"
tar -czf "$fixture/native/windows.tar.gz" -C "$fixture/windows-archive" runtime codex-voice-host.exe
for target in x86_64-pc-windows-msvc aarch64-pc-windows-msvc; do
  VOICE_FIXTURE_WINDOWS=1 bash "$producer" verify "$fixture/native/windows.tar.gz" "$target" \
    "$fixture/native-test" "$commit" > "$fixture/stdout" 2> "$fixture/stderr"
done
[[ "$(wc -l < "$VOICE_FIXTURE_PATH_LOG")" == 4 ]]
[[ "$(grep -Fc -- --list "$VOICE_FIXTURE_LOG")" == 6 ]]
[[ "$(grep -Fc -- --nocapture "$VOICE_FIXTURE_LOG")" == 6 ]]
for failure in missing identity test; do
  if VOICE_FIXTURE_FAILURE="$failure" bash "$producer" verify "$archive" x86_64-unknown-linux-musl \
    "$fixture/native-test" "$commit" > "$fixture/stdout" 2> "$fixture/stderr"; then
    echo "Native verification accepted failure: $failure" >&2
    exit 1
  fi
  case "$failure" in
    missing) grep -Fq 'Required native voice test is missing' "$fixture/stderr" ;;
    identity) grep -Fq wrong-shipping-identity "$fixture/stderr" ;;
    test) grep -Fq fixture-native-load-failed "$fixture/stderr" ;;
  esac
done
[[ -z "$(find "$fixture/native" -maxdepth 1 -name '.voice-verify-*' -print)" ]]

# The build path publishes only after verification, and removes stale output on failure.
build_producer="$fixture/project/.github/scripts/build-voice-runtime.sh"
for failure in missing identity test; do
  mkdir -p "$fixture/build-output"
  printf stale > "$fixture/build-output/voice-runtime-x86_64-unknown-linux-musl.tar.gz"
  if VOICE_FIXTURE_FAILURE="$failure" bash "$build_producer" build "$fixture/upstream" \
    x86_64-unknown-linux-musl "$fixture/build-output" > "$fixture/stdout" 2> "$fixture/stderr"; then
    echo "Build published after failed verification: $failure" >&2
    exit 1
  fi
  [[ ! -e "$fixture/build-output/voice-runtime-x86_64-unknown-linux-musl.tar.gz" ]]
  [[ -z "$(find "$fixture/build-output" -mindepth 1 -print)" ]]
done
bash "$build_producer" build "$fixture/upstream" x86_64-unknown-linux-musl \
  "$fixture/build-output" > "$fixture/stdout" 2> "$fixture/stderr"
[[ -f "$fixture/build-output/voice-runtime-x86_64-unknown-linux-musl.tar.gz" ]]
grep -Fxq '//codex-rs/voice-host:voice-host-packaged_runtime-test-bin' "$VOICE_FIXTURE_BUILD_LOG"
[[ -z "$(find "$fixture/build-output" -maxdepth 1 -name '.voice-*' -print)" ]]

for target in x86_64-pc-windows-msvc aarch64-pc-windows-msvc; do
  prefix=windows_x86_64
  host_arch=AMD64
  if [[ "$target" == aarch64-pc-windows-msvc ]]; then
    prefix=windows_aarch64
    host_arch=ARM64
  fi
  status=0
  VOICE_FIXTURE_BUILD_ONLY=1 VOICE_WINDOWS_BAZEL_REPOSITORY="$fixture/tools" \
    VOICE_WINDOWS_SYSTEM_ROOT='C:\Windows' VOICE_WINDOWS_HOST_ARCH="$host_arch" \
    bash "$build_producer" build "$fixture/upstream" "$target" "$fixture/windows-build" \
    > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
  [[ "$status" == 23 ]]
  if grep -Fxq build "$VOICE_FIXTURE_BUILD_LOG"; then
    :
  else
    printf 'Windows voice producer did not select artifact build for %s\n' "$target" >&2
    exit 1
  fi
  printf '%s\n' "//third_party/voice:native_runtime_$prefix" \
    //codex-rs/voice-host:codex-voice-host \
    //codex-rs/voice-host:voice-host-packaged_runtime-test-bin > "$fixture/expected-targets"
  grep -E '^//|^@' "$VOICE_FIXTURE_BUILD_LOG" > "$fixture/actual-targets"
  cmp "$fixture/expected-targets" "$fixture/actual-targets"
  grep -Fxq -- --windows-msvc-host-platform "$VOICE_FIXTURE_BUILD_LOG"
  grep -Fxq -- --platforms=//:local_windows_msvc "$VOICE_FIXTURE_BUILD_LOG"
  grep -Fxq -- --output_groups=default,receipt,sdk "$VOICE_FIXTURE_BUILD_LOG"
  grep -Fxq -- "--action_env=PROCESSOR_ARCHITECTURE=$host_arch" "$VOICE_FIXTURE_BUILD_LOG"
done
