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
