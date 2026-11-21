#!/usr/bin/env bash
# Save and restore the exact bundle in the release executable cache OCI repository.
set -euo pipefail

operation="${1:?Expected capture, restore, or publish}"
case "$operation" in
  capture|restore|publish) ;;
  *) printf 'Invalid final output cache operation\n' >&2; exit 2 ;;
esac

metadata="$RUNNER_TEMP/manifest-patch-repo"
bundle=(bash "${CODEX_TOOLING:?}" package-final-output-bundle)
archive=final-outputs.tar.gz
capture="$RUNNER_TEMP/package-final-output-capture"
media_type=application/vnd.codex.package-final-output.v1.tar+gzip
artifact_type=application/vnd.codex.package-final-output.v1
tag_prefix=cargo-v3-x86_64-unknown-linux-musl-pkg-
dependency_tag="${CACHE_TAG:-}"
tag_suffix="${dependency_tag#"$tag_prefix"}"
cache_tag="${tag_prefix}final-v1-${tag_suffix}"
status=unavailable
stage=''
path=''
verified_digest=''
started="$SECONDS"

# Discard credentials and staging files and report the outcome. A verified
# restore retains only its private directory for the later install step.
finish() {
  if [[ -n "$stage" ]]; then rm -rf -- "$stage"; fi
  printf 'final_output_cache_result=%s transport_seconds=%s' "$status" "$((SECONDS-started))"
  if [[ -n "$verified_digest" ]]; then printf ' digest=%s' "$verified_digest"; fi
  printf '\n'
  if [[ -n "${GITHUB_OUTPUT:-}" && "$operation" != capture ]]; then
    printf 'result=%s\n' "$status" >> "$GITHUB_OUTPUT"
    if [[ -n "$path" ]]; then printf 'path=%s\n' "$path" >> "$GITHUB_OUTPUT"; fi
  fi
  if [[ "$status" == unavailable ]]; then
    printf '::warning::Final output cache %s unavailable\n' "$operation"
  fi
}
trap finish EXIT

if [[ "${RUNNER_OS:-}:${TARGET:-}" != Linux:x86_64-unknown-linux-musl ||
      "$dependency_tag" != "$tag_prefix"* || ! "$tag_suffix" =~ ^[0-9a-f]{64}$ ]]; then
  exit 0
fi

if ! PACKAGE_FINAL_SOURCE="$(git rev-parse HEAD)"; then exit 0; fi
export PACKAGE_FINAL_SOURCE
if ! selected="$(bash .github/scripts/codex-package-operations.sh select-binaries \
  --runner-os "$RUNNER_OS" --build-mode release --build-system cargo)"; then
  exit 0
fi
read -r -a binaries <<< "$selected"

if [[ "$operation" == capture ]]; then
  result="${CODEX_PACKAGE_BUILD_RESULT:-}"
  if ! [[ -f "$result/completed" && "$(<"$result/completed")" == true &&
          "$(<"$result/format-version")" == 1 && "$(<"$result/operation")" == build_binaries &&
          "$(<"$result/build-mode")" == release && "$(<"$result/target")" == "$TARGET" &&
          "$(<"$result/runner-os")" == "$RUNNER_OS" && "$(<"$result/binaries")" == "$selected" &&
          "$(<"$result/source-commit")" == "$PACKAGE_FINAL_SOURCE" ]]; then
    exit 0
  fi
  stage="$(mktemp -d "$RUNNER_TEMP/package-final-output-capture.XXXXXX")" || exit 0
  if "${bundle[@]}" capture "$stage/$archive" "$CARGO_TARGET_DIR/$TARGET/release" bwrap "${binaries[@]}" &&
    printf '%s\n' "$dependency_tag:$PACKAGE_FINAL_SOURCE" > "$stage/identity" &&
    [[ ! -e "$capture" && ! -L "$capture" ]] && mv -T -- "$stage" "$capture"; then
    status=captured
  fi
  exit 0
fi

