# Strict core test Clippy

## Intent

Core test targets compile under the maintained strict Clippy warning policy.
The patch removes two unused imports and preserves all test targets and lint checks.

## Reference and base

- [Issue #56](https://github.com/holdmyspot-com/codex-unleashed/issues/56).
- Upstream base: `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`.
- The patch applies independently to that base and has no shared prerequisites.
- Remove the patch when the selected upstream release includes these import removals.

## Reproduction

The maintained Bazel Clippy configuration treats unused imports as errors.
The `core-all-test-bin` target imports `body_json` and `ReasoningEffort` without using them.

## Verification

Apply the patch to a fresh checkout of the declared base. From that checkout, run
`just fmt-check`. In the prepared hosted CI checkout, run the maintained
`run-bazel-ci.sh` wrapper with `build --config=clippy -- //codex-rs/core:core-all-test-bin`.
Formatting and strict Clippy pass. The full supported Clippy matrix remains required.

This patch introduces no feature flags or project configuration properties.
