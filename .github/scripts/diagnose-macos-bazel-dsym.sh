#!/usr/bin/env bash
# Builds only the native Codex Bazel binary and verifies its hermetic dSYM generation.
# Usage: diagnose-macos-bazel-dsym.sh <patched-upstream> <new-evidence-directory>
#        diagnose-macos-bazel-dsym.sh --self-test <temporary-parent> [patched-process-wrapper]
#        diagnose-macos-bazel-dsym.sh --self-test-evidence <temporary-parent> <patched-process-wrapper>
#        diagnose-macos-bazel-dsym.sh --finish-profile <patched-upstream> <evidence-directory> <full-jdk>
# Native execution requires macOS, the maintained CI setup, jq, dwarfdump, and sandbox-exec.
# Real-wrapper self-tests additionally accept an executable wrapper; Linux uses bubblewrap.
# The diagnostic job owns evidence cleanup after its always-run artifact upload.
set -euo pipefail

# Resolve maintained helpers before the diagnostic changes to its patched upstream checkout.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# Runs a Bazel command with the diagnostic's shared JVM startup options.
# Arguments: 1: Owned evidence directory; 2: Compatible full JDK; remaining: Bazel command and arguments.
# Identical startup options let shutdown flush the recording without replacing the profiled server.
run_profiled_bazel() {
  local evidence="$1" profiling_jdk="$2"
  shift 2
  if [[ "$profiling_jdk" != /* || ! -x "$profiling_jdk/bin/java" || ! -x "$profiling_jdk/bin/jfr" ]]; then
    echo 'Bazel allocation profiling requires an absolute full JDK path containing java and jfr' >&2
    return 2
  fi
  # WORKAROUND: https://github.com/bazelbuild/bazel/issues/31786
  # Remove this launcher when our pinned Bazel includes commit 5cf14d47ab994b6004a1e12387c6fc1d6395dc94.
  # JDK 27 rejects the launcher's obsolete verification option; forward every other argument unchanged.
  mkdir -p "$evidence/bazel-jdk/bin"
  {
    printf '#!/usr/bin/env bash\nreal_java=%q\n' "$profiling_jdk/bin/java"
    cat <<'JAVA_LAUNCHER'
arguments=()
for argument in "$@"; do
  if [[ "$argument" != -Xverify:none ]]; then arguments+=("$argument"); fi
done
exec "$real_java" "${arguments[@]}"
JAVA_LAUNCHER
  } > "$evidence/bazel-jdk/bin/java"
  chmod +x "$evidence/bazel-jdk/bin/java"

  bash "$script_dir/run-bazel-with-buildbuddy.sh" \
    "--server_javabase=$evidence/bazel-jdk" \
    --host_jvm_args=--enable-native-access=ALL-UNNAMED \
    --host_jvm_args=-Xmx2560m \
    --host_jvm_args=-XX:+ExitOnOutOfMemoryError \
    "--host_jvm_args=-Xlog:gc*=info:file=$evidence/bazel-gc.log:uptime,level,tags" \
    "--host_jvm_args=-XX:FlightRecorderOptions=repository=$evidence/bazel-jfr-repository,preserve-repository=true,maxchunksize=1m" \
    "--host_jvm_args=-XX:StartFlightRecording=name=bazel-allocation,settings=none,disk=true,maxsize=32m,maxage=10m,dumponexit=true,filename=$evidence/bazel-allocation.jfr,+jdk.ObjectAllocationSample#enabled=true,+jdk.ObjectAllocationSample#stackTrace=true,+jdk.ObjectAllocationSample#throttle=100/s" \
    "$@"
}

# Checks literal startup and command forwarding; the tool double does not verify JVM or JFR behavior.
# Argument: 1: Owned self-test fixture.
test_profiled_startup() {
  local root="$1/profile startup" status=0 argument
  local -a actual=()
  mkdir -p "$root/jdk/bin"
  printf '#!/bin/sh\nexit 0\n' > "$root/jdk/bin/java"
  cp "$root/jdk/bin/java" "$root/jdk/bin/jfr"
  cat > "$root/tooling" <<'PROFILE_TOOL'
#!/usr/bin/env bash
printf '%s\0' "$@" > "$PROFILE_ARGUMENTS"
PROFILE_TOOL
  chmod +x "$root/jdk/bin/java" "$root/jdk/bin/jfr" "$root/tooling"
  (
    cd "$root"
    CODEX_UNLEASHED_TOOLING="$root/tooling" PROFILE_ARGUMENTS="$root/arguments" \
      run_profiled_bazel "$root" "$root/jdk" build '--test_arg=two words' //fixture:target
  )
  while IFS= read -r -d '' argument; do actual+=("$argument"); done < "$root/arguments"
  [[ "${#actual[@]}" == 11 && "${actual[0]}" == run-bazel-with-buildbuddy ]]
  [[ "${actual[1]}" == "--server_javabase=$root/bazel-jdk" ]]
  [[ "${actual[6]}" == "--host_jvm_args=-XX:FlightRecorderOptions=repository=$root/bazel-jfr-repository,preserve-repository=true,maxchunksize=1m" ]]
  [[ "${actual[7]}" == *settings=none* && "${actual[7]}" == *'jdk.ObjectAllocationSample#stackTrace=true'* ]]
  [[ "${actual[4]}" == --host_jvm_args=-XX:+ExitOnOutOfMemoryError ]]
  [[ "${actual[8]}" == build && "${actual[9]}" == '--test_arg=two words' && "${actual[10]}" == //fixture:target ]]
  cat > "$root/jdk/bin/java" <<'JAVA_TOOL'
#!/usr/bin/env bash
printf '%s\0' "$@" > "$PROFILE_ARGUMENTS"
exit 23
JAVA_TOOL
  PROFILE_ARGUMENTS="$root/java-arguments" "$root/bazel-jdk/bin/java" \
    -Xverify:none '' 'two words' -noverify '--property=-Xverify:none' -Xverify:none || status=$?
  [[ "$status" == 23 ]]
  printf '%s\0' '' 'two words' -noverify '--property=-Xverify:none' > "$root/expected-java-arguments"
  cmp "$root/expected-java-arguments" "$root/java-arguments"
  status=0
  rm "$root/jdk/bin/jfr" "$root/arguments"
  CODEX_UNLEASHED_TOOLING="$root/tooling" PROFILE_ARGUMENTS="$root/arguments" \
    run_profiled_bazel "$root" "$root/jdk" shutdown > "$root/missing-tool.log" 2>&1 || status=$?
  [[ "$status" == 2 && ! -e "$root/arguments" ]]
  echo 'PASS: profiled startup preserves literal arguments and rejects an incomplete JDK before launching Bazel'
}

# Retains bounded server diagnostics before owned sandbox cleanup, preserving the caller's status.
# Arguments: 1: Original status; 2: Owned sandbox; 3: Evidence directory; 4: Configured output base.
# The CI setup passes this exact output base to setup-bazel; no default path is inferred.
finish_dsym_diagnostic() {
  local status="$1" sandbox="$2" evidence="$3" output_base="$4"
  trap - EXIT
  if [[ "$status" != 0 && "$output_base" == /* && -f "$output_base/server/jvm.out" ]]; then
    tail -c 65536 -- "$output_base/server/jvm.out" > "$evidence/bazel-jvm-tail.log" || true
  fi
  if [[ -f "$evidence/compact-log/compact-converter-owner" ]]; then
    bash "$script_dir/bazel-compact-execution-log.sh" cleanup "$evidence/compact-log" || true
  fi
  rm -rf -- "$sandbox" || true
  exit "$status"
}

# Exercises the EXIT boundary without starting Bazel or changing the caller's traps.
test_jvm_retention() {
  local fixture="$1/jvm-retention" expected actual
  mkdir -p "$fixture/base/server" "$fixture/unrelated"
  # A recognizable suffix proves that retention selects the tail rather than the prefix.
  { head -c 70000 /dev/zero; printf 'terminal JVM evidence\n'; } > "$fixture/base/server/jvm.out"
  tail -c 65536 "$fixture/base/server/jvm.out" > "$fixture/expected-tail"
  printf 'unrelated\n' > "$fixture/unrelated/keep"
  for expected in 0 33 37 130 143; do
    mkdir -p "$fixture/sandbox" "$fixture/evidence"
    mkdir -p "$fixture/evidence/compact-log/runtime"
    printf '%s\n' 'bazel-compact-execution-log-v1' > "$fixture/evidence/compact-log/compact-converter-owner"
    printf 'retained GC evidence\n' > "$fixture/evidence/bazel-gc.log"
    actual=0
    (
      trap 'finish_dsym_diagnostic "$?" "$fixture/sandbox" "$fixture/evidence" "$fixture/base"' EXIT
      exit "$expected"
    ) || actual=$?
    [[ "$actual" == "$expected" && ! -e "$fixture/sandbox" ]]
    [[ ! -e "$fixture/evidence/compact-log/runtime" ]] || {
      printf 'Compact converter runtime remains after diagnostic status %s\n' "$expected" >&2; return 1;
    }
    grep -Fxq 'retained GC evidence' "$fixture/evidence/bazel-gc.log"
    if [[ "$expected" == 0 ]]; then
      [[ ! -e "$fixture/evidence/bazel-jvm-tail.log" ]]
    else
      cmp "$fixture/expected-tail" "$fixture/evidence/bazel-jvm-tail.log"
      [[ "$(wc -c < "$fixture/evidence/bazel-jvm-tail.log" | tr -d ' ')" == 65536 ]]
    fi
    rm -rf -- "$fixture/evidence"
  done
  # Use a separate Bash process so actual signals cannot cancel the self-test's owner.
  {
    declare -f finish_dsym_diagnostic
    cat <<'SIGNAL_TEST'
set -euo pipefail
fixture="$1"
trap 'finish_dsym_diagnostic "$?" "$fixture/sandbox" "$fixture/evidence" "$fixture/base"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
kill -s "$2" "$$"
exit 99
SIGNAL_TEST
  } > "$fixture/signal-test.sh"
  for expected in INT TERM; do
    mkdir -p "$fixture/sandbox" "$fixture/evidence"
    actual=0
    bash "$fixture/signal-test.sh" "$fixture" "$expected" || actual=$?
    case "$expected" in
      INT) [[ "$actual" == 130 ]] ;;
      TERM) [[ "$actual" == 143 ]] ;;
    esac
    [[ ! -e "$fixture/sandbox" ]]
    cmp "$fixture/expected-tail" "$fixture/evidence/bazel-jvm-tail.log"
    rm -rf -- "$fixture/evidence"
  done
  for expected in missing-base missing-log copy-failure; do
    mkdir -p "$fixture/sandbox" "$fixture/evidence"
    actual=0
    (
      local output_base="$fixture/base"
      case "$expected" in
        missing-base) output_base= ;;
        missing-log) output_base="$fixture/unrelated" ;;
        copy-failure) mkdir "$fixture/evidence/bazel-jvm-tail.log" ;;
      esac
      trap 'finish_dsym_diagnostic "$?" "$fixture/sandbox" "$fixture/evidence" "$output_base"' EXIT
      exit 37
    ) 2> "$fixture/retention.stderr" || actual=$?
    [[ "$actual" == 37 && ! -e "$fixture/sandbox" ]]
    if [[ "$expected" == copy-failure ]]; then
      [[ -d "$fixture/evidence/bazel-jvm-tail.log" ]]
    else
      [[ ! -e "$fixture/evidence/bazel-jvm-tail.log" ]]
    fi
    rm -rf -- "$fixture/evidence"
  done
  [[ "$(cat "$fixture/unrelated/keep")" == unrelated ]]
  echo 'PASS: bounded JVM tail retention preserves cleanup and failure/cancellation status'
}

# Rejects paths that could escape an action's execution root.
# Arguments: 1: Execution-root-relative input or output path.
# Exit status: Zero for a nonempty relative path without traversal or control characters.
check_relative_path() {
  jq -en --arg path "$1" '
    $path | length > 0 and (startswith("/") | not) and
    (test("(^|/)[.][.]?(/|$)|//|[\u0000-\u001f]") | not)' > /dev/null
}

# Authenticates one file against its unique SHA-256 SpawnExec input receipt.
# Arguments: 1: Selected spawn JSON; 2: Execution root; 3: Relative input path.
# Exit status: Nonzero for an unsafe, missing, ambiguous, or changed input.
check_input_digest() {
  local spawn="$1" root="$2" path="$3" hash size
  check_relative_path "$path" || return 1
  hash="$(jq -er --arg path "$path" '
    [.inputs[] | select(.path == $path)] |
    if length == 1 then .[0].digest.hash else error("Input receipt is not unique") end |
    select(test("^[0-9a-f]{64}$"))' "$spawn")" || return 1
  size="$(jq -er --arg path "$path" '
    .inputs[] | select(.path == $path) | .digest |
    select(.hashFunctionName == "SHA-256") | .sizeBytes | tonumber' "$spawn")" || return 1
  [[ -f "$root/$path" ]] || { printf 'Missing replay input: %s\n' "$path" >&2; return 1; }
  [[ "$(wc -c < "$root/$path" | tr -d ' ')" == "$size" &&
     "$(shasum -a 256 "$root/$path" | awk '{print $1}')" == "$hash" ]] || {
    printf 'Input bytes differ from SpawnExec receipt: %s\n' "$path" >&2
    return 1
  }
}

# Checks the final ordered compiler arguments, independently of their transport.
# Arguments: 1: JSON array of effective arguments; 2: Required native target.
# Exit status: Nonzero for malformed arguments or overridden target/debug settings.
check_effective_arguments() {
  jq -e --arg native_target "$2" '
    def codegen_flags:
      . as $args | [range(0; length) as $i |
        if $args[$i] == "-C" then "-C" + $args[$i + 1]
        elif ($args[$i] | startswith("-C")) then $args[$i]
        else empty end];
    def targets:
      . as $args | [range(0; length) as $i |
        if $args[$i] == "--target" then $args[$i + 1]
        elif ($args[$i] | startswith("--target=")) then $args[$i] | ltrimstr("--target=")
        else empty end];
    type == "array" and all(.[]; type == "string") and
    (any(.[]; contains("shell-argfiles")) | not) and
    (targets | last) == $native_target and
    (codegen_flags |
      ([.[] | select(startswith("-Csplit-debuginfo="))] | last) == "-Csplit-debuginfo=packed" and
      ([.[] | select(startswith("-Cdebuginfo="))] | last) == "-Cdebuginfo=2")
  ' "$1" > /dev/null || {
    echo 'Effective rustc arguments do not select the native target and packed/full debug information' >&2
    return 1
  }
}

# Runs the real wrapper with writes confined to its fresh replay directory.
# Arguments: 1: Capture directory; 2: Platform name; remaining: Wrapper command.
# Exit status: The wrapper status, or nonzero when OS isolation is unavailable.
run_isolated_wrapper() {
  local capture="$1" platform="$2"
  shift 2
  case "$platform" in
    Darwin) /usr/bin/sandbox-exec -f "$capture/replay.sb" "$@" ;;
    Linux)
      local -a mounts=()
      local runtime
      for runtime in /usr /bin /lib /lib64 /etc/ld.so.cache; do
        if [[ -e "$runtime" ]]; then mounts+=(--ro-bind "$runtime" "$runtime"); fi
      done
      "$(command -v bwrap)" "${mounts[@]}" --dev /dev --proc /proc \
        --ro-bind "$capture/raw" "$capture/raw" \
        --ro-bind "$capture/record-child" "$capture/record-child" \
        --bind "$capture/work" "$capture/work" --remount-ro / --chdir "$PWD" -- "$@"
      ;;
    *) echo 'No supported OS isolation for wrapper replay' >&2; return 1 ;;
  esac
}

# Collects bounded native diagnostics after a failed opt-in self-test replay.
# Arguments: Capture directory, authenticated wrapper path, then exact replay command.
# Side effects: Retains loader, sandbox and debugger output under the owned capture.
# Debugger failure is evidence, not a replacement for recording or acceptance.
collect_darwin_replay_evidence() (
  local capture="$1" wrapper="$2" debugger watchdog status=0
  shift 2
  /usr/bin/otool -L "$wrapper" > "$capture/loader.txt" 2>&1 || true
  /usr/bin/codesign --display --verbose=4 "$wrapper" > "$capture/codesign.txt" 2>&1 || true
  /usr/bin/log show --last 1m --style compact \
    --predicate 'process == "sandboxd" OR eventMessage CONTAINS "process_wrapper"' \
    2>&1 | head -c 65536 > "$capture/sandbox-log.txt" || true
  /usr/bin/lldb --batch --no-lldbinit \
    --one-line-on-crash 'thread backtrace all' \
    --one-line-on-crash 'register read x0 x1 x2 x3 x4 x5' \
    --one-line-on-crash "memory read --format s --count 1 \$x4" \
    --one-line-on-crash 'process kill' \
    -o 'settings set target.disable-aslr false' \
    -o 'settings set target.process.stop-on-exec false' \
    -o 'process handle SIGABRT -s true -n true -p true' \
    -o run -o 'thread backtrace all' -o 'register read x0 x1 x2 x3 x4 x5' \
    -o "memory read --format s --count 1 \$x4" -o 'process kill' -- \
    /usr/bin/sandbox-exec -f "$capture/replay.sb" "$@" \
    > "$capture/debugger.txt" 2>&1 &
  debugger=$!
  (
    sleep 30 &
    timer=$!
    trap 'kill -TERM "$timer" 2>/dev/null || true; wait "$timer" 2>/dev/null || true' EXIT
    trap 'exit 143' TERM
    wait "$timer"
    kill -TERM "$debugger" 2>/dev/null || true
  ) &
  watchdog=$!
  wait "$debugger" || status=$?
  kill -TERM "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  printf 'Debugger status: %s (30-second bound; launch denial is inconclusive)\n' "$status" \
    >> "$capture/debugger.txt"
)

# Writes the Darwin replay policy shared by capture and its executable regression.
# Argument: Fresh owned capture directory; output: replay.sb below that directory.
write_replay_profile() {
  # dyld opens the root directory before starting env; literal grants no descendants.
  # mkdir -p inspects existing ancestors of its absolute output without reading contents.
  jq -nr --arg capture "$1" '
    ($capture | split("/")) as $parts |
    [range(2; $parts | length) as $end | $parts[0:$end] | join("/")] as $ancestors |
    "(version 1)\n(allow default)\n(deny file-write*)\n(deny file-read*)\n" +
    "(allow file-read* (literal \"/\") (subpath \"/System\") (subpath \"/usr\") (subpath \"/bin\") " +
    "(subpath \"/dev\") (subpath \"/private/etc\") (subpath \"/opt/homebrew\") " +
    "(subpath \"/usr/local\") (subpath " + ($capture|tojson) + "))\n" +
    (if ($ancestors | length) > 0 then
      "(allow file-read-metadata " + ($ancestors | map("(literal " + tojson + ")") | join(" ")) + ")\n"
    else "" end) +
    "(allow file-write* (subpath " + (($capture + "/work")|tojson) + ") " +
    "(literal " + (($capture + "/replay.stdout")|tojson) + ") " +
    "(literal " + (($capture + "/replay.stderr")|tojson) + "))"
  ' > "$1/replay.sb"
}

# Captures effective compiler arguments using the action's own process_wrapper.
# Arguments: 1: Selected native spawn; 2: Existing sandbox base; 3: Fresh capture directory;
#   4: Platform name (Darwin in CI, Linux for real-wrapper self-tests).
#   5: Execution root for a local unsandboxed spawn; unused for sandboxed spawns.
# Side effects: Retains authenticated preparation inputs, wrapper, native expanded bytes,
#   ordered child argv and effective arguments; removes its writable replay after consumption.
# Exit status: Nonzero for unsupported command shape, missing/ambiguous/altered evidence,
#   unavailable isolation, or an incomplete recording. Never runs rustc.
capture_effective_arguments() (
  set -euo pipefail
  local spawn="$1" sandbox_base="$2" capture="$3" platform="$4"
  local wrapper params0 params1 candidate root="" matches=0 path llvm status=0 capture_status=0 argument
  local -a environment=() replay_command=()
  jq -e '
    .mnemonic == "Rustc" and (.targetLabel | endswith("//codex-rs/cli:codex")) and
    (.environmentVariables | all(.[]; (.name | test("^[A-Za-z_][A-Za-z0-9_]*$")) and
      (.value | type == "string" and (contains("\u0000") | not)))) and
    (.runner == "local" or .runner == "darwin-sandbox") and
    (.cacheHit // false) == false and (.exitCode // 0) == 0 and (.status // "") == "" and
    any(.inputs[]; .path == "bazel/tools/dsymutil" and
      .digest.hash == "d99f57ec7e351a8a44f207b63f91076b060567f0a3080175e18cf1543f9d4488") and
    (.environmentVariables | map({key:.name,value:.value}) | from_entries |
      (.PATH | split(":")[0]) == "${pwd}/bazel/tools" and
      (.LLVM_DSYMUTIL | ltrimstr("$${pwd}/") | ltrimstr("${pwd}/") |
        test("^external/.*/(llvm-)?dsymutil$") and (contains("/../") | not)))
  ' "$spawn" > /dev/null || { echo 'Root spawn is cached, failed, nonnative, or lacks hermetic tool provenance' >&2; exit 1; }
  # This is the maintained scheduler's exact outer/inner boundary, not a shell command parser.
  jq -e '
    .commandArgs | length == 12 and .[1] == "--scheduler-outer" and
    .[2] == "--scheduler-output" and .[4] == "--scheduler-output" and
    .[6] == "--" and (.[7] | endswith("/process_wrapper")) and
    (.[8] | startswith("@")) and .[9] == "--" and
    (.[10] | endswith("/bin/rustc")) and (.[11] | startswith("@"))
  ' "$spawn" > /dev/null || { echo 'Unsupported native scheduler/wrapper command shape' >&2; exit 1; }
  wrapper="$(jq -r '.commandArgs[7]' "$spawn")"
  params0="$(jq -r '.commandArgs[8] | ltrimstr("@")' "$spawn")"
  params1="$(jq -r '.commandArgs[11] | ltrimstr("@")' "$spawn")"
  check_relative_path "$wrapper"
  check_relative_path "$params0"
  check_relative_path "$params1"
  mkdir "$capture"
  capture="$(cd -P "$capture" && pwd -P)"
  mkdir "$capture/raw" "$capture/work"
  # Scratch writes end here; the uploaded capture belongs to the caller.
  trap 'capture_status=$?; if [[ "${retain_failed_replay:-false}" != true || "$capture_status" == 0 ]]; then rm -rf -- "$capture/work"; fi' EXIT
  cp "$spawn" "$capture/spawn.json"
  while IFS= read -r -d '' candidate; do
    candidate="${candidate%/"$params1".expanded}"
    if check_input_digest "$spawn" "$candidate" "$params0" >> "$capture/candidate-inputs.log" 2>&1 &&
       check_input_digest "$spawn" "$candidate" "$params1" >> "$capture/candidate-inputs.log" 2>&1; then
      root="$(cd -P "$candidate" && pwd -P)"
      matches=$((matches + 1))
    fi
  done < <(
    find "$sandbox_base" -type f -path "*/$params1.expanded" -print0
    if [[ "$(jq -r '.runner' "$spawn")" == local && -n "${5:-}" && -f "$5/$params1.expanded" ]]; then
      printf '%s\0' "$5/$params1.expanded"
    fi
  )
  [[ "$matches" == 1 ]] || { printf 'Expected one authenticated native root sandbox; found %s\n' "$matches" >&2; exit 1; }
  local replay_root="$capture/work/$(basename -- "$root")"
  mkdir -p "$replay_root/external"
  cp "$root/$params1.expanded" "$capture/native.params.expanded"
  llvm="$(jq -r '.environmentVariables[] | select(.name == "LLVM_DSYMUTIL") | .value |
    ltrimstr("$${pwd}/") | ltrimstr("${pwd}/")' "$spawn")"
  check_input_digest "$spawn" "$root" bazel/tools/dsymutil
  check_input_digest "$spawn" "$root" "$llvm"
  # This root action supplies wrapper preparation data as these file types. Compiler
  # libraries stay outside replay; any unavailable preparation read fails closed.
  jq -er --arg wrapper "$wrapper" '
    .inputs | map(.path) |
    if length == (unique | length) then .[] else error("Duplicate input paths") end |
    select(. == $wrapper or test("[.](params|flags|linkflags|env|txt|linksearchpaths)$"))
  ' "$spawn" > "$capture/preparation-inputs.txt"
  while IFS= read -r path; do
    check_input_digest "$spawn" "$root" "$path"
    mkdir -p "$capture/raw/$(dirname -- "$path")" "$replay_root/$(dirname -- "$path")"
    cp "$root/$path" "$capture/raw/$path"
    check_input_digest "$spawn" "$capture/raw" "$path"
    chmod a-w "$capture/raw/$path"
    ln -s "$capture/raw/$path" "$replay_root/$path"
  done < "$capture/preparation-inputs.txt"
  chmod +x "$capture/raw/$wrapper"
  while IFS= read -r path; do
    check_relative_path "$path"
    mkdir -p "$replay_root/$(dirname -- "$path")"
  done < <(jq -r '.listedOutputs[]' "$spawn")
  mkdir "$replay_root/_recording"
  # Rust 1.99.1 args.rs consumes the wrapper's UTF-8 output as literal lines,
  # including empty arguments, without shell parsing or recursive @ expansion.
  {
    printf '#!/bin/bash\nset -euo pipefail\nrecording=%q\njq_tool=%q\n' \
      "$replay_root/_recording" "$(command -v jq)"
    cat <<'RECORDER'
printf '%s\0' "$@" > "$recording/child-argv.nul"
: > "$recording/argument-arrays.jsonl"
for argument do
  case "$argument" in
    @*.expanded)
      path="${argument#@}"
      canonical="$(cd -- "$(dirname -- "$path")" && pwd -P)/$(basename -- "$path")"
      case "$canonical" in
        "$PWD"/*) ;;
        *) echo 'Expanded response file escapes replay root' >&2; exit 2 ;;
      esac
      "$jq_tool" -Rsc 'split("\n") | if .[-1] == "" then .[:-1] else . end |
        map(sub("\r$"; ""))' "$path" >> "$recording/argument-arrays.jsonl"
      mkdir -p "$recording/expanded/$(dirname -- "$path")"
      cp "$path" "$recording/expanded/$path"
      ;;
    @*) echo 'Unsupported child response-file syntax' >&2; exit 2 ;;
    *) "$jq_tool" -nc --arg argument "$argument" '[$argument]' >> "$recording/argument-arrays.jsonl" ;;
  esac
done
"$jq_tool" -s 'add' "$recording/argument-arrays.jsonl" > "$recording/effective-arguments.json"
"$jq_tool" -e 'all(.[]; contains("shell-argfiles") | not)' \
  "$recording/effective-arguments.json" > "$recording/argument-validation.json"
printf '73\n' > "$recording/complete"
# The pinned wrapper skips compiler-output postprocessing for this child status.
exit 73
RECORDER
  } > "$capture/record-child"
  chmod +x "$capture/record-child"
  write_replay_profile "$capture"
  while IFS= read -r -d '' argument; do environment+=("$argument"); done < <(
    jq -j '.environmentVariables[] | .name + "=" + .value + "\u0000"' "$spawn"
  )
  replay_command=(/usr/bin/env -i "${environment[@]}" "TMPDIR=$capture/work" "HOME=$capture/work"
    "$capture/raw/$wrapper" "@$params0" -- "$capture/record-child" "@$params1")
  (
    cd "$replay_root"
    run_isolated_wrapper "$capture" "$platform" "${replay_command[@]}"
  ) > "$capture/replay.stdout" 2> "$capture/replay.stderr" || status=$?
  if [[ "$status" != 73 && "$platform" == Darwin && "${retain_failed_replay:-false}" == true ]]; then
    (cd "$replay_root"; collect_darwin_replay_evidence "$capture" "$capture/raw/$wrapper" "${replay_command[@]}")
  fi
  [[ "$status" == 73 && "$(cat "$replay_root/_recording/complete")" == 73 ]] || {
    printf 'Wrapper recording did not complete with sentinel status 73 (status %s)\n' "$status" >&2
    cat "$capture/replay.stderr" >&2
    exit 1
  }
  # The matching workspace layout keeps all three directory substitutions equivalent.
  jq -jRs --arg native_root "$root" --arg replay_root "$replay_root" \
    'split($native_root) | join($replay_root)' "$capture/native.params.expanded" > "$capture/native.normalized"
  cmp "$capture/native.normalized" "$replay_root/$params1.expanded"
  mv "$replay_root/_recording" "$capture/recording"
  cp "$capture/recording/effective-arguments.json" "$capture/effective-arguments.json"
  jq -Rs 'split("\u0000") | .[:-1]' "$capture/recording/child-argv.nul" > "$capture/child-argv.json"
  rm "$capture/native.normalized"
)

# Verifies root loader access while unrelated file reads remain denied on Darwin.
# Argument: Self-test-owned fixture; artifacts remain below its policy directory.
test_replay_policy() (
  set -euo pipefail
  local fixture="$1" capture="$1/policy" status=0 ancestor metadata_rule
  mkdir -p "$capture/raw" "$capture/work"
  write_replay_profile "$capture"
  grep -Fq -- '(literal "/")' "$capture/replay.sb"
  grep -Fxq -- '(deny file-read*)' "$capture/replay.sb"
  metadata_rule="$(grep -F '(allow file-read-metadata ' "$capture/replay.sb")"
  [[ "$metadata_rule" != *'(subpath '* ]]
  ancestor="${capture%/*}"
  while [[ -n "$ancestor" && "$ancestor" != / ]]; do
    grep -Fq -- "(literal \"$ancestor\")" <<< "$metadata_rule"
    ancestor="${ancestor%/*}"
  done
  if grep -Fq -- '(subpath "/")' "$capture/replay.sb"; then
    echo 'Self-test: root access must not authorize descendant reads' >&2
    exit 1
  fi
  if [[ "$(uname -s)" != Darwin ]]; then return; fi
  printf 'allowed replay bytes\n' > "$capture/raw/read-control"
  printf 'unrelated private bytes\n' > "$fixture/unrelated-read-control"
  [[ -r "$fixture/unrelated-read-control" ]]
  cd "$capture/work"
  run_isolated_wrapper "$capture" Darwin /usr/bin/env -i /bin/ls -d / \
    > root.stdout 2> root.stderr
  grep -Fxq / root.stdout
  run_isolated_wrapper "$capture" Darwin /usr/bin/env -i /bin/cat "$capture/raw/read-control" \
    > allowed.stdout 2> allowed.stderr
  cmp "$capture/raw/read-control" allowed.stdout
  mkdir -p "$capture/work/existing/output"
  run_isolated_wrapper "$capture" Darwin /usr/bin/env -i /bin/mkdir -p \
    "$capture/work/existing/output/expanded/nested" > mkdir.stdout 2> mkdir.stderr
  [[ -d "$capture/work/existing/output/expanded/nested" ]]
  run_isolated_wrapper "$capture" Darwin /usr/bin/env -i /bin/cat "$fixture/unrelated-read-control" \
    > denied.stdout 2> denied.stderr || status=$?
  [[ "$status" == 1 && ! -s denied.stdout ]]
  grep -Fq -- "$fixture/unrelated-read-control" denied.stderr
  grep -Eq -- 'Operation not permitted|Permission denied' denied.stderr
  status=0
  run_isolated_wrapper "$capture" Darwin /usr/bin/env -i /bin/mkdir -p "$fixture/unrelated-output" \
    > denied-mkdir.stdout 2> denied-mkdir.stderr || status=$?
  [[ "$status" == 1 && ! -e "$fixture/unrelated-output" ]]
  grep -Fq -- "$fixture/unrelated-output" denied-mkdir.stderr
  grep -Eq -- 'Operation not permitted|Permission denied' denied-mkdir.stderr
  echo 'PASS: Darwin loader root access, nested mkdir, unrelated-read and outside-write denial'
)

# Exercises recording and isolation with the real pinned wrapper, without running rustc.
# Arguments: 1: Self-test-owned fixture; 2: Executable patched process_wrapper.
# Exit status: Nonzero for a recording, ordering, authentication, or isolation regression.
test_wrapper_capture() (
  set -euo pipefail
  local fixture="$1/wrapper-capture" executable="$2" root params0 params1 wrapper flags path hash size mutation status
  local flags_path
  local empty_links=empty.linksearchpaths native_links=native.linksearchpaths link_flags=build.linkflags empty_link_flags=empty.linkflags false_command
  local retain_failed_replay="${retain_failed_self_test:-false}"
  mkdir "$fixture"
  root="$fixture/sandboxes/one/execroot/_main"
  params0=bazel-out/native/bin/codex-rs/cli/codex-0.params
  params1=bazel-out/native/bin/codex-rs/cli/codex-1.params
  flags=bazel-out/native/bin/codex-rs/cli/cli-build-script.flags
  wrapper=bazel-out/exec/bin/util/process_wrapper/process_wrapper
  mkdir -p "$root/$(dirname "$params0")" "$root/$(dirname "$wrapper")" "$root/bazel/tools" "$root/external/llvm/bin"
  cp "$executable" "$root/$wrapper"
  cp "$1/source/bazel/tools/dsymutil" "$root/bazel/tools/dsymutil"
  printf 'fixture dsymutil input\n' > "$root/external/llvm/bin/dsymutil"
  printf '%s\n' '--arg-file' "$empty_links" '--arg-file' "$native_links" '--arg-file' "$flags" \
    '--arg-file' "$empty_link_flags" '--arg-file' "$link_flags" '--env-file' 'build.env' \
    '--subst' "pwd=\${pwd}" '--subst' "output_base=\${output_base}" \
    '--subst' "exec_root=\${exec_root}" > "$root/$params0"
  : > "$root/$empty_links"
  : > "$root/$empty_link_flags"
  printf '%s\n' '-Lnative=fixture literal path' '-Csplit-debuginfo=off' > "$root/$native_links"
  printf '%s\n' 'FIXTURE_ENV=literal value' > "$root/build.env"
  printf '%s\n' 'nested literal argument' > "$root/nested.params"
  printf '%s\n' '--target=aarch64-apple-darwin' '-Csplit-debuginfo=off' \
    '-Csplit-debuginfo=packed' '-Cdebuginfo=2' 'literal space "quote" \backslash' \
    '@nested.params' "\${pwd}/literal-path" "\${output_base}/output-base-path" \
    "\${exec_root}/exec-root-path" > "$root/$params1"
  printf '%s\n' "\\" 'empty-line-successor' >> "$root/$params1"
  printf '%s\r\n' 'literal CRLF argument' >> "$root/$params1"
  printf '%s\n' '-Csplit-debuginfo=packed' > "$root/$flags"
  printf '%s\n' '-Clink-arg=fixture-linkflag' > "$root/$link_flags"
  # Resolve an external command: a spawn error also exits 1 and is not this control.
  false_command="$(type -P false)"
  [[ -x "$false_command" ]]
  status=0
  (cd "$root"; "$root/$wrapper" "@$params0" -- "$false_command" "@$params1") \
    > "$fixture/control.stdout" 2> "$fixture/control.stderr" || status=$?
  [[ "$status" == 1 ]]
  [[ ! -s "$fixture/control.stderr" && -f "$root/$params1.expanded" ]]
  printf 'Intentional child status 1: %s\n' "$false_command" > "$fixture/control.txt"
  : > "$fixture/inputs.jsonl"
  for path in "$params0" "$params1" "$empty_links" "$native_links" "$flags" "$link_flags" "$empty_link_flags" "$wrapper" build.env nested.params bazel/tools/dsymutil external/llvm/bin/dsymutil; do
    hash="$(shasum -a 256 "$root/$path" | awk '{print $1}')"
    size="$(wc -c < "$root/$path" | tr -d ' ')"
    jq -nc --arg path "$path" --arg hash "$hash" --arg size "$size" \
      '{path:$path,digest:{hash:$hash,sizeBytes:$size,hashFunctionName:"SHA-256"}}' >> "$fixture/inputs.jsonl"
  done
  jq -n --slurpfile inputs "$fixture/inputs.jsonl" --arg wrapper "$wrapper" \
    --arg params0 "$params0" --arg params1 "$params1" '{
    mnemonic:"Rustc",targetLabel:"//codex-rs/cli:codex",runner:"darwin-sandbox",cacheHit:false,exitCode:0,
    commandArgs:["bootstrap","--scheduler-outer","--scheduler-output","bazel-out/native/bin/codex-rs/cli/codex",
      "--scheduler-output","bazel-out/native/bin/codex-rs/cli/codex.dSYM","--",$wrapper,"@"+$params0,"--",
      "external/rust/bin/rustc","@"+$params1],
    environmentVariables:[{name:"PATH",value:"${pwd}/bazel/tools:/bin:/usr/bin:/usr/local/bin"},
      {name:"LLVM_DSYMUTIL",value:"external/llvm/bin/dsymutil"}],
    inputs:$inputs,listedOutputs:["bazel-out/native/bin/codex-rs/cli/codex","bazel-out/native/bin/codex-rs/cli/codex.dSYM"]
  }' > "$fixture/baseline.json"
  capture_effective_arguments "$fixture/baseline.json" "$fixture/sandboxes" "$fixture/valid" "$(uname -s)"
  check_effective_arguments "$fixture/valid/effective-arguments.json" aarch64-apple-darwin
  jq -e '.[-1] == "-Clink-arg=fixture-linkflag" and .[-2] == "-Csplit-debuginfo=packed" and .[-3] == "-Csplit-debuginfo=off" and
    .[-4] == "-Lnative=fixture literal path" and
    index("literal space \"quote\" \\backslash") != null and
    index("") != null and index("literal CRLF argument") != null and index("nested literal argument") != null' "$fixture/valid/effective-arguments.json" > /dev/null
  [[ -f "$fixture/valid/native.params.expanded" && -f "$fixture/valid/raw/$wrapper" &&
     -f "$fixture/valid/raw/$empty_links" && ! -s "$fixture/valid/raw/$empty_links" &&
     -s "$fixture/valid/raw/$native_links" && -f "$fixture/valid/raw/$flags" && ! -e "$fixture/valid/work" ]]
  mkdir "$fixture/local-sandboxes"
  jq '.runner="local"' "$fixture/baseline.json" > "$fixture/local.json"
  capture_effective_arguments "$fixture/local.json" "$fixture/local-sandboxes" "$fixture/local-capture" "$(uname -s)" "$root"
  check_effective_arguments "$fixture/local-capture/effective-arguments.json" aarch64-apple-darwin
  [[ ! -e "$fixture/local-capture/work" ]]
  for path in "$params0" "$params1" "$empty_links" "$native_links" "$flags" "$link_flags" "$empty_link_flags" "$wrapper" build.env nested.params bazel/tools/dsymutil external/llvm/bin/dsymutil; do
    check_input_digest "$fixture/baseline.json" "$root" "$path"
  done
  # Expected rejection cases keep their normal cleanup and do not launch a debugger.
  retain_failed_replay=false
  for path in "$empty_links" "$native_links" "$flags" "$link_flags" "$empty_link_flags"; do
    rm "$root/$path"
    expect_capture_rejection "$fixture/baseline.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
    grep -Fxq -- "Missing replay input: $path" "$fixture/rejected.rejection.log"
    [[ ! -e "$fixture/rejected/replay.stdout" && ! -e "$fixture/rejected/work" ]]
    rm -rf -- "$fixture/rejected"
    cp "$fixture/valid/raw/$path" "$root/$path"
    jq --arg path "$path" \
      '(.inputs[] | select(.path == $path) | .digest.hash) = "0000000000000000000000000000000000000000000000000000000000000000"' \
      "$fixture/baseline.json" > "$fixture/invalid.json"
    expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
    grep -Fxq -- "Input bytes differ from SpawnExec receipt: $path" "$fixture/rejected.rejection.log"
    [[ ! -e "$fixture/rejected/replay.stdout" && ! -e "$fixture/rejected/work" ]]
    rm -rf -- "$fixture/rejected"
  done
  for flags_path in "$flags" "$link_flags"; do
    chmod u+w "$root/$flags_path"
    for mutation in '-Csplit-debuginfo=off' '-Cdebuginfo=0' '--target=x86_64-apple-darwin'; do
      printf '%s\n' "$mutation" > "$root/$flags_path"
      hash="$(shasum -a 256 "$root/$flags_path" | awk '{print $1}')"
      size="$(wc -c < "$root/$flags_path" | tr -d ' ')"
      jq --arg path "$flags_path" --arg hash "$hash" --arg size "$size" \
        '(.inputs[] | select(.path == $path) | .digest) |= (.hash=$hash | .sizeBytes=$size)' \
        "$fixture/baseline.json" > "$fixture/late.json"
      capture_effective_arguments "$fixture/late.json" "$fixture/sandboxes" "$fixture/late-capture" "$(uname -s)"
      if check_effective_arguments "$fixture/late-capture/effective-arguments.json" aarch64-apple-darwin \
        > "$fixture/rejection.log" 2>&1; then
        echo 'Self-test accepted a late build-script override' >&2; exit 1
      fi
      rm -rf -- "$fixture/late-capture"
    done
    cp "$fixture/valid/raw/$flags_path" "$root/$flags_path"
  done
  for mutation in '.cacheHit=true' '.runner="remote"' '.exitCode=1' \
    '.commandArgs[8]="@../escape.params"' \
    '.inputs[0].digest.hash="0000000000000000000000000000000000000000000000000000000000000000"'; do
    jq "$mutation" "$fixture/baseline.json" > "$fixture/invalid.json"
    jq '.commandArgs' "$fixture/invalid.json" > "$fixture/invalid-arguments.json"
    expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
    [[ ! -e "$fixture/rejected/work" ]]
    rm -rf -- "$fixture/rejected"
  done
  printf '%s\n' '-Zshell-argfiles' > "$root/$flags"
  hash="$(shasum -a 256 "$root/$flags" | awk '{print $1}')"
  size="$(wc -c < "$root/$flags" | tr -d ' ')"
  jq --arg path "$flags" --arg hash "$hash" --arg size "$size" \
    '(.inputs[] | select(.path == $path) | .digest) |= (.hash=$hash | .sizeBytes=$size)' \
    "$fixture/baseline.json" > "$fixture/invalid.json"
  expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  rm -rf -- "$fixture/rejected"
  cp "$fixture/valid/raw/$flags" "$root/$flags"
  rm "$root/$flags"
  expect_capture_rejection "$fixture/baseline.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  rm -rf -- "$fixture/rejected"
  cp "$fixture/valid/raw/$flags" "$root/$flags"
  printf 'invalid json\n' > "$fixture/invalid.json"
  expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  mkdir -p "$fixture/sandboxes/two/execroot"
  cp -R "$root" "$fixture/sandboxes/two/execroot/_main"
  expect_capture_rejection "$fixture/baseline.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  rm -rf -- "$fixture/sandboxes/two"
  for path in "$params0" "$params1"; do
    cp "$root/$path" "$fixture/original-parameter"
    printf '\377\n' > "$root/$path"
    hash="$(shasum -a 256 "$root/$path" | awk '{print $1}')"
    size="$(wc -c < "$root/$path" | tr -d ' ')"
    jq --arg path "$path" --arg hash "$hash" --arg size "$size" \
      '(.inputs[] | select(.path == $path) | .digest) |= (.hash=$hash | .sizeBytes=$size)' \
      "$fixture/baseline.json" > "$fixture/invalid.json"
    expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
    rm -rf -- "$fixture/rejected"
    cp "$fixture/original-parameter" "$root/$path"
  done
  cp "$root/$params1.expanded" "$fixture/original-expanded"
  printf 'tampered native expansion\n' >> "$root/$params1.expanded"
  expect_capture_rejection "$fixture/baseline.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  rm -rf -- "$fixture/rejected"
  cp "$fixture/original-expanded" "$root/$params1.expanded"
  printf '%s\n' '--stderr-file' "$fixture/outside-replay" >> "$root/$params0"
  hash="$(shasum -a 256 "$root/$params0" | awk '{print $1}')"
  size="$(wc -c < "$root/$params0" | tr -d ' ')"
  jq --arg path "$params0" --arg hash "$hash" --arg size "$size" \
    '(.inputs[] | select(.path == $path) | .digest) |= (.hash=$hash | .sizeBytes=$size)' \
    "$fixture/baseline.json" > "$fixture/invalid.json"
  expect_capture_rejection "$fixture/invalid.json" "$fixture/sandboxes" "$fixture/rejected" "$(uname -s)"
  [[ ! -e "$fixture/outside-replay" && ! -e "$fixture/rejected/work" ]]
  echo 'PASS: real patched wrapper, response files, late overrides, authenticated capture and isolated replay'
)

# Requires capture to reject invalid fixture evidence while retaining its diagnostic.
# Arguments: The four capture_effective_arguments inputs.
# Exit status: Nonzero if invalid evidence is accepted.
expect_capture_rejection() {
  local status=0
  # A separate unconditional invocation preserves errexit inside the capture subshell.
  set +e
  (set -e; capture_effective_arguments "$@") > "$3.rejection.log" 2>&1
  status=$?
  set -e
  [[ "$status" -gt 0 ]] || { echo 'Self-test accepted invalid capture evidence' >&2; return 1; }
}

# Checks one successful native Codex spawn and verifies its resulting dSYM.
# Arguments:
#   1: Bazel JSON execution log, containing independent SpawnExec records.
#   2: Patched source root whose bazel-out symlink exposes the generated outputs.
#   3: Evidence directory owned by the caller.
#   4: dwarfdump executable; the self-test supplies a command-recording fixture.
#   5: Rust target triple matching the native macOS runner architecture.
#   6: Authenticated complete effective compiler arguments as a JSON array.
# Exit status: Nonzero for missing, cached, remote, nonhermetic, or invalid evidence.
# Side effects: Writes the selected spawn and dwarfdump evidence below argument 3.
verify_dsym_execution() {
  local execution_log="$1" source_root="$2" evidence="$3" dwarf_tool="$4"
  local native_target="$5"
  local wrapper_hash dsym_relative dsym
  # This is the three-line wrapper emitted by the independent issue-55693 patch.
  wrapper_hash=d99f57ec7e351a8a44f207b63f91076b060567f0a3080175e18cf1543f9d4488
  if [[ "$(shasum -a 256 "$source_root/bazel/tools/dsymutil" | awk '{print $1}')" != "$wrapper_hash" ]]; then
    echo 'Patched source does not contain the issue-55693 dsymutil wrapper' >&2
    return 1
  fi

  # jq processes independent SpawnExec records one at a time, retaining only Codex's final compile.
  jq -c 'select(.mnemonic == "Rustc" and (.targetLabel | endswith("//codex-rs/cli:codex")))' \
    "$execution_log" > "$evidence/codex-spawn.json" || return 1
  jq -es --arg wrapper_hash "$wrapper_hash" '
    length == 1 and (.[0] |
      (.environmentVariables | map({key: .name, value: .value}) | from_entries) as $env |
      ($env.LLVM_DSYMUTIL // "" | ltrimstr("$${pwd}/") | ltrimstr("${pwd}/")) as $llvm |
      (.runner == "local" or .runner == "darwin-sandbox") and
      (.cacheHit // false) == false and (.exitCode // 0) == 0 and (.status // "") == "" and
      (($env.PATH // "" | split(":")[0]) == "${pwd}/bazel/tools") and
      ($llvm | startswith("external/")) and
      ($llvm | test("/(llvm-)?dsymutil$")) and
      ($llvm | contains("/../") | not) and
      any(.inputs[]; .path == "bazel/tools/dsymutil" and .digest.hash == $wrapper_hash) and
      any(.inputs[]; .path == $llvm and (.digest.hash // "") != "")
    )' "$evidence/codex-spawn.json" > /dev/null || {
    echo 'Expected one successful uncached native Codex compile with the declared wrapper and external LLVM dsymutil input' >&2
    return 1
  }

  check_effective_arguments "$6" "$native_target" || return 1

  dsym_relative="$(jq -er '
    [.listedOutputs[] | select(endswith("/codex.dSYM"))] |
    if length == 1 then .[0] else error("Expected one Codex dSYM output") end
  ' "$evidence/codex-spawn.json")" || return 1
  case "$dsym_relative" in
    bazel-out/*/codex.dSYM) ;;
    *) echo "Invalid Codex dSYM output path: $dsym_relative" >&2; return 1 ;;
  esac
  dsym="$source_root/$dsym_relative"
  test -s "$dsym/Contents/Resources/DWARF/codex" || return 1
  test -s "${dsym%.dSYM}" || return 1
  "$dwarf_tool" --verify "$dsym" > "$evidence/dwarfdump-verify.txt" 2>&1 || return 1
  "$dwarf_tool" --uuid "${dsym%.dSYM}" > "$evidence/binary-uuid.txt" || return 1
  "$dwarf_tool" --uuid "$dsym" > "$evidence/dsym-uuid.txt" || return 1
  awk '$1 == "UUID:" {print $2, $3}' "$evidence/binary-uuid.txt" | sort > "$evidence/binary-uuid-values.txt" || return 1
  awk '$1 == "UUID:" {print $2, $3}' "$evidence/dsym-uuid.txt" | sort > "$evidence/dsym-uuid-values.txt" || return 1
  test -s "$evidence/binary-uuid-values.txt" || return 1
  cmp "$evidence/binary-uuid-values.txt" "$evidence/dsym-uuid-values.txt" || return 1
  printf 'Verified native hermetic dsymutil execution and matching valid dSYM: %s\n' "$dsym_relative"
}

