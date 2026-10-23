#!/usr/bin/env bash
# Tests the publisher's command ordering and failure handling without publishing.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fixture="$(mktemp -d "${TMPDIR:?}/release-publisher-test-XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/assets"
touch "$fixture/assets/first asset" "$fixture/assets/second" "$fixture/notes"
export PUBLISH_TEST_LOG="$fixture/calls" PUBLISH_TEST_FAIL_UPLOAD=false
cat > "$fixture/bin/gh" <<'SH'
#!/usr/bin/env bash
# Model gh's documented nonzero exit on upload failure; record requests only.
set -euo pipefail
printf '%s\n' "$2" >> "$PUBLISH_TEST_LOG"
if [[ "$2" == upload ]]; then
  [[ $# == 6 && "$5" == --repo ]]
  if [[ "$PUBLISH_TEST_FAIL_UPLOAD" == true ]]; then
    exit 17
  fi
fi
SH
chmod +x "$fixture/bin/gh"
export PATH="$fixture/bin:$PATH"

bash "$script_dir/publish-github-release.sh" owner/repo tag title \
  "$fixture/notes" "$fixture/assets" true
printf '%s\n' create upload upload edit > "$fixture/expected"
cmp "$fixture/expected" "$PUBLISH_TEST_LOG"

: > "$PUBLISH_TEST_LOG"
export PUBLISH_TEST_FAIL_UPLOAD=true
if bash "$script_dir/publish-github-release.sh" owner/repo tag title \
  "$fixture/notes" "$fixture/assets" false; then
  echo 'An upload failure must stop publication' >&2
  exit 1
fi
printf '%s\n' create upload > "$fixture/expected"
cmp "$fixture/expected" "$PUBLISH_TEST_LOG"

: > "$PUBLISH_TEST_LOG"
mkdir "$fixture/empty"
if bash "$script_dir/publish-github-release.sh" owner/repo tag title \
  "$fixture/notes" "$fixture/empty" false 2> "$fixture/error"; then
  echo 'An empty asset directory must fail before creating a release' >&2
  exit 1
fi
[[ ! -s "$PUBLISH_TEST_LOG" ]]
