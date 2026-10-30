package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises readiness, delivery uncertainty and exact permission acknowledgement through the production owner and codec.
 * Native frames are copied unchanged from native-allocation-protocol's protocol-incremental-success.log. The poster
 * records Java requests and controlled failures; these tests do not establish native delivery or allocation success.
 */
public final class AllocationPermissionsTest
{
	/** Actual attempt UUID from the retained two-thread native protocol receipt. */
	private static final UUID ATTEMPT = UUID.fromString("8e24a611-ec3e-486e-b4d2-261f85599c47");
	/** Exact ready envelope observed in the retained native receipt. */
	private static final String READY =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"ready\"}}";
	/** Exact blocked one envelope observed in the retained native receipt. */
	private static final String BLOCKED_ONE =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"blocked\",\"wait\":\"1\",\"thread\":\"3262\",\"bytes\":\"268435456\",\"error\":\"12\"}}";
	/** Exact blocked two envelope observed in the retained native receipt. */
	private static final String BLOCKED_TWO =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"blocked\",\"wait\":\"2\",\"thread\":\"3263\",\"bytes\":\"268435456\",\"error\":\"12\"}}";
	/** Exact accepted two envelope observed in the retained native receipt. */
	private static final String ACCEPTED_TWO =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"granted\",\"wait\":\"2\",\"status\":\"accepted\"}}";
	/** Exact duplicate two envelope observed in the retained native receipt. */
	private static final String DUPLICATE_TWO =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"granted\",\"wait\":\"2\",\"status\":\"duplicate\"}}";
	/** Exact unknown envelope observed in the retained native receipt. */
	private static final String UNKNOWN =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"granted\",\"wait\":\"999999\",\"status\":\"unknown\"}}";
	/** Exact blocked three envelope observed in the retained native receipt. */
	private static final String BLOCKED_THREE =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"blocked\",\"wait\":\"3\",\"thread\":\"3263\",\"bytes\":\"268435456\",\"error\":\"12\"}}";
	/** Exact accepted three envelope observed in the retained native receipt. */
	private static final String ACCEPTED_THREE =
		"{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"8e24a611-ec3e-486e-b4d2-261f85599c47\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":\"granted\",\"wait\":\"3\",\"status\":\"accepted\"}}";

	/**
	 * Creates resource-free checks of installation permission state.
	 */
	public AllocationPermissionsTest()
	{
	}

	/**
	 * Accepts readiness exactly once and permanently rejects a duplicate ready event.
	 * @throws IOException if initial readiness is rejected
	 */
	@Test
	public void acceptsReadinessExactlyOnce() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		requireThat(owner.ready(), "initialReadiness").isFalse();
		requireThat(owner.read(READY), "readyEvent").isEqualTo(new AllocationMessages.Ready(), "expected");
		requireThat(owner.ready(), "acceptedReadiness").isTrue();

