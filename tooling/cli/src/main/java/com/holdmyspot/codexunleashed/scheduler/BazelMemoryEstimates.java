package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Attaches content-based predictions to distinct logical queue entries and learns from accepted successful attempts.
 * All calls belong to the invocation's coordination thread. The caller freezes registered input snapshots throughout
 * the logical work, validates original success plus whole-containment zero, then reports completion. Retried attempts
 * retain the original logical name and prediction; their measurements never mix. Missing history remains null demand.
 */
final class BazelMemoryEstimates implements Controller.Measurements
{
    /** Borrowed source of complete native host and whole-containment measurement batches. */
    private final Controller.Measurements measurements;
    /** Borrowed storage of compatible successful completed measurements. */
    private final BazelMemoryHistory history;
    /** Reusable content identity and submitted prediction indexed by invocation-local host-issued logical name. */
    private final Map<String, Work> work = new HashMap<>();
    /** Greatest accepted residency for each distinct native attempt. */
    private final Map<Attempt, Long> accepted = new HashMap<>();
    /** Current unaccepted measurement batch, discarded on failure or the next host observation. */
    private final Map<Attempt, Long> pending = new HashMap<>();
    /** Prediction/outcome observations retained for caller inspection and calibration. */
    private final List<Observation> observations = new ArrayList<>();
    /** Atomic history publication failures that leave future demand unknown without fabricating a reservation. */
    private final List<IOException> problems = new ArrayList<>();
    /** Indicates whether the current pending batch began with a successful host observation. */
    private boolean staged;

    /**
     * Borrows native measurement and history services without acquiring resources.
     * @param measurements native complete-batch source
     * @param history explicitly owned Bazel-compatible completed-sample history
     * @throws NullPointerException if either input is null
     */
    BazelMemoryEstimates(Controller.Measurements measurements, BazelMemoryHistory history)
    {
        this.measurements = Objects.requireNonNull(measurements, "measurements");
        this.history = Objects.requireNonNull(history, "history");
    }

    /**
     * Registers authenticated ready work and supplies its original queue submission.
     * Each logical name is reserved exactly once, even if the work subsequently fails; retries reuse this submission.
     * @param name nonblank host-generated logical identity, distinct from the reusable content key
     * @param identity lowercase SHA-256 key of the authenticated immutable complete ready inputs
     * @return original logical identity and matching historical peak, or null peak for unknown demand
     * @throws NullPointerException if either input is null
     * @throws IllegalArgumentException if the name is blank/already registered or the content key is invalid
     */
    SchedulerCoordinator.Submission register(String name, String identity)
    {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || work.containsKey(name))
            throw new IllegalArgumentException("Bazel logical name must be nonblank and registered only once: " + name);
        Long prediction = history.estimate(identity);
        SchedulerCoordinator.Submission submission = new SchedulerCoordinator.Submission(name, prediction);
        work.put(name, new Work(identity, prediction));
        return submission;
    }

    /**
     * Finalizes one native attempt's training eligibility after the caller verifies its authentic terminal outcome.
     * Successful means original exit zero, complete descendant zero, helper closure and output finalization acknowledged.
     * Failed, denied, retried or cancelled attempts never train history. No accepted positive sample means no training.
     * The immutable snapshot contract makes the registered content key applicable throughout successful completion.
     * @param name registered logical queue identity
     * @param sequence nonnegative queue-wide attempt ordinal
     * @param successful indicates whether the authenticated whole-attempt successful completion contract holds
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if the name is unregistered or sequence is negative
     */
    void completed(String name, long sequence, boolean successful)
    {
        Objects.requireNonNull(name, "name");
        Work registered = work.get(name);
        if (registered == null || sequence < 0)
            throw new IllegalArgumentException("Completion requires a registered name and nonnegative attempt sequence");
        Attempt attempt = new Attempt(name, sequence);
        Long observed = accepted.remove(attempt);
        pending.remove(attempt);
        Status status = successful ? Status.UNOBSERVED : Status.UNSUCCESSFUL;
        if (successful && observed != null && observed > 0)
        {
            try
            {
                history.record(registered.identity(), observed);
                status = Status.RECORDED;
            }
            catch (IOException failure)
            {
                problems.add(failure);
                status = Status.HISTORY_UNAVAILABLE;
            }
        }
        observations.add(new Observation(name, sequence, registered.identity(), registered.prediction(), observed, status));
    }

    /**
     * Returns completed prediction/measurement pairs without claiming calibrated prediction accuracy.
     * @return immutable snapshot of completed observations
     */
    List<Observation> observations() { return List.copyOf(observations); }

    /**
     * Returns best-effort atomic history publication failures for diagnostics.
     * @return immutable snapshot of publication failures
     */
    List<IOException> problems() { return List.copyOf(problems); }

    @Override public Controller.Memory readHost() throws IOException
    {
        pending.clear();
        staged = false;
        Controller.Memory result = measurements.readHost();
        staged = true;
        return result;
    }

    @Override public long readResidentBytes(Policy.Job job) throws IOException
    {
        try
        {
            long resident = measurements.readResidentBytes(job);
            if (!staged || job.startedAt() == null || resident < 0 || !work.containsKey(job.name()))
                throw new IOException("Bazel sampling requires a staged host reading and a registered live attempt");
            pending.merge(new Attempt(job.name(), job.startedAt()), resident, Math::max);
            return resident;
        }
        catch (IOException | RuntimeException failure)
        {
            pending.clear();
            staged = false;
            throw failure;
        }
    }

    @Override public void acceptReadings() throws IOException
    {
        try
        {
            measurements.acceptReadings();
            if (staged)
                for (Map.Entry<Attempt, Long> sample : pending.entrySet())
                    accepted.merge(sample.getKey(), sample.getValue(), Math::max);
        }
        finally
        {
            pending.clear();
            staged = false;
        }
    }

    /**
     * Training outcome for one completed attempt.
     */
    enum Status
    {
        /** Positive accepted measurements were atomically recorded after successful completion. */
        RECORDED,
        /** Failed, cancelled or aborted completion excludes its measurements. */
        UNSUCCESSFUL,
        /** Successful completion had no accepted positive sample. */
        UNOBSERVED,
        /** Atomic publication failed and no new profile was accepted. */
        HISTORY_UNAVAILABLE
    }

    /**
     * Preserves a reusable content key independently of the logical queue name.
     * @param identity immutable ready-input content key
     * @param prediction original submitted sampled demand, or null when unknown
     */
    private record Work(String identity, Long prediction) {}

    /**
     * Identifies a native attempt without conflating it with content equivalence or queue age.
     * @param name invocation-local logical queue name
     * @param sequence queue-wide attempt ordinal
     */
    private record Attempt(String name, long sequence) {}

    /**
     * Captures prediction quality for one completed native attempt.
     * @param name invocation-local logical queue name
     * @param sequence queue-wide attempt ordinal
     * @param identity complete ready-input content key
     * @param predictedBytes submitted sampled-demand prediction, or null when unknown
     * @param observedBytes greatest accepted sampled whole-containment residency, or null without an accepted sample
     * @param status training eligibility/publication outcome
     */
    record Observation(String name, long sequence, String identity, Long predictedBytes, Long observedBytes, Status status) {}
}
