package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owns one manager and every root/descendant installation for an authoritative Windows Job.
 * Held SPAWN events require opaque live membership, not a PID crawl. Unexpected origins stay held.
 * Native callbacks only copy messages; this owner and peer service run on the constructing thread.
 */
public final class WindowsAllocationHooks implements WindowsAttempt.Hooks
{
    /** Only thread permitted to control these owners. */
    private final Thread owner = Thread.currentThread();
    /** Retained native operation bridge; construction performs no acquisition. */
    private final Backend backend;
    /** Fair owner-thread peer service without entering JobQueue controls. */
    private final AllocationHook.Pump peers;
    /** Every partial or ready hook, indexed by never-reused positive generation. */
    private final Map<Long, Hook> installations = new LinkedHashMap<>();
    /** Retained native identity context by PID, including terminal parents. */
    private final Map<Long, Member> images = new LinkedHashMap<>();
    /** Every acquired opaque member identity, retained until instrumentation destruction. */
    private final List<Member> members = new ArrayList<>();
    /** Copied held-child notifications awaiting a bounded transition round. */
    private final ArrayDeque<PendingChild> children = new ArrayDeque<>();
    /** Observers registered before acquisition, retained through actual Job zero. */
    private final Map<Long, Observer> observers = new LinkedHashMap<>();
    /** Transferred creation authority awaiting its matching held SPAWN event. */
    private final Map<Long, CreatedChild> createdChildren = new LinkedHashMap<>();
    /** Child PIDs already delivered by Frida, including consumed events. */
    private final Set<Long> deliveredChildren = new HashSet<>();
    /** Child PIDs reserved before their single possibly uncertain native continuation request. */
    private final Set<Long> resumeAttempts = new HashSet<>();
    /** Monotonic observation source, controlled by modeled tests. */
    private final LongSupplier ticker;
    /** Maximum receipt join interval in nanoseconds. */
    private final long joinTimeoutNanos;
    /** Next observer rotation offset for bounded fair polling. */
    private int observerCursor;
    /** Maximum unmatched held notifications or source receipts per attempt. */
    private static final int MAX_PENDING = 1024;
    /** Maximum callback or observer frames copied in one round. */
    private static final int FRAME_BUDGET = 32;
    /** Longest pause between observations of a resumed child's parked entry point: 1 millisecond. */
    private static final long ARRIVAL_POLL_NANOS = 1_000_000;
    /** Distinct owned manager retained before native startup. */
    private Manager manager;
    /** Next positive generation, reserved with checked arithmetic before acquisition. */
    private long nextInstallation = 1;
    /** One-shot reservation made before acquiring or releasing native resources. */
    private boolean startAttempted;
    /** Whether start, child installation or grant acknowledgement is already advancing. */
    private boolean advancing;
    /** Permanent withdrawal of instrumentation grant authority. */
    private boolean stopped;
    /** Whether every owned resource was released successfully. */
    private boolean closed;
    /** First operational fault; successful cleanup never clears it. */
    private IOException failure;
    /** First unresolved cleanup fault, retained independently of operational failure. */
    private IOException closeFailure;

    /**
     * Borrows native owners without acquiring a manager, process handle or session.
     * @param scope borrowed shared values; the caller retains ownership through all operations
     * @param identity unique attempt correlation UUID
     * @param runtime borrowed started Frida runtime
     * @param process authoritative owned Job, borrowed by instrumentation
     * @param waits shared root and descendant allocation batches
     * @param timeout positive representable control interval
     * @param peers owner-thread peer pump without queue reentry
     */
    public WindowsAllocationHooks(ToolingScope scope, UUID identity, FridaRuntime runtime, WindowsProcess process, AllocationWaits waits,
        Duration timeout, AllocationHook.Pump peers)
    {
        this(nativeBackend(scope, identity, runtime, process, waits, timeout), peers);
    }

    /**
     * Composes an additional per-image protocol before any root or descendant can resume.
     * @param scope borrowed Pouch values
     * @param identity exact attempt identity
     * @param runtime borrowed started Frida runtime
     * @param process authoritative prepared Job owner
     * @param waits attempt allocation batches
     * @param timeout positive native-control interval
     * @param peers owner-thread peer service
     * @param imageHooks additional resource-free image hook factory, or null when absent
     */
    public WindowsAllocationHooks(ToolingScope scope, UUID identity, FridaRuntime runtime, WindowsProcess process,
        AllocationWaits waits, Duration timeout, AllocationHook.Pump peers, ImageHooks imageHooks)
    {
        this(new NativeBackend(scope, identity, runtime, process, waits, WindowsAttempts.checkedTimeout(timeout),
            true, imageHooks), peers);
    }

    /**
     * Creates root Cargo observation without reporting root allocations as compiler-attempt waits.
     * @param scope borrowed Pouch values
     * @param identity invocation identity
     * @param runtime borrowed started runtime
     * @param process prepared root Cargo Job
     * @param timeout positive native-control interval
     * @param imageHooks additional per-image token protocol, or null when absent
     * @return resource-free creation-only adapter
     */
    static Backend creationBackend(ToolingScope scope, UUID identity, FridaRuntime runtime,
        WindowsProcess process, Duration timeout, ImageHooks imageHooks)
    {
        return new NativeBackend(scope, identity, runtime, process, new AllocationWaits(),
            WindowsAttempts.checkedTimeout(timeout), false, imageHooks);
    }

    /**
     * Creates a native-operation adapter without acquiring managers, process handles or sessions.
     * The caller retains the borrowed owners through every coordinator operation and cleanup.
     * @param scope borrowed shared values
     * @param identity unique attempt correlation UUID
     * @param runtime borrowed started Frida runtime
     * @param process authoritative owned Job borrowed by instrumentation
     * @param waits shared root and descendant allocation batches
     * @param timeout positive representable control interval
     * @return resource-free adapter for the maintained native operations
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if the interval is nonpositive or cannot fit nanoseconds
     */
    static Backend nativeBackend(ToolingScope scope, UUID identity, FridaRuntime runtime, WindowsProcess process,
        AllocationWaits waits, Duration timeout)
    {
        return new NativeBackend(scope, identity, runtime, process, waits, WindowsAttempts.checkedTimeout(timeout), true, null);
    }

    /**
     * Supplies the same lifecycle through controlled native-operation ports for model tests.
     * @param backend resource-free native-operation adapter
     * @param peers owner-thread peer pump without queue reentry
     */
    public WindowsAllocationHooks(Backend backend, AllocationHook.Pump peers)
    {
        this(backend, peers, System::nanoTime);
    }

