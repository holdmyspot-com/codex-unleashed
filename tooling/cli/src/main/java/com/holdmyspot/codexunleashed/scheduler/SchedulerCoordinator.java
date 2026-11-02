package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Services one build's action protocol and queue on the constructing thread.
 * The session is supplied by the caller and remains reachable by that caller after failed cleanup.
 * This class neither discovers native actions nor substitutes a root build invocation for an action.
 * @param <R> original native whole-action result type
 */
final class SchedulerCoordinator<R>
{
    /** Maximum consecutive rejected batches before recovery reports ordinary failure. */
    static final int MAX_CONSECUTIVE_REJECTED_OBSERVATIONS = 32;
    /** Thread allowed to invoke collaborators. */
    private final Thread owner = Thread.currentThread();
    /** Retained outer owners and build-tool protocol. */
    private final Session<R> session;
    /** Validated physical reserve and live-attempt ceiling. */
    private final Policy.Budget budget;
    /** Bounded idle operation; it must not call back into this coordinator. */
    private final Idle idle;
    /** Owner-thread durable event boundary; failures use ordinary retained cleanup. */
    private final Observer observer;
    /** Owner-thread listener told about each policy wait; it cannot change scheduling. */
    private final WaitObserver waits;
    /** Prevents reuse after any attempted invocation. */
    private boolean attempted;
    /** Accepted payloads retained even if a later measurement, protocol or cleanup operation fails. */
    private List<JobQueue.Completion<R>> accepted = List.of();

    /**
     * Configures a resource-free coordinator; ownership begins only when run is called.
     * @param session caller-retained protocol and outer resource owner
     * @param budget physical reserve and concurrency ceiling
     * @param idle bounded idle operation
     */
    SchedulerCoordinator(Session<R> session, Policy.Budget budget, Idle idle)
    {
        this(session, budget, idle, (event, payload) -> {});
    }

    /**
     * Configures a coordinator with an invocation-owned durable observation boundary.
     * Records run synchronously on the constructing thread and must not reenter the coordinator.
     * @param session caller-retained protocol and outer resource owner
     * @param budget physical reserve and concurrency ceiling
     * @param idle bounded idle operation
     * @param observer durable event recorder; failure aborts through normal retained cleanup
     */
    SchedulerCoordinator(Session<R> session, Policy.Budget budget, Idle idle, Observer observer)
    {
        this(session, budget, idle, observer, (decision, snapshot) -> {});
    }

    /**
     * Configures a coordinator that also reports each policy wait so a caller can explain a stall.
     * @param session caller-retained protocol and outer resource owner
     * @param budget physical reserve and concurrency ceiling
     * @param idle bounded idle operation
     * @param observer durable event recorder; failure aborts through normal retained cleanup
     * @param waits owner-thread listener called before every policy idle wait; it must not throw
     */
    SchedulerCoordinator(Session<R> session, Policy.Budget budget, Idle idle, Observer observer, WaitObserver waits)
    {
        this.waits = Objects.requireNonNull(waits, "waits");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.session = Objects.requireNonNull(session, "session");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.idle = Objects.requireNonNull(idle, "idle");
    }

