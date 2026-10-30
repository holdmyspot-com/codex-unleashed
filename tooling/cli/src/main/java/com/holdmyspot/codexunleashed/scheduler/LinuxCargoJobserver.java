package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns one Linux FIFO jobserver for exactly one root Cargo invocation and all of its compiler attempts.
 * The invocation has one implicit slot; the FIFO contains the remaining initial slots.
 * Instances and their leases are confined to one controller thread.
 * <p>
 * The caller retains the Cargo lease until Cargo, wrappers, supervisors and compiler descendants have all
 * terminated and been reaped, including after failure. This owner starts no processes and cannot establish that
 * condition itself. It never replaces tokens that a terminated holder may have lost.
 */
final class LinuxCargoJobserver implements AutoCloseable
{
	/** Environment entries owned by this root jobserver, following jobserver 0.1.34 configure_make. */
	private static final Set<String> MAKE_VARIABLES = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS");
	/** Private setup directory permissions before workload access is published. */
	private static final Set<PosixFilePermission> SETUP_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rwx------"));
	/** Directory access permitting the workload group to traverse without modifying entries. */
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rwx--x---"));
	/** FIFO access permitting the workload group to acquire and return tokens. */
	private static final Set<PosixFilePermission> FIFO_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rw-rw----"));
	/** Initial FIFO permissions while its enclosing directory is private. */
	private static final Set<PosixFilePermission> INITIAL_FIFO_PERMISSIONS = Set.copyOf(PosixFilePermissions.fromString("rw-------"));
	/** Exclusive child-directory prefix owned by this component. */
	private static final String DIRECTORY_PREFIX = "cargo-jobserver-";
	/** Known entry that consumers open within the retained directory. */
	private static final String FIFO_NAME = "tokens";
	/** Auth option marker selected using the pinned jobserver client's last-instance lookup. */
	private static final String AUTH_OPTION = "--jobserver-auth=";
	/** Legacy auth option emitted alongside the preferred option by jobserver 0.1.34. */
	private static final String FDS_OPTION = "--jobserver-fds=";
	/** Numeric group attribute supplied by the pinned Linux JDK filesystem provider. */
	private static final String GROUP_ATTRIBUTE = "unix:gid";
	/** Native operations; the retained descriptor belongs to this owner rather than an arena. */
	private final Calls calls;
	/** Exclusive setup directory, or null before acquisition and after deletion. */
	private Path directory = null;
	/** Exact FIFO pathname, or null until its enclosing directory is acquired. */
	private Path fifo = null;
	/** Indicates whether this operation created the FIFO entry. */
	private boolean fifoCreated = false;
	/** Retained Linux descriptor, or -1 before open and after its single close attempt. */
	private int descriptor = -1;
	/** Indicates whether this owner has already supplied its single Cargo invocation. */
	private boolean retained = false;
	/** Current invocation borrow, or null before retention and after its release. */
	private CargoLease lease = null;
	/** Indicates whether cleanup has begun, permanently preventing new invocation borrows. */
	private boolean closing = false;
	/** Indicates whether every acquired resource has been released. */
	private boolean closed = false;

	/**
	 * Retains resource-free native access before acquisition.
	 * @param calls native FIFO operations
	 */
	private LinuxCargoJobserver(Calls calls)
	{
		this.calls = Objects.requireNonNull(calls, "calls may not be null");
	}

