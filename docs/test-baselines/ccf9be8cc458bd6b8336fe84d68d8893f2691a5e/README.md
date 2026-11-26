# Bazel baselines for upstream rust-v0.171.0

The `*-bazel-*.json` files here record the failures of unpatched runs of the Bazel baseline workflows at upstream
commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`. An unpatched run shows only that a failure is not caused by our
patches. Per `.cat/rules/include/common/upstream-test-failures.md`, this file records which entries are upstream's.

| Files | Source run | Executed / failed |
| --- | --- | --- |
| `linux-bazel-shard1.json`, `linux-bazel-shard2.json` | 45422687726 (completion-backed reports) | 177/2, 198/1 |
| `macos-bazel-arm64-shard-1.json`, `macos-bazel-arm64-shard-2.json` | saved arm64 shard reports | 174/2, 184/0 |
| `windows-bazel-gnullvm-shard-{1,2,3,4}.json` | page-file run 45333894851 | 80/0, 77/0, 100/22, 105/0 |
| `windows-bazel-msvc-shard-1.json` | 45320758830, artifact 14896209964 (shard 1 of 1) | 238/20 |

No report has a `timed_out` entry. ## Unverified upstream behavior

Upstream's records show no status for these tests on the matching platform:

- MSVC: `app-server`, `codex-bin-unit-tests`, `core-all`, `core-mcp_trace_propagation`, rmcp-client recovery and
  `user_agent`.
- Linux: `cli-app_server_daemon` appeared in earlier evidence; neither completion-backed report from 45422687726
  lists it.

## Environment differences that cannot be aligned

These pass in upstream's CI:

- The bwrap fallback test: upstream runs Linux Bazel tests on remote execution (`//:rbe`).
- `windows-sandbox` `legacy_workspace_write_delete_is_limited_to_writable_roots`: it fails from both C: and D: on
  hosted runners; upstream uses its custom `codex-windows-x64` runners.

## Upstream passes that still need follow-up

- The PTY conpty test looks flaky; it passed in the page-file run.
- Linux shard 2's `codex-exec-server` `exec_process` target failed after one 67.137-second run, with five failed,
  56 passed and 12 ignored cases. Four are the shell snapshot cases documented in
  `docs/upstream-failure-comparison.md` (`blocked_descriptor_path`, `remote_pipe_recovery`, `remote_tty_recovery`,
  `remote_sandbox`); the fifth, `remote_process_preserves_empty_workspace_roots`, is documented there as a separate
  historical timeout. Those historical diagnoses do not by themselves establish each current cause.

## Clippy

`clippy.json` lists the upstream Clippy diagnostics that the Bazel Clippy jobs accept, written as
`path:line: message`, with paths relative to `codex-rs/`. Upstream's source at this commit has all of them:
- `core/src/session/session.rs:1816` `collapsible_if` (Linux and Windows).
- `app-server/tests/suite/v2/sleep.rs:8, 9, 12` unused imports. Upstream commit 4aac2eacc5 (#61382) left them
  behind, and they are still on upstream main. Upstream's Bazel Clippy jobs at 4aac2eacc5 (blocking-ci 43514491801)
  failed or were cancelled before reaching this target. The rust-v0.171.0 release ran no Clippy job.
- `core/tests/suite/scenarios.rs:4558` `match_like_matches_macro`. Upstream's macOS Bazel Clippy fails on the same
  code, at line 4441 at 4aac2eacc5 (blocking-ci 43514491801, job 132192817164).
A run passes only if Bazel's sole errors are failed Clippy actions and each of their diagnostics is listed here.

## macOS x86_64 Bazel (native Intel)

`macos-bazel-x86_64-shard-1..4.json` come from unpatched run 45651600030 on macos-15-intel, which uses four shards and
`--local_test_jobs=4`. They record 113/12, 61/36, 61/0 and 123/7 (executed/failed), with no timeouts.
They are classified as native-Intel environment failures, not upstream failures. Upstream runs its x86_64-apple-darwin
Bazel tests on macos-15-xlarge (Apple silicon), not on Intel hardware, so upstream's behavior here is unverified.
- 36 failures are code-mode-host tests (grpc, grpc_tcp, grpc_notifications, grpc_timing, capacity, settled). The host
  does not print its endpoint within the 10-second wait in `code-mode-host/tests/support/host.rs:32-37`. The same tests
  pass on arm64.
- The rest are app-server-all-test (9), cli-daemon_startup (3), multi-env-copy-file (3), codex-api websocket probe (2)
  and core-all-test (2).
They are accepted only so patched runs can be compared; the slow host start is an open investigation.
