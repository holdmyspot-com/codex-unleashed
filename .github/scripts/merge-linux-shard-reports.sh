#!/usr/bin/env bash
# Merge the nextest reports of the Linux test shards into one report directory per lane.
#
# Each shard job uploads an evidence artifact named baseline-linux-tests-evidence-shard-N. Its nextest-reports/LANE
# directory holds that shard's JUnit reports for the lane, and its nextest-status.txt holds one nextest_status_LANE=CODE
# line per lane. The results job downloads the artifacts under EVIDENCE_DIRECTORY/jobs. A lane is complete only when
# every shard from 1 to SHARD_COUNT reported it, so a shard that crashed or never uploaded cannot leave part of the
# suite unjudged.
# Arguments:
#   EVIDENCE_DIRECTORY  Directory with the downloaded jobs/ subdirectory.
#   REPORTS_DIRECTORY   Directory to fill with LANE/shard-N report trees; it must not exist yet.
#   SHARD_COUNT         Number of shards the run used.
#   LANE...             One or more lanes the run executed.
# Output:
#   One nextest_status_LANE=CODE line per lane on standard output: 0 when every shard's nextest run for the lane exited
#   with 0, otherwise the first nonzero code. Problems go to standard error.
# Exit status:
#   0 when every lane is complete, 1 when a shard's reports or status are missing, 2 on a usage error.
set -euo pipefail
if (( $# < 4 )); then
  printf 'Usage: %s EVIDENCE_DIRECTORY REPORTS_DIRECTORY SHARD_COUNT LANE...\n' "$0" >&2
  exit 2
fi
evidence="$1"
reports="$2"
shard_count="$3"
shift 3
if [[ ! "$shard_count" =~ ^[1-9][0-9]*$ ]]; then
  printf 'SHARD_COUNT must be a positive integer, got: %s\n' "$shard_count" >&2
  exit 2
fi
if [[ -e "$reports" ]]; then
  printf 'The report directory already exists: %s\n' "$reports" >&2
  exit 2
fi
incomplete=0
for lane in "$@"; do
  lane_status=0
  for (( shard = 1; shard <= shard_count; ++shard )); do
    shard_evidence="$evidence/jobs/baseline-linux-tests-evidence-shard-$shard"
    lane_reports="$shard_evidence/nextest-reports/$lane"
    if [[ ! -d "$lane_reports" ]] || [[ -z "$(find "$lane_reports" -type f -name junit.xml -print -quit)" ]]; then
      printf 'Shard %s has no JUnit report for the %s lane.\n' "$shard" "$lane" >&2
      incomplete=1
      continue
    fi
    shard_status="$(sed -n "s/^nextest_status_${lane}=//p" "$shard_evidence/nextest-status.txt" 2>/dev/null | tail -n 1)"
    if [[ ! "$shard_status" =~ ^[0-9]+$ ]]; then
      printf 'Shard %s recorded no nextest status for the %s lane.\n' "$shard" "$lane" >&2
      incomplete=1
      continue
    fi
    if [[ "$lane_status" == 0 ]]; then lane_status="$shard_status"; fi
    mkdir -p "$reports/$lane"
    # Hard links avoid a second copy of large reports; the tooling reads regular files only.
    cp -al "$lane_reports" "$reports/$lane/shard-$shard"
  done
  printf 'nextest_status_%s=%s\n' "$lane" "$lane_status"
done
exit "$incomplete"
