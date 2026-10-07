#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: reproduce-release.sh <release-manifest.json> [options]

Rebuilds a Codex Unleashed release from the upstream commit and the exact
patches recorded in the manifest. The resulting package archive is checked
against the manifest's published SHA-256 digest.

Options:
  --target <rust-target>  Target to build (defaults to the manifest target)
  --output-dir <dir>      Directory for the reproduced package
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

manifest_path="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
shift
target=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      target="${2:?--target requires a value}"
      shift 2
      ;;
    --output-dir)
      output_dir="${2:?--output-dir requires a value}"
      shift 2
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

for command in git jq; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "ERROR: required command is not installed: $command" >&2
    exit 1
  fi
done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "ERROR: sha256sum or shasum is required" >&2
    exit 1
  fi
}

manifest_dir="$(dirname "$manifest_path")"
patch_repository="$(jq -er '.release.patch_repository' "$manifest_path")"
workflow_sha="$(jq -er '.workflow.sha' "$manifest_path")"
upstream_repository="$(jq -er '.upstream.repository' "$manifest_path")"
upstream_tag="$(jq -r '.upstream.tag // empty' "$manifest_path")"
upstream_commit="$(jq -er '.upstream.commit' "$manifest_path")"
build_number="$(jq -r '.release.build_number // empty' "$manifest_path")"

if [[ -z "$target" ]]; then
  target="$(jq -er '.release.supported_targets[0]' "$manifest_path")"
fi
if [[ -z "$output_dir" ]]; then
  output_dir="$PWD/reproduced-${target}"
fi
output_dir="$(mkdir -p "$output_dir" && cd "$output_dir" && pwd)"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/codex-reproduce.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
patch_checkout="$work_dir/patch-repository"
upstream_checkout="$work_dir/upstream"

clone_at() {
  local repository="$1"
  local ref="$2"
  local destination="$3"
  git clone --filter=blob:none --no-checkout \
    "https://github.com/${repository}.git" "$destination" >/dev/null
  git -C "$destination" fetch --depth=1 origin "$ref" >/dev/null
  git -C "$destination" checkout --detach FETCH_HEAD >/dev/null
}

echo "Fetching patch repository ${patch_repository}@${workflow_sha}"
clone_at "$patch_repository" "$workflow_sha" "$patch_checkout"
if [[ "$(git -C "$patch_checkout" rev-parse HEAD)" != "$workflow_sha" ]]; then
  echo "ERROR: fetched patch repository is not the manifest's workflow commit" >&2
  exit 1
fi

echo "Verifying patch hashes"
while IFS=$'\t' read -r relative_path expected_hash; do
  patch_path="$patch_checkout/$relative_path"
  if [[ ! -f "$patch_path" ]]; then
    echo "ERROR: patch is missing from ${patch_repository}: ${relative_path}" >&2
    exit 1
  fi
  actual_hash="$(sha256_file "$patch_path")"
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    echo "ERROR: patch hash mismatch for ${relative_path}" >&2
    exit 1
  fi
done < <(jq -er '.patches[] | [.path, .sha256] | @tsv' "$manifest_path")

if [[ -n "$upstream_tag" ]]; then
  echo "Fetching upstream ${upstream_repository}@${upstream_tag}"
  clone_at "$upstream_repository" "$upstream_tag" "$upstream_checkout"
else
  echo "Fetching upstream ${upstream_repository}@${upstream_commit}"
  clone_at "$upstream_repository" "$upstream_commit" "$upstream_checkout"
fi
actual_upstream_commit="$(git -C "$upstream_checkout" rev-parse HEAD)"
if [[ "$actual_upstream_commit" != "$upstream_commit" ]]; then
  echo "ERROR: upstream ref does not resolve to the manifest commit" >&2
  echo "Expected: $upstream_commit" >&2
  echo "Actual:   $actual_upstream_commit" >&2
  exit 1
fi

echo "Applying recorded patches"
bash "$patch_checkout/scripts/apply-patches.sh" "$upstream_checkout"

build_dir="$work_dir/build"
export CODEX_UNLEASHED_BUILD_NUMBER="$build_number"
export PATCH_REPOSITORY="$patch_repository"
export UPSTREAM_REPOSITORY="$upstream_repository"
export UPSTREAM_TAG="$upstream_tag"
export UPSTREAM_SOURCE_SHA="$upstream_commit"
export UPSTREAM_TARGET="$target"
echo "Building target ${target}"
bash "$patch_checkout/scripts/build-release.sh" "$upstream_checkout" "$build_dir"

release_dir="$upstream_checkout/codex-rs/target/$target/release"
case "$target" in
  *linux-musl)
    for binary in codex codex-code-mode-host codex-responses-api-proxy; do
      if [[ -f "$release_dir/$binary" ]]; then
        strip --strip-debug --strip-unneeded "$release_dir/$binary"
      fi
    done
    ;;
  *apple-darwin)
    for binary in codex codex-code-mode-host codex-responses-api-proxy; do
      if [[ -f "$release_dir/$binary" ]]; then
        if [[ ! -d "$release_dir/$binary.dSYM" ]] && command -v dsymutil >/dev/null 2>&1; then
          dsymutil "$release_dir/$binary" -o "$release_dir/$binary.dSYM"
        fi
        strip -S -x "$release_dir/$binary"
      fi
    done
    ;;
esac

echo "Packaging target ${target}"
bash "$patch_checkout/.github/scripts/build-codex-package-archive.sh" \
  --target "$target" \
  --bundle primary \
  --entrypoint-dir "$release_dir" \
  --archive-dir "$output_dir" \
  --zsh-manifest "$patch_checkout/scripts/codex_package/codex-zsh" \
  --rg-manifest "$upstream_checkout/scripts/codex_package/rg"

archive="$output_dir/codex-package-${target}.tar.gz"
if [[ ! -f "$archive" ]]; then
  echo "ERROR: reproduced package was not created: $archive" >&2
  exit 1
fi
expected_hash="$(jq -er --arg path "codex-package-${target}.tar.gz" '.artifacts[] | select(.path == $path) | .sha256' "$manifest_path")"
actual_hash="$(sha256_file "$archive")"
if [[ "$actual_hash" != "$expected_hash" ]]; then
  echo "ERROR: reproduced archive does not match the published digest" >&2
  echo "Expected: $expected_hash" >&2
  echo "Actual:   $actual_hash" >&2
  exit 1
fi

echo "Reproduction succeeded: $archive"
echo "SHA-256: $actual_hash"
