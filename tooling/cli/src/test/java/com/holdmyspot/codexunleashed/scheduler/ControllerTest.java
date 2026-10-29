package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises admission requests at the controller's measurement and process-control boundaries.
 * In-memory measurements model the validated values and IOException failures reported by native memory readers;
 * recorded controls prove requested effects, not operating-system process behavior.
 */
public final class ControllerTest
{
	/**
	 * Creates controller admission tests.
	 */
	public ControllerTest()
	{
	}

	/**
	 * Uses fresh live residency to reserve remaining growth and starts the earliest submitted ready action.
	 *
	 * @throws IOException if the supplied valid observations are rejected
	 */
	@Test
	public void startsUsingFreshResidency() throws IOException
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(100, 3),
			new Readings(new Controller.Memory(1000, 0, false), Map.of("running", 900L)), controls::add);
		List<Policy.Job> jobs = List.of(
			new Policy.Job("later", Policy.State.READY, 2, 200L, 0, null, null, 0),
			new Policy.Job("running", Policy.State.RUNNING, 0, 900L, 0, 0L, null, 0),
			new Policy.Job("earlier", Policy.State.READY, 1, 200L, 0, null, null, 0));

		Policy.Decision decision = controller.cycle(jobs, 0);

		assertEquals(decision.control(), Policy.Control.START);
		assertEquals(decision.jobName(), "earlier");
		assertEquals(controls, List.of(decision));
		assertEquals(jobs.get(1).residentBytes(), 0L, "The caller's lifecycle snapshot stays unchanged");
	}

	/**
	 * Refuses a start after missing owned-tree data and selects the original oldest ready action on a fresh read.
	 *
	 * @throws IOException if the recovered measurements fail
	 */
	@Test
	public void failedResidencyPreventsStartUntilFreshRead() throws IOException
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 0, false), Map.of());
		Controller controller = new Controller(new Policy.Budget(100, 3), readings, controls::add);
		List<Policy.Job> jobs = List.of(
			new Policy.Job("later", Policy.State.READY, 2, 200L, 0, null, null, 0),
			new Policy.Job("running", Policy.State.RUNNING, 0, 900L, 900, 0L, null, 0),
			new Policy.Job("earlier", Policy.State.READY, 1, 200L, 0, null, null, 0));

		IOException failure = expectThrows(IOException.class, () -> controller.cycle(jobs, 0));
		assertEquals(failure.getMessage(), "Owned residency unavailable for running");
		assertTrue(controls.isEmpty());
		assertEquals(readings.residentRequests, List.of("running"));

		readings.residency.put("running", 300L);
		Policy.Decision decision = controller.cycle(jobs, 0);
		assertEquals(decision.control(), Policy.Control.START);
		assertEquals(decision.jobName(), "earlier");
		assertEquals(controls, List.of(decision));
		assertEquals(readings.residentRequests, List.of("running", "running"));
		assertEquals(jobs.get(1).residentBytes(), 900L);
	}

	/**
	 * Refuses resumption after a failed blocked-tree sample and preserves denial order on the next fresh cycle.
	 *
	 * @throws IOException if recovered readings fail
	 */
	@Test
	public void failedResidencyPreventsResumeUntilFreshRead() throws IOException
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 0, false),
			Map.of("running", 100L, "newer-denial", 100L));
		Controller controller = new Controller(new Policy.Budget(100, 4), readings, controls::add);
		List<Policy.Job> jobs = List.of(
			new Policy.Job("newer-denial", Policy.State.BLOCKED, 1, 200L, 100, 2L, 1L, 0),
			new Policy.Job("ready", Policy.State.READY, 3, 100L, 0, null, null, 0),
			new Policy.Job("oldest-denial", Policy.State.BLOCKED, 2, 200L, 100, 1L, 0L, 0),
			new Policy.Job("running", Policy.State.RUNNING, 0, 100L, 100, 0L, null, 0));

		expectThrows(IOException.class, () -> controller.cycle(jobs, 0));
		assertTrue(controls.isEmpty());

		readings.residency.put("oldest-denial", 100L);
		Policy.Decision decision = controller.cycle(jobs, 0);
		assertEquals(decision.control(), Policy.Control.RESUME);
		assertEquals(decision.jobName(), "oldest-denial");
		assertEquals(controls, List.of(decision));
		assertEquals(readings.residentRequests,
			List.of("newer-denial", "oldest-denial", "newer-denial", "oldest-denial", "running"));
	}

	/**
	 * Preserves the last complete swap baseline when a later cycle fails its owned-tree reading.
	 *
	 * @throws IOException if a complete sample is rejected
	 */
	@Test
	public void failedTreeSampleDoesNotAdvanceSwapHistory() throws IOException
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 10, false),
			Map.of("oldest", 100L, "newest", 100L));
		Controller controller = new Controller(new Policy.Budget(100, 2), readings, controls::add);
		List<Policy.Job> jobs = List.of(
			new Policy.Job("newest", Policy.State.RUNNING, 1, 100L, 100, 1L, null, 0),
			new Policy.Job("oldest", Policy.State.RUNNING, 0, 100L, 100, 0L, null, 0));
		assertEquals(controller.cycle(jobs, 0).control(), Policy.Control.WAIT);

		readings.host = new Controller.Memory(1000, 20, false);
		readings.residency.remove("oldest");
		expectThrows(IOException.class, () -> controller.cycle(jobs, 0));
		assertTrue(controls.isEmpty());

		readings.host = new Controller.Memory(1000, 15, false);
		readings.residency.put("oldest", 100L);
		Policy.Decision decision = controller.cycle(jobs, 0);
		assertEquals(decision.control(), Policy.Control.TERMINATE);
		assertEquals(decision.jobName(), "newest");
		assertEquals(controls, List.of(decision));
	}

	/**
	 * Propagates host-read failure without sampling trees or issuing controls, then remeasures after recovery.
	 *
	 * @throws IOException if recovered measurements fail
	 */
	@Test
	public void failedHostReadPreventsControls() throws IOException
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 0, false), Map.of());
		IOException unavailable = new IOException("Native host memory unavailable");
		readings.hostFailure = unavailable;
		Controller controller = new Controller(new Policy.Budget(100, 1), readings, controls::add);
		List<Policy.Job> jobs = List.of(new Policy.Job("ready", Policy.State.READY, 0, 100L, 0,
			null, null, 0));

		assertSame(expectThrows(IOException.class, () -> controller.cycle(jobs, 0)), unavailable);
		assertTrue(controls.isEmpty());
		assertTrue(readings.residentRequests.isEmpty());

		readings.hostFailure = null;
		assertEquals(controller.cycle(jobs, 0).control(), Policy.Control.START);
		assertEquals(readings.hostRequests, 2);
		assertEquals(controls.size(), 1);
	}

	/**
	 * Rejects invalid lifecycle snapshots before invoking either external boundary.
	 */
	@Test
	public void invalidSnapshotHasNoExternalEffects()
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 0, false), Map.of());
		Controller controller = new Controller(new Policy.Budget(100, 2), readings, controls::add);
		Policy.Job ready = new Policy.Job("ready", Policy.State.READY, 0, 100L, 0, null, null, 0);

		expectThrows(IllegalArgumentException.class, () -> controller.cycle(List.of(ready, ready), 0));
		expectThrows(IllegalArgumentException.class, () -> controller.cycle(List.of(ready), -1));
		assertEquals(readings.hostRequests, 0);
		assertTrue(readings.residentRequests.isEmpty());
		assertTrue(controls.isEmpty());
	}

	/**
	 * Rejects a negative external residency reading rather than passing zero to admission.
	 */
	@Test
	public void invalidResidencyPreventsControls()
	{
		List<Policy.Decision> controls = new ArrayList<>();
		Readings readings = new Readings(new Controller.Memory(1000, 0, false), Map.of("running", -1L));
		Controller controller = new Controller(new Policy.Budget(100, 2), readings, controls::add);
		List<Policy.Job> jobs = List.of(
			new Policy.Job("running", Policy.State.RUNNING, 0, 100L, 100, 0L, null, 0),
			new Policy.Job("ready", Policy.State.READY, 1, 100L, 0, null, null, 0));

		IOException failure = expectThrows(IOException.class, () -> controller.cycle(jobs, 0));
		assertEquals(failure.getMessage(), "Negative resident bytes for action running: -1");
		assertTrue(controls.isEmpty());
	}

	/**
	 * Propagates a control failure and makes only one request within that cycle.
	 */
	@Test
	public void doesNotRetryFailedControlWithinCycle()
	{
		List<Policy.Decision> controls = new ArrayList<>();
		IOException failedEffect = new IOException("Process launch failed after preparation");
		Controller controller = new Controller(new Policy.Budget(100, 1),
			new Readings(new Controller.Memory(1000, 0, false), Map.of()), decision ->
			{
				controls.add(decision);
				throw failedEffect;
			});
		List<Policy.Job> jobs = List.of(new Policy.Job("ready", Policy.State.READY, 0, 100L, 0,
			null, null, 0));

		assertSame(expectThrows(IOException.class, () -> controller.cycle(jobs, 0)), failedEffect);
		assertEquals(controls.size(), 1);
		assertEquals(controls.getFirst().control(), Policy.Control.START);
		assertEquals(controls.getFirst().jobName(), "ready");
	}

	/**
	 * Supplies controlled observations and unavailable-reader failures at the native measurement boundary.
	 */
	private static final class Readings implements Controller.Measurements
	{
		private Controller.Memory host;
		private final Map<String, Long> residency;
		private IOException hostFailure = null;
		private int hostRequests = 0;
		/** Action identities whose owned-tree readings the controller requested, in request order. */
		private final List<String> residentRequests = new ArrayList<>();

		/**
		 * Copies the scripted observations so each test owns its changes.
		 *
		 * @param host validated host observations
		 * @param residency resident bytes keyed by stable action identity
		 */
		private Readings(Controller.Memory host, Map<String, Long> residency)
		{
			this.host = host;
			this.residency = new HashMap<>(residency);
		}

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
			residentRequests.add(job.name());
			Long bytes = residency.get(job.name());
			if (bytes == null)
				throw new IOException("Owned residency unavailable for " + job.name());
			return bytes;
		}
	}
}
