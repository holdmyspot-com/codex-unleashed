# TLS errors inside I/O wrappers

## Intent

Codex recognizes TLS failures when a transport or route resolver wraps them in
nested I/O errors. Certificate-like text alone does not qualify as a TLS failure.

## Issue and upstream status

- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/62
- Rust behavior: https://github.com/rust-lang/rust/issues/101817
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.

The declared base traverses ordinary error sources, which skip the immediate
error wrapped by `std::io::Error`. The patch checks `io::Error::get_ref()` before
continuing through ordinary sources. It applies independently, with no feature
flag or project-specific configuration.

## Reproduction

A resolver error containing an I/O error around another I/O error around a typed
rustls certificate error produces `ProxyResolutionUnavailable` instead of
`TlsError`. A real HTTPS request using rustls against an untrusted self-signed
certificate produces no classification. Native-TLS fallback tests also require
an explicit test environment: an inherited `SSL_CERT_FILE` selects rustls before
those tests exercise native TLS.

## Verification

Run checks through `tooling/bin/codex-tooling run-local-rust` with absolute project
and checkout paths and a project-owned cache name. Compiler outputs must remain
separate for baseline and patched checkouts.

Use a temporary Cargo configuration file containing:

```toml
[env]
SSL_CERT_FILE = { value = "", force = true }
CODEX_CA_CERTIFICATE = { value = "", force = true }
```

Pass its absolute path through the maintained test command:

```sh
just test -p codex-http-client --config /absolute/path/to/test-env.toml
just fmt-check
```

All HTTP-client tests and formatting must pass. The force settings override CA
variables introduced by the runner. They affect only this verification process;
they do not change application certificate handling. The CA integration tests
set their own subprocess environments.

For a test-only baseline comparison, apply only the added tests and run
`just test -p codex-http-client --lib request_failures_` with the same Cargo config.
The nested typed-error test must fail. On the patched checkout it must pass, and
the certificate-text test must retain `ProxyResolutionUnavailable`.

To exercise the real handshake specifically through rustls, set `SSL_CERT_FILE`
in a second forced Cargo configuration to an existing system CA bundle and run
the focused `request_failures_` tests. All four focused tests must pass. This
configuration intentionally changes the backend, so it is not suitable for the
native-TLS fallback tests. Remove temporary configurations and checkout-owned
build outputs after verification.
