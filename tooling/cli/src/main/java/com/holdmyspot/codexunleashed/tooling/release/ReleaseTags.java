package com.holdmyspot.codexunleashed.tooling.release;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates stable upstream release tags while preserving their raw decimal digit spelling.
 */
public final class ReleaseTags
{
	private static final Pattern STABLE_UPSTREAM = Pattern.compile("rust-v\\d+\\.\\d+\\.\\d+",
		Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * Prevents construction.
	 */
	private ReleaseTags()
	{
	}

	/**
	 * Validates an upstream release tag in the form rust-vX.Y.Z.
	 *
	 * @param upstreamTag the raw upstream release tag
	 * @throws NullPointerException if {@code upstreamTag} is null
	 * @throws IllegalArgumentException if the tag has an invalid form
	 */
	public static void validateStableUpstreamTag(String upstreamTag)
	{
		Objects.requireNonNull(upstreamTag, "upstreamTag");
		if (!STABLE_UPSTREAM.matcher(upstreamTag).matches())
			throw new IllegalArgumentException("upstream tag must be rust-vX.Y.Z");
	}
}
