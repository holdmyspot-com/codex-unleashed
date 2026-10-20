# Latest stable Rust, Bazel and V8 dependencies

## Intent

Codex Unleashed builds with the latest stable releases of its direct Rust crates, Bazel modules and V8. Behavior, diagnostics and fork features stay the same. Export failures from an unreachable OpenTelemetry collector stay out of the local log database.

## Reference and base

- Issue: https://github.com/holdmyspot-com/codex-unleashed/issues/71
- Upstream base: `rust-v0.163.0`, commit
  `83086188ae0614bd882818ff1aa23f3194962012`.
- Apply the release patch queue in repository filename order. This patch
  follows the issue #60 toolchain upgrade and the earlier issue patches whose
  dependency declarations, lock entries and Bazel patches it updates. The
  security patches for RUSTSEC-2024-0449, RUSTSEC-2026-0346 and
  RUSTSEC-2026-0367 follow it and own the serde_yaml, rustls and zip versions.
- The patch depends on that queue context and doesn't apply to the clean
  upstream base by itself: it rewrites lock entries, Bazel annotations and V8
  patches that the earlier issue patches introduce.

## Scope

`latest-stable-dependencies.patch` raises each direct workspace dependency in
`codex-rs/Cargo.toml` to its latest stable release and regenerates
`Cargo.lock`. Releases with breaking changes include:

- schemars 1, ts-rs 12 and jsonptr 0.8 (generated protocol schemas and fixtures)
- jsonwebtoken 11 with the aws-lc-rs backend
- keyring-core 1, which replaces keyring 3
- ed25519-dalek 3, hmac 0.13, sha1 0.11, sha2 0.11 and rand 0.10
- gix 0.89, reqwest 0.13, toml 1 and toml_edit 0.25
- the OpenTelemetry 0.33 crates and tracing-opentelemetry 0.34
- tree-sitter 0.27, vt100 0.18, similar 3, pulldown-cmark 0.13 and whoami 2
- dirs 7, dns-lookup 4, ctor 1, shlex 2, signal-hook 0.4 and zstd 0.14

The affected crates are ported to these APIs without new lint suppressions.
Regenerated app-server protocol schemas and the Python SDK's generated
bindings reflect the schemars 1 output.

These direct dependencies stay behind their newest release because a
dependency requires an exact or bounded version:

| Crate | Locked | Holder |
| --- | --- | --- |
| blake3 | 1.8.2 | `pagable` and `starlark` require `=1.8.2` |
| time | 0.3.47 | `rama-crypto` and `rama-http` require `=0.3.47` |
| libsqlite3-sys | 0.37 | `sqlx-sqlite` requires `<0.38` |
| sse-stream | 0.2 | `rmcp` 3.5.1 requires `^0.2.4` and shares its event types |
| tracelogging | 1.2.3 | upstream pins `=1.2.3`: 1.2.4 links `OneCore_apiset`, which the Windows GNU toolchain does not ship |
| windows-future | 0.3.2 | `windows` 0.62.2, the latest release, returns its 0.3 async types |

The Bazel modules move to their latest registry releases, including
protobuf 36.2, rules_rs 0.1.0, abseil-cpp 20260817.0, windows_support 0.4.1,
apple_support 2.10.1, rules_cc 0.2.26, rules_pkg 1.3.0 and zstd, bzip2, zlib
and aws-lc revisions. Crate annotations use the rules_rs 0.1.0
`crate.annotation_select` API. Workspace members keep upstream's `0.0.0`
`Cargo.lock` versions; Bazel CI reconciles them with the release version
through Cargo before rules_rs reads the lockfile. The rules_rs and rules_rust patches made redundant by the new releases
are removed and the rest are rebased.

New crate annotations keep compile-action sandbox paths out of build outputs:

- libsqlite3-sys reports its bundled header directory from its build script.
- openssl-src uses a relative manifest directory.
- rust-embed embeds files in every build mode.

rules_rs 0.1.0 rejects a compiled Rust output that contains the absolute
action working directory. `rules_rust_working_dir_context.patch` adds the byte
offset and the printable string around the first match to that error, so a
rejected output names the embedded value.

rama-dns 0.4 links `Dnsapi`, while MinGW names its import library
`libdnsapi.a`. Windows gnullvm links run lld on Linux, where library lookup is
case-sensitive, so `rama-dns_lowercase_dnsapi.patch` links the lowercase name.
Windows itself ignores the case, so MSVC links are unchanged.

