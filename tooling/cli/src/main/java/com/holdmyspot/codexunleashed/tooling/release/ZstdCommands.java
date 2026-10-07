package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Selects the native zstd executable or the repository's DotSlash compression manifest.
 */
public final class ZstdCommands
{
	/**
	 * Prevents construction.
	 */
	private ZstdCommands()
	{
	}

	/**
	 * Prefers native zstd and falls back to DotSlash only with an existing regular manifest.
	 *
	 * @param manifest the repository's zstd DotSlash manifest
	 * @param executableLookup the executable search boundary, returning absence for an unavailable tool
	 * @return the immutable executable command prefix
	 * @throws IOException if neither compression route is available
	 * @throws NullPointerException if any argument or executable lookup result is null
	 */
	public static List<String> resolve(Path manifest, Function<String, Optional<Path>> executableLookup)
		throws IOException
	{
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(executableLookup, "executableLookup");
		Optional<Path> zstd = Objects.requireNonNull(executableLookup.apply("zstd"), "zstd lookup result");
		if (zstd.isPresent())
			return List.of(zstd.orElseThrow().toString());
		Optional<Path> dotslash = Objects.requireNonNull(executableLookup.apply("dotslash"), "dotslash lookup result");
		if (dotslash.isPresent() && Files.isRegularFile(manifest))
			return List.of(dotslash.orElseThrow().toString(), manifest.toString());
		throw new IOException("zstd is required to write .tar.zst archives. Install zstd, or install " +
			"DotSlash so the repository wrapper can run: " + manifest);
	}
}
