# TLS errors inside I/O wrappers

- Issue: [holdmyspot-com/codex-unleashed#62](https://github.com/holdmyspot-com/codex-unleashed/issues/62)
- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: none. It also applies after the rest of the queue.
- Related Rust behavior: [rust-lang/rust#101817](https://github.com/rust-lang/rust/issues/101817), still open: `io::Error::source` returns the wrapped error's cause instead of the wrapped error.

## Intent

Codex recognizes a TLS failure when a transport or route resolver wraps it in nested I/O errors. Certificate-like text alone is not treated as a TLS failure.

The pinned base follows ordinary error sources, which skip the error an `io::Error` wraps. Upstream already checks one directly wrapped rustls error; the patch makes the general traversal follow `io::Error::get_ref` first, at any depth, and keeps every existing classification.

## Feature configuration

### Codex feature flag

None. The classification is always active.

### Project-specific `config.toml` property

None.

## Reproduction

A resolver error holding an I/O error, which holds another I/O error around a typed rustls certificate error, is classified as `ProxyResolutionUnavailable` instead of `TlsError`.

## Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo nextest run -p codex-http-client --lib -E 'test(request_failures_)'
just fmt-check
```

The nested typed-error case expects `TlsError`, and the certificate-text case keeps `ProxyResolutionUnavailable`. With `route_aware_client_pool.rs` restored to the base, the nested case fails.
