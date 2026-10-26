#!/usr/bin/env bash
set -euo pipefail

: "CODEX_TEST_ORAS:?Set CODEX_TEST_ORAS to the real ORAS executable}"
: "TMPDIR:?Set TMPDIR to owned test storage}"
script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fixture="$(mktemp -d "$TMPDIR/ghcr-cache-test.XXXXXX")"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/source target" "$fixture/restored target" "$fixture/temporary"
export RUNNER_TEMP="$fixture/temporary"
export CODEX_TEST_ORAS
cat > "$fixture/bin/oras" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${CODEX_TEST_ORAS_FAIL_RESOLVE:-}" == 1 && "$1" == resolve ]]; then
  printf 'Injected registry transport failure\n' >&2
  exit 42
fi
operation="$1"
shift
exec "$CODEX_TEST_ORAS" "$operation" --oci-layout "$@"
WRAPPER
chmod +x "$fixture/bin/oras"
export PATH="$fixture/bin:$PATH"
repository="$fixture/registry"
"$CODEX_TEST_ORAS" push --oci-layout "$repository:seed" --artifact-type application/vnd.codex.cache-test.v1
target=x86_64-unknown-linux-musl
identity="$(printf fixture-inputs | sha256sum | cut -d ' ' -f 1)"
tag="cargo-v3-$target-$identity"
cache="$script_root/ghcr-cargo-target-cache.sh"

set +e
bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 3

release="$fixture/source target/$target/release"
mkdir -p "$release/deps"
printf dependency > "$release/deps/libfixture.rlib"
node - "$release/deps/libfixture.rlib" <<'JS'
const fs = require('node:fs');
const timestamp = new Date(1700000000123);
fs.utimesSync(process.argv[2], timestamp, timestamp);
JS
printf executable > "$release/deps/codex-fixture"
ln "$release/deps/codex-fixture" "$release/codex"

bash "$cache" push-partial "$repository" "$tag" "$fixture/source target" rust-v0.164.0
set +e
bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 3
bash "$cache" pull-partial "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
cmp "$release/deps/libfixture.rlib" "$fixture/restored target/$target/release/deps/libfixture.rlib"
cmp "$release/codex" "$fixture/restored target/$target/release/codex"

bash "$cache" push "$repository" "$tag" "$fixture/source target" rust-v0.164.0
bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
restored="$fixture/restored target/$target/release"
cmp "$release/deps/libfixture.rlib" "$restored/deps/libfixture.rlib"
node - "$release/deps/libfixture.rlib" "$restored/deps/libfixture.rlib" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const original = fs.statSync(process.argv[2], { bigint: true }).mtimeNs;
const restored = fs.statSync(process.argv[3], { bigint: true }).mtimeNs;
assert.notEqual(original % 1000000000n, 0n, 'fixture must exercise a fractional timestamp');
assert.equal(restored, original, 'cache round trip must preserve the exact modification timestamp');
JS
cmp "$release/deps/codex-fixture" "$restored/deps/codex-fixture"
cmp "$release/codex" "$restored/codex"

printf partial-update > "$release/deps/libfixture.rlib"
bash "$cache" push-partial "$repository" "$tag" "$fixture/source target" rust-v0.164.0
bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
test "$(cat "$restored/deps/libfixture.rlib")" = dependency
bash "$cache" pull-partial "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
test "$(cat "$restored/deps/libfixture.rlib")" = partial-update

changed_identity="$(printf changed-fixture-inputs | sha256sum | cut -d ' ' -f 1)"
set +e
bash "$cache" pull "$repository" "cargo-v3-$target-$changed_identity" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 3

set +e
CODEX_TEST_ORAS_FAIL_RESOLVE=1 bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 42
printf 'PASS: missing cache, real OCI archive round trip, exact timestamp and hard-link preservation, executable preservation, partial-state isolation, changed-input miss, transport failure propagation\n'
