package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.secrets.WindowsCargoJobserverAccess;
import com.holdmyspot.codexunleashed.secrets.WindowsCargoJobserverSecrets;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.Serial;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owns one same-user Windows named-semaphore jobserver for one root Cargo invocation.
 * Cargo has one implicit outer slot; the semaphore starts with the remaining {@code jobs - 1} tokens.
 * The controller must retain its single Cargo lease until Cargo, every wrapper and every compiler descendant
 * is terminal and reaped. This owner launches no processes and cannot establish that condition itself.
 * <p>
 * All operations, including lease release, belong to the creating thread. The non-inherited owner handle
 * remains live while consumers import its name. Default creator security permits same-user use; this class
 * does not establish another user's authorization. Missing tokens are reported at quiescent close, never minted.
 */
public final class WindowsCargoJobserver implements AutoCloseable
{
	/** Variables emitted by pinned jobserver 0.1.34 configure_make. */
	private static final Set<String> MAKE_VARIABLES = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS");

	static
	{
		WindowsCargoJobserverSecrets.setWindowsCargoJobserverAccess(new WindowsCargoJobserverAccess()
		{
			@Override
			public WindowsCargoJobserver create(int jobs, Calls calls) throws IOException
			{
				validateJobs(jobs);
				return acquire(jobs, calls);
			}

			@Override
			public Calls nativeCalls(Arena arena, MemorySegment errors, long errorOffset, NativeFunctions functions)
			{
				return new NativeCalls(arena, errors, errorOffset, functions);
			}
		});
	}
	/** The only thread allowed to mutate this owner or release its lease. */
	private final Thread controller = Thread.currentThread();
	/** Same-session ASCII name interoperable with pinned jobserver's OpenSemaphoreA. */
	private final String name = "Local\\codex-unleashed-jobserver-" + UUID.randomUUID();
	/** Native operations and their library scope, transferred at validated acquisition. */
	private final Calls calls;
	/** Initial explicit capacity, excluding the one implicit Cargo slot. */
	private final int expectedTokens;
	/** Owned handle, or NULL before acquisition and after its one close attempt. */
	private MemorySegment handle = MemorySegment.NULL;
	/** Indicates whether creation acquired a new pool rather than opening a name collision. */
	private boolean newPool = false;
	/** Indicates whether the sole invocation lease was ever issued. */
	private boolean retained = false;
	/** Live invocation borrow, or null before retention and after release. */
	private CargoLease lease = null;
	/** Permanently bars new work once close begins, including after a failed close. */
	private boolean closing = false;
	/** Original close failure and suppressed cleanup errors, retained rather than silently cleared. */
	private Throwable closeFailure = null;

	/**
	 * Acquires resource-free access under its owner before creating a semaphore.
	 * @param jobs positive capacity including the implicit slot
	 * @param calls owned native access
	 */
	private WindowsCargoJobserver(int jobs, Calls calls)
	{
		this.expectedTokens = jobs - 1;
		this.calls = Objects.requireNonNull(calls, "calls may not be null");
	}

	/**
	 * Creates a same-user, non-inherited semaphore before any Cargo consumer starts.
	 * Capacity is one implicit slot plus {@code jobs - 1} semaphore tokens. Windows LONG bounds are
	 * represented exactly by positive Java int. An existing generated name is rejected without changing its count.
	 * @param scope borrowed native services, kept open until this jobserver and its consumers close
	 * @param jobs positive invocation capacity including its implicit slot
	 * @return sole owner of the new named pool
	 * @throws IllegalArgumentException if jobs is nonpositive
	 * @throws NullPointerException if scope is null
	 * @throws UnsupportedOperationException if native 64-bit Windows or required FFM symbols are unavailable
	 * @throws IOException if creation or cleanup fails
	 * @throws PreparationFailure if failed acquisition retains an unresolved cleanup failure
	 */
	public static WindowsCargoJobserver create(ToolingScope scope, int jobs) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		validateJobs(jobs);
		if (!System.getProperty("os.name", "").startsWith("Windows") ||
			ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("Named Cargo jobserver ownership requires native 64-bit Windows");
		return acquire(jobs, new NativeCalls(scope));
	}

