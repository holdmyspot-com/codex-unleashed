package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stops reproduction at the first Git transport boundary without downloading or building a release. */
public final class ReproductionGitFixture
{
	/** Prevents construction. */
	private ReproductionGitFixture()
	{
	}

	/**
	 * Records the attempted clone and returns the configured transport refusal.
	 *
	 * @param arguments Git arguments
	 * @throws IOException if the clone invocation is wrong or its marker cannot be written
	 */
	public static void main(String[] arguments) throws IOException
	{
		if (arguments.length == 0 || !arguments[0].equals("clone"))
			throw new IOException("Expected the reproduction clone boundary");
		Files.writeString(Path.of(System.getenv("REPRODUCTION_GIT_MARKER")), "clone");
		System.exit(37);
	}
}
