package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import oshi.util.driver.linux.proc.ProcessStat;
import oshi.util.driver.linux.proc.ProcessStat.PidStat;

/**
 * Owns one Cargo root, authenticated wrappers and their invocation-wide native dependencies.
 * Construction is resource-free. Calls belong to the constructing thread; the queue exclusively closes attempts.
 * The caller retains its Cargo scope and exclusive invocation directories until {@link #isClosed()} is true.
 * After failed session cleanup the caller retains this owner and may call close to progress safe cleanup; observation
 * never retries native effects. Failed descriptor closes and uncertain publication are never replayed.
 */
public final class LinuxCargoInvocation implements CargoSession.Invocation
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
    private CargoSession<LinuxSupervisor.Result> session;
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
    public LinuxCargoInvocation(LinuxCargoScope scope, Configuration configuration)
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
    public LinuxCargoInvocation(LinuxCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration"), Objects.requireNonNull(memory, "memory")));
    }

    /**
     * Creates the same lifecycle owner with controlled invocation-specific native boundaries.
     * @param resources retained acquisition, service and cleanup owner
     * @throws NullPointerException if resources is null
     */
    public LinuxCargoInvocation(Resources resources)
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
    public CargoSession<LinuxSupervisor.Result> session()
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
    List<CargoMemoryEstimates.Observation> memoryObservations()
    {
        requireThread();
        return resources.memoryObservations();
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
        try
        {
            Optional<SchedulerCoordinator.Blocked> notification = resources.pollBlocked();
            notification.ifPresent(event -> System.getLogger(LinuxCargoInvocation.class.getName()).log(
                System.Logger.Level.INFO, "Linux Cargo allocation-blocked action=" + event.name() +
                    " attempt=" + event.attemptSequence() + " wait=" + event.waitSequence()));
            return notification;
        }
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
        private final transient LinuxCargoInvocation owner;

        /**
         * Retains the owner without retaining a Throwable as a cause.
         * @param owner original thread-confined partial invocation
         */
        private CleanupFailure(LinuxCargoInvocation owner)
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
     * Trusted caller-owned launch boundaries; invocation directories are exclusive and already provisioned.
     * The caller installs its actual wrapper launcher in the Cargo environment. No harness executable is selected here.
     * @param cargo exact Cargo root launch, using retained leader execution
     * @param root caller-selected file streams or borrowed inherited descriptors
     * @param registration trusted compiler context and complete native wrapper prefix
     * @param controllerEndpoint absent controller socket path in a caller-owned private directory
     * @param frida pinned native producer library
     * @param authority retained authority configuration for compiler admission
     * @param jobs positive FIFO capacity including Cargo's implicit token
     * @param timeout positive bounded native control wait, not a Cargo runtime deadline
     */
    public record Configuration(LinuxProcessTree.Launch cargo, RootInput root,
        LinuxCargoRegistration.Configuration registration, Path controllerEndpoint, Path frida,
        LinuxAuthority.Settings authority, int jobs, Duration timeout)
    {
        /**
         * Preserves file-root construction for existing resource-free callers.
         * @param cargo exact Cargo root launch
         * @param files caller-owned stream paths
         * @param registration trusted compiler launch policy
         * @param controllerEndpoint private controller socket path
         * @param frida pinned instrumentation library
         * @param authority compiler admission configuration
         * @param jobs positive FIFO capacity
         * @param timeout positive native control bound
         */
        public Configuration(LinuxProcessTree.Launch cargo, LinuxProcessTree.StandardFiles files,
            LinuxCargoRegistration.Configuration registration, Path controllerEndpoint, Path frida,
            LinuxAuthority.Settings authority, int jobs, Duration timeout)
        {
            this(cargo, new FileRoot(files), registration, controllerEndpoint, frida, authority, jobs, timeout);
        }

        /**
         * Validates consistent immutable native boundaries without filesystem acquisition.
         * @param cargo exact Cargo root launch using retained leader execution
         * @param root caller-selected file streams or borrowed inherited descriptors
         * @param registration trusted compiler context and wrapper prefix
         * @param controllerEndpoint absolute normalized absent controller socket path
         * @param frida absolute normalized producer library
         * @param authority authority configuration matching compiler context
         * @param jobs positive FIFO capacity
         * @param timeout positive control bound representable as signed nanoseconds
         * @throws NullPointerException if an input is null
         * @throws IllegalArgumentException if launch identities, paths or control bounds disagree
         */
        public Configuration
        {
            Objects.requireNonNull(cargo, "cargo");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(registration, "registration");
            Objects.requireNonNull(authority, "authority");
            for (Path path : List.of(controllerEndpoint, frida))
                if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException("Cargo paths must be absolute and normalized");
            Objects.requireNonNull(timeout, "timeout");
            if (jobs <= 0 || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Cargo capacity and timeout must be positive");
            try { timeout.toNanos(); }
            catch (ArithmeticException failure) { throw new IllegalArgumentException("Cargo timeout exceeds signed nanoseconds", failure); }
            var context = registration.context();
            if (cargo.executionMode() != LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC ||
                context.executionMode() != LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC ||
                !cargo.command().getFirst().equals(registration.cargoExecutable().toString()) ||
                cargo.uid() != context.uid() || cargo.gid() != context.gid() ||
                !cargo.scratchParent().equals(context.scratchParent()) ||
                !authority.scratch().equals(context.scratchParent()) ||
                !authority.admissionParent().equals(context.cgroupParent()) ||
                !cargo.javaExecutable().equals(context.javaExecutable()) || !cargo.classPath().equals(context.classPath()))
                throw new IllegalArgumentException("Cargo and compiler launch boundaries disagree");
            if (!registration.rustcExecutable().toString().equals(cargo.environment().get("RUSTC")) ||
                !Path.of(cargo.environment().getOrDefault("RUSTC_WRAPPER", "")).isAbsolute())
                throw new IllegalArgumentException("Cargo requires the pinned rustc and an absolute trusted wrapper launcher");
            for (var entry : registration.requiredEnvironment().entrySet())
                if (!entry.getValue().equals(cargo.environment().get(entry.getKey())))
                    throw new IllegalArgumentException("Cargo environment differs from trusted compiler requirements");
        }
    }

    /**
     * Selects exactly one root standard-stream acquisition mode without acquiring resources.
     */
    sealed interface RootInput permits FileRoot, BorrowedRoot {}

    /**
     * Selects caller-owned standard stream paths for existing native fixture and library callers.
     * @param files immutable input/output path selection
     */
    public record FileRoot(LinuxProcessTree.StandardFiles files) implements RootInput
    {
        /**
         * Validates the required file-stream selection.
         * @param files immutable standard stream paths
         * @throws NullPointerException if files is null
         */
        public FileRoot { Objects.requireNonNull(files, "files"); }
    }

    /**
     * Selects the invoking process's borrowed fd0/1/2 through a controller-owned private endpoint.
     * @param directory existing controller-owned0700 directory retained until invocation closure
     */
    public record BorrowedRoot(Path directory) implements RootInput
    {
        /**
         * Validates the resource-free private endpoint location.
         * @param directory existing controller-owned0700 directory
         * @throws NullPointerException if directory is null
         * @throws IllegalArgumentException if directory is not absolute and normalized
         */
        public BorrowedRoot
        {
            Objects.requireNonNull(directory, "directory");
            if (!directory.isAbsolute() || !directory.equals(directory.normalize()))
                throw new IllegalArgumentException("Root stream directory must be absolute and normalized");
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
        JobQueue.AttemptFactory<LinuxSupervisor.Result> factory();
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
        default void acceptedOutcome(JobQueue.Completion<LinuxSupervisor.Result> completion) {}
        /**
         * Returns completed prediction/observation pairs when profiling is configured.
         * @return immutable completed observations, or empty without profiling
         */
        default List<CargoMemoryEstimates.Observation> memoryObservations() { return List.of(); }
        /**
         * Installs the session and releases held Cargo last.
         * @param session retained coordinator callbacks
         * @throws IOException if release fails
         */
        void release(CargoSession<LinuxSupervisor.Result> session) throws IOException;
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
     * Implements native acquisition and retained cleanup using existing Linux owners.
     */
    private static final class NativeResources implements Resources
    {
        /**
         * Borrowed Pouch dependencies retained by the caller through whole cleanup.
         */
        private final LinuxCargoScope scope;
        /**
         * Trusted immutable launch boundaries.
         */
        private final Configuration configuration;
        /** Explicit caller-owned completed profile boundary, or null without history. */
        private final CargoMemoryEstimates.Settings memory;
        /** Invocation-local profile source borrowing complete native readings, or null without history. */
        private CargoMemoryEstimates estimates;
        /**
         * Every accepted connection, retained before any parsing or authentication.
         */
        private final List<LinuxCargoTransport.Connection> connections = new ArrayList<>();
        /**
         * Connections of authenticated wrappers by action name; each owns its accepted connection.
         */
        private final Map<String, LinuxCargoWrapperConnection> wrappers = new LinkedHashMap<>();
        /**
         * Partial stream acquisitions retained before a listener or connection can be returned.
         */
        private final List<LinuxBorrowedStreams.CleanupFailure> streamFailures = new ArrayList<>();
        /**
         * Controller listener, including failed close ownership.
         */
        private LinuxCargoTransport.Listener server;
        /**
         * FIFO owner retained until its sole Cargo lease is released.
         */
        private LinuxCargoJobserver jobserver;
        /**
         * Failed FIFO acquisition retaining retryable path cleanup.
         */
        private LinuxCargoJobserver.PreparationFailure jobserverFailure;
        /**
         * One invocation lease borrowed across all retries.
         */
        private LinuxCargoJobserver.CargoLease lease;
        /**
         * Retained native producer, including failed start.
         */
        private FridaRuntime runtime;
        /**
         * Build device whose manager the leader and every attempt borrow, retained until they close.
         */
        private LinuxFridaDevice device;
        /**
         * Retained native memory authority, including failed acquisition.
         */
        private LinuxAuthority authority;
        /**
         * Queue-owned native attempt registry.
         */
        private LinuxAttempts attempts;
        /**
         * Shared complete native observations.
         */
        private LinuxMeasurements measurements;
        /**
         * Held or released whole-Cargo native owner.
         */
        private LinuxSupervisor cargo;
        /** Owned root descriptor transfer, retained through sender and listener closure. */
        private LinuxCargoRootStreams rootStreams;
        /**
         * Original gate attachment and manager retained through actual whole-Cargo zero.
         */
        private LinuxLeaderExec leader;
        /**
         * Root and descendant image connections, including partial and retired generations.
         */
        private LinuxCargoImages images;
        /**
         * Indicates whether all root attachments closed and collection was acknowledged.
         */
        private boolean instrumentationClosed;
        /**
         * Partial Cargo preparation cleanup owner.
         */
        private LinuxSupervisor.PreparationFailure cargoFailure;
        /**
         * Maintained native registration authenticator.
         */
        private LinuxCargoRegistration.Configuration registrationPolicy;
        /** Independently ready Cargo-descendant parent images retained until whole-consumer closure. */
        private final Map<Long, LinuxCargoRegistration.Parent> cargoParents = new LinkedHashMap<>();
        /** Invocation-owned explicit routes shared with authentic scheduled attempt controllers. */
        private final AllocationHooks.Routing routing = new AllocationHooks.Routing();
        /** Original Cargo image controller's retained source and ownership registration. */
        private AllocationHooks.Routing.Registration imageRoute;
        /** Held metadata images delivered from any receiving manager to the original Cargo image controller. */
        private final java.util.ArrayDeque<FridaRuntime.Child> imageChildren = new java.util.ArrayDeque<>();
        /** Prevents a peer pump from reentering an image transition. */
        private boolean advancingImage;
        /**
         * Session installed before releasing Cargo.
         */
        private CargoSession<LinuxSupervisor.Result> session;
        /**
         * Authentically collected natural Cargo root status.
         */
        private OptionalInt result = OptionalInt.empty();
        /**
         * Whether arrivals are stopped.
         */
        private boolean stopping;
        /**
         * Whether a poll found no remaining arrivals after authentic root completion.
         */
        private boolean drained;

        /**
         * Creates a resource-free native owner borrowing scoped services.
         * @param scope borrowed Cargo scope
         * @param configuration trusted launch boundaries
         * @param memory explicit history settings, or null without history
         */
        private NativeResources(LinuxCargoScope scope, Configuration configuration, CargoMemoryEstimates.Settings memory)
        {
            this.scope = scope;
            this.configuration = configuration;
            this.memory = memory;
        }

        @Override public void acquire() throws IOException
        {
            var config = configuration;
            try { server = LinuxCargoTransport.listen(scope.tooling(), config.controllerEndpoint(), 16, config.timeout()); }
            catch (LinuxBorrowedStreams.CleanupFailure failure) { streamFailures.add(failure); throw failure; }
            Files.setAttribute(config.controllerEndpoint(), "unix:uid", config.cargo().uid());
            Files.setAttribute(config.controllerEndpoint(), "unix:gid", config.cargo().gid());
            Files.setPosixFilePermissions(config.controllerEndpoint(), PosixFilePermissions.fromString("rw-------"));
            try { jobserver = LinuxCargoJobserver.create(scope.tooling(), config.cargo().scratchParent(), config.cargo().gid(), config.jobs()); }
            catch (LinuxCargoJobserver.PreparationFailure failure) { jobserverFailure = failure; throw failure; }
            lease = jobserver.retainCargo(config.cargo().environment());
            runtime = new FridaRuntime(scope.tooling(), config.frida());
            runtime.start();
            device = LinuxFridaDevice.open(runtime, routing);
            try { authority = LinuxAuthority.open(config.authority()); }
            catch (LinuxAuthority.AcquisitionFailure failure) { authority = failure.owner(); throw failure; }
            attempts = new LinuxAttempts(UUID.randomUUID(), new LinuxAttempts.NativeOwners(scope.tooling(), device,
                authority, routing, config.authority().cgroupRoot(), lease, config.timeout()), config.timeout());
            measurements = LinuxMeasurements.open(authority);
            if (memory != null) estimates = memory.observe(measurements);
            LinuxProcessTree.Launch rootLaunch = withEnvironment(config.cargo(), lease.environment());
            try
            {
                switch (config.root())
                {
                    case FileRoot files -> cargo = LinuxSupervisor.prepareInstrumented(rootLaunch, files.files(), config.timeout());
                    case BorrowedRoot borrowed ->
                    {
                        rootStreams = LinuxCargoRootStreams.open(scope, borrowed.directory(), rootLaunch, config.timeout());
                        cargo = LinuxSupervisor.prepareInstrumentedBorrowed(rootStreams.binding(), config.timeout());
                        rootStreams.await();
                    }
                }
            }
            catch (LinuxSupervisor.PreparationFailure failure) { cargoFailure = failure; throw failure; }
            catch (LinuxBorrowedStreams.CleanupFailure failure) { streamFailures.add(failure); throw failure; }
            long pid = cargo.rootPid();
            var birth = new LinuxBorrowedStreams.Peer(pid, birth(pid), config.cargo().uid(), config.cargo().gid());
            var trusted = config.registration();
            LinuxCargoRegistration.Configuration policy = new LinuxCargoRegistration.Configuration(trusted.cargoExecutable(),
                trusted.wrapperExecutable(), trusted.rustcExecutable(), trusted.wrapperPrefix(),
                trusted.context(), lease.environment());
            registrationPolicy = policy;
            cargoParents.put(birth.pid(), new LinuxCargoRegistration.Parent(birth, trusted.cargoExecutable(), 0,
                LinuxCargoRegistration.imageIdentity(trusted.cargoExecutable())));
        }
        @Override public JobQueue.AttemptFactory<LinuxSupervisor.Result> factory() { return attempts; }
        @Override public Controller.Measurements measurements() { return estimates == null ? measurements : estimates; }
        @Override public void acceptedOutcome(JobQueue.Completion<LinuxSupervisor.Result> completion)
        {
            if (estimates == null) return;
            LinuxSupervisor.Result nativeResult = completion.outcome().result();
            boolean successful = !completion.outcome().aborted() && nativeResult.succeeded();
            estimates.completed(completion.name(), completion.attemptSequence(), successful);
        }
        @Override public List<CargoMemoryEstimates.Observation> memoryObservations()
        {
            return estimates == null ? List.of() : estimates.observations();
        }
        @Override public void register(CargoCompilerRequest request, long position) throws IOException
        {
            var wrapper = wrappers.get(request.name());
            if (wrapper == null) throw new IOException("Cargo compiler request has no authenticated wrapper");
            var context = configuration.registration().context();
            var launch = new LinuxProcessTree.Launch(context.cgroupParent(), context.scratchParent(), context.javaExecutable(),
                context.classPath(), request.arguments(), request.environment(), request.directory(), context.uid(), context.gid(), context.executionMode());
            attempts.register(request.name(), new LinuxAttempts.Command(launch, wrapper));
        }
        @Override public void release(CargoSession<LinuxSupervisor.Result> session) throws IOException
        {
            this.session = session;
            Configuration config = configuration;
            LinuxProcessTree.Launch launch = withEnvironment(config.cargo(), lease.environment());
            leader = new LinuxLeaderExec(scope.tooling(), device, launch, lease.descriptors(), cargo, config.timeout(),
                attempts::pumpAll);
            leader.start((image, manager) ->
            {
                LinuxCargoImages.Identity root = new LinuxCargoImages.Identity(image.pid(), image.started(), image.parent(),
                    launch.uid(), launch.gid(), image.executable(), cargo.directory());
                images = new LinuxCargoImages(scope.tooling(), manager, root, cargo.directory(),
                    new LinuxCargoImages.Policy(launch.uid(), launch.gid(), config.registration().cargoExecutable(),
                        config.registration().wrapperExecutable()),
                    (readyImage, generation) ->
                    {
                        // Readiness supplies independently checked lineage and containment, never receipt-derived authority.
                        retainReadyParent(cargoParents, readyImage, generation,
                            LinuxCargoRegistration.imageIdentity(Path.of("/proc", Long.toString(readyImage.pid()), "exe")));
                    }, config.timeout(), cargo.pid(), birth(cargo.pid()));
                imageRoute = routing.register(images::owns, imageChildren::addLast);
                images.start();
            });
        }

        @Override public void pump() throws IOException
        {
            if (!stopping && !instrumentationClosed && leader != null)
            {
                if (images != null)
                {
                    // Every held image waits for this pump, so one pump releases all of them rather than one per idle
                    // interval.
                    for (FridaRuntime.Child nativeChild = images.pollNativeChild(); nativeChild != null;
                        nativeChild = images.pollNativeChild())
                        routing.route(imageRoute, nativeChild);
                }
                device.copy();
                if (!advancingImage)
                {
                    advancingImage = true;
                    try
                    {
                        while (!imageChildren.isEmpty())
                        {
                            FridaRuntime.Child child = imageChildren.removeFirst();
                            routing.prepare(imageRoute, child);
                            images.advance(child);
                            routing.completed(imageRoute, child);
                        }
                    }
                    finally { advancingImage = false; }
                }
            }
            if (!stopping)
            {
                Optional<LinuxCargoTransport.Connection> next;
                try { next = server.poll(); }
                catch (LinuxBorrowedStreams.CleanupFailure failure) { streamFailures.add(failure); throw failure; }
                if (next.isPresent())
                {
                    var connection = next.orElseThrow();
                    connections.add(connection);
                    var wrapper = new LinuxCargoWrapperConnection(connection);
                    var register = wrapper.register();
                    LinuxBorrowedStreams.Peer captured = wrapper.peer();
                    long parent = ProcessHandle.of(captured.pid()).flatMap(ProcessHandle::parent).
                        orElseThrow(() -> new IOException("Wrapper parent is unavailable")).pid();
                    CargoCompilerRequest request;
                    try
                    {
                        LinuxCargoRegistration.Parent retained = retainedCargoParent(cargoParents, parent);
                        LinuxCargoRegistration registration = estimates == null ? new LinuxCargoRegistration(registrationPolicy, retained) :
                            new LinuxCargoRegistration(registrationPolicy, retained, estimates);
                        request = registration.authenticate(captured, register);
                    }
                    catch (IOException failure)
                    {
                        throw parentFailure(captured, parent, cargoParents.get(parent), failure);
                    }
                    if (wrappers.putIfAbsent(request.name(), wrapper) != null) throw new IOException("Duplicate Cargo wrapper action");
                    session.registerAuthenticated(request, nativeResult -> wrapper.finish(nativeResult.rootExit()));
                }
                else if (result.isPresent()) drained = true;
            }
            attempts.pumpAll();
            for (var entry : wrappers.entrySet())
            {
                var wrapper = entry.getValue();
                if (wrapper.isClosed()) continue;
                // A wrapper that is between attempts reports its next child unprompted; reading it promptly keeps the
                // wrapper's bounded send from expiring before the queue starts that attempt.
                try { wrapper.pump(); }
                catch (IOException failure) { session.wrapperDisconnected(entry.getKey()); }
            }
        }
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked()
        {
            return attempts.pollBlocked().map(event -> new SchedulerCoordinator.Blocked(event.name(), event.attemptSequence(), event.waitSequence()));
        }
        @Override public OptionalInt pollToolResult() throws IOException
        {
            if (result.isEmpty())
            {
                finishRootInstrumentation();
                if (instrumentationClosed && cargo.observeCompletion()) result = OptionalInt.of(cargo.result().rootExit());
            }
            return result;
        }
        @Override public boolean inputClosed() { return result.isPresent() && drained; }
        @Override public boolean hasOpenAttempts() { return attempts != null && attempts.hasOpenAttempts(); }
        @Override public void stopAccepting() throws IOException
        {
            stopping = true;
            if (imageRoute != null) imageRoute.cancel();
            if (server != null) { server.close(); server = null; }
        }
        @Override public void closeConsumers() throws IOException
        {
            if (cargoFailure != null) { cargoFailure.close(); cargoFailure = null; }
            if (cargo != null)
            {
                if (leader != null) leader.stop();
                cargo.requestTermination();
                while (!finishRootInstrumentation())
                {
                    try { Thread.sleep(10); }
                    catch (InterruptedException failure)
                    {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retaining Cargo attachments until whole-tree zero", failure);
                    }
                }
                cargo.close();
                cargo = null;
            }
        }

        /**
         * Closes native image owners at genuine whole-Cargo zero before permitting status collection.
         * @return true after the attachment and collection handoff completed, or false while workload remains live
         * @throws IOException if zero, attachment closure or collection acknowledgement cannot be established
         */
        private boolean finishRootInstrumentation() throws IOException
        {
            if (instrumentationClosed) return true;
            instrumentationClosed = LinuxCargoInvocation.finishRootInstrumentation(cargo::observeExecutionImpossible, () ->
            {
                if (images != null) images.close(cargo::observeExecutionImpossible);
                if (imageRoute != null) imageRoute.close();
                if (leader != null) leader.close(cargo::observeExecutionImpossible);
            }, cargo::finishInstrumentation);
            return instrumentationClosed;
        }

        @Override public void closeEndpoints() throws IOException
        {
            Throwable failure = null;
            if (rootStreams != null)
            {
                try { rootStreams.close(); rootStreams = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            for (var connection : connections)
            {
                if (connection.isClosed()) continue;
                try { connection.close(); }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            for (var iterator = streamFailures.iterator(); iterator.hasNext();)
            {
                var partial = iterator.next();
                try { partial.retryCleanup(); iterator.remove(); }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            rethrow(failure);
        }
        @Override public void closeDependencies() throws IOException
        {
            Throwable failure = null;
            if (lease != null)
            {
                try { lease.close(); lease = null; }
                catch (RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            if (lease == null && jobserver != null)
            {
                try { jobserver.close(); jobserver = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            if (jobserverFailure != null)
            {
                try { jobserverFailure.close(); jobserverFailure = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            if (authority != null)
            {
                try { authority.close(); authority = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            if (device != null)
            {
                try { device.close(); device = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            if (runtime != null)
            {
                try { runtime.close(); runtime = null; }
                catch (IOException | RuntimeException | Error cleanup) { failure = combine(failure, cleanup); }
            }
            rethrow(failure);
        }
    }

    /**
     * Closes Cargo attachments and acknowledges collection only after the supplied whole-workload barrier.
     * A live or unknown workload retains all attachments; a failed attachment close never permits collection.
     * The caller retains its process owner and jobserver lease until subsequent authentic status collection.
     * @param barrier original supervisor's actual whole-workload-zero observation
     * @param attachments closes every partial, current and retired native root image owner
     * @param collect acknowledges completed native closure to the original supervisor
     * @return true after successful closure and acknowledgement, or false while the workload remains live
     * @throws NullPointerException if an operation is null
     * @throws IOException if a barrier observation, attachment closure or collection acknowledgement fails
     */
    public static boolean finishRootInstrumentation(AllocationHook.ExecutionBarrier barrier, LinuxAttempt.Close attachments,
        LinuxAttempt.Close collect) throws IOException
    {
        Objects.requireNonNull(barrier, "barrier");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(collect, "collect");
        if (!barrier.reached()) return false;
        attachments.close();
        collect.close();
        return true;
    }

    /**
     * Selects an authenticator's parent exclusively from independently retained invocation image births.
     * Selection grants no registration authority: the existing authenticator must recheck current direct
     * parentage, birth, credentials, retained executable and complete wrapper context before acceptance.
     * @param <T> independently retained parent observation type
     * @param retained independently verified invocation-owned parents keyed by native PID
     * @param parent independently sampled current wrapper parent PID
     * @return retained exact parent identity
     * @throws IOException if the parent was never independently enrolled
     */
    public static <T> T retainedCargoParent(Map<Long, T> retained, long parent) throws IOException
    {
        T result = retained.get(parent);
        if (result == null) throw new IOException("Wrapper parent is not an independently retained invocation image: " + parent);
        return result;
    }

    /**
     * Retains the exact birth, executable and generation supplied by completed held-image readiness.
     * Only the independently authenticated image enrollment callback may supply production inputs.
     * A later ready exec generation replaces its predecessor instead of inheriting the old image policy.
     * @param retained invocation-owned ready image parents keyed by PID
     * @param image independently checked held image before its explicit resume
     * @param generation positive readiness ordinal greater than this PID's retained predecessor
     * @param imageIdentity independently sampled running executable inode observations while the image is held
     * @throws IllegalArgumentException if readiness generation is absent, repeated or stale
     */
    public static void retainReadyParent(Map<Long, LinuxCargoRegistration.Parent> retained,
        LinuxCargoImages.Identity image, long generation, Map<String, Object> imageIdentity)
    {
        if (generation <= 0) throw new IllegalArgumentException("Ready parent generation must be positive");
        LinuxCargoRegistration.Parent previous = retained.get(image.pid());
        if (previous != null && generation <= previous.generation())
            throw new IllegalArgumentException("Ready parent generation must advance its retained predecessor");
        var parent = new LinuxCargoRegistration.Parent(new LinuxBorrowedStreams.Peer(image.pid(), image.birth(),
            image.uid(), image.gid()), image.executable(), generation, imageIdentity);
        retained.put(image.pid(), parent);
    }

    /**
     * Captures bounded current identity diagnostics after a failed parent selection or authentication.
     * Observations are diagnostic only: an unavailable or changed process never supplies fallback authority.
     * @param wrapper kernel-captured wrapper birth
     * @param parent independently sampled parent selected before authentication
     * @param retained independently ready parent image, or null when never enrolled
     * @param failure exact parent-selection or authentication failure
     * @return contextual failure retaining its original cause
     */
    private static IOException parentFailure(LinuxBorrowedStreams.Peer wrapper, long parent,
        LinuxCargoRegistration.Parent retained, IOException failure)
    {
        String currentParent;
        try
        {
            currentParent = ProcessHandle.of(wrapper.pid()).flatMap(ProcessHandle::parent).
                map(handle -> Long.toString(handle.pid())).orElse("unavailable");
        }
        catch (RuntimeException observation) { currentParent = "unavailable(" + observation.getClass().getSimpleName() + ")"; }
        String executable;
        try { executable = Files.readSymbolicLink(Path.of("/proc", Long.toString(parent), "exe")).toString(); }
        catch (IOException | RuntimeException observation) { executable = "unavailable(" + observation.getClass().getSimpleName() + ")"; }
        String currentBirth;
        try { currentBirth = Long.toString(birth(parent)); }
        catch (IOException | RuntimeException observation) { currentBirth = "unavailable(" + observation.getClass().getSimpleName() + ")"; }
        return new IOException(parentFailureMessage(wrapper, parent, currentParent, executable, currentBirth, retained), failure);
    }

    /**
     * Formats captured parent diagnostics without exposing argv, environment or unbounded executable text.
     * @param wrapper kernel-captured wrapper birth
     * @param parent originally selected direct parent PID
     * @param currentParent current independently observed wrapper parent, or explicit unavailable state
     * @param executable actual observed selected-parent executable, or explicit unavailable state
     * @param currentBirth actual observed selected-parent birth, or explicit unavailable state
     * @param retained independently ready expected image, or null when never enrolled
     * @return bounded diagnostic preserving the distinction between observed and retained identity
     */
    public static String parentFailureMessage(LinuxBorrowedStreams.Peer wrapper, long parent, String currentParent,
        String executable, String currentBirth, LinuxCargoRegistration.Parent retained)
    {
        String expected = retained == null ? "unenrolled" : "pid=" + retained.peer().pid() +
            ",birth=" + retained.peer().startTicks() + ",generation=" + retained.generation() +
            ",exe=" + boundedIdentity(retained.executable().toString());
        return "Compiler-wrapper parent authentication failed: wrapper=" + wrapper.pid() + ",birth=" + wrapper.startTicks() +
            "; selectedParent=" + parent + "; currentWrapperParent=" + boundedIdentity(currentParent) +
            "; observedSelectedParentBirth=" + boundedIdentity(currentBirth) +
            "; observedSelectedParentExe=" + boundedIdentity(executable) + "; retainedParent=" + expected;
    }

    /**
     * Limits one identity diagnostic to 512 characters and one physical line.
     * @param value observed diagnostic text
     * @return single-line text truncated only after its first 512 characters
     */
    private static String boundedIdentity(String value)
    {
        String bounded = value.substring(0, Math.min(512, value.length()));
        return bounded.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    /**
     * Copies a trusted launch while installing the complete retained jobserver environment.
     * @param launch immutable trusted root launch
     * @param environment exact retained lease environment
     * @return equivalent launch with supported FIFO authorization
     */
    private static LinuxProcessTree.Launch withEnvironment(LinuxProcessTree.Launch launch, Map<String, String> environment)
    {
        return new LinuxProcessTree.Launch(launch.cgroupParent(), launch.scratchParent(), launch.javaExecutable(), launch.classPath(),
            launch.command(), environment, launch.directory(), launch.uid(), launch.gid(), launch.executionMode());
    }

    /**
     * Reads positive raw kernel start ticks before the held Cargo gate drops workload credentials.
     * @param pid held native process identity
     * @return retained positive birth ticks
     * @throws IOException if the birth cannot be observed
     */
    private static long birth(long pid) throws IOException
    {
        var stats = ProcessStat.getPidStats(Math.toIntExact(pid));
        Long ticks = stats == null ? null : stats.getC().get(PidStat.STARTTIME);
        if (ticks == null || ticks <= 0) throw new IOException("Cargo root birth ticks are unavailable");
        return ticks;
    }
}