	/**
	 * Creates and seeds a FIFO before any Cargo consumer starts.
	 * The caller owns the existing parent and must provide workload-group traversal through its ancestors while
	 * preventing the workload from renaming the created child. Shared ancestor permissions are never changed.
	 * Capacity is exactly one implicit slot plus {@code jobs - 1} explicit tokens. An oversized seed fails instead
	 * of waiting for a consumer or silently reducing capacity.
	 * @param scratchParent absolute parent on the Linux JDK filesystem, with a lossless space-free real path without the auth-option marker
	 * @param workloadGroup positive workload GID matching the credential-dropped compiler context
	 * @param jobs positive initial capacity including Cargo's implicit slot
	 * @return retained owner whose FIFO is ready for the workload group
	 * @throws NullPointerException if {@code scratchParent} is null
	 * @throws IllegalArgumentException if a numeric input is nonpositive or the parent path is relative, unrepresentable or contains an auth-option marker
	 * @throws UnsupportedOperationException if Linux LP64, unix attributes or POSIX permissions are unavailable
	 * @throws IOException if setup or nonblocking seeding fails
	 * @throws PreparationFailure if failed setup leaves owned path cleanup requiring retry
	 */
	static LinuxCargoJobserver create(Path scratchParent, int workloadGroup, int jobs) throws IOException
	{
		Path parent = validate(scratchParent, workloadGroup, jobs);
		return acquire(parent, workloadGroup, jobs, new NativeCalls());
	}

	/**
	 * Checks the complete context before acquiring native or filesystem resources.
	 * @param scratchParent caller-owned existing parent
	 * @param workloadGroup positive workload GID
	 * @param jobs positive capacity including the implicit slot
	 * @return the lossless real parent path
	 * @throws IOException if the real parent cannot be resolved
	 */
	private static Path validate(Path scratchParent, int workloadGroup, int jobs) throws IOException
	{
		if (!Objects.requireNonNull(scratchParent, "scratchParent may not be null").isAbsolute())
			throw new IllegalArgumentException("scratchParent must be absolute: " + scratchParent);
		if (workloadGroup <= 0 || jobs <= 0)
			throw new IllegalArgumentException("workloadGroup and jobs must be positive: " + workloadGroup + ", " + jobs);
		if (!System.getProperty("os.name").equals("Linux") || ValueLayout.ADDRESS.byteSize() != Long.BYTES)
			throw new UnsupportedOperationException("Cargo FIFO ownership requires Linux LP64");
		Set<String> views = scratchParent.getFileSystem().supportedFileAttributeViews();
		if (!views.containsAll(Set.of("unix", "posix")))
			throw new UnsupportedOperationException("Cargo FIFO ownership requires unix attributes and POSIX permissions");
		Path parent = scratchParent.toRealPath();
		if (!Path.of(parent.toString()).equals(parent) || parent.toString().contains(" "))
			throw new IllegalArgumentException("FIFO auth requires a lossless path without spaces: " + parent);
		if (parent.toString().contains(AUTH_OPTION))
			throw new IllegalArgumentException("FIFO auth path contains the reserved option marker " + AUTH_OPTION + ": " + parent);
		return parent;
	}

