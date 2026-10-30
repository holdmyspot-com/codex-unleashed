package com.holdmyspot.codexunleashed.scheduler;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Chooses one admission or recovery control from an immutable scheduling snapshot.
 * <p>
 * The policy returns requests and does not control processes. Before another decision, the controller must record
 * the actual outcome of the previous request, reap terminated trees and obtain fresh memory readings. Concurrent
 * calls with independent snapshots share no mutable state.
 */
public final class Policy
{
	/**
	 * Prevents construction of this stateless policy.
	 */
	private Policy()
	{
	}

	/**
	 * Represents an unfinished action's lifecycle; a stopping action remains live until its tree has been reaped.
	 */
	public enum State
	{
		/** An action that has not yet started. */
		READY,
		/** An active process attempt. */
		RUNNING,
		/** An attempt waiting after an allocation denial. */
		BLOCKED,
		/** An attempt selected for termination whose tree has not yet been reaped. */
		STOPPING,
		/** An aborted action awaiting its required completion count and enough memory. */
		RETRY
	}

	/**
	 * Represents a requested controller effect, without asserting that the effect has occurred.
	 */
	public enum Control
	{
		/** Wait for a changed snapshot without controlling a job. */
		WAIT,
		/** Launch the selected action or its retry. */
		START,
		/** Permit the selected blocked attempt to continue. */
		RESUME,
		/** Terminate the selected attempt and reap its entire tree. */
		TERMINATE
	}

	/**
	 * Describes one unfinished action and its current process attempt.
	 *
	 * @param name the nonempty action identity, stable across retries
	 * @param state the unfinished lifecycle state
	 * @param position the nonnegative original submission ordinal, retained across retries
	 * @param peakBytes the estimated peak resident bytes of this action's whole process tree, or null if unmeasured
	 * @param residentBytes the current resident bytes of that tree, excluding expected future growth
	 * @param startedAt the nonnegative process-attempt sequence number, or null when inactive; not a timestamp
	 * @param blockedAt the nonnegative allocation-denial sequence number for a blocked attempt, otherwise null
	 * @param retryAfter the earliest non-aborted completion count that permits retry, positive for retry state
	 */
	public record Job(String name, State state, long position, Long peakBytes, long residentBytes,
		Long startedAt, Long blockedAt, long retryAfter)
	{
		/**
		 * Creates a validated action snapshot.
		 *
		 * @param name the nonempty action identity, stable across retries
		 * @param state the unfinished lifecycle state
		 * @param position the nonnegative original submission ordinal
		 * @param peakBytes the nonnegative estimated peak resident bytes, or null if unmeasured
		 * @param residentBytes the nonnegative current resident bytes
		 * @param startedAt the nonnegative attempt sequence for live states, otherwise null
		 * @param blockedAt the nonnegative denial sequence for blocked state, otherwise null
		 * @param retryAfter the nonnegative completion eligibility count, positive for retry state
		 * @throws NullPointerException if {@code name} or {@code state} is null
		 * @throws IllegalArgumentException if a count is negative, the name is empty, retry eligibility is zero
		 * for retry state, a live state lacks an attempt sequence, an inactive state has residency or an attempt
		 * sequence, or the denial sequence does not match blocked state
		 */
		public Job
		{
			Objects.requireNonNull(name, "name may not be null");
			Objects.requireNonNull(state, "state may not be null");
			if (name.isEmpty())
				throw new IllegalArgumentException("Action name must be nonempty");
			requireCount(position, "position");
			requireCount(residentBytes, "residentBytes");
			requireCount(retryAfter, "retryAfter");
			if (peakBytes != null)
				requireCount(peakBytes, "peakBytes");
			if (state == State.RETRY && retryAfter == 0)
				throw new IllegalArgumentException("Retry state requires a positive retryAfter completion count");

			boolean hasLiveProcess = switch (state)
			{
				case RUNNING, BLOCKED, STOPPING -> true;
				case READY, RETRY -> false;
			};
			if (hasLiveProcess)
			{
				if (startedAt == null)
					throw new IllegalArgumentException("Live state requires a startedAt attempt sequence");
				requireCount(startedAt, "startedAt");
			}
			else if (startedAt != null || residentBytes != 0)
				throw new IllegalArgumentException("Inactive state requires null startedAt and zero residentBytes");
			if (state == State.BLOCKED)
			{
				if (blockedAt == null)
					throw new IllegalArgumentException("Blocked state requires a blockedAt denial sequence");
				requireCount(blockedAt, "blockedAt");
			}
			else if (blockedAt != null)
				throw new IllegalArgumentException("Only blocked state permits a blockedAt denial sequence");
		}
	}