Abseil 20260817.0 opens zoneinfo files with `O_NONBLOCK`, which MinGW's
`fcntl.h` does not define, so the Windows gnullvm build fails.
`abseil_mingw_o_nonblock.patch` backports the upstream fix
([google/cctz@361e539](https://github.com/google/cctz/commit/361e53992abb446c7ef33fa92828bbde01473ea8)),
which defines the flag as 0 where the platform lacks it.

windows_support 0.4.1 also extracts the `CA.Ext` MSVC packages, which spell
the host tool directories `HostX64` and `HostArm64` while the compiler packages
use `Hostx64` and `Hostarm64`. Native Windows merges them under the first
spelling extracted, so `windows-support-native-tools.patch` renames the
directory to its canonical spelling before it prunes the tool tree.

rama-dns 0.4 generates its glibc resolver bindings with bindgen. Its build
script uses the hermetic libclang, which Bazel builds from LLVM source for
Linux targets.

keyring-core 1 moves the Windows credential store into
windows-native-keyring-store, whose 1.1.0 release keeps its error type
private. The keyring store reads the Windows error code from that type's
derived `Debug` form until a release exports it as `WindowsErrorCode`
([windows-native-keyring-store#22](https://github.com/open-source-cooperative/windows-native-keyring-store/pull/22)).

The Windows sandbox service moves from `windows` 0.58 to 0.62, which moves
`BOOL` into `windows::core` and the WinRT async types into the windows-future
crate, so the service depends on windows-future directly. Event registration
tokens are now plain `i64` values.

V8 moves to rusty_v8 152.2.0 and V8 15.2.124.1. The V8 patches are rebased,
`v8_array_sort_elements_kind.patch` backports the upstream M152 fix for
CVE-2026-85046
([v8/v8@94c34b2](https://github.com/v8/v8/commit/94c34b200ae9caf83d06c167a070746a1c7e825c)),
which rejects mixed elements kinds when Maglev and TurboFan inline
`Array.prototype.sort`. Also, `third_party/v8` records the new archive checksums. The
V8 canary and rusty_v8 release workflows build the same patched source.

hyper 1.12 rejects response bytes that reach an idle client connection.
The exec-server and remote-control tests for stalled response bodies now read
the request before they send the partial response, as a real server does.

The OpenTelemetry SDK 0.33 reports each failed periodic metric export as an
`ERROR` event. The log database drops the `PeriodicReader.ExportError` and
`PeriodicReader.ExportFailed` events from `opentelemetry_sdk`. Recording them
would feed the next export while the collector stays unavailable. Other SDK
diagnostics are still recorded.

## Reproduction

On the patched queue without this patch, list direct registry dependencies
that trail their latest stable release:

```sh
cargo metadata --format-version 1 --manifest-path codex-rs/Cargo.toml > metadata.json
python3 -I audit.py metadata.json
```

`audit.py` resolves each workspace member's direct registry dependencies from
the metadata and compares them with the newest non-yanked, non-prerelease
crates.io release. Before this patch it reported 35 trailing crates.

With the OpenTelemetry upgrade but without the log-database filter, the
app-server logging suite records SDK export errors from an unreachable
collector and fails.

## Verification

From the patched checkout root, resolve workspace package versions, then run:

```sh
cargo update --workspace --manifest-path codex-rs/Cargo.toml
just fmt-check
just bazel-lock-check
cargo deny --manifest-path codex-rs/Cargo.toml check --deny unmatched-source \
  --deny license-not-encountered --deny unused-wrapper --deny advisory-not-detected
just clippy --workspace --all-targets -- -D warnings
just test --workspace --features codex-v8-poc/sandbox
bazel test //codex-rs/state:state-unit-tests //codex-rs/app-server:app-server-all-test
bazel build --platforms=//:windows_x86_64_gnullvm //codex-rs/exec-server/testing:exec-server
bazel query --repo_env=BAZEL_MSVC_RUNTIME_VISUAL_STUDIO_EULA=1 @msvc_runtime//:msvc_tools_x64_files
bazel test @rules_rust//util/process_wrapper:process_wrapper_test
cargo clippy --target x86_64-pc-windows-gnullvm -p codex-keyring-store --all-targets -- -D warnings
bazel build --config=clippy --platforms=//:windows_x86_64_gnullvm \
  //codex-rs/windows-sandbox-service:windows-sandbox-service \
  //codex-rs/windows-sandbox-service:windows-sandbox-service-unit-tests-bin
just bazel-test
just build-for-release
```

The audit reports only the dependencies held back in the table above and
releases published after the patch was generated. Formatting, strict lock
validation and Clippy report no warnings. With the CI arguments
(`--deny unmatched-source --deny license-not-encountered --deny unused-wrapper
--deny advisory-not-detected`), `cargo deny check` passes. The upgraded rama
releases no longer depend on hickory-proto, so the patch removes its
RUSTSEC-2026-0118 and RUSTSEC-2026-0119 exceptions and its async-trait
wrapper entry. Duplicate-version warnings rise from 85 to 108 because
transitive dependencies still use older majors. `multiple-versions` stays at
its upstream `warn` level. The state unit tests and the
app-server logging suite pass with the export-error filter. Without the
tracelogging hold and the rama-dns patch, the Windows gnullvm link of
`exec-server` fails on `-lOneCore_apiset` and `-lDnsapi`; with them it links.
The process wrapper tests pass; without the context lookup,
`test_working_dir_context_reports_surrounding_string` fails.
The MSVC runtime repository keeps `bin/Hostx64/x64` and `bin/HostArm64/arm64`.
A repository-rule probe that extracts only `HostX64` keeps no host directory
without the rename and keeps `Hostx64` with it. The keyring
store and the Windows sandbox service pass Clippy for Windows gnullvm,
including their tests. The two rules_rs
"well-known crate annotation" warnings for `tikv-jemalloc-sys` and
`libgit2-sys` remain visible; the generated build-script modes behave the same.
Full tests and release builds must pass on their required platforms.

## Upstream status

The selected upstream release pins the older dependency versions. Remove the CVE-2026-85046 backport once the selected rusty_v8 release ships a
V8 version that contains the upstream fix. Remove `abseil_mingw_o_nonblock.patch` once the
selected Abseil release includes the cctz fix.
