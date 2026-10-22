---
agents: ["main", "subagents"]
---
# Project Rule Routing

## Design Goals

- Route project-specific decisions directly to their authoritative rules before the governed action.
- Keep unrelated tasks from loading conventions they cannot apply.

## Guidance

When defining, serializing, or documenting Codex-Unleashed-owned `config.toml` values, lazy load
`../include/codex-configuration-values.md`.

When configuring or running this project's Maven tooling build, lazy load `../include/maven.md`.

When selecting or changing a date-time representation in project-owned data, names, logs, or documentation, lazy load
`../include/dates-and-times.md`.

When defining tooling command output or exit behavior, lazy load `../include/cli-output.md`.

When an explanation or instructional workflow must make an operation, relationship, term, option, or audience flow
precise, lazy load `../include/documentation/precision.md`.

When creating or revising a Git commit message, lazy load `../include/commit-messages.md`.

When defining, parsing, reading, or processing project-owned JSON or YAML, or handling independently processable
unbounded record or binary input, lazy load `../include/data.md`.

When applying a rule, convention, policy, migration, or systematic correction across existing artifacts, lazy load
`../include/rule-coverage.md`.
