package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Creates resource-free Linux Cargo compiler attempts and defers their allocation notifications until outside queue
 * controls.
 * <p>
 * Each attempt runs one child that the native Cargo wrapper holds before its {@code execve}; the attempt contains,
 * registers and instruments that child, then releases it through the wrapper connection. All operations belong to the
 * constructing thread. Native owners and live attempts are borrowed; JobQueue exclusively owns attempt cleanup. A
 * failed cleanup remains visible through hasOpenAttempts.
 */
public final class LinuxAttempts implements JobQueue.AttemptFactory<LinuxSupervisor.Result>
{
	/** Thread permitted to mutate the borrowed registry and notifications. */
	private final Thread owner = Thread.currentThread();
	/** Correlation seed shared by this build's distinct attempt ordinals. */
	private final UUID build;
	/** Borrowed acquisition of each attempt's native owners. */
	private final Natives natives;
	/** Validated immutable action inputs. */
	private final Commands commands = new Commands();
	/** Positive native control deadline. */
	private final Duration timeout;

	/** Borrowed owners in creation order, retained after failed cleanup. */
	private final Map<Long, LinuxAttempt> live = new LinkedHashMap<>();
	/** Highest reserved ordinal, never reset after cleanup. */
	private long lastSequence = -1;
	/** Deferred notifications; no queue callback is retained. */
	private final Notifications notifications = new Notifications();

	/**
	 * Validates configuration without native calls or filesystem access.
	 * @param build   correlation seed
	 * @param natives borrowed acquisition of each attempt's native owners
	 * @param timeout positive control timeout
	 * @throws NullPointerException     if an input is null
	 * @throws IllegalArgumentException if the timeout is invalid
	 */
	public LinuxAttempts(UUID build, Natives natives, Duration timeout)
	{
		this.build = Objects.requireNonNull(build, "build");
		this.natives = Objects.requireNonNull(natives, "natives");
		this.timeout = checkedTimeout(timeout);
	}

    /**
     * Appends an immutable action before queue submission, without acquiring resources.
     * Names remain reserved after all attempts finish; replacement is never permitted.
     * @param name stable action name
     * @param command validated immutable launch inputs
     * @throws IllegalArgumentException if the name is blank or already reserved
     */
    public void register(String name, Command command)
    {
        requireOwner();
        commands.register(name, command);
    }

	/** Returns a fresh resource-free owner for one configured action and unused ordinal.
	 * @param name configured stable action name
	 * @param attemptSequence nonnegative increasing queue ordinal
	 * @return new exclusively queue-owned attempt
	 * @throws IllegalArgumentException if name or ordinal is invalid
	 */
	@Override
	public LinuxAttempt create(String name, long attemptSequence)
	{
		requireOwner();
		Command command = commands.get(name);
		if (command == null || attemptSequence <= lastSequence)
			throw new IllegalArgumentException("Unknown action or reused attempt ordinal: " + name + "/" + attemptSequence);
		UUID identity = attemptIdentity(build, attemptSequence);
		LinuxAttempt attempt = new LinuxAttempt(this, natives, name, attemptSequence, identity, command, timeout);
		live.put(attemptSequence, attempt);
		lastSequence = attemptSequence;
		return attempt;
	}

	/** Services a fair snapshot without reentering JobQueue.
	 * @throws IOException if native delivery or batch publication fails
	 */
	public void pumpAll() throws IOException
	{
		requireOwner();
		for (LinuxAttempt attempt : java.util.List.copyOf(live.values()))
		{
			attempt.pumpEvents();
			var batch = attempt.pollAllocationWait();
			if (batch.isPresent())
				notifications.add(new Blocked(attempt.name(), attempt.sequence(), batch.getAsLong()));
		}
	}

	/** Returns the oldest deferred event for delivery outside queue controls.
	 * @return oldest event, or empty
	 */
	public Optional<Blocked> pollBlocked()
	{
		requireOwner();
		return notifications.poll();
	}

