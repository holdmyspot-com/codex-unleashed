# Changelog

Changes to the Codex Unleashed patch queue, by release. Each release is upstream `openai/codex`'s stable release plus
the patches listed in its release notes.

## Next release (upstream `rust-v0.167.0`)

### Patches removed because upstream provides them

- `issue-62/classify-wrapped-tls-errors.patch` ([#62](https://github.com/holdmyspot-com/codex-unleashed/issues/62)):
  TLS failures wrapped in nested I/O errors are classified as TLS errors. Upstream `rust-v0.167.0` follows
  `io::Error::get_ref` when classifying request failures, so the fix and its regression tests were removed.
