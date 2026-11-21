#!/usr/bin/env bash
# Exercise the Git Bash probe with isolated MSVC tool and SDK fixtures.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"
mkdir -p ".github/actions/setup-msvc-env" bin
printf 'fixture native setup\n' > .github/actions/setup-msvc-env/setup-msvc-env.ps1
cat > bin/cygpath <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -au || "$1" == -am ]]
test "$#" = 2
printf '%s\n' "$2"
SHIM
chmod +x bin/cygpath
export PATH="$fixture/bin:$PATH"

export RUNNER_OS=Windows RUNNER_ARCH=X64 TARGET=x86_64-pc-windows-msvc
export VCToolsInstallDir="$fixture/MSVC Tools/14.44"
export WindowsSdkDir="$fixture/Windows Kits/10"
export UniversalCRTSdkDir="$WindowsSdkDir"
export WindowsSDKVersion="10.0.26100.0\\" WindowsSDKLibVersion="10.0.26100.0\\" UCRTVersion=10.0.26100.0
export WindowsSdkBinPath="$WindowsSdkDir/bin"
export INCLUDE='first;second' LIB='native;SDK' LIBPATH='native;metadata'
unset MSVC_REAL_LINKER MSVC_LINKER_WRAPPER_COMPILER WindowsLibPath

for arch in x64 arm64; do
  tools="$VCToolsInstallDir/bin/Host$arch/$arch"
  sdk="$WindowsSdkDir/Lib/$UCRTVersion"
  mkdir -p "$tools" "$sdk/um/$arch" "$sdk/ucrt/$arch"
  printf 'fixture %s compiler\n' "$arch" > "$tools/cl.exe"
  printf 'fixture %s librarian\n' "$arch" > "$tools/lib.exe"
  printf 'fixture %s linker\n' "$arch" > "$tools/link.exe"
  chmod +x "$tools/"*.exe
  printf 'fixture SDK\n' > "$sdk/um/$arch/kernel32.lib"
  printf 'fixture CRT\n' > "$sdk/ucrt/$arch/ucrt.lib"
done

x64_tools="$VCToolsInstallDir/bin/Hostx64/x64"
arm64_tools="$VCToolsInstallDir/bin/Hostarm64/arm64"
export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER="$fixture/rust-lld.exe"
export CARGO_TARGET_AARCH64_PC_WINDOWS_MSVC_LINKER="$arm64_tools/link.exe"
printf 'fixture rust linker\n' > "$CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER"
probe="$script_dir/windows-package-toolchain-inputs.sh"
export PATH="$x64_tools:$PATH"

x64_inputs="$(bash "$probe")"
test "$x64_inputs" = "$(bash "$probe")"
grep -qx 'target=x86_64-pc-windows-msvc' <<< "$x64_inputs"
test "$x64_inputs" != "$(INCLUDE='second;first' bash "$probe")"
printf 'new linker identity\n' >> "$CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER"
test "$x64_inputs" != "$(bash "$probe")"

# Exercise the package key command with the same native files as the toolchain probe.
export RUNNER_TEMP="$fixture/runner" GITHUB_WORKSPACE="$fixture" CARGO_TARGET_DIR="$fixture/cargo"
metadata="$RUNNER_TEMP/manifest-patch-repo/.github"
mkdir -p "$metadata/scripts" "$metadata/workflows" .github/scripts codex-rs
cp "$probe" "$script_dir/package-cargo-cache.sh" "$metadata/scripts/"
printf 'fixed transport\n' > "$metadata/scripts/ghcr-cargo-target-cache.sh"
printf 'fixed workflow\n' > "$metadata/workflows/upstream-auto-release.yml"
printf 'fixed lockfile\n' > codex-rs/Cargo.lock
printf 'fixed Rust toolchain\n' > codex-rs/rust-toolchain.toml
printf 'printf "fixed package settings\\n"\n' > .github/scripts/codex-package-operations.sh

export VOICE_BUILD_COMMIT=1111111111111111111111111111111111111111
cat > bin/git <<'SHIM'
#!/usr/bin/env bash
case "$1" in
  rev-parse) printf '%s\n' "$VOICE_BUILD_COMMIT" ;;
  status) ;;
  *) exit 2 ;;