	/** Checks whether borrowed native ownership must remain available.
	 * @return whether an attempt still retains cleanup ownership
	 */
	public boolean hasOpenAttempts()
	{
		requireOwner();
		return live.isEmpty() == false;
	}

	/** Removes a borrowed reference only after complete successful cleanup.
	 * @param attempt exact successfully closed owner
	 */
	void forget(LinuxAttempt attempt)
	{
		requireOwner();
		if (live.remove(attempt.sequence(), attempt) == false)
			throw new IllegalStateException("Attempt is not retained by this factory");
	}

	/** Derives correlation from fixed-width big-endian build UUID and queue ordinal.
	 * @param build build seed
	 * @param attemptSequence nonnegative queue ordinal
	 * @return deterministic distinct attempt correlation
	 * @throws IllegalArgumentException if the ordinal is negative
	 */
	public static UUID attemptIdentity(UUID build, long attemptSequence)
	{
		Objects.requireNonNull(build, "build");
		if (attemptSequence < 0)
			throw new IllegalArgumentException("Attempt ordinal must be nonnegative");
		return UUID.nameUUIDFromBytes(ByteBuffer.allocate(24).putLong(build.getMostSignificantBits()).
			putLong(build.getLeastSignificantBits()).putLong(attemptSequence).array());
	}

	/** Validates bounded nanosecond timeout without starting a clock.
	 * @param timeout proposed timeout
	 * @return original validated value
	 * @throws IllegalArgumentException if timeout is nonpositive or too large
	 */
	public static Duration checkedTimeout(Duration timeout)
	{
		Objects.requireNonNull(timeout, "timeout");
		if (timeout.isZero() || timeout.isNegative())
			throw new IllegalArgumentException("Control timeout must be positive");
		try { timeout.toNanos(); }
		catch (ArithmeticException cause) { throw new IllegalArgumentException("Control timeout is too large", cause); }
		return timeout;
	}

	/** Requires the constructing thread before touching borrowed owners. */
	private void requireOwner()
	{
		if (Thread.currentThread() != owner)
			throw new IllegalStateException("Linux attempts belong to their constructing thread");
	}

	/**
	 * Immutable compiler action whose attempts run as children of one native Cargo wrapper.
	 * @param launch  approved compiler execution: its command and environment are the wrapper's registration, and its
	 *                cgroup parent, uid and gid bound every attempt
	 * @param wrapper borrowed connection of the wrapper that forks each attempt's child; the invocation owns it
	 */
	public record Command(LinuxProcessTree.Launch launch, LinuxCargoWrapperConnection wrapper)
	{
		/**
		 * Retains the inputs without reading the connection or acquiring resources.
		 * @throws NullPointerException if an input is null
		 */
		public Command
		{
			Objects.requireNonNull(launch, "launch");
			Objects.requireNonNull(wrapper, "wrapper");
		}
	}

	/**
	 * Acquires the native owners of one attempt, in the order the attempt needs them.
	 */
	public interface Natives
	{
		/**
		 * Moves a held child into a fresh owned cgroup subtree and registers it with the memory authority.
		 * @param name     stable action name
		 * @param sequence queue attempt ordinal
		 * @param launch   approved compiler execution
		 * @param child    verified held child of the wrapper
		 * @param parent   pid of the wrapper, the child's parent
		 * @return owned containment of the whole attempt
		 * @throws IOException if the child cannot be moved, verified or registered; partial ownership is released
		 */
		Containment contain(String name, long sequence, LinuxProcessTree.Launch launch, LinuxCargoProtocol.Child child,
			long parent) throws IOException;

		/**
		 * Retains Cargo token ownership for one whole attempt.
		 * @return attempt token owner, closed only after the attempt can no longer execute
		 * @throws IOException if the invocation lease cannot lend tokens
		 */
		LinuxCargoTokens.Attempt retainTokens() throws IOException;

