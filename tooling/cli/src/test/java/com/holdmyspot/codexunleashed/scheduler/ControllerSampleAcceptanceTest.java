package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies when a complete measurement batch may advance a provider's cumulative-counter baseline. */
public final class ControllerSampleAcceptanceTest
{
	/** Creates complete-sample acceptance checks. */
	public ControllerSampleAcceptanceTest()
	{
	}

	/**
	 * Retains interval activity across an incomplete batch, then advances it after every required tree was sampled.
	 * @throws IOException if a valid sample batch fails
	 */
	@Test
	public void acceptsBaselineOnlyAfterEveryTreeRead() throws IOException
	{
		var readings = new Readings();
		var controller = new Controller(new Policy.Budget(100, 2), readings,
			decision -> readings.events.add("control"));
		var jobs = List.of(running("A", 0), running("B", 1));
		readings.current = 110;
		readings.failTree = "B";

		requireException(IOException.class, () -> controller.cycle(jobs, 0));
		requireThat(readings.events, "incompleteReadOrder").isEqualTo(List.of("host", "A", "B"), "expected");
		requireThat(controller.memoryDemands().isEmpty(), "noIncompleteDemandHistory").isTrue();
		readings.events.clear();
		readings.failTree = "";
		controller.cycle(jobs, 0);

		requireThat(readings.events, "completeReadOrder").isEqualTo(List.of("host", "A", "B", "accept"), "expected");
		controller.cycle(jobs, 0);
		requireThat(readings.pressures, "activitySinceAcceptedBaseline").isEqualTo(List.of(true, true, false), "expected");
	}

	/**
	 * Accepts valid measurements before a control effect that fails, so its failure does not replay interval activity.
	 */
	@Test
	public void acceptsReadingsBeforeApplyingControl()
	{
		var readings = new Readings();
		var failure = new IOException("Controlled start failure");
		var controller = new Controller(new Policy.Budget(100, 1), readings, decision ->
		{
			readings.events.add("control");
			throw failure;
		});
		var ready = new Policy.Job("A", Policy.State.READY, 0, 100L, 0, null, null, 0);

		IOException observed = requireException(IOException.class, () -> controller.cycle(List.of(ready), 0));

		requireThat(observed == failure, "originalControlFailure").isTrue();
		requireThat(readings.events, "acceptedBeforeEffect").isEqualTo(List.of("host", "accept", "control"), "expected");
		requireThat(controller.memoryDemands().size(), "acceptedDemandHistory").isEqualTo(1, "expected");
	}

	/** A control fault cannot impersonate permission to retry memory acquisition. */
	@Test
	public void controlRejectionRemainsTerminal()
	{
		var readings = new Readings();
		var rejected = new ObservationRejected(ObservationRejected.Operation.RESIDENT,
			ObservationRejected.Reason.MEMBERSHIP_CHANGED,
			List.of(new ObservationRejected.Attempt("A", 0)), new IOException("control fault"));
		var controller = new Controller(new Policy.Budget(0, 1), readings, decision -> { throw rejected; });
		var ready = new Policy.Job("A", Policy.State.READY, 0, 100L, 0, null, null, 0);
		var failure = requireException(IOException.class, () -> controller.cycle(List.of(ready), 0));
		requireThat(failure instanceof ObservationRejected, "noRetryOfControl").isFalse();
		requireThat(failure.getCause() == rejected, "retainedControlFault").isTrue();
	}

	/**
	 * Issues no control and publishes no controller history if the provider cannot accept the completed batch.
	 * @throws IOException if a valid baseline or recovery sample fails
	 */
	@Test
	public void rejectsProviderAcceptanceFailure() throws IOException
	{
		var readings = new Readings();
		var controller = new Controller(new Policy.Budget(100, 1), readings,
			decision -> readings.events.add("control"));
		controller.cycle(List.of(), 0);
		readings.events.clear();
		readings.swapUsed = 100;
		readings.acceptanceFailure = new IOException("Controlled acceptance failure");
		var ready = new Policy.Job("A", Policy.State.READY, 0, 100L, 0, null, null, 0);

		IOException observed = requireException(IOException.class, () -> controller.cycle(List.of(ready), 0));

		requireThat(observed == readings.acceptanceFailure, "originalAcceptanceFailure").isTrue();
		requireThat(readings.events, "noEffectAfterRejectedBatch").isEqualTo(List.of("host", "accept"), "expected");
		requireThat(controller.memoryDemands().isEmpty(), "unpublishedDemandHistory").isTrue();
		readings.acceptanceFailure = null;
		readings.events.clear();
		requireThat(controller.cycle(List.of(ready), 0).control(), "growthSinceAcceptedSwapBaseline")
			.isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(readings.events, "noAdmissionDuringRecoveredSwapGrowth")
			.isEqualTo(List.of("host", "accept"), "expected");
	}