# Exercises the acceptance boundary with Bazel SpawnExec and dwarfdump command fixtures.
# Arguments: 1: Existing temporary parent; the function removes its fresh child on exit.
#   2: Optional executable patched process_wrapper, enabling real recording/isolation tests.
#   3: Optional true retains failed fixtures for caller-owned upload and cleanup.
# Output: A pass message after positive and negative acceptance cases complete.
# Exit status: Nonzero when the acceptance boundary accepts invalid evidence or skips verification.
self_test() (
  local fixture status retain_failed_self_test="${3:-false}"
  fixture="$(mktemp -d "${1:?temporary parent required}/macos-dsym-self-test-XXXXXX")"
  trap 'status=$?; if [[ "$status" == 0 || "$retain_failed_self_test" != true ]]; then rm -rf -- "$fixture"; else printf "Retained failed self-test evidence: %s\n" "$fixture" >&2; fi' EXIT
  test_jvm_retention "$fixture"
  test_profiled_startup "$fixture"
  mkdir -p "$fixture/source/bazel/tools" "$fixture/evidence" \
    "$fixture/source/bazel-out/native/bin/codex-rs/cli/codex.dSYM/Contents/Resources/DWARF"
  cat > "$fixture/source/bazel/tools/dsymutil" <<'WRAPPER'
#!/bin/sh
# rustc invokes dsymutil by name; the C toolchain supplies its hermetic path.
exec "${LLVM_DSYMUTIL:?missing hermetic dsymutil}" "$@"
WRAPPER
  printf 'fixture binary\n' > "$fixture/source/bazel-out/native/bin/codex-rs/cli/codex"
  printf 'fixture DWARF bytes\n' > "$fixture/source/bazel-out/native/bin/codex-rs/cli/codex.dSYM/Contents/Resources/DWARF/codex"
  cat > "$fixture/dwarfdump" <<'DWARFDUMP'
#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")" && pwd)"
printf '%s\n' "$@" >> "$root/dwarfdump-calls"
if [[ "$1" == --verify ]]; then
  if [[ -e "$root/fail-verify" ]]; then echo 'Fixture DWARF verification rejected' >&2; fi
  test ! -e "$root/fail-verify"
elif [[ "$1" == --uuid ]]; then
  if [[ "$2" == *.dSYM && -e "$root/mismatch-uuid" ]]; then
    printf 'UUID: BBBB (arm64) %s\n' "$2"
  else
    printf 'UUID: AAAA (arm64) %s\n' "$2"
  fi
else
  exit 2
fi
DWARFDUMP
  chmod +x "$fixture/dwarfdump"
  # Schema: https://github.com/bazelbuild/bazel/blob/9.3.1/src/main/protobuf/spawn.proto
  jq -n '{
    mnemonic: "Rustc", targetLabel: "//codex-rs/cli:codex", runner: "local",
    cacheHit: false, exitCode: 0,
    commandArgs: ["rustc", "--target=aarch64-apple-darwin", "-Csplit-debuginfo=off", "-Csplit-debuginfo=packed", "-Cdebuginfo=2"],
    environmentVariables: [
      {name: "PATH", value: "${pwd}/bazel/tools:/bin:/usr/bin:/usr/local/bin"},
      {name: "LLVM_DSYMUTIL", value: "external/llvm/toolchain/bin/dsymutil"}
    ],
    inputs: [
      {path: "bazel/tools/dsymutil", digest: {hash: "d99f57ec7e351a8a44f207b63f91076b060567f0a3080175e18cf1543f9d4488"}},
      {path: "external/llvm/toolchain/bin/dsymutil", digest: {hash: "fixture-tool-digest"}}
    ],
    listedOutputs: ["bazel-out/native/bin/codex-rs/cli/codex", "bazel-out/native/bin/codex-rs/cli/codex.dSYM"]
  }' > "$fixture/baseline.json"
  jq '.commandArgs' "$fixture/baseline.json" > "$fixture/effective-arguments.json"
  verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin "$fixture/effective-arguments.json"
  if [[ ! -f "$fixture/dwarfdump-calls" ]] || ! grep -Fxq -- '--verify' "$fixture/dwarfdump-calls"; then
    echo 'Self-test: successful evidence must invoke dwarfdump --verify' >&2
    exit 1
  fi
  local mutation expected_rejection
  for mutation in \
    '.cacheHit = true' \
    '.runner = "remote"' \
    '.exitCode = 1' \
    '.inputs |= map(select(.path != "bazel/tools/dsymutil"))' \
    '.inputs[0].digest.hash = "changed-wrapper"' \
    '.environmentVariables[0].value = "/usr/bin:/bin"' \
    '.environmentVariables[1].value = "/usr/bin/dsymutil"' \
    '.inputs |= map(select(.path != "external/llvm/toolchain/bin/dsymutil"))' \
    '.commandArgs += ["-C", "split-debuginfo=off"]' \
    '.commandArgs += ["--target=x86_64-apple-darwin"]'; do
    jq "$mutation" "$fixture/baseline.json" > "$fixture/invalid.json"
    jq '.commandArgs' "$fixture/invalid.json" > "$fixture/invalid-arguments.json"
    jq -e --slurpfile spawn "$fixture/invalid.json" 'type == "array" and . == $spawn[0].commandArgs' \
      "$fixture/invalid-arguments.json" > /dev/null
    if [[ "$mutation" == .commandArgs* ]]; then
      expected_rejection='Effective rustc arguments do not select the native target and packed/full debug information'
    else
      expected_rejection='Expected one successful uncached native Codex compile with the declared wrapper and external LLVM dsymutil input'
    fi
    rm -f "$fixture/dwarfdump-calls"
    if (verify_dsym_execution "$fixture/invalid.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin "$fixture/invalid-arguments.json") \
      > "$fixture/rejection.log" 2>&1; then
      printf 'Self-test: accepted invalid spawn: %s\n' "$mutation" >&2
      exit 1
    fi
    if ! grep -Fxq -- "$expected_rejection" "$fixture/rejection.log" || [[ -e "$fixture/dwarfdump-calls" ]]; then
      printf 'Self-test: invalid spawn failed outside its intended boundary: %s\n' "$mutation" >&2
      cat "$fixture/rejection.log" >&2
      exit 1
    fi
  done
  check_effective_arguments "$fixture/effective-arguments.json" aarch64-apple-darwin
  rm -f "$fixture/dwarfdump-calls"
  touch "$fixture/fail-verify"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin "$fixture/effective-arguments.json") \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: dwarfdump verification failure must fail acceptance' >&2
    exit 1
  fi
  grep -Fxq 'Fixture DWARF verification rejected' "$fixture/evidence/dwarfdump-verify.txt"
  [[ "$(grep -Fxc -- '--verify' "$fixture/dwarfdump-calls")" == 1 ]]
  if grep -Fxq -- '--uuid' "$fixture/dwarfdump-calls"; then
    echo 'Self-test: rejected DWARF verification must stop before UUID inspection' >&2
    exit 1
  fi
  rm "$fixture/fail-verify"
  rm "$fixture/dwarfdump-calls"
  touch "$fixture/mismatch-uuid"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin "$fixture/effective-arguments.json") \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: mismatched binary and dSYM UUIDs must fail acceptance' >&2
    exit 1
  fi
  [[ "$(grep -Fxc -- '--verify' "$fixture/dwarfdump-calls")" == 1 &&
     "$(grep -Fxc -- '--uuid' "$fixture/dwarfdump-calls")" == 2 ]]
  grep -Fxq 'AAAA (arm64)' "$fixture/evidence/binary-uuid-values.txt"
  grep -Fxq 'BBBB (arm64)' "$fixture/evidence/dsym-uuid-values.txt"
  grep -Fq 'differ:' "$fixture/rejection.log"
  rm "$fixture/mismatch-uuid"
  rm "$fixture/dwarfdump-calls"
  rm "$fixture/source/bazel-out/native/bin/codex-rs/cli/codex.dSYM/Contents/Resources/DWARF/codex"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin "$fixture/effective-arguments.json") \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: missing generated DWARF bytes must fail acceptance' >&2
    exit 1
  fi
  [[ ! -e "$fixture/dwarfdump-calls" ]]
  test_replay_policy "$fixture"
  if [[ -n "${2:-}" ]]; then test_wrapper_capture "$fixture" "$2"; fi
  echo 'PASS: native dSYM acceptance boundary; command fixtures do not verify macOS compilation or DWARF validity'
)

