# Windows Bazel baselines for upstream rust-v0.167.0

The `windows-bazel-*.json` files here record the failures of an unpatched run of
`.github/workflows/baseline-windows-bazel-tests.yml` at upstream commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
An unpatched run shows only that a failure is not caused by our patches. This file records, per
the rule `.cat/rules/include/common/upstream-test-failures.md`, which entries are also upstream's.

## Entries that upstream's own CI also fails

The three tests below fail in upstream's native-MSVC job, `test-windows-native-main` in `bazel.yml` ("Bazel test on
windows-latest for x86_64-pc-windows-msvc (native main)"). Upstream's pull-request jobs run different targets:
`.github/workflows/bazel.yml` `test-windows-shard` builds with `--config=ci-windows-cross`, where `defs.bzl` marks the
native integration targets `WINDOWS_GNULLVM_INCOMPATIBLE` (skipped) and runs the `-windows-cross` variants
(`WINDOWS_GNULLVM_ONLY`). Our key-less fallback builds MSVC-target binaries, so we run the native variants.

| Test | Upstream native-MSVC result (blocking-ci run, job) | Upstream `-windows-cross` result |
| --- | --- | --- |
| `//codex-rs/ext/queue:queue-queue_service-test` (`rejected_queue_messages_are_consumed_without_retrying_or_blocking_followups`, `explicitly_started_rejected_queue_messages_are_consumed`; `core/tests/common/lib.rs:405`) | FAILED 12.9-23.2 s in 6 of 6 runs below | PASSED 9.1 s, run 40571711631, job 122427348598 (shard 3/4); native target SKIPPED in job 122427348744 |
| `//codex-rs/cli:cli-doctor_path_safety-test` (`startup_and_doctor_do_not_execute_path_helpers`) | FAILED 16.9-20.3 s in 5 of 6 runs below (not in 41049704335) | PASSED 51.9 s, run 40571711631, job 122427348598; native target SKIPPED in job 122427348744 |
| `//codex-rs/ext/multi-env-copy-file:multi-env-copy-file-copy_file-test` | FAILED 29.0-58.3 s in 3 of 4 runs where it ran (PASSED 30.3 s in 41077022086) | `-windows-cross` PASSED 71.1 s, run 40571711631, job 122427348571 (shard 1/4); native target SKIPPED in job 122427348744 |

Native-MSVC runs checked (all `blocking-ci`, event `push` to `main`, UTC):

| Run | Job | Date | Commit |
| --- | --- | --- | --- |
| 40773098500 | 123115205194 | 2026-10-28 | `d373d649` |
| 40804812047 | 123221097326 | 2026-10-29 | `6c776cfc` |
| 40914828226 | 123592146302 | 2026-10-29 | `3062f7aa` |
| 41049704335 | 124042436896 | 2026-10-30 | `f2ac9983` |
| 41077022086 | 124135814722 | 2026-10-30 | `85d042c7` |
| 41103447384 | 124226137791 | 2026-10-30 | `a94ba8bd` |

The logs are retrievable with `gh api repos/openai/codex/actions/jobs/<job>/logs`.

### Proof that upstream's configuration did not block on these failures

All three are failures of one job, so the proof is about that job, not about each test:

- `bazel.yml` line 264: `test-windows-native-main` runs only when `github.event_name == 'push' && github.ref ==
  'refs/heads/main'` or on `workflow_dispatch`. It never runs on a pull request, so it cannot gate a merge. The six runs
  above are all post-merge pushes to `main` and each ended `failure`.
- `rust-release.yml` lines 12-15 trigger on `rust-v*.*.*` tags; its only gate, `tag-check` (lines 27-57), compares the
  tag with `codex-rs/Cargo.toml`, and `build` needs only `tag-check` (lines 59-60). The release does not depend on
  `blocking-ci.yml` or `bazel.yml`. Release run 42130003128 for the tag commit succeeded.
- `blocking-ci.yml` lines 53-61 at the tag: the `required` job's `needs` omits `bazel` ("Bazel is advisory while the
  BuildBuddy key is revoked"). Before that change (for example at `a94ba8bd`, lines 53-54) it listed `bazel`, but only
  the post-merge push run reported the native job's failure; no pull request could depend on it.

This establishes that upstream tolerated these failures and that no release depended on them. It does not show that
upstream triaged these particular tests. No retry passed them and no tag or filter marks them flaky or manual. Treat the
entries as unverified upstream behavior beyond "upstream's own native-MSVC job fails them".

## Entries that are not accepted as upstream failures

These tests pass in upstream's blocking jobs or in upstream's native job, so their entries are possible defects in our
setup. They were recorded with `--local_test_jobs=8` and `RUST_TEST_THREADS=1` on MSVC-target binaries (see the
workflow comment) and Bazel's 300 s timeout. Re-record the baseline after the workflow change and drop the entries that
no longer fail:

- `//codex-rs/thread-store:thread-store-unit-tests` (gnullvm shard 4): upstream PASSED 40.7 s, run 40571711631.
- `//codex-rs/cli:cli-daemon_startup-test` (shard 1) and `//codex-rs/cli:cli-worktree-test` (shard 4): upstream passes
  the `-windows-cross` variants (run 40571711631) and the native variants in most native runs.
- `//codex-rs/tui:tui-unit-tests` (shard 3): upstream gnullvm PASSED 200.5 s; upstream native main also hits the 300 s
  timeout (run 41103447384, 304 s).
- `//codex-rs/windows-sandbox-rs:windows-sandbox-rs-unit-tests`
  `unified_exec::tests::legacy_workspace_write_delete_is_limited_to_writable_roots` (gnullvm shard 3 and MSVC): passes
  upstream (run 40571711631, job 122427348598, 31.9 s). Cause not established; see the delivery report.
