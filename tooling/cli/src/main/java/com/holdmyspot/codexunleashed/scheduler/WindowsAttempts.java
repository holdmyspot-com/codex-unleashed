package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates Windows attempt owners and queues allocation notifications outside JobQueue controls.
 * The constructing thread owns the registry; JobQueue alone owns each returned attempt.
 * The invocation retains borrowed Cargo jobserver leases across these attempts and retries.
 */
final class WindowsAttempts implements JobQueue.AttemptFactory<WindowsProcess.Result>
{
    /** Only thread permitted to control these owners. */
    private final Thread owner = Thread.currentThread();
    /** Build correlation seed shared across distinct attempt ordinals. */
    private final UUID build;
    /** Copied action configuration, indexed by stable name. */
    private final Map<String, Command> commands;
    /** Positive bounded native control interval. */
    private final Duration timeout;
    /** Retained acquisition plan, including any checked preparation-failure capsule. */
    private final ResourcesFactory resources;
    /** Borrowed queue-owned attempts in creation order, including failed cleanup. */
    private final Map<Long, WindowsAttempt> live = new LinkedHashMap<>();
    /** Copied batches in observation order, consumed outside queue controls. */
    private final ArrayDeque<Blocked> notifications = new ArrayDeque<>();
    /** Highest reserved queue ordinal, never reused after failure. */
    private long lastSequence = -1;
    /** Whether an outer peer-service round is already advancing children. */
    private boolean pumping;

    /**
     * Borrows a started runtime and measurement adapter without acquiring native resources.
     * @param build build correlation seed
     * @param runtime borrowed started Frida runtime
     * @param measurements borrowed Windows measurement adapter
     * @param commands stable-name command mapping with distinct original positions
     * @param timeout positive representable control interval
     */
    WindowsAttempts(UUID build, FridaRuntime runtime, WindowsMeasurements measurements,
        Map<String, Command> commands, Duration timeout)
    {
        this(build, commands, timeout, nativeResources(runtime, measurements));
    }

    /**
     * Supplies native resource-free acquisition plans after validating borrowed owners.
     * @param runtime borrowed started Frida runtime
     * @param measurements borrowed Windows measurement adapter
     * @return resource-free native acquisition-plan factory
     */
    private static ResourcesFactory nativeResources(FridaRuntime runtime, WindowsMeasurements measurements)
    {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(measurements, "measurements");
        return (command, sequence, identity, timeout) ->
            new WindowsAttempt.NativeResources(runtime, measurements, command, sequence, identity, timeout);
    }

    /**
     * Allows controlled lifecycle tests to supply acquisition plans, never fabricated native evidence.
     * @param build build correlation seed
     * @param commands stable-name command mapping with distinct original positions
     * @param timeout positive representable control interval
     * @param resources resource-free acquisition plan retaining any failed preparation capsule
     */
    WindowsAttempts(UUID build, Map<String, Command> commands, Duration timeout, ResourcesFactory resources)
    {
        this.build = Objects.requireNonNull(build, "build");
        this.commands = Map.copyOf(commands);
        this.timeout = checkedTimeout(timeout);
        this.resources = Objects.requireNonNull(resources, "resources");
        var positions = new HashSet<Long>();
        for (var entry : this.commands.entrySet())
            if (entry.getKey().equals(entry.getValue().action().name()) == false ||
                positions.add(entry.getValue().action().position()) == false)
                throw new IllegalArgumentException("Commands require matching names and distinct original positions");
    }

    @Override
    public WindowsAttempt create(String name, long attemptSequence)
    {
        requireOwner();
        Command command = commands.get(name);
        if (command == null || attemptSequence < 0 || attemptSequence <= lastSequence)
            throw new IllegalArgumentException("Unknown action or reused attempt ordinal: " + name + "/" + attemptSequence);
        UUID identity = attemptIdentity(build, attemptSequence);
        // Reserve before calling caller-supplied code; failure must not make this ordinal reusable.
        lastSequence = attemptSequence;
        WindowsAttempt attempt = new WindowsAttempt(this, name, attemptSequence,
            resources.create(command, attemptSequence, identity, timeout), timeout);
        live.put(attemptSequence, attempt);
        return attempt;
    }

