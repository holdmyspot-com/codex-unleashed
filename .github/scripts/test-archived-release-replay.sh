#!/usr/bin/env bash
# Exercises pinned archive metadata, policy and selection shell without hosted downloads.
# Pass an owned fixture parent directory; fixtures are removed on every exit.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
workflow="$script_dir/../workflows/diagnose-archived-tests.yml"
fixture="$(mktemp -d "${1:?owned fixture parent directory required}/archive-replay-XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/tmp" "$fixture/workspace"

# Extracts the executable Bash body of one named workflow step.
# Arguments: step name.
# Output: the step's shell script, without YAML indentation.
# Errors: exits 1 if the step has no executable body.
extract_step() {
  awk -v name="$1" '
    $0 == "      - name: " name { selected = 1; next }
    selected && /^      - / { exit }
    selected && /^        run: \|/ { copying = 1; next }
    copying { sub(/^          /, ""); print; lines++ }
    END { if (lines == 0) exit 1 }
  ' "$workflow"
}

# Requires a workflow-shell invocation to succeed.
# Arguments: descriptive control name followed by command and literal arguments.
# Side effects: captures stdout/stderr under the owned fixture.
# Errors: exits 1 with captured diagnostics if the command fails.
expect_ok() {
  local label="$1"
  shift
  if "$@" > "$fixture/stdout" 2> "$fixture/stderr"; then
    :
  else
    printf 'FAIL: %s\n' "$label" >&2
    cat "$fixture/stdout" "$fixture/stderr" >&2
    exit 1
  fi
}

# Requires a workflow-shell invocation to reject its controlled input.
# Arguments: descriptive control name followed by command and literal arguments.
# Side effects: captures stdout/stderr under the owned fixture.
# Errors: exits 1 if the command accepts the input.
expect_rejected() {
  local label="$1"
  shift
  if "$@" > "$fixture/stdout" 2> "$fixture/stderr"; then
    printf 'FAIL: accepted %s\n' "$label" >&2
    exit 1
  fi
}

extract_step 'Authenticate the current archive source and artifact identities' > "$fixture/auth.sh"

export RUNNER_TEMP="$fixture/tmp" GITHUB_WORKSPACE="$fixture/workspace"
export GITHUB_OUTPUT="$fixture/output" GITHUB_REPOSITORY=holdmyspot-com/codex-unleashed
export SOURCE_RUN=40093103558 ORIGINAL_SOURCE_POLICY=false PRESERVE_ARCHIVE_POLICY=true
export ARM_LAUNCH_CONTROLS=false ARCHIVE_SHARD='' ARTIFACT_ID=macos-x64
export RUNNER_OS=macOS RUNNER_ARCH=X64 FIXTURE_METADATA="$fixture/metadata.json"
export PATH="$fixture/bin:$PATH"
# GH is the external metadata boundary. Recorded API shapes model its replies;
# these controls exercise our shell's decisions, not GitHub's implementation.
cat > "$fixture/bin/gh" <<'GH'
#!/usr/bin/env bash
set -euo pipefail
endpoint="${*: -1}"
jq -e --arg key "${endpoint##*/}" '.[$key]' "$FIXTURE_METADATA"
GH
chmod +x "$fixture/bin/gh"
jq -n '
  "84ba3d0eafea32eb6c2da055891263c70e5abd9e" as $head |
  "72118e7a449834add11bbcb8fef63642430ea200" as $old_head |
  {
    "40093103558": {id:40093103558, status:"completed", head_sha:$head,
      path:".github/workflows/rust-ci-full.yml",
      repository:{full_name:"holdmyspot-com/codex-unleashed"},
      head_repository:{full_name:"holdmyspot-com/codex-unleashed"}},
    "12563197007": {id:12563197007, name:"nextest-archive-macos-x64", expired:false,
      digest:"sha256:ad342d508886a363f215e3367d5136e6c0beaed52306f75c559d2806db41133f",
      workflow_run:{id:40093103558, head_sha:$head}},
    "12563476183": {id:12563476183, name:"nextest-native-macos-x64", expired:false,
      digest:"sha256:1d8939c44f0416019b16ba24908092da6d00e3774a26637580edf4088f06b3b7",
      workflow_run:{id:40093103558, head_sha:$head}},
    "40038389605": {id:40038389605, status:"completed", head_sha:$old_head},
    "12541876650": {id:12541876650, name:"nextest-archive-macos-x64", expired:false,
      digest:"sha256:236d917b0c71b77aad333c4ea95c2b08759434c425d4aae509572762d8807230",
      workflow_run:{id:40038389605, head_sha:$old_head}},
    "12541762119": {id:12541762119, name:"nextest-native-macos-x64", expired:false,
      digest:"sha256:c5839312e781b23659ebd6dbc98ec6741dbe51d9bdb4ca379b5b2d18367c9962",
      workflow_run:{id:40038389605, head_sha:$old_head}}
  }' > "$fixture/valid.json"
