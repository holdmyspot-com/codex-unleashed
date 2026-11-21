#!/usr/bin/env bash
# Hosted-only harness around the exact production helper.
set -euo pipefail

metadata="$RUNNER_TEMP/manifest-patch-repo/.github/scripts"
# shellcheck disable=SC1090
source <(sed '/^operation=/,$d' "$metadata/macos-sealed-voice-cache.sh")
candidate="$RUNNER_TEMP/voice-cache-incoming/1/approved.json"
origin="$RESULT_DIR/evidence/voice-cache-origin.json"
inputs="$RUNNER_TEMP/voice-cache-inputs.json"
proof="$RUNNER_TEMP/voice-auto-proof"
case "$PROBE" in
  cold)
    test "$RESTORED" = false
    test ! -f "$candidate" && test ! -f "$origin"
    test -f "$RESULT_DIR/evidence/build.stdout.log"
    ;;
  warm)
    test "$RESTORED" = true
    test -f "$origin" && test -f "$candidate"
    jq -e --slurpfile inputs "$inputs" --arg consumer "$GITHUB_RUN_ID" '
      .inputs == $inputs[0] and .producer.run_id != $consumer and .consumer.run_id == $consumer' "$origin" >/dev/null
    ;;
  corrupt)
    test "$RESTORED" = false
    test -f "$candidate"
    test ! -f "$origin"
    test -f "$RESULT_DIR/evidence/build.stdout.log"
    ;;
esac

mkdir -- "$proof"
verify_fixture "$TARGET" "$(jq -er .version "$inputs")" "$RESULT_DIR" "$inputs" "$proof/native-fixture"
inventory "$RESULT_DIR" "$inputs" | jq -cS . > "$proof/files.json"
wire_producer="$(producer)"
wire_origin=null
wire_approval=null
if [[ -f "$origin" ]]; then
  wire_origin="$(cat "$origin")"
  wire_producer="$(jq -er .producer "$origin")"
fi
if [[ -f "$candidate" ]]; then wire_approval="$(cat "$candidate")"; fi
jq -nS --arg phase "$PROBE" --argjson restored "$RESTORED" --argjson producer "$wire_producer" \
  --argjson consumer "$(producer)" --argjson origin "$wire_origin" --argjson approved "$wire_approval" \
  --arg sha "$(sha256 "$proof/files.json")" --argjson files "$(jq -er length "$proof/files.json")" '
  {phase:$phase,restored:$restored,producer:$producer,consumer:$consumer,origin:$origin,
   approved:$approved,files_sha256:$sha,files:$files,verification:"both native fixture tests passed"}' \
  > "$proof/receipt.json"
