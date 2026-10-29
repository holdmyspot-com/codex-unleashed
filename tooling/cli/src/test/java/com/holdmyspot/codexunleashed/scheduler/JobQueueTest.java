package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Exercises real queue decisions with the documented Attempt contract supplied in process; no native behavior is claimed.
 */
public final class JobQueueTest
{
	/**
	 * Creates queue lifecycle acceptance tests.
	 */
	public JobQueueTest()
	{
	}

	/**
	 * Starts FIFO work once and retains the exact owner before a failed start so explicit close can clean it.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "initial")
	public void startsEarliestSubmissionAndRetainsAttemptBeforeStart() throws IOException
	{
		Fixture fixture = new Fixture(1);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);

			assertEquals(queue.cycle().control(), Policy.Control.START, "Earliest submitted action must start");
			assertEquals(fixture.events, List.of("create:A:0", "start:A:0"));
			assertEquals(queue.jobs().getFirst().startedAt(), Long.valueOf(0));

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
		}
		assertEquals(fixture.attempt("A", 0).closes, 1);
		Fixture failing = new Fixture(1);
		failing.failStart = true;
		var queue = failing.queue();
		queue.submit("A", 10L);
		expectThrows(IOException.class, queue::cycle);
		queue.close();
		assertEquals(failing.attempt("A", 0).closes, 1, "Attempt must be retained before fallible start");
	}

	/**
	 * Resumes the oldest global denial with unchanged attempt and live-slot ownership, without bypassing an unaffordable wait.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "waits")
	public void resumesOldestDenialWithoutAnotherSlot() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 20L);
			queue.submit("B", 20L);
			queue.submit("C", 1L);

			queue.cycle();

			queue.cycle();
			assertTrue(queue.allocationBlocked("B", 1, 40));
			assertTrue(queue.allocationBlocked("A", 0, 90));
			var before = queue.jobs();
			assertEquals(before.get(1).blockedAt(), Long.valueOf(0));
			fixture.memory = new Controller.Memory(0, 0, false);

			assertEquals(queue.cycle().control(), Policy.Control.WAIT, "Unaffordable oldest denial cannot be bypassed");
			assertEquals(fixture.owners.size(), 2);
			assertEquals(fixture.latestAttempt("A").resumes, 0);
			fixture.calm();
			var decision = queue.cycle();
			assertEquals(decision.control(), Policy.Control.RESUME);
			assertEquals(decision.jobName(), "B");
			assertEquals(queue.jobs().get(1).startedAt(), before.get(1).startedAt());
			assertEquals(queue.jobs().get(1).position(), 1);
			assertEquals(fixture.latestAttempt("B").resumes, 1);
			assertEquals(fixture.owners.size(), 2, "Resume retains its live slot and owner");
		}
	}

	/**
	 * Preserves denial order and the resumed wait high-water mark while rejecting a newer wait during BLOCKED.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "waits")
	public void ignoresDuplicateAndDelayedWaitReports() throws IOException
	{
		Fixture fixture = new Fixture(1);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);

			queue.cycle();
			assertTrue(queue.allocationBlocked("A", 0, 5));
			var blocked = queue.jobs();
			assertFalse(queue.allocationBlocked("A", 0, 5));
			assertFalse(queue.allocationBlocked("A", 0, 4));
			expectThrows(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 6));
			assertEquals(queue.jobs(), blocked);

			assertEquals(queue.cycle().control(), Policy.Control.RESUME);
			assertFalse(queue.allocationBlocked("A", 0, 5));
			assertFalse(queue.allocationBlocked("A", 0, 1));
			assertFalse(queue.allocationBlocked("A", 99, 6));
			assertEquals(queue.jobs().getFirst().state(), Policy.State.RUNNING);
			assertTrue(queue.allocationBlocked("A", 0, 6));
			assertEquals(queue.jobs().getFirst().blockedAt(), Long.valueOf(1));
		}
	}

	/**
	 * Retries selected-aborted A at its original position with a new attempt identity and a reset wait high-water mark.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void preservesOriginalPositionAcrossRetry() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.submit("C", 10L);

			queue.cycle();

			queue.cycle();
			queue.allocationBlocked("A", 0, 8);
			fixture.pressure();

			assertEquals(queue.cycle().jobName(), "A");
			assertEquals(fixture.attempt("A", 0).terminates, 1);
			fixture.attempt("A", 0).finish(true, 137);
			fixture.attempt("B", 1).finish(false, 0);
			fixture.calm();

			assertEquals(queue.cycle().jobName(), "A");
			assertEquals(queue.jobs().getFirst().position(), 0);
			assertEquals(queue.jobs().getFirst().startedAt(), Long.valueOf(2));
			assertEquals(fixture.attempt("A", 0).closes, 1);
			assertFalse(queue.allocationBlocked("A", 0, 99));
			assertTrue(queue.allocationBlocked("A", 2, 0));
		}
	}

	/**
	 * Keeps older ineligible retry A ahead of an eligible later retry and READY work until another completion arrives.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void doesNotBypassIneligibleOldestRetry() throws IOException
	{
		Fixture fixture = new Fixture(4);

		try (var queue = fixture.queue())
		{
			for (String name : List.of("A", "B", "C", "D", "ready"))queue.submit(name, 10L);
			for (int i = 0;i<4;i++)queue.cycle();
			fixture.pressure();

			assertEquals(queue.cycle().jobName(), "D");
			fixture.latestAttempt("A").finish(true, 9);
			fixture.latestAttempt("B").finish(false, 0);
			fixture.latestAttempt("D").finish(true, 137);
			fixture.calm();

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
			assertEquals(queue.jobs().getFirst().retryAfter(), 2);
			assertEquals(queue.jobs().get(2).retryAfter(), 1);
			assertEquals(fixture.owners.size(), 4, "Older ineligible A must block eligible D and READY work");
			fixture.latestAttempt("C").finish(false, 0);

			assertEquals(queue.cycle().jobName(), "A");
		}
	}

	/**
	 * Keeps STOPPING while the controlled owner withholds whole-job completion, then requires accepted outcome and cleanup.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void keepsStoppingBarrierUntilWholeJobOutcomeAndCleanup() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.submit("C", 10L);

			queue.cycle();

			queue.cycle();
			fixture.pressure();

			assertEquals(queue.cycle().control(), Policy.Control.TERMINATE);
			fixture.calm();
			// Empty input models the native owner withholding whole-job completion while descendants remain.

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
			assertEquals(queue.jobs().get(1).state(), Policy.State.STOPPING);
			assertFalse(queue.allocationBlocked("B", 1, 0));
			assertEquals(fixture.latestAttempt("B").terminates, 1);
			assertEquals(fixture.owners.size(), 2);
			fixture.latestAttempt("B").finish(true, 137);

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
			assertEquals(fixture.attempt("B", 1).closes, 1);
			assertEquals(queue.jobs().get(1).state(), Policy.State.RETRY);
			fixture.latestAttempt("A").finish(false, 0);

			assertEquals(queue.cycle().jobName(), "B");
		}
	}

	/**
	 * Counts a natural completion during STOPPING against the saved selection threshold without releasing the barrier early.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void completionDuringStoppingSatisfiesSelectedBaseline() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);

			queue.cycle();

			queue.cycle();
			fixture.pressure();

			queue.cycle();
			fixture.calm();
			fixture.latestAttempt("A").finish(false, 7);

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
			assertEquals(queue.completions(), 1);
			assertEquals(queue.jobs().getFirst().state(), Policy.State.STOPPING);
			fixture.latestAttempt("B").finish(true, 137);

			assertEquals(queue.cycle().jobName(), "B");
			assertEquals(fixture.latestAttempt("B").sequence, 2);
		}
	}

	/**
	 * Computes an unexpected-abort threshold after every natural completion in its observation batch.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void unexpectedAbortUsesFreshObservationBaseline() throws IOException
	{
		Fixture fixture = new Fixture(3);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.submit("C", 10L);

			queue.cycle();

			queue.cycle();

			queue.cycle();
			fixture.latestAttempt("A").finish(true, 0);
			fixture.latestAttempt("B").finish(false, 0);

			assertEquals(queue.cycle().control(), Policy.Control.WAIT);
			assertEquals(queue.completions(), 1);
			assertEquals(queue.jobs().getFirst().retryAfter(), 2, "Same-batch natural completion precedes unexpected-abort baseline");
			fixture.latestAttempt("C").finish(false, 0);

			assertEquals(queue.cycle().jobName(), "A");
			assertEquals(queue.completions(), 2);
		}
	}

	/**
	 * Counts a natural nonzero outcome once and retains the identical typed result through repeated cycles and close.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void countsNaturalNonzeroCompletionOnceAndRetainsTypedResult() throws IOException
	{
		Fixture fixture = new Fixture(1);
		var queue = fixture.queue();
		queue.submit("A", 10L);
		queue.cycle();
		var result = new Result(7, List.of(0, 23, 137));
		var outcome = new JobQueue.Outcome<>(result, false);
		fixture.latestAttempt("A").outcome = outcome;
		queue.cycle();
		queue.cycle();
		assertEquals(queue.completions(), 1);
		assertTrue(queue.jobs().isEmpty());
		assertEquals(queue.completedAttempts().size(), 1);
		assertSame(queue.completedAttempts().getFirst().outcome().result(), result);
		assertEquals(fixture.latestAttempt("A").polls, 1);
		var saved = queue.completedAttempts();
		queue.close();
		assertEquals(queue.completions(), 1);
		assertEquals(queue.completedAttempts(), saved);
		assertSame(queue.completedAttempts().getFirst().outcome(), outcome);
	}

	/**
	 * Maps the supplied accepted abortion classification independently of termination intent and root status; this models no native race.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "lifecycle")
	public void acceptedNativeOutcomeWinsTerminationRace() throws IOException
	{
		for (boolean aborted : List.of(false, true))
		{
			Fixture fixture = new Fixture(2);
			try (var queue = fixture.queue())
			{
				queue.submit("A", 10L);
				queue.submit("B", 10L);
				queue.cycle();
				queue.cycle();
				fixture.pressure();
				queue.cycle();
				fixture.latestAttempt("B").finish(aborted, 0);
				var original = fixture.latestAttempt("B").outcome;
				fixture.calm();
				queue.cycle();
				assertSame(queue.completedAttempts().getFirst().outcome(), original);
				assertEquals(queue.completions(), aborted?0L : 1L);
				assertEquals(queue.jobs().stream().anyMatch(j -> j.name().equals("B")), aborted);
				if (aborted)assertEquals(queue.jobs().get(1).state(), Policy.State.RETRY);
			}
		}
	}

	/**
	 * Polls after a partial failed start without relaunch, retaining ownership until explicit cleanup or accepted completion.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void failedStartCannotRelaunchAndStillOwnsCleanup() throws IOException
	{
		Fixture fixture = new Fixture(2);
		fixture.failStart = true;
		var queue = fixture.queue();
		queue.submit("A", 10L);
		queue.submit("B", 10L);
		var failure = expectThrows(IOException.class, queue::cycle);
		assertSame(failure.getCause(), fixture.latestAttempt("A").startFailure);
		assertTrue(fixture.latestAttempt("A").held);
		expectThrows(IllegalStateException.class, queue::jobs);
		assertSame(expectThrows(IOException.class, queue::cycle), failure);
		assertSame(expectThrows(IOException.class, queue::cycle), failure);
		assertEquals(fixture.latestAttempt("A").starts, 1);
		assertEquals(fixture.owners.size(), 1);
		assertEquals(fixture.latestAttempt("A").polls, 2);
		queue.close();
		queue.close();
		assertFalse(fixture.latestAttempt("A").held);
		assertEquals(fixture.latestAttempt("A").closes, 1);
		assertEquals(queue.completions(), 0);
		Fixture recovered = new Fixture(1);
		recovered.failStart = true;

		try (var reconciledQueue = recovered.queue())
		{
			reconciledQueue.submit("A", 10L);
			expectThrows(IOException.class, reconciledQueue::cycle);
			recovered.latestAttempt("A").finish(false, 7);
			assertEquals(reconciledQueue.cycle().control(), Policy.Control.WAIT);
			assertEquals(reconciledQueue.completions(), 1);
			assertTrue(reconciledQueue.jobs().isEmpty());
		}
	}

	/**
	 * Keeps an uncertain continuation from repeating or allowing another control until accepted completion and cleanup.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void failedResumeCannotRepeatOrIssueAnotherControl() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);

			queue.cycle();
			queue.allocationBlocked("A", 0, 4);
			queue.submit("B", 10L);
			var actionA = fixture.latestAttempt("A");
			actionA.resumeFailure = new IOException("partial continuation");
			var failure = expectThrows(IOException.class, queue::cycle);
			assertSame(failure.getCause(), actionA.resumeFailure);
			expectThrows(IOException.class, queue::cycle);
			expectThrows(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 5));
			expectThrows(IllegalStateException.class, () -> queue.submit("C", 1L));
			assertEquals(actionA.resumes, 1);
			assertEquals(actionA.terminates, 0);
			assertEquals(fixture.owners.size(), 1);
			actionA.finish(false, 0);

			assertEquals(queue.cycle().jobName(), "B");
			assertEquals(actionA.closes, 1);
		}
	}

	/**
	 * Preserves the selected retry threshold after a partial termination failure without repeating the request.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void failedTerminateKeepsSelectionThresholdAndBarrier() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);

			queue.cycle();

			queue.cycle();
			var actionB = fixture.latestAttempt("B");
			actionB.terminateFailure = new IOException("partial termination");
			fixture.pressure();
			var failure = expectThrows(IOException.class, queue::cycle);
			assertSame(failure.getCause(), actionB.terminateFailure);
			fixture.calm();
			fixture.latestAttempt("A").finish(false, 0);
			assertSame(expectThrows(IOException.class, queue::cycle), failure);
			assertEquals(queue.completions(), 1);
			assertEquals(actionB.terminates, 1);
			actionB.finish(true, 137);

			assertEquals(queue.cycle().jobName(), "B");
			assertEquals(fixture.latestAttempt("B").sequence, 2);
			assertEquals(actionB.closes, 1);
		}
	}

	/**
	 * Retains an earlier accepted result when a later poll fails, deferring accounting and cleanup without polling it again.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void pollFailurePreservesAcceptedOutcomesAndPreventsControls() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.submit("C", 10L);

			queue.cycle();

			queue.cycle();
			var actionA = fixture.latestAttempt("A");
			var actionB = fixture.latestAttempt("B");
			actionA.finish(false, 7);
			actionB.pollFailure = new IOException("whole-job read unavailable");
			var failure = expectThrows(IOException.class, queue::cycle);
			assertSame(failure.getCause(), actionB.pollFailure);
			assertEquals(actionA.closes, 0);
			assertEquals(fixture.owners.size(), 2);
			expectThrows(IllegalStateException.class, queue::jobs);
			assertSame(queue.completedAttempts().getFirst().outcome(), actionA.outcome);
			assertEquals(queue.completions(), 0);
			int polls = actionA.polls;
			actionB.pollFailure = null;

			assertEquals(queue.cycle().jobName(), "C");
			assertEquals(actionA.polls, polls);
			assertEquals(actionA.closes, 1);
			assertEquals(queue.completions(), 1);
			assertEquals(queue.completedAttempts().size(), 1);
		}
	}

	/**
	 * Retains accepted results and the original cleanup failure permanently, never retrying failed native close.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void failedCleanupNeverAllowsRetryOrFalseSuccess() throws IOException
	{
		Fixture fixture = new Fixture(2);
		var queue = fixture.queue();
		queue.submit("A", 10L);
		queue.submit("B", 10L);
		queue.cycle();
		queue.cycle();
		var actionA = fixture.latestAttempt("A");
		var actionB = fixture.latestAttempt("B");
		actionB.finish(true, 137);
		actionB.closeFailure = new IOException("owner cleanup incomplete");
		var failure = expectThrows(IOException.class, queue::cycle);
		assertSame(failure.getCause(), actionB.closeFailure);
		assertSame(queue.completedAttempts().getFirst().outcome(), actionB.outcome);
		expectThrows(IllegalStateException.class, queue::jobs);
		expectThrows(IOException.class, queue::cycle);
		assertEquals(actionB.closes, 1);
		assertEquals(fixture.owners.size(), 2);
		var closeFailure = expectThrows(IOException.class, queue::close);
		assertSame(closeFailure.getCause(), actionB.closeFailure);
		assertEquals(actionA.closes, 1);
		assertEquals(actionB.closes, 1);
		actionB.closeFailure = null;
		assertSame(expectThrows(IOException.class, queue::close), closeFailure);
		assertEquals(actionB.closes, 1);
		assertEquals(queue.completions(), 0);
	}

	/**
	 * Closes all retained owners once, aggregates contextual failures and rejects scheduling without inventing results.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void closeAggregatesFailuresAndNeverInventsCompletion() throws IOException
	{
		Fixture fixture = new Fixture(3);
		var queue = fixture.queue();
		for (String name : List.of("A", "B", "C"))
		{
			queue.submit(name, 10L);

			queue.cycle();
		}
		var actionA = fixture.latestAttempt("A");
		var actionB = fixture.latestAttempt("B");
		var actionC = fixture.latestAttempt("C");
		actionA.closeFailure = new IOException("A cleanup");
		actionC.closeFailure = new IOException("C cleanup");
		var failure = expectThrows(IOException.class, queue::close);
		assertSame(failure.getCause(), actionA.closeFailure);
		assertEquals(failure.getSuppressed().length, 1);
		assertSame(failure.getSuppressed()[0].getCause(), actionC.closeFailure);
		assertTrue(failure.getMessage().contains("A attempt 0"));
		assertTrue(failure.getSuppressed()[0].getMessage().contains("C attempt 2"));
		assertEquals(List.of(actionA.closes, actionB.closes, actionC.closes), List.of(1, 1, 1));
		assertEquals(queue.completions(), 0);
		assertTrue(queue.completedAttempts().isEmpty());
		expectThrows(IllegalStateException.class, queue::cycle);
		expectThrows(IllegalStateException.class, queue::jobs);
		expectThrows(IllegalStateException.class, () -> queue.submit("D", 1L));
		expectThrows(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 0));
		assertSame(expectThrows(IOException.class, queue::close), failure);
		assertEquals(List.of(actionA.closes, actionB.closes, actionC.closes), List.of(1, 1, 1));
	}

	/**
	 * Preserves ownership and order across failed host and resident sampling through the actual Controller.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void failedSamplingPreservesQueueOwnershipAndOrder() throws IOException
	{
		Fixture fixture = new Fixture(2);

		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.submit("C", 10L);
			var ready = queue.jobs();
			fixture.hostFailure = new IOException("host unavailable");
			assertSame(expectThrows(IOException.class, queue::cycle), fixture.hostFailure);
			assertEquals(queue.jobs(), ready);
			assertTrue(fixture.owners.isEmpty());
			fixture.hostFailure = null;

			queue.cycle();
			var running = queue.jobs();
			fixture.residentFailure = new IOException("resident unavailable");
			assertSame(expectThrows(IOException.class, queue::cycle), fixture.residentFailure);
			assertEquals(queue.jobs(), running);
			assertEquals(fixture.owners.size(), 1);
			fixture.residentFailure = null;

			assertEquals(queue.cycle().jobName(), "B");
			assertEquals(queue.jobs().get(1).position(), 1);
		}
	}

	/**
	 * Rejects invalid queue inputs before effects and returns immutable snapshots that survive later mutations.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void validatesInputsBeforeCollaboratorEffects() throws IOException
	{
		Fixture fixture = new Fixture(1);
		var budget = new Policy.Budget(0, 1);
		JobQueue.AttemptFactory<Result> factory = (name, sequence) -> {
			throw new AssertionError("Invalid construction must not call factory");
		};
		expectThrows(NullPointerException.class, () -> new JobQueue<>(null, fixture, factory));
		expectThrows(NullPointerException.class, () -> new JobQueue<>(budget, null, factory));
		expectThrows(NullPointerException.class, () -> new JobQueue<>(budget, fixture, null));
		expectThrows(NullPointerException.class, () -> new JobQueue.Outcome<Result>(null, false));
		var outcome = new JobQueue.Outcome<>(new Result(0, List.of()), false);
		expectThrows(NullPointerException.class, () -> new JobQueue.Completion<Result>(null, 0, outcome));
		expectThrows(IllegalArgumentException.class, () -> new JobQueue.Completion<>("", 0, outcome));
		expectThrows(IllegalArgumentException.class, () -> new JobQueue.Completion<>("A", -1, outcome));
		expectThrows(NullPointerException.class, () -> new JobQueue.Completion<Result>("A", 0, null));

		try (var queue = fixture.queue())
		{
			expectThrows(NullPointerException.class, () -> queue.submit(null, 0L));
			expectThrows(IllegalArgumentException.class, () -> queue.submit("", 0L));
			expectThrows(IllegalArgumentException.class, () -> queue.submit("A", -1L));
			queue.submit("A", null);
			var ready = queue.jobs();
			expectThrows(IllegalArgumentException.class, () -> queue.submit("A", 1L));
			expectThrows(NullPointerException.class, () -> queue.allocationBlocked(null, 0, 0));
			for (String name : List.of("", "unknown"))
				expectThrows(IllegalArgumentException.class, () -> queue.allocationBlocked(name, 0, 0));
			expectThrows(IllegalArgumentException.class, () -> queue.allocationBlocked("A", -1, 0));
			expectThrows(IllegalArgumentException.class, () -> queue.allocationBlocked("A", 0, -1));
			assertFalse(queue.allocationBlocked("A", 0, 0));
			assertTrue(fixture.owners.isEmpty());
			assertEquals(ready.getFirst().position(), 0);
			expectThrows(UnsupportedOperationException.class, () -> ready.clear());

			queue.cycle();
			assertEquals(ready.getFirst().state(), Policy.State.READY);
			fixture.latestAttempt("A").finish(false, 0);

			queue.cycle();
			assertFalse(queue.allocationBlocked("A", 0, 1));
			expectThrows(IllegalArgumentException.class, () -> queue.submit("A", 1L));
			var completed = queue.completedAttempts();
			expectThrows(UnsupportedOperationException.class, () -> completed.clear());
			assertEquals(completed.size(), 1);
		}
	}

	/**
	 * Latches a null or throwing factory as a contract violation and never repeats creation.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "failures")
	public void rejectsBrokenFactoryWithoutRepeatingCreation() throws IOException
	{
		for (boolean returnsNull : List.of(true, false))
		{
			Fixture fixture = new Fixture(1);
			int[] calls = {
				0
			};
			var original = new IllegalArgumentException("factory violated resource-free contract");
			var queue = new JobQueue<Result>(new Policy.Budget(0, 1), fixture, (name, sequence) -> {
				calls[0]++;
				if (returnsNull)
					return null;
				throw original;
			});
			queue.submit("A", 10L);
			var failure = expectThrows(IllegalStateException.class, queue::cycle);
			assertTrue(failure.getMessage().contains("A attempt 0"));
			if (returnsNull == false)
				assertSame(failure.getCause(), original);
			assertSame(expectThrows(IllegalStateException.class, queue::cycle), failure);
			expectThrows(IllegalStateException.class, queue::jobs);
			assertEquals(calls[0], 1);
			queue.close();
			assertTrue(queue.completedAttempts().isEmpty());
		}
	}

	/**
	 * Carries the original root status and additional statuses whose preservation the queue must prove.
	 *
	 * @param rootStatus scripted root status, including nonzero natural completion
	 * @param descendantStatuses scripted additional accepted native-result metadata
	 */
	private record Result(int rootStatus, List<Integer> descendantStatuses)
	{
	}

