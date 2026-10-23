# Isolate disk-heavy daemon tests

## Intent

Daemon package installation tests run without competing tests consuming the
runner's disk bandwidth. All existing package and lifecycle assertions remain.

## Reference and base

- Upstream change: https://github.com/openai/codex/pull/53897
- Base: `rust-v0.163.0`, `83086188ae0614bd882818ff1aa23f3194962012`.
- The patch applies independently to that base and in the release patch queue.

## Scope

Nextest reserves all test threads for the package-copy cases. Bazel marks the
existing daemon integration target `exclusive` and runs its cases with one
libtest thread. Other integration targets keep their scheduling settings.

Upstream splits package cases into a separate binary. This adaptation isolates
the entire existing daemon integration binary, preserving downstream lifecycle
fixtures without moving them or depending on their patches. It serializes some
additional lifecycle cases but does not exclude coverage or extend deadlines.

## Verification

Run `just fmt-check` in the patched checkout. From `codex-rs`, run
`cargo nextest run -p codex-cli --test app_server_daemon` using the maintained
CI environment. Run `bazel test //codex-rs/cli:cli-app_server_daemon-test`
with the platform's maintained CI configuration. All cases must pass; the Bazel
target must run exclusively with one test thread. Hosted execution is required
to assess disk contention alongside the rest of the suite.

## Upstream status

Upstream commit `97771b1d78cf6366bc3946fe9e4bbca4ebee31d1` contains the
package-binary split. Reassess this backport when upgrading the pinned base.
