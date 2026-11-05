package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoTokens;
import com.holdmyspot.codexunleashed.scheduler.MacCargoNative;
import com.holdmyspot.codexunleashed.scheduler.MacCargoTokenHook;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the actual codec, ledger and image owner using only copied-message and FIFO boundary doubles.
 * These cases do not authenticate or start a native process.
 */
public final class MacCargoTokenHookTest
{
	/** Fixed correlation identity independent of generated controls. */
	private static final UUID ATTEMPT = UUID.fromString("12345678-90ab-4cde-8fab-0123456789ab");

	/**
	 * Creates isolated owner checks without native resources.
	 */
	public MacCargoTokenHookTest() {}

	/**
	 * Requires independent detach and retains borrowed token ownership after exact-image reference retirement.
	 * @throws IOException if the accepted controlled lifecycle fails
	 */
	@Test public void detachedCleanupRetainsAttemptCapability() throws IOException
	{
		try (var f = ready())
		{
			requireThat(f.hook.detached(), "liveSessionIsNotDetached").isFalse();
			requireException(IOException.class, f.hook::closeDetached);
			requireThat(f.connection.closes, "liveConnectionRemainsOwned").isEqualTo(0);
			f.connection.detached = true;
			f.hook.closeDetached();
			f.hook.closeDetached();
			f.hook.close(() -> false);
			requireThat(f.connection.closes, "detachedReferenceReleasedOnce").isEqualTo(1);
			requireThat(f.transfer.writes, "imageRetirementDoesNotReturnAttemptBytes").isEqualTo(0);
			requireException(IOException.class, f.hook::pump);
		}
	}

	/**
	 * Retains uncertain detached destruction without repeating effects under a later whole-workload barrier.
	 * @throws IOException if valid setup fails
	 */
	@Test public void detachedCleanupFailureIsNotReplayed() throws IOException
	{
		try (var f = ready())
		{
			f.connection.detached = true;
			f.connection.closeFailure = new IOException("Uncertain native release");
			requireException(IOException.class, f.hook::closeDetached);
			requireException(IOException.class, () -> f.hook.close(() -> true));
			requireThat(f.connection.closes, "uncertainDestructionNeverReplayed").isEqualTo(1);
		}
	}

