package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies which failed attempt starts the scheduler retries and how leader receipts are classified.
 */
public final class LeaderDispatchRetriesTest
{
	/** Leader frame observed in CI when the leader could not run its launch callback. */
	private static final String DISPATCH_RECEIPT =
		"{\"type\":\"send\",\"payload\":\"dispatch-error:Error: failed to run on thread\"}";

	/**
	 * Creates retry policy tests.
	 */
	public LeaderDispatchRetriesTest()
	{
	}

	/**
	 * Classifies the leader's dispatch-error receipt as a dispatch failure and other unexpected receipts as generic.
	 */
	@Test
	public void classifiesDispatchReceipt()
	{
		requireThat(LeaderDispatchFailure.unexpected("dispatch-error:Error: failed to run on thread", DISPATCH_RECEIPT),
			"dispatch").isInstanceOf(LeaderDispatchFailure.class);
		requireThat(LeaderDispatchFailure.unexpected("exec-returned:-1:2", "{}") instanceof LeaderDispatchFailure,
			"execReturned").isFalse();
		requireThat(LeaderDispatchFailure.unexpected("", "{}") instanceof LeaderDispatchFailure, "absent").isFalse();
	}

	/**
	 * Retries an attempt whose start failed because the leader could not dispatch the launch.
	 */
	@Test
	public void dispatchFailureIsRetried()
	{
		var retries = new LeaderDispatchRetries();
		requireThat(retries.retry("crate", startFailure(LeaderDispatchFailure.unexpected("dispatch-error:x",
			DISPATCH_RECEIPT))), "retried").isTrue();
	}

	/**
	 * Keeps every other start failure fatal.
	 */
	@Test
	public void otherStartFailuresAreFatal()
	{
		var retries = new LeaderDispatchRetries();
		requireThat(retries.retry("crate", startFailure(LeaderDispatchFailure.unexpected("exec-returned:-1:2", "{}"))),
			"execReturned").isFalse();
		requireThat(retries.retry("crate", startFailure(new IOException("Leader receipt deadline exceeded"))),
			"deadline").isFalse();
	}

	/**
	 * Makes the third consecutive dispatch failure of one action fatal, counting each action separately and starting
	 * the count again after a successful start.
	 */
	@Test
	public void boundsConsecutiveDispatchFailures()
	{
		var retries = new LeaderDispatchRetries();
		IOException dispatch = startFailure(LeaderDispatchFailure.unexpected("dispatch-error:x", DISPATCH_RECEIPT));
		requireThat(retries.retry("crate", dispatch), "first").isTrue();
		requireThat(retries.retry("other", dispatch), "otherAction").isTrue();
		requireThat(retries.retry("crate", dispatch), "second").isTrue();
		requireThat(retries.retry("crate", dispatch), "third").isFalse();

		retries.started("other");
		requireThat(retries.retry("other", dispatch), "afterSuccessfulStart").isTrue();
		requireThat(retries.retry("other", dispatch), "secondAfterSuccessfulStart").isTrue();
		requireThat(retries.retry("other", dispatch), "thirdAfterSuccessfulStart").isFalse();
	}

	/**
	 * Wraps a leader failure as instrumentation start reports it to the attempt.
	 * @param cause the leader failure
	 * @return the failure that the attempt's start observes
	 */
	private static IOException startFailure(IOException cause)
	{
		return new IOException("Instrumentation start failed", cause);
	}
}