esac
SHIM
cat > bin/tooling <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
checksum="$(cat "${@:3}" | sha256sum)"
printf 'tag=cargo-v3-%s-%s\n' "$2" "${checksum%% *}"
SHIM
printf '#!/usr/bin/env bash\nprintf "fixed tool version\\n"\n' > bin/rustc
cp bin/rustc bin/cargo
chmod +x bin/git bin/tooling bin/rustc bin/cargo
export CODEX_TOOLING="$fixture/bin/tooling" GITHUB_OUTPUT="$fixture/cache-output"
export ImageOS=win25 ImageVersion=fixture LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC
export UPSTREAM_COMMIT=2222222222222222222222222222222222222222 UPSTREAM_TAG=rust-v0.167.0
export VERSION=0.167.0 CODEX_UNLEASHED_BUILD_NUMBER=1 SOURCE_DATE_EPOCH=1
export PATCHES='patches/fixture.patch'
mkdir -p "$RUNNER_TEMP/downstream-patches"
printf 'fixed patch\n' > "$RUNNER_TEMP/downstream-$PATCHES"
export RUSTY_V8_ARCHIVE="$fixture/voice-v8.lib" RUSTY_V8_SRC_BINDING_PATH="$fixture/voice-v8.rs"
printf 'fixed V8 archive\n' > "$RUSTY_V8_ARCHIVE"
printf 'fixed V8 bindings\n' > "$RUSTY_V8_SRC_BINDING_PATH"
export VOICE_RELEASE_DIR="$fixture/sealed"
mkdir -p "$VOICE_RELEASE_DIR/runtime/bin"
printf 'voice host\n' > "$VOICE_RELEASE_DIR/codex-voice-host.exe"
printf '{"libraries":[{"path":"bin/voice.dll"}]}\n' > "$VOICE_RELEASE_DIR/runtime/runtime.json"
printf 'voice DLL\n' > "$VOICE_RELEASE_DIR/runtime/bin/voice.dll"

# Windows-native jq writes CRLF to a pipe unless binary mode is enabled.
export FIXTURE_JQ="$(command -v jq)"
cat > bin/jq <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
mode="$1"
shift
case "$mode" in
  -er) "$FIXTURE_JQ" -er "$@" | sed $'s/$/\r/' ;;
  -ber) "$FIXTURE_JQ" -er "$@" ;;
  *) exit 2 ;;
esac
SHIM
chmod +x bin/jq

key_script="$script_dir/package-cargo-cache.sh"
bash "$key_script" inputs >/dev/null
original_key="$(tail -n1 "$GITHUB_OUTPUT")"
grep -Ex 'tag=cargo-v3-x86_64-pc-windows-msvc-pkg-[0-9a-f]{64}' <<< "$original_key"
bash "$key_script" inputs >/dev/null
test "$original_key" = "$(tail -n1 "$GITHUB_OUTPUT")"
printf 'changed linker\n' >> "$CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER"
bash "$key_script" inputs >/dev/null
test "$original_key" != "$(tail -n1 "$GITHUB_OUTPUT")"

touch "$VOICE_RELEASE_DIR/runtime/bin/unlisted.dll"
: > "$GITHUB_OUTPUT"
if bash "$key_script" inputs >/dev/null 2>&1; then
  printf 'Windows package key unexpectedly accepted an unlisted voice DLL\n' >&2
  exit 1
fi
test ! -s "$GITHUB_OUTPUT"

export RUNNER_ARCH=ARM64 TARGET=aarch64-pc-windows-msvc
if bash "$probe" >/dev/null 2>&1; then
  printf 'ARM64 unexpectedly accepted x64 tools on PATH\n' >&2
  exit 1
fi
export PATH="$arm64_tools:$PATH"
# Windows runs the real preflight. On this POSIX fixture Cargo, PE reads
# and executable output are modeled independently to exercise refusal paths.
export ARM64_FIXTURE_RUST_SYSROOT="$fixture/native Rust"
linker="$ARM64_FIXTURE_RUST_SYSROOT/lib/rustlib/aarch64-pc-windows-msvc/bin/rust-lld.exe"
csc="$fixture/Visual Studio/MSBuild/Current/Bin/Roslyn/csc.exe"
wrapper_dir="$RUNNER_TEMP/msvc-lld-wrapper"
mkdir -p "$(dirname "$linker")" "$(dirname "$csc")" "$wrapper_dir"
printf 'rust-lld\n' > "$linker"
printf 'Roslyn csc\n' > "$csc"
printf 'deterministic wrapper\n' > "$wrapper_dir/lld-link-wrapper.exe"
printf 'wrapper source\n' > "$wrapper_dir/lld-link-wrapper.cs"
cat > bin/rustc <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == '-vV' ]]; then
  printf 'fixture ARM64 Rust version\n'
  exit
fi
test "$*" = '--print sysroot'
printf '%s\n' "$ARM64_FIXTURE_RUST_SYSROOT"
SHIM
cat > bin/cargo <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == '--version' ]]; then
  printf 'fixture ARM64 Cargo version\n'
  exit
fi
test "$*" = "rustc --offline --release --target aarch64-pc-windows-msvc --bin codex-arm64-link-preflight -- -C debuginfo=2 -C link-arg=@$PWD/link.rsp"
grep -qx /arm64hazardfree link.rsp
test "$CARGO_TARGET_DIR" = "$PWD/output"
test "${ARM64_FIXTURE_FAILURE-}" != cargo
output="$CARGO_TARGET_DIR/aarch64-pc-windows-msvc/release"
mkdir -p "$output"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "%s"\n'   "${ARM64_FIXTURE_OUTPUT:-codex-arm64-link-ok}" > "$output/codex-arm64-link-preflight.exe"
chmod +x "$output/codex-arm64-link-preflight.exe"
if [[ "${ARM64_FIXTURE_FAILURE-}" != symbols ]]; then
  printf 'Microsoft C/C++ MSF 7.00' > "$output/codex_arm64_link_preflight.pdb"
