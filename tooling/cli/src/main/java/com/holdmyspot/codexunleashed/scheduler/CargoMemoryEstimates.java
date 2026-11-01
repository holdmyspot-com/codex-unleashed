package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Learns best-effort compiler estimates only from accepted measurement batches and successful whole-tree outcomes.
 * Calls belong to one invocation's coordination thread. The borrowed measurement source and history directory remain
 * owned by the caller. Native memory recovery remains necessary because sampled past demand cannot bound future work.
 */
final class CargoMemoryEstimates implements Controller.Measurements
{
    /** Borrowed complete native measurement provider. */
    private final Controller.Measurements measurements;
    /** Caller-owned completed observation history. */
    private final CargoMemoryHistory history;
    /** Caller-pinned compiler work content boundaries. */
    private final CargoWorkIdentity identities;

    /** Authenticated original work inputs retained until successful completion or invocation disposal. */
    private final Map<String, Work> work = new HashMap<>();
    /** Accepted maximum tree residency by native attempt; retries never share training samples. */
    private final Map<Attempt, Long> accepted = new HashMap<>();
    /** Current incomplete batch, discarded before another host observation or after any observation failure. */
    private final Map<Attempt, Long> pending = new HashMap<>();
    /** Completed prediction/observation pairs for caller inspection and calibration. */
    private final List<Observation> observations = new ArrayList<>();
    /** Best-effort history/content failures, never substituted with invented estimates. */
    private final List<IOException> problems = new ArrayList<>();
    /** Indicates whether a successful host reading began the current pending batch. */
    private boolean staged;