    /**
     * Supplies a deterministic monotonic clock without acquiring native resources.
     * @param backend resource-free operation adapter
     * @param peers owner-thread peer service
     * @param ticker monotonic nanosecond observation source
     * @throws IllegalArgumentException if the join interval is nonpositive or cannot fit nanoseconds
     */
    public WindowsAllocationHooks(Backend backend, AllocationHook.Pump peers, LongSupplier ticker)
    {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.joinTimeoutNanos = WindowsAttempts.checkedTimeout(backend.joinTimeout()).toNanos();
    }

    @Override
    public void start() throws IOException
    {
        requireOwner();
        requireCertain();
        if (startAttempted || stopped) throw new IllegalStateException("Instrumentation cannot start again");
        startAttempted = true;
        advancing = true;
        try
        {
            Member root = retainMember(backend.rootPid());
            manager = backend.manager();
            manager.start();
            observe(root);
            Hook hook = install(root);
            hook.awaitReady(peers);
            root.requireLive();
            backend.releaseRoot();
        }
        catch (IOException | RuntimeException cause) { throw failed("Instrumentation start", cause); }
        catch (Error cause) { failed("Instrumentation start", cause); throw cause; }
        finally { advancing = false; }
    }

    @Override
    public void pump(boolean advance) throws IOException
    {
        requireOwner();
        requireCertain();
        if (stopped || closed || manager == null) return;
        try
        {
            for (int copied = 0; copied < FRAME_BUDGET; ++copied)
            {
                Optional<FridaRuntime.Child> child = manager.pollChild();
                if (child.isEmpty()) break;
                enqueue(child.get());
            }
            if (!advancing) pollObservers();
            for (Hook hook : List.copyOf(installations.values())) hook.pump();
            if (advancing) return;
            for (PendingChild child : children)
            {
                if (!createdChildren.containsKey(child.event().pid()) &&
                    ticker.getAsLong() - child.firstObservedNanos() >= joinTimeoutNanos)
                    throw new IOException("Held child creation receipt timed out: " + child.event().pid());
            }
            if (!advance || children.isEmpty()) return;
            for (PendingChild pending : List.copyOf(children))
            {
                CreatedChild created = createdChildren.get(pending.event().pid());
                if (created == null) continue;
                children.remove(pending);
                createdChildren.remove(pending.event().pid());
                advancing = true;
                try { advanceChild(pending.event(), created); }
                finally { advancing = false; }
                break;
            }
        }
        catch (IOException | RuntimeException cause) { throw failed("Instrumentation delivery", cause); }
        catch (Error cause) { failed("Instrumentation delivery", cause); throw cause; }
    }

    /**
     * Copies one supported event while preserving the suspended child's missing-authority state.
     * @param event held SPAWN notification
     * @throws IOException if the event is malformed, repeated, unowned or exceeds the bound
     */
    private void enqueue(FridaRuntime.Child event) throws IOException
    {
        if (event.origin() != 2 || event.pid() <= 0 || event.parentPid() <= 0 ||
            event.path() == null || event.path().isBlank() || images.containsKey(event.pid()) ||
            !images.containsKey(event.parentPid()) || !deliveredChildren.add(event.pid()))
            throw new IOException("Unsupported or unowned held child: " + event);
        if (children.size() >= MAX_PENDING) throw new IOException("Too many unmatched held child events");
        children.addLast(new PendingChild(event, ticker.getAsLong()));
    }

    /**
     * Polls observers fairly within one total frame budget, without reentering native transitions.
     * @throws IOException if a frame or source transfer fails
     */
    private void pollObservers() throws IOException
    {
        List<Map.Entry<Long, Observer>> sources = List.copyOf(observers.entrySet());
        if (sources.isEmpty()) return;
        int empty = 0;
        for (int copied = 0; copied < FRAME_BUDGET && empty < sources.size(); ++copied)
        {
            Map.Entry<Long, Observer> entry = sources.get(observerCursor % sources.size());
            observerCursor = (observerCursor + 1) % sources.size();
            Optional<Receipt> receipt = entry.getValue().pollReceipt();
            if (receipt.isEmpty()) { ++empty; continue; }
            empty = 0;
            advancing = true;
            try { retainReceipt(entry.getValue(), receipt.get()); }
            finally { advancing = false; }
        }
    }

    /**
     * Registers observer ownership before startup and validates readiness before child gating.
     * @param parent exact retained process member
     * @return retained ready observer
     * @throws IOException if observer acquisition or readiness fails
     */
    private Observer observe(Member parent) throws IOException
    {
        if (observers.containsKey(parent.pid())) throw new IOException("Process observer already exists: " + parent.pid());
        Observer observer = backend.observer(parent, manager);
        observers.put(parent.pid(), observer);
        observer.start();
        observer.awaitReady(peers);
        return observer;
    }

    /**
     * Transfers exact source authority before acknowledging parent-local handle release.
     * @param observer producing retained observer
     * @param receipt exact retained source frame
     * @throws IOException if source identity, uniqueness, transfer or acknowledgement fails
     */
    private void retainReceipt(Observer observer, Receipt receipt) throws IOException
    {
        if (observers.get(receipt.parentPid()) != observer || images.containsKey(receipt.childPid()) ||
            createdChildren.containsKey(receipt.childPid()))
            throw new IOException("Creation receipt has an unowned parent or repeated child: " + receipt.childPid());
        if (createdChildren.size() >= MAX_PENDING) throw new IOException("Too many unmatched creation receipts");
        Member parent = images.get(receipt.parentPid());
        Member member = backend.createdMember(parent, receipt);
        members.add(member);
        createdChildren.put(receipt.childPid(), new CreatedChild(receipt, member));
        observer.release(receipt, peers);
    }

    /**
     * Installs a child's observer and allocation hook at its parked entry point, after its single authorized resume.
     * The child's own primary thread runs loader initialization: a held child has run no thread, so attaching first
     * would make the injected thread run every DLL's process attach. The MSYS2 and Cygwin runtimes record that thread
     * as the main thread, and their signal thread deadlocks once it exits.
     * @param event copied held SPAWN notification
     * @param created validated transferred creation authority
     * @throws IOException if identity, arrival, readiness, resume or release cannot be established
     */
    private void advanceChild(FridaRuntime.Child event, CreatedChild created) throws IOException
    {
        if (event.parentPid() != created.receipt().parentPid() || event.pid() != created.member().pid())
            throw new IOException("Held child differs from retained creation authority: " + event);
        Member member = created.member();
        member.requireLive();
        try (EntryPark park = backend.park(member))
        {
            if (!resumeAttempts.add(event.pid())) throw new IOException("Held child continuation was already attempted: " + event.pid());
            manager.resume(event.pid());
            awaitArrival(park, member);

            observe(member);
            Hook hook = install(member);
            hook.awaitReady(peers);
            member.requireLive();
            park.release();
        }
    }

