package com.holdmyspot.codexunleashed.tooling.cache;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Associates dependency caches with upstream releases and selects their retention policy.
 */
public final class CacheRetention
{
	private static final Pattern STABLE_TAG = Pattern.compile("rust-v[0-9]+\\.[0-9]+\\.[0-9]+");
	private static final Pattern CACHE_RELEASE =
		Pattern.compile("(?:^|-)upstream-(rust-v[0-9]+\\.[0-9]+\\.[0-9]+)(?:-|$)");
	private static final Pattern LEGACY_DEPENDENCY_CACHE = Pattern.compile(
		"^(pnpm-|node-cache-.*-pnpm-|apt-|rusty-v8-|bazel-cache-|setup-uv-|" +
			"v[0-9]+-rust-(?:codex-release-downloads-|repo-checks-))");

	/**
	 * Prevents construction.
	 */
	private CacheRetention()
	{
	}

	/**
	 * Associates a cache key with a stable release or the development lineage.
	 *
	 * @param upstreamRef the upstream reference
	 * @return the release association prefix
	 * @throws NullPointerException if {@code upstreamRef} is null
	 */
	public static String prefix(String upstreamRef)
	{
		Objects.requireNonNull(upstreamRef, "upstreamRef");
		if (STABLE_TAG.matcher(upstreamRef).matches())
			return "upstream-" + upstreamRef + "-";
		return "upstream-unreleased-";
	}

	/**
	 * Selects the two newest distinct stable release tags in descending numeric order.
	 * Numerically equal spellings retain their input order.
	 *
	 * @param tags the upstream release tags
	 * @return the retained tags
	 * @throws NullPointerException if {@code tags} or any tag is null
	 * @throws IllegalArgumentException if no stable release is present
	 */
	public static List<String> retainedStableTags(List<String> tags)
	{
		List<String> validatedTags = List.copyOf(tags);
		List<String> stableTags = new ArrayList<>();
		for (String tag : new LinkedHashSet<>(validatedTags))
		{
			if (STABLE_TAG.matcher(tag).matches())
				stableTags.add(tag);
		}
		if (stableTags.isEmpty())
			throw new IllegalArgumentException("No stable upstream releases found; refusing to delete caches");

		stableTags.sort(CacheRetention::compareNewestFirst);
		return List.copyOf(stableTags.subList(0, Math.min(2, stableTags.size())));
	}

	/**
	 * Indicates whether a managed dependency cache falls outside the retained release lineages.
	 * A stable release association takes precedence over a legacy cache prefix.
	 *
	 * @param key the cache key
	 * @param retainedTags the retained stable release tags
	 * @return true if the cache is obsolete
	 * @throws NullPointerException if either argument or a retained tag is null
	 */
	public static boolean isObsoleteCache(String key, List<String> retainedTags)
	{
		Objects.requireNonNull(key, "key");
		List<String> validatedTags = List.copyOf(retainedTags);
		Matcher release = CACHE_RELEASE.matcher(key);
		if (release.find())
			return !validatedTags.contains(release.group(1));
		return LEGACY_DEPENDENCY_CACHE.matcher(key).find() || key.contains("upstream-unreleased-");
	}

	/**
	 * Compares validated stable tags by their numeric version components, newest first.
	 *
	 * @param left the first stable tag
	 * @param right the second stable tag
	 * @return the descending version comparison
	 */
	private static int compareNewestFirst(String left, String right)
	{
		String[] leftParts = left.substring("rust-v".length()).split("\\.");
		String[] rightParts = right.substring("rust-v".length()).split("\\.");
		for (int index = 0; index < leftParts.length; ++index)
		{
			int comparison = new BigInteger(rightParts[index]).compareTo(new BigInteger(leftParts[index]));
			if (comparison != 0)
				return comparison;
		}
		return 0;
	}
}
