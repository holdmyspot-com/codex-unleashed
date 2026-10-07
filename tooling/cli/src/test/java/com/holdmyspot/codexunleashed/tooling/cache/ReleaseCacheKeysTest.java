package com.holdmyspot.codexunleashed.tooling.cache;

import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.expectThrows;

/**
 * Verifies reusable release cache identities and their input boundaries.
 */
public final class ReleaseCacheKeysTest
{
	private static final String TARGET = "x86_64-unknown-linux-gnu";
	private static final String FINGERPRINT = "a".repeat(64);

	/**
	 * Creates the release cache identity tests.
	 */
	public ReleaseCacheKeysTest()
	{
	}

	/**
	 * Derives the four release cache output fields in their existing order.
	 */
	@Test
	public void derivesReusableIdentities()
	{
		assertEquals(ReleaseCacheKeys.derive(TARGET, FINGERPRINT, "1.2.3", "off"), List.of(
			"cargo_download_key=codex-release-downloads-v5-" + TARGET,
			"cargo_target_tag=cargo-v2-" + TARGET + "-off-" + FINGERPRINT,
			"rusty_v8_key=rusty-v8-v2-" + TARGET + "-1.2.3",
			"rusty_v8_version=1.2.3"));
	}

	/**
	 * Rejects unsafe identifiers, non-lowercase fingerprints, and undeclared modes.
	 */
	@Test
	public void rejectsInvalidInputs()
	{
		for (String[] input : new String[][]{{"bad/target", FINGERPRINT, "1.2.3", "off"},
			{"single", FINGERPRINT, "1.2.3", "off"}, {TARGET, "A".repeat(64), "1.2.3", "off"},
			{TARGET, "a".repeat(63), "1.2.3", "off"}, {TARGET, FINGERPRINT, "../1.2", "off"},
			{TARGET, FINGERPRINT, "", "off"}, {TARGET, FINGERPRINT, "1.2.3", "fast"},
			{TARGET, FINGERPRINT, "1.2.3\n", "off"}})
			expectThrows(IllegalArgumentException.class,
				() -> ReleaseCacheKeys.derive(input[0], input[1], input[2], input[3]));
	}

	/**
	 * Isolates compiled and V8 cache inputs while retaining reusable download identities.
	 */
	@Test
	public void isolatesCacheInputs()
	{
		List<String> base = ReleaseCacheKeys.derive(TARGET, FINGERPRINT, "1.2.3", "off");
		List<String> compiler = ReleaseCacheKeys.derive(TARGET, "b".repeat(64), "1.2.3", "off");
		List<String> mode = ReleaseCacheKeys.derive(TARGET, FINGERPRINT, "1.2.3", "deterministic");
		List<String> v8 = ReleaseCacheKeys.derive(TARGET, FINGERPRINT, "9.8.7", "off");
		List<String> target = ReleaseCacheKeys.derive("aarch64-unknown-linux-gnu", FINGERPRINT, "1.2.3", "off");
		for (List<String> changed : List.of(compiler, mode, v8))
			assertEquals(changed.get(0), base.get(0));
		assertNotEquals(target.get(0), base.get(0));
		for (List<String> changed : List.of(compiler, mode, target))
			assertNotEquals(changed.get(1), base.get(1));
		assertNotEquals(v8.get(2), base.get(2));
		assertEquals(compiler.get(2), base.get(2));
		assertEquals(mode.get(2), base.get(2));
	}
}
