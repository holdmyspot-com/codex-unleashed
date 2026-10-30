package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises hook decisions using the real codec, permission owner and wait collection. The in-memory connection
 * represents copied Frida frames and uncertain native posts observed in native-allocation-protocol's retained receipt;
 * these checks establish Java interpretation and ownership ordering, not native listener execution.
 */
public final class AllocationHookTest
{
	/**
	 * Correlation UUID copied from the accepted native protocol receipt.
	 */
	private static final UUID ATTEMPT = UUID.fromString("8e24a611-ec3e-486e-b4d2-261f85599c47");

	/**

	 * Creates resource-free, isolated protocol checks.

	 */
	public AllocationHookTest()
	{
	}

	/**
	 * Delivers duplicate and fresh native denials into real batching while awaiting the exact posted acknowledgement.
	 * @throws IOException if valid receipt-shaped transitions are rejected
	 */
	@Test
	public void batchesFreshDenialDuringAcknowledgement() throws IOException
	{
		Connection connection = new Connection();
		AllocationWaits waits = new AllocationWaits();
		AllocationHook hook = hook(connection, waits);
		connection.frames.add(ready());
		hook.start();
		hook.awaitReady(() -> {});
		connection.frames.add(blocked(1));
		connection.frames.add(blocked(1));
		hook.pump();
		long batch = waits.poll().orElseThrow();
		connection.onPost = permission ->
		{
			connection.frames.add(blocked(2));
			connection.frames.add(granted(1, "accepted"));
		};

		waits.resume(batch, wait -> hook.grant(wait, () -> {}));

		requireThat(connection.posts.size(), "oneGrantForDuplicateReport").isEqualTo(1, "expected");
		requireThat(connection.posts.getFirst().path("wait").asString(), "postedWait").isEqualTo("1", "expected");
		requireThat(waits.poll().orElseThrow(), "followingBatch").isEqualTo(1L, "expected");
		requireThat(hook.pending(), "acknowledgedPendingPermission").isNull();
		hook.close(() -> true);
	}

