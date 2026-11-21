#!/usr/bin/env bash
# Print native Windows package-build toolchain inputs before restoring the Cargo cache.
# Run from the patched source root in Git Bash, after setup-msvc-env.
set -euo pipefail
export LC_ALL=C

case "$RUNNER_OS:$RUNNER_ARCH:$TARGET" in
  Windows:X64:x86_64-pc-windows-msvc) msvc_arch=x64 ;;
  Windows:ARM64:aarch64-pc-windows-msvc) msvc_arch=arm64 ;;
  *) printf 'Unsupported native Windows package target: %s/%s/%s\n' "$RUNNER_OS" "$RUNNER_ARCH" "$TARGET" >&2; exit 2 ;;
esac

target_variable="${TARGET^^}"
linker_variable="CARGO_TARGET_${target_variable//-/_}_LINKER"
cargo_linker="$(cygpath -au "${!linker_variable:?Missing target Cargo linker}")"
linker_name="${cargo_linker##*/}"
if [[ -n "${MSVC_REAL_LINKER-}" || "${linker_name,,}" == lld-link-wrapper.exe ]]; then
  printf 'The generated ARM64 linker wrapper requires a reproducible compiler receipt before cache reuse\n' >&2
  exit 1
fi

tools_dir="$(cygpath -au "${VCToolsInstallDir:?Missing MSVC tools directory}")"
native_bin="${tools_dir%/}/bin/Host$msvc_arch/$msvc_arch"
native_linker="$native_bin/link.exe"
if [[ "$msvc_arch" == arm64 && "${cargo_linker,,}" != "${native_linker,,}" ]]; then
  printf 'ARM64 package cache requires the direct native MSVC linker\n' >&2
  exit 1
fi
compiler="$(cygpath -au "$(command -v cl.exe)")"
librarian="$(cygpath -au "$(command -v lib.exe)")"
expected_compiler="$native_bin/cl.exe"
expected_librarian="$native_bin/lib.exe"
if [[ "${compiler,,}" != "${expected_compiler,,}" || "${librarian,,}" != "${expected_librarian,,}" ]]; then
  printf 'PATH selects a compiler or librarian outside the native MSVC tool directory\n' >&2
  exit 1
fi

sdk_dir="$(cygpath -au "${WindowsSdkDir:?Missing Windows SDK directory}")"
ucrt_dir="$(cygpath -au "${UniversalCRTSdkDir:?Missing Universal CRT directory}")"
sdk_lib_version="${WindowsSDKLibVersion:?Missing Windows SDK library version}"
sdk_lib_version="${sdk_lib_version//\\//}"
sdk_lib_version="${sdk_lib_version%/}"
sdk_library="${sdk_dir%/}/Lib/$sdk_lib_version/um/$msvc_arch/kernel32.lib"
ucrt_library="${ucrt_dir%/}/Lib/${UCRTVersion:?Missing Universal CRT version}/ucrt/$msvc_arch/ucrt.lib"

# Emit each file's path and digest. Hash the bytes read at the selected path so
# a file replaced in place invalidates the key.
print_file_identity() {
  local label="$1" path="$2" digest
  digest="$(sha256sum < "$path")"
  printf '%s_path=%q\n%s_sha256=%q\n' "$label" "$path" "$label" "${digest%% *}"
}

printf '%s=%q\n' target "$TARGET" runner_os "$RUNNER_OS" runner_arch "$RUNNER_ARCH" \
  msvc_tools_dir "$tools_dir" windows_sdk_dir "$sdk_dir" \
  windows_sdk_version "${WindowsSDKVersion:?Missing Windows SDK version}" \
  windows_sdk_library_version "$sdk_lib_version" \
  windows_sdk_bin_path "${WindowsSdkBinPath:?Missing Windows SDK binary directory}" \
  ucrt_dir "$ucrt_dir" ucrt_version "$UCRTVersion" \
  include "${INCLUDE:?Missing MSVC include search path}" lib "${LIB:?Missing MSVC library search path}" \
  libpath "${LIBPATH:?Missing MSVC metadata search path}" windows_lib_path "${WindowsLibPath-}"
print_file_identity compiler "$compiler"
print_file_identity librarian "$librarian"
print_file_identity cargo_linker "$cargo_linker"
print_file_identity sdk_kernel32 "$sdk_library"
print_file_identity sdk_ucrt "$ucrt_library"
print_file_identity setup_msvc .github/actions/setup-msvc-env/setup-msvc-env.ps1
