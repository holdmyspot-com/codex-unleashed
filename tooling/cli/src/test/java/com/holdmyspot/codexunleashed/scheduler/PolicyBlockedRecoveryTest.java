package com.holdmyspot.codexunleashed.scheduler;

import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises requests to reclaim allocation-waiting attempts using explicit policy snapshots.
 * These requests do not establish native termination or memory reclamation.
 */
public final class PolicyBlockedRecoveryTest
{
	/**
	 * Preserves the oldest denial and selects its newest eligible peer when admission is one byte short.
	 */
	@Test
	public void reclaimsPeerWhenOldestDenialIsOneByteShort()
	{
		List<Policy.Job> jobs = List.of(
			new Policy.Job("new", Policy.State.READY, 0, 10L, 0, null, null, 0),
			new Policy.Job("older", Policy.State.BLOCKED, 2, 500L, 100, 2L, 4L, 0),
			new Policy.Job("younger", Policy.State.BLOCKED, 1, 200L, 100, 1L, 5L, 0));

		Policy.Decision decision = Policy.chooseAction(new Policy.Budget(100, 3), jobs,
			new Policy.Observation(499, false, false, 0));

		requireThat(decision.control(), "control").isEqualTo(Policy.Control.TERMINATE, "expected");
		requireThat(decision.jobName(), "selectedPeer").isEqualTo("younger", "expected");
	}

	/**
	 * Uses normal admission before reclamation when the oldest denial fits exactly.
	 */
	@Test
	public void resumesOldestDenialWhenMemoryFitsExactly()
	{
		List<Policy.Job> jobs = List.of(
			blocked("oldest", 0, 0, 0, 500L, 100),
			blocked("newer", 1, 1, 1, 200L, 100));

		Policy.Decision decision = Policy.chooseAction(new Policy.Budget(100, 2), jobs,
			new Policy.Observation(500, false, false, 0));

		requireDecision(decision, Policy.Control.RESUME, "oldest");
	}

	/**
	 * Selects the newest eligible peer by attempt order while protecting the oldest allocation denial.
	 */
	@Test
	public void preservesDenialOrderAcrossDifferentAttemptAndSubmissionOrders()
	{
		List<Policy.Job> jobs = List.of(
			blocked("oldest-denial", 2, 9, 0, 500L, 100),
			blocked("older-peer", 0, 2, 2, 200L, 100),
			blocked("newest-peer", 1, 5, 1, 200L, 100));

		Policy.Decision decision = Policy.chooseAction(new Policy.Budget(100, 3), jobs,
			new Policy.Observation(499, false, false, 0));

		requireDecision(decision, Policy.Control.TERMINATE, "newest-peer");
	}

	/**
	 * Reclaims a peer when every live attempt reports an allocation wait under either pressure gate.
	 */
	@Test
	public void reclaimsPeerUnderPressureOrSwapRecovery()
	{
		List<Policy.Job> jobs = List.of(
			blocked("oldest", 0, 0, 0, 100L, 100),
			blocked("newest", 1, 1, 1, 100L, 100));
		Policy.Budget budget = new Policy.Budget(100, 2);

		requireDecision(Policy.chooseAction(budget, jobs, new Policy.Observation(1000, true, false, 0)),
			Policy.Control.TERMINATE, "newest");
		requireDecision(Policy.chooseAction(budget, jobs, new Policy.Observation(1000, false, true, 0)),
			Policy.Control.TERMINATE, "newest");
	}

	/**
	 * Retains normal waiting when a peer without an allocation wait may complete.
	 */
	@Test
	public void doesNotReclaimForBlockedAdmissionWhilePeerIsRunning()
	{
		List<Policy.Job> jobs = List.of(
			blocked("oldest", 0, 0, 0, 500L, 100),
			new Policy.Job("running", Policy.State.RUNNING, 1, 100L, 100, 1L, null, 0));
		Policy.Budget budget = new Policy.Budget(100, 2);

		requireDecision(Policy.chooseAction(budget, jobs, new Policy.Observation(499, false, false, 0)),
			Policy.Control.WAIT, null);
		requireDecision(Policy.chooseAction(budget, jobs, new Policy.Observation(1000, true, false, 0)),
			Policy.Control.WAIT, null);
		requireDecision(Policy.chooseAction(budget, jobs, new Policy.Observation(1000, false, true, 0)),
			Policy.Control.TERMINATE, "oldest");
	}

	/**
	 * Selects a sole blocked attempt for process retry rather than leaving its retained memory in a waiting cycle.
	 */
	@Test
	public void reclaimsSoleUnknownAttemptWithoutInferringItsPeak()
	{
		Policy.Job unknown = blocked("unknown", 0, 0, 0, null, 123);
		Policy.Decision decision = Policy.chooseAction(new Policy.Budget(100, 1), List.of(unknown),
			new Policy.Observation(100, false, false, 0));

		requireDecision(decision, Policy.Control.TERMINATE, "unknown");
		requireThat(unknown.peakBytes(), "unmeasuredPeak").isNull();
	}

	/**
	 * Keeps a selected termination as the only outstanding effect until its complete tree is reaped.
	 */
	@Test
	public void stoppingTreePreventsFurtherReclamation()
	{
		List<Policy.Job> jobs = List.of(
			blocked("oldest", 0, 0, 0, 500L, 100),
			new Policy.Job("stopping", Policy.State.STOPPING, 1, 100L, 100, 1L, null, 0));

		requireDecision(Policy.chooseAction(new Policy.Budget(100, 2), jobs,
			new Policy.Observation(0, true, true, 0)), Policy.Control.WAIT, null);
	}

	/**
	 * Leaves an inactive retry waiting for fresh external capacity, without declaring the work impossible.
	 */
	@Test
	public void doesNotReclaimInactiveWorkForInsufficientCapacity()
	{
		Policy.Job retry = new Policy.Job("retry", Policy.State.RETRY, 0, 500L, 0, null, null, 1);

		requireDecision(Policy.chooseAction(new Policy.Budget(100, 1), List.of(retry),
			new Policy.Observation(599, false, false, 0)), Policy.Control.WAIT, null);
	}

	/**
	 * Creates an immutable allocation-waiting policy input.
	 * @param name stable action identity
	 * @param position original submission ordinal
	 * @param startedAt process-attempt ordinal
	 * @param blockedAt allocation-denial ordinal
	 * @param peakBytes known peak estimate, or null for unknown demand
	 * @param residentBytes current whole-tree residency
	 * @return the blocked action snapshot
	 */
	private static Policy.Job blocked(String name, long position, long startedAt, long blockedAt,
		Long peakBytes, long residentBytes)
	{
		return new Policy.Job(name, Policy.State.BLOCKED, position, peakBytes, residentBytes, startedAt, blockedAt, 0);
	}

	/**
	 * Requires the control and selected stable action returned by production policy.
	 * @param decision actual production request
	 * @param control expected requested effect
	 * @param name expected stable action identity, or null for waiting
	 */
	private static void requireDecision(Policy.Decision decision, Policy.Control control, String name)
	{
		requireThat(decision.control(), "control").isEqualTo(control, "expected");
		requireThat(decision.jobName(), "selectedAction").isEqualTo(name, "expected");
	}
}
