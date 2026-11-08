package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Runs cache commands, retention previews and V8 script extraction.
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
	 * @param in caller-owned inventory input for retention previews; remains open
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success, one on operational failure or a rejected input document, or two on an unknown command or
	 * invalid command arguments
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
		try (ToolingScope scope = new DefaultToolingScope())
		{
			return switch (arguments[0])
			{
				case "cargo-cache-keys" -> CargoCacheKeys.run(Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
				case "verify-cargo-lock" -> VerifyCargoLock.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), out,
					err);
				case "cargo-cache-parts" -> CargoCacheParts.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length),
					System.getenv(), out, err);
				case V8_SCRIPT_COMMAND -> writeV8Script(scope, arguments, out, err);
				case GhcrRetentionPlan.NAME -> GhcrRetentionPlan.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), in, out, err);
				case ActionsCacheRetentionPlan.NAME -> ActionsCacheRetentionPlan.run(scope, Arrays.copyOfRange(arguments, 1,
					arguments.length), in, out, err);
				case SourceCacheCommand.NAME -> SourceCacheCommand.run(scope, Arrays.copyOfRange(arguments, 1,
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
	 * @param scope invocation-owned shared values
	 * @param arguments the command name and action document path
	 * @param out the unchanged decoded script
	 * @param err usage diagnostics
	 * @return zero on success or two when the action path argument is missing or extra arguments are supplied
	 * @throws IOException if the action document cannot be read
	 */
	private static int writeV8Script(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err) throws IOException
	{
		if (arguments.length != 2)
			return usage(err);
		out.print(UpstreamV8Script.extract(scope, Files.readString(Path.of(arguments[1]))));
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
		err.println("       codex-tooling " + GhcrRetentionPlan.NAME + " < inventory.json");
		err.println("       codex-tooling " + ActionsCacheRetentionPlan.NAME + " < inventory.json");
		err.println("       codex-tooling cargo-cache-keys <target> <upstream> <wrappers> <helper> <compiler> [<patches>]");
		err.println("       codex-tooling verify-cargo-lock <original> <current> <workspace-metadata>");
		err.println("       codex-tooling cargo-cache-parts <push|pull> <repository> <reference> <previous> <target> <temporary> [options]");
		err.println("       codex-tooling " + V8_SCRIPT_COMMAND + " <upstream-action.yml>");
		return 2;
	}
}
