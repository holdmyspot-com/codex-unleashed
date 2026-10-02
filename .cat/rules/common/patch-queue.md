# Patch queue

## Design Goals

- Keep downstream patches independently reviewable and applicable, with documented
  configuration, upstream provenance, dependencies, and release versioning.
- Accept patch verification only when the declared upstream checks pass on the
  emitted patch applied to its declared base.

## Guidance

Apply these rules when creating, updating, or releasing patches under `patches/`.

## Layout

- Use one issue directory per repository-relative GitHub issue. Security advisory
  patches may use `security-<advisory-id>` instead.
- Put a human-readable `README.md` in every issue directory.
- Use these paths:
  - `patches/<owner>/<repo>/issue-<number>/README.md`
  - `patches/<owner>/<repo>/issue-<number>/<short-slug>.patch`
  - `patches/<owner>/<repo>/security-<advisory-id>/<short-slug>.patch`
- Use `<short-slug>.patch` for a single patch. For a dependent series, numeric
  prefixes such as `0001-` and `0002-` are optional but recommended to make
  dependencies and application order clear.
- Do not combine unrelated issues into a rollup.

## Patch authoring

- Generate patches with `git format-patch` and keep each patch to one logical
  bug fix.
- Make each patch apply independently against its declared upstream base
  whenever possible, including any downstream feature-property registration it
  needs. Keep unavoidable shared prerequisites narrow and document their
  dependencies and application order.
- Name downstream Codex feature flags under `[features]` with the
  `unleashed_<descriptive_name>` prefix and the matching
  `Unleashed<DescriptiveName>` Rust feature variant. Name downstream
  project-specific settings without that prefix under `[codex_unleashed]`.
  Keep upstream-owned property names unchanged.
- Enable every downstream Codex feature flag by default. Each associated
  project `config.toml` property has an independent default and may default to
  `false`. Document both defaults and their combined behavior in the patch
  README.
- When a feature has project-specific configuration, register both its Codex
  feature flag and each corresponding `[codex_unleashed]` field in Codex's
  `config.toml` model, even when the feature is disabled. Gate each field's
  behavior on the feature flag so a supplied value has no effect and causes no
  unknown-key warning while disabled, including in a project config. Recognize
  only fields declared by that feature patch. Do not add a catch-all for
  `[codex_unleashed]`: an undeclared field in that section must trigger Codex's
  normal unknown-key warning.
- Unless the task or patch README explicitly specifies another base, build
  each patch against the latest stable upstream release and record its tag and
  commit in the patch README.
- Follow TDD for every patch: add and run a focused test against the declared
  upstream base that fails for the missing outcome, make the patch change,
  include the test in the patch, and run it on the patched tree to show it
  passes. This includes documentation and build changes; choose a test of the
  intended outcome. Retain failing and passing commands and results in the
  patch commit message or task execution record. List current verification
  commands and expected outcomes in the patch README.
- Before selecting verification commands, read the declared upstream base's
  maintained check entry points and use their commands, options, and configuration.
  Do not substitute a direct tool invocation for an upstream check wrapper or
  omit its options when claiming that check passes. Focused checks supplement,
  rather than replace, required upstream gates. For Codex formatting, run
  `just fmt-check` from the patched checkout root; plain `cargo fmt -- --check`
  does not establish that gate. After generating the patch, apply it to a fresh
  checkout of its declared base with its documented prerequisites and rerun the
  required checks there. Record the commands, results, base commit, and emitted
  patch identity; an authoring-tree result does not verify the emitted patch.
- When a local patch check reports an error in source unchanged from its
  declared upstream base, verify the base and compare the local toolchain,
  command, dependencies, configuration, environment, generated state, and
  agent-applied changes with the upstream passing path before changing the
  reported source. If the same source passes upstream, treat agent-introduced
  differences as the likely cause and resolve those first.
- Use locally available tool versions by default, even when they differ from
  upstream pins. When a check fails, debug it with the existing local toolchain
  first and preserve the command, inputs, environment, and diagnostics. Do not
  attribute failure to the toolchain merely because its version differs. Only
  after that investigation leaves compatibility as a live cause may the
  matching upstream-pinned version be tried as a controlled comparison.
  Require the same check to pass with the pin before concluding that the
  version difference caused the failure. If the project contract requires a
  pinned toolchain, use that pin from the start.
- Remove a patch once upstream ships the fix. Keep patch history easy to
  review against upstream.

## Patch README content

- Keep `## Intent` concise and user-facing. State the change users notice and
  any condition or scope that affects it. Put implementation mechanics and
  test details in their relevant sections.
- Use the same `## Feature configuration` format in every feature patch README:
  document the Codex feature flag's stage and default, its enable and disable
  commands, each project-specific `config.toml` property, its exact section,
  and its independent default. State when a feature has no project-specific
  properties and explain how the defaults combine to affect behavior.
- Include the canonical issue or advisory reference, patch intent and scope,
  reproduction summary, current verification commands and expected results,
  and upstream status or related links.

## Release versioning

For npm package versions derived from a patched GitHub release tag
`rust-vX.Y.Z+N`, replace the `+` before the vendor build number with `-` and
omit the `rust-v` prefix: publish `X.Y.Z-N`. Keep `X.Y.Z` unchanged when the
release tag has no vendor build number.
