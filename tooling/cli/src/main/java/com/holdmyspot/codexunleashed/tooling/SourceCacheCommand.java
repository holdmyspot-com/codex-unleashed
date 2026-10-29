package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs source-cache preparation and explicit successful-build recording.
 */
public final class SourceCacheCommand
{
	/**
	 * The maintained source-cache command name.
	 */
	public static final String NAME = "clean-cached-release-binaries";

	/**
	 * Prevents construction.
	 */
	private SourceCacheCommand()
	{
	}

	/**
	 * Prepares or records release inputs by default, with optional ci-test profile selection before the workspace.
	 * Recording remains the first option when selected; positional binary arguments are ignored during recording.
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
		if (record)
			offset = 1;
		SourceInputCache.Request request;
		try
		{
			CargoCacheProfile profile = CargoCacheProfile.RELEASE;
			boolean hasProfileOption = arguments.length > offset && arguments[offset].equals("--profile");
			if (hasProfileOption)
			{
				if (arguments.length <= offset + 1)
					throw new IllegalArgumentException("--profile requires release or ci-test");
				profile = CargoCacheProfile.parse(arguments[offset + 1]);
				offset += 2;
			}
			int minimum = 3;
			if (record)
				minimum = 2;
			if (arguments.length - offset < minimum)
			{
				err.println("Usage: " + NAME + " [--record-source-inputs] [--profile release|ci-test] " +
					"<workspace> <target> [binary ...]");
				return 2;
			}

			Set<String> binaries = Set.of();
			if (!record)
				binaries = new TreeSet<>(Arrays.asList(arguments).subList(offset + 2, arguments.length));
			request = new SourceInputCache.Request(Path.of(arguments[offset]), arguments[offset + 1], binaries, profile);
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
