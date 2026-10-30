package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Owns one prepared Windows Job, instrumentation and measurement registration.
 * Only actual whole-Job collection creates an outcome. Faults remain visible after safe cleanup.
 */
final class WindowsAttempt implements JobQueue.Attempt<WindowsProcess.Result>
{
    /** Only thread permitted to control these owners. */
    private final Thread owner = Thread.currentThread();
    /** Borrowed factory supplying peer service and retaining this owner until cleanup. */
    private final WindowsAttempts attempts;
    /** Stable action name, unchanged across retries. */
    private final String name;
    /** Reserved queue attempt ordinal, distinct from native wait identities. */
    private final long sequence;
    /** Retained acquisition plan, including any checked preparation-failure capsule. */
    private final Resources resources;
    /** Validated positive monotonic cleanup deadline in nanoseconds. */
    private final long timeoutNanos;
    /** Attempt-wide allocation batches shared by root and descendant installations. */
    private final AllocationWaits waits = new AllocationWaits();
    /** Authoritative Windows Job owner retained before instrumentation acquisition. */
    private Process process;
    /** Measurement registration retained through STOPPING and actual whole-Job zero. */
    private Close registration;
    /** Retained instrumentation owner, including partial start. */
    private Hooks hooks;
    /** One-shot reservation made before acquiring or releasing native resources. */
    private boolean startAttempted;
    /** Whether start or resume must defer publication of its next allocation batch. */
    private boolean controlling;
    /** Permanent withdrawal of automatic allocation grants. */
    private boolean stopping;
    /** Cancellation reservation made before its potentially ambiguous native effect. */
    private boolean terminationAttempted;
    /** Whether all instrumentation owners passed their whole-Job barrier and closed. */
    private boolean instrumentationClosed;
    /** Whether every owned resource was released successfully. */
    private boolean closed;
    /** First operational fault; successful cleanup never clears it. */
    private IOException failure;
    /** First unresolved cleanup fault, retained independently of operational failure. */
    private IOException closeFailure;
    /** First uncertain cancellation fault, preventing a successful repeated acknowledgement. */
    private IOException terminationFailure;
    /** Authentic whole-Job result retained before fallible instrumentation teardown. */
    private WindowsProcess.Result collected;
    /** Accepted native queue outcome; cleanup never invents one. */
    private JobQueue.Outcome<WindowsProcess.Result> outcome;

    /**
     * Retains resource-free configuration before any native acquisition.
     * @param attempts borrowed factory retaining the owner and servicing peers
     * @param name stable action name
     * @param sequence reserved queue attempt ordinal
     * @param resources resource-free acquisition plan retaining any failed preparation capsule
     * @param timeout positive representable control interval
     */
    WindowsAttempt(WindowsAttempts attempts, String name, long sequence, Resources resources, Duration timeout)
    {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        new WindowsAttempts.Blocked(name, sequence, 0);
        this.name = name;
        this.sequence = sequence;
        this.resources = Objects.requireNonNull(resources, "resources");
        timeoutNanos = WindowsAttempts.checkedTimeout(timeout).toNanos();
    }

    /**
     * Returns the stable action name.
     * @return original action name
     */
    String name() { requireOwner(); return name; }
    /**
     * Returns this attempt ordinal.
     * @return reserved queue ordinal
     */
    long sequence() { requireOwner(); return sequence; }

    /** Prepares and registers the Job, then instruments and verifies the root before its single release.
     * @throws IOException if preparation, registration, readiness or release fails; partial owners remain retained
     */
    @Override
    public void start() throws IOException
    {
        requireOwner();
        requireCertain();
        if (startAttempted || stopping || closed) throw new IllegalStateException("Attempt cannot start again");
        startAttempted = true;
        controlling = true;
        try
        {
            process = resources.prepare();
            registration = resources.register(process);
            hooks = resources.hooks(process, waits, attempts::pumpAll);
            hooks.start();
        }
        catch (IOException | RuntimeException cause) { throw failed("Start", cause); }
        catch (Error cause) { failed("Start", cause); throw cause; }
        finally { controlling = false; }
    }

    /** Acknowledges only the native waits selected by this published batch.
     * New waits observed during acknowledgement remain deferred until this control returns.
     * @param waitSequence published queue batch ordinal
     * @throws IOException if an exact permission post or acknowledgement is uncertain
     */
    @Override
    public void resume(long waitSequence) throws IOException
    {
        requireOwner();
        requireCertain();
        if (hooks == null || stopping || controlling || closed)
            throw new IllegalStateException("Attempt cannot resume");
        controlling = true;
        try { waits.resume(waitSequence, hooks::grant); }
        catch (IOException | RuntimeException cause) { throw failed("Allocation continuation", cause); }
        catch (Error cause) { failed("Allocation continuation", cause); throw cause; }
        finally { controlling = false; }
    }

