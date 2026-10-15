package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes Bazel with the selected BuildBuddy configuration and caller-owned standard streams. */
public final class BazelCommand
{
	/** The maintained Bazel wrapper command. */
	public static final String NAME = "run-bazel-with-buildbuddy";
	// WORKAROUND: https://github.com/bazelbuild/bazel/issues/31786
	// Remove this exact diagnostic filter when Bazel's launcher stops adding -Xverify:none.
	private static final byte[] OBSOLETE_VERIFICATION_WARNING = ("OpenJDK 64-Bit Server VM warning: " +
		"Options -Xverify:none and -noverify were deprecated in JDK 13 and will likely be removed in a future release.").
		getBytes(StandardCharsets.US_ASCII);

	/** Prevents construction. */
	private BazelCommand()
	{
	}

	/**
	 * Runs the direct Bazel child and returns its ordinary status without shell argument reparsing.
	 *
	 * @param arguments literal Bazel arguments
	 * @param err diagnostic output
	 * @return child exit status, or two when no command is supplied
	 * @throws NullPointerException if arguments, an argument element or diagnostics are null
	 * @throws IOException if event encoding, process startup or waiting fails
	 */
	public static int run(String[] arguments, PrintStream err) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(err, "err");
		List<String> values = List.copyOf(Arrays.asList(arguments));
		if (values.stream().allMatch(value -> value.startsWith("-")))
		{
			err.println("Usage: " + NAME + " [startup options] <Bazel command> [arguments]");
			return 2;
		}
		Map<String, String> environment = System.getenv();
		Path directory = Path.of("").toAbsolutePath();
		BazelCommands.Invocation invocation = BazelCommands.plan(values, environment, directory);
		if (invocation.remoteConfig().isEmpty())
			err.println("BuildBuddy key unavailable; using local Bazel configuration.");
		else
		{
			String host = "generic";
			if (invocation.openaiHost())
				host = "OpenAI tenant";
			err.println("Using " + host + " BuildBuddy configuration: " + invocation.remoteConfig().orElseThrow() + ".");
		}
		ProcessBuilder builder = SystemCommands.createBuilder(invocation.command()).directory(directory.toFile()).
			inheritIO().redirectError(ProcessBuilder.Redirect.PIPE);
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try (InputStream diagnostics = new BufferedInputStream(process.getErrorStream()))
			{
				copyDiagnostics(diagnostics, err);
			}
			try
			{
				return process.waitFor();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Bazel", failure);
			}
		}
	}

	/**
	 * Streams raw diagnostic bytes, removing only the complete obsolete launcher warning with LF, CRLF, or EOF.
	 * A matching prefix is bounded by the warning length; other output streams immediately without line buffering.
	 *
	 * @param input the child's diagnostic stream
	 * @param output the caller's diagnostic stream
	 * @throws IOException if reading the child diagnostics fails
	 */
	private static void copyDiagnostics(InputStream input, PrintStream output) throws IOException
	{
		int matched = 0;
		for (int value = input.read(); value != -1; value = input.read())
		{
			if (matched >= 0 && matched < OBSOLETE_VERIFICATION_WARNING.length &&
				value == OBSOLETE_VERIFICATION_WARNING[matched])
			{
				matched += 1;
				continue;
			}
			if (matched == OBSOLETE_VERIFICATION_WARNING.length)
			{
				if (value == '\n')
				{
					matched = 0;
					continue;
				}
				if (value == '\r')
				{
					int following = input.read();
					if (following == '\n')
					{
						matched = 0;
						continue;
					}
					output.write(OBSOLETE_VERIFICATION_WARNING);
					output.write(value);
					if (following >= 0)
						output.write(following);
					matched = -1;
					continue;
				}
			}
			if (matched > 0)
				output.write(OBSOLETE_VERIFICATION_WARNING, 0, matched);
			output.write(value);
			matched = -1;
			if (value == '\n')
				matched = 0;
		}
		if (matched > 0 && matched < OBSOLETE_VERIFICATION_WARNING.length)
			output.write(OBSOLETE_VERIFICATION_WARNING, 0, matched);
		output.flush();
	}
}
