# Warning inventory (checkpoint 5)

Sources, all with the 20 CP4 patches applied to upstream `dddc3c0a79`:

- six-target release build `42427338720`;
- Linux nextest run `42410922258` attempt 3, which builds every workspace test in the debug profile;
- Bazel run `42410924616`.

Scope is Codex and its direct dependencies. Each entry names its cause and its disposition.

## Codex sources

| Warning | Where it appears | Cause | Disposition |
| --- | --- | --- | --- |
| Unused import `PluginStartupTasks`; unused constants `MANAGED_CONFIG_PATH_ENV_VAR` and `DISABLE_MANAGED_CONFIG_ENV_VAR` (`app-server/src/main.rs`) | Release, all six targets | Their only uses are debug-only test hooks | Patch 21, [release-build-warnings](../patches/holdmyspot-com/codex-unleashed/release-build-warnings/README.md) |
| Unused imports `HttpClientFactory` and `OutboundProxyPolicy` (`cloud-tasks/src/lib.rs`) | Release, all six targets | Used only by the debug-only mock backend | Patch 21 |
| Variable does not need to be mutable: `loader_overrides` (`app-server/src/lib.rs:1545`) | Release, all six targets | Mutated only in the debug-only branch | Patch 21 |
| `-Wmissing-field-initializers` for `nl_pid` at `vendor/bubblewrap/network.c:53` and `:142` | Linux debug build of `codex-bwrap` | The vendored bubblewrap initializes `struct sockaddr_nl` positionally with only two of its fields | Patch 22 |
| `.config/nextest.toml`: unknown key `profile.local.inherits` | Linux nextest | The explicit `inherits` key needs nextest 0.9.115; every custom profile already inherits `default` | Patch 23 |

## Codex build configuration

| Warning | Where it appears | Cause | Disposition |
| --- | --- | --- | --- |
| `MODULE.bazel` requires `rules_cc@0.2.18` and `platforms@1.0.0`, but the resolved graph uses 0.2.20 and 1.1.0 | Bazel | Direct `bazel_dep` versions are older than what other modules require | Patch 24 |
| Test execution time outside the range for the declared size (for example `app-server-all-test`: consider `size="medium"`), about 200 per job | Bazel | Upstream's Bazel CI passes `--test_verbose_timeout_warnings` to report them; durations depend on the runner (Linux, macOS, Windows, Wine) | Kept: a timing report the flag requests, not a code diagnostic. Resizing from one runner's timings would shorten timeouts on slower ones |
| Suggested crate annotations for `tikv-jemalloc-sys` and `libgit2-sys` | Bazel | `rules_rs` advises replacing these crates' build scripts with BCR modules (for jemalloc, `5.3.0-bcr.alpha.4`) | Kept: upstream deliberately builds `tikv-jemalloc-sys` from its build script with two local patches. Applying the advice changes the built allocator, and disabling it would only suppress the message |

## Outside the Codex scope (reported separately)

| Warning | Source |
| --- | --- |
| `-Wunknown-pragmas` in `aws-lc` `crypto/thread_win.c:186` and `:192` | Transitive dependency `aws-lc-sys` (through `rustls`), not a direct dependency |
| `-Wbuiltin-macro-redefined` (3 per invocation) and `-W#warnings` `_FORTIFY_SOURCE requires compiling with optimization` | Bazel's bindgen run on `aws-lc-sys` `include/rust_wrapper.h`, with the toolchain's C flags and no optimization; same transitive dependency |
| `-Wextra-tokens` at `mingw-w64-crt/def-include/func.def.in:59` (`#ifndef F_X86_NATIVE(x)`), 30 or more per Bazel job | The mingw-w64 CRT source that the hermetic `llvm` toolchain module builds for Windows cross targets; toolchain source, not Codex or a Codex crate |
| `bazel_tools` sha256 target uses implicit `__init__.py` creation | Bazel's own tools |
| `module not found: com.holdmyspot.codexunleashed.tooling.test`; `antlr4` module name ends in digits; restricted `java.lang.foreign` methods; `java.io.tmpdir` set twice; unknown module `org.junit.platform.commons` for `--add-opens` | This repository's Java cache tooling, built by the release workflow |
| Node.js 20 deprecation for `mlugg/setup-zig` | GitHub Actions dependency of the workflow |