package="$(cat "$metadata/.github/package-final-output-cache-package")" || exit 0
owner="${GITHUB_REPOSITORY_OWNER:-}"
owner="${owner,,}"
if ! [[ "$owner" =~ ^[a-z0-9_-]+$ && "$package" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then exit 0; fi
repository="ghcr.io/$owner/$package"
reference="$repository:$cache_tag"
stage="$(mktemp -d "$RUNNER_TEMP/package-final-output.XXXXXX")" || exit 0
config=(--registry-config "$stage/auth.json")
if ! printf '%s' "${GHCR_TOKEN:-}" | oras login ghcr.io --username "${GITHUB_ACTOR:-}" \
  --password-stdin "${config[@]}"; then
  exit 0
fi
unset GHCR_TOKEN

if [[ "$operation" == publish ]]; then
  upstream_tag="${UPSTREAM_TAG:-}"
  if ! [[ "$upstream_tag" =~ ^rust-v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then exit 0; fi
  # Attach both tags from the local manifest, since a pushed tag may not resolve immediately.
  release_tag="cargo-release-$(printf '%s' "$cache_tag" | sha256sum | cut -d ' ' -f 1)-$upstream_tag"
  publication_reference="$repository:$release_tag,$cache_tag"
  printf 'Final output cache operation=push reference=%s\n' "$reference"
  if [[ -f "$capture/$archive" && ! -L "$capture/$archive" && -f "$capture/identity" &&
        "$(<"$capture/identity")" == "$dependency_tag:$PACKAGE_FINAL_SOURCE" ]] &&
    (cd "$capture" && oras push "${config[@]}" --artifact-type "$artifact_type" \
      --format json "$publication_reference" "$archive:$media_type") > "$stage/push.json" &&
    verified_digest="$(jq -er --arg repo "$repository" '
      select(.digest | test("^sha256:[0-9a-f]{64}$")) |
      select(.reference == ($repo + "@" + .digest)) | .digest
    ' "$stage/push.json")"; then
    status=saved
  fi
  exit 0
fi

printf 'Final output cache operation=pull reference=%s\n' "$reference"
if ! digest="$(oras resolve "${config[@]}" "$reference" 2>"$stage/resolve.err")"; then
  if grep -Eqi 'not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|404 Not Found' "$stage/resolve.err"; then
    status=miss
  fi
  exit 0
fi
if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  ! oras manifest fetch "${config[@]}" --output "$stage/manifest.json" "$repository@$digest" ||
  ! printf '%s  %s\n' "${digest#sha256:}" "$stage/manifest.json" | sha256sum --check --status; then
  exit 0
fi

# Fetch the selected blob directly into private scratch. OCI archive filenames
# and arbitrary layers never control where the registry client writes files.
if ! layer="$(jq -er --arg artifact "$artifact_type" --arg media "$media_type" '
  select(.schemaVersion == 2 and .artifactType == $artifact and (.layers | length == 1))
  | .layers[0]
  | select(.mediaType == $media and (.digest | test("^sha256:[0-9a-f]{64}$")) and
           (.size | type == "number") and .size == (.size | floor) and
           .size > 0 and .size <= 8589934592)
  | [.digest, .size] | @tsv
' "$stage/manifest.json")"; then
  exit 0
fi
read -r blob size <<< "$layer"
if ! oras blob fetch "${config[@]}" --output "$stage/$archive" "$repository@$blob" ||
  [[ "$(stat -c %s -- "$stage/$archive")" != "$size" ]] ||
  ! printf '%s  %s\n' "${blob#sha256:}" "$stage/$archive" | sha256sum --check --status; then
  exit 0
fi
if "${bundle[@]}" restore "$stage/$archive" "$stage/verified" "$digest" bwrap "${binaries[@]}" &&
  verified="$(mktemp -d "$RUNNER_TEMP/package-final-output-verified.XXXXXX")"; then
  if mv -T -- "$stage/verified" "$verified/payload"; then
    path="$verified/payload"
    status=hit
    verified_digest="$digest"
    jq -r '"final_output_cache_producer_run_id=\(.producer_run_id) producer_run_attempt=\(.producer_run_attempt)"' \
      "$path/provenance.json"
  else
    rmdir -- "$verified"
  fi
fi
