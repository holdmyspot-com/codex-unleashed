package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns unfinished actions, their process attempts and accepted results for one build.
 * <p>
 * One caller thread must perform every operation, including allocation notifications and collaborator calls.
 * Collaborators must not call back into this queue during an operation. Asynchronous and reentrant use is unsupported.
 * Budget, measurements and factory are borrowed; every returned Attempt is exclusively owned until cleanup succeeds.
 * A failed start, resume or termination may have partial effects: only whole-job completion followed by successful
 * cleanup permits further controls. A failed cleanup permanently prevents scheduling and requires explicit close.
 * <p>
 * Selection belongs to Controller and Policy. Native attempt adapters and actual allocation-wait notifications are
 * separate responsibilities; this queue does not infer allocation waits from memory pressure or abortion from status.
 *
 * @param <R> the complete native accepted result payload, retained unchanged
 */
final class JobQueue<R> implements AutoCloseable
{
	/** Selects controls from fresh measurements and immutable owned-state projections. */
	private final Controller controller;
	/** Borrowed resource-free factory; ownership of each returned attempt transfers to this queue. */
	private final AttemptFactory<R> attempts;
	/** Stable action identities retained in original submission order, including finished names. */
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	/** Accepted outcomes in observation order, retained independently of accounting and owner cleanup. */
	private final List<Completion<R>> completed = new ArrayList<>();
	/** Next original submission ordinal; its successor is checked before reservation. */
	private long nextPosition = 0;
	/** Next queue-wide attempt ordinal; its successor is checked before creation. */
	private long nextAttempt = 0;
	/** Next global denial ordinal, separate from per-attempt waits. */
	private long nextDenial = 0;
	/** Non-aborted accepted outcomes accounted once, initially zero. */
	private long completions = 0;
	/** Terminal state set before any explicit cleanup. */
	private boolean closing = false;
	/** Latched factory contract violation, or null before any violation. */
	private IllegalStateException fatalFailure = null;
	/** Latched exact-arithmetic accounting failure, or null while accounting remains possible. */
	private IOException accountingFailure = null;
	/** First contextual explicit-close failure with later cleanup failures suppressed, or null. */
	private IOException closeFailure = null;

	/**
	 * Creates an empty queue without acquiring resources or calling collaborators.
	 *
	 * @param budget reserved physical bytes and maximum live attempts
	 * @param measurements fresh host and complete owned-tree memory readings
	 * @param attempts resource-free factory configured for each action identity and attempt sequence
	 * @throws NullPointerException if a parameter is null
	 */
	JobQueue(Policy.Budget budget, Controller.Measurements measurements, AttemptFactory<R> attempts)
	{
		Objects.requireNonNull(budget, "budget may not be null");
		Objects.requireNonNull(measurements, "measurements may not be null");
		this.attempts = Objects.requireNonNull(attempts, "attempts may not be null");
		controller = new Controller(budget, measurements, this::apply);
	}

	/**
	 * Reserves an action name and its original position without launching it. Names remain reserved after completion.
	 * The first position is zero; retries retain that position. Initial state is READY with zero cached resident bytes,
	 * no attempt or denial sequence and retryAfter zero.
	 *
	 * @param name nonempty action identity, unique for this queue's entire lifetime
	 * @param peakBytes nonnegative whole-tree peak estimate in bytes, or null for unknown demand
	 * @throws NullPointerException if {@code name} is null
	 * @throws IllegalArgumentException if {@code name} is empty or reserved, or {@code peakBytes} is negative
	 * @throws IllegalStateException if closing or an unresolved control, {@code outcome}, factory or cleanup prevents scheduling
	 * @throws ArithmeticException if another submission position cannot be reserved without overflow
	 */
	void submit(String name, Long peakBytes)
	{
		requireScheduling();
		Objects.requireNonNull(name, "name may not be null");
		if (name.isEmpty())
			throw new IllegalArgumentException("Action name must be nonempty");
		if (entries.containsKey(name))
			throw new IllegalArgumentException("Action name is already reserved: " + name);
		if (peakBytes != null && peakBytes < 0)
			throw new IllegalArgumentException("Peak bytes must be nonnegative: " + peakBytes);

		long successor = Math.incrementExact(nextPosition);
		entries.put(name, new Entry(name, nextPosition, peakBytes));
		nextPosition = successor;
	}