		/**
		 * Creates unstarted instrumentation for a contained held root.
		 * @param identity native attempt correlation
		 * @param launch   approved compiler execution
		 * @param root     held root and its containment
		 * @param tokens   attempt token owner
		 * @param waits    attempt allocation waits
		 * @param peers    peer service without queue reentry
		 * @return instrumentation that has acquired nothing yet
		 * @throws IOException if instrumentation cannot be created
		 */
		Instrumentation instrument(UUID identity, LinuxProcessTree.Launch launch, AllocationHooks.Root root,
			LinuxCargoTokens.Attempt tokens, AllocationWaits waits, AllocationHook.Pump peers) throws IOException;
	}

	/**
	 * Owned cgroup subtree that contains one whole attempt and its memory-authority registration.
	 */
	public interface Containment extends AutoCloseable
	{
		/**
		 * Returns the owned subtree.
		 * @return absolute cgroup directory
		 */
		Path directory();

		/**
		 * Reports whether any process of the attempt is still live; zombies do not count.
		 * @return whether a process of the attempt can still execute
		 * @throws IOException if the population cannot be read
		 */
		boolean isPopulated() throws IOException;

		/**
		 * Kills every process of the attempt without waiting.
		 * @throws IOException if the kernel rejects the request
		 */
		void terminate() throws IOException;

		/**
		 * Unregisters the attempt, kills anything left and removes the subtree.
		 * @throws IOException if ownership remains unresolved; close may be retried
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Allocation and token instrumentation of one contained attempt.
	 */
	public interface Instrumentation
	{
		/**
		 * Attaches to the held root, lets it exec through {@code release} and instruments the image it executes.
		 * @param release releases the held root; called once
		 * @throws IOException if attachment or instrumentation fails
		 */
		void start(AllocationHooks.Release release) throws IOException;

		/**
		 * Services native events and held images without reentering the queue.
		 * @throws IOException if delivery fails
		 */
		void pump() throws IOException;

		/**
		 * Grants one published allocation wait.
		 * @param wait selected wait
		 * @throws IOException if the grant is uncertain
		 */
		void grant(AllocationWaits.Wait wait) throws IOException;

		/** Withdraws grant authority without unloading code or resuming parked tasks. */
		void stop();

		/**
		 * Destroys native resources once no process of the attempt can execute.
		 * @param barrier observation that no process of the attempt can execute
		 * @throws IOException if the barrier is not reached or native cleanup fails
		 */
		void close(AllocationHook.ExecutionBarrier barrier) throws IOException;
	}

	/**
	 * Production owners: a cgroup-v2 subtree registered with the memory authority, the invocation's jobserver lease
	 * and either the preloaded build hook or Frida instrumentation.
	 */
	public static final class NativeOwners implements Natives
	{
		/** Borrowed shared values. */
		private final ToolingScope scope;
		/** Borrowed build device whose manager every attempt's instrumentation uses. */
		private final LinuxFridaDevice device;
		/** Borrowed opened memory authority. */
		private final LinuxAuthority authority;
		/** Invocation-owned image event routing. */
		private final AllocationHooks.Routing routing;
		/** Mountpoint of the cgroup-v2 hierarchy that process membership records are relative to. */
		private final Path cgroupRoot;
		/** Invocation jobserver lease borrowed across all attempts. */
		private final LinuxCargoJobserver.CargoLease lease;
		/** Bound on each native control. */
		private final Duration timeout;
		/** Build hook library that instruments attempts instead of Frida, or empty to instrument them with Frida. */
		private final Optional<Path> buildHook;
		/** Number of the next attempt's build hook socket, unique within the invocation's scratch directory. */
		private final AtomicLong nextEndpoint = new AtomicLong();

