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
trap 'rm -rf -- "$fixture"' EXIT

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
  plugins="$(jq -c --arg directory "$plugin_directory" --arg suffix "$plugin_suffix" \
    'map($directory + "/libgst" + . + $suffix) | sort' <<< "$plugin_names")"
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
printf '%s\n' 'Voice target selection, receipt staging, archive extraction, source commit and tamper checks pass.'
