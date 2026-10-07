package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Captures literal native-child arguments and returns a fixture-selected Bazel status. */
public final class BazelChildFixture
{
	/** Prevents construction. */
	private BazelChildFixture()
	{
	}

	/**
	 * Writes NUL-separated direct arguments and a fixed diagnostic before terminating.
	 *
	 * @param arguments the actual child argv
	 * @throws IOException if fixture output cannot be written
	 */
	public static void main(String[] arguments) throws IOException
	{
		Files.writeString(Path.of(System.getenv("BAZEL_FIXTURE_ARGUMENTS")), String.join("\0", arguments));
		System.out.println("fixture bazel output");
		System.exit(Integer.parseInt(System.getenv("BAZEL_FIXTURE_STATUS")));
	}
}
