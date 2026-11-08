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
| `-Wmissing-field-initializers` for `nl_pid` at `vendor/bubblewrap/network.c:53` and `:142` | Linux debug build of `codex-bwrap` | The vendored bubblewrap initializes `struct sockaddr_nl` positionally with only two of its fields | Open |
| `.config/nextest.toml`: unknown key `profile.local.inherits` | Linux nextest | The pinned nextest 0.9.103 does not support profile inheritance | Open |

## Codex build configuration

| Warning | Where it appears | Cause | Disposition |
| --- | --- | --- | --- |
| `MODULE.bazel` requires `rules_cc@0.2.18` and `platforms@1.0.0`, but the resolved graph uses 0.2.20 and 1.1.0 | Bazel | Direct `bazel_dep` versions are older than what other modules require | Open |
| `-W#warnings`: `_FORTIFY_SOURCE requires compiling with optimization` | Bazel, Linux glibc headers | `_FORTIFY_SOURCE` is defined for unoptimized (fastbuild) C compilation | Open |
| `-Wbuiltin-macro-redefined` (9 per job) | Bazel C compilation | The hermetic toolchain redefines builtin macros, which is presumably how it keeps builds reproducible | Open; cause to confirm |
| Suggested crate annotations for `tikv-jemalloc-sys` and `libgit2-sys` | Bazel | `rules_rust` offers well-known build annotations that the module does not apply | Open |

## Outside the Codex scope (reported separately)

| Warning | Source |
| --- | --- |
| `-Wunknown-pragmas` in `aws-lc` `crypto/thread_win.c:186` and `:192` | Transitive dependency (through `rustls`), not a direct dependency |
| `bazel_tools` sha256 target uses implicit `__init__.py` creation | Bazel's own tools |
| `module not found: com.holdmyspot.codexunleashed.tooling.test`; `antlr4` module name ends in digits; restricted `java.lang.foreign` methods; `java.io.tmpdir` set twice; unknown module `org.junit.platform.commons` for `--add-opens` | This repository's Java cache tooling, built by the release workflow |
| Node.js 20 deprecation for `mlugg/setup-zig` | GitHub Actions dependency of the workflow |
| `git am`: 3 lines add whitespace errors | Intentional trailing padding in the branding patch's TUI snapshots |
