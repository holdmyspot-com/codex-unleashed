package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks retained-demand decisions through queue termination, accepted reaping and retry using in-memory owners.
 * The owners supply the documented Attempt outcomes; these tests make no native process-lifecycle claim.
 */
public final class JobQueueMemoryHistoryTest
{
	/**
	 * Creates queue retained-demand checks.
	 */
	public JobQueueMemoryHistoryTest()
	{
	}

	/**
	 * Retains observed demand across selected abortion and waits for both a natural completion and sufficient memory.
	 * @throws IOException if a valid controlled lifecycle fails
	 */
	@Test
	public void retryUsesObservedDemandAndOriginalPosition() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("older", 10L);
			queue.submit("compile", 10L);
			queue.submit("newer", 1L);
			queue.cycle();
			queue.cycle();
			fixture.residency.put("compile", 90L);
			queue.cycle();
			fixture.host = new Controller.Memory(1000, 1, true);

			requireThat(queue.cycle().control(), "termination").isEqualTo(Policy.Control.TERMINATE, "expected");
			fixture.owners.get("compile:1").outcome = new JobQueue.Outcome<>(137, true);
			fixture.host = new Controller.Memory(1000, 0, false);
			requireThat(queue.cycle().control(), "retryWithoutCompletion").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.owners.get("older:0").outcome = new JobQueue.Outcome<>(0, false);
			fixture.host = new Controller.Memory(89, 0, false);

