package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates fresh memory readings, one scheduling decision and its process-control request.
 * <p>
 * Use an instance from one thread. The caller owns the queue and job states. After a control request, the caller
 * must record its actual outcome and reap terminated process trees before supplying the next snapshot. This also
 * applies when a control fails after partly changing process state. A failed memory reading issues no control,
 * preserves swap history and leaves the supplied jobs unchanged. The next cycle reads memory again.
 */
public final class Controller
{
	private final Policy.Budget budget;
	private final Measurements measurements;
	private final Controls controls;
	/** Swap-growth history from cycles with successful host readings and readings for every live job. */
	private final SwapRecovery swapRecovery = new SwapRecovery();

	/**
	 * Represents host memory needed for admission and swap-growth recovery.
	 *
	 * @param availableBytes nonnegative physical bytes available without swapping, excluding current resident use
	 * @param swapUsedBytes nonnegative occupied swap bytes
	 * @param memoryPressure indicates whether current native measurements show memory pressure
	 */
	public record Memory(long availableBytes, long swapUsedBytes, boolean memoryPressure)
	{
		/**
		 * Creates validated host measurements.
		 *
		 * @param availableBytes nonnegative available physical bytes
		 * @param swapUsedBytes nonnegative occupied swap bytes
		 * @param memoryPressure indicates whether current native measurements show memory pressure
		 * @throws IllegalArgumentException if a byte count is negative
		 */
		public Memory
		{
			if (availableBytes < 0 || swapUsedBytes < 0)
				throw new IllegalArgumentException("Available and occupied swap bytes must be nonnegative");
		}
	}

	/**
	 * Reads current host memory and the resident bytes of each job's owned process tree.
	 * Missing, inconsistent or unavailable readings must throw rather than return a substitute value.
	 */
	public interface Measurements
	{
		/**
		 * Reads fresh host memory and pressure.
		 *
		 * @return validated host measurements
		 * @throws IOException if required host measurements cannot be obtained
		 */
		Memory readHost() throws IOException;

		/**
		 * Reads the current residency of one owned live attempt, including descendants.
		 *
		 * @param job the action identity and attempt to sample
		 * @return nonnegative resident bytes; zero is valid only after a successful observation
		 * @throws IOException if complete, consistent owned-tree residency cannot be obtained
		 */
		long readResidentBytes(Policy.Job job) throws IOException;
	}

	/**
	 * Applies one start, resume or termination request to the selected job's owned process attempt.
	 * The owner must record actual outcomes, including partial effects, and reap terminated trees before another cycle.
	 */
	@FunctionalInterface
	public interface Controls
	{
		/**
		 * Applies a start, resume or terminate request; waiting is never submitted.
		 *
		 * @param decision the selected action and requested effect
		 * @throws IOException if the effect fails, possibly after changing process state
		 */
		void apply(Policy.Decision decision) throws IOException;
	}

	/**
	 * Creates a controller for one build's budget, measurement source and process owner.
	 *
	 * @param budget the reserved physical headroom and concurrency bound
	 * @param measurements the source of fresh host and owned-tree observations
	 * @param controls the owner that applies requested process effects
	 * @throws NullPointerException if a parameter is null
	 */
	public Controller(Policy.Budget budget, Measurements measurements, Controls controls)
	{
		this.budget = Objects.requireNonNull(budget, "budget may not be null");
		this.measurements = Objects.requireNonNull(measurements, "measurements may not be null");
		this.controls = Objects.requireNonNull(controls, "controls may not be null");
	}

	/**
	 * Reads host memory and every live job's resident bytes, then selects and applies at most one control.
	 * <p>
	 * Fresh resident-byte readings replace the supplied values for this decision. A failed reading leaves swap history
	 * unchanged and issues no control. Successful readings advance swap history even if applying the selected control
	 * then fails. The caller must establish the actual process state after a failed control before another cycle;
	 * repeating the same snapshot could repeat a partially completed effect. The supplied list and job records stay
	 * unchanged on success and failure.
	 *
	 * @param jobs unfinished actions with unique identities and ordering sequences; callers must not modify the list
	 * during the call
	 * @param completions nonnegative non-aborted action completions; reaping a termination does not count
	 * @return the issued request, or a waiting decision when no effect is eligible
	 * @throws NullPointerException if {@code jobs} or an action is null
	 * @throws IllegalArgumentException if ordering sequences or identities repeat, or {@code completions} is negative
	 * @throws IOException if required sampling or applying the selected effect fails
	 */
	public Policy.Decision cycle(List<Policy.Job> jobs, long completions) throws IOException
	{
		List<Policy.Job> snapshot = List.copyOf(jobs);
		Policy.validateSnapshot(snapshot);
		if (completions < 0)
			throw new IllegalArgumentException("completions must be nonnegative; received " + completions);

		Memory host = measurements.readHost();
		if (host == null)
			throw new IOException("Host memory observation is unavailable");
		List<Policy.Job> sampled = new ArrayList<>(snapshot.size());
		for (Policy.Job job : snapshot)
		{
			boolean hasLiveProcess = switch (job.state())
			{
				case RUNNING, BLOCKED, STOPPING -> true;
				case READY, RETRY -> false;
			};
			if (hasLiveProcess)
			{
				long residentBytes = measurements.readResidentBytes(job);
				if (residentBytes < 0)
					throw new IOException("Negative resident bytes for action " + job.name() + ": " + residentBytes);
				sampled.add(new Policy.Job(job.name(), job.state(), job.position(), job.peakBytes(), residentBytes,
					job.startedAt(), job.blockedAt(), job.retryAfter()));
			}
			else
				sampled.add(job);
		}

		boolean recovering = swapRecovery.observe(host.swapUsedBytes(), host.memoryPressure());
		Policy.Observation observation = new Policy.Observation(host.availableBytes(), host.memoryPressure(),
			recovering, completions);
		Policy.Decision decision = Policy.chooseAction(budget, sampled, observation);
		if (decision.control() != Policy.Control.WAIT)
			controls.apply(decision);
		return decision;
	}
}
