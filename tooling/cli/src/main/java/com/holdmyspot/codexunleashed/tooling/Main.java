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
     * Pending native cleanup reports the original failure and incomplete closure before exit status one.
	 *
	 * @param arguments the command name followed by its arguments
	 */
	public static void main(String[] arguments)
	{
        int status;
        try { status = run(arguments, System.in, System.out, System.err); }
        catch (Scheduler.PendingCleanup pending)
        {
            Scheduler.report(pending.getCause(), System.err);
            System.err.println("Native cleanup is incomplete; terminating with failure.");
            status = 1;
        }
        System.exit(status);
	}

	/**
	 * Runs a tooling command and writes its output and diagnostics to the supplied streams.
	 *
	 * @param arguments the command name followed by its arguments
	 * @param in caller-owned inventory input for retention previews or inherited native scheduler input; remains open
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success, one on operational failure, unavailable scheduling or a rejected input document, or two on an unknown command or invalid command arguments
	 * @throws NullPointerException if an argument is null
     * @throws Scheduler.PendingCleanup if native cleanup remains pending; the exception retains tooling ownership
     * and must be closed on the calling thread before its scopes can close
	 */
	public static int run(String[] arguments, InputStream in, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(in, "in");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 0)
			return usage(err);
        ToolingScope scope = new DefaultToolingScope();
        boolean retained = false;
        try
        {
			return switch (arguments[0])
			{
				case "prepare-release-native" -> prepareReleaseNative(Arrays.copyOfRange(arguments, 1, arguments.length));
                case "validate-release-native" -> {
                    if (arguments.length != 5)
                        throw new IllegalArgumentException("Expected native output, producer-support, host and architecture");
                    try
                    {
                        PrepareReleaseSchedulerNative.validateReuse(Path.of(arguments[1]), Path.of(arguments[2]),
                            arguments[3], arguments[4]);
                    }
                    catch (UnsupportedOperationException unsupported)
                    {
                        throw new IllegalArgumentException(unsupported.getMessage(), unsupported);
                    }
                    yield 0;
                }
                case "validate-release-installation" -> {
                    ValidateReleaseSchedulerInstallation.main(Arrays.copyOfRange(arguments, 1, arguments.length));
                    yield 0;
                }
                case "prepare-cargo-launch" -> com.holdmyspot.codexunleashed.scheduler.CargoLaunchConfigurationCommand.run(scope,
                    Arrays.copyOfRange(arguments, 1, arguments.length), out);
                case "cargo-cache-keys" -> CargoCacheKeys.run(Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
                case "verify-cargo-lock" -> VerifyCargoLock.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), out, err);
                case "cargo-cache-parts" -> CargoCacheParts.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), System.getenv(), out, err);
                case "schedule" -> Scheduler.run(scope, Arrays.copyOfRange(arguments, 1, arguments.length), in, out, err);
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
        catch (Scheduler.PendingCleanup pending)
        {
            pending.retain(scope);
            retained = true;
            throw pending;
        }
		catch (IOException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
        finally { if (!retained) scope.close(); }
	}


    /**
     * Produces authenticated platform inputs using the bundled Java runtime and owned native tools.
     * @param arguments the fresh output directory and immutable producer-support directory
     * @return zero after every production command completes successfully
     * @throws IOException if acquisition, validation, command execution or interruption prevents completion
     */
    private static int prepareReleaseNative(String[] arguments) throws IOException
    {
        try { PrepareReleaseSchedulerNative.main(arguments); }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Native preparation was interrupted; retain its owned inputs and command evidence", interrupted);
        }
        return 0;
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
		err.println("       codex-tooling schedule <scheduler-options> -- <tool-arguments>");
        err.println("       codex-tooling cargo-cache-keys <target> <upstream> <wrappers> <helper> <compiler>");
        err.println("       codex-tooling verify-cargo-lock <original> <current> <workspace-metadata>");
        err.println("       codex-tooling cargo-cache-parts <push|pull> <repository> <reference> <previous> <target> <temporary> [options]");
        err.println("       codex-tooling prepare-release-native <fresh-output> <producer-support>");
        err.println("       codex-tooling validate-release-native <output> <producer-support> <Linux|macOS|Windows> <architecture>");
        err.println("       codex-tooling validate-release-installation <installation>");
        err.println("       codex-tooling prepare-cargo-launch <explicit platform/tool/runtime/output options>");
		err.println("       codex-tooling " + V8_SCRIPT_COMMAND + " <upstream-action.yml>");
		return 2;
	}
}
