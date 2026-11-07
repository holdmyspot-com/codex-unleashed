# Consistent CLI identity across build systems

- Issue: [holdmyspot-com/codex-unleashed#55](https://github.com/holdmyspot-com/codex-unleashed/issues/55)
- Applies to: upstream `openai/codex` main, commit `dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4`.
- Prerequisites: the release queue through `branding-and-app-server-distribution/warn-on-openai-app-server.patch`; `cli-version-identity.patch` changes the build-information test added by `add-branding-and-build-metadata.patch`.
- Upstream status: fork-specific; upstream has no `--build-info` regression test.

## Intent

Codex Unleashed reports the same CLI identity, `codex-cli`, in Cargo and Bazel
builds. The parser names the command explicitly instead of deriving it from
package metadata, which Bazel supplies differently.

The patch introduces no feature flag or configuration property.

## Reproduction

Without the repair, the Bazel version test receives `codex` where Cargo
receives `codex-cli`. Cargo builds pass with or without it.

## Verification

```sh
bazel test //codex-rs/cli:cli-build_info-test
just test -p codex-cli -E 'test(version_identifies_codex_unleashed_build)'
```