    /**
     * Runs once, publishing outcomes only after successful queue reconciliation.
     * An uncertain protocol delivery is never resent. Cleanup issues no invented completion.
     * On failure, stop the producer, close queue-owned attempts once, then release outer resources only
     * if no attempt remains owned. The caller must retain the session when that barrier cannot be proved.
     * Each cleanup stage temporarily clears a pending interrupt so bounded cleanup can collect its acknowledgement.
     * The original failure stays primary, cleanup failures remain suppressed, and interruption is restored afterward.
     * @return the actual completed build-tool root status supplied by the session
     * @throws IOException if protocol, measurements, controls or cleanup fail
     * @throws IllegalStateException if called again or from another thread
     */
    int run() throws IOException
    {
        if (Thread.currentThread() != owner || attempted)
            throw new IllegalStateException("Coordinator runs once on its constructing thread");
        attempted = true;
        JobQueue<R> queue = null;
        Throwable failure = null;
        int result = 0;
        try
        {
            queue = new JobQueue<>(budget, session.measurements(), session.attempts());
            int delivered = 0;
            int rejectedObservations = 0;
            while (true)
            {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Scheduler interrupted");
                session.pump();
                var notification = session.pollBlocked();
                while (notification.isPresent())
                {
                    Blocked blocked = notification.orElseThrow();
                    observer.record("blocked", blocked);
                    queue.allocationBlocked(blocked.name(), blocked.attemptSequence(), blocked.waitSequence());
                    notification = session.pollBlocked();
                }
                var input = session.pollSubmission();
                while (input.isPresent())
                {
                    Submission submission = input.orElseThrow();
                    observer.record("submission", submission);
                    queue.submit(submission.name(), submission.peakBytes());
                    input = session.pollSubmission();
                }
                Policy.Decision decision;
                try { decision = queue.cycle(); }
                catch (ObservationRejected rejected)
                {
                    if (++rejectedObservations >= MAX_CONSECUTIVE_REJECTED_OBSERVATIONS)
                        throw new IOException("Consecutive rejected memory observations exhausted recovery", rejected);
                    idle.await();
                    continue;
                }
                rejectedObservations = 0;
                if (decision.control() != Policy.Control.WAIT) observer.record("decision", decision);
                var outcomes = queue.completedAttempts();
                while (delivered < outcomes.size())
                {
                    JobQueue.Completion<R> completion = outcomes.get(delivered);
                    observer.record("completion-before", completion);
                    session.acceptOutcome(completion);
                    observer.record("completion-after", completion);
                    delivered = Math.incrementExact(delivered);
                }
                OptionalInt root = session.pollToolResult();
                boolean completed = session.inputClosed() && queue.jobs().isEmpty() &&
                    session.hasOpenAttempts() == false && root.isPresent();
                if (completed)
                {
                    result = root.getAsInt();
                    break;
                }
                if (decision.control() == Policy.Control.WAIT)
                {
                    waits.waiting(decision, queue.diagnostics());
                    idle.await();
                }
            }
        }
        catch (InterruptedException cause)
        {
            Thread.currentThread().interrupt();
            var interrupted = new InterruptedIOException("Scheduler interrupted");
            interrupted.initCause(cause);
            failure = interrupted;
        }
        catch (IOException | RuntimeException | Error cause) { failure = cause; }
        if (queue != null) accepted = queue.completedAttempts();
        boolean restoreInterrupt = Thread.interrupted();
        try
        {
            if (failure != null)
            {
                try { session.stopAccepting(); }
                catch (IOException | RuntimeException | Error cause) { failure = append(failure, cause); }
            }
            restoreInterrupt |= Thread.interrupted();
            if (queue != null)
                try { queue.close(); }
                catch (IOException | RuntimeException | Error cause) { failure = append(failure, cause); }
            restoreInterrupt |= Thread.interrupted();
            try
            {
                if (session.hasOpenAttempts())
                    throw new IOException("Attempt cleanup remains unresolved; retain the session and its dependencies");
                session.close();
                if (failure == null) observer.record("terminal", result);
            }
            catch (IOException | RuntimeException | Error cause) { failure = append(failure, cause); }
        }
        finally
        {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
        if (failure instanceof IOException cause) throw cause;
        if (failure instanceof RuntimeException cause) throw cause;
        if (failure instanceof Error cause) throw cause;
        return result;
    }

    /**
     * Returns accepted native outcomes, including those withheld from unsafe protocol publication.
     * The snapshot remains available after success or failure and invents no result during cleanup.
     * @return immutable accepted attempt outcomes
     * @throws IllegalStateException if called from another thread
     */
    List<JobQueue.Completion<R>> acceptedAttempts()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Coordinator outcomes belong to its constructing thread");
        return accepted;
    }

    /**
     * Preserves the first failure and distinct later cleanup failures.
     * @param first original failure, or null
     * @param later later failure
     * @return the original failure, or later when no original exists
     */
    private static Throwable append(Throwable first, Throwable later)
    {
        if (first == null) return later;
        if (first != later) first.addSuppressed(later);
        return first;
    }

