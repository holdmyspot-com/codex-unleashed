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
 * Exercises allocation-wait recovery through the actual queue, controller and policy with in-memory native owners.
 * Scripted whole-job outcomes and measurements model the Attempt and Measurements contracts; these tests establish
 * scheduling effects and ordering, without establishing operating-system memory reclamation.
 */
public final class JobQueueBlockedRecoveryTest
{
	/**
	 * Keeps the termination barrier until whole-job acceptance and cleanup, then samples again before resuming oldest.
	 * Completed results and submission positions survive the peer's aborted attempt.
	 * @throws IOException if the controlled lifecycle is rejected
	 */
	@Test
	public void reapsPeerAndReadsFreshMemoryBeforeOldestResume() throws IOException
	{
		Fixture fixture = new Fixture();
		try (JobQueue<Integer> queue = fixture.queue())
		{
			startBlockedPair(queue, fixture);
			queue.submit("C", 10L);

			requireDecision(queue.cycle(), Policy.Control.TERMINATE, "B");
			requireThat(queue.jobs().get(1).state(), "selectedState").isEqualTo(Policy.State.STOPPING, "expected");
			fixture.host = new Controller.Memory(500, 0, false);
			requireDecision(queue.cycle(), Policy.Control.WAIT, null);
			requireThat(fixture.owner(1).terminates, "oneTerminate").isEqualTo(1, "expected");
			requireThat(fixture.owner(0).resumes, "noEarlyResume").isEqualTo(0, "expected");

			// The native owner is modeled as accepting whole-tree reaping only when this outcome becomes present.
			fixture.owner(1).outcome = new JobQueue.Outcome<>(137, true);
			fixture.events.clear();
			requireDecision(queue.cycle(), Policy.Control.RESUME, "A");

			requireThat(fixture.events, "cleanupReadAndResumeOrder").isEqualTo(
				List.of("poll:0", "poll:1", "close:1", "host:500", "resident:A:0", "resume:0"), "expected");
			requireThat(queue.completions(), "abortionIsNotCompletion").isEqualTo(0L, "expected");
			requireThat(queue.jobs().get(1).position(), "retainedSubmission").isEqualTo(1L, "expected");
			requireThat(queue.jobs().get(1).state(), "reapedVictim").isEqualTo(Policy.State.RETRY, "expected");
			requireThat(queue.jobs().get(1).retryAfter(), "naturalCompletionBarrier").isEqualTo(1L, "expected");
			requireThat(queue.completedAttempts().getFirst().outcome().result(), "acceptedNativePayload").
				isEqualTo(137, "expected");

			fixture.owner(0).outcome = new JobQueue.Outcome<>(0, false);
			fixture.host = new Controller.Memory(1000, 0, false);
			requireDecision(queue.cycle(), Policy.Control.START, "B");
			requireThat(fixture.created, "retryBeforeReadyWork").isEqualTo(List.of("A:0", "B:1", "B:2"), "expected");
			requireThat(queue.completions(), "actualNaturalCompletion").isEqualTo(1L, "expected");
			requireThat(queue.completedAttempts().size(), "retainedOutcomes").isEqualTo(2, "expected");
			requireThat(queue.allocationBlocked("B", 1, 100), "staleVictimWait").isFalse();
		}
	}

