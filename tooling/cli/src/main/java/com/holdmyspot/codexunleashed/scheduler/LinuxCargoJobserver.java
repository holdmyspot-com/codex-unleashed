package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Owns one Linux FIFO jobserver for exactly one root Cargo invocation and all of its compiler attempts.
 * The invocation has one implicit slot; the FIFO contains the remaining initial slots.
 * Instances and their leases are confined to one controller thread.
 * <p>
 * The caller retains the Cargo lease until Cargo, wrappers, supervisors and compiler descendants have all
 * terminated and been reaped, including after failure. This owner starts no processes and cannot establish that
 * condition itself. A lease accounts for mediated attempt borrows; unmediated native consumers remain untracked.
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
	 * @param scope borrowed native services, kept open until this jobserver and its consumers close
	 * @param scratchParent absolute parent on the Linux JDK filesystem, with a lossless space-free real path without the auth-option marker
	 * @param workloadGroup positive workload GID matching the credential-dropped compiler context
	 * @param jobs positive initial capacity including Cargo's implicit slot
	 * @return retained owner whose FIFO is ready for the workload group
	 * @throws NullPointerException if {@code scope} or {@code scratchParent} is null
	 * @throws IllegalArgumentException if a numeric input is nonpositive or the parent path is relative, unrepresentable or contains an auth-option marker
	 * @throws UnsupportedOperationException if Linux LP64, unix attributes or POSIX permissions are unavailable
	 * @throws IOException if setup or nonblocking seeding fails
	 * @throws PreparationFailure if failed setup leaves owned path cleanup requiring retry
	 */
	static LinuxCargoJobserver create(ToolingScope scope, Path scratchParent, int workloadGroup, int jobs) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Path parent = validate(scratchParent, workloadGroup, jobs);
		return acquire(parent, workloadGroup, jobs, scope.getLinuxFifoBindings());
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
		/** Logical reservations for this invocation; every attempt retires before the native borrow is released. */
		private final LinuxCargoTokens tokens = new LinuxCargoTokens(new TokenTransfer());
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
		 * Retains token ownership for one whole compiler attempt authenticated by the invocation coordinator.
		 * The coordinator must prevent new image starts and establish execution impossibility for every descendant
		 * before closing the returned owner. Parent-only exit is insufficient. Different processes in the same
		 * authenticated attempt may return its exact borrowed bytes.
		 * @return new logical attempt capability borrowing this lease's native FIFO
		 * @throws IllegalStateException if this lease was released
		 * @throws IOException if an earlier uncertain transfer prevents further FIFO operations
		 */
		LinuxCargoTokens.Attempt retainAttempt() throws IOException
		{
			if (released)
				throw new IllegalStateException("Cargo jobserver lease was released");
			return tokens.retainAttempt();
		}

		/**
		 * Observes the native endpoint retained by this live invocation lease.
		 * The observation follows the descriptor's kernel object even after pathname aliases or replacement.
		 * The caller must authenticate each process image separately before installing a token hook.
		 * @return exact FIFO device components and unsigned inode bits
		 * @throws IllegalStateException if this lease was released
		 * @throws IOException if retained descriptor metadata is unavailable, incomplete or not a FIFO
		 */
		LinuxFifoBindings.Identity identity() throws IOException
		{
			if (released)
				throw new IllegalStateException("Cargo jobserver lease was released");
			return calls.identity(descriptor);
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
		 * is insufficient. Live logical attempts reject closure without releasing this borrow.
		 * This operation cannot discover consumer liveness. Repeated successful close has no effect.
		 * @throws IllegalStateException if a logical attempt remains live
		 */
		@Override
		public void close()
		{
			if (released)
				return;
			tokens.close();
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
	 * Borrows this jobserver's retained descriptor for confirmed one-byte ledger transfers.
	 * The Cargo lease prevents native owner closure until all logical attempts retire.
	 */
	private final class TokenTransfer implements LinuxCargoTokens.Transfer
	{
		/**
		 * Borrows the enclosing jobserver's retained endpoint without acquiring native resources.
		 */
		private TokenTransfer() {}

		@Override
		public OptionalInt tryRead() throws IOException
		{
			return calls.tryRead(descriptor);
		}

		@Override
		public void write(byte token) throws IOException
		{
			int written = calls.write(descriptor, new byte[] {token}, 1);
			if (written != 1)
				throw new IOException("FIFO token return did not confirm one byte: " + written);
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
		 * Obtains the retained descriptor's actual FIFO identity independently of its pathname.
		 * @param descriptor retained nonnegative native endpoint
		 * @return exact kernel device components and unsigned inode bits
		 * @throws IllegalArgumentException if {@code descriptor} is negative
		 * @throws IOException if metadata fails, required fields are unavailable or the descriptor is not a FIFO
		 */
		LinuxFifoBindings.Identity identity(int descriptor) throws IOException;

		/**
		 * Reads one opaque byte from an owned nonblocking FIFO endpoint without waiting for another consumer.
		 * Retries only EINTR, which reports interruption before any data was read.
		 * @param descriptor retained read-write FIFO endpoint
		 * @return unsigned byte in 0..255, or empty when EAGAIN establishes that no byte was transferred
		 * @throws IOException if read fails, reaches EOF, or does not confirm exactly one byte
		 */
		OptionalInt tryRead(int descriptor) throws IOException;
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
		 * @param scope borrowed native services, retained open by the fixture through all operations
		 * @return the scope's shared resource-free native access
		 */
		static Calls nativeCalls(ToolingScope scope) { return scope.getLinuxFifoBindings(); }
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

}