		/**
		 * Borrows the invocation's native owners without acquiring anything.
		 * @param scope      borrowed shared values
		 * @param device     borrowed build device
		 * @param authority  borrowed opened memory authority
		 * @param routing    invocation-owned image event routing
		 * @param cgroupRoot absolute cgroup-v2 mountpoint
		 * @param lease      invocation jobserver lease
		 * @param timeout    positive control bound
		 * @param buildHook  absolute build hook library that instruments attempts instead of Frida, with its trampoline
		 *                   beside it, or empty to instrument them with Frida
		 * @throws NullPointerException     if an input is null
		 * @throws IllegalArgumentException if the timeout is invalid
		 */
		NativeOwners(ToolingScope scope, LinuxFridaDevice device, LinuxAuthority authority,
			AllocationHooks.Routing routing, Path cgroupRoot, LinuxCargoJobserver.CargoLease lease, Duration timeout,
			Optional<Path> buildHook)
		{
			this.scope = Objects.requireNonNull(scope, "scope");
			this.device = Objects.requireNonNull(device, "device");
			this.authority = Objects.requireNonNull(authority, "authority");
			this.routing = Objects.requireNonNull(routing, "routing");
			this.cgroupRoot = Objects.requireNonNull(cgroupRoot, "cgroupRoot");
			this.lease = Objects.requireNonNull(lease, "lease");
			this.timeout = checkedTimeout(timeout);
			this.buildHook = Objects.requireNonNull(buildHook, "buildHook");
		}

		/**
		 * Creates subtree {@code G} with leaf {@code G/w}, moves the child into the leaf, checks through the child's
		 * own membership record that it arrived, checks that it is still the same process with the same parent, and
		 * registers {@code G} with the memory authority.
		 */
		@Override
		public Containment contain(String name, long sequence, LinuxProcessTree.Launch launch,
			LinuxCargoProtocol.Child child, long parent) throws IOException
		{
			LinuxCgroup group = LinuxCgroup.create(launch.cgroupParent(), timeout);
			try
			{
				Path leaf = Files.createDirectory(group.directory().resolve("w"));
				Files.writeString(leaf.resolve("cgroup.procs"), Long.toString(child.pid()), StandardOpenOption.WRITE);
				Path process = Path.of("/proc", Long.toString(child.pid()));
				String expected = "0::/" + cgroupRoot.relativize(leaf) + "\n";
				if (!Files.readString(process.resolve("cgroup")).equals(expected))
					throw new IOException("Attempt root " + child.pid() + " did not enter its cgroup " + leaf);
				String stat = Files.readString(process.resolve("stat"));
				String[] fields = stat.substring(stat.lastIndexOf(')') + 1).strip().split(" ");
				if (AllocationHooks.startTime(stat) != child.startTime() || !fields[1].equals(Long.toString(parent)))
					throw new IOException("Attempt root " + child.pid() + " changed while it was contained");
				LinuxAuthority.Registration registration = authority.register(name, sequence, group.directory(),
					child.pid(), () -> !group.isPopulated());
				return new CgroupContainment(group, registration);
			}
			catch (IOException | RuntimeException | Error failure)
			{
				try
				{
					group.close();
				}
				catch (IOException | RuntimeException cleanup)
				{
					failure.addSuppressed(cleanup);
				}
				throw failure;
			}
		}

		@Override
		public LinuxCargoTokens.Attempt retainTokens() throws IOException
		{
			return lease.retainAttempt();
		}

		/**
		 * With a build hook, the attempt's socket is a fresh path in the launch's scratch directory, owned by the
		 * workload, and the workload's own preloads stay loaded after the library.
		 */
		@Override
		public Instrumentation instrument(UUID identity, LinuxProcessTree.Launch launch, AllocationHooks.Root root,
			LinuxCargoTokens.Attempt tokens, AllocationWaits waits, AllocationHook.Pump peers) throws IOException
		{
			var cargoTokens = new AllocationHooks.CargoTokens(lease.identity(), tokens);
			if (buildHook.isEmpty())
			{
				return new AllocationHooks(scope, identity, device, launch, root, waits, timeout, peers,
					Optional.of(cargoTokens), routing);
			}
			Path endpoint = launch.scratchParent().resolve("h" + nextEndpoint.getAndIncrement());
			return new LinuxBuildHookServer(scope, identity, root, cgroupRoot, launch.uid(), launch.gid(),
				buildHook.get(), LinuxBuildHookServer.inheritedPreloads(launch.environment()), endpoint, waits, timeout,
				peers, Optional.of(cargoTokens));
		}
	}