	/**
	 * Validates before transferring native access or acquiring a handle.
	 * @param jobs invocation capacity including the implicit slot
	 */
	private static void validateJobs(int jobs)
	{
		if (jobs <= 0)
			throw new IllegalArgumentException("jobs must be positive: " + jobs);
	}

	/**
	 * Creates once without reseeding an existing pool; failed setup closes acquired resources.
	 * @param jobs validated positive invocation capacity
	 * @param calls owned native access
	 * @return fully acquired owner
	 * @throws IOException if creation or cleanup fails
	 */
	private static WindowsCargoJobserver acquire(int jobs, Calls calls) throws IOException
	{
		Objects.requireNonNull(calls, "calls may not be null");
		WindowsCargoJobserver owner = null;
		try
		{
			owner = new WindowsCargoJobserver(jobs, calls);
			// Allocate the ownership receiver before any native acquisition. Its body only stores the handle.
			WindowsCargoJobserver pending = owner;
			boolean alreadyExists = calls.create(owner.name, jobs - 1, jobs, acquired -> pending.handle = acquired);
			if (owner.handle.address() == 0)
				throw new IOException("CreateSemaphoreExW returned NULL");
			if (alreadyExists)
				throw new IOException("Generated Cargo jobserver name already exists: " + owner.name);
			owner.newPool = true;
			return owner;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try
			{
				if (owner == null)
					calls.close();
				else
					owner.close();
			}
			catch (IOException | RuntimeException | Error cleanup)
			{
				combine(failure, cleanup);
				if (owner != null)
					throw new PreparationFailure(failure, owner);
			}
			throw failure;
		}
	}

	/**
	 * Reserves the sole invocation and copies its complete launch environment.
	 * Windows environment names are case-insensitive, so any casing of an owned make variable is rejected,
	 * including an explicitly empty value. Validation failure does not consume the one invocation.
	 * @param environment complete root environment without any owned make variable
	 * @return immutable environment lease that must outlive every consumer and retry
	 * @throws NullPointerException if the map or an entry is null
	 * @throws IllegalArgumentException if any owned make variable is present
	 * @throws IllegalStateException if called from another thread, closing, or already retained
	 */
	public CargoLease retainCargo(Map<String, String> environment)
	{
		requireController();
		Map<String, String> copy = new HashMap<>(Map.copyOf(environment));
		for (String key : copy.keySet())
			for (String owned : MAKE_VARIABLES)
				if (key.equalsIgnoreCase(owned))
					throw new IllegalArgumentException("Root Cargo environment already contains " + key);
		if (closing || retained)
			throw new IllegalStateException("Jobserver is closing or its single Cargo invocation was already retained");
		String flags = "-j --jobserver-fds=" + name + " --jobserver-auth=" + name;
		for (String variable : MAKE_VARIABLES)
			copy.put(variable, flags);
		CargoLease result = new CargoLease(Map.copyOf(copy));
		retained = true;
		lease = result;
		return result;
	}

	/**
	 * Requires invocation quiescence, compares actual capacity, then closes owned resources once.
	 * A live lease rejects close before any native operation. Once its coordinator releases the lease, it
	 * must prevent new starts and have reaped every consumer; root-only exit is insufficient.
	 * The count check temporarily drains and restores only observed tokens. Lost or excess capacity fails
	 * explicitly; no baseline is reseeded. Cleanup still runs on mismatch or native failure.
	 * Repeated close returns after success or reports its retained failure without retrying native operations.
	 * @throws IllegalStateException if called from another thread or the invocation lease remains live
	 * @throws CapacityMismatch if quiescent capacity differs from the initial explicit capacity
	 * @throws IOException if a native operation or cleanup fails
	 */
	@Override
	public void close() throws IOException
	{
		requireController();
		if (lease != null)
			throw new IllegalStateException("Cargo consumers still retain the jobserver lease");
		if (closing)
		{
			if (closeFailure != null)
				throw new IOException("Previous Cargo jobserver close failed; native operations were not retried", closeFailure);
			return;
		}
		closing = true;
		Throwable failure = null;
		if (newPool)
		{
			try { verifyCapacity(); }
			catch (Throwable outcome) { failure = outcome; }
		}
		if (handle.address() != 0)
		{
			MemorySegment owned = handle;
			handle = MemorySegment.NULL;
			try { calls.closeHandle(owned); }
			catch (Throwable cleanup) { failure = combine(failure, cleanup); }
		}
		try { calls.close(); }
		catch (Throwable cleanup) { failure = combine(failure, cleanup); }
		closeFailure = failure;
		rethrow(failure);
	}

