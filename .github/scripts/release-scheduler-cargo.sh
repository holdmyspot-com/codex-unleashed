#!/usr/bin/env bash
# Capture the current release stage, publish its authorization and run original Cargo argv through public Main schedule.
# RELEASE_SCHEDULER_ROOT is the exclusive installation from provision-release-scheduler.sh.
# RUNNER_OS selects Linux/macOS/Windows. Unix credentials come from that installation's
# RELEASE_SCHEDULER_UID/GID. GITHUB_WORKSPACE is the fixed upstream source root;
# CARGO_TARGET_DIR must lie below it, and RUNNER_TEMP owns private construction files.
# RELEASE_SCHEDULER_HEADROOM_BYTES, RELEASE_SCHEDULER_MAX_JOBS and
# RELEASE_SCHEDULER_CONTROL_TIMEOUT_MS are explicit policy inputs with no hidden defaults.
# The installed Java command captures the actual current environment after the driver's
# STABLE_GIT_COMMIT and CODEX_BWRAP_SHA256 updates, excluding native invocation authority.
# Unix configuration is published read-only in a root-controlled namespace. Windows
# retains its existing same-caller identity contract without copying foreign handles.
# Original streams/cwd/argv and command status survive. Before native launch a retained
# marker is created; only status zero removes it. Other outcomes preserve the exact
# configuration and installation for the native owner or terminal worker cleanup.
set -euo pipefail
: "${RELEASE_SCHEDULER_ROOT:?Provision the accepted runtime and native authority}"
: "${GITHUB_WORKSPACE:?Set the fixed pristine upstream checkout}"
: "${CARGO_TARGET_DIR:?Set the owned generated output root below that checkout}"
: "${RUNNER_TEMP:?Set the owned construction parent}"
: "${RELEASE_SCHEDULER_HEADROOM_BYTES:?Supply explicit headroom bytes}"
: "${RELEASE_SCHEDULER_MAX_JOBS:?Supply explicit live-attempt ceiling}"
: "${RELEASE_SCHEDULER_CONTROL_TIMEOUT_MS:?Supply explicit control timeout}"
[[ "$(git -C "$GITHUB_WORKSPACE" rev-parse HEAD)" == dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4 ]]
root="$RELEASE_SCHEDULER_ROOT"
[[ -f "$root/installation" && ! -L "$root" ]]
case "$RUNNER_OS" in
  Linux) option=--linux-launch-config; java_name=java ;;
  macOS) option=--mac-launch-config; java_name=java ;;
  Windows) option=--windows-launch-config; java_name=java.exe ;;
  *) printf 'Unsupported scheduler runner platform.\n' >&2; exit 2 ;;
esac

# Resolve one shell path for the actual native platform without changing tool arguments.
# Arguments: a single existing or declared absolute pathname.
# Environment: RUNNER_OS determines Windows path conversion when cygpath is installed.
# Output: the native path spelling for Java and its strict configuration reader.
# Exit status: path conversion status, or zero when the supplied spelling is already native.
native_path() {
  if [[ "$RUNNER_OS" == Windows ]] && command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s\n' "$1"; fi
}

# Execute one exact filesystem operation with the installation's original authority.
# Arguments: executable and individual arguments, without shell interpretation.
# Environment: RUNNER_OS selects the existing privilege boundary.
# Exit status: the operation's actual status.
admin() {
  if [[ "$RUNNER_OS" == Windows ]]; then "$@"; else sudo -n "$@"; fi
}

if [[ "$RUNNER_OS" == Linux ]]; then
  "$RUNNER_TEMP/source-cache-build/runtime/bin/codex-tooling" validate-release-installation "$root"
elif [[ "$RUNNER_OS" != Windows ]]; then
  bash "$RUNNER_TEMP/source-cache-build/runtime/bin/codex-tooling" validate-release-installation "$root"
fi

