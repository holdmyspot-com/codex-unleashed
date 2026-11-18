package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates one build's policy observations, diagnostic reports, termination and owned cleanup.
 */
public final class QuietBuildCoordinator
{
	/** Established prefix shared by every quiet-build diagnostic. */
	private static final String DIAGNOSTIC_PREFIX = "watch-quiet-build:";

	/** Inactivity policy used for this build. */
	private final QuietBuildPolicy policy;
	/** Boundary that starts the owned build. */
	private final BuildAccess builds;
	/** Factory for the build's CPU reader. */
	private final QuietBuildCpu.ReaderFactory cpuReaders;
	/** Reads the newest output timestamp. */
	private final OutputReader output;
	/** Appends process reports. */
	private final ReportWriter reports;
	/** Resource sampler owned and closed by this coordinator's run. */
	private final ResourceSampler sampler;
	/** Time source used for every policy observation. */
	private final Clock clock;
	/** Waits between observations. */
	private final Sleeper sleeper;
	/** Writes safe diagnostics. */
	private final Diagnostics diagnostics;

	/**
	 * Constructs a one-run coordinator that owns the supplied sampler and borrows its other collaborators.
	 *
	 * @param policy      policy that decides when to report, read CPU, or stop
	 * @param builds      boundary that starts and owns the build
	 * @param cpuReaders  factory for process CPU observations
	 * @param output      reader for the latest build output time
	 * @param reports     appender for process reports
	 * @param sampler     invocation-owned resource sampler closed after this run
	 * @param clock       source of monitoring instants
	 * @param sleeper     delay between observations
	 * @param diagnostics writer for fixed safe diagnostics
	 * @throws NullPointerException if any argument is null
	 */
	public QuietBuildCoordinator(QuietBuildPolicy policy, BuildAccess builds, QuietBuildCpu.ReaderFactory cpuReaders,
		OutputReader output, ReportWriter reports, ResourceSampler sampler, Clock clock, Sleeper sleeper,
		Diagnostics diagnostics)
	{
		this.policy = Objects.requireNonNull(policy, "policy");
		this.builds = Objects.requireNonNull(builds, "builds");
		this.cpuReaders = Objects.requireNonNull(cpuReaders, "cpuReaders");
		this.output = Objects.requireNonNull(output, "output");
		this.reports = Objects.requireNonNull(reports, "reports");
		this.sampler = Objects.requireNonNull(sampler, "sampler");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
	}

	/**
	 * Runs and monitors the command from the supplied arguments and child context.
	 *
	 * @param arguments validated watcher arguments
	 * @param context   child invocation paths and explicitly supplied variables
	 * @return completion, monitoring failure or quiet timeout after successful cleanup
	 * @throws NullPointerException if an argument is null
	 * @throws IOException          if startup, reporting, process cleanup or owned resource cleanup fails
	 * @throws InterruptedException if waiting or cleanup is interrupted
	 */
	public Result run(QuietBuildArguments.Values arguments, QuietBuildCapture.Context context)
		throws IOException, InterruptedException
	{
		try (ResourceSampler ownedSampler = sampler)
		{
			try
			{
				Objects.requireNonNull(arguments, "arguments");
				Objects.requireNonNull(context, "context");
				return runWithSampler(arguments, context, ownedSampler);
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw failure;
			}
		}
	}