	/**
	 * Accepts an exact native readiness frame before any token effects.
	 * @throws IOException if owner startup or accepted readiness fails
	 */
	@Test
	public void acceptsMatchingReadiness() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.connection.frames.add(frame("ready", ""));
			fixture.hook.start();
			fixture.hook.awaitReady(() -> {});
			requireThat(fixture.connection.starts, "starts").isEqualTo(1, "expected");
			requireThat(fixture.connection.posts.isEmpty(), "noReadinessTransfer").isTrue();
			requireThat(fixture.transfer.reads, "noReadinessRead").isEqualTo(0, "expected");
		}
	}

	/**
	 * Reserves an exact byte before granting it and returns it before acknowledging the matching write.
	 * @throws IOException if valid transfers fail
	 */
	@Test
	public void borrowsAndReturnsExactByte() throws IOException
	{
		try (Fixture f = ready())
		{
			f.transfer.bytes.add(165);
			f.connection.frames.add(read(1));
			f.hook.pump();
			requireThat(f.transfer.bytes.isEmpty(), "reservedBeforeGrant").isTrue();
			requireThat(f.connection.posts.getFirst().path("byte").asInt(), "opaqueByte").isEqualTo(165, "expected");
			requireThat(f.connection.posts.getFirst().path("type").asString(), "sequenceChannel").isEqualTo("cargo-token-control-1", "expected");
			f.connection.frames.add(write(2, 165));
			f.hook.pump();
			requireThat(f.transfer.bytes.getFirst(), "returnedExactByte").isEqualTo(165, "expected");
			requireThat(f.transfer.writes, "oneReturn").isEqualTo(1, "expected");
			requireThat(f.connection.posts.getLast().has("byte"), "ackHasNoByte").isFalse();
			f.hook.close(() -> true);
			requireThat(f.attempt.isClosed(), "imageCloseRetainsAttempt").isFalse();
		}
	}

	/**
	 * Services a return while an earlier read waits for capacity, then grants that exact returned byte.
	 * @throws IOException if valid transfers fail
	 */
	@Test
	public void pendingReadDoesNotBlockReturn() throws IOException
	{
		try (Fixture f = ready())
		{
			f.connection.frames.add(read(1));
			f.hook.pump();
			requireThat(f.connection.posts.isEmpty(), "emptyFifoGrantsNothing").isTrue();
			f.transfer.bytes.add(255);
			f.hook.pump();
			f.connection.frames.add(read(2));
			f.connection.frames.add(write(3, 255));
			f.hook.pump();
			requireThat(f.connection.posts.size(), "threeControls").isEqualTo(3, "expected");
			requireThat(f.connection.posts.get(1).path("sequence").asString(), "returnBeforeWaitingGrant").isEqualTo("3", "expected");
			requireThat(f.connection.posts.get(2).path("sequence").asString(), "pendingReadGranted").isEqualTo("2", "expected");
			requireThat(f.connection.posts.getLast().path("byte").asInt(), "sameByteReborrowed").isEqualTo(255, "expected");
		}
	}

	/**
	 * Rejects duplicate and skipped sequences before any second or unauthorized read effect.
	 * @throws IOException if valid readiness fails
	 */
	@Test
	public void rejectsReplayAndSequenceGaps() throws IOException
	{
		for (boolean replay : List.of(false, true))
			try (Fixture f = ready())
			{
				f.transfer.bytes.add(81);
				if (replay) { f.connection.frames.add(read(1)); f.hook.pump(); }
				int previous = f.transfer.reads;
				f.connection.frames.add(read(replay ? 1 : 2));
				requireException(IOException.class, f.hook::pump);
				requireThat(f.transfer.reads, "noReplayRead").isEqualTo(previous, "expected");
				requireException(IOException.class, f.hook::pump);
			}
	}

	/**
	 * Retains an uncertain grant's reserved byte and never repeats its post or read.
	 * @throws IOException if valid readiness fails
	 */
	@Test
	public void uncertainGrantRemainsReservedWithoutResend() throws IOException
	{
		try (Fixture f = ready())
		{
			f.transfer.bytes.add(81);
			f.connection.postFailure = new IOException("Controlled uncertain grant");
			f.connection.frames.add(read(1));
			requireException(IOException.class, f.hook::pump);
			requireException(IOException.class, f.hook::pump);
			requireThat(f.connection.posts.size(), "singleUncertainPost").isEqualTo(1, "expected");
			requireThat(f.transfer.bytes.isEmpty(), "reservedUntilWholeAttemptRetirement").isTrue();
			f.hook.close(() -> true);
			requireThat(f.transfer.bytes.isEmpty(), "imageCloseDoesNotReclaim").isTrue();
			f.attempt.close();
			requireThat(f.transfer.bytes.getFirst(), "wholeAttemptReclaimsExactByte").isEqualTo(81, "expected");
		}
	}

	/**
	 * Does not repeat a confirmed return when its acknowledgment post has uncertain effects.
	 * @throws IOException if valid readiness or borrowing fails
	 */
	@Test
	public void uncertainAcknowledgmentDoesNotRepeatReturn() throws IOException
	{
		try (Fixture f = ready())
		{
			f.transfer.bytes.add(0);
			f.connection.frames.add(read(1));
			f.hook.pump();
			f.connection.postFailure = new IOException("Controlled uncertain acknowledgment");
			f.connection.frames.add(write(2, 0));
			requireException(IOException.class, f.hook::pump);
			requireException(IOException.class, f.hook::pump);
			f.attempt.close();
			requireThat(f.transfer.writes, "oneConfirmedReturn").isEqualTo(1, "expected");
			requireThat(f.transfer.bytes.size(), "noRetirementDuplicate").isEqualTo(1, "expected");
			requireThat(f.connection.posts.size(), "noAckResend").isEqualTo(2, "expected");
		}
	}

	/**
	 * Permits another authenticated image in the same logical attempt to return the original byte.
	 * @throws IOException if valid shared-attempt transfers fail
	 */
	@Test
	public void differentImageCanReturnWholeAttemptByte() throws IOException
	{
		try (Fixture f = ready())
		{
			f.transfer.bytes.add(165);
			f.connection.frames.add(read(1));
			f.hook.pump();
			Connection other = new Connection();
			MacCargoTokenHook hook = new MacCargoTokenHook(f.scope, ATTEMPT, 10, 43, new MacCargoNative.FifoIdentity(1,-1),
				other, f.attempt, "", Duration.ofSeconds(1), System::nanoTime);
			other.frames.add(frame("ready", "").replace("\"9\"", "\"10\"").replace("\"42\"", "\"43\""));
			hook.start(); hook.awaitReady(() -> {});
			other.frames.add(write(1,165).replace("\"9\"", "\"10\"").replace("\"42\"", "\"43\""));
			hook.pump(); hook.close(() -> true);
			requireThat(f.transfer.bytes.getFirst(), "crossImageOriginalByte").isEqualTo(165, "expected");
			requireThat(other.posts.getFirst().path("pid").asString(), "newImageAck").isEqualTo("43", "expected");
		}
	}

	/**
	 * Rejects unborrowed returns before the external FIFO boundary observes a write.
	 * @throws IOException if valid readiness fails
	 */
	@Test
	public void rejectsForeignReturnBeforeIo() throws IOException
	{
		try (Fixture f = ready())
		{
			f.connection.frames.add(write(1, 81));
			requireException(IOException.class, f.hook::pump);
			requireThat(f.transfer.writes, "noForeignReturn").isEqualTo(0, "expected");
			requireThat(f.connection.posts.isEmpty(), "noForeignAck").isTrue();
		}
	}

	/**
	 * Rejects duplicate readiness, native rejection, failure and script errors before token I/O.
	 * @throws IOException if valid initial readiness fails
	 */
	@Test
	public void nativeFaultsWithdrawAuthority() throws IOException
	{
		for (String fault : List.of(frame("ready", ""),
			frame("control-rejected", ",\"sequence\":\"1\",\"operation\":\"read\""),
			frame("failure", ",\"cause\":\"Request sequence exhausted\""),
			frame("failure", ",\"sequence\":\"1\",\"operation\":\"read\",\"cause\":\"Descriptor statx failed\""),
			"{\"type\":\"error\",\"description\":\"controlled native fault\",\"stack\":\"native stack\"}", "{}"))
			try (Fixture f = ready())
			{
				f.connection.frames.add(fault);
				requireException(IOException.class, f.hook::pump);
				requireThat(f.hook.failure(), "retainedNativeFailure").isNotNull();
				requireException(IOException.class, f.hook::pump);
				requireThat(f.transfer.reads + f.transfer.writes, "noFaultTransfers").isEqualTo(0, "expected");
			}
	}

	/**
	 * Withdraws authority on transfers before readiness and on missing readiness without reloading.
	 * @throws IOException if native start fails
	 */
	@Test
	public void readinessIsRequiredAndBounded() throws IOException
	{
		for (boolean early : List.of(false, true))
			try (Fixture f = new Fixture())
			{
				if (early) f.connection.frames.add(read(1));
				f.hook.start();
				requireException(IOException.class, () -> f.hook.awaitReady(() -> {}));
				requireException(IOException.class, f.hook::start);
				requireThat(f.connection.starts, "noReload").isEqualTo(1, "expected");
				requireThat(f.transfer.reads, "noEarlyRead").isEqualTo(0, "expected");
			}
	}

	/**
	 * Bounds each pump's copied-frame and grant work while preserving the remaining pending operations.
	 * @throws IOException if valid transfers fail
	 */
	@Test
	public void boundsEachPumpForPeerFairness() throws IOException
	{
		try (Fixture f = ready())
		{
			for (int i = 1; i <= 130; i++) { f.connection.frames.add(read(i)); f.transfer.bytes.add(i); }
			f.hook.pump();
			requireThat(f.connection.posts.size(), "firstFairRound").isEqualTo(64, "expected");
			f.hook.pump();
			requireThat(f.connection.posts.size(), "secondFairRound").isEqualTo(128, "expected");
			f.hook.pump();
			requireThat(f.connection.posts.size(), "finalFairRound").isEqualTo(130, "expected");
		}
	}

	/**
	 * Keeps the image connection while the whole execution barrier is false or unavailable.
	 * @throws IOException if startup or later safe cleanup fails
	 */
	@Test
	public void barrierPrecedesNativeDestruction() throws IOException
	{
		try (Fixture f = ready())
		{
			requireException(IOException.class, () -> f.hook.close(() -> false));
			requireException(IOException.class, () -> f.hook.close(() -> { throw new IOException("Unknown barrier"); }));
			requireThat(f.connection.closes, "retainedOwner").isEqualTo(0, "expected");
			requireException(IOException.class, f.hook::pump);
			f.hook.close(() -> true); f.hook.close(() -> true);
			requireThat(f.connection.closes, "closedOnce").isEqualTo(1, "expected");
			requireThat(f.attempt.isClosed(), "borrowedAttemptRemains").isFalse();
		}
	}

	/**
	 * Retains partial start and destruction failures, including Errors, without retrying uncertain cleanup.
	 * @throws IOException if fixture creation fails
	 */
	@Test
	public void retainsPartialStartAndStickyCleanup() throws IOException
	{
		for (boolean fatal : List.of(false, true))
			try (Fixture f = new Fixture())
			{
				Throwable first = fatal ? new AssertionError("Controlled start Error") : new IOException("Controlled partial start");
				f.connection.startFailure = first;
				if (fatal) requireThat(requireException(AssertionError.class, f.hook::start), "originalError").isEqualTo(first, "expected");
				else requireException(IOException.class, f.hook::start);
				requireThat(f.hook.failure().getCause(), "firstCause").isEqualTo(first, "expected");
				f.connection.closeFailure = fatal ? new AssertionError("Controlled close Error") : new IOException("Controlled close");
				if (fatal) requireException(AssertionError.class, () -> f.hook.close(() -> true));
				else requireException(IOException.class, () -> f.hook.close(() -> true));
				requireException(IOException.class, () -> f.hook.close(() -> true));
				requireThat(f.connection.closes, "noCleanupRetry").isEqualTo(1, "expected");
			}
	}

	/**
	 * Propagates an original posting Error while preserving its reservation and first operational failure.
	 * @throws IOException if startup fails
	 */
	@Test
	public void postingErrorRetainsReservation() throws IOException
	{
		try (Fixture f = ready())
		{
			f.transfer.bytes.add(81);
			AssertionError original = new AssertionError("Controlled posting Error");
			f.connection.postFailure = original;
			f.connection.frames.add(read(1));
			requireThat(requireException(AssertionError.class, f.hook::pump), "originalPostingError").isEqualTo(original, "expected");
			requireThat(f.hook.failure().getCause(), "retainedPostingCause").isEqualTo(original, "expected");
			requireException(IOException.class, f.hook::pump);
			requireThat(f.transfer.bytes.isEmpty(), "errorReservationRetained").isTrue();
		}
	}

	/**
	 * Supplies one ready resource-free owner to a behavior check.
	 * @return fixture retaining its actual scope, ledger and image owner
	 * @throws IOException if startup or readiness fails
	 */
	private static Fixture ready() throws IOException
	{
		Fixture f = new Fixture();
        try
        {
            f.connection.frames.add(frame("ready", ""));
            f.hook.start();
            f.hook.awaitReady(() -> {});
            return f;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { f.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
	}

	/**
	 * Supplies a native read request in its observed wire shape.
	 * @param sequence native request number
	 * @return independent read frame
	 */
	private static String read(long sequence)
	{
		return frame("transfer", ",\"sequence\":\"" + sequence + "\",\"operation\":\"read\",\"blocking\":true");
	}

	/**
	 * Supplies a native write request in its observed wire shape.
	 * @param sequence native request number
	 * @param value opaque unsigned byte
	 * @return independent write frame
	 */
	private static String write(long sequence, int value)
	{
		return frame("transfer", ",\"sequence\":\"" + sequence + "\",\"operation\":\"write\",\"byte\":" + value);
	}

	/**
	 * Builds receipt-shaped native frames independently of the codec's control encoder.
	 * @param event native event name
	 * @param extra additional members with their leading comma
	 * @return complete Frida send envelope
	 */
	private static String frame(String event, String extra)
	{
		return "{\"type\":\"send\",\"payload\":{\"protocol\":\"codex-build-cargo-tokens\",\"version\":1," +
			"\"attempt\":\"" + ATTEMPT + "\",\"installation\":\"9\",\"pid\":\"42\",\"event\":\"" + event + "\"" + extra + "}}";
	}

	/**
	 * Owns a real scope, ledger and logical attempt around resource-free external boundaries.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/** Shared immutable tooling values retained through the owner. */
		private final DefaultToolingScope scope = new DefaultToolingScope();
		/** Native FIFO boundary recording exact effects. */
		private final Transfer transfer = new Transfer();
		/** Actual invocation accounting owner. */
		private final LinuxCargoTokens ledger = new LinuxCargoTokens(transfer);
		/** Actual whole-attempt capability borrowed by the hook. */
		private final LinuxCargoTokens.Attempt attempt;
		/** Copied-message native connection boundary. */
		private final Connection connection = new Connection();
		/** Actual image owner under test. */
		private final MacCargoTokenHook hook;

		/**
		 * Constructs isolated real collaborators with a deterministic readiness clock.
		 * @throws IOException if the new ledger rejects attempt retention
		 */
		private Fixture() throws IOException
		{
			this.attempt = ledger.retainAttempt();
			long[] now = {0};
			this.hook = new MacCargoTokenHook(scope, ATTEMPT, 9, 42, new MacCargoNative.FifoIdentity(1, -1),
				connection, attempt, "", Duration.ofNanos(3), () -> now[0]++);
		}

		/**
		 * Retires the resource-free attempt and then its ledger and scope.
		 * @throws IOException if a controlled token return fails during retirement
		 */
		@Override
		public void close() throws IOException
		{
			try { attempt.close(); }
			finally { ledger.close(); scope.close(); }
		}
	}

	/**
	 * Models only copied message delivery, posting uncertainty and native ownership calls.
	 */
	private static final class Connection implements AllocationHook.Connection
	{
		/** Copied frames in callback delivery order. */
		private final ArrayDeque<String> frames = new ArrayDeque<>();
		/** Exact controls posted by the real owner. */
		private final List<JsonNode> posts = new ArrayList<>();
		/** Number of native start attempts. */
		private int starts;
		/** Number of attempted native destructions. */
		private int closes;
		/** Independent exact-session detach observation. */
		private boolean detached;
		/** Optional checked or fatal partial-start result. */
		private Throwable startFailure;
		/** Optional checked or fatal uncertain posting result. */
		private Throwable postFailure;
		/** Optional checked or fatal uncertain native destruction result. */
		private Throwable closeFailure;

		/**
		 * Creates an empty copied-message boundary.
		 */
		private Connection() {}

		@Override public void start(String source) throws IOException { starts++; fail(startFailure); }
		@Override public Optional<String> pollMessage() { return Optional.ofNullable(frames.poll()); }
		@Override public void post(JsonNode control) throws IOException { posts.add(control); fail(postFailure); }
		@Override public boolean detached() { return detached; }
		@Override public void close() throws IOException { closes++; fail(closeFailure); }

		/**
		 * Delivers a controlled external failure without changing its identity.
		 * @param failure optional failure at the native boundary
		 * @throws IOException if the controlled checked failure is present
		 */
		private static void fail(Throwable failure) throws IOException
		{
			if (failure instanceof Error error) throw error;
			if (failure instanceof IOException checked) throw checked;
		}
	}

	/**
	 * Models only nonblocking FIFO effects; the actual ledger retains and retires byte occurrences.
	 */
	private static final class Transfer implements LinuxCargoTokens.Transfer
	{
		/** Bytes currently available at the external FIFO boundary. */
		private final ArrayDeque<Integer> bytes = new ArrayDeque<>();
		/** Number of actual nonblocking read attempts. */
		private int reads;
		/** Number of external one-byte write effects. */
		private int writes;

		/**
		 * Creates an empty external FIFO boundary.
		 */
		private Transfer() {}

		@Override
		public OptionalInt tryRead()
		{
			reads++;
			Integer value = bytes.poll();
			return value == null ? OptionalInt.empty() : OptionalInt.of(value);
		}

		@Override public void write(byte token) { writes++; bytes.add(Byte.toUnsignedInt(token)); }
	}
}