if [[ "${1:-}" == --finish-profile ]]; then
  source_root="$(cd -- "${2:?patched upstream required}" && pwd)"
  evidence="${3:?evidence directory required}"
  profiling_jdk="${4:?profiling JDK required}"
  if [[ -d "$evidence" ]]; then
    cd "$source_root"
    run_profiled_bazel "$evidence" "$profiling_jdk" shutdown
    "$profiling_jdk/bin/jfr" summary "$evidence/bazel-allocation.jfr" > "$evidence/bazel-allocation-summary.txt"
  fi
elif [[ "${1:-}" == --self-test-evidence ]]; then
  self_test "${2:?temporary parent required}" "${3:?patched process_wrapper required}" true
elif [[ "${1:-}" == --self-test ]]; then
  self_test "${2:?temporary parent required}" "${3:-}"
else
  if [[ $# != 2 ]]; then
    echo "Usage: $0 <patched-upstream> <new-evidence-directory> | --self-test <temporary-parent> [patched-process-wrapper]" >&2
    exit 2
  fi
  if [[ "$(uname -s)" != Darwin ]]; then
    echo 'Native macOS dSYM acceptance requires a macOS runner' >&2
    exit 1
  fi
  case "$(uname -m)" in
    arm64) native_target=aarch64-apple-darwin ;;
    x86_64) native_target=x86_64-apple-darwin ;;
    *) echo 'Unsupported native macOS architecture' >&2; exit 1 ;;
  esac
  source_root="$(cd -- "$1" && pwd)"
  evidence="$2"
  [[ "$evidence" == /* ]] || { echo 'Evidence directory must be absolute' >&2; exit 2; }
  [[ "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}" =~ ^[0-9]+$ ]]
  [[ "${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT is required}" =~ ^[0-9]+$ ]]
  command -v jq > /dev/null
  profiling_jdk="${BAZEL_PROFILING_JAVA_HOME:?BAZEL_PROFILING_JAVA_HOME is required}"
  dwarf_tool="$(command -v dwarfdump)"
  # The job uploads this evidence before its always-run cleanup removes it.
  mkdir "$evidence"
  { uname -a; printf 'Rust target: %s\ndwarfdump: %s\n' "$native_target" "$dwarf_tool"; } > "$evidence/native-host.txt"
  "$profiling_jdk/bin/java" -version >> "$evidence/native-host.txt" 2>&1
  "$profiling_jdk/bin/java" --list-modules | grep '^jdk.jfr@' >> "$evidence/native-host.txt"
  sandbox_base="$(mktemp -d "$evidence/sandboxes.XXXXXX")"
  trap 'finish_dsym_diagnostic "$?" "$sandbox_base" "$evidence" "${BAZEL_OUTPUT_BASE:-}"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  # Fail cheaply on native converter/JNI incompatibility before the uncached Codex action.
  bash "$script_dir/bazel-compact-execution-log.sh" prepare "$evidence/compact-log" "$profiling_jdk"
  cd "$source_root"
  # Scope packed/full debug information to Codex; dependency and exec-tool profiles stay unchanged.
  # A distinct inert cfg per run attempt forces only this binary's action to execute, retaining dependency caches.
  # ci-macos enables Linux RBE when credentials exist. Cache-only ci-bazel keeps execution native;
  # the maintained Java wrapper still owns compiler scheduling, credentials, and repository caches.
  # Record allocation samples with stacks; settings=none excludes initial environment and system properties.
  build_status=0
  run_profiled_bazel "$evidence" "$profiling_jdk" \
    build --config=ci-bazel \
    --jobs=2 --loading_phase_threads=1 --local_resources=cpu=1 \
    --notrack_incremental_state --nokeep_state_after_build --remote_download_outputs=toplevel \
    --experimental_remote_cache_chunking \
    --materialize_param_files --sandbox_debug "--sandbox_base=$sandbox_base" \
    --output_groups=+dsym_folder \
    "--execution_log_compact_file=$evidence/execution.compact" \
    --@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@-Csplit-debuginfo=packed \
    --@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@-Cdebuginfo=2 \
    "--@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@--cfg=codex_dsym_diagnostic_${GITHUB_RUN_ID}_${GITHUB_RUN_ATTEMPT}" \
    "--build_metadata=COMMIT_SHA=${GITHUB_SHA:?GITHUB_SHA is required}" -- //codex-rs/cli:codex \
    2>&1 | tee "$evidence/bazel-build.log" || build_status=$?
  # Decode outside Bazel's JVM, retaining build failure as the primary result if decoding also fails.
  conversion_status=0
  bash "$script_dir/bazel-compact-execution-log.sh" convert "$evidence/compact-log" "$profiling_jdk" \
    "$evidence/execution.compact" "$evidence/execution.json" || conversion_status=$?
  if [[ "$build_status" != 0 ]]; then exit "$build_status"; fi
  if [[ "$conversion_status" != 0 ]]; then exit "$conversion_status"; fi
  jq -c 'select(.mnemonic == "Rustc" and (.targetLabel | endswith("//codex-rs/cli:codex")))' \
    "$evidence/execution.json" > "$evidence/codex-spawn.json"
  [[ "$(jq -s length "$evidence/codex-spawn.json")" == 1 ]]
  local_execroot="$(cd -P "$source_root/bazel-out" && cd -P .. && pwd -P)"
  capture_effective_arguments "$evidence/codex-spawn.json" "$sandbox_base" "$evidence/argument-capture" Darwin "$local_execroot"
  verify_dsym_execution "$evidence/execution.json" "$source_root" "$evidence" "$dwarf_tool" "$native_target" \
    "$evidence/argument-capture/effective-arguments.json"
fi
