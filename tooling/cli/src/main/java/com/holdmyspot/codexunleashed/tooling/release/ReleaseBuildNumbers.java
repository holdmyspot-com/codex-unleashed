package com.holdmyspot.codexunleashed.tooling.release;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects a vendor build number independently for each upstream release tag.
 */
public final class ReleaseBuildNumbers
{
	private static final String TAG_WHITESPACE = "[\\s\\x{001c}-\\x{001f}]*";
	/**
	 * Prevents construction.
	 */
	private ReleaseBuildNumbers()
	{
	}

	/**
	 * Selects one greater than the largest positive vendor build for the upstream tag.
	 *
	 * @param upstreamTag the upstream release tag in the form {@code rust-vX.Y.Z}
	 * @param tags the repository tag inventory
	 * @return the next build number, or one when no vendor builds exist
	 * @throws NullPointerException if {@code upstreamTag}, {@code tags}, or any tag are null
	 * @throws IllegalArgumentException if the upstream tag has an invalid form
	 */
	public static BigInteger next(String upstreamTag, List<String> tags)
	{
		Objects.requireNonNull(upstreamTag, "upstreamTag");
		Objects.requireNonNull(tags, "tags");
		ReleaseTags.validateStableUpstreamTag(upstreamTag);
		Pattern pattern = Pattern.compile(TAG_WHITESPACE + Pattern.quote(upstreamTag) +
			"\\+([1-9][0-9]*)" + TAG_WHITESPACE, Pattern.UNICODE_CHARACTER_CLASS);
		BigInteger largest = BigInteger.ZERO;
		for (String tag : tags)
		{
			Matcher match = pattern.matcher(tag);
			if (match.matches())
				largest = largest.max(new BigInteger(match.group(1)));
		}
		return largest.add(BigInteger.ONE);
	}
}
