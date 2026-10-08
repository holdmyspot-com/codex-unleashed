# Make composer shortcut snapshots independent of host WSL status

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/50](https://github.com/holdmyspot-com/codex-unleashed/issues/50)
- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

## Intent

Composer unit snapshots use non-WSL shortcuts but inherit WSL detection from the host. Give the fixture explicit platform state and cover both paste shortcuts, while production retains host detection.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(bottom_pane::chat_composer::)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0).
