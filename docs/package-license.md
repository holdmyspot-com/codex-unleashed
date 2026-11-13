# Package legal documents

Release maintainers must bundle the complete repository `LICENSE.md` (License Terms and Explanation), its HTML edition, the supplied Usage Terms and Privacy Notice, and applicable original notices in CLI and app-server packages. The legal bundle resides at `codex-resources/legal/`; its relative `docs/` links remain valid. It adds no billing terms or rates.

The package job preserves downstream `LICENSE.md`, `docs/LICENSE.html`, `docs/terms.html`, `docs/privacy.html`, and `licenses/` before checking out upstream. The original upstream checkout supplies its `LICENSE`, `NOTICE`, sample-skill licenses, bubblewrap notices, and voice/wezterm notices. The required upstream source paths are declared in `.github/scripts/package-license.py` for the pinned source revision; review that list when changing the upstream pin.

After upstream's build action assembles both package directories and evaluates its smoke tests, run the retained script for each primary package directory:

```bash
python3 "$RUNNER_TEMP/package-legal-tools/package-license.py" stage \
  --metadata-root "$RUNNER_TEMP/package-legal-metadata" \
  --upstream-root "$GITHUB_WORKSPACE" \
  --package-dir "$RUNNER_TEMP/codex-package-$TARGET"
```

Use `codex-app-server-package-$TARGET` for the app-server variant. Staging preserves existing package files and notices. It refuses a missing source, empty legal document, document over 2 MiB, linked destination, or differing existing legal document. Identical existing legal bytes permit a retry. A write failure may leave part of the bundle staged; rerun staging with the same inputs after resolving the filesystem error.

Serialize the updated primary directories with upstream's `codex_package.archive.write_archive(..., force=True)` for both `.tar.gz` and `.tar.zst`; that is the same writer the upstream package builder uses. Replacing these archives must precede release manifest/checksum generation, attestation, asset collection, and independent consumption. Preserve the native binaries and separate symbol archive unchanged.

Check each final archive against its supplied sources:

```bash
python3 "$RUNNER_TEMP/package-legal-tools/package-license.py" check \
  --metadata-root "$RUNNER_TEMP/package-legal-metadata" \
  --upstream-root "$GITHUB_WORKSPACE" \
  --archive "dist/$TARGET/codex-package-$TARGET.tar.gz"
```

For zstd archives, use upstream's resolved zstd executable to decompress to stdout and pass the plain tar stream with `--archive -`. The checker streams without extracting archive members. It rejects missing or altered supplied legal bytes, duplicate/noncanonical paths, links/special files, or over 10,000 members. It returns exit status 1 and a diagnostic on failure; do not publish that archive. Other existing regular files and directories remain permitted.

Run Python unit checks with `python3 -B .github/scripts/test-package-license.py`. These checks prove staging and legal archive validation with canonical layout fixtures. They do not establish that an actual release was built, installed, or consumed; the release acceptance must use the final reserialized archives.
