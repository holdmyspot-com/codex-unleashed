# Release build cache

Release builds use the GitHub Actions cache for dependency downloads and a
GHCR cache for compiled Cargo target trees. Compiled caches are shared across
upstream versions and patch sets when the target, compiler, and compiler mode
match:

```text
ghcr.io/holdmyspot-com/cargo-cache:cargo-v2-<target>-<mode>-<compiler-fingerprint>
```

The cache contains only the post-build Cargo target directory after release
archives, debug symbols, staging directories, and timing files have been
removed. It does not contain release packages or credentials.

The repository package must be configured as a public container package to
remain within GitHub's free public-package policy. Cache contents are
therefore public and must not contain secrets. Set `ghcr_cache: false` only
for cache diagnostics or while configuring the package.

The workflow compares the contents of the patched upstream checkout with the
source files recorded after successful compilation. It marks changed files for
Cargo, which rebuilds their consumers and dependent crates. Editing a patch
without changing the resulting source does not invalidate compiled libraries.
Release binaries rebuild to embed the current release metadata.

Source snapshots advance only after successful compilation. A failed or
interrupted build keeps the previous snapshot for retry. Caches without a source
snapshot rebuild workspace crates once while retaining external dependencies.

## Retention

The `Prune release caches` workflow runs daily, after release builds, and on
manual dispatch. It retains caches associated with the two highest stable
upstream versions, excluding draft and prerelease releases. Both GHCR and
Actions cleanup use the same stable-version selection. If discovery fails,
cleanup deletes nothing and reports failure.

GHCR manifests carry release-qualified aliases alongside their shared compiler
compatibility tags. A shared manifest remains available while either retained
version references it. Older caches, untagged manifests, and compatibility
caches without a release association are deleted. Release builds also prune
GHCR after cache publication.

Managed Actions dependency caches include Cargo downloads, pnpm, APT, Bazel
repository caches, rusty_v8, uv dependencies, and Zig build caches.
Their keys include the resolved upstream version. Older managed entries and
legacy dependency caches without a release association are deleted on all Git
refs. Development refs use an `upstream-unreleased-` namespace that cleanup does
not retain. Installer caches without a configurable release association, such
as Zig compiler tarballs, and unrelated caches such as CodeQL databases remain
under their providers' retention policies.

BuildBuddy remains under its server-side eviction policy. Its documented
[cache API](https://www.buildbuddy.io/docs/enterprise-api/#deletefile) deletes
individual entries by URI; it does not provide the complete release-associated
inventory needed to remove older versions while preserving shared data used by
either retained version. This workflow does not delete BuildBuddy entries.

GitHub Actions build artifacts, reports, logs, and metadata, including source
provenance and failure markers, expire after 1 day. Retrying publication with `artifact_run_id` requires
the original build artifacts to remain available; after expiry, rebuild the
release. Published GitHub Release assets do not expire under this policy.

Preview Actions cleanup without deleting caches:

```sh
python3 .github/scripts/cache_retention.py actions --repository OWNER/REPO --dry-run
```
