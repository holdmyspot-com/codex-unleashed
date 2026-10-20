# Maintained YAML frontmatter parsing

## Intent

Skill frontmatter uses a maintained YAML parser while preserving existing supported frontmatter behavior.

## Reference and upstream base

- [Canonical reference](https://rustsec.org/advisories/RUSTSEC-2024-0449.html).
- Base: rust-v0.163.0 (`83086188ae0614bd882818ff1aa23f3194962012`).

## Scope

The workspace aliases `yaml_serde` 0.10.7 as `serde_yaml`, replacing the unmaintained parser and its unsafe-libyaml dependency. Rust imports and the public skill parser interface remain unchanged. `yaml-parser-bazel-aliases.patch` carries Cargo dependency aliases into Bazel targets, preserving each target's dependency kinds and platform conditions.

## Patch variants and application order

`maintained-yaml-parser.patch` integrates with this repository's sorted patch
queue. It follows the issue #71 dependency upgrade, whose lockfile entries
(such as `indexmap` 2.14.2) form its context.

`maintained-yaml-parser.upstream.mbox` is the self-contained upstream
contribution variant. Apply it directly to the clean upstream base with
`git apply` or `git am`; it requires no other downstream patch. Its `.mbox`
suffix keeps the queue application script from applying both variants. The
parser change is the same in both variants.

## Reproduction

Run `cargo deny check` from `codex-rs` on the declared upstream base. Its unsafe-libyaml 0.2.11 dependency triggers the unmaintained RUSTSEC-2024-0449 advisory.

## Verification

- From the patched checkout root, `just fmt-check` and `just bazel-lock-check` pass.
- `just test -p codex-skills` passes all skill tests.
- `bazel test //codex-rs/skills:skills-unit-tests //codex-rs/skills:skills-yaml_dependency_alias-test` compiles the renamed dependency and passes the public frontmatter regression. The alias regression requires `maintained-yaml-parser.patch`; apply the parser patch before the alias patch for verification.
- From `codex-rs`, `cargo deny check` reports no advisory, license, ban, or source failures.

## Upstream status

The upstream stable base retains the unmaintained dependency. The maintained fork is [yaml/yaml-serde](https://github.com/yaml/yaml-serde).