	/**
	 * Observes only a quiescent pool, restoring each observed acquisition even on a later failed wait.
	 * @throws IOException if waiting, returning observed tokens or the capacity comparison fails
	 */
	private void verifyCapacity() throws IOException
	{
		int observed = 0;
		Throwable failure = null;
		try
		{
			while (calls.tryAcquire(handle))
				observed++;
			if (observed != expectedTokens)
				throw new CapacityMismatch(expectedTokens, observed);
		}
		catch (Throwable outcome) { failure = outcome; }
		if (observed > 0)
		{
			try { calls.release(handle, observed); }
			catch (Throwable cleanup) { failure = combine(failure, cleanup); }
		}
		rethrow(failure);
	}

	/**
	 * Enforces ownership before touching a thread-confined native scope or lease.
	 */
	private void requireController()
	{
		if (Thread.currentThread() != controller)
			throw new IllegalStateException("Cargo jobserver operations require the creating controller thread");
	}

	/**
	 * Retains independent cleanup outcomes without losing the original operation failure.
	 * @param first earlier failure, or null
	 * @param next later cleanup failure
	 * @return aggregate retaining original identity
	 */
	private static Throwable combine(Throwable first, Throwable next)
	{
		if (first == null)
			return next;
		if (first != next)
			first.addSuppressed(next);
		return first;
	}

	/**
	 * Preserves checked native failures and unchecked JVM outcomes after cleanup.
	 * @param failure nullable terminal outcome
	 * @throws IOException if the outcome is a checked native failure
	 */
	private static void rethrow(Throwable failure) throws IOException
	{
		if (failure == null)
			return;
		switch (failure)
		{
			case IOException checked -> throw checked;
			case RuntimeException unchecked -> throw unchecked;
			case Error error -> throw error;
			default -> throw new IOException("Windows Cargo jobserver operation failed", failure);
		}
	}

	/** Retains one immutable launch context until the coordinator proves complete consumer quiescence. */
	public final class CargoLease implements AutoCloseable
	{
		/** Copied root environment shared unchanged through all invocation launch boundaries. */
		private final Map<String, String> environment;
		/** Indicates whether the coordinator released this lease. */
		private boolean released = false;
		/** Live controller ledgers that must retire before the invocation borrow ends. */
		private int tokenLedgers = 0;
		/** First uncertain token operation; later operations must never replay it. */
		private IOException tokenFailure = null;

		/**
		 * Retains a validated complete environment.
		 * @param environment copied immutable launch environment
		 */
		private CargoLease(Map<String, String> environment) { this.environment = environment; }

		/**
		 * Reports the pool's initial explicit capacity without touching the semaphore.
		 * @return explicit tokens in a quiescent pool, excluding Cargo's implicit slot
		 */
		int capacity() { return expectedTokens; }

		/**
		 * Returns the complete launch environment while the invocation is retained.
		 * @return immutable named-jobserver environment
		 * @throws IllegalStateException if released or accessed from another thread
		 */
		public Map<String, String> environment()
		{
			requireController();
			if (released)
				throw new IllegalStateException("Cargo jobserver lease was released");
			return environment;
		}

		/**
		 * Retains controller accounting before its first possible native reservation.
		 * @throws IllegalStateException if the lease is released or used off-thread
		 */
		void retainTokenLedger()
		{
			environment();
			++tokenLedgers;
		}

		/**
		 * Releases one completely retired token ledger.
		 * @throws IllegalStateException if no ledger remains or accessed off-thread
		 */
		void releaseTokenLedger()
		{
			environment();
			if (tokenLedgers == 0)
				throw new IllegalStateException("No retained token ledger remains");
			--tokenLedgers;
		}

		/**
		 * Tries one atomic zero-time semaphore reservation for an already retained ledger.
		 * @return true on acquisition, or false when no explicit capacity is available
		 * @throws IllegalStateException if no ledger is retained, released, or used off-thread
		 * @throws IOException if this or an earlier native token operation is uncertain
		 */
		boolean tryAcquireToken() throws IOException
		{
			requireTokenAuthority();
			try { return calls.tryAcquire(handle); }
			catch (IOException | RuntimeException | Error cause)
			{
				tokenFailure = new IOException("Cargo semaphore acquisition failed; its count is uncertain", cause);
				throw tokenFailure;
			}
		}

