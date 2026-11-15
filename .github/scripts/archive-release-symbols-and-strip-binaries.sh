#!/usr/bin/env bash
set -euo pipefail

# The dSYM bundle layout that contains the staged DWARF symbol file.
readonly DSYM_DWARF_DIRECTORY="Contents/Resources/DWARF"

# Prints this script's accepted arguments and their required values.
#
# Output: Writes the usage instructions to standard output.
usage() {
  cat <<'EOF'
Usage: archive-release-symbols-and-strip-binaries.sh \
  --target <rust-target> \
  --artifact-name <artifact-name> \
  --release-dir <dir> \
  --archive-dir <dir> \
  --binaries "<space-delimited binary basenames>"
EOF
}

# Requires the code-mode host executable to define exactly one demangled entrypoint symbol.
#
# Arguments:
#   nm_bin: Symbol inspection executable selected by the caller.
#   target: Rust target used to select supported nm options.
#   binary_path: Code-mode host executable to inspect.
#
# Side effects: Runs nm and awk.
# Errors: Reports nm failures and a missing or ambiguous entrypoint symbol to standard error.
# Exit status: Returns nm's failure status, 1 when the symbol is absent or ambiguous, or 0 when exactly one is defined.
validate_code_mode_host_symbols() {
  local nm_bin="$1"
  local target="$2"
  local binary_path="$3"
  local -a nm_arguments=(-C)
  local symbol_output
  local nm_status
  local matching_symbol_count

  if [[ "$target" == *linux* ]]; then
    nm_arguments+=(--defined-only)
  fi

  if symbol_output=$("$nm_bin" "${nm_arguments[@]}" "$binary_path"); then
    :
  else
    nm_status=$?
    echo "Unable to inspect code-mode host symbols in $binary_path with $nm_bin" >&2
    return "$nm_status"
  fi

  matching_symbol_count=$(printf '%s\n' "$symbol_output" | awk '
    /^[[:space:]]*([[:xdigit:]]+[[:space:]]+)?[tT][[:space:]]/ {
      symbol = $0
      sub(/^[[:space:]]*([[:xdigit:]]+[[:space:]]+)?[tT][[:space:]]+/, "", symbol)
      if (symbol == "codex_code_mode_host::main") {
        count++
      }
    }
    END { print count + 0 }
  ')

  if [[ "$matching_symbol_count" == "1" ]]; then
    return 0
  fi

  echo "Expected exactly one defined codex_code_mode_host::main symbol in $binary_path; found $matching_symbol_count" >&2
  return 1
}

# Requires a dSYM DWARF directory to contain one regular, nonempty symbol file.
#
# Arguments:
#   dwarf_dir: dSYM DWARF directory to validate.
#
# Errors: Reports a missing, ambiguous, empty, or non-regular DWARF member to standard error.
# Exit status: Returns 0 when one regular nonempty member exists, or 1 otherwise.
validate_single_dwarf_member() {
  local dwarf_dir="$1"
  local member
  local -a dwarf_members=()

  for member in "$dwarf_dir"/* "$dwarf_dir"/.[!.]* "$dwarf_dir"/..?*; do
    if [[ -e "$member" ]]; then
      dwarf_members+=("$member")
    elif [[ -L "$member" ]]; then
      dwarf_members+=("$member")
    fi
  done

  if (( ${#dwarf_members[@]} != 1 )); then
    echo "Expected exactly one DWARF member in $dwarf_dir; found ${#dwarf_members[@]}" >&2
    return 1
  fi

  member="${dwarf_members[0]}"
  if [[ -L "$member" ]]; then
    echo "DWARF member is not a regular file: $member" >&2
    return 1
  fi
  if [[ -f "$member" ]]; then
    :
  else
    echo "DWARF member is not a regular file: $member" >&2
    return 1
  fi
  if [[ -s "$member" ]]; then
    return 0
  fi

  echo "DWARF member is empty: $member" >&2
  return 1
}

target=""
artifact_name=""
release_dir=""
archive_dir=""
binaries=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      target="${2:?--target requires a value}"
      shift 2
      ;;
    --artifact-name)
      artifact_name="${2:?--artifact-name requires a value}"
      shift 2
      ;;
    --release-dir)
      release_dir="${2:?--release-dir requires a value}"
      shift 2
      ;;
    --archive-dir)
      archive_dir="${2:?--archive-dir requires a value}"
      shift 2
      ;;
    --binaries)
      binaries="${2:?--binaries requires a value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unexpected argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$target" || -z "$artifact_name" || -z "$release_dir" || -z "$archive_dir" || -z "$binaries" ]]; then
  usage >&2
  exit 1
fi

symbols_root="${RUNNER_TEMP:-/tmp}/codex-symbols-${artifact_name}"
symbols_dir="${symbols_root}/codex-symbols-${artifact_name}"
archive_path="${archive_dir%/}/codex-symbols-${artifact_name}.tar.gz"
rm -rf "$symbols_root"
mkdir -p "$symbols_dir" "$archive_dir"
read -r -a binary_names <<< "$binaries"

case "$target" in
  *apple-darwin)
    for binary in "${binary_names[@]}"; do
      binary_path="${release_dir%/}/${binary}"
      dsym_path="${binary_path}.dSYM"
      if [[ ! -f "$binary_path" ]]; then
        echo "Binary $binary_path not found" >&2
        exit 1
      fi
      if [[ ! -d "$dsym_path" ]]; then
        echo "dSYM $dsym_path not found" >&2
        exit 1
      fi

      if [[ "$binary" == "codex-code-mode-host" ]]; then
        validate_code_mode_host_symbols "${NM:-nm}" "$target" "$binary_path"
        validate_single_dwarf_member "${dsym_path}/${DSYM_DWARF_DIRECTORY}"
      fi

      cp -RL "$dsym_path" "${symbols_dir}/${binary}.dSYM"
      strip -S -x "$binary_path"
    done
    ;;
  *linux*)
    objcopy_bin="${OBJCOPY:-objcopy}"
    strip_bin="${STRIP:-strip}"
    for binary in "${binary_names[@]}"; do
      binary_path="${release_dir%/}/${binary}"
      debug_path="${symbols_dir}/${binary}.debug"
      if [[ ! -f "$binary_path" ]]; then
        echo "Binary $binary_path not found" >&2
        exit 1
      fi

      if [[ "$binary" == "codex-code-mode-host" ]]; then
        validate_code_mode_host_symbols "${NM:-nm}" "$target" "$binary_path"
      fi

      "$objcopy_bin" --only-keep-debug "$binary_path" "$debug_path"
      "$strip_bin" --strip-debug --strip-unneeded "$binary_path"
      "$objcopy_bin" --add-gnu-debuglink="$debug_path" "$binary_path"
    done
    ;;
  *windows*)
    for binary in "${binary_names[@]}"; do
      pdb_path="${release_dir%/}/${binary}.pdb"
      if [[ ! -f "$pdb_path" ]]; then
        echo "PDB $pdb_path not found" >&2
        exit 1
      fi

      cp "$pdb_path" "${symbols_dir}/${binary}.pdb"
    done
    ;;
  *)
    echo "No symbols packaging support for target: $target" >&2
    exit 1
    ;;
esac

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PYTHONPATH="${repo_root}/scripts${PYTHONPATH:+:${PYTHONPATH}}" \
CODEX_REPO_ROOT="$repo_root" \
python3 - "$symbols_root" "$archive_path" <<'PY'
import sys
from pathlib import Path

from codex_package.archive import write_archive


write_archive(Path(sys.argv[1]), Path(sys.argv[2]), force=True)
PY