    /**
     * Services this attempt's hooks and its peers until a resumed child waits at its parked entry point.
     * @param park the child's diverted entry point
     * @param member exact retained resumed child
     * @throws IOException if the child exits, the join interval elapses or service fails
     */
    private void awaitArrival(EntryPark park, Member member) throws IOException
    {
        long began = ticker.getAsLong();
        while (!park.arrived())
        {
            member.requireLive();
            for (Hook hook : List.copyOf(installations.values())) hook.pump();
            peers.pump();
            if (Thread.currentThread().isInterrupted())
                throw new IOException("Interrupted while awaiting a child's entry point: " + member.pid());
            long elapsed = ticker.getAsLong() - began;
            if (elapsed >= joinTimeoutNanos)
                throw new IOException("Child did not reach its entry point: " + member.pid());
            LockSupport.parkNanos(Math.min(ARRIVAL_POLL_NANOS, joinTimeoutNanos - elapsed));
        }
    }

    /**
     * Retains one validated receipt and its query identity until the matching event is consumed.
     * @param receipt transferred creation authority
     * @param member exact retained child identity
     */
    private record CreatedChild(Receipt receipt, Member member) {}

    /**
     * Retains a copied event and its bounded receipt-wait start.
     * @param event held SPAWN notification
     * @param firstObservedNanos monotonic enqueue observation
     */
    private record PendingChild(FridaRuntime.Child event, long firstObservedNanos) {}

    /**
     * Describes an exact child handle retained in the creating parent's handle table.
     * @param generation observer UUID
     * @param sequence positive observer-local receipt ordinal
     * @param parentPid creating process identifier
     * @param parentBirth raw unsigned parent creation FILETIME bits
     * @param childPid created process identifier
     * @param childBirth raw unsigned child creation FILETIME bits
     * @param sourceHandle parent-local retained process handle bits
     * @param launch immutable native creation facts, or null for legacy modeled receipts
     */
    public record Receipt(UUID generation, long sequence, long parentPid, long parentBirth, long childPid,
        long childBirth, long sourceHandle, WindowsCreationReceipt.Launch launch)
    {
        /**
         * Creates identity-only facts for callers that do not authorize a compiler registration.
         * @param generation observer UUID
         * @param sequence positive observer-local sequence
         * @param parentPid creator process identifier
         * @param parentBirth creator FILETIME bits
         * @param childPid descendant process identifier
         * @param childBirth descendant FILETIME bits
         * @param sourceHandle creator-local retained process handle bits
         */
        public Receipt(UUID generation, long sequence, long parentPid, long parentBirth, long childPid,
            long childBirth, long sourceHandle)
        {
            this(generation, sequence, parentPid, parentBirth, childPid, childBirth, sourceHandle, null);
        }

        /**
         * Validates a reusable creation-authority value before it enters owner state.
         * @param generation observer UUID
         * @param sequence positive safe JavaScript integer
         * @param parentPid unsigned nonzero DWORD
         * @param parentBirth nonzero unsigned FILETIME bits
         * @param childPid unsigned nonzero DWORD
         * @param childBirth nonzero unsigned FILETIME bits
         * @param sourceHandle valid parent-local handle bits
         * @param launch native launch facts, or null when unavailable to identity-only callers
         */
        public Receipt
        {
            Objects.requireNonNull(generation, "generation");
            if (sequence <= 0 || sequence > 9007199254740991L || parentPid <= 0 || parentPid > 0xffff_ffffL ||
                childPid <= 0 || childPid > 0xffff_ffffL || parentBirth == 0 || childBirth == 0 ||
                sourceHandle == 0 || sourceHandle == -1)
                throw new IllegalArgumentException("Creation receipt fields are outside their native ranges");
        }
    }

    /** Retains the opaque identity acquired from the exact owned Job.
     * @param pid held root or child PID
     * @return retained verified member
     * @throws IOException if membership cannot be established
     */
    private Member retainMember(long pid) throws IOException
    {
        Member member = backend.member(pid);
        members.add(member);
        return member;
    }

    /**
     * Reserves and retains a generation before any attach/load effect.
     * @param member retained opaque identity for the exact Job member
     * @return retained installation awaiting readiness
     * @throws IOException if membership, resource loading or startup fails
     */
    private Hook install(Member member) throws IOException
    {
        long generation = nextInstallation;
        nextInstallation = Math.incrementExact(nextInstallation);
        Hook hook = backend.hook(generation, member, manager);
        installations.put(generation, hook);
        images.put(member.pid(), member);
        member.requireLive();
        hook.start();
        return hook;
    }

    @Override
    public void grant(AllocationWaits.Wait wait) throws IOException
    {
        requireOwner();
        requireCertain();
        if (stopped || advancing) throw new IllegalStateException("Instrumentation cannot grant during another transition");
        Hook hook = installations.get(wait.installation());
        if (hook == null) throw failed("Native permission", new IOException("Unowned installation: " + wait));
        advancing = true;
        try { hook.grant(wait, peers); }
        catch (IOException | RuntimeException cause) { throw failed("Native permission", cause); }
        catch (Error cause) { failed("Native permission", cause); throw cause; }
        finally { advancing = false; }
    }

    @Override
    public void stop()
    {
        requireOwner();
        stopped = true;
        for (Hook hook : installations.values()) hook.stop();
    }

