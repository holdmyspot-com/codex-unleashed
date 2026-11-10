---
agents: ["main", "subagents"]
---
# Project Rule Routing

## Design Goals

- Route each project-specific reader decision directly to its authoritative rule before the reader takes the governed
	action.
- Keep project routes narrow enough that unrelated work does not receive policy it cannot apply.

## Guidance

When changing an API, configuration entry, file format, data structure, command, or documented procedure, or writing
documentation or a code comment that describes an earlier contract, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/contract-maintenance.md`.

When writing or editing documentation for a function or method, command, configuration entry, or user-facing workflow,
lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/documentation.md`.

Before constructing or running a Maven command in this project, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`, then `${CAT_PROJECT_ROOT}/.cat/rules/include/maven.md`.

When applying a rule, convention, policy, migration, or systematic correction across existing artifacts, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/rule-coverage.md`.

When a proposed prevention, rule revision, or prompt-file change itself creates or preserves the wrong prevention for a
reported failure, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/mistake-learning.md`. Analyze the reported
operational failure first through the bundled Failure Prevention rule.

When creating or revising a Git commit message, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/commit-messages.md`.

When defining, writing, parsing, reading, or processing JSON, JSONL, or YAML that this project owns, including Markdown
frontmatter, writing a key-value pair in project-owned Markdown, or handling independently handleable unbounded record
or binary input, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/data.md`.

When writing, parsing, or displaying a date or time, including in a name or identifier, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/dates-and-times.md`.

When writing code, a command, a hook, or a skill or rule instruction that passes an argument to a launcher, lazy
load `${CAT_PROJECT_ROOT}/.cat/rules/include/jlink-launchers/launcher-arguments.md`.

When defining, serializing, or documenting Codex-Unleashed-owned `config.toml` values, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/codex-configuration-values.md`.

When running local builds, tests, formatting, or packaging for this project, or creating, reusing, or cleaning build
caches and outputs, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/common/build-caches.md`.
