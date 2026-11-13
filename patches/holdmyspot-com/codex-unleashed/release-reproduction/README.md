# Deterministic release tar archives

- Applies to: upstream `openai/codex` commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a` after the 68 patches selected before this patch.
- Prerequisites: all 68 earlier patches in `patches/README.md`.
- Patch: `deterministic-archives.patch` (queue entry 69).

## Intent

Equivalent package trees must produce the same gzip and zstd tar bytes despite differences in filesystem owners, timestamps, directory modes, non-executable permission bits, or creation order. The symbols release archive uses the same gzip tar writer while preserving its `codex-symbols-<target>` root directory. ZIP output is unchanged and outside this patch's determinism guarantee.

## What changed

The package archive writer fixes gzip and tar metadata, sorts POSIX relative names, preserves file payloads, executable roles and symlink targets, and rejects unsupported special files. It uses the same normalized tar stream before the existing zstd compressor. The symbols archive caller now uses this writer instead of host `tar`.

## Verification

The focused tests compare gzip and zstd SHA-256 for separately staged equivalent trees, inspect extracted payloads and metadata, verify changed payloads change both archive digests, reject a FIFO, retain the Windows executable role, and exercise the symbols caller with equal payloads and different mtimes. These fixtures establish the archive assembly boundary only; they do not establish native build or published-release reproducibility.
