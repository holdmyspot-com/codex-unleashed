package com.holdmyspot.codexunleashed.scheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides whether an attempt whose start failed is retried instead of failing the build, and bounds those retries
 * per action. Instances are confined to the scheduler's queue thread.
 */
final class LeaderDispatchRetries
{
	/**
	 * Number of consecutive attempts of one action whose leader dispatch fails before the failure becomes fatal. A
	 * transient cause such as memory pressure is not expected to persist this long, while a defect in the leader
	 * script would.
	 */
	static final int LIMIT = 3;

	/** Consecutive leader dispatch failures of each action since its last successful start. */
	private final Map<String, Integer> failures = new HashMap<>();

	/**
	 * Creates a policy that has recorded no failures.
	 */
	LeaderDispatchRetries()
	{
	}

	/**
	 * Records a failed start of an action and decides whether to retry it.
	 * @param name the action name
	 * @param cause the start failure
	 * @return {@code true} if the leader could not dispatch the launch and fewer than {@link #LIMIT} consecutive
	 *     attempts of the action failed that way; {@code false} if the failure must fail the build
	 * @throws NullPointerException if any argument is null
	 */
	boolean retry(String name, Throwable cause)
	{
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(cause, "cause");
		if (!dispatchFailure(cause))
			return false;
		int consecutive = failures.merge(name, 1, Integer::sum);
		return consecutive < LIMIT;
	}

	/**
	 * Indicates whether a failure or one of its causes is a leader dispatch failure.
	 * @param failure the failure to inspect
	 * @return {@code true} if the cause chain contains a {@link LeaderDispatchFailure}
	 */
	private static boolean dispatchFailure(Throwable failure)
	{
		for (Throwable cause = failure; cause != null; cause = cause.getCause())
			if (cause instanceof LeaderDispatchFailure)
				return true;
		return false;
	}

	/**
	 * Records a successful start of an action, ending its run of consecutive dispatch failures.
	 * @param name the action name
	 * @throws NullPointerException if {@code name} is null
	 */
	void started(String name)
	{
		failures.remove(Objects.requireNonNull(name, "name"));
	}
}
