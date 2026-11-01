package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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

	/**
	 * Java residency lookup failure nominates a removed leaf only after complete authority verification;
	 * its structured rejection still requires the registered owner's authentic zero observation.
	 * @throws IOException if the test-owned directory cannot be created or removed
	 */
	@Test
	public void classifiesJavaResidencyRemovalThroughCompleteAuthority() throws IOException
	{
		Path directory = Files.createTempDirectory("authority-observation-");
		try
		{
			NoSuchFileException missing = requireException(NoSuchFileException.class,
				() -> LinuxResidency.read(directory, directory, directory.resolve("completed-leaf")));
			LinuxMeasurements.Attempt attempt = new LinuxMeasurements.Attempt("compile", 7);
			// The callback models the reader's full native authority check reporting this exact absent leaf.
			IOException removed = LinuxAuthorityReader.SharedSecrets.removedLeaf(attempt);
			IOException result = LinuxAuthorityReader.SharedSecrets.observationFailure("resident", missing,
				() -> { throw removed; });
			Properties reply = LinuxAuthorityReader.SharedSecrets.rejection(result);
			requireThat(reply.getProperty("reason"), "javaRemovalHasStructuredRejection").isEqualTo(LinuxAuthority.MISSING_LEAF);
			requireThat(result.getSuppressed()[0] == missing, "originalJavaLookupFailureRetained").isTrue();
			IOException rejected = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
				reply, "resident", Map.of(attempt, () -> true)));
			requireThat(rejected instanceof ObservationRejected, "authenticZeroPermitsReconciliation").isTrue();
			IOException live = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
				reply, "resident", Map.of(attempt, () -> false)));
			requireThat(live instanceof ObservationRejected, "missingPathDoesNotProveZero").isFalse();
		}
		finally { Files.delete(directory); }
	}

	/**
	 * Java missing paths cannot recover from live leaves, changed authority or uncertain cleanup.
	 * @throws IOException if a controlled healthy verification fails unexpectedly
	 */
	@Test
	public void preservesJavaLookupAuthorityFailures() throws IOException
	{
		for (String operation : List.of("host", "resident", "verify"))
		{
			IOException missing = new NoSuchFileException("completed-leaf");
			IOException live = LinuxAuthorityReader.SharedSecrets.observationFailure(operation, missing, () -> { });
			requireThat(live == missing, "liveLeafRetainsOriginalJavaFailure").isTrue();
			IOException trust = new IOException("Another workload identity changed");
			IOException observed = requireException(IOException.class, () -> LinuxAuthorityReader.SharedSecrets.observationFailure(
				operation, missing, () -> { throw trust; }));
			requireThat(observed == trust, "changedAuthorityDefeatsJavaAbsence").isTrue();
			missing.addSuppressed(new IOException("Uncertain descriptor cleanup"));
			IOException uncertain = LinuxAuthorityReader.SharedSecrets.observationFailure(operation, missing,
				() -> { throw new AssertionError("Uncertain cleanup prevents recovery"); });
			requireThat(uncertain == missing, "uncertainCleanupRemainsFatal").isTrue();
		}
	}

	/**
	 * Mutation requests and Java access or generic I/O failures never enter absent-leaf recovery.
	 * @throws IOException if an excluded verification is incorrectly invoked
	 */
	@Test
	public void excludesJavaMutationAndAccessFailures() throws IOException
	{
		for (String operation : List.of("register", "unregister", "terminate", "unknown"))
		{
			IOException missing = new NoSuchFileException("completed-leaf");
			IOException result = LinuxAuthorityReader.SharedSecrets.observationFailure(operation, missing,
				() -> { throw new AssertionError("Mutations must not recover missing paths"); });
			requireThat(result == missing, "mutationKeepsOriginalJavaFailure").isTrue();
		}
		for (IOException failure : List.of(new AccessDeniedException("completed-leaf"), new IOException("unknown I/O failure")))
		{
			IOException result = LinuxAuthorityReader.SharedSecrets.observationFailure("resident", failure,
				() -> { throw new AssertionError("Access and unknown failures must not recover"); });
			requireThat(result == failure, "unrelatedJavaFailureRetained").isTrue();
		}
	}

	/**
	 * Both missing-path and removed-core errors require complete verification before missing-leaf classification.
	 * @throws IOException if the controlled complete verification fails unexpectedly
	 */
	@Test
	public void rechecksRemovedCoreFilesWithCompleteAuthority() throws IOException
	{
		for (int errno : new int[] {2, 19})
			for (String operation : List.of("host", "resident", "verify"))
			{
				var removed = LinuxAuthorityReader.SharedSecrets.removedLeaf(new LinuxMeasurements.Attempt("compile", 7));
				var result = LinuxAuthorityReader.SharedSecrets.observationFailure(operation, errno, false,
					() -> { throw removed; });
				requireThat(result == removed, "completeVerifiedRemoval").isTrue();
				requireThat(result.getSuppressed().length, "originalNativeFaultRetained").isEqualTo(1);
				requireThat(LinuxAuthorityReader.SharedSecrets.rejection(result).getProperty("reason"),
					"verifiedMissingLeafOnly").isEqualTo(LinuxAuthority.MISSING_LEAF);
			}
	}

	/**
	 * A live leaf, invalid authority or uncertain cleanup cannot turn native failure into a recoverable observation.
	 * @throws IOException if a controlled healthy verification fails unexpectedly
	 */
	@Test
	public void preservesAuthorityFailuresAfterRemovedCoreRead() throws IOException
	{
		for (int errno : new int[] {2, 19})
		{
			var live = LinuxAuthorityReader.SharedSecrets.observationFailure("host", errno, false, () -> { });
			requireThat(LinuxAuthorityReader.SharedSecrets.rejection(live).containsKey("reason"), "liveLeafIsNotMissing").isFalse();
			var trust = new IOException("Authority changed during recheck");
			var observed = requireException(IOException.class, () -> LinuxAuthorityReader.SharedSecrets.observationFailure(
				"host", errno, false, () -> { throw trust; }));
			requireThat(observed == trust, "authorityFailureRetained").isTrue();
			var uncertain = LinuxAuthorityReader.SharedSecrets.observationFailure("host", errno, true,
				() -> { throw new AssertionError("Uncertain cleanup must prevent rechecking"); });
			requireThat(uncertain.getSuppressed().length, "uncertainCleanupRetained").isEqualTo(1);
		}
	}

	/**
	 * Mutations and unrelated native failures retain failure without attempting observation recovery.
	 * @throws IOException if an excluded verification is incorrectly invoked
	 */
	@Test
	public void neverRechecksMutationsOrUnrelatedNativeErrors() throws IOException
	{
		for (int errno : new int[] {2, 19})
			for (String operation : List.of("register", "unregister", "terminate", "unknown"))
			{
				var result = LinuxAuthorityReader.SharedSecrets.observationFailure(operation, errno, false,
					() -> { throw new AssertionError("Mutation must not recheck"); });
				requireThat(LinuxAuthorityReader.SharedSecrets.rejection(result).containsKey("reason"), "mutationRemainsFailure").isFalse();
			}
		for (int errno : new int[] {5, 13, 116})
			LinuxAuthorityReader.SharedSecrets.observationFailure("host", errno, false,
				() -> { throw new AssertionError("Unrelated native failure must not recheck"); });
	}

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

	/** Complete reader-verified membership churn permits a fresh sample without claiming workload completion. */
	@Test
	public void acknowledgesVerifiedMembershipChurn()
	{
		var attempt = new LinuxMeasurements.Attempt("compile", 7);
		var reply = removed(attempt);
		reply.setProperty("reason", "registered-membership-changed");
		var failure = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(
			reply, "resident", Map.of(attempt, () -> { throw new IOException("No zero observation is required"); })));
		requireThat(failure instanceof ObservationRejected, "verifiedMembershipRetry").isTrue();
		var rejected = (ObservationRejected) failure;
		requireThat(rejected.reason(), "reason").isEqualTo(ObservationRejected.Reason.MEMBERSHIP_CHANGED);
		requireThat(rejected.attempts(), "exactAttempt").isEqualTo(List.of(new ObservationRejected.Attempt("compile", 7)));
		assertOrdinary(reply, "host", attempt);
		assertOrdinary(reply, "verify", attempt);
		var unknown = requireException(IOException.class, () -> LinuxAuthority.SharedSecrets.requireSuccess(reply, "resident", Map.of()));
		requireThat(unknown instanceof ObservationRejected, "unknownAttempt").isFalse();
		reply.setProperty("extra", "untrusted");
		assertOrdinary(reply, "resident", attempt);
	}

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
