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
# A miss that persisted through three attempts within half a minute while five sibling jobs resolved the same artifact
# means that this runner's path to Central was serving a wrong answer, so retrying the same endpoint cannot help. Every
# even attempt therefore resolves Central through MAVEN_CENTRAL_FALLBACK_URL (default: Google's Maven Central mirror)
# by way of a generated global settings file; odd attempts use Central. After each resolution failure the script
# prints the HTTP status, remote address and CDN headers that this runner gets for the missing artifact, so that the
# next occurrence names its cause in the log.
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
maven_central_fallback="${MAVEN_CENTRAL_FALLBACK_URL:-https://maven-central.storage-download.googleapis.com/maven2/}"
work_directory="$(mktemp -d)"
maven_log="$work_directory/maven.log"
fallback_settings="$work_directory/central-fallback-settings.xml"
trap 'rm -rf -- "$work_directory"' EXIT
cat > "$fallback_settings" <<SETTINGS
<settings>
  <mirrors>
    <mirror>
      <id>central-fallback</id>
      <mirrorOf>central</mirrorOf>
      <url>$maven_central_fallback</url>
    </mirror>
  </mirrors>
</settings>
SETTINGS

# Prints what this runner receives for the artifact that the last Maven log reported missing from a repository.
diagnose_missing_artifact() {
  local pattern='Could not find artifact ([^: ]+):([^: ]+):([^: ]+):([^: ]+) in [^ ]+ \((https?://[^)]+)\)'
  local line url
  line="$(grep -Em1 'Could not find artifact [^ ]+ in [^ ]+ \(https?://[^)]+\)' "$maven_log" || true)"
  if [[ ! $line =~ $pattern ]]; then
    return 0
  fi
  url="${BASH_REMATCH[5]%/}/${BASH_REMATCH[1]//.//}/${BASH_REMATCH[2]}/${BASH_REMATCH[4]}/${BASH_REMATCH[2]}-${BASH_REMATCH[4]}.${BASH_REMATCH[3]}"
  echo "Diagnostics for the missing artifact $url:" >&2
  curl -sSI -m 20 -w 'remote_ip=%{remote_ip} http_code=%{http_code}\n' "$url" 2>&1 |
    grep -Eia '^(HTTP|remote_ip|x-served-by|x-cache|via|age|server|date|curl:)' >&2 || true
}
attempt=1
while true; do
  # A repository from a failed attempt may hold negative-cache markers (*.lastUpdated, resolver-status.properties).
  rm -rf -- "$repository"
  mkdir -p "$repository"
  central_options=()
  if ((attempt % 2 == 0)); then
    central_options=(-gs "$fallback_settings")
    echo "Resolving Maven Central through $maven_central_fallback (attempt $attempt)." >&2
  fi
  set +e
  # The expansion form keeps an empty array valid under "set -u" in Bash 3.2, the macOS system Bash.
  mvn -B -ntp -U ${central_options[@]+"${central_options[@]}"} "-Dmaven.repo.local=$repository" "$@" 2>&1 | tee "$maven_log"
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
  diagnose_missing_artifact
  sleep "$((maven_retry_delay * attempt))"
  attempt=$((attempt + 1))
done
