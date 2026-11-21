#!/usr/bin/env bash
# Hosted-only compatibility check using the original immutable Python artifact.
set -euo pipefail

helper="$RUNNER_TEMP/manifest-patch-repo/.github/scripts/macos-sealed-voice-cache.sh"
candidate="$RUNNER_TEMP/voice-python-compatibility"
proof="$RUNNER_TEMP/voice-auto-proof"
frozen="$RUNNER_TEMP/voice-python-inputs.json"
current="$RUNNER_TEMP/voice-cache-inputs.json"
result="$RUNNER_TEMP/voice-python-result"
jq -eS '.inputs' "$candidate/manifest.json" > "$frozen"
# Prove production refuses the frozen artifact under the current fork/workflow.
if bash "$helper" restore-one "$TARGET" "$PACKAGE_VERSION" "$candidate" "$result" "$proof/rejected" "$current"; then
  printf '::error::An out-of-scope voice bundle was accepted\n'
  exit 1
fi
test ! -e "$result"
# Freeze the expected historical fork and workflow in this fixture only.
# The actual native settings and patched application tree must still match.
if ! diff -q <(jq -cS 'del(.fork_commit)' "$frozen") <(jq -cS 'del(.fork_commit)' "$current"); then
  jq -n '{status:"skipped",reason:"Frozen producer has different native inputs"}' > "$proof/python-compatibility.json"
  exit 0
fi
GITHUB_WORKFLOW_REF="$(jq -er .producer.workflow_ref "$candidate/manifest.json")" \
  bash "$helper" restore-one "$TARGET" "$PACKAGE_VERSION" "$candidate" "$result" "$proof/python-native-fixture" "$frozen"
origin="$result/evidence/voice-cache-origin.json"
jq -eS --arg consumer "$GITHUB_RUN_ID" '
  select(.producer.run_id == "44917974040" and .consumer.run_id == $consumer and .artifact_id == 14713750157) |
  {status:"passed", original_producer:.producer, current_consumer_run:.consumer.run_id, artifact_id, artifact_sha256,
   manifest_sha256, archive_sha256}' "$origin" > "$proof/python-compatibility.json"
