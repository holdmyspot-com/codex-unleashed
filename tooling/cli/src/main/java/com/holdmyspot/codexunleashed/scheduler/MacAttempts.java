package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Creates fresh native compiler attempts and defers their allocation notifications outside queue controls.
 * Constructing-thread confinement applies; peer descriptors and invocation services remain borrowed.
 */
public final class MacAttempts implements JobQueue.AttemptFactory<Integer>
{
    /** Native controller thread. */
    private final Thread thread = Thread.currentThread();
    /** Borrowed invocation services retained through native cleanup. */
    private final ToolingScope scope;
    /** Borrowed started native runtime. */
    private final FridaRuntime runtime;
    /** Exact live native-owner registry. */
    private final MacMeasurements.NativeSource measurements;
    /** Trusted absolute workload bridge. */
    private final Path library;
    /** Trusted absolute non-forking anchor. */
    private final Path helper;
    /** Trusted installed hook script dylibs. */
    private final MacNativeModules modules;
    /** Invocation-owned parent for fresh attempt directories. */
    private final Path directory;
    /** Positive acknowledgement and cleanup bound. */
    private final Duration timeout;
    /** Authenticated invocation user identifier. */
    private final int uid;
    /** Authenticated invocation group identifier. */
    private final int gid;
    /** Borrowed invocation dispatcher; native workload owns image delivery. */
    private final AllocationHooks.Routing routing;
    /** Commands remain reserved after their attempts close. */
    private final Map<String, Command> commands = new LinkedHashMap<>();
    /** Native cleanup owners remain reachable until complete successful closure. */
    private final Map<Long, MacAttempt> live = new LinkedHashMap<>();
    /** Wait notifications in first-observed order, delivered outside queue controls. */
    private final ArrayDeque<Blocked> pending = new ArrayDeque<>();
    /** Highest issued queue ordinal; minus one before the first attempt. */
    private long lastSequence = -1;
    /** Immutable decoded reports retained before successful live-owner removal. */
    private final Map<Long, List<AllocationReceipt>> collectedDenials = new LinkedHashMap<>();
    /** Successful native retirements retained after live attempt removal. */
    private final Retirements retirements = new Retirements();

    /**
     * Reads authenticated compiler correlation and genuine decoded reports across live-owner removal.
     * @return immutable attempt-order snapshot; observation performs no native operation
     */
    List<AllocationReceipt> allocationReceipts()
    {
        requireThread();
        var receipts = new ArrayList<AllocationReceipt>();
        collectedDenials.values().forEach(receipts::addAll);
        for (MacAttempt attempt : live.values())
            for (AllocationHook.Denial denial : attempt.denials())
                receipts.add(new AllocationReceipt(attempt.name(), attempt.sequence(), denial));
        receipts.sort(java.util.Comparator.comparingLong(AllocationReceipt::attemptSequence));
        return List.copyOf(receipts);
    }

    /**
     * Binds a validated native report to its original authenticated compiler action.
     * @param name authenticated compiler request name
     * @param attemptSequence queue-wide original attempt ordinal
     * @param denial genuine immutable decoder receipt, never reconstructed from queue notifications
     */
    public record AllocationReceipt(String name, long attemptSequence, AllocationHook.Denial denial) {}

    /**
     * Returns successful native attempt retirements in their publication order.
     * @return immutable snapshot retained across attempt removal; empty before any complete retirement
     */
    List<Retirement> retirements() { requireThread(); return retirements.snapshot(); }

    /**
     * Borrows the invocation retirement ledger for an attempt's terminal publication.
     * @return ledger owned by this factory
     */
    Retirements retirementLedger() { requireThread(); return retirements; }

