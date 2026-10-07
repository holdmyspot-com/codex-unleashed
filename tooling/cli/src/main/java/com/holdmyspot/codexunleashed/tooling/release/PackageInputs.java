package com.holdmyspot.codexunleashed.tooling.release;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies resolved binary inputs for canonical package assembly.
 *
 * @param entrypoint the selected package executable
 * @param codeModeHost the code-mode host executable
 * @param ripgrep the ripgrep executable
 * @param zsh the optional patched zsh executable
 * @param bwrap the optional Linux sandbox executable
 * @param windowsCommandRunner the optional Windows command runner
 * @param windowsSandboxSetup the optional Windows sandbox setup executable
 */
public record PackageInputs(Path entrypoint, Path codeModeHost, Path ripgrep, Optional<Path> zsh,
	Optional<Path> bwrap, Optional<Path> windowsCommandRunner, Optional<Path> windowsSandboxSetup)
{
	/**
	 * Validates the supplied paths and optional containers.
	 *
	 * @param entrypoint the selected package executable
	 * @param codeModeHost the code-mode host executable
	 * @param ripgrep the ripgrep executable
	 * @param zsh the optional patched zsh executable
	 * @param bwrap the optional Linux sandbox executable
	 * @param windowsCommandRunner the optional Windows command runner
	 * @param windowsSandboxSetup the optional Windows sandbox setup executable
	 * @throws NullPointerException if any argument is null
	 */
	public PackageInputs
	{
		Objects.requireNonNull(entrypoint, "entrypoint");
		Objects.requireNonNull(codeModeHost, "codeModeHost");
		Objects.requireNonNull(ripgrep, "ripgrep");
		Objects.requireNonNull(zsh, "zsh");
		Objects.requireNonNull(bwrap, "bwrap");
		Objects.requireNonNull(windowsCommandRunner, "windowsCommandRunner");
		Objects.requireNonNull(windowsSandboxSetup, "windowsSandboxSetup");
	}
}
