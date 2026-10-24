#!/usr/bin/env bash
set -euo pipefail

mode="${1:?Expected capture or identity}"
shift
case "$mode" in
  capture)
    gate="${1:?Expected gate}"
    target_log_path="${2:?Expected target log path}"
    evidence="${3:?Expected evidence directory}"
    case "$gate" in helpers|elevated|legacy) ;; *) printf 'Unknown diagnostic gate: %s\n' "$gate" >&2; exit 1 ;; esac
    capture="$evidence/$gate-direct-output"
    mkdir -p "$capture"
    target_logs="bazel-testlogs/$target_log_path"
    if [[ -d "$target_logs/test.outputs" ]]; then
      cp -R -L -- "$target_logs/test.outputs" "$capture/undeclared"
    else
      printf 'Test undeclared-output directory unavailable: %s\n' "$target_logs/test.outputs" > "$capture/unavailable.txt"
    fi
    for suffix in bat exe; do
      launcher="bazel-bin/$target_log_path.$suffix"
      if [[ -f "$launcher" ]]; then
        sha256sum "$launcher" >> "$capture/launcher.sha256"
        if [[ "$suffix" == bat ]]; then
          cp -- "$launcher" "$capture/generated-launcher.bat"
        fi
      else
        printf 'Generated test artifact unavailable: %s\n' "$launcher" >> "$capture/unavailable.txt"
      fi
    done
    python3 - "$capture" "$evidence/$gate-raw/test.log" "$gate" <<'PY'
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

capture, raw, gate = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
archive = capture / "undeclared" / "outputs.zip"
for name in ("libtest-direct.log", "libtest-direct.status"):
    source = capture / "undeclared" / name
    destination = capture / name
    if source.is_file():
        shutil.copyfile(source, destination)
    elif archive.is_file():
        with zipfile.ZipFile(archive) as outputs:
            try:
                member = outputs.getinfo(name)
            except KeyError:
                continue
            with outputs.open(member) as incoming, destination.open("wb") as outgoing:
                shutil.copyfileobj(incoming, outgoing, 65536)


def inspect(path):
    if not path.is_file():
        return {"available": False}
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    summaries = []
    marker = False
    with path.open("r", encoding="utf-8", errors="replace") as source:
        for line in source:
            if line.startswith("test result:"):
                summaries.append(line.rstrip())
            if line.startswith("WINDOWS_DIRECT_LIBTEST_COPY_RETURNED exit="):
                marker = True
    return {"available": True, "sha256": digest.hexdigest(), "summaries": summaries,
            "copy_returned_marker": marker}


direct = inspect(capture / "libtest-direct.log")
raw_result = inspect(raw)
status_path = capture / "libtest-direct.status"
status = None
if status_path.is_file():
    with status_path.open("r", encoding="ascii") as source:
        status = source.read(32).strip()
comparison = "direct_output_unavailable"
if direct["available"]:
    comparison = "raw_output_unavailable"
    if raw_result["available"]:
        comparison = "summaries_equal" if direct["summaries"] == raw_result["summaries"] else "summaries_differ"
report = {"format_version": 1, "gate": gate, "child_status": status,
          "direct": direct, "raw": raw_result, "comparison": comparison,
          "release_acceptance": "unchanged_strict_validator_required"}
with (capture / "comparison.json").open("w", encoding="utf-8") as result:
    json.dump(report, result, indent=2)
    result.write("\n")
PY
    ;;
  identity)
    evidence="${1:?Expected evidence directory}"
    identity="$evidence/bazel-test-wrapper-identity"
    mkdir -p "$identity"
    startup_args=()
    if [[ -n "${BAZEL_OUTPUT_USER_ROOT:-}" ]]; then
      startup_args+=("--output_user_root=${BAZEL_OUTPUT_USER_ROOT}")
    fi
    wrapper="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}/.github/scripts/run-bazel-with-buildbuddy.sh"
    for key in release install_base; do
      status=0
      MSYS2_ARG_CONV_EXCL='*' "$wrapper" "${startup_args[@]}" info "$key" \
        > "$identity/$key.txt" 2> "$identity/$key.stderr" || status=$?
      printf '%s\n' "$status" > "$identity/$key.status"
      if [[ "$status" != 0 ]]; then
        printf 'Bazel info %s unavailable; exit status %s.\n' "$key" "$status" >> "$identity/unavailable.txt"
      fi
    done
    if [[ "$(cat "$identity/install_base.status")" == 0 ]]; then
      install_base="$(tr -d '\r' < "$identity/install_base.txt")"
      if command -v cygpath > /dev/null; then
        install_base="$(cygpath -u "$install_base")"
      fi
      if [[ -d "$install_base" ]]; then
        found=false
        while IFS= read -r -d '' binary; do
          found=true
          sha256sum "$binary" >> "$identity/wrapper.sha256"
        done < <(find "$install_base" -maxdepth 8 -type f \( -iname tw.exe -o -iname test-setup.exe \) -print0)
        if [[ "$found" == false ]]; then
          printf 'No Windows test wrapper binary discovered beneath install base: %s\n' "$install_base" >> "$identity/unavailable.txt"
        fi
      else
        printf 'Bazel install base is not a readable directory: %s\n' "$install_base" >> "$identity/unavailable.txt"
      fi
    fi
    ;;
  *) printf 'Unknown diagnostic operation: %s\n' "$mode" >&2; exit 1 ;;
esac
