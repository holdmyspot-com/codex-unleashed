#!/usr/bin/env bash
# Builds only the native Codex Bazel binary and verifies its hermetic dSYM generation.
# Usage: diagnose-macos-bazel-dsym.sh <patched-upstream> <new-evidence-directory>
#        diagnose-macos-bazel-dsym.sh --self-test <temporary-parent>
# Native execution requires macOS, the maintained CI setup, jq, and dwarfdump.
# The diagnostic job owns evidence cleanup after its always-run artifact upload.
set -euo pipefail

# Checks one successful native Codex spawn and verifies its resulting dSYM.
# Arguments:
#   1: Bazel JSON execution log, containing independent SpawnExec records.
#   2: Patched source root whose bazel-out symlink exposes the generated outputs.
#   3: Evidence directory owned by the caller.
#   4: dwarfdump executable; the self-test supplies a command-recording fixture.
#   5: Rust target triple matching the native macOS runner architecture.
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
  jq -es --arg wrapper_hash "$wrapper_hash" --arg native_target "$native_target" '
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
    length == 1 and (.[0] |
      (.environmentVariables | map({key: .name, value: .value}) | from_entries) as $env |
      ($env.LLVM_DSYMUTIL // "" | ltrimstr("$${pwd}/") | ltrimstr("${pwd}/")) as $llvm |
      (.commandArgs | codegen_flags) as $flags |
      (.runner == "local" or .runner == "darwin-sandbox") and
      (.commandArgs | targets | last) == $native_target and
      (.cacheHit // false) == false and (.exitCode // 0) == 0 and (.status // "") == "" and
      (($env.PATH // "" | split(":")[0]) == "${pwd}/bazel/tools") and
      ($llvm | startswith("external/")) and
      ($llvm | test("/(llvm-)?dsymutil$")) and
      ($llvm | contains("/../") | not) and
      any(.inputs[]; .path == "bazel/tools/dsymutil" and .digest.hash == $wrapper_hash) and
      any(.inputs[]; .path == $llvm and (.digest.hash // "") != "") and
      ([$flags[] | select(startswith("-Csplit-debuginfo="))] | last) == "-Csplit-debuginfo=packed" and
      ([$flags[] | select(startswith("-Cdebuginfo="))] | last) == "-Cdebuginfo=2"
    )' "$evidence/codex-spawn.json" > /dev/null || {
    echo 'Expected one successful uncached native Codex compile with packed debug info, the declared wrapper, and a declared external LLVM dsymutil input' >&2
    return 1
  }

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
# Output: A pass message after positive and negative acceptance cases complete.
# Exit status: Nonzero when the acceptance boundary accepts invalid evidence or skips verification.
self_test() (
  local fixture
  fixture="$(mktemp -d "${1:?temporary parent required}/macos-dsym-self-test-XXXXXX")"
  trap 'rm -rf -- "$fixture"' EXIT
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
  verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin
  if [[ ! -f "$fixture/dwarfdump-calls" ]] || ! grep -Fxq -- '--verify' "$fixture/dwarfdump-calls"; then
    echo 'Self-test: successful evidence must invoke dwarfdump --verify' >&2
    exit 1
  fi
  local mutation
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
    if (verify_dsym_execution "$fixture/invalid.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin) \
      > "$fixture/rejection.log" 2>&1; then
      printf 'Self-test: accepted invalid spawn: %s\n' "$mutation" >&2
      exit 1
    fi
  done
  touch "$fixture/fail-verify"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin) \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: dwarfdump verification failure must fail acceptance' >&2
    exit 1
  fi
  rm "$fixture/fail-verify"
  touch "$fixture/mismatch-uuid"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin) \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: mismatched binary and dSYM UUIDs must fail acceptance' >&2
    exit 1
  fi
  rm "$fixture/mismatch-uuid"
  rm "$fixture/source/bazel-out/native/bin/codex-rs/cli/codex.dSYM/Contents/Resources/DWARF/codex"
  if (verify_dsym_execution "$fixture/baseline.json" "$fixture/source" "$fixture/evidence" "$fixture/dwarfdump" aarch64-apple-darwin) \
    > "$fixture/rejection.log" 2>&1; then
    echo 'Self-test: missing generated DWARF bytes must fail acceptance' >&2
    exit 1
  fi
  echo 'PASS: native dSYM acceptance boundary; command fixtures do not verify macOS compilation or DWARF validity'
)

if [[ "${1:-}" == --self-test ]]; then
  self_test "${2:?temporary parent required}"
else
  if [[ $# != 2 ]]; then
    echo "Usage: $0 <patched-upstream> <new-evidence-directory> | --self-test <temporary-parent>" >&2
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
  dwarf_tool="$(command -v dwarfdump)"
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  # The job uploads this evidence before its always-run cleanup removes it.
  mkdir "$evidence"
  { uname -a; printf 'Rust target: %s\ndwarfdump: %s\n' "$native_target" "$dwarf_tool"; } > "$evidence/native-host.txt"
  cd "$source_root"
  # Scope packed/full debug information to Codex; dependency and exec-tool profiles stay unchanged.
  # A distinct inert cfg per run attempt forces only this binary's action to execute, retaining dependency caches.
  # ci-macos enables Linux RBE when credentials exist. Cache-only ci-bazel keeps execution native;
  # the maintained Java wrapper still owns compiler scheduling, credentials, and repository caches.
  bash "$script_dir/run-bazel-with-buildbuddy.sh" build --config=ci-bazel \
    --jobs=2 --loading_phase_threads=1 --local_resources=cpu=1 \
    --notrack_incremental_state --nokeep_state_after_build --remote_download_outputs=toplevel \
    --output_groups=+dsym_folder \
    "--execution_log_json_file=$evidence/execution.json" \
    --@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@-Csplit-debuginfo=packed \
    --@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@-Cdebuginfo=2 \
    "--@rules_rust//rust/settings:per_crate_rustc_flag=//codex-rs/cli:codex@--cfg=codex_dsym_diagnostic_${GITHUB_RUN_ID}_${GITHUB_RUN_ATTEMPT}" \
    "--build_metadata=COMMIT_SHA=${GITHUB_SHA:?GITHUB_SHA is required}" -- //codex-rs/cli:codex
  verify_dsym_execution "$evidence/execution.json" "$source_root" "$evidence" "$dwarf_tool" "$native_target"
fi
