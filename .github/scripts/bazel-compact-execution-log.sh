#!/usr/bin/env bash
# Preflights and converts Bazel 9.3.1 compact logs using its unchanged maintained converter.
# Usage: bazel-compact-execution-log.sh prepare <new-absolute-evidence> <absolute-jdk27>
#        bazel-compact-execution-log.sh convert <evidence> <jdk27> <compact-input> <new-json-output>
#        bazel-compact-execution-log.sh cleanup <evidence>
#        bazel-compact-execution-log.sh --self-test <new-absolute-evidence> <absolute-jdk27>
# The caller owns evidence through upload and must call cleanup after the last conversion, including failure/cancel.
set -euo pipefail

# Rejects unavailable or altered dependency bytes before executing them.
# Arguments: Expected SHA256; dependency file.
# Exit status: Nonzero for missing files or mismatched bytes.
verify_hash() {
  local expected="$1" path="$2" actual
  [[ -f "$path" ]] || { printf 'Missing converter dependency: %s\n' "$path" >&2; return 1; }
  actual="$(shasum -a 256 -- "$path" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || { printf 'Converter dependency SHA256 mismatch: %s\n' "$path" >&2; return 1; }
}

# Selects the authenticated native distribution for the two evaluated hosts.
# Output: Official release basename and SHA256, separated by a space.
# Exit status: Nonzero for any unsupported operating system or architecture.
native_distribution() {
  case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) printf '%s\n' 'bazel-9.3.1-darwin-arm64 19c4958626e0e3a564a61c2e85eeaab97b550a30632b82341191aa61ed0ccc1b' ;;
    Linux/x86_64) printf '%s\n' 'bazel-9.3.1-linux-x86_64 7f775b54bbec487efaf0f49ccda1d2ed02cd34f582edfdd6aeeec76e39ef585c' ;;
    *) echo 'Compact log conversion supports only Darwin arm64 and Linux x86_64' >&2; return 1 ;;
  esac
}