		requireException(IOException.class, () -> owner.read(READY));
		requireThat(owner.failure(), "duplicateReadinessFailure").isNotNull();
	}

	/**
	 * Rejects a denial, acknowledgement or grant before readiness and issues no post.
	 */
	@Test
	public void rejectsProtocolTransitionsBeforeReady()
	{
		for (String frame : List.of(BLOCKED_ONE, ACCEPTED_TWO))
		{
			List<String> posts = new ArrayList<>();
			AllocationPermissions owner = owner(posts::add);
			requireException(IOException.class, () -> owner.read(frame));
			requireException(IOException.class, () -> owner.grant(nativeWait(2)));
			requireThat(posts.size(), "noUnreadyPost").isEqualTo(0, "expected");
		}
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		requireException(IOException.class, () -> owner.grant(nativeWait(2)));
		requireThat(owner.pending(), "noUnreadyPendingIdentity").isNull();
		requireThat(posts.size(), "noUnreadyGrant").isEqualTo(0, "expected");
	}

	/**
	 * Returns repeated and new denial values without granting or manufacturing acknowledgement state.
	 * @throws IOException if valid native envelopes are rejected
	 */
	@Test
	public void acceptsRepeatedDenialsAsValues() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		AllocationMessages.Blocked expected = new AllocationMessages.Blocked(nativeWait(1),
			BigInteger.valueOf(3262), BigInteger.valueOf(268435456), 12);

		requireThat(owner.read(BLOCKED_ONE), "firstDenial").isEqualTo(expected, "expected");
		requireThat(owner.read(BLOCKED_ONE), "repeatedDenial").isEqualTo(expected, "expected");
		requireThat(((AllocationMessages.Blocked) owner.read(BLOCKED_TWO)).identity(), "otherDenial").
			isEqualTo(nativeWait(2), "expected");
		requireThat(((AllocationMessages.Blocked) owner.read(BLOCKED_THREE)).identity(), "freshRetryDenial").
			isEqualTo(nativeWait(3), "expected");
		requireThat(owner.pending(), "noInventedPendingPermission").isNull();
		requireThat(owner.acknowledged(nativeWait(1)), "noInventedAcknowledgement").isFalse();
		requireThat(posts.size(), "noDenialPost").isEqualTo(0, "expected");
	}

	/**
	 * Publishes pending identity before the post and accepts only its subsequent exact native response.
	 * @throws IOException if the valid posting and acknowledgement sequence fails
	 */
	@Test
	public void recordsPendingBeforePostAndMatchesAcceptedResponse() throws IOException
	{
		AllocationPermissions[] holder = new AllocationPermissions[1];
		List<String> posts = new ArrayList<>();
		holder[0] = owner(permission ->
		{
			requireThat(holder[0].pending(), "postTimePendingIdentity").isEqualTo(nativeWait(2), "expected");
			requireThat(holder[0].acknowledged(nativeWait(2)), "notAcknowledgedDuringPost").isFalse();
			posts.add(permission);
		});
		AllocationPermissions owner = holder[0];
		owner.read(READY);

		owner.grant(nativeWait(2));
		requireThat(posts.getFirst(), "serializedPermission").isEqualTo(codec().permission(nativeWait(2)).toString(), "expected");
		requireThat(owner.pending(), "postedPendingIdentity").isEqualTo(nativeWait(2), "expected");
		requireThat(owner.read(ACCEPTED_TWO), "exactResponse").isEqualTo(
			new AllocationMessages.Granted(nativeWait(2), AllocationMessages.GrantStatus.ACCEPTED), "expected");
		requireThat(owner.pending(), "completedPermission").isNull();
		requireThat(owner.acknowledged(nativeWait(2)), "acceptedExactWait").isTrue();
	}

	/**
	 * Retains a throwing post's identity and first cause and never resends it.
	 * @throws IOException if initial readiness is rejected
	 */
	@Test
	public void throwingPostWithdrawsAuthorityWithoutResend() throws IOException
	{
		List<String> posts = new ArrayList<>();
		IOException delivery = new IOException("Controlled uncertain delivery");
		AllocationPermissions owner = owner(permission -> { posts.add(permission); throw delivery; });
		owner.read(READY);

		requireException(IOException.class, () -> owner.grant(nativeWait(2)));
		IOException retained = owner.failure();
		requireThat(retained.getCause(), "originalPostCause").isEqualTo(delivery, "expected");
		requireException(IOException.class, () -> owner.grant(nativeWait(2)));
		requireException(IOException.class, () -> owner.grant(nativeWait(3)));
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));
		owner.transportFailed(new IOException("Later diagnostic"));

		requireThat(owner.failure(), "firstFailureRetained").isEqualTo(retained, "expected");
		requireThat(owner.pending(), "uncertainPermissionRetained").isEqualTo(nativeWait(2), "expected");
		requireThat(posts.size(), "onePostOnly").isEqualTo(1, "expected");
	}

	/**
	 * Converts operational runtime posting failures into contextual checked failures with retained authority loss.
	 * @throws IOException if initial readiness is rejected
	 */
	@Test
	public void runtimePostingFailureRetainsCause() throws IOException
	{
		IllegalStateException nativeFailure = new IllegalStateException("Controlled script-post rejection");
		AllocationPermissions owner = owner(_ -> { throw nativeFailure; });
		owner.read(READY);

		requireException(IOException.class, () -> owner.grant(nativeWait(2)));

		requireThat(owner.failure().getCause(), "runtimePostCause").isEqualTo(nativeFailure, "expected");
		requireThat(owner.pending(), "runtimePendingIdentity").isEqualTo(nativeWait(2), "expected");
	}

	/**
	 * Retains delivery uncertainty before propagating an Error and prohibits another post afterwards.
	 * @throws IOException if initial readiness is rejected
	 */
	@Test
	public void postingErrorWithdrawsAuthorityBeforePropagation() throws IOException
	{
		AssertionError nativeFailure = new AssertionError("Controlled fatal post");
		AllocationPermissions owner = owner(_ -> { throw nativeFailure; });
		owner.read(READY);

		requireThat(requireException(AssertionError.class, () -> owner.grant(nativeWait(2))), "propagatedError").
			isEqualTo(nativeFailure, "expected");
		requireThat(owner.failure().getCause(), "retainedErrorCause").isEqualTo(nativeFailure, "expected");
		requireThat(owner.pending(), "errorPendingIdentity").isEqualTo(nativeWait(2), "expected");
		requireException(IOException.class, () -> owner.grant(nativeWait(3)));
	}

	/**
	 * Rejects a synchronous acknowledgement while posting even if the poster catches that rejection and returns.
	 * @throws IOException if initial readiness is rejected
	 */
	@Test
	public void reentrantAcknowledgementDuringPostingIsTerminal() throws IOException
	{
		AllocationPermissions[] holder = new AllocationPermissions[1];
		holder[0] = owner(_ -> requireException(IOException.class, () -> holder[0].read(ACCEPTED_TWO)));
		AllocationPermissions owner = holder[0];
		owner.read(READY);

		requireException(IOException.class, () -> owner.grant(nativeWait(2)));

		requireThat(owner.pending(), "postingIdentityRetained").isEqualTo(nativeWait(2), "expected");
		requireThat(owner.acknowledged(nativeWait(2)), "reentrantAcceptanceForbidden").isFalse();
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));
	}

	/**
	 * Rejects the native duplicate and unknown statuses rather than interpreting them as successful continuation.
	 * @throws IOException if valid setup is rejected
	 */
	@Test
	public void rejectsNativeDuplicateAndUnknownResponses() throws IOException
	{
		for (String frame : List.of(DUPLICATE_TWO, UNKNOWN))
		{
			AllocationMessages.Granted response = (AllocationMessages.Granted) codec().read(frame);
			List<String> posts = new ArrayList<>();
			AllocationPermissions owner = owner(posts::add);
			owner.read(READY);
			owner.grant(response.identity());

			requireException(IOException.class, () -> owner.read(frame));
			requireThat(owner.pending(), "nonacceptedPendingIdentity").isEqualTo(response.identity(), "expected");
			requireThat(owner.acknowledged(response.identity()), "noNonacceptedAcknowledgement").isFalse();
			requireException(IOException.class, () -> owner.grant(nativeWait(3)));
			requireThat(posts.size(), "noStatusResend").isEqualTo(1, "expected");
		}
	}

	/**
	 * Rejects an otherwise valid accepted response for a different wait while retaining the actual pending identity.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void rejectsMismatchedWaitAcknowledgement() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		owner.read(READY);
		owner.grant(nativeWait(2));

		requireException(IOException.class, () -> owner.read(ACCEPTED_THREE));

		requireThat(owner.pending(), "matchingPermissionStillPending").isEqualTo(nativeWait(2), "expected");
		requireThat(owner.acknowledged(nativeWait(3)), "noWrongWaitAcceptance").isFalse();
	}

	/**
	 * Rejects a native accepted response without a preceding production post.
	 * @throws IOException if readiness is rejected
	 */
	@Test
	public void rejectsUnsolicitedAcceptedResponse() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		owner.read(READY);

		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));

		requireThat(owner.pending(), "noInventedPermission").isNull();
		requireThat(owner.acknowledged(nativeWait(2)), "noUnsolicitedAcceptance").isFalse();
	}

	/**
	 * Treats a second acknowledgement as a protocol failure while retaining the earlier accepted fact.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void rejectsRepeatedAcknowledgement() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		owner.read(READY);
		owner.grant(nativeWait(2));
		owner.read(ACCEPTED_TWO);

		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));

		requireThat(owner.acknowledged(nativeWait(2)), "earlierAcceptedFact").isTrue();
		requireThat(owner.pending(), "noOutstandingPermission").isNull();
	}

	/**
	 * Keeps an earlier acknowledgement distinct from a later pending permission, including after an old-response error.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void distinguishesCompletedAndCurrentPendingWaits() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		owner.read(READY);
		owner.grant(nativeWait(2));
		owner.read(ACCEPTED_TWO);
		owner.grant(nativeWait(3));

		requireThat(owner.acknowledged(nativeWait(2)), "earlierAcknowledgement").isTrue();
		requireThat(owner.acknowledged(nativeWait(3)), "laterPermissionNotAcknowledged").isFalse();
		requireThat(owner.pending(), "laterPendingPermission").isEqualTo(nativeWait(3), "expected");
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));
		requireThat(owner.pending(), "errorPreservesCurrentPending").isEqualTo(nativeWait(3), "expected");
	}

	/**
	 * Retains acknowledgements for each completed permission and prohibits posting one of them again.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void retainsAcknowledgedIdentitiesWithoutResending() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		owner.grant(nativeWait(2));
		owner.read(ACCEPTED_TWO);
		owner.grant(nativeWait(3));
		owner.read(ACCEPTED_THREE);

		requireThat(owner.acknowledged(nativeWait(2)), "firstAcknowledgedWait").isTrue();
		requireThat(owner.acknowledged(nativeWait(3)), "secondAcknowledgedWait").isTrue();
		requireException(IOException.class, () -> owner.grant(nativeWait(2)));
		requireThat(posts.size(), "noAcknowledgedResend").isEqualTo(2, "expected");
	}

	/**
	 * Rejects another grant while one permission is outstanding without replacing the first identity or posting again.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void pendingPermissionCannotBeReplaced() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		owner.grant(nativeWait(2));

		requireException(IOException.class, () -> owner.grant(nativeWait(3)));

		requireThat(owner.pending(), "originalPendingIdentity").isEqualTo(nativeWait(2), "expected");
		requireThat(posts.size(), "noSecondPendingPost").isEqualTo(1, "expected");
	}

	/**
	 * Invalid codec input permanently withdraws authority and preserves an outstanding permission identity.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void decodingFailurePreservesPendingAndFirstCause() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		owner.grant(nativeWait(2));

		requireException(IOException.class, () -> owner.read("{"));
		IOException original = owner.failure();
		requireThat(original.getCause(), "codecParseCause").isNotNull();
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));
		requireThat(owner.failure(), "firstDecodeCauseRetained").isEqualTo(original, "expected");
		requireThat(owner.pending(), "decodeFailurePendingIdentity").isEqualTo(nativeWait(2), "expected");
		requireThat(posts.size(), "noDecodeResend").isEqualTo(1, "expected");
	}

	/**
	 * Rejects copied messages from another attempt, installation or process through the real identity-checking codec.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void rejectsOtherInstallationMessages() throws IOException
	{
		for (String invalid : List.of(
			ACCEPTED_TWO.replace(ATTEMPT.toString(), "00000000-0000-0000-0000-000000000001"),
			ACCEPTED_TWO.replace("\"installation\":\"1\"", "\"installation\":\"2\""),
			ACCEPTED_TWO.replace("\"pid\":\"3216\"", "\"pid\":\"3217\"")))
		{
			AllocationPermissions owner = owner(_ -> {});
			owner.read(READY);
			owner.grant(nativeWait(2));

			requireException(IOException.class, () -> owner.read(invalid));
			requireThat(owner.pending(), "identityFailurePendingWait").isEqualTo(nativeWait(2), "expected");
			requireThat(owner.acknowledged(nativeWait(2)), "noForeignAcknowledgement").isFalse();
		}
	}

	/**
	 * Retains an externally reported first transport failure, preserving pending identity and preventing future effects.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void ownerReportedTransportFailureIsSticky() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		owner.grant(nativeWait(2));
		IOException original = new IOException("Controlled callback-copy failure");

		owner.transportFailed(original);
		owner.transportFailed(new IOException("Later deadline"));
		requireException(IOException.class, () -> owner.grant(nativeWait(3)));
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));

		requireThat(owner.failure(), "firstTransportFailure").isEqualTo(original, "expected");
		requireThat(owner.pending(), "transportPendingWait").isEqualTo(nativeWait(2), "expected");
		requireThat(posts.size(), "noTransportResend").isEqualTo(1, "expected");
	}

	/**
	 * Stops without posting or fabricating acknowledgement and retains pending state for diagnostics.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void stopHasNoNativeEffectAndProhibitsNewGrants() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);
		owner.grant(nativeWait(2));

		owner.stop();
		owner.stop();
		requireException(IOException.class, () -> owner.grant(nativeWait(3)));
		requireException(IOException.class, () -> owner.read(ACCEPTED_TWO));

		requireThat(owner.pending(), "stoppedPendingWait").isEqualTo(nativeWait(2), "expected");
		requireThat(owner.acknowledged(nativeWait(2)), "noStopAcknowledgement").isFalse();
		requireThat(owner.failure(), "stopIsNotTransportFailure").isNull();
		requireThat(posts.size(), "noStopPost").isEqualTo(1, "expected");
	}

	/**
	 * Validates permission identities before recording pending state or posting and permits a subsequent valid grant.
	 * @throws IOException if valid setup fails
	 */
	@Test
	public void validatesGrantBeforePendingState() throws IOException
	{
		List<String> posts = new ArrayList<>();
		AllocationPermissions owner = owner(posts::add);
		owner.read(READY);

		requireException(NullPointerException.class, () -> owner.grant(null));
		requireException(IllegalArgumentException.class, () -> owner.grant(new AllocationWaits.Wait(2, 2)));

		requireThat(owner.pending(), "noInvalidPendingIdentity").isNull();
		requireThat(owner.failure(), "callerValidationIsNotDeliveryFailure").isNull();
		requireThat(posts.size(), "noInvalidPermissionPost").isEqualTo(0, "expected");
		owner.grant(nativeWait(2));
		requireThat(posts.size(), "subsequentValidPost").isEqualTo(1, "expected");
	}

	/**
	 * Rejects native-callback-thread mutation while preserving constructing-thread readiness and grant state.
	 * @throws IOException if valid owner-thread readiness is rejected
	 */
	@Test
	public void confinesPermissionStateToConstructingThread() throws IOException
	{
		AllocationPermissions owner = owner(_ -> {});
		try (var executor = Executors.newSingleThreadExecutor())
		{
			var read = executor.submit(() -> owner.read(READY));
			ExecutionException wrongThread = requireException(ExecutionException.class, read::get);
			requireThat(wrongThread.getCause(), "threadConfinementFailure").isInstanceOf(IllegalStateException.class);
		}

		requireThat(owner.ready(), "noForeignReadinessMutation").isFalse();
		requireThat(owner.failure(), "threadMisuseNotTransportFailure").isNull();
		owner.read(READY);
		requireThat(owner.ready(), "ownerThreadReadiness").isTrue();
	}

	/**
	 * Creates one owner configured for the exact installation that emitted the retained native frames.
	 * @param poster borrowed controlled post collaborator
	 * @return resource-free production owner
	 */
	private static AllocationPermissions owner(AllocationPermissions.Poster poster)
	{
		return new AllocationPermissions(codec(), poster);
	}

	/**
	 * Creates the real codec with the retained native attempt, generation and process identity.
	 * @return resource-free codec for the actual receipt envelopes
	 */
	private static AllocationMessages codec()
	{
		return new AllocationMessages(ATTEMPT, 1, 3216);
	}

	/**
	 * Creates an exact wait identity in the retained native installation.
	 * @param sequence positive native wait identity
	 * @return the value used for matching production permission state
	 */
	private static AllocationWaits.Wait nativeWait(long sequence)
	{
		return new AllocationWaits.Wait(1, sequence);
	}
}
