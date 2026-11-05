package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Owns one Cargo root, authenticated wrappers and their invocation-wide native dependencies.
 * Construction is resource-free. Calls belong to the constructing thread; the queue exclusively closes attempts.
 * The caller retains its Cargo scope and exclusive invocation directories until {@link #isClosed()} is true.
 * After failed session cleanup the caller retains this owner and may call close to progress safe cleanup; observation
 * never retries native effects. Failed descriptor closes and uncertain publication are never replayed.
 */
public final class MacCargoInvocation implements CargoSession.Invocation
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
    private CargoSession<Integer> session;
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
     * Creates the native invocation owner without acquiring any native resource.
     * @param scope borrowed Cargo services, retained open until whole cleanup succeeds
     * @param configuration trusted immutable launch and containment boundaries
     * @throws NullPointerException if an input is null
     */
    public MacCargoInvocation(MacCargoScope scope, Configuration configuration)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration"), null));
    }

    /**
     * Owns native compiler execution with explicitly retained completed-work history across invocations.
     * Missing, corrupt or inapplicable profiles retain conservative unknown-demand admission.
     * @param scope borrowed Cargo services retained through whole cleanup
     * @param configuration trusted immutable launch and containment boundaries
     * @param memory caller-owned history and complete source/toolchain identity boundaries
     * @throws NullPointerException if an input is null
     */
    public MacCargoInvocation(MacCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration"), Objects.requireNonNull(memory, "memory")));
    }

    /**
     * Creates the same lifecycle owner with controlled invocation-specific native boundaries.
     * @param resources retained acquisition, service and cleanup owner
     * @throws NullPointerException if resources is null
     */
    public MacCargoInvocation(Resources resources)
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
            session = new CargoSession<>(resources.factory(), resources.measurements(), resources::register, this, resources::acceptedOutcome);
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
    public CargoSession<Integer> session()
    {
        requireThread();
        if (!started) throw new IllegalStateException("Cargo invocation did not start successfully");
        return session;
    }

    /**
     * Returns completed prediction/observation pairs for best-effort estimate calibration.
     * @return immutable completed compiler observations, or empty when history was not supplied
     * @throws IllegalStateException if another thread calls
     */
    public List<CargoMemoryEstimates.Observation> memoryObservations()
    {
        requireThread();
        return resources.memoryObservations();
    }

    /**
     * Reads genuine decoded allocation frames with original authenticated compiler correlation.
     * Reports survive native retirement and invocation closure; inspection never pumps or grants.
     * @return immutable original reports, empty before native delivery
     * @throws IllegalStateException if another thread calls
     */
    public List<MacAttempts.AllocationReceipt> allocationReceipts()
    {
        requireThread();
        return List.copyOf(resources.allocationReceipts());
    }

    /**
     * Returns native compiler retirement receipts in successful retirement order, independently of outcome acceptance.
     * @return immutable receipts, including superseded attempts that completed collection
     * @throws IllegalStateException if another thread calls
     */
    public List<MacAttempts.Retirement> attemptRetirements()
    {
        requireThread();
        return resources.attemptRetirements();
    }

    /**
     * Returns original Cargo retirement after native, image and root token ownership close successfully.
     * @return authentic root identity and result, or empty before complete consumer cleanup
     * @throws IllegalStateException if another thread calls
     */
    public Optional<RootRetirement> originalCargoRetirement()
    {
        requireThread();
        return resources.originalCargoRetirement();
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
        private final transient MacCargoInvocation owner;

        /**
         * Retains the owner without retaining a Throwable as a cause.
         * @param owner original thread-confined partial invocation
         */
        private CleanupFailure(MacCargoInvocation owner)
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
        JobQueue.AttemptFactory<Integer> factory();
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
         * Observes one validated complete native outcome before final response publication.
         * @param completion consumed actual whole-tree result, including abortion classification
         */
        default void acceptedOutcome(JobQueue.Completion<Integer> completion) {}
        /**
         * Returns completed prediction/observation pairs when profiling is configured.
         * @return immutable completed observations, or empty without profiling
         */
        public default List<CargoMemoryEstimates.Observation> memoryObservations() { return List.of(); }
        /**
         * Reads retained decoded compiler allocation reports without native operations.
         * @return immutable original reports with authenticated compiler correlation
         */
        public default List<MacAttempts.AllocationReceipt> allocationReceipts() { return List.of(); }
        /**
         * Returns successful native compiler retirements independently of coordinator acceptance.
         * @return immutable production retirement receipts
         */
        public default List<MacAttempts.Retirement> attemptRetirements() { return List.of(); }
        /**
         * Returns original Cargo retirement after successful native, image and root token closure.
         * @return authentic root receipt, or empty before complete consumer cleanup
         */
        public default Optional<RootRetirement> originalCargoRetirement() { return Optional.empty(); }
        /**
         * Installs the session and releases held Cargo last.
         * @param session retained coordinator callbacks
         * @throws IOException if release fails
         */
        void release(CargoSession<Integer> session) throws IOException;
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
     * Pins installed tools, original descriptors and caller-owned native namespaces for one invocation.
     * The caller provisions root-owned mode0700 Cargo state and search-only workload access to endpoint parents.
     * Compiler scratch directories are freshly owned by each attempt and never shared across retries.
     * @param cargo exact original Cargo launch and borrowed standard descriptor descriptions
     * @param registration trusted wrapper/compiler authentication requirements
     * @param controllerEndpoint fresh absolute socket name under caller-controlled immutable ancestry
     * @param cargoLibrary trusted installed Cargo bridge library
     * @param frida pinned native Frida library
     * @param scratchParent caller-owned root-private parent for fresh compiler launch directories
     * @param jobs total positive jobserver capacity including Cargo implicit slot
     * @param timeout positive bounded native readiness interval, not a tool runtime deadline
     */
    public record Configuration(MacWorkloadNative.Launch cargo, MacCargoRegistration.Configuration registration,
        Path controllerEndpoint, Path cargoLibrary, Path frida, Path scratchParent, int jobs, Duration timeout)
    {
        /**
         * Validates immutable boundaries without acquiring any native or filesystem resources.
         * @param cargo exact original Cargo launch
         * @param registration independent trusted wrapper/compiler policy
         * @param controllerEndpoint fresh absolute endpoint
         * @param cargoLibrary trusted native registration bridge
         * @param frida trusted native instrumentation library
         * @param scratchParent root-controlled compiler state parent
         * @param jobs capacity from one through1024
         * @param timeout positive representable readiness bound
         * @throws NullPointerException if any input is null
         * @throws IllegalArgumentException if trusted commands, credentials, environment or bounds disagree
         */
        public Configuration
        {
            Objects.requireNonNull(cargo, "cargo");
            Objects.requireNonNull(registration, "registration");
            for (Path path : List.of(controllerEndpoint, cargoLibrary, frida, scratchParent))
                if (!path.isAbsolute() || !path.equals(path.normalize()))
                    throw new IllegalArgumentException("Mac Cargo paths must be absolute and normalized");
            Objects.requireNonNull(timeout, "timeout");
            if (jobs < 1 || jobs > 1024 || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("Mac Cargo requires bounded positive capacity and readiness timeout");
            try { timeout.toNanos(); }
            catch (ArithmeticException failure) { throw new IllegalArgumentException("Readiness timeout is too large", failure); }
            Map<String, String> environment = environment(cargo.environment());
            if (cargo.uid() <= 0 || cargo.gid() <= 0 || cargo.uid() != registration.uid() || cargo.gid() != registration.gid() ||
                !cargo.command().getFirst().equals(registration.cargoExecutable().toString()) ||
                !registration.rustcExecutable().toString().equals(environment.get("RUSTC")) ||
                !registration.wrapperExecutable().toString().equals(environment.get("RUSTC_WRAPPER")) ||
                !controllerEndpoint.toString().equals(environment.get("CODEX_CARGO_SOCKET")))
                throw new IllegalArgumentException("Cargo root and authenticated compiler boundaries disagree");
            for (var required : registration.requiredEnvironment().entrySet())
                if (!required.getValue().equals(environment.get(required.getKey())))
                    throw new IllegalArgumentException("Cargo environment differs from trusted compiler requirements");
        }
    }

    /** Retains concrete producer owners before every native effect and through ordered terminal cleanup. */
    private static final class NativeResources implements Resources
    {
        /** Borrowed scoped services retained through every native owner. */
        private final MacCargoScope scope;
        /** Immutable trusted launch and namespace boundaries. */
        private final Configuration configuration;
        /** Optional explicitly supplied completed-work profile boundary. */
        private final CargoMemoryEstimates.Settings memory;
        /** Concrete listener retains all accepted and partially acquired peers/FIFOs. */
        private final MacCargoNative nativeCargo;
        /** Authenticated requests and original peer stream descriptions across retries. */
        private final Map<String, Wrapper> wrappers = new LinkedHashMap<>();
        /** Root-owned jobserver owner retained before open. */
        private MacCargoJobserver jobserver;
        /** One invocation-wide accounting lease. */
        private MacCargoJobserver.CargoLease lease;
        /** Root Cargo token accounting retained through its complete terminal barrier. */
        private LinuxCargoTokens.Attempt rootTokens;
        /** Native runtime retained before startup. */
        private FridaRuntime runtime;
        /** Scoped original runtime borrow retained through complete invocation dependency cleanup. */
        private MacCargoScope.RuntimeLease runtimeLease;
        /** Original Cargo manager retained even before ownership transfers to native Cargo. */
        private FridaRuntime.Manager manager;
        /** Original held Cargo native owner, including failed root acquisition. */
        private MacWorkloadNative.Owner cargoOwner;
        /** Original Cargo instrumentation and terminal-composition owner. */
        private MacWorkload cargo;
        /** Token-only original Cargo image instrumentation. */
        private MacCargoInstrumentation cargoInstrumentation;
        /** Complete native compiler measurement registry. */
        private MacMeasurements.NativeSource source;
        /** Complete accepted-batch native measurements. */
        private Controller.Measurements measurements;
        /** Optional estimator wrapping exactly the same native observations. */
        private CargoMemoryEstimates estimates;
        /** Queue-owned compiler attempt factory. */
        private MacAttempts attempts;
        /** Authentication policy bound to actual Cargo root authority. */
        private MacCargoRegistration registration;
        /** Session installed before original Cargo release. */
        private CargoSession<Integer> session;
        /** Genuine original Cargo result after complete native terminal composition. */
        private OptionalInt result = OptionalInt.empty();
        /** Prevents further authenticated publications during cleanup. */
        private boolean stopping;
        /** Original root incarnation captured from native authority before workload release. */
        private List<Long> rootAuthority = List.of();
        /** Indicates whether cleanup requested termination before genuine completion. */
        private boolean rootTerminationRequested;
        /** Indicates that actual root token retirement returned successfully. */
        private boolean rootTokensRetired;
        /** Uncertain root token destruction retained without replay or false successful retirement. */
        private Throwable rootTokenFailure;
        /** Successful original Cargo retirement retained independently of coordinator acceptance. */
        private RootRetirement rootRetirement;
        /** Confirms an empty native queue after original Cargo completion. */
        private boolean drained;

        /**
         * Retains resource-free producer ownership before acquisition.
         * @param scope borrowed scoped services
         * @param configuration trusted launch boundaries
         * @param memory optional explicit completed-work history
         */
        private NativeResources(MacCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
        {
            this.scope = scope;
            this.configuration = configuration;
            this.memory = memory;
            nativeCargo = new MacCargoNative(scope.tooling(), configuration.cargoLibrary());
        }

        @Override public void acquire() throws IOException
        {
            Configuration config = configuration;
            nativeCargo.open(config.controllerEndpoint(), config.cargo().uid(), config.cargo().gid());
            jobserver = new MacCargoJobserver(nativeCargo);
            jobserver.open(config.controllerEndpoint().getParent(), config.cargo().uid(), config.cargo().gid(), config.jobs());
            lease = jobserver.retainCargo(environment(config.cargo().environment()));
            runtimeLease = scope.runtime(config.frida());
            runtime = runtimeLease.value();
            source = new MacMeasurements.NativeSource(scope.tooling());
            measurements = new MacMeasurements(source);
            if (memory != null)
            {
                estimates = memory.observe(measurements);
                measurements = estimates;
            }
            attempts = new MacAttempts(scope.tooling(), runtime, source, config.cargo().library(), config.cargo().helper(),
                config.cargo().modules(), config.scratchParent(), config.cargo().uid(), config.cargo().gid(), config.timeout(), new AllocationHooks.Routing());
            manager = runtime.newManager();
            manager.start();
            MacWorkloadNative.Launch launch = withEnvironment(config.cargo(), lease.environment());
            cargoOwner = new MacWorkloadNative.Owner(scope.tooling(), launch, manager);
            MacImages.Identity root = cargoOwner.root();
            rootAuthority = cargoOwner.authority(root);
            MacCargoRegistration.Configuration policy = registrationFor(config.registration(), lease.environment());
            registration = estimates == null ? new MacCargoRegistration(policy, cargoOwner, root) :
                new MacCargoRegistration(policy, cargoOwner, root, estimates);
            rootTokens = lease.retainAttempt();
            cargoInstrumentation = new MacCargoInstrumentation(scope.tooling(), UUID.randomUUID(), manager, cargoOwner,
                lease, rootTokens, new AllocationWaits(), config.timeout(), attempts::pumpAll, MacCargoInstrumentation.Mode.ROOT_CARGO);
            cargo = new MacWorkload(scope.tooling(), cargoOwner, cargoInstrumentation, config.timeout(), System.err::println);
        }

        @Override public JobQueue.AttemptFactory<Integer> factory() { return attempts; }
        @Override public Controller.Measurements measurements() { return measurements; }
        @Override public void register(CargoCompilerRequest request, long position) throws IOException
        {
            Wrapper wrapper = wrappers.get(request.name());
            if (wrapper == null || !wrapper.request().equals(request))
                throw new IOException("Compiler registration lacks its authenticated retained original peer");
            attempts.register(request.name(), request, wrapper.peer(), lease);
        }
        @Override public void release(CargoSession<Integer> value) throws IOException
        {
            session = value;
            cargo.start();
        }
        @Override public void acceptedOutcome(JobQueue.Completion<Integer> completion)
        {
            if (estimates != null)
                estimates.completed(completion.name(), completion.attemptSequence(),
                    !completion.outcome().aborted() && completion.outcome().result() == 0);
        }
        @Override public List<CargoMemoryEstimates.Observation> memoryObservations()
        {
            return estimates == null ? List.of() : estimates.observations();
        }
        @Override public List<MacAttempts.AllocationReceipt> allocationReceipts()
        {
            return attempts == null ? List.of() : attempts.allocationReceipts();
        }
        @Override public List<MacAttempts.Retirement> attemptRetirements()
        {
            return attempts == null ? List.of() : attempts.retirements();
        }
        @Override public Optional<RootRetirement> originalCargoRetirement()
        {
            return Optional.ofNullable(rootRetirement);
        }
        @Override public void pump() throws IOException
        {
            if (!stopping)
            {
                Optional<MacCargoNative.Peer> next = nativeCargo.accept();
                if (next.isPresent())
                {
                    MacCargoNative.Peer peer = next.orElseThrow();
                    CargoCompilerRequest request = registration.authenticate(peer);
                    Wrapper wrapper = new Wrapper(request, peer);
                    if (wrappers.putIfAbsent(request.name(), wrapper) != null)
                        throw new IOException("Duplicate authenticated compiler request");
                    session.registerAuthenticated(request, status ->
                    {
                        // Original descriptor imports remain retained until all queue owners using them have closed.
                        if (attempts.hasOpenAttempts(request.name()))
                            throw new IOException("Compiler attempt still retains the original peer descriptors");
                        peer.respond(status);
                        peer.close();
                    });
                }
                else if (result.isPresent()) drained = true;
            }
            attempts.pumpAll();
            if (cargoInstrumentation != null && result.isEmpty()) cargoInstrumentation.pump();
        }
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked()
        {
            return attempts.pollBlocked().map(event ->
                new SchedulerCoordinator.Blocked(event.name(), event.attemptSequence(), event.waitSequence()));
        }
        @Override public OptionalInt pollToolResult() throws IOException
        {
            if (result.isEmpty()) result = cargo.advance();
            return result;
        }
        @Override public boolean inputClosed() { return result.isPresent() && drained; }
        @Override public boolean hasOpenAttempts() { return attempts != null && attempts.hasOpenAttempts(); }
        @Override public void stopAccepting() { stopping = true; }

        @Override public void closeConsumers() throws IOException
        {
            // Closing imported peer descriptions before waiting for Cargo pipe EOF avoids a descriptor-lifetime cycle.
            if (hasOpenAttempts()) throw new IOException("Compiler attempts still retain original peer descriptors");
            closePeers();
            if (cargo != null)
            {
                if (cargo.completedResult().isEmpty()) rootTerminationRequested = true;
                cargo.cancel();
                while (cargo.completedResult().isEmpty())
                {
                    try { cargo.advance(); }
                    catch (IOException | RuntimeException | Error failure)
                    {
                        if (cargo.completedResult().isEmpty()) throw failure;
                    }
                    if (cargo.completedResult().isEmpty()) pause();
                }
            }
            else if (cargoOwner != null)
            {
                cargoOwner.cancel();
                while (cargoOwner.terminalResult().isEmpty()) pause();
                cargoOwner.close();
            }
            else if (manager != null)
            {
                manager.close();
                manager = null;
            }
            rethrow(rootTokenFailure);
            if (rootTokens != null)
            {
                try { rootTokens.close(); }
                catch (IOException | RuntimeException | Error failure)
                {
                    rootTokenFailure = failure;
                    throw failure;
                }
                rootTokens = null;
                rootTokensRetired = true;
            }
            if (rootRetirement == null && rootTokensRetired && cargo != null && cargo.completedResult().isPresent() && cargoOwner.isClosed())
                rootRetirement = new RootRetirement(rootAuthority, cargo.completedResult().getAsInt(), rootTerminationRequested);
        }
        @Override public void closeEndpoints() throws IOException { closePeers(); }
        @Override public void closeDependencies() throws IOException
        {
            if (lease != null) { lease.close(); lease = null; }
            if (jobserver != null) { jobserver.close(); jobserver = null; }
            // createFifo retains failed acquisitions before returning; close every remaining retained partial owner.
            for (MacCargoNative.Fifo fifo : nativeCargo.fifos()) fifo.close();
            nativeCargo.close();
            if (runtimeLease != null) { runtimeLease.close(); runtimeLease = null; runtime = null; }
        }

        /**
         * Closes every original and partially accepted peer after all compiler attempts have retired.
         * @throws IOException if any native descriptor destruction remains uncertain
         */
        private void closePeers() throws IOException
        {
            Throwable failure = null;
            for (MacCargoNative.Peer peer : nativeCargo.peers())
            {
                try { peer.close(); }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            rethrow(failure);
        }
    }

    /**
     * Records original Cargo collection after genuine terminal result, image and native closure,
     * and successful root token retirement. Empty resident snapshots cannot create this receipt.
     * @param rootAuthority immutable eighteen-field native original root identity captured before release
     * @param result genuine original root result
     * @param terminationRequested whether cleanup requested cancellation before terminal composition
     */
    public record RootRetirement(List<Long> rootAuthority, int result, boolean terminationRequested)
    {
        /**
         * Retains complete immutable native identity without replacing it with a process identifier.
         * @param rootAuthority native original root authority
         * @param result genuine original root result
         * @param terminationRequested whether cancellation preceded complete terminal composition
         */
        public RootRetirement
        {
            rootAuthority = List.copyOf(rootAuthority);
            if (rootAuthority.size() != 18) throw new IllegalArgumentException("Complete native root authority required");
        }
    }

    /**
     * Retains authenticated execution inputs and exact original peer streams across every retry.
     * @param request independently authenticated compiler work
     * @param peer original retained kernel peer and imported stream descriptions
     */
    private record Wrapper(CargoCompilerRequest request, MacCargoNative.Peer peer) {}

    /**
     * Copies one original held launch with its invocation-owned jobserver environment.
     * @param launch trusted launch preserving all original descriptors and credentials
     * @param environment exact complete lease environment
     * @return equivalent launch with actual FIFO authorization
     */
    private static MacWorkloadNative.Launch withEnvironment(MacWorkloadNative.Launch launch, Map<String, String> environment)
    {
        return new MacWorkloadNative.Launch(launch.library(), launch.helper(), launch.modules(), launch.directory(), launch.workingDirectory(),
            launch.streams(), launch.command(), environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList(),
            launch.uid(), launch.gid());
    }

    /**
     * Returns the compiler registration policy for the environment Cargo actually received.
     * The jobserver lease may rewrite a required entry, such as {@code PATH} naming the make front end first; every
     * compiler then inherits the lease value, so registration requires that value instead of the original one.
     * @param trusted trusted registration policy built from the original Cargo environment
     * @param lease exact complete lease environment published to Cargo
     * @return equivalent policy whose required entries take the lease values
     * @throws IllegalArgumentException if the lease removes a required entry
     */
    public static MacCargoRegistration.Configuration registrationFor(MacCargoRegistration.Configuration trusted,
        Map<String, String> lease)
    {
        Map<String, String> required = new LinkedHashMap<>();
        for (String name : trusted.requiredEnvironment().keySet())
        {
            String value = lease.get(name);
            if (value == null) throw new IllegalArgumentException("Jobserver lease removed required Cargo environment entry: " + name);
            required.put(name, value);
        }
        return new MacCargoRegistration.Configuration(trusted.cargoExecutable(), trusted.wrapperExecutable(),
            trusted.rustcExecutable(), trusted.uid(), trusted.gid(), required);
    }

    /**
     * Converts already validated complete NAME=value entries without inheriting controller environment.
     * @param values validated exact native environment vector
     * @return independent insertion-ordered complete environment
     */
    private static Map<String, String> environment(List<String> values)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (String value : values)
        {
            int separator = value.indexOf('=');
            result.put(value.substring(0, separator), value.substring(separator + 1));
        }
        return result;
    }

    /**
     * Yields while retaining all native ownership through actual terminal observation.
     * @throws IOException if interrupted before the execution-impossible barrier
     */
    private static void pause() throws IOException
    {
        try { Thread.sleep(10); }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retaining native Cargo ownership", failure);
        }
    }
}