## Update 2026-11-11 (queue on `rust-v0.167.0`)

Sources: argument-comment lint run `43002517995`, repository checks `43009570386`, platform lints `43003310886`, macOS lints `43011377187` and Windows Bazel run `43004017546`.

| Warning | Cause | Disposition |
| --- | --- | --- |
| `'_WIN32_WINNT' macro redefined`, about 1,565 per Windows Bazel run | Bazel's local Windows C++ toolchain defaults `_WIN32_WINNT` to `0x0601` while V8 defines `0x0A00` | Fixed: the Windows Bazel workflows pass `--repo_env=BAZEL_WIN32_WINNT=/D_WIN32_WINNT=0x0A00`; rules_cc uses the value as the whole compiler flag, and a bare `0x0A00` made every MSVC compile warn D9024. Not yet confirmed by a Windows Bazel run. Release packages are built with Cargo and are unaffected |
| `git am`: 9 lines add whitespace errors | Trailing spaces that are real content: TUI snapshot lines and context lines of a nested `.patch` file | Fixed: workflows mark `*.snap` and `*.patch` with `-whitespace` before applying the queue; the applied tree is identical |
| cargo-deny: eight entries in `deny.toml` match no crate | Dependency upgrades and rama 0.4 removed what they matched | Fixed by patch 55, [stale-cargo-deny-entries.patch](../patches/holdmyspot-com/codex-unleashed/release-build-warnings/stale-cargo-deny-entries.patch) |
| cargo-deny: duplicate crate versions (`sha2`, `rustix`, `zbus`, `windows_*` and others) | Dependencies require incompatible major versions | Kept: removing a duplicate needs a dependency release that agrees with the others |
| Trailing semicolon in macro used in expression position (`color_eyre::eyre::bail!`, 5 sites in `codex-tui`) | Upstream source at the base | Already fixed by the queue: the patched sources end each of those calls with `;` |
| `Warning: can't set imports_granularity = Item` (one per file in `cargo fmt`) | Upstream's `rustfmt.toml` sets a nightly-only option, and upstream's CI runs stable rustfmt; upstream's file says the warning can be ignored | Kept: enforcing it needs nightly rustfmt, which would change what the formatting check enforces |
| `linker stderr: clang++: argument unused during compilation: '--unwindlib=none'` | The hermetic `llvm` module adds `--unwindlib=none` to every link action. rustc links with `-nodefaultlibs`, so clang ignores it for Rust links | Kept: C++ links use the flag, and the toolchain cannot drop it for Rust links alone. Hiding the message would be suppression |
| `-Wthread-safety-analysis`, `-Wpragma-clang-attribute`, `-Wignored-attributes` and `-Wundef` in V8, Abseil and libc++ headers | Third-party C++ that the Bazel jobs compile | Outside the Codex scope: fixing it means patching those dependencies |
| `redefining builtin macro` from bindgen on `aws-lc-sys` | Already listed above | Outside the Codex scope |
| Node.js 20 deprecation for `codespell-project/codespell-problem-matcher` and `mlugg/setup-zig` | Both actions' newest releases target Node 16 or Node 20, and the workflows pin the same versions as upstream | Kept until the actions publish Node 24 releases |
| `cargo-shear` has no prebuilt binary, so it falls back to `cargo-binstall` | `taiki-e/install-action` has no manifest for this version | Kept: the installed version is unchanged |
| actionlint: `queue` is an unknown key for `concurrency`; SC2016 notes on single-quoted `$` | actionlint predates `concurrency.queue`; the quoted strings are meant to stay literal | Kept. SC2115 and SC2012 in the Linux tests and release workflows were fixed |
