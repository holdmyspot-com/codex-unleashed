package com.holdmyspot.codexunleashed.tooling.cache;

import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
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
		requireThat(ReleaseCacheKeys.derive(TARGET, FINGERPRINT, "1.2.3", "off"), "keys").isEqualTo(List.of(
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
			requireThat(changed.get(0), "downloadKey").isEqualTo(base.get(0));
		requireThat(target.get(0), "targetDownloadKey").isNotEqualTo(base.get(0));
		for (List<String> changed : List.of(compiler, mode, target))
			requireThat(changed.get(1), "cargoTag").isNotEqualTo(base.get(1));
		requireThat(v8.get(2), "v8Key").isNotEqualTo(base.get(2));
		requireThat(compiler.get(2), "compilerV8Key").isEqualTo(base.get(2));
		requireThat(mode.get(2), "modeV8Key").isEqualTo(base.get(2));
	}
}