		/**
		 * Returns one definitely owned explicit token without retrying an uncertain effect.
		 * @throws IllegalStateException if no ledger is retained, released, or used off-thread
		 * @throws IOException if this or an earlier native token operation is uncertain
		 */
		void returnToken() throws IOException
		{
			requireTokenAuthority();
			try { calls.release(handle, 1); }
			catch (IOException | RuntimeException | Error cause)
			{
				tokenFailure = new IOException("Cargo semaphore return failed; its count is uncertain", cause);
				throw tokenFailure;
			}
		}

		/**
		 * Returns the invocation's named semaphore identity without parsing MAKEFLAGS.
		 * @return retained pool name
		 * @throws IllegalStateException if released or called off-thread
		 */
		String semaphoreName() { environment(); return name; }

		/**
		 * Observes available explicit capacity by temporarily owning and restoring only actual acquisitions.
		 * Consumers may run concurrently, so the observation is approximate, as is Rust jobserver's available probe.
		 * No synthetic token is introduced. A failed wait or restore permanently poisons count authority.
		 * @return count of actual acquisitions observed and restored by this probe
		 * @throws IllegalStateException if no ledger remains, released, or called off-thread
		 * @throws IOException if acquisition or restoration fails
		 */
		int availableTokens() throws IOException
		{
			requireTokenAuthority();
			int observed = 0;
			try
			{
				while (calls.tryAcquire(handle)) ++observed;
			}
			catch (IOException | RuntimeException | Error cause)
			{
				tokenFailure = new IOException("Cargo semaphore availability observation failed", cause);
				if (observed > 0)
				{
					try { calls.release(handle, observed); }
					catch (IOException | RuntimeException | Error cleanup) { tokenFailure.addSuppressed(cleanup); }
				}
				throw tokenFailure;
			}
			if (observed > 0)
			{
				try { calls.release(handle, observed); }
				catch (IOException | RuntimeException | Error cause)
				{
					tokenFailure = new IOException("Cargo semaphore availability restoration failed", cause);
					throw tokenFailure;
				}
			}
			return observed;
		}

		/**
		 * Checks the live borrow before any count-changing operation.
		 * @throws IllegalStateException if no ledger is retained, released, or used off-thread
		 * @throws IOException if a previous native effect is uncertain
		 */
		private void requireTokenAuthority() throws IOException
		{
			environment();
			if (tokenLedgers == 0)
				throw new IllegalStateException("Token operations require a retained controller ledger");
			if (tokenFailure != null)
				throw tokenFailure;
		}

		/**
		 * Releases after every consumer is terminal/reaped and no further starts are possible.
		 * This is the coordinator's assertion, not a process-liveness check. Repeated release has no effect.
		 * @throws IllegalStateException if called from another thread
		 */
		@Override
		public void close()
		{
			requireController();
			if (released)
				return;
			if (tokenLedgers > 0)
				throw new IllegalStateException("Controller token ledgers still retain the Cargo lease");
			released = true;
			lease = null;
		}
	}

	/** Reports an observed quiescent capacity mismatch without claiming which consumer lost or added tokens. */
	public static final class CapacityMismatch extends IOException
	{
		/** Serialization version for the immutable count discrepancy. */
		@Serial
		private static final long serialVersionUID = 1L;
		/** Expected explicit tokens, excluding the implicit Cargo slot. */
		private final int expectedTokens;
		/** Actual tokens observed and restored before cleanup. */
		private final int observedTokens;

		/**
		 * Retains the actual count discrepancy.
		 * @param expectedTokens initial explicit capacity
		 * @param observedTokens actual quiescent capacity
		 */
		private CapacityMismatch(int expectedTokens, int observedTokens)
		{
			super("Quiescent Cargo jobserver capacity differs: expected " + expectedTokens + ", observed " + observedTokens);
			this.expectedTokens = expectedTokens;
			this.observedTokens = observedTokens;
		}

