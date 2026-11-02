# Maven

## Design Goals

- Keep Maven configuration centralized, reproducible, and consistent with project conventions.
- Run complete Maven verification with quiet routine output, actionable failure diagnostics, and an explicit
	evidence-backed reason for any normal-output exception.

## Guidance

- Declare dependency versions only in the root POM's `<dependencyManagement>` section.
- Declare plugin versions and plugin dependency versions only in the root POM's `<pluginManagement>` section.

Use Maven quiet mode (`-q`) for routine builds, tests, packages, and verification. Add it while constructing the
command, before running Maven. It preserves failures and exit status while omitting routine reactor, cache, transfer,
and plugin messages. Do not skip modules, tests, linters, or other quality gates merely to reduce output. If a quiet
command fails without enough information to choose the next action, rerun the same proven scope without `-q` only to
obtain the missing diagnostic detail. Use normal output when the user requests it or when a named successful Maven
output is itself required evidence; state that output and why the command exit status or a produced artifact cannot
prove it before invoking Maven. A build artifact, installer result, later tool result, or general confirmation that a
build succeeded is not such an exception.
