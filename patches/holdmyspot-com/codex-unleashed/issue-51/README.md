# Verify Kitty local-file references without substring false positives

- Issue: [https://github.com/holdmyspot-com/codex-unleashed/issues/51](https://github.com/holdmyspot-com/codex-unleashed/issues/51)
- Applies to: upstream `openai/codex` `rust-v0.160.0`, commit `a956835d020762cb2b570053af06f643a11c0ecc`

## Intent

The Kitty file-reference test rejects the encoded image-byte substring anywhere in a command, although an encoded path can contain that substring. Decode the transmitted file-reference payload and compare it with the canonical frame path.

## Scope and reproduction

This patch repairs the baseline behavior exercised by the focused check. It applies independently to the declared upstream base and introduces no feature flag or project configuration property. The regression preserves the command result, security boundary, or presentation assertions relevant to this issue.

## Verification

Apply the complete release queue to the declared upstream base, then run from
the patched upstream checkout root:

```sh
just test --features codex-v8-poc/sandbox -E 'test(kitty_local_file_pet_image_uses_file_reference_without_inline_payload)'
just fmt-check
just clippy --features codex-v8-poc/sandbox
just test --features codex-v8-poc/sandbox
```

All selected checks expect to pass. The sandbox feature matches the Linux V8 build used by the release verification environment. Verify the emitted patch in a fresh checkout of the declared base; the complete release queue also requires the maintained workspace checks.

## Upstream status

The issue tracks this downstream baseline repair against [upstream 0.160.0](https://github.com/openai/codex/releases/tag/rust-v0.160.0).
