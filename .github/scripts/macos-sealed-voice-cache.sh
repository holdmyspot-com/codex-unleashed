#!/usr/bin/env bash
# Transport exact, verified native macOS voice bundles between package jobs.
set -euo pipefail

script="${BASH_SOURCE[0]}"
helper=release/codex-voice-host
relocated=verification/packaged-runtime-test-bin
installed=verification/installed-package-runtime-test-bin
producer_inventory=evidence/release_files.json
required=("$helper" "$relocated" "$installed" "$producer_inventory" release/runtime/runtime.json completed)
max_transport_bytes=$((128 * 1024 * 1024))
max_native_bytes=$((512 * 1024 * 1024))

sha256() {
  shasum -a 256 "$1" | cut -d ' ' -f 1
}

producer() {
  jq -n --arg repository "$GITHUB_REPOSITORY" --arg workflow "$GITHUB_WORKFLOW_REF" \
    --arg run "$GITHUB_RUN_ID" --argjson attempt "$GITHUB_RUN_ATTEMPT" \
    '{repository:$repository, workflow_ref:$workflow, run_id:$run, run_attempt:$attempt}'
}

native_inputs() {
  local target="$1" version="$2" commit tree pin xcode sdk sdk_version sdk_build clang rust python
  test "$RUNNER_OS" = macOS && test -n "${ImageVersion:-}" || return 1
  commit="$(git rev-parse HEAD)"
  tree="$(git rev-parse 'HEAD^{tree}')"
  pin="$(cat .bazelversion)"
  xcode="$(xcodebuild -version)"
  sdk="$(xcrun --sdk macosx --show-sdk-path)"
  sdk_version="$(xcrun --sdk macosx --show-sdk-version)"
  sdk_build="$(xcrun --sdk macosx --show-sdk-build-version)"
  clang="$(xcrun --sdk macosx clang --version)"
  rust="$(rustc -vV)"
  python="$(python --version)"
  jq -nS --arg fork "$GITHUB_SHA" --arg target "$target" --arg version "$version" \
    --arg commit "$commit" --arg tree "$tree" --arg pin "$pin" --arg xcode "$xcode" \
    --arg sdk "$sdk" --arg sdk_version "$sdk_version" --arg sdk_build "$sdk_build" \
    --arg clang "$clang" --arg rust "$rust" --arg python "$python" --arg source "$(pwd -P)" '
    {fork_commit:$fork, target:$target, source_commit:$commit, source_tree:$tree, version:$version,
     bazel_pin:$pin, xcode:$xcode, sdk_path:$sdk, sdk_version:$sdk_version, sdk_build:$sdk_build,
     clang:$clang, rust:$rust, python:$python, source_path:$source, environment:
       (["ImageOS","ImageVersion","RUNNER_OS","RUNNER_ARCH","SOURCE_DATE_EPOCH","MACOSX_DEPLOYMENT_TARGET",
         "SDKROOT","DEVELOPER_DIR","CC","CXX","CFLAGS","CXXFLAGS","RUSTFLAGS","BAZELISK_BASE_URL","USE_BAZEL_VERSION"]
        | map({key:.,value:env[.]}) | from_entries)}'
}

file_rows() (
  local root="$1" name mode bytes digest
  cd "$root"
  for name in "${required[@]}"; do
    test -f "$name" && test ! -L "$name" || return 1
  done
  {
    printf '%s\n' "${required[@]}"
    find release/runtime -mindepth 1 ! -type d -print
  } | LC_ALL=C sort -u | while IFS= read -r name; do
    [[ "$name" =~ ^[A-Za-z0-9._/-]+$ && -f "$name" && ! -L "$name" ]] || return 1
    mode="$(stat -f '%Lp' "$name")" || return 1
    bytes="$(stat -f '%z' "$name")" || return 1
    case "$mode" in 444|555|644|755) ;; *) return 1 ;; esac
    digest="$(sha256 "$name")" || return 1
    printf '%s\t%s\t%s\t%s\n' "$name" "$((8#$mode))" "$bytes" "$digest"
  done
)