    @Override
    public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireOwner();
        Objects.requireNonNull(barrier, "barrier");
        if (closeFailure != null) throw new IOException("Instrumentation cleanup remains unresolved", closeFailure);
        if (closed) return;
        stop();
        // A false or unavailable observation is retryable observation, not an attempted destruction.
        if (barrier.reached() == false) throw new IOException("Instrumentation requires actual whole-Job zero");
        try
        {
            IOException cleanup = null;
            for (Hook hook : installations.values())
            {
                try { hook.close(barrier); }
                catch (IOException | RuntimeException cause)
                { cleanup = merge(cleanup, new IOException("Retained native owner cleanup failed", cause)); }
            }
            if (cleanup != null) throw cleanup;
            for (Observer observer : observers.values())
            {
                try { observer.close(barrier); }
                catch (IOException | RuntimeException cause)
                { cleanup = merge(cleanup, new IOException("Creation observer cleanup failed", cause)); }
            }
            if (cleanup != null) throw cleanup;
            if (manager != null) manager.close();
            for (Member member : members)
            {
                try { member.close(); }
                catch (IOException | RuntimeException cause)
                { cleanup = merge(cleanup, new IOException("Retained native owner cleanup failed", cause)); }
            }
            if (cleanup != null) throw cleanup;
            closed = true;
        }
        catch (IOException | RuntimeException cause)
        {
            closeFailure = new IOException("Instrumentation destruction failed", cause);
            throw closeFailure;
        }
        catch (Error cause)
        {
            closeFailure = new IOException("Instrumentation destruction failed", cause);
            throw cause;
        }
    }

    /** Aggregates independent native release faults without losing the first cause.
     * @param first earlier failure, or null
     * @param next newly observed failure
     * @return first failure with later causes suppressed
     */
    private static IOException merge(IOException first, IOException next)
    {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    /** Retains the first operational fault and permanently withdraws grants.
     * @param operation failed transition
     * @param cause original fault
     * @return retained contextual failure
     */
    private IOException failed(String operation, Throwable cause)
    {
        if (failure == null) failure = new IOException(operation + " failed", cause);
        else if (cause != failure) failure.addSuppressed(cause);
        stopped = true;
        return failure;
    }
    /** Rejects further delivery or automatic control after a fault.
     * @throws IOException if an earlier failure remains retained
     */
    private void requireCertain() throws IOException
    {
        if (failure != null) throw new IOException("Instrumentation retains an earlier failure", failure);
    }
    /** Rejects cross-thread access before touching native or queue ownership. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Windows hooks require their owner thread");
    }

    /** Narrow native operations; each returned owner must be retained before starting native effects. */
    public interface Backend
    {
        /**
         * Returns the prepared root PID.
         * @return owned root PID
         */
        long rootPid();
        /**
         * Returns the positive representable receipt join interval.
         * @return maximum monotonic wait for a matching creation receipt
         */
        Duration joinTimeout();
        /**
         * Creates resource-free observer ownership before native acquisition.
         * @param parent exact retained creating process
         * @param manager borrowed attempt manager
         * @return observer retained before startup
         * @throws IOException if the packaged observer cannot be read
         */
        Observer observer(Member parent, Manager manager) throws IOException;
        /**
         * Transfers and verifies one exact child identity before source acknowledgement.
         * @param parent retained creator identity
         * @param receipt retained native source authority
         * @return exact child query member
         * @throws IOException if transfer or identity validation fails
         */
        Member createdMember(Member parent, Receipt receipt) throws IOException;
        /** Opens a verified live member of the exact owned Job.
         * @param pid native held process PID
         * @return opaque member identity
         * @throws IOException if live membership cannot be verified
         */
        Member member(long pid) throws IOException;
        /**
         * Creates a resource-free manager owner.
         * @return distinct attempt manager
         */
        Manager manager();
        /** Creates a resource-free hook owner for a reserved generation.
         * @param generation positive reserved installation ordinal
         * @param member independently verified target
         * @param manager this attempt's manager
         * @return new hook, retained before startup
         * @throws IOException if the packaged program cannot be read
         */
        Hook hook(long generation, Member member, Manager manager) throws IOException;
        /**
         * Diverts a held child's image entry point so that, once resumed, its primary thread finishes loader
         * initialization and then waits there.
         * @param member exact retained held child
         * @return the diverted entry point, retained until released
         * @throws IOException if the child's image or memory cannot be read or written
         */
        EntryPark park(Member member) throws IOException;
        /** Releases the prepared primary thread once.
         * @throws IOException if release has an uncertain result
         */
        void releaseRoot() throws IOException;
    }
    /** Owns one creating-process observer through actual whole-Job zero. */
    public interface Observer
    {
        /** Starts the retained observer once.
         * @throws IOException if native acquisition fails
         */
        void start() throws IOException;
        /** Awaits authenticated observer readiness.
         * @param peers owner-thread peer service
         * @throws IOException if readiness or peer delivery fails
         */
        void awaitReady(AllocationHook.Pump peers) throws IOException;
        /** Copies at most one validated creation receipt.
         * @return next receipt, or empty when none is available
         * @throws IOException if a frame or state is invalid
         */
        Optional<Receipt> pollReceipt() throws IOException;
        /** Attempts parent-local release once and awaits its exact acknowledgement.
         * @param receipt transferred creation authority
         * @param peers owner-thread peer service
         * @throws IOException if post or acknowledgement is uncertain
         */
        void release(Receipt receipt, AllocationHook.Pump peers) throws IOException;
        /** Destroys retained observer resources at actual whole-Job zero.
         * @param barrier authoritative execution barrier
         * @throws IOException if the barrier or destruction fails
         */
        void close(AllocationHook.ExecutionBarrier barrier) throws IOException;
    }
    /** Owns an opaque native handle independently verified against this exact Job. */
    public interface Member extends AutoCloseable
    {
        /**
         * Returns the PID associated with the retained native handle.
         * @return verified process PID
         */
        long pid();
        /** Rechecks this retained handle's liveness and exact Job membership.
         * @throws IOException if either observation is unavailable or differs
         */
        void requireLive() throws IOException;
        @Override void close() throws IOException;
    }
    /** Owns the distinct attempt manager; child notifications are copied before caller-thread processing. */
    public interface Manager extends AutoCloseable
    {
        /** Starts the already retained native owner once.
         * @throws IOException if startup fails after any partial acquisition
         */
        void start() throws IOException;
        /** Copies the next held child notification.
         * @return copied notification, or empty
         * @throws IOException if callback delivery failed
         */
        Optional<FridaRuntime.Child> pollChild() throws IOException;
        /** Resumes only the verified held child, after instrumentation readiness.
         * @param pid verified native child PID
         * @throws IOException if resume is uncertain
         */
        void resume(long pid) throws IOException;
        @Override void close() throws IOException;
    }
    /**
     * Composes a resource-free additional image protocol with the original native manager.
     */
    @FunctionalInterface
    interface ImageHooks
    {
        /**
         * Creates ownership before its native attachment or script is started.
         * @param installation exact reserved image generation
         * @param pid exact retained live image identifier
         * @param manager original manager that alone resumes this image
         * @return resource-free hook retained before startup, or null when this image needs no additional protocol
         * @throws IOException if packaged protocol acquisition fails
         */
        Hook create(long installation, long pid, FridaRuntime.Manager manager) throws IOException;
    }

    /**
     * Joins allocation and optional token readiness while retaining every partial image owner.
     * @param allocation allocation protocol, or null in creation-only mode
     * @param additional additional image protocol, or null when absent
     */
    private record ImageProtocols(Hook allocation, Hook additional) implements Hook
    {
        @Override public void start() throws IOException
        {
            if (additional != null) additional.start();
            if (allocation != null) allocation.start();
        }
        @Override public void awaitReady(AllocationHook.Pump peers) throws IOException
        {
            if (additional != null) additional.awaitReady(peers);
            if (allocation != null) allocation.awaitReady(peers);
        }
        @Override public void pump() throws IOException
        {
            if (additional != null) additional.pump();
            if (allocation != null) allocation.pump();
        }
        @Override public void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers) throws IOException
        {
            if (allocation == null) throw new IOException("Creation-only images cannot receive allocation grants");
            allocation.grant(wait, peers);
        }
        @Override public void stop()
        {
            if (additional != null) additional.stop();
            if (allocation != null) allocation.stop();
        }
        @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
        {
            IOException failure = null;
            if (allocation != null)
            {
                try { allocation.close(barrier); }
                catch (IOException cause) { failure = cause; }
            }
            if (additional != null)
            {
                try { additional.close(barrier); }
                catch (IOException cause) { failure = merge(failure, cause); }
            }
            if (failure != null) throw failure;
        }
    }

    /** A held child's diverted image entry point, where its primary thread waits after loader initialization. */
    public interface EntryPark extends AutoCloseable
    {
        /**
         * Returns whether the primary thread has reached the entry point, restoring the entry point's original code
         * on the first such observation.
         * @return {@code true} once the primary thread waits at the entry point
         * @throws IOException if the child's memory cannot be read or restored
         */
        boolean arrived() throws IOException;
        /**
         * Lets the waiting primary thread continue into the restored entry point.
         * @throws IOException if the release cannot be written
         */
        void release() throws IOException;
        /**
         * Releases the native resources used to observe and release the entry point, without releasing a waiting
         * primary thread.
         * @throws IOException if native release fails
         */
        @Override void close() throws IOException;
    }

    /** Owns one generation of an allocation script and its attachment session. */
    public interface Hook
    {
        /** Starts the already retained native owner once.
         * @throws IOException if startup fails after any partial acquisition
         */
        void start() throws IOException;
        /** Awaits authentic readiness while servicing peers.
         * @param peers owner-thread peer pump
         * @throws IOException if readiness or peer service fails
         */
        void awaitReady(AllocationHook.Pump peers) throws IOException;
        /** Delivers a bounded round of copied protocol frames.
         * @throws IOException if a frame or its owner correlation is invalid
         */
        void pump() throws IOException;
        /** Grants an observed wait and awaits its accepted native acknowledgement.
         * @param wait exact installation and native allocation identity
         * @param peers owner-thread peer service
         * @throws IOException if post or acknowledgement is uncertain
         */
        void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers) throws IOException;
        /** Permanently withdraws grants without releasing live native resources. */
        void stop();
        /** Destroys instrumentation only after the actual Job-zero barrier.
         * @param barrier authoritative whole-Job observation
         * @throws IOException if execution is still possible or native destruction fails
         */
        void close(AllocationHook.ExecutionBarrier barrier) throws IOException;
    }

    /**
     * Owns a creating-process attachment and validates its exact retained-handle protocol.
     * Native callback transport remains in FridaRuntime; all parsing and control runs on the owner thread.
     */
    private static final class NativeObserver implements Observer
    {
        /** Owned Pouch scope for complete process-state frames, retained through native destruction. */
        private final WindowsCreationObserver.MessageScope messages = new WindowsCreationObserver.MessageScope();
        /** Immutable bounded parser borrowed from this observer's owned Pouch scope. */
        private final JsonMapper json;
        /** Unique canonical UUID expected in every frame. */
        private final UUID generation;
        /** Retained exact creator identity; this observer never closes it. */
        private final WindowsProcess.Member parent;
        /** Borrowed manager retaining the attachment's device. */
        private final FridaRuntime.Manager manager;
        /** Packaged source with its single UUID placeholder substituted. */
        private final String program;
        /** Positive representable native-control interval in nanoseconds. */
        private final long timeoutNanos;
        /** Attachment reserved before native startup. */
        private FridaRuntime.Manager.Session session;
        /** Script reserved before native startup. */
        private FridaRuntime.Manager.Session.Script script;
        /** Copied receipts that arrived while awaiting an acknowledgement. */
        private final ArrayDeque<Receipt> queued = new ArrayDeque<>();
        /** Parent-local sequences already reserved for one release attempt. */
        private final Set<Long> releaseAttempts = new HashSet<>();
        /** Creator creation time bound by readiness. */
        private long parentBirth;
        /** Exact module spelling from readiness, compared case-insensitively. */
        private String module;
        /** Exact native hook address bound by readiness. */
        private String address;
        /** Last accepted strictly increasing receipt sequence. */
        private long sequence;
        /** Outstanding one-shot release awaiting acknowledgement, or zero. */
        private long awaitingRelease;
        /** Last acknowledged release, never accepted twice. */
        private long released;
        /** Indicates that the single ready frame was accepted. */
        private boolean ready;
        /** Indicates that startup has already been attempted. */
        private boolean started;
        /** Indicates that native destruction has been attempted. */
        private boolean closed;
        /** First protocol or transport fault, permanently retained. */
        private IOException failure;
        /** First uncertain native observer destruction, retained without retry. */
        private IOException closeFailure;

        /**
         * Borrows native services without acquiring a session or script.
         * @param generation unique observer identity
         * @param parent retained exact creating process
         * @param manager borrowed started manager
         * @param program substituted observer source
         * @param timeout positive representable native-control interval
         */
        NativeObserver(UUID generation, WindowsProcess.Member parent,
            FridaRuntime.Manager manager, String program, Duration timeout)
        {
            this.json = messages.json();
            this.generation = Objects.requireNonNull(generation, "generation");
            this.parent = Objects.requireNonNull(parent, "parent");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.program = Objects.requireNonNull(program, "program");
            this.timeoutNanos = WindowsAttempts.checkedTimeout(timeout).toNanos();
        }

        @Override public void start() throws IOException
        {
            requireCertain();
            if (started) throw new IllegalStateException("Creation observer cannot start twice");
            started = true;
            try
            {
                parent.requireLive();
                session = manager.newSession(parent.pid());
                session.start();
                script = session.newScript(program);
                script.start();
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        @Override public void awaitReady(AllocationHook.Pump peers) throws IOException
        {
            requireCertain();
            long began = System.nanoTime();
            try
            {
                while (!ready)
                {
                    if (System.nanoTime() - began >= timeoutNanos) throw new IOException("Creation observer readiness timed out");
                    receive();
                    peers.pump();
                }
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        @Override public Optional<Receipt> pollReceipt() throws IOException
        {
            requireCertain();
            try
            {
                if (queued.isEmpty()) receive();
                return Optional.ofNullable(queued.pollFirst());
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        @Override public void release(Receipt receipt, AllocationHook.Pump peers) throws IOException
        {
            requireCertain();
            try
            {
                if (!generation.equals(receipt.generation()) || awaitingRelease != 0 ||
                    !releaseAttempts.add(receipt.sequence()))
                    throw new IOException("Creation release does not identify one unused observer receipt");
                awaitingRelease = receipt.sequence();
                ObjectNode request = json.createObjectNode();
                request.put("type", "release");
                ObjectNode payload = request.putObject("payload");
                payload.put("generation", generation.toString());
                payload.put("sequence", receipt.sequence());
                script.post(request);
                long began = System.nanoTime();
                while (released != receipt.sequence())
                {
                    if (System.nanoTime() - began >= timeoutNanos) throw new IOException("Creation source release acknowledgement timed out");
                    receive();
                    peers.pump();
                }
                awaitingRelease = 0;
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        /**
         * Parses at most one copied frame and advances only this observer's protocol state.
         * @throws IOException if frame syntax, shape, correlation or ordering is invalid
         */
        private void receive() throws IOException
        {
            if (script == null) throw new IOException("Creation observer script has not started");
            Optional<String> message = script.pollMessage();
            if (message.isEmpty()) return;
            if (message.get().length() > WindowsCreationObserver.MessageScope.MAX_CHARACTERS) throw new IOException("Creation observer message exceeds its bound");
            JsonNode envelope;
            try { envelope = json.readTree(message.get()); }
            catch (tools.jackson.core.JacksonException cause) { throw new IOException("Creation observer JSON is invalid", cause); }
            if (envelope != null && envelope.path("type").isString() && envelope.path("type").asString().equals("error"))
            {
                throw new IOException("Creation observer script error: " + envelope.path("description").asString() +
                    "; stack=" + envelope.path("stack").asString());
            }
            fields(envelope, "type", "payload");
            if (!text(envelope, "type").equals("send")) throw new IOException("Creation observer requires a send envelope");
            JsonNode body = envelope.get("payload");
            if (body == null || !body.isObject()) throw new IOException("Creation observer payload must be an object");
            if (!text(body, "generation").equals(generation.toString())) throw new IOException("Creation observer generation differs");
            switch (text(body, "kind"))
            {
                case "ready" ->
                {
                    fields(body, "kind", "generation", "parentPid", "parentBirth", "module", "address", "boundary", "pointerSize", "state");
                    if (ready || sequence != 0) throw new IOException("Creation observer readiness is repeated or late");
                    if (integer(body, "parentPid", 0xffff_ffffL) != parent.pid() ||
                        integer(body, "pointerSize", 8) != 8 ||
                        !text(body, "boundary").equals("private-pinned-CreateProcessInternalW"))
                        throw new IOException("Creation observer readiness has a different native boundary");
                    parentBirth = unsigned(body, "parentBirth");
                    parent.requireBirth(parentBirth);
                    JsonNode nativeState = body.get("state");
                    fields(nativeState, "commandLine", "directory", "environment");
                    JsonNode entries = nativeState.get("environment");
                    if (!entries.isArray()) throw new IOException("Native child environment must be an array");
                    List<String> environment = new ArrayList<>();
                    for (JsonNode entry : entries)
                    {
                        if (!entry.isString()) throw new IOException("Native child environment entry must be text");
                        environment.add(entry.stringValue());
                    }
                    parent.sealNativeState(parentBirth, new WindowsCreationReceipt.NativeState(
                        text(nativeState, "commandLine"), text(nativeState, "directory"), environment));
                    session.enableChildGating();
                    module = text(body, "module");
                    if (!Set.of("kernelbase.dll", "kernel32.dll").contains(module.toLowerCase(java.util.Locale.ROOT)))
                        throw new IOException("Creation observer module is unsupported");
                    address = text(body, "address");
                    pointer(body, "address");
                    ready = true;
                }
                case "retained" ->
                {
                    fields(body, "kind", "generation", "sequence", "parentPid", "parentBirth", "childPid", "childBirth",
                        "sourceHandle", "originalHandle", "originalThread", "originalFlags", "module", "address",
                        "requiredRights", "originalHandlesUntouched", "launch");
                    long ordinal = integer(body, "sequence", 9007199254740991L);
                    if (!ready || ordinal <= sequence || integer(body, "parentPid", 0xffff_ffffL) != parent.pid() ||
                        unsigned(body, "parentBirth") != parentBirth || !module.equalsIgnoreCase(text(body, "module")) ||
                        !address.equals(text(body, "address")) || !text(body, "requiredRights").equals("0x101040") ||
                        !body.path("originalHandlesUntouched").isBoolean() || !body.path("originalHandlesUntouched").booleanValue())
                        throw new IOException("Creation receipt has invalid order, creator or native authority");
                    pointer(body, "originalHandle");
                    pointer(body, "originalThread");
                    nonnegativeInteger(body, "originalFlags", 0xffff_ffffL);
                    Receipt receipt = new Receipt(generation, ordinal, parent.pid(), parentBirth,
                        integer(body, "childPid", 0xffff_ffffL), unsigned(body, "childBirth"), pointer(body, "sourceHandle"), launch(body.get("launch")));
                    if (queued.size() >= MAX_PENDING) throw new IOException("Too many queued creation receipts");
                    sequence = ordinal;
                    queued.addLast(receipt);
                }
                case "released", "rejected-release" ->
                {
                    fields(body, "kind", "generation", "sequence");
                    long ordinal = integer(body, "sequence", 9007199254740991L);
                    if (awaitingRelease == 0 || ordinal != awaitingRelease || released == ordinal)
                        throw new IOException("Creation release acknowledgement has no matching attempt");
                    if (text(body, "kind").equals("rejected-release")) throw new IOException("Native creator rejected source release");
                    released = ordinal;
                }
                case "failure" ->
                {
                    fields(body, "kind", "generation", "message");
                    String diagnostic = text(body, "message");
                    if (diagnostic.isBlank()) throw new IOException("Creation failure message is blank");
                    throw new IOException("Native creation observer failed: " + diagnostic);
                }
                default -> throw new IOException("Unknown creation observer frame kind");
            }
        }

        /**
         * Reads native launch facts without replacing them with a connecting wrapper's claims.
         * @param node copied native launch object
         * @return immutable launch facts
         * @throws IOException if a required native fact is absent or malformed
         */
        private static WindowsCreationReceipt.Launch launch(JsonNode node) throws IOException
        {
            fields(node, "application", "commandLine", "directory", "environment", "inheritedEnvironment",
                "inheritedDirectory", "standardHandles", "input", "output", "error");
            JsonNode entries = node.get("environment");
            if (!entries.isArray()) throw new IOException("Native creation environment must be an array");
            List<String> environment = new ArrayList<>();
            for (JsonNode entry : entries)
            {
                if (!entry.isString()) throw new IOException("Native environment entry must be text");
                environment.add(entry.stringValue());
            }
            return new WindowsCreationReceipt.Launch(nullableText(node, "application"), nullableText(node, "commandLine"),
                text(node, "directory"), environment, condition(node, "inheritedEnvironment"),
                condition(node, "inheritedDirectory"), condition(node, "standardHandles"),
                handleBits(node, "input"), handleBits(node, "output"), handleBits(node, "error"));
        }

        /**
         * Reads a native argument whose omitted form remains distinct from an empty string.
         * @param node launch object
         * @param name native argument name
         * @return exact text, or null for the omitted native argument
         * @throws IOException if the value has another type
         */
        private static String nullableText(JsonNode node, String name) throws IOException
        {
            if (node.get(name).isNull()) return null;
            return text(node, name);
        }

        /**
         * Reads one noncoerced native launch condition.
         * @param node launch object
         * @param name condition name
         * @return native condition
         * @throws IOException if the value is not boolean
         */
        private static boolean condition(JsonNode node, String name) throws IOException
        {
            if (!node.get(name).isBoolean()) throw new IOException("Native launch condition must be boolean: " + name);
            return node.get(name).booleanValue();
        }

        /**
         * Reads raw native standard-handle bits, including NULL and INVALID_HANDLE_VALUE.
         * @param node launch object
         * @param name standard-handle name
         * @return raw unsigned handle bits
         * @throws IOException if the native pointer text is invalid
         */
        private static long handleBits(JsonNode node, String name) throws IOException
        {
            String value = text(node, name);
            if (!value.startsWith("0x")) throw new IOException("Native handle requires hexadecimal text: " + name);
            try { return Long.parseUnsignedLong(value.substring(2), 16); }
            catch (NumberFormatException cause) { throw new IOException("Invalid native handle: " + name, cause); }
        }

        /**
         * Requires an exact object field set.
         * @param node received object
         * @param names permitted required fields
         * @throws IOException if shape or keys differ
         */
        private static void fields(JsonNode node, String... names) throws IOException
        {
            if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(Set.of(names)))
                throw new IOException("Creation observer frame has missing or unexpected fields");
        }

        /**
         * Reads a textual JSON scalar without coercion.
         * @param node received object
         * @param name required member
         * @return exact string value
         * @throws IOException if absent or not textual
         */
        private static String text(JsonNode node, String name) throws IOException
        {
            JsonNode value = node.get(name);
            if (value == null || !value.isString()) throw new IOException("Creation observer field must be text: " + name);
            return value.stringValue();
        }

        /**
         * Reads a positive bounded exact JSON integer.
         * @param node received object
         * @param name required member
         * @param maximum inclusive upper bound
         * @return exact positive value
         * @throws IOException if type or range differs
         */
        private static long integer(JsonNode node, String name, long maximum) throws IOException
        {
            long value = nonnegativeInteger(node, name, maximum);
            if (value == 0) throw new IOException("Creation observer field must be positive: " + name);
            return value;
        }

        /**
         * Reads an exact nonnegative bounded JSON integer.
         * @param node received object
         * @param name required member
         * @param maximum inclusive upper bound
         * @return exact integer value
         * @throws IOException if type or range differs
         */
        private static long nonnegativeInteger(JsonNode node, String name, long maximum) throws IOException
        {
            JsonNode value = node.get(name);
            if (value == null || !value.isIntegralNumber() || value.bigIntegerValue().signum() < 0 ||
                value.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(maximum)) > 0)
                throw new IOException("Creation observer integer is outside its range: " + name);
            return value.longValue();
        }

        /**
         * Reads canonical nonzero unsigned decimal FILETIME bits.
         * @param node received object
         * @param name required member
         * @return raw unsigned bits
         * @throws IOException if syntax or range differs
         */
        private static long unsigned(JsonNode node, String name) throws IOException
        {
            String value = text(node, name);
            try
            {
                long bits = Long.parseUnsignedLong(value);
                if (bits == 0 || !Long.toUnsignedString(bits).equals(value)) throw new NumberFormatException("Noncanonical FILETIME");
                return bits;
            }
            catch (NumberFormatException cause) { throw new IOException("Invalid creation FILETIME: " + name, cause); }
        }

        /**
         * Reads nonzero native pointer bits, excluding INVALID_HANDLE_VALUE.
         * @param node received object
         * @param name required member
         * @return raw unsigned pointer bits
         * @throws IOException if syntax or value differs
         */
        private static long pointer(JsonNode node, String name) throws IOException
        {
            String value = text(node, name);
            if (!value.matches("0x[0-9a-fA-F]{1,16}")) throw new IOException("Invalid native pointer syntax: " + name);
            try
            {
                long bits = Long.parseUnsignedLong(value.substring(2), 16);
                if (bits == 0 || bits == -1) throw new NumberFormatException("Invalid native pointer value");
                return bits;
            }
            catch (NumberFormatException cause) { throw new IOException("Invalid native pointer: " + name, cause); }
        }

        /**
         * Retains one fault and prevents subsequent effects.
         * @param cause original protocol or transport failure
         * @return sticky contextual failure
         */
        private IOException failed(Throwable cause)
        {
            if (failure == null) failure = new IOException("Creation observer authority failed", cause);
            return failure;
        }

        /**
         * Rejects operation after failure or destruction.
         * @throws IOException if authority has failed
         */
        private void requireCertain() throws IOException
        {
            if (failure != null) throw new IOException("Creation observer retains a failure", failure);
            if (closed) throw new IOException("Creation observer is closed");
        }

        @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
        {
            if (!barrier.reached()) throw new IOException("Creation observer requires actual whole-Job zero");
            if (closeFailure != null) throw new IOException("Creation observer destruction remains unresolved", closeFailure);
            if (closed) return;
            closed = true;
            try
            {
                if (script != null) script.close();
                if (session != null) session.close();
            }
            catch (IOException | RuntimeException cause)
            {
                closeFailure = new IOException("Creation observer destruction failed", cause);
                throw closeFailure;
            }
            catch (Error cause)
            {
                closeFailure = new IOException("Creation observer destruction failed", cause);
                throw cause;
            }
            finally { messages.close(); }
        }
    }

    /** Loads only the accepted Windows program and delegates to existing authoritative native owners. */
    private static final class NativeBackend implements Backend
    {
        /** Unique correlation UUID for this queue attempt. */
        private final UUID identity;
        /** Borrowed started Frida runtime; this adapter never closes it. */
        private final FridaRuntime runtime;
        /** Borrowed immutable shared values. */
        private final ToolingScope scope;
        /** Borrowed authoritative Windows Job; the attempt retains it through instrumentation destruction. */
        private final WindowsProcess process;
        /** Attempt-wide allocation batches shared by root and descendant installations. */
        private final AllocationWaits waits;
        /** Positive bounded native control interval. */
        private final Duration timeout;
        /** Accepted Windows resource text, loaded only after this owner is retained. */
        private String program;
        /** Packaged observer source loaded once before individual UUID substitution. */
        private String observerProgram;
        /** Indicates whether this Job owns queued compiler allocation controls. */
        private final boolean allocations;
        /** Optional independently owned protocol required before each image can resume. */
        private final ImageHooks imageHooks;

        /**
         * Borrows native services without acquiring any manager, member or session.
         * @param scope borrowed shared values; the caller retains ownership through all operations
         * @param identity unique attempt correlation UUID
         * @param runtime borrowed started Frida runtime
         * @param process authoritative owned Job, borrowed by instrumentation
         * @param waits shared root and descendant allocation batches
         * @param timeout positive representable control interval
         * @param allocations indicates whether compiler allocation controls are installed
         * @param imageHooks additional per-image protocol, or null when absent
         */
        NativeBackend(ToolingScope scope, UUID identity, FridaRuntime runtime, WindowsProcess process, AllocationWaits waits, Duration timeout, boolean allocations, ImageHooks imageHooks)
        {
            this.allocations = allocations;
            this.imageHooks = imageHooks;
            this.identity = Objects.requireNonNull(identity, "identity");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.process = Objects.requireNonNull(process, "process");
            this.waits = Objects.requireNonNull(waits, "waits");
            this.timeout = timeout;
        }
        @Override public long rootPid() { return process.pid(); }
        @Override public Member member(long pid) throws IOException
        {
            NativeMember bridge = new NativeMember();
            bridge.process = process;
            bridge.member = process.openMember(pid);
            return bridge;
        }
        @Override public Manager manager()
        {
            NativeManager bridge = new NativeManager();
            bridge.runtime = runtime;
            bridge.manager = runtime.newManager();
            return bridge;
        }
        @Override public Hook hook(long generation, Member member, Manager manager) throws IOException
        {
            Hook allocation = null;
            if (allocations)
            {
                if (program == null)
                {
                    try (java.io.InputStream input = WindowsAllocationHooks.class.getResourceAsStream("windows-allocation-hook.js"))
                    {
                        if (input == null) throw new IOException("Packaged Windows allocation program is missing");
                        program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                allocation = new NativeHook(new AllocationHook(scope, identity, generation, member.pid(),
                    ((NativeManager) manager).manager, waits, program, timeout));
            }
            Hook additional = null;
            if (imageHooks != null)
                additional = imageHooks.create(generation, member.pid(), ((NativeManager) manager).manager);
            return new ImageProtocols(allocation, additional);
        }
        @Override public Duration joinTimeout() { return timeout; }
        @Override public Observer observer(Member parent, Manager manager) throws IOException
        {
            if (!(parent instanceof NativeMember member) || !(manager instanceof NativeManager nativeManager) ||
                member.process != process || nativeManager.runtime != runtime)
                throw new IOException("Creation observer requires this backend's retained native owners");
            if (observerProgram == null)
            {
                try (java.io.InputStream input = WindowsAllocationHooks.class.getResourceAsStream("windows-creation-observer.js"))
                {
                    if (input == null) throw new IOException("Packaged Windows creation observer is missing");
                    observerProgram = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                String placeholder = "__OBSERVER_GENERATION__";
                if (observerProgram.indexOf(placeholder) < 0 ||
                    observerProgram.indexOf(placeholder) != observerProgram.lastIndexOf(placeholder))
                    throw new IOException("Creation observer requires exactly one generation placeholder");
            }
            UUID generation = UUID.randomUUID();
            return new NativeObserver(generation, member.member, nativeManager.manager,
                observerProgram.replace("__OBSERVER_GENERATION__", generation.toString()), timeout);
        }
        @Override public Member createdMember(Member parent, Receipt receipt) throws IOException
        {
            if (!(parent instanceof NativeMember creator) || creator.process != process)
                throw new IOException("Creation transfer requires this backend's retained creator");
            NativeMember bridge = new NativeMember();
            bridge.process = process;
            bridge.source = process.retainCreatedSource(creator.member, receipt.parentBirth(), receipt.childPid(),
                receipt.childBirth(), receipt.sourceHandle());
            process.retainReceipt(bridge.source, receipt);
            bridge.member = process.openMember(receipt.childPid());
            bridge.member.requireBirth(receipt.childBirth());
            return bridge;
        }
        @Override public void releaseRoot() throws IOException { process.releaseRoot(); }
        @Override public EntryPark park(Member member) throws IOException
        {
            if (!(member instanceof NativeMember child) || child.process != process)
                throw new IOException("Entry park requires this backend's retained child");
            WindowsEntryPark park = child.member.parkAtEntry();
            return new EntryPark()
            {
                @Override public boolean arrived() throws IOException { return park.arrived(); }
                @Override public void release() throws IOException { park.release(); }
                @Override public void close() throws IOException { park.close(); }
            };
        }
    }
    /** Adapts the authoritative opaque identity without exposing its native handle. */
    private static final class NativeMember implements Member
    {
        /** Opaque native identity handle retained by the authoritative Job owner. */
        private WindowsProcess.Member member;
        /** Exact authoritative process owner used to reject cross-backend bridge reuse. */
        private WindowsProcess process;
        /** Borrowed created-source authority retained by the process owner until Job zero. */
        private WindowsProcess.CreatedSource source;
        /** Allocates the bridge before opening its native identity handle. */
        private NativeMember() {}
        @Override public long pid() { return member.pid(); }
        @Override public void requireLive() throws IOException { member.requireLive(); }
        @Override public void close() throws IOException { member.close(); }
    }
    /** Adapts the existing manager without changing its session ownership or native bindings. */
    private static final class NativeManager implements Manager
    {
        /** Distinct owned manager retained before native startup. */
        private FridaRuntime.Manager manager;
        /** Exact borrowed runtime used to reject cross-backend manager reuse. */
        private FridaRuntime runtime;
        /** Allocates the bridge before obtaining its resource-free manager owner. */
        private NativeManager() {}
        @Override public void start() throws IOException { manager.start(); }
        @Override public Optional<FridaRuntime.Child> pollChild() throws IOException { return manager.pollChild(); }
        @Override public void resume(long pid) throws IOException { manager.resume(pid); }
        @Override public void close() throws IOException { manager.close(); }
    }
    /** Delegates exact protocol validation and acknowledgements to the shared hook owner.
     * @param hook retained hook and session owner
     */
    private record NativeHook(AllocationHook hook) implements Hook
    {
        @Override public void start() throws IOException { hook.start(); }
        @Override public void awaitReady(AllocationHook.Pump peers) throws IOException { hook.awaitReady(peers); }
        @Override public void pump() throws IOException { hook.pump(); }
        @Override public void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers) throws IOException
        { hook.grant(wait, peers); }
        @Override public void stop() { hook.stop(); }
        @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException { hook.close(barrier); }
    }
}
