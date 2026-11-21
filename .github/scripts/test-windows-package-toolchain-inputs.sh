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
test "$1" = -au
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
unset MSVC_REAL_LINKER WindowsLibPath

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
