package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Owns one fresh native compiler workload until its complete terminal composition and token retirement.
 * Original peer descriptors remain borrowed; only the invocation closes them after all retries retire.
 */
final class MacAttempt implements JobQueue.Attempt<Integer>
{
    /** Retained native execution and cleanup boundary. */
    private final Backend backend;
    /** Borrowed factory, or null for caller-owned direct cleanup. */
    private final MacAttempts attempts;
    /** Authenticated logical compiler action. */
    private final String name;
    /** Queue-wide attempt ordinal. */
    private final long sequence;
    /** Only thread authorized to control native ownership. */
    private final Thread thread = Thread.currentThread();
    /** Indicates whether native acquisition consumed its opportunity. */
    private boolean started;
    /** Indicates whether queue cancellation withdrew continuation authority. */
    private boolean terminated;
    /** Indicates whether backend and factory ownership closed successfully. */
    private boolean closed;
    /** Excludes queue controls reentering a native start, resume, poll or cleanup callback. */
    private boolean controlling;
    /** Invocation-owned successful retirement history, or null for callers that do not retain receipts. */
    private final MacAttempts.Retirements retirements;
    /** Indicates whether an earlier cleanup failed, permanently withholding successful retirement publication. */
    private boolean retirementUncertain;

    /**
     * Configures a fresh native backend without acquisition.
     * @param scope scoped services
     * @param attempts retaining factory
     * @param runtime started runtime
     * @param measurements native owner registry
     * @param library trusted bridge
     * @param helper trusted anchor
     * @param directory absolute scratch root
     * @param timeout positive control bound
     * @param name action identity
     * @param sequence queue ordinal
     * @param command authenticated command and borrowed capabilities
     */
    MacAttempt(ToolingScope scope, MacAttempts attempts, FridaRuntime runtime,
        MacMeasurements.NativeSource measurements, Path library, Path helper, Path directory,
        Duration timeout, String name, long sequence, MacAttempts.Command command)
    {
        this(new NativeExecution(scope, attempts, runtime, measurements, library, helper, directory,
            timeout, name, sequence, command), attempts, name, sequence, attempts.retirementLedger());
    }

    /**
     * Binds queue lifecycle decisions to a retained external execution boundary.
     * @param backend resource-free native execution owner
     * @param attempts retaining factory, or null when the caller directly owns cleanup
     * @param name nonblank logical action identity
     * @param sequence nonnegative queue ordinal
     */
    MacAttempt(Backend backend, MacAttempts attempts, String name, long sequence)
    {
        this(backend, attempts, name, sequence, null);
    }

    /**
     * Binds external execution and immutable retirement publication to caller-owned lifetimes.
     * @param backend retained external native execution boundary
     * @param attempts retaining factory, or null for caller-owned cleanup
     * @param name nonblank compiler action
     * @param sequence nonnegative queue-wide ordinal
     * @param retirements caller-owned history, or null when receipt retention is not needed
     */
    MacAttempt(Backend backend, MacAttempts attempts, String name, long sequence, MacAttempts.Retirements retirements)
    {
        new MacAttempts.Blocked(name, sequence, 0);
        this.backend = Objects.requireNonNull(backend, "backend");
        this.attempts = attempts;
        this.name = name;
        this.sequence = sequence;
        this.retirements = retirements;
    }

    /**
     * Returns the retained logical action identity.
     * @return stable action name
     */
    String name() { requireThread(); return name; }

    /**
     * Returns the native attempt's queue correlation.
     * @return nonnegative ordinal
     */
    long sequence() { requireThread(); return sequence; }

    @Override public void start() throws IOException
    {
        requireThread();
        if (started || terminated || closed) throw new IllegalStateException("Attempt starts once");
        started = true;
        controlling = true;
        try { backend.start(); }
        finally { controlling = false; }
    }

    @Override public void resume(long waitSequence) throws IOException
    {
        requireThread();
        if (!started || terminated || closed || controlling) throw new IllegalStateException("Attempt cannot resume");
        controlling = true;
        try { backend.resume(waitSequence); }
        finally { controlling = false; }
    }

