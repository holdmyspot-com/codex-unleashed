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
if [[ "${CODEX_TEST_ORAS_FAIL_RESOLVE:-}" == 1 ]]; then
  case "$1" in
    resolve|manifest)
      printf 'Injected registry transport failure\n' >&2
      exit 42
      ;;
  esac
fi
operation="$1"
shift
case "$operation" in
  blob|manifest)
    subcommand="$1"
    shift
    exec "$CODEX_TEST_ORAS" "$operation" "$subcommand" --oci-layout "$@"
    ;;
esac
exec "$CODEX_TEST_ORAS" "$operation" --oci-layout "$@"
WRAPPER
chmod +x "$fixture/bin/oras"
export PATH="$fixture/bin:$PATH"
repository="$fixture/registry"
"$CODEX_TEST_ORAS" push --oci-layout "$repository:seed" --artifact-type application/vnd.codex.cache-test.v1
target=x86_64-unknown-linux-musl
identity="$(printf fixture-inputs | sha256sum | cut -d ' ' -f 1)"
tag="cargo-v3-$target-$identity"
storage_suffix=""
if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
  storage_suffix=-parts-v1
fi
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
if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
  oras manifest fetch "$repository:$tag$storage_suffix-partial" > "$fixture/parts-manifest.json"
  node - "$fixture/parts-manifest.json" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const manifest = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
assert.equal(manifest.artifactType, 'application/vnd.codex-unleashed.cargo-target.v2');
assert.ok(manifest.layers.length >= 2, 'dependencies and executable must be separate compressed parts');
JS
  bash "$cache" push-partial "$repository" "$tag" "$fixture/source target" rust-v0.164.0 > "$fixture/unchanged.log"
  grep -q 'uploaded_archive_bytes=0' "$fixture/unchanged.log"
fi
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
node - "$restored/deps/codex-fixture" "$restored/codex" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
assert.equal(fs.statSync(process.argv[2], {bigint: true}).ino,
             fs.statSync(process.argv[3], {bigint: true}).ino, 'restore must preserve executable hardlinks');
JS

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

seed_tag="cargo-seed-v1-$target-$identity"
next_tag="cargo-v3-$target-$changed_identity"
bash "$cache" push-partial "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0 "$seed_tag"
set +e
bash "$cache" pull "$repository" "$seed_tag" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 3
bash "$cache" pull-partial "$repository" "$seed_tag" "$fixture/restored target" rust-v0.164.0
test "$(cat "$restored/deps/libfixture.rlib")" = partial-update
test "$(oras resolve "$repository:$next_tag$storage_suffix-partial")" = "$(oras resolve "$repository:$seed_tag$storage_suffix-partial")"

printf verified-seed > "$release/deps/libfixture.rlib"
bash "$cache" push "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0 "$seed_tag"
seed_digest="$(oras resolve "$repository:$seed_tag$storage_suffix")"
test "$(oras resolve "$repository:$next_tag$storage_suffix")" = "$seed_digest"
seed_release_identity="$(printf '%s' "$next_tag$storage_suffix" | sha256sum | cut -d ' ' -f 1)"
test "$(oras resolve "$repository:cargo-release-$seed_release_identity-rust-v0.164.0")" = "$seed_digest"

printf newer-partial > "$release/deps/libfixture.rlib"
bash "$cache" push-partial "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0 "$seed_tag"
test "$(oras resolve "$repository:$seed_tag$storage_suffix")" = "$seed_digest"
bash "$cache" pull "$repository" "$seed_tag" "$fixture/restored target" rust-v0.164.0
test "$(cat "$restored/deps/libfixture.rlib")" = verified-seed
bash "$cache" pull-partial "$repository" "$seed_tag" "$fixture/restored target" rust-v0.164.0
test "$(cat "$restored/deps/libfixture.rlib")" = newer-partial

for invalid_seed_tag in '' "cargo-seed-v1-aarch64-unknown-linux-musl-$identity" "$tag"; do
  set +e
  bash "$cache" push "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0 "$invalid_seed_tag"
  result=$?
  set -e
  test "$result" = 2
done
test "$(oras resolve "$repository:$seed_tag$storage_suffix")" = "$seed_digest"

set +e
CODEX_TEST_ORAS_FAIL_RESOLVE=1 bash "$cache" pull "$repository" "$tag" "$fixture/restored target" rust-v0.164.0
result=$?
set -e
test "$result" = 42
if [[ "${CARGO_CACHE_PARTS:-}" == 1 ]]; then
  oras manifest fetch "$repository:$next_tag$storage_suffix-partial" > "$fixture/before-change.json"
  python3 - "$release/deps/libfixture.rlib" <<'PY'
import os
from pathlib import Path
import sys
path = Path(sys.argv[1])
status = path.stat()
path.write_bytes(b'altered-state')
assert path.stat().st_size == status.st_size
os.utime(path, ns=(status.st_atime_ns, status.st_mtime_ns))
PY
  bash "$cache" push-partial "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0
  oras manifest fetch "$repository:$next_tag$storage_suffix-partial" > "$fixture/after-change.json"
  node - "$fixture/before-change.json" "$fixture/after-change.json" <<'JS'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const layers = path => JSON.parse(fs.readFileSync(path, 'utf8')).layers;
const previous = new Map(layers(process.argv[2]).map(layer => [layer.annotations['io.holdmyspot.cargo.group'], layer.digest]));
let changed = 0;
for (const layer of layers(process.argv[3])) {
  const group = layer.annotations['io.holdmyspot.cargo.group'];
  if (group.endsWith('/libfixture.rlib')) {
    assert.notEqual(layer.digest, previous.get(group), 'same-size, same-mtime content changes require a new archive');
    changed++;
  } else {
    assert.equal(layer.digest, previous.get(group), 'unaffected groups must retain their existing blobs');
  }
}
assert.equal(changed, 1);
JS
  rm -- "$release/deps/libfixture.rlib"
  bash "$cache" push-partial "$repository" "$next_tag" "$fixture/source target" rust-v0.164.0
  bash "$cache" pull-partial "$repository" "$next_tag" "$fixture/restored after deletion" rust-v0.164.0
  test ! -e "$fixture/restored after deletion/$target/release/deps/libfixture.rlib"
fi
printf 'PASS: archive bytes/timestamps/links, executable retention, compatible aliases, release association, successful/partial isolation, invalid alias rejection, changed-input miss, transport failure propagation\n'