inventory() {
  local root="$1" inputs="$2" files
  files="$(file_rows "$root" | jq -Rn '
    [inputs | split("\t") | {key:.[0],value:{mode:(.[1]|tonumber),bytes:(.[2]|tonumber),sha256:.[3]}}]
    | from_entries')" || return 1
  jq -e --slurpfile inputs "$inputs" --argjson files "$files" '
    .format_version == 1 and .source_commit == $inputs[0].source_commit and
      .app_target == $inputs[0].target and .voice_target == $inputs[0].target and
      (.files | length == ([.[].path] | unique | length)) and
      (.files | map({key: .path, value: {bytes: .size, sha256: .sha256}}) | from_entries) ==
      ($files | with_entries(select(.key | startswith("release/")) |
       .key |= ltrimstr("release/") | .value |= del(.mode)))' "$root/$producer_inventory" >/dev/null || return 1
  printf '%s\n' "$files"
}

verify_fixture() (
  local target="$1" version="$2" root="$3" inputs="$4" evidence="$5"
  local architecture="${target%%-*}" path bundle staged delivered commit binary
  case "$architecture" in aarch64) architecture=arm64 ;; x86_64) ;; *) return 1 ;; esac
  path="$(mktemp -d "$RUNNER_TEMP/voice-cache-native-fixture.XXXXXX")"
  trap 'rm -rf -- "$path"' EXIT
  bundle="$path/fixture.tar.gz"
  staged="$path/staging"
  delivered="$path/extracted package"
  commit="$(jq -er .source_commit "$inputs")"
  find "$root/release/runtime" -name '*.dylib' -type f > "$path/libraries"
  test -s "$path/libraries"
  printf '%s\n' "$root/$helper" "$root/$relocated" "$root/$installed" >> "$path/libraries"
  while IFS= read -r binary; do
    test "$(xcrun lipo -archs "$binary")" = "$architecture"
  done < "$path/libraries"
  mkdir -p "$staged/bin" "$staged/codex-path" "$delivered" "$(dirname -- "$evidence")"
  printf '#!/bin/sh\nexit 97\n' > "$staged/bin/codex"
  cp "$staged/bin/codex" "$staged/bin/codex-exec-server"
  chmod 755 "$staged/bin/"*
  jq -n --arg target "$target" --arg version "$version" '
    {layoutVersion:1,target:$target,version:$version,variant:"codex",entrypoint:"bin/codex",
     execServer:"bin/codex-exec-server",resourcesDir:"codex-resources",pathDir:"codex-path"}' \
    > "$staged/codex-package.json"
  local -a common=(--upstream-root "$PWD" --target "$target" --build-commit "$commit" --package-version "$version")
  bash "$CODEX_TOOLING" voice-runtime stage --release-dir "$root/release" --package-dir "$staged" "${common[@]}"
  # The upstream package writer normalizes executable permissions before native verification.
  PYTHONPATH="$PWD/scripts" CODEX_REPO_ROOT="$PWD" python3 -m codex_package.archive write \
    --package-dir "$staged" --archive-output "$bundle"
  gtar -xzf "$bundle" -C "$delivered"
  bash "$CODEX_TOOLING" voice-runtime verify --test-binary "$root/$relocated" --installed-test-binary "$root/$installed" \
    --evidence-dir "$evidence" --package-dir "$delivered" "${common[@]}"
)

save() {
  local target="$1" version="$2" root="$3" outgoing="$4" inputs="$5" files
  jq -e --arg fork "$GITHUB_SHA" --arg commit "$(git rev-parse HEAD)" --arg tree "$(git rev-parse 'HEAD^{tree}')" \
    --arg target "$target" --arg version "$version" '
    .fork_commit == $fork and .source_commit == $commit and .source_tree == $tree and
    .target == $target and .version == $version' "$inputs" >/dev/null
  files="$(inventory "$root" "$inputs")"
  mkdir -- "$outgoing"
  jq -r 'keys[]' <<< "$files" | gtar --format=pax --no-recursion -C "$root" -czf "$outgoing/voice.tar.gz" -T -
  jq -nS --argjson producer "$(producer)" --slurpfile inputs "$inputs" --argjson files "$files" \
    --arg archive "$(sha256 "$outgoing/voice.tar.gz")" \
    '{format:1, producer:$producer, inputs:$inputs[0], files:$files, archive_sha256:$archive}' > "$outgoing/manifest.json"
}

