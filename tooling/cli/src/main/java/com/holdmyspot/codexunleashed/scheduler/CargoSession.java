package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Retains authenticated compiler requests while one coordinator owns their attempts and retry order.
 * The producer must establish native identity, streams and result authority before registration. This owner supplies
 * no authentication transport or native launch implementation. All operations belong to the constructing thread.
 * The invocation owns wrappers, streams and the single Cargo lease through retries and whole-session cleanup;
 * queue-owned attempts borrow those resources and cannot close them.
 * @param <R> unchanged native whole-action result payload
 */
final class CargoSession<R> implements SchedulerCoordinator.Session<R>
{
    /** Sole mutation thread. */
    private final Thread owner = Thread.currentThread();
    /** Borrowed shared platform factory, preserving peer pumping across all actions. */
    private final JobQueue.AttemptFactory<R> factory;
    /** Borrowed complete host/tree observations. */
    private final Controller.Measurements measurements;
    /** Resource-free platform command registration. */
    private final Registration registration;
    /** Root Cargo, wrappers, transport and invocation-wide dependency owner. */
    private final Invocation invocation;
    /** Stable requests retained even after completion or uncertain publication. */
    private final Map<String, Pending<R>> requests = new LinkedHashMap<>();
    /** Fully registered submissions in original acceptance order. */
    private final ArrayDeque<SchedulerCoordinator.Submission> submissions = new ArrayDeque<>();
    /** Attempt identity ledger retained after native cleanup and retry. */
    private final Map<Long, Attempt> attempts = new LinkedHashMap<>();
    /** Accepted outcomes, including aborted attempts and uncertain final responses. */
    private final List<JobQueue.Completion<R>> outcomes = new ArrayList<>();
    /** Ordinals of outcomes already consumed, independently of response success. */
    private final java.util.Set<Long> completed = new java.util.HashSet<>();
    /** Next original discovery position. */
    private long nextPosition;
    /** Highest global attempt ordinal, never reused after failure. */
    private long lastAttempt = -1;
    /** First uncertain registration, delivery or producer failure. */
    private IOException fault;
    /** Prevents collaborator reentry from reversing reserved submission positions. */
    private boolean registering;
    /** Whether new requests are forbidden. */
    private boolean stopping;
    /** Whether producer stop has already been attempted. */
    private boolean stopAttempted;
    /** Whether outer close has already been attempted. */
    private boolean closeAttempted;
    /** Successful terminal outer cleanup. */
    private boolean closed;

