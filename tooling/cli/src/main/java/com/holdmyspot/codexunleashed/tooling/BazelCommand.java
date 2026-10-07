package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
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
		return SystemCommands.execute(invocation.command(), directory, environment);
	}
}