	/**
	 * Mutable observations are controlled inputs to the real Controller, not native memory claims.
	 */
	private static final class Fixture implements Controller.Measurements
	{

		/**
		 * Recorded queue requests in issue order.
		 */
		private final List<String> events = new ArrayList<>();

		/**
		 * Owners returned resource-free to the queue, retained here for request observations.
		 */
		private final List<Owner> owners = new ArrayList<>();

		/** Controlled concurrency bound supplied to the actual Policy budget. */
		private final int slots;

		/**
		 * Controlled fresh host observation for the next Controller sample.
		 */
		private Controller.Memory memory = new Controller.Memory(1000, 0, false);

		/** Controlled host/tree read failures, or null for successful sampling. */
		private IOException hostFailure = null, residentFailure = null;

		/** Whether newly configured owners throw after marking their controlled resource acquired. */
		private boolean failStart = false;

		/** Creates isolated measurement and owner fixtures with the supplied positive live-slot bound.
		 * @param slots maximum simultaneous live attempts
		 */
		Fixture(int slots)
		{
			this.slots = slots;
		}

		/** Creates the real queue with controlled inputs and a resource-free owner factory.
		 * @return a new queue owned by the test
		 */
		JobQueue<Result> queue()
		{
			return new JobQueue<>(new Policy.Budget(0, slots), this, (name, sequence) -> {
				events.add("create:" + name + ":" + sequence);
				var owner = new Owner(name, sequence, events);
				if (failStart)
					owner.startFailure = new IOException("start acquired then failed");
				owners.add(owner);
				return owner;
			});
		}

