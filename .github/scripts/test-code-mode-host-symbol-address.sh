#!/usr/bin/env bash
# Exercises symbol selection against real binaries, including local entrypoints.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fixture="$(mktemp -d "${1:?compiled fixture parent directory required}/symbol-address-XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
cat > "$fixture/entrypoint.rs" <<'RS'
fn main() { println!("entrypoint"); }
RS
rustc -g -D warnings -C opt-level=0 -C symbol-mangling-version=v0 \
  --crate-name codex_code_mode_host "$fixture/entrypoint.rs" -o "$fixture/entrypoint"
if address="$(bash "$script_dir/code-mode-host-symbol-address.sh" x86_64-unknown-linux-gnu "$fixture/entrypoint")"; then
  :
else
  selector_status=$?
  printf 'First fixture selector failed with status %s; determining tool and symbol evidence follows\n' "$selector_status" >&2
  for diagnostic in nm-path nm-version rustc-version raw-symbols demangled-symbols; do
    printf 'Symbol fixture diagnostic: %s\n' "$diagnostic" >&2
    case "$diagnostic" in
      nm-path) diagnostic_command=(command -v "${NM:-nm}") ;;
      nm-version) diagnostic_command=("${NM:-nm}" --version) ;;
      rustc-version) diagnostic_command=(rustc --version) ;;
      raw-symbols) diagnostic_command=("${NM:-nm}" --defined-only "$fixture/entrypoint") ;;
      demangled-symbols) diagnostic_command=("${NM:-nm}" -C --defined-only "$fixture/entrypoint") ;;
    esac
    if [[ "$diagnostic" == *-symbols ]]; then
      if "${diagnostic_command[@]}" | awk '/main|codex_code_mode_host/ { print }' >&2; then
        :
      else
        diagnostic_status=$?
        printf 'Symbol fixture diagnostic %s failed with status %s\n' "$diagnostic" "$diagnostic_status" >&2
      fi
    elif "${diagnostic_command[@]}" >&2; then
      :
    else
      diagnostic_status=$?
      printf 'Symbol fixture diagnostic %s failed with status %s\n' "$diagnostic" "$diagnostic_status" >&2
    fi
  done
  exit "$selector_status"
fi
resolved="$(addr2line -C -f -e "$fixture/entrypoint" "$address")"
source_location="${resolved#*$'\n'}"
[[ "$source_location" == *entrypoint.rs:* ]]

cat > "$fixture/instantiation.rs" <<'RS'
mod codex_code_mode_host { pub struct Marker; }
#[inline(never)]
fn dependency<T>() { println!("{}", std::any::type_name::<T>()); }
fn main() { dependency::<codex_code_mode_host::Marker>(); }
RS
rustc -g -D warnings -C opt-level=0 -C symbol-mangling-version=v0 \
  --crate-name dependency "$fixture/instantiation.rs" -o "$fixture/instantiation"
if bash "$script_dir/code-mode-host-symbol-address.sh" x86_64-unknown-linux-gnu \
  "$fixture/instantiation" > "$fixture/output" 2> "$fixture/error"; then
  echo 'A generic instantiation mentioning the host crate is not its entrypoint' >&2
  exit 1
fi
[[ ! -s "$fixture/output" ]]

# Exercise the Cargo staging shell itself with real local symbol bytes. These
# ELF fixtures verify member selection and the exact-main guard, not Mach-O DWARF.
printf 'set -euo pipefail\n' > "$fixture/staging-symbols.sh"
awk '
  index($0, "if [[ \"$binary\" == codex-code-mode-host ]]") { copying = 1 }
  copying && index($0, "if [[ \"${TARGET}\" == *linux-musl ]]") { exit }
  copying { sub(/^            /, ""); print }
' "$script_dir/../workflows/build-release.yml" >> "$fixture/staging-symbols.sh"
release_dir="$fixture/release with spaces"
binary=codex-code-mode-host
binary_path="$fixture/entrypoint"
TARGET=aarch64-apple-darwin
export release_dir binary binary_path TARGET
dwarf_dir="$release_dir/$binary.dSYM/Contents/Resources/DWARF"
mkdir -p "$dwarf_dir"
for member in codex_code_mode_host-a1b2c3 codex-code-mode-host; do
  cp "$fixture/entrypoint" "$dwarf_dir/$member"
  (cd "$script_dir/../.." && bash "$fixture/staging-symbols.sh") > "$fixture/staging-output"
  grep -Fxq "Selected Cargo code-mode host DWARF member: $dwarf_dir/$member" "$fixture/staging-output"
  rm "$dwarf_dir/$member"
done
for invalid in missing empty multiple directory unrelated; do
  rm -rf -- "$dwarf_dir"
  mkdir -p "$dwarf_dir"
  case "$invalid" in
    missing) rmdir "$dwarf_dir" ;;
    empty) : > "$dwarf_dir/empty" ;;
    multiple) cp "$fixture/entrypoint" "$dwarf_dir/one"; cp "$fixture/entrypoint" "$dwarf_dir/two" ;;
    directory) mkdir "$dwarf_dir/subdir" ;;
    unrelated) cp "$fixture/instantiation" "$dwarf_dir/codex_code_mode_host-a1b2c3" ;;
  esac
  if (cd "$script_dir/../.." && bash "$fixture/staging-symbols.sh") > "$fixture/staging-output" 2> "$fixture/staging-error"; then
    printf 'Cargo staging must reject %s DWARF input\n' "$invalid" >&2
    exit 1
  fi
  if [[ "$invalid" == unrelated ]]; then
    grep -Fq 'Expected exactly one codex_code_mode_host::main symbol, found 0' "$fixture/staging-error"
  else
    grep -Fq 'Expected exactly one nonempty DWARF file in ' "$fixture/staging-error"
  fi
done
