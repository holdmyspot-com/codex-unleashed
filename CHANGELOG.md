# Changelog

Changes to the Codex Unleashed patch queue, by the upstream `openai/codex` stable release the queue is based on. The
release notes of every fork release built on an upstream release include that release's section as "Changes".
Section headings are exactly `## Upstream rust-vX.Y.Z`, newest upstream release first; add a new release's section above the
existing ones.

## Upstream rust-v0.167.0

### Patches removed because upstream provides them

- `issue-62/classify-wrapped-tls-errors.patch` ([#62](https://github.com/holdmyspot-com/codex-unleashed/issues/62)):
  TLS failures wrapped in nested I/O errors are classified as TLS errors. Upstream `rust-v0.167.0` follows
  `io::Error::get_ref` when classifying request failures, so the fix and its regression tests were removed.
