package com.holdmyspot.codexunleashed.scheduler;

import java.util.List;
import org.testng.annotations.Test;
import com.holdmyspot.codexunleashed.scheduler.Policy.Budget;
import com.holdmyspot.codexunleashed.scheduler.Policy.Control;
import com.holdmyspot.codexunleashed.scheduler.Policy.Decision;
import com.holdmyspot.codexunleashed.scheduler.Policy.Job;
import com.holdmyspot.codexunleashed.scheduler.Policy.Observation;
import com.holdmyspot.codexunleashed.scheduler.Policy.State;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.expectThrows;

/**
 * Exercises admission and retry decisions using explicit snapshots without claiming native process effects.
 */
public final class PolicyTest
{
	/**
	 * Creates policy behavior tests.
	 */
	public PolicyTest()
	{
	}

	/**
	 * Reserves operating-system headroom and expected growth before admitting another job.
	 */
	@Test
	public void reservesRemainingGrowth()
	{
		List<Job> jobs = List.of(new Job("active", State.RUNNING, 0, 700L, 300, 0L, null, 0),
			new Job("next", State.READY, 1, 300L, 0, null, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs, new Observation(799, false, false, 0)),
			Control.WAIT, null);
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs, new Observation(800, false, false, 0)),
			Control.START, "next");
	}

	/**
	 * Reclaims a blocked peer when the oldest denial cannot fit, and resumes that denial once its estimate fits.
	 */
	@Test
	public void resumesBlockedJobsInDenialOrder()
	{
		List<Job> jobs = List.of(new Job("new", State.READY, 0, 10L, 0, null, null, 0),
			new Job("older", State.BLOCKED, 2, 500L, 100, 2L, 4L, 0),
			new Job("younger", State.BLOCKED, 1, 200L, 100, 1L, 5L, 0));
		Decision recovery = Policy.chooseAction(new Budget(100, 3), jobs, new Observation(499, false, false, 0));
		requireThat(recovery.control(), "recoveryControl").isEqualTo(Control.TERMINATE, "expected");
		requireThat(recovery.jobName(), "reclaimedPeer").isEqualTo("younger", "expected");
		assertDecision(Policy.chooseAction(new Budget(100, 3), jobs, new Observation(500, false, false, 0)),
			Control.RESUME, "older");
	}

	/**
	 * Selects the newest process attempt under swap pressure while protecting the oldest running job.
	 */
	@Test
	public void terminatesNewestAttemptAndProtectsOldestRunningJob()
	{
		List<Job> jobs = List.of(new Job("oldest", State.RUNNING, 0, 400L, 300, 10L, null, 0),
			new Job("newest", State.RUNNING, 1, 400L, 300, 12L, null, 0));
		Observation observation = new Observation(10, true, true, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs, observation), Control.TERMINATE, "newest");
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs.subList(0, 1), observation), Control.WAIT, null);
		List<Job> retryOrder = List.of(new Job("recent-retry", State.RUNNING, 0, 10L, 10, 7L, null, 0),
			new Job("older-attempt", State.RUNNING, 1, 10L, 10, 5L, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 2), retryOrder, observation),
			Control.TERMINATE, "recent-retry");
		List<Job> blockedAndRunning = List.of(new Job("blocked", State.BLOCKED, 0, 10L, 10, 0L, 0L, 0),
			new Job("peer", State.RUNNING, 1, 10L, 10, 1L, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 2), blockedAndRunning, observation),
			Control.TERMINATE, "blocked");
	}

	/**
	 * Requires exclusive admission and memory above reserved headroom for work whose peak memory demand is unknown.
	 */
	@Test
	public void measuresUnknownDemandAlone()
	{
		Job unknown = new Job("unknown", State.READY, 0, null, 0, null, null, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 4), List.of(unknown), new Observation(101, false, false, 0)),
			Control.START, "unknown");
		assertDecision(Policy.chooseAction(new Budget(100, 4), List.of(unknown), new Observation(100, false, false, 0)),
			Control.WAIT, null);
		Job active = new Job("active", State.RUNNING, 1, 10L, 10, 1L, null, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 4), List.of(unknown, active), new Observation(1000, false, false, 0)),
			Control.WAIT, null);
		Job measuring = new Job("measuring", State.RUNNING, 0, null, 20, 0L, null, 0);
		Job next = new Job("next", State.READY, 1, 1L, 0, null, null, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 4), List.of(measuring, next), new Observation(1000, false, false, 0)),
			Control.WAIT, null);
	}

	/**
	 * Retains retry submission order and permits the oldest retry without a completion when no attempt remains live.
	 */
	@Test
	public void preservesRetryOrderAndCompletionGate()
	{
		List<Job> jobs = List.of(new Job("new", State.READY, 3, 10L, 0, null, null, 0),
			new Job("later-retry", State.RETRY, 2, 10L, 0, null, null, 1),
			new Job("first-retry", State.RETRY, 1, 10L, 0, null, null, 2));
		Decision completionFree = Policy.chooseAction(new Budget(100, 3), jobs, new Observation(1000, false, false, 1));
		requireThat(completionFree.control(), "completionFreeControl").isEqualTo(Control.START, "expected");
		requireThat(completionFree.jobName(), "oldestRetry").isEqualTo("first-retry", "expected");
		assertDecision(Policy.chooseAction(new Budget(100, 3), jobs, new Observation(1000, false, false, 2)),
			Control.START, "first-retry");
		Job costly = new Job("retry", State.RETRY, 0, 101L, 0, null, null, 1);
		assertDecision(Policy.chooseAction(new Budget(100, 2), List.of(costly), new Observation(200, false, false, 1)),
			Control.WAIT, null);
	}

	/**
	 * Ends pressure-driven kills without requiring recorded swap occupancy to decrease.
	 */
	@Test
	public void admitsAfterPressureRelief()
	{
		SwapRecovery history = new SwapRecovery();
		history.observe(0, false);
		history.observe(1024, true);
		List<Job> jobs = List.of(new Job("oldest", State.RUNNING, 0, 100L, 100, 0L, null, 0),
			new Job("next", State.READY, 1, 100L, 0, null, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs,
			new Observation(200, false, history.observe(1024, false), 0)),
			Control.START, "next");
	}

	/**
	 * Responds to fresh swap growth even when the native low-memory signal remains clear.
	 */
	@Test
	public void terminatesNewestOnSwapGrowthWithoutNativePressure()
	{
		SwapRecovery history = new SwapRecovery();
		history.observe(0, false);
		Observation growth = new Observation(2000, false, history.observe(1024, false), 0);
		List<Job> jobs = List.of(new Job("oldest", State.RUNNING, 0, 100L, 100, 0L, null, 0),
			new Job("newest", State.RUNNING, 1, 100L, 100, 1L, null, 0),
			new Job("next", State.READY, 2, 100L, 0, null, null, 0));
		Budget budget = new Budget(100, 4);
		assertDecision(Policy.chooseAction(budget, jobs, growth), Control.TERMINATE, "newest");
		assertDecision(Policy.chooseAction(budget, List.of(jobs.getFirst(), jobs.getLast()), growth),
			Control.WAIT, null);
		Observation stable = new Observation(2000, false, history.observe(1024, false), 0);
		assertDecision(Policy.chooseAction(budget, jobs, stable), Control.START, "next");
	}

	/**
	 * Waits for a selected process tree to be reaped before any pressure reassessment or admission.
	 */
	@Test
	public void waitsForTerminationReaping()
	{
		List<Job> jobs = List.of(new Job("oldest", State.RUNNING, 0, 100L, 100, 0L, null, 0),
			new Job("selected", State.STOPPING, 1, 100L, 100, 1L, null, 0),
			new Job("next", State.READY, 2, 10L, 0, null, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 4), jobs, new Observation(1000, false, false, 0)),
			Control.WAIT, null);
		assertDecision(Policy.chooseAction(new Budget(100, 4), jobs, new Observation(0, true, true, 0)),
			Control.WAIT, null);
	}

	/**
	 * Applies the supplied concurrency limit to new attempts while permitting an existing blocked attempt to resume.
	 */
	@Test
	public void limitsNewProcessesWithoutBlockingResumption()
	{
		Job active = new Job("active", State.RUNNING, 0, 10L, 10, 0L, null, 0);
		Job ready = new Job("next", State.READY, 1, 10L, 0, null, null, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 1), List.of(active, ready), new Observation(1000, false, false, 0)),
			Control.WAIT, null);
		Job blocked = new Job("blocked", State.BLOCKED, 0, 10L, 10, 0L, 0L, 0);
		assertDecision(Policy.chooseAction(new Budget(100, 1), List.of(blocked), new Observation(1000, false, false, 0)),
			Control.RESUME, "blocked");
	}

	/**
	 * Never kills for ordinary memory pressure without a monitor-confirmed swap-growth incident.
	 */
	@Test
	public void requiresSwapRecoveryForTermination()
	{
		List<Job> jobs = List.of(new Job("a", State.RUNNING, 0, 10L, 10, 0L, null, 0),
			new Job("b", State.RUNNING, 1, 10L, 10, 1L, null, 0));
		assertDecision(Policy.chooseAction(new Budget(100, 2), jobs, new Observation(0, true, false, 0)),
			Control.WAIT, null);
	}

	/**
	 * Keeps byte comparisons exact even when aggregate growth would overflow a signed long.
	 */
	@Test
	public void refusesOverflowingReservations()
	{
		List<Job> jobs = List.of(new Job("a", State.RUNNING, 0, Long.MAX_VALUE, 0, 0L, null, 0),
			new Job("b", State.RUNNING, 1, Long.MAX_VALUE, 0, 1L, null, 0),
			new Job("next", State.READY, 2, 1L, 0, null, null, 0));
		assertDecision(Policy.chooseAction(new Budget(0, 3), jobs, new Observation(Long.MAX_VALUE, false, false, 0)),
			Control.WAIT, null);
		Job exact = new Job("exact", State.READY, 0, Long.MAX_VALUE, 0, null, null, 0);
		assertDecision(Policy.chooseAction(new Budget(0, 1), List.of(exact), new Observation(Long.MAX_VALUE, false, false, 0)),
			Control.START, "exact");
	}

	/**
	 * Rejects invalid measurements and inconsistent lifecycle states when their records are constructed.
	 */
	@Test
	public void rejectsInvalidRecords()
	{
		expectThrows(IllegalArgumentException.class, () -> new Budget(-1, 2));
		expectThrows(IllegalArgumentException.class, () -> new Budget(0, 0));
		expectThrows(IllegalArgumentException.class, () -> new Observation(-1, false, false, 0));
		expectThrows(IllegalArgumentException.class, () -> new Observation(0, false, false, -1));
		expectThrows(IllegalArgumentException.class, () -> new Job("a", State.READY, 0, -1L, 0, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> new Job("a", State.RUNNING, 0, 10L, 0, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> new Job("a", State.READY, 0, 10L, 1, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> new Job("a", State.RETRY, 0, 10L, 0, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> new Job("a", State.RUNNING, 0, 10L, 0, 0L, 0L, 0));
		expectThrows(NullPointerException.class, () -> new Job("a", null, 0, 10L, 0, null, null, 0));
	}

	/**
	 * Rejects ambiguous submission, attempt or denial ordering before selecting a control.
	 */
	@Test
	public void rejectsAmbiguousOrdering()
	{
		Budget budget = new Budget(0, 3);
		Observation observation = new Observation(1000, false, false, 0);
		List<Job> positions = List.of(new Job("a", State.READY, 0, 10L, 0, null, null, 0),
			new Job("b", State.READY, 0, 10L, 0, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> Policy.chooseAction(budget, positions, observation));
		List<Job> attempts = List.of(new Job("a", State.RUNNING, 0, 10L, 0, 0L, null, 0),
			new Job("b", State.RUNNING, 1, 10L, 0, 0L, null, 0));
		expectThrows(IllegalArgumentException.class, () -> Policy.chooseAction(budget, attempts, observation));
		List<Job> denials = List.of(new Job("a", State.BLOCKED, 0, 10L, 0, 0L, 0L, 0),
			new Job("b", State.BLOCKED, 1, 10L, 0, 1L, 0L, 0));
		expectThrows(IllegalArgumentException.class, () -> Policy.chooseAction(budget, denials, observation));
		List<Job> names = List.of(new Job("a", State.READY, 0, 10L, 0, null, null, 0),
			new Job("a", State.READY, 1, 10L, 0, null, null, 0));
		expectThrows(IllegalArgumentException.class, () -> Policy.chooseAction(budget, names, observation));
	}

	/**
	 * Requires a returned control to identify exactly its selected job and supply an explanation.
	 *
	 * @param decision the actual policy request
	 * @param control the expected control
	 * @param jobName the expected selected job, or null for waiting
	 */
	private static void assertDecision(Decision decision, Control control, String jobName)
	{
		assertEquals(decision.control(), control);
		assertEquals(decision.jobName(), jobName);
		assertFalse(decision.reason().isEmpty());
	}
}
