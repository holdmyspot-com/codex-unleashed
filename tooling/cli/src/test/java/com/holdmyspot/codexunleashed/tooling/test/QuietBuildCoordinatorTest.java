package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BuildOutput;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.QuietBuildArguments;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCapture;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCoordinator;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCoordinator.OwnedBuild;
import com.holdmyspot.codexunleashed.tooling.QuietBuildCoordinator.ResourceSampler;
import com.holdmyspot.codexunleashed.tooling.QuietBuildPolicy;
import com.holdmyspot.codexunleashed.tooling.QuietBuildProcess;
import com.holdmyspot.codexunleashed.tooling.QuietBuildReport;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResourceSampler;
import com.holdmyspot.codexunleashed.tooling.QuietBuildResources;
import com.holdmyspot.codexunleashed.tooling.SourceInputTimes;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies quiet-build policy orchestration, process ownership and collaborator adapters.
 */
public final class QuietBuildCoordinatorTest
{
	/**
	 * Creates the test suite.
	 */
	public QuietBuildCoordinatorTest()
	{
	}

	/**
	 * Completed Unix and Windows process observations retain their exact typed status and close the process once.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void preservesTypedCompletions() throws IOException, InterruptedException
	{
		List<QuietBuildProcess.Completion> completions = List.of(new QuietBuildProcess.UnixExit(7),
			new QuietBuildProcess.UnixSignal(15, true), new QuietBuildProcess.WindowsExit(0xffff_ffffL));
		for (QuietBuildProcess.Completion completion : completions)
		{
			Fixture fixture = new Fixture();
			fixture.process.polls.add(Optional.of(completion));

			QuietBuildCoordinator.Result result = fixture.coordinator(limits()).run(arguments(), context());

			requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.Completed(completion));
			requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
			requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
		}
	}

	/**
	 * Samples before process launch and after each wait, with completion polled before sleeping.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void samplesAroundWaitAndPollsBeforeSleeping() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		QuietBuildProcess.Completion completion = new QuietBuildProcess.UnixExit(0);
		fixture.process.polls.add(Optional.empty());
		fixture.process.polls.add(Optional.of(completion));

		fixture.coordinator(limits()).run(arguments(), context());

		requireThat(fixture.events, "eventOrder").isEqualTo(List.of("sample", "output", "start", "cpu.create",
			"poll", "sleep", "output", "sample", "cpu.read", "poll", "process.close", "sampler.close"));
	}

	/**
	 * Returns an exact completion on the first CPU-failure recheck without reporting or stopping the build.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void cpuFailureFirstRecheckCompletes() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		QuietBuildProcess.Completion completion = new QuietBuildProcess.WindowsExit(23);
		fixture.process.polls.add(Optional.empty());
		fixture.process.polls.add(Optional.of(completion));
		fixture.cpuFailure = new IOException("sensitive CPU detail");

		QuietBuildCoordinator.Result result = fixture.coordinator(limits()).run(arguments(), context());

		requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.Completed(completion));
		requireThat(fixture.reportCount, "reportCount").isEqualTo(0);
		requireThat(fixture.diagnostics, "diagnostics").isEmpty();
		requireThat(fixture.events.contains("stop"), "stopped").isFalse();
		requireThat(fixture.events.contains("await"), "awaited").isFalse();
	}

	/**
	 * Reports best-effort, diagnoses report and CPU failures, then stops and reaps a still-running build.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void cpuFailureReportsThenStopsAndReaps() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.cpuFailure = new IOException("secret cpu output");
		fixture.reportFailure = new IOException("secret report output");

		QuietBuildCoordinator.Result result = fixture.coordinator(limits()).run(arguments(), context());

		requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.MonitoringFailed());
		requireThat(fixture.diagnostics, "safeDiagnostics").isEqualTo(List.of(
			"watch-quiet-build: process report failed (IOException)",
			"watch-quiet-build: CPU monitoring failed (IOException); stopping build"));
		requireThat(fixture.events, "eventOrder").isEqualTo(List.of("sample", "output", "start", "cpu.create",
			"poll", "sleep", "output", "sample", "cpu.read", "poll", "report", "diagnostic",
			"diagnostic", "poll", "stop", "poll", "await", "process.close", "sampler.close"));
	}

	/**
	 * Returns the exact completion observed on the second CPU-failure recheck without stopping or awaiting.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void cpuFailureSecondRecheckCompletes() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		QuietBuildProcess.Completion completion = new QuietBuildProcess.UnixSignal(9, false);
		fixture.process.polls.add(Optional.empty());
		fixture.process.polls.add(Optional.empty());
		fixture.process.polls.add(Optional.of(completion));
		fixture.cpuFailure = new IOException("cpu read failed");

		QuietBuildCoordinator.Result result = fixture.coordinator(limits()).run(arguments(), context());

		requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.Completed(completion));
		requireThat(fixture.reportCount, "bestEffortReportCount").isEqualTo(1);
		requireThat(fixture.events.contains("stop"), "stopped").isFalse();
		requireThat(fixture.events.contains("await"), "awaited").isFalse();
	}

	/**
	 * Returns quiet timeout only after reporting, stopping with GONE and reaping the process root.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void timeoutReportsBeforeStoppingAndReaping() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		QuietBuildPolicy.Limits timeoutLimits = new QuietBuildPolicy.Limits(Duration.ZERO, Duration.ofSeconds(4),
			Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(1));

		QuietBuildCoordinator.Result result = fixture.coordinator(timeoutLimits).run(arguments(), context());

		requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.QuietTimeout());
		requireThat(fixture.events, "eventOrder").isEqualTo(List.of("sample", "output", "start", "cpu.create",
			"poll", "sleep", "output", "sample", "cpu.read", "report", "diagnostic", "stop", "poll",
			"await", "process.close", "sampler.close"));
		requireThat(fixture.diagnostics.getFirst(), "timeoutDiagnostic").isEqualTo(
			"watch-quiet-build: stopping quiet build after 1 seconds without output or CPU use; process report: report");
	}

	/**
	 * Does not return a timeout when stop cannot establish that the process group or tree is gone.
	 *
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void nonGoneTimeoutPropagatesAfterRootReap() throws InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.process.stopOutcome = new QuietBuildProcess.StopOutcome(QuietBuildProcess.GroupState.ACCESS_DENIED,
			List.of("permission denied"));
		boolean failed = false;
		try
		{
			fixture.coordinator(timeoutLimits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "failure").isEqualTo(
				"Quiet-build process group or tree did not stop: ACCESS_DENIED");
		}

		requireThat(failed, "nonGoneResult").isTrue();
		requireThat(fixture.events.contains("await"), "rootReaped").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Propagates timeout report failure and closes both acquired owners.
	 *
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void timeoutReportFailureClosesOwners() throws InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.reportFailure = new IOException("report cannot be appended");
		boolean failed = false;
		try
		{
			fixture.coordinator(timeoutLimits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "reportFailure").isEqualTo("report cannot be appended");
		}

		requireThat(failed, "reportFailurePropagated").isTrue();
		requireThat(fixture.events.contains("stop"), "stoppedAfterReportFailure").isFalse();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Preserves the process close failure and suppresses the later sampler close failure.
	 *
	 * @throws IOException if the test receives an unexpected cleanup failure
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void closeFailuresAreSuppressed() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.process.polls.add(Optional.of(new QuietBuildProcess.UnixExit(0)));
		fixture.process.closeFailure = new IOException("process close");
		fixture.samplerCloseFailure = new IOException("sampler close");
		IOException failure = null;
		try
		{
			fixture.coordinator(limits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failure = expected;
		}

		requireThat(failure, "primaryCloseFailure").isNotNull();
		requireThat(failure.getMessage(), "primaryMessage").isEqualTo("process close");
		requireThat(failure.getSuppressed().length, "suppressedFailureCount").isEqualTo(1);
		requireThat(failure.getSuppressed()[0].getMessage(), "suppressedMessage").isEqualTo("sampler close");
		requireThat(fixture.events.subList(fixture.events.size() - 2, fixture.events.size()), "closeOrder").
			isEqualTo(List.of("process.close", "sampler.close"));
	}

	/**
	 * Propagates periodic report failure and closes both acquired owners.
	 *
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void periodicReportFailureClosesOwners() throws InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.reportFailure = new IOException("periodic report failed");
		QuietBuildPolicy.Limits reportLimits = new QuietBuildPolicy.Limits(Duration.ZERO, Duration.ofSeconds(4),
			Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1));
		boolean failed = false;
		try
		{
			fixture.coordinator(reportLimits).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "reportFailure").isEqualTo("periodic report failed");
		}

		requireThat(failed, "periodicReportFailurePropagated").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Propagates stop and await failures after closing acquired owners.
	 *
	 */
	@Test
	public void stopAndAwaitFailuresPropagate()
	{
		Fixture stopFixture = new Fixture();
		stopFixture.process.stopFailure = new IOException("stop failed");
		assertCleanupFailure(stopFixture, "stop failed");

		Fixture awaitFixture = new Fixture();
		awaitFixture.process.awaitFailure = new IOException("await failed");
		assertCleanupFailure(awaitFixture, "await failed");
	}

