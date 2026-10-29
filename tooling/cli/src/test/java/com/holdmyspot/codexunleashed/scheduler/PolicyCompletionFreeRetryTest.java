package com.holdmyspot.codexunleashed.scheduler;

import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the no-live-attempt retry exception while preserving admission, submission order and live-peer barriers.
 * These immutable snapshots describe policy inputs, without asserting native process state.
 */
public final class PolicyCompletionFreeRetryTest
{
	/**
	 * Creates completion-free retry policy checks.
	 */
	public PolicyCompletionFreeRetryTest()
	{
	}

	/**
	 * Selects the oldest retry before ready work when no live action can supply the unmet completion count.
	 */
	@Test
	public void retriesWithoutLivePeerInOriginalOrder()
	{
		Policy.Job oldest = retry("oldest", 0, null, 2);
		Policy.Job later = retry("later", 1, 1L, 1);
		Policy.Job ready = new Policy.Job("ready", Policy.State.READY, 2, 0L, 0, null, null, 0);

		Policy.Decision decision = choose(List.of(ready, later, oldest), 1, false, false, 0);

		requireThat(decision.control(), "control").isEqualTo(Policy.Control.START, "expected");
		requireThat(decision.jobName(), "identity").isEqualTo("oldest", "expected");
		requireThat(oldest.retryAfter(), "unchangedBarrier").isEqualTo(2L, "expected");
	}

	/**
	 * Retains an unmet completion barrier while another action is running, then admits the eligible normal retry.
	 */
	@Test
	public void runningPeerRetainsCompletionBarrier()
	{
		Policy.Job retry = retry("retry", 0, 10L, 2);
		Policy.Job peer = new Policy.Job("peer", Policy.State.RUNNING, 1, 10L, 10, 9L, null, 0);

		requireThat(choose(List.of(retry, peer), 100, false, false, 1).control(), "unmetBarrier")
			.isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(choose(List.of(retry, peer), 100, false, false, 2).control(), "satisfiedBarrier")
			.isEqualTo(Policy.Control.START, "expected");
	}

	/**
	 * Preserves oldest-denial priority and the stopping-tree reaping barrier ahead of no-peer retry recovery.
	 */
	@Test
	public void blockedAndStoppingPeersRetainPriority()
	{
		Policy.Job retry = retry("retry", 0, 10L, 2);
		Policy.Job blocked = new Policy.Job("blocked", Policy.State.BLOCKED, 1, 10L, 0, 9L, 3L, 0);
		Policy.Decision decision = choose(List.of(retry, blocked), 100, false, false, 0);
		requireThat(decision.control(), "blockedControl").isEqualTo(Policy.Control.RESUME, "expected");
		requireThat(decision.jobName(), "blockedIdentity").isEqualTo("blocked", "expected");
		Policy.Job stopping = new Policy.Job("stopping", Policy.State.STOPPING, 1, 10L, 0, 9L, null, 0);
		requireThat(choose(List.of(retry, stopping), 100, false, false, 0).control(), "reapingBarrier")
			.isEqualTo(Policy.Control.WAIT, "expected");
	}

	/**
	 * Applies pressure, swap recovery, headroom and estimated demand before completion-free admission.
	 */
	@Test
	public void exceptionPreservesEveryMemoryAdmissionGate()
	{
		List<Policy.Job> unknown = List.of(retry("unknown", 0, null, 1));
		requireThat(choose(unknown, 100, true, false, 0).control(), "pressure")
			.isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(choose(unknown, 100, false, true, 0).control(), "swapRecovery")
			.isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(choose(unknown, 0, false, false, 0).control(), "noHeadroom")
			.isEqualTo(Policy.Control.WAIT, "expected");
		List<Policy.Job> known = List.of(retry("known", 0, 20L, 1));
		requireThat(choose(known, 19, false, false, 0).control(), "unaffordableEstimate")
			.isEqualTo(Policy.Control.WAIT, "expected");
		requireThat(choose(known, 20, false, false, 0).control(), "affordableEstimate")
			.isEqualTo(Policy.Control.START, "expected");
		Policy.Decision reserved = Policy.chooseAction(new Policy.Budget(20, 1), unknown,
			new Policy.Observation(20, false, false, 0));
		requireThat(reserved.control(), "reservedHeadroom").isEqualTo(Policy.Control.WAIT, "expected");
	}

	/**
	 * Does not bypass the oldest unaffordable retry for a cheaper later action.
	 */
	@Test
	public void cannotBypassUnaffordableOldestRetry()
	{
		List<Policy.Job> jobs = List.of(retry("oldest", 0, 200L, 1), retry("later", 1, 1L, 1),
			new Policy.Job("ready", Policy.State.READY, 2, 1L, 0, null, null, 0));

		requireThat(choose(jobs, 100, false, false, 0).control(), "oldestDemand")
			.isEqualTo(Policy.Control.WAIT, "expected");
	}

	/**
	 * Creates an inactive retry while retaining its original position and exact completion threshold.
	 * @param name stable action identity
	 * @param position original submission ordinal
	 * @param peakBytes estimated peak bytes, or null if unknown
	 * @param retryAfter required natural completion count
	 * @return the retry snapshot
	 */
	private static Policy.Job retry(String name, long position, Long peakBytes, long retryAfter)
	{
		return new Policy.Job(name, Policy.State.RETRY, position, peakBytes, 0, null, null, retryAfter);
	}

	/**
	 * Selects a policy request with zero reserved bytes and room for three live attempts.
	 * @param jobs immutable action snapshots
	 * @param availableBytes available physical bytes
	 * @param pressure indicates observed memory pressure
	 * @param swapRecovery indicates an active swap-growth incident
	 * @param completions accepted natural completion count
	 * @return the selected request
	 */
	private static Policy.Decision choose(List<Policy.Job> jobs, long availableBytes, boolean pressure,
		boolean swapRecovery, long completions)
	{
		return Policy.chooseAction(new Policy.Budget(0, 3), jobs,
			new Policy.Observation(availableBytes, pressure, swapRecovery, completions));
	}
}