	/**
	 * Describes fresh native measurements, without sampling or modifying the machine.
	 *
	 * @param availableBytes the nonnegative available physical bytes, already excluding current resident use
	 * @param memoryPressure indicates whether native measurements currently show memory pressure
	 * @param swapRecovery indicates fresh swap growth or an incident retained while native pressure persists
	 * @param completions the nonnegative count of non-aborted action completions; reaping a termination does not count
	 */
	public record Observation(long availableBytes, boolean memoryPressure, boolean swapRecovery, long completions)
	{
		/**
		 * Creates a validated measurement snapshot.
		 *
		 * @param availableBytes the nonnegative available physical bytes
		 * @param memoryPressure indicates whether current native pressure is present
		 * @param swapRecovery indicates fresh swap growth or an incident retained while native pressure persists
		 * @param completions the nonnegative non-aborted completion count
		 * @throws IllegalArgumentException if {@code availableBytes} or {@code completions} is negative
		 */
		public Observation
		{
			requireCount(availableBytes, "availableBytes");
			requireCount(completions, "completions");
		}
	}

	/**
	 * Defines the physical memory reserved for other work and the maximum number of live process attempts.
	 *
	 * @param headroomBytes the nonnegative physical bytes reserved for the operating system and other work
	 * @param jobs the positive maximum concurrent live attempts
	 */
	public record Budget(long headroomBytes, int jobs)
	{
		/**
		 * Creates a validated admission budget.
		 *
		 * @param headroomBytes the nonnegative reserved physical bytes
		 * @param jobs the positive maximum concurrent live attempts
		 * @throws IllegalArgumentException if {@code headroomBytes} is negative or {@code jobs} is below one
		 */
		public Budget
		{
			requireCount(headroomBytes, "headroomBytes");
			if (jobs < 1)
				throw new IllegalArgumentException("jobs must be positive; received " + jobs);
		}
	}

	/**
	 * Describes one requested control for the controller to acknowledge before its next decision.
	 *
	 * @param control the requested effect
	 * @param jobName the selected action identity, or null for waiting
	 * @param reason the nonempty explanation of the decision
	 */
	public record Decision(Control control, String jobName, String reason)
	{
		/**
		 * Creates a control request with a matching action identity.
		 *
		 * @param control the requested effect
		 * @param jobName the nonempty selected action identity, or null for waiting
		 * @param reason the nonempty decision explanation
		 * @throws NullPointerException if {@code control} or {@code reason} is null
		 * @throws IllegalArgumentException if the explanation is empty, waiting specifies an action,
		 * or another control lacks a nonempty action identity
		 */
		public Decision
		{
			Objects.requireNonNull(control, "control may not be null");
			Objects.requireNonNull(reason, "reason may not be null");
			if (reason.isEmpty())
				throw new IllegalArgumentException("Decision reason must be nonempty");
			if (control == Control.WAIT)
			{
				if (jobName != null)
					throw new IllegalArgumentException("Waiting must not select an action");
			}
			else if (jobName == null || jobName.isEmpty())
				throw new IllegalArgumentException("An action control requires a nonempty jobName");
		}
	}

