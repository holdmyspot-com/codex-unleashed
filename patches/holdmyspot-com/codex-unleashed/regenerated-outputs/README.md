# Regenerated outputs

- Applies to: upstream `openai/codex` tag `rust-v0.171.0`, commit `ccf9be8cc458bd6b8336fe84d68d8893f2691a5e`.
- Prerequisites: every other patch in the queue; these patches go last.
- Patches, in order: `regenerate-module-bazel-lock-1.patch` to `-4`, `regenerate-app-server-json-1.patch` to `-4`, `regenerate-app-server-exports-1.patch` and `-2`, `regenerate-bindings-and-config-schemas.patch`.

## Intent

The earlier patches change what upstream's generators read (`codex-rs/Cargo.lock`, `MODULE.bazel`, schemars and ts-rs output) without regenerating the files the generators write. These patches are the generators' output for the whole queue on `rust-v0.171.0`, so the committed schemas, bindings and lock file match the sources.

| Patches | Regenerated files |
| --- | --- |
| `regenerate-module-bazel-lock-1` to `-4` | `MODULE.bazel.lock` |
| `regenerate-app-server-json-1` to `-4` | the app-server JSON schemas under `codex-rs/app-server-protocol/schema/json` |
| `regenerate-app-server-exports-1`, `-2` | the precomputed export manifests under `codex-rs/app-server-protocol/schema/precomputed` |
| `regenerate-bindings-and-config-schemas` | the TypeScript bindings in `schema/typescript`, `codex-rs/core/config.schema.json`, the hooks model-request schema and the Python SDK `v2_all.py` |

The repository rejects a blob over 512000 bytes, and each patch is a blob, so the output is split into patches under 500000 bytes. `MODULE.bazel.lock` is split into consecutive groups of hunks of one file: apply the four patches in order, and the lock file is complete only after the fourth.

## Regenerating

Run the `baseline-platform-lints.yml` workflow with `generators="app-server-schema config-schema hooks-schema bazel-lock"` on the queue's tree with all patches before these applied. The run that produced these patches is `45114435647`. Download its diff and split it by file group, and split `MODULE.bazel.lock` by hunk, to keep each patch under the size limit. Replace these patches with the new ones and keep the order above. A change to any earlier patch that touches a generator input needs a new run.