temporary_root="$RUNNER_TEMP"
if [[ "$RUNNER_OS" == Windows ]] && command -v cygpath >/dev/null 2>&1; then temporary_root="$(cygpath -u "$temporary_root")"; fi
stage="$(mktemp -d "$temporary_root/release-stage-XXXXXXXX")"
configuration_directory=''
launched=false
completed=false
# Before native launch there are no borrowers. After launch only authentic success
# permits removal; uncertain failures retain both the exact configuration and lease.
trap 'if [[ "$launched" == false || "$completed" == true ]]; then rm -rf -- "$stage"; if [[ -n "$configuration_directory" ]]; then admin rm -rf -- "$configuration_directory"; fi; fi' EXIT
frida_name=frida
if [[ "$RUNNER_OS" == Windows ]]; then frida_name=frida.dll; fi
runtime="$(native_path "$root/runtime")"
java="$runtime/bin/$java_name"
flags=("-Djava.io.tmpdir=$(native_path "$stage")" -Xlog:all=off:stdout -Xlog:all=warning:stderr
  --disable-@files -Djdk.lang.Process.allowAmbiguousCommands=true
  "--enable-native-access=com.holdmyspot.codexunleashed.tooling,com.github.oshi.ffm"
  -m com.holdmyspot.codexunleashed.tooling/com.holdmyspot.codexunleashed.tooling.Main)
construction=(prepare-cargo-launch --platform "$RUNNER_OS" --runtime "$runtime"
  --toolchain "$(native_path "$root/toolchain")" --frida-library "$(native_path "$root/native/$frida_name")"
  --workspace "$(native_path "$GITHUB_WORKSPACE")" --output-root "$(native_path "$CARGO_TARGET_DIR")"
  --history "$(native_path "$root/history")" --output "$(native_path "$stage/launch.json")")
if [[ "$RUNNER_OS" != Windows ]]; then
  construction+=(--uid "${RELEASE_SCHEDULER_UID:?Missing original workload user}" --gid "${RELEASE_SCHEDULER_GID:?Missing original workload group}")
fi
if [[ "$RUNNER_OS" == Linux ]]; then
  construction+=(--unshare /usr/bin/unshare --libcgroup "$root/native/libcgroup.so" --delegation-parent "$(cat "$root/cgroup-parent")"
    --build-hook "$root/native/libbuildhook.so")
fi
"$java" "${flags[@]}" "${construction[@]}" > "$stage/construction.log"
configuration="$stage/launch.json"
if [[ "$RUNNER_OS" != Windows ]]; then
  configuration_directory="$(sudo -n mktemp -d "$root/config-XXXXXXXX")"
  configuration="$configuration_directory/launch.json"
  sudo -n install -m 0444 -o 0 -g 0 "$stage/launch.json" "$configuration"
fi
mkdir -p "$temporary_root/baseline-evidence"
sha256sum "$stage/launch.json" >> "$temporary_root/baseline-evidence/scheduler-configuration-identities.log"
admin touch "$root/retained-owner"
launched=true
command=("$java" "-Djava.io.tmpdir=$(native_path "$root/scratch")" "${flags[@]:1}"
  schedule --tool cargo --workspace "$(native_path "$PWD")" --scratch "$(native_path "$root/scratch")"
  --frida-library "$(native_path "$root/native/$frida_name")" --headroom-bytes "$RELEASE_SCHEDULER_HEADROOM_BYTES"
  --max-jobs "$RELEASE_SCHEDULER_MAX_JOBS" --control-timeout-ms "$RELEASE_SCHEDULER_CONTROL_TIMEOUT_MS"
  "$option" "$(native_path "$configuration")" -- "$@")
set +e
if [[ "$RUNNER_OS" == Windows ]]; then "${command[@]}"; else sudo -n /usr/bin/env -i PATH=/usr/bin:/bin "${command[@]}"; fi
status=$?
set -e
if [[ "$status" == 0 ]]; then admin rm -- "$root/retained-owner"; completed=true; fi
exit "$status"