	/**
	 * Permits one completion-free process retry and permanently stops a second retry before owner acquisition.
	 * @throws IOException if valid setup operations fail
	 */
	@Test
	public void boundsRepeatedSoleAttemptReclamation() throws IOException
	{
		Fixture fixture = new Fixture();
		try (JobQueue<Integer> queue = fixture.queue())
		{
			queue.submit("A", 500L);
			queue.cycle();
			queue.allocationBlocked("A", 0, 0);
			fixture.host = new Controller.Memory(0, 0, false);
			requireDecision(queue.cycle(), Policy.Control.TERMINATE, "A");
			fixture.owner(0).outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(1000, 0, false);
			requireDecision(queue.cycle(), Policy.Control.START, "A");
			queue.allocationBlocked("A", 1, 0);
			fixture.host = new Controller.Memory(0, 0, false);
			requireDecision(queue.cycle(), Policy.Control.TERMINATE, "A");
			fixture.owner(1).outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(1000, 0, false);

			IOException exhausted = requireException(IOException.class, queue::cycle);
			int eventsAtFailure = fixture.events.size();
			IOException repeated = requireException(IOException.class, queue::cycle);

			requireThat(repeated.getCause(), "latchedRecoveryCause").isEqualTo(exhausted.getCause(), "expected");
			requireThat(fixture.events.size(), "noFurtherCollaboratorCalls").isEqualTo(eventsAtFailure, "expected");
			requireThat(fixture.created, "boundedOwners").isEqualTo(List.of("A:0", "A:1"), "expected");
			requireThat(queue.completions(), "noInventedCompletions").isEqualTo(0L, "expected");
			requireThat(queue.completedAttempts().size(), "retainedAbortedOutcomes").isEqualTo(2, "expected");
			requireThat(fixture.owner(0).closes, "firstOwnerReaped").isEqualTo(1, "expected");
			requireThat(fixture.owner(1).closes, "secondOwnerReaped").isEqualTo(1, "expected");
		}
	}

	/**
	 * Leaves insufficient external capacity as a wait without consuming the one retry opportunity.
	 * @throws IOException if the controlled lifecycle is rejected
	 */
	@Test
	public void waitsForExternalCapacityBeforeConsumingRetry() throws IOException
	{
		Fixture fixture = new Fixture();
		try (JobQueue<Integer> queue = fixture.queue())
		{
			queue.submit("A", 500L);
			queue.cycle();
			queue.allocationBlocked("A", 0, 0);
			fixture.host = new Controller.Memory(0, 0, false);
			queue.cycle();
			fixture.owner(0).outcome = new JobQueue.Outcome<>(137, true);

			requireDecision(queue.cycle(), Policy.Control.WAIT, null);
			requireDecision(queue.cycle(), Policy.Control.WAIT, null);
			requireThat(fixture.created.size(), "noPrematureRetry").isEqualTo(1, "expected");
			fixture.host = new Controller.Memory(600, 0, false);
			requireDecision(queue.cycle(), Policy.Control.START, "A");
			requireThat(fixture.created, "firstAllowedRetry").isEqualTo(List.of("A:0", "A:1"), "expected");
		}
	}

	/**
	 * Retains an uncertain termination and never repeats it before the owner accepts whole-job completion.
	 * @throws IOException if recovered valid operations fail
	 */
	@Test
	public void partialTerminationPreservesOwnerAndBarrier() throws IOException
	{
		Fixture fixture = new Fixture();
		try (JobQueue<Integer> queue = fixture.queue())
		{
			startBlockedPair(queue, fixture);
			IOException nativeFailure = new IOException("Controlled partial termination");
			fixture.owner(1).terminateFailure = nativeFailure;

			IOException first = requireException(IOException.class, queue::cycle);
			requireException(IOException.class, queue::cycle);

			requireThat(first.getCause(), "retainedNativeCause").isEqualTo(nativeFailure, "expected");
			requireThat(fixture.owner(1).terminates, "noRepeatedTermination").isEqualTo(1, "expected");
			requireThat(fixture.owner(0).resumes, "noUncertainResume").isEqualTo(0, "expected");
			fixture.owner(1).outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(500, 0, false);
			requireDecision(queue.cycle(), Policy.Control.RESUME, "A");
		}
	}

