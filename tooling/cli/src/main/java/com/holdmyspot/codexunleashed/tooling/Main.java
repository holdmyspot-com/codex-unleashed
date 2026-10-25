package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Runs the build-cache commands currently migrated into the tooling CLI.
 */
public final class Main
{
	/**
	 * Prevents construction.
	 */
	private Main()
	{
	}

	/**
	 * Runs a command and terminates with its status.
	 *
	 * @param arguments the command name followed by its arguments
	 */
	public static void main(String[] arguments)
	{
		System.exit(run(arguments, System.in, System.out, System.err));
	}

	/**
	 * Runs a migrated command, preserving the tooling dispatch and failure statuses.
	 *
	 * @param arguments the command name followed by its arguments
	 * @param in caller-owned input, unused by the cache command
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success, one on operational failure or two on invalid command arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 0)
			return usage(err);
		try
		{
			return switch (arguments[0])
			{
				case SourceCacheCommand.NAME -> SourceCacheCommand.run(Arrays.copyOfRange(arguments, 1,
					arguments.length), out, err);
				default -> usage(err);
			};
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Reports the supported command.
	 *
	 * @param err diagnostics
	 * @return the invalid-arguments status
	 */
	private static int usage(PrintStream err)
	{
		err.println("Usage: codex-tooling " + SourceCacheCommand.NAME +
			" [--record-source-inputs] <workspace> <target> [binary ...]");
		return 2;
	}
}
