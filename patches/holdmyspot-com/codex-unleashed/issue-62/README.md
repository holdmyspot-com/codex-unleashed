# TLS errors inside I/O wrappers

- Issue: [holdmyspot-com/codex-unleashed#62](https://github.com/holdmyspot-com/codex-unleashed/issues/62)
- Applies to: upstream `openai/codex` tag `rust-v0.167.0`, commit `086ccc833bc9cf01024ad2dd6af7dbd421b6bf9a`.
- Prerequisites: none. It also applies after the rest of the queue.
- Related Rust behavior: [rust-lang/rust#101817](https://github.com/rust-lang/rust/issues/101817), still open: `io::Error::source` returns the wrapped error's cause instead of the wrapped error.

## Intent

Codex recognizes a TLS failure when a transport or route resolver wraps it in nested I/O errors. Certificate-like text alone is not treated as a TLS failure.

The pinned base follows ordinary error sources, which skip the error an `io::Error` wraps. Upstream already checks one directly wrapped rustls error; `rust-v0.167.0` also contains the general traversal that follows `io::Error::get_ref` (in `route_aware_client_pool.rs`), so the source change of this patch is upstream and was dropped. The patch now carries only the regression tests.

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

## Rebase onto rust-v0.167.0

The source fix is already upstream at `rust-v0.167.0`, so the patch narrowed to the regression tests in `route_aware_client_pool_tests.rs`. The nested typed-error test passes on upstream's own implementation. The base's "With `route_aware_client_pool.rs` restored to the base, the nested case fails" check no longer applies.