	/**
	 * Permanently stops controls when cleanup of the reclaimed peer fails, retaining the accepted native outcome.
	 * @throws IOException if valid setup operations fail
	 */
	@Test
	public void failedCleanupPreventsRecoveryControls() throws IOException
	{
		Fixture fixture = new Fixture();
		JobQueue<Integer> queue = fixture.queue();
		try
		{
			startBlockedPair(queue, fixture);
			queue.cycle();
			fixture.owner(1).outcome = new JobQueue.Outcome<>(137, true);
			fixture.owner(1).closeFailure = new IOException("Controlled unreleased owner");

			requireException(IOException.class, queue::cycle);
			requireException(IOException.class, queue::cycle);

			requireThat(fixture.owner(1).closes, "singleCleanupAttempt").isEqualTo(1, "expected");
			requireThat(fixture.owner(0).resumes, "noUncleanResume").isEqualTo(0, "expected");
			requireThat(fixture.created.size(), "noUncleanRetry").isEqualTo(2, "expected");
			requireThat(queue.completedAttempts().size(), "acceptedAbortionRetained").isEqualTo(1, "expected");
		}
		finally
		{
			requireException(IOException.class, queue::close);
		}
	}

	/**
	 * Publishes no new control when a required tree observation is missing, then reclaims only after a complete read.
	 * @throws IOException if valid recovery operations fail
	 */
	@Test
	public void requiresCompleteFreshSamplingBeforeReclamation() throws IOException
	{
		Fixture fixture = new Fixture();
		try (JobQueue<Integer> queue = fixture.queue())
		{
			startBlockedPair(queue, fixture);
			fixture.residentFailure = new IOException("Controlled missing owned tree");

			requireException(IOException.class, queue::cycle);

			requireThat(fixture.owner(0).terminates, "noUnsampledVictimA").isEqualTo(0, "expected");
			requireThat(fixture.owner(1).terminates, "noUnsampledVictimB").isEqualTo(0, "expected");
			fixture.residentFailure = null;
			requireDecision(queue.cycle(), Policy.Control.TERMINATE, "B");
		}
	}

	/**
	 * Retains residency as a lower bound while unknown demand stays unknown through a sole-attempt reclamation request.
	 * @throws IOException if valid controller observations fail
	 */
	@Test
	public void unknownResidencyDoesNotBecomeKnownPeak() throws IOException
	{
		Fixture fixture = new Fixture();
		fixture.host = new Controller.Memory(100, 0, false);
		List<Policy.Decision> controls = new ArrayList<>();
		Controller controller = new Controller(new Policy.Budget(100, 1), fixture, controls::add);
		Policy.Job unknown = new Policy.Job("unknown", Policy.State.BLOCKED, 0, null, 0, 0L, 0L, 0);

		requireDecision(controller.cycle(List.of(unknown), 0), Policy.Control.TERMINATE, "unknown");

		Controller.MemoryDemand demand = controller.memoryDemands().get(new Controller.Action("unknown", 0));
		requireThat(demand.peakBytes(), "unknownPeak").isNull();
		requireThat(demand.residentLowerBoundBytes(), "observedLowerBound").isEqualTo(100L, "expected");
		requireThat(controls.size(), "singleControl").isEqualTo(1, "expected");
	}

	/**
	 * Starts two known-demand attempts, publishes their authentic-contract wait notifications and lowers available bytes.
	 * The local notifications model validated allocation waits supplied by each native attempt adapter.
	 * @param queue production queue whose lifecycle is exercised
	 * @param fixture controlled native observations and owned attempts
	 * @throws IOException if the valid setup fails
	 */
	private static void startBlockedPair(JobQueue<Integer> queue, Fixture fixture) throws IOException
	{
		queue.submit("A", 500L);
		queue.submit("B", 200L);
		queue.cycle();
		queue.cycle();
		requireThat(queue.allocationBlocked("A", 0, 0), "firstAllocationWait").isTrue();
		requireThat(queue.allocationBlocked("B", 1, 0), "secondAllocationWait").isTrue();
		fixture.host = new Controller.Memory(499, 0, false);
	}

