package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises bounded retry decisions through documented in-memory measurements and accepted whole-job outcomes.
 * Owners model the Attempt contract; these checks do not classify native OOM or prove actual resource reclamation.
 */
public final class JobQueueCompletionFreeRetryTest
{
	/**
	 * Creates completion-free retry checks.
	 */
	public JobQueueCompletionFreeRetryTest()
	{
	}

	/**
	 * Retries a sole unknown action after accepted abortion and cleanup without fabricating a natural completion.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void retriesExclusiveUnknownActionAfterReaping() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.cycle();
			fixture.owner("unknown", 0).outcome = new JobQueue.Outcome<>(137, true);

			Policy.Decision retry = queue.cycle();

			requireThat(retry.control(), "retryControl").isEqualTo(Policy.Control.START, "expected");
			requireThat(retry.jobName(), "retryIdentity").isEqualTo("unknown", "expected");
			requireThat(queue.jobs().getFirst().startedAt(), "newAttempt").isEqualTo(1L, "expected");
			requireThat(queue.jobs().getFirst().position(), "originalPosition").isEqualTo(0L, "expected");
			requireThat(queue.jobs().getFirst().peakBytes(), "unknownPeak").isNull();
			requireThat(queue.completions(), "naturalCompletions").isEqualTo(0L, "expected");
			requireThat(fixture.owner("unknown", 0).closes, "reapedOwnerCleanup").isEqualTo(1, "expected");
		}
	}

	/**
	 * Keeps the oldest unknown retry ahead of newer work and retains unknown demand after an incomplete observation.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void retryPrecedesNewerReadyWork() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.submit("later", 1L);
			queue.submit("last", 1L);
			queue.cycle();
			fixture.residency = 90;
			requireThat(queue.cycle().control(), "exclusiveMeasurement").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.owner("unknown", 0).outcome = new JobQueue.Outcome<>(137, true);
			requireThat(queue.cycle().jobName(), "oldestRetry").isEqualTo("unknown", "expected");
			requireThat(queue.jobs().getFirst().peakBytes(), "unknownPeak").isNull();
			requireThat(queue.jobs().getFirst().position(), "originalPosition").isEqualTo(0L, "expected");
			fixture.owner("unknown", 1).outcome = new JobQueue.Outcome<>(0, false);

			requireThat(queue.cycle().jobName(), "firstNewAction").isEqualTo("later", "expected");
			requireThat(queue.cycle().jobName(), "secondNewAction").isEqualTo("last", "expected");
			requireThat(fixture.creations, "creationOrder")
				.isEqualTo(List.of("unknown:0", "unknown:1", "later:2", "last:3"), "expected");
		}
	}

	/**
	 * Stops an exhausted action before another factory call and preserves diagnostics with fresh failure wrappers.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void exhaustionIsTerminalAndRetainsAcceptedOutcomes() throws IOException
	{
		Fixture fixture = new Fixture();
		var queue = fixture.queue();
		try
		{
			queue.submit("unknown", null);
			queue.submit("later", 1L);
			queue.cycle();
			fixture.owner("unknown", 0).outcome = new JobQueue.Outcome<>(137, true);
			queue.cycle();
			requireThat(queue.jobs().getFirst().peakBytes(), "retryPeak").isNull();
			fixture.owner("unknown", 1).outcome = new JobQueue.Outcome<>(9, true);

			IOException first = requireException(IOException.class, queue::cycle);
			int hostReads = fixture.hostReads;
			int polls = fixture.polls();
			IOException second = requireException(IOException.class, queue::cycle);
			IOException third = requireException(IOException.class, queue::cycle);

			requireThat(first == second, "freshWrapper").isFalse();
			requireThat(second == third, "anotherFreshWrapper").isFalse();
			requireThat(first.getCause() == second.getCause(), "retainedCause").isTrue();
			requireThat(second.getCause() == third.getCause(), "sameRetainedCause").isTrue();
			requireThat(first.getCause(), "exhaustionCause").isInstanceOf(IOException.class);
			requireThat(first.getCause().getMessage().contains("unknown"), "actionInDiagnostic").isTrue();
			requireThat(fixture.hostReads, "noFurtherSampling").isEqualTo(hostReads, "before");
			requireThat(fixture.polls(), "noFurtherPolling").isEqualTo(polls, "before");
			requireThat(fixture.creations, "noThirdAttempt").isEqualTo(List.of("unknown:0", "unknown:1"), "expected");
			IllegalStateException submission = requireException(IllegalStateException.class,
				() -> queue.submit("forbidden", 0L));
			requireThat(submission.getCause() == first.getCause(), "submissionCause").isTrue();
			requireException(IllegalStateException.class, queue::jobs);
			requireException(IllegalStateException.class, () -> queue.allocationBlocked("unknown", 1, 0));
			requireThat(queue.completions(), "noFabricatedCompletion").isEqualTo(0L, "expected");
			requireThat(queue.completedAttempts().size(), "acceptedOutcomes").isEqualTo(2, "expected");
			requireThat(queue.completedAttempts().get(1).outcome().result(), "originalPayload").isEqualTo(9, "expected");
		}
		finally
		{
			queue.close();
		}
		requireThat(queue.completedAttempts().size(), "diagnosticsAfterClose").isEqualTo(2, "expected");
		requireThat(fixture.owner("unknown", 0).closes, "firstOwnerClose").isEqualTo(1, "expected");
		requireThat(fixture.owner("unknown", 1).closes, "secondOwnerClose").isEqualTo(1, "expected");
	}

	/**
	 * Keeps the barrier with a running peer and leaves the free opportunity unused by an eligible normal retry.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void normalRetryDoesNotConsumeCompletionFreeOpportunity() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("action", 10L);
			queue.submit("peer", 10L);
			queue.cycle();
			queue.cycle();
			queue.allocationBlocked("action", 0, 0);
			fixture.host = new Controller.Memory(1000, 1, true);
			requireThat(queue.cycle().jobName(), "selectedTermination").isEqualTo("action", "expected");
			fixture.owner("action", 0).outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(1000, 0, false);
			requireThat(queue.cycle().control(), "livePeerBarrier").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.owner("peer", 1).outcome = new JobQueue.Outcome<>(0, false);
			requireThat(queue.cycle().jobName(), "normalRetry").isEqualTo("action", "expected");
			fixture.owner("action", 2).outcome = new JobQueue.Outcome<>(137, true);

			requireThat(queue.cycle().control(), "unusedFreeOpportunity").isEqualTo(Policy.Control.START, "expected");
			requireThat(queue.jobs().getFirst().startedAt(), "freeAttempt").isEqualTo(3L, "expected");
			requireThat(queue.completions(), "realCompletionOnly").isEqualTo(1L, "expected");
		}
	}

	/**
	 * Permits a later eligible normal retry after using the opportunity, without resetting that opportunity.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void usedOpportunityDoesNotImposeOneRetryTotal() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("action", 10L);
			queue.cycle();
			fixture.owner("action", 0).outcome = new JobQueue.Outcome<>(137, true);
			queue.cycle();
			queue.submit("peer", 10L);
			queue.cycle();
			queue.allocationBlocked("action", 1, 0);
			fixture.host = new Controller.Memory(1000, 1, true);
			requireThat(queue.cycle().jobName(), "selectedTermination").isEqualTo("action", "expected");
			fixture.owner("action", 1).outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(1000, 0, false);
			requireThat(queue.cycle().control(), "livePeerBarrier").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.owner("peer", 2).outcome = new JobQueue.Outcome<>(0, false);

			requireThat(queue.cycle().control(), "eligibleNormalRetry").isEqualTo(Policy.Control.START, "expected");
			requireThat(queue.jobs().getFirst().startedAt(), "normalAttempt").isEqualTo(3L, "expected");
			fixture.owner("action", 3).outcome = new JobQueue.Outcome<>(137, true);
			requireException(IOException.class, queue::cycle);
			requireThat(fixture.creations.size(), "opportunityNeverReset").isEqualTo(4, "expected");
		}
	}

	/**
	 * Counts a same-batch natural peer completion once while retrying the last aborted action without inventing another.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void lastPeerCompletionUsesBoundedException() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("action", 10L);
			queue.submit("peer", 10L);
			queue.cycle();
			queue.cycle();
			fixture.owner("action", 0).outcome = new JobQueue.Outcome<>(137, true);
			fixture.owner("peer", 1).outcome = new JobQueue.Outcome<>(0, false);

			requireThat(queue.cycle().control(), "retry").isEqualTo(Policy.Control.START, "expected");
			requireThat(queue.completions(), "actualCompletions").isEqualTo(1L, "expected");
			requireThat(queue.jobs().getFirst().retryAfter(), "unchangedThreshold").isEqualTo(2L, "expected");
			fixture.owner("action", 2).outcome = new JobQueue.Outcome<>(137, true);
			requireException(IOException.class, queue::cycle);
			requireThat(fixture.creations.size(), "boundedException").isEqualTo(3, "expected");
		}
	}

	/**
	 * Withholds recovery through incomplete outcomes and failed polling before accepting cleanup and a new attempt.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void incompleteAndFailedPollDoNotStartAnotherAttempt() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.cycle();
			requireThat(queue.cycle().control(), "incompleteOutcome").isEqualTo(Policy.Control.WAIT, "expected");
			Owner owner = fixture.owner("unknown", 0);
			owner.outcome = new JobQueue.Outcome<>(137, true);
			owner.pollFailure = new IOException("Controlled incomplete native observation");
			requireException(IOException.class, queue::cycle);
			requireThat(fixture.creations.size(), "noUnreconciledRetry").isEqualTo(1, "expected");
			owner.pollFailure = null;

			requireThat(queue.cycle().control(), "reconciledRetry").isEqualTo(Policy.Control.START, "expected");
			requireThat(owner.closes, "cleanupBeforeRetry").isEqualTo(1, "expected");
		}
	}

	/**
	 * Consumes the opportunity before a partial start and retains uncertain ownership until accepted reaping.
	 * @throws IOException if valid controlled operations fail
	 */
	@Test
	public void partialStartConsumesOpportunityWithoutAutomaticReplacement() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.cycle();
			fixture.owner("unknown", 0).outcome = new JobQueue.Outcome<>(137, true);
			fixture.failStartSequence = 1L;
			requireException(IOException.class, queue::cycle);
			requireException(IOException.class, queue::cycle);
			requireThat(fixture.creations.size(), "retainedPartialOwner").isEqualTo(2, "expected");
			requireThat(fixture.owner("unknown", 1).starts, "singleStartRequest").isEqualTo(1, "expected");
			fixture.owner("unknown", 1).outcome = new JobQueue.Outcome<>(137, true);

