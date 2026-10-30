package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
final class WindowsCargoJobserver implements AutoCloseable
{
	/** Variables emitted by pinned jobserver 0.1.34 configure_make. */
	private static final Set<String> MAKE_VARIABLES = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS");
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
	 * @param jobs positive invocation capacity including its implicit slot
	 * @return sole owner of the new named pool
	 * @throws IllegalArgumentException if jobs is nonpositive
	 * @throws UnsupportedOperationException if native 64-bit Windows or required FFM symbols are unavailable
	 * @throws IOException if creation or cleanup fails
	 * @throws PreparationFailure if failed acquisition retains an unresolved cleanup failure
	 */
	static WindowsCargoJobserver create(int jobs) throws IOException
	{
		validateJobs(jobs);
		if (!System.getProperty("os.name", "").startsWith("Windows") ||
			ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("Named Cargo jobserver ownership requires native 64-bit Windows");
		return acquire(jobs, new NativeCalls());
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
	CargoLease retainCargo(Map<String, String> environment)
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
	final class CargoLease implements AutoCloseable
	{
		/** Copied root environment shared unchanged through all invocation launch boundaries. */
		private final Map<String, String> environment;
		/** Indicates whether the coordinator released this lease. */
		private boolean released = false;

		/**
		 * Retains a validated complete environment.
		 * @param environment copied immutable launch environment
		 */
		private CargoLease(Map<String, String> environment) { this.environment = environment; }

		/**
		 * Returns the complete launch environment while the invocation is retained.
		 * @return immutable named-jobserver environment
		 * @throws IllegalStateException if released or accessed from another thread
		 */
		Map<String, String> environment()
		{
			requireController();
			if (released)
				throw new IllegalStateException("Cargo jobserver lease was released");
			return environment;
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
			released = true;
			lease = null;
		}
	}

	/** Reports an observed quiescent capacity mismatch without claiming which consumer lost or added tokens. */
	static final class CapacityMismatch extends IOException
	{
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

		/** @return expected explicit tokens, excluding the implicit slot */
		int expectedTokens() { return expectedTokens; }
		/** @return tokens actually observed, without inferring an individual consumer's borrow */
		int observedTokens() { return observedTokens; }
	}

	/** Retains a failed acquisition and its terminal unresolved cleanup owner; close never retries a raw handle. */
	static final class PreparationFailure extends IOException implements AutoCloseable
	{
		/** Owner preserving its original cleanup outcome and handle-release accounting. */
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
	interface Calls extends AutoCloseable
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

	/** Typed access to the production acquisition path for controlled native-outcome tests. */
	static final class SharedSecrets
	{
		/** Prevents construction. */
		private SharedSecrets() { }
		/**
		 * Runs normal acquisition with owned external operations after validating capacity.
		 * @param jobs positive capacity including the implicit slot
		 * @param calls access transferred only after input validation
		 * @return normally owned jobserver
		 * @throws IOException if acquisition or cleanup fails
		 */
		static WindowsCargoJobserver create(int jobs, Calls calls) throws IOException
		{
			validateJobs(jobs);
			return acquire(jobs, calls);
		}

		/**
		 * Retains controlled bindings through the same production creation and cleanup operations.
		 * @param arena owned native-access scope
		 * @param errors captured native error storage
		 * @param errorOffset offset of the captured DWORD
		 * @param create semaphore creation binding with leading captured-state argument
		 * @param wait zero-time wait binding with leading captured-state argument
		 * @param release token-return binding with leading captured-state argument
		 * @param close handle-close binding with leading captured-state argument
		 * @return owned access through the production native adapter
		 */
		static Calls nativeCalls(Arena arena, MemorySegment errors, long errorOffset, MethodHandle create,
			MethodHandle wait, MethodHandle release, MethodHandle close)
		{
			return new NativeCalls(arena, errors, errorOffset, create, wait, release, close);
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
		/** HANDLE CreateSemaphoreExW(SECURITY_ATTRIBUTES*,LONG,LONG,LPCWSTR,DWORD,DWORD). */
		private final MethodHandle create;
		/** DWORD WaitForSingleObject(HANDLE,DWORD). */
		private final MethodHandle wait;
		/** BOOL ReleaseSemaphore(HANDLE,LONG,LONG*). */
		private final MethodHandle release;
		/** BOOL CloseHandle(HANDLE). */
		private final MethodHandle close;

		/** Acquires bindings with same-call error capture, releasing the library scope if any binding fails. */
		private NativeCalls()
		{
			this.arena = Arena.ofConfined();
			try
			{
				SymbolLookup library = SymbolLookup.libraryLookup("Kernel32.dll", arena);
				this.errors = arena.allocate(Linker.Option.captureStateLayout());
				this.errorOffset = Linker.Option.captureStateLayout()
					.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
				this.create = bind(library, "CreateSemaphoreExW", FunctionDescriptor.of(ValueLayout.ADDRESS,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
					ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
				this.wait = bind(library, "WaitForSingleObject", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
				this.release = bind(library, "ReleaseSemaphore", FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
				this.close = bind(library, "CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			}
			catch (RuntimeException | Error failure)
			{
				try { arena.close(); }
				catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
				throw failure;
			}
		}

		/**
		 * Retains controlled bindings without loading platform libraries; ownership still follows native creation.
		 * @param arena owned access scope
		 * @param errors captured native error storage
		 * @param errorOffset captured DWORD offset
		 * @param create creation binding
		 * @param wait wait binding
		 * @param release return binding
		 * @param close handle-close binding
		 */
		private NativeCalls(Arena arena, MemorySegment errors, long errorOffset, MethodHandle create,
			MethodHandle wait, MethodHandle release, MethodHandle close)
		{
			this.arena = arena;
			this.errors = errors;
			this.errorOffset = errorOffset;
			this.create = create;
			this.wait = wait;
			this.release = release;
			this.close = close;
		}

		/**
		 * Binds one documented signature using the platform ABI and captured GetLastError.
		 * @param library retained Kernel32 symbols
		 * @param name required native symbol
		 * @param signature Windows-width signature
		 * @return binding whose leading argument is captured call-state storage
		 */
		private MethodHandle bind(SymbolLookup library, String name, FunctionDescriptor signature)
		{
			MemorySegment address = library.find(name)
				.orElseThrow(() -> new UnsupportedOperationException("Missing Windows semaphore symbol: " + name));
			return Linker.nativeLinker().downcallHandle(address, signature, Linker.Option.captureCallState("GetLastError"));
		}

		/**
		 * Invokes with this owner's call-state scope without collapsing native wait failures into timeouts.
		 * @param operation bound native operation
		 * @param arguments arguments after captured state
		 * @return raw native result
		 * @throws IOException if a checked invocation fails before a result is obtained
		 */
		private Object call(MethodHandle operation, Object... arguments) throws IOException
		{
			List<Object> values = new ArrayList<>();
			values.add(errors);
			values.addAll(List.of(arguments));
			try { return operation.invokeWithArguments(values); }
			catch (RuntimeException | Error failure) { throw failure; }
			catch (Throwable failure) { throw new IOException("Windows jobserver native invocation failed", failure); }
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
				MemorySegment handle = (MemorySegment) call(create, MemorySegment.NULL, initial, maximum, wide, 0, ACCESS);
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
			int result = (int) call(wait, handle, 0);
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
			if ((int) call(release, handle, count, MemorySegment.NULL) == 0)
				throw failure("ReleaseSemaphore");
		}

		@Override
		public void closeHandle(MemorySegment handle) throws IOException
		{
			if ((int) call(close, handle) == 0)
				throw failure("CloseHandle " + Long.toUnsignedString(handle.address()));
		}

		@Override
		public void close() { arena.close(); }
	}
}
