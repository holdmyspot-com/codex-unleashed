package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Arrays;
import java.util.Objects;

/**
 * Defines the Codex CLI and app-server package entrypoints.
 */
public enum PackageVariant
{
	/** The interactive Codex CLI. */
	CODEX("codex", "codex", "codex"),
	/** The Codex app-server. */
	APP_SERVER("codex-app-server", "codex-app-server", "codex-app-server");

	private final String variantName;
	private final String cargoBinary;
	private final String executableStem;

	/**
	 * Assigns one variant's package and binary identities.
	 *
	 * @param variantName the package variant name
	 * @param cargoBinary the Cargo binary target
	 * @param executableStem the packaged executable stem
	 */
	PackageVariant(String variantName, String cargoBinary, String executableStem)
	{
		this.variantName = variantName;
		this.cargoBinary = cargoBinary;
		this.executableStem = executableStem;
	}

	/**
	 * Resolves an exact supported package variant.
	 *
	 * @param name the case-sensitive variant name
	 * @return the package variant
	 * @throws NullPointerException if {@code name} is null
	 * @throws IllegalArgumentException if the variant is unsupported
	 */
	public static PackageVariant fromName(String name)
	{
		Objects.requireNonNull(name, "name");
		return Arrays.stream(values()).filter(variant -> variant.variantName.equals(name)).findFirst().
			orElseThrow(() -> new IllegalArgumentException("Unsupported package variant: " + name));
	}

	/**
	 * Returns the package variant identity.
	 *
	 * @return the variant name
	 */
	public String variantName()
	{
		return variantName;
	}

	/**
	 * Returns the Cargo binary target.
	 *
	 * @return the binary target
	 */
	public String cargoBinary()
	{
		return cargoBinary;
	}

	/**
	 * Returns the variant's packaged executable name for the supplied target.
	 *
	 * @param target the package target
	 * @return the entrypoint filename
	 * @throws NullPointerException if {@code target} is null
	 */
	public String entrypointName(PackageTarget target)
	{
		Objects.requireNonNull(target, "target");
		return executableStem + target.executableSuffix();
	}
}
