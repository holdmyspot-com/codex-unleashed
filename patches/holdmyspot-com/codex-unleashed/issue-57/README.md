# Strict app-server initialization checks

## Intent

App-server initialization passes strict Clippy checks.

Issue: <https://github.com/holdmyspot-com/codex-unleashed/issues/57>

## Scope

The patch returns the existing time-provider expression directly from its
constructor argument. The strict warning policy applies to the complete target.
This repair has no feature flag or project-specific configuration properties.

## Upstream base

- Tag: `rust-v0.162.1`
- Commit: `092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`
- The patch applies independently to this base.

## Reproduction

The time-provider argument returns a local binding directly from its block.
Clippy reports `let_and_return`, which fails the maintained `-D warnings` check.

## Verification

Apply the patch to a fresh checkout of the upstream base and run its maintained
checks from the checkout root:

```sh
just fmt-check
just bazel-clippy
```

Formatting and the complete strict Clippy target list pass. The repository's
hosted Bazel workflow also checks the Linux, macOS, and Windows target matrix.

## Upstream status

The declared upstream release contains the redundant binding. The issue tracks
the downstream repair until a stable upstream release includes the correction.