		/**
		 * Returns the invocation's initial explicit token capacity.
		 * @return expected explicit tokens, excluding the implicit slot
		 */
		public int expectedTokens() { return expectedTokens; }
		/**
		 * Returns the actual count observed during quiescent close.
		 * @return observed tokens, without inferring an individual consumer's borrow
		 */
		public int observedTokens() { return observedTokens; }
	}

	/** Retains a failed acquisition and its terminal unresolved cleanup owner; close never retries a raw handle. */
	public static final class PreparationFailure extends IOException implements AutoCloseable
	{
		/** Serialization version inherited from the checked failure API. */
		@Serial
		private static final long serialVersionUID = 1L;
		/** Owner preserving its original cleanup outcome and handle-release accounting. */
		// Native cleanup authority cannot be serialized. Keeping it non-transient deliberately rejects serialization
		// rather than producing a deserialized exception whose close silently loses its live owner.
		@SuppressWarnings("serial")
		private final WindowsCargoJobserver owner;

		/**
		 * Retains setup and cleanup evidence.
		 * @param cause original setup failure with suppressed cleanup failure
		 * @param owner partial owner whose cleanup was attempted once
		 */
		private PreparationFailure(Throwable cause, WindowsCargoJobserver owner)
		{
			super("Cargo jobserver preparation failed and native cleanup remains unresolved", cause);
			this.owner = owner;
		}

		/**
		 * Re-emits the retained cleanup outcome; a potentially consumed handle is never retried.
		 * @throws IOException if cleanup remains unresolved
		 */
		@Override
		public void close() throws IOException { owner.close(); }
	}

	/** Native operations preserving acquired/timeout/failed outcomes and explicit handle ownership. */
	public interface Calls extends AutoCloseable
	{
		/**
		 * Creates or opens the generated name without changing an existing pool's count.
		 * @param name ASCII same-session name
		 * @param initial initial explicit tokens
		 * @param maximum positive native maximum
		 * @param acquired preallocated receiver that immediately stores ownership and cannot throw
		 * @return exact existing-name outcome after handle ownership has transferred
		 * @throws IOException if native creation fails
		 */
		boolean create(String name, int initial, int maximum, Consumer<MemorySegment> acquired) throws IOException;
		/**
		 * Acquires once without waiting.
		 * @param handle retained semaphore
		 * @return true only for an acquired token; false only for timeout
		 * @throws IOException if the wait fails
		 */
		boolean tryAcquire(MemorySegment handle) throws IOException;
		/**
		 * Returns exactly the positive count observed by the owner, without reseeding a baseline.
		 * @param handle retained semaphore
		 * @param count positive observed acquisitions to restore
		 * @throws IOException if native return fails
		 */
		void release(MemorySegment handle, int count) throws IOException;
		/**
		 * Attempts one handle release; the caller must not retry an ambiguous failed release.
		 * @param handle owned semaphore
		 * @throws IOException if CloseHandle fails
		 */
		void closeHandle(MemorySegment handle) throws IOException;
		/**
		 * Releases this access object's library scope after the owner handle close attempt.
		 * @throws IOException if scope cleanup fails
		 */
		@Override
		void close() throws IOException;
	}

	/** Native semaphore functions with their Windows ABI; each receives the storage that captures GetLastError. */
	public interface NativeFunctions
	{
		/**
		 * Creates or opens a named semaphore.
		 * @param errors captured native error storage
		 * @param attributes security attributes, or NULL
		 * @param initial initial count
		 * @param maximum maximum count
		 * @param name wide name
		 * @param flags creation flags
		 * @param access requested access rights
		 * @return the semaphore HANDLE, or NULL on failure
		 * @throws IOException if the native invocation raises a checked failure
		 */
		MemorySegment createSemaphore(MemorySegment errors, MemorySegment attributes, int initial, int maximum,
			MemorySegment name, int flags, int access) throws IOException;

		/**
		 * Waits on a handle.
		 * @param errors captured native error storage
		 * @param handle waited handle
		 * @param milliseconds wait duration
		 * @return the native wait result
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int waitForSingleObject(MemorySegment errors, MemorySegment handle, int milliseconds) throws IOException;

		/**
		 * Returns tokens to a semaphore.
		 * @param errors captured native error storage
		 * @param handle semaphore handle
		 * @param count returned tokens
		 * @param previous previous-count output, or NULL
		 * @return the native BOOL result
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int releaseSemaphore(MemorySegment errors, MemorySegment handle, int count, MemorySegment previous)
			throws IOException;

		/**
		 * Closes a handle.
		 * @param errors captured native error storage
		 * @param handle closed handle
		 * @return the native BOOL result
		 * @throws IOException if the native invocation raises a checked failure
		 */
		int closeHandle(MemorySegment errors, MemorySegment handle) throws IOException;
	}

