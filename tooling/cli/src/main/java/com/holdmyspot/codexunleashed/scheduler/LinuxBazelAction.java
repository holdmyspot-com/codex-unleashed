package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Retains one authenticated namespace-local action owner across genuine queued child attempts.
 * The original native owner creates and reaps children; this host owner never substitutes a new wrapper.
 * Queue-selected cancellation alone authorizes an aborted outcome. Exit status alone does not authorize replay.
 * All operations belong to the constructing thread; native callbacks must copy events for later pumping.
 */
public final class LinuxBazelAction implements LinuxBazelSession.Action
{
    /** Exclusive operation thread. */
    private final Thread owner = Thread.currentThread();
    /** Stable invocation-unique action name. */
    private final String name;
    /** Independently supplied trusted peak estimate; null preserves unknown-memory exclusivity. */
    private final Long peakBytes;
    /** Original native conversation, owned until outer action cleanup. */
    private final LinuxBazelControl control;
    /** Resource-free provider of actual authenticated containment and original hook ownership. */
    private final ResourcesFactory factory;
    /** Positive bound for cancellation cleanup; never a total workload runtime bound. */
    private final Duration timeout;
    /** Accepted closed attempts, retained even after another retry has begun. */
    private final Map<Long, JobQueue.Outcome<LinuxBazelControl.Result>> completed = new LinkedHashMap<>();
    /** Outcomes already delivered to the session, distinct from native FINAL publication. */
    private final java.util.Set<Long> delivered = new java.util.HashSet<>();
    /** Most recently reserved queue-wide ordinal. */
    private long lastSequence = -1;
    /** Current queue-owned attempt, including incomplete cleanup; null only when no attempt is owned. */
    private Attempt live;
    /** Terminal final publication attempted, including uncertain publication. */
    private boolean finalAttempted;
    /** First operational failure, retained even when native cleanup eventually succeeds. */
    private IOException failure;
    /** Whether original socket cleanup was attempted. */
    private boolean closeAttempted;
    /** Retained cleanup uncertainty, never hidden by repeated close. */
    private IOException closeFailure;

    /**
     * Retains an independently authenticated original owner without creating a child or native attachment.
     * The caller must compare the initial control birth with the kernel-captured peer birth before construction.
     * @param name stable invocation-unique action identity
     * @param peakBytes nonnegative independently supplied peak estimate, or null when unknown
     * @param control original native owner connection, transferred to this action
     * @param factory provider retaining native resources before each potentially failing acquisition
     * @param timeout positive bounded native control and cleanup timeout
     * @throws NullPointerException if a parameter is null
     * @throws IllegalArgumentException if the name is blank, the peak is negative, or the timeout is nonpositive or unrepresentable
     */
    public LinuxBazelAction(String name, Long peakBytes, LinuxBazelControl control, ResourcesFactory factory, Duration timeout)
    {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("Bazel action name must be nonblank");
        if (peakBytes != null && peakBytes < 0) throw new IllegalArgumentException("Peak estimate must be nonnegative");
        this.peakBytes = peakBytes;
        this.control = Objects.requireNonNull(control, "control");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.timeout = LinuxAttempts.checkedTimeout(timeout);
    }

    /**
     * Returns this retained invocation-local action identity.
     * @return nonblank stable action name
     */
    @Override public String name() { requireOwner(); return name; }

    /**
     * Preserves the independently supplied host estimate without deriving a prediction from residency.
     * @return nonnegative trusted peak estimate, or null when the producer supplied none
     */
    @Override public Long peakBytes() { requireOwner(); return peakBytes; }

