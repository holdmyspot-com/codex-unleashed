package com.holdmyspot.codexunleashed.scheduler;

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

/** Owns one manager and every root/descendant installation for an authoritative Windows Job.
 * Held SPAWN events require opaque live membership, not a PID crawl. Unexpected origins stay held.
 * Native callbacks only copy messages; this owner and peer service run on the constructing thread.
 */
final class WindowsAllocationHooks implements WindowsAttempt.Hooks
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
    private final ArrayDeque<FridaRuntime.Child> children = new ArrayDeque<>();
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
     * @param identity unique attempt correlation UUID
     * @param runtime borrowed started Frida runtime
     * @param process authoritative owned Job, borrowed by instrumentation
     * @param waits shared root and descendant allocation batches
     * @param timeout positive representable control interval
     * @param peers owner-thread peer pump without queue reentry
     */
    WindowsAllocationHooks(UUID identity, FridaRuntime runtime, WindowsProcess process, AllocationWaits waits,
        Duration timeout, AllocationHook.Pump peers)
    {
        this(new NativeBackend(identity, runtime, process, waits, WindowsAttempts.checkedTimeout(timeout)), peers);
    }

    /**
     * Supplies the same lifecycle through controlled native-operation ports for model tests.
     * @param backend resource-free native-operation adapter
     * @param peers owner-thread peer pump without queue reentry
     */
    WindowsAllocationHooks(Backend backend, AllocationHook.Pump peers)
    {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.peers = Objects.requireNonNull(peers, "peers");
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
            // Bound callback draining even if new children keep arriving.
            for (int copied = 0; copied < 32; ++copied)
            {
                var child = manager.pollChild();
                if (child.isEmpty()) break;
                children.addLast(child.get());
            }
            for (Hook hook : List.copyOf(installations.values())) hook.pump();
            if (advance == false || advancing || children.isEmpty()) return;
            advancing = true;
            try { advanceChild(children.removeFirst()); }
            finally { advancing = false; }
        }
        catch (IOException | RuntimeException cause) { throw failed("Instrumentation delivery", cause); }
        catch (Error cause) { failed("Instrumentation delivery", cause); throw cause; }
    }

    /**
     * Uses only previously retained parent context; a terminal root may still own a held living child.
     * @param event copied held-child notification
     * @throws IOException if origin, identity, attachment, readiness or resume cannot be established
     */
    private void advanceChild(FridaRuntime.Child event) throws IOException
    {
        if (event.origin() != 2 || event.pid() <= 0 || event.parentPid() <= 0 ||
            event.path() == null || event.path().isBlank() || images.containsKey(event.pid()) ||
            images.containsKey(event.parentPid()) == false)
            throw new IOException("Unsupported or unowned held child: " + event);
        Member member = retainMember(event.pid());
        member.requireLive();
        Hook hook = install(member);
        hook.awaitReady(peers);
        member.requireLive();
        member.requireLive();
        manager.resume(event.pid());
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
    interface Backend
    {
        /**
         * Returns the prepared root PID.
         * @return owned root PID
         */
        long rootPid();
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
        /** Releases the prepared primary thread once.
         * @throws IOException if release has an uncertain result
         */
        void releaseRoot() throws IOException;
    }
    /** Owns an opaque native handle independently verified against this exact Job. */
    interface Member extends AutoCloseable
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
    interface Manager extends AutoCloseable
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
    /** Owns one generation of an allocation script and its attachment session. */
    interface Hook
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

    /** Loads only the accepted Windows program and delegates to existing authoritative native owners. */
    private static final class NativeBackend implements Backend
    {
        /** Unique correlation UUID for this queue attempt. */
        private final UUID identity;
        /** Borrowed started Frida runtime; this adapter never closes it. */
        private final FridaRuntime runtime;
        /** Borrowed authoritative Windows Job; the attempt retains it through instrumentation destruction. */
        private final WindowsProcess process;
        /** Attempt-wide allocation batches shared by root and descendant installations. */
        private final AllocationWaits waits;
        /** Positive bounded native control interval. */
        private final Duration timeout;
        /** Accepted Windows resource text, loaded only after this owner is retained. */
        private String program;

        /**
         * Borrows native services without acquiring any manager, member or session.
         * @param identity unique attempt correlation UUID
         * @param runtime borrowed started Frida runtime
         * @param process authoritative owned Job, borrowed by instrumentation
         * @param waits shared root and descendant allocation batches
         * @param timeout positive representable control interval
         */
        NativeBackend(UUID identity, FridaRuntime runtime, WindowsProcess process, AllocationWaits waits, Duration timeout)
        {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.process = Objects.requireNonNull(process, "process");
            this.waits = Objects.requireNonNull(waits, "waits");
            this.timeout = timeout;
        }
        @Override public long rootPid() { return process.pid(); }
        @Override public Member member(long pid) throws IOException
        {
            NativeMember bridge = new NativeMember();
            bridge.member = process.openMember(pid);
            return bridge;
        }
        @Override public Manager manager()
        {
            NativeManager bridge = new NativeManager();
            bridge.manager = runtime.newManager();
            return bridge;
        }
        @Override public Hook hook(long generation, Member member, Manager manager) throws IOException
        {
            if (program == null)
            {
                try (var input = WindowsAllocationHooks.class.getResourceAsStream("windows-allocation-hook.js"))
                {
                    if (input == null) throw new IOException("Packaged Windows allocation program is missing");
                    program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new NativeHook(new AllocationHook(identity, generation, member.pid(),
                ((NativeManager) manager).manager, waits, program, timeout));
        }
        @Override public void releaseRoot() throws IOException { process.releaseRoot(); }
    }
    /** Adapts the authoritative opaque identity without exposing its native handle. */
    private static final class NativeMember implements Member
    {
        /** Opaque native identity handle retained by the authoritative Job owner. */
        private WindowsProcess.Member member;
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