	/**
	 * Requires production's control and selected stable action.
	 * @param decision actual request returned by the production queue or controller
	 * @param control expected requested effect
	 * @param name expected action identity, or null for waiting
	 */
	private static void requireDecision(Policy.Decision decision, Policy.Control control, String name)
	{
		requireThat(decision.control(), "control").isEqualTo(control, "expected");
		requireThat(decision.jobName(), "selectedAction").isEqualTo(name, "expected");
	}

	/**
	 * Supplies in-memory validated native values and resource-free owners through the production collaborator contracts.
	 */
	private static final class Fixture implements Controller.Measurements
	{
		/** Fresh host capacity and pressure supplied to each production measurement cycle. */
		private Controller.Memory host = new Controller.Memory(2000, 0, false);
		/** Native tree measurement failure, or null when every tree reading succeeds. */
		private IOException residentFailure = null;
		/** Created owners retained by their queue-wide attempt ordinal. */
		private final Map<Long, Owner> owners = new LinkedHashMap<>();
		/** Stable action and attempt identities requested by the production queue. */
		private final List<String> created = new ArrayList<>();
		/** Calls observed at the borrowed measurement and exclusively owned attempt boundaries. */
		private final List<String> events = new ArrayList<>();

		/**
		 * Creates an empty resource-owning queue using this fixture's documented collaborator values.
		 * @return the caller-owned production queue
		 */
		private JobQueue<Integer> queue()
		{
			return new JobQueue<>(new Policy.Budget(100, 2), this, (name, sequence) ->
			{
				Owner owner = new Owner(sequence, events);
				owners.put(sequence, owner);
				created.add(name + ":" + sequence);
				return owner;
			});
		}

		/**
		 * Returns the owner acquired for an accepted queue-wide attempt sequence.
		 * @param sequence the attempt ordinal requested by production
		 * @return the recorded owner
		 */
		private Owner owner(long sequence)
		{
			return owners.get(sequence);
		}

		@Override
		public Controller.Memory readHost()
		{
			events.add("host:" + host.availableBytes());
			return host;
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			events.add("resident:" + job.name() + ":" + job.startedAt());
			if (residentFailure != null)
				throw residentFailure;
			return 100;
		}
	}

	/**
	 * Models Attempt's native whole-tree acceptance and uncertain-control contracts, recording production requests.
	 * This object owns no operating-system process or resource.
	 */
	private static final class Owner implements JobQueue.Attempt<Integer>
	{
		private final long sequence;
		private final List<String> events;
		/** Accepted native whole-job outcome, or null while the modeled owner withholds completion. */
		private JobQueue.Outcome<Integer> outcome = null;
		/** Modeled partial termination failure, or null for an acknowledged request. */
		private IOException terminateFailure = null;
		/** Modeled owner-release failure, or null for accepted cleanup. */
		private IOException closeFailure = null;
		/** Counts of production requests for this modeled native owner. */
		private int resumes = 0, terminates = 0, closes = 0;

		/**
		 * Creates a resource-free owner for one queue attempt.
		 * @param sequence queue-wide attempt ordinal
		 * @param events borrowed record of collaborator calls
		 */
		private Owner(long sequence, List<String> events)
		{
			this.sequence = sequence;
			this.events = events;
		}

		@Override
		public void start()
		{
			events.add("start:" + sequence);
		}

		@Override
		public void resume(long waitSequence)
		{
			++resumes;
			events.add("resume:" + sequence);
		}

		@Override
		public void terminate() throws IOException
		{
			++terminates;
			events.add("terminate:" + sequence);
			if (terminateFailure != null)
				throw terminateFailure;
		}

		@Override
		public Optional<JobQueue.Outcome<Integer>> poll()
		{
			events.add("poll:" + sequence);
			return Optional.ofNullable(outcome);
		}

		@Override
		public void close() throws IOException
		{
			++closes;
			events.add("close:" + sequence);
			if (closeFailure != null)
				throw closeFailure;
		}
	}
}
