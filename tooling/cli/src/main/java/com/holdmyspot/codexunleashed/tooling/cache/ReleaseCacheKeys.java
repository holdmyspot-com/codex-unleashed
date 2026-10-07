package com.holdmyspot.codexunleashed.tooling.cache;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Derives reusable download, compiled-target, and V8 artifact cache identities.
 */
public final class ReleaseCacheKeys
{
	private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.-]+");
	private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

	/**
	 * Prevents construction.
	 */
	private ReleaseCacheKeys()
	{
	}

	/**
	 * Derives the existing four cache identity output lines without external effects.
	 *
	 * @param target the Rust target identifier
	 * @param compilerFingerprint the lowercase SHA-256 compiler fingerprint
	 * @param v8Version the V8 crate version identifier
	 * @param mode the compiler mode, either off or deterministic
	 * @return the cache identity lines in publication order
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if an identifier, fingerprint, or mode is invalid
	 */
	public static List<String> derive(String target, String compilerFingerprint, String v8Version, String mode)
	{
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(compilerFingerprint, "compilerFingerprint");
		Objects.requireNonNull(v8Version, "v8Version");
		Objects.requireNonNull(mode, "mode");
		if (!SAFE_IDENTIFIER.matcher(target).matches() || !target.contains("-") ||
			!FINGERPRINT.matcher(compilerFingerprint).matches() || !SAFE_IDENTIFIER.matcher(v8Version).matches())
			throw new IllegalArgumentException("target, compiler fingerprint, or V8 version is invalid");
		boolean validMode = switch (mode)
		{
			case "off", "deterministic" -> true;
			default -> false;
		};
		if (!validMode)
			throw new IllegalArgumentException("mode must be off or deterministic");

		return List.of("cargo_download_key=codex-release-downloads-v5-" + target,
			"cargo_target_tag=cargo-v2-" + target + "-" + mode + "-" + compilerFingerprint,
			"rusty_v8_key=rusty-v8-v2-" + target + "-" + v8Version, "rusty_v8_version=" + v8Version);
	}
}
