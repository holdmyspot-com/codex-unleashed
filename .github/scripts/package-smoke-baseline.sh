#!/usr/bin/env bash
# Judges a failed package smoke run by its test failures, the way the nextest and Bazel workflows judge theirs.
#
# Upstream's build-codex-packages action builds the package archives and then runs pytest over them in one step, so a
# failing smoke test also fails the build. When that step fails, this script checks that the build produced every archive
# and runs the smoke tests again with JUnit reports, once per compression. A failure that is new fails the run; a
# failure the baseline from an unpatched run of the same upstream commit already lists does not.
#
# Usage: package-smoke-baseline.sh record|compare
#   record   writes the failures to $PACKAGE_SMOKE_EVIDENCE/<platform>.json, to be committed as the baseline
#   compare  compares them with <baseline dir>/<upstream commit>/<platform>.json, where <platform> is
#            package-smoke-<target>. With no baseline file any failure is new; a run with no failures passes.
#
# Environment: TARGET, UPSTREAM_COMMIT, RUNNER_TEMP, GITHUB_WORKSPACE (the upstream checkout, patched or not),
# PACKAGE_SMOKE_TOOLS (directory holding compare-test-baseline.py and test-baselines/), PACKAGE_SMOKE_EVIDENCE (record).
# PYTHONPATH must be set as in the action's smoke step.

set -euo pipefail

mode="${1:?usage: package-smoke-baseline.sh record|compare}"
case "$mode" in record | compare) ;; *) echo "Unknown mode: $mode" >&2; exit 2 ;; esac

platform="package-smoke-${TARGET}"
archive_dir="${GITHUB_WORKSPACE}/dist/${TARGET}"
symbols_archive="${GITHUB_WORKSPACE}/symbols-dist/${TARGET}/codex-symbols-${TARGET}.tar.gz"
tools="${PACKAGE_SMOKE_TOOLS}"
reports="${RUNNER_TEMP}/package-smoke-reports"

# A build failure leaves archives missing; that must stay a failure rather than an empty comparison.
for archive in "${archive_dir}/codex-package-${TARGET}.tar.gz" "${archive_dir}/codex-package-${TARGET}.tar.zst" \
  "${archive_dir}/codex-app-server-package-${TARGET}.tar.gz" "${archive_dir}/codex-app-server-package-${TARGET}.tar.zst" \
  "$symbols_archive"; do
  if [[ ! -f "$archive" ]]; then
    echo "The package build did not produce ${archive}; this is a build failure, not a smoke-test failure." >&2
    exit 1
  fi
done

rm -rf "$reports"
mkdir -p "$reports"
cd "${GITHUB_WORKSPACE}/scripts/codex_package/smoke_tests"
junit_reports=()
for compression in gzip zstd; do
  extension="tar.gz"
  if [[ "$compression" == zstd ]]; then extension="tar.zst"; fi
  # The exit status is judged by the comparison below. Separate base directories keep Windows setup helpers that finish
  # background work after pytest exits from deleting each other's executables.
  uv run --frozen pytest -v \
    --package-target "$TARGET" \
    --symbols-archive "$symbols_archive" \
    --basetemp "${GITHUB_WORKSPACE}/codex-rs/target/package-smoke-${compression}-compare-tmp" \
    --compression "$compression" \
    --cli-archive "${archive_dir}/codex-package-${TARGET}.${extension}" \
    --app-server-archive "${archive_dir}/codex-app-server-package-${TARGET}.${extension}" \
    --junitxml "${reports}/${compression}.xml" \
    test_codex_package.py || true
  # Pytest writes the report even when tests fail; a missing report means pytest did not start or collect.
  if [[ ! -f "${reports}/${compression}.xml" ]]; then
    echo "The ${compression} smoke run wrote no JUnit report." >&2
    exit 1
  fi
  junit_reports+=("${reports}/${compression}.xml")
done

if [[ "$mode" == record ]]; then
  mkdir -p "${PACKAGE_SMOKE_EVIDENCE}"
  python3 "${tools}/compare-test-baseline.py" --platform "$platform" --upstream-commit "$UPSTREAM_COMMIT" \
    --write-baseline --output "${PACKAGE_SMOKE_EVIDENCE}/${platform}.json" "${junit_reports[@]}"
  exit 0
fi

baseline="${tools}/test-baselines/${UPSTREAM_COMMIT}/${platform}.json"
if [[ -f "$baseline" ]]; then
  python3 "${tools}/compare-test-baseline.py" --platform "$platform" --upstream-commit "$UPSTREAM_COMMIT" \
    --baseline-dir "${tools}/test-baselines" "${junit_reports[@]}"
  exit 0
fi

# Without a baseline every failure is new, and a run that has none passes.
python3 "${tools}/compare-test-baseline.py" --platform "$platform" --upstream-commit "$UPSTREAM_COMMIT" \
  --write-baseline --output "${reports}/observed.json" "${junit_reports[@]}"
if python3 -I -c 'import json, sys; d = json.load(open(sys.argv[1])); sys.exit(1 if d["failed"] or d["timed_out"] else 0)' \
  "${reports}/observed.json"; then
  echo "No smoke test failed on this rerun and there is no baseline file; nothing is new."
  exit 0
fi
echo "Smoke tests failed and ${baseline} does not exist; record it from an unpatched run (record mode)." >&2
cat "${reports}/observed.json" >&2
exit 1