cp "$fixture/valid.json" "$FIXTURE_METADATA"

expect_ok 'pinned release identity' bash "$fixture/auth.sh"
printf '%s\n' head-sha=84ba3d0eafea32eb6c2da055891263c70e5abd9e \
  archive-id=12563197007 native-id=12563476183 > "$fixture/expected-output"
cmp "$fixture/expected-output" "$GITHUB_OUTPUT"
echo 'PASS 1: pinned identity publishes exactly its authenticated inputs'

for override in SOURCE_RUN=40093103559 ORIGINAL_SOURCE_POLICY=true PRESERVE_ARCHIVE_POLICY=false \
  ARCHIVE_SHARD=1 RUNNER_ARCH=ARM64 ARTIFACT_ID=linux-x64; do
  expect_rejected "$override" env "$override" bash "$fixture/auth.sh"
done
echo 'PASS 2: wrong run and execution modes are refused'

for mutation in \
  '.["40093103558"].head_sha = ("0" * 40)' \
  '.["40093103558"].status = "in_progress"' \
  '.["40093103558"].path = ".github/workflows/other.yml"' \
  '.["12563197007"].digest = ("sha256:" + ("0" * 64))' \
  '.["12563476183"].expired = true' \
  '.["12563476183"].name = "foreign-runtime"'; do
  jq "$mutation" "$fixture/valid.json" > "$FIXTURE_METADATA"
  expect_rejected "$mutation" bash "$fixture/auth.sh"
done
echo 'PASS 3: changed source or artifact metadata is refused'

for mutation in \
  '.["12563197007"].workflow_run.id = 40038389605' \
  '.["12563476183"].workflow_run.head_sha = ("0" * 40)' \
  '.["40093103558"].head_repository.full_name = "foreign/repository"'; do
  jq "$mutation" "$fixture/valid.json" > "$FIXTURE_METADATA"
  expect_rejected "$mutation" bash "$fixture/auth.sh"
done
echo 'PASS 4: cross-run artifacts and foreign source repositories are refused'

cp "$fixture/valid.json" "$FIXTURE_METADATA"
rm "$GITHUB_OUTPUT"
expect_ok 'historical identity' env SOURCE_RUN=40038389605 bash "$fixture/auth.sh"
grep -Fxq 'head-sha=72118e7a449834add11bbcb8fef63642430ea200' "$GITHUB_OUTPUT"
echo 'PASS 5: historical authentication remains supported'

extract_step 'Verify the current default policy and archived test selection' > "$fixture/verify.sh"
extract_step 'Probe the pinned release runtime without rebuilding' > "$fixture/runtime.sh"

cat > "$fixture/selection.sh" <<'SCRIPT'
set -euo pipefail
diagnostic_root="$RUNNER_TEMP/archived-test-diagnostic"
SCRIPT
awk '
  /^elif \[\[ "\$SOURCE_RUN" == 40093103558 \]\]; then$/ { copying = 1; next }
  copying && /^else$/ { exit }
  copying { print; lines++ }
  END { if (lines == 0) exit 1 }
' "$fixture/verify.sh" >> "$fixture/selection.sh"
pairs='[["codex-core","client::tool_metadata::tests::message_budget_removes_residual_values_and_markers_when_ordinary_input_fits"], ["codex-exec","suite::daybreak::exec_resumes_and_forks_the_saved_daybreak_choice"], ["codex-feedback","tests::feedback_upload_delivers_large_files_intact"]]'
for mode in valid missing extra ignored; do
  jq -n --arg mode "$mode" --argjson pairs "$pairs" '
    (if $mode == "missing" then $pairs[0:2]
     elif $mode == "extra" then $pairs + [["codex-core","unrelated"]]
     else $pairs end) |
    {"rust-suites": (to_entries | map({key:(.key|tostring), value:{
      "package-name":.value[0], testcases:{(.value[1]):{
        "filter-match":{status:"matches"}, ignored:($mode == "ignored")}}
    }}) | from_entries)}' > "$RUNNER_TEMP/archived-test-diagnostic/selected-tests.json"
  if [[ "$mode" == valid ]]; then
    expect_ok 'exact three nonignored tests' bash "$fixture/selection.sh"
  else
    expect_rejected "$mode selected tests" bash "$fixture/selection.sh"
  fi