	/** Calls the bound Kernel32 functions without collapsing native wait failures into timeouts. */
	private static final class BoundFunctions implements NativeFunctions
	{
		/** HANDLE CreateSemaphoreExW(SECURITY_ATTRIBUTES*,LONG,LONG,LPCWSTR,DWORD,DWORD). */
		private final MethodHandle create;
		/** DWORD WaitForSingleObject(HANDLE,DWORD). */
		private final MethodHandle wait;
		/** BOOL ReleaseSemaphore(HANDLE,LONG,LONG*). */
		private final MethodHandle release;
		/** BOOL CloseHandle(HANDLE). */
		private final MethodHandle close;

		/**
		 * Retains the bound functions.
		 * @param create the bound CreateSemaphoreExW
		 * @param wait the bound WaitForSingleObject
		 * @param release the bound ReleaseSemaphore
		 * @param close the bound CloseHandle
		 */
		private BoundFunctions(MethodHandle create, MethodHandle wait, MethodHandle release, MethodHandle close)
		{
			this.create = create;
			this.wait = wait;
			this.release = release;
			this.close = close;
		}

		@Override
		public MemorySegment createSemaphore(MemorySegment errors, MemorySegment attributes, int initial, int maximum,
			MemorySegment name, int flags, int access) throws IOException
		{
			return (MemorySegment) call(create, errors, attributes, initial, maximum, name, flags, access);
		}

		@Override
		public int waitForSingleObject(MemorySegment errors, MemorySegment handle, int milliseconds) throws IOException
		{
			return (int) call(wait, errors, handle, milliseconds);
		}

		@Override
		public int releaseSemaphore(MemorySegment errors, MemorySegment handle, int count, MemorySegment previous)
			throws IOException
		{
			return (int) call(release, errors, handle, count, previous);
		}

		@Override
		public int closeHandle(MemorySegment errors, MemorySegment handle) throws IOException
		{
			return (int) call(close, errors, handle);
		}

		/**
		 * Invokes a bound operation with its captured-state storage.
		 * @param operation bound native operation
		 * @param arguments the operation's complete arguments, including captured-state storage
		 * @return raw native result
		 * @throws IOException if a checked invocation fails before a result is obtained
		 */
		private static Object call(MethodHandle operation, Object... arguments) throws IOException
		{
			try { return operation.invokeWithArguments(arguments); }
			catch (RuntimeException | Error failure) { throw failure; }
			catch (Throwable failure) { throw new IOException("Windows jobserver native invocation failed", failure); }
		}
	}

	/** JDK27 FFM bindings for the documented native Windows named-semaphore operations proved by the retained probe. */
	private static final class NativeCalls implements Calls
	{
		/** SYNCHRONIZE | SEMAPHORE_MODIFY_STATE from pinned jobserver 0.1.34 windows.rs. */
		private static final int ACCESS = 0x0010_0002;
		/** Existing-name outcome from CreateSemaphoreExW; it never authorizes reseeding. */
		private static final int ALREADY_EXISTS = 183;
		/** Documented zero-time wait timeout result. */
		private static final int WAIT_TIMEOUT = 258;
		/** Library and call-state owner, confined to the controlling thread. */
		private final Arena arena;
		/** Shared same-call GetLastError storage. */
		private final MemorySegment errors;
		/** Offset of the JDK-captured error field. */
		private final long errorOffset;
		/** The native semaphore functions, each taking the captured-state storage as its leading argument. */
		private final NativeFunctions functions;