	/**
	 * Owned cgroup subtree and its memory-authority registration.
	 */
	private static final class CgroupContainment implements Containment
	{
		/** Owned subtree. */
		private final LinuxCgroup group;
		/** Registration, or null once it was removed. */
		private LinuxAuthority.Registration registration;

		/**
		 * Takes ownership of a registered subtree.
		 * @param group        owned subtree
		 * @param registration its registration
		 */
		private CgroupContainment(LinuxCgroup group, LinuxAuthority.Registration registration)
		{
			this.group = group;
			this.registration = registration;
		}

		@Override
		public Path directory()
		{
			return group.directory();
		}

		@Override
		public boolean isPopulated() throws IOException
		{
			return group.isPopulated();
		}

		@Override
		public void terminate() throws IOException
		{
			group.terminate();
		}

		/**
		 * Removes the registration before the subtree, so the authority never samples a removed group.
		 */
		@Override
		public void close() throws IOException
		{
			if (registration != null)
			{
				registration.close();
				registration = null;
			}
			group.close();
		}
	}

	/** Queue notification, distinct from native installation and allocation identities.
	 * @param name action identity
	 * @param attemptSequence queue attempt ordinal
	 * @param waitSequence per-attempt batch ordinal
	 */
	public record Blocked(String name, long attemptSequence, long waitSequence)
	{
		/** Validates the three distinct queue correlation components.
		 * @throws IllegalArgumentException if an identity is invalid
		 */
		public Blocked
		{
			Objects.requireNonNull(name, "name");
			if (name.isBlank() || attemptSequence < 0 || waitSequence < 0)
				throw new IllegalArgumentException("Blocked notification requires a name and nonnegative ordinals");
		}
	}

	/** Owns production notification order independently of the queue's active controls. */
	public static final class Notifications
	{
		/** Events in first-observed order, including those observed during permission acknowledgement. */
		private final ArrayDeque<Blocked> pending = new ArrayDeque<>();
		/** Creates an empty resource-free FIFO. */
		public Notifications() {}
		/** Appends a deferred event in observation order.
		 * @param blocked validated event to append
		 */
		public void add(Blocked blocked) { pending.addLast(Objects.requireNonNull(blocked, "blocked")); }
		/** Consumes the next event outside queue controls.
		 * @return oldest event, or empty
		 */
		public Optional<Blocked> poll() { return Optional.ofNullable(pending.pollFirst()); }
	}
    /** Retains immutable launch inputs independently of native runtime acquisition. */
    public static final class Commands
    {
        /** Only thread allowed to append or retrieve commands. */
        private final Thread owner = Thread.currentThread();
        /** Names stay reserved for the whole invocation. */
        private final Map<String, Command> values = new LinkedHashMap<>();
        /** Creates an empty resource-free registry. */
        public Commands() {}
        /**
         * Appends one command atomically without filesystem access.
         * @param name nonblank unused identity
         * @param command immutable validated input
         */
        public void register(String name, Command command)
        {
            requireOwner();
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            if (name.isBlank() || values.containsKey(name))
                throw new IllegalArgumentException("Action name is blank or already reserved: " + name);
            values.put(name, command);
        }
        /**
         * Retrieves exact configured inputs without transferring native ownership.
         * @param name action identity
         * @return configured command, or null for unknown identity
         */
        public Command get(String name) { requireOwner(); return values.get(name); }
        /** Rejects cross-thread registry access before observing inputs. */
        private void requireOwner()
        {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Linux commands require their owner thread");
        }
    }

}
