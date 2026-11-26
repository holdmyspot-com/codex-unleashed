#!/usr/bin/env bash
# Records and compares the native ABI that compiled Cargo outputs depend on, so compiled state is reused across runner
# image updates that keep it compatible and is discarded when they do not.
#
#   native-abi.sh identity                      print this runner's ABI identity, one key=value per line
#   native-abi.sh compatible <cached> <current> exit 0 when outputs built under <cached> run and link under <current>
#
# Compatibility rules: the operating-system release (Linux), compiler major version, and toolset major version must
# match; glibc and the platform SDK may only be the same or newer on the current runner, because both are backward
# compatible but code built against a newer one can need symbols an older one lacks.
set -euo pipefail

identity() {
  case "${RUNNER_OS:-$(uname -s)}" in
    Linux)
      printf 'os=%s\n' "$(. /etc/os-release && printf '%s-%s' "$ID" "$VERSION_ID")"
      printf 'compiler=gcc-%s\n' "$(cc -dumpfullversion)"
      printf 'cxx=%s\n' "$(c++ -dumpfullversion)"
      printf 'libc=%s\n' "$(getconf GNU_LIBC_VERSION | awk '{print $2}')"
      ;;
    macOS|Darwin)
      printf 'compiler=apple-clang-%s\n' "$(cc -dumpversion)"
      printf 'sdk=%s\n' "$(xcrun --show-sdk-version)"
      ;;
    Windows)
      local program_files vswhere installation
      program_files="$(cygpath -u "${ProgramFiles} (x86)")"
      vswhere="$program_files/Microsoft Visual Studio/Installer/vswhere.exe"
      installation="$(cygpath -u "$("$vswhere" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | tr -d '\r')")"
      printf 'compiler=msvc-%s\n' "$(ls "$installation/VC/Tools/MSVC" | sort -V | tail -n 1)"
      printf 'sdk=%s\n' "$(ls "$program_files/Windows Kits/10/Lib" | sort -V | tail -n 1)"
      ;;
    *)
      printf 'Unsupported runner OS: %s\n' "${RUNNER_OS:-unknown}" >&2
      exit 2
      ;;
  esac
}

value() {
  sed -n "s/^$2=//p" "$1" | head -n 1
}

major() {
  # gcc-13.3.0 -> gcc-13; apple-clang-17.0.0 -> apple-clang-17; msvc-14.44.35207 -> msvc-14; 10.0.26100.0 -> 10.0
  sed -E 's/^([a-z-]*[0-9]+)(\.[0-9]+)?.*/\1/' <<< "$1"
}

not_newer() {
  # True when version $1 is the same as or older than version $2.
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n 1)" == "$1" ]]
}

compatible() {
  local cached="$1" current="$2" key old new
  if [[ ! -s "$cached" ]]; then
    printf 'incompatible: the cached state records no native ABI\n'
    return 1
  fi
  for key in os; do
    old="$(value "$cached" "$key")"; new="$(value "$current" "$key")"
    if [[ "$old" != "$new" ]]; then
      printf 'incompatible: %s %s differs from %s\n' "$key" "$old" "$new"; return 1
    fi
  done
  for key in compiler cxx; do
    old="$(value "$cached" "$key")"; new="$(value "$current" "$key")"
    if [[ "$(major "$old")" != "$(major "$new")" ]]; then
      printf 'incompatible: %s %s has a different major version from %s\n' "$key" "$old" "$new"; return 1
    fi
  done
  for key in libc sdk; do
    old="$(value "$cached" "$key")"; new="$(value "$current" "$key")"
    if [[ "${old%%.*}" != "${new%%.*}" ]] || ! not_newer "$old" "$new"; then
      printf 'incompatible: %s %s is not an older or equal release of %s\n' "$key" "$old" "$new"; return 1
    fi
  done
  printf 'compatible: cached %s; current %s\n' "$(paste -sd' ' "$cached")" "$(paste -sd' ' "$current")"
}

case "${1:-}" in
  identity) identity ;;
  compatible) compatible "${2:?cached identity}" "${3:?current identity}" ;;
  *) printf 'usage: %s identity | compatible <cached> <current>\n' "$0" >&2; exit 2 ;;
esac
