package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the production interval commit using complete and rejected native-source observations.
 */
public final class LinuxMeasurementsTest
{
	/**
	 * Creates independent sample-transaction tests.
	 */
	public LinuxMeasurementsTest()
	{
	}

	/**
	 * Keeps activity pending across a failed residency read, then commits only the complete replacement batch.
	 * @throws IOException if a valid observation is rejected
	 */
	@Test
	public void failedResidencyDoesNotConsumePressure() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		source.counter = 110;
		requireThat(measurements.readHost().memoryPressure(), "firstIntervalActivity").isTrue();
		source.failResidency = true;
		requireException(IOException.class, () -> measurements.readResidentBytes(running(0)));
		requireException(IOException.class, measurements::acceptReadings);
		source.failResidency = false;
		requireThat(measurements.readHost().memoryPressure(), "retainedIntervalActivity").isTrue();
		measurements.readResidentBytes(running(0));
		measurements.acceptReadings();
		requireThat(measurements.readHost().memoryPressure(), "acceptedIntervalActivity").isFalse();
	}

	/**
	 * Rejects incomplete, stale-attempt and changed-registry batches without advancing the baseline.
	 * @throws IOException if a valid observation is rejected
	 */
	@Test
	public void onlyExactCompleteAttemptBatchMayCommit() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		source.counter = 110;
		measurements.readHost();
		requireException(IOException.class, measurements::acceptReadings);
		measurements.readHost();
		requireException(IOException.class, () -> measurements.readResidentBytes(running(1)));
		measurements.readHost();
		measurements.readResidentBytes(running(0));
		source.generation++;
		requireException(IOException.class, measurements::acceptReadings);
		requireThat(measurements.readHost().memoryPressure(), "activityAfterRegistryRejection").isTrue();
	}

	/**
	 * Rejects a counter rollback or boot change instead of representing it as zero activity.
	 * @throws IOException if a valid observation is rejected
	 */
	@Test
	public void resetCountersRequireNewSession() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		source.counter = 99;
		requireException(IOException.class, measurements::readHost);
		source.counter = 100;
		source.bootId = "another-boot";
		requireException(IOException.class, measurements::readHost);
	}

	/**
	 * A failed replacement host read discards the older pending batch and preserves interval activity.
	 * @throws IOException if a valid recovery observation fails
	 */
	@Test
	public void failedHostReplacementCannotAcceptOlderBatch() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		source.counter = 110;
		measurements.readHost();
		measurements.readResidentBytes(running(0));
		source.failHost = true;
		requireException(IOException.class, measurements::readHost);
		requireException(IOException.class, measurements::acceptReadings);
		source.failHost = false;
		requireThat(measurements.readHost().memoryPressure(), "activityRetainedAfterHostFailure").isTrue();
	}

	/**
	 * Distinguishes an observed zero budget from an unavailable read and detects PSI independently of swap activity.
	 * @throws IOException if a valid zero or interval reading fails
	 */
	@Test
	public void measuredZeroAndPsiActivityRemainDistinct() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		source.available = 0;
		source.stalls++;
		Controller.Memory memory = measurements.readHost();
		requireThat(memory.availableBytes(), "measuredEffectiveZero").isEqualTo(0L, "expected");
		requireThat(memory.memoryPressure(), "psiActivityWithoutSwap").isTrue();
		measurements.readResidentBytes(running(0));
		measurements.acceptReadings();
		requireThat(measurements.readHost().memoryPressure(), "acceptedPsiInterval").isFalse();
	}

	/**
	 * Controller.cycle commits the actual adapter's complete interval before a later process-control failure.
	 * @throws IOException if priming or checking the accepted interval fails
	 */
	@Test
	public void controllerAcceptsBeforeFailedControl() throws IOException
	{
		Readings source = new Readings();
		LinuxMeasurements measurements = LinuxMeasurements.open(source);
		Controller controller = new Controller(new Policy.Budget(100, 1), measurements, decision ->
		{
			throw new IOException("Controlled process-control failure after accepted readings");
		});
		source.counter = 110;
		source.available = 0;
		Policy.Job blocked = new Policy.Job("compile", Policy.State.BLOCKED, 9, 100L, 0, 0L, 0L, 0);
		requireException(IOException.class, () -> controller.cycle(List.of(blocked), 0));
		requireThat(measurements.readHost().memoryPressure(), "adapterBaselineCommittedBeforeControl").isFalse();
	}

	/**
	 * Creates the live action presented by Controller.cycle.
	 * @param sequence actual attempt ordinal
	 * @return live job with an independent submission position
	 */
	private static Policy.Job running(long sequence)
	{
		return new Policy.Job("compile", Policy.State.RUNNING, 9, 100L, 0, sequence, null, 0);
	}

	/**
	 * Supplies the native source's documented successful-counter, failed-read and generation-change outcomes.
	 * Assertions concern the adapter's interval decisions, rather than this fixture's returned values.
	 */
	private static final class Readings implements LinuxMeasurements.Source
	{
		/**
		 * Cumulative source activity, increased between host reads.
		 */
		private long counter = 100;
		/**
		 * Registry revision rejected by verification when it changes during a batch.
		 */
		private long generation;
		/**
		 * Native boot identity retained through a source session.
		 */
		private String bootId = "boot";
		/**
		 * Indicates whether the next native residency observation fails.
		 */
		private boolean failResidency;
		/**
		 * Indicates whether the replacement host observation fails before returning counters.
		 */
		private boolean failHost;
		/**
		 * Complete effective bytes, including a valid zero budget.
		 */
		private long available = 400;
		/**
		 * Cumulative PSI microseconds, independent of the swap-out page count.
		 */
		private long stalls = 100;

		/**
		 * Creates one independent controlled native source.
		 */
		private Readings()
		{
		}

		@Override
		public LinuxMeasurements.Observation read() throws IOException
		{
			if (failHost)
				throw new IOException("Controlled required host read failure");
			return new LinuxMeasurements.Observation(new LinuxMemory.Sample(1000, 500, 0, 0, counter, stalls),
				available, bootId, generation, Set.of(new LinuxMeasurements.Attempt("compile", 0)));
		}

		@Override
		public long readResidentBytes(LinuxMeasurements.Attempt attempt) throws IOException
		{
			if (failResidency)
				throw new IOException("Controlled required residency read failure");
			return 20;
		}

		@Override
		public void verify(long generation) throws IOException
		{
			if (this.generation != generation)
				throw new IOException("Registered workload generation changed during the sample");
		}
	}
}
