package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.TomlDocuments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Resolves package metadata versions using the upstream workspace version and executable vendor build policy.
 */
public final class PackageVersions
{
	/**
	 * Prevents construction.
	 */
	private PackageVersions()
	{
	}

	/**
	 * Appends the exact supplied build number, defaulting to dev only when the environment variable is absent.
	 *
	 * @param workspace the patched upstream checkout
	 * @param environment the explicit caller environment
	 * @return the workspace version plus the vendor build suffix
	 * @throws IOException if the workspace manifest is unreadable, malformed, or lacks a nonempty version string
	 * @throws NullPointerException if an argument or environment entry is null
	 */
	public static String resolve(Path workspace, Map<String, String> environment) throws IOException
	{
		Objects.requireNonNull(workspace, "workspace");
		Map<String, String> variables = Map.copyOf(environment);
		Path manifest = workspace.resolve("codex-rs/Cargo.toml");
		String text = Files.readString(manifest);
		JsonNode document;
		try
		{
			document = TomlDocuments.parse(text);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse package version from " + manifest, failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Missing workspace package version in " + manifest);
		JsonNode version = document.path("workspace").path("package").path("version");
		if (!version.isString() || version.stringValue().isEmpty())
			throw new IOException("Workspace package version must be a nonempty string in " + manifest);
		return version.stringValue() + "+" + variables.getOrDefault("CODEX_UNLEASHED_BUILD_NUMBER", "dev");
	}
}
