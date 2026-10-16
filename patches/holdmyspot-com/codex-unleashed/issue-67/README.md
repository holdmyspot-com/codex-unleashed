# Isolated marketplace migration fixture

Issue: <https://github.com/holdmyspot-com/codex-unleashed/issues/67>

## Intent

The official marketplace migration test runs against a temporary local Git
repository. Its success does not depend on GitHub availability or the contents
of Anthropic's marketplace.

## Scope and base

`offline-marketplace-migration-fixture.patch` changes only the existing test.
It applies to `rust-v0.163.0`, commit
`83086188ae0614bd882818ff1aa23f3194962012`, without patch prerequisites.
The release queue includes the toolchain and fixture repairs required by the
full workspace checks.

The parent owns the temporary repository and joins a child test process before
cleanup. Only the child receives the isolated Git configuration. Its URL rewrite
redirects the inferred official marketplace URL to the fixture repository;
HTTP and HTTPS Git transports are denied. The original missing-plugin assertions
remain in place, and the parent verifies that the child executes one test.

## Reproduction and verification

Run these commands from the patched checkout root with the release queue's
documented native dependencies and isolated Rust build environment:

```sh
just fmt-check
just test --workspace --features codex-v8-poc/sandbox \
  -E 'package(codex-external-agent-migration)'
just test --workspace --features codex-v8-poc/sandbox
```

Formatting and all migration-package tests pass. The full workspace suite is
also required for queue acceptance.

For the focused network regression, set `GIT_CONFIG_COUNT=1`,
`GIT_CONFIG_KEY_0=protocol.https.allow`, and `GIT_CONFIG_VALUE_0=never`, then run
the workspace test command with this filter:

```text
package(codex-external-agent-migration) & test(import_plugins_infers_external_official_marketplace_when_missing_from_settings)
```

The original fixture fails its successful-marketplace assertion because it
requires a live HTTPS clone. The patched fixture passes using the local clone.

## Upstream status

The declared upstream base uses a live GitHub clone in this test. The patch
preserves product behavior and isolates its regression fixture.
