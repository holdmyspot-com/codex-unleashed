package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Owns one Cargo root, authenticated wrappers and their invocation-wide native dependencies.
 * Construction is resource-free. Calls belong to the constructing thread; the queue exclusively closes attempts.
 * The caller retains its Cargo scope and exclusive invocation directories until {@link #isClosed()} is true.
 * After failed session cleanup the caller retains this owner and may call close to progress safe cleanup; observation
 * never retries native effects. Failed descriptor closes and uncertain publication are never replayed.
 */
public final class WindowsCargoInvocation implements CargoSession.Invocation
{
    /**
     * Sole native coordination thread.
     */
    private final Thread thread = Thread.currentThread();
    /**
     * Retained concrete native owner, including partial acquisition.
     */
    private final Resources resources;
    /**
     * Coordinator session, installed before Cargo release.
     */
    private CargoSession<WindowsProcess.Result> session;
    /**
     * Whether acquisition has been attempted.
     */
    private boolean startAttempted;
    /**
     * Whether Cargo release completed.
     */
    private boolean started;
    /**
     * Whether stop was attempted, including uncertain failure.
     */
    private boolean stopAttempted;
    /**
     * Exact uncertain stop failure, never replayed.
     */
    private Throwable stopFailure;
    /**
     * Whether every Cargo consumer has been collected.
     */
    private boolean consumersClosed;
    /**
     * Whether every endpoint closed successfully.
     */
    private boolean endpointsClosed;
    /**
     * Whether all dependencies closed successfully.
     */
    private boolean dependenciesClosed;
    /**
     * Whether whole cleanup has completed.
     */
    private boolean closed;
    /**
     * First operational failure; permanently prevents new execution.
     */
    private Throwable fault;

    /**
     * Retains native Windows acquisition plans without starting a process or loading a native producer.
     * @param scope borrowed invocation services retained through completed cleanup
     * @param configuration trusted immutable Cargo and wrapper boundaries
     * @throws NullPointerException if an input is null
     */
    public WindowsCargoInvocation(WindowsCargoScope scope, Configuration configuration)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration"), null));
    }

    /**
     * Retains native execution plans with caller-owned completed-work history across invocations.
     * Missing or inapplicable history preserves conservative unknown demand; sampled history cannot bound future demand.
     * @param scope borrowed invocation services retained through completed cleanup
     * @param configuration trusted immutable Cargo and wrapper boundaries
     * @param memory caller-owned history and complete source/toolchain identity boundaries
     * @throws NullPointerException if an input is null
     */
    public WindowsCargoInvocation(WindowsCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration"), Objects.requireNonNull(memory, "memory")));
    }

    /**
     * Creates the same lifecycle owner with controlled invocation-specific native boundaries.
     * @param resources retained acquisition, service and cleanup owner
     * @throws NullPointerException if resources is null
     */
    public WindowsCargoInvocation(Resources resources)
    {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * Acquires dependencies once, constructs the coordinator session and releases held Cargo last.
     * Partial acquisition remains owned on failure; safe cleanup is attempted without replacing the primary failure.
     * @throws IllegalStateException if called from another thread or acquisition was already attempted or closed
     * @throws IOException if native acquisition or release fails
     */
    public void start() throws IOException
    {
        requireThread();
        if (startAttempted || closed) throw new IllegalStateException("Cargo invocation acquisition is permitted once");
        startAttempted = true;
        try
        {
            resources.acquire();
            session = new CargoSession<>(resources.factory(), resources.measurements(), resources::register, this,
                resources::acceptedOutcome);
            resources.release(session);
            started = true;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            fault = failure;
            try { close(); }
            catch (IOException | RuntimeException | Error cleanup) { suppress(failure, cleanup); retainOn(failure); }
            throw failure;
        }
    }

    /**
     * Supplies the installed coordinator session, including its retained failure history after cleanup.
     * @return started coordinator session
     * @throws IllegalStateException if no successful start occurred or another thread calls
     */
    public CargoSession<WindowsProcess.Result> session()
    {
        requireThread();
        if (!started) throw new IllegalStateException("Cargo invocation did not start successfully");
        return session;
    }

    /**
     * Reads the native facts a stall report adds to the queue state, without changing any owner.
     * @return attempt owners, unregistered wrapper pids and token accounting
     * @throws IllegalStateException if called from another thread
     */
    StallWatch.Facts stallFacts()
    {
        requireThread();
        return resources.stallFacts();
    }

    /**
     * Returns completed prediction and sampled-residency pairs for best-effort estimate calibration.
     * @return immutable completed observations, or empty when history was not configured
     * @throws IllegalStateException if another thread calls
     */
    public List<CargoMemoryEstimates.Observation> memoryObservations()
    {
        requireThread();
        return List.copyOf(resources.memoryObservations());
    }

    @Override public void pump() throws IOException
    {
        requireActive();
        try { resources.pump(); }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }
    @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException
    {
        requireActive();
        try { return resources.pollBlocked(); }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }
    @Override public boolean inputClosed() { requireThread(); return started && resources.inputClosed(); }
    @Override public OptionalInt pollToolResult() throws IOException
    {
        requireActive();
        try { return resources.pollToolResult(); }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }
    @Override public boolean hasOpenAttempts() { requireThread(); return startAttempted && resources.hasOpenAttempts(); }
    @Override public void stopAccepting() throws IOException
    {
        requireThread();
        if (!startAttempted) return;
        if (!stopAttempted)
        {
            stopAttempted = true;
            try { resources.stopAccepting(); }
            catch (IOException | RuntimeException | Error failure) { stopFailure = failure; throw failure; }
        }
        else rethrow(stopFailure);
    }

    /**
     * Stops discovery and collects consumers before closing endpoints and invocation dependencies.
     * Repeated calls progress only safely retryable cleanup; an uncertain stop or endpoint close remains retained.
     * @throws IllegalStateException if another thread calls
     * @throws IOException if consumers, endpoints or dependencies remain owned
     */
    @Override public void close() throws IOException
    {
        requireThread();
        if (closed) return;
        if (!startAttempted) { closed = true; return; }
        Throwable failure = null;
        try { stopAccepting(); }
        catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; }
        if (resources.hasOpenAttempts())
        {
            failure = combine(failure, new IOException("Queue-owned Cargo attempts retain invocation dependencies"));
            retainOn(failure);
            rethrow(failure);
            return;
        }
        if (!consumersClosed)
        {
            try { resources.closeConsumers(); consumersClosed = true; }
            catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
        }
        if (consumersClosed && !endpointsClosed)
        {
            try { resources.closeEndpoints(); endpointsClosed = true; }
            catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
        }
        if (consumersClosed && endpointsClosed && stopFailure == null && !dependenciesClosed)
        {
            try { resources.closeDependencies(); dependenciesClosed = true; }
            catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
        }
        closed = consumersClosed && endpointsClosed && dependenciesClosed && stopFailure == null;
        if (failure != null) retainOn(failure);
        rethrow(failure);
    }
    @Override public boolean isClosed() { requireThread(); return closed; }

    /**
     * Rejects another thread before accessing any native collaborator.
     * @throws IllegalStateException if another thread calls
     */
    private void requireThread()
    {
        if (thread != Thread.currentThread()) throw new IllegalStateException("Cargo invocation requires its constructing thread");
    }

    /**
     * Requires successful startup and healthy active execution.
     * @throws IllegalStateException if startup did not succeed or another thread calls
     * @throws IOException if execution previously failed or discovery has stopped
     */
    private void requireActive() throws IOException
    {
        requireThread();
        if (!started) throw new IllegalStateException("Cargo invocation has not started");
        if (fault != null || stopAttempted || closed) throw new IOException("Cargo invocation is no longer active", fault);
    }

    /**
     * Retains a later independent failure without replacing the first exact throwable.
     * @param first primary failure, or null
     * @param next subsequent failure
     * @return first failure, or next when none preceded it
     */
    private static Throwable combine(Throwable first, Throwable next)
    {
        if (first == null) return next;
        suppress(first, next);
        return first;
    }

    /**
     * Adds a distinct cleanup failure without self-suppression or duplicate references.
     * @param primary original failure
     * @param cleanup independent cleanup failure
     */
    private static void suppress(Throwable primary, Throwable cleanup)
    {
        if (primary == cleanup || references(cleanup, primary, new HashSet<>())) return;
        for (Throwable retained : primary.getSuppressed()) if (retained == cleanup) return;
        primary.addSuppressed(cleanup);
    }

    /**
     * Checks whether adding suppression would introduce a cause or suppression cycle.
     * @param source failure graph being inspected
     * @param target failure that must not become reachable from itself
     * @param visited already inspected failures
     * @return whether source already retains target
     */
    private static boolean references(Throwable source, Throwable target, Set<Throwable> visited)
    {
        if (source == target) return true;
        if (source == null || !visited.add(source)) return false;
        if (references(source.getCause(), target, visited)) return true;
        for (Throwable retained : source.getSuppressed())
            if (references(retained, target, visited)) return true;
        return false;
    }

    /**
     * Keeps this partial owner reachable from an exact failure without adding a cause cycle.
     * @param failure exact failure to propagate
     */
    private void retainOn(Throwable failure)
    {
        for (Throwable retained : failure.getSuppressed())
            if (retained instanceof CleanupFailure marker && marker.owner == this) return;
        failure.addSuppressed(new CleanupFailure(this));
    }

    /**
     * Retains a partial invocation without changing the original failure's type or cause graph.
     */
    public static final class CleanupFailure extends IOException implements AutoCloseable
    {
        /**
         * Exception serialization format identifier.
         */
        private static final long serialVersionUID = 1L;
        /**
         * Native cleanup authority, meaningful only in the originating JVM.
         */
        private final transient WindowsCargoInvocation owner;

        /**
         * Retains the owner without retaining a Throwable as a cause.
         * @param owner original thread-confined partial invocation
         */
        private CleanupFailure(WindowsCargoInvocation owner)
        {
            super("Cargo invocation retains incomplete cleanup ownership");
            this.owner = owner;
        }

        /**
         * Progresses safely retryable cleanup on the owner's original thread.
         * Uncertain native close remains sticky; this operation never replays a publication or consumed descriptor.
         * @throws IllegalStateException if another thread calls
         * @throws IOException if cleanup remains incomplete or this marker was deserialized
         */
        @Override public void close() throws IOException
        {
            if (owner == null) throw new IOException("Serialized Cargo cleanup marker carries no native authority");
            owner.close();
        }
    }

    /**
     * Rethrows an exact native failure without changing its type or identity.
     * @param failure IOException, RuntimeException, Error, or null
     * @throws IOException if the original failure is checked
     */
    private static void rethrow(Throwable failure) throws IOException
    {
        if (failure instanceof IOException checked) throw checked;
        if (failure instanceof RuntimeException unchecked) throw unchecked;
        if (failure instanceof Error fatal) throw fatal;
    }

    /**
     * Prepares the complete Windows root environment for the configured compiler and packaged wrapper.
     * Existing compiler and invocation-control names are replaced case-insensitively. The wrapper's app
     * directory is prepended to PATH so the packaged launcher can load its runtime without restarting;
     * an existing PATH keeps its spelling and contents, including an explicit empty value. The returned
     * map remains mutable for the invocation-specific control endpoint and controller identity values.
     *
     * @param configuration validated immutable Cargo launch configuration
     * @return copied environment containing configured compiler, wrapper, and runtime search paths
     * @throws NullPointerException if configuration is null
     * @throws IllegalArgumentException if input names contain case-insensitive duplicates
     */
    public static Map<String, String> approvedEnvironment(Configuration configuration)
    {
        Objects.requireNonNull(configuration, "configuration may not be null");
        Map<String, String> environment = new LinkedHashMap<>(configuration.environment());
        var names = new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String name : environment.keySet())
        {
            if (!names.add(name))
                throw new IllegalArgumentException("Case-insensitive duplicate environment name: " + name);
        }
        String controlPrefix = "CODEX_CARGO_";
        environment.keySet().removeIf(name -> name.equalsIgnoreCase("RUSTC") ||
            name.equalsIgnoreCase("RUSTC_WRAPPER") ||
            name.regionMatches(true, 0, controlPrefix, 0, controlPrefix.length()));
        String pathName = environment.keySet().stream().filter(name -> name.equalsIgnoreCase("PATH")).
            findFirst().orElse("PATH");
        String appDirectory = configuration.wrapper().getParent().resolve("app").toString();
        String path = environment.containsKey(pathName) ? appDirectory + ";" + environment.get(pathName) :
            appDirectory;
        environment.put(pathName, path);
        environment.put("RUSTC", configuration.rustc().toString());
        environment.put("RUSTC_WRAPPER", configuration.wrapper().toString());
        return environment;
    }

    /**
     * Trusted immutable Windows Cargo launch and caller-owned native stream boundaries.
     * @param cargo exact Cargo argv, beginning with an absolute executable
     * @param directory absolute normalized Cargo working directory
     * @param environment complete caller environment, excluding jobserver-owned flag variables
     * @param input caller-owned live standard-input handle
     * @param output caller-owned live standard-output handle
     * @param error caller-owned live standard-error handle
     * @param rustc absolute normalized pinned compiler executable
     * @param wrapper absolute normalized packaged wrapper executable
     * @param frida absolute normalized pinned native producer
     * @param imageHashes immutable permitted wrapper/runtime executable paths and SHA256 values
     * @param jobs positive capacity including Cargo's implicit slot
     * @param timeout positive bounded native control interval, not a Cargo runtime deadline
     */
    public record Configuration(List<String> cargo, Path directory, Map<String, String> environment,
        MemorySegment input, MemorySegment output, MemorySegment error, Path rustc, Path wrapper, Path frida,
        Map<Path, String> imageHashes, int jobs, Duration timeout)
    {
        /**
         * Copies launch inputs without acquiring resources or observing the filesystem.
         * @param cargo exact absolute Cargo argv
         * @param directory absolute normalized working directory
         * @param environment complete explicit environment
         * @param input borrowed live native input handle
         * @param output borrowed live native output handle
         * @param error borrowed live native error handle
         * @param rustc absolute normalized compiler path
         * @param wrapper absolute normalized packaged launcher path
         * @param frida absolute normalized native producer path
         * @param imageHashes permitted wrapper/runtime images and immutable SHA256 values
         * @param jobs positive total job capacity
         * @param timeout positive signed-nanosecond control interval
         * @throws NullPointerException if an input or collection member is null
         * @throws IllegalArgumentException if paths, capacity or interval are invalid
         */
        public Configuration
        {
            cargo = List.copyOf(cargo);
            environment = Map.copyOf(environment);
            imageHashes = Map.copyOf(imageHashes);
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(timeout, "timeout");
            if (cargo.isEmpty()) throw new IllegalArgumentException("Cargo requires an executable");
            for (Path path : List.of(Path.of(cargo.getFirst()), directory, rustc, wrapper, frida))
                if (!path.isAbsolute() || !path.equals(path.normalize()))
                    throw new IllegalArgumentException("Cargo paths must be absolute and normalized");
            for (Path path : imageHashes.keySet())
                if (!path.isAbsolute() || !path.equals(path.normalize()))
                    throw new IllegalArgumentException("Permitted images must have absolute normalized paths");
            if (jobs <= 0 || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("Cargo capacity and control interval must be positive");
            try { timeout.toNanos(); }
            catch (ArithmeticException failure)
            {
                throw new IllegalArgumentException("Cargo control interval exceeds signed nanoseconds", failure);
            }
            if (!imageHashes.containsKey(wrapper))
                throw new IllegalArgumentException("Packaged wrapper image must be pinned");
            if (environment.entrySet().stream().anyMatch(entry ->
                entry.getKey().equalsIgnoreCase("RUSTC_WORKSPACE_WRAPPER") && !entry.getValue().isEmpty()))
                throw new IllegalArgumentException("Nested workspace wrappers are outside the approved launch chain");
        }
    }

    /**
     * Attempts an independent disposal batch once after its caller has established every required safety barrier.
     * A failed batch retains its first exact failure without repeating uncertain effects.
     */
    public static final class Cleanup
    {
        /** Whether any owner disposal has begun; no uncertain operation may be repeated. */
        private boolean attempted;
        /** First exact disposal failure, retained across later calls. */
        private Throwable failure;

        /** Creates a resource-free disposal guard. */
        public Cleanup() {}

        /**
         * Attempts every independent owner even when an earlier owner fails.
         * Callers must establish quiescence before invoking this operation.
         * @param owners independent close operations whose dependencies are already quiescent
         * @throws IOException if a checked owner disposal fails
         * @throws RuntimeException if an unchecked owner disposal fails first
         * @throws Error if a fatal owner disposal fails first
         */
        public void close(WindowsAttempt.Close... owners) throws IOException
        {
            if (attempted) { rethrow(failure); return; }
            attempted = true;
            for (WindowsAttempt.Close owner : owners)
            {
                try { owner.close(); }
                catch (IOException | RuntimeException | Error cause) { failure = combine(failure, cause); }
            }
            rethrow(failure);
        }
    }

    /**
     * Retains the concrete native owners through acquisition, service and staged cleanup.
     */
    public interface Resources
    {
        /**
         * Acquires native dependencies under this retained owner.
         * @throws IOException if any acquisition fails
         */
        void acquire() throws IOException;
        /**
         * Returns the configured attempt factory.
         * @return borrowed factory
         */
        JobQueue.AttemptFactory<WindowsProcess.Result> factory();
        /**
         * Returns complete memory observations.
         * @return borrowed observations
         */
        Controller.Measurements measurements();
        /**
         * Registers an immutable compiler request before submission.
         * @param request authenticated compiler request
         * @param position discovery position
         * @throws IOException if registration fails
         */
        void register(CargoCompilerRequest request, long position) throws IOException;
        /**
         * Supplies the estimator wrapping this invocation's actual native measurement source.
         * @return invocation-local estimator, or null when history is not configured or acquisition has not reached it
         */
        default CargoMemoryEstimates memoryEstimates() { return null; }
        /**
         * Reads the native facts a stall report adds to the queue state, without changing any owner.
         * @return attempt owners, unregistered wrapper pids and token accounting; empty when none are acquired
         */
        default StallWatch.Facts stallFacts() { return StallWatch.Facts.NONE; }
        /**
         * Consumes a validated whole-Job outcome before publication to the wrapper.
         * Only non-aborted zero-status completion can publish accepted residency as reusable history.
         * @param completion accepted whole-Job result and exact queue attempt ordinal
         */
        default void acceptedOutcome(JobQueue.Completion<WindowsProcess.Result> completion)
        {
            CargoMemoryEstimates estimates = memoryEstimates();
            if (estimates == null) return;
            WindowsProcess.Result result = completion.outcome().result();
            boolean successful = !completion.outcome().aborted() && !result.aborted() && result.rootExit() == 0;
            estimates.completed(completion.name(), completion.attemptSequence(), successful);
        }
        /**
         * Returns completed predictions and accepted sampled-residency observations.
         * @return immutable completed observations, or empty without an acquired estimator
         */
        public default List<CargoMemoryEstimates.Observation> memoryObservations()
        {
            CargoMemoryEstimates estimates = memoryEstimates();
            return estimates == null ? List.of() : estimates.observations();
        }
        /**
         * Installs the session and releases held Cargo last.
         * @param session retained coordinator callbacks
         * @throws IOException if release fails
         */
        void release(CargoSession<WindowsProcess.Result> session) throws IOException;
        /**
         * Services platform registrations and attempts.
         * @throws IOException if service fails
         */
        void pump() throws IOException;
        /**
         * Copies the next deferred blocked notification.
         * @return copied notification, or empty
         * @throws IOException if observation fails
         */
        Optional<SchedulerCoordinator.Blocked> pollBlocked() throws IOException;
        /**
         * Observes Cargo completion and retains its native result.
         * @return actual Cargo root result, or empty
         * @throws IOException if observation fails
         */
        OptionalInt pollToolResult() throws IOException;
        /**
         * Reports complete discovery including retained arrivals.
         * @return whether discovery is terminal
         */
        boolean inputClosed();
        /**
         * Reports queue-owned attempts retaining native dependencies.
         * @return whether an attempt remains owned
         */
        boolean hasOpenAttempts();
        /**
         * Stops arrivals without closing accepted endpoints.
         * @throws IOException if stopping is uncertain
         */
        void stopAccepting() throws IOException;
        /**
         * Collects every Cargo consumer before endpoint closure.
         * @throws IOException if collection remains incomplete
         */
        void closeConsumers() throws IOException;
        /**
         * Independently closes every retained endpoint without replaying native close uncertainty.
         * @throws IOException if endpoint cleanup remains incomplete
         */
        void closeEndpoints() throws IOException;
        /**
         * Closes the lease and outer dependencies after consumers and endpoints finish.
         * @throws IOException if cleanup remains incomplete
         */
        void closeDependencies() throws IOException;
    }


    /**
     * Composes the existing Job, attempt, creation, stream and semaphore owners for one real Cargo invocation.
     */
    private static final class NativeResources implements Resources
    {
        /** Borrowed invocation services, kept open by the composition boundary. */
        private final WindowsCargoScope scope;
        /** Trusted copied native launch boundaries. */
        private final Configuration configuration;
        /** Caller-owned completed history and immutable input boundaries, or null without history. */
        private final CargoMemoryEstimates.Settings memory;
        /** Actual measurement source wrapped with invocation-local sample and prediction state after acquisition. */
        private CargoMemoryEstimates estimates;
        /** Invocation correlation, shared by root observers and distinct compiler attempt identities. */
        private final UUID identity = UUID.randomUUID();
        /** Strict message codec borrowing the scope's immutable parser. */
        private final WindowsCargoMessages messages;
        /** Accepted control peers, retained even after uncertain registration or publication. */
        private final List<Peer> peers = new ArrayList<>();
        /** Authenticated requests, including partial imports, indexed by stable action identity. */
        private final Map<String, Peer> requests = new LinkedHashMap<>();
        /** Exclusive registration listener retained before Cargo release. */
        private WindowsCargoTransport.Listener listener;
        /** One invocation-wide named semaphore, retained before its Cargo lease. */
        private WindowsCargoJobserver jobserver;
        /** Root lease preserving Cargo's implicit slot and complete jobserver environment. */
        private WindowsCargoJobserver.CargoLease lease;
        /** Explicit token ownership across all compiler and root-descendant domains. */
        private WindowsCargoTokens tokens;
        /** Root descendants outside queued compiler Jobs borrow this invocation domain. */
        private WindowsCargoTokens.Domain rootDomain;
        /** Native runtime, retained before its one permitted initialization attempt. */
        private FridaRuntime runtime;
        /** Prepared Cargo root and every original descendant creation authority. */
        private WindowsProcess cargo;
        /** Checked partial process acquisition, if preparation did not return its owner. */
        private WindowsProcess.PreparationFailure preparationFailure;
        /** Creation-only root observation, sharing the existing sole manager resume path. */
        private WindowsCreationObserver observer;
        /** Complete Windows host/tree measurements borrowed by the coordinator. */
        private WindowsMeasurements measurements;
        /** Append-only factory exclusively lending each attempt to the shared queue. */
        private WindowsAttempts attempts;
        /** Coordinator callbacks installed before root Cargo is released. */
        private CargoSession<WindowsProcess.Result> session;
        /** Actual root status becomes present only after the entire Cargo Job is terminal. */
        private OptionalInt toolResult = OptionalInt.empty();
        /** Whether listener closure has been attempted; uncertain native effects are not repeated. */
        private boolean listenerStopAttempted;
        /** Exact listener closure failure, retained without replay. */
        private Throwable listenerStopFailure;
        /** Whether an outer peer service round is active. */
        private boolean pumping;
        /** Whether all Cargo consumers and their original handles have closed successfully. */
        private boolean consumersClosed;
        /** Independent consumer disposals, entered only after actual whole-Job zero. */
        private final Cleanup consumerCleanup = new Cleanup();
        /** Independent endpoint disposals, retaining exact uncertainty without replay. */
        private final Cleanup endpointCleanup = new Cleanup();
        /** Independent dependency disposals, entered only after every consumer and endpoint closes. */
        private final Cleanup dependencyCleanup = new Cleanup();

        /**
         * Creates only resource-free retained acquisition plans.
         * @param scope borrowed Pouch services
         * @param configuration trusted immutable native launch
         * @param memory caller-owned history settings, or null to retain unknown demand
         */
        private NativeResources(WindowsCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
        {
            this.scope = scope;
            this.configuration = configuration;
            this.memory = memory;
            this.messages = new WindowsCargoMessages(scope);
        }

        @Override public void acquire() throws IOException
        {
            rejectLauncherOverrides();
            jobserver = WindowsCargoJobserver.create(scope.tooling(), configuration.jobs());
            Map<String, String> environment = approvedEnvironment(configuration);
            String endpoint = WindowsCargoTransport.endpoint();
            environment.put(WindowsCargoWrapperMain.ENDPOINT_ENV, endpoint);
            environment.put(WindowsCargoWrapperMain.CONTROLLER_PID_ENV, Long.toString(ProcessHandle.current().pid()));
            try (var nativeIdentity = new WindowsCargoWrapperMain.Identity(scope))
            {
                environment.put(WindowsCargoWrapperMain.CONTROLLER_BIRTH_ENV,
                    Long.toString(nativeIdentity.birth(ProcessHandle.current().pid())));
            }
            lease = jobserver.retainCargo(environment);
            tokens = new WindowsCargoTokens(lease);
            listener = WindowsCargoTransport.listen(scope, endpoint, configuration.timeout());
            runtime = new FridaRuntime(scope.tooling(), configuration.frida());
            runtime.start();
            measurements = new WindowsMeasurements(scope.tooling());
            if (memory != null) estimates = memory.observe(measurements);
            attempts = new WindowsAttempts(identity, Map.of(), configuration.timeout(),
                (command, sequence, attemptIdentity, timeout) ->
                    new CargoAttemptResources(command, sequence, attemptIdentity, timeout));
            try
            {
                cargo = WindowsProcess.prepare(scope.tooling(), configuration.cargo(), configuration.directory(),
                    lease.environment(), configuration.input(), configuration.output(), configuration.error());
            }
            catch (WindowsProcess.PreparationFailure failure) { preparationFailure = failure; throw failure; }
            rootDomain = tokens.retain(identity);
            var rootImages = WindowsCargoTokenHook.factory(scope.tooling(), identity, lease.semaphoreName(),
                rootDomain, configuration.timeout());
            observer = new WindowsCreationObserver(scope.tooling(), identity, runtime, cargo, configuration.timeout(),
                this::pump, (installation, pid, manager) ->
                    pid == cargo.pid() ? null : rootImages.create(installation, pid, manager));
        }

        /**
         * Rejects external jpackage configuration authority before a launcher can execute.
         * @throws IOException if package metadata or a user override cannot be trusted
         */
        private void rejectLauncherOverrides() throws IOException
        {
            Path application = configuration.wrapper().getParent().resolve("app");
            Path metadata = application.resolve(".package");
            if (!Files.exists(metadata)) return;
            var lines = Files.readAllLines(metadata, StandardCharsets.UTF_8);
            if (lines.isEmpty() || lines.getFirst().isEmpty()) return;
            String name = lines.getFirst();
            if (Path.of(name).getNameCount() != 1 || name.equals(".") || name.equals(".."))
                throw new IOException("Packaged launcher metadata has an unsupported package name");
            for (String variable : List.of("LOCALAPPDATA", "APPDATA"))
            {
                String base = configuration.environment().get(variable);
                if (base != null && Files.exists(Path.of(base).resolve(name).resolve("codex-cargo-wrapper.cfg")))
                    throw new IOException("External user configuration can override the sealed Cargo launcher");
            }
        }

        @Override public JobQueue.AttemptFactory<WindowsProcess.Result> factory() { return attempts; }
        @Override public Controller.Measurements measurements() { return estimates == null ? measurements : estimates; }
        @Override public CargoMemoryEstimates memoryEstimates() { return estimates; }
        @Override public StallWatch.Facts stallFacts()
        {
            List<Long> pending = new ArrayList<>();
            for (Peer peer : peers)
                if (peer.request == null) pending.add(peer.connection.peerPid());
            StallWatch.TokenFacts tokenFacts = null;
            if (tokens != null)
            {
                WindowsCargoTokens.Accounting counts = tokens.accounting();
                int capacity = tokens.capacity();
                tokenFacts = new StallWatch.TokenFacts(capacity, counts.held(), counts.outstanding(),
                    Math.max(0, capacity - counts.outstanding()));
            }
            return new StallWatch.Facts(attempts == null ? List.of() : attempts.facts(), pending, tokenFacts);
        }
        @Override public void register(CargoCompilerRequest request, long position) throws IOException
        {
            Peer peer = requests.get(request.name());
            if (peer == null || peer.request != request || peer.streams == null)
                throw new IOException("Compiler registration has no retained authenticated stream owner");
            attempts.register(request.name(), new WindowsAttempts.Command(new Controller.Action(request.name(), position),
                request.arguments(), request.directory(), request.environment(),
                peer.streams.input(), peer.streams.output(), peer.streams.error()));
        }
        @Override public void release(CargoSession<WindowsProcess.Result> value) throws IOException
        {
            session = value;
            observer.start();
        }
        @Override public void pump() throws IOException
        {
            boolean outer = !pumping;
            pumping = true;
            try
            {
                if (observer != null) observer.pump(outer);
                if (attempts != null) attempts.pumpAll();
                if (outer && !listenerStopAttempted)
                {
                    WindowsCargoTransport.Connection connection;
                    while ((connection = listener.poll()) != null) peers.add(new Peer(connection));
                }
                for (Peer peer : List.copyOf(peers))
                {
                    if (peer.responded) continue;
                    if (peer.request != null)
                    {
                        try { peer.receipt.requireLive(); }
                        catch (IOException failure)
                        {
                            session.wrapperDisconnected(peer.request.name());
                            throw failure;
                        }
                        continue;
                    }
                    byte[] frame = peer.connection.poll();
                    if (frame != null) authenticate(peer, messages.readRegistration(frame));
                }
            }
            finally { pumping = !outer; }
        }

        /**
         * Binds a complete registration to original native authority and independent stream copies before submission.
         * @param peer retained native pipe connection
         * @param registration untrusted bounded wrapper frame
         * @throws IOException if identity, launch, stream acquisition or acknowledgement is uncertain
         */
        private void authenticate(Peer peer, WindowsCargoMessages.Registration registration) throws IOException
        {
            CargoCompilerRequest request = registration.request();
            if (peer.connection.peerPid() != request.wrapper().pid())
                throw new IOException("Wrapper claim differs from the native pipe peer");
            var source = WindowsCargoRegistration.nativeSource(scope.tooling(), cargo, observer,
                peer.connection.peerPid(), configuration.wrapper(), configuration.imageHashes());
            WindowsCargoRegistration.authenticate(request, source, new WindowsCargoRegistration.Configuration(
                configuration.rustc(), cargo.pid(), cargo.birth(), Map.of()));
            if (requests.putIfAbsent(request.name(), peer) != null)
                throw new IOException("Wrapper logical request identity is already reserved");
            if (estimates != null) request = estimates.register(request);
            peer.request = request;
            WindowsCreationReceipt receipt = observer.receipt(request.wrapper().pid()).orElseThrow(
                () -> new IOException("Authenticated wrapper lost original creation authority"));
            peer.receipt = receipt;
            peer.streams = receipt.importStandardStreams(MemorySegment.ofAddress(registration.input()),
                MemorySegment.ofAddress(registration.output()), MemorySegment.ofAddress(registration.error()));
            peer.connection.send(messages.accepted());
            session.registerAuthenticated(request, result ->
            {
                peer.streams.close();
                peer.connection.send(messages.finalResult(result.rootExit()));
                peer.responded = true;
            });
        }

        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked()
        {
            return attempts.pollBlocked().map(value -> new SchedulerCoordinator.Blocked(value.name(),
                value.attemptSequence(), value.waitSequence()));
        }
        @Override public OptionalInt pollToolResult() throws IOException
        {
            if (cargo != null && toolResult.isEmpty())
            {
                var result = cargo.poll();
                if (result.isPresent()) toolResult = OptionalInt.of((int) result.orElseThrow().rootExit());
            }
            return toolResult;
        }
        @Override public boolean inputClosed() { return toolResult.isPresent(); }
        @Override public boolean hasOpenAttempts() { return attempts != null && attempts.hasOpenAttempts(); }
        @Override public void stopAccepting() throws IOException
        {
            if (listener == null) return;
            if (listenerStopAttempted) { rethrow(listenerStopFailure); return; }
            listenerStopAttempted = true;
            try { listener.stopAccepting(); }
            catch (IOException | RuntimeException | Error failure) { listenerStopFailure = failure; throw failure; }
        }
        @Override public void closeConsumers() throws IOException
        {
            if (consumersClosed) return;
            if (consumerCleanup.attempted) { consumerCleanup.close(); return; }
            if (cargo != null)
            {
                if (observer != null) observer.stop();
                if (cargo.poll().isEmpty()) cargo.terminate();
                try { cargo.waitFor(configuration.timeout()); }
                catch (TimeoutException failure) { throw new IOException("Cargo Job remains live during cleanup", failure); }
            }
            // A failed preparation retains its own retryable Job-zero observation and uncertain-release guards.
            if (preparationFailure != null) preparationFailure.close();
            // Whole-Job zero cannot reverse: independent releases remain safe even when token accounting fails.
            List<WindowsAttempt.Close> owners = new ArrayList<>();
            if (observer != null) owners.add(() -> observer.close(() -> true));
            if (rootDomain != null) owners.add(() -> rootDomain.retire(() -> true));
            for (Peer peer : peers)
                if (peer.streams != null) owners.add(peer.streams::close);
            if (cargo != null) owners.add(cargo::close);
            consumerCleanup.close(owners.toArray(WindowsAttempt.Close[]::new));
            consumersClosed = true;
        }
        @Override public void closeEndpoints() throws IOException
        {
            endpointCleanup.close(peers.stream().map(peer -> (WindowsAttempt.Close) peer.connection::close)
                .toArray(WindowsAttempt.Close[]::new));
        }
        @Override public void closeDependencies() throws IOException
        {
            List<WindowsAttempt.Close> owners = new ArrayList<>();
            if (listener != null) owners.add(listener::close);
            if (tokens != null) owners.add(tokens::close);
            if (lease != null) owners.add(lease::close);
            if (jobserver != null) owners.add(jobserver::close);
            if (runtime != null) owners.add(runtime::close);
            dependencyCleanup.close(owners.toArray(WindowsAttempt.Close[]::new));
        }

        /**
         * Retains one native pipe peer and the logical request resources it successfully acquires.
         */
        private static final class Peer
        {
            /** Native authenticated-candidate endpoint retained before frame parsing. */
            private final WindowsCargoTransport.Connection connection;
            /** Immutable authenticated request, installed before stream import or acknowledgement. */
            private CargoCompilerRequest request;
            /** Original native source authority checked throughout execution, never reconstructed by PID. */
            private WindowsCreationReceipt receipt;
            /** Imported source endpoints, retained across all attempt incarnations. */
            private WindowsProcess.StandardStreams streams;
            /** Whether exact terminal publication completed, never used to infer a compiler result. */
            private boolean responded;
            /**
             * Retains an accepted transport owner before registration can fail.
             * @param connection native pipe candidate
             */
            private Peer(WindowsCargoTransport.Connection connection) { this.connection = connection; }
        }

        /**
         * Adds per-attempt token ownership to the existing Windows attempt acquisition plan.
         */
        private final class CargoAttemptResources implements WindowsAttempt.Resources, WindowsAttempt.Close
        {
            /** Existing native attempt owner, borrowing a lazy image-token factory. */
            private final WindowsAttempt.NativeResources delegate;
            /** Persistent wrapper authority, retained by the root Cargo Job across retry incarnations. */
            private final Peer peer;
            /** Native attempt correlation used for its exclusive token domain. */
            private final UUID attemptIdentity;
            /** Prepared whole-Job authority, retained before native instrumentation acquisition. */
            private WindowsAttempt.Process process;
            /** Attempt token domain, installed before any image protocol can start. */
            private WindowsCargoTokens.Domain domain;
            /** Whether this resource-free registration has already closed successfully. */
            private boolean closed;
            /** Independent domain and measurement disposals after this attempt reaches whole-Job zero. */
            private final Cleanup cleanup = new Cleanup();

            /**
             * Creates resource-free native/token composition for an exact issued coordinator receipt.
             * @param command registered immutable native launch
             * @param sequence exact global attempt ordinal
             * @param attemptIdentity distinct native correlation
             * @param timeout positive control interval
             * @throws IllegalStateException if the shared session did not issue this attempt
             */
            private CargoAttemptResources(WindowsAttempts.Command command, long sequence, UUID attemptIdentity, Duration timeout)
            {
                CargoSession.Attempt receipt = session.attempt(sequence).orElseThrow(
                    () -> new IllegalStateException("Cargo attempt has no issued coordinator receipt"));
                if (!receipt.request().name().equals(command.action().name()))
                    throw new IllegalStateException("Cargo attempt receipt names a different logical compiler");
                this.attemptIdentity = attemptIdentity;
                peer = Objects.requireNonNull(requests.get(receipt.request().name()), "authenticated peer");
                delegate = new WindowsAttempt.NativeResources(scope.tooling(), runtime, measurements, command,
                    sequence, attemptIdentity, timeout, (installation, pid, manager) ->
                        WindowsCargoTokenHook.factory(scope.tooling(), attemptIdentity, lease.semaphoreName(), domain,
                            timeout).create(installation, pid, manager));
            }
            @Override public WindowsAttempt.Process prepare() throws IOException
            {
                peer.receipt.requireLive();
                process = delegate.prepare();
                return process;
            }
            @Override public WindowsAttempt.Close register(WindowsAttempt.Process value)
            {
                delegate.register(value);
                return this;
            }
            @Override public WindowsAttempt.Hooks hooks(WindowsAttempt.Process value, AllocationWaits waits,
                AllocationHook.Pump peers) throws IOException
            {
                domain = tokens.retain(attemptIdentity);
                return delegate.hooks(value, waits, peers);
            }
            @Override public void close() throws IOException
            {
                if (closed) return;
                if (cleanup.attempted) { cleanup.close(); return; }
                if (process != null && process.poll().isEmpty())
                    throw new IOException("Attempt cleanup requires actual whole-Job zero");
                cleanup.close(() -> { if (domain != null) domain.retire(() -> true); }, delegate::close);
                closed = true;
            }
            @Override public void closePreparationFailure() throws IOException { delegate.closePreparationFailure(); }
        }
    }
}
