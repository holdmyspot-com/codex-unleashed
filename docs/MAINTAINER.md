# Maintainer Guide

## Add A New Patch

1. Review the issue and confirm the scope, definition of done, and whether this repository should carry the fix.
2. Create the fix against a clean checkout of the upstream `openai/codex` release in use.
3. Add the patch under the layout described in [patches/README.md](../patches/README.md).
4. Add a regression test before implementing the fix and verify that it fails for the unfixed code.
5. Implement the fix and verify that the same test passes.
6. Match the surrounding upstream test style rather than introducing repository-specific test conventions.
7. Record the regression test and its before/after results in the patch's `README.md`.

## Build And Release

Releases are built by the workflows under [.github/workflows](../.github/workflows). The [automatic release workflow](../.github/workflows/upstream-auto-release.yml) detects each new stable upstream release, applies the patch queue, and runs the lint, test, and release-build gates. See [release-build-checkpoints.md](release-build-checkpoints.md) for the checkpoints and [build-baseline.md](build-baseline.md) for the baseline.

Verify that release notes mention the upstream tag, upstream commit SHA, patch list, supported platforms, and checksums.

## Remove A Patch After Upstream Fixes It

1. Confirm the fix is present in an upstream release or commit.
2. Remove the corresponding patch directory.
3. Confirm the remaining patches still apply cleanly.
4. Note the removal in the next release notes.

## Accounts, Billing, And Commercial Terms

- Codex Unleashed is intended to require an account and to bill usage by output tokens. No price, rate, tier, or allowance is published; see [COMMERCIAL_TERMS.md](COMMERCIAL_TERMS.md).
- Customer-facing billing documentation is deferred to a later billing milestone.
- For paid professional work with custom terms, use a separate written agreement covering support, warranty, SLA, and liability terms.

This document is operational guidance, not legal advice. Review customer-facing terms with a lawyer before relying on them.
