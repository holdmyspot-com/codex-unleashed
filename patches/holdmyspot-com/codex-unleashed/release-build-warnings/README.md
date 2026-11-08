# Build warnings in Codex sources and build configuration

- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: none. Each patch applies alone and after the rest of the queue.
- Patches, in queue order:
  1. `debug-only-release-items.patch`: release-build warnings from debug-only test hooks.
  2. `bubblewrap-netlink-initializers.patch`: `-Wmissing-field-initializers` in the vendored bubblewrap.
  3. `nextest-local-profile.patch`: nextest's unknown `profile.local.inherits` key.
  4. `bazel-module-versions.patch`: Bazel's root-module version mismatch for `platforms` and `rules_cc`.

The inventory these come from, with the warnings left open and why, is [warning-inventory.md](../../../../docs/warning-inventory.md).

## debug-only-release-items.patch

## Intent

A release build of Codex compiles without warnings in `codex-app-server` and `codex-cloud-tasks`. Debug builds keep every test hook unchanged.

Release builds compile out the debug-only test hooks. At the pinned base this leaves the following unused in release builds:

- the `PluginStartupTasks` import and the two managed-config environment-variable constants in `app-server/src/main.rs`;
- the `HttpClientFactory` and `OutboundProxyPolicy` imports in `cloud-tasks/src/lib.rs`;
- the `mut` on `loader_overrides` in `app-server/src/lib.rs`.

The patch gates those items on `debug_assertions` too. It also returns the overridden loader settings by value, so the parameter no longer needs `mut`.

### Feature configuration

#### Codex feature flag

None.

#### Project-specific `config.toml` property

None.

### Reproduction

`cargo build --release -p codex-app-server -p codex-cloud-tasks` at the base reports six warnings on every release target: two unused imports in `codex-cloud-tasks`, and in `codex-app-server` an unused import, two unused constants and an unneeded `mut` (release run `42427338720`).

### Verification

From the patched upstream checkout root:

```sh
cd codex-rs && cargo build --release -p codex-app-server -p codex-cloud-tasks
cargo nextest run -p codex-app-server -p codex-cloud-tasks
just fmt-check
```

The release build reports no warnings from either package. The debug test suites still use the gated hooks.

## bubblewrap-netlink-initializers.patch

The `cc` crate builds the vendored bubblewrap with `-Wall -Wextra`. `vendor/bubblewrap/network.c` initializes `struct sockaddr_nl` as `{ AF_NETLINK, 0 }`, which names two of its four fields, so GCC reports `nl_pid` as missing at lines 53 and 142 (Linux nextest run `42410922258`). Upstream bubblewrap v0.13.1 has the same code. The patch names the one field it sets, `{ .nl_family = AF_NETLINK }`; C still zeroes the remaining fields, so the addresses are unchanged.

Verification: a Linux build of `codex-bwrap` reports no compiler warnings, and `cargo nextest run -p codex-linux-sandbox` passes.

## nextest-local-profile.patch

`.config/nextest.toml` declares `[profile.local]` with `inherits = "default"`. The `inherits` key arrived in nextest 0.9.115, so upstream's pinned 0.9.103 warns that it is an unknown key. Every custom profile already inherits from `default`, as the [nextest configuration docs](https://nexte.st/docs/configuration/) state, so the patch removes the key and keeps the profile that `just test` selects.

Verification: `NEXTEST_PROFILE=local cargo nextest run ...` with nextest 0.9.103 prints no configuration warning.

## bazel-module-versions.patch

The root `MODULE.bazel` requires `platforms@1.0.0` and `rules_cc@0.2.18`, but other modules require 1.1.0 and 0.2.20, so Bazel resolves those and warns about the mismatch. The patch raises the two requirements. `bazel mod deps --lockfile_mode=update` with the pinned Bazel 9.2.0 removes only the two registry hashes nothing uses any more; every resolved module version stays the same.

Verification: `bazel mod deps --lockfile_mode=error` with Bazel 9.2.0 passes and prints no version warning.