	/**
	 * Samples resources before launch, then runs the process lifecycle inside the sampler's ownership scope.
	 *
	 * @param arguments    validated watcher arguments
	 * @param context      child invocation paths and explicitly supplied variables
	 * @param ownedSampler sampler owned by the surrounding scope
	 * @return typed process or monitoring result
	 * @throws IOException          if startup, reporting or process cleanup fails
	 * @throws InterruptedException if waiting or process cleanup is interrupted
	 */
	private Result runWithSampler(QuietBuildArguments.Values arguments, QuietBuildCapture.Context context,
		ResourceSampler ownedSampler) throws IOException, InterruptedException
	{
		Instant started = clock.instant();
		pollSampler(ownedSampler, started);
		QuietBuildPolicy.State state = policy.start(started, output.latest());

		try (OwnedBuild process = Objects.requireNonNull(builds.start(arguments.command(), context.workingDirectory(),
			context.environment()), "build"))
		{
			QuietBuildCpu.Reader cpuReader = cpuReaders.create(process.pid(), context);
			Duration delay = waitDelay(arguments.reportInterval(), arguments.sampleInterval());
			while (true)
			{
				Optional<QuietBuildProcess.Completion> completion = process.poll();
				if (completion.isPresent())
					return new Completed(completion.get());

				sleeper.sleep(delay);
				Instant now = clock.instant();
				Instant latestOutput = output.latest();
				pollSampler(ownedSampler, now);
				QuietBuildPolicy.Observation observation = policy.observe(state, now, latestOutput);
				if (observation instanceof QuietBuildPolicy.Continue continuing)
				{
					state = continuing.state();
					continue;
				}
				if (observation instanceof QuietBuildPolicy.WriteReport report)
				{
					writeReport(arguments, report.quiet(), now);
					state = report.state();
					diagnostics.write(periodicReport(report.quiet(), arguments.reportPath()));
					continue;
				}
				if (observation instanceof QuietBuildPolicy.StopQuietBuild stopping)
					return stopQuietBuild(process, arguments, now, stopping.quiet());

				QuietBuildPolicy.ReadCpu request = (QuietBuildPolicy.ReadCpu) observation;
				Map<Long, Duration> cpuSnapshot;
				try
				{
					cpuSnapshot = cpuReader.read();
				}
				catch (IOException failure)
				{
					return stopAfterCpuFailure(process, arguments, request, failure);
				}
				QuietBuildPolicy.Decision decision = policy.cpuRead(request, cpuSnapshot);
				if (decision instanceof QuietBuildPolicy.Continue continuing)
				{
					state = continuing.state();
					continue;
				}
				if (decision instanceof QuietBuildPolicy.WriteReport report)
				{
					writeReport(arguments, report.quiet(), request.now());
					state = report.state();
					diagnostics.write(periodicReport(report.quiet(), arguments.reportPath()));
					continue;
				}
				QuietBuildPolicy.StopQuietBuild stopping = (QuietBuildPolicy.StopQuietBuild) decision;
				return stopQuietBuild(process, arguments, request.now(), stopping.quiet());
			}
		}
	}

	/**
	 * Applies best-effort reporting and the two completion checks around a CPU failure diagnostic.
	 *
	 * @param process owned build
	 * @param arguments validated watcher arguments
	 * @param request pending CPU observation
	 * @param failure CPU reader failure
	 * @return exact completion or monitoring failure after successful stop and reap
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process cleanup is interrupted
	 */
	private Result stopAfterCpuFailure(OwnedBuild process, QuietBuildArguments.Values arguments,
		QuietBuildPolicy.ReadCpu request, IOException failure) throws IOException, InterruptedException
	{
		Optional<QuietBuildProcess.Completion> completion = process.poll();
		if (completion.isPresent())
			return new Completed(completion.get());

		QuietBuildPolicy.MonitoringFailure monitoringFailure = policy.cpuFailed(request);
		try
		{
			writeReport(arguments, monitoringFailure.quiet(), request.now());
		}
		catch (IOException reportFailure)
		{
			diagnostics.write(reportFailure(reportFailure));
		}
		diagnostics.write(cpuFailure(failure));

		completion = process.poll();
		if (completion.isPresent())
			return new Completed(completion.get());

		stopAndReap(process, true);
		return new MonitoringFailed();
	}

	/**
	 * Reports and stops a build whose quiet duration reached its configured limit.
	 *
	 * @param process owned build
	 * @param arguments validated watcher arguments
	 * @param now observation instant used by the policy
	 * @param quiet elapsed quiet duration
	 * @return quiet-timeout result after the owned group is gone and root is reaped
	 * @throws IOException if report writing or process cleanup fails
	 * @throws InterruptedException if process cleanup is interrupted
	 */
	private Result stopQuietBuild(OwnedBuild process, QuietBuildArguments.Values arguments, Instant now,
		Duration quiet) throws IOException, InterruptedException
	{
		writeReport(arguments, quiet, now);
		diagnostics.write(timeout(quiet, arguments.reportPath()));
		stopAndReap(process, true);
		return new QuietTimeout();
	}

