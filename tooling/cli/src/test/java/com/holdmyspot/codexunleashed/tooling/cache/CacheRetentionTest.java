package com.holdmyspot.codexunleashed.tooling.cache;

import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies release lineage and dependency-cache retention decisions.
 */
public final class CacheRetentionTest
{
	/**
	 * Creates the cache retention policy tests.
	 */
	public CacheRetentionTest()
	{
	}

	/**
	 * Associates stable ASCII release tags with their own cache lineage.
	 */
	@Test
	public void associatesStableRelease()
	{
		requireThat(CacheRetention.prefix("rust-v0.160.0"), "prefix").isEqualTo("upstream-rust-v0.160.0-");
		requireThat(CacheRetention.prefix("rust-v00.01.02"), "prefix").isEqualTo("upstream-rust-v00.01.02-");
	}

	/**
	 * Keeps branches, prereleases, vendor tags, Unicode digits, and padded references in the development lineage.
	 */
	@Test
	public void associatesDevelopmentReferences()
	{
		for (String reference : new String[]{"main", "rust-v0.160.0-alpha.1", "rust-v0.160.0+41", "rust-v٠.١.٢",
			" rust-v0.160.0", "rust-v0.160.0\n", ""})
			requireThat(CacheRetention.prefix(reference), "prefix").isEqualTo("upstream-unreleased-");
	}

	/**
	 * Retains the two greatest stable versions while removing repeated tags.
	 */
	@Test
	public void retainsNewestStableReleases()
	{
		requireThat(CacheRetention.retainedStableTags(List.of("rust-v0.99.0", "rust-v0.160.0",
			"rust-v0.159.3", "rust-v0.161.0-alpha.1", "rust-v0.160.0")), "retainedTags").isEqualTo(
			List.of("rust-v0.160.0", "rust-v0.159.3"));
	}

	/**
	 * Preserves distinct spellings and input order for numerically equal versions.
	 */
	@Test
	public void preservesEqualVersionSpellings()
	{
		requireThat(CacheRetention.retainedStableTags(List.of("rust-v00.01.02", "rust-v0.1.2",
			"rust-v00.01.02", "rust-v0.1.1")), "retainedTags").isEqualTo(List.of("rust-v00.01.02", "rust-v0.1.2"));
	}

	/**
	 * Orders version components without fixed-width integer overflow.
	 */
	@Test
	public void ordersArbitraryVersionComponents()
	{
		requireThat(CacheRetention.retainedStableTags(List.of("rust-v99999999999999999999999.0.0",
			"rust-v99999999999999999999998.9.9", "rust-v1.0.0")), "retainedTags").isEqualTo(
			List.of("rust-v99999999999999999999999.0.0", "rust-v99999999999999999999998.9.9"));
	}

	/**
	 * Refuses cleanup when no stable release provides a retention boundary.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "No stable upstream releases found; refusing to delete caches")
	public void rejectsMissingStableReleases()
	{
		CacheRetention.retainedStableTags(List.of("main", "rust-v0.160.0-alpha.1", "rust-v0.160.0\n"));
	}

	/**
	 * Removes old managed caches while preserving retained releases and unrelated keys.
	 */
	@Test
	public void identifiesObsoleteManagedCaches()
	{
		List<String> retained = List.of("rust-v0.160.0", "rust-v0.159.3");
		for (String key : List.of("bazel-cache-upstream-rust-v0.159.2-linux", "pnpm-legacy",
			"rusty-v8-legacy", "v0-rust-codex-release-downloads-v5", "setup-uv-2-old",
			"apt-upstream-unreleased-linux"))
			requireThat(CacheRetention.isObsoleteCache(key, retained), key).isTrue();
		for (String key : List.of("pnpm-upstream-rust-v0.160.0-linux", "v0-upstream-rust-v0.159.3-linux",
			"setup-uv-2-upstream-rust-v0.160.0-linux", "unrelated-cache",
			"notupstream-rust-v0.159.2-linux"))
			requireThat(CacheRetention.isObsoleteCache(key, retained), key).isFalse();
	}
}
