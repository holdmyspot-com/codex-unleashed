# Package legal documents

Release maintainers must bundle the complete repository `LICENSE.md` (License Terms and Explanation), its HTML edition, the supplied Usage Terms and Privacy Notice, and applicable original notices in CLI and app-server packages. The legal bundle resides at `codex-resources/legal/`; its relative `docs/` links remain valid. It adds no billing terms or rates.

The package job preserves downstream `LICENSE.md`, `docs/LICENSE.html`, `docs/terms.html`, `docs/privacy.html`, and `licenses/` before checking out upstream. The original upstream checkout supplies its `LICENSE`, `NOTICE`, sample-skill licenses, bubblewrap notices, and voice/wezterm notices. The required upstream source paths are declared in `.github/scripts/package-license.py` for the pinned source revision; review that list when changing the upstream pin.

After upstream's build action assembles both package directories and evaluates its smoke tests, run one finalization command. It stages the supplied legal documents in both canonical package directories, asks upstream's archive writer to replace each `.tar.gz` and `.tar.zst` output, and validates each completed archive before returning success:

```bash
python3 "$RUNNER_TEMP/package-legal-tools/package-license.py" finalize \
  --metadata-root "$RUNNER_TEMP/package-legal-metadata" \
  --upstream-root "$GITHUB_WORKSPACE" \
  --package-root "$RUNNER_TEMP" \
  --archive-root "$DIST_TARGET_DIR" \
  --target "$TARGET"
```

The package root must contain `codex-package-$TARGET` and `codex-app-server-package-$TARGET`. The archive root receives `codex-package-$TARGET.tar.gz`, `codex-package-$TARGET.tar.zst`, `codex-app-server-package-$TARGET.tar.gz`, and `codex-app-server-package-$TARGET.tar.zst`. Staging preserves existing package files and notices. It refuses a missing source, empty legal document, document over 2 MiB, linked destination, or differing existing legal document. Identical existing legal bytes permit a retry. A write or archive-validation failure stops later outputs; resolve the reported cause before rerunning finalization.

The command uses upstream's `codex_package.archive.write_archive(..., force=True)` and its resolved zstd executable. The checker streams archive contents without extracting members. It rejects missing or altered supplied legal bytes, duplicate/noncanonical paths, links/special files, or over 10,000 members. Invalid or missing arguments return exit status 2. A source, writer, decompressor, or archive-validation failure returns nonzero with a diagnostic; do not publish the affected archive. Other existing regular files and directories remain permitted. Replacing these archives must precede release manifest/checksum generation, attestation, asset collection, and independent consumption. Preserve the native binaries and separate symbol archive unchanged.

Run Python unit checks with `python3 -I .github/scripts/test-package-license.py`. These checks prove staging and legal archive validation with canonical layout fixtures. They do not establish that an actual release was built, installed, or consumed; release acceptance must use the four final archives produced by this command.
