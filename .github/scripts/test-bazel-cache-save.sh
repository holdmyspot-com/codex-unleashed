#!/usr/bin/env bash
# Checks the late-producer cache policy without accessing GitHub's cache service.
set -euo pipefail
workflow="${1:-.github/workflows/bazel.yml}"
awk '
function check_save() {
  if (saving) {
    saves++
    if (!(lookup && lookup_only && restore && guard && lookup_key != "" && lookup_path != "" && lookup_key == save_key && lookup_path == save_path)) {
      print "Cache save requires an exact, download-free lookup and a miss guard at line " save_line > "/dev/stderr"
      failed = 1
    }
  }
  saving = 0
}
/^  [^ ]/ { check_save(); lookup = 0; lookup_only = 0; restore = 0 }
/^      - name:/ {
  check_save()
  looking = 0
  if ($0 ~ /Recheck Bazel repository cache before saving/) {
    lookup = 1
    looking = 1
    lookup_only = 0
    restore = 0
    lookup_key = ""
    lookup_path = ""
  }
  if ($0 ~ /Save [Bb]azel repository cache/) {
    saving = 1
    save_line = NR
    guard = 0
    save_key = ""
    save_path = ""
  }
}
looking && /uses: actions\/cache\/restore@/ { restore = 1 }
looking && /lookup-only: true/ { lookup_only = 1 }
looking && /^          key:/ { lookup_key = $0 }
looking && /^          path:/ { lookup_path = $0 }
saving && /^          key:/ { save_key = $0 }
saving && /^          path:/ { save_path = $0 }
saving && /steps.repository_cache_before_save.outputs.cache-hit !=/ {
  if (index($0, "\047true\047")) guard = 1
}
END {
  check_save()
  if (saves == 0) {
    print "No cache producers remain" > "/dev/stderr"
    failed = 1
  }
  if (failed) exit 1
  print "Verified " saves " cache producers retain miss saves and skip late exact hits"
}
' "$workflow"