	/**
	 * Reconciles retained attempts, then obtains fresh measurements and applies at most one selected control.
	 * Polls run in original submission order. Accepted outcomes survive a later poll failure and are never polled again.
	 * Every accepted non-aborted outcome counts once, including a nonzero exit; aborted outcomes never count.
	 * Selected termination retains its completion threshold at selection count plus one. An unexpected abort uses the
	 * count after every natural completion in the successful observation batch, plus one. Neither threshold substitutes
	 * for accepted whole-job completion and successful owner cleanup. WAIT issues no Attempt request.
	 *
	 * @return the selected request, including WAIT when no control is eligible
	 * @throws IllegalStateException if closing or a factory previously violated its contract
	 * @throws ArithmeticException if another attempt sequence or selected retry threshold cannot be reserved
	 * @throws IOException if observation, memory sampling, control, cleanup or exact completion accounting fails;
	 * uncertain controls retain their original contextual failure until accepted completion and successful cleanup
	 */
	Policy.Decision cycle() throws IOException
	{
		requireOpen();
		if (fatalFailure != null)
			throw fatalFailure;
		reconcile();
		return controller.cycle(jobs(), completions);
	}

	/**
	 * Records an actual allocation wait supplied by the attempt adapter. A new denial retains the live slot and uses
	 * one global denial ordinal; its per-attempt wait sequence is separate and survives successful resume.
	 *
	 * @param name nonempty previously submitted action identity
	 * @param attemptSequence nonnegative attempt ordinal reported by the adapter
	 * @param waitSequence nonnegative per-attempt wait ordinal, strictly newer than its previous accepted wait
	 * @return true if the current RUNNING attempt enters BLOCKED; false for completed actions, noncurrent attempts,
	 * duplicate or older waits, inactive work or STOPPING
	 * @throws NullPointerException if {@code name} is null
	 * @throws IllegalArgumentException if {@code name} is empty or unknown, or a sequence is negative
	 * @throws IllegalStateException if closing, scheduling is unresolved, or a newer wait arrives while already BLOCKED;
	 * the rejected notification leaves state unchanged
	 * @throws ArithmeticException if another denial ordinal cannot be reserved without overflow
	 */
	boolean allocationBlocked(String name, long attemptSequence, long waitSequence)
	{
		requireScheduling();
		Objects.requireNonNull(name, "name may not be null");
		Entry entry = entries.get(name);
		if (name.isEmpty() || entry == null)
			throw new IllegalArgumentException("Allocation wait requires a known nonempty action name: " + name);
		if (attemptSequence < 0 || waitSequence < 0)
			throw new IllegalArgumentException("Allocation wait sequences must be nonnegative: attempt=" +
				attemptSequence + ", wait=" + waitSequence);
		boolean stale = entry.finished || entry.sequence == null || entry.sequence != attemptSequence ||
			waitSequence <= entry.waitSequence || entry.state == Policy.State.STOPPING;
		if (stale)
			return false;
		if (entry.state == Policy.State.BLOCKED)
			throw new IllegalStateException("Action already has an allocation wait: " + name);
		if (entry.state != Policy.State.RUNNING)
			return false;

		long successor = Math.incrementExact(nextDenial);
		entry.blockedAt = nextDenial;
		nextDenial = successor;
		entry.waitSequence = waitSequence;
		entry.state = Policy.State.BLOCKED;
		return true;
	}

	/**
	 * Returns immutable unfinished-action projections in original submission order, independent of later changes.
	 * Cached resident bytes may be zero or stale; Controller obtains fresh readings for its decision.
	 *
	 * @return unfinished jobs, omitting completed actions
	 * @throws IllegalStateException if closing or an unresolved effect, accepted {@code outcome} or cleanup prevents projection
	 */
	List<Policy.Job> jobs()
	{
		requireScheduling();
		return List.copyOf(entries.values().stream().filter(entry -> entry.finished == false).map(Entry::snapshot).toList());
	}