			requireException(IOException.class, queue::cycle);
			requireThat(fixture.creations.size(), "consumedOpportunity").isEqualTo(2, "expected");
		}
	}

	/**
	 * Permanently refuses recovery after accepted abortion when owner cleanup fails.
	 * @throws IOException if valid setup operations fail
	 */
	@Test
	public void failedCleanupNeverPermitsRecovery() throws IOException
	{
		Fixture fixture = new Fixture();
		var queue = fixture.queue();
		try
		{
			queue.submit("unknown", null);
			queue.cycle();
			Owner owner = fixture.owner("unknown", 0);
			owner.outcome = new JobQueue.Outcome<>(137, true);
			owner.closeFailure = new IOException("Controlled native cleanup failure");
			requireException(IOException.class, queue::cycle);
			requireException(IOException.class, queue::cycle);
			requireThat(owner.closes, "singleCleanupAttempt").isEqualTo(1, "expected");
			requireThat(fixture.creations.size(), "noUncleanRetry").isEqualTo(1, "expected");
		}
		finally
		{
			requireException(IOException.class, queue::close);
		}
	}

	/**
	 * Applies complete sampling, pressure and physical headroom before consuming the retry opportunity.
	 * @throws IOException if valid setup operations fail
	 */
	@Test
	public void failedReadingsAndMemoryGatesDoNotConsumeOpportunity() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.cycle();
			fixture.residentFailure = new IOException("Controlled missing tree observation");
			requireException(IOException.class, queue::cycle);
			fixture.residentFailure = null;
			fixture.residency = -1;
			requireException(IOException.class, queue::cycle);
			fixture.residency = 0;
			fixture.owner("unknown", 0).outcome = new JobQueue.Outcome<>(137, true);
			fixture.hostFailure = new IOException("Controlled unavailable host observation");
			requireException(IOException.class, queue::cycle);
			fixture.hostFailure = null;
			fixture.host = new Controller.Memory(1000, 1, true);
			requireThat(queue.cycle().control(), "pressure").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.host = new Controller.Memory(0, 1, false);
			requireThat(queue.cycle().control(), "noHeadroom").isEqualTo(Policy.Control.WAIT, "expected");
			requireThat(fixture.creations.size(), "noPrematureRetry").isEqualTo(1, "expected");
			fixture.host = new Controller.Memory(1, 1, false);

			requireThat(queue.cycle().control(), "eventualRecovery").isEqualTo(Policy.Control.START, "expected");
		}
	}

	/**
	 * Supplies controlled host/tree values and resource-free owners at the production collaborator boundaries.
	 */
	private static final class Fixture implements Controller.Measurements
	{
		/** Host bytes and pressure supplied to the controller. */
		private Controller.Memory host = new Controller.Memory(1000, 0, false);
		/** Stable accepted owners keyed by action and attempt sequence. */
		private final Map<String, Owner> owners = new LinkedHashMap<>();
		/** Requested factory identities in creation order. */
		private final List<String> creations = new ArrayList<>();
		/** Fresh resident bytes supplied for any live owned tree in this test. */
		private long residency = 0;
		/** Controlled host failure, or null when host sampling succeeds. */
		private IOException hostFailure = null;
		/** Controlled owned-tree failure, or null when resident sampling succeeds. */
		private IOException residentFailure = null;
		/** Attempt sequence whose start fails after request, or null for successful starts. */
		private Long failStartSequence = null;
		/** Number of host-reader requests received from the controller. */
		private int hostReads = 0;

		/**
		 * Creates a resource-free queue whose measurements and owners belong to this fixture.
		 * @return an exclusively owned queue
		 */
		private JobQueue<Integer> queue()
		{
			return new JobQueue<>(new Policy.Budget(0, 3), this, (name, sequence) ->
			{
				Owner owner = new Owner();
				if (failStartSequence != null && failStartSequence == sequence)
					owner.startFailure = new IOException("Controlled partial start failure");
				String identity = name + ":" + sequence;
				creations.add(identity);
				owners.put(identity, owner);
				return owner;
			});
		}

		/**
		 * Finds an owner already created by a production queue control.
		 * @param name submitted action identity
		 * @param sequence accepted attempt sequence
		 * @return the previously created owner
		 */
		private Owner owner(String name, long sequence)
		{
			return owners.get(name + ":" + sequence);
		}

		/**
		 * Counts owner polling requests across all attempts for terminal-guard assertions.
		 * @return total polling requests in this fixture
		 */
		private int polls()
		{
			return owners.values().stream().mapToInt(owner -> owner.polls).sum();
		}

		@Override
		public Controller.Memory readHost() throws IOException
		{
			++hostReads;
			if (hostFailure != null)
				throw hostFailure;
			return host;
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			if (residentFailure != null)
				throw residentFailure;
			return residency;
		}
	}

	/**
	 * Models accepted whole-job outcome and cleanup separately from each process-control request.
	 */
	private static final class Owner implements JobQueue.Attempt<Integer>
	{
		/** Accepted whole-job result, or null before the modeled native owner accepts completion/reaping. */
		private JobQueue.Outcome<Integer> outcome = null;
		/** Number of explicit owner-cleanup requests. */
		private int closes = 0;
		/** Number of start requests received by this owner. */
		private int starts = 0;
		/** Number of whole-job polling requests received by this owner. */
		private int polls = 0;
		/** Controlled partial-start failure, or null. */
		private IOException startFailure = null;
		/** Controlled observation failure, or null. */
		private IOException pollFailure = null;
		/** Controlled cleanup failure, or null. */
		private IOException closeFailure = null;

		@Override
		public void start() throws IOException
		{
			++starts;
			if (startFailure != null)
				throw startFailure;
		}

		@Override
		public void resume(long waitSequence) throws IOException
		{
		}

		@Override
		public void terminate() throws IOException
		{
		}

		@Override
		public Optional<JobQueue.Outcome<Integer>> poll() throws IOException
		{
			++polls;
			if (pollFailure != null)
				throw pollFailure;
			return Optional.ofNullable(outcome);
		}

		@Override
		public void close() throws IOException
		{
			++closes;
			if (closeFailure != null)
				throw closeFailure;
		}
	}
}
