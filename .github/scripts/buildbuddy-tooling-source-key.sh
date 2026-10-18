#!/usr/bin/env bash
# Fingerprints the verified runtime's source and configuration, matching setup-tooling's input scope.
set -euo pipefail

git ls-files -z tooling .github scripts patches | LC_ALL=C sort -z \
  | xargs -0 sha256sum | sha256sum | cut -d ' ' -f 1