    /**
     * Reserves one resource-free attempt with a fresh global ordinal, retaining original action position externally.
     * @param sequence queue-wide increasing nonnegative attempt identity
     * @return exclusively queue-owned attempt
     * @throws IllegalArgumentException if the ordinal is negative or reused
     * @throws IllegalStateException if another owner or an unresolved operation prevents a new attempt
     */
    @Override public JobQueue.Attempt<LinuxBazelControl.Result> create(long sequence)
    {
        requireOwner();
        if (sequence < 0 || sequence <= lastSequence) throw new IllegalArgumentException("Reused Bazel attempt ordinal");
        if (live != null || finalAttempted || failure != null || closeAttempted)
            throw new IllegalStateException("Bazel action cannot create another attempt", failure);
        live = new Attempt(sequence);
        lastSequence = sequence;
        return live;
    }

    /**
     * Pumps copied hook events without entering the queue or publishing a batch during controls.
     * @throws IOException if native event delivery fails or a previous operation is uncertain
     */
    @Override public void pump() throws IOException
    {
        requireHealthy();
        if (live != null) live.pump();
    }

    /**
     * Publishes at most one allocation batch outside start, resume and termination operations.
     * @return next current-attempt batch identity, or empty
     * @throws IOException if a previous grant or event delivery has uncertain effects
     */
    @Override public OptionalLong pollBlocked() throws IOException
    {
        requireHealthy();
        return live == null ? OptionalLong.empty() : live.pollBlocked();
    }

    /**
     * Reports retained queue-owned cleanup responsibility, including failed acquisition or cleanup.
     * @return whether an attempt still owns native resources or an unresolved preparation
     */
    @Override public boolean hasOpenAttempt() { requireOwner(); return live != null; }

