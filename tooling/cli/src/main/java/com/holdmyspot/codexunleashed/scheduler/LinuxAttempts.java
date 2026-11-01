package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates resource-free Linux owners and defers their allocation notifications until outside queue controls.
 * All operations belong to the constructing thread. Runtime, authority and live attempts are borrowed;
 * JobQueue exclusively owns attempt cleanup. A failed cleanup remains visible through hasOpenAttempts.
 */
final class LinuxAttempts implements JobQueue.AttemptFactory<LinuxSupervisor.Result>
{
	/** Thread permitted to mutate the borrowed registry and notifications. */
	private final Thread owner = Thread.currentThread();
	/** Correlation seed shared by this build's distinct attempt ordinals. */
	private final UUID build;
	/** Borrowed shared values for every attempt. */
	private final ToolingScope scope;
	/** Borrowed started native runtime. */
	private final FridaRuntime runtime;
	/** Borrowed opened measurement authority. */
	private final LinuxAuthority authority;
	/** Validated immutable action inputs. */
	private final Commands commands = new Commands();
	/** Positive native control deadline. */
	private final Duration timeout;
    /** Explicit build/invocation event routing; no queue or action admission authority. */
    private final AllocationHooks.Routing routing;

	/** Borrowed owners in creation order, retained after failed cleanup. */
	private final Map<Long, LinuxAttempt> live = new LinkedHashMap<>();
	/** Highest reserved ordinal, never reset after cleanup. */
	private long lastSequence = -1;
	/** Deferred notifications; no queue callback is retained. */
	private final Notifications notifications = new Notifications();

	/**
	 * Validates and copies configuration without native calls or filesystem access.
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param build correlation seed
	 * @param runtime borrowed started runtime
	 * @param authority borrowed opened authority
	 * @param commands complete action mapping
	 * @param timeout positive control timeout
	 * @throws NullPointerException if an input is null
	 * @throws IllegalArgumentException if a name or timeout is invalid
	 */
	LinuxAttempts(ToolingScope scope, UUID build, FridaRuntime runtime, LinuxAuthority authority, Map<String, Command> commands,
		Duration timeout)
    {
        this(scope, build, runtime, authority, commands, timeout, new AllocationHooks.Routing());
    }

    /** Borrows an explicit invocation dispatcher shared by all authentic attempt controllers.
     * @param scope borrowed shared values
     * @param build correlation seed
     * @param runtime borrowed runtime
     * @param authority borrowed memory authority
     * @param commands immutable action mapping
     * @param timeout bounded native control interval
     * @param routing invocation-owned image event routing
     */
    LinuxAttempts(ToolingScope scope, UUID build, FridaRuntime runtime, LinuxAuthority authority, Map<String, Command> commands,
        Duration timeout, AllocationHooks.Routing routing)

	{
		this.routing = Objects.requireNonNull(routing, "routing");
		this.scope = Objects.requireNonNull(scope, "scope");
		this.build = Objects.requireNonNull(build, "build");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.authority = Objects.requireNonNull(authority, "authority");
		Map.copyOf(commands).forEach(this::register);
		this.timeout = checkedTimeout(timeout);
	}

    /** Borrows this invocation's original-controller dispatcher without granting action authority.
     * @return explicit shared routing owner
     */
    AllocationHooks.Routing routing() { requireOwner(); return routing; }