	/**
	 * Returns the number of accepted non-aborted whole-action completions accounted once. A failed observation batch
	 * defers accounting until reconciliation succeeds. This diagnostic remains available after failure or close.
	 *
	 * @return the nonnegative completion count
	 */
	long completions()
	{
		return completions;
	}

	/**
	 * Returns immutable accepted attempt outcomes in acceptance order, including aborted attempts before retries.
	 * Entries retain the original typed payload and remain available after failure or close; close invents no result.
	 *
	 * @return all accepted attempt outcomes, independent of later additions
	 */
	List<Completion<R>> completedAttempts()
	{
		return List.copyOf(completed);
	}

	/**
	 * Permanently closes the queue before attempting cleanup of every retained owner. Each owner is closed at most once,
	 * including owners whose cleanup failed. Successful ownership release is recorded; failed owners and their original
	 * contextual failures remain retained. Later failures are suppressed on the first failure. Repeated close after
	 * success is a no-op; after failure it reports the retained failure without retrying native close or promising recovery.
	 * No completion or abortion is inferred from cleanup.
	 *
	 * @throws IOException if any retained owner's cleanup failed, including a failure from earlier reconciliation
	 */
	@Override
	public void close() throws IOException
	{
		closing = true;
		if (closeFailure != null)
			throw closeFailure;
		for (Entry entry : entries.values())
		{
			closeOwner(entry);
			if (entry.cleanupFailure != null)
			{
				if (closeFailure == null)
					closeFailure = entry.cleanupFailure;
				else
					closeFailure.addSuppressed(entry.cleanupFailure);
			}
		}
		if (closeFailure != null)
			throw closeFailure;
	}

	/**
	 * Attempts an owner's cleanup once, releasing its reference only on success and retaining any contextual failure.
	 *
	 * @param entry action whose retained owner may need cleanup
	 */
	private void closeOwner(Entry entry)
	{
		if (entry.owner == null || entry.closeAttempted)
			return;
		entry.closeAttempted = true;
		try
		{
			entry.owner.close();
			entry.owner = null;
		}
		catch (IOException failure)
		{
			entry.cleanupFailure = contextual(entry, "close", failure);
		}
	}

	/**
	 * Applies Controller's single selected effect after reserving its identity or termination threshold.
	 *
	 * @param decision selected start, resume or terminate request
	 * @throws IllegalStateException if the resource-free factory throws or returns null
	 * @throws ArithmeticException if a required successor cannot be represented before issuing the effect
	 * @throws IOException if a control fails after potentially changing attempt state
	 */
	private void apply(Policy.Decision decision) throws IOException
	{
		Entry entry = entries.get(decision.jobName());
		switch (decision.control())
		{
			case START ->
			{
				long successor = Math.incrementExact(nextAttempt);
				entry.sequence = nextAttempt;
				nextAttempt = successor;
				entry.waitSequence = -1;
				entry.outcome = null;
				entry.accounted = false;
				entry.selectedRetryAfter = null;
				entry.closeAttempted = false;
				try
				{
					entry.owner = Objects.requireNonNull(attempts.create(entry.name, entry.sequence),
						"Factory returned null Attempt");
				}
				catch (RuntimeException failure)
				{
					fatalFailure = new IllegalStateException("Action " + entry.name + " attempt " + entry.sequence +
						" factory violated its contract", failure);
					throw fatalFailure;
				}
				try
				{
					entry.owner.start();
					entry.state = Policy.State.RUNNING;
				}
				catch (IOException failure)
				{
					entry.uncertain = contextual(entry, "start", failure);
					throw entry.uncertain;
				}
			}
			case RESUME ->
			{
				try
				{
					entry.owner.resume(entry.waitSequence);
					entry.state = Policy.State.RUNNING;
					entry.blockedAt = null;
				}
				catch (IOException failure)
				{
					entry.uncertain = contextual(entry, "resume", failure);
					throw entry.uncertain;
				}
			}
			case TERMINATE ->
			{
				entry.selectedRetryAfter = Math.incrementExact(completions);
				entry.state = Policy.State.STOPPING;
				entry.blockedAt = null;
				try
				{
					entry.owner.terminate();
				}
				catch (IOException failure)
				{
					entry.uncertain = contextual(entry, "terminate", failure);
					throw entry.uncertain;
				}
			}
			case WAIT -> throw new AssertionError("Controller must not apply WAIT");
		}
	}

