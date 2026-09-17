# Codex Unleashed branding and build metadata

This distribution patch adds Codex Unleashed build metadata to the CLI and
interactive session header. The upstream version remains visible as the base
version, with the provider build appended as SemVer build metadata.

The build number is supplied at compile time through
`CODEX_UNLEASHED_BUILD_NUMBER` by the release workflow.

## `--version`

```text
codex-cli 0.155.0+25
(See --build-info for more information)
```

## `--build-info`

```text
Codex Unleashed build information

Codex CLI version: 0.155.0+25
Build number:      25

Upstream project:  OpenAI Codex
Upstream version:  0.155.0
Upstream source:   https://github.com/openai/codex

Provided by:       Codex Unleashed
Project:           https://github.com/holdmyspot-com/codex-unleashed
```

The interactive session header shows `v0.155.0+25` and the provider website
`https://holdmyspot.com/codex-unleashed/` beneath it, aligned with the title text.
