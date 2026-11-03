package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stages complete macOS host and authenticated workload observations for one controller batch.
 * Residency is a directly observed lower bound; it grants neither a peak estimate nor completion authority.
 * All calls and borrowed sources are confined to the constructing thread.
 */
public final class MacMeasurements implements Controller.Measurements
{
    /**
     * Identifies a registered workload attempt independently of native image changes.
     * @param name nonblank stable action name
     * @param sequence nonnegative controller attempt ordinal
     */
    public record Attempt(String name, long sequence)
    {
        /**
         * Validates exact action correlation fields.
         * @param name nonblank stable action name
         * @param sequence nonnegative attempt ordinal
         * @throws NullPointerException if name is null
         * @throws IllegalArgumentException if name is blank or sequence is negative
         */
        public Attempt
        {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || sequence < 0) throw new IllegalArgumentException("Attempt requires a nonblank name and nonnegative sequence");
        }
    }

    /**
     * Freezes the exact native-owner registry represented by one host observation.
     * @param memory complete directly acquired native host counters
     * @param generation nonnegative native-owner registration generation
     * @param attempts exact live registered attempts
     */
    public record Observation(MacMemory.Sample memory, long generation, Set<Attempt> attempts)
    {
        /**
         * Validates and freezes a complete host and registry observation.
         * @param memory complete native host counters
         * @param generation nonnegative registration generation
         * @param attempts exact live attempts, copied before native sampling
         * @throws NullPointerException if memory, attempts or an attempt is null
         * @throws IllegalArgumentException if generation is negative
         */
        public Observation
        {
            Objects.requireNonNull(memory, "memory");
            attempts = Set.copyOf(attempts);
            if (generation < 0) throw new IllegalArgumentException("Registration generation must be nonnegative");
        }
    }

    /** Supplies complete native observations while retaining every registered owner through acceptance. */
    public interface Source
    {
        /**
         * Reads fresh host counters and freezes the current native-owner registry.
         * @return complete host and registration observation
         * @throws IOException if a native reading or registry authority is unavailable
         */
        Observation read() throws IOException;

        /**
         * Acquires a complete authenticated cohort with directly observed resident bytes.
         * @param attempt exact retained live attempt
         * @return complete native rows, excluding only the non-forking anchor
         * @throws MacWorkloadNative.MembershipChanged if native observations establish image or cohort churn
         * @throws IOException if access, identity or another required native reading fails
         */
        List<MacWorkloadNative.Resident> residents(Attempt attempt) throws IOException;

        /**
         * Reads the current registration generation without advancing or closing any owner.
         * @return nonnegative native-owner registry generation
         * @throws IOException if retained registration authority cannot be read
         */
        long generation() throws IOException;
    }

    /**
     * Connects real host readings to exact retained native owners without assuming PID or image callbacks are inventories.
     * The attempts owner registers only acquired held workloads and removes them only after complete owner closure.
     */
    static final class NativeSource implements Source
    {
        /** Borrowed invocation scope supplying native host bindings. */
        private final ToolingScope scope;
        /** Exact retained owners, indexed by controller action and attempt ordinal. */
        private final Map<Attempt, MacWorkloadNative.Owner> owners = new HashMap<>();
        /** Constructing thread required by every retained native owner. */
        private final Thread thread = Thread.currentThread();
        /** Monotonic owner registration generation. */
        private long generation;

        /**
         * Retains native host dependencies without acquiring or launching a workload.
         * @param scope invocation scope retained through all source calls
         * @throws NullPointerException if scope is null
         */
        NativeSource(ToolingScope scope)
        {
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        /**
         * Registers an already acquired held workload before the controller can sample its attempt.
         * The caller retains cleanup ownership; this registry never launches, resumes or closes a workload.
         * @param attempt exact controller action and attempt ordinal
         * @param owner independently acquired native owner retained through terminal image cleanup
         * @throws NullPointerException if either input is null
         * @throws IllegalArgumentException if the attempt or owner is already registered
         * @throws IllegalStateException if called from another thread
         * @throws ArithmeticException if the registration generation is exhausted
         */
        void register(Attempt attempt, MacWorkloadNative.Owner owner)
        {
            requireThread();
            Objects.requireNonNull(attempt, "attempt");
            Objects.requireNonNull(owner, "owner");
            if (owners.containsKey(attempt) || owners.containsValue(owner))
                throw new IllegalArgumentException("Attempt or native owner is already registered");
            generation = Math.incrementExact(generation);
            owners.put(attempt, owner);
        }

        /**
         * Removes exactly one registration after its actual native and image ownership has closed.
         * @param attempt exact registered attempt
         * @param owner original retained owner whose successful close has completed
         * @throws IllegalArgumentException if the attempt is not registered to this owner
         * @throws IllegalStateException if another thread calls or the native owner remains live
         * @throws ArithmeticException if the registration generation is exhausted
         */
        void unregister(Attempt attempt, MacWorkloadNative.Owner owner)
        {
            requireThread();
            if (owner == null || owners.get(attempt) != owner)
                throw new IllegalArgumentException("Attempt is not registered to this native owner");
            if (!owner.isClosed()) throw new IllegalStateException("Native owner must finish successful closure before unregistering");
            generation = Math.incrementExact(generation);
            owners.remove(attempt);
        }

        @Override public Observation read() throws IOException
        {
            requireThread();
            MacMemory.Sample host = MacMemory.read(scope);
            return new Observation(host, generation, owners.keySet());
        }

        @Override public List<MacWorkloadNative.Resident> residents(Attempt attempt) throws IOException
        {
            requireThread();
            MacWorkloadNative.Owner owner = owners.get(attempt);
            if (owner == null) throw new IOException("Attempt has no retained native owner: " + attempt);
            return owner.residents();
        }

        @Override public long generation()
        {
            requireThread();
            return generation;
        }

        /**
         * Enforces the retained native owners' thread confinement.
         * @throws IllegalStateException if another thread calls
         */
        private void requireThread()
        {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Mac native measurements require their constructing thread");
        }
    }

    /** Borrowed native producer retained through every batch. */
    private final Source source;
    /** Constructing thread, required by the borrowed native owners. */
    private final Thread thread = Thread.currentThread();
    /** Complete host and registration observation awaiting acceptance. */
    private Observation pending;
    /** Full native image identities observed for every successfully sampled attempt. */
    private final Map<Attempt, List<List<Long>>> sampled = new HashMap<>();

    /**
     * Retains a resource-free native observation source.
     * @param source borrowed host, registry and authenticated residency producer
     * @throws NullPointerException if source is null
     */
    public MacMeasurements(Source source)
    {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public Controller.Memory readHost() throws IOException
    {
        requireThread();
        discard();
        pending = source.read();
        MacMemory.Sample memory = pending.memory();
        return new Controller.Memory(memory.availableBytes(), memory.swapUsedBytes(), memory.pressure() != MacMemory.Pressure.NORMAL);
    }

    @Override public long readResidentBytes(Policy.Job job) throws IOException
    {
        requireThread();
        try
        {
            Objects.requireNonNull(job, "job");
            if (pending == null) throw new IOException("No complete macOS host observation is staged");
            if (job.startedAt() == null) throw new IOException("Job has no live attempt ordinal: " + job.name());
            Attempt attempt = new Attempt(job.name(), job.startedAt());
            if (!pending.attempts().contains(attempt)) throw new IOException("Attempt is absent from the staged native registry: " + attempt);
            verifyRegistry(ObservationRejected.Operation.RESIDENT);
            List<MacWorkloadNative.Resident> rows = residents(attempt, ObservationRejected.Operation.RESIDENT);
            long bytes = 0;
            for (MacWorkloadNative.Resident row : rows) bytes = Math.addExact(bytes, row.residentBytes());
            sampled.put(attempt, rows.stream().map(MacWorkloadNative.Resident::authority).toList());
            return bytes;
        }
        catch (ArithmeticException failure)
        {
            discard();
            throw new IOException("Native resident byte sum exceeds the signed supported range", failure);
        }
        catch (IOException | RuntimeException failure)
        {
            discard();
            throw failure;
        }
    }

    @Override public void acceptReadings() throws IOException
    {
        requireThread();
        try
        {
            if (pending == null || !sampled.keySet().equals(pending.attempts()))
                throw new IOException("Every staged live attempt requires a successful residency observation before acceptance");
            verifyRegistry(ObservationRejected.Operation.ACCEPT);
            for (Attempt attempt : pending.attempts())
            {
                List<List<Long>> current = residents(attempt, ObservationRejected.Operation.ACCEPT).stream().
                    map(MacWorkloadNative.Resident::authority).toList();
                if (!sampled.get(attempt).equals(current))
                    throw rejection(ObservationRejected.Operation.ACCEPT, Set.of(attempt),
                        new IOException("Authenticated native image or cohort changed before batch acceptance"));
            }
            verifyRegistry(ObservationRejected.Operation.ACCEPT);
        }
        finally { discard(); }
    }

    /**
     * Acquires full native rows or correlates a native-confirmed rejection to the exact registered attempt.
     * @param attempt retained exact action attempt
     * @param operation controller boundary making this read
     * @return complete authenticated cohort and resident observations
     * @throws IOException if native authority or a required reading fails
     */
    private List<MacWorkloadNative.Resident> residents(Attempt attempt, ObservationRejected.Operation operation) throws IOException
    {
        try { return source.residents(attempt); }
        catch (MacWorkloadNative.MembershipChanged failure) { throw rejection(operation, Set.of(attempt), failure); }
        catch (MacWorkloadNative.IncompleteInventory failure) { throw rejection(operation, Set.of(attempt), failure); }
    }

    /**
     * Rejects a batch after an independently observed native-owner registry transition.
     * @param operation controller boundary being validated
     * @throws IOException if the generation changed or registration authority is unavailable
     */
    private void verifyRegistry(ObservationRejected.Operation operation) throws IOException
    {
        if (source.generation() != pending.generation())
            throw rejection(operation, pending.attempts(), new IOException("Native-owner registration generation changed during the batch"));
    }

    /**
     * Creates retry permission only when the rejected observation has exact affected registered attempts.
     * @param operation rejected controller boundary
     * @param attempts exact affected attempts from the staged authenticated registry
     * @param cause independently established change or incomplete native inventory
     * @return correlated retry rejection, or an ordinary failure when there are no affected attempts
     */
    private IOException rejection(ObservationRejected.Operation operation, Set<Attempt> attempts, IOException cause)
    {
        if (attempts.isEmpty()) return cause;
        List<ObservationRejected.Attempt> affected = attempts.stream().
            map(attempt -> new ObservationRejected.Attempt(attempt.name(), attempt.sequence())).toList();
        ObservationRejected.Reason reason = cause instanceof MacWorkloadNative.IncompleteInventory ?
            ObservationRejected.Reason.INCOMPLETE_INVENTORY : ObservationRejected.Reason.MEMBERSHIP_CHANGED;
        return new ObservationRejected(operation, reason, affected, cause);
    }

    /** Discards an incomplete observation without changing any retained native owner. */
    private void discard()
    {
        pending = null;
        sampled.clear();
    }

    /**
     * Enforces the native producer's thread confinement before touching a batch.
     * @throws IllegalStateException if another thread calls
     */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Mac measurements require their constructing thread");
    }
}
