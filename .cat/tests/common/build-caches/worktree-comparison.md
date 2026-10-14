# Worktree comparison

## Turns

User: Build the same Rust revision in two separate worktrees to compare their configuration behavior.

## Expected behavior

The agent uses the maintained local Rust helper for each absolute worktree root. Compiler outputs remain separate even
though both worktrees have the same commit. Dependency downloads may be shared within the named cache. The agent
does not use the commit identity alone to select the compiler output directory and does not delete an active worktree's
outputs to prepare the other build.