		/** Acquires bindings with same-call error capture, releasing the library scope if any binding fails.
		 * @param scope borrowed shared native linker, retained open through this adapter's operations */
		// JDK FFM is the supported Windows semaphore boundary; Java has no named-semaphore ownership API.
		@SuppressWarnings("restricted")
		private NativeCalls(ToolingScope scope)
		{
			Linker linker = scope.getNativeLinker();
			this.arena = Arena.ofConfined();
			try
			{
				SymbolLookup library = SymbolLookup.libraryLookup("Kernel32.dll", arena);
				this.errors = arena.allocate(Linker.Option.captureStateLayout());
				this.errorOffset = Linker.Option.captureStateLayout()
					.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
				this.functions = new BoundFunctions(
					bind(linker, library, "CreateSemaphoreExW", FunctionDescriptor.of(ValueLayout.ADDRESS,
						ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)),
					bind(linker, library, "WaitForSingleObject", FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
					bind(linker, library, "ReleaseSemaphore", FunctionDescriptor.of(ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
					bind(linker, library, "CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
			}
			catch (RuntimeException | Error failure)
			{
				try { arena.close(); }
				catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
				throw failure;
			}
		}

		/**
		 * Retains controlled functions without loading platform libraries; ownership still follows native creation.
		 * @param arena owned access scope
		 * @param errors captured native error storage
		 * @param errorOffset captured DWORD offset
		 * @param functions native semaphore functions
		 */
		private NativeCalls(Arena arena, MemorySegment errors, long errorOffset, NativeFunctions functions)
		{
			this.arena = arena;
			this.errors = errors;
			this.errorOffset = errorOffset;
			this.functions = functions;
		}

		/**
		 * Binds one documented signature using the platform ABI and captured GetLastError.
		 * @param linker borrowed shared native linker
		 * @param library retained Kernel32 symbols
		 * @param name required native symbol
		 * @param signature Windows-width signature
		 * @return binding whose leading argument is captured call-state storage
		 */
		// This boundary binds only documented Win32 semaphore operations under the retained library scope.
		@SuppressWarnings("restricted")
		private static MethodHandle bind(Linker linker, SymbolLookup library, String name, FunctionDescriptor signature)
		{
			MemorySegment address = library.find(name)
				.orElseThrow(() -> new UnsupportedOperationException("Missing Windows semaphore symbol: " + name));
			return linker.downcallHandle(address, signature, Linker.Option.captureCallState("GetLastError"));
		}

		/**
		 * Reads the error captured from the same failing native invocation.
		 * @param operation failed operation name
		 * @return diagnostic with unsigned DWORD error
		 */
		private IOException failure(String operation)
		{
			return new IOException(operation + " failed with Windows error " +
				Integer.toUnsignedString(errors.get(ValueLayout.JAVA_INT, errorOffset)));
		}

		@Override
		public boolean create(String name, int initial, int maximum, Consumer<MemorySegment> acquired) throws IOException
		{
			try (Arena text = Arena.ofConfined())
			{
				MemorySegment wide = text.allocate((name.length() + 1L) * Character.BYTES, Character.BYTES);
				for (int i = 0; i < name.length(); i++)
					wide.set(ValueLayout.JAVA_CHAR, (long) i * Character.BYTES, name.charAt(i));
				MemorySegment handle = (MemorySegment) functions.createSemaphore(errors, MemorySegment.NULL, initial, maximum, wide, 0,
					ACCESS);
				// Publish ownership before error inspection or temporary name-scope cleanup can fail.
				acquired.accept(handle);
				if (handle.address() == 0)
					throw failure("CreateSemaphoreExW");
				return errors.get(ValueLayout.JAVA_INT, errorOffset) == ALREADY_EXISTS;
			}
		}

		@Override
		public boolean tryAcquire(MemorySegment handle) throws IOException
		{
			int result = functions.waitForSingleObject(errors, handle, 0);
			return switch (result)
			{
				case 0 -> true;
				case WAIT_TIMEOUT -> false;
				default -> throw failure("WaitForSingleObject returned " + Integer.toUnsignedString(result));
			};
		}

		@Override
		public void release(MemorySegment handle, int count) throws IOException
		{
			if (functions.releaseSemaphore(errors, handle, count, MemorySegment.NULL) == 0)
				throw failure("ReleaseSemaphore");
		}

		@Override
		public void closeHandle(MemorySegment handle) throws IOException
		{
			if (functions.closeHandle(errors, handle) == 0)
				throw failure("CloseHandle " + Long.toUnsignedString(handle.address()));
		}

		@Override
		public void close() { arena.close(); }
	}
}
