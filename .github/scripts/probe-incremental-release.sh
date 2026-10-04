#!/usr/bin/env bash
set -euo pipefail

# The hosted job owns this evidence directory until artifact upload completes.
evidence="$RUNNER_TEMP/incremental-evidence"
mkdir -p "$evidence"
archive_tar=tar
if command -v gtar >/dev/null 2>&1; then archive_tar=gtar; fi

measure_build() {
  phase="$1"
  started="$(date +%s)"
  ./scripts/build-release.sh upstream "$RUNNER_TEMP/probe-output" 2>&1 | tee "$evidence/$phase.log"
  completed="$(date +%s)"
  printf '%s %s %s\n' "$phase" "$started" "$completed" >> "$evidence/elapsed.tsv"
  cp "$CARGO_TARGET_DIR/cargo-timings/cargo-timing.html" "$evidence/$phase.html"
  python3 .github/scripts/clean-cached-release-binaries.py --record-source-inputs upstream/codex-rs "$UPSTREAM_TARGET"
  "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/codex" --version | tee "$evidence/$phase-version.txt"
  du -sk "$CARGO_TARGET_DIR/$UPSTREAM_TARGET/release/incremental" >> "$evidence/incremental-size.tsv" 2>/dev/null || true
}

if [[ "$CARGO_INCREMENTAL" == 1 ]]; then
  measure_build seed
  incremental_relative="$UPSTREAM_TARGET/release/incremental"
  test -d "$CARGO_TARGET_DIR/$incremental_relative"
  test -n "$(find "$CARGO_TARGET_DIR/$incremental_relative" -type f -print -quit)"
  archive="$RUNNER_TEMP/probe-incremental.tar.zst"
  trap 'rm -f "$archive"' EXIT
  started="$(date +%s)"
  "$archive_tar" --zstd -cf "$archive" -C "$CARGO_TARGET_DIR" "$incremental_relative"
  du -k "$archive" > "$evidence/incremental-archive-size.tsv"
  rm -rf "${CARGO_TARGET_DIR:?}/${incremental_relative:?}"
  "$archive_tar" --zstd -xf "$archive" -C "$CARGO_TARGET_DIR"
  printf 'archive_restore %s %s\n' "$started" "$(date +%s)" >> "$evidence/elapsed.tsv"
fi

export CODEX_UNLEASHED_BUILD_NUMBER=40
read -r -a binary_names <<< "$UPSTREAM_BINARIES"
python3 .github/scripts/clean-cached-release-binaries.py upstream/codex-rs "$UPSTREAM_TARGET" "${binary_names[@]}"
measure_build warm
rg -q 'codex-cli 0.160.0\+40' "$evidence/warm-version.txt"
python3 - "$evidence" "$CARGO_INCREMENTAL" "$UPSTREAM_TARGET" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
rows=[{'phase':p,'seconds':int(end)-int(start)} for p,start,end in (line.split() for line in (root/'elapsed.tsv').read_text().splitlines())]
(root/'result.json').write_text(json.dumps({'target':sys.argv[3],'incremental':sys.argv[2],'timings':rows,'runtime_version':'0.160.0+40','result':'passed'},indent=2)+'\n')
PY
