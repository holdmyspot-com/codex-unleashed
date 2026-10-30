package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks Windows jobserver ownership decisions with controlled native-operation outcomes.
 * The model proves no Windows kernel interoperability, process quiescence or lost-token recovery.
 */
public final class WindowsCargoJobserverTest
{
	/**
	 * Closes an acquired handle if reading its captured creation outcome fails before publication.
	 * @throws Exception if controlled binding construction fails
	 */
	@Test
	public void nativeCreationFailureClosesUnpublishedHandle() throws Exception
	{
		NativeModel model = new NativeModel();
		requireException(IndexOutOfBoundsException.class,
			() -> WindowsCargoJobserver.SharedSecrets.create(2, model.calls()));
		requireThat(model.creates, "successfulNativeAcquisitions").isEqualTo(1, "expected");
		requireThat(model.closes, "unpublishedHandleCloseAttempts").isEqualTo(1, "expected");
		requireThat(model.accessCloses, "nativeAccessCloseAttempts").isEqualTo(1, "expected");
	}

	/**
	 * Retains both failed creation publication and its independent ambiguous handle cleanup failure.
	 * @throws Exception if controlled binding construction fails
	 */
	@Test
	public void nativeCreationFailureRetainsHandleCloseFailure() throws Exception
	{
		NativeModel model = new NativeModel();
		model.failClose = true;
		var failure = requireException(WindowsCargoJobserver.PreparationFailure.class,
			() -> WindowsCargoJobserver.SharedSecrets.create(2, model.calls()));
		requireThat(failure.getCause() instanceof IndexOutOfBoundsException, "originalPublicationFailure").isTrue();
		requireThat(failure.getCause().getSuppressed().length, "cleanupFailures").isEqualTo(1, "expected");
		requireThat(failure.getCause().getSuppressed()[0].getCause() == model.closeFailure, "originalCleanupFailureIdentity").isTrue();
		requireException(IOException.class, failure::close);
		requireThat(model.creates, "successfulNativeAcquisitions").isEqualTo(1, "expected");
		requireThat(model.closes, "singleHandleCloseAttempt").isEqualTo(1, "expected");
		requireThat(model.accessCloses, "singleAccessCloseAttempt").isEqualTo(1, "expected");
	}

	/** Runs the actual NativeCalls transfer algorithm with deterministic downcall outcomes. */
	private static final class NativeModel implements Arena
	{
		/** Backing memory scope whose close is counted independently from HANDLE cleanup. */
		private final Arena arena = Arena.ofConfined();
		/** Number of successful modeled CreateSemaphoreExW calls. */
		private int creates;
		/** Number of modeled CloseHandle attempts. */
		private int closes;
		/** Number of native-access scope close attempts. */
		private int accessCloses;
		/** Requests an independently failing CloseHandle operation. */
		private boolean failClose;
		/** Exact cleanup failure retained alongside the original publication error. */
		private final IOException closeFailure = new IOException("Controlled native CloseHandle failure");

		/** Creates the controlled native scope. */
		private NativeModel() { }

		/**
		 * Supplies native-shaped bindings and deliberately undersized captured-state storage.
		 * @return production native adapter owning this scope
		 * @throws ReflectiveOperationException if fixture binding lookup fails
		 */
		private WindowsCargoJobserver.Calls calls() throws ReflectiveOperationException
		{
			var lookup = MethodHandles.lookup();
			var create = lookup.findVirtual(NativeModel.class, "create",
				MethodType.methodType(MemorySegment.class)).bindTo(this);
			create = MethodHandles.dropArguments(create, 0, MemorySegment.class, MemorySegment.class,
				int.class, int.class, MemorySegment.class, int.class, int.class);
			var close = lookup.findVirtual(NativeModel.class, "closeHandle",
				MethodType.methodType(int.class, MemorySegment.class)).bindTo(this);
			close = MethodHandles.dropArguments(close, 0, MemorySegment.class);
			return WindowsCargoJobserver.SharedSecrets.nativeCalls(this, arena.allocate(0), 0,
				create, null, null, close);
		}

