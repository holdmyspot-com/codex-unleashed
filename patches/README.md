# Patch Queue

## Enableable features

- `unleashed_agent_fast_switching` keeps `/subagents` switching back to the
  main agent responsive when its history spans many compactions. It is enabled
  by default. See
  [its patch README](holdmyspot-com/codex-unleashed/issue-34776/README.md) for
  the feature controls.
- `unleashed_expanded_diff_previews` shows complete patch diffs in the
  full-screen transcript when both the feature flag and project setting are
  enabled. The feature flag is enabled by default; the project setting defaults
  to `false`, so previews remain compact until a project opts in. See
  [its patch README](holdmyspot-com/codex-unleashed/issue-47390/README.md) for
  the feature controls and project property.

Patch layout:

- `patches/<owner>/<repo>/issue-<number>/README.md`
- `patches/<owner>/<repo>/issue-<number>/<short-slug>.patch`
- `patches/<owner>/<repo>/security-<advisory-id>/<short-slug>.patch`

Examples:

- `patches/openai/codex/issue-1234/README.md`
- `patches/openai/codex/issue-1234/fix-crash-on-startup.patch`
- `patches/openai/codex/security-rustsec-2026-0285/update-rustls.patch`
- `patches/codex-unleashed/codex-unleashed/issue-12/README.md`
- `patches/codex-unleashed/codex-unleashed/issue-12/fix-release-notes.patch`

Operational rules:

- one issue directory per repository-relative GitHub issue; security advisory
  patches may instead use `security-<advisory-id>`
- each issue directory must contain a human-readable `README.md`
- patches should be generated with `git format-patch`
- each patch should correspond to one logical bug fix
- each patch should apply independently against its declared upstream base whenever possible, including any downstream feature-property registration it needs; keep unavoidable shared prerequisites narrow and document their dependencies and application order
- downstream feature properties must use the `unleashed_<descriptive_name>` key prefix and the matching `Unleashed<DescriptiveName>` Rust feature variant; keep upstream-owned property names unchanged
- default every downstream Codex feature flag introduced by a patch to enabled. Each associated project `config.toml` property has an independent default and may default to `false`; document both defaults and their combined behavior in the patch README
- when a feature has project-specific configuration, its patch must register both the Codex feature enable/disable flag and each corresponding `unleashed_` field in Codex's `config.toml` model, even when the feature is disabled. Gate every field's behavior on the feature flag so a supplied value has no effect and causes no unknown-key warning while disabled, including when present in a project config. Scope recognition to fields declared by that feature patch. Do not add a catch-all or suppress warnings based only on the `unleashed_` prefix: an `unleashed_` property that no feature declares must remain an unknown key and trigger Codex's normal unknown-key warning
- use the same `## Feature configuration` format in every feature patch README: document the Codex feature flag's stage and default, its enable/disable commands, each project-specific `config.toml` property and its independent default (or state that there are none), and how the defaults combine to affect behavior
- unless the task or patch README explicitly specifies another base, build each patch against the latest stable (non-prerelease) version released by the upstream repository and record its release tag and commit in the issue README
- for npm package versions derived from a patched GitHub release tag `rust-vX.Y.Z+N`, replace the `+` before the vendor build number with `-` and omit the `rust-v` prefix: publish `X.Y.Z-N`. Keep `X.Y.Z` unchanged when the release tag has no vendor build number
- every patch must follow TDD: add and run a focused test against the declared upstream base that fails for the missing outcome, then make the patch change, include the test in the patch, and run it on the patched tree to show it passes. This applies to documentation and build changes too; choose a test that verifies the patch's intended outcome. Record both commands and results in the patch README
- when a local patch check reports an error in source unchanged from its declared upstream base, verify the base and compare the local toolchain, command, dependencies, configuration, environment, generated state, and agent-applied changes with the upstream passing path before changing the reported source; if upstream confirms that the same source passes, treat agent-introduced differences as the likely cause and resolve those first
- use locally available tool versions by default, even when they differ from upstream pins. When a check fails, debug the failure using the existing local toolchain first and preserve the command, inputs, environment, and diagnostics that produced it. Do not attribute the failure to the toolchain merely because its version differs from upstream. Only after evidence from that investigation leaves toolchain compatibility as a live cause may the matching upstream-pinned version be tried as a controlled comparison; require the same check to pass with the pin before concluding that the version difference caused the failure. If the project contract requires a pinned toolchain, use that pin from the start
- use `<short-slug>.patch` for a single patch
- for dependent patch series, numeric prefixes such as `0001-` and `0002-` are optional but recommended to make their order explicit
- no multi-issue rollups
- remove the patch once upstream ships the fix
- keep the patch history easy to review against upstream

Security / trust model:

- patch files are public and intended to be small, auditable, and removable
- each issue directory is repository-relative, for example `patches/openai/codex/issue-1234/`
- consumers should review patch diffs, workflow definitions, and release notes before trusting a binary
- security reports should follow [SECURITY.md](../SECURITY.md)

Why numeric prefixes may still be useful:

- they make dependencies in a multi-patch series obvious
- they preserve the order produced by `git format-patch`
- they keep alphabetical application order deterministic

Issue directory `README.md` should include:

- canonical issue or advisory reference, for example `openai/codex#1234` or
  `RUSTSEC-2026-0285`
- patch intent and scope
- keep the `## Intent` section concise and user-facing: state the change users
  will notice and any condition or scope that affects what they see. Leave
  implementation mechanics and test details to their relevant sections
- reproduction summary
- regression-test description and before/after results
- upstream status or related links
