# Verify a release

Use the verification script from a trusted Codex Unleashed checkout to check downloaded release assets against their
manifest, package receipts and reconstructed source. Bash and Git are required. GitHub CLI with `gh attestation
verify` support is also required when checking attestations.

From the root of that trusted checkout, build the verification tooling with Maven and JDK 27. The generated launcher
includes its Java runtime. Keep this shell open for the remaining commands, which use `CODEX_TOOLING`:

```bash
tooling_build="$PWD/.cat/work/temp/release-verification"
mkdir -p "$tooling_build/tmp"
TMPDIR="$tooling_build/tmp" TMP="$tooling_build/tmp" TEMP="$tooling_build/tmp" \
  mvn -q -B -ntp -f tooling/cli/pom.xml -Pruntime -DskipTests \
  "-Dtooling.build.directory=$tooling_build" "-Djava.io.tmpdir=$tooling_build/tmp" package
export CODEX_TOOLING="$tooling_build/runtime/bin/codex-tooling"
```

In GitHub Actions, use the trusted checkout's `.github/actions/setup-tooling` composite action instead; it builds the
runtime and exports `CODEX_TOOLING` for later steps. Remove the local `release-verification` build directory after
finishing verification.

Download every asset for the selected release into a new directory:

```bash
gh release download 'rust-v0.167.0+1' --repo holdmyspot-com/codex-unleashed --dir release-assets
```

Before using source references from the downloaded manifest, authenticate that manifest with the fixed consumer policy:

```bash
bash "$CODEX_TOOLING" release-manifest authenticate --manifest release-assets/release-manifest.json
```

Success verifies the manifest's GitHub attestation for the configured repository and signer workflow. It authenticates
the manifest only; it does not verify the release assets, receipts, checksums or reconstructed source. Run the full
verification command below to check those records and files.

Read `release-assets/release-manifest.json` to obtain `fork_commit`, `upstream.commit` and `upstream.tag`. Check out that
fork commit in `patch-repo` and that upstream commit in `upstream-source`. Fetch the upstream tag as well; verification
requires it to resolve to the pinned commit. The fork checkout supplies the raw patches and their order from
`patches/README.md`.

From the trusted verification checkout, reconstruct the source with the same patch-application identity used by the
release workflow. The parser below belongs to that trusted
checkout; the downloaded fork README is data. Do not run scripts from the downloaded fork checkout before verifying
its publisher identity:

```bash
read -r -a patches <<< "$(bash "$CODEX_TOOLING" upstream-auto-release patches --readme patch-repo/patches/README.md)"
patch_repo=$(cd patch-repo && pwd)
for patch in "${patches[@]}"; do
  git -C upstream-source -c core.longpaths=true -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
    am --committer-date-is-author-date "$patch_repo/$patch"
done
bash "$CODEX_TOOLING" align-workspace-lock --source-root upstream-source
```

The last command applies the release's one source change that is not a patch. Upstream's release commit sets the
version in `codex-rs/Cargo.toml` but leaves the workspace packages in `codex-rs/Cargo.lock` at `0.0.0`. The command
sets each of those packages to the manifest's version, changing only their `version` lines and any `"name version"`
dependency reference that names one, and commits the lockfile with fixed author, committer, message and dates, so the
resulting HEAD and tree equal the ones the release recorded. It changes nothing and makes no commit when the lockfile
already matches, so it is also correct for a release whose queue still contained the retired
`workspace_package_versions.patch`.

Run verification from the trusted checkout, supplying all three directories:

```bash
scripts/verify-release.sh release-assets --patch-repo patch-repo --upstream-source upstream-source --verify-attestations
```

This checks every published asset's size and SHA256, the complete six-target inventory, agreement among package
receipts, the exact packaged CLI version observations, binary hashes inside the archives, and the source HEAD, tree
and Cargo.lock digest. It independently applies the pinned raw patches to upstream and compares the resulting Git tree;
matching assertions in the manifest and receipts alone are insufficient. Tracked source modifications, extra untracked
source inputs, different patch bytes, missing targets, duplicate asset names and extra release files fail verification.
Build outputs ignored by Git are outside the source identity.

Attestation verification invokes `gh attestation verify` for every payload, receipt, manifest and checksum asset,
requiring repository `holdmyspot-com/codex-unleashed` and signer workflow
`holdmyspot-com/codex-unleashed/.github/workflows/upstream-auto-release.yml`. The downloaded manifest cannot change
those trusted identities. Any failed attestation fails the whole operation. Credentials and packaged executables are
not printed or executed by the verifier.

For checksum and source verification without network attestation checks, omit the flag:

```bash
scripts/verify-release.sh release-assets --patch-repo patch-repo --upstream-source upstream-source
```

The completion message explicitly says attestations were not checked. Checksums establish byte consistency; anyone who
can replace both payloads and checksums can preserve that consistency. Hosted attestations supply the independent
publisher/workflow check. Neither mode proves a binary is bit-for-bit reproducible from source.

## Reproduce a release

Use the trusted launcher configured above, a complete downloaded release directory, and the selected target's native
build host with its production prerequisites. Reproduction also needs JDK 27 and Maven to provision the authenticated
fork's Java recipe helpers. The [reproduction workflow](../.github/workflows/reproduce-release.yml) prepares the supported
native runners from the recorded source's setup actions; dispatch it with the published release tag and one target.

```bash
bash scripts/reproduce-release.sh release-assets/release-manifest.json \
  --target x86_64-unknown-linux-musl --output-dir reproduction-output
```

The output directory must be new or empty. The command authenticates the manifest, reconstructs its exact fork/upstream
commits and ordered patches, verifies every release asset and attestation, and invokes that source's shared native
recipe with a fresh Cargo target and staging tree. Downloaded published binaries never seed that build. Missing recorded
shared operations or actual native prerequisites fail with a diagnostic; older releases without those interfaces are
unsupported. Valid dependency caches remain available to the selected production recipe.

`reproduction-output/reproduction-result.json` records the expected and actual final compressed CLI archive SHA-256,
failed stage and diagnostic, observed input differences, and available binary hashes. The rebuilt archive and operation
evidence remain in the output after owned scratch cleanup. Exit 0 means the finalized compressed archive matched;
exit 1 means authentication, source, prerequisite, build, cleanup, or digest failure; exit 2 means malformed input or an
unsupported target. Equal unpacked binary payloads cannot turn a compressed archive mismatch into success. Different
observed host images, tool paths, or checkout paths are diagnostic and allow the unchanged recipe to attempt the build.

A result applies to that target and release. Other targets remain unverified until their own native reproduction runs.
Two builds can help diagnose a mismatch; they are not a prerequisite for attempting the authenticated comparison.

## Manifest and receipt contract for release maintainers

The tooling operation `release-manifest` has four commands. Each returns 0 on success or 1 with a diagnostic
on invalid input, missing files, Git failure or failed attestation; argument syntax errors return 2. `authenticate`
validates the manifest schema and fixed consumer policy before asking GitHub CLI to verify that manifest's attestation.

`receipt` records one target's packaged files. Before invoking it, run that target's packaged CLI natively with
`--version` through `package-identity binary --output assets/codex-version-TARGET.txt`, which accepts the patched CLI's
two-line banner and stores its first line. That stored text, allowing a final newline, must be
`codex-cli VERSION_WITH_BUILD`. A correctly named archive does not establish its version. Source must remain
clean at the receipt boundary; investigate unexpected tracked modifications rather than restoring them after building.

Run `release-native-inputs` on the native package host after both package operations complete and legal documents
have been finalized. The patched build actions export `CODEX_PACKAGE_BUILD_RESULT` and `CODEX_PACKAGE_ARCHIVE_RESULT`
through `GITHUB_ENV`; these name their completed operation directories, respectively
`$RUNNER_TEMP/codex-build-operations-$TARGET` and `$RUNNER_TEMP/codex-archive-operations-$TARGET`. They are outputs of the
actions. The producer requires both directories' completion markers, metadata and recorded NUL-delimited argument and
environment files. It reads those records as data.

The upstream V8 setup action downloads and verifies a local archive before exporting `RUSTY_V8_ARCHIVE`. Pass that
actual configured file explicitly. `--package-root` is the parent of both finalized `codex-package-TARGET` and
`codex-app-server-package-TARGET` directories; the workflow uses `RUNNER_TEMP`. Keep their resource trees and the V8
archive unchanged through receipt creation. The producer inventories regular resource files in both packages,
including supplied legal documents, and rejects conflicting copies of the same resource path.

```bash
native_options=(
  --build-result "$CODEX_PACKAGE_BUILD_RESULT" --archive-result "$CODEX_PACKAGE_ARCHIVE_RESULT"
  --target "$TARGET" --package-root "$RUNNER_TEMP" --output "$RUNNER_TEMP/native-inputs.json"
  --runner-arch "$RUNNER_ARCH" --configured-v8-archive "$RUSTY_V8_ARCHIVE"
)
if [[ ${ImageOS+x} ]]; then native_options+=(--image-os "$ImageOS"); fi
if [[ ${ImageVersion+x} ]]; then native_options+=(--image-version "$ImageVersion"); fi
bash "$CODEX_TOOLING" release-native-inputs "${native_options[@]}"
```