		/** Finds the retained owner selected for one action and queue-wide attempt ordinal.
		 * @param name action identity
		 * @param sequence attempt ordinal
		 * @return the owner created by the queue
		 */
		Owner attempt(String name, long sequence)
		{
			return owners.stream().filter(owner -> owner.name.equals(name) && owner.sequence == sequence).findFirst().orElseThrow();
		}

		/** Finds the most recently created owner for an action.
		 * @param name action identity
		 * @return the latest owner selected by the queue
		 */
		Owner latestAttempt(String name)
		{
			return owners.stream().filter(owner -> owner.name.equals(name)).toList().getLast();
		}

		@Override
		public Controller.Memory readHost() throws IOException
		{
			if (hostFailure != null)throw hostFailure;
			return memory;
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			if (residentFailure != null)throw residentFailure;
			return 10;
		}

		/**
		 * Supplies swap growth with pressure to the actual Controller on its next read.
		 */
		void pressure()
		{
			memory = new Controller.Memory(1000, memory.swapUsedBytes() + 1, true);
		}

		/**
		 * Clears scripted pressure while keeping the measured swap baseline unchanged.
		 */
		void calm()
		{
			memory = new Controller.Memory(1000, memory.swapUsedBytes(), false);
		}
	}

	/**
	 * Supplies only the accepted Attempt contract: requests, optional whole-job outcomes and partial-effect failures.
	 */
	private static final class Owner implements JobQueue.Attempt<Result>
	{

