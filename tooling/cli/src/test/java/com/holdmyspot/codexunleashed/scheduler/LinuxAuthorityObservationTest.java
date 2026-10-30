package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies rejected-observation decisions using controlled reader receipts and retained-zero observations.
 */
public final class LinuxAuthorityObservationTest
{
	/** Every removed leaf must independently prove retained zero before one batch can be retried. */
	@Test
	public void acknowledgesMultipleCompletedLeaves()
	{
		var first = new LinuxMeasurements.Attempt("A", 0);
		var second = new LinuxMeasurements.Attempt("B", 1);
		var reply = removed(first);

		reply.setProperty("count", "2");
		reply.setProperty("name.0", "A");
		reply.setProperty("attempt.0", "0");
		reply.setProperty("name.1", "B");
		reply.setProperty("attempt.1", "1");
		var failure = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, "host", Map.of(first, () -> true, second, () -> true)));
		requireThat(failure instanceof ObservationRejected, "allCompletedLeavesRejectedTogether").isTrue();
		var rejected = (ObservationRejected) failure;
		requireThat(rejected.attempts(), "allExactAttemptIdentities").isEqualTo(List.of(
			new ObservationRejected.Attempt("A", 0), new ObservationRejected.Attempt("B", 1)));
		for (LinuxAuthority.ZeroBarrier secondBarrier : List.<LinuxAuthority.ZeroBarrier>of(
			() -> false, () -> { throw new IOException("zero unavailable"); }))
		{
			var denied = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
				reply, "host", Map.of(first, () -> true, second, secondBarrier)));
			requireThat(denied instanceof ObservationRejected, "oneZeroCannotAuthorizeAnotherLeaf").isFalse();
		}
		var stale = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, "host", Map.of(first, () -> true,
				new LinuxMeasurements.Attempt("B", 2), () -> true)));
		requireThat(stale instanceof ObservationRejected, "noStaleAttemptAuthority").isFalse();
		for (String field : List.of("count", "name.1", "attempt.1"))
		{
			var malformed = (Properties) reply.clone();
			malformed.remove(field);
			var denied = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
				malformed, "host", Map.of(first, () -> true, second, () -> true)));
			requireThat(denied instanceof ObservationRejected, "noPartialCandidateList").isFalse();
		}
		reply.setProperty("name.1", "A");
		reply.setProperty("attempt.1", "0");
		var duplicate = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, "host", Map.of(first, () -> true, second, () -> true)));
		requireThat(duplicate instanceof ObservationRejected, "noDuplicateCandidateAuthority").isFalse();
	}
	/**
	 * Creates independent observation-decision checks.
	 */
	public LinuxAuthorityObservationTest() { }

	/**
	 * Exact acknowledged missing leaf plus retained zero grants observation retry only.
	 * @throws IOException if the focused decision cannot be exercised
	 */
	@Test
	public void acknowledgesOnlyExactZeroAttempt() throws IOException
	{
		var attempt = new LinuxMeasurements.Attempt("compile", 7);
		var reply = removed(attempt);
		IOException observed = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, "host", Map.of(attempt, () -> true)));

		requireThat(observed instanceof ObservationRejected, "acknowledgedObservationRetry").isTrue();
		var retry = (ObservationRejected) observed;
		requireThat(retry.operation(), "rejectedOperation").isEqualTo(ObservationRejected.Operation.HOST, "expected");
		requireThat(retry.attempts().getFirst().action(), "exactAction").isEqualTo("compile", "expected");
		requireThat(retry.attempts().getFirst().attemptSequence(), "exactAttempt").isEqualTo(7L, "expected");
	}

	/**
	 * Missing, false and failed retained-zero authority cannot grant retry permission.
	 */
	@Test
	public void rejectsUnprovenZero()
	{
		var attempt = new LinuxMeasurements.Attempt("compile", 7);
		for (Map<LinuxMeasurements.Attempt, LinuxAuthority.ZeroBarrier> barriers :
			List.<Map<LinuxMeasurements.Attempt, LinuxAuthority.ZeroBarrier>>of(
			Map.<LinuxMeasurements.Attempt, LinuxAuthority.ZeroBarrier>of(), Map.of(attempt, () -> false),
			Map.of(attempt, () -> { throw new IOException("Zero observation unavailable"); })))
		{
			IOException failure = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
				removed(attempt), "resident", barriers));
			requireThat(failure instanceof ObservationRejected, "noRetryWithoutZero").isFalse();
		}
	}

	/**
	 * Controls, malformed metadata and generic rejection remain failures without retry permission.
	 */
	@Test
	public void rejectsOtherOperationsAndMetadata()
	{
		var attempt = new LinuxMeasurements.Attempt("compile", 7);
		for (String operation : List.of("register", "unregister", "terminate", "unknown"))
			assertOrdinary(removed(attempt), operation, attempt);
		var generic = new Properties();
		generic.setProperty("status", "unavailable");
		generic.setProperty("error", "openat2 java-owner failed with errno 2");
		assertOrdinary(generic, "host", attempt);
		for (String field : List.of("name.0", "attempt.0", "count", "reason"))
		{
			var malformed = removed(attempt);
			malformed.remove(field);
			assertOrdinary(malformed, "verify", attempt);
		}
		var invalid = removed(attempt);
		invalid.setProperty("attempt.0", "-1");
		assertOrdinary(invalid, "host", attempt);
		for (String count : List.of("0", "2", "01", "-1", "9223372036854775808"))
		{
			var malformed = removed(attempt);
			malformed.setProperty("count", count);
			assertOrdinary(malformed, "host", attempt);
		}
		var unexpected = removed(attempt);
		unexpected.setProperty("name.1", "other");
		assertOrdinary(unexpected, "host", attempt);
	}

	/**
	 * Another invalid workload defeats a missing candidate regardless of iteration order.
	 */
	@Test
	public void checksEveryWorkloadBeforeClassifying()
	{
		var missing = LinuxAuthorityReader.SharedSecrets.removedLeaf(new LinuxMeasurements.Attempt("compile", 7));
		var trust = new IOException("Another retained cgroup identity changed");
		LinuxAuthorityReader.Check absent = () -> { throw missing; };
		LinuxAuthorityReader.Check invalid = () -> { throw trust; };
		for (var checks : List.of(List.of(absent, invalid), List.of(invalid, absent)))
		{
			var failure = requireException(IOException.class,
				() -> LinuxAuthorityReader.SharedSecrets.verifyWorkloads(checks));
			requireThat(failure == trust, "trustFailureDefeatsCandidate").isTrue();
		}
	}

	/**
	 * A changed global view after workload checks defeats an otherwise valid missing-leaf candidate.
	 */
	@Test
	public void checksContextAfterCandidate()
	{
		var missing = LinuxAuthorityReader.SharedSecrets.removedLeaf(new LinuxMeasurements.Attempt("compile", 7));
		var trust = new IOException("Reader mount namespace changed");
		int[] checks = {0};
		var failure = requireException(IOException.class, () -> LinuxAuthorityReader.SharedSecrets.verifyObservation(
			() -> { if (++checks[0] == 2) throw trust; }, List.of(() -> { throw missing; })));
		requireThat(failure == trust, "finalContextDefeatsCandidate").isTrue();
	}

	/**
	 * Every verified candidate survives complete trust checks and is serialized without losing its identity.
	 */
	@Test
	public void retainsEveryVerifiedCandidate()
	{
		var missing = LinuxAuthorityReader.SharedSecrets.removedLeaf(new LinuxMeasurements.Attempt("compile", 7));
		var observed = requireException(IOException.class, () -> LinuxAuthorityReader.SharedSecrets.verifyObservation(
			() -> { }, List.of(() -> { throw missing; }, () -> { })));
		requireThat(LinuxAuthorityReader.SharedSecrets.rejection(observed).getProperty("count"), "soleVerifiedCandidate").isEqualTo("1");
		var another = LinuxAuthorityReader.SharedSecrets.removedLeaf(new LinuxMeasurements.Attempt("other", 8));
		var rejected = requireException(IOException.class, () -> LinuxAuthorityReader.SharedSecrets.verifyWorkloads(
			List.of(() -> { throw missing; }, () -> { throw another; })));
		var serialized = LinuxAuthorityReader.SharedSecrets.rejection(rejected);
		requireThat(serialized.getProperty("count"), "everyVerifiedCandidate").isEqualTo("2");
		requireThat(serialized.getProperty("name.0"), "firstCandidate").isEqualTo("compile");
		requireThat(serialized.getProperty("name.1"), "secondCandidate").isEqualTo("other");
	}

	/**
	 * A successful zero-valued reply remains successful and never asks an owner for retry authority.
	 * @throws IOException if a valid response is rejected
	 */
	@Test
	public void preservesSuccessfulZero() throws IOException
	{
		var reply = new Properties();
		reply.setProperty("status", "ok");
		reply.setProperty("resident", "0");
		LinuxAuthority.SharedSecrets.requireSuccess(reply, "resident", Map.of());
		requireThat(LinuxAuthority.number(reply, "resident"), "successfulMeasuredZero").isEqualTo(0L, "expected");
	}

	/**
	 * Creates the structured candidate emitted only after reader-side trust checks.
	 * @param attempt registered action identity
	 * @return candidate rejection, not a successful sample
	 */
	private static Properties removed(LinuxMeasurements.Attempt attempt)
	{
		var reply = new Properties();
		reply.setProperty("status", "unavailable");
		reply.setProperty("reason", LinuxAuthority.MISSING_LEAF);
		reply.setProperty("count", "1");
		reply.setProperty("name.0", attempt.name());
		reply.setProperty("attempt.0", Long.toString(attempt.sequence()));
		reply.setProperty("error", "Registered leaf removed");
		return reply;
	}

	/**
	 * Requires an ordinary failure rather than observation retry authority.
	 * @param reply controlled acknowledged reply
	 * @param operation operation being performed
	 * @param attempt retained registered action
	 */
	private static void assertOrdinary(Properties reply, String operation, LinuxMeasurements.Attempt attempt)
	{
		var failure = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, operation, Map.of(attempt, () -> true)));
		requireThat(failure instanceof ObservationRejected, "ordinaryFailure").isFalse();
	}
}
