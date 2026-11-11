# Bazel download fallbacks

- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: none. The patch changes only `patches/rules_rs_zlib_snapshot_urls.patch`, which no other patch in the queue touches.
- Upstream status: downstream fix. rules_rs later added Launchpad URLs for newer zlib packages
  ([hermeticbuild/rules_rs@ee9a99d0a8](https://github.com/hermeticbuild/rules_rs/commit/ee9a99d0a8)); drop this patch
  when upstream's rules_rs pin includes that change.

## Intent

The Linux rustc toolchain repository still downloads its pinned zlib package when `snapshot.ubuntu.com` is unavailable.

## Cause

Upstream's `rules_rs_zlib_snapshot_urls.patch` gives each pinned `zlib1g` package one URL on `snapshot.ubuntu.com`. A
503 from that server failed every Linux Bazel shard of Clippy run `43003234610`.

## Scope

`zlib-launchpad-fallback.patch` turns each package's `url` into a list: the snapshot URL first, then the same file on
`launchpad.net`. `repository_ctx.download_and_extract` tries the URLs in order, so Launchpad is used only when the
snapshot download fails, and the unchanged `sha256` is compared whichever server answers. The patch copies no dependency
source and leaves `MODULE.bazel` and `MODULE.bazel.lock` unchanged.

## Verification

On 2026-11-11 both Launchpad files matched the pinned checksums. A one-off Linux job (run `43094373128`, job
`130779297945`) pointed `snapshot.ubuntu.com` at `0.0.0.0` in `/etc/hosts`, fetched the zlib packages through Bazel, and
succeeded: Bazel logged `Download from https://snapshot.ubuntu.com/... failed: ... Connection refused`, and the fetch
did not fail, so the packages came from the Launchpad URL, the only other URL listed, with the pinned checksums.
