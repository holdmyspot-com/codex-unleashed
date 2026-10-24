#!/usr/bin/env bash
# Replays one authenticated Intel archive shard between serial controls.
# Workflow owns source/artifact/cache authentication and all downloaded inputs.
# Environment: RUNNER_TEMP, GITHUB_WORKSPACE, ARCHIVE_SHARD (1 or 2).
# Output: bounded resource samples, selection JSON, phase logs and exact statuses.
# Errors: setup failures stop; test failures retain all three phases and return
# the full-shard failure first, otherwise the first failed serial/tee status.
set -euo pipefail

# Requires an exact nonignored selection count containing every required test.
# Arguments: nextest list JSON path, expected count, required package/test pairs JSON.
# Errors: jq rejects malformed, missing, extra or ignored required selections.
verify_selection() {
  jq -e --argjson count "$2" --argjson required "$3" '
    [."rust-suites"[] | ."package-name" as $package | .testcases | to_entries[] |
      select(.value."filter-match".status == "matches" and .value.ignored == false) |
      [$package, .key]] as $matches |
    ($matches | length) == $count and ($required - $matches | length) == 0
  ' "$1"
}

# Captures at most 180 samples, 20 seconds apart, with at most 128 process rows sorted by CPU.
# Output: CPU/memory/pressure and PID/CPU/RSS/command basename; never argv or env.
# Side effects: owns one sleep child; signal/exit cleanup reaps that child.
sample_resources() {
  set +e
  sleeper=
  trap 'if [[ -n "$sleeper" ]]; then kill "$sleeper" 2>/dev/null; wait "$sleeper" 2>/dev/null; fi' EXIT
  trap 'exit 0' TERM INT
  for ((sample=1; sample<=180; sample++)); do
    printf '\nsample=%s\n' "$sample"
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    sysctl hw.model hw.logicalcpu hw.physicalcpu hw.memsize vm.swapusage
    top -l 1 -n 0
    vm_stat
    memory_pressure -Q
    ps -r -axo pid=,pcpu=,rss=,comm= | awk '
      NR <= 128 { pid=$1; cpu=$2; rss=$3; $1=$2=$3="";
        sub(/^ +/, ""); sub(/^.*\//, ""); print pid, cpu, rss, $0 }
    '
    sleep 20 &
    sleeper=$!
    wait "$sleeper"
    sleeper=
  done
}

# Runs a single phase and records nextest and tee status separately.
# Arguments: evidence root, workspace root, shard, exact filter, phase name.
# Errors: returns nextest's status first, otherwise tee's status.
run_phase() {
  local evidence="$1" workspace="$2" shard="$3" filter="$4" phase="$5"
  local -a selection=(--test-threads 1 -E "$filter") pipeline_status
  if [[ "$phase" == original-workload ]]; then
    selection=(--partition "hash:$shard/4")
  fi
  cargo nextest run \
    --archive-file "$evidence/archive/nextest-macos-x64.tar.zst" \
    --extract-to "$evidence/extracted" --extract-overwrite \
    --workspace-remap "$workspace" \
    --config-file "$workspace/.config/nextest.toml" --profile default \
    "${selection[@]}" --retries 0 --no-fail-fast --no-tests fail \
    --failure-output immediate-final 2>&1 | tee "$evidence/$phase.log"
  pipeline_status=("${PIPESTATUS[@]}")
  printf 'nextest=%s\ntee=%s\n' "${pipeline_status[0]}" "${pipeline_status[1]}" > "$evidence/$phase.status"
  if [[ "${pipeline_status[0]}" != 0 ]]; then
    return "${pipeline_status[0]}"
  fi
  return "${pipeline_status[1]}"
}

# Owns the sampler while collecting all phases, even after a test failure.
# Arguments: evidence root, workspace root, shard, exact filter.
# Errors: returns full-shard failure first, otherwise before/after control failure.
run_phases() (
  set +e
  local evidence="$1" workspace="$2" shard="$3" filter="$4"
  local monitor before workload after
  sample_resources > "$evidence/resource-samples.log" 2>&1 &
  monitor=$!
  printf '%s\n' "$monitor" > "$evidence/sampler.pid"
  trap 'kill "$monitor" 2>/dev/null; wait "$monitor" 2>/dev/null' EXIT
  trap 'exit 143' TERM
  trap 'exit 130' INT
  run_phase "$evidence" "$workspace" "$shard" "$filter" serial-before
  before=$?
  run_phase "$evidence" "$workspace" "$shard" "$filter" original-workload
  workload=$?
  run_phase "$evidence" "$workspace" "$shard" "$filter" serial-after
  after=$?
  printf 'serial-before=%s\noriginal-workload=%s\nserial-after=%s\n' "$before" "$workload" "$after" > "$evidence/phase-status.txt"
  if [[ "$workload" != 0 ]]; then exit "$workload"; fi
  if [[ "$before" != 0 ]]; then exit "$before"; fi
  exit "$after"
)

# Checks immutable policy and full/focused selection before starting any tests.
# Arguments: evidence root, workspace root, current release shard (1 or 2).
# Errors: rejects unsupported shards and hash/count/membership drift before execution.
main() {
  local evidence="$1" workspace="$2" shard="$3" count filter required focused_count
  case "$shard" in
    1)
      count=5498
      required='[["codex-core","client::tool_metadata::tests::message_budget_removes_residual_values_and_markers_when_ordinary_input_fits"]]'
      filter='package(codex-core) & test(=client::tool_metadata::tests::message_budget_removes_residual_values_and_markers_when_ordinary_input_fits)'
      focused_count=1
      ;;
    2)
      count=5457
      required='[["codex-exec","suite::daybreak::exec_resumes_and_forks_the_saved_daybreak_choice"],["codex-feedback","tests::feedback_upload_delivers_large_files_intact"]]'
      filter='(package(codex-exec) & test(=suite::daybreak::exec_resumes_and_forks_the_saved_daybreak_choice)) | (package(codex-feedback) & test(=tests::feedback_upload_delivers_large_files_intact))'
      focused_count=2
      ;;
    *) printf 'Unsupported current release shard: %s\n' "$shard" >&2; return 2 ;;
  esac
  [[ "$(git -C "$workspace/../.." rev-parse HEAD)" == 84ba3d0eafea32eb6c2da055891263c70e5abd9e ]]
  [[ "$(git -C "$workspace" rev-parse HEAD)" == 83086188ae0614bd882818ff1aa23f3194962012 ]]
  cd "$workspace"
  cmp .config/nextest.toml "$evidence/original-nextest.toml"
  local -a sha256=(sha256sum)
  if command -v shasum >/dev/null 2>&1; then sha256=(shasum -a 256); fi
  "${sha256[@]}" "$evidence/original-nextest.toml" > "$evidence/original-nextest.sha256"
  printf '%s  %s\n' f411d7e8ea0a8b60a3cdd9a1e47223ad964e2d2fd5f80d0abe4d877b41851817 \
    "$evidence/original-nextest.toml" | "${sha256[@]}" -c
  local -a archive=(--archive-file "$evidence/archive/nextest-macos-x64.tar.zst"
    --extract-to "$evidence/extracted" --workspace-remap "$workspace"
    --config-file "$workspace/.config/nextest.toml" --profile default)
  mkdir "$evidence/extracted"
  cargo nextest list "${archive[@]}" --partition "hash:$shard/4" --message-format json > "$evidence/full-selection.json"
  verify_selection "$evidence/full-selection.json" "$count" "$required"
  cargo nextest list "${archive[@]}" --extract-overwrite -E "$filter" --message-format json > "$evidence/focused-selection.json"
  verify_selection "$evidence/focused-selection.json" "$focused_count" "$required"
  printf 'source-run=40093103558\nsource-head=84ba3d0eafea32eb6c2da055891263c70e5abd9e\npartition=hash:%s/4\nfull-count=%s\nfocused-count=%s\nprofile=default\nretries=0\nfilter=%s\n' \
    "$shard" "$count" "$focused_count" "$filter" > "$evidence/contention-provenance.txt"
  run_phases "$evidence" "$workspace" "$shard" "$filter"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "${RUNNER_TEMP:?}/archived-test-diagnostic" "${GITHUB_WORKSPACE:?}/upstream/codex-rs" "${ARCHIVE_SHARD:?}"
fi
