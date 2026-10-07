#!/usr/bin/env bash

# Resolves the upstream checkout and publishes its cache association.
set -euo pipefail

if [[ -z "${UPSTREAM_REF}" ]]; then
  UPSTREAM_REF="$(cat "${GITHUB_WORKSPACE}/.github/upstream-ref")"
  test -n "${UPSTREAM_REF}"
fi

repo_url="https://github.com/${UPSTREAM_REPO}.git"
rm -rf "${CHECKOUT_PATH}"

if git -c core.autocrlf=false init "${CHECKOUT_PATH}" >/dev/null 2>&1 \
  && git -C "${CHECKOUT_PATH}" remote add origin "${repo_url}" \
  && git -C "${CHECKOUT_PATH}" config core.autocrlf false \
  && git -C "${CHECKOUT_PATH}" -c core.autocrlf=false fetch --depth 1 origin "${UPSTREAM_REF}" \
  && git -C "${CHECKOUT_PATH}" checkout --detach FETCH_HEAD; then
  :
elif [[ "${FALLBACK_TO_DEFAULT_BRANCH}" == "true" ]]; then
  echo "Falling back to the upstream default branch because ${UPSTREAM_REF} could not be fetched."
  UPSTREAM_REF=unreleased
  rm -rf "${CHECKOUT_PATH}"
  git -c core.autocrlf=false clone --depth 1 "${repo_url}" "${CHECKOUT_PATH}"
else
  echo "ERROR: failed to clone ${UPSTREAM_REPO} at ${UPSTREAM_REF}" >&2
  exit 1
fi

git -C "${CHECKOUT_PATH}" config core.autocrlf false

actual_commit="$(git -C "${CHECKOUT_PATH}" rev-parse HEAD)"
if [[ -n "${EXPECTED_UPSTREAM_COMMIT}" && "${actual_commit}" != "${EXPECTED_UPSTREAM_COMMIT}" ]]; then
  echo "ERROR: ${UPSTREAM_REF} resolved to ${actual_commit}, expected ${EXPECTED_UPSTREAM_COMMIT}." >&2
  exit 1
fi

: "${CODEX_UNLEASHED_TOOLING:?CODEX_UNLEASHED_TOOLING is required; run setup-tooling first}"
cache_prefix="$("${CODEX_UNLEASHED_TOOLING}" cache-prefix --upstream-ref "$UPSTREAM_REF")"
echo "CACHE_UPSTREAM_PREFIX=$cache_prefix" >> "$GITHUB_ENV"