	/**
	 * Retains one uncertain native post and never sends it again, even after a matching response arrives late.
	 * @throws IOException if valid startup frames are rejected
	 */
	@Test
	public void uncertainPostRetainsIdentityWithoutResend() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add(blocked(1));
		hook.pump();
		connection.postFailure = new IOException("Controlled uncertain native post");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		connection.frames.add(granted(1, "accepted"));
		requireException(IOException.class, hook::pump);
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(hook.pending(), "uncertainWait").isEqualTo(nativeWait(1), "expected");
		requireThat(connection.posts.size(), "noResend").isEqualTo(1, "expected");
		hook.close(() -> true);
	}

	/**
	 * Rejects native duplicate/unknown acknowledgements and a response for another wait instead of resuming a batch.
	 * @throws IOException if valid startup or denial frames are rejected
	 */
	@Test
	public void rejectsUnmatchedOrUnacceptedAcknowledgements() throws IOException
	{
		for (String response : List.of(granted(1, "duplicate"), granted(1, "unknown"), granted(2, "accepted")))
		{
			Connection connection = new Connection();
			AllocationHook hook = readyHook(connection);
			connection.frames.add(blocked(1));
			hook.pump();
			connection.onPost = _ -> connection.frames.add(response);
			requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
			requireThat(hook.failure(), "ackFailure").isNotNull();
			requireThat(hook.pending(), "unresolvedWait").isEqualTo(nativeWait(1), "expected");
			hook.close(() -> true);
		}
	}

	/**
	 * Rejects an unobserved native wait before sending any permission.
	 * @throws IOException if startup fails
	 */
	@Test
	public void rejectsUnobservedWaitBeforePosting() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.size(), "noUnobservedGrant").isEqualTo(0, "expected");
		hook.close(() -> true);
	}

	/**
	 * Times out a lost acknowledgement using controlled monotonic progress while still servicing peers.
	 * @throws IOException if valid startup or denial frames are rejected
	 */
	@Test
	public void lostAcknowledgementPumpsPeersAndWithdrawsGrants() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add(blocked(1));
		hook.pump();
		int[] peerRounds = {0};
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> peerRounds[0]++));
		requireThat(peerRounds[0] > 0, "peersServiced").isTrue();
		requireThat(hook.pending(), "unacknowledgedWait").isEqualTo(nativeWait(1), "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.size(), "noLostAckResend").isEqualTo(1, "expected");
		hook.close(() -> true);
	}

	/**
	 * Preserves script error context, including a native capacity fault, and rejects subsequent grants.
	 * @throws IOException if startup fails
	 */
	@Test
	public void scriptFaultPreservesNativeContext() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add("{\"type\":\"error\",\"description\":\"allocation registry fault 1\",\"stack\":\"native registry poll\"}");
		IOException failure = requireException(IOException.class, hook::pump);
		requireThat(failure.getMessage().contains("allocation registry fault 1"), "nativeFaultDescription").isTrue();
		requireThat(failure.getMessage().contains("native registry poll"), "nativeFaultStack").isTrue();
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.isEmpty(), "noFaultGrant").isTrue();
		hook.close(() -> true);
	}

	/**
	 * Rejects stale generation and malformed frames without manufacturing queue waits.
	 * @throws IOException if startup fails
	 */
	@Test
	public void rejectsStaleAndMalformedFrames() throws IOException
	{
		for (String frame : List.of(blocked(1).replace("\"installation\":\"1\"", "\"installation\":\"2\""), "{}"))
		{
			Connection connection = new Connection();
			AllocationWaits waits = new AllocationWaits();
			AllocationHook hook = hook(connection, waits);
			connection.frames.add(ready());
			hook.start();
			hook.awaitReady(() -> {});
			connection.frames.add(frame);
			requireException(IOException.class, hook::pump);
			requireThat(waits.poll().isPresent(), "noMalformedWait").isFalse();
			hook.close(() -> true);
		}
	}

	/**
	 * Holds instrumentation while execution remains possible and withdraws permission before observing the barrier.
	 * @throws IOException if startup or safe cleanup fails
	 */
	@Test
	public void requiresExecutionBarrierBeforeNativeClose() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		requireException(IOException.class, () -> hook.close(() -> false));
		requireThat(connection.closes, "retainedNativeOwner").isEqualTo(0, "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		hook.close(() -> true);
		hook.close(() -> true);
		requireThat(connection.closes, "closedOnceAfterBarrier").isEqualTo(1, "expected");
	}

	/**
	 * Retains a partial start for cleanup and latches failed native destruction instead of treating a retry as success.
	 */
	@Test
	public void retainsPartialAcquisitionAndCleanupFailure()
	{
		Connection connection = new Connection();
		AllocationHook hook = hook(connection, new AllocationWaits());
		connection.startFailure = new IOException("Controlled partial native acquisition");
		requireException(IOException.class, hook::start);
		connection.closeFailure = new IOException("Controlled native destruction failure");
		IOException first = requireException(IOException.class, () -> hook.close(() -> true));
		IOException later = requireException(IOException.class, () -> hook.close(() -> true));
		requireThat(first.getCause(), "nativeCleanupCause").isEqualTo(connection.closeFailure, "expected");
		requireThat(later.getCause(), "retainedCleanupFailure").isEqualTo(first, "expected");
		requireThat(connection.closes, "noPretendSuccessfulCleanup").isEqualTo(1, "expected");
	}

	/**
	 * Retains copied-callback failure and blocks all future native controls.
	 * @throws IOException if startup fails
	 */
	@Test
	public void callbackFailureWithdrawsPermissionAuthority() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.readFailure = new IOException("Controlled callback copy failure");
		requireException(IOException.class, hook::pump);
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.isEmpty(), "noControlsAfterCopyFailure").isTrue();
		hook.close(() -> true);
	}

	/**
	 * Rejects a denial before readiness instead of publishing an unauthorized queue wait.
	 * @throws IOException if native start fails
	 */
	@Test
	public void rejectsDenialBeforeReadiness() throws IOException
	{
		Connection connection = new Connection();
		AllocationWaits waits = new AllocationWaits();
		AllocationHook hook = hook(connection, waits);
		hook.start();
		connection.frames.add(blocked(1));
		requireException(IOException.class, () -> hook.awaitReady(() -> {}));
		requireThat(waits.poll().isPresent(), "noUnreadyDenial").isFalse();
		hook.close(() -> true);
	}

	/**
	 * Withdraws startup authority after a missing readiness response and rejects a late startup retry.
	 * @throws IOException if native start fails
	 */
	@Test
	public void missingReadinessExpiresWithoutReload() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = hook(connection, new AllocationWaits());
		hook.start();
		requireException(IOException.class, () -> hook.awaitReady(() -> {}));
		requireException(IOException.class, hook::start);
		requireThat(hook.failure(), "readinessDeadlineRetained").isNotNull();
		hook.close(() -> true);
	}

	/**
	 * Keeps a copied-message burst from starving the borrowed peer pump while an acknowledgement is pending.
	 * @throws IOException if valid protocol transitions are rejected
	 */
	@Test
	public void servicesPeersDuringCopiedMessageBurst() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add(blocked(1));
		hook.pump();
		connection.onPost = _ ->
		{
			for (int index = 0; index < 100; index++)
				connection.frames.add(blocked(2));
			connection.frames.add(granted(1, "accepted"));
		};
		int[] peersBeforeAck = {0};
		hook.grant(nativeWait(1), () ->
		{
			if (hook.pending() != null)
				peersBeforeAck[0]++;
		});
		requireThat(peersBeforeAck[0] > 0, "peerServiceBeforeBacklogDrained").isTrue();
		hook.close(() -> true);
	}

	/**
	 * Propagates the original start Error while retaining first failure and partial-acquisition cleanup responsibility.
	 * @throws IOException if retained-owner checks or safe cleanup fail
	 */
	@Test
	public void startErrorRetainsFirstFailureAndCleanupOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = hook(connection, new AllocationWaits());
		AssertionError first = new AssertionError("Controlled partial-start Error");
		connection.startError = first;

		requireThat(requireException(AssertionError.class, hook::start), "originalStartError").isEqualTo(first, "expected");
		requireRetainedAndClosable(hook, connection, first);
	}

	/**
	 * Propagates a copied-message polling Error while permanently withdrawing further native controls.
	 * @throws IOException if valid startup or retained-owner checks fail
	 */
	@Test
	public void pollingErrorRetainsFirstFailureAndCleanupOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		AssertionError first = new AssertionError("Controlled copied-message polling Error");
		connection.readError = first;

		requireThat(requireException(AssertionError.class, hook::pump), "originalPollingError").isEqualTo(first, "expected");
		requireRetainedAndClosable(hook, connection, first);
	}

	/**
	 * Propagates an uncertain native-post Error and retains its exact permission identity without resending.
	 * @throws IOException if valid startup, denial or retained-owner checks fail
	 */
	@Test
	public void postingErrorRetainsPendingPermissionAndCleanupOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add(blocked(1));
		hook.pump();
		AssertionError first = new AssertionError("Controlled uncertain native-post Error");
		connection.postError = first;

		requireThat(requireException(AssertionError.class, () -> hook.grant(nativeWait(1), () -> {})),
			"originalPostingError").isEqualTo(first, "expected");
		requireThat(hook.pending(), "uncertainPermissionAfterError").isEqualTo(nativeWait(1), "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.size(), "noPostErrorResend").isEqualTo(1, "expected");
		requireRetainedAndClosable(hook, connection, first);
	}

	/**
	 * Retains the first peer-pump Error through both control-wait and permission boundaries without clearing pending state.
	 * @throws IOException if valid startup, denial or retained-owner checks fail
	 */
	@Test
	public void peerPumpErrorRetainsPendingPermissionAndCleanupOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		connection.frames.add(blocked(1));
		hook.pump();
		AssertionError first = new AssertionError("Controlled peer-pump Error");

		requireThat(requireException(AssertionError.class, () -> hook.grant(nativeWait(1), () -> { throw first; })),
			"originalPeerError").isEqualTo(first, "expected");
		requireThat(hook.pending(), "unacknowledgedPermissionAfterPeerError").isEqualTo(nativeWait(1), "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		requireThat(connection.posts.size(), "noPeerErrorResend").isEqualTo(1, "expected");
		requireRetainedAndClosable(hook, connection, first);
	}

	/**
	 * Propagates the original native-destruction Error and retains it behind later cleanup failure observations.
	 * @throws IOException if startup fails
	 */
	@Test
	public void closeErrorRetainsUnresolvedNativeOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		AssertionError first = new AssertionError("Controlled native destruction Error");
		connection.closeError = first;

		requireThat(requireException(AssertionError.class, () -> hook.close(() -> true)), "originalCloseError").
			isEqualTo(first, "expected");
		connection.closeError = new AssertionError("Later native destruction Error");
		IOException later = requireException(IOException.class, () -> hook.close(() -> true));
		requireThat(later.getCause().getCause(), "retainedFirstCloseError").isEqualTo(first, "expected");
		requireException(IOException.class, hook::pump);
		requireThat(connection.closes, "failedOwnerCannotBecomeNoOpSuccess").isEqualTo(1, "expected");
	}

	/**
	 * Keeps the connection owned and grants stopped after an unknown barrier, permitting a later authoritative observation.
	 * @throws IOException if startup or later safe cleanup fails
	 */
	@Test
	public void throwingBarrierRetainsNativeOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		IOException unknown = new IOException("Controlled unavailable execution barrier");

		requireThat(requireException(IOException.class, () -> hook.close(() -> { throw unknown; })),
			"originalUnknownBarrier").isEqualTo(unknown, "expected");
		requireThat(connection.closes, "unknownBarrierRetainsConnection").isEqualTo(0, "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		hook.close(() -> true);
		requireThat(connection.closes, "safeCloseAfterBarrierRecovery").isEqualTo(1, "expected");
	}

	/**
	 * Propagates a barrier Error before native destruction while retaining ownership for a later trusted barrier.
	 * @throws IOException if startup or later safe cleanup fails
	 */
	@Test
	public void barrierErrorRetainsNativeOwner() throws IOException
	{
		Connection connection = new Connection();
		AllocationHook hook = readyHook(connection);
		AssertionError first = new AssertionError("Controlled execution-barrier Error");

		requireThat(requireException(AssertionError.class, () -> hook.close(() -> { throw first; })),
			"originalBarrierError").isEqualTo(first, "expected");
		requireThat(connection.closes, "barrierErrorRetainsConnection").isEqualTo(0, "expected");
		requireException(IOException.class, () -> hook.grant(nativeWait(1), () -> {}));
		hook.close(() -> true);
		requireThat(connection.closes, "safeCloseAfterBarrierError").isEqualTo(1, "expected");
	}

	/**
	 * Checks first-failure identity, stopped native controls and retained destruction responsibility after an Error.
	 * @param hook actual protocol/lifecycle owner after the controlled Error
	 * @param connection borrowed external-boundary double observing destruction requests
	 * @param first original Error that the owning operation must retain
	 * @throws IOException if safe cleanup fails
	 */
	private static void requireRetainedAndClosable(AllocationHook hook, Connection connection, Error first)
		throws IOException
	{
		IOException retained = hook.failure();
		requireThat(retained.getCause(), "firstErrorCause").isEqualTo(first, "expected");
		connection.readError = new AssertionError("Later unconsumed polling Error");
		requireException(IOException.class, hook::pump);
		requireThat(hook.failure(), "firstFailureUnchanged").isEqualTo(retained, "expected");
		requireException(IOException.class, () -> hook.close(() -> false));
		requireThat(connection.closes, "noPrematureDestructionAfterError").isEqualTo(0, "expected");
		hook.close(() -> true);
		requireThat(connection.closes, "retainedOwnerDestructionRequested").isEqualTo(1, "expected");
		requireThat(hook.failure(), "failureSurvivesCleanup").isEqualTo(retained, "expected");
	}

	/**
	 * Creates and starts an owner after supplying its native readiness frame.
	 * @param connection isolated copied-message transport
	 * @return ready owner whose cleanup remains with the test
	 * @throws IOException if valid readiness is rejected
	 */
	private static AllocationHook readyHook(Connection connection) throws IOException
	{
		AllocationHook hook = hook(connection, new AllocationWaits());
		connection.frames.add(ready());
		hook.start();
		hook.awaitReady(() -> {});
		return hook;
	}

	/**
	 * Supplies a monotonic timer that advances one nanosecond per observation, avoiding real-time waits.
	 * @param connection isolated copied-message transport
	 * @param waits real attempt-wide native wait collection
	 * @return unstarted hook owner
	 */
	private static AllocationHook hook(Connection connection, AllocationWaits waits)
	{
		long[] now = {0};
		return new AllocationHook(ATTEMPT, 1, 3216, connection, waits, "", Duration.ofNanos(3), () -> now[0]++);
	}

	/**

	 * Constructs the common strict frame identity from the retained receipt.
	 * @return exact native receipt's identity envelope prefix

	 */
	private static String prefix()
	{
		return "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-allocation\",\"version\":1,\"attempt\":\"" + ATTEMPT +
			"\",\"installation\":\"1\",\"pid\":\"3216\",\"event\":";
	}

	/**

	 * Supplies the native readiness reaction observed in the accepted receipt.
	 * @return readiness frame shaped exactly as the accepted native receipt

	 */
	private static String ready() { return prefix() + "\"ready\"}}"; }
	/**
	 * Constructs a controlled receipt-shaped frame or wait identity.
	 * @param id native denial sequence
	 * @return blocked frame with receipt-derived native diagnostics
	 */
	private static String blocked(long id) { return prefix() + "\"blocked\",\"wait\":\"" + id + "\",\"thread\":\"3262\",\"bytes\":\"268435456\",\"error\":\"12\"}}"; }
	/**
	 * Constructs a controlled receipt-shaped frame or wait identity.
	 * @param id native denial sequence
	 * @param status controlled accepted/duplicate/unknown result
	 * @return native response
	 */
	private static String granted(long id, String status) { return prefix() + "\"granted\",\"wait\":\"" + id + "\",\"status\":\"" + status + "\"}}"; }
	/**
	 * Constructs a controlled receipt-shaped frame or wait identity.
	 * @param id native denial sequence
	 * @return identity belonging to this installation
	 */
	private static AllocationWaits.Wait nativeWait(long id) { return new AllocationWaits.Wait(1, id); }

	/**
	 * Models the existing Frida connection boundary only: queues copied frames and records controls. Frame reactions are
	 * supplied from retained native protocol cases; no target allocation, native lifetime or barrier behavior is simulated.
	 */
	private static final class Connection implements AllocationHook.Connection
	{
		/**
		 * Copied native messages waiting for the real owner to interpret them.
		 */
		private final ArrayDeque<String> frames = new ArrayDeque<>();
		/**
		 * Generated controls crossing the production post boundary, used to assert exact request count.
		 */
		private final List<JsonNode> posts = new ArrayList<>();
		/**
		 * Native-receipt-derived response supplied after a successful controlled post.
		 */
		private Consumer<JsonNode> onPost = _ -> {};
		/**
		 * Optional failure after the modeled native acquisition boundary is invoked.
		 */
		private IOException startFailure;
		/**
		 * Optional uncertain post effect, after the control has been recorded.
		 */
		private IOException postFailure;
		/**
		 * Optional sticky callback-copy error from the modeled native receiver.
		 */
		private IOException readFailure;
		/**
		 * Optional failed native destruction result; no actual native resource is acquired by this double.
		 */
		private IOException closeFailure;
		/**
		 * Number of destruction requests made by the actual Java owner.
		 */
		private int closes;
		/**
		 * Controlled unchecked acquisition failure after the start boundary is invoked.
		 */
		private Error startError;
		/**
		 * Controlled unchecked receiver failure, representing interruption of copied-message delivery.
		 */
		private Error readError;
		/**
		 * Controlled unchecked post failure after the outgoing control is recorded.
		 */
		private Error postError;
		/**
		 * Controlled unchecked native-destruction failure; no native resource is acquired by this double.
		 */
		private Error closeError;


		/**

		 * Creates an isolated, resource-free external boundary double.

		 */
		private Connection() {}
		@Override public void start(String source) throws IOException { if (startError != null) throw startError; if (startFailure != null) throw startFailure; }
		@Override public Optional<String> pollMessage() throws IOException { if (readError != null) throw readError; if (readFailure != null) throw readFailure; return Optional.ofNullable(frames.poll()); }
		@Override public void post(JsonNode permission) throws IOException { posts.add(permission); if (postError != null) throw postError; if (postFailure != null) throw postFailure; onPost.accept(permission); }
		@Override public void close() throws IOException { closes++; if (closeError != null) throw closeError; if (closeFailure != null) throw closeFailure; }
	}
}
