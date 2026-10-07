#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: verify-release.sh <release-dir> [options]

Verifies a codex-unleashed release directory against its release manifest.

Options:
  --manifest <path>          Override the manifest path. Default: <release-dir>/release-manifest.json
  --patch-repo <path>        Patch repository root. Default: repository root containing this script
  --upstream-checkout <path> Verify the upstream checkout HEAD matches the manifest commit
  --verify-attestations      Verify release assets against GitHub attestations using gh
  -h, --help                 Show this help text
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

release_dir="$(cd "${1}" && pwd)"
shift

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest_path="${release_dir}/release-manifest.json"
patch_repo="${repo_root}"
upstream_checkout=""
verify_attestations=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      manifest_path="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
      shift 2
      ;;
    --patch-repo)
      patch_repo="$(cd "$2" && pwd)"
      shift 2
      ;;
    --upstream-checkout)
      upstream_checkout="$(cd "$2" && pwd)"
      shift 2
      ;;
    --verify-attestations)
      verify_attestations=1
      shift
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ ! -f "${manifest_path}" ]]; then
  echo "ERROR: manifest not found: ${manifest_path}" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required" >&2
  exit 1
fi

checksum_file_rel="$(jq -r '.verification.consolidated_checksums' "${manifest_path}")"
if [[ -z "${checksum_file_rel}" || "${checksum_file_rel}" == "null" ]]; then
  echo "ERROR: manifest is missing verification.consolidated_checksums" >&2
  exit 1
fi
checksum_file="${release_dir}/${checksum_file_rel}"

if [[ ! -f "${checksum_file}" ]]; then
  echo "ERROR: consolidated checksum file not found: ${checksum_file}" >&2
  exit 1
fi

(
  cd "${release_dir}"
  sha256sum -c "${checksum_file_rel}"
)

while IFS=$'\t' read -r relative_path expected_hash; do
  artifact_path="${release_dir}/${relative_path}"
  if [[ ! -f "${artifact_path}" ]]; then
    echo "ERROR: manifest artifact missing: ${relative_path}" >&2
    exit 1
  fi
  actual_hash="$(sha256sum "${artifact_path}" | awk '{print $1}')"
  if [[ "${actual_hash}" != "${expected_hash}" ]]; then
    echo "ERROR: manifest hash mismatch for ${relative_path}" >&2
    printf 'Expected hash: %q\nActual hash: %q\n' "${expected_hash}" "${actual_hash}" >&2
    exit 1
  fi
done < <(jq -r '.artifacts[] | [.path, .sha256] | @tsv' "${manifest_path}")

while IFS=$'\t' read -r relative_path expected_hash; do
  patch_path="${patch_repo}/${relative_path}"
  if [[ ! -f "${patch_path}" ]]; then
    echo "ERROR: patch missing from repository: ${relative_path}" >&2
    exit 1
  fi
  actual_hash="$(sha256sum "${patch_path}" | awk '{print $1}')"
  if [[ "${actual_hash}" != "${expected_hash}" ]]; then
    echo "ERROR: patch hash mismatch for ${relative_path}" >&2
    printf 'Expected hash: %q\nActual hash: %q\n' "${expected_hash}" "${actual_hash}" >&2
    exit 1
  fi
done < <(jq -r '.patches[] | [.path, .sha256] | @tsv' "${manifest_path}")

if [[ -n "${upstream_checkout}" ]]; then
  expected_upstream_commit="$(jq -r '.upstream.commit' "${manifest_path}")"
  actual_upstream_commit="$(git -C "${upstream_checkout}" rev-parse HEAD)"
  if [[ "${actual_upstream_commit}" != "${expected_upstream_commit}" ]]; then
    echo "ERROR: upstream checkout HEAD does not match manifest commit" >&2
    echo "Expected: ${expected_upstream_commit}" >&2
    echo "Actual:   ${actual_upstream_commit}" >&2
    exit 1
  fi
fi

if [[ "${verify_attestations}" == "1" ]]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: gh is required for attestation verification" >&2
    exit 1
  fi
  patch_repository="$(jq -r '.release.patch_repository' "${manifest_path}")"
  while IFS= read -r relative_path; do
    gh attestation verify "${release_dir}/${relative_path}" --repo "${patch_repository}" >/dev/null
  done < <(jq -r '.artifacts[] | .path' "${manifest_path}")
fi

echo "Release verification succeeded for ${release_dir}"