fetch_one() (
  local row="$1" destination="$2" id run digest bytes payload scratch details attempt
  IFS=$'\t' read -r id run digest bytes <<< "$row"
  [[ "$id" =~ ^[0-9]+$ && "$run" =~ ^[0-9]+$ && "$digest" =~ ^sha256:[a-f0-9]{64}$ && "$bytes" =~ ^[0-9]+$ ]]
  test "$run" != "$GITHUB_RUN_ID"
  # The latest completed producer attempt is authoritative. Its witness must
  # also match the attempt preserved inside the immutable bundle on restore.
  details="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run")"
  attempt="$(jq -er --arg repo "$GITHUB_REPOSITORY" --arg head "$GITHUB_SHA" --arg branch "$GITHUB_REF_NAME" '
    select(.path == ".github/workflows/upstream-auto-release.yml" and .head_sha == $head and .head_branch == $branch and
      .head_repository.full_name == $repo and (.event == "schedule" or .event == "workflow_dispatch") and
      .status == "completed" and .conclusion == "success") | .run_attempt' <<< "$details")"
  [[ "$attempt" =~ ^[1-9][0-9]*$ ]]
  scratch="$(mktemp -d "$RUNNER_TEMP/voice-cache-fetch.XXXXXX")"
  trap 'rm -rf -- "$scratch"' EXIT
  payload="$scratch/artifact.zip"
  # Do not put signed download redirects in step logs.
  (
    ulimit -f 131072
    gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$id/zip" > "$payload" 2>/dev/null
  )
  test "$(stat -f '%z' "$payload")" = "$bytes"
  test "sha256:$(sha256 "$payload")" = "$digest"
  test "$(unzip -Z -1 "$payload" | LC_ALL=C sort)" = $'manifest.json\nvoice.tar.gz'
  (
    ulimit -f 131072
    unzip -p "$payload" manifest.json > "$scratch/manifest.json"
    unzip -p "$payload" voice.tar.gz > "$scratch/voice.tar.gz"
  )
  test "$(stat -f '%z' "$scratch/manifest.json")" -le 65536
  rm -- "$payload"
  jq -n --arg run "$run" --argjson attempt "$attempt" --argjson artifact "$id" --arg digest "${digest#sha256:}" \
    --arg manifest "$(sha256 "$scratch/manifest.json")" '
    {run_id:$run,run_attempt:$attempt,artifact_id:$artifact,artifact_sha256:$digest,manifest_sha256:$manifest}' \
    > "$scratch/approved.json"
  mv -- "$scratch" "$destination"
)

fetch() {
  local target="$1" version="$2" incoming="$3" inputs="$4" name rows row count=0
  # Snapshot the producer environment before package-specific tool setup.
  native_inputs "$target" "$version" > "$inputs"
  name="sealed-voice-v1-$target-$(sha256 "$inputs")"
  printf 'name=%s\n' "$name" >> "$GITHUB_OUTPUT"
  # Same-run downstream retries already retain their original package artifacts.
  # Only select other runs and consider at most the newest three candidates.
  rows="$(gh api "repos/$GITHUB_REPOSITORY/actions/artifacts?per_page=20&name=$name" | jq -r \
    --arg name "$name" --arg own "$GITHUB_RUN_ID" --arg head "$GITHUB_SHA" --arg branch "$GITHUB_REF_NAME" \
    --argjson repository "$GITHUB_REPOSITORY_ID" --argjson limit "$max_transport_bytes" '
    .artifacts | map(select(.name == $name and .expired == false and .size_in_bytes > 0 and .size_in_bytes <= $limit and
      (.digest | strings | test("^sha256:[a-f0-9]{64}$")) and .workflow_run.head_branch == $branch and
      .workflow_run.head_sha == $head and .workflow_run.repository_id == $repository and
      .workflow_run.head_repository_id == $repository and (.workflow_run.id | tostring) != $own))
    | sort_by(.created_at, .id) | reverse | .[:3][] | [.id,.workflow_run.id,.digest,.size_in_bytes] | @tsv')"
  if [[ -z "$rows" ]]; then return 0; fi
  mkdir -- "$incoming"
  while IFS= read -r row; do
    count=$((count + 1))
    if ! bash "$script" fetch-one "$row" "$incoming/$count"; then
      printf '::notice::A sealed voice cache candidate was unavailable.\n'
    fi
  done <<< "$rows"
}

