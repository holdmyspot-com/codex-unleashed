# Integration test compiler environment

- Applies to: the queue through `rmcp-stdio/close-after-oversized-line.patch` (entry 104), on upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: every earlier patch in the queue; it changes `defs.bzl` and `codex-rs/linux-sandbox/tests/suite/managed_proxy.rs`.
- Patch: `integration-compiler-environment.patch` (entry 108, owner-approved on 2026-11-25).
- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/69

## Intent

`defs.bzl` passes `rustc_env_files` (the generated environment files that carry the bundled-bubblewrap SHA256) only to the library and binary targets. Integration test targets compile without it, while `managed_proxy.rs` branches on `CODEX_BWRAP_SHA256`; both `managed_proxy` tests fail on the Linux Bazel baseline at `rust-v0.167.0`.

The patch passes `rustc_env_files` to the three integration test targets (the shard-count binary, the ordinary test and the Windows cross-test binary) and adds `bazel_tests_receive_bundled_bwrap_digest`, which fails a Bazel build of the integration tests that lacks the digest. Production sandbox permissions and digest verification are unchanged. The `defs.bzl` hunks are re-based on `rust-v0.171.0`'s integration test targets.

## Verification

Run the Linux Bazel test `//codex-rs/linux-sandbox:linux-sandbox-all-test` on a `rust-v0.171.0` queue, with and without the patch. The managed-proxy tests must fail without it and pass with it, and the guard test must pass. No native run has been made on this base.
