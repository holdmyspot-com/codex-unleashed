package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;

/** Exercises queue-level batching; supplied continuations establish no native grant or process behavior. */
public final class AllocationWaitsTest
{
	/** Creates allocation batching acceptance tests. */
	public AllocationWaitsTest()
	{
	}

	/**
	 * Reports one queue wait for all known native waits and grants each once in first-accepted order.
	 *
	 * @throws IOException if a valid batch is rejected
	 */
	@Test
	public void batchesReorderedNativeWaitsAndRetainsDuplicateHistory() throws IOException
	{
		var waits = new AllocationWaits();
		var later = new AllocationWaits.Wait(1, 2);
		var earlier = new AllocationWaits.Wait(1, 1);
		var granted = new ArrayList<AllocationWaits.Wait>();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");

		requireThat(waits.blocked(later), "acceptedWait").isTrue();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(0), "expected");
		requireThat(waits.blocked(earlier), "acceptedWait").isTrue();
		requireThat(waits.blocked(later), "acceptedWait").isFalse();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");

		waits.resume(0, granted::add);
		requireThat(granted, "granted").isEqualTo(List.of(later, earlier), "expected");
		requireThat(waits.blocked(earlier), "acceptedWait").isFalse();
		requireThat(waits.blocked(later), "acceptedWait").isFalse();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");
		requireException(IllegalStateException.class, () -> waits.resume(0, granted::add));
		requireThat(granted, "granted").isEqualTo(List.of(later, earlier), "expected");
	}

	/**
	 * Freezes the selected batch while retaining new denials for the following queue decision.
	 *
	 * @throws IOException if a valid batch is rejected
	 */
	@Test
	public void defersWaitsReportedDuringAcknowledgement() throws IOException
	{
		var waits = new AllocationWaits();
		var first = new AllocationWaits.Wait(1, 1);
		var peer = new AllocationWaits.Wait(2, 1);
		var repeatedDenial = new AllocationWaits.Wait(1, 2);
		var granted = new ArrayList<AllocationWaits.Wait>();
		waits.blocked(first);
		waits.blocked(peer);
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(0), "expected");

		waits.resume(0, wait ->
		{
			granted.add(wait);
			waits.blocked(repeatedDenial);
			requireException(IllegalStateException.class, waits::poll);
		});
		requireThat(granted, "granted").isEqualTo(List.of(first, peer), "expected");
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(1), "expected");
		waits.resume(1, granted::add);
		requireThat(granted, "granted").isEqualTo(List.of(first, peer, repeatedDenial), "expected");
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");
	}

	/**
	 * Stops after an uncertain grant, retaining partial effects and refusing every later automatic continuation.
	 *
	 * @throws IOException if initial queue-wait publication fails
	 */
	@Test
	public void latchesPartialGrantFailureWithoutRetry() throws IOException
	{
		var waits = new AllocationWaits();
		var first = new AllocationWaits.Wait(1, 1);
		var second = new AllocationWaits.Wait(1, 2);
		var third = new AllocationWaits.Wait(1, 3);
		var attempted = new ArrayList<AllocationWaits.Wait>();
		var nativeFailure = new IOException("Grant acknowledgement lost");
		waits.blocked(first);
		waits.blocked(second);
		waits.blocked(third);
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(0), "expected");

		var failure = requireException(IOException.class, () -> waits.resume(0, wait ->
		{
			attempted.add(wait);
			if (wait.equals(second))
				throw nativeFailure;
		}));
		requireThat(failure.getCause(), "actualReference").isReferenceEqualTo(nativeFailure, "expectedReference");
		requireThat(attempted, "attempted").isEqualTo(List.of(first, second), "expected");
		requireThat(requireException(IOException.class, waits::poll).getCause(), "actualReference")
			.isReferenceEqualTo(failure, "expectedReference");
		requireThat(requireException(IOException.class, () -> waits.resume(0, attempted::add)).getCause(), "actualReference")
			.isReferenceEqualTo(failure, "expectedReference");
		requireThat(attempted, "attempted").isEqualTo(List.of(first, second), "expected");
		requireThat(waits.blocked(second), "acceptedWait").isFalse();
		requireThat(waits.blocked(new AllocationWaits.Wait(2, 1)), "acceptedWait").isTrue();
		waits.stop();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");
	}

	/**
	 * Rejects stale selections and invalid callbacks before consuming the active queue wait.
	 *
	 * @throws IOException if a valid batch is rejected
	 */
	@Test
	public void invalidSelectionLeavesBatchAvailable() throws IOException
	{
		var waits = new AllocationWaits();
		var wait = new AllocationWaits.Wait(1, 1);
		var granted = new ArrayList<AllocationWaits.Wait>();
		waits.blocked(wait);
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(0), "expected");

		requireException(IllegalStateException.class, () -> waits.resume(1, granted::add));
		requireException(NullPointerException.class, () -> waits.resume(0, null));
		waits.resume(0, granted::add);
		requireThat(granted, "granted").isEqualTo(List.of(wait), "expected");
	}

	/**
	 * Ends grant authority before native teardown while accepting harmless late reports as ignored.
	 *
	 * @throws IOException if empty polling fails
	 */
	@Test
	public void stoppingPreventsNewGrants() throws IOException
	{
		var waits = new AllocationWaits();
		var granted = new ArrayList<AllocationWaits.Wait>();
		waits.blocked(new AllocationWaits.Wait(1, 1));
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.of(0), "expected");

		waits.stop();
		waits.stop();
		requireThat(waits.blocked(new AllocationWaits.Wait(2, 1)), "acceptedWait").isFalse();
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty(), "expected");
		requireException(IllegalStateException.class, () -> waits.resume(0, granted::add));
		requireThat(granted, "granted").isEmpty();
	}

	/**
	 * Stops grants after an unexpected callback exit; runtime failures remain causes and Errors propagate unchanged.
	 *
	 * @throws IOException if preparing the published wait fails
	 */
	@Test
	public void uncheckedContinuationFailuresRetainUncertainty() throws IOException
	{
		var runtimeWaits = new AllocationWaits();
		runtimeWaits.blocked(new AllocationWaits.Wait(1, 1));
		runtimeWaits.poll();
		var runtimeFailure = new IllegalStateException("Native message decoder failed");
		var wrapped = requireException(IOException.class, () -> runtimeWaits.resume(0, wait ->
		{
			throw runtimeFailure;
		}));
		requireThat(wrapped.getCause(), "actualReference").isReferenceEqualTo(runtimeFailure, "expectedReference");
		requireThat(requireException(IOException.class, runtimeWaits::poll).getCause(), "actualReference")
			.isReferenceEqualTo(wrapped, "expectedReference");

		var errorWaits = new AllocationWaits();
		errorWaits.blocked(new AllocationWaits.Wait(1, 1));
		errorWaits.poll();
		var error = new AssertionError("Unexpected callback failure");
		requireThat(requireException(AssertionError.class, () -> errorWaits.resume(0, wait ->
		{
			throw error;
		})), "actualReference").isReferenceEqualTo(error, "expectedReference");
		requireException(IOException.class, errorWaits::poll);
		requireException(IOException.class, () -> errorWaits.resume(0, wait ->
		{
			throw new AssertionError("Uncertain continuation must not be repeated");
		}));
		errorWaits.stop();
		requireThat(errorWaits.poll(), "queueWait").isEqualTo(OptionalLong.empty());
	}

	/**
	 * Permits reports while awaiting acknowledgements while rejecting other reentrant mutations before their effects.
	 *
	 * @throws IOException if a valid acknowledged grant fails
	 */
	@Test
	public void reentrantControlsCannotWithdrawOrRepeatSelectedGrant() throws IOException
	{
		var waits = new AllocationWaits();
		waits.blocked(new AllocationWaits.Wait(1, 1));
		waits.poll();
		var granted = new ArrayList<AllocationWaits.Wait>();
		waits.resume(0, wait ->
		{
			granted.add(wait);
			requireException(IllegalStateException.class, waits::stop);
			requireException(IllegalStateException.class, () -> waits.resume(0, granted::add));
		});
		requireThat(granted, "granted").isEqualTo(List.of(new AllocationWaits.Wait(1, 1)), "expected");
		requireThat(waits.poll(), "queueWait").isEqualTo(OptionalLong.empty());
	}

	/** Validates native identity values without merging installations or accepting absent reports. */
	@Test
	public void rejectsInvalidNativeIdentity()
	{
		requireException(IllegalArgumentException.class, () -> new AllocationWaits.Wait(0, 1));
		requireException(IllegalArgumentException.class, () -> new AllocationWaits.Wait(1, 0));
		requireException(IllegalArgumentException.class, () -> new AllocationWaits.Wait(-1, 1));
		requireException(IllegalArgumentException.class, () -> new AllocationWaits.Wait(1, -1));
		requireException(NullPointerException.class, () -> new AllocationWaits().blocked(null));
	}
}