	/**
	 * Observes whole-job outcomes before accounting the successful batch and cleaning accepted-complete owners.
	 *
	 * @throws IOException if polling, accounting, cleanup or a retained uncertain control prevents another decision
	 */
	private void reconcile() throws IOException
	{
		for (Entry entry : entries.values())
			if (entry.owner != null && entry.outcome == null)
			{
				try
				{
					Outcome<R> outcome = entry.owner.poll().orElse(null);
					if (outcome != null)
					{
						entry.outcome = outcome;
						completed.add(new Completion<>(entry.name, entry.sequence, outcome));
					}
				}
				catch (IOException failure)
				{
					throw contextual(entry, "poll", failure);
				}
			}

		if (accountingFailure != null)
			throw accountingFailure;
		try
		{
			accountOutcomes();
		}
		catch (ArithmeticException failure)
		{
			accountingFailure = new IOException("Completion count or retry threshold exhausted", failure);
			throw accountingFailure;
		}

		for (Entry entry : entries.values())
			if (entry.owner != null && entry.outcome != null)
			{
				closeOwner(entry);
				if (entry.cleanupFailure != null)
					continue;
				entry.uncertain = null;
				entry.sequence = null;
				entry.blockedAt = null;
				if (entry.outcome.aborted())
					entry.state = Policy.State.RETRY;
				else
					entry.finished = true;
			}
		for (Entry entry : entries.values())
		{
			if (entry.cleanupFailure != null)
				throw entry.cleanupFailure;
			if (entry.uncertain != null)
				throw entry.uncertain;
		}
	}

	/**
	 * Accounts stored outcomes once, using the entire batch's natural completion count for unexpected-abort thresholds.
	 * Computes every required successor before committing accounting changes.
	 *
	 * @throws ArithmeticException if a completion count or fresh retry threshold cannot be represented
	 */
	private void accountOutcomes()
	{
		long observedCompletions = completions;
		for (Entry entry : entries.values())
			if (entry.outcome != null && entry.accounted == false && entry.outcome.aborted() == false)
				observedCompletions = Math.incrementExact(observedCompletions);
		boolean unexpectedAbort = entries.values().stream().anyMatch(entry -> entry.outcome != null &&
			entry.accounted == false && entry.outcome.aborted() && entry.selectedRetryAfter == null);
		long unexpectedRetryAfter = 0;
		if (unexpectedAbort)
			unexpectedRetryAfter = Math.incrementExact(observedCompletions);

		for (Entry entry : entries.values())
			if (entry.outcome != null && entry.accounted == false)
			{
				if (entry.outcome.aborted())
				{
					if (entry.selectedRetryAfter == null)
						entry.retryAfter = unexpectedRetryAfter;
					else
						entry.retryAfter = entry.selectedRetryAfter;
				}
				entry.accounted = true;
			}
		completions = observedCompletions;
	}

	/**
	 * Rejects work after terminal cleanup begins.
	 *
	 * @throws IllegalStateException if the queue is closing
	 */
	private void requireOpen()
	{
		if (closing)
			throw new IllegalStateException("Queue is closing");
	}