		/**
		 * Models successful native acquisition before the captured-state read fails.
		 * @return acquired raw HANDLE
		 */
		private MemorySegment create()
		{
			creates++;
			return MemorySegment.ofAddress(42);
		}

		/**
		 * Counts the one native close attempt, optionally preserving an ambiguous failure.
		 * @param handle acquired HANDLE
		 * @return native success BOOL
		 * @throws IOException if requested cleanup fails
		 */
		private int closeHandle(MemorySegment handle) throws IOException
		{
			requireThat(handle.address(), "closedHandle").isEqualTo(42L, "acquiredHandle");
			closes++;
			if (failClose)
				throw closeFailure;
			return 1;
		}

		@Override
		public MemorySegment allocate(long size, long alignment) { return arena.allocate(size, alignment); }
		@Override
		public MemorySegment.Scope scope() { return arena.scope(); }
		@Override
		public void close()
		{
			accessCloses++;
			arena.close();
		}
	}

	/** Creates focused ownership checks. */
	public WindowsCargoJobserverTest() { }

	/**
	 * Preserves one implicit slot and one immutable invocation lease, rejecting premature owner cleanup.
	 * @throws Exception if owner cleanup fails
	 */
	@Test
	public void retainsOneImplicitSlotAndOneCargoLease() throws Exception
	{
		Model calls = new Model();
		try (var owner = WindowsCargoJobserver.SharedSecrets.create(2, calls))
		{
			requireThat(calls.available, "explicitTokens").isEqualTo(1, "jobsMinusOne");
			requireThat(calls.maximum, "maximumTokens").isEqualTo(2, "jobs");
			Map<String, String> original = new HashMap<>(Map.of("marker", "original"));
			var lease = owner.retainCargo(original);
			try (lease)
			{
				var environment = lease.environment();
				original.put("marker", "changed");
				requireThat(environment.get("marker"), "marker").isEqualTo("original", "expected");
				String flags = environment.get("CARGO_MAKEFLAGS");
				requireThat(environment.get("MAKEFLAGS"), "makeFlags").isEqualTo(flags, "cargoFlags");
				requireThat(environment.get("MFLAGS"), "legacyFlags").isEqualTo(flags, "cargoFlags");
				requireThat(flags, "auth").isEqualTo("-j --jobserver-fds=" + calls.name +
					" --jobserver-auth=" + calls.name, "pinnedClientFlags");
				requireException(UnsupportedOperationException.class, () -> environment.put("other", "value"));
				requireException(IllegalStateException.class, owner::close);
				requireThat(calls.waits, "waitsWhileLeased").isEqualTo(0, "expected");
				requireThat(calls.handleCloses, "retainedHandleCloseAttempts").isEqualTo(0, "expected");
				requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
			}
			requireException(IllegalStateException.class, lease::environment);
			lease.close();
			requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
		}
		requireThat(calls.handleCloses, "handleCloseAttempts").isEqualTo(1, "expected");
		requireThat(calls.accessCloses, "accessCloseAttempts").isEqualTo(1, "expected");
		requireThat(calls.available, "restoredObservedCount").isEqualTo(1, "expected");
	}