	/**
	 * Stops the process group or tree and reaps the root before returning a failure result.
	 *
	 * @param process owned build
	 * @param requireGone whether non-GONE stop outcomes prevent a failure result
	 * @throws IOException if stopping does not establish a gone group/tree or reaping fails
	 * @throws InterruptedException if stopping or reaping is interrupted
	 */
	private void stopAndReap(OwnedBuild process, boolean requireGone) throws IOException, InterruptedException
	{
		QuietBuildProcess.StopOutcome outcome = process.stop();
		Optional<QuietBuildProcess.Completion> completion = process.poll();
		if (completion.isEmpty())
			process.await();
		if (requireGone && outcome.state() != QuietBuildProcess.GroupState.GONE)
			throw new IOException("Quiet-build process group or tree did not stop: " + outcome.state());
	}

	/**
	 * Appends one report using the observation's supplied instant and optional jcmd path.
	 *
	 * @param arguments validated watcher arguments
	 * @param quiet elapsed quiet duration
	 * @param reportTime report instant
	 * @throws IOException if report writing fails
	 */
	private void writeReport(QuietBuildArguments.Values arguments, Duration quiet, Instant reportTime)
		throws IOException
	{
		reports.append(arguments.reportPath(), quiet, reportTime, arguments.jcmd());
	}

	/**
	 * Polls the optional resource sampler and continues if its implementation reports an unchecked acquisition failure.
	 *
	 * @param ownedSampler sampler owned by the current run
	 * @param now observation instant
	 */
	private void pollSampler(ResourceSampler ownedSampler, Instant now)
	{
		try
		{
			ownedSampler.poll(now);
		}
		catch (RuntimeException failure)
		{
			diagnostics.write(DIAGNOSTIC_PREFIX + " resource sampling failed (" + failure.getClass().getSimpleName() + ")");
		}
	}

	/**
	 * Returns a bounded, nonnegative delay for the next observation.
	 *
	 * @param reportInterval configured report interval
	 * @param sampleInterval configured resource sample interval
	 * @return minimum of one second, one quarter report interval and sample interval, clamped at zero
	 */
	private static Duration waitDelay(Duration reportInterval, Duration sampleInterval)
	{
		Duration delay = Duration.ofSeconds(1);
		Duration reportDelay = reportInterval.dividedBy(4);
		if (reportDelay.compareTo(delay) < 0)
			delay = reportDelay;
		if (sampleInterval.compareTo(delay) < 0)
			delay = sampleInterval;
		if (delay.isNegative())
			return Duration.ZERO;
		return delay;
	}

	/**
	 * Formats the periodic report diagnostic with ties-to-even whole-second rounding.
	 *
	 * @param quiet elapsed quiet duration
	 * @param reportPath report destination
	 * @return safe diagnostic line
	 */
	private static String periodicReport(Duration quiet, Path reportPath)
	{
		return DIAGNOSTIC_PREFIX + " no output or CPU use for " + roundedSeconds(quiet) + " seconds; process report: " +
			reportPath;
	}

	/**
	 * Formats the quiet timeout diagnostic with ties-to-even whole-second rounding.
	 *
	 * @param quiet elapsed quiet duration
	 * @param reportPath report destination
	 * @return safe diagnostic line
	 */
	private static String timeout(Duration quiet, Path reportPath)
	{
		return DIAGNOSTIC_PREFIX + " stopping quiet build after " + roundedSeconds(quiet) +
			" seconds without output or CPU use; process report: " + reportPath;
	}

	/**
	 * Formats a signed duration as whole decimal seconds using ties-to-even rounding.
	 *
	 * @param duration signed duration
	 * @return rounded whole seconds, preserving negative zero
	 */
	private static String roundedSeconds(Duration duration)
	{
		BigDecimal seconds = BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
		String rounded = seconds.setScale(0, RoundingMode.HALF_EVEN).toPlainString();
		if (duration.isNegative() && rounded.equals("0"))
			return "-0";
		return rounded;
	}

