# No-marker skills fixture isolation

## Intent

The skills ancestry regression test gives the same result when temporary files
reside inside a Git checkout or outside one.

## Issue and scope

- Tracking: https://github.com/holdmyspot-com/codex-unleashed/issues/64
- Base: `rust-v0.163.0`, commit `83086188ae0614bd882818ff1aa23f3194962012`.

The patch applies independently and changes only the no-project-marker test
fixture. The fixture configures a marker absent from its owned temporary tree,
so host Git ancestry cannot introduce a project root. The assertion still
requires skills discovery to stop at the current directory. Production marker
detection is unchanged. There is no feature flag or configuration property.

## Reproduction

Run the focused test with `TMPDIR` beneath a Git checkout. Default `.git`
marker detection finds the enclosing project and includes its ancestor skills
roots, contradicting the fixture's no-project-marker premise.

## Verification

Run from the patched checkout through `tooling/bin/codex-tooling run-local-rust`
with absolute project and checkout paths and separate compiler outputs for the
baseline and patched checkouts. Keep the helper's default project-local
`TMPDIR` to exercise host Git ancestry:

```sh
just test -p codex-skills-extension --lib repo_ancestry_without_project_marker_does_not_walk_parents
just test -p codex-skills-extension --lib
just fmt-check
```

The focused test, unit suite, and formatting must pass. On the declared base,
the same focused command must fail with an unexpected ancestor skills root.
A compilation failure does not establish that regression.
