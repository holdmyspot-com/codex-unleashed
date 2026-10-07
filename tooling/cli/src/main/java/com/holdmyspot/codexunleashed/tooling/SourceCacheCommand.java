package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Runs source-cache preparation and explicit successful-build recording. */
public final class SourceCacheCommand
{
	/** The maintained source-cache command name. */
	public static final String NAME = "clean-cached-release-binaries";

	/** Prevents construction. */
	private SourceCacheCommand()
	{
	}

	/**
	 * Preserves first-position recording selection and the original positional argument contract.
	 *
	 * @param arguments command arguments without the command name
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success or two for invalid arguments
	 * @throws NullPointerException if an argument or output stream is null
	 * @throws IOException if preparation or recording fails
	 */
	public static int run(String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		boolean record = arguments.length != 0 && arguments[0].equals("--record-source-inputs");
		int offset = 0;
		int minimum = 3;
		if (record)
		{
			offset = 1;
			minimum = 2;
		}
		if (arguments.length - offset < minimum)
		{
			err.println("Usage: " + NAME + " [--record-source-inputs] <workspace> <target> [binary ...]");
			return 2;
		}
		SourceInputCache.Request request;
		try
		{
			Set<String> binaries = Set.of();
			if (!record)
				binaries = new TreeSet<>(Arrays.asList(arguments).subList(offset + 2, arguments.length));
			request = new SourceInputCache.Request(Path.of(arguments[offset]), arguments[offset + 1], binaries);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}

		SourceInputCache.Context context = new SourceInputCache.Context(Path.of("").toAbsolutePath(),
			Path.of(System.getProperty("java.io.tmpdir")), System.getenv(), Clock.systemUTC());
		if (record)
			SourceInputCache.record(request, context, out, err);
		else
			SourceInputCache.prepare(request, context, out, err);
		return 0;
	}
}
