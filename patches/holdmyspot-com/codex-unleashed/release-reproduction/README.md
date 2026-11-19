# Deterministic release tar archives

- Applies to: upstream `openai/codex` commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a` after the 69 patches selected before this patch.
- Prerequisites: all 72 earlier patches in `patches/README.md`.
- Patch: `deterministic-archives.patch` (queue entry 73).

## Intent

Equivalent package trees must produce the same gzip and zstd tar bytes despite differences in filesystem owners, timestamps, directory modes, non-executable permission bits, or creation order. The symbols release archive uses the same gzip tar writer while preserving its `codex-symbols-<target>` root directory. ZIP output is unchanged and outside this patch's determinism guarantee.

## What changed

The package archive writer fixes gzip and tar metadata, sorts POSIX relative names, preserves file payloads, executable roles and symlink targets, and rejects unsupported special files. It uses the same normalized tar stream before the existing zstd compressor. The symbols archive caller now uses this writer instead of host `tar`.

## Verification

The focused tests compare gzip and zstd SHA-256 for separately staged equivalent trees, inspect extracted payloads and metadata, verify changed payloads change both archive digests, reject a FIFO, retain the Windows executable role, and exercise the symbols caller with equal payloads and different mtimes. These fixtures establish the archive assembly boundary only; they do not establish native build or published-release reproducibility.

## Shared package operations

- Applies after upstream commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`, the 72 earlier selected patches, and queue 73 (`deterministic-archives.patch`).
- Patch: `shared-package-builder.patch` (queue entry 74).

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

## Archive command

`archive-command.patch` is queue entry 75 and requires entries 1–74. It exposes the existing package archive writer to release tooling through a module command. Archive creation uses the same gzip and zstd compression arguments and normalized tar bytes as direct calls to `write_archive`.

From the patched upstream checkout, write a package archive:

```sh
CODEX_REPO_ROOT="$PWD" PYTHONPATH="$PWD/scripts" python3 -m codex_package.archive write \
  --package-dir staging/codex-package-x86_64-unknown-linux-gnu \
  --archive-output archives/codex-package-x86_64-unknown-linux-gnu.tar.gz
```

Use a `.tar.zst` output path to select zstd compression. An existing output is rejected unless `--force` is supplied; that option replaces the archive using the writer's existing replacement behavior. Successful writes produce no terminal output.

To obtain the writer's selected zstd executable and fixed arguments for archive inspection:

```sh
CODEX_REPO_ROOT="$PWD" PYTHONPATH="$PWD/scripts" python3 -m codex_package.archive resolve-zstd
```

The resolver writes one JSON argument array followed by a newline. Both commands exit 0 on success, 2 for invalid invocation, or 1 when the archive writer or resolver fails; failure diagnostics go to stderr.

The portable module tests compare direct and command-created gzip/zstd bytes, verify resolver output, reject invalid invocations without creating an archive, and exercise failure status and forced replacement. Run them with:

```sh
CODEX_REPO_ROOT="$PWD" python3 scripts/codex_package/test_archive.py ArchiveModuleCommandTest
```

## Code-mode symbol validation

`code-mode-symbol-validation.patch` is queue entry 83 and requires entries 1–82 on upstream commit
`086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.

Before stripping `codex-code-mode-host` on Linux or macOS, the symbols archive command requires exactly one defined,
exact demangled `codex_code_mode_host::main` symbol. A failed symbol-tool invocation stops packaging with its failure
status. On macOS, the dSYM must also contain exactly one regular, nonempty DWARF file. Rejected symbols or DWARF
members stop packaging before stripping. The Windows PDB path and packaging of other binaries retain their existing
behavior.

After applying the queue, run the focused shell-boundary fixtures and static checks from the patched upstream root:

```sh
python3 .github/scripts/test-code-mode-symbol-validation.py
bash -n .github/scripts/archive-release-symbols-and-strip-binaries.sh
shellcheck .github/scripts/archive-release-symbols-and-strip-binaries.sh
git diff --check
```

The eight fixture tests cover tool invocation, validation decisions, failure propagation, and validation before
stripping. Native Linux and macOS release checks must separately validate real symbol-tool output and the resulting
symbol archives.

## Dev Drive volume classification

`dev-drive-volume-predicate.patch` is queue entry 92 and requires entries 1–91.
The setup command classifies non-ReFS volumes as ordinary volumes. For ReFS, it opens the filesystem root
and queries the Dev Volume flag through the Windows API; query failures stop setup. Trust status is separate
from volume classification. The compiled helper must resolve to exactly one expected type.

The command is safe to run again in one job. When `CI_BUILD_ROOT` names a bare drive letter whose volume passes
the Dev Drive test, or when the owned `codex-dev-drive.vhdx` is already attached and one of its partitions passes it,
the command exports that same build root and removes or recreates nothing. An attached owned VHD without a Dev Drive
volume stops setup, because Windows refuses to remove an attached VHD file. A `CI_BUILD_ROOT` value that is not a bare
drive letter, names no present volume or names a volume that is not a Dev Drive is not reused.

The 29 controlled cases cover classification, query failures, helper type selection, the BFS boundary and reuse of a
provisioned Dev Drive.
The native Windows check additionally exercised real volume creation, BFS allowlisting and remounting,
the exact attached BFS instance, and publication of one build-root entry while preserving the existing D: volume.

## MSVC environment reuse

`msvc-environment-reuse.patch` is queue entry 98 and requires entries 1–97.
The Windows package job configures the Visual Studio developer environment in `prepare-bazel-ci`, in the MSVC setup
step, and again when the Cargo package build repeats its setup. Every `VsDevCmd.bat` run prepends the same tool
directories, about 2,100 characters, to `PATH`, and the next run passes that `PATH` to `cmd.exe`, which rejects a line
longer than 8,191 characters. The third run failed with "The input line is too long" and never exported
`VCToolsInstallDir`.

The setup script now skips `VsDevCmd.bat` when `VCToolsInstallDir` is set and its `bin\Host<host>\<target>` directory
is already on `PATH`. The comparison ignores case, slash style and trailing separators. It still derives the linker,
builds the ARM64 wrapper when needed, and writes the linker variables, but it exports nothing else. A different host or
target, an unset `VCToolsInstallDir` or a missing directory runs `VsDevCmd.bat` as before.

The eight controlled cases in `.github/scripts/test-setup-msvc-env.ps1` model the 8,191-character limit with a fake
`VsDevCmd.bat` and run under PowerShell on Linux or macOS. They cover a first run, reuse, case and separator variants,
three runs in one job, another target, another host, an unset tools directory and a missing directory.

## Workspace lockfile versions

Earlier queues carried `workspace_package_versions.patch`, which changed 171 `version = "0.0.0"` lines in `codex-rs/Cargo.lock` to `0.167.0`. It fixed one release version and broke the test and lint workflows, which rewrite `workspace.package.version` to `0.0.0` before applying the queue. The patch is retired, and the release workflow performs the same edit after the last patch with `codex-tooling align-workspace-lock --source-root ROOT`, taking the version from `codex-rs/Cargo.toml`, and commits it with fixed author, message and dates so that the recorded source commit and tree are reproducible. It makes no commit when the lockfile already matches. For 0.167.0 the resulting lockfile is byte-identical to the one the retired patch produced. See [package identity](../../../../docs/package-identity.md).
