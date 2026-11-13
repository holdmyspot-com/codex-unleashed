# Verify a release

Use the verification script from a trusted Codex Unleashed checkout to check downloaded release assets against their
manifest, package receipts and reconstructed source. Python 3 and Git are required. GitHub CLI with `gh attestation
verify` support is also required when checking attestations.

Download every asset for the selected release into a new directory:

```bash
gh release download 'rust-v0.167.0+1' --repo holdmyspot-com/codex-unleashed --dir release-assets
```

Before using source references from the downloaded manifest, authenticate that manifest with the fixed consumer policy:

```bash
python3 -I .github/scripts/release-manifest.py authenticate --manifest release-assets/release-manifest.json
```

Success verifies the manifest's GitHub attestation for the configured repository and signer workflow. It authenticates
the manifest only; it does not verify the release assets, receipts, checksums or reconstructed source. Run the full
verification command below to check those records and files.

Read `release-assets/release-manifest.json` to obtain `fork_commit`, `upstream.commit` and `upstream.tag`. Check out that
fork commit in `patch-repo` and that upstream commit in `upstream-source`. Fetch the upstream tag as well; verification
requires it to resolve to the pinned commit. The fork checkout supplies the raw patches and their order from
`patches/README.md`.

From the trusted verification checkout, reconstruct the source with the same patch-application identity used by the
release workflow. The parser below belongs to that trusted checkout; the downloaded fork README is data. Do not run
scripts from the downloaded fork checkout before verifying its publisher identity:

```bash
read -r -a patches <<< "$(python3 -I .github/scripts/upstream-auto-release.py patches --readme patch-repo/patches/README.md)"
patch_repo=$(cd patch-repo && pwd)
for patch in "${patches[@]}"; do
  git -C upstream-source -c core.longpaths=true -c user.name='Gili Tzabari' -c user.email='cowwoc2020@gmail.com' \
    am --committer-date-is-author-date "$patch_repo/$patch"
done
```

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

## Manifest and receipt contract for release maintainers

The helper `.github/scripts/release-manifest.py` has four commands. Each returns 0 on success or 1 with a diagnostic
on invalid input, missing files, Git failure or failed attestation; argument syntax errors return 2. `authenticate`
validates the manifest schema and fixed consumer policy before asking GitHub CLI to verify that manifest's attestation.

`receipt` records one target's packaged files. Before invoking it, run that target's packaged CLI natively with
`--version`, storing its stdout in `assets/codex-version-TARGET.txt`. Its exact observed text, allowing a final newline,
must be `codex-cli VERSION_WITH_BUILD`. A correctly named archive does not establish its version. Source must remain
clean at the receipt boundary; investigate unexpected tracked modifications rather than restoring them after building.

```bash
python3 -I .github/scripts/release-manifest.py receipt \
  --source upstream-source --patch-repo patch-repo --target x86_64-unknown-linux-musl \
  --version '0.167.0+1' --build-number 1 --upstream-tag rust-v0.167.0 \
  --upstream-commit "$upstream_commit" --fork-commit "$fork_commit" --run-id "$run_id" --run-attempt 1 \
  --assets assets --output assets/package-receipt-x86_64-unknown-linux-musl.json --native-inputs native-inputs.json
```

Each target directory contains its `codex-package-TARGET.tar.gz`, `codex-symbols-TARGET.tar.gz`, and version proof;
the x86_64 Linux directory also supplies `install.sh` and `install.ps1`. An optional
`codex-app-server-package-TARGET.tar.gz` is supported and inventoried separately. Each distributed server archive must contain
its own regular `codex-app-server` or `codex-app-server.exe` member, whose hash is recorded separately from the CLI.
The package receipt records archive hashes and executable-member hashes separately. Its required `--native-inputs`
file supplies the `inputs.native_build` record: runner observations, the `build_binaries` and `archive_packages`
argument vectors and approved diagnostic environment values, one status/path/version record for each named tool, and
sorted resource path/size/SHA256 observations. Present empty environment values remain distinct from omitted keys.
These observations preserve the values the builder reported for diagnosis; they do not establish complete compiler
input capture or require a reproduction host to use the same image, tool path or environment. The receipt also records
the operation, Python, Git and host values visible at the receipt boundary; it does not infer earlier compiler
invocations. The native caller and workflow attestation establish the origin of the version proof.

Merge the six target directories without replacing duplicate names, then assemble:

```bash
python3 -I .github/scripts/release-manifest.py assemble --receipts assets --assets assets \
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
| `source` | Clean patched Git `commit`, Git `tree` and `cargo_lock_sha256` for `codex-rs/Cargo.lock`. |

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
