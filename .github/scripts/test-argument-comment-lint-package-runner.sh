#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixtures="$(mktemp -d "${TMPDIR:?TMPDIR must name owned test storage}/argument-lint-runner-XXXXXX")"
trap 'rm -rf -- "$fixtures"' EXIT

for scenario in cold warm rustup-failure install-failure syntax-failure unittest-failure test-failure; do
  root="$fixtures/$scenario"
  mkdir -p "$root/bin" "$root/project/.github/scripts" "$root/project/upstream/tools/argument-comment-lint" "$root/tmp"
  cp "$project_root/.github/scripts/run-memory-scheduled-build.sh" "$root/project/.github/scripts/"
  if [[ -f "$project_root/.github/scripts/test-argument-comment-lint-package.sh" ]]; then
    cp "$project_root/.github/scripts/test-argument-comment-lint-package.sh" "$root/project/.github/scripts/"
  fi
  printf 'source fixture\n' > "$root/project/upstream/tools/argument-comment-lint/source"
  cat > "$root/project/upstream/tools/argument-comment-lint/list-bazel-targets.sh" <<'TARGETS'
#!/usr/bin/env bash
printf '//fixture:lint\n'
TARGETS
  cat > "$root/project/.github/scripts/run-bazel-ci.sh" <<'BAZEL'
#!/usr/bin/env bash
set -euo pipefail
printf 'bazel %s\n' "$*" >> "$EVENT_LOG"
BAZEL
  cat > "$root/bin/rustup" <<'RUSTUP'
#!/usr/bin/env bash
set -euo pipefail
printf 'rustup %s\n' "$*" >> "$EVENT_LOG"
[[ "$SCENARIO" != rustup-failure ]] || exit 41
RUSTUP
  cat > "$root/bin/cargo" <<'CARGO'
#!/usr/bin/env bash
set -euo pipefail
printf 'cargo %s\n' "$*" >> "$EVENT_LOG"
printf 'cargo-env %s|%s|%s|%s\n' "$CARGO_HOME" "$RUSTUP_HOME" "$CARGO_TARGET_DIR" "${RUST_MIN_STACK:-}" >> "$ENV_LOG"
printf 'dylint-driver-cache %s\n' "${DYLINT_DRIVER_PATH:-}" >> "$ENV_LOG"
if [[ "$2" == install ]]; then
  [[ "$SCENARIO" != install-failure ]] || exit 42
  package="$4"
  shift 4
  while [[ "$1" != --root ]]; do shift; done
  mkdir -p "$2/bin"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$2/bin/$package"
  chmod +x "$2/bin/$package"
else
  [[ "$SCENARIO" != test-failure ]] || exit 45
  printf 'cargo-test-directory %s\n' "$PWD" >> "$ENV_LOG"
  mkdir -p "$CARGO_TARGET_DIR"
  touch "$CARGO_TARGET_DIR/fixture-output"
fi
CARGO
  cat > "$root/bin/python3" <<'PYTHON'
#!/usr/bin/env bash
set -euo pipefail
printf 'python3 %s\n' "$*" >> "$EVENT_LOG"
printf 'python-cache %s\n' "$PYTHONPYCACHEPREFIX" >> "$ENV_LOG"
printf 'python-directory %s\n' "$PWD" >> "$ENV_LOG"
mkdir -p "$PYTHONPYCACHEPREFIX"
touch "$PYTHONPYCACHEPREFIX/fixture-bytecode"
if [[ "$2" == py_compile && "$SCENARIO" == syntax-failure ]]; then exit 43; fi
if [[ "$2" == unittest && "$SCENARIO" == unittest-failure ]]; then exit 44; fi
PYTHON
  cat > "$root/bin/tooling" <<'TOOLING'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == schedule-compilers && "$2" == -- ]]