    /**
     * Retains invocation configuration without native acquisition.
     * @param scope borrowed shared services
     * @param runtime started native runtime
     * @param measurements exact native owner registry
     * @param library trusted absolute workload bridge
     * @param helper trusted absolute workload anchor
     * @param modules trusted installed hook script dylibs
     * @param directory caller-owned absolute scratch root
     * @param uid positive authenticated workload user identifier
     * @param gid positive authenticated workload group identifier
     * @param timeout positive acknowledgement and cleanup bound
     * @param routing invocation image routing retained through cleanup
     */
    MacAttempts(ToolingScope scope, FridaRuntime runtime, MacMeasurements.NativeSource measurements,
        Path library, Path helper, MacNativeModules modules, Path directory, int uid, int gid, Duration timeout,
        AllocationHooks.Routing routing)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.measurements = Objects.requireNonNull(measurements, "measurements");
        this.library = absolute(library);
        this.helper = absolute(helper);
        this.modules = Objects.requireNonNull(modules, "modules");
        this.directory = absolute(directory);
        if (uid <= 0 || gid <= 0) throw new IllegalArgumentException("Positive workload credentials required");
        this.uid = uid;
        this.gid = gid;
        this.timeout = LinuxAttempts.checkedTimeout(timeout);
        this.routing = Objects.requireNonNull(routing, "routing");
    }

    /**
     * Reserves authenticated compiler inputs while retaining original peer descriptors across retries.
     * @param name stable request identity
     * @param request authenticated immutable compiler request
     * @param peer original invocation-owned wrapper peer
     * @param lease invocation-owned FIFO lease
     * @throws IOException if peer identity is unavailable or differs from invocation credentials
     */
    void register(String name, CargoCompilerRequest request, MacCargoNative.Peer peer,
        MacCargoJobserver.CargoLease lease) throws IOException
    {
        requireThread();
        Command command = new Command(request, peer, lease);
        MacCargoNative.KernelIdentity identity = peer.identity();
        if (identity.uid() != uid || identity.gid() != gid)
            throw new IOException("Authenticated compiler peer credentials differ from the invocation");
        if (!request.name().equals(name) || commands.containsKey(name))
            throw new IllegalArgumentException("Compiler action must use its unused authenticated request name");
        commands.put(name, command);
    }

    @Override public MacAttempt create(String name, long attemptSequence)
    {
        requireThread();
        Command command = commands.get(name);
        if (command == null || attemptSequence < 0 || attemptSequence <= lastSequence)
            throw new IllegalArgumentException("Unknown compiler action or reused attempt ordinal");
        MacAttempt attempt = new MacAttempt(scope, this, runtime, measurements, library, helper, modules,
            directory, timeout, name, attemptSequence, command);
        live.put(attemptSequence, attempt);
        lastSequence = attemptSequence;
        return attempt;
    }

    /**
     * Services hook frames fairly and publishes waits outside active queue controls.
     * @throws IOException if an authentic attempt observation fails
     */
    void pumpAll() throws IOException
    {
        requireThread();
        for (MacAttempt attempt : List.copyOf(live.values()))
        {
            attempt.pumpEvents();
            OptionalLong wait = attempt.pollAllocationWait();
            if (wait.isPresent()) pending.addLast(new Blocked(attempt.name(), attempt.sequence(), wait.getAsLong()));
        }
    }

    /**
     * Consumes the oldest deferred allocation event.
     * @return oldest event, or empty when none is pending
     */
    Optional<Blocked> pollBlocked() { requireThread(); return Optional.ofNullable(pending.pollFirst()); }

    /**
     * Indicates whether any native attempt still requires invocation services.
     * @return true while a cleanup owner remains retained
     */
    boolean hasOpenAttempts() { requireThread(); return !live.isEmpty(); }

    /**
     * Indicates whether a logical action still has a retained native cleanup owner.
     * @param name authenticated action name
     * @return true while an attempt of this action remains retained
     */
    boolean hasOpenAttempts(String name)
    {
        requireThread();
        Objects.requireNonNull(name, "name");
        return live.values().stream().anyMatch(attempt -> attempt.name().equals(name));
    }

    /**
     * Returns invocation image routing without transferring ownership.
     * @return borrowed dispatcher
     */
    AllocationHooks.Routing routing() { requireThread(); return routing; }

    /**
     * Removes one owner after its complete terminal cleanup.
     * @param attempt exact closed owner
     */
    void forget(MacAttempt attempt)
    {
        requireThread();
        if (live.get(attempt.sequence()) != attempt) throw new IllegalStateException("Attempt is not retained");
        collectedDenials.put(attempt.sequence(), attempt.denials().stream()
            .map(denial -> new AllocationReceipt(attempt.name(), attempt.sequence(), denial)).toList());
        live.remove(attempt.sequence());
    }

    /**
     * Validates an absolute local path without filesystem effects.
     * @param path native configuration path
     * @return original absolute path
     */
    private static Path absolute(Path path)
    {
        Objects.requireNonNull(path, "path");
        if (!path.isAbsolute()) throw new IllegalArgumentException("Native configuration path must be absolute");
        return path;
    }

    /** Rejects controls outside the native owner thread. */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Mac attempts belong to their constructing thread");
    }

    /**
     * Retains authenticated action inputs and borrowed invocation capabilities.
     * @param request authenticated compiler inputs
     * @param peer original peer retained through every attempt
     * @param lease original invocation FIFO lease
     */
    record Command(CargoCompilerRequest request, MacCargoNative.Peer peer, MacCargoJobserver.CargoLease lease)
    {
        /**
         * Rejects missing borrowed authority before registration.
         * @param request authenticated compiler inputs
         * @param peer original peer
         * @param lease invocation lease
         */
        Command
        {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(lease, "lease");
        }
    }

    /**
     * Carries deferred queue correlation without granting native permission itself.
     * @param name stable action name
     * @param attemptSequence queue-wide ordinal
     * @param waitSequence attempt allocation batch ordinal
     */
    record Blocked(String name, long attemptSequence, long waitSequence)
    {
        /**
         * Rejects incomplete queue correlation.
         * @param name stable action name
         * @param attemptSequence queue-wide ordinal
         * @param waitSequence attempt allocation batch ordinal
         */
        Blocked
        {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || attemptSequence < 0 || waitSequence < 0)
                throw new IllegalArgumentException("Nonblank action and nonnegative queue ordinals required");
        }
    }

    /**
     * Records an original root's authenticated native authority and complete successful retirement.
     * Construction validates a value; only the native producer's terminal publication establishes its authority.
     * @param name stable authenticated compiler action
     * @param attemptSequence queue-wide attempt ordinal
     * @param rootAuthority all eighteen native authority fields captured before original root release
     * @param result genuine original root exit status
     * @param aborted indicates whether termination was requested for this attempt
     * @param retirementSequence zero-based successful retirement publication order for this invocation
     */
    public record Retirement(String name, long attemptSequence, List<Long> rootAuthority, int result,
        boolean aborted, long retirementSequence)
    {
        /**
         * Freezes exact native fields and rejects incomplete receipt values.
         * @param name stable compiler action
         * @param attemptSequence queue-wide ordinal
         * @param rootAuthority eighteen authenticated native fields
         * @param result original root exit status in 0 through 255
         * @param aborted indicates whether termination was requested for this attempt
         * @param retirementSequence zero-based retirement order
         */
        public Retirement
        {
            new Blocked(name, attemptSequence, retirementSequence);
            rootAuthority = new MacAttempt.Collected(rootAuthority, result, aborted).rootAuthority();
        }
    }

    /** Retains successful native retirements independently of the live attempt registry. */
    public static final class Retirements
    {
        /** Completed receipts in deterministic publication order. */
        private final List<Retirement> completed = new ArrayList<>();

        /** Creates an empty invocation-owned ledger without external effects. */
        public Retirements() {}

        /**
         * Appends one exact terminal receipt after its owner finishes successful cleanup.
         * @param name authenticated compiler action
         * @param attemptSequence queue-wide ordinal
         * @param collected complete native collection certified by the execution backend
         */
        void append(String name, long attemptSequence, MacAttempt.Collected collected)
        {
            Objects.requireNonNull(collected, "collected");
            if (completed.stream().anyMatch(receipt -> receipt.attemptSequence() == attemptSequence))
                throw new IllegalStateException("Attempt retirement was already published");
            completed.add(new Retirement(name, attemptSequence, collected.rootAuthority(), collected.result(),
                collected.aborted(), completed.size()));
        }

        /**
         * Copies successful receipts without transferring ledger ownership.
         * @return immutable receipts in successful retirement order
         */
        public List<Retirement> snapshot() { return List.copyOf(completed); }
    }
}
