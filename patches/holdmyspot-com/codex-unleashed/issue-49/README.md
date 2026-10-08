# Use a mountable procfs denial to test snapshot descriptor fallback

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/49](https://github.com/holdmyspot-com/codex-unleashed/issues/49)
- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

## Intent

The Linux snapshot fallback fixture masks the stable /proc directory under explicit PID inheritance, then verifies actual environment replay, independent readers, and preserved stdin. On macOS, the fallback fixture accepts an unset Bash source path during environment replay while still rejecting descriptor paths. Both fixtures retain nounset shell behavior and the protected transport checks.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(shell_snapshot_concurrent_replays_keep_independent_readers)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0).
