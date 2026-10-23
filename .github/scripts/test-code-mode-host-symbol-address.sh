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
      nm-path) diagnostic_command=(command -v nm) ;;
      nm-version) diagnostic_command=(nm --version) ;;
      rustc-version) diagnostic_command=(rustc --version) ;;
      raw-symbols) diagnostic_command=(nm --defined-only "$fixture/entrypoint") ;;
      demangled-symbols) diagnostic_command=(nm -C --defined-only "$fixture/entrypoint") ;;
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
[[ "$resolved" == codex_code_mode_host::main*$'\n'*entrypoint.rs:* ]]

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
