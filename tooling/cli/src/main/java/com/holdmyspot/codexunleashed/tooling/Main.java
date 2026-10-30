package com.holdmyspot.codexunleashed.tooling;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Runs cache commands, retention previews, V8 script extraction and explicit scheduler selection.
 */
public final class Main
{
	/**
	 * Names the upstream V8 action script extraction command.
	 */
	private static final String V8_SCRIPT_COMMAND = "upstream-v8-script";

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
	 * Runs a tooling command and writes its output and diagnostics to the supplied streams.
	 *
	 * @param arguments the command name followed by its arguments
	 * @param in caller-owned inventory input for retention previews; remains open and is unused by the other commands
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success, one on operational failure, unavailable scheduling or a rejected input document, or two on an unknown command or invalid command arguments
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
				case "schedule" -> Scheduler.run(Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case V8_SCRIPT_COMMAND -> writeV8Script(arguments, out, err);
				case RetentionPreview.NAME -> RetentionPreview.run(Arrays.copyOfRange(arguments, 1,
					arguments.length), in, out, err);
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
	 * Writes an upstream V8 action's decoded script for execution in a caller-owned child environment.
	 *
	 * @param arguments the command name and action document path
	 * @param out the unchanged decoded script
	 * @param err usage diagnostics
	 * @return zero on success or two when the action path argument is missing or extra arguments are supplied
	 * @throws IOException if the action document cannot be read
	 */
	private static int writeV8Script(String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		if (arguments.length != 2)
			return usage(err);
		out.print(UpstreamV8Script.extract(Files.readString(Path.of(arguments[1]))));
		return 0;
	}

	/**
	 * Reports the supported commands.
	 *
	 * @param err diagnostics
	 * @return the invalid-arguments status
	 */
	private static int usage(PrintStream err)
	{
		err.println("Usage: codex-tooling " + SourceCacheCommand.NAME +
			" [--record-source-inputs] [--profile release|ci-test] <workspace> <target> [binary ...]");
		err.println("       codex-tooling " + RetentionPreview.NAME + " < inventory.json");
		err.println("       codex-tooling schedule <scheduler-options> -- <tool-arguments>");
		err.println("       codex-tooling " + V8_SCRIPT_COMMAND + " <upstream-action.yml>");
		return 2;
	}
}
