# Rama 0.4 port

- Applies to: the CP7 queue through `dependency-upgrades/transitive-updates.patch` (patch 34), which sits on upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: every earlier patch in the queue, through `dependency-upgrades/transitive-updates.patch`.
- Patches: `rama-0.4.patch`.

## Intent

`codex-network-proxy` moves from rama 0.3.0-alpha.4 to the released rama 0.4.0. 0.4.0 removes `ProxyTarget`, `StreamForwardService` and `PeekStream`, gives the SOCKS5 acceptor an executor, and changes the URI, DNS, TLS, header and extension APIs. The patch contains only Codex code, manifests and `Cargo.lock`; it vendors nothing and uses released rama crates.

## What changed

- Stream forwarding: byte forwarding between two streams uses `rama_net::proxy::IoForwardService::new(executor)` served with `BridgeIo(a, b)`, and its outcome is mapped to `()`.
- HTTP CONNECT: an `UpgradeLayer` with a `CONNECT` method matcher accepts the request, answers `200` and attaches the tunnel handler. `ConnectTunnel` (HTTP proxy) and `MitmTunnel` (target, mode, MITM state, executor) carry what `ProxyTarget` and request extensions used to carry.
- Peeked prefixes: `brokered_tunnel::peek_protocol` returns `PrefixedIo<Cursor<Vec<u8>>, S>`, so every peeked byte is replayed ahead of the live stream for TLS, HTTP and opaque forwarding. The HTTP probe parses a CONNECT target with `Uri::parse_authority_form`, because rama's URI parser reads `host:port` as `scheme:path`.
- Upstream dialing: `ConnectRequest` with a `ProxyRoute` and a `TlsConnector` tunnel over `HttpProxyConnector::optional(TargetCheckedTcpConnector)`. The target-checked connector still refuses non-public targets after resolution. Tunnels through an HTTPS proxy now work as well.
- SOCKS5: `Socks5Acceptor::new(executor).with_connector(Connector::new(policy_connector, bridge_service))`. The policy connector produces a `Socks5TcpConnection` (direct, MITM or protocol detection) that carries the shared proxy state; `proxy_socks5_tcp` bridges it. UDP uses `DefaultUdpRelay` with an async inspector that applies the same policy.
- Listeners: `Executor::graceful(guard)`, `TcpListener::build(executor)`, and `CancelOnShutdown` (which now takes an executor). The MITM TLS acceptor uses `TlsAcceptorLayer` with a dynamic server config; `certs.rs` returns an `Arc<rustls::ServerConfig>`.
- Request handling: `request_context.rs` replaces rama's removed request context. Header values are built with `HeaderValue::from_bytes`, because 0.4's `from_str` is ASCII-only and the credential broker must still mask opaque bytes. The MITM hook matcher reads the path as it appeared on the wire (`raw_path`), because 0.4 percent-encodes `[` and `]` in its path accessor.
- `ConnectMitmMode` implements `Extension` by hand. rama's derive finds `rama-core` by reading the crate's `Cargo.toml`, which the Bazel compile sandbox does not contain, so under Bazel it expanded to a `compile_error!` block (Bazel Clippy run `42749913658`).
- rama-dns 0.4.0 links `Dnsapi`, but the MinGW import library is `libdnsapi.a`, so the Bazel Windows builds linked on Linux could not find it (Bazel Clippy run `42754696627`, native Bazel test run `42754700653`). A Bazel crate patch, `patches/rama-dns_windows_dnsapi_link_name.patch`, lowercases the name; Windows resolves either spelling to the same DLL. Cargo builds on Windows are unaffected.
- GitHub host hints are parsed by hand, because 0.4's authority parser rejects IPv6 zone ids such as `[fe80::1%en0]:8443`.
- `Cargo.toml`: `rama-*` `=0.4.0`, plus the new `rama-tls`. `Cargo.lock` moves the rama crates and adds rama-crypto, rama-json, rama-tls, aws-lc-fips-sys and bindgen 0.73. `MODULE.bazel.lock` is regenerated.

## Behavior that is not preserved

- On Linux and Windows the DNS resolver is rama's `TokioDnsResolver` with a five-second timeout, where it was the system resolver. macOS keeps `SystemDnsResolver`.
- The upstream HTTP client runs on a default executor, not the graceful one.
- 0.4's HTTP/1 parser drops `Content-Length` when `Transfer-Encoding: chunked` follows it (RFC 9112 section 6.1), so a request with both reaches the policy as plain chunked and is allowed, where the signer test expected `403`. `signs_only_supported_http1_requests_for_an_http2_origin` now expects `200` for that case and counts both forwarded requests. Ambiguous framing never reaches the signer or the upstream.
- Forwarded paths use 0.4's encoded form, so a raw `[` in a request path goes upstream as `%5B`. Hook matching still sees the raw path.
- `ConnectMitmMode` is stored as a response extension so the tests can read it.
- The Windows ingress code (`windows_proxy_ingress.rs`, executor wiring in `proxy.rs`) was edited without a Windows build and is not CI-verified.

## Dependency effects

- `rama-crypto 0.4.0` requires `time =0.3.47`, so `Cargo.lock` takes `time` from 0.3.55 to 0.3.47, `time-core` from 0.1.9 to 0.1.8 and `time-macros` from 0.2.32 to 0.2.27. `Cargo.toml` does not pin them.
- hickory-proto 0.25.2, hickory-resolver, enum-as-inner, resolv-conf, ipconfig and matchit 0.9.2 leave `Cargo.lock` entirely: rama-dns 0.4 pulls hickory only through a feature Codex does not enable. This clears RUSTSEC-2026-0118 and RUSTSEC-2026-0119.
- `rama-crypto 0.4.0` enables aws-lc-rs's `bindgen` feature, so `aws-lc-sys` now generates its bindings with bindgen 0.73.2 and libclang at build time on every target instead of using its pregenerated ones. `aws-lc-fips-sys` is recorded in `Cargo.lock` but no target builds it (`cargo tree -i aws-lc-fips-sys --target all` prints nothing).
- bindgen 0.73.2 accepts `syn >=2, <4` but only compiles against syn 2, so `Cargo.lock` records its `syn` as 2.0.119 (the transitive update had picked 3.0.10, which fails with a `ParseQuote` error; platform lint run `42732311440`).
- ipnet 2.12.2, proc-macro-crate 3.5.0 and serde_html_form 0.4.1 move with rama; rama-error, rama-macros and rama-utils 0.3.0-alpha.4 leave the lock.

## Verification

```sh
cd codex-rs
cargo metadata --locked --format-version 1 > /dev/null
cargo nextest run -p codex-network-proxy
cargo clippy -p codex-network-proxy --tests -- -D warnings
```

Remote runs on the 34-patch queue plus `rama-0.4.patch`:
- Platform lints (clippy on x86_64 and aarch64 musl, dependency-lints, rustdoc): `42733377684`, success.
- Linux tests, `package(codex-network-proxy)`: `42733382014`, 345 of 345 passed.
- Bazel lock regeneration: `42737222851`, empty diff, so `MODULE.bazel.lock` in the patch is current.

Earlier rounds on the pre-transitive queue (lints `42727337993`, tests `42727341558`) also passed. The first transitive-base lint round (`42732311440`) failed on bindgen and syn 3, fixed above.
