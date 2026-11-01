package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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

			requireThat(queue.cycle().control(), "queue.cycle().control()")
				.withContext("Earliest submitted action must start", "diagnostic")
				.isEqualTo(Policy.Control.START);
			requireThat(fixture.events, "fixture.events").isEqualTo(List.of("create:A:0", "start:A:0"));
			requireThat(queue.jobs().getFirst().startedAt(), "queue.jobs().getFirst().startedAt()")
				.isEqualTo(Long.valueOf(0));

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
		}
		requireThat(fixture.attempt("A", 0).closes, "fixture").isEqualTo(1);
		Fixture failing = new Fixture(1);
		failing.failStart = true;
		var queue = failing.queue();
		queue.submit("A", 10L);
		requireException(IOException.class, queue::cycle);
		queue.close();
		requireThat(failing.attempt("A", 0).closes, "failing")
			.withContext("Attempt must be retained before fallible start", "diagnostic")
			.isEqualTo(1);
	}

	/**
	 * Waits for capacity while a peer is running, then resumes the oldest denial without another slot.
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
			requireThat(queue.allocationBlocked("B", 1, 40), "queue").isTrue();
			var before = queue.jobs();
			requireThat(before.get(1).blockedAt(), "before.get(1).blockedAt()").isEqualTo(Long.valueOf(0));
			fixture.memory = new Controller.Memory(0, 0, false);

			requireThat(queue.cycle().control(), "unaffordableDenialWithRunningPeer").isEqualTo(Policy.Control.WAIT);
			requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(2);
			requireThat(fixture.latestAttempt("A").resumes, "fixture").isEqualTo(0);
			fixture.calm();
			requireThat(queue.allocationBlocked("A", 0, 90), "laterPeerDenial").isTrue();
			var decision = queue.cycle();
			requireThat(decision.control(), "decision.control()").isEqualTo(Policy.Control.RESUME);
			requireThat(decision.jobName(), "decision.jobName()").isEqualTo("B");
			requireThat(queue.jobs().get(1).startedAt(), "queue.jobs().get(1).startedAt()")
				.isEqualTo(before.get(1).startedAt());
			requireThat(queue.jobs().get(1).position(), "queue.jobs().get(1).position()").isEqualTo(1);
			requireThat(fixture.latestAttempt("B").resumes, "fixture").isEqualTo(1);
			requireThat(fixture.owners.size(), "fixture.owners.size()")
				.withContext("Resume retains its live slot and owner", "diagnostic")
				.isEqualTo(2);
		}
	}

	/**
	 * Authorizes termination instead of a grant for an attempt that cannot continue its denied allocation, then
	 * retains original position and permits only the existing single completion-free retry after accepted cleanup.
	 *
	 * @throws IOException if a valid controlled lifecycle is rejected
	 */
	@Test(groups = "waits")
	public void retriesNoncontinuableDenialOnlyAfterAcceptedTermination() throws IOException
	{
		Fixture fixture = new Fixture(1);
		try (JobQueue<Result> queue = fixture.queue())
		{
			queue.submit("A", 20L);
			queue.submit("B", 20L);
			queue.cycle();
			Owner original = fixture.latestAttempt("A");
			original.retryAfterDenial = true;
			queue.allocationBlocked("A", 0, 4);

			requireThat(queue.cycle().control(), "issuedControl").isEqualTo(Policy.Control.TERMINATE);
			requireThat(original.resumes, "inPlaceGrants").isEqualTo(0);
			requireThat(original.terminates, "authorizedTerminations").isEqualTo(1);
			requireThat(queue.jobs().getFirst().state(), "awaitingOriginalOwner").isEqualTo(Policy.State.STOPPING);
			requireThat(queue.cycle().control(), "uncollectedAttemptRetainsSlot").isEqualTo(Policy.Control.WAIT);
			requireThat(fixture.owners.size(), "attemptsBeforeCollection").isEqualTo(1);

			original.finish(true, 137);
			Policy.Decision retry = queue.cycle();
			requireThat(retry.control(), "freshAttemptControl").isEqualTo(Policy.Control.START);
			requireThat(retry.jobName(), "originalActionRetainsPriority").isEqualTo("A");
			requireThat(original.closes, "cleanupBeforeFreshAttempt").isEqualTo(1);
			requireThat(queue.jobs().getFirst().position(), "originalPosition").isEqualTo(0);
			requireThat(queue.jobs().getFirst().retryAfter(), "selectedCompletionThreshold").isEqualTo(1);
			requireThat(queue.completions(), "abortionIsNotCompletion").isEqualTo(0);

			Owner second = fixture.latestAttempt("A");
			second.retryAfterDenial = true;
			queue.allocationBlocked("A", 1, 0);
			requireThat(queue.cycle().control(), "secondDenialControl").isEqualTo(Policy.Control.TERMINATE);
			second.finish(true, 137);
			requireException(IOException.class, queue::cycle);
			requireThat(fixture.owners.size(), "noUnboundedCompletionFreeRetry").isEqualTo(2);
			requireThat(second.resumes, "secondInPlaceGrants").isEqualTo(0);
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
			requireThat(queue.allocationBlocked("A", 0, 5), "queue").isTrue();
			var blocked = queue.jobs();
			requireThat(queue.allocationBlocked("A", 0, 5), "queue").isFalse();
			requireThat(queue.allocationBlocked("A", 0, 4), "queue").isFalse();
			requireException(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 6));
			requireThat(queue.jobs(), "queue.jobs()").isEqualTo(blocked);

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.RESUME);
			requireThat(queue.allocationBlocked("A", 0, 5), "queue").isFalse();
			requireThat(queue.allocationBlocked("A", 0, 1), "queue").isFalse();
			requireThat(queue.allocationBlocked("A", 99, 6), "queue").isFalse();
			requireThat(queue.jobs().getFirst().state(), "queue.jobs().getFirst().state()").isEqualTo(Policy.State.RUNNING);
			requireThat(queue.allocationBlocked("A", 0, 6), "queue").isTrue();
			requireThat(queue.jobs().getFirst().blockedAt(), "queue.jobs().getFirst().blockedAt()")
				.isEqualTo(Long.valueOf(1));
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

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("A");
			requireThat(fixture.attempt("A", 0).terminates, "fixture").isEqualTo(1);
			fixture.attempt("A", 0).finish(true, 137);
			fixture.attempt("B", 1).finish(false, 0);
			fixture.calm();

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("A");
			requireThat(queue.jobs().getFirst().position(), "queue.jobs().getFirst().position()").isEqualTo(0);
			requireThat(queue.jobs().getFirst().startedAt(), "queue.jobs().getFirst().startedAt()")
				.isEqualTo(Long.valueOf(2));
			requireThat(fixture.attempt("A", 0).closes, "fixture").isEqualTo(1);
			requireThat(queue.allocationBlocked("A", 0, 99), "queue").isFalse();
			requireThat(queue.allocationBlocked("A", 2, 0), "queue").isTrue();
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

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("D");
			fixture.latestAttempt("A").finish(true, 9);
			fixture.latestAttempt("B").finish(false, 0);
			fixture.latestAttempt("D").finish(true, 137);
			fixture.calm();

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
			requireThat(queue.jobs().getFirst().retryAfter(), "queue.jobs().getFirst().retryAfter()").isEqualTo(2);
			requireThat(queue.jobs().get(2).retryAfter(), "queue.jobs().get(2).retryAfter()").isEqualTo(1);
			requireThat(fixture.owners.size(), "fixture.owners.size()")
				.withContext("Older ineligible A must block eligible D and READY work", "diagnostic")
				.isEqualTo(4);
			fixture.latestAttempt("C").finish(false, 0);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("A");
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

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.TERMINATE);
			fixture.calm();
			// Empty input models the native owner withholding whole-job completion while descendants remain.

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
			requireThat(queue.jobs().get(1).state(), "queue.jobs().get(1).state()").isEqualTo(Policy.State.STOPPING);
			requireThat(queue.allocationBlocked("B", 1, 0), "queue").isFalse();
			requireThat(fixture.latestAttempt("B").terminates, "fixture").isEqualTo(1);
			requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(2);
			fixture.latestAttempt("B").finish(true, 137);

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
			requireThat(fixture.attempt("B", 1).closes, "fixture").isEqualTo(1);
			requireThat(queue.jobs().get(1).state(), "queue.jobs().get(1).state()").isEqualTo(Policy.State.RETRY);
			fixture.latestAttempt("A").finish(false, 0);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("B");
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

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
			requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
			requireThat(queue.jobs().getFirst().state(), "queue.jobs().getFirst().state()").isEqualTo(Policy.State.STOPPING);
			fixture.latestAttempt("B").finish(true, 137);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("B");
			requireThat(fixture.latestAttempt("B").sequence, "fixture").isEqualTo(2);
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

			requireThat(queue.cycle().control(), "queue.cycle().control()").isEqualTo(Policy.Control.WAIT);
			requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
			requireThat(queue.jobs().getFirst().retryAfter(), "queue.jobs().getFirst().retryAfter()")
				.withContext("Same-batch natural completion precedes unexpected-abort baseline", "diagnostic")
				.isEqualTo(2);
			fixture.latestAttempt("C").finish(false, 0);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("A");
			requireThat(queue.completions(), "queue.completions()").isEqualTo(2);
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
		requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
		requireThat(queue.jobs().isEmpty(), "queue.jobs().isEmpty()").isTrue();
		requireThat(queue.completedAttempts().size(), "queue.completedAttempts().size()").isEqualTo(1);
		requireThat(queue.completedAttempts().getFirst().outcome().result(), "queue")
			.isReferenceEqualTo(result, "expected");
		requireThat(fixture.latestAttempt("A").polls, "fixture").isEqualTo(1);
		var saved = queue.completedAttempts();
		queue.close();
		requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
		requireThat(queue.completedAttempts(), "queue.completedAttempts()").isEqualTo(saved);
		requireThat(queue.completedAttempts().getFirst().outcome(), "queue.completedAttempts().getFirst().outcome()")
			.isReferenceEqualTo(outcome, "expected");
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
				requireThat(queue.completedAttempts().getFirst().outcome(), "queue.completedAttempts().getFirst().outcome()")
					.isReferenceEqualTo(original, "expected");
				requireThat(queue.completions(), "queue.completions()").isEqualTo(aborted?0L : 1L);
				requireThat(queue.jobs().stream().anyMatch(j -> j.name().equals("B")), "queue").isEqualTo(aborted);
				if (aborted)requireThat(queue.jobs().get(1).state(), "queue.jobs().get(1).state()")
					.isEqualTo(Policy.State.RETRY);
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
		var failure = requireException(IOException.class, queue::cycle);
		requireThat(failure.getCause(), "failure.getCause()")
			.isReferenceEqualTo(fixture.latestAttempt("A").startFailure, "expected");
		requireThat(fixture.latestAttempt("A").held, "fixture").isTrue();
		requireException(IllegalStateException.class, queue::jobs);
		requireThat(requireException(IOException.class, queue::cycle), "requireException")
			.isReferenceEqualTo(failure, "expected");
		requireThat(requireException(IOException.class, queue::cycle), "requireException")
			.isReferenceEqualTo(failure, "expected");
		requireThat(fixture.latestAttempt("A").starts, "fixture").isEqualTo(1);
		requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(1);
		requireThat(fixture.latestAttempt("A").polls, "fixture").isEqualTo(2);
		queue.close();
		queue.close();
		requireThat(fixture.latestAttempt("A").held, "fixture").isFalse();
		requireThat(fixture.latestAttempt("A").closes, "fixture").isEqualTo(1);
		requireThat(queue.completions(), "queue.completions()").isEqualTo(0);
		Fixture recovered = new Fixture(1);
		recovered.failStart = true;

		try (var reconciledQueue = recovered.queue())
		{
			reconciledQueue.submit("A", 10L);
			requireException(IOException.class, reconciledQueue::cycle);
			recovered.latestAttempt("A").finish(false, 7);
			requireThat(reconciledQueue.cycle().control(), "reconciledQueue.cycle().control()")
				.isEqualTo(Policy.Control.WAIT);
			requireThat(reconciledQueue.completions(), "reconciledQueue.completions()").isEqualTo(1);
			requireThat(reconciledQueue.jobs().isEmpty(), "reconciledQueue.jobs().isEmpty()").isTrue();
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
			var failure = requireException(IOException.class, queue::cycle);
			requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(actionA.resumeFailure, "expected");
			requireException(IOException.class, queue::cycle);
			requireException(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 5));
			requireException(IllegalStateException.class, () -> queue.submit("C", 1L));
			requireThat(actionA.resumes, "actionA.resumes").isEqualTo(1);
			requireThat(actionA.terminates, "actionA.terminates").isEqualTo(0);
			requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(1);
			actionA.finish(false, 0);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("B");
			requireThat(actionA.closes, "actionA.closes").isEqualTo(1);
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
			var failure = requireException(IOException.class, queue::cycle);
			requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(actionB.terminateFailure, "expected");
			fixture.calm();
			fixture.latestAttempt("A").finish(false, 0);
			requireThat(requireException(IOException.class, queue::cycle), "requireException")
				.isReferenceEqualTo(failure, "expected");
			requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
			requireThat(actionB.terminates, "actionB.terminates").isEqualTo(1);
			actionB.finish(true, 137);

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("B");
			requireThat(fixture.latestAttempt("B").sequence, "fixture").isEqualTo(2);
			requireThat(actionB.closes, "actionB.closes").isEqualTo(1);
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
			var failure = requireException(IOException.class, queue::cycle);
			requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(actionB.pollFailure, "expected");
			requireThat(actionA.closes, "actionA.closes").isEqualTo(0);
			requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(2);
			requireException(IllegalStateException.class, queue::jobs);
			requireThat(queue.completedAttempts().getFirst().outcome(), "queue.completedAttempts().getFirst().outcome()")
				.isReferenceEqualTo(actionA.outcome, "expected");
			requireThat(queue.completions(), "queue.completions()").isEqualTo(0);
			int polls = actionA.polls;
			actionB.pollFailure = null;

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("C");
			requireThat(actionA.polls, "actionA.polls").isEqualTo(polls);
			requireThat(actionA.closes, "actionA.closes").isEqualTo(1);
			requireThat(queue.completions(), "queue.completions()").isEqualTo(1);
			requireThat(queue.completedAttempts().size(), "queue.completedAttempts().size()").isEqualTo(1);
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
		var failure = requireException(IOException.class, queue::cycle);
		requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(actionB.closeFailure, "expected");
		requireThat(queue.completedAttempts().getFirst().outcome(), "queue.completedAttempts().getFirst().outcome()")
			.isReferenceEqualTo(actionB.outcome, "expected");
		requireException(IllegalStateException.class, queue::jobs);
		requireException(IOException.class, queue::cycle);
		requireThat(actionB.closes, "actionB.closes").isEqualTo(1);
		requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(2);
		var closeFailure = requireException(IOException.class, queue::close);
		requireThat(closeFailure.getCause(), "closeFailure.getCause()")
			.isReferenceEqualTo(actionB.closeFailure, "expected");
		requireThat(actionA.closes, "actionA.closes").isEqualTo(1);
		requireThat(actionB.closes, "actionB.closes").isEqualTo(1);
		actionB.closeFailure = null;
		requireThat(requireException(IOException.class, queue::close).getCause(), "requireException")
			.isReferenceEqualTo(closeFailure, "expected");
		requireThat(actionB.closes, "actionB.closes").isEqualTo(1);
		requireThat(queue.completions(), "queue.completions()").isEqualTo(0);
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
		var failure = requireException(IOException.class, queue::close);
		requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(actionA.closeFailure, "expected");
		requireThat(failure.getSuppressed().length, "failure.getSuppressed().length").isEqualTo(1);
		requireThat(failure.getSuppressed()[0].getCause(), "failure.getSuppressed()[0].getCause()")
			.isReferenceEqualTo(actionC.closeFailure, "expected");
		requireThat(failure.getMessage().contains("A attempt 0"), "failure").isTrue();
		requireThat(failure.getSuppressed()[0].getMessage().contains("C attempt 2"), "failure").isTrue();
		requireThat(List.of(actionA.closes, actionB.closes, actionC.closes), "List").isEqualTo(List.of(1, 1, 1));
		requireThat(queue.completions(), "queue.completions()").isEqualTo(0);
		requireThat(queue.completedAttempts().isEmpty(), "queue.completedAttempts().isEmpty()").isTrue();
		requireException(IllegalStateException.class, queue::cycle);
		requireException(IllegalStateException.class, queue::jobs);
		requireException(IllegalStateException.class, () -> queue.submit("D", 1L));
		requireException(IllegalStateException.class, () -> queue.allocationBlocked("A", 0, 0));
		requireThat(requireException(IOException.class, queue::close).getCause(), "requireException")
			.isReferenceEqualTo(failure, "expected");
		requireThat(List.of(actionA.closes, actionB.closes, actionC.closes), "List").isEqualTo(List.of(1, 1, 1));
	}

	/**
	 * Reports an explicit cleanup failure through automatic closure without retrying the owner or suppressing itself.
	 * The in-process owner supplies only the documented failed-close input; the real queue owns error reporting.
	 *
	 * @throws IOException if starting the controlled action fails
	 */
	@Test(groups = "failures")
	public void explicitFailedCloseInsideTryWithResourcesPreservesCleanupFailure() throws IOException
	{
		Fixture fixture = new Fixture(1);
		var queue = fixture.queue();
		queue.submit("A", 10L);
		queue.cycle();
		var owner = fixture.latestAttempt("A");
		owner.closeFailure = new IOException("owner cleanup incomplete");

		var failure = requireException(IOException.class, () ->
		{
			try (queue)
			{
				queue.close();
			}
		});

		requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(owner.closeFailure, "expected");
		requireThat(failure.getMessage().contains("A attempt 0"), "failure").isTrue();
		requireThat(failure.getSuppressed().length, "failure.getSuppressed().length").isEqualTo(1);
		requireThat(failure.getSuppressed()[0].getCause(), "failure.getSuppressed()[0].getCause()")
			.isReferenceEqualTo(failure, "expected");
		requireThat(owner.closes, "owner.closes").isEqualTo(1);
		requireThat(queue.completions(), "queue.completions()").isEqualTo(0);
		requireThat(queue.completedAttempts().isEmpty(), "queue.completedAttempts().isEmpty()").isTrue();
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
			requireThat(requireException(IOException.class, queue::cycle), "requireException")
				.isReferenceEqualTo(fixture.hostFailure, "expected");
			requireThat(queue.jobs(), "queue.jobs()").isEqualTo(ready);
			requireThat(fixture.owners.isEmpty(), "fixture.owners.isEmpty()").isTrue();
			fixture.hostFailure = null;

			queue.cycle();
			var running = queue.jobs();
			fixture.residentFailure = new IOException("resident unavailable");
			requireThat(requireException(IOException.class, queue::cycle), "requireException")
				.isReferenceEqualTo(fixture.residentFailure, "expected");
			requireThat(queue.jobs(), "queue.jobs()").isEqualTo(running);
			requireThat(fixture.owners.size(), "fixture.owners.size()").isEqualTo(1);
			fixture.residentFailure = null;

			requireThat(queue.cycle().jobName(), "queue.cycle().jobName()").isEqualTo("B");
			requireThat(queue.jobs().get(1).position(), "queue.jobs().get(1).position()").isEqualTo(1);
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
		requireException(NullPointerException.class, () -> new JobQueue<>(null, fixture, factory));
		requireException(NullPointerException.class, () -> new JobQueue<>(budget, null, factory));
		requireException(NullPointerException.class, () -> new JobQueue<>(budget, fixture, null));
		requireException(NullPointerException.class, () -> new JobQueue.Outcome<Result>(null, false));
		var outcome = new JobQueue.Outcome<>(new Result(0, List.of()), false);
		requireException(NullPointerException.class, () -> new JobQueue.Completion<Result>(null, 0, outcome));
		requireException(IllegalArgumentException.class, () -> new JobQueue.Completion<>("", 0, outcome));
		requireException(IllegalArgumentException.class, () -> new JobQueue.Completion<>("A", -1, outcome));
		requireException(NullPointerException.class, () -> new JobQueue.Completion<Result>("A", 0, null));

		try (var queue = fixture.queue())
		{
			requireException(NullPointerException.class, () -> queue.submit(null, 0L));
			requireException(IllegalArgumentException.class, () -> queue.submit("", 0L));
			requireException(IllegalArgumentException.class, () -> queue.submit("A", -1L));
			queue.submit("A", null);
			var ready = queue.jobs();
			requireException(IllegalArgumentException.class, () -> queue.submit("A", 1L));
			requireException(NullPointerException.class, () -> queue.allocationBlocked(null, 0, 0));
			for (String name : List.of("", "unknown"))
				requireException(IllegalArgumentException.class, () -> queue.allocationBlocked(name, 0, 0));
			requireException(IllegalArgumentException.class, () -> queue.allocationBlocked("A", -1, 0));
			requireException(IllegalArgumentException.class, () -> queue.allocationBlocked("A", 0, -1));
			requireThat(queue.allocationBlocked("A", 0, 0), "queue").isFalse();
			requireThat(fixture.owners.isEmpty(), "fixture.owners.isEmpty()").isTrue();
			requireThat(ready.getFirst().position(), "ready.getFirst().position()").isEqualTo(0);
			requireException(UnsupportedOperationException.class, () -> ready.clear());

			queue.cycle();
			requireThat(ready.getFirst().state(), "ready.getFirst().state()").isEqualTo(Policy.State.READY);
			fixture.latestAttempt("A").finish(false, 0);

			queue.cycle();
			requireThat(queue.allocationBlocked("A", 0, 1), "queue").isFalse();
			requireException(IllegalArgumentException.class, () -> queue.submit("A", 1L));
			var completed = queue.completedAttempts();
			requireException(UnsupportedOperationException.class, () -> completed.clear());
			requireThat(completed.size(), "completed.size()").isEqualTo(1);
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
			var failure = requireException(IllegalStateException.class, queue::cycle);
			requireThat(failure.getMessage().contains("A attempt 0"), "failure").isTrue();
			if (returnsNull == false)
				requireThat(failure.getCause(), "failure.getCause()").isReferenceEqualTo(original, "expected");
			requireThat(requireException(IllegalStateException.class, queue::cycle), "requireException")
				.isReferenceEqualTo(failure, "expected");
			requireException(IllegalStateException.class, queue::jobs);
			requireThat(calls[0], "calls[0]").isEqualTo(1);
			queue.close();
			requireThat(queue.completedAttempts().isEmpty(), "queue.completedAttempts().isEmpty()").isTrue();
		}
	}

	/**
	 * Composes real native-wait batches with the real queue so a denial during resume gets a new global FIFO position.
	 * The owner supplies only acknowledged-continuation input; no native execution is claimed.
	 *
	 * @throws IOException if valid composed queue decisions fail
	 */
	@Test(groups = "waits")
	public void batchesNativeWaitsWithoutBypassingOlderQueueDenial() throws IOException
	{
		Fixture fixture = new Fixture(2);
		var granted = new ArrayList<String>();
		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.submit("B", 10L);
			queue.cycle();
			queue.cycle();
			var actionA = fixture.latestAttempt("A");
			var actionB = fixture.latestAttempt("B");
			actionA.allocationWaits = new AllocationWaits();
			actionB.allocationWaits = new AllocationWaits();
			actionA.continuation = wait ->
			{
				granted.add("A:" + wait.sequence());
				actionA.allocationWaits.blocked(new AllocationWaits.Wait(1, 3));
			};
			actionB.continuation = wait -> granted.add("B:" + wait.sequence());
			actionA.allocationWaits.blocked(new AllocationWaits.Wait(1, 1));
			requireThat(queue.allocationBlocked("A", actionA.sequence,
				actionA.allocationWaits.poll().orElseThrow()), "acceptedWait").isTrue();
			actionA.allocationWaits.blocked(new AllocationWaits.Wait(1, 2));
			requireThat(actionA.allocationWaits.poll(), "queueWait").isEqualTo(java.util.OptionalLong.empty());
			actionB.allocationWaits.blocked(new AllocationWaits.Wait(1, 1));
			requireThat(queue.allocationBlocked("B", actionB.sequence,
				actionB.allocationWaits.poll().orElseThrow()), "acceptedWait").isTrue();

			requireThat(queue.cycle().jobName(), "selectedAction").isEqualTo("A", "expected");
			requireThat(queue.jobs().getFirst().state(), "actionState").isEqualTo(Policy.State.RUNNING, "expected");
			requireThat(granted, "granted").isEqualTo(List.of("A:1", "A:2"), "expected");
			requireThat(queue.allocationBlocked("A", actionA.sequence,
				actionA.allocationWaits.poll().orElseThrow()), "acceptedWait").isTrue();
			requireThat(queue.cycle().jobName(), "selectedAction").isEqualTo("B", "expected");
			requireThat(queue.cycle().jobName(), "selectedAction").isEqualTo("A", "expected");
			requireThat(granted, "granted").isEqualTo(List.of("A:1", "A:2", "B:1", "A:3"), "expected");
			requireThat(actionA.allocationWaits.poll(), "queueWait").isEqualTo(java.util.OptionalLong.empty());
			requireThat(queue.completions(), "completions").isEqualTo(0, "expected");
		}
	}

	/**
	 * Retains queue ownership after the second native grant fails and issues no additional grant before final close.
	 *
	 * @throws IOException if preparing valid queue state fails
	 */
	@Test(groups = "failures")
	public void nativeBatchFailureStopsQueueControlsWithoutLosingOwner() throws IOException
	{
		Fixture fixture = new Fixture(1);
		var attempted = new ArrayList<Long>();
		try (var queue = fixture.queue())
		{
			queue.submit("A", 10L);
			queue.cycle();
			var owner = fixture.latestAttempt("A");
			owner.allocationWaits = new AllocationWaits();
			owner.continuation = wait ->
			{
				attempted.add(wait.sequence());
				if (wait.sequence() == 2)
					throw new IOException("Second native grant acknowledgement lost");
			};
			for (long sequence : List.of(1L, 2L, 3L))
				owner.allocationWaits.blocked(new AllocationWaits.Wait(1, sequence));
			requireThat(queue.allocationBlocked("A", owner.sequence,
				owner.allocationWaits.poll().orElseThrow()), "acceptedWait").isTrue();

			requireException(IOException.class, queue::cycle);
			requireException(IOException.class, queue::cycle);
			requireThat(attempted, "attempted").isEqualTo(List.of(1L, 2L), "expected");
			requireThat(owner.resumes, "resumeCalls").isEqualTo(1, "expected");
			requireThat(owner.closes, "closeCalls").isEqualTo(0, "expected");
			requireThat(queue.completions(), "completions").isEqualTo(0, "expected");
			requireThat(queue.completedAttempts(), "completedAttempts").isEmpty();
		}
		requireThat(fixture.latestAttempt("A").closes, "closeCalls").isEqualTo(1, "expected");
		requireThat(attempted, "attempted").isEqualTo(List.of(1L, 2L), "expected");
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
		/** Controlled partial start failure, or null for success. */
		private IOException startFailure = null;
		/** Controlled partial continuation failure, or null for success. */
		private IOException resumeFailure = null;
		/** Controlled partial termination failure, or null for success. */
		private IOException terminateFailure = null;
		/** Controlled observation failure, or null for success. */
		private IOException pollFailure = null;
		/** Controlled cleanup failure, or null for success. */
		private IOException closeFailure = null;

		/** Optional real wait collector for queue composition tests; null leaves native-wait composition unused. */
		private AllocationWaits allocationWaits = null;
		/** Borrowed acknowledgement input used only when this owner has an allocation-wait collector. */
		private AllocationWaits.Continuation continuation = null;

		/** Controlled denial capability; true models a native hold recoverable only by a fresh attempt. */
		private boolean retryAfterDenial = false;

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
			if (allocationWaits != null)
				allocationWaits.resume(waitSequence, continuation);
		}

		@Override
		public boolean requiresRetryAfterDenial()
		{
			return retryAfterDenial;
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