	/**
	 * Acquires each resource under its owner before performing the next fallible step.
	 * @param parent validated real parent
	 * @param workloadGroup positive workload GID
	 * @param jobs positive capacity including the implicit slot
	 * @param calls native FIFO access
	 * @return fully initialized owner
	 * @throws IOException if setup fails
	 */
	private static LinuxCargoJobserver acquire(Path parent, int workloadGroup, int jobs, Calls calls) throws IOException
	{
		LinuxCargoJobserver owner = new LinuxCargoJobserver(calls);
		try
		{
			owner.directory = Files.createTempDirectory(parent, DIRECTORY_PREFIX,
				PosixFilePermissions.asFileAttribute(SETUP_PERMISSIONS));
			Files.setPosixFilePermissions(owner.directory, SETUP_PERMISSIONS);
			owner.fifo = owner.directory.resolve(FIFO_NAME);
			calls.createFifo(owner.fifo, mode(INITIAL_FIFO_PERMISSIONS));
			owner.fifoCreated = true;
			// mkfifo applies the calling process's umask; restore owner access while the directory is still private.
			Files.setPosixFilePermissions(owner.fifo, INITIAL_FIFO_PERMISSIONS);
			owner.descriptor = calls.open(owner.fifo);
			owner.seed(jobs);

			Files.setAttribute(owner.fifo, GROUP_ATTRIBUTE, workloadGroup);
			Files.setAttribute(owner.directory, GROUP_ATTRIBUTE, workloadGroup);
			Files.setPosixFilePermissions(owner.fifo, FIFO_PERMISSIONS);
			owner.verifyAccess(owner.fifo, workloadGroup, FIFO_PERMISSIONS);
			Files.setPosixFilePermissions(owner.directory, DIRECTORY_PERMISSIONS);
			owner.verifyAccess(owner.directory, workloadGroup, DIRECTORY_PERMISSIONS);
			return owner;
		}
		catch (IOException | RuntimeException | Error failure)
		{
			try { owner.close(); }
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
				if (!owner.closed)
					throw new PreparationFailure(failure, owner);
			}
			throw failure;
		}
	}

	/**
	 * Seeds explicit tokens exactly once while no consumer exists.
	 * @param jobs total initial capacity including the implicit token
	 * @throws IOException if nonblocking seeding fails or makes no progress
	 */
	private void seed(int jobs) throws IOException
	{
		byte[] tokens = new byte[Math.min(jobs - 1, 512)];
		java.util.Arrays.fill(tokens, (byte) '+');
		int seeded = 0;
		try
		{
			while (seeded < jobs - 1)
			{
				int count = Math.min(tokens.length, (jobs - 1) - seeded);
				int written = calls.write(descriptor, tokens, count);
				if (written <= 0 || written > count)
					throw new IOException("FIFO seed write made invalid progress: " + written + " of " + count);
				seeded += written;
			}
		}
		catch (IOException failure)
		{
			throw new IOException("Cannot seed " + (jobs - 1) + " explicit FIFO tokens; wrote " + seeded, failure);
		}
	}

	/**
	 * Checks the published numeric group and POSIX access rather than inferring them from requested settings.
	 * @param path owned directory or FIFO
	 * @param workloadGroup expected workload GID
	 * @param permissions expected access bits
	 * @throws IOException if observed access differs
	 */
	private void verifyAccess(Path path, int workloadGroup, Set<PosixFilePermission> permissions) throws IOException
	{
		if (!Files.getAttribute(path, GROUP_ATTRIBUTE).equals(workloadGroup) ||
			!Files.getPosixFilePermissions(path).equals(permissions))
			throw new IOException("Owned jobserver access differs from requested group/permissions: " + path);
	}

	/**
	 * Reserves the only Cargo invocation and supplies its complete immutable environment.
	 * Existing make flags are rejected, including empty values, before this owner's one borrow is consumed.
	 * @param environment complete root environment without CARGO_MAKEFLAGS, MAKEFLAGS or MFLAGS
	 * @return lease that must outlive every consumer and every retry of this Cargo invocation
	 * @throws NullPointerException if the map or an entry is null
	 * @throws IllegalArgumentException if an owned make-variable key is already present
	 * @throws IllegalStateException if closing has begun or the single invocation was already retained
	 */
	CargoLease retainCargo(Map<String, String> environment)
	{
		Map<String, String> copy = new HashMap<>(Map.copyOf(environment));
		for (String name : MAKE_VARIABLES)
			if (copy.containsKey(name))
				throw new IllegalArgumentException("Root Cargo environment already contains " + name);
		if (closing || retained)
			throw new IllegalStateException("Jobserver is closing or its single Cargo invocation was already retained");
		String auth = "fifo:" + fifo;
		String flags = "-j " + FDS_OPTION + auth + " " + AUTH_OPTION + auth;
		for (String name : MAKE_VARIABLES)
			copy.put(name, flags);
		CargoLease result = new CargoLease(Map.copyOf(copy));
		retained = true;
		lease = result;
		return result;
	}

	/**
	 * Releases the descriptor once, then removes only the created FIFO and directory.
	 * A live lease rejects close without changing resources. After a cleanup failure, retry close for unresolved
	 * path deletion only. Linux releases a descriptor even when close reports EINTR, so its number is never retried.
	 * @throws IllegalStateException if the Cargo lease remains live
	 * @throws IOException if descriptor close or owned path deletion fails
	 */
	@Override
	public void close() throws IOException
	{
		if (lease != null)
			throw new IllegalStateException("Cargo consumers still retain the jobserver lease");
		if (closed)
			return;
		closing = true;
		IOException failure = null;
		if (descriptor >= 0)
		{
			int owned = descriptor;
			descriptor = -1;
			try { calls.close(owned); }
			catch (IOException cleanup) { failure = cleanup; }
		}
		if (fifoCreated)
		{
			try
			{
				Files.delete(fifo);
				fifoCreated = false;
			}
			catch (IOException cleanup) { failure = combine(failure, cleanup); }
		}
		if (!fifoCreated && directory != null)
		{
			try
			{
				Files.delete(directory);
				directory = null;
			}
			catch (IOException cleanup) { failure = combine(failure, cleanup); }
		}
		closed = descriptor < 0 && !fifoCreated && directory == null;
		if (failure != null)
			throw failure;
	}

	/**
	 * Aggregates independent cleanup failures without discarding their original causes.
	 * @param first earlier cleanup error, or null
	 * @param next later cleanup error
	 * @return first error with later errors suppressed, or next when no earlier error exists
	 */
	private static IOException combine(IOException first, IOException next)
	{
		if (first == null)
			return next;
		first.addSuppressed(next);
		return first;
	}

	/**
	 * Converts readable POSIX permission sets only at the native mode boundary.
	 * @param permissions permissions to encode
	 * @return Linux mode bits
	 */
	private static int mode(Set<PosixFilePermission> permissions)
	{
		int result = 0;
		for (PosixFilePermission permission : permissions)
			result |= switch (permission)
			{
				case OWNER_READ -> 0400;
				case OWNER_WRITE -> 0200;
				case OWNER_EXECUTE -> 0100;
				case GROUP_READ -> 0040;
				case GROUP_WRITE -> 0020;
				case GROUP_EXECUTE -> 0010;
				case OTHERS_READ -> 0004;
				case OTHERS_WRITE -> 0002;
				case OTHERS_EXECUTE -> 0001;
			};
		return result;
	}

	/**
	 * Retains the transport while one Cargo invocation and its descendants can still open it.
	 */
	final class CargoLease implements AutoCloseable
	{
		/** Complete immutable launch environment, retained only while this borrow is live. */
		private final Map<String, String> environment;
		/** Indicates whether the invocation coordinator released this borrow. */
		private boolean released = false;

		/**
		 * Retains an already validated immutable environment.
		 * @param environment complete immutable launch environment
		 */
		private CargoLease(Map<String, String> environment)
		{
			this.environment = environment;
		}

		/**
		 * Returns the complete environment for Cargo and all compiler launch contexts.
		 * @return immutable root environment with supported FIFO auth
		 * @throws IllegalStateException if this lease was released
		 */
		Map<String, String> environment()
		{
			if (released)
				throw new IllegalStateException("Cargo jobserver lease was released");
			return environment;
		}

		/**
		 * Releases this borrow after all invocation consumers are terminal and reaped.
		 * The coordinator must first prevent new starts and reconcile every retained native owner; root-only exit
		 * is insufficient. This operation cannot discover consumer liveness. Repeated close has no effect.
		 */
		@Override
		public void close()
		{
			if (released)
				return;
			released = true;
			lease = null;
		}
	}

	/**
	 * Preserves failed acquisition whose owned path cleanup requires retry.
	 */
	static final class PreparationFailure extends IOException implements AutoCloseable
	{
		/** Partially acquired owner retained until cleanup succeeds. */
		private final LinuxCargoJobserver owner;

		/**
		 * Retains the original setup failure and cleanup capability.
		 * @param cause setup failure, including suppressed cleanup failures
		 * @param owner partial resource owner
		 */
		private PreparationFailure(Throwable cause, LinuxCargoJobserver owner)
		{
			super("Cargo jobserver setup failed and path cleanup requires retry", cause);
			this.owner = owner;
		}

		/**
		 * Retries retained path cleanup without retrying a consumed descriptor number.
		 * @throws IOException if owned path cleanup remains incomplete
		 */
		@Override
		public void close() throws IOException
		{
			owner.close();
		}
	}

	/**
	 * Narrow Linux FIFO access boundary; implementations return actual positive write progress or throw.
	 */
	interface Calls
	{
		/**
		 * Creates a new FIFO without replacing an existing entry.
		 * @param path new FIFO path
		 * @param mode initial native permission bits
		 * @throws IOException if creation fails
		 */
		void createFifo(Path path, int mode) throws IOException;
		/**
		 * Opens an owner endpoint nonblocking and close-on-exec.
		 * @param path existing FIFO path
		 * @return newly owned nonnegative descriptor
		 * @throws IOException if open fails
		 */
		int open(Path path) throws IOException;
		/**
		 * Writes bounded token bytes, retrying only an interrupted write that transferred no bytes.
		 * @param descriptor retained owner endpoint
		 * @param tokens token-byte source
		 * @param count positive requested prefix length
		 * @return actual positive transferred bytes
		 * @throws IOException if write fails, including nonblocking capacity exhaustion
		 */
		int write(int descriptor, byte[] tokens, int count) throws IOException;
		/**
		 * Closes a Linux descriptor once, including when close reports an error.
		 * @param descriptor owned endpoint whose number must not be retried
		 * @throws IOException if close reports an error
		 */
		void close(int descriptor) throws IOException;
	}

	/**
	 * Provides typed access to the same acquisition operation for controlled native-failure checks.
	 */
	static final class SharedSecrets
	{
		/**
		 * Prevents construction.
		 */
		private SharedSecrets() { }
		/**
		 * Uses actual native operations as the delegate of a focused fault fixture.
		 * @return new resource-free native access
		 */
		static Calls nativeCalls() { return new NativeCalls(); }
		/**
		 * Executes normal validated acquisition with controlled external access.
		 * @param scratchParent existing caller-owned parent
		 * @param workloadGroup workload GID
		 * @param jobs total initial capacity
		 * @param calls native access controlling the boundary under test
		 * @return normally owned FIFO
		 * @throws IOException if acquisition or cleanup fails
		 */
		static LinuxCargoJobserver create(Path scratchParent, int workloadGroup, int jobs, Calls calls) throws IOException
		{
			return acquire(validate(scratchParent, workloadGroup, jobs), workloadGroup, jobs, calls);
		}
	}

	/**
	 * Binds Linux LP64 libc without JNI or descriptor extraction from JDK internals.
	 */
	private static final class NativeCalls implements Calls
	{
		/** Linux open flags from asm-generic/fcntl.h; clients independently reopen their own blocking endpoints. */
		private static final int OWNER_FLAGS = (02 | 04000) | 02000000;
		/** Linux interrupted-system-call errno from asm-generic/errno-base.h. */
		private static final int INTERRUPTED = 4;
		/** Native ABI linker shared by these four resource-free bindings. */
		private final Linker linker = Linker.nativeLinker();
		/** Captured errno member supplied by the JDK linker rather than global errno lookup. */
		private final long errnoOffset = Linker.Option.captureStateLayout()
			.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
		/** Linux int mkfifo(const char *, mode_t), with uint32 mode_t. */
		private final MethodHandle mkfifo = bind("mkfifo", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT), false);
		/** Linux int open(const char *, int, ...), supplying ignored promoted mode 0. */
		private final MethodHandle open = bind("open", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), true);
		/** Linux ssize_t write(int, const void *, size_t), with LP64 word-sized count and result. */
		private final MethodHandle write = bind("write", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
			ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), false);
		/** Linux int close(int); Linux consumes the descriptor even when interrupted. */
		private final MethodHandle close = bind("close", FunctionDescriptor.of(ValueLayout.JAVA_INT,
			ValueLayout.JAVA_INT), false);

		/**
		 * Resolves fixed native bindings without acquiring a descriptor or filesystem entry.
		 */
		private NativeCalls() { }

		/**
		 * Resolves one Linux symbol with same-call errno capture.
		 * @param name required libc symbol
		 * @param signature verified LP64 signature
		 * @param variadic indicates whether the third argument is a promoted open vararg
		 * @return native handle with captured call-state argument
		 */
		private MethodHandle bind(String name, FunctionDescriptor signature, boolean variadic)
		{
			MemorySegment symbol = linker.defaultLookup().find(name)
				.orElseThrow(() -> new UnsupportedOperationException("Missing Linux libc symbol: " + name));
			if (variadic)
				return linker.downcallHandle(symbol, signature, Linker.Option.captureCallState("errno"),
					Linker.Option.firstVariadicArg(2));
			return linker.downcallHandle(symbol, signature, Linker.Option.captureCallState("errno"));
		}

		/**
		 * Calls a bound operation, preserving its native result and same-invocation errno on failure.
		 * @param operation bound native operation
		 * @param arguments operation arguments after captured state
		 * @return result and failure errno, with zero errno on success
		 * @throws IOException if invocation fails before a native result can be obtained
		 */
		private NativeResult call(MethodHandle operation, Object... arguments) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
				List<Object> values = new ArrayList<>();
				values.add(state);
				values.addAll(List.of(arguments));
				long result = ((Number) operation.invokeWithArguments(values)).longValue();
				int error = result == -1 ? state.get(ValueLayout.JAVA_INT, errnoOffset) : 0;
				return new NativeResult(result, error);
			}
			catch (RuntimeException | Error failure) { throw failure; }
			catch (Throwable failure) { throw new IOException("Linux FIFO native invocation failed", failure); }
		}

		@Override
		public void createFifo(Path path, int mode) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				requireSuccess("mkfifo " + path, call(mkfifo, arena.allocateFrom(path.toString()), mode));
			}
		}

		@Override
		public int open(Path path) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				NativeResult result = call(open, arena.allocateFrom(path.toString()), OWNER_FLAGS, 0);
				requireSuccess("open " + path, result);
				return Math.toIntExact(result.value());
			}
		}

		@Override
		public int write(int descriptor, byte[] tokens, int count) throws IOException
		{
			try (Arena arena = Arena.ofConfined())
			{
				MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, tokens);
				while (true)
				{
					NativeResult result = call(write, descriptor, buffer, (long) count);
					if (result.value() == -1 && result.error() == INTERRUPTED)
						continue;
					requireSuccess("write FIFO tokens", result);
					return Math.toIntExact(result.value());
				}
			}
		}

		@Override
		public void close(int descriptor) throws IOException
		{
			requireSuccess("close FIFO descriptor " + descriptor, call(close, descriptor));
		}

		/**
		 * Converts a failed libc return into an operation-specific diagnostic.
		 * @param operation failed or successful native operation
		 * @param result same-invocation status and errno
		 * @throws IOException if libc returned -1
		 */
		private void requireSuccess(String operation, NativeResult result) throws IOException
		{
			if (result.value() == -1)
				throw new IOException(operation + " failed with errno " + result.error());
		}
	}

	/**
	 * Preserves the native return and its errno only when the return indicated failure.
	 * @param value native return value
	 * @param error captured errno for -1, or zero on success
	 */
	private record NativeResult(long value, int error) { }
}
