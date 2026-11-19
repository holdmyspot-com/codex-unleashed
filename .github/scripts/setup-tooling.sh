#!/usr/bin/env bash
# Build the maintained tooling runtime and append its launcher paths to GITHUB_ENV.
# TOOLING_ROOT names the checkout containing tooling/cli/pom.xml. RUNNER_TEMP owns
# the codex-tooling build, Maven cache and temporary directories. RUNNER_OS selects
# Windows path conversion. PATH must provide Maven, Bash and, on Windows, cygpath;
# the caller must select the JDK before invoking this script.
# Exit nonzero on a build, path conversion or missing-launcher failure. Build and
# cache directories remain under RUNNER_TEMP; launcher exports are appended only
# after the build and path conversion succeed.
# Maven runs through maven-retry.sh, which retries an artifact-resolution failure with -U and a new empty repository.
set -euo pipefail
root="$RUNNER_TEMP/codex-tooling"
tooling_root="$TOOLING_ROOT"
if [[ "$RUNNER_OS" == Windows ]]; then
  tooling_root="$(cygpath -u "$tooling_root")"
fi
mkdir -p "$root/build" "$root/tmp"
export TMPDIR="$root/tmp" TMP="$root/tmp" TEMP="$root/tmp"
bash "$(dirname "${BASH_SOURCE[0]}")/maven-retry.sh" "$root/maven" -q -f "$tooling_root/tooling/cli/pom.xml" -Pruntime -DskipTests \
  "-Dtooling.build.directory=$root/build" \
  "-Djava.io.tmpdir=$root/tmp" package
launcher="$root/build/runtime/bin/codex-tooling"
bash_path="$(command -v bash)"
test -f "$launcher"
if [[ "$RUNNER_OS" == Windows ]]; then
  launcher="$(cygpath -w "$launcher")"
  bash_path="$(cygpath -w "$bash_path")"
  root="$(cygpath -w "$root")"
fi
{
  printf 'CODEX_TOOLING=%s\n' "$launcher"
  printf 'CODEX_TOOLING_BASH=%s\n' "$bash_path"
  printf 'CODEX_TOOLING_ROOT=%s\n' "$root"
} >> "$GITHUB_ENV"