    /**
     * Pumps a creation-order snapshot. Nested peer service copies frames without recursively advancing children.
     * @throws IOException if any live attempt reports delivery or batch-publication failure
     */
    void pumpAll() throws IOException
    {
        requireOwner();
        boolean advance = pumping == false;
        boolean previous = pumping;
        pumping = true;
        try
        {
            for (WindowsAttempt attempt : List.copyOf(live.values()))
            {
                attempt.pumpEvents(advance);
                var batch = attempt.pollAllocationWait();
                if (batch.isPresent())
                    notifications.addLast(new Blocked(attempt.name(), attempt.sequence(), batch.getAsLong()));
            }
        }
        finally { pumping = previous; }
    }

    /**
     * Delivers the oldest copied notification; callers must drain this outside queue control operations.
     * @return oldest deferred batch, or empty
     */
    Optional<Blocked> pollBlocked() { requireOwner(); return Optional.ofNullable(notifications.pollFirst()); }
    /**
     * Reports retained attempt ownership, including failed cleanup.
     * @return whether the factory still retains any attempt owner
     */
    boolean hasOpenAttempts() { requireOwner(); return live.isEmpty() == false; }
    /**
     * Forgets only the exact successfully cleaned owner.
     * @param attempt exact successfully cleaned owner to forget
     */
    void forget(WindowsAttempt attempt)
    {
        requireOwner();
        if (live.remove(attempt.sequence(), attempt) == false)
            throw new IllegalStateException("Attempt is not retained by this factory");
    }

    /**
     * Derives correlation from fixed-width big-endian build UUID and queue ordinal.
     * @param build build correlation seed
     * @param sequence reserved queue attempt ordinal
     * @return deterministic correlation from the build seed and ordinal
     */
    static UUID attemptIdentity(UUID build, long sequence)
    {
        Objects.requireNonNull(build, "build");
        if (sequence < 0) throw new IllegalArgumentException("Attempt ordinal must be nonnegative");
        return UUID.nameUUIDFromBytes(ByteBuffer.allocate(24).putLong(build.getMostSignificantBits()).
            putLong(build.getLeastSignificantBits()).putLong(sequence).array());
    }

    /**
     * Validates the positive, representable monotonic deadline before resource acquisition.
     * @param timeout positive representable control interval
     * @return original validated timeout
     */
    static Duration checkedTimeout(Duration timeout)
    {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("Timeout is too large", cause); }
        return timeout;
    }

    /** Rejects cross-thread access before touching native or queue ownership. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Windows attempts require their owner thread");
    }

    /**
     * Copies workload configuration while borrowing standard handles; no path is opened or inspected here.
     * Environment entries, including a borrowed invocation jobserver, are passed unchanged to preparation.
     * @param action original stable action identity and submission position
     * @param arguments copied executable and arguments
     * @param directory working directory passed to preparation
     * @param environment copied environment, including any borrowed invocation jobserver
     * @param input borrowed standard input handle
     * @param output borrowed standard output handle
     * @param error borrowed standard error handle
     */
    record Command(Controller.Action action, List<String> arguments, Path directory, Map<String, String> environment,
        MemorySegment input, MemorySegment output, MemorySegment error)
    {
        /** Validates and copies this immutable configuration before any native acquisition. */
        Command
        {
            Objects.requireNonNull(action, "action");
            if (action.name().isBlank()) throw new IllegalArgumentException("Action name must contain text");
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty() || arguments.getFirst().isBlank())
                throw new IllegalArgumentException("Command requires an executable");
            for (String argument : arguments)
                if (argument.indexOf('\0') >= 0) throw new IllegalArgumentException("Command contains NUL");
            Objects.requireNonNull(directory, "directory");
            environment = Map.copyOf(environment);
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * A queue batch identity, distinct from installation and native allocation wait identifiers.
     * @param name stable action name
     * @param attemptSequence nonnegative queue attempt ordinal
     * @param waitSequence nonnegative queue batch ordinal
     */
    record Blocked(String name, long attemptSequence, long waitSequence)
    {
        /** Validates and copies this immutable configuration before any native acquisition. */
        Blocked
        {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || attemptSequence < 0 || waitSequence < 0)
                throw new IllegalArgumentException("Blocked notification requires a name and nonnegative ordinals");
        }
    }

    /** Creates a resource-free acquisition plan for one reserved attempt. */
    @FunctionalInterface
    interface ResourcesFactory
    {
        /** Creates a resource-free acquisition plan for an already reserved attempt.
         * @param command copied stable action and workload
         * @param sequence reserved attempt ordinal
         * @param identity unique attempt correlation
         * @param timeout positive native control bound
         * @return resource-free plan retaining any partial preparation owner
         */
        WindowsAttempt.Resources create(Command command, long sequence, UUID identity, Duration timeout);
    }
}