	/**
	 * Formats the CPU monitoring diagnostic without exposing exception details.
	 *
	 * @param failure CPU reader failure
	 * @return safe diagnostic line
	 */
	private static String cpuFailure(IOException failure)
	{
		return DIAGNOSTIC_PREFIX + " CPU monitoring failed (" + failure.getClass().getSimpleName() + "); stopping build";
	}

	/**
	 * Formats a report failure diagnostic without exposing exception details.
	 *
	 * @param failure report writer failure
	 * @return safe diagnostic line
	 */
	private static String reportFailure(IOException failure)
	{
		return DIAGNOSTIC_PREFIX + " process report failed (" + failure.getClass().getSimpleName() + ")";
	}

	/**
	 * Adapts the production launcher to the coordinator's process boundary.
	 *
	 * @param launcher launcher used to start the owned process
	 * @return process access boundary
	 * @throws NullPointerException if {@code launcher} is null
	 */
	public static BuildAccess buildAccess(QuietBuildLauncher launcher)
	{
		Objects.requireNonNull(launcher, "launcher");
		return (command, workingDirectory, environment) ->
		{
			QuietBuildProcess process = launcher.start(command, workingDirectory, environment);
			return new OwnedBuild()
			{
				@Override
				public long pid()
				{
					return process.pid();
				}

				@Override
				public Optional<QuietBuildProcess.Completion> poll() throws IOException
				{
					return process.poll();
				}

				@Override
				public QuietBuildProcess.Completion await() throws IOException, InterruptedException
				{
					return process.await();
				}

				@Override
				public QuietBuildProcess.StopOutcome stop() throws IOException, InterruptedException
				{
					return process.stop();
				}

				@Override
				public void close() throws IOException
				{
					process.close();
				}
			};
		};
	}

	/**
	 * Adapts the build output reader.
	 *
	 * @param output build output reader
	 * @return timestamp reader
	 * @throws NullPointerException if {@code output} is null
	 */
	public static OutputReader outputReader(BuildOutput output)
	{
		Objects.requireNonNull(output, "output");
		return output::latest;
	}

	/**
	 * Adapts process report creation and appending.
	 *
	 * @param factory report factory
	 * @param dumpRunner process dump writer
	 * @return report writer
	 * @throws NullPointerException if an argument is null
	 */
	public static ReportWriter reportWriter(QuietBuildReport.Factory factory, QuietBuildReport.DumpRunner dumpRunner)
	{
		Objects.requireNonNull(factory, "factory");
		Objects.requireNonNull(dumpRunner, "dumpRunner");
		return (reportPath, quiet, reportTime, jcmd) -> factory.create(reportPath, dumpRunner).
			append(quiet, reportTime, Optional.ofNullable(jcmd));
	}

	/**
	 * Adapts the real resource sampler.
	 *
	 * @param sampler sampler that owns the platform resource reader
	 * @return sampler lifecycle
	 * @throws NullPointerException if {@code sampler} is null
	 */
	public static ResourceSampler resourceSampler(QuietBuildResourceSampler sampler)
	{
		Objects.requireNonNull(sampler, "sampler");
		return new ResourceSampler()
		{
			@Override
			public void poll(Instant now)
			{
				sampler.poll(now);
			}

			@Override
			public void close() throws IOException
			{
				sampler.close();
			}
		};
	}

	/**
	 * Build completion or a monitored failure outcome.
	 */
	public sealed interface Result permits Completed, MonitoringFailed, QuietTimeout
	{
	}

	/**
	 * A completed build with its platform-specific exit result.
	 *
	 * @param completion typed Unix or Windows process completion
	 */
	public record Completed(QuietBuildProcess.Completion completion) implements Result
	{
		/**
		 * Creates a completed result.
		 *
		 * @param completion typed process completion
		 * @throws NullPointerException if {@code completion} is null
		 */
		public Completed
		{
			Objects.requireNonNull(completion, "completion");
		}
	}