	/**
	 * Selects one control without mutating the supplied snapshot.
	 * <p>
	 * Stopping trees prevent further controls. Current pressure or an active swap-growth incident prevents admission;
	 * the swap incident also permits terminating the newest live attempt while protecting the oldest running attempt.
	 * Otherwise the oldest blocked denial precedes retries in original submission order, which precede new work.
	 * If every live attempt has an allocation wait and the oldest denial cannot resume, termination selects the newest
	 * other blocked attempt, or that denial itself when it is the only live attempt. This conservative recovery may
	 * discard work in other threads; an allocation wait does not establish that the whole process is suspended.
	 * Reaping and cleanup must precede fresh measurements and bounded retry by the owner.
	 * An ineligible or unaffordable older action is never bypassed. An unmet retry completion count prevents admission
	 * while any live attempt remains. With no live attempts, the retry proceeds through normal memory checks; its
	 * owner must enforce the per-action completion-free recovery bound. Resumption does not consume another process slot.
	 * <p>
	 * Admission reserves headroom and expected remaining growth of other running attempts. Work with an unknown peak
	 * demand requires no other live attempts and at least one byte beyond headroom. Estimates do not guarantee enough
	 * memory for the attempt to finish.
	 *
	 * @param budget the headroom and concurrency limits
	 * @param jobs the unfinished actions with unique identities, positions, attempt sequences and denial sequences;
	 * callers must not modify this list during the call
	 * @param observation the fresh native measurements and completion count
	 * @return a request naming its selected action, or a waiting request with no action
	 * @throws NullPointerException if {@code budget}, {@code jobs}, {@code observation}, or an action is null
	 * @throws IllegalArgumentException if action identities or any present ordering sequence are duplicated
	 */
	public static Decision chooseAction(Budget budget, List<Job> jobs, Observation observation)
	{
		Objects.requireNonNull(budget, "budget may not be null");
		Objects.requireNonNull(observation, "observation may not be null");
		List<Job> snapshot = List.copyOf(jobs);
		validateSnapshot(snapshot);
		if (snapshot.stream().anyMatch(job -> job.state() == State.STOPPING))
			return waitFor("Wait for the selected process tree to be reaped");

		List<Job> running = snapshot.stream().filter(job -> job.state() == State.RUNNING).toList();
		List<Job> blocked = snapshot.stream().filter(job -> job.state() == State.BLOCKED).toList();
		List<Job> live = snapshot.stream().filter(job -> switch (job.state())
		{
			case RUNNING, BLOCKED -> true;
			default -> false;
		}).toList();
		if (observation.memoryPressure() || observation.swapRecovery())
		{
			Decision pressureControl = choosePressureControl(running, live, observation);
			return chooseBlockedRecovery(running, blocked, pressureControl);
		}

		Job candidate = blocked.stream().min(Comparator.comparingLong(Job::blockedAt)).orElse(null);
		Control control = Control.RESUME;
		if (candidate == null)
		{
			candidate = snapshot.stream().filter(job -> job.state() == State.RETRY).
				min(Comparator.comparingLong(Job::position)).orElse(null);
			if (candidate != null)
			{
				boolean awaitsLiveCompletion = observation.completions() < candidate.retryAfter() && !live.isEmpty();
				if (awaitsLiveCompletion)
					return waitFor("The oldest retry awaits an actual job completion while another attempt remains live");
			}
			else
				candidate = snapshot.stream().filter(job -> job.state() == State.READY).
					min(Comparator.comparingLong(Job::position)).orElse(null);
			if (candidate == null)
				return waitFor("No unfinished work is ready");
			control = Control.START;
			if (live.size() >= budget.jobs())
				return waitFor("Build-tool concurrency limit is occupied");
		}
		Decision admission = chooseAdmission(budget, running, live, candidate, control, observation);
		return chooseBlockedRecovery(running, blocked, admission);
	}

	/**
	 * Selects targeted reclamation when every live attempt has an allocation wait and normal admission must wait.
	 * The oldest denial is retained while any blocked peer remains; among those peers the newest attempt is selected.
	 * A sole blocked attempt is selected for termination so its owner can perform bounded process retry after reaping.
	 *
	 * @param running attempts without a published allocation wait
	 * @param blocked attempts with published allocation waits
	 * @param decision the normal admission or pressure-control request
	 * @return the unchanged request unless all live attempts are blocked and it waits; then a termination request
	 */
	private static Decision chooseBlockedRecovery(List<Job> running, List<Job> blocked, Decision decision)
	{
		if (decision.control() != Control.WAIT || !running.isEmpty() || blocked.isEmpty())
			return decision;

		Job oldest = blocked.stream().min(Comparator.comparingLong(Job::blockedAt)).orElseThrow();
		Job victim = blocked.stream().filter(job -> !job.equals(oldest)).
			max(Comparator.comparingLong(Job::startedAt)).orElse(oldest);
		return new Decision(Control.TERMINATE, victim.name(),
			"Every live attempt has an allocation wait and the oldest denial cannot resume; reclaim one attempt");
	}

