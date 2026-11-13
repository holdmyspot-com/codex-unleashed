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

## Shared package operations

- Applies after upstream commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`, the 68 earlier selected patches, and queue 69 (`deterministic-archives.patch`).
- Patch: `shared-package-builder.patch` (queue entry 70).

The Cargo package action and package assembly action use `.github/scripts/codex-package-operations.sh` for binary selection, profile setup, Cargo builds, symbol/PDB preparation, and both package variants. The standalone caller can invoke the same operations and receives the same fixed binary list, ordered Cargo arguments, platform branches, and package arguments. Rust setup, platform tool setup, Bazel builds, Python/uv setup, and smoke tests remain in their existing action steps.

Build and archive operations write separate result directories selected by the caller. The result format stores one-line metadata, fixed profile/export assignments, NUL-delimited command argument-vector records, and only the approved determining environment variables. Files inside a result directory are addressed relative to that explicitly supplied result-directory base. Historical command argument-vector records and environment observations preserve exact values for diagnostics; they are never path locators and must never be resolved or replayed as commands. Consumers construct fresh arguments through the shared operations. `completed` appears only after the operation and its records finish; a missing marker means the operation did not complete.

The records expose observed inputs at the operation boundary, including selected target/profile, inherited toolchain settings, source commit, final stripped bwrap digest, and actual command arguments. They do not prove that all dependency build-script-selected tools, SDKs, or prebuilt resources are captured. Native-input equivalence and clean binary reproduction require the later recorder work and are outside this patch's acceptance claim.

The focused Bash suite runs the real dispatcher with fixture executables and paths containing spaces. It checks selection and profile output, build/archive argument and environment forwarding, command order, final stripped-helper digest, failure status, and incomplete-result behavior. Fixture checks establish shell wiring only; they do not build native binaries or validate archive contents.

Verification after applying the patch:

```sh
bash -n .github/scripts/codex-package-operations.sh
bash -n .github/scripts/test-codex-package-operations.sh
bash .github/scripts/test-codex-package-operations.sh
shellcheck .github/scripts/codex-package-operations.sh .github/scripts/test-codex-package-operations.sh
CODEX_REPO_ROOT="$PWD" python3 -m unittest discover -s scripts/codex_package -p 'test_*.py'
git diff --check
```
