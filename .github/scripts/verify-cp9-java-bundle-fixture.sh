#!/usr/bin/env bash
# Verify the Java tooling against the exact bundle from the frozen Python pilot.
set -euo pipefail
umask 077
scratch=$(mktemp -d "$RUNNER_TEMP/cp9-java-bundle-fixture.XXXXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
export TARGET=x86_64-unknown-linux-musl
export CACHE_TAG=cargo-v3-x86_64-unknown-linux-musl-pkg-1d61fb231da55d396374b9ed10a817c08e38b166f9f0d4ff2de2825e79934c58
export PACKAGE_FINAL_SOURCE=7135e17bec4434818c4b4336bf88817153441840
registry=ghcr.io/holdmyspot-com/cargo-cache-cp9-linux-manifest-355bbca
digest=sha256:957cc0a2b4a94becf891056f81ab08dc0412b38c27beb1d51c2444b37ec9b87d
producer=44912959295
inventory_hash=4359c97e6f85e930c23e0a492d8ac9196d107ff69cb9c1262f95f5fcc890ae94
manifest_hash=4f06fc5e34d5bdc98951934f9d0004898f46efc9598ee85b0371e9b3f37834c2
frozen_hash=e1ece66fbe69ab0e0921ac831cb46703170e40eb
frozen_dir="$GITHUB_WORKSPACE/frozen-bundle-reference"
test "$(git -C "$frozen_dir" rev-parse HEAD)" = "$frozen_hash"
reference="$frozen_dir/.github/scripts/package-final-output-bundle.py"
names=(bwrap codex codex-app-server codex-code-mode-host codex-exec-server)
tooling=(bash "$CODEX_TOOLING" package-final-output-bundle)

config=(--registry-config "$scratch/auth.json")
printf '%s' "$GHCR_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin "${config[@]}"
unset GHCR_TOKEN
oras manifest fetch "${config[@]}" --output "$scratch/oci.json" "$registry@$digest"
printf '%s  %s\n' "${digest#sha256:}" "$scratch/oci.json" | sha256sum --check --status
layer=$(jq -er '
  select(.schemaVersion == 2 and .artifactType == "application/vnd.codex.package-final-output.v1" and (.layers | length == 1))
  | .layers[0]
  | select(.mediaType == "application/vnd.codex.package-final-output.v1.tar+gzip" and
    (.digest | test("^sha256:[0-9a-f]{64}$")) and .size == 940196278)
  | .digest
' "$scratch/oci.json")
oras blob fetch "${config[@]}" --output "$scratch/frozen.tar.gz" "$registry@$layer"
rm -f -- "$scratch/auth.json"
[[ "$(stat -c '%s' "$scratch/frozen.tar.gz")" == 940196278 ]]
printf '%s  %s\n' "${layer#sha256:}" "$scratch/frozen.tar.gz" | sha256sum --check --status

if PACKAGE_FINAL_SOURCE=0000000000000000000000000000000000000000 "${tooling[@]}" restore "$scratch/frozen.tar.gz" \
    "$scratch/wrong-source" "$digest" "${names[@]}"; then
  printf 'The Java tool accepted a bundle from the wrong source.\n' >&2
  exit 1
fi
test ! -e "$scratch/wrong-source"

"${tooling[@]}" restore "$scratch/frozen.tar.gz" "$scratch/java-restored" "$digest" "${names[@]}"
receipt="$scratch/java-restored/provenance.json"
jq -e --arg digest "$digest" --arg producer "$producer" '
  .oci_digest == $digest and .producer_run_id == $producer and .producer_run_attempt == "1" and
  .producer_repository == "holdmyspot-com/codex-unleashed"
' "$receipt" >/dev/null
test "$(jq -Sc 'del(.oci_digest)' "$receipt" | sha256sum | cut -d' ' -f1)" = "$manifest_hash"
"${tooling[@]}" install --restored-dir "$scratch/java-restored" --release-dir "$scratch/java-installed" \
  --dependency-tag "$CACHE_TAG" --source-commit "$PACKAGE_FINAL_SOURCE" --binaries "${names[@]}"
rm -rf -- "$scratch/java-restored"
rm -- "$scratch/frozen.tar.gz"

"${tooling[@]}" capture "$scratch/java.tar.gz" "$scratch/java-installed" "${names[@]}"
portable_digest=sha256:$(sha256sum "$scratch/java.tar.gz" | cut -d' ' -f1)
python3 "$reference" restore "$scratch/java.tar.gz" "$scratch/python-restored" "$portable_digest" "${names[@]}"
reciprocal="$scratch/python-restored/provenance.json"
jq -e --arg digest "$portable_digest" --arg run "$GITHUB_RUN_ID" --arg attempt "$GITHUB_RUN_ATTEMPT" '
  .oci_digest == $digest and .producer_run_id == $run and .producer_run_attempt == $attempt
' "$reciprocal" >/dev/null
test "$(jq -Sc '.files' "$reciprocal" | sha256sum | cut -d' ' -f1)" = "$inventory_hash"
for binary in "${names[@]}"; do
  cmp "$scratch/java-installed/$binary" "$scratch/python-restored/bin/$binary"
  test "$(stat -c '%a' "$scratch/java-installed/$binary")" = "$(stat -c '%a' "$scratch/python-restored/bin/$binary")"
done
jq -n --arg frozen_ref "$frozen_hash" --arg src "$PACKAGE_FINAL_SOURCE" --arg tag "$CACHE_TAG" \
  --arg digest "$digest" --arg layer "$layer" --arg producer "$producer" --arg inventory "$inventory_hash" \
  --arg verifier "$GITHUB_SHA" --arg run "$GITHUB_RUN_ID" --arg reciprocal "$portable_digest" '{
    frozen_ref: $frozen_ref, source_commit: $src, dependency_tag: $tag, frozen_oci_digest: $digest,
    frozen_blob_digest: $layer, frozen_producer_run: $producer, frozen_producer_attempt: "1",
    inventory_sha256: $inventory, verifier_ref: $verifier, verification_run: $run,
    java_capture_sha256: $reciprocal,
    java_install_matches_frozen: true, frozen_python_reads_java_output: true, wrong_source_rejected: true
  }' > "$RUNNER_TEMP/cp9-java-final-output-fixture.json"