	/**
	 * Rejected host, later residency and acceptance preserve every previously accepted baseline.
	 * @throws IOException if a complete modeled batch fails
	 */
	@Test
	public void trustedRejectionPreservesCompleteBatchState() throws IOException
	{
		for (var operation : ObservationRejected.Operation.values())
		{
			var readings = new Readings();
			var controller = new Controller(new Policy.Budget(0, 3), readings,
				decision -> readings.events.add("control"));
			controller.cycle(List.of(), 0);
			var prior = controller.memoryDemands();
			readings.events.clear();
			readings.current = 110;
			readings.swapUsed = 100;
			readings.rejectedOperation = operation;
			var jobs = List.of(running("A", 0), running("B", 1));
			requireException(ObservationRejected.class, () -> controller.cycle(jobs, 0));
			requireThat(readings.baseline, "acceptedProviderBaseline").isEqualTo(100L);
			requireThat(controller.memoryDemands(), "acceptedControllerHistory").isEqualTo(prior);
			requireThat(readings.events.contains("control"), "noControlForRejectedBatch").isFalse();
			readings.rejectedOperation = null;
			readings.current = 100;
			readings.swapUsed = 50;
			readings.events.clear();
			var ready = new Policy.Job("C", Policy.State.READY, 2, 0L, 0, null, null, 0);
			requireThat(controller.cycle(List.of(ready), 0).control(), "swapGrowthFromLastAcceptedBatch")
				.isEqualTo(Policy.Control.WAIT);
			requireThat(readings.baseline, "freshCompleteAcceptance").isEqualTo(100L);
		}
	}

	/**
	 * Creates one valid live action whose measured bytes replace its supplied residency.
	 * @param name stable action identity
	 * @param position original submission and attempt ordinal
	 * @return a running action with a known estimate
	 */
	private static Policy.Job running(String name, long position)
	{
		return new Policy.Job(name, Policy.State.RUNNING, position, 100L, 0, position, null, 0);
	}

	/** Models a provider which stages cumulative counters until the controller accepts every required reading. */
	private static final class Readings implements Controller.Measurements
	{
		/** Cumulative counter retained by the last accepted complete batch. */
		private long baseline = 100;
		/** Current counter provided by the modeled native source. */
		private long current = 100;
		/** Occupied swap bytes provided by the modeled native source. */
		private long swapUsed = 0;
		/** Ordered provider and control boundary calls. */
		private final List<String> events = new ArrayList<>();
		/** Pressure values returned on host reads, including rejected batches. */
		private final List<Boolean> pressures = new ArrayList<>();
		/** Action whose tree read fails, or empty when every reading succeeds. */
		private String failTree = "";
		/** Rejection before committing the provider baseline, or null for successful acceptance. */
		private IOException acceptanceFailure = null;
		/** Controlled trusted-churn boundary, or null for complete observations. */
		private ObservationRejected.Operation rejectedOperation;

		/**
		 * Models established native churn without supplying successful counters.
		 * @return controlled rejection at the selected measurement boundary
		 */
		private ObservationRejected rejection()
		{
			return new ObservationRejected(rejectedOperation, ObservationRejected.Reason.MEMBERSHIP_CHANGED,
				List.of(new ObservationRejected.Attempt("B", 1)), new IOException("trusted modeled churn"));
		}

		/** Creates independent staged counter observations for one test. */
		private Readings()
		{
		}

		@Override
		public Controller.Memory readHost() throws IOException
		{
			events.add("host");
			if (rejectedOperation == ObservationRejected.Operation.HOST) throw rejection();
			boolean pressure = current > baseline;
			pressures.add(pressure);
			return new Controller.Memory(1000, swapUsed, pressure);
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			events.add(job.name());
			if (job.name().equals("B") && rejectedOperation == ObservationRejected.Operation.RESIDENT) throw rejection();
			if (job.name().equals(failTree))
				throw new IOException("Controlled incomplete tree reading");
			return 100;
		}

		/**
		 * Accepts staged counters only after complete sampling.
		 * @throws IOException if the fixture rejects acceptance without advancing its baseline
		 */
		@Override
		public void acceptReadings() throws IOException
		{
			events.add("accept");
			if (rejectedOperation == ObservationRejected.Operation.ACCEPT) throw rejection();
			if (acceptanceFailure != null)
				throw acceptanceFailure;
			baseline = current;
		}
	}
}