fi
SHIM
cat > bin/od <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
case "$2:$3" in
  -tx2:-N2) printf ' 5a4d\n' ;;
  -tu4:-j60) printf ' 128\n' ;;
  -tx4:-N4) printf ' 00004550\n' ;;
  -tx2:-j132) printf ' %s\n' "${ARM64_FIXTURE_MACHINE:-aa64}" ;;
  *) exit 2 ;;
esac
SHIM
chmod +x bin/od
arm64_inputs="$(bash "$probe")"
grep -qx 'target=aarch64-pc-windows-msvc' <<< "$arm64_inputs"
test "$arm64_inputs" = "$(bash "$probe")"
printf 'new CRT identity\n' >> "$WindowsSdkDir/Lib/$UCRTVersion/ucrt/arm64/ucrt.lib"
test "$arm64_inputs" != "$(bash "$probe")"

if CARGO_TARGET_AARCH64_PC_WINDOWS_MSVC_LINKER="$fixture/lld-link-wrapper.exe" bash "$probe" >/dev/null 2>&1; then
  printf 'ARM64 unexpectedly accepted an unverified linker wrapper\n' >&2
  exit 1
fi
if (unset CARGO_TARGET_AARCH64_PC_WINDOWS_MSVC_LINKER; bash "$probe") >/dev/null 2>&1; then
  printf 'ARM64 unexpectedly accepted missing linker evidence\n' >&2
  exit 1
fi
if RUNNER_ARCH=X64 bash "$probe" >/dev/null 2>&1; then
  printf 'ARM64 unexpectedly accepted an x64 runner\n' >&2
  exit 1
fi


export CARGO_TARGET_AARCH64_PC_WINDOWS_MSVC_LINKER="$wrapper_dir/lld-link-wrapper.exe"
export MSVC_REAL_LINKER="$linker" MSVC_LINKER_WRAPPER_COMPILER="$csc"
wrapped_inputs="$(bash "$probe")"
test "$wrapped_inputs" = "$(bash "$probe")"
for component in cargo_linker wrapper_source wrapper_compiler real_linker arm64_link_preflight; do
  grep -Ex "${component}_sha256=[0-9a-f]{64}" <<< "$wrapped_inputs"
done
printf 'different linker bytes\n' >> "$linker"
test "$wrapped_inputs" != "$(bash "$probe")"
printf 'rust-lld\n' > "$linker"
printf 'different deterministic wrapper bytes\n' >> "$wrapper_dir/lld-link-wrapper.exe"
test "$wrapped_inputs" != "$(bash "$probe")"

expect_arm64_refusal() {
  if env "$@" bash "$probe" > "$fixture/arm64-refusal" 2>&1; then
    printf 'ARM64 unexpectedly accepted %s\n' "$*" >&2
    exit 1
  fi
  if grep -q '^target=' "$fixture/arm64-refusal"; then
    printf 'ARM64 emitted a receipt after preflight failure for %s\n' "$*" >&2
    exit 1
  fi
}
expect_arm64_refusal MSVC_REAL_LINKER="$arm64_tools/link.exe"
expect_arm64_refusal MSVC_LINKER_WRAPPER_COMPILER=
expect_arm64_refusal ARM64_FIXTURE_FAILURE=cargo
expect_arm64_refusal ARM64_FIXTURE_FAILURE=symbols
expect_arm64_refusal ARM64_FIXTURE_MACHINE=8664
expect_arm64_refusal ARM64_FIXTURE_OUTPUT=unexpected

rm -- "$VOICE_RELEASE_DIR/runtime/bin/unlisted.dll"
cp "$probe" "$script_dir/windows-arm64-package-link-preflight.sh" "$metadata/scripts/"
unset LIBSQLITE3_FLAGS
: > "$GITHUB_OUTPUT"
bash "$key_script" inputs >/dev/null
arm64_key="$(tail -n1 "$GITHUB_OUTPUT")"
grep -Ex 'tag=cargo-v3-aarch64-pc-windows-msvc-pkg-[0-9a-f]{64}' <<< "$arm64_key"
bash "$key_script" inputs >/dev/null
test "$arm64_key" = "$(tail -n1 "$GITHUB_OUTPUT")"
LIBSQLITE3_FLAGS='' bash "$key_script" inputs >/dev/null
test "$arm64_key" != "$(tail -n1 "$GITHUB_OUTPUT")"
for component in cargo_linker wrapper_source wrapper_compiler real_linker arm64_link_preflight; do
  grep -Ex "${component}_sha256=[0-9a-f]{64}" "$RUNNER_TEMP/package-cargo-cache-key.txt"
done
