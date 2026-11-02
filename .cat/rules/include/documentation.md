# Documentation Rule Routing

## Design Goals

- Route documentation-specific guidance from the writing task's observable trigger so readers load only the companion
	rule that governs their documentation decision.

When writing or editing documentation or help for an operation, command, option, or user-facing workflow, or when an
explanation or instructional workflow must make an operation, relationship, term, option, or audience flow precise,
lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/documentation/precision.md`.
