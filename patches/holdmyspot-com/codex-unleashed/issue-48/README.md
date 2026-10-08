# Preserve procfs for explicit sandbox PID inheritance on WSL

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/48](https://github.com/holdmyspot-com/codex-unleashed/issues/48)
- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

## Intent

WSL masking hides inherited procfs even when the caller explicitly requests PID namespace inheritance. Commands that need matching shell and procfs PIDs fail. Explicit inheritance retains procfs while user-namespace isolation blocks foreign root, cwd, and descriptor links. Legacy no-proc fallback retains its mask.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(wsl_masks_preserve_proc_only_with_explicit_pid_inheritance) | test(proc_mount_denial_preserves_legacy_fallback_and_explicit_pid_inheritance) | test(pid_inheritance_is_startup_only_for_process_and_filesystem_helpers)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0).
