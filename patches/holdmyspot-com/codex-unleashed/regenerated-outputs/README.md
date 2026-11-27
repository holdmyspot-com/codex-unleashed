# Regenerated outputs

- Applies to: upstream `openai/codex` tag `rust-v0.172.0`, commit `3d86e43b075adeba2193ec5c22ac92f55afd82e6`.
- Prerequisites: every other patch in the queue; these patches go last.
- Patches, in order: `regenerate-module-bazel-lock-1.patch` to `-4`, `regenerate-app-server-json-1.patch` to `-4`, `regenerate-app-server-exports-1.patch` and `-2`, `regenerate-bindings-and-config-schemas.patch`.

## Intent

The earlier patches change what upstream's generators read (`codex-rs/Cargo.lock`, `MODULE.bazel`, schemars and ts-rs output) without regenerating the files the generators write. These patches are the generators' output for the whole queue on `rust-v0.172.0`, so the committed schemas, bindings and lock file match the sources.

| Patches | Regenerated files |
| --- | --- |
| `regenerate-module-bazel-lock-1` to `-4` | `MODULE.bazel.lock` |
| `regenerate-app-server-json-1` to `-4` | the app-server JSON schemas under `codex-rs/app-server-protocol/schema/json` |
| `regenerate-app-server-exports-1`, `-2` | the precomputed export manifests under `codex-rs/app-server-protocol/schema/precomputed` |
| `regenerate-bindings-and-config-schemas` | the TypeScript bindings in `schema/typescript`, `codex-rs/core/config.schema.json`, the hooks model-request schema and the Python SDK `v2_all.py` |

The repository rejects a blob over 512000 bytes, and each patch is a blob, so the output is split into patches under 500000 bytes. `MODULE.bazel.lock` is split into consecutive groups of hunks of one file: apply the four patches in order, and the lock file is complete only after the fourth.

## Regenerating

Run the `baseline-platform-lints.yml` workflow with `generators="app-server-schema config-schema hooks-schema bazel-lock"` on the queue's tree with all patches before these applied, or run the same generator commands locally with the workspace version at `0.0.0`, as the workflow's generators job does. These patches for `rust-v0.172.0` were generated locally (Bazel 9.2.0, Rust 1.99.1, `uv`) on the tree of the first 108 patches; the later queue entries 120 to 122 change test code only. No workflow run has produced them yet. Split the diff by file group, and `MODULE.bazel.lock` by hunk, to keep each patch under the size limit. Replace these patches with the new ones and keep the order above. A change to any earlier patch that touches a generator input needs a new run.
