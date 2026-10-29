package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks controller admission after earlier in-memory residency observations; no native measurement is claimed.
 */
public final class ControllerMemoryHistoryTest
{
	/**
	 * Creates retained-demand checks.
	 */
	public ControllerMemoryHistoryTest()
	{
	}

	/**
	 * Reserves an observed higher demand after the running action's current residency falls.
	 * @throws IOException if the controlled readings fail
	 */
	@Test
	public void reservesHigherObservationAfterResidencyFalls() throws IOException
	{
		Readings readings = new Readings();
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(0, 2), readings, controls::add);
		Policy.Job running = new Policy.Job("compile", Policy.State.RUNNING, 0, 10L, 0, 0L, null, 0);
		readings.residency.put("compile", 90L);
		controller.cycle(List.of(running), 0);
		readings.residency.put("compile", 10L);
		readings.host = new Controller.Memory(100, 0, false);
		Policy.Job ready = new Policy.Job("link", Policy.State.READY, 1, 30L, 0, null, null, 0);

		Policy.Decision decision = controller.cycle(List.of(running, ready), 0);

		requireThat(decision.control(), "control").isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(controls.isEmpty(), "noControlRequest").isTrue();
	}

	/**
	 * Keeps peaks and lower bounds monotonic while preserving unknown peaks and exclusive admission.
	 * @throws IOException if valid readings fail
	 */
	@Test
	public void keepsKnownAndUnknownDemandDistinct() throws IOException
	{
		Readings readings = new Readings();
		Controller controller = new Controller(new Policy.Budget(0, 3), readings, decision -> { });
		readings.residency.put("known", 90L);
		readings.residency.put("unknown", 70L);
		controller.cycle(List.of(live("known", 0, 10L, Policy.State.BLOCKED),
			live("unknown", 1, null, Policy.State.STOPPING)), 0);
		readings.residency.put("known", 2L);
		readings.residency.put("unknown", 1L);
		controller.cycle(List.of(live("known", 0, 5L, Policy.State.RUNNING),
			live("unknown", 1, null, Policy.State.RUNNING)), 0);

		requireThat(controller.memoryDemands().get(new Controller.Action("known", 0)), "known")
			.isEqualTo(new Controller.MemoryDemand(90L, 90), "expected");
		requireThat(controller.memoryDemands().get(new Controller.Action("unknown", 1)), "unknown")
			.isEqualTo(new Controller.MemoryDemand(null, 70), "expected");
		Policy.Decision decision = controller.cycle(List.of(live("unknown", 1, null, Policy.State.RUNNING),
			new Policy.Job("ready", Policy.State.READY, 2, 1L, 0, null, null, 0)), 0);
		requireThat(decision.control(), "exclusiveMeasurement").isEqualTo(Policy.Control.WAIT, "expected");
		controller.cycle(List.of(new Policy.Job("known", Policy.State.RETRY, 0, 120L, 0, null, null, 1)), 0);
		requireThat(controller.memoryDemands().get(new Controller.Action("known", 0)), "raisedEstimate")
			.isEqualTo(new Controller.MemoryDemand(120L, 90), "expected");
		controller.cycle(List.of(new Policy.Job("known", Policy.State.READY, 0, 1L, 0, null, null, 0)), 0);
		requireThat(controller.memoryDemands().get(new Controller.Action("known", 0)), "retainedEstimate")
			.isEqualTo(new Controller.MemoryDemand(120L, 90), "expected");
	}

	/**
	 * Publishes no candidate observations after host, later-tree or negative-tree failures or invalid snapshots.
	 * @throws IOException if the initial complete sample fails
	 */
	@Test
	public void discardsIncompleteObservationBatches() throws IOException
	{
		Readings readings = new Readings();
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(0, 2), readings, controls::add);
		Policy.Job first = live("first", 0, 10L, Policy.State.RUNNING);
		Policy.Job second = live("second", 1, 10L, Policy.State.RUNNING);
		readings.residency.put("first", 10L);
		readings.residency.put("second", 10L);
		controller.cycle(List.of(first, second), 0);
		Map<Controller.Action, Controller.MemoryDemand> before = controller.memoryDemands();
		readings.residency.put("first", 900L);
		readings.residency.remove("second");

		requireException(IOException.class, () -> controller.cycle(List.of(first, second), 0));
		requireThat(controller.memoryDemands(), "afterPartialTraversal").isEqualTo(before, "before");
		readings.residency.put("second", -1L);
		requireException(IOException.class, () -> controller.cycle(List.of(first, second), 0));
		requireThat(controller.memoryDemands(), "afterNegativeReading").isEqualTo(before, "before");
		readings.hostFailure = new IOException("Controlled host failure");
		requireException(IOException.class, () -> controller.cycle(List.of(first, second), 0));
		requireThat(controller.memoryDemands(), "afterHostFailure").isEqualTo(before, "before");
		int requestsBefore = readings.hostRequests;
		requireException(IllegalArgumentException.class, () -> controller.cycle(List.of(first, first), 0));
		requireException(IllegalArgumentException.class, () -> controller.cycle(List.of(first), -1));
		requireThat(readings.hostRequests, "hostRequests").isEqualTo(requestsBefore, "before");
		requireThat(controller.memoryDemands(), "afterInvalidSnapshot").isEqualTo(before, "before");
		requireThat(controls.isEmpty(), "noControls").isTrue();
	}

	/**
	 * Retains a complete batch before issuing exactly one control that reports its original failure.
	 */
	@Test
	public void retainsCompleteHistoryAfterControlFailure()
	{
		Readings readings = new Readings();
		readings.residency.put("live", 70L);
		IOException failure = new IOException("Controlled start failure");
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(0, 2), readings, decision ->
		{
			controls.add(decision);
			throw failure;
		});
		Policy.Job ready = new Policy.Job("ready", Policy.State.READY, 1, 20L, 0, null, null, 0);

		IOException actual = requireException(IOException.class,
			() -> controller.cycle(List.of(live("live", 0, 10L, Policy.State.RUNNING), ready), 0));

		requireThat(actual == failure, "originalFailure").isTrue();
		requireThat(controls.size(), "controls").isEqualTo(1, "expected");
		requireThat(controls.getFirst().control(), "effect").isEqualTo(Policy.Control.START, "expected");
		requireThat(controller.memoryDemands(), "completeHistory").isEqualTo(Map.of(
			new Controller.Action("live", 0), new Controller.MemoryDemand(70L, 70),
			new Controller.Action("ready", 1), new Controller.MemoryDemand(20L, 0)), "expected");
	}

	/**
	 * Keeps immutable snapshots, completed actions and separate original positions while sharing retries.
	 * @throws IOException if valid readings fail
	 */
	@Test
	public void snapshotsAreStableAndActionsRetainOriginalIdentity() throws IOException
	{
		Readings readings = new Readings();
		Controller controller = new Controller(new Policy.Budget(0, 2), readings, decision -> { });
		Map<Controller.Action, Controller.MemoryDemand> empty = controller.memoryDemands();
		requireThat(empty.isEmpty(), "initialHistory").isTrue();
		readings.residency.put("compile", 90L);
		controller.cycle(List.of(live("compile", 0, 10L, Policy.State.RUNNING)), 0);
		Map<Controller.Action, Controller.MemoryDemand> first = controller.memoryDemands();
		requireException(UnsupportedOperationException.class, first::clear);
		readings.residency.put("compile", 100L);
		Policy.Job retry = new Policy.Job("compile", Policy.State.RUNNING, 0, null, 0, 9L, null, 0);
		controller.cycle(List.of(retry), 1);
		requireThat(first.get(new Controller.Action("compile", 0)), "oldSnapshot")
			.isEqualTo(new Controller.MemoryDemand(90L, 90), "expected");
		requireThat(controller.memoryDemands().get(new Controller.Action("compile", 0)), "retryHistory")
			.isEqualTo(new Controller.MemoryDemand(100L, 100), "expected");
		controller.cycle(List.of(new Policy.Job("compile", Policy.State.READY, 2, 3L, 0, null, null, 0)), 1);
		requireThat(controller.memoryDemands().get(new Controller.Action("compile", 2)), "differentPosition")
			.isEqualTo(new Controller.MemoryDemand(3L, 0), "expected");
		controller.cycle(List.of(), 2);
		requireThat(controller.memoryDemands().size(), "completedHistory").isEqualTo(2, "expected");
		requireThat(empty.isEmpty(), "initialSnapshotUnchanged").isTrue();
	}

	/**
	 * Retains exact boundary counts without overflow and rejects invalid identity and demand values.
	 * @throws IOException if valid extreme readings fail
	 */
	@Test
	public void validatesAndRetainsExactByteBoundaries() throws IOException
	{
		Readings readings = new Readings();
		Controller controller = new Controller(new Policy.Budget(0, 1), readings, decision -> { });
		readings.residency.put("compile", 0L);
		controller.cycle(List.of(live("compile", 0, null, Policy.State.RUNNING)), 0);
		requireThat(controller.memoryDemands().get(new Controller.Action("compile", 0)), "observedZero")
			.isEqualTo(new Controller.MemoryDemand(null, 0), "expected");
		readings.residency.put("compile", Long.MAX_VALUE);
		controller.cycle(List.of(live("compile", 0, 0L, Policy.State.RUNNING)), 0);
		requireThat(controller.memoryDemands().get(new Controller.Action("compile", 0)), "maximum")
			.isEqualTo(new Controller.MemoryDemand(Long.MAX_VALUE, Long.MAX_VALUE), "expected");
		requireException(NullPointerException.class, () -> new Controller.Action(null, 0));
		requireException(IllegalArgumentException.class, () -> new Controller.Action("", 0));
		requireException(IllegalArgumentException.class, () -> new Controller.Action("compile", -1));
		requireException(IllegalArgumentException.class, () -> new Controller.MemoryDemand(null, -1));
		requireException(IllegalArgumentException.class, () -> new Controller.MemoryDemand(-1L, 0));
		requireException(IllegalArgumentException.class, () -> new Controller.MemoryDemand(1L, 2));
	}

	/**
	 * Creates a live snapshot with distinct position-based attempt and denial ordinals.
	 * @param name stable action identity
	 * @param position original submission ordinal
	 * @param peak supplied peak estimate, or null if unknown
	 * @param state a live lifecycle state
	 * @return the live snapshot
	 */
	private static Policy.Job live(String name, long position, Long peak, Policy.State state)
	{
		Long denial = state == Policy.State.BLOCKED ? position : null;
		return new Policy.Job(name, state, position, peak, 0, position, denial, 0);
	}

	/**
	 * Rejects a missing initial host observation before recording even ready-action estimates.
	 */
	@Test
	public void missingInitialHostLeavesHistoryEmpty()
	{
		Readings readings = new Readings();
		readings.host = null;
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(0, 1), readings, controls::add);
		Policy.Job ready = new Policy.Job("compile", Policy.State.READY, 0, 90L, 0, null, null, 0);

		requireException(IOException.class, () -> controller.cycle(List.of(ready), 0));

		requireThat(controller.memoryDemands().isEmpty(), "emptyHistory").isTrue();
		requireThat(controls.isEmpty(), "noControls").isTrue();
	}

	/**
	 * Supplies validated host values and controlled whole-tree byte readings through the production boundary.
	 */
	private static final class Readings implements Controller.Measurements
	{
		/**
		 * Controlled available physical bytes, occupied swap bytes and pressure returned by the host reader.
		 */
		private Controller.Memory host = new Controller.Memory(1000, 0, false);
		/**
		 * Controlled host failure, or null for a successful observation.
		 */
		private IOException hostFailure = null;
		/**
		 * Number of requests reaching the controlled host reader.
		 */
		private int hostRequests = 0;
		/**
		 * Controlled resident observations keyed by stable action name.
		 */
		private final Map<String, Long> residency = new HashMap<>();

		@Override
		public Controller.Memory readHost() throws IOException
		{
			++hostRequests;
			if (hostFailure != null)
				throw hostFailure;
			return host;
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			Long value = residency.get(job.name());
			if (value == null)
				throw new IOException("Missing controlled residency for " + job.name());
			return value;
		}
	}
}
