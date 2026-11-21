#!/usr/bin/env bash
# Exercise the actual Cargo linker without touching the package target directory.
set -euo pipefail
export LC_ALL=C
test "${RUNNER_OS:-}:${RUNNER_ARCH:-}:${TARGET:-}" = Windows:ARM64:aarch64-pc-windows-msvc

# Use a space in the response-file path to exercise the linker's normal quoting.
scratch="$(mktemp -d "$(cygpath -au "${RUNNER_TEMP:?}")/arm64 link preflight.XXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT
cd "$scratch"
mkdir src
cat > Cargo.toml <<'TOML'
[package]
name = "codex-arm64-link-preflight"
version = "0.0.0"
edition = "2021"
TOML
printf 'fn main() { println!("codex-arm64-link-ok"); }\n' > src/main.rs
printf '/arm64hazardfree\n' > link.rsp
# Supply an explicit response file so the LLD wrapper's response-file path is
# exercised even when rustc would link this small executable without one.
CARGO_TARGET_DIR="$(cygpath -am "$scratch/output")"
export CARGO_TARGET_DIR
export MSYS2_ARG_CONV_EXCL='*'
cargo rustc --offline --release --target "$TARGET" --bin codex-arm64-link-preflight -- \
  -C debuginfo=2 -C "link-arg=@$(cygpath -am "$scratch/link.rsp")" >&2

output="$scratch/output/$TARGET/release"
executable="$output/codex-arm64-link-preflight.exe"
symbols="$output/codex-arm64-link-preflight.pdb"
if [[ ! -f "$symbols" ]]; then
  symbols="$output/codex_arm64_link_preflight.pdb"
fi
# Windows PE starts with MZ and stores the PE header offset at byte 60.
# Require the PE signature and ARM64 machine number (0xAA64).
read -r dos_signature < <(od -An -tx2 -N2 "$executable")
test "$dos_signature" = 5a4d
read -r pe_offset < <(od -An -tu4 -j60 -N4 "$executable")
read -r signature < <(od -An -tx4 -N4 -j"$pe_offset" "$executable")
test "$signature" = 00004550
test "$(od -An -tx2 -j"$((pe_offset + 4))" -N2 "$executable" | tr -d ' ')" = aa64
test "$(head -c 24 "$symbols")" = 'Microsoft C/C++ MSF 7.00'
test "$("$executable" | tr -d '\r')" = codex-arm64-link-ok
