package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * Runs and monitors one quiet-build process using invocation-scoped collaborators.
 */
public final class QuietBuildCommand
{
	/**
	 * Prevents construction.
	 */
	private QuietBuildCommand()
	{
	}

	/**
	 * Parses watcher options, runs the build coordinator and maps its result to a process status. Invalid options throw
	 * {@link IllegalArgumentException}; I/O failures and interruption return status one, with interruption restored.
	 * Unsupported native host behavior returns status one with a safe diagnostic.
	 *
	 * @param scope     invocation scope providing shared services
	 * @param arguments watcher options and build command, excluding the command name
	 * @param context   child invocation paths and explicitly supplied variables
	 * @param platform  host platform
	 * @param launcher  validated packaged launcher path
	 * @param err       caller-owned diagnostic stream
	 * @return build exit status, negative signal number, one for monitoring or operational failure, or 124 for quiet
	 *         timeout
	 * @throws IllegalArgumentException if the watcher options are invalid
	 * @throws NullPointerException     if a required argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, QuietBuildCapture.Context context,
		QuietBuildDump.Platform platform, Path launcher, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(platform, "platform");
		Objects.requireNonNull(launcher, "launcher");
		Objects.requireNonNull(err, "err");

		QuietBuildArguments.Values parsed = QuietBuildArguments.parse(arguments, context.environment(),
			platform == QuietBuildDump.Platform.WINDOWS);
		Path watchDirectory = context.workingDirectory().resolve(parsed.watchDirectory()).normalize();
		Path reportPath = context.workingDirectory().resolve(parsed.reportPath()).normalize();
		QuietBuildArguments.Values normalized = new QuietBuildArguments.Values(watchDirectory, reportPath,
			parsed.jcmd(),
			parsed.firstReport(), parsed.reportInterval(), parsed.stopAfter(), parsed.sampleInterval(),
			parsed.cpuInterval(), parsed.minimumCpu(), parsed.command());
		Path samples = reportPath.resolveSibling("build-resources.jsonl");

		try
		{
			return runCoordinator(scope, normalized, context, platform, launcher, err, samples);
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			err.println("ERROR: watch-quiet-build failed (InterruptedException)");
			return 1;
		}
		catch (IOException _)
		{
			err.println("ERROR: watch-quiet-build failed (IOException)");
			return 1;
		}
		catch (UnsupportedOperationException _)
		{
			err.println("ERROR: watch-quiet-build requires a supported native process API on this host " +
				"(UnsupportedOperationException)");
			return 1;
		}
	}

	/**
	 * Maps a completed build or coordinator condition to the status observed by the calling process.
	 *
	 * @param result completed build, monitoring failure or quiet timeout
	 * @return Unix exit code, negative Unix signal number, Windows exit bits as a signed integer, one for monitoring
	 *         failure, or 124 for quiet timeout
	 * @throws NullPointerException if {@code result} is null
	 */
	public static int exitStatus(QuietBuildCoordinator.Result result)
	{
		Objects.requireNonNull(result, "result");
		if (result instanceof QuietBuildCoordinator.Completed completed)
		{
			return switch (completed.completion())
			{
				case QuietBuildProcess.UnixExit exit -> exit.code();
				case QuietBuildProcess.UnixSignal signal -> -signal.number();
				case QuietBuildProcess.WindowsExit exit -> (int) exit.unsignedCode();
			};
		}
		if (result instanceof QuietBuildCoordinator.MonitoringFailed)
			return 1;
		if (result instanceof QuietBuildCoordinator.QuietTimeout)
			return 124;
		throw new IllegalArgumentException("Unsupported quiet-build coordinator result");
	}

	/**
	 * Creates borrowed collaborators before the sampler, then gives sampler ownership to the coordinator.
	 *
	 * @param scope invocation scope providing shared services
	 * @param arguments normalized watcher arguments
	 * @param context complete process context
	 * @param platform host platform
	 * @param launcher validated packaged launcher path
	 * @param err caller-owned diagnostic stream
	 * @param samples normalized resource-samples destination
	 * @return status mapped from the coordinator result
	 * @throws IOException if native identity lookup or coordinator I/O fails
	 * @throws InterruptedException if coordinator waiting is interrupted
	 */
	private static int runCoordinator(ToolingScope scope, QuietBuildArguments.Values arguments,
		QuietBuildCapture.Context context, QuietBuildDump.Platform platform, Path launcher, PrintStream err, Path samples)
		throws IOException, InterruptedException
	{
		QuietBuildProcessAccess capture = new QuietBuildProcessAccess();
		long effectiveUid = new QuietBuildNativeFacts(scope, platform).access().effectiveUid();
		QuietBuildDumpRunner dumpRunner = new QuietBuildDumpRunner(capture, new QuietBuildDumpProcessAccess(), context,
			platform, launcher, effectiveUid);
		BuildOutput buildOutput = new BuildOutput(scope, arguments.watchDirectory(), arguments.reportPath(), samples);
		QuietBuildLauncher buildLauncher = new QuietBuildLauncher(scope);
		QuietBuildCpu.ReaderFactory cpuReaders = QuietBuildCpu.factory(scope, capture);
		QuietBuildResources.ReaderFactory resourceReaders = QuietBuildResourceReaders.factory(scope, capture, platform);
		QuietBuildPolicy.Limits limits = new QuietBuildPolicy.Limits(arguments.firstReport(), arguments.reportInterval(),
			arguments.stopAfter(), arguments.cpuInterval(), arguments.minimumCpu());
		QuietBuildPolicy policy = new QuietBuildPolicy(limits);
		QuietBuildCoordinator.BuildAccess builds = QuietBuildCoordinator.buildAccess(buildLauncher);
		QuietBuildCoordinator.OutputReader outputReader = QuietBuildCoordinator.outputReader(buildOutput);
		QuietBuildCoordinator.ReportWriter reportWriter =
			QuietBuildCoordinator.reportWriter(QuietBuildReport.factory(), dumpRunner);
		Clock clock = Clock.systemUTC();
		QuietBuildCoordinator.Sleeper sleeper = Thread::sleep;
		QuietBuildCoordinator.Diagnostics diagnostics = err::println;
		QuietBuildResourceSampler sampler = new QuietBuildResourceSampler(scope, samples, arguments.sampleInterval(),
			resourceReaders, context);
		QuietBuildCoordinator coordinator = new QuietBuildCoordinator(policy, builds, cpuReaders, outputReader,
			reportWriter, QuietBuildCoordinator.resourceSampler(sampler), clock, sleeper, diagnostics);
		return exitStatus(coordinator.run(arguments, context));
	}
}
