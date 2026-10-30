package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Rejects a complete memory batch after a trusted ownership change was independently established.
 * Reconcile authentic owners and obtain a fresh complete batch before selecting another control.
 * This exception conveys neither a measurement nor a completion or control acknowledgement.
 */
public final class ObservationRejected extends IOException
{
    /** Measurement boundary that rejected the batch. */
    private final Operation operation;
    /** Independently established change that invalidated the observation. */
    private final Reason reason;
    /** Exact registered attempts affected by the change. */
    private final List<Attempt> attempts;

    /** Observation boundaries; ACCEPT includes final validation of a staged batch. */
    public enum Operation
    {
        /** Host acquisition. */ HOST,
        /** Owned-tree residency acquisition. */ RESIDENT,
        /** Complete-batch acceptance. */ ACCEPT
    }

    /** Trusted changes which invalidate a batch without supplying substitute values. */
    public enum Reason
    {
        /** Registered Linux leaves disappeared and every retained owner proved execution impossible. */ COMPLETED_LEAVES,
        /** Complete Windows Job observations established membership churn. */ MEMBERSHIP_CHANGED
    }

    /**
     * Identifies one registered action attempt, independently of its native allocation waits.
     * @param action nonblank stable action identity
     * @param attemptSequence nonnegative queue attempt ordinal
     */
    public record Attempt(String action, long attemptSequence)
    {
        /** Validates the exact action and attempt correlation fields. */
        public Attempt
        {
            Objects.requireNonNull(action, "action");
            if (action.isBlank() || attemptSequence < 0)
                throw new IllegalArgumentException("Observation requires a nonblank action and nonnegative attempt");
        }
    }

    /**
     * Retains an independently verified observation rejection.
     * Only measurement providers may issue this retry permission after successful observation cleanup.
     * @param operation failed measurement boundary
     * @param reason verified ownership change
     * @param attempts nonempty distinct exact registered attempts
     * @param cause retained diagnostic cause
     */
    ObservationRejected(Operation operation, Reason reason, List<Attempt> attempts, IOException cause)
    {
        super("Memory observation rejected: " + reason, Objects.requireNonNull(cause, "cause"));
        this.operation = Objects.requireNonNull(operation, "operation");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.attempts = List.copyOf(attempts);
        if (this.attempts.isEmpty() || new HashSet<>(this.attempts).size() != this.attempts.size())
            throw new IllegalArgumentException("Observation requires nonempty distinct attempt identities");
    }

    /**
     * Returns the failed measurement boundary.
     * @return rejected measurement boundary
     */
    public Operation operation() { return operation; }
    /**
     * Returns the independently verified ownership change.
     * @return ownership change which invalidated this batch
     */
    public Reason reason() { return reason; }
    /**
     * Returns every affected registered attempt without supplying completion authority.
     * @return immutable exact registered attempt identities
     */
    public List<Attempt> attempts() { return attempts; }
}