	/**
	 * CPU monitoring failed and the owned build was stopped and reaped.
	 */
	public record MonitoringFailed() implements Result
	{
		/**
		 * Creates a monitoring-failure result.
		 */
		public MonitoringFailed
		{
		}
	}

	/**
	 * A quiet build was stopped and reaped successfully.
	 */
	public record QuietTimeout() implements Result
	{
		/**
		 * Creates a quiet-timeout result.
		 */
		public QuietTimeout
		{
		}
	}

	/**
	 * Starts a process owned by the coordinator.
	 */
	@FunctionalInterface
	public interface BuildAccess
	{
		/**
		 * Starts a build with its literal command, working directory and the environment policy of
		 * {@link QuietBuildLauncher}.
		 *
		 * @param command          executable and arguments
		 * @param workingDirectory child working directory
		 * @param environment      explicit child variables and their values
		 * @return owned build lifecycle
		 * @throws IOException if process startup fails
		 */
		OwnedBuild start(List<String> command, Path workingDirectory, Map<String, String> environment) throws IOException;
	}

	/**
	 * Lifecycle of a build process owned by the coordinator.
	 */
	public interface OwnedBuild extends AutoCloseable
	{
		/**
		 * Returns the root process identifier.
		 *
		 * @return root process identifier
		 */
		long pid();

		/**
		 * Reaps a completed process or reports that it is still running.
		 *
		 * @return completion, or empty while the process is running
		 * @throws IOException if completion cannot be observed
		 */
		Optional<QuietBuildProcess.Completion> poll() throws IOException;

		/**
		 * Waits for and reaps the process.
		 *
		 * @return typed process completion
		 * @throws IOException if waiting fails
		 * @throws InterruptedException if waiting is interrupted
		 */
		QuietBuildProcess.Completion await() throws IOException, InterruptedException;

		/**
		 * Stops the owned process group or tree.
		 *
		 * @return observed group or tree stop outcome
		 * @throws IOException if stopping fails
		 * @throws InterruptedException if stopping is interrupted
		 */
		QuietBuildProcess.StopOutcome stop() throws IOException, InterruptedException;

		/**
		 * Closes the owned process and reaps outstanding work.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Reads the timestamp of the latest output from the build.
	 */
	@FunctionalInterface
	public interface OutputReader
	{
		/**
		 * Returns the newest output instant.
		 *
		 * @return newest output instant, or {@link Instant#EPOCH} when none exists
		 */
		Instant latest();
	}

	/**
	 * Appends one process report.
	 */
	@FunctionalInterface
	public interface ReportWriter
	{
		/**
		 * Appends the quiet duration, report instant and optional diagnostic command.
		 *
		 * @param reportPath report destination
		 * @param quiet      elapsed quiet duration
		 * @param reportTime report instant
		 * @param nullableJcmd optional jcmd path, or null when absent
		 * @throws IOException if report writing fails
		 */
		void append(Path reportPath, Duration quiet, Instant reportTime, Path nullableJcmd) throws IOException;
	}

	/**
	 * Supplies optional resource observations and owns its reader for a run.
	 */
	public interface ResourceSampler extends AutoCloseable
	{
		/**
		 * Records resource observations available at the supplied instant.
		 *
		 * @param now observation instant
		 */
		void poll(Instant now);

		/**
		 * Releases the sampler's owned reader.
		 *
		 * @throws IOException if sampler cleanup fails
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Advances to the next monitoring observation.
	 */
	@FunctionalInterface
	public interface Sleeper
	{
		/**
		 * Waits for the supplied nonnegative delay.
		 *
		 * @param delay duration until the next observation
		 * @throws InterruptedException if waiting is interrupted
		 */
		void sleep(Duration delay) throws InterruptedException;
	}

	/**
	 * Writes one diagnostic line that contains no command or environment data.
	 */
	@FunctionalInterface
	public interface Diagnostics
	{
		/**
		 * Writes a fixed safe diagnostic line.
		 *
		 * @param safeLine diagnostic text
		 */
		void write(String safeLine);
	}
}
