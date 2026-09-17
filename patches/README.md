# Patch Queue

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
- fixes follow test-driven development: include a regression test that fails before the fix and passes after it
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
- reproduction summary
- regression-test description and before/after results
- upstream status or related links