# Checks the explicit JDK boundary before downloading or running dependencies.
# Arguments: Absolute full JDK27 directory.
# Exit status: Nonzero for missing tools or a different compiler version.
verify_jdk() {
  local jdk="$1"
  [[ "$jdk" == /* && -x "$jdk/bin/java" && -x "$jdk/bin/javac" ]] || {
    echo 'Compact log conversion requires an absolute full JDK27 directory' >&2; return 2;
  }
  "$jdk/bin/javac" -version | grep -E '^javac 27([.+ -]|$)' >&2
}

# Removes only the helper-owned runtime; compact/JSON logs and diagnostics remain for upload.
# Arguments: Existing absolute helper evidence directory created by prepare.
# Exit status: Nonzero for an invalid ownership marker or failed removal.
cleanup_runtime() {
  local tools="$1"
  [[ "$tools" == /* && -f "$tools/compact-converter-owner" ]] || {
    echo 'Missing compact converter ownership marker' >&2; return 2;
  }
  rm -rf -- "$tools/runtime"
}

# Reauthenticates native binary and source bytes, then recompiles the unchanged maintained CLI.
# Arguments: Helper evidence directory; full JDK27 directory.
# Side effects: Replaces only owned extracted JAR/classes and records their identity.
# Exit status: Nonzero for bad identity, extraction, or strict compilation failure.
compile_converter() {
  local tools="$1" jdk="$2" distribution expected runtime="$1/runtime"
  read -r distribution expected < <(native_distribution)
  verify_hash "$expected" "$runtime/$distribution"
  verify_hash c52c12c978d49b1b87cce893297574a9d728729b86d42756fc8984d256ead798 "$runtime/ConverterOptions.java"
  verify_hash 09b3063a1c6555f8b9d6c85e9b2c8721a48dbde68e363c5cbda75d6cb2bcd2b4 "$runtime/ExecLogConverter.java"
  unzip -p "$runtime/$distribution" A-server.jar > "$runtime/A-server.jar"
  shasum -a 256 "$runtime/$distribution" "$runtime/A-server.jar" "$runtime/ConverterOptions.java" \
    "$runtime/ExecLogConverter.java" > "$tools/dependency-sha256.txt"
  rm -rf -- "$runtime/classes"
  mkdir "$runtime/classes"
  TMPDIR="$runtime/native" "$jdk/bin/javac" "-J-Djava.io.tmpdir=$runtime/native" --release 27 -Werror -proc:none -cp "$runtime/A-server.jar" \
    -d "$runtime/classes" "$runtime/ConverterOptions.java" "$runtime/ExecLogConverter.java" \
    2>&1 | tee -a "$tools/converter.log" >&2
}

# Invokes the real maintained converter with stable argument ordering and no sorting.
# Arguments: Helper evidence; full JDK27; maintained converter input/output options.
# Side effects: Records visible diagnostics and confines JNI extraction to owned runtime paths.
# Exit status: Preserves converter failure through pipefail.
run_converter() {
  local tools="$1" jdk="$2"
  shift 2
  TMPDIR="$tools/runtime/native" "$jdk/bin/java" --enable-native-access=ALL-UNNAMED \
    "-Djava.io.tmpdir=$tools/runtime/native" -cp "$tools/runtime/classes:$tools/runtime/A-server.jar" \
    com.google.devtools.build.execlog.ExecLogConverter "$@" --sort=false \
    2>&1 | tee -a "$tools/converter.log" >&2
}

# Converts authenticated compact input using the maintained decoder, publishing JSON only on success.
# Arguments: Helper evidence; JDK27; absolute existing compact log; absolute new JSON output.
# Side effects: Retains a partial output on decoding failure and diagnostics beside helper evidence.
# Exit status: Nonzero for invalid paths, altered dependencies, or decoding failure.
convert_log() {
  local tools="$1" jdk="$2" input="$3" output="$4"
  [[ "$tools" == /* && -f "$tools/compact-converter-owner" && "$input" == /* && -f "$input" &&
     "$output" == /* && ! -e "$output" && ! -L "$output" && ! -e "$output.partial" && ! -L "$output.partial" ]] || {
    echo 'Conversion requires owned tools, existing absolute input, and an unused absolute output' >&2; return 2;
  }
  verify_jdk "$jdk"
  compile_converter "$tools" "$jdk"
  run_converter "$tools" "$jdk" "--input=compact:$input" "--output=json:$output.partial"
  mv -- "$output.partial" "$output"
}

# Exercises the native distribution and decoder with a tiny actual Bazel spawn before the Codex build.
# Arguments: Prepared helper evidence directory; JDK27 directory.
# Side effects: Creates owned fixture inputs/outputs and retains compact, JSON and command diagnostics.
# Exit status: Nonzero for native startup, zstd/decoder incompatibility, or incorrect receipt/digests.
smoke_test() {
  local tools="$1" jdk="$2" distribution expected root path hash size
  read -r distribution expected < <(native_distribution)
  mkdir -p "$tools/runtime/smoke/source"
  printf 'module(name = "compact_converter_smoke")\n' > "$tools/runtime/smoke/source/MODULE.bazel"
  cat > "$tools/runtime/smoke/source/BUILD.bazel" <<'SMOKE_BUILD'
genrule(name = "receipt", srcs = ["input.params"], outs = ["receipt.txt"], cmd = "cat $(location input.params) > $@")
SMOKE_BUILD
  printf '%s\n' '-Cdebuginfo=0' '-Cdebuginfo=2' > "$tools/runtime/smoke/source/input.params"
  (
    cd "$tools/runtime/smoke/source"
    TMPDIR="$tools/runtime/native" "$tools/runtime/$distribution" --batch --nosystem_rc --nohome_rc --noworkspace_rc \
      "--output_user_root=$tools/runtime/smoke/output-root" build \
      "--repository_cache=$tools/runtime/smoke/repository" \
      "--execution_log_compact_file=$tools/smoke.compact" //:receipt
  ) 2>&1 | tee "$tools/smoke-bazel.log" >&2
  convert_log "$tools" "$jdk" "$tools/smoke.compact" "$tools/smoke.json"
  # This fixture has exactly one spawn; whole-document checks stay bounded to its tiny receipt.
  jq -es 'length == 1 and .[0].targetLabel == "//:receipt" and .[0].exitCode == 0 and
    .[0].cacheHit == false and (.[0].runner == "darwin-sandbox" or .[0].runner == "linux-sandbox") and
    (.[0].commandArgs | length) == 3 and (.[0].actualOutputs | length) == 1 and
    any(.[0].inputs[]; .path == "input.params")' "$tools/smoke.json" > "$tools/smoke-checks.log"
  root="$(cd -P "$tools/runtime/smoke/source/bazel-out" && cd -P .. && pwd -P)"
  while IFS=$'\t' read -r path hash size; do
    verify_hash "$hash" "$root/$path"
    [[ "$(wc -c < "$root/$path" | tr -d ' ')" == "$size" ]]
    printf 'PASS digest+size: %s\n' "$path" >> "$tools/smoke-checks.log"
  done < <(jq -r '(.inputs[],.actualOutputs[]) | [.path,.digest.hash,.digest.sizeBytes] | @tsv' "$tools/smoke.json")
  cmp "$tools/runtime/smoke/source/input.params" "$tools/runtime/smoke/source/bazel-bin/receipt.txt"
}

# Acquires authenticated dependencies and runs native smoke before expensive diagnostic work.
# Arguments: New absolute helper evidence directory; full JDK27 directory.
# Side effects: Owns downloaded sources/binary/classes/native extraction under evidence until cleanup.
# Exit status: Nonzero for invalid input, acquisition/identity failure, or failed native smoke.
prepare_converter() (
  local tools="$1" jdk="$2" distribution expected source status=0
  [[ "$tools" == /* ]] || { echo 'Converter evidence path must be absolute' >&2; exit 2; }
  verify_jdk "$jdk"
  read -r distribution expected < <(native_distribution)
  mkdir "$tools"
  printf '%s\n' 'bazel-compact-execution-log-v1' > "$tools/compact-converter-owner"
  trap 'status=$?; trap - EXIT; if [[ "$status" != 0 ]]; then cleanup_runtime "$tools"; fi; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  mkdir -p "$tools/runtime/native"
  curl --fail --location --show-error "https://releases.bazel.build/9.3.1/release/$distribution" -o "$tools/runtime/$distribution"
  verify_hash "$expected" "$tools/runtime/$distribution"
  chmod +x "$tools/runtime/$distribution"
  for source in ConverterOptions ExecLogConverter; do
    curl --fail --location --show-error \
      "https://raw.githubusercontent.com/bazelbuild/bazel/fee2bf2d1c14bc2096068a4d46f1842472092044/src/tools/execlog/src/main/java/com/google/devtools/build/execlog/$source.java" \
      -o "$tools/runtime/$source.java"
  done
  compile_converter "$tools" "$jdk"
  smoke_test "$tools" "$jdk"
)

# Verifies malformed rejection and complete ordered-argument/digest roundtrip with the actual decoder.
# Arguments: New absolute helper evidence directory; full JDK27 directory; absolute script path.
# Side effects: Retains bounded fixture evidence and cleans runtime after all consumers, including failure.
# Exit status: Nonzero for any failed conversion, rejection, roundtrip or cleanup assertion.
self_test() (
  local tools="$1" jdk="$2" script="$3" status=0 distribution expected dependency
  prepare_converter "$tools" "$jdk"
  trap 'status=$?; trap - EXIT; cleanup_runtime "$tools"; exit "$status"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  printf 'malformed compact execution log\n' > "$tools/malformed.compact"
  if bash "$script" convert "$tools" "$jdk" "$tools/malformed.compact" "$tools/malformed.json" \
      > "$tools/malformed.log" 2>&1; then
    echo 'Malformed compact log was accepted' >&2; exit 1
  fi
  cat "$tools/malformed.log" >&2
  [[ ! -e "$tools/malformed.json" ]]
  grep -F 'ZstdIOException' "$tools/malformed.log" > /dev/null
  read -r distribution expected < <(native_distribution)
  for dependency in "$distribution" ConverterOptions.java; do
    cp "$tools/runtime/$dependency" "$tools/runtime/$dependency.saved"
    printf '\n' >> "$tools/runtime/$dependency"
    if bash "$script" convert "$tools" "$jdk" "$tools/smoke.compact" "$tools/altered.json" \
        > "$tools/altered-$dependency.log" 2>&1; then
      echo 'Altered dependency was accepted' >&2; exit 1
    fi
    grep -F "Converter dependency SHA256 mismatch: $tools/runtime/$dependency" "$tools/altered-$dependency.log" > /dev/null
    mv -- "$tools/runtime/$dependency.saved" "$tools/runtime/$dependency"
  done
  mv -- "$tools/runtime/ConverterOptions.java" "$tools/runtime/ConverterOptions.java.saved"
  if bash "$script" convert "$tools" "$jdk" "$tools/smoke.compact" "$tools/missing.json" \
      > "$tools/missing.log" 2>&1; then
    echo 'Missing dependency was accepted' >&2; exit 1
  fi
  grep -F "Missing converter dependency: $tools/runtime/ConverterOptions.java" "$tools/missing.log" > /dev/null
  mv -- "$tools/runtime/ConverterOptions.java.saved" "$tools/runtime/ConverterOptions.java"
  run_converter "$tools" "$jdk" "--input=json:$tools/smoke.json" "--output=binary:$tools/roundtrip.bin"
  run_converter "$tools" "$jdk" "--input=binary:$tools/roundtrip.bin" "--output=json:$tools/roundtrip.json"
  jq -S . "$tools/smoke.json" > "$tools/smoke-canonical.json"
  jq -S . "$tools/roundtrip.json" > "$tools/roundtrip-canonical.json"
  cmp "$tools/smoke-canonical.json" "$tools/roundtrip-canonical.json"
  echo 'PASS: real compact conversion, malformed rejection, ordered command/digest roundtrip' >&2
)

case "${1:-}" in
  prepare) [[ "$#" == 3 ]]; prepare_converter "$2" "$3" ;;
  convert) [[ "$#" == 5 ]]; convert_log "$2" "$3" "$4" "$5" ;;
  cleanup) [[ "$#" == 2 ]]; cleanup_runtime "$2" ;;
  --self-test) [[ "$#" == 3 ]]; self_test "$2" "$3" "$(cd -- "$(dirname -- "$0")" && pwd)/$(basename -- "$0")" ;;
  *) echo 'Usage: bazel-compact-execution-log.sh prepare|convert|cleanup|--self-test (see source header)' >&2; exit 2 ;;
esac