    /**
     * Borrows complete measurements and explicit history without acquiring native resources.
     * @param measurements unchanged native complete-batch provider
     * @param history explicitly owned completed sample storage
     * @param identities reusable authenticated work identity source
     * @throws NullPointerException if an input is null
     */
    CargoMemoryEstimates(Controller.Measurements measurements, CargoMemoryHistory history, CargoWorkIdentity identities)
    {
        this.measurements = Objects.requireNonNull(measurements, "measurements");
        this.history = Objects.requireNonNull(history, "history");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /**
     * Supplies the completed-work prediction after native registration authenticates all compiler inputs.
     * @param request independently authenticated request
     * @return request carrying reusable sampled demand, or unknown demand when no applicable history exists
     */
    CargoCompilerRequest register(CargoCompilerRequest request)
    {
        Objects.requireNonNull(request, "request");
        if (work.containsKey(request.name()))
            throw new IllegalArgumentException("Compiler work was already registered: " + request.name());
        String identity = null;
        Long prediction = null;
        try
        {
            identity = identities.identify(request);
            prediction = history.estimate(identity);
        }
        catch (IOException failure) { problems.add(failure); }
        work.put(request.name(), new Work(request, identity, prediction));
        return new CargoCompilerRequest(request.identity(), request.wrapper(), request.arguments(), request.directory(),
            request.environment(), prediction);
    }

    /**
     * Records training only after the accepted whole-tree attempt has completed successfully.
     * @param name authenticated compiler action name
     * @param sequence original global attempt ordinal
     * @param successful indicates whether the native complete result succeeded without termination
     */
    void completed(String name, long sequence, boolean successful)
    {
        Objects.requireNonNull(name, "name");
        Work registered = work.get(name);
        Long observed = accepted.remove(new Attempt(name, sequence));
        if (registered == null) return;
        Status status = successful ? Status.UNOBSERVED : Status.UNSUCCESSFUL;
        if (successful && observed != null && observed > 0 && registered.identity() != null)
        {
            try
            {
                if (registered.identity().equals(identities.identify(registered.request())))
                {
                    history.record(registered.identity(), observed);
                    status = Status.RECORDED;
                }
                else status = Status.INPUTS_CHANGED;
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
     * Returns completed prediction/observation pairs without claiming exact process peaks or calibrated accuracy.
     * @return immutable completed outcome observations
     */
    List<Observation> observations() { return List.copyOf(observations); }

    /**
     * Returns failures that made best-effort reuse unavailable without failing compiler execution.
     * @return immutable content/history failure snapshot
     */
    List<IOException> problems() { return List.copyOf(problems); }

    @Override public Controller.Memory readHost() throws IOException
    {
        pending.clear();
        staged = false;
        Controller.Memory memory = measurements.readHost();
        staged = true;
        return memory;
    }
    @Override public long readResidentBytes(Policy.Job job) throws IOException
    {
        try
        {
            long resident = measurements.readResidentBytes(job);
            if (!staged || job.startedAt() == null || resident < 0)
                throw new IOException("Compiler residency requires a staged host reading and a live attempt");
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
     * Supplies an explicitly owned history directory and complete compiler content boundaries across invocations.
     * The directory must be outside source and toolchain trees so publishing a profile cannot change work identity.
     */
    static final class Settings
    {
        /** Caller-owned immutable completed samples retained across invocations. */
        private final CargoMemoryHistory history;
        /** Complete source, generated-output and pinned toolchain boundaries. */
        private final CargoWorkIdentity identities;

        /**
         * Validates and retains explicit profile inputs without filesystem acquisition.
         * @param historyDirectory existing owned history directory, outside source and toolchain trees
         * @param inputRoot complete source and configuration tree
         * @param outputRoots generated compiler output directories strictly below the input root
         * @param toolchainRoot pinned immutable compiler and sysroot tree
         * @throws NullPointerException if a required value or collection member is null
         * @throws IllegalArgumentException if paths are invalid or history overlaps source or toolchain
         */
        Settings(Path historyDirectory, Path inputRoot, List<Path> outputRoots, Path toolchainRoot)
        {
            this.identities = new CargoWorkIdentity(inputRoot, outputRoots, toolchainRoot);
            this.history = new CargoMemoryHistory(historyDirectory);
            if (historyDirectory.startsWith(inputRoot) || historyDirectory.startsWith(toolchainRoot))
                throw new IllegalArgumentException("Compiler history must be outside source and toolchain trees");
        }

        /**
         * Starts one invocation's independent profile observation state around its actual measurement provider.
         * @param measurements native complete-batch observations owned by the invocation
         * @return invocation-local estimate source
         * @throws NullPointerException if {@code measurements} is null
         */
        CargoMemoryEstimates observe(Controller.Measurements measurements)
        {
            return new CargoMemoryEstimates(measurements, history, identities);
        }
    }

    /**
     * Eligibility result for one completed attempt's reusable training sample.
     */
    enum Status
    {
        /** A successful completed attempt published an accepted sampled maximum. */
        RECORDED,
        /** Native whole-tree completion failed or was aborted. */
        UNSUCCESSFUL,
        /** No accepted positive complete-batch sample or applicable identity was available. */
        UNOBSERVED,
        /** Compiler inputs changed before successful whole-tree completion. */
        INPUTS_CHANGED,
        /** Complete input revalidation or atomic history publication failed. */
        HISTORY_UNAVAILABLE
    }

    /**
     * Records a completed attempt's prediction and accepted maximum sampled tree residency.
     * @param name authenticated action name
     * @param sequence queue-wide native attempt ordinal
     * @param identity reusable work key, or null when inputs were inapplicable
     * @param predictedBytes submitted best-effort bytes, or null for unknown demand
     * @param observedBytes greatest accepted sampled residency, or null without an accepted live sample
     * @param status training eligibility/publication result
     */
    record Observation(String name, long sequence, String identity, Long predictedBytes, Long observedBytes, Status status) {}

    /**
     * Retains the native attempt identity independently of reusable compiler-work identity.
     * @param name authenticated action name
     * @param sequence queue-wide native attempt ordinal
     */
    private record Attempt(String name, long sequence) {}

    /**
     * Retains original authenticated inputs for completion-time invalidation and calibration.
     * @param request authenticated request before attaching a prediction
     * @param identity reusable work key, or null for inapplicable work
     * @param prediction submitted best-effort bytes, or null for unknown demand
     */
    private record Work(CargoCompilerRequest request, String identity, Long prediction) {}
}