    /**
     * Appends an immutable action before queue submission, without acquiring resources.
     * Names remain reserved after all attempts finish; replacement is never permitted.
     * @param name stable action name
     * @param command validated immutable launch inputs
     * @throws IllegalArgumentException if the name is blank or already reserved
     */
    void register(String name, Command command)
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
		LinuxAttempt attempt = new LinuxAttempt(scope, this, runtime, authority, name, attemptSequence, identity, command, timeout);
		live.put(attemptSequence, attempt);
		lastSequence = attemptSequence;
		return attempt;
	}

	/** Services a fair snapshot without reentering JobQueue.
	 * @throws IOException if native delivery or batch publication fails
	 */
	void pumpAll() throws IOException
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
	Optional<Blocked> pollBlocked()
	{
		requireOwner();
		return notifications.poll();
	}

	/** Checks whether borrowed native ownership must remain available.
	 * @return whether an attempt still retains cleanup ownership
	 */
	boolean hasOpenAttempts()
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
	static UUID attemptIdentity(UUID build, long attemptSequence)
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
	static Duration checkedTimeout(Duration timeout)
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

	/** Immutable attached workload configuration; constructing it acquires no native owners. */
	sealed interface Command permits FilesCommand, BorrowedCommand, CargoCommand
	{
		/** Returns the exact approved execution inputs.
		 * @return immutable attached launch
		 */
		LinuxProcessTree.Launch launch();
	}

	/** Immutable caller-owned workload inputs with file-based standard streams.
	 * @param launch copied validated launch
	 * @param files caller-owned standard endpoints
	 */
	record FilesCommand(LinuxProcessTree.Launch launch, LinuxProcessTree.StandardFiles files) implements Command
	{
		/** Validates attached execution without inspecting any filesystem paths.
		 * @throws IllegalArgumentException if execution is not explicitly instrumented
		 */
		FilesCommand
		{
			Objects.requireNonNull(launch, "launch");
			Objects.requireNonNull(files, "files");
			if (launch.executionMode() != LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC)
				throw new IllegalArgumentException("Instrumented attempt requires attached leader execution");
		}
	}

	/** Supplies an already authenticated stream receipt for one issued queue attempt.
	 * The invocation retains listeners, wrapper endpoints and any partial acquisition through cleanup.
	 * This boundary does not create registration authority or transfer their ownership to the attempt.
	 */
	@FunctionalInterface
	interface Bindings
	{
		/** Selects a fresh receipt without starting the compiler or resending a prior transfer.
		 * The producer maps this queue-wide ordinal to the retained Cargo action-local ordinal.
		 * @param attemptSequence nonnegative queue-wide ordinal already issued by CargoSession
		 * @return independently authenticated binding for the exact action and current retry
		 * @throws IOException if authentication or receipt selection fails; the invocation retains cleanup responsibility
		 */
		LinuxBorrowedStreams.Binding forAttempt(long attemptSequence) throws IOException;
	}

	/** Immutable attached execution with invocation-owned stream registration across retries.
	 * @param launch exact approved compiler execution
	 * @param bindings borrowed provider of independently authenticated per-attempt receipts
	 */
	record BorrowedCommand(LinuxProcessTree.Launch launch, Bindings bindings) implements Command
	{
		/** Validates configuration without requesting a binding or acquiring native resources.
		 * @throws NullPointerException if either input is null
		 * @throws IllegalArgumentException if execution is not explicitly attached
		 */
		BorrowedCommand
		{
			Objects.requireNonNull(launch, "launch");
			Objects.requireNonNull(bindings, "bindings");
			if (launch.executionMode() != LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC)
				throw new IllegalArgumentException("Borrowed attempt requires attached leader execution");
		}

		/** Selects the current receipt and rejects another action or execution before native preparation.
		 * Construction of a Binding alone does not authenticate it; the invocation supplies that authority.
		 * @param name registered Cargo action UUID text
		 * @param attemptSequence nonnegative issued queue-wide ordinal
		 * @return exact selected binding, with no transfer performed
		 * @throws IllegalArgumentException if the ordinal is negative
		 * @throws IOException if selection fails, returns no receipt or supplies another action or execution
		 */
		LinuxBorrowedStreams.Binding binding(String name, long attemptSequence) throws IOException
		{
			Objects.requireNonNull(name, "name");
			if (attemptSequence < 0) throw new IllegalArgumentException("Attempt ordinal must be nonnegative");
			LinuxBorrowedStreams.Binding binding = bindings.forAttempt(attemptSequence);
			if (binding == null || name.equals(binding.actionId().toString()) == false)
				throw new IOException("Borrowed stream receipt does not identify the registered action");
			binding.requireExecution(launch);
			return binding;
		}
	}

	/**
	 * Immutable borrowed compiler execution bound to its retained invocation jobserver lease.
	 * The invocation authenticates each stream receipt and keeps this lease open across all retries and descendants.
	 * Constructing or registering this command neither borrows an extra token nor transfers lease ownership.
	 * @param command independently approved borrowed compiler execution and per-attempt stream receipt provider
	 * @param lease live invocation lease borrowed until every attempt and descendant has been retired
	 */
	record CargoCommand(BorrowedCommand command, LinuxCargoJobserver.CargoLease lease) implements Command
	{
		/**
		 * Retains immutable borrowed inputs without acquiring resources or selecting a stream receipt.
		 * @throws NullPointerException if either input is null
		 */
		CargoCommand
		{
			Objects.requireNonNull(command, "command");
			Objects.requireNonNull(lease, "lease");
		}

		/**
		 * Returns the exact approved compiler launch without requesting a binding.
		 * @return the borrowed command's immutable launch
		 */
		@Override
		public LinuxProcessTree.Launch launch() { return command.launch(); }
	}

	/** Queue notification, distinct from native installation and allocation identities.
	 * @param name action identity
	 * @param attemptSequence queue attempt ordinal
	 * @param waitSequence per-attempt batch ordinal
	 */
	record Blocked(String name, long attemptSequence, long waitSequence)
	{
		/** Validates the three distinct queue correlation components.
		 * @throws IllegalArgumentException if an identity is invalid
		 */
		Blocked
		{
			Objects.requireNonNull(name, "name");
			if (name.isBlank() || attemptSequence < 0 || waitSequence < 0)
				throw new IllegalArgumentException("Blocked notification requires a name and nonnegative ordinals");
		}
	}

	/** Owns production notification order independently of the queue's active controls. */
	static final class Notifications
	{
		/** Events in first-observed order, including those observed during permission acknowledgement. */
		private final ArrayDeque<Blocked> pending = new ArrayDeque<>();
		/** Creates an empty resource-free FIFO. */
		Notifications() {}
		/** Appends a deferred event in observation order.
		 * @param blocked validated event to append
		 */
		void add(Blocked blocked) { pending.addLast(Objects.requireNonNull(blocked, "blocked")); }
		/** Consumes the next event outside queue controls.
		 * @return oldest event, or empty
		 */
		Optional<Blocked> poll() { return Optional.ofNullable(pending.pollFirst()); }
	}
    /** Retains immutable launch inputs independently of native runtime acquisition. */
    static final class Commands
    {
        /** Only thread allowed to append or retrieve commands. */
        private final Thread owner = Thread.currentThread();
        /** Names stay reserved for the whole invocation. */
        private final Map<String, Command> values = new LinkedHashMap<>();
        /** Creates an empty resource-free registry. */
        Commands() {}
        /**
         * Appends one command atomically without filesystem access.
         * @param name nonblank unused identity
         * @param command immutable validated input
         */
        void register(String name, Command command)
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
        Command get(String name) { requireOwner(); return values.get(name); }
        /** Rejects cross-thread registry access before observing inputs. */
        private void requireOwner()
        {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Linux commands require their owner thread");
        }
    }

}