    /** Withdraws grants before requesting whole-Job cancellation once.
     * Repeating an acknowledged request has no effect; repeating an uncertain request reports its retained fault.
     * @throws IOException if cancellation is uncertain; a successful request never supplies a completion outcome
     */
    @Override
    public void terminate() throws IOException
    {
        requireOwner();
        stopGrants();
        if (process == null) return;
        if (terminationAttempted)
        {
            if (terminationFailure != null) throw new IOException("Cancellation remains uncertain", terminationFailure);
            return;
        }
        terminationAttempted = true;
        try { process.terminate(); }
        catch (IOException | RuntimeException cause)
        {
            terminationFailure = failed("Cancellation request", cause);
            throw terminationFailure;
        }
        catch (Error cause) { terminationFailure = failed("Cancellation request", cause); throw cause; }
    }

    /**
     * Services frames without publishing a batch from inside start/resume.
     * @param advance whether this round may advance held-child installation
     * @throws IOException if delivery or child processing fails
     */
    void pumpEvents(boolean advance) throws IOException
    {
        requireOwner();
        requireCertain();
        if (hooks == null || stopping || instrumentationClosed || closed) return;
        try { hooks.pump(advance); }
        catch (IOException | RuntimeException cause) { throw failed("Native event delivery", cause); }
        catch (Error cause) { failed("Native event delivery", cause); throw cause; }
    }

    /** Publishes a fresh batch only outside controls.
     * @return next batch, or empty while controlling, stopping or without pending waits
     * @throws IOException if an earlier continuation remains uncertain
     */
    OptionalLong pollAllocationWait() throws IOException
    {
        requireOwner();
        if (controlling || stopping || hooks == null || closed) return OptionalLong.empty();
        requireCertain();
        return waits.poll();
    }

    /** Observes actual whole-Job completion before any hook destruction.
     * @return the authentic unsigned root status and abortion flag, or empty while execution remains possible
     * @throws IOException if observation, teardown or any earlier operational/cleanup fault prevents acceptance
     */
    @Override
    public Optional<JobQueue.Outcome<WindowsProcess.Result>> poll() throws IOException
    {
        requireOwner();
        requireCertain();
        if (closeFailure != null) throw new IOException("Attempt retains an earlier cleanup fault", closeFailure);
        if (outcome != null) return Optional.of(outcome);
        if (closed || process == null) return Optional.empty();
        try
        {
            // Root exit alone remains empty: only the authoritative owner can supply a whole-Job result.
            var result = process.poll();
            if (result.isEmpty())
            {
                pumpEvents(true);
                return Optional.empty();
            }
            collected = result.get();
            closeInstrumentation();
            outcome = new JobQueue.Outcome<>(collected, collected.aborted());
            return Optional.of(outcome);
        }
        catch (IOException | RuntimeException cause) { throw failed("Whole-Job observation", cause); }
        catch (Error cause) { failed("Whole-Job observation", cause); throw cause; }
    }

    /** Withdraws grants before cancellation without resuming parked native allocations. */
    private void stopGrants()
    {
        stopping = true;
        waits.stop();
        if (hooks != null) hooks.stop();
    }

    /** Tears down hooks only after rechecking actual whole-Job zero.
     * @throws IOException if execution remains possible or hook destruction is unresolved
     */
    private void closeInstrumentation() throws IOException
    {
        if (instrumentationClosed) return;
        stopGrants();
        if (process.poll().isEmpty()) throw new IOException("Instrumentation requires actual whole-Job zero");
        if (hooks != null) hooks.close(() -> process.poll().isPresent());
        instrumentationClosed = true;
    }