    @Override public void terminate() throws IOException
    {
        requireThread();
        if (controlling) throw new IllegalStateException("Queue controls cannot reenter native execution");
        if (terminated || closed) return;
        terminated = true;
        controlling = true;
        try { backend.terminate(); }
        finally { controlling = false; }
    }

    @Override public Optional<JobQueue.Outcome<Integer>> poll() throws IOException
    {
        requireThread();
        if (controlling) throw new IllegalStateException("Queue controls cannot reenter native execution");
        if (!started || closed) return Optional.empty();
        controlling = true;
        try { return backend.poll().map(result -> new JobQueue.Outcome<>(result.result(), terminated || result.aborted())); }
        finally { controlling = false; }
    }

    @Override public void close() throws IOException
    {
        requireThread();
        if (controlling) throw new IllegalStateException("Queue controls cannot reenter native execution");
        if (closed) return;
        controlling = true;
        try { backend.close(); }
        catch (IOException | RuntimeException | Error cause)
        {
            retirementUncertain = true;
            throw cause;
        }
        finally { controlling = false; }
        if (retirements != null && !retirementUncertain)
            backend.collected().ifPresent(collected -> retirements.append(name, sequence,
                new Collected(collected.rootAuthority(), collected.result(), terminated || collected.aborted())));
        if (attempts != null) attempts.forget(this);
        closed = true;
    }

    /**
     * Reads genuine decoded allocation receipts independently of native cleanup and outcome acceptance.
     * @return immutable retained native report history, including after successful close
     */
    List<AllocationHook.Denial> denials() { requireThread(); return List.copyOf(backend.denials()); }

    /**
     * Services native frames without entering queue controls.
     * @throws IOException if native delivery fails
     */
    void pumpEvents() throws IOException { requireThread(); if (started && !closed) backend.pumpEvents(); }

    /**
     * Publishes one genuine allocation batch.
     * @return next batch, or empty while inactive
     * @throws IOException if a native grant has uncertain effects
     */
    OptionalLong pollAllocationWait() throws IOException
    {
        requireThread();
        return started && !closed && !controlling ? backend.pollAllocationWait() : OptionalLong.empty();
    }

