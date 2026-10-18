#!/usr/bin/env bash
# Keeps product Bazel state separate from the hosted runner's checkout-management server.
set -euo pipefail

exec "${BUILDBUDDY_BAZEL_BINARY:?}" --noworkspace_rc "--bazelrc=${CI_BUILD_ROOT:?}/product.bazelrc" \
  "--bazelrc=${BUILDBUDDY_BAZEL_WORKSPACE:?}/.bazelrc" \
  "--output_user_root=${BAZEL_OUTPUT_USER_ROOT:?}" "$@"