			requireThat(queue.cycle().control(), "retryBelowRetainedDemand").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.host = new Controller.Memory(90, 0, false);
			Policy.Decision retry = queue.cycle();
			requireThat(retry.control(), "retryControl").isEqualTo(Policy.Control.START, "expected");
			requireThat(retry.jobName(), "retryIdentity").isEqualTo("compile", "expected");
			requireThat(queue.jobs().getFirst().position(), "originalPosition").isEqualTo(1L, "expected");
			requireThat(queue.jobs().getFirst().startedAt(), "newAttempt").isEqualTo(2L, "expected");
		}
	}

	/**
	 * Discards an earlier action's candidate observation when the later owned-tree reader fails.
	 * @throws IOException if controlled valid lifecycle operations fail
	 */
	@Test
	public void failedSampleDoesNotRaiseLaterRetryDemand() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("compile", 10L);
			queue.submit("peer", 10L);
			queue.cycle();
			queue.cycle();
			fixture.residency.put("compile", 90L);
			fixture.failedSample = "peer";
			requireException(IOException.class, queue::cycle);
			fixture.failedSample = null;
			fixture.residency.put("compile", 10L);
			queue.allocationBlocked("compile", 0, 0);
			fixture.host = new Controller.Memory(1000, 1, true);
			requireThat(queue.cycle().jobName(), "terminatedAction").isEqualTo("compile", "expected");
			fixture.owners.get("compile:0").outcome = new JobQueue.Outcome<>(137, true);
			fixture.owners.get("peer:1").outcome = new JobQueue.Outcome<>(0, false);
			fixture.host = new Controller.Memory(10, 0, false);

			Policy.Decision retry = queue.cycle();

			requireThat(retry.control(), "retryControl").isEqualTo(Policy.Control.START, "expected");
			requireThat(retry.jobName(), "retryIdentity").isEqualTo("compile", "expected");
		}
	}

	/**
	 * Retains a fully acquired observation even when termination fails before its native outcome is reconciled.
	 * @throws IOException if controlled valid lifecycle operations fail
	 */
	@Test
	public void failedControlRetainsDemandForReconciledRetry() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("compile", 10L);
			queue.submit("peer", 10L);
			queue.cycle();
			queue.cycle();
			queue.allocationBlocked("compile", 0, 0);
			fixture.residency.put("compile", 90L);
			fixture.host = new Controller.Memory(1000, 1, true);
			Owner owner = fixture.owners.get("compile:0");
			IOException failedTermination = new IOException("Controlled partial termination failure");
			owner.terminateFailure = failedTermination;

			IOException actual = requireException(IOException.class, queue::cycle);

			requireThat(actual.getCause() == failedTermination, "originalCause").isTrue();
			requireThat(owner.terminations, "terminationRequests").isEqualTo(1, "expected");
			// Accepted whole-job outcomes model native reconciliation after the uncertain request, not its success.
			owner.outcome = new JobQueue.Outcome<>(137, true);
			fixture.owners.get("peer:1").outcome = new JobQueue.Outcome<>(0, false);
			fixture.host = new Controller.Memory(89, 0, false);
			requireThat(queue.cycle().control(), "retainedDemand").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.host = new Controller.Memory(90, 0, false);
			requireThat(queue.cycle().control(), "affordableRetry").isEqualTo(Policy.Control.START, "expected");
			requireThat(owner.terminations, "noRepeatedTermination").isEqualTo(1, "expected");
		}
	}

	/**
	 * Keeps an unknown action's peak unknown after an observed but incomplete attempt is accepted as aborted.
	 * @throws IOException if valid controlled lifecycle operations fail
	 */
	@Test
	public void abortedUnknownObservationDoesNotSupplyPeak() throws IOException
	{
		Fixture fixture = new Fixture();
		try (var queue = fixture.queue())
		{
			queue.submit("unknown", null);
			queue.submit("ready", 1L);
			queue.cycle();
			fixture.residency.put("unknown", 90L);
			requireThat(queue.cycle().control(), "exclusiveObservation").isEqualTo(Policy.Control.WAIT, "expected");
			fixture.owners.get("unknown:0").outcome = new JobQueue.Outcome<>(137, true);

			requireThat(queue.cycle().control(), "requiresNaturalCompletion").isEqualTo(Policy.Control.WAIT, "expected");
			requireThat(queue.jobs().getFirst().state(), "retryState").isEqualTo(Policy.State.RETRY, "expected");
			requireThat(queue.jobs().getFirst().peakBytes(), "unknownPeak").isNull();
			requireThat(queue.completions(), "abortionIsNotCompletion").isEqualTo(0L, "expected");
		}
	}

	/**
	 * Supplies native-boundary readings and stable whole-job outcomes under each test's exclusive control.
	 */
	private static final class Fixture implements Controller.Measurements
	{
		/**
		 * Controlled available physical bytes, occupied swap bytes and pressure returned by the host reader.
		 */
		private Controller.Memory host = new Controller.Memory(1000, 0, false);
		/**
		 * Action whose controlled reader fails, or null for complete readings.
		 */
		private String failedSample = null;
		/**
		 * Controlled current resident bytes by action identity.
		 */
		private final Map<String, Long> residency = new HashMap<>();
		/**
		 * Attempt owners keyed by action and attempt sequence.
		 */
		private final Map<String, Owner> owners = new HashMap<>();

		/**
		 * Creates a queue whose owners acquire no external resources.
		 * @return an exclusively owned test queue
		 */
		private JobQueue<Integer> queue()
		{
			return new JobQueue<>(new Policy.Budget(0, 2), this, (name, sequence) ->
			{
				Owner owner = new Owner();
				owners.put(name + ":" + sequence, owner);
				return owner;
			});
		}

		@Override
		public Controller.Memory readHost()
		{
			return host;
		}

		@Override
		public long readResidentBytes(Policy.Job job) throws IOException
		{
			if (job.name().equals(failedSample))
				throw new IOException("Controlled residency failure for " + job.name());
			return residency.getOrDefault(job.name(), 0L);
		}
	}

	/**
	 * Models stable accepted whole-job results; requests do not manufacture completion or reaping.
	 */
	private static final class Owner implements JobQueue.Attempt<Integer>
	{
		/**
		 * Null while the modeled native owner has not accepted whole-job completion.
		 */
		private JobQueue.Outcome<Integer> outcome = null;
		/**
		 * Controlled failure after requesting termination, or null.
		 */
		private IOException terminateFailure = null;
		/**
		 * Number of termination requests issued to this attempt.
		 */
		private int terminations = 0;

		@Override
		public void start()
		{
		}

		@Override
		public void resume(long waitSequence)
		{
		}

		@Override
		public void terminate() throws IOException
		{
			++terminations;
			if (terminateFailure != null)
				throw terminateFailure;
		}

		@Override
		public Optional<JobQueue.Outcome<Integer>> poll()
		{
			return Optional.ofNullable(outcome);
		}

		@Override
		public void close()
		{
		}
	}
}