    /** Rejects controls from outside the constructing native thread. */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Attempt belongs to its constructing thread");
    }

    /**
     * Collects a retained native owner after acquisition stopped before complete workload construction.
     * The owner supplies the actual native destruction authority; an empty terminal observation never certifies it.
     * @param rootAcquired whether original native root acquisition returned successfully
     * @param owner retained native boundary, including partial pre-bootstrap ownership
     * @param timeout positive bound for an acquired root's complete terminal collection
     * @throws IOException if cancellation, terminal collection or native destruction fails
     */
    static void collectPartial(boolean rootAcquired, PartialOwner owner, Duration timeout) throws IOException
    {
        Objects.requireNonNull(owner, "owner");
        LinuxAttempts.checkedTimeout(timeout);
        if (!rootAcquired)
        {
            owner.close();
            return;
        }
        owner.cancel();
        long began = System.nanoTime();
        while (owner.terminalResult().isEmpty())
        {
            if (System.nanoTime() - began >= timeout.toNanos())
                throw new IOException("Partial compiler acquisition awaits native terminal collection");
            NativeExecution.pause();
        }
        owner.close();
    }

    /** Retained native operations for collecting an incompletely constructed compiler workload. */
    interface PartialOwner
    {
        /**
         * Requests exact native cancellation after successful original root acquisition.
         * @throws IOException if native cancellation cannot be confirmed
         */
        void cancel() throws IOException;
        /**
         * Reads the genuine original root result after complete native tree collection.
         * @return original result, or empty while native collection remains incomplete
         * @throws IOException if native terminal authority cannot be established
         */
        OptionalInt terminalResult() throws IOException;
        /**
         * Destroys retained native ownership, including safe pre-bootstrap state when supported by the owner.
         * @throws IOException if native destruction remains uncertain
         */
        void close() throws IOException;
    }

    /**
     * Owns external execution; outcomes require the complete native workload and instrumentation barrier.
     * Cleanup retains every uncertain acquisition until terminal collection is established.
     */
    interface Backend extends JobQueue.Attempt<Integer>
    {
        /**
         * Copies already decoded native allocation reports without advancing execution.
         * @return immutable genuine reports, empty before instrumentation observes a denial
         */
        default List<AllocationHook.Denial> denials() { return List.of(); }

        /**
         * Returns complete original-root collection after native closure, token retirement and registry removal.
         * @return immutable original authority and result, or empty for incomplete or failed retirement
         */
        default Optional<Collected> collected() { return Optional.empty(); }
        /**
         * Services copied native protocol frames without queue callbacks.
         * @throws IOException if delivery fails
         */
        void pumpEvents() throws IOException;
        /**
         * Publishes an authentic allocation wait after native controls return.
         * @return next wait batch or empty
         * @throws IOException if permission effects are uncertain
         */
        OptionalLong pollAllocationWait() throws IOException;
    }

    /**
     * Carries a complete native collection independently of its later publication order.
     * Construction alone establishes no native authority or terminal proof.
     * @param rootAuthority all eighteen original root fields captured before release
     * @param result actual original root exit status
     * @param aborted indicates whether termination was requested for the attempt
     */
    record Collected(List<Long> rootAuthority, int result, boolean aborted)
    {
        /**
         * Freezes complete native authority and rejects an invalid original result.
         * @param rootAuthority all eighteen original root fields
         * @param result original root result in 0 through 255
         * @param aborted indicates whether termination was requested for the attempt
         */
        Collected
        {
            rootAuthority = List.copyOf(rootAuthority);
            if (rootAuthority.size() != 18 || rootAuthority.get(0) <= 0 || rootAuthority.get(1) <= 0)
                throw new IllegalArgumentException("Original root authority requires eighteen native fields and positive PID/incarnation");
            if (result < 0 || result > 255) throw new IllegalArgumentException("Root result must be in 0 through 255");
        }
    }

    /** Retains execution faults independently of the outcome of later mandatory cleanup. */
    static final class Fault
    {
        /** Context bound to this exact compiler attempt. */
        private final String context;
        /** First execution or cleanup fault, preserved for the caller. */
        private IOException failure;

        /**
         * Retains stable diagnostic correlation without native acquisition.
         * @param name authenticated compiler action
         * @param sequence queue-wide attempt ordinal
         */
        Fault(String name, long sequence)
        {
            new MacAttempts.Blocked(name, sequence, 0);
            context = "Mac compiler attempt failed: " + name + "/" + sequence;
        }

        /**
         * Preserves the first fault and its independent later cleanup evidence.
         * @param cause observed execution or cleanup fault
         * @return original contextual fault
         */
        IOException failed(Throwable cause)
        {
            Objects.requireNonNull(cause, "cause");
            if (failure == null) failure = new IOException(context, cause);
            else if (cause != failure) failure.addSuppressed(cause);
            return failure;
        }

        /**
         * Rejects further execution after an observed fault.
         * @throws IOException if execution has failed
         */
        void requireCertain() throws IOException { if (failure != null) throw failure; }

        /**
         * Runs independently required cleanup while retaining execution diagnostics.
         * @param cleanup exact owned cleanup, returning only after every ownership barrier succeeds
         * @throws IOException if this cleanup fails; a previously reported execution fault alone does not fail cleanup
         */
        void cleanup(Cleanup cleanup) throws IOException
        {
            Objects.requireNonNull(cleanup, "cleanup");
            try { cleanup.close(); }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }
    }

    /** Supplies the actual native cleanup operation without transferring its ownership authority. */
    @FunctionalInterface
    interface Cleanup
    {
        /**
         * Closes exact owned resources after their supported terminal and instrumentation barriers.
         * @throws IOException if any required cleanup remains incomplete
         */
        void close() throws IOException;
    }

    /** Owns the real Frida/native workload boundary and all its partial acquisitions. */
    private static final class NativeExecution implements Backend
    {
        /** Native controller thread. */
        private final Thread thread = Thread.currentThread();
        /** Borrowed shared services retained through cleanup. */
        private final ToolingScope scope;
        /** Borrowed invocation peer service. */
        private final MacAttempts attempts;
        /** Borrowed started native runtime. */
        private final FridaRuntime runtime;
        /** Exact native-owner measurement registry. */
        private final MacMeasurements.NativeSource measurements;
        /** Trusted absolute native bridge. */
        private final Path library;
        /** Trusted absolute non-forking anchor. */
        private final Path helper;
        /** Invocation-owned parent for fresh attempt storage. */
        private final Path directory;
        /** Positive bound for cleanup and native acknowledgement. */
        private final Duration timeout;
        /** Stable authenticated action identity. */
        private final String name;
        /** Queue-wide native attempt ordinal. */
        private final long sequence;
        /** Authenticated inputs and borrowed original descriptors and FIFO lease. */
        private final MacAttempts.Command command;
        /** Native allocation waits shared across this attempt's images. */
        private final AllocationWaits waits = new AllocationWaits();
        /** Original manager retained before native start and transferred to the workload owner. */
        private FridaRuntime.Manager manager;
        /** Fresh attempt storage retained until native closure. */
        private Path scratch;
        /** Partial or active native workload owner. */
        private MacWorkloadNative.Owner nativeOwner;
        /** Records successful native root acquisition before later construction can fail. */
        private boolean rootAcquired;
        /** Complete native and image lifecycle owner. */
        private MacWorkload workload;
        /** Image hooks retained through the terminal barrier. */
        private MacCargoInstrumentation instrumentation;
        /** Whole-workload token ownership, retired after native closure. */
        private LinuxCargoTokens.Attempt tokens;
        /** Indicates whether the exact owner is registered for measurements. */
        private boolean registered;
        /** Indicates whether the start opportunity was consumed. */
        private boolean attempted;
        /** Indicates whether start or resume prevents wait publication. */
        private boolean controlling;
        /** Indicates whether continuation authority has been withdrawn. */
        private boolean stopping;
        /** Indicates whether all native and scratch ownership closed. */
        private boolean closed;
        /** First genuine fault retained through independent cleanup. */
        private final Fault fault;
        /** Genuine original root result published after complete terminal composition. */
        private JobQueue.Outcome<Integer> outcome;
        /** Exact original root native authority captured while held, before its release. */
        private List<Long> rootAuthority;
        /** Complete native retirement retained independently of later queue outcome publication. */
        private Collected collected;
        /** Indicates whether token retirement or measurement unregister failed and forbids success publication. */
        private boolean retirementFailed;
        /** Indicates whether the exact token owner's close returned successfully. */
        private boolean tokensRetired;
        /** Indicates whether exact native measurement unregister returned successfully. */
        private boolean measurementsUnregistered;

        /**
         * Retains resource-free action configuration and borrowed invocation services.
         * @param scope shared scoped services
         * @param attempts invocation factory retaining this owner
         * @param runtime started native runtime
         * @param measurements native owner registry
         * @param library trusted absolute bridge
         * @param helper trusted absolute anchor
         * @param directory absolute invocation scratch root
         * @param timeout positive cleanup and acknowledgement bound
         * @param name authenticated action name
         * @param sequence nonnegative queue-wide attempt ordinal
         * @param command authenticated immutable execution and borrowed capabilities
         */
        NativeExecution(ToolingScope scope, MacAttempts attempts, FridaRuntime runtime,
            MacMeasurements.NativeSource measurements, Path library, Path helper, Path directory,
            Duration timeout, String name, long sequence, MacAttempts.Command command)
        {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.attempts = Objects.requireNonNull(attempts, "attempts");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.measurements = Objects.requireNonNull(measurements, "measurements");
            this.library = Objects.requireNonNull(library, "library");
            this.helper = Objects.requireNonNull(helper, "helper");
            this.directory = Objects.requireNonNull(directory, "directory");
            this.timeout = LinuxAttempts.checkedTimeout(timeout);
            new MacAttempts.Blocked(name, sequence, 0);
            this.name = name;
            this.sequence = sequence;
            this.command = Objects.requireNonNull(command, "command");
            this.fault = new Fault(name, sequence);
            if (!library.isAbsolute() || !helper.isAbsolute() || !directory.isAbsolute() ||
                !name.equals(command.request().name()))
                throw new IllegalArgumentException("Absolute native paths and authenticated action name required");
        }

        /**
         * Returns this action's stable queue name.
         * @return authenticated request UUID text
         */
        String name() { requireThread(); return name; }

        /**
         * Returns this fresh attempt's queue ordinal.
         * @return nonnegative queue-wide attempt ordinal
         */
        long sequence() { requireThread(); return sequence; }

        @Override public void start() throws IOException
        {
            requireThread();
            requireCertain();
            if (attempted || stopping || closed) throw new IllegalStateException("Compiler attempt starts once");
            attempted = true;
            controlling = true;
            try
            {
                manager = runtime.newManager();
                manager.start();
                scratch = Files.createTempDirectory(directory, "compiler-");
                CargoCompilerRequest request = command.request();
                List<String> environment = new ArrayList<>();
                request.environment().forEach((key, value) -> environment.add(key + "=" + value));
                MacWorkloadNative.Launch launch = new MacWorkloadNative.Launch(library, helper, scratch,
                    request.directory(), command.peer().streams(), request.arguments(), environment,
                    command.peer().identity().uid(), command.peer().identity().gid());
                nativeOwner = new MacWorkloadNative.Owner(scope, launch, manager);
                MacImages.Identity root = nativeOwner.root();
                rootAcquired = true;
                rootAuthority = List.copyOf(nativeOwner.authority(root));
                measurements.register(new MacMeasurements.Attempt(name, sequence), nativeOwner);
                registered = true;
                tokens = command.lease().retainAttempt();
                instrumentation = new MacCargoInstrumentation(scope, UUID.randomUUID(), manager, nativeOwner,
                    command.lease(), tokens, waits, timeout, attempts::pumpAll, MacCargoInstrumentation.Mode.COMPILER);
                workload = new MacWorkload(scope, nativeOwner, instrumentation, timeout);
                workload.start();
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
            finally { controlling = false; }
        }

        @Override public void resume(long waitSequence) throws IOException
        {
            requireThread();
            requireCertain();
            if (instrumentation == null || controlling || stopping || closed)
                throw new IllegalStateException("Compiler attempt cannot grant allocation permission");
            controlling = true;
            try { waits.resume(waitSequence, instrumentation::grant); }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
            finally { controlling = false; }
        }

        @Override public void terminate() throws IOException
        {
            requireThread();
            stop();
            try
            {
                if (workload != null) workload.cancel();
                else if (nativeOwner != null && rootAcquired) nativeOwner.cancel();
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        /**
         * Services copied allocation and token frames without entering queue controls.
         * @throws IOException if native delivery or acknowledgement fails
         */
        @Override public void pumpEvents() throws IOException
        {
            requireThread();
            requireCertain();
            if (instrumentation != null && !stopping && !closed) instrumentation.pump();
        }

        /**
         * Publishes an allocation batch only outside start and resume controls.
         * @return next wait ordinal, or empty while controlling, stopped or without waits
         * @throws IOException if a prior grant failed
         */
        @Override public OptionalLong pollAllocationWait() throws IOException
        {
            requireThread();
            if (controlling || stopping || closed) return OptionalLong.empty();
            requireCertain();
            return waits.poll();
        }

        @Override public Optional<JobQueue.Outcome<Integer>> poll() throws IOException
        {
            requireThread();
            requireCertain();
            if (outcome != null) return Optional.of(outcome);
            if (workload == null) return Optional.empty();
            try
            {
                pumpEvents();
                OptionalInt result = workload.advance();
                if (result.isEmpty()) return Optional.empty();
                retire();
                outcome = new JobQueue.Outcome<>(result.getAsInt(), stopping);
                return Optional.of(outcome);
            }
            catch (IOException | RuntimeException cause) { throw failed(cause); }
        }

        @Override public List<AllocationHook.Denial> denials()
        {
            requireThread();
            return instrumentation == null ? List.of() : instrumentation.denials();
        }

        @Override public Optional<Collected> collected()
        {
            requireThread();
            return closed ? Optional.ofNullable(collected) : Optional.empty();
        }

        @Override public void close() throws IOException
        {
            requireThread();
            if (closed) return;
            stop();
            fault.cleanup(() ->
            {
                if (workload != null)
                {
                    if (workload.completedResult().isEmpty()) workload.cancel();
                    long began = System.nanoTime();
                    while (workload.completedResult().isEmpty())
                    {
                        try { workload.advance(); }
                        catch (IOException cause)
                        {
                            if (workload.completedResult().isEmpty()) throw cause;
                        }
                        if (System.nanoTime() - began >= timeout.toNanos())
                            throw new IOException("Compiler cleanup awaits complete native workload collection");
                        if (workload.completedResult().isEmpty()) pause();
                    }
                }
                else if (nativeOwner != null)
                {
                    collectPartial(rootAcquired, new PartialOwner()
                    {
                        @Override public void cancel() throws IOException { nativeOwner.cancel(); }
                        @Override public OptionalInt terminalResult() throws IOException { return nativeOwner.terminalResult(); }
                        @Override public void close() throws IOException { nativeOwner.close(); }
                    }, timeout);
                }
                else if (manager != null) manager.close();
                retire();
                if (scratch != null) Files.delete(scratch);
                closed = true;
            });
        }

        /** Withdraws grants before native cancellation without waking waiters. */
        private void stop()
        {
            stopping = true;
            waits.stop();
            if (instrumentation != null) instrumentation.stop();
        }

        /**
         * Retires tokens and measurement registration only after complete native owner destruction.
         * @throws IOException if token accounting cannot confirm retirement
         */
        private void retire() throws IOException
        {
            if (retirementFailed) throw new IOException("Earlier native retirement remains unresolved");
            if (nativeOwner != null && !nativeOwner.isClosed())
                throw new IOException("Native owner must close before compiler retirement");
            try
            {
                if (tokens != null)
                {
                    LinuxCargoTokens.Attempt retiring = tokens;
                    tokens = null;
                    retiring.close();
                    tokensRetired = true;
                }
                if (registered)
                {
                    measurements.unregister(new MacMeasurements.Attempt(name, sequence), nativeOwner);
                    registered = false;
                    measurementsUnregistered = true;
                }
            }
            catch (IOException | RuntimeException | Error cause)
            {
                retirementFailed = true;
                throw cause;
            }
            boolean hasCompleteRetirement = tokensRetired && measurementsUnregistered && workload != null &&
                nativeOwner != null && rootAuthority != null;
            if (collected == null && hasCompleteRetirement)
            {
                OptionalInt result = workload.completedResult();
                if (result.isPresent()) collected = new Collected(rootAuthority, result.getAsInt(), stopping);
            }
        }

        /**
         * Retains the first genuine failure through independently required cleanup.
         * @param cause original failed operation
         * @return first contextual failure
         */
        private IOException failed(Throwable cause)
        {
            return fault.failed(cause);
        }

        /**
         * Rejects automatic controls after an uncertain failure.
         * @throws IOException if the first failure remains retained
         */
        private void requireCertain() throws IOException { fault.requireCertain(); }

        /**
         * Pauses retained cleanup while preserving interruption.
         * @throws IOException if interrupted
         */
        private static void pause() throws IOException
        {
            try { Thread.sleep(1); }
            catch (InterruptedException cause)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while retaining compiler cleanup ownership", cause);
            }
        }

        /** Requires the constructing native controller thread. */
        private void requireThread()
        {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Mac compiler attempt belongs to its constructing thread");
        }
    }
}