		/** Stable action identity supplied by the queue. */
		private final String name;

		/** Attempt ordinal supplied by the queue. */
		private final long sequence;

		/** Recorded queue requests in issue order. */
		private final List<String> events;

		/** Accepted whole-job input, or null while no whole-job result is available. */
		private JobQueue.Outcome<Result> outcome = null;
		IOException startFailure = null, resumeFailure = null, terminateFailure = null, pollFailure = null, closeFailure = null;

		/** Counts issued start, resume, terminate, poll and cleanup requests. */
		private int starts = 0, resumes = 0, terminates = 0, polls = 0, closes = 0;

		/** Close-tracked in-process resource state; no operating-system resource is simulated. */
		private boolean held = false;

		/** Configures a resource-free controlled owner using only the documented Attempt contract.
		 * @param name action identity
		 * @param sequence queue-assigned attempt ordinal
		 * @param events mutable request history owned by this test
		 */
		Owner(String name, long sequence, List<String> events)
		{
			this.name = name;
			this.sequence = sequence;
			this.events = events;
		}

		@Override
		public void start() throws IOException
		{
			starts++;
			held = true;
			events.add("start:" + name + ":" + sequence);
			if (startFailure != null)throw startFailure;
		}

		@Override
		public void resume(long waitSequence) throws IOException
		{
			resumes++;
			events.add("resume:" + name + ":" + waitSequence);
			if (resumeFailure != null)throw resumeFailure;
		}

		@Override
		public void terminate() throws IOException
		{
			terminates++;
			events.add("terminate:" + name);
			if (terminateFailure != null)throw terminateFailure;
		}

		@Override
		public Optional<JobQueue.Outcome<Result>> poll() throws IOException
		{
			polls++;
			if (pollFailure != null)throw pollFailure;
			return Optional.ofNullable(outcome);
		}

		@Override
		public void close() throws IOException
		{
			closes++;
			held = false;
			events.add("close:" + name + ":" + sequence);
			if (closeFailure != null)throw closeFailure;
		}

		/** Supplies accepted whole-job input; queue classification and accounting are the asserted decisions.
		 * @param aborted native-contract abortion classification supplied to the queue
		 * @param status original root status, independent of the classification
		 */
		void finish(boolean aborted, int status)
		{
			outcome = new JobQueue.Outcome<>(new Result(status, List.of(19, 23)), aborted);
		}
	}
}
