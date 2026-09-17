# Update Rustls for RUSTSEC-2026-0285

- Advisory: [RUSTSEC-2026-0285](https://rustsec.org/advisories/RUSTSEC-2026-0285)
- Affected upstream: `openai/codex` `rust-v0.153.4`

The upstream lockfile selected `rustls 0.23.36`, which is affected by a TLS 1.3
handshake validation vulnerability. The patch updates it to `0.23.45`, the first
version identified by the advisory as fixed. Cargo resolves the required
transitive updates to `rustls-webpki`, `aws-lc-rs`, and `aws-lc-sys`.

Regression check: before the patch, `cargo deny` reports RUSTSEC-2026-0285;
afterward, `cargo check -p codex-utils-rustls-provider` succeeds with Rustls
`0.23.45`.

Remove this patch when the applicable upstream release contains the fixed
Rustls version.
