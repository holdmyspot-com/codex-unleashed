# Codex-Unleashed Configuration Values

## Design Goals

- Give downstream-owned fixed-vocabulary string values consistent spellings while preserving upstream configuration
  contracts and user-provided content.

## Guidance

Spell fixed-vocabulary string values in Codex-Unleashed-owned `config.toml` settings in lowercase kebab-case: separate
words with hyphens, not underscores. Use `dedicated-terminal`; single-word values such as `codex` and `claude` remain
lowercase. Keep declarations, serialization, configuration examples, and documentation consistent.

Keep configuration keys in snake_case, such as `background_tasks_mode`. Preserve upstream Codex value spellings.
This convention does not rewrite free text, paths, URLs, model names, externally defined identifiers, booleans, or
numbers, and it does not prescribe spellings for other protocol formats.
