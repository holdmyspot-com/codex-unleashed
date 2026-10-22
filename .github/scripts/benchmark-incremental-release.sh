#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?Hosted evidence storage is required}"
: "${CARGO_TARGET_DIR:?An isolated restored Cargo target directory is required}"
: "${UPSTREAM_TARGET:?A release target is required}"
: "${CARGO_INCREMENTAL:?Select incremental mode 0 or 1}"
: "${CODEX_UNLEASHED_BUILD_NUMBER:?A numeric release build number is required}"
if [[ "$UPSTREAM_TARGET" != x86_64-unknown-linux-musl ]]; then
  echo 'This benchmark requires the native Linux x64 musl release target.' >&2
  exit 2
fi
case "$CARGO_INCREMENTAL" in 0|1) ;; *) echo 'CARGO_INCREMENTAL must be 0 or 1.' >&2; exit 2 ;; esac
case "$CODEX_UNLEASHED_BUILD_NUMBER" in ''|*[!0-9]*) echo 'The build number must be numeric.' >&2; exit 2 ;; esac

evidence="$RUNNER_TEMP/incremental-release-evidence"
mkdir -p "$evidence"

# Measures one maintained release build and retains its output, timings, and executable version.
# Arguments:
#   phase: unique evidence filename prefix.
# Environment:
#   evidence: this job's evidence directory.
#   RUNNER_TEMP: hosted temporary output root.
#   CARGO_TARGET_DIR: this job's isolated compiler output root.
#   UPSTREAM_TARGET: native Linux musl target.
#   All release and scheduler environment settings are inherited unchanged.
# Side effects:
#   Runs the release build and writes phase evidence.
# Errors:
#   Prints failed build diagnostics and propagates its status; missing outputs fail the benchmark.
measure() {
  local phase="$1" started completed status=0
  started="$(date +%s)"
  bash .github/scripts/run-memory-scheduled-build.sh ./scripts/build-release.sh upstream \
    "$RUNNER_TEMP/incremental-release-output" > "$evidence/$phase.log" 2>&1 || status=$?
  completed="$(date +%s)"
  printf '%s\t%s\t%s\n' "$phase" "$((completed - started))" "$status" >> "$evidence/elapsed.tsv"
  if [[ "$status" != 0 ]]; then
    cat "$evidence/$phase.log"
    return "$status"
  fi
  cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$evidence/$phase.html"
  "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --version > "$evidence/$phase-version.txt"
  printf '%s: %s seconds\n' "$phase" "$((completed - started))"
}

measure seed
"$CODEX_UNLEASHED_TOOLING" clean-cached-release-binaries --record-source-inputs upstream/codex-rs "$UPSTREAM_TARGET" > "$evidence/record-source-inputs.log" 2>&1
if [[ "$CARGO_INCREMENTAL" == 1 ]]; then
  incremental="$UPSTREAM_TARGET/release/incremental"
  test -n "$(find "$CARGO_TARGET_DIR/$incremental" -type f -print -quit)"
  archive="$RUNNER_TEMP/incremental-release-state.tar.zst"
  trap 'rm -f "$archive"' EXIT
  started="$(date +%s)"
  tar --zstd -cf "$archive" -C "$CARGO_TARGET_DIR" "$incremental"
  du -k "$archive" > "$evidence/incremental-archive-size.tsv"
  du -sk "$CARGO_TARGET_DIR/$incremental" > "$evidence/incremental-size.tsv"
  rm -rf -- "${CARGO_TARGET_DIR:?}/${incremental:?}"
  tar --zstd -xf "$archive" -C "$CARGO_TARGET_DIR"
  printf 'archive-restore\t%s\t0\n' "$(( $(date +%s) - started ))" >> "$evidence/elapsed.tsv"
  rm -f "$archive"
fi
measure unchanged

read -r -a binaries <<< "$UPSTREAM_BINARIES"
"$CODEX_UNLEASHED_TOOLING" clean-cached-release-binaries upstream/codex-rs "$UPSTREAM_TARGET" \
  "${binaries[@]}" > "$evidence/forced-invalidation.log" 2>&1
measure forced-invalidation

export CODEX_UNLEASHED_BUILD_NUMBER="$((10#$CODEX_UNLEASHED_BUILD_NUMBER + 1))"
measure build-number-change
grep -F -- "+$CODEX_UNLEASHED_BUILD_NUMBER" "$evidence/build-number-change-version.txt"
touch upstream/codex-rs/cli/src/main.rs
measure unchanged-source-bytes

node - "$evidence" "$CARGO_INCREMENTAL" <<'JS'
const fs = require('fs');
const path = require('path');
const root = process.argv[2];
const rows = fs.readFileSync(path.join(root, 'elapsed.tsv'), 'utf8').trim().split('\n').map(line => {
  const [phase, seconds, status] = line.split('\t');
  return {phase, seconds: Number(seconds), status: Number(status)};
});
for (const row of rows) {
  if (row.phase === 'archive-restore') continue;
  const html = fs.readFileSync(path.join(root, row.phase + '.html'), 'utf8');
  const text = html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
  row.fresh = Number(text.match(/Fresh units: (\d+)/)[1]);
  row.dirty = Number(text.match(/Dirty units: (\d+)/)[1]);
  row.version = fs.readFileSync(path.join(root, row.phase + '-version.txt'), 'utf8').trim();
}
const unchanged = rows.find(row => row.phase === 'unchanged');
if (unchanged.dirty !== 0) throw Error('Identical inputs rebuilt units; inspect retained Cargo diagnostics before interpreting incremental results');
fs.writeFileSync(path.join(root, 'result.json'), JSON.stringify({incremental: process.argv[3], rows}, null, 2) + '\n');
JS