    /**
     * Borrows resource-free collaborators; no process, transport or lease is created.
     * @param factory shared platform factory configured by registration
     * @param measurements complete observations for this invocation
     * @param registration appends platform command inputs before submission
     * @param invocation root/wrapper and outer dependency owner
     */
    CargoSession(JobQueue.AttemptFactory<R> factory, Controller.Measurements measurements,
        Registration registration, Invocation invocation)
    {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.measurements = Objects.requireNonNull(measurements, "measurements");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    /**
     * Appends an already authenticated action once, before exposing it to the queue.
     * A registration failure may have effects, so the request remains retained and the session stops accepting work.
     * @param request immutable independently authenticated request
     * @param response borrowed final-response endpoint, retained through retries
     * @throws IOException if registration has uncertain effects or the session already failed
     * @throws IllegalArgumentException if the identity is already reserved
     */
    void registerAuthenticated(CargoCompilerRequest request, Response<R> response) throws IOException
    {
        requireActive();
        if (registering) throw new IllegalStateException("Compiler registration cannot reenter its collaborator");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (requests.containsKey(request.name())) throw new IllegalArgumentException("Compiler identity is already reserved");
        long successor = Math.incrementExact(nextPosition);
        requests.put(request.name(), new Pending<>(request, response));
        long position = nextPosition;
        nextPosition = successor;
        registering = true;
        try { registration.register(request, position); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Compiler registration is uncertain", cause); }
        finally { registering = false; }
        submissions.addLast(new SchedulerCoordinator.Submission(request.name(), request.peakBytes()));
    }

    /**
     * Marks an authenticated wrapper disappearance as whole-session failure; no queue cancellation API is invented.
     * @param name registered wrapper's stable action name
     * @throws IOException always, after retaining the fault and preventing new work
     */
    void wrapperDisconnected(String name) throws IOException
    {
        requireActive();
        if (!requests.containsKey(name)) throw new IllegalArgumentException("Unknown compiler wrapper");
        throw failed("Compiler wrapper disconnected: " + name, null);
    }

    @Override public JobQueue.AttemptFactory<R> attempts() { requireOwner(); return this::create; }
    @Override public Controller.Measurements measurements() { requireOwner(); return measurements; }

    /**
     * Records distinct global and per-action ordinals before invoking the shared resource-free factory.
     * @param name registered stable action name
     * @param sequence global queue attempt ordinal
     * @return exclusively queue-owned resource-free attempt
     */
    private JobQueue.Attempt<R> create(String name, long sequence)
    {
        requireOwner();
        if (fault != null || stopping || closed) throw new IllegalStateException("Cargo session cannot create attempts", fault);
        Pending<R> pending = requests.get(name);
        if (pending == null || pending.responseAttempted || sequence < 0 || sequence <= lastAttempt)
            throw new IllegalArgumentException("Unknown action, answered request or reused global attempt ordinal");
        long local = Math.incrementExact(pending.attempts);
        pending.attempts = local;
        lastAttempt = sequence;
        attempts.put(sequence, new Attempt(pending.request, sequence, local));
        return Objects.requireNonNull(factory.create(name, sequence), "factory attempt");
    }

    /**
     * Returns the retained exact request and both independent ordinals for an issued attempt.
     * This receipt grants no native authority and remains available after failure for diagnosis.
     * @param sequence global queue attempt ordinal
     * @return issued attempt receipt, or empty
     */
    Optional<Attempt> attempt(long sequence) { requireOwner(); return Optional.ofNullable(attempts.get(sequence)); }

    /**
     * Returns retained accepted native outcomes, including an outcome whose final response was uncertain.
     * @return immutable outcome snapshot
     */
    List<JobQueue.Completion<R>> acceptedOutcomes() { requireOwner(); return List.copyOf(outcomes); }

    @Override public void pump() throws IOException
    {
        requireActive();
        try { invocation.pump(); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Cargo producer service failed", cause); }
    }

    @Override public Optional<SchedulerCoordinator.Submission> pollSubmission() throws IOException
    {
        requireActive();
        return Optional.ofNullable(submissions.pollFirst());
    }

    @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException
    {
        requireActive();
        try { return invocation.pollBlocked(); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Cargo allocation event service failed", cause); }
    }

    @Override public void acceptOutcome(JobQueue.Completion<R> completion) throws IOException
    {
        requireActive();
        Objects.requireNonNull(completion, "completion");
        Attempt issued = attempts.get(completion.attemptSequence());
        if (issued == null || !issued.request().name().equals(completion.name()) || completed.contains(completion.attemptSequence()))
            throw new IllegalArgumentException("Outcome does not match an unconsumed issued attempt");
        Pending<R> pending = requests.get(completion.name());
        if (pending.responseAttempted) throw new IllegalArgumentException("Compiler request already answered");
        completed.add(completion.attemptSequence());
        outcomes.add(completion);
        if (completion.outcome().aborted()) return;
        pending.responseAttempted = true;
        try { pending.response.send(completion.outcome().result()); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Compiler final response is uncertain", cause); }
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
        catch (IOException | RuntimeException | Error cause) { throw failed("Cargo root observation failed", cause); }
    }
    @Override public boolean hasOpenAttempts() { requireOwner(); return invocation.hasOpenAttempts(); }

    @Override public void stopAccepting() throws IOException
    {
        requireOwner();
        stopping = true;
        if (stopAttempted) return;
        stopAttempted = true;
        try { invocation.stopAccepting(); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Cargo producer stop is uncertain", cause); }
    }

    @Override public void close() throws IOException
    {
        requireOwner();
        if (closed) return;
        if (invocation.hasOpenAttempts()) throw new IOException("Cargo attempts still retain outer dependencies");
        if (closeAttempted) throw new IOException("Cargo outer cleanup remains uncertain", fault);
        stopping = true;
        closeAttempted = true;
        try { invocation.close(); }
        catch (IOException | RuntimeException | Error cause) { throw failed("Cargo outer cleanup is uncertain", cause); }
        closed = true;
    }

    /**
     * Latches a potentially effective failure without retrying or discarding its ownership.
     * @param message failed boundary
     * @param cause original cause, or null for authenticated disconnection
     * @return first retained failure
     */
    private IOException failed(String message, Throwable cause)
    {
        stopping = true;
        if (fault == null) fault = new IOException(message, cause);
        else if (cause != null && cause != fault) fault.addSuppressed(cause);
        return fault;
    }

    /** Rejects wrong-thread access before touching collaborators. */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Cargo session requires its constructing thread");
    }
    /**
     * Requires healthy active ownership before production or final response operations.
     * @throws IOException if a prior operation failed or discovery has stopped
     */
    private void requireActive() throws IOException
    {
        requireOwner();
        if (fault != null) throw fault;
        if (stopping || closed) throw new IOException("Cargo session is no longer accepting operations");
    }

    /**
     * Relates a global queue ordinal to its independent positive action-local retry ordinal.
     * @param request stable immutable invocation
     * @param globalSequence global nonnegative queue attempt ordinal
     * @param actionSequence positive ordinal for this action only
     */
    record Attempt(CargoCompilerRequest request, long globalSequence, long actionSequence) {}

    /** Resource-free append-only platform command registration. */
    @FunctionalInterface
    interface Registration
    {
        /**
         * Registers exact launch inputs before corresponding queue submission.
         * @param request already authenticated immutable request
         * @param position original queue submission position
         * @throws IOException if registration may have partial effects
         */
        void register(CargoCompilerRequest request, long position) throws IOException;
    }
    /**
     * Borrowed wrapper response endpoint; the invocation retains its lifetime and reaps the wrapper.
     * @param <R> unchanged native result payload
     */
    @FunctionalInterface
    interface Response<R>
    {
        /**
         * Publishes one actual final native result; an exception may follow successful publication.
         * @param result unchanged non-aborted native result
         * @throws IOException if delivery is uncertain
         */
        void send(R result) throws IOException;
    }
    /**
     * Supplies authentic root/peer service and owns consumers, transport, streams and one invocation lease.
     * Implementations copy callbacks and never invoke JobQueue; native integrations remain separately gated.
     */
    interface Invocation extends AutoCloseable
    {
        /** Services wrapper registrations and platform peers on the coordinator thread.
         * @throws IOException if producer service fails */
        void pump() throws IOException;
        /** Consumes one copied native allocation notification outside controls.
         * @return next copied notification, or empty
         * @throws IOException if native correlation fails */
        Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException;
        /** Reports terminal discovery including all buffered wrapper arrivals.
         * @return whether future registrations are impossible */
        boolean inputClosed();
        /** Observes real Cargo completion without substituting it for compiler completion.
         * @return actual root status, or empty while incomplete
         * @throws IOException if observation fails */
        OptionalInt pollToolResult() throws IOException;
        /** Reports platform owners, including failed cleanup.
         * @return whether queue-owned attempts remain retained */
        boolean hasOpenAttempts();
        /** Stops new arrivals without closing borrowed endpoints used by existing attempts.
         * @throws IOException if stop has uncertain effects */
        void stopAccepting() throws IOException;
        /** Reaps every root/wrapper consumer before stream, jobserver lease and runtime closure.
         * Unresolved cleanup must retain its owners and outer dependencies.
         * @throws IOException if complete cleanup cannot be established */
        @Override void close() throws IOException;
    }
    /**
     * Retains a request and its borrowed endpoint across every attempt.
     * @param <R> unchanged native result payload
     */
    private static final class Pending<R>
    {
        /** Stable immutable launch and peer receipt. */
        private final CargoCompilerRequest request;
        /** Borrowed final-response endpoint. */
        private final Response<R> response;
        /** Positive action-local attempt counter. */
        private long attempts;
        /** Set before fallible publication, preventing every resend. */
        private boolean responseAttempted;
        /**
         * Retains registration inputs without acquiring resources.
         * @param request immutable invocation
         * @param response borrowed endpoint
         */
        Pending(CargoCompilerRequest request, Response<R> response) { this.request = request; this.response = response; }
    }
}