    /**
     * A discovered, fully registered action; queue submission reserves its original discovery position.
     * The adapter must retain its immutable launch input before returning this value.
     * @param name nonblank stable identity, reserved for this invocation even after completion
     * @param peakBytes nonnegative caller-supplied estimate, or null for unknown demand
     */
    record Submission(String name, Long peakBytes)
    {
        /** Validates identity and preserves unknown demand separately from zero. */
        Submission
        {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || (peakBytes != null && peakBytes < 0))
                throw new IllegalArgumentException("Submission requires a nonblank name and nonnegative or unknown demand");
        }
    }

    /**
     * Copied notification delivered outside queue controls.
     * @param name stable submitted action name
     * @param attemptSequence current queue attempt identity
     * @param waitSequence monotonic per-attempt allocation batch identity
     */
    record Blocked(String name, long attemptSequence, long waitSequence)
    {
        /** Validates the three independent correlation fields. */
        Blocked
        {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || attemptSequence < 0 || waitSequence < 0)
                throw new IllegalArgumentException("Blocked notification requires an action and nonnegative ordinals");
        }
    }

    /**
     * Synchronous owner-thread observation boundary for durable invocation records.
     * Event payloads retain the exact immutable submission, blocked, decision and native completion records.
     * The terminal payload is the authentic integer root status after all cleanup has succeeded.
     */
    @FunctionalInterface
    interface Observer
    {
        /**
         * Records one event before its associated boundary is allowed to proceed.
         * @param event submission, blocked, decision, completion-before, completion-after or terminal
         * @param payload exact original immutable payload, or authentic terminal root status
         * @throws IOException if the event cannot be durably recorded
         */
        void record(String event, Object payload) throws IOException;
    }

    /**
     * Learns of each policy wait with the queue state that explains it.
     * Calls run on the constructing thread, must not reenter the coordinator and must not throw.
     */
    @FunctionalInterface
    interface WaitObserver
    {
        /**
         * Records one policy wait, immediately before the idle operation.
         * @param decision the waiting decision and its reason
         * @param snapshot the queue state at this wait
         */
        void waiting(Policy.Decision decision, JobQueue.Snapshot snapshot);
    }

    /** Bounded cooperative wait after policy WAIT or a rejected memory observation. */
    @FunctionalInterface
    interface Idle
    {
        /** Waits for the bounded idle interval.
         * @throws InterruptedException if cancellation interrupts the wait
         * @throws IOException if waiting fails
         */
        void await() throws InterruptedException, IOException;
    }

    /**
     * Package-private integration boundary for authentic Cargo/Bazel action producers.
     * Every operation is owner-thread confined and must not call JobQueue. Async callbacks may only copy events.
     * The session owns the root build tool, protocol and outer dependencies; the queue owns returned attempts.
     * Native adapters must establish action/result authority before implementing this boundary.
     * @param <R> unchanged native whole-action result payload
     */
    interface Session<R> extends AutoCloseable
    {
        /** Supplies the configured action factory without acquiring an attempt.
         * @return borrowed resource-free attempt factory with registered immutable action inputs */
        JobQueue.AttemptFactory<R> attempts();
        /** Supplies complete memory acquisition for this invocation.
         * @return borrowed fresh complete host/tree measurement provider */
        Controller.Measurements measurements();
        /** Services producers and native peers without queue reentrancy.
         * @throws IOException if event handling fails
         */
        void pump() throws IOException;
        /** Consumes one registered action in original discovery order.
         * @return oldest newly registered action, or empty
         * @throws IOException if discovery cannot establish an authentic action
         */
        Optional<Submission> pollSubmission() throws IOException;
        /** Consumes one copied allocation notification outside queue controls.
         * @return oldest copied allocation batch, or empty
         * @throws IOException if correlation fails
         */
        Optional<Blocked> pollBlocked() throws IOException;
        /** Records every accepted attempt; only non-aborted outcomes may answer action requests.
         * A failed delivery may have effects and must not be automatically resent.
         * @param completion original payload and action/attempt identity
         * @throws IOException if receipt or action-response publication fails
         */
        void acceptOutcome(JobQueue.Completion<R> completion) throws IOException;
        /** Checks that discovery and its buffers are terminal.
         * @return whether no future action can arrive, including buffered discoveries */
        boolean inputClosed();
        /** Observes the root tool without substituting its exit for action completion.
         * @return authentic completed root-tool status, or empty; root-only exit does not finish actions
         * @throws IOException if the root result cannot be established
         */
        OptionalInt pollToolResult() throws IOException;
        /** Checks whether outer dependencies must remain available.
         * @return whether any platform attempt retains ownership, including failed cleanup */
        boolean hasOpenAttempts();
        /** Stops further producer actions while retaining existing owners and borrowed endpoints.
         * @throws IOException if production cannot be stopped
         */
        void stopAccepting() throws IOException;
        /** Reaps root/wrapper consumers before lease release and outer runtime disposal.
         * Must retain any unresolved ownership and reject unsafe dependency closure.
         * @throws IOException if complete cleanup cannot be established
         */
        @Override void close() throws IOException;
    }
}