	/**
	 * Selects at most one pressure-driven termination while retaining the oldest running process.
	 *
	 * @param running the currently running attempts
	 * @param live the running and blocked attempts
	 * @param observation the current pressure and active swap-growth incident
	 * @return a termination request for the newest eligible attempt, or a waiting request
	 */
	private static Decision choosePressureControl(List<Job> running, List<Job> live, Observation observation)
	{
		if (observation.swapRecovery() && !running.isEmpty())
		{
			Job oldest = running.stream().min(Comparator.comparingLong(Job::startedAt)).orElseThrow();
			Job newest = live.stream().filter(job -> job.equals(oldest) == false).
				max(Comparator.comparingLong(Job::startedAt)).orElse(null);
			if (newest != null)
				return new Decision(Control.TERMINATE, newest.name(),
					"Relieve observed swap pressure; retain the oldest running job");
		}
		return waitFor("Memory pressure or active swap growth prevents admission");
	}

	/**
	 * Checks the selected action against headroom and expected running growth using exact aggregate arithmetic.
	 *
	 * @param budget the headroom and concurrency limits
	 * @param running the currently running attempts
	 * @param live the running and blocked attempts
	 * @param candidate the selected action to start or resume
	 * @param control the start or resume request
	 * @param observation the available physical memory
	 * @return the requested admission if it fits, or a waiting request
	 */
	private static Decision chooseAdmission(Budget budget, List<Job> running, List<Job> live, Job candidate,
		Control control, Observation observation)
	{
		List<Job> otherRunning = running.stream().filter(job -> job.equals(candidate) == false).toList();
		if (otherRunning.stream().anyMatch(job -> job.peakBytes() == null))
			return waitFor("An unknown-memory job is being measured exclusively");
		BigInteger remaining = BigInteger.valueOf(observation.availableBytes()).subtract(
			BigInteger.valueOf(budget.headroomBytes()));
		for (Job job : otherRunning)
			remaining = remaining.subtract(BigInteger.valueOf(Math.max(job.peakBytes() - job.residentBytes(), 0)));
		if (candidate.peakBytes() == null)
		{
			if (live.stream().anyMatch(job -> job.equals(candidate) == false))
				return waitFor("Unknown-memory work requires exclusive measurement");
			if (remaining.signum() <= 0)
				return waitFor("No memory remains beyond reserved headroom");
		}
		else
		{
			long required = Math.max(candidate.peakBytes() - candidate.residentBytes(), 0);
			if (remaining.compareTo(BigInteger.valueOf(required)) < 0)
				return waitFor("Expected growth exceeds memory remaining after reservations");
		}
		return new Decision(control, candidate.name(), "Memory estimate fits available budget");
	}

	/**
	 * Creates a waiting request with no selected action.
	 *
	 * @param reason the nonempty decision explanation
	 * @return the waiting request
	 */
	private static Decision waitFor(String reason)
	{
		return new Decision(Control.WAIT, null, reason);
	}

	/**
	 * Rejects ambiguous identities and orderings across the complete unfinished snapshot.
	 *
	 * @param jobs the validated individual action records
	 * @throws IllegalArgumentException if identities, positions, or present attempt or denial sequences repeat
	 */
	static void validateSnapshot(List<Job> jobs)
	{
		Set<String> names = new HashSet<>();
		Set<Long> positions = new HashSet<>();
		Set<Long> attempts = new HashSet<>();
		Set<Long> denials = new HashSet<>();
		for (Job job : jobs)
		{
			if (names.add(job.name()) == false)
				throw new IllegalArgumentException("Duplicate action name: " + job.name());
			if (positions.add(job.position()) == false)
				throw new IllegalArgumentException("Duplicate action submission position: " + job.position());
			if (job.startedAt() != null && attempts.add(job.startedAt()) == false)
				throw new IllegalArgumentException("Duplicate action attempt sequence: " + job.startedAt());
			if (job.blockedAt() != null && denials.add(job.blockedAt()) == false)
				throw new IllegalArgumentException("Duplicate action denial sequence: " + job.blockedAt());
		}
	}

	/**
	 * Requires a nonnegative byte count or sequence number.
	 *
	 * @param value the caller-supplied count
	 * @param name the count's diagnostic name
	 * @throws IllegalArgumentException if {@code value} is negative
	 */
	private static void requireCount(long value, String name)
	{
		if (value < 0)
			throw new IllegalArgumentException(name + " must be nonnegative; received " + value);
	}
}
