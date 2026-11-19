#!/usr/bin/env bash
# Build the maintained tooling runtime and append its launcher paths to GITHUB_ENV.
# TOOLING_ROOT names the checkout containing tooling/cli/pom.xml. RUNNER_TEMP owns
# the codex-tooling build, Maven cache and temporary directories. RUNNER_OS selects
# Windows path conversion. PATH must provide Maven, Bash and, on Windows, cygpath;
# the caller must select the JDK before invoking this script.
# Exit nonzero on a build, path conversion or missing-launcher failure. Build and
# cache directories remain under RUNNER_TEMP; launcher exports are appended only
# after the build and path conversion succeed.
# A Maven artifact-resolution failure (a hosted runner's Maven Central request answered "not found" for an artifact that
# Central serves) is retried up to MAVEN_ATTEMPTS times (default 3) after MAVEN_RETRY_DELAY_SECONDS (default 10) times the
# attempt number. Each attempt uses -U and a new empty repository, so no failed lookup from an earlier attempt is reused.
# Any other Maven failure, such as a compilation error, fails immediately.
set -euo pipefail
root="$RUNNER_TEMP/codex-tooling"
tooling_root="$TOOLING_ROOT"
if [[ "$RUNNER_OS" == Windows ]]; then
  tooling_root="$(cygpath -u "$tooling_root")"
fi
mkdir -p "$root/build" "$root/maven" "$root/tmp"
export TMPDIR="$root/tmp" TMP="$root/tmp" TEMP="$root/tmp"
maven_attempts="${MAVEN_ATTEMPTS:-3}"
maven_retry_delay="${MAVEN_RETRY_DELAY_SECONDS:-10}"
maven_log="$root/tmp/maven.log"
attempt=1
while true; do
  # A repository from a failed attempt may hold negative-cache markers (*.lastUpdated, resolver-status.properties).
  rm -rf -- "$root/maven"
  mkdir -p "$root/maven"
  status=0
  mvn -q -B -ntp -U -f "$tooling_root/tooling/cli/pom.xml" -Pruntime -DskipTests \
    "-Dtooling.build.directory=$root/build" \
    "-Dmaven.repo.local=$root/maven" \
    "-Djava.io.tmpdir=$root/tmp" package > "$maven_log" 2>&1 || status=$?
  cat "$maven_log"
  if ((status == 0)); then
    break
  fi
  if ((attempt >= maven_attempts)) ||
    ! grep -Eq 'Could not find artifact|Could not transfer artifact|could not be resolved|is missing, no dependency information' "$maven_log"; then
    exit "$status"
  fi
  echo "Maven could not resolve an artifact (attempt $attempt of $maven_attempts); retrying." >&2
  sleep "$((maven_retry_delay * attempt))"
  attempt=$((attempt + 1))
done
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
