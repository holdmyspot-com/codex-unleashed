# Avoid repeating Bash startup when replaying shell snapshots over SSH

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/47](https://github.com/holdmyspot-com/codex-unleashed/issues/47)
- Applies to: upstream `openai/codex` `rust-v0.160.0`, commit `a956835d020762cb2b570053af06f643a11c0ecc`

## Intent

Remote noninteractive Bash can read .bashrc before replaying cached state. Profiles can execute twice or redefine exec before replay, causing commands to fail. Replay suppresses automatic .bashrc startup while retaining the captured state and environment policy.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(shell_snapshot_v2_filters_profile_exports_and_stays_in_memory) | test(shell_snapshot_v2_capture_failure_falls_back_and_retries) | test(snapshot_failure_retries_are_bounded_and_single_flight)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.160.0](https://github.com/openai/codex/releases/tag/rust-v0.160.0).