	/**
	 * Represents jobs=1 without seeding, acquiring or returning an extra implicit token.
	 * @throws Exception if owner cleanup fails
	 */
	@Test
	public void oneJobHasNoExplicitTokens() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(1, calls);
		requireThat(calls.available, "initialExplicitTokens").isEqualTo(0, "expected");
		requireThat(calls.maximum, "nativeMaximum").isEqualTo(1, "expected");
		owner.close();
		owner.close();
		requireThat(calls.returns, "returnCalls").isEqualTo(0, "expected");
		requireThat(calls.handleCloses, "singleClose").isEqualTo(1, "expected");
	}

	/**
	 * Rejects case-insensitive Windows environment collisions and null entries before consuming its lease.
	 * @throws Exception if owner cleanup fails
	 */
	@Test
	public void rejectsEnvironmentConflictsBeforeRetention() throws Exception
	{
		try (var owner = WindowsCargoJobserver.SharedSecrets.create(1, new Model()))
		{
			for (String variable : List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS", "cargo_makeflags", "MakeFlags", "mflags"))
				for (String value : List.of("", "-j8"))
					requireException(IllegalArgumentException.class, () -> owner.retainCargo(Map.of(variable, value)));
			requireException(NullPointerException.class, () -> owner.retainCargo(null));
			Map<String, String> malformed = new HashMap<>();
			malformed.put("key", null);
			requireException(NullPointerException.class, () -> owner.retainCargo(malformed));
			try (var lease = owner.retainCargo(Map.of()))
			{
				requireThat(lease.environment().size(), "ownedVariables").isEqualTo(3, "expected");
			}
		}
	}

	/**
	 * Rejects invalid capacities before transferring native access.
	 * @throws Exception if an unexpected owner is created
	 */
	@Test
	public void validatesBeforeAcquisition() throws Exception
	{
		Model calls = new Model();
		for (int jobs : List.of(0, -1))
		{
			requireException(IllegalArgumentException.class, () -> WindowsCargoJobserver.SharedSecrets.create(jobs, calls));
			requireException(IllegalArgumentException.class, () -> WindowsCargoJobserver.create(jobs));
		}
		requireException(NullPointerException.class, () -> WindowsCargoJobserver.SharedSecrets.create(1, null));
		requireThat(calls.creates, "nativeCreates").isEqualTo(0, "expected");
		requireThat(calls.accessCloses, "transfersBeforeValidation").isEqualTo(0, "expected");
	}

	/**
	 * Reports loss without returning an unobserved replacement token or retrying close.
	 * @throws Exception if setup fails
	 */
	@Test
	public void lostCapacityRemainsExplicitWithoutReseeding() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(2, calls);
		calls.available = 0;
		var mismatch = requireException(WindowsCargoJobserver.CapacityMismatch.class, owner::close);
		requireThat(mismatch.expectedTokens(), "expectedTokens").isEqualTo(1, "expected");
		requireThat(mismatch.observedTokens(), "observedTokens").isEqualTo(0, "expected");
		requireThat(calls.returns, "fabricatedReturns").isEqualTo(0, "expected");
		requireThat(calls.available, "remainingTokens").isEqualTo(0, "expected");
		requireException(IOException.class, owner::close);
		requireThat(calls.handleCloses, "closeAttempts").isEqualTo(1, "expected");
		requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
	}

	/**
	 * Reports excess capacity while restoring the actual observed count instead of resetting a baseline.
	 * @throws Exception if setup fails
	 */
	@Test
	public void excessCapacityRestoresOnlyObservedTokens() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(2, calls);
		calls.available = 2;
		var mismatch = requireException(WindowsCargoJobserver.CapacityMismatch.class, owner::close);
		requireThat(mismatch.observedTokens(), "observedTokens").isEqualTo(2, "expected");
		requireThat(calls.lastReturn, "returnedCount").isEqualTo(2, "actualObservedCount");
		requireThat(calls.available, "remainingTokens").isEqualTo(2, "notReseededBaseline");
	}

	/**
	 * Restores already-observed acquisitions after a failed wait, without treating the failure as timeout.
	 * @throws Exception if setup fails
	 */
	@Test
	public void failedWaitRestoresKnownAcquisitionsAndPreservesFailure() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(3, calls);
		calls.failWait = 2;
		IOException failure = requireException(IOException.class, owner::close);
		requireThat(failure == calls.waitFailure, "originalWaitFailure").isTrue();
		requireThat(calls.available, "restoredKnownTokens").isEqualTo(2, "initialCount");
		requireThat(calls.lastReturn, "returnedCount").isEqualTo(1, "onlyObservedAcquisition");
		requireThat(calls.handleCloses, "handleCleanup").isEqualTo(1, "expected");
	}

	/**
	 * Preserves a failed return and independent cleanup errors without retrying uncertain token operations.
	 * @throws Exception if setup fails
	 */
	@Test
	public void failedReturnAndCleanupRemainExplicit() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(2, calls);
		calls.failReturn = true;
		calls.failHandleClose = true;
		calls.failAccessClose = true;
		IOException failure = requireException(IOException.class, owner::close);
		requireThat(failure == calls.returnFailure, "originalReturnFailure").isTrue();
		requireThat(failure.getSuppressed().length, "cleanupFailures").isEqualTo(2, "expected");
		requireException(IOException.class, owner::close);
		requireThat(calls.returns, "returnAttempts").isEqualTo(1, "expected");
		requireThat(calls.handleCloses, "handleCloseAttempts").isEqualTo(1, "expected");
		requireThat(calls.accessCloses, "accessCloseAttempts").isEqualTo(1, "expected");
	}

	/**
	 * Never drains or seeds a generated-name collision belonging to another owner.
	 */
	@Test
	public void nameCollisionClosesOnlyItsOpenedHandle()
	{
		Model calls = new Model();
		calls.existing = true;
		requireException(IOException.class, () -> WindowsCargoJobserver.SharedSecrets.create(2, calls));
		requireThat(calls.waits, "foreignPoolAcquires").isEqualTo(0, "expected");
		requireThat(calls.returns, "foreignPoolReturns").isEqualTo(0, "expected");
		requireThat(calls.handleCloses, "openedHandleClose").isEqualTo(1, "expected");
		requireThat(calls.accessCloses, "accessClose").isEqualTo(1, "expected");
	}

	/**
	 * Retains failed setup and cleanup accounting without retrying an uncertain raw handle.
	 * @throws Exception if an unexpected setup path succeeds
	 */
	@Test
	public void failedPreparationRetainsItsCleanupOwner() throws Exception
	{
		Model calls = new Model();
		calls.existing = true;
		calls.failHandleClose = true;
		var failure = requireException(WindowsCargoJobserver.PreparationFailure.class,
			() -> WindowsCargoJobserver.SharedSecrets.create(2, calls));
		requireThat(failure.getCause().getSuppressed()[0] == calls.handleFailure, "setupCleanupFailure").isTrue();
		requireException(IOException.class, failure::close);
		requireThat(calls.handleCloses, "noRawHandleRetry").isEqualTo(1, "expected");
		requireThat(calls.accessCloses, "accessReleased").isEqualTo(1, "expected");
	}

	/**
	 * Preserves a close error when a try-with-resources scope closes again while unwinding that same error.
	 * @throws Exception if setup fails
	 */
	@Test
	public void repeatedFailedCloseDoesNotSelfSuppress() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(1, calls);
		calls.failHandleClose = true;
		IOException failure = requireException(IOException.class, () ->
		{
			try (owner) { owner.close(); }
		});
		requireThat(failure == calls.handleFailure, "originalCloseFailure").isTrue();
		requireThat(failure.getSuppressed().length, "repeatedCloseEvidence").isEqualTo(1, "expected");
		requireThat(failure.getSuppressed()[0].getCause() == failure, "originalCauseRetained").isTrue();
		requireThat(calls.handleCloses, "rawCloseAttempts").isEqualTo(1, "expected");
	}

	/**
	 * Rejects mutation from another thread before touching native access or releasing the live lease.
	 * @throws Exception if the worker or owner cleanup fails
	 */
	@Test
	public void controllerThreadOwnsLeaseAndCleanup() throws Exception
	{
		Model calls = new Model();
		try (var owner = WindowsCargoJobserver.SharedSecrets.create(1, calls);
			var lease = owner.retainCargo(Map.of()))
		{
			AtomicReference<Throwable> observed = new AtomicReference<>();
			Thread other = Thread.ofPlatform().start(() ->
			{
				try
				{
					requireException(IllegalStateException.class, owner::close);
					requireException(IllegalStateException.class, lease::close);
					requireException(IllegalStateException.class, lease::environment);
					requireException(IllegalStateException.class, () -> owner.retainCargo(Map.of()));
				}
				catch (Throwable failure) { observed.set(failure); }
			});
			other.join();
			requireThat(observed.get(), "unexpectedWorkerFailure").isNull();
			requireThat(calls.waits, "nativeWaitsFromOtherThread").isEqualTo(0, "expected");
			requireThat(lease.environment().size(), "retainedEnvironment").isEqualTo(3, "expected");
		}
	}

	/**
	 * Does not let a repeated external failure identity interrupt later cleanup.
	 * @throws Exception if setup fails
	 */
	@Test
	public void sharedFailureIdentityDoesNotSkipCleanup() throws Exception
	{
		Model calls = new Model();
		var owner = WindowsCargoJobserver.SharedSecrets.create(2, calls);
		calls.failReturn = true;
		calls.failHandleClose = true;
		calls.failAccessClose = true;
		calls.commonFailure = new IOException("One external failure reported by several cleanup operations");
		IOException failure = requireException(IOException.class, owner::close);
		requireThat(failure == calls.commonFailure, "originalFailureIdentity").isTrue();
		requireThat(calls.handleCloses, "handleCleanupAttempt").isEqualTo(1, "expected");
		requireThat(calls.accessCloses, "accessCleanupAttempt").isEqualTo(1, "expected");
	}

	/** Models documented external outcomes; it acquires no native Windows handles. */
	private static final class Model implements WindowsCargoJobserver.Calls
	{
		/** Remaining modeled explicit tokens; a deliberate change represents an external loss or excess. */
		private int available = 0;
		/** Native maximum requested by the production owner. */
		private int maximum = 0;
		/** Number of creation calls. */
		private int creates = 0;
		/** Number of zero-time waits. */
		private int waits = 0;
		/** Number of token-return calls. */
		private int returns = 0;
		/** Exact last requested return count. */
		private int lastReturn = 0;
		/** Number of raw handle close attempts. */
		private int handleCloses = 0;
		/** Number of native-access cleanup attempts. */
		private int accessCloses = 0;
		/** Name supplied by the production owner. */
		private String name = null;
		/** Indicates an existing pool; its count must not be inspected or modified by cleanup. */
		private boolean existing = false;
		/** One-based wait call on which a controlled failure occurs, or zero for normal waits. */
		private int failWait = 0;
		/** Indicates a failed return with unknown native result. */
		private boolean failReturn = false;
		/** Indicates raw handle cleanup failure. */
		private boolean failHandleClose = false;
		/** Indicates access-scope cleanup failure. */
		private boolean failAccessClose = false;
		/** Optional shared error identity used to verify cleanup aggregation without self-suppression. */
		private IOException commonFailure = null;
		/** Distinct controlled failure identities retained for outcome checks. */
		private final IOException waitFailure = new IOException("Controlled wait failure");
		/** Distinct controlled token-return failure. */
		private final IOException returnFailure = new IOException("Controlled token-return failure");
		/** Distinct controlled raw-handle release failure. */
		private final IOException handleFailure = new IOException("Controlled handle-close failure");
		/** Distinct controlled access-scope cleanup failure. */
		private final IOException accessFailure = new IOException("Controlled access-close failure");

		/** Creates a resource-free native-outcome model. */
		private Model() { }

		@Override
		public boolean create(String name, int initial, int maximum, Consumer<MemorySegment> acquired)
		{
			this.name = name;
			this.available = initial;
			this.maximum = maximum;
			this.creates++;
			acquired.accept(MemorySegment.ofAddress(42));
			return existing;
		}

		@Override
		public boolean tryAcquire(MemorySegment handle) throws IOException
		{
			waits++;
			if (waits == failWait)
				throw waitFailure;
			if (available == 0)
				return false;
			available--;
			return true;
		}

		@Override
		public void release(MemorySegment handle, int count) throws IOException
		{
			returns++;
			lastReturn = count;
			if (failReturn)
				throw commonFailure == null ? returnFailure : commonFailure;
			available += count;
		}

		@Override
		public void closeHandle(MemorySegment handle) throws IOException
		{
			handleCloses++;
			if (failHandleClose)
				throw commonFailure == null ? handleFailure : commonFailure;
		}

		@Override
		public void close() throws IOException
		{
			accessCloses++;
			if (failAccessClose)
				throw commonFailure == null ? accessFailure : commonFailure;
		}
	}
}