    /** Reconciles partial acquisition or cancels and collects the Job before releasing dependent owners.
     * Later calls may observe newly terminal work and finish safe cleanup; the first cleanup fault remains visible.
     * Native owners independently prohibit retries of uncertain destruction. Cleanup never publishes an outcome.
     * @throws IOException if ownership remains unresolved or an earlier operation/cleanup fault was retained
     */
    @Override
    public void close() throws IOException
    {
        requireOwner();
        if (closed)
        {
            if (closeFailure != null) throw closeFailure;
            requireCertain();
            return;
        }
        try
        {
            stopGrants();
            // This retained acquisition plan owns any checked preparation-failure capsule even if no process returned.
            resources.closePreparationFailure();
            if (process != null)
            {
                if (process.poll().isEmpty())
                {
                    terminate();
                    long began = System.nanoTime();
                    while (process.poll().isEmpty())
                    {
                        if (System.nanoTime() - began >= timeoutNanos)
                            throw new IOException("Cleanup awaits actual whole-Job zero");
                        pause();
                    }
                }
                closeInstrumentation();
                if (registration != null) { registration.close(); registration = null; }
                process.close();
            }
            attempts.forget(this);
            closed = true;
        }
        catch (IOException | RuntimeException cause)
        {
            throw rememberCleanup(cause);
        }
        catch (Error cause)
        {
            rememberCleanup(cause);
            throw cause;
        }
        if (closeFailure != null) throw closeFailure;
        requireCertain();
    }

    /** Retains the first cleanup fault while allowing safe later barrier observations.
     * Hook and process owners independently forbid repeated uncertain native destruction.
     * @param cause current cleanup fault
     * @return first retained cleanup fault, with later independent causes suppressed
     */
    private IOException rememberCleanup(Throwable cause)
    {
        IOException current = new IOException("Cleanup failed for " + name + "/" + sequence, cause);
        if (failure != null && failure != cause) current.addSuppressed(failure);
        if (closeFailure == null) closeFailure = current;
        else if (cause != closeFailure) closeFailure.addSuppressed(current);
        return closeFailure;
    }

