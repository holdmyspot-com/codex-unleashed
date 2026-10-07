package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Records delivery to the retained upstream check boundary without executing upstream Python tests. */
public final class UpstreamChecksFixture
{
	/** Prevents construction. */
	private UpstreamChecksFixture()
	{
	}

	/**
	 * Accepts only the maintained upstream recipe and writes its invocation marker.
	 *
	 * @param arguments the expected upstream recipe
	 * @throws IOException if the invocation is wrong or its marker cannot be written
	 */
	public static void main(String[] arguments) throws IOException
	{
		if (!Arrays.equals(arguments, new String[]{"test-github-scripts"}))
			throw new IOException("Unexpected upstream recipe: " + Arrays.toString(arguments));
		Files.writeString(Path.of(System.getenv("UPSTREAM_CHECK_MARKER")), "test-github-scripts");
	}
}