shift 2
printf 'schedule %s\n' "$*" >> "$EVENT_LOG"
exec "$@"
TOOLING
  chmod +x "$root/bin/"* "$root/project/.github/scripts/run-bazel-ci.sh" \
    "$root/project/upstream/tools/argument-comment-lint/list-bazel-targets.sh"
  awk 'BEGIN { print "set -euo pipefail" } /^      bazel-argument-comment-lint\)$/ { body=1; next } body && /^        ;;$/ { exit } body { print }' \
    "$project_root/.github/scripts/buildbuddy-ci.sh" > "$root/case.sh"
  (cd "$root/project/upstream" && find . -type f -exec sha256sum {} + | sort) > "$root/before"
  repeats=1
  if [[ "$scenario" == warm ]]; then repeats=2; fi
  for ((run=1; run<=repeats; run++)); do
    : > "$root/events"
    : > "$root/environment"
    status=0
    (cd "$root/project" && env PATH="$root/bin:$PATH" project_root="$root/project" \
      CI_BUILD_ROOT="$root/cache" RUNNER_TEMP="$root/tmp" RUNNER_OS=Darwin GITHUB_SHA=fixture \
      CODEX_UNLEASHED_TOOLING="$root/bin/tooling" EVENT_LOG="$root/events" ENV_LOG="$root/environment" \
      SCENARIO="$scenario" bash "$root/case.sh") > "$root/output" 2>&1 || status=$?
    expected_status=0
    case "$scenario" in
      rustup-failure) expected_status=41 ;;
      install-failure) expected_status=42 ;;
      syntax-failure) expected_status=43 ;;
      unittest-failure) expected_status=44 ;;
      test-failure) expected_status=45 ;;
    esac
    if [[ "$status" != "$expected_status" ]]; then
      printf '%s: expected exit %s, received %s\n' "$scenario" "$expected_status" "$status" >&2
      cat "$root/output" >&2
      exit 1
    fi
    if [[ "$expected_status" == 0 ]]; then
      {
        printf 'rustup toolchain install nightly-2026-08-20 --profile minimal --component llvm-tools-preview --component rustc-dev --component rust-src --no-self-update\n'
        if [[ "$run" == 1 ]]; then
          for package in cargo-dylint dylint-link; do
            printf 'schedule cargo +nightly-2026-08-20 install --locked %s --version 6.1.0 --root %s/cache/argument-comment-lint/tools-6.1.0\n' "$package" "$root"
            printf 'cargo +nightly-2026-08-20 install --locked %s --version 6.1.0 --root %s/cache/argument-comment-lint/tools-6.1.0\n' "$package" "$root"
          done
        fi
        printf 'python3 -m py_compile tools/argument-comment-lint/wrapper_common.py tools/argument-comment-lint/run.py tools/argument-comment-lint/run-prebuilt-linter.py tools/argument-comment-lint/test_wrapper_common.py\n'
        printf 'python3 -m unittest discover -s tools/argument-comment-lint -p test_*.py\n'
        printf 'schedule cargo +nightly-2026-08-20 test\ncargo +nightly-2026-08-20 test\n'
        printf 'bazel --print-failed-action-summary -- build --config=argument-comment-lint --build_metadata=COMMIT_SHA=fixture --build_metadata=TAG_job=argument-comment-lint -- //fixture:lint\n'
      } > "$root/expected"
      if ! diff -u "$root/expected" "$root/events"; then
        printf '%s: package checks or Bazel aspect coverage were omitted or reordered\n' "$scenario" >&2
        exit 1
      fi
      expected_cargo_env="cargo-env $root/cache/argument-comment-lint/cargo|$root/cache/argument-comment-lint/rustup|$root/cache/argument-comment-lint/target|8388608"
      grep -Fx "$expected_cargo_env" "$root/environment" > /dev/null
      grep -Fx "dylint-driver-cache $root/cache/argument-comment-lint/drivers" "$root/environment" > /dev/null
      grep -Fx "cargo-test-directory $root/project/upstream/tools/argument-comment-lint" "$root/environment" > /dev/null
      grep -Fx "python-cache $root/tmp/argument-comment-lint-pycache" "$root/environment" > /dev/null
      grep -Fx "python-directory $root/project/upstream" "$root/environment" > /dev/null
    else
      if grep -q '^bazel ' "$root/events"; then
        printf '%s: Bazel ran after a package-test failure\n' "$scenario" >&2
        exit 1
      fi
    fi
    (cd "$root/project/upstream" && find . -type f -exec sha256sum {} + | sort) > "$root/after"
    diff -u "$root/before" "$root/after"
  done
done