	/**
	 * Verifies a cleanup failure while ensuring process and sampler owners close.
	 *
	 * @param fixture fixture whose cleanup operation fails
	 * @param expectedMessage expected safe failure message
	 */
	private static void assertCleanupFailure(Fixture fixture, String expectedMessage)
	{
		boolean failed = false;
		try
		{
			fixture.coordinator(timeoutLimits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "cleanupFailure").isEqualTo(expectedMessage);
		}
		catch (InterruptedException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}

		requireThat(failed, "cleanupFailurePropagated").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Propagates process close failure and still closes the sampler.
	 */
	@Test
	public void processCloseFailureClosesSampler()
	{
		Fixture fixture = new Fixture();
		fixture.process.polls.add(Optional.of(new QuietBuildProcess.UnixExit(0)));
		fixture.process.closeFailure = new IOException("process close failed");
		assertCloseFailure(fixture, "process close failed", true);
	}

	/**
	 * Propagates sampler close failure after closing the completed process.
	 */
	@Test
	public void samplerCloseFailurePropagates()
	{
		Fixture fixture = new Fixture();
		fixture.process.polls.add(Optional.of(new QuietBuildProcess.UnixExit(0)));
		fixture.samplerCloseFailure = new IOException("sampler close failed");
		assertCloseFailure(fixture, "sampler close failed", false);
	}

	/**
	 * Verifies a close failure and both owner close counts.
	 *
	 * @param fixture fixture whose selected owner fails on close
	 * @param expectedMessage expected primary close failure
	 * @param processFails whether the process close is the failing operation
	 */
	private static void assertCloseFailure(Fixture fixture, String expectedMessage, boolean processFails)
	{
		boolean failed = false;
		try
		{
			fixture.coordinator(limits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "closeFailure").isEqualTo(expectedMessage);
		}
		catch (InterruptedException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}

		requireThat(failed, "closeFailurePropagated").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
		if (!processFails)
			requireThat(fixture.events.getLast(), "lastCloseOperation").isEqualTo("sampler.close");
	}

	/**
	 * Restores interruption from await while closing the process and sampler.
	 */
	@Test
	public void awaitInterruptionRestoresFlag()
	{
		Fixture fixture = new Fixture();
		fixture.process.awaitInterruption = new InterruptedException("await interrupted");
		boolean interrupted = false;
		try
		{
			fixture.coordinator(timeoutLimits()).run(arguments(), context());
		}
		catch (InterruptedException _)
		{
			interrupted = Thread.currentThread().isInterrupted();
		}
		catch (IOException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}
		finally
		{
			Thread.interrupted();
		}

		requireThat(interrupted, "interruptRestored").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Diagnoses unchecked optional sampler failure by class and continues to normal completion.
	 *
	 * @throws IOException if process cleanup fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void samplerFailureIsDiagnosticAndNonfatal() throws IOException, InterruptedException
	{
		Fixture fixture = new Fixture();
		fixture.process.polls.add(Optional.of(new QuietBuildProcess.UnixExit(0)));
		fixture.samplerPollFailureCount = 1;

		QuietBuildCoordinator.Result result = fixture.coordinator(limits()).run(arguments(), context());

		requireThat(result, "result").isEqualTo(new QuietBuildCoordinator.Completed(new QuietBuildProcess.UnixExit(0)));
		requireThat(fixture.diagnostics, "diagnostics").isEqualTo(
			List.of("watch-quiet-build: resource sampling failed (IllegalStateException)"));
	}

	/**
	 * Restores interruption after sleep and closes both the process and sampler.
	 */
	@Test
	public void sleepInterruptionRestoresFlag()
	{
		Fixture fixture = new Fixture();
		fixture.process.polls.add(Optional.empty());
		fixture.sleepFailure = new InterruptedException("sleep interrupted");
		boolean interrupted = false;
		try
		{
			fixture.coordinator(limits()).run(arguments(), context());
		}
		catch (InterruptedException _)
		{
			interrupted = Thread.currentThread().isInterrupted();
		}
		catch (IOException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}
		finally
		{
			Thread.interrupted();
		}

		requireThat(interrupted, "interruptRestored").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Restores interruption from stop while closing both owned resources.
	 */
	@Test
	public void stopInterruptionRestoresFlag()
	{
		Fixture fixture = new Fixture();
		fixture.process.stopInterruption = new InterruptedException("stop interrupted");
		boolean interrupted = false;
		try
		{
			fixture.coordinator(timeoutLimits()).run(arguments(), context());
		}
		catch (InterruptedException _)
		{
			interrupted = Thread.currentThread().isInterrupted();
		}
		catch (IOException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}
		finally
		{
			Thread.interrupted();
		}

		requireThat(interrupted, "interruptRestored").isTrue();
		requireThat(fixture.process.closeCount, "processCloseCount").isEqualTo(1);
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
	}

	/**
	 * Output, report and resource adapters delegate to their frozen production components.
	 *
	 * @throws IOException if the fixture tree or report cannot be created or removed
	 */
	@Test
	public void adaptersDelegateToComponents() throws IOException
	{
		try (FixtureTree fixture = new FixtureTree())
		{
			Path outputFile = Files.writeString(fixture.root.resolve("build-output"), "output");
			Instant outputTime = Instant.parse("2024-01-01T00:00:02Z");
			SourceInputTimes.setTimes(fixture.scope, outputFile, FileTime.from(outputTime));
			BuildOutput buildOutput = new BuildOutput(fixture.scope, fixture.root, fixture.root.resolve("report"),
				fixture.root.resolve("samples"));
			requireThat(QuietBuildCoordinator.outputReader(buildOutput).latest(), "latestOutput").isEqualTo(outputTime);

			Path reportPath = fixture.root.resolve("process-report");
			StringWriter dumpOutput = new StringWriter();
			AtomicInteger dumpCalls = new AtomicInteger();
			QuietBuildCoordinator.ReportWriter reportWriter = QuietBuildCoordinator.reportWriter(
				QuietBuildReport.factory(), (jcmd, writer, timeout) ->
				{
					dumpCalls.incrementAndGet();
					requireThat(jcmd, "absentJcmd").isEmpty();
					requireThat(timeout, "dumpTimeout").isEqualTo(Duration.ofSeconds(600));
					writer.write("dump\n");
					dumpOutput.write("dump\n");
				});
			reportWriter.append(reportPath, Duration.ofMillis(1500), outputTime, null);
			requireThat(Files.readString(reportPath), "reportContents").isEqualTo(
				"=== quiet_seconds=2 time=2024-01-01T00:00:02Z\ndump\n");
			requireThat(dumpCalls.get(), "dumpCalls").isEqualTo(1);
			requireThat(dumpOutput.toString(), "dumpOutput").isEqualTo("dump\n");

			Path samples = fixture.root.resolve("resource-samples");
			AtomicInteger closes = new AtomicInteger();
			try (ResourceSampler adapter = QuietBuildCoordinator.resourceSampler(
				new QuietBuildResourceSampler(fixture.scope, samples, Duration.ZERO, context ->
					new QuietBuildResources.Reader()
					{
						@Override
						public QuietBuildResources.Counters read()
						{
							return linuxCounters();
						}

						@Override
						public void close()
						{
							closes.incrementAndGet();
						}
					}, context())))
			{
				adapter.poll(Instant.EPOCH);
			}
			requireThat(closes.get(), "readerCloses").isEqualTo(1);
		}
	}

	/**
	 * Closes the sampler when process startup fails before an owned build is returned.
	 */
	@Test
	public void launchFailureClosesSampler()
	{
		Fixture fixture = new Fixture();
		fixture.launchFailure = new IOException("launch failed");
		boolean failed = false;
		try
		{
			fixture.coordinator(limits()).run(arguments(), context());
		}
		catch (IOException expected)
		{
			failed = true;
			requireThat(expected.getMessage(), "launchFailure").isEqualTo("launch failed");
		}
		catch (InterruptedException unexpected)
		{
			throw new IllegalStateException(unexpected);
		}

		requireThat(failed, "launchFailurePropagated").isTrue();
		requireThat(fixture.samplerCloseCount, "samplerCloseCount").isEqualTo(1);
		requireThat(fixture.events.contains("process.close"), "uncreatedProcessClosed").isFalse();
	}

	/**
	 * Uses an isolated default scope and empty build tree for the production output adapter.
	 */
	private static final class FixtureTree implements AutoCloseable
	{
		/** Native file service borrowed by the output reader and sampler. */
		private final ToolingScope scope;
		/** Temporary file tree removed when this fixture closes. */
		private final Path root;

		/**
		 * Creates a scope and a new temporary file tree.
		 *
		 * @throws IOException if the temporary file tree cannot be created
		 */
		private FixtureTree() throws IOException
		{
			scope = new DefaultToolingScope();
			try
			{
				root = Files.createTempDirectory("quiet-build-coordinator-");
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try
				{
					scope.close();
				}
				catch (RuntimeException | Error cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Closes the scope and removes this test's file tree.
		 *
		 * @throws IOException if the tree cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			try (scope)
			{
				try (Stream<Path> paths = Files.walk(root))
				{
					for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
						Files.deleteIfExists(path);
				}
			}
		}
	}

	/**
	 * Creates deterministic policy, process, sampler and diagnostic collaborators for one test.
	 */
	private static final class Fixture
	{
		/** Process and coordinator operation order. */
		private final List<String> events = new ArrayList<>();
		/** Safe diagnostic lines written by the coordinator. */
		private final List<String> diagnostics = new ArrayList<>();
		/** Scripted build process. */
		private final ScriptedBuild process;
		/** Mutable clock advanced only by the fake sleeper. */
		private final MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
		/** CPU read failure supplied to the coordinator. */
		private IOException cpuFailure;
		/** Report append failure supplied to the coordinator. */
		private IOException reportFailure;
		/** Process launch failure supplied to the coordinator. */
		private IOException launchFailure;
		/** Sampler close failure supplied to the coordinator. */
		private IOException samplerCloseFailure;
		/** Sleeper interruption supplied to the coordinator. */
		private InterruptedException sleepFailure;
		/** Number of initial sampler polls that throw. */
		private int samplerPollFailureCount;
		/** Number of reports requested from the report adapter. */
		private int reportCount;
		/** Number of sampler closes. */
		private int samplerCloseCount;

		/**
		 * Creates a process double that records operations in this fixture.
		 */
		private Fixture()
		{
			process = new ScriptedBuild(events);
		}

		/**
		 * Creates a coordinator backed by this fixture's scripted collaborators.
		 *
		 * @param limits policy thresholds for this test
		 * @return coordinator whose process, sampler, clock and collaborators are fixture-owned
		 */
		private QuietBuildCoordinator coordinator(QuietBuildPolicy.Limits limits)
		{
			QuietBuildCoordinator.BuildAccess builds = (command, workingDirectory, environment) ->
			{
				events.add("start");
				if (launchFailure != null)
					throw launchFailure;
				return process;
			};
			QuietBuildCoordinator.OutputReader output = () ->
			{
				events.add("output");
				return Instant.EPOCH;
			};
			QuietBuildCoordinator.ReportWriter reports = (reportPath, quiet, reportTime, jcmd) ->
			{
				events.add("report");
				reportCount = reportCount + 1;
				if (reportFailure != null)
					throw reportFailure;
			};
			QuietBuildCoordinator.Sleeper sleeper = delay ->
			{
				events.add("sleep");
				if (sleepFailure != null)
					throw sleepFailure;
				clock.advance(delay);
			};
			QuietBuildCoordinator.Diagnostics diagnosticWriter = line ->
			{
				events.add("diagnostic");
				diagnostics.add(line);
			};
			return new QuietBuildCoordinator(new QuietBuildPolicy(limits), builds,
				(pid, context) ->
				{
					events.add("cpu.create");
					return () ->
					{
						events.add("cpu.read");
						if (cpuFailure != null)
							throw cpuFailure;
						return Map.of();
					};
				}, output, reports, new QuietBuildCoordinator.ResourceSampler()
			{
				@Override
				public void poll(Instant now)
				{
					events.add("sample");
					if (samplerPollFailureCount > 0)
					{
						samplerPollFailureCount = samplerPollFailureCount - 1;
						throw new IllegalStateException("sample detail must not be exposed");
					}
				}

				@Override
				public void close() throws IOException
				{
					events.add("sampler.close");
					samplerCloseCount = samplerCloseCount + 1;
					if (samplerCloseFailure != null)
						throw samplerCloseFailure;
				}
			}, clock, sleeper, diagnosticWriter);
		}

		/**
		 * Builds regular report and CPU thresholds that leave the command running after one quiet observation.
		 *
		 * @return nonnegative policy limits for one deterministic observation
		 */
		private static QuietBuildPolicy.Limits limits()
		{
			return new QuietBuildPolicy.Limits(Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(30),
				Duration.ofSeconds(5), Duration.ofSeconds(1));
		}
	}

	/**
	 * Scripted root process whose poll and cleanup operations record their order.
	 */
	private static final class ScriptedBuild implements OwnedBuild
	{
		/** Process operations shared with the coordinator fixture. */
		private final List<String> events;
		/** Poll results consumed in order. */
		private final Deque<Optional<QuietBuildProcess.Completion>> polls = new ArrayDeque<>();
		/** Stop result returned by this process. */
		private QuietBuildProcess.StopOutcome stopOutcome = new QuietBuildProcess.StopOutcome(
			QuietBuildProcess.GroupState.GONE, List.of());
		/** Process close failure supplied by a test. */
		private IOException closeFailure;
		/** Stop failure supplied by a test. */
		private IOException stopFailure;
		/** Await failure supplied by a test. */
		private IOException awaitFailure;
		/** Stop interruption supplied by a test. */
		private InterruptedException stopInterruption;
		/** Await interruption supplied by a test. */
		private InterruptedException awaitInterruption;
		/** Process close count. */
		private int closeCount;

		/**
		 * Creates a scripted build process.
		 *
		 * @param events shared operation history
		 */
		private ScriptedBuild(List<String> events)
		{
			this.events = events;
		}

		/**
		 * Returns this process's root identifier.
		 *
		 * @return fixed root process identifier
		 */
		@Override
		public long pid()
		{
			return 42;
		}

		/**
		 * Returns the next scripted process completion, or an empty result while running.
		 *
		 * @return scripted completion or empty
		 */
		@Override
		public Optional<QuietBuildProcess.Completion> poll()
		{
			events.add("poll");
			if (polls.isEmpty())
				return Optional.empty();
			return polls.removeFirst();
		}

		/**
		 * Reaps the scripted process.
		 *
		 * @return normal zero exit
		 */
		@Override
		public QuietBuildProcess.Completion await() throws IOException, InterruptedException
		{
			events.add("await");
			if (awaitInterruption != null)
				throw awaitInterruption;
			if (awaitFailure != null)
				throw awaitFailure;
			return new QuietBuildProcess.UnixExit(0);
		}

		/**
		 * Returns this process's scripted group or tree stop outcome.
		 *
		 * @return configured stop outcome
		 * @throws InterruptedException if the test scripts stop interruption
		 */
		@Override
		public QuietBuildProcess.StopOutcome stop() throws IOException, InterruptedException
		{
			events.add("stop");
			if (stopInterruption != null)
				throw stopInterruption;
			if (stopFailure != null)
				throw stopFailure;
			return stopOutcome;
		}

		/**
		 * Closes this process and reports a scripted close failure.
		 *
		 * @throws IOException if the test scripts close failure
		 */
		@Override
		public void close() throws IOException
		{
			events.add("process.close");
			closeCount = closeCount + 1;
			if (closeFailure != null)
				throw closeFailure;
		}
	}

	/**
	 * Deterministic clock whose instant advances only through fixture sleeps.
	 */
	private static final class MutableClock extends Clock
	{
		/** Current instant shared with the fixture sleeper. */
		private final AtomicReference<Instant> current;
		/** Zone used when displaying this clock. */
		private final ZoneId zone;

		/**
		 * Creates a clock at the supplied instant.
		 *
		 * @param initial initial time
		 */
		private MutableClock(Instant initial)
		{
			this(new AtomicReference<>(initial), ZoneId.of("UTC"));
		}

		/**
		 * Creates a clock that shares its instant with another zone view.
		 *
		 * @param current shared instant
		 * @param zone display zone
		 */
		private MutableClock(AtomicReference<Instant> current, ZoneId zone)
		{
			this.current = current;
			this.zone = zone;
		}

		/**
		 * Advances the clock by one sleeper interval.
		 *
		 * @param duration elapsed sleep duration
		 */
		private void advance(Duration duration)
		{
			current.updateAndGet(now -> now.plus(duration));
		}

		/**
		 * Returns the zone-free clock's instant.
		 *
		 * @return current fixture instant
		 */
		@Override
		public Instant instant()
		{
			return current.get();
		}

		/**
		 * Returns this clock when the requested zone is UTC.
		 *
		 * @param zone requested zone
		 * @return this clock
		 */
		@Override
		public Clock withZone(ZoneId zone)
		{
			return new MutableClock(current, zone);
		}

		/**
		 * Returns UTC for the fixture clock.
		 *
		 * @return UTC zone
		 */
		@Override
		public ZoneId getZone()
		{
			return zone;
		}
	}

	/**
	 * Creates the common positive report and timeout thresholds.
	 *
	 * @return policy thresholds for a running build
	 */
	private static QuietBuildPolicy.Limits limits()
	{
		return Fixture.limits();
	}

	/**
	 * Creates valid watcher arguments for a deterministic process command.
	 *
	 * @return watcher arguments with a single build command
	 */
	private static QuietBuildArguments.Values arguments()
	{
		return new QuietBuildArguments.Values(Path.of("watch"), Path.of("report"), null, Duration.ofSeconds(5),
			Duration.ofSeconds(4), Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(5),
			Duration.ofSeconds(1), List.of("build"));
	}

	/**
	 * Creates the immutable invocation context supplied to a build.
	 *
	 * @return working directory, temporary directory and empty environment
	 */
	private static QuietBuildCapture.Context context()
	{
		return new QuietBuildCapture.Context(Path.of("."), Path.of("tmp"), Map.of());
	}

	/**
	 * Creates thresholds that stop on the first CPU observation.
	 *
	 * @return quiet timeout limits
	 */
	private static QuietBuildPolicy.Limits timeoutLimits()
	{
		return new QuietBuildPolicy.Limits(Duration.ZERO, Duration.ofSeconds(4), Duration.ZERO, Duration.ofSeconds(5),
			Duration.ofSeconds(1));
	}

	/**
	 * Returns minimal Linux counters accepted by the real sampler JSON writer.
	 *
	 * @return empty process map and unit aggregate counters
	 */
	private static QuietBuildResources.LinuxCounters linuxCounters()
	{
		return new QuietBuildResources.LinuxCounters(Map.of(), java.math.BigInteger.ONE, java.math.BigInteger.ONE,
			java.math.BigInteger.ONE, java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, java.math.BigInteger.ZERO,
			java.math.BigInteger.ZERO, Map.of(), Map.of());
	}
}
