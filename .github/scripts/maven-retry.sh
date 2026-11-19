#!/usr/bin/env bash
# Run Maven against a job-owned local repository, retrying an artifact-resolution failure.
# Usage: maven-retry.sh REPOSITORY [MAVEN_ARGUMENT...]
# REPOSITORY is the Maven local repository directory. It must be owned by the caller: this script deletes it before every
# attempt. The script runs "mvn -B -ntp -U -Dmaven.repo.local=REPOSITORY MAVEN_ARGUMENT...", so callers must not pass
# -Dmaven.repo.local or -U themselves. PATH must provide Maven and Bash.
# Maven output goes to standard output and standard error as it is produced. The exit status is Maven's.
# A Maven artifact-resolution failure (a hosted runner's Maven Central request answered "not found" for an artifact that
# Central serves) is retried up to MAVEN_ATTEMPTS times (default 3) after MAVEN_RETRY_DELAY_SECONDS (default 10) times the
# attempt number. Each attempt uses -U and a new empty repository, so no failed lookup from an earlier attempt is reused.
# Any other Maven failure, such as a compilation error, fails immediately.
set -euo pipefail
if (($# < 1)); then
  echo "usage: maven-retry.sh REPOSITORY [MAVEN_ARGUMENT...]" >&2
  exit 2
fi
repository="$1"
shift
maven_attempts="${MAVEN_ATTEMPTS:-3}"
maven_retry_delay="${MAVEN_RETRY_DELAY_SECONDS:-10}"
maven_log="$(mktemp)"
trap 'rm -f -- "$maven_log"' EXIT
attempt=1
while true; do
  # A repository from a failed attempt may hold negative-cache markers (*.lastUpdated, resolver-status.properties).
  rm -rf -- "$repository"
  mkdir -p "$repository"
  set +e
  mvn -B -ntp -U "-Dmaven.repo.local=$repository" "$@" 2>&1 | tee "$maven_log"
  status="${PIPESTATUS[0]}"
  set -e
  if ((status == 0)); then
    exit 0
  fi
  if ((attempt >= maven_attempts)) ||
    ! grep -Eq 'Could not find artifact|Could not transfer artifact|could not be resolved|is missing, no dependency information' "$maven_log"; then
    exit "$status"
  fi
  echo "Maven could not resolve an artifact (attempt $attempt of $maven_attempts); retrying." >&2
  sleep "$((maven_retry_delay * attempt))"
  attempt=$((attempt + 1))
done