done
echo 'PASS 6: selection requires exactly the three nonignored tests'

cat > "$fixture/policy.sh" <<'SCRIPT'
set -euo pipefail
diagnostic_root="$RUNNER_TEMP/archived-test-diagnostic"
SCRIPT
if command -v shasum >/dev/null 2>&1; then
  printf 'sha256=(shasum -a 256)\n' >> "$fixture/policy.sh"
else
  printf 'sha256=(sha256sum)\n' >> "$fixture/policy.sh"
fi
awk '
  index($0, "> \"$diagnostic_root/original-nextest.sha256\"") { copying = 1; next }
  copying && /^selection_args=/ { exit }
  copying { print; lines++ }
  END { if (lines == 0) exit 1 }
' "$fixture/verify.sh" >> "$fixture/policy.sh"
printf '%s\n' '[profile.default]' \
  'slow-timeout = { period = "60s", terminate-after = 10 }' \
  > "$RUNNER_TEMP/archived-test-diagnostic/original-nextest.toml"
expect_rejected 'changed600s deadline policy' bash "$fixture/policy.sh"
grep -Fq FAILED "$fixture/stdout"
echo 'PASS 7: modified deadline policy is refused'

expect_rejected 'missing pinned cache' env TOOLING_RUNTIME_CACHE_HIT=false bash "$fixture/runtime.sh"
grep -Fq 'Pinned release tooling cache unavailable' "$fixture/stderr"
echo 'PASS 8: cache miss stops before source preparation'

expect_rejected 'incomplete cached runtime' env TOOLING_RUNTIME_CACHE_HIT=true bash "$fixture/runtime.sh"
grep -Fq 'runtime is incomplete' "$fixture/stderr"
grep -Fq 'refusing to build' "$fixture/stderr"
echo 'PASS 9: incomplete cached runtime refuses rebuilding'

# The same driver functions run in hosted CI; controlled command executables stand
# only at its external process boundary. They do not claim native test coverage.
if extract_step 'Validate current release contention mode' > "$fixture/contention-mode.sh"; then
  :
else
  echo 'FAIL: current release contention mode boundary is missing' >&2
  exit 1
fi
for shard in 1 2; do
  expect_ok "current contention shard $shard" env CONTENTION_CONTROLS=true ARCHIVE_SHARD="$shard" TEST_FILTER='all()' bash "$fixture/contention-mode.sh"
  expect_ok "authenticated contention shard $shard" env CONTENTION_CONTROLS=true ARCHIVE_SHARD="$shard" bash "$fixture/auth.sh"
done
for override in ARCHIVE_SHARD=4 SOURCE_RUN=40038389605 ORIGINAL_SOURCE_POLICY=true RUNNER_ARCH=ARM64 PRESERVE_ARCHIVE_POLICY=false TEST_FILTER=unrelated; do
  expect_rejected "contention $override" env CONTENTION_CONTROLS=true ARCHIVE_SHARD=1 TEST_FILTER='all()' "$override" bash "$fixture/contention-mode.sh"
done
expect_rejected 'foreign source cannot request current controls' env CONTENTION_CONTROLS=true SOURCE_RUN=40038389605 ARCHIVE_SHARD=1 bash "$fixture/auth.sh"
for mutation in '.["40093103558"].head_sha = ("0" * 40)' '.["12563197007"].digest = ("sha256:" + ("0" * 64))'; do
  jq "$mutation" "$fixture/valid.json" > "$FIXTURE_METADATA"
  expect_rejected "contention $mutation" env CONTENTION_CONTROLS=true ARCHIVE_SHARD=1 bash "$fixture/auth.sh"
done
cp "$fixture/valid.json" "$FIXTURE_METADATA"
echo 'PASS 10: only explicit current Intel shard1/2 contention mode authenticates'

source "$script_dir/diagnose-intel-contention.sh"
required='[["codex-core","original_timeout"]]'
for count in 1 2 5498 5457; do
  jq -n --argjson count "$count" '
    {"rust-suites":{"core":{"package-name":"codex-core",testcases:
      ([range($count) | {key:(if . == 0 then "original_timeout" else "filler_\(.)" end),
        value:{"filter-match":{status:"matches"},ignored:false}}] | from_entries)}}}
  ' > "$fixture/selection.json"
  expect_ok "full shard count $count" verify_selection "$fixture/selection.json" "$count" "$required"
  expect_rejected 'wrong count' verify_selection "$fixture/selection.json" "$((count-1))" "$required"
  expect_rejected 'wrong membership' verify_selection "$fixture/selection.json" "$count" '[["codex-feedback","missing"]]'
  jq '."rust-suites".core.testcases.original_timeout.ignored=true' "$fixture/selection.json" > "$fixture/ignored.json"
  expect_rejected 'ignored timeout test' verify_selection "$fixture/ignored.json" "$count" "$required"
