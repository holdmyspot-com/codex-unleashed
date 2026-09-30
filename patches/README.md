# Patch Queue

## Enableable features

- `unleashed_agent_fast_switching` keeps `/subagents` switching back to the
  main agent responsive when its history spans many compactions. It is enabled
  by default. See
  [its patch README](holdmyspot-com/codex-unleashed/issue-34776/README.md) for
  the feature controls.
- `unleashed_expanded_diff_previews` shows complete patch diffs in the
  full-screen transcript when both the feature flag and project setting are
  enabled. The feature flag is enabled by default; the
  `[codex_unleashed] expanded_diff_previews` project setting defaults to
  `false`, so previews remain compact until a project opts in. See
  [its patch README](holdmyspot-com/codex-unleashed/issue-47390/README.md) for
  the feature controls and project property.
- `unleashed_collapsed_tool_activity` groups adjacent file reads and shell
  commands into compact summaries. In full-screen chat, hover highlights the
  summary and clicking expands or collapses the complete commands and output.
  The feature flag is enabled by default; the
  `[codex_unleashed] collapsed_tool_activity` property defaults to `false`,
  so grouping requires
  explicit opt-in. See
  [its patch README](holdmyspot-com/codex-unleashed/collapsed-tool-activity/README.md)
  for the feature controls and mouse interaction requirements.

## Contributor rules

Follow the [project patch rules](../.cat/rules/common/patch-queue.md) when adding or updating patches.

## Security and trust

- Patch files are public and intended to be small, auditable, and removable.
- Review patch diffs, workflow definitions, and release notes before trusting a binary.
- Send security reports according to [SECURITY.md](../SECURITY.md).
