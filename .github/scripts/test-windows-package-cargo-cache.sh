#!/usr/bin/env bash
# Exercise staged cache validation and package output removal with a local transport fixture.
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
export RUNNER_TEMP
RUNNER_TEMP="$(mktemp -d)"
trap 'rm -rf -- "$RUNNER_TEMP"' EXIT
metadata="$RUNNER_TEMP/manifest-patch-repo/.github"
mkdir -p "$metadata/scripts" "$RUNNER_TEMP/bin"
cp "$script_dir"/package-cargo-cache.sh "$metadata/scripts/"
cp "$script_dir"/windows-package-*.sh "$metadata/scripts/"
printf 'fixture-package\n' > "$metadata/cargo-cache-package"
cat > "$RUNNER_TEMP/bin/oras" <<'SHIM'
#!/usr/bin/env bash
cat >/dev/null
SHIM
chmod +x "$RUNNER_TEMP/bin/oras"
cat > "$metadata/scripts/ghcr-cargo-target-cache.sh" <<'TRANSPORT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == pull ]]; then
  cp -a "$CACHE_FIXTURE/." "$4"
else
  cp -a "$4/." "$CACHE_FIXTURE"
fi
TRANSPORT
mkdir -p "$RUNNER_TEMP/source"
cd "$RUNNER_TEMP/source"
export PATH="$RUNNER_TEMP/bin:$PATH" RUNNER_OS=Windows RUNNER_ARCH=X64 TARGET=x86_64-pc-windows-msvc
export GITHUB_REPOSITORY_OWNER=fixture GITHUB_ACTOR=fixture GHCR_TOKEN=fixture UPSTREAM_TAG=rust-v0.167.0
printf -v digest '%064d' 0
export CACHE_TAG="cargo-v3-$TARGET-pkg-$digest"
export GITHUB_STEP_SUMMARY="$RUNNER_TEMP/summary" GITHUB_OUTPUT="$RUNNER_TEMP/output"
export CARGO_TARGET_DIR="$RUNNER_TEMP/cargo" CACHE_FIXTURE="$RUNNER_TEMP/archive"
export CODEX_PACKAGE_BUILD_RESULT="$RUNNER_TEMP/build-result"
mkdir "$CODEX_PACKAGE_BUILD_RESULT"
printf 'codex codex-app-server\n' > "$CODEX_PACKAGE_BUILD_RESULT/binaries"
release="$CARGO_TARGET_DIR/$TARGET/release"
archived="$CACHE_FIXTURE/$TARGET/release"
mkdir -p "$release/deps" "$release/build/fixture/out" "$CACHE_FIXTURE"
touch "$release/deps/libretained.rlib" "$release/build/fixture/out/keep.exe"
for binary in codex codex-app-server; do
  touch "$release/$binary.exe" "$release/$binary.pdb" "$release/${binary//-/_}.pdb"
  ln "$release/$binary.exe" "$release/deps/$binary-hashed.exe"
  ln "$release/$binary.pdb" "$release/deps/symbol-alias-$binary.pdb"
  touch "$release/deps/${binary//-/_}-hashed.pdb"
done

bash "$script_dir/package-cargo-cache.sh" save
grep -Fx 'result=saved' "$GITHUB_OUTPUT"
test -f "$archived/deps/libretained.rlib"
test -f "$archived/build/fixture/out/keep.exe"
test -z "$(find "$archived/deps" \( -iname '*.exe' -o -iname '*.pdb' \) -print)"

test ! -e "$archived/codex.exe"
test ! -e "$archived/codex-app-server.pdb"
test ! -e "$archived/codex_app_server.pdb"

rm -rf -- "$CARGO_TARGET_DIR"
mkdir "$CARGO_TARGET_DIR"
touch "$archived/deps/CODEX_APP_SERVER-deadbeef.PDB"
bash "$script_dir/package-cargo-cache.sh" restore
test "$(tail -n 1 "$GITHUB_OUTPUT")" = 'result=unavailable'
test -z "$(ls -A "$CARGO_TARGET_DIR")"

rm -- "$archived/deps/CODEX_APP_SERVER-deadbeef.PDB"
bash "$script_dir/package-cargo-cache.sh" restore
test "$(tail -n 1 "$GITHUB_OUTPUT")" = 'result=hit'
test -f "$release/deps/libretained.rlib"