The optional runner architecture and image fields preserve a supplied empty string; omitted fields remain null.
The producer also probes its current host's fixed tool list for availability, path and version. Those are observations
at collection time; the recorded build and archive argument vectors and approved environment values come from the
completed operations. The output stays outside the release assets and becomes the required input to `receipt`:

```bash
bash "$CODEX_TOOLING" release-manifest receipt \
  --source upstream-source --patch-repo patch-repo --target x86_64-unknown-linux-musl \
  --version '0.167.0+1' --build-number 1 --upstream-tag rust-v0.167.0 \
  --upstream-commit "$upstream_commit" --fork-commit "$fork_commit" --run-id "$run_id" --run-attempt 1 \
  --assets assets --output assets/package-receipt-x86_64-unknown-linux-musl.json \
  --native-inputs "$RUNNER_TEMP/native-inputs.json"
```

Each target directory contains its `codex-package-TARGET.tar.gz`, `codex-symbols-TARGET.tar.gz`, and version proof;
the x86_64 Linux directory also supplies `install.sh` and `install.ps1`. An optional
`codex-app-server-package-TARGET.tar.gz` is supported and inventoried separately. Each distributed server archive must contain
its own regular `codex-app-server` or `codex-app-server.exe` member, whose hash is recorded separately from the CLI.
The package receipt records archive hashes and executable-member hashes separately. Its required `--native-inputs`
file supplies the `inputs.native_build` record: runner observations, the `build_binaries` and `archive_packages`
argument vectors and approved diagnostic environment values, one status/path/version record for each named tool, and
sorted resource path/size/SHA256 observations. Present empty environment values remain distinct from omitted keys.
These observations support diagnosis; they do not establish complete compiler input capture or require a reproduction
host to use the same image, tool path or environment. The receipt also records
the receipt operation and current Git and host observations. Its Python version comes from the supplied native-inputs
tool observation; the receipt does not infer earlier compiler invocations. The native caller and workflow attestation
establish the origin of the version proof.

Merge the six target directories without replacing duplicate names, then assemble:

```bash
bash "$CODEX_TOOLING" release-manifest assemble --receipts assets --assets assets \
  --output assets/release-manifest.json
```

Assembly requires exactly one receipt per supported target, both installers, all CLI/symbol archives and version
proofs. Receipts must agree on release version/build, upstream identity, fork commit, ordered patches and source
identity. Assembly rejects unsupported or uninventoried files. The caller must separately collect receipts if
`--receipts` names another directory; matching copies must also be present in `--assets` for publication.

`format_version` 1 has these fields in both receipts and the manifest:

| Field | Meaning |
| --- | --- |
| `repository`, `signer_workflow` | Fixed consumer policy stated above. |
| `release` | `tag` (`rust-vX.Y.Z+BUILD`), numeric `version` (`X.Y.Z+BUILD`), positive `build_number`. |
| `upstream` | `repository` (`openai/codex`), stable `tag`, pinned 40-character lowercase Git `commit`. |
| `fork_commit` | Exact fork Git commit containing the queue and patch bytes. |
| `patches` | Ordered `path` relative to the fork checkout and raw-file `sha256`; paths remain below `patches/`. |
| `source` | Clean patched Git `commit`, Git `tree` and `cargo_lock_sha256` for the committed, aligned `codex-rs/Cargo.lock`. |

A receipt additionally has `target`, `run` (`id`, `attempt`), `observed_cli_version`, `inputs`, `artifacts` and `binaries`.
A manifest additionally has `receipts` and `artifacts`. Each artifact records safe basename `name`, semantic `kind`,
`target`, byte `size` and lowercase `sha256`. Each binary records its containing `archive`, slash-separated member
`path` relative to that archive, byte `size` and `sha256`. Unknown fields, duplicate JSON keys and malformed required
values fail validation. There are at most 128 assets, 512 patches, 16 executable members per target, 4 MiB per JSON
manifest/receipt, and 4096 bytes per version proof. These bounds constrain verification of untrusted documents.

The six targets are aarch64 and x86_64 for Apple Darwin, Windows MSVC and Linux musl. Receipts themselves are published
and included in the manifest artifact inventory. `release-manifest.json` and `SHA256SUMS` are excluded from that array
to avoid self-hash circularity. `SHA256SUMS` includes every receipt, payload and the manifest, and excludes itself.
Attest both generated files along with all other assets before publishing. Verify checksum/source and attestation
claims separately from any later reproduction check.
