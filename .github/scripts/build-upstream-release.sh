#!/usr/bin/env bash
# Build the selected native upstream release bundles with default features.
# Run from codex-rs with TARGET, CARGO_TARGET_DIR and RUNNER_TEMP configured.
# Cargo failures propagate; logs and toolchain identity go to baseline-evidence.
# RELEASE_PACKAGES optionally names whitespace-separated Cargo package specifications. When set, one Cargo build of
# exactly those packages replaces the release stages and no release stage is checkpointed, so a build option can be
# checked on a few crates before the whole build. Each entry must be a plain package specification. The build is
# verbose so that a failing compilation's log records its exact compiler command line. When it fails,
# package-libraries.txt in baseline-evidence lists every library in a deps directory as "size modified path", sorted by
# path with UTC modification times, so a library that a dependent could not load can be checked afterwards.
set -euo pipefail

: "${TARGET:?Set the release target}"
: "${CARGO_TARGET_DIR:?Set the owned Cargo output directory}"
: "${RUNNER_TEMP:?Set the owned runner temporary directory}"
evidence="$RUNNER_TEMP/baseline-evidence"
mkdir -p "$evidence"

# Run the configured checkpoint command for a completed release binary.
# Arguments:
#   Completed release binary name, passed unchanged to the checkpoint command.
# Environment:
#   RELEASE_CHECKPOINT_COMMAND: optional executable path; empty or unset skips saving.
# Side effects:
#   Runs the configured command with the caller's environment and working directory.
# Output:
#   Forwards the command's standard output.
# Exit status:
#   Zero when no command is configured; otherwise the command's status.
checkpoint() {
  if [[ -n "${RELEASE_CHECKPOINT_COMMAND:-}" ]]; then
    "$RELEASE_CHECKPOINT_COMMAND" "$1"
  fi
}

# Compile with Cargo.
# Arguments:
#   Original Cargo arguments, forwarded individually without shell interpretation.
# Environment:
#   PATH: command search path for cargo.
# Output:
#   Forwards Cargo's standard output and standard error.
# Side effects:
#   Runs one compilation in the caller's working directory and environment.
# Exit status:
#   Cargo's status.
compile() {
  cargo "$@"
}

if [[ -n "${RELEASE_PACKAGES+set}" ]]; then
  read -r -a packages <<< "$RELEASE_PACKAGES"
  if (( ${#packages[@]} == 0 )); then
    printf 'RELEASE_PACKAGES names no package\n' >&2
    exit 2
  fi
  selection=()
  for package in "${packages[@]}"; do
    if [[ ! "$package" =~ ^[A-Za-z0-9_][A-Za-z0-9_.@:+-]*$ ]]; then
      printf 'Not a plain Cargo package specification: %s\n' "$package" >&2
      exit 2
    fi
    selection+=(-p "$package")
  done
  started=$SECONDS
  status=0
  compile build --target "$TARGET" --release --timings --verbose "${selection[@]}" 2>&1 |
    tee "$evidence/cargo-build-packages.log" || status=$?
  if (( status != 0 )); then
    python3 -I - "$CARGO_TARGET_DIR" > "$evidence/package-libraries.txt" <<'LIST'
import datetime, pathlib, sys
for path in sorted(pathlib.Path(sys.argv[1]).rglob("lib*")):
    if path.parent.name != "deps" or not path.is_file():
        continue
    metadata = path.stat()
    seconds, nanoseconds = divmod(metadata.st_mtime_ns, 1_000_000_000)
    moment = datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    print(f"{metadata.st_size} {moment}.{nanoseconds:09d}Z {path}")
LIST
    exit "$status"
  fi
  printf 'packages=%s build_seconds=%s\n' "${packages[*]}" "$((SECONDS-started))" | tee -a "$evidence/build-stage-timings.log"
  exit 0
fi

case "$TARGET" in
  *-linux-musl)
    started=$SECONDS
    compile build --target "$TARGET" --release --timings --bin bwrap 2>&1 | tee "$evidence/bwrap-build.log"
    helper="$CARGO_TARGET_DIR/$TARGET/release/bwrap"
    strip --preserve-dates --strip-debug --strip-unneeded "$helper"
    CODEX_BWRAP_SHA256="$(sha256sum "$helper" | cut -d ' ' -f 1)"
    export CODEX_BWRAP_SHA256
    printf 'binary=bwrap build_seconds=%s\n' "$((SECONDS-started))" | tee -a "$evidence/build-stage-timings.log"
    checkpoint bwrap
    ;;
esac

STABLE_GIT_COMMIT="$(git rev-parse HEAD)"
export STABLE_GIT_COMMIT
rustc -vV > "$evidence/rust-toolchain.txt"
cargo --version >> "$evidence/rust-toolchain.txt"
binaries=(codex-responses-api-proxy codex-code-mode-host codex codex-app-server)
case "$TARGET" in
  *-windows-msvc)
    binaries+=(codex-windows-sandbox-setup codex-windows-sandbox-service codex-command-runner)
    ;;
esac

for binary in "${binaries[@]}"; do
  started=$SECONDS
  compile build --target "$TARGET" --release --timings --bin "$binary" 2>&1 | tee "$evidence/cargo-build-$binary.log"
  printf 'binary=%s build_seconds=%s\n' "$binary" "$((SECONDS-started))" | tee -a "$evidence/build-stage-timings.log"
  checkpoint "$binary"
done