done
echo 'PASS 11: current full-shard count and nonignored membership are required'

mkdir "$fixture/phase-bin"
real_tee=$(command -v tee)
export PHASE_FIXTURE="$fixture" REAL_TEE="$real_tee"
cat > "$fixture/phase-bin/cargo" <<'CARGO'
#!/usr/bin/env bash
set -euo pipefail
index=$(cat "$PHASE_FIXTURE/phase-index")
index=$((index+1))
printf '%s\n' "$index" > "$PHASE_FIXTURE/phase-index"
printf '%s\n' "$*" >> "$PHASE_FIXTURE/phase-args"
printf 'controlled external nextest phase %s\n' "$index"
sleep 0.05
IFS=, read -r -a statuses <<< "$PHASE_STATUSES"
exit "${statuses[index-1]}"
CARGO
cat > "$fixture/phase-bin/tee" <<'TEE'
#!/usr/bin/env bash
set -euo pipefail
"$REAL_TEE" "$@"
if [[ "${TEE_FAIL_INDEX:-0}" == "$(cat "$PHASE_FIXTURE/phase-index")" ]]; then exit 24; fi
TEE
# Fixture telemetry includes an absolute executable path but no arguments.
cat > "$fixture/phase-bin/ps" <<'PS'
#!/usr/bin/env bash
printf '123 25.0 1024 /private/example/fixture-process\n'
PS
for name in sysctl top vm_stat memory_pressure; do
  printf '#!/usr/bin/env bash\nprintf "controlled telemetry\\n"\n' > "$fixture/phase-bin/$name"
done
chmod +x "$fixture/phase-bin/"*
export PATH="$fixture/phase-bin:$PATH"
for scenario in '0,0,0:0' '0,100,0:100' '7,100,9:100' '7,0,0:7' '0,0,9:9'; do
  statuses=${scenario%:*}
  expected=${scenario##*:}
  printf '0\n' > "$fixture/phase-index"
  : > "$fixture/phase-args"
  export PHASE_STATUSES="$statuses" TEE_FAIL_INDEX=0
  result=0
  run_phases "$fixture" "$fixture/workspace" 2 exact-filter > "$fixture/phase-output" 2>&1 || result=$?
  [[ "$result" == "$expected" ]]
  [[ "$(cat "$fixture/phase-index")" == 3 ]]
  [[ "$(wc -l < "$fixture/phase-args" | tr -d ' ')" == 3 ]]
  grep -F -- '--test-threads 1 -E exact-filter --retries 0' "$fixture/phase-args" > /dev/null
  full_args=$(sed -n '2p' "$fixture/phase-args")
  [[ "$full_args" == *'--partition hash:2/4 --retries 0'* ]]
  [[ "$full_args" != *' -E '* && "$full_args" != *'--test-threads'* ]]
  if kill -0 "$(cat "$fixture/sampler.pid")" 2>/dev/null; then
    echo 'Sampler survives phase completion' >&2; exit 1
  fi
done
echo 'PASS 12: all phases execute, full-shard status wins, serial failures propagate, sampler is reaped'

printf '0\n' > "$fixture/phase-index"
export PHASE_STATUSES=0,0,0 TEE_FAIL_INDEX=2
result=0
run_phases "$fixture" "$fixture/workspace" 1 exact-filter > "$fixture/phase-output" 2>&1 || result=$?
[[ "$result" == 24 && "$(cat "$fixture/phase-index")" == 3 ]]
grep -Fxq 'nextest=0' "$fixture/original-workload.status"
grep -Fxq 'tee=24' "$fixture/original-workload.status"
echo 'PASS 13: tee failure propagates without losing the after-control'

sample_resources > "$fixture/sampler-output" 2>&1 &
sampler_pid=$!
# Allow its first sample to finish and enter the cancellable sleep.
sleep 0.1
sleeper_pid=$(pgrep -P "$sampler_pid" sleep)
kill "$sampler_pid"
wait "$sampler_pid"
if kill -0 "$sleeper_pid" 2>/dev/null; then
  echo 'Sampler sleep child survives cancellation' >&2; exit 1
fi
grep -Fxq '123 25.0 1024 fixture-process' "$fixture/sampler-output"
if grep -F '/private/example' "$fixture/sampler-output"; then exit 1; fi
echo 'PASS 14: sampler cancellation reaps its sleep child and logs only command basename'
