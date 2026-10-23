#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name owned test storage}/v8-release-manifest-XXXXXX")"
trap 'rm -rf -- "$fixtures"' EXIT

bash_path="$(command -v bash)"
hash_tools="$fixtures/hash-tools"
mkdir "$hash_tools"
for tool in awk basename dirname shasum; do
  ln -s "$(command -v "$tool")" "$hash_tools/$tool"
done

for scenario in genuine forged missing duplicate crlf crlf-modified forged-cache; do
  root="$fixtures/$scenario"
  release="$root/release"
  target=x86_64-unknown-linux-gnu
  profile=ptrcomp_sandbox_release
  archive="librusty_v8_${profile}_${target}.a.gz"
  binding="src_binding_${profile}_${target}.rs"
  manifest="rusty_v8_${profile}_${target}.sha256"
  trusted="$root/upstream/third_party/v8/rusty_v8_1_2_3_release_manifests.sha256"
  mkdir -p "$release" "$(dirname "$trusted")" "$root/bin" "$root/.github/scripts"
  if [[ -f "$project_root/.github/scripts/verify-v8-release-manifest.sh" ]]; then
    cp "$project_root/.github/scripts/verify-v8-release-manifest.sh" "$root/.github/scripts/"
  fi
  printf 'genuine archive\n' > "$release/$archive"
  printf 'genuine binding\n' > "$release/$binding"
  (cd "$release" && sha256sum "$archive" "$binding") > "$release/$manifest"
  if [[ "$scenario" == crlf || "$scenario" == crlf-modified ]]; then
    sed 's/$/\r/' "$release/$manifest" > "$root/crlf"
    mv "$root/crlf" "$release/$manifest"
  fi
  (cd "$release" && sha256sum "$manifest") > "$trusted"
  expected_status=1
  case "$scenario" in
    genuine|crlf) expected_status=0 ;;
    forged|forged-cache)
      printf 'forged archive\n' > "$release/$archive"
      printf 'forged binding\n' > "$release/$binding"
      (cd "$release" && sha256sum "$archive" "$binding") > "$release/$manifest"
      ;;
    missing) printf '' > "$trusted" ;;
    duplicate) cat "$trusted" > "$root/entry"; cat "$root/entry" >> "$trusted" ;;
    crlf-modified) tr -d '\r' < "$release/$manifest" > "$root/lf"; mv "$root/lf" "$release/$manifest" ;;
  esac
  if [[ "$scenario" == crlf ]]; then
    sed 's/$/\r/' "$trusted" > "$root/trusted-crlf"
    mv "$root/trusted-crlf" "$trusted"
  fi
  if [[ "$scenario" == forged-cache ]]; then
    mkdir -p "$root/.cache/rusty_v8/$target/downloads"
    cp "$release/"* "$root/.cache/rusty_v8/$target/downloads/"
  fi

  # curl -o writes the downloaded response bytes; the fixture supplies release files locally.
  cat > "$root/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -fsSL && "$3" == -o ]]
cp "$RELEASE_FIXTURE/${2##*/}" "$4"
CURL
  cat > "$root/bin/tooling" <<'TOOLING'
#!/usr/bin/env bash
printf '1.2.3\n'
TOOLING
  chmod +x "$root/bin/curl" "$root/bin/tooling"
  awk '/^      run: \|$/ { body=1; next } body { sub(/^        /, ""); print }' \
    "$project_root/.github/actions/setup-rusty-v8/action.yml" > "$root/action.sh"
  status=0
  env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root" GITHUB_ENV="$root/environment" \
    TARGET="$target" CODEX_UNLEASHED_TOOLING="$root/bin/tooling" RELEASE_FIXTURE="$release" \
    bash "$root/action.sh" > "$root/output" 2>&1 || status=$?
  if [[ "$expected_status" == 0 ]]; then
    if [[ "$status" != 0 ]]; then
      printf '%s: authentic manifest was rejected\n' "$scenario" >&2
      cat "$root/output" >&2
      exit 1
    fi
    expected_archive="RUSTY_V8_ARCHIVE=$root/.cache/rusty_v8/$target/inputs/$archive"
    expected_binding="RUSTY_V8_SRC_BINDING_PATH=$root/.cache/rusty_v8/$target/inputs/$binding"
    if [[ "$(cat "$root/environment")" != "$expected_archive"$'\n'"$expected_binding" ]]; then
      printf '%s: expected stable Cargo override paths\n' "$scenario" >&2
      exit 1
    fi
  else
    if [[ "$status" == 0 ]]; then
      printf '%s: unauthenticated manifest was accepted\n' "$scenario" >&2
      exit 1
    fi
    if [[ -s "$root/environment" ]]; then
      printf '%s: overrides exported before authentication\n' "$scenario" >&2
      exit 1
    fi
  fi

  status=0
  env PATH="$hash_tools" "$bash_path" "$root/.github/scripts/verify-v8-release-manifest.sh" \
    "$trusted" "$release/$manifest" > "$root/shasum-output" 2>&1 || status=$?
  if [[ "$expected_status" == 0 && "$status" != 0 || "$expected_status" != 0 && "$status" == 0 ]]; then
    printf '%s: shasum authentication returned unexpected status %s\n' "$scenario" "$status" >&2
    cat "$root/shasum-output" >&2
    exit 1
  fi
done
