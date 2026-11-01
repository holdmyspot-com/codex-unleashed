package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Retains authenticated wrapped Bazel actions in discovery order while the
 * coordinator owns their attempts. This session supplies no authentication,
 * process launch or status-derived retry authority. Each action retains its
 * original control connection across retries; the invocation owns the original
 * Bazel root and outer dependencies. All calls belong to the constructing thread.
 */
final class LinuxBazelSession implements SchedulerCoordinator.Session<LinuxBazelControl.Result>
{
    /** Sole thread allowed to mutate this invocation. */
    private final Thread owner = Thread.currentThread();
    /** Borrowed fresh complete memory observations. */
    private final Controller.Measurements measurements;
    /** Original Bazel root, discovery and outer cleanup owner. */
    private final Invocation invocation;
    /** Accepted action owners retained after success or uncertainty. */
    private final Map<String, Entry> actions = new LinkedHashMap<>();
    /** Newly accepted immutable submissions in original discovery order. */
    private final ArrayDeque<SchedulerCoordinator.Submission> submissions = new ArrayDeque<>();
    /** Issued global attempts retained through closed-attempt outcome delivery. */
    private final Map<Long, Issued> issued = new LinkedHashMap<>();
    /** Highest reserved global attempt ordinal; failed reservations are not reused. */
    private long lastSequence = -1;
    /** First uncertain effect, retained permanently to prevent automatic replay. */
    private IOException fault;
    /** Indicates whether registration is reading action metadata. */
    private boolean registering;
    /** Indicates whether new production operations are forbidden. */
    private boolean stopping;
    /** Indicates whether stopping the invocation has already been attempted. */
    private boolean stopAttempted;
    /** Indicates whether outer cleanup has already been attempted. */
    private boolean closeAttempted;
    /** Indicates whether every action and the outer invocation closed successfully. */
    private boolean closed;

    /**
     * Borrows measurements and retains the invocation without acquiring processes.
     * @param measurements complete host and owned-tree observation provider
     * @param invocation original Bazel discovery/root and outer cleanup owner
     * @throws NullPointerException if either argument is null
     */
    LinuxBazelSession(Controller.Measurements measurements, Invocation invocation)
    {
        this.measurements = Objects.requireNonNull(measurements, "measurements");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    /**
     * Retains one already authenticated action before exposing its submission.
     * Native identity, original argv/env/cwd, containment and result authority
     * must already be established by the producer. Metadata is copied once.
     * Ownership transfers on successful return; rejected actions remain caller-owned.
     * @param action independently authenticated wrapped action and control owner
     * @throws NullPointerException if {@code action} is null
     * @throws IllegalArgumentException if its name/demand is invalid or reserved
     * @throws IllegalStateException if registration reenters or uses another thread
     * @throws IOException if discovery stopped or an earlier operation failed
     */
    void registerAuthenticated(Action action) throws IOException
    {
        requireActive();
        Objects.requireNonNull(action, "action");
        if (registering) throw new IllegalStateException("Bazel action registration cannot reenter");
        registering = true;
        try
        {
            SchedulerCoordinator.Submission submission = new SchedulerCoordinator.Submission(action.name(), action.peakBytes());
            if (actions.containsKey(submission.name()))
                throw new IllegalArgumentException("Bazel action name is already reserved");
            Entry entry = new Entry(action, submission);
            actions.put(submission.name(), entry);
            submissions.addLast(submission);
        }
        finally { registering = false; }
    }

    @Override public JobQueue.AttemptFactory<LinuxBazelControl.Result> attempts()
    {
        requireOwner();
        return this::create;
    }

    @Override public Controller.Measurements measurements()
    {
        requireOwner();
        return measurements;
    }

    /**
     * Reserves a strictly increasing global identity before calling the action's
     * resource-free factory. A closed aborted attempt may be retried before its
     * earlier completion is delivered; an ordinary collected attempt cannot.
     * @param name retained action identity
     * @param sequence nonnegative queue-wide ordinal greater than every reservation
     * @return queue-owned attempt recording exact observed outcome and closure
     * @throws IllegalArgumentException if the identity, sequence or lifecycle is invalid
     * @throws IllegalStateException if the session failed, stopped, or factory failed
     */
    private JobQueue.Attempt<LinuxBazelControl.Result> create(String name, long sequence)
    {
        requireOwner();
        if (fault != null || stopping || closed)
            throw new IllegalStateException("Bazel session cannot create attempts", fault);
        Entry entry = actions.get(name);
        boolean available = entry != null && !entry.ordinaryCollected &&
            (entry.current == null || (entry.current.closeSucceeded &&
                entry.current.outcome != null && entry.current.outcome.aborted()));
        if (!available || sequence < 0 || sequence <= lastSequence)
            throw new IllegalArgumentException("Bazel factory requires an available action and a fresh global ordinal");
        Issued attempt = new Issued(entry, sequence);
        lastSequence = sequence;
        entry.current = attempt;
        issued.put(sequence, attempt);
        try
        {
            attempt.delegate = Objects.requireNonNull(entry.action.create(sequence), "action attempt");
            return attempt;
        }
        catch (RuntimeException | Error cause)
        {
            failed("Bazel action factory failed after reserving an attempt", cause);
            throw cause;
        }
    }

    @Override public void pump() throws IOException
    {
        requireActive();
        try
        {
            invocation.pump();
            for (Entry entry : List.copyOf(actions.values())) entry.action.pump();
        }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel producer or action pump failed", cause);
        }
    }

