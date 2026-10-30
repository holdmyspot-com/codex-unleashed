package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Supplies instantaneous Windows host measurements and the residency of registered owned attempts.
 * <p>
 * Use an instance and its registrations only from the constructing thread. The caller owns every registered
 * Windows process and retains its registration through RUNNING, BLOCKED and STOPPING until the whole tree is reaped.
 * Closing a registration removes measurement access; it neither closes nor terminates the borrowed process owner.
 * A retry requires a new registration with a greater attempt sequence after the previous registration closes.
 */
public final class WindowsMeasurements implements Controller.Measurements
{
    /** Thread that owns this adapter and every registration operation. */
    private final Thread controllingThread = Thread.currentThread();
    /** Borrowed source of complete instantaneous host readings. */
    private final Host host;
    /** Currently registered attempt per stable action name. */
    private final Map<String, Registration> registrations = new HashMap<>();
    /** Original action identities and latest registered attempt sequences, retained across retries. */
    private final Map<String, Attempt> latestAttempts = new HashMap<>();
    /** Process owners already assigned to an active registration, compared by owner identity. */
    private final Map<WindowsProcess, Registration> owners = new IdentityHashMap<>();

    /**
     * Reads one complete instantaneous host sample; acquisition failure must throw rather than return null.
     */
    @FunctionalInterface
    interface Host
    {
        /**
         * Reads the current host sample.
         * @return a complete sample
         * @throws IOException if acquisition fails
         */
        WindowsMemory.Sample read() throws IOException;
    }

    /**
     * Creates measurements using native Windows host acquisition.
     */
    public WindowsMeasurements()
    {
        this(WindowsMemory::read);
    }

    /**
     * Creates measurements using a supplied host reader.
     * @param host the borrowed instantaneous host reader
     * @throws NullPointerException if {@code host} is null
     */
    WindowsMeasurements(Host host)
    {
        this.host = Objects.requireNonNull(host, "host may not be null");
    }

    /**
     * Registers one owned process attempt without acquiring or transferring ownership of its native resources.
     * The process must belong to this thread and remain open until the caller finishes sampling and reaping it.
     * An unavailable or closed owner causes sampling to fail; registration itself performs no native observation.
     *
     * @param action stable action identity, retaining its original submission position across retries
     * @param startedAt nonnegative attempt sequence, greater than this action's previous registered sequence
     * @param process borrowed owner of this attempt's complete Job Object tree
     * @return the registration to close after reaping the whole tree, before registering another attempt
     * @throws NullPointerException if {@code action} or {@code process} is null
     * @throws IllegalArgumentException if the sequence is negative or stale, the action position changes,
     * the action already has a registration, or the process already belongs to another registration
     * @throws IllegalStateException if called outside the constructing thread
     */
    public Registration register(Controller.Action action, long startedAt, WindowsProcess process)
    {
        requireControllingThread();
        Objects.requireNonNull(action, "action may not be null");
        Objects.requireNonNull(process, "process may not be null");
        if (startedAt < 0)
            throw new IllegalArgumentException("Attempt sequence must be nonnegative: " + startedAt);
        if (registrations.containsKey(action.name()))
            throw new IllegalArgumentException("Action already has a registered attempt: " + action.name());
        if (owners.containsKey(process))
            throw new IllegalArgumentException("Process owner already belongs to a registered attempt");
        Attempt previous = latestAttempts.get(action.name());
        if (previous != null)
        {
            if (!previous.action().equals(action))
                throw new IllegalArgumentException("Action position differs from its original registration: " + action);
            if (startedAt <= previous.startedAt())
                throw new IllegalArgumentException("Attempt sequence must exceed " + previous.startedAt() + ": " + startedAt);
        }

        var attempt = new Attempt(action, startedAt);
        var registration = new Registration(attempt, process);
        latestAttempts.put(action.name(), attempt);
        registrations.put(action.name(), registration);
        owners.put(process, registration);
        return registration;
    }

    @Override
    public Controller.Memory readHost() throws IOException
    {
        requireControllingThread();
        WindowsMemory.Sample sample = host.read();
        if (sample == null)
            throw new IOException("Windows host memory observation is unavailable");
        return new Controller.Memory(sample.availableBytes(), sample.swapUsedBytes(), sample.lowMemory());
    }

    /**
     * Reads the complete owned tree only when its registered action and attempt match the supplied live job.
     * @param job action and current attempt to sample, including STOPPING until its tree is reaped
     * @return observed nonnegative whole-job residency, including a successfully observed zero
     * @throws NullPointerException if {@code job} is null
     * @throws IllegalStateException if called outside the constructing thread
     * @throws IOException if the job is inactive, unregistered, mismatched, stale or its owner cannot be sampled
     */
    @Override
    public long readResidentBytes(Policy.Job job) throws IOException
    {
        requireControllingThread();
        Objects.requireNonNull(job, "job may not be null");
        if (job.startedAt() == null)
            throw new IOException("Inactive action has no process attempt to sample: " + job.name());
        Registration registration = registrations.get(job.name());
        if (registration == null)
            throw new IOException("Action has no registered process attempt: " + job.name());
        Attempt attempt = registration.attempt;
        boolean matchesAttempt = attempt.action().position() == job.position() && attempt.startedAt() == job.startedAt();
        if (!matchesAttempt)
            throw new IOException("Action identity or attempt sequence differs from its registration: " + job.name());
        try
        {
            return registration.process.readResidentBytes();
        }
        catch (IllegalStateException failure)
        {
            throw new IOException("Registered process owner cannot be sampled for action " + job.name(), failure);
        }
    }

    /**
     * Rejects access from any thread other than this adapter's constructing thread.
     * @throws IllegalStateException if called outside the constructing thread
     */
    private void requireControllingThread()
    {
        if (Thread.currentThread() != controllingThread)
            throw new IllegalStateException("Windows measurements require their constructing thread");
    }

    /**
     * Holds a previously validated action identity and its registration sequence.
     * @param action original action identity
     * @param startedAt nonnegative current attempt sequence
     */
    private record Attempt(Controller.Action action, long startedAt)
    {
    }

    /**
     * Keeps a borrowed process owner available for one attempt's measurements until the caller removes it.
     * Close only after reaping the complete process tree, including when its last scheduler state is STOPPING.
     */
    public final class Registration implements AutoCloseable
    {
        /** Stable action identity and sequence accepted for this registration. */
        private final Attempt attempt;
        /** Borrowed owner of the registered attempt's complete native job. */
        private final WindowsProcess process;
        /** Indicates whether measurement access has been removed. */
        private boolean closed = false;

        /**
         * Retains an already validated attempt and its borrowed owner.
         * @param attempt validated action and attempt identity
         * @param process borrowed process owner
         */
        private Registration(Attempt attempt, WindowsProcess process)
        {
            this.attempt = attempt;
            this.process = process;
        }

        /**
         * Removes this attempt's measurement access without closing or terminating its borrowed owner.
         * Repeated calls have no effect. The caller must already have reaped the complete tree.
         * @throws IllegalStateException if called outside the adapter's constructing thread
         */
        @Override
        public void close()
        {
            requireControllingThread();
            if (closed)
                return;
            registrations.remove(attempt.action().name(), this);
            owners.remove(process);
            closed = true;
        }
    }
}
