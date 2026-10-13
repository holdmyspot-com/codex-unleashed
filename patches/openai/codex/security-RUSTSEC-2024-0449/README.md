# Maintained YAML frontmatter parsing

## Intent

Skill frontmatter uses a maintained YAML parser while preserving existing supported frontmatter behavior.

## Reference and upstream base

- [Canonical reference](https://rustsec.org/advisories/RUSTSEC-2024-0449.html).
- Base: rust-v0.162.1 (`092d3acd6bec3e3a14bdc7e7a2810ab628ab759d`).

## Scope

The workspace aliases `yaml_serde` 0.10.7 as `serde_yaml`, replacing the unmaintained parser and its unsafe-libyaml dependency. Rust imports and the public skill parser interface remain unchanged.

## Reproduction

The declared upstream base fails the corresponding verification when the affected dependency or native Windows build path is checked.

## Verification

- From the patched checkout root, `just fmt-check` passes.
- `just test -p codex-skills` passes all skill tests.
- From `codex-rs`, `cargo deny check` reports no advisory, license, ban, or source failures.

## Upstream status

The upstream stable base retains the unmaintained dependency. The maintained fork is [yaml/yaml-serde](https://github.com/yaml/yaml-serde).