restore_one() (
  local target="$1" version="$2" candidate="$3" result="$4" evidence="$5" inputs="$6"
  local approved="$candidate/approved.json" manifest="$candidate/manifest.json" archive="$candidate/voice.tar.gz"
  local staged actual name
  test "$(sha256 "$manifest")" = "$(jq -er .manifest_sha256 "$approved")"
  test "$(sha256 "$archive")" = "$(jq -er .archive_sha256 "$manifest")"
  jq -e --slurpfile approved "$approved" --slurpfile inputs "$inputs" --argjson current "$(producer)" '
    .format == 1 and .inputs == $inputs[0] and $approved[0].run_id != $current.run_id and
    .producer == ($current | .run_id=$approved[0].run_id | .run_attempt=$approved[0].run_attempt)' "$manifest" >/dev/null
  staged="$(mktemp -d "$(dirname -- "$result")/voice-cache-restore.XXXXXX")"
  trap 'rm -rf -- "$staged"' EXIT
  jq -er '.files | keys[]' "$manifest" > "$staged/expected"
  while IFS= read -r name; do
    [[ "$name" =~ ^[A-Za-z0-9._/-]+$ && "$name" != /* && ! "$name" =~ (^|/)\.\.?(/|$) ]]
    case "$name" in "$helper"|"$relocated"|"$installed"|"$producer_inventory"|completed|release/runtime/*) ;; *) return 1 ;; esac
  done < "$staged/expected"
  gtar --absolute-names --quoting-style=escape -tzf "$archive" | LC_ALL=C sort > "$staged/names"
  cmp "$staged/expected" "$staged/names"
  LC_ALL=C gtar --numeric-owner --absolute-names --quoting-style=escape -tvzf "$archive" | \
    awk -v limit="$max_native_bytes" '
      $1 !~ /^-/ || $3 !~ /^[0-9]+$/ {bad=1}
      {total+=$3}
      END {exit (bad || NR==0 || total>limit)}'
  rm -- "$staged/expected" "$staged/names"
  gtar --no-same-owner --same-permissions -xzf "$archive" -C "$staged"
  actual="$(inventory "$staged" "$inputs")"
  jq -e --argjson files "$actual" '.files == $files' "$manifest" >/dev/null
  verify_fixture "$target" "$version" "$staged" "$inputs" "$evidence"
  jq -nS --slurpfile manifest "$manifest" --slurpfile approved "$approved" --argjson consumer "$(producer)" '
    {producer:$manifest[0].producer,consumer:$consumer,inputs:$manifest[0].inputs,
     artifact_id:$approved[0].artifact_id,artifact_sha256:$approved[0].artifact_sha256,
     manifest_sha256:$approved[0].manifest_sha256,archive_sha256:$manifest[0].archive_sha256}' \
    > "$staged/evidence/voice-cache-origin.json"
  test ! -e "$result" && test ! -L "$result"
  mv -- "$staged" "$result"
)

restore() (
  local target="$1" version="$2" result="$3" incoming="$4" evidence="$5" inputs candidate
  inputs="$(mktemp "$RUNNER_TEMP/voice-cache-current.XXXXXX")"
  trap 'rm -f -- "$inputs"' EXIT
  native_inputs "$target" "$version" > "$inputs"
  for candidate in "$incoming"/[123]; do
    # A new Bash keeps validation fail-closed even when the caller tests its status.
    if [[ -f "$candidate/approved.json" ]] &&
      bash "$script" restore-one "$target" "$version" "$candidate" "$result" "$evidence/${candidate##*/}" "$inputs"; then
      return 0
    fi
  done
  return 1
)

operation="${1:?missing voice cache operation}"
shift
case "$operation" in
  fetch) fetch "$@" ;;
  fetch-one) fetch_one "$@" ;;
  restore) restore "$@" ;;
  restore-one) restore_one "$@" ;;
  save) save "$@" ;;
  *) exit 2 ;;
esac