    /** Waits for collection while retaining caller interruption.
     * @throws IOException if interrupted
     */
    private static void pause() throws IOException
    {
        try { Thread.sleep(1); }
        catch (InterruptedException cause)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retaining unfinished Windows Job", cause);
        }
    }

    /** Retains the first operation fault and suppresses subsequent independent faults.
     * @param operation operation that failed
     * @param cause original failure
     * @return first contextual fault
     */
    private IOException failed(String operation, Throwable cause)
    {
        if (failure == null) failure = new IOException(operation + " failed for " + name + "/" + sequence, cause);
        else if (cause != failure) failure.addSuppressed(cause);
        // Do not call AllocationWaits.stop during its continuation callback; resume's finally unwinds that callback.
        return failure;
    }

    /** Rejects automatic controls or successful outcomes after an uncertain operation.
     * @throws IOException if an earlier fault remains retained
     */
    private void requireCertain() throws IOException
    {
        if (failure != null) throw new IOException("Attempt retains an earlier failure", failure);
    }

    /** Rejects cross-thread access before touching native or queue ownership. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Windows attempt requires its owner thread");
    }

    /** Narrow ports preserve the production ownership sequence in controlled lifecycle tests. */
    interface Resources
    {
        /** Prepares a suspended Job or retains its checked failure capsule within this acquisition plan.
         * @return newly owned prepared Job
         * @throws IOException if preparation fails, including partial native acquisition
         */
        Process prepare() throws IOException;
        /** Registers exact action and attempt identity while borrowing the Job.
         * @param process authoritative owned Job
         * @return registration retained until instrumentation closes after Job zero
         * @throws IOException if registration fails
         */
        Close register(Process process) throws IOException;
        /** Creates a resource-free instrumentation owner before its native startup.
         * @param process authoritative owned Job
         * @param waits shared root and descendant batches
         * @param peers owner-thread peer service without queue reentry
         * @return instrumentation owner
         * @throws IOException if configuration cannot be established
         */
        Hooks hooks(Process process, AllocationWaits waits, AllocationHook.Pump peers) throws IOException;
        /** Reconciles a retained preparation-failure capsule, if acquisition did not return a Job.
         * @throws IOException if partial acquisition remains unresolved
         */
        void closePreparationFailure() throws IOException;
    }
    /** Owns an authoritative Job; its result is empty until root and every Job member are terminal. */
    interface Process extends AutoCloseable
    {
        /** Reads actual complete Job status.
         * @return authentic unsigned root status and abortion flag, or empty while any member can execute
         * @throws IOException if status cannot be observed
         */
        Optional<WindowsProcess.Result> poll() throws IOException;
        /** Requests whole-Job cancellation once; it supplies no completion outcome.
         * @throws IOException if the cancellation request is uncertain
         */
        void terminate() throws IOException;
        @Override void close() throws IOException;
    }
    /** Owns instrumentation independently of the Job and requires actual Job zero before destruction. */
    interface Hooks
    {
        /** Attaches and awaits readiness before releasing the prepared root.
         * @throws IOException if membership, attachment, readiness or release fails
         */
        void start() throws IOException;
        /** Delivers copied messages and optionally advances one held child.
         * @param advance whether this round may install and resume a child
         * @throws IOException if delivery or child validation fails
         */
        void pump(boolean advance) throws IOException;
        /** Posts to the exact installation and returns only after its authentic accepted acknowledgement.
         * @param wait validated native allocation identity
         * @throws IOException if post or acknowledgement is uncertain
         */
        void grant(AllocationWaits.Wait wait) throws IOException;
        /** Withdraws grant authority without unloading live code. */
        void stop();
        /** Closes hooks and their manager only after actual whole-Job zero.
         * @param barrier authoritative Job-zero observation
         * @throws IOException if the barrier or native destruction is unresolved
         */
        void close(AllocationHook.ExecutionBarrier barrier) throws IOException;
    }
    /** Retires a previously retained registration; it never closes its borrowed Job owner. */
    @FunctionalInterface
    interface Close
    {
        /**
         * Releases this registration.
         * @throws IOException if release remains unresolved
         */
        void close() throws IOException;
    }

    /** Retains the checked native preparation capsule before propagating acquisition failure. */
    static final class NativeResources implements Resources, Close
    {
        /** Borrowed started Frida runtime; this adapter never closes it. */
        private final FridaRuntime runtime;
        /** Borrowed measurement adapter; registrations do not transfer process ownership. */
        private final WindowsMeasurements measurements;
        /** Copied stable action and workload inputs; standard handles remain borrowed. */
        private final WindowsAttempts.Command command;
        /** Reserved queue attempt ordinal, distinct from native wait identities. */
        private final long sequence;
        /** Unique correlation UUID for this queue attempt. */
        private final UUID identity;
        /** Positive bounded native control interval. */
        private final Duration timeout;
        /** Checked capsule retaining the partially acquired native owner after preparation fails. */
        private WindowsProcess.PreparationFailure preparationFailure;
        /** Measurement registration retained through STOPPING and actual whole-Job zero. */
        private WindowsMeasurements.Registration registration;

        /**
         * Borrows native services and copied inputs without acquiring process or instrumentation owners.
         * @param runtime borrowed started Frida runtime
         * @param measurements borrowed Windows measurement adapter
         * @param command copied stable action and workload inputs
         * @param sequence reserved queue attempt ordinal
         * @param identity unique attempt correlation UUID
         * @param timeout positive representable control interval
         */
        NativeResources(FridaRuntime runtime, WindowsMeasurements measurements, WindowsAttempts.Command command,
            long sequence, UUID identity, Duration timeout)
        {
            this.runtime = runtime;
            this.measurements = measurements;
            this.command = command;
            this.sequence = sequence;
            this.identity = identity;
            this.timeout = timeout;
        }

        @Override public Process prepare() throws IOException
        {
            // Allocate the Java bridge before native acquisition so the successful return cannot lose its owner.
            NativeProcess bridge = new NativeProcess();
            try
            {
                bridge.process = WindowsProcess.prepare(command.arguments(), command.directory(), command.environment(),
                    command.input(), command.output(), command.error());
                return bridge;
            }
            catch (WindowsProcess.PreparationFailure cause) { preparationFailure = cause; throw cause; }
        }
        @Override public Close register(Process process)
        {
            registration = measurements.register(command.action(), sequence, ((NativeProcess) process).process);
            return this;
        }
        @Override public Hooks hooks(Process process, AllocationWaits waits, AllocationHook.Pump peers)
        {
            return new WindowsAllocationHooks(identity, runtime, ((NativeProcess) process).process, waits, timeout, peers);
        }
        @Override public void close()
        {
            if (registration != null) registration.close();
        }
        @Override public void closePreparationFailure() throws IOException
        {
            if (preparationFailure != null) preparationFailure.close();
        }
    }

    /** Bridges the already authoritative owner without exposing any borrowed native handles. */
    private static final class NativeProcess implements Process
    {
        /** Authoritative Windows Job owner retained before instrumentation acquisition. */
        private WindowsProcess process;
        /** Allocates the Java bridge before acquiring its native Job owner. */
        private NativeProcess() {}
        @Override public Optional<WindowsProcess.Result> poll() throws IOException { return process.poll(); }
        @Override public void terminate() throws IOException { process.terminate(); }
        @Override public void close() throws IOException { process.close(); }
    }
}