	/**
	 * Requires an open queue whose owned state supports an accurate scheduling projection.
	 *
	 * @throws IllegalStateException if closing or a retained failure or pending accepted {@code outcome} prevents projection
	 */
	private void requireScheduling()
	{
		requireOpen();
		if (fatalFailure != null)
			throw fatalFailure;
		if (accountingFailure != null)
			throw new IllegalStateException("Completion accounting is unresolved", accountingFailure);
		for (Entry entry : entries.values())
		{
			if (entry.cleanupFailure != null)
				throw new IllegalStateException("Action cleanup failed: " + entry.name, entry.cleanupFailure);
			if (entry.uncertain != null)
				throw new IllegalStateException("Action has an unresolved control: " + entry.name, entry.uncertain);
			if (entry.owner != null && entry.outcome != null)
				throw new IllegalStateException("Action outcome awaits reconciliation: " + entry.name);
		}
	}

	/**
	 * Preserves the native cause alongside its action, attempt and failed operation.
	 *
	 * @param entry affected action and attempt identity
	 * @param operation failed collaborator operation
	 * @param cause original failure
	 * @return contextual ownership failure
	 */
	private IOException contextual(Entry entry, String operation, Throwable cause)
	{
		return new IOException("Action " + entry.name + " attempt " + entry.sequence + " " + operation + " failed", cause);
	}

	/**
	 * Mutable ownership of one stable action; Policy.Job is only its immutable projection.
	 */
	private final class Entry
	{
		/** Stable nonempty action identity, reserved even after completion. */
		private final String name;
		/** Original submission ordinal retained across retries. */
		private final long position;
		/** Estimated whole-tree peak bytes, or null for unknown demand. */
		private final Long peakBytes;
		/** Policy lifecycle when reconciled; an uncertain control prevents exposing this projection. */
		private Policy.State state = Policy.State.READY;
		/** Exclusively owned attempt, retained until cleanup succeeds; null before creation or after success. */
		private Attempt<R> owner = null;
		/** Current queue-wide attempt ordinal, or null while inactive. */
		private Long sequence = null;
		/** Global denial ordinal for BLOCKED, otherwise null. */
		private Long blockedAt = null;
		/** Highest accepted per-attempt wait ordinal; minus one before any wait on a fresh attempt. */
		private long waitSequence = -1;
		/** Completion eligibility threshold, initially zero and positive for RETRY. */
		private long retryAfter = 0;
		/** Threshold saved before termination selection, or null for an unexpected abort. */
		private Long selectedRetryAfter = null;
		/** Accepted whole-job outcome, or null before acceptance on this attempt. */
		private Outcome<R> outcome = null;
		/** Whether the accepted outcome has already affected completion count and retry eligibility. */
		private boolean accounted = false;
		/** Whether successful cleanup completed a non-aborted action. */
		private boolean finished = false;
		/** Original contextual failed control, or null when that attempt has no unresolved control. */
		private IOException uncertain = null;
		/** Whether owner cleanup has been invoked, regardless of success. */
		private boolean closeAttempted = false;
		/** Original contextual failed cleanup, or null before any cleanup failure. */
		private IOException cleanupFailure = null;

		/**
		 * Reserves immutable action identity and ordering.
		 *
		 * @param name unique nonempty stable name
		 * @param position original nonnegative submission ordinal
		 * @param peakBytes nonnegative estimated peak bytes, or null for unknown demand
		 */
		private Entry(String name, long position, Long peakBytes)
		{
			this.name = name;
			this.position = position;
			this.peakBytes = peakBytes;
		}

		/**
		 * Projects internally reconciled state without claiming freshly measured residency.
		 *
		 * @return immutable unfinished action state
		 */
		private Policy.Job snapshot()
		{
			return new Policy.Job(name, state, position, peakBytes, 0, sequence, blockedAt, retryAfter);
		}
	}

	/**
	 * Configures fresh owners synchronously without process launch, external acquisition or I/O.
	 * The action-to-launch-input mapping belongs to the adapter. Returning null or throwing violates this contract and
	 * permanently stops scheduling; resources must never be acquired before the queue receives their owner.
	 *
	 * @param <R> complete accepted native result payload
	 */
	@FunctionalInterface
	interface AttemptFactory<R>
	{
		/**
		 * Creates a fresh resource-free owner configured for exactly this identity; ownership transfers on return.
		 *
		 * @param name stable nonempty submitted action name
		 * @param attemptSequence nonnegative queue-wide attempt ordinal, beginning at zero
		 * @return fresh nonnull attempt owned exclusively by the queue
		 */
		Attempt<R> create(String name, long attemptSequence);
	}

