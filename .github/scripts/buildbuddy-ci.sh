#!/usr/bin/env bash
# Runs the selected CI task inside a hosted BuildBuddy Linux VM.
set -euo pipefail

finish() {
  local status=$?
  local artifacts="${BUILDBUDDY_ARTIFACTS_DIRECTORY:?}"
  mkdir -p "$artifacts"
  printf '%s\n%s\n%s\n%s\n' "$GITHUB_SHA" "$BUILDBUDDY_CI_TASK" "$BUILDBUDDY_CI_TARGET" "$status" \
    > "$artifacts/result.txt"
  (cd "$artifacts" && find . -type f ! -name artifact-manifest.sha256 -print0 \
    | sort -z | xargs -0 sha256sum > artifact-manifest.sha256)
}
trap finish EXIT

case "${BUILDBUDDY_CI_TASK:?BUILDBUDDY_CI_TASK is required}" in
  probe)
    printf 'Runner platform: %s\n' "$(uname -sm)"
    for tool in bash curl jq git rustup cargo node java python3 just uv bazel sudo; do
      if command -v "$tool"; then
        :
      else
        printf 'Not preinstalled: %s\n' "$tool"
      fi
    done
    printf 'Remote runner probe completed.\n'
    ;;
  tooling)
    source .github/scripts/buildbuddy-bootstrap.sh
    ;;
  codespell|repo-checks|format|cargo-shear|bazel-test|sdk)
    source .github/scripts/buildbuddy-bootstrap.sh
    source .github/scripts/buildbuddy-tools.sh
    if [[ "$BUILDBUDDY_CI_TASK" == codespell ]]; then
      uv tool run --from 'codespell[toml]>=2.2.4' codespell --count --skip ./.git --ignore-words .codespellignore
      exit
    fi
    source .github/scripts/buildbuddy-prepare-upstream.sh
    case "$BUILDBUDDY_CI_TASK" in
      repo-checks|format|cargo-shear)
        pnpm --dir upstream install --frozen-lockfile
        case "$BUILDBUDDY_CI_TASK" in
          repo-checks)
            export NODE_OPTIONS=--max-old-space-size=4096
            export ALLOW_STALE_CODE_MODE_FEATURE_EXCEPTION=1
            ./scripts/run-upstream-repo-checks.sh upstream
            ;;
          format)
            (cd upstream && just fmt-check && cd codex-rs && just bench-smoke)
            ;;
          cargo-shear)
            if ! command -v cargo-shear >/dev/null; then
              cargo install cargo-shear --version 1.11.2 --locked
            fi
            "$CODEX_UNLEASHED_TOOLING" check-cargo-shear upstream
            ;;
        esac
        ;;
      bazel-test)
        cd upstream
        if [[ "$BUILDBUDDY_CI_TARGET" == x86_64-unknown-linux-gnu ]]; then
          bash "$project_root/.github/scripts/test-bazel-cache-save.sh" "$project_root/.github/workflows/bazel.yml"
          "$CODEX_UNLEASHED_TOOLING" rusty-v8-bazel . check-module-bazel
          "$CODEX_UNLEASHED_TOOLING" rusty-v8-bazel . check-consumer-selectors
          just test-github-scripts
          "$project_root/.github/scripts/run-bazel-ci.sh" -- build --nobuild -- @llvm//runtimes/msvc:msvc_include
          ./scripts/check-module-bazel-lock.sh
        fi
        "$project_root/.github/scripts/run-bazel-ci.sh" --print-failed-action-summary --print-failed-test-logs -- \
          test --test_tag_filters=-argument-comment-lint --test_verbose_timeout_warnings \
          "--build_metadata=COMMIT_SHA=$GITHUB_SHA" -- //... -//third_party/v8:all -//codex-rs/v8-poc:v8-poc-unit-tests
        "$project_root/.github/scripts/run-bazel-ci.sh" -- cquery --output=label -- \
          'deps(//tools/build-warnings/rpaths:all)' > "$RUNNER_TEMP/native-sdk-labels.txt"
        bash tools/build-warnings/rpaths/check-sdk-analysis.sh "$RUNNER_OS" "$RUNNER_TEMP/native-sdk-labels.txt"
        ;;
      sdk)
        cd upstream
        "$project_root/.github/scripts/run-bazel-ci.sh" --remote-download-toplevel -- build \
          "--build_metadata=COMMIT_SHA=$GITHUB_SHA" --build_metadata=TAG_job=sdk -- \
          //codex-rs/cli:codex //codex-rs/code-mode-host:codex-code-mode-host
        mapfile -t binaries < <("$project_root/.github/scripts/run-bazel-ci.sh" -- cquery --output=files -- \
          'set(//codex-rs/cli:codex //codex-rs/code-mode-host:codex-code-mode-host)' | grep -E '^(/|bazel-out/)')
        if [[ ${#binaries[@]} != 2 ]]; then
          printf 'Expected both Codex package binaries; found %s.\n' "${#binaries[@]}" >&2
          exit 1
        fi
        mkdir -p "$RUNNER_TEMP/codex-sdk-ci"
        install -m 755 "${binaries[@]}" "$RUNNER_TEMP/codex-sdk-ci"
        export CODEX_EXEC_PATH="$RUNNER_TEMP/codex-sdk-ci/codex"
        "$CODEX_EXEC_PATH" --version
        pnpm install --frozen-lockfile
        pnpm --dir sdk/typescript run build
        pnpm --dir sdk/typescript run lint
        pnpm --dir sdk/typescript exec jest --testTimeout=30000
        ;;
    esac
    ;;
  *)
    printf 'Unsupported BuildBuddy CI task: %s\n' "$BUILDBUDDY_CI_TASK" >&2
    exit 2
    ;;
esac
