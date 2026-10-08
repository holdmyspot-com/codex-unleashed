# Align macOS Seatbelt metadata parameter fixtures

- Issue: [codex-unleashed#58](https://github.com/holdmyspot-com/codex-unleashed/issues/58)
- Applies to: upstream `openai/codex` `rust-v0.161.0`, commit `979011409de0a60b52f179721948e65531d26144`

## Intent

The macOS sandbox checks validate the generated protected-path mappings in their current order. They retain checks that configuration and Git hooks stay read-only and ordinary workspace files remain writable.

## Scope and reproduction

The two baseline fixtures expect different `.git`, `.agents`, and `.codex` exclusion indexes from the generated policy. This patch changes only those expected indexes. It applies independently to the declared base and preserves production sandbox permissions, the complete parameter list, and command arguments. It adds no feature flag or project configuration property.

## Verification

Run on macOS from the patched upstream checkout root:

```sh
just test -p codex-sandboxing -E 'test(create_seatbelt_args_with_read_only_git_and_codex_subpaths) | test(create_seatbelt_args_for_cwd_as_git_repo)'
just fmt-check
```

Both fixtures expect to pass, including actual Seatbelt execution. The maintained macOS Bazel gate also runs the complete `//codex-rs/sandboxing:sandboxing-unit-tests` target. Verify the emitted patch on a fresh checkout of the declared base, then run the complete maintained release queue checks.

## Upstream status

This is a baseline test correction against [upstream 0.161.0](https://github.com/openai/codex/releases/tag/rust-v0.161.0). The production policy is unchanged.