	/**
	 * Owns one workload attempt and every resource acquired while starting it. Retain each resource internally before
	 * the next fallible acquisition step. After every attempted start, poll and close remain callable, even when no
	 * resource was acquired. Requests may fail after partial effects; their exceptions do not discard ownership.
	 *
	 * @param <R> complete accepted native result payload
	 */
	interface Attempt<R> extends AutoCloseable
	{
		/**
		 * Starts/releases the configured workload at most once. Success permits a RUNNING projection.
		 *
		 * @throws IOException if acquisition or start fails, possibly after partial effects
		 */
		void start() throws IOException;

		/**
		 * Continues the current allocation wait at most once. Success permits RUNNING; failure retains that wait and owner.
		 *
		 * @param waitSequence current nonnegative per-attempt wait ordinal supplied by the adapter
		 * @throws IOException if continuation fails, possibly after partial effects
		 */
		void resume(long waitSequence) throws IOException;

		/**
		 * Requests termination once. Success acknowledges the request, not whole-job completion or ownership release.
		 *
		 * @throws IOException if termination fails, possibly after partial effects
		 */
		void terminate() throws IOException;

		/**
		 * Observes accepted whole-job completion/reaping. Empty means no whole-job result, including root-only exit.
		 * Repeated observation of an accepted result returns the same outcome. A failed read permits another poll but no
		 * new scheduling control until reconciliation succeeds.
		 *
		 * @return accepted outcome, or empty while the native owner has not accepted whole-job completion
		 * @throws IOException if the whole-job {@code outcome} cannot be observed; no abortion or completion is implied
		 */
		Optional<Outcome<R>> poll() throws IOException;

		/**
		 * Releases or terminally cleans every acquired resource, including resources retained after a partial start.
		 * The queue calls this once; a failure permanently prevents scheduling and is never cleared by a later no-op close.
		 *
		 * @throws IOException if complete cleanup cannot be established
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Retains a native owner's whole-job outcome without normalizing statuses or inferring abortion.
	 *
	 * @param <R> complete accepted native result payload
	 * @param result nonnull original payload, including any descendant/adopted statuses
	 * @param aborted native owner's accepted abortion classification, independent of root status or queue intent
	 */
	record Outcome<R>(R result, boolean aborted)
	{
		/**
		 * Creates an accepted outcome with its original payload.
		 *
		 * @param result nonnull complete native payload
		 * @param aborted native owner's accepted abortion classification
		 * @throws NullPointerException if {@code result} is null
		 */
		Outcome
		{
			Objects.requireNonNull(result, "result may not be null");
		}
	}

	/**
	 * Records one accepted attempt outcome, including aborted attempts before any retry.
	 *
	 * @param <R> complete accepted native result payload
	 * @param name nonempty stable action identity
	 * @param attemptSequence nonnegative queue-wide attempt ordinal
	 * @param outcome nonnull accepted whole-job outcome
	 */
	record Completion<R>(String name, long attemptSequence, Outcome<R> outcome)
	{
		/**
		 * Creates an immutable accepted-attempt record.
		 *
		 * @param name nonempty stable action identity
		 * @param attemptSequence nonnegative queue-wide attempt ordinal
		 * @param outcome nonnull accepted whole-job outcome
		 * @throws NullPointerException if {@code name} or {@code outcome} is null
		 * @throws IllegalArgumentException if {@code name} is empty or {@code attemptSequence} is negative
		 */
		Completion
		{
			Objects.requireNonNull(name, "name may not be null");
			Objects.requireNonNull(outcome, "outcome may not be null");
			if (name.isEmpty() || attemptSequence < 0)
				throw new IllegalArgumentException("Completion requires a nonempty name and nonnegative attempt sequence");
		}
	}
}