    /**
     * Correlates the queue's unchanged outcome with an already collected and successfully closed exact attempt.
     * Native RESET or FINAL was already published once during that attempt's successful cleanup; it is never resent.
     * A later retry may already exist because queue reconciliation precedes session outcome delivery.
     * @param completion original queue-accepted attempt payload
     * @throws IllegalArgumentException if the receipt is unknown, changed, duplicated or names another action
     * @throws IOException if an earlier operational fault prevents accepting a session result
     */
    @Override public void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) throws IOException
    {
        requireHealthy();
        Objects.requireNonNull(completion, "completion");
        var original = completed.get(completion.attemptSequence());
        if (!name.equals(completion.name()) || original == null || !original.equals(completion.outcome()) ||
            !delivered.add(completion.attemptSequence()))
            throw new IllegalArgumentException("Bazel outcome does not match an unconsumed closed native attempt");
    }

    /**
     * Closes the original action connection only after all queue-owned attempt cleanup has succeeded.
     * Cleanup never invents a result or retries an uncertain native close.
     * @throws IOException if an attempt remains owned, socket cleanup fails or a prior cleanup is uncertain
     */
    @Override public void close() throws IOException
    {
        requireOwner();
        if (closeFailure != null) throw new IOException("Bazel action cleanup remains uncertain", closeFailure);
        if (closeAttempted) return;
        if (live != null) throw new IOException("Bazel action retains a queue-owned attempt");
        closeAttempted = true;
        try { control.close(); }
        catch (IOException | RuntimeException cause)
        {
            closeFailure = new IOException("Original Bazel owner connection cleanup failed", cause);
            throw closeFailure;
        }
    }

    /**
     * Requires the owner thread and certain live protocol before external operations.
     * @throws IOException if an earlier effect is uncertain or cleanup began
     */
    private void requireHealthy() throws IOException
    {
        requireOwner();
        if (failure != null) throw new IOException("Bazel action has an unresolved operation", failure);
        if (closeAttempted) throw new IOException("Bazel action connection is closed");
    }

    /** Requires the constructing thread before touching retained native owners. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Bazel action belongs to its constructing thread");
    }

    /**
     * Retains the first genuine operational failure without erasing its possible effects.
     * @param operation failed native operation
     * @param cause original failure
     * @return retained contextual checked failure
     */
    private IOException failed(String operation, Throwable cause)
    {
        if (failure == null) failure = new IOException("Bazel " + name + " " + operation + " failed", cause);
        return failure;
    }

    /** Owns one exact child, its host containment, measurement registration and original instrumentation manager. */
    private final class Attempt implements JobQueue.Attempt<LinuxBazelControl.Result>
    {
        /** Queue-wide immutable attempt ordinal. */
        private final long sequence;
        /** Genuine validated allocation reports, never synthesized from exit or memory readings. */
        private final AllocationWaits waits = new AllocationWaits();
        /** Native resource owner retained before admission can fail partially. */
        private Resources resources;
        /** One-time start reservation, made before child creation. */
        private boolean started;
        /** Prevents batch publication while queue control is active. */
        private boolean controlling;
        /** Queue-selected cancellation; this is the only abort classification authority. */
        private boolean aborted;
        /** Indicates that no more native allocation grants may be issued. */
        private boolean stopped;
        /** Hooks have closed after independently observed actual whole-tree zero. */
        private boolean instrumentationClosed;
        /** Complete authentic collected direct-child result plus independent zero, before native owner release. */
        private JobQueue.Outcome<LinuxBazelControl.Result> outcome;
        /** Successful complete cleanup. */
        private boolean closed;
        /** First unresolved cleanup failure, retained without silent replay. */
        private IOException cleanupFailure;

        /**
         * Reserves a resource-free attempt.
         * @param sequence immutable nonnegative global queue ordinal
         */
        private Attempt(long sequence) { this.sequence = sequence; }

        /**
         * Creates and authenticates the genuine native child, contains it, installs hooks and resumes once.
         * @throws IOException if native preparation, authentication, readiness or original continuation fails
         */
        @Override public void start() throws IOException
        {
            requireHealthy();
            if (started || closed) throw new IllegalStateException("Bazel attempt cannot start again");
            started = true;
            controlling = true;
            try
            {
                resources = Objects.requireNonNull(factory.create(sequence, waits), "resources");
                resources.prepareWorkspace();
                resources.admit(control.prepare());
                resources.start();
                control.collect();
            }
            catch (IOException | RuntimeException cause) { throw failed("attempt start", cause); }
            catch (Error cause) { failed("attempt start", cause); throw cause; }
            finally { controlling = false; }
        }

        /**
         * Grants only the selected genuine native wait batch and awaits each matching acknowledgement.
         * @param waitSequence current published per-attempt batch ordinal
         * @throws IOException if a grant or its acknowledgement has uncertain effects
         */
        @Override public void resume(long waitSequence) throws IOException
        {
            requireHealthy();
            if (resources == null || stopped || controlling || closed)
                throw new IllegalStateException("Bazel attempt cannot grant allocations");
            controlling = true;
            try { waits.resume(waitSequence, resources::grant); }
            catch (IOException | RuntimeException cause) { throw failed("allocation grant", cause); }
            catch (Error cause) { failed("allocation grant", cause); throw cause; }
            finally { controlling = false; }
        }

        /**
         * Indicates whether the acquired allocation backend requires a fresh attempt after denial.
         * @return true if the current backend cannot continue the denied allocation
         * @throws IllegalStateException if resource acquisition has not begun or another thread calls this method
         */
        @Override public boolean requiresRetryAfterDenial()
        {
            requireOwner();
            if (resources == null)
                throw new IllegalStateException("Bazel attempt has not acquired allocation resources");
            return resources.requiresRetryAfterDenial();
        }

        /**
         * Withdraws grants before requesting host whole-tree termination; no signal status classifies the attempt.
         * @throws IOException if grant withdrawal or native cancellation is uncertain
         */
        @Override public void terminate() throws IOException
        {
            requireHealthy();
            aborted = true;
            controlling = true;
            try
            {
                stop();
                if (resources != null) resources.requestTermination();
            }
            catch (IOException | RuntimeException cause) { throw failed("allocation-abort cancellation", cause); }
            catch (Error cause) { failed("allocation-abort cancellation", cause); throw cause; }
            finally { controlling = false; }
        }

        /**
         * Copies actual hook events for later batch publication without entering queue controls.
         * Independent whole-tree zero makes queued image admissions obsolete; original native status and hook closure
         * remain required by polling before any outcome can be accepted.
         * @throws IOException if whole-tree observation or native event delivery fails
         */
        private void pump() throws IOException
        {
            if (resources == null || stopped || closed) return;
            try
            {
                if (!resources.zero()) resources.pump();
            }
            catch (IOException | RuntimeException cause) { throw failed("hook event delivery", cause); }
            catch (Error cause) { failed("hook event delivery", cause); throw cause; }
        }

        /**
         * Publishes the next queue batch only outside controls and after actual hook admission.
         * @return genuine wait batch, or empty
         * @throws IOException if a grant or delivery is uncertain
         */
        private OptionalLong pollBlocked() throws IOException
        {
            if (controlling || resources == null || stopped || closed) return OptionalLong.empty();
            return waits.poll();
        }

        /**
         * Accepts a child result only after original native wait and independent host whole-tree zero.
         * @return unchanged native outcome, or empty while either barrier is incomplete
         * @throws IOException if status delivery, zero observation or complete hook closure fails
         */
        @Override public Optional<JobQueue.Outcome<LinuxBazelControl.Result>> poll() throws IOException
        {
            requireHealthy();
            if (outcome != null) return Optional.of(outcome);
            if (resources == null) return Optional.empty();
            try
            {
                pump();
                var result = control.pollResult();
                if (result.isEmpty() || !resources.zero()) return Optional.empty();
                finishInstrumentation();
                outcome = new JobQueue.Outcome<>(result.orElseThrow(), aborted);
                return Optional.of(outcome);
            }
            catch (IOException | RuntimeException cause) { throw failed("whole-action observation", cause); }
            catch (Error cause) { failed("whole-action observation", cause); throw cause; }
        }

        /**
         * Withdraws grants before cancellation or instrumentation destruction.
         * @throws IOException if original hook authority cannot be withdrawn
         */
        private void stop() throws IOException
        {
            if (stopped) return;
            stopped = true;
            waits.stop();
            if (resources != null) resources.stop();
        }

        /**
         * Destroys original hooks only after current independent actual zero, preserving cleanup ownership on failure.
         * @throws IOException if zero or complete instrumentation closure cannot be established
         */
        private void finishInstrumentation() throws IOException
        {
            if (instrumentationClosed) return;
            stop();
            if (!resources.zero()) throw new IOException("Bazel hooks retain a potentially executing workload");
            resources.finishInstrumentation();
            instrumentationClosed = true;
        }

        /**
         * Performs bounded native cleanup, then publishes one RESET or unchanged FINAL only for an accepted outcome.
         * RESET invalidation precedes any fresh CREATE, and no native outcome is invented during failure cleanup.
         * The first cleanup cause is retained independently of any earlier operational failure.
         * An uncertain cleanup is never replayed, including after an original Error.
         * @throws IOException if zero, original hook closure, owner control or resource cleanup remains uncertain
         */
        @Override public void close() throws IOException
        {
            requireOwner();
            if (cleanupFailure != null) throw new IOException("Bazel attempt cleanup remains unresolved", cleanupFailure);
            if (closed) return;
            try
            {
                stop();
                if (resources != null)
                {
                    if (outcome == null)
                    {
                        resources.requestTermination();
                        long startedAt = System.nanoTime();
                        while (!resources.zero())
                        {
                            if (System.nanoTime() - startedAt >= timeout.toNanos())
                                throw new IOException("Bazel cleanup awaits actual whole-tree zero");
                            try { Thread.sleep(1); }
                            catch (InterruptedException cause)
                            {
                                Thread.currentThread().interrupt();
                                var interrupted = new InterruptedIOException("Bazel attempt cleanup interrupted");
                                interrupted.initCause(cause);
                                throw interrupted;
                            }
                        }
                    }
                    finishInstrumentation();
                    if (outcome != null)
                    {
                        resources.collectWorkspace(outcome);
                        if (outcome.aborted()) control.resetAborted(resources::zero);
                        else
                        {
                            finalAttempted = true;
                            control.finish(resources::zero);
                        }
                    }
                    resources.close();
                }
                if (outcome != null) completed.put(sequence, outcome);
                closed = true;
                if (live != this) throw new IllegalStateException("Bazel attempt is not the retained owner");
                live = null;
            }
            catch (IOException | RuntimeException cause)
            {
                failed("attempt cleanup", cause);
                cleanupFailure = new IOException("Bazel " + name + " attempt cleanup failed", cause);
                throw cleanupFailure;
            }
            catch (Error cause)
            {
                failed("attempt cleanup", cause);
                cleanupFailure = new IOException("Bazel " + name + " attempt cleanup failed", cause);
                throw cause;
            }
        }
    }

    /** Creates one resource-free native owner before any child admission can fail partially. */
    @FunctionalInterface
    public interface ResourcesFactory
    {
        /**
         * Supplies an exclusive resource-free owner for one issued attempt.
         * @param sequence nonnegative queue-wide attempt ordinal
         * @param waits collector for validated actual native allocation reports
         * @return retained owner; creation must acquire no native resources
         */
        Resources create(long sequence, AllocationWaits waits);
    }

    /**
     * Retains authentic child containment, measurement and original instrumentation through complete cleanup.
     * Admission/start failures may be partial; this same owner remains reachable for bounded cleanup.
     */
    public interface Resources extends AutoCloseable
    {
        /**
         * Establishes the private attempt filesystem before native CREATE can fork its blocked child.
         * @throws IOException if mapping has partial or uncertain effects
         */
        default void prepareWorkspace() throws IOException {}
        /**
         * Freezes successful private output after actual zero and hook closure, before original waiter finalization.
         * This operation grants no worker-visible publication authority. Aborted and nonzero output is discarded.
         * @param outcome independently collected ordinary result or queue-authorized abort
         * @throws IOException if output freezing or private namespace withdrawal remains unresolved
         */
        default void collectWorkspace(JobQueue.Outcome<LinuxBazelControl.Result> outcome) throws IOException {}
        /**
         * Authenticates the native claim, contains the exact blocked child and registers host measurements.
         * @param child untrusted local child PID and birth from original owner
         * @throws IOException if identity, containment or registration cannot be established
         */
        void admit(LinuxBazelControl.Identity child) throws IOException;
        /**
         * Establishes held-image readiness, independently revalidates host authority and resumes the original manager once.
         * @throws IOException if any native transition, hook readiness or host revalidation fails
         */
        void start() throws IOException;
        /** Copies and validates actual hook events without queue callbacks.
         * @throws IOException if event delivery fails */
        void pump() throws IOException;
        /**
         * Indicates whether a denied allocation requires a new workload attempt instead of an in-place grant.
         * This observation has no control effects and remains stable for the lifetime of these resources.
         * @return true if the installed allocation backend cannot continue a denied allocation in this attempt
         */
        default boolean requiresRetryAfterDenial()
        {
            return false;
        }

        /** Grants a matching actual allocation wait and awaits acknowledgement.
         * @param wait exact validated native wait
         * @throws IOException if permission has uncertain effects */
        void grant(AllocationWaits.Wait wait) throws IOException;
        /** Withdraws allocation grants without waking blocked native allocations.
         * @throws IOException if withdrawal has uncertain effects */
        void stop() throws IOException;
        /** Requests termination of the whole owned cgroup without inventing collected status.
         * @throws IOException if native cancellation fails */
        void requestTermination() throws IOException;
        /** Independently observes actual whole-tree execution impossibility.
         * @return true only for authentic current cgroup zero, including a retained previously observed zero
         * @throws IOException if the barrier cannot be established */
        boolean zero() throws IOException;
        /** Closes original instrumentation only after actual host whole-tree zero.
         * @throws IOException if native instrumentation remains owned */
        void finishInstrumentation() throws IOException;
        /** Unregisters host sampling before removing containment storage; never closes the outer action owner.
         * @throws IOException if native cleanup remains incomplete */
        @Override void close() throws IOException;
    }
}
