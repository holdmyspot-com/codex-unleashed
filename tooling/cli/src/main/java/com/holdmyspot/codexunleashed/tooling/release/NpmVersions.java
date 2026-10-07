package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts exact stable release versions to the npm vendor-build spelling without normalizing their digits.
 */
public final class NpmVersions
{
	private static final Pattern VERSION =
		Pattern.compile("(?<base>\\p{Nd}+\\.\\p{Nd}+\\.\\p{Nd}+)(?:\\+(?<build>[0-9]+))?");

	/**
	 * Prevents construction.
	 */
	private NpmVersions()
	{
	}

	/**
	 * Removes an exact supported release tag prefix, leaving version validation to the publication boundary.
	 *
	 * @param tag the raw release tag
	 * @return the unchanged text after {@code rust-v} or {@code v}
	 * @throws NullPointerException if {@code tag} is null
	 * @throws IllegalArgumentException if the tag has no supported prefix
	 */
	public static String removeTagPrefix(String tag)
	{
		Objects.requireNonNull(tag, "tag");
		if (tag.startsWith("rust-v"))
			return tag.substring("rust-v".length());
		if (tag.startsWith("v"))
			return tag.substring(1);
		throw new IllegalArgumentException("Cannot derive npm version from tag: " + tag);
	}

	/**
	 * Replaces the optional vendor build's plus separator with a hyphen, retaining the exact base and build text.
	 *
	 * @param version the stable three-component release version with an optional ASCII-decimal vendor build
	 * @return the corresponding npm version
	 * @throws NullPointerException if {@code version} is null
	 * @throws IllegalArgumentException if the version has an unsupported form
	 */
	public static String toNpmVersion(String version)
	{
		Objects.requireNonNull(version, "version");
		Matcher match = VERSION.matcher(version);
		if (!match.matches())
			throw new IllegalArgumentException("Expected a stable vendor version such as 0.153.4+25; got " + version);
		String build = match.group("build");
		if (build == null)
			return match.group("base");
		return match.group("base") + "-" + build;
	}
}