    @Override public Optional<SchedulerCoordinator.Submission> pollSubmission() throws IOException
    {
        requireActive();
        return Optional.ofNullable(submissions.pollFirst());
    }

    @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException
    {
        requireActive();
        try
        {
            for (Entry entry : actions.values())
            {
                OptionalLong blocked = entry.action.pollBlocked();
                if (blocked.isEmpty()) continue;
                long wait = blocked.getAsLong();
                Issued current = entry.current;
                boolean correlated = current != null && !current.closeAttempted &&
                    current.outcome == null && wait >= 0 && wait > current.lastWait;
                if (!correlated) throw new IOException("Bazel allocation batch does not match a live attempt and fresh wait ordinal");
                current.lastWait = wait;
                return Optional.of(new SchedulerCoordinator.Blocked(entry.submission.name(), current.sequence, wait));
            }
            return Optional.empty();
        }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel allocation event correlation failed", cause);
        }
    }

    @Override public void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) throws IOException
    {
        requireActive();
        Objects.requireNonNull(completion, "completion");
        Issued attempt = issued.get(completion.attemptSequence());
        boolean matches = attempt != null && !attempt.consumed && attempt.closeSucceeded &&
            attempt.entry.submission.name().equals(completion.name()) &&
            completion.outcome().equals(attempt.outcome);
        if (!matches) throw new IllegalArgumentException("Bazel completion must match one unconsumed collected and closed issued attempt");
        attempt.consumed = true;
        try { attempt.entry.action.acceptOutcome(completion); }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel accepted-outcome delivery is uncertain", cause);
        }
    }

    @Override public boolean inputClosed()
    {
        requireOwner();
        return submissions.isEmpty() && invocation.inputClosed();
    }

    @Override public OptionalInt pollToolResult() throws IOException
    {
        requireActive();
        try { return invocation.pollToolResult(); }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel original root result observation failed", cause);
        }
    }

    @Override public boolean hasOpenAttempts()
    {
        requireOwner();
        for (Issued attempt : issued.values())
            if (attempt.delegate != null && !attempt.closeSucceeded) return true;
        for (Entry entry : actions.values())
            if (entry.action.hasOpenAttempt()) return true;
        return false;
    }

    @Override public void stopAccepting() throws IOException
    {
        requireOwner();
        stopping = true;
        if (stopAttempted || closed) return;
        stopAttempted = true;
        try { invocation.stopAccepting(); }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel producer stop is uncertain", cause);
        }
    }

    @Override public void close() throws IOException
    {
        requireOwner();
        if (closed) return;
        if (hasOpenAttempts()) throw new IOException("Bazel attempts still retain outer dependencies");
        if (closeAttempted) throw new IOException("Bazel outer cleanup remains uncertain and cannot be replayed", fault);
        stopping = true;
        closeAttempted = true;
        IOException cleanupFailure = null;
        for (Entry entry : actions.values())
        {
            try { entry.action.close(); }
            catch (IOException | RuntimeException | Error cause)
            {
                IOException failure = failed("Bazel action-control cleanup is uncertain", cause);
                if (cleanupFailure == null) cleanupFailure = failure;
            }
        }
        if (cleanupFailure != null) throw cleanupFailure;
        try { invocation.close(); }
        catch (IOException | RuntimeException | Error cause)
        {
            throw failed("Bazel outer invocation cleanup is uncertain", cause);
        }
        closed = true;
    }

    /**
     * Latches the first potentially effective failure and retains later causes.
     * @param message exact failed boundary
     * @param cause original operation failure
     * @return original sticky failure
     */
    private IOException failed(String message, Throwable cause)
    {
        stopping = true;
        if (fault == null) fault = new IOException(message, cause);
        else if (cause != fault) fault.addSuppressed(cause);
        return fault;
    }

    /**
     * Rejects access from a different thread before touching collaborators.
     * @throws IllegalStateException if called outside the constructing thread
     */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Bazel session requires its constructing thread");
    }

    /**
     * Requires healthy active production ownership.
     * @throws IOException if a previous operation failed or production stopped
     */
    private void requireActive() throws IOException
    {
        requireOwner();
        if (fault != null) throw fault;
        if (stopping || closed) throw new IOException("Bazel session is no longer accepting operations");
    }

    /**
     * Owns one independently authenticated wrapped action's native control and
     * closed-attempt ledger. Methods may not call JobQueue. Queue-owned attempts
     * borrow this control; close retains it until every attempt has released it.
     */
    interface Action extends AutoCloseable
    {
        /**
         * Supplies the immutable invocation-local action identity.
         * @return nonblank stable name reserved through cleanup
         */
        String name();
        /**
         * Supplies the original admission estimate without inventing demand.
         * @return nonnegative estimated peak bytes, or null when unknown
         */
        Long peakBytes();
        /**
         * Creates a resource-free owner for one reserved global attempt.
         * @param sequence nonnegative strictly increasing queue-wide ordinal
         * @return fresh nonnull attempt whose ownership transfers to the queue
         */
        JobQueue.Attempt<LinuxBazelControl.Result> create(long sequence);
        /**
         * Services native peers and copies allocation batches outside queue controls.
         * @throws IOException if action servicing fails
         */
        void pump() throws IOException;
        /**
         * Consumes one copied allocation batch for the current attempt.
         * @return fresh nonnegative per-attempt wait ordinal, or empty
         * @throws IOException if the native batch cannot be established
         */
        OptionalLong pollBlocked() throws IOException;
        /**
         * Indicates whether any attempt still retains native resources or uncertainty.
         * @return true while outer control must remain available
         */
        boolean hasOpenAttempt();
        /**
         * Consumes the exact collected closed-attempt receipt at most once.
         * An earlier aborted receipt may arrive after a new retry was created.
         * RESET/FINAL and their authority remain this action's responsibility;
         * an aborted payload never authorizes fabrication of a final response.
         * @param completion exact issued identity and authoritative outcome
         * @throws IOException if receipt acceptance has uncertain effects
         */
        void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) throws IOException;
        /**
         * Reaps action consumers and closes retained control after all attempts.
         * @throws IOException if complete cleanup cannot be established
         */
        @Override void close() throws IOException;
    }

    /**
     * Owns genuine Bazel root discovery and all invocation-wide dependencies.
     * Root-only exit does not complete actions; callbacks only copy native events.
     */
    interface Invocation extends AutoCloseable
    {
        /**
         * Services discovery, registering authenticated action owners before submission.
         * @throws IOException if genuine discovery or root service fails
         */
        void pump() throws IOException;
        /**
         * Indicates whether native discovery is terminal with no future arrivals.
         * @return true when no future owner can be produced
         */
        boolean inputClosed();
        /**
         * Observes the original completed Bazel root without inferring action results.
         * @return authentic root exit status, or empty before collection
         * @throws IOException if the root result cannot be established
         */
        OptionalInt pollToolResult() throws IOException;
        /**
         * Stops new producers while preserving retained consumers and controls.
         * @throws IOException if stopping has uncertain effects
         */
        void stopAccepting() throws IOException;
        /**
         * Reaps the original tool and releases invocation-wide dependencies.
         * @throws IOException if complete cleanup cannot be established
         */
        @Override void close() throws IOException;
    }

    /**
     * Retains copied submission metadata and the original authenticated action.
     */
    private static final class Entry
    {
        /** Authenticated action owner retained through retries and cleanup. */
        private final Action action;
        /** Original immutable admission metadata. */
        private final SchedulerCoordinator.Submission submission;
        /** Most recently issued attempt, or null before the first. */
        private Issued current;
        /** Indicates whether an ordinary whole-job outcome forbids further retries. */
        private boolean ordinaryCollected;

        /**
         * Retains validated immutable metadata alongside its authoritative owner.
         * @param action authenticated action controlling the original waiter
         * @param submission copied validated admission metadata
         */
        private Entry(Action action, SchedulerCoordinator.Submission submission)
        {
            this.action = Objects.requireNonNull(action, "action");
            this.submission = Objects.requireNonNull(submission, "submission");
        }
    }

    /**
     * Records one queue-owned attempt's observed outcome and successful closure.
     * Native effects remain in the action's delegate; this wrapper supplies only
     * the session's exact completion correlation and outer dependency barrier.
     */
    private final class Issued implements JobQueue.Attempt<LinuxBazelControl.Result>
    {
        /** Authenticated action and original admission metadata. */
        private final Entry entry;
        /** Globally reserved attempt identity. */
        private final long sequence;
        /** Queue-owned native delegate, or null after a failed resource-free create. */
        private JobQueue.Attempt<LinuxBazelControl.Result> delegate;
        /** Original whole-job outcome returned by native poll, or null. */
        private JobQueue.Outcome<LinuxBazelControl.Result> outcome;
        /** Last consumed per-attempt allocation wait, or -1 before any. */
        private long lastWait = -1;
        /** Indicates whether the queue's sole close operation was attempted. */
        private boolean closeAttempted;
        /** Indicates whether the original close completed successfully. */
        private boolean closeSucceeded;
        /** Indicates whether completion delivery was attempted, including uncertainty. */
        private boolean consumed;

        /**
         * Reserves a validated global identity before native factory invocation.
         * @param entry retained authenticated action
         * @param sequence fresh nonnegative global ordinal
         */
        private Issued(Entry entry, long sequence)
        {
            this.entry = Objects.requireNonNull(entry, "entry");
            if (sequence < 0) throw new IllegalArgumentException("Attempt ordinal must be nonnegative");
            this.sequence = sequence;
        }

        @Override public void start() throws IOException { requireOwner(); delegate.start(); }
        @Override public void resume(long waitSequence) throws IOException { requireOwner(); delegate.resume(waitSequence); }
        @Override public boolean requiresRetryAfterDenial()
        {
            requireOwner();
            return delegate.requiresRetryAfterDenial();
        }
        @Override public void terminate() throws IOException { requireOwner(); delegate.terminate(); }
        @Override public Optional<JobQueue.Outcome<LinuxBazelControl.Result>> poll() throws IOException
        {
            requireOwner();
            JobQueue.Outcome<LinuxBazelControl.Result> observed = delegate.poll().orElse(null);
            if (outcome != null && !outcome.equals(observed))
                throw failed("Bazel attempt changed an already accepted whole-job outcome", new IOException("Native outcome changed"));
            if (observed != null)
            {
                outcome = observed;
                if (!observed.aborted()) entry.ordinaryCollected = true;
            }
            return Optional.ofNullable(outcome);
        }

        @Override public void close() throws IOException
        {
            requireOwner();
            if (closeSucceeded) return;
            if (closeAttempted) throw new IOException("Bazel attempt cleanup is uncertain and cannot be replayed");
            closeAttempted = true;
            delegate.close();
            closeSucceeded = true;
        }
    }
}
