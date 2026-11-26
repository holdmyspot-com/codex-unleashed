#!/usr/bin/env bash
# Prints the native toolchain and system-library versions that decide whether compiled Cargo outputs are ABI-compatible
# with this runner. A compiled-state cache keyed on this output is reused across runner image updates that leave these
# unchanged, and misses when any of them changes.
set -euo pipefail

first_line() {
  "$@" 2>&1 | head -n 1 || true
}

case "${RUNNER_OS:-$(uname -s)}" in
  Linux)
    # Upstream versions only: distribution security revisions keep the ABI and would otherwise miss the cache.
    printf 'cc=%s %s\n' "$(cc -dumpmachine)" "$(cc -dumpfullversion)"
    printf 'c++=%s\n' "$(c++ -dumpfullversion)"
    printf 'libc=%s\n' "$(getconf GNU_LIBC_VERSION)"
    printf 'os=%s\n' "$(. /etc/os-release && printf '%s %s' "$ID" "$VERSION_ID")"
    ;;
  macOS)
    printf 'cc=%s\n' "$(first_line cc --version)"
    # The SDK, not the host's macOS point release, decides the ABI alongside MACOSX_DEPLOYMENT_TARGET.
    printf 'sdk=%s\n' "$(xcrun --show-sdk-version)"
    ;;
  Windows)
    vswhere="$(cygpath -u "${ProgramFiles} (x86)")/Microsoft Visual Studio/Installer/vswhere.exe"
    installation="$(cygpath -u "$("$vswhere" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | tr -d '\r')")"
    printf 'msvc=%s\n' "$(ls "$installation/VC/Tools/MSVC" | sort | tail -n 1)"
    printf 'windows_sdk=%s\n' "$(ls "$(cygpath -u "${ProgramFiles} (x86)")/Windows Kits/10/Lib" | sort | tail -n 1)"
    ;;
  *)
    printf 'Unsupported runner OS: %s\n' "${RUNNER_OS:-unknown}" >&2
    exit 1
    ;;
esac
