package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Collects native allocation waits into queue-level batches for one workload attempt.
 * <p>
 * One caller thread owns this object. The attempt adapter must validate native messages and installation ownership
 * before reporting waits here. A blocked allocation does not imply that the whole process is suspended.
 * Each {@link #poll()} publishes one queue wait; {@link #resume(long, Continuation)} grants only the native waits
 * known when that resume begins. New reports accepted during acknowledgement belong to the following batch.
 * The caller must publish that following batch after JobQueue has accepted the previous resume.
 * <p>
 * This object owns no native resources. The attempt retains process and instrumentation ownership independently.
 * A failed continuation permanently stops automatic grants, including unsent members of its selected batch.
 */
final class AllocationWaits
{
	/** Accepted identities retained for the attempt lifetime, including previously granted waits. */
	private final Set<Wait> seen = new LinkedHashSet<>();
	/** Unselected identities in first-accepted order; native publication may reorder their sequence numbers. */
	private final Set<Wait> pending = new LinkedHashSet<>();
	/** Frozen batch retained until every continuation succeeds; retained after uncertain partial effects. */
	private List<Wait> selected = List.of();
	/** Next per-attempt queue wait sequence, beginning at zero; successor checked before publication. */
	private long nextSequence = 0;
	/** Published queue wait, or null before publication and after its complete acknowledged continuation. */
	private Long activeSequence = null;
	/** Whether a continuation callback is running; only blocked reports may reenter then. */
	private boolean resuming = false;
	/** Terminal grant-authority withdrawal, initially false. */
	private boolean stopped = false;
	/** Contextual uncertain continuation failure, or null before any failed or ongoing continuation. */
	private IOException failure = null;

	/** Creates an empty, resource-free collection. */
	AllocationWaits()
	{
	}

	/**
	 * Retains a validated native wait without publishing a second notification for the active queue wait.
	 * Reports after a failed continuation remain retained; reports after stop are ignored.
	 *
	 * @param wait positive installation and native wait identity, already validated by the owning adapter
	 * @return true for a previously unseen wait while grant authority has not been stopped
	 * @throws NullPointerException if {@code wait} is null
	 */
	boolean blocked(Wait wait)
	{
		Objects.requireNonNull(wait, "wait may not be null");
		if (stopped || seen.add(wait) == false)
			return false;
		pending.add(wait);
		return true;
	}

	/**
	 * Publishes the next queue wait once. Membership remains open until resume begins.
	 *
	 * @return the new nonnegative sequence, or empty when stopped, already published or without pending waits
	 * @throws IllegalStateException if called from a continuation callback
	 * @throws ArithmeticException if another sequence cannot be reserved without overflow
	 * @throws IOException if an earlier continuation failed; its contextual failure is retained as the cause
	 */
	OptionalLong poll() throws IOException
	{
		requireOutsideContinuation();
		if (stopped)
			return OptionalLong.empty();
		requireCertain();
		if (activeSequence != null || pending.isEmpty())
			return OptionalLong.empty();

		long successor = Math.incrementExact(nextSequence);
		activeSequence = nextSequence;
		nextSequence = successor;
		return OptionalLong.of(activeSequence);
	}

	/**
	 * Grants the frozen known batch in first-accepted order. The borrowed continuation must return only after the
	 * matching native permission acknowledgement. It may report newly observed waits with {@link #blocked(Wait)};
	 * no other operation may reenter this collection. A partial failure permits no further automatic grants.
	 *
	 * @param sequence currently published queue wait sequence
	 * @param continuation acknowledged native grant operation borrowed for this call
	 * @throws NullPointerException if {@code continuation} is null
	 * @throws IllegalStateException if stopped, reentrant or the sequence does not identify the published wait
	 * @throws IOException if a grant or acknowledgement fails; the selected batch remains retained
	 */
	void resume(long sequence, Continuation continuation) throws IOException
	{
		Objects.requireNonNull(continuation, "continuation may not be null");
		requireOutsideContinuation();
		if (stopped)
			throw new IllegalStateException("Allocation continuation authority has stopped");
		requireCertain();
		if (activeSequence == null || sequence != activeSequence)
			throw new IllegalStateException("Allocation continuation requires the published queue wait: " + sequence);

		selected = List.copyOf(pending);
		pending.clear();
		resuming = true;
		try
		{
			for (Wait wait : selected)
			{
				// Retain uncertainty before the external effect, including an Error that prevents normal return.
				failure = new IOException("Native allocation continuation did not return: " + wait);
				try
				{
					continuation.continueAllocation(wait);
				}
				catch (IOException | RuntimeException cause)
				{
					failure = new IOException("Native allocation continuation failed: " + wait, cause);
					throw failure;
				}
				failure = null;
			}
			selected = List.of();
			activeSequence = null;
		}
		finally
		{
			resuming = false;
		}
	}

	/**
	 * Permanently withdraws grant authority before the attempt tears down native owners. Repeated calls are harmless.
	 * Retained identities and partial-effect evidence survive; this does not wake or terminate native threads.
	 *
	 * @throws IllegalStateException if called from a continuation callback
	 */
	void stop()
	{
		requireOutsideContinuation();
		stopped = true;
	}

	/**
	 * Rejects operations whose reentry would change queue publication or the selected batch during a grant.
	 *
	 * @throws IllegalStateException if a continuation is in progress
	 */
	private void requireOutsideContinuation()
	{
		if (resuming)
			throw new IllegalStateException("Only blocked reports may reenter an allocation continuation");
	}

	/**
	 * Refuses another automatic effect after an uncertain one without rethrowing the same exception instance.
	 *
	 * @throws IOException if a previous continuation did not complete normally
	 */
	private void requireCertain() throws IOException
	{
		if (failure != null)
			throw new IOException("Allocation continuations have an unresolved native effect", failure);
	}

	/**
	 * Identifies a native allocation wait within a unique script installation.
	 *
	 * @param installation positive adapter-assigned generation, never reused within this attempt, including after exec
	 * @param sequence positive native wait identity, unique within the installation
	 */
	record Wait(long installation, long sequence)
	{
		/**
		 * Validates identity components without acquiring resources.
		 *
		 * @throws IllegalArgumentException if either component is zero or negative
		 */
		Wait
		{
			if (installation <= 0 || sequence <= 0)
				throw new IllegalArgumentException("Native wait identities must be positive");
		}
	}

	/** Performs one native grant and returns only after its matching acknowledgement. */
	@FunctionalInterface
	interface Continuation
	{
		/**
		 * Grants one matching native permission and awaits its acknowledgement, without claiming allocation success.
		 *
		 * @param wait validated native identity selected by the owning collection
		 * @throws IOException if posting, acknowledgement or native permission fails or has an uncertain result
		 */
		void continueAllocation(Wait wait) throws IOException;
	}
}
