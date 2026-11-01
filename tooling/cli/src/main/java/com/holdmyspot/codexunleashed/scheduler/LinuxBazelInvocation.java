package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Owns original Bazel discovery and dependencies while its session owns registered actions.
 * All operations belong to the constructing thread. Construction acquires no resources.
 */
final class LinuxBazelInvocation implements LinuxBazelSession.Invocation
{
    /** Original operation thread, also used by the session and its queue. */
    private final Thread owner = Thread.currentThread();
    /** Original native invocation owner retained through partial acquisition and cleanup. */
    private final Resources resources;
    /** Session installed before the original root is released. */
    private LinuxBazelSession session;
    /** Indicates whether acquisition has been reserved, preventing replacement after uncertainty. */
    private boolean startAttempted;
    /** Indicates whether original root release returned successfully. */
    private boolean started;
    /** Indicates whether discovery has stopped. */
    private boolean stopping;
    /** Retains an uncertain discovery-withdrawal effect without replaying it. */
    private Throwable stopFailure;
    /** Indicates whether all invocation dependencies have closed. */
    private boolean closed;
    /** First operational failure permanently withdraws further work authority. */
    private Throwable failure;

    /**
     * Retains a resource-free native composition owner.
     * @param resources original acquisition, discovery and cleanup owner
     * @throws NullPointerException if {@code resources} is null
     */
    LinuxBazelInvocation(Resources resources)
    {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    /**
     * Creates a concrete native invocation without acquiring processes, sockets or memory-reader ownership.
     * @param scope borrowed pouch values retained through complete session cleanup
     * @param configuration trusted launch, declared actions and privileged helper inputs
     * @throws NullPointerException if an input is null
     */
    LinuxBazelInvocation(ToolingScope scope, Configuration configuration)
    {
        this(new NativeResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration")));
    }

    /**
     * Creates the production ready-action composition without acquiring native resources.
     * @param scope borrowed pouch values retained through complete cleanup
     * @param configuration original consumer and authenticated worker runtime
     */
    LinuxBazelInvocation(ToolingScope scope, ProductionConfiguration configuration)
    {
        this(new ProductionResources(Objects.requireNonNull(scope, "scope"),
            Objects.requireNonNull(configuration, "configuration")));
    }

    /**
     * Rejects mappings whose original namespace meaning would be hidden by the fresh per-attempt temporary alias.
     * @param paths original action-visible input, temporary, control, execution or runtime target paths
     * @throws IOException if an original path intersects the private temporary alias
     */
    static void requireOutsidePrivateTemporary(Path... paths) throws IOException
    {
        Path alias = Path.of("/tmp");
        for (Path path : paths)
            if (!path.isAbsolute() || !path.equals(path.normalize()) || path.startsWith(alias) || alias.startsWith(path))
                throw new IOException("Original path conflicts with private /tmp mapping: " + path);
    }

    /**
     * Immutable host-selected production inputs; no action can choose a helper or privilege.
     * @param runner authenticated ready-action source
     * @param bazel genuine original consumer launch
     * @param files original consumer streams
     * @param authority privileged measurement reader policy
     * @param scratch exclusive invocation directory
     * @param runtime sealed helper and runtime bindings
     * @param timeout bounded native observation duration
     * @param observer durable owner-thread event sink outside every action namespace
     */
    record ProductionConfiguration(LinuxBazelRunner runner, LinuxProcessTree.Launch bazel,
        LinuxProcessTree.StandardFiles files, LinuxAuthority.Settings authority, Path scratch,
        LinuxBazelProducer.Runtime runtime, Duration timeout, SchedulerCoordinator.Observer observer)
    {
        /**
         * Checks the complete host-selected configuration before acquisition.
         * @param runner authenticated ready-action source
         * @param bazel original consumer
         * @param files original standard streams
         * @param authority privileged reader policy
         * @param scratch exclusive absolute normalized directory
         * @param runtime sealed native runtime
         * @param timeout positive native bound
         * @param observer durable owner-thread event sink
         */
        ProductionConfiguration
        {
            Objects.requireNonNull(runner, "runner");
            Objects.requireNonNull(bazel, "bazel");
            Objects.requireNonNull(files, "files");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(scratch, "scratch");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(observer, "observer");
            timeout = LinuxAttempts.checkedTimeout(timeout);
            if (!scratch.isAbsolute() || !scratch.normalize().equals(scratch))
                throw new IllegalArgumentException("Production scratch must be absolute and normalized");
        }
    }

    /**
     * Acquires dependencies and installs the session before releasing original Bazel once.
     * Failed acquisition remains owned by this invocation for explicit cleanup.
     * @throws IOException if acquisition or original root release fails
     * @throws IllegalStateException if start was already attempted or another thread calls
     */
    void start() throws IOException
    {
        requireOwner();
        if (startAttempted || closed) throw new IllegalStateException("Bazel invocation start is permitted once");
        startAttempted = true;
        try
        {
            resources.acquire();
            session = new LinuxBazelSession(resources.measurements(), this);
            resources.release();
            started = true;
        }
        catch (IOException | RuntimeException | Error cause)
        {
            failure = cause;
            throw cause;
        }
    }

    /**
     * Returns the original coordinator session after successful root release.
     * @return original installed session
     * @throws IllegalStateException if startup failed or another thread calls
     */
    LinuxBazelSession session()
    {
        requireOwner();
        if (!started) throw new IllegalStateException("Bazel invocation did not start successfully");
        return session;
    }

    @Override public void pump() throws IOException
    {
        requireActive();
        try
        {
            LinuxBazelSession.Action action = resources.discover();
            if (action != null)
            {
                session.registerAuthenticated(action);
                resources.registered(action);
            }
        }
        catch (IOException | RuntimeException | Error cause)
        {
            failure = cause;
            throw cause;
        }
    }

    @Override public boolean inputClosed()
    {
        requireOwner();
        return started && resources.inputClosed();
    }

    @Override public OptionalInt pollToolResult() throws IOException
    {
        requireActive();
        return resources.pollToolResult();
    }

    @Override public void stopAccepting() throws IOException
    {
        requireOwner();
        if (stopping)
        {
            if (stopFailure != null) throw new IOException("Original Bazel discovery withdrawal remains uncertain", stopFailure);
            return;
        }
        stopping = true;
        try { resources.stopAccepting(); }
        catch (IOException | RuntimeException | Error cause)
        {
            stopFailure = cause;
            throw cause;
        }
    }

    @Override public void close() throws IOException
    {
        requireOwner();
        if (closed) return;
        stopAccepting();
        if (session != null && session.hasOpenAttempts())
            throw new IOException("Queue-owned Bazel attempts retain original invocation dependencies");
        resources.close();
        closed = true;
    }

    /**
     * Rejects cross-thread calls before observing or changing owned state.
     * @throws IllegalStateException if another thread calls
     */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Bazel invocation belongs to another thread");
    }

    /**
     * Requires successful startup and live original work authority.
     * @throws IOException if an earlier operation failed or discovery stopped
     * @throws IllegalStateException if startup has not succeeded or another thread calls
     */
    private void requireActive() throws IOException
    {
        requireOwner();
        if (!started) throw new IllegalStateException("Bazel invocation has not started");
        if (failure != null || stopping || closed) throw new IOException("Bazel invocation work authority is withdrawn", failure);
    }

    /**
     * Owns native root, authenticated discovery, measurements and ordered dependency cleanup.
     * No operation calls the queue; returned actions remain owned until registration succeeds.
     */
    interface Resources extends AutoCloseable
    {
        /**
         * Acquires dependencies once while retaining partial cleanup authority.
         * @throws IOException if acquisition fails
         */
        void acquire() throws IOException;
        /**
         * Supplies the original complete host and owned-attempt sampler.
         * @return sampler retained through session cleanup
         */
        Controller.Measurements measurements();
        /**
         * Releases the original prepared root once after session installation.
         * @throws IOException if release has uncertain effects
         */
        void release() throws IOException;
        /**
         * Authenticates one ready original action, retaining it until acknowledged registration.
         * @return authenticated action, or null if no action is ready
         * @throws IOException if discovery or independent authentication fails
         */
        LinuxBazelSession.Action discover() throws IOException;
        /**
         * Transfers the exact action to its session after successful registration.
         * @param action action returned by discovery and accepted by the session
         * @throws IOException if ownership transfer cannot be acknowledged
         */
        void registered(LinuxBazelSession.Action action) throws IOException;
        /**
         * Indicates whether root completion and endpoint drain establish no future actions.
         * @return true only after both conditions are observed
         */
        boolean inputClosed();
        /**
         * Collects the authentic original root result independently of action completion.
         * @return original root status, or empty while not collected
         * @throws IOException if collection is uncertain
         */
        OptionalInt pollToolResult() throws IOException;
        /**
         * Stops new admissions while retaining root termination and zero authority for ordered cleanup.
         * @throws IOException if withdrawal is uncertain
         */
        void stopAccepting() throws IOException;
        /**
         * Collects original root, then closes endpoints and borrowed native dependencies.
         * @throws IOException if any original ownership remains unresolved
         */
        @Override void close() throws IOException;
    }

    /**
     * One explicitly wrapped action authorized by the trusted invocation before Bazel starts.
     * Directory selection is restricted separately to the invocation's exclusive sandbox tree.
     * @param name stable unique action identity
     * @param peakBytes nonnegative independently supplied peak estimate, or null when unknown
     * @param ownerArguments exact owner argv before insertion of the host-bound socket identity after capability
     * @param ownerSha256 trusted digest of the wrapper executable
     * @param heldImage approved actual kernel image after leader replacement
     * @param heldArguments exact resulting native argv, including original argv zero
     * @param environment complete declared native environment
     */
    record Definition(String name, Long peakBytes, List<String> ownerArguments, String ownerSha256,
        LinuxBazelIdentity.Image heldImage, List<String> heldArguments, Map<String, String> environment)
    {
        /**
         * Copies declared policy and rejects values that cannot identify an exact native execution.
         * @param name stable action identity
         * @param peakBytes nonnegative independently supplied peak estimate, or null when unknown
         * @param ownerArguments exact wrapper argv
         * @param ownerSha256 trusted wrapper digest
         * @param heldImage actual approved executable image
         * @param heldArguments exact target argv
         * @param environment exact target and wrapper environment
         * @throws NullPointerException if a required input or collection member is null
         * @throws IllegalArgumentException if the name, digest or argument vectors are invalid
         */
        Definition
        {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(ownerSha256, "ownerSha256");
            Objects.requireNonNull(heldImage, "heldImage");
            ownerArguments = List.copyOf(ownerArguments);
            heldArguments = List.copyOf(heldArguments);
            environment = Map.copyOf(environment);
            if (peakBytes != null && peakBytes < 0) throw new IllegalArgumentException("Peak estimate must be nonnegative");
            if (name.isBlank() || ownerArguments.isEmpty() || heldArguments.isEmpty() ||
                !ownerSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Declared action requires a name, exact argv and wrapper SHA-256");
            new LinuxBazelIdentity.Execution(heldImage, heldArguments, environment, Path.of("/"), 1, 0);
            new LinuxBazelIdentity.Execution(heldImage, ownerArguments, Map.of(), Path.of("/"), 1, 0);
        }
    }

    /**
     * Trusted native composition supplied before any unprivileged action can connect.
     * The sandbox tree and socket parent must be exclusively invocation-owned, with the socket mounted into actions.
     * @param bazel original root launch, including explicit unprivileged credentials
     * @param files original root's standard-stream endpoints
     * @param authority original privileged native measurement reader settings
     * @param endpoint absolute original control socket path visible inside the sandbox
     * @param capability original invocation's 64 lowercase hexadecimal secret
     * @param sandboxRoot exclusive absolute sandbox parent under the Bazel output base
     * @param processWrapper sealed original Bazel process-wrapper executable
     * @param helper sealed host-selected namespace helper runtime inputs
     * @param definitions immutable explicitly wrapped actions
     * @param timeout positive bounded native setup and cleanup duration
     */
    record Configuration(LinuxProcessTree.Launch bazel, LinuxProcessTree.StandardFiles files,
        LinuxAuthority.Settings authority, Path endpoint, String capability, Path sandboxRoot,
        LinuxBazelIdentity.Image processWrapper, LinuxBazelNamespaceHooks.Launch helper,
        List<Definition> definitions, Duration timeout)
    {
        /**
         * Validates immutable composition policy before resource acquisition.
         * @param bazel original Bazel root launch
         * @param files original root stream endpoints
         * @param authority original measurement reader settings
         * @param endpoint original controller socket
         * @param capability exact invocation secret
         * @param sandboxRoot exclusive sandbox parent
         * @param processWrapper sealed original process-wrapper image
         * @param helper sealed privileged helper policy
         * @param definitions declared action policies
         * @param timeout positive native observation bound
         * @throws NullPointerException if a required input or collection member is null
         * @throws IllegalArgumentException if paths, capability, timeout or action identities are invalid
         */
        Configuration
        {
            Objects.requireNonNull(bazel, "bazel");
            Objects.requireNonNull(files, "files");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(sandboxRoot, "sandboxRoot");
            Objects.requireNonNull(processWrapper, "processWrapper");
            Objects.requireNonNull(helper, "helper");
            definitions = List.copyOf(definitions);
            timeout = LinuxAttempts.checkedTimeout(timeout);
            if (!endpoint.isAbsolute() || !endpoint.normalize().equals(endpoint) ||
                !sandboxRoot.isAbsolute() || !sandboxRoot.normalize().equals(sandboxRoot) ||
                !capability.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Bazel endpoint, sandbox root or capability is invalid");
            Set<String> names = new HashSet<>();
            Set<List<String>> arguments = new HashSet<>();
            for (Definition definition : definitions)
                if (!names.add(definition.name()) || !arguments.add(definition.ownerArguments()))
                    throw new IllegalArgumentException("Declared actions must have unique names and native owner argv");
        }
    }

    /**
     * Owns one frozen authentic attempt's diagnostics until successful final publication.
     * Aborted attempts never acquire this owner. Capture paths are private; worker endpoints remain absent until commit.
     * The host caller supplies immutable directory ancestry and whole-attempt zero before freeze.
     */
    static final class Diagnostics
    {
        /** Private immutable snapshots in stdout/stderr order. */
        private final List<Path> snapshots;
        /** Original worker endpoints, inaccessible to the action namespace. */
        private final List<Path> destinations;

        /**
         * Retains the independent frozen files and original worker destinations.
         * @param snapshots private immutable stdout/stderr snapshots
         * @param destinations original authenticated worker stdout/stderr paths
         */
        private Diagnostics(List<Path> snapshots, List<Path> destinations)
        {
            this.snapshots = List.copyOf(snapshots);
            this.destinations = List.copyOf(destinations);
        }

        /**
         * Copies only one authentic non-aborted attempt after whole zero and closed instrumentation.
         * @param captures private logical-owner append endpoints
         * @param destinations original authenticated worker endpoints
         * @param directory absent private per-attempt snapshot directory
         * @return independent diagnostic publication owner
         * @throws IOException if snapshots cannot be completely frozen
         */
        static Diagnostics freeze(List<Path> captures, List<Path> destinations, Path directory) throws IOException
        {
            if (captures.size() != 2 || destinations.size() != 2)
                throw new IllegalArgumentException("Diagnostics require exactly stdout and stderr");
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            List<Path> snapshots = new ArrayList<>();
            for (int index = 0; index < captures.size(); ++index)
            {
                Path capture = captures.get(index);
                if (!Files.isRegularFile(capture, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Private native capture is not an original regular file");
                Path snapshot = directory.resolve(index == 0 ? "stdout" : "stderr");
                Files.copy(capture, snapshot);
                Files.setPosixFilePermissions(snapshot, PosixFilePermissions.fromString("r--------"));
                snapshots.add(snapshot);
            }
            return new Diagnostics(snapshots, destinations);
        }

        /**
         * Publishes frozen authentic diagnostics with the original rollback-capable output transaction.
         * No existing worker endpoint is overwritten. Failure removes only exact newly acquired destination objects.
         * @param permitted original Runner cancellation/publication guard
         * @param outputs original private workspace promotion, or an empty transaction for authentic ordinary nonzero
         * @throws IOException if the guard withdraws, a path is occupied, copying fails or output promotion fails
         */
        void publish(java.util.function.BooleanSupplier permitted, LinuxBazelRunner.Publication outputs) throws IOException
        {
            Map<Path, Object> created = new LinkedHashMap<>();
            try
            {
                if (!permitted.getAsBoolean()) throw new IOException("Original Runner withdrew diagnostic publication");
                for (int index = 0; index < snapshots.size(); ++index)
                {
                    Path destination = destinations.get(index);
                    try (var target = Files.newByteChannel(destination, java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
                    {
                        Object key = Files.readAttributes(destination, java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey();
                        if (key == null) throw new IOException("Worker diagnostic destination has no stable file key");
                        created.put(destination, key);
                        try (var source = Files.newInputStream(snapshots.get(index)))
                        { source.transferTo(java.nio.channels.Channels.newOutputStream(target)); }
                    }
                    if (!permitted.getAsBoolean()) throw new IOException("Original Runner withdrew diagnostic publication");
                }
                outputs.commit();
            }
            catch (IOException | RuntimeException | Error failure)
            {
                for (Map.Entry<Path, Object> entry : created.entrySet())
                    try
                    {
                        Object current = Files.readAttributes(entry.getKey(), java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey();
                        if (!entry.getValue().equals(current)) throw new IOException("Worker diagnostic destination was replaced");
                        Files.delete(entry.getKey());
                    }
                    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    /**
     * Connects authenticated worker requests to the original native scheduler and consumer.
     * Every partial ready-action acquisition remains in {@link #actions} before another operation can fail.
     */
    private static final class ProductionResources implements Resources
    {
        /** Borrowed pouch scope. */
        private final ToolingScope scope;
        /** Immutable host-selected composition. */
        private final ProductionConfiguration configuration;
        /** Ready leases, including failed acquisition and transferred actions. */
        private final List<ProductionAction> actions = new ArrayList<>();
        /** Privileged sampling owner retained before opening measurements. */
        private LinuxAuthority authority;
        /** Host measurements enriched only by authenticated completed history. */
        private BazelMemoryEstimates estimates;
        /** Trusted worker readonly input inode cache, scoped to this original invocation. */
        private final BazelWorkIdentity.ImmutableFiles inputIdentities = new BazelWorkIdentity.ImmutableFiles();
        /** Original consumer supervisor, independent from all action results. */
        private LinuxSupervisor root;
        /** Partial original consumer owner. */
        private LinuxSupervisor.PreparationFailure rootFailure;
        /** Exact action pending session ownership transfer. */
        private ProductionAction pending;
        /** Original consumer result. */
        private OptionalInt result = OptionalInt.empty();
        /** No requests arrived after the original consumer was collected. */
        private boolean drained;
        /** Future ready admissions have been withdrawn. */
        private boolean stopped;
        /** Every original dependency has closed. */
        private boolean closed;

        /**
         * Retains resource-free production policy.
         * @param scope borrowed pouch
         * @param configuration original host configuration
         */
        private ProductionResources(ToolingScope scope, ProductionConfiguration configuration)
        {
            this.scope = scope;
            this.configuration = configuration;
        }

        @Override public void acquire() throws IOException
        {
            try { authority = LinuxAuthority.open(configuration.authority()); }
            catch (LinuxAuthority.AcquisitionFailure failure) { authority = failure.owner(); throw failure; }
            estimates = new BazelMemoryEstimates(LinuxMeasurements.open(authority),
                new BazelMemoryHistory(configuration.scratch().getParent().resolve("history")));
            try { root = LinuxSupervisor.prepare(configuration.bazel(), configuration.files(), configuration.timeout()); }
            catch (LinuxSupervisor.PreparationFailure failure) { rootFailure = failure; throw failure; }
        }

        @Override public Controller.Measurements measurements() { return estimates; }
        @Override public void release() throws IOException { root.release(); }

        @Override public LinuxBazelSession.Action discover() throws IOException
        {
            if (stopped || pending != null) throw new IOException("Production ready discovery is unavailable");
            Optional<LinuxBazelRunner.Ready> request = configuration.runner().poll();
            if (request.isEmpty())
            {
                if (result.isPresent()) drained = true;
                return null;
            }
            ProductionAction action = new ProductionAction(this, request.orElseThrow());
            actions.add(action);
            pending = action;
            try { action.acquire(); }
            catch (IOException | RuntimeException | Error failure) { action.ready.fail(failure); throw failure; }
            return action;
        }

        @Override public void registered(LinuxBazelSession.Action action) throws IOException
        {
            if (pending != action) throw new IOException("Ready action differs from retained original owner");
            pending = null;
        }

        @Override public boolean inputClosed() { return result.isPresent() && drained; }
        @Override public OptionalInt pollToolResult() throws IOException
        {
            if (result.isEmpty() && root.observeCompletion()) result = OptionalInt.of(root.result().rootExit());
            return result;
        }

        @Override public void stopAccepting()
        {
            stopped = true;
            configuration.runner().stopAccepting();
        }

        @Override public void close() throws IOException
        {
            if (closed) return;
            stopAccepting();
            for (ProductionAction action : actions) action.close();
            if (rootFailure != null) { rootFailure.close(); rootFailure = null; }
            if (root != null) root.close();
            if (authority != null) authority.close();
            closed = true;
        }
    }

    /**
     * Retains one original RPC across fresh native attempts and publishes only authentic final output.
     * The native namespace supervisor never runs the payload; the retained Frida manager is its sole launcher.
     */
    private static final class ProductionAction implements LinuxBazelSession.Action,
        LinuxBazelAdmission.InvocationTermination
    {
        /** Invocation dependencies retained until this action closes. */
        private final ProductionResources invocation;
        /** Original authenticated Run request and cancellation authority. */
        private final LinuxBazelRunner.Ready ready;
        /** Stable logical identity across all retries. */
        private final String name;
        /** All attempt resources, retained before native mapping. */
        private final Map<Long, ProductionAttempt> attempts = new LinkedHashMap<>();
        /** Exclusive host files and namespace skeleton. */
        private Path directory;
        /** Unmodified complete action arguments. */
        private List<String> arguments;
        /** Unmodified complete action environment. */
        private Map<String, String> environment;
        /** Immutable snapshot and transactional output owner. */
        private LinuxBazelWorkspace workspace;
        /** Listener retained across uncertain accept. */
        private LinuxCargoTransport.Listener listener;
        /** Partial listener cleanup capability. */
        private LinuxBorrowedStreams.CleanupFailure listenerFailure;
        /** Original native connection. */
        private LinuxCargoTransport.Connection connection;
        /** Root helper process and retained private pipe protocol. */
        private NamespaceLease namespace;
        /** Exact owner and blocked-child authentication policy. */
        private LinuxBazelIdentity authenticator;
        /** Independently authenticated original owner. */
        private LinuxBazelIdentity.Owner nativeOwner;
        /** Exact generated helper execution before manager replacement. */
        private LinuxBazelIdentity.Execution prepared;
        /** Original launch target and argv before kernel script interpretation. */
        private LinuxBazelIdentity.Execution launchExecution;
        /** Exact actual kernel execution after manager replacement. */
        private LinuxBazelIdentity.Execution held;
        /** Complete original scheduler action. */
        private LinuxBazelAction delegate;
        /** Admission prediction registered from immutable complete inputs. */
        private Long peak;
        /** Complete successful cleanup barrier. */
        private boolean closed;
        /** Native action/namespace/socket ownership retired before final worker publication. */
        private boolean executionRetired;

        /**
         * Reserves a resource-free original logical action owner.
         * @param invocation original composition owner
         * @param ready authenticated original request
         */
        private ProductionAction(ProductionResources invocation, LinuxBazelRunner.Ready ready)
        {
            this.invocation = invocation;
            this.ready = ready;
            name = "bazel-" + ready.sequence();
        }

        /**
         * Extends unprivileged image membership to the independently mapped fresh private temporary trees.
         * These roots grant no privileged helper load path or exemption from exact process authentication.
         * @return immutable namespace-local permitted runtime and private temporary image roots
         */
        private List<Path> imageRoots()
        {
            List<Path> roots = new ArrayList<>(invocation.configuration.runtime().bindings().stream()
                .map(LinuxBazelProducer.RuntimeBinding::target).toList());
            roots.add(ready.temporaryDirectory());
            roots.add(Path.of("/tmp"));
            return List.copyOf(roots);
        }

        /**
         * Pins ready inputs, native endpoint, helper and exact original execution before queue submission.
         * @throws IOException if any independent authentication or acquisition fails
         */
        private void acquire() throws IOException
        {
            ProductionConfiguration configuration = invocation.configuration;
            LinuxBazelProducer.Runtime runtime = configuration.runtime();
            directory = Files.createDirectory(configuration.scratch().resolve(name));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx--x--x"));
            arguments = List.copyOf(ready.request().getArgumentsList());
            environment = Map.copyOf(ready.request().getEnvironmentVariablesMap());
            if (arguments.isEmpty()) throw new IOException("Original ready action has no argv zero");
            workspace = ready.openWorkspace(directory.resolve("workspace"));
            String identity = invocation.inputIdentities.identify(workspace.immutableSnapshot(), ready.inputRoot(),
                arguments, environment, ready.directory(), runtime.backendIdentity());
            peak = invocation.estimates.register(name, identity).peakBytes();
            Map<String, Object> admitted = new LinkedHashMap<>();
            admitted.put("name", name);
            admitted.put("runSequence", ready.sequence());
            admitted.put("identity", identity);
            admitted.put("peakBytes", peak);
            admitted.put("arguments", arguments);
            admitted.put("workingDirectory", ready.request().getWorkingDirectory());
            admitted.put("inputRoot", ready.inputRoot().toString());
            configuration.observer().record("ready-input", admitted);
            Path executable = executable(arguments.getFirst(), ready.directory(), environment);
            launchExecution = new LinuxBazelIdentity.Execution(new LinuxBazelIdentity.Image(executable,
                digest(visibleSource(executable, workspace.immutableSnapshot(), ready.inputRoot(), runtime.bindings()))),
                arguments, environment, ready.directory(), runtime.actionUid(), runtime.actionGid());
            held = interpretedExecution(launchExecution, workspace.immutableSnapshot(), ready.inputRoot(), runtime.bindings());
            Path endpoint = directory.resolve("control");
            try { listener = LinuxCargoTransport.listen(invocation.scope, endpoint, 1, configuration.timeout()); }
            catch (LinuxBorrowedStreams.CleanupFailure failure) { listenerFailure = failure; throw failure; }
            Files.setAttribute(endpoint, "unix:uid", runtime.actionUid());
            Files.setAttribute(endpoint, "unix:gid", runtime.actionGid());
            Files.setPosixFilePermissions(endpoint, PosixFilePermissions.fromString("rw-------"));
            long device = ((Number) Files.getAttribute(endpoint, "unix:dev", LinkOption.NOFOLLOW_LINKS)).longValue();
            long inode = ((Number) Files.getAttribute(endpoint, "unix:ino", LinkOption.NOFOLLOW_LINKS)).longValue();
            String capability = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            namespace = new NamespaceLease(configuration, directory, ready, arguments, environment,
                endpoint, device, inode, capability, executable);
            namespace.launch();
            long deadline = System.nanoTime() + configuration.timeout().toNanos();
            while (connection == null)
            {
                connection = listener.poll().orElse(null);
                if (connection == null) pause(deadline, "Original native owner connection");
            }
            namespace.authenticateOwner();
            LinuxBorrowedStreams.Peer peer = connection.peer();
            if (peer.pid() != namespace.ownerPid || peer.startTicks() != namespace.ownerBirth ||
                peer.uid() != runtime.actionUid() || peer.gid() != runtime.actionGid())
                throw new IOException("Native owner peer differs from the independently collected namespace child");
            LinuxBazelControl control = LinuxBazelControl.openNative(connection, capability);
            List<String> ownerArguments = new ArrayList<>(List.of(runtime.actionWaiter().toString(), endpoint.toString(),
                capability, "--socket-identity", Long.toUnsignedString(device), Long.toUnsignedString(inode),
                "--cwd", ready.directory().toString(), "0", "--", executable.toString()));
            ownerArguments.addAll(arguments);
            prepared = new LinuxBazelIdentity.Execution(new LinuxBazelIdentity.Image(runtime.actionWaiter(),
                digest(runtime.actionWaiter())), ownerArguments, Map.of(), ready.directory(),
                runtime.actionUid(), runtime.actionGid());
            LinuxBazelIdentity.Execution ownerExecution = new LinuxBazelIdentity.Execution(prepared.image(),
                ownerArguments, Map.of(), Path.of("/"), runtime.actionUid(), runtime.actionGid());
            authenticator = new LinuxBazelIdentity(new LinuxBazelIdentity.Parent(namespace.process.pid(),
                namespace.birth, new LinuxBazelIdentity.Image(runtime.actionOwner(),
                    digest(runtime.actionOwner()))), ownerExecution);
            nativeOwner = authenticator.authenticate(peer, control.identity().pid());
            if (control.identity().birth() != namespace.ownerBirth)
                throw new IOException("Native owner control receipt has another original birth");
            namespace.requireLiveIdentity();
            delegate = new LinuxBazelAction(name, peak, control, (sequence, waits) ->
            {
                ProductionAttempt attempt = new ProductionAttempt(this, sequence, waits);
                if (attempts.putIfAbsent(sequence, attempt) != null)
                    throw new IllegalStateException("Production attempt ordinal was reused");
                return attempt;
            }, configuration.timeout());
        }

        @Override public String name() { return name; }
        @Override public Long peakBytes() { return peak; }
        @Override public JobQueue.Attempt<LinuxBazelControl.Result> create(long sequence) { return delegate.create(sequence); }
        @Override public void pump() throws IOException { if (!executionRetired) delegate.pump(); }
        @Override public OptionalLong pollBlocked() throws IOException
        { return executionRetired ? OptionalLong.empty() : delegate.pollBlocked(); }
        @Override public boolean hasOpenAttempt() { return delegate != null && delegate.hasOpenAttempt(); }

        @Override public void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) throws IOException
        {
            delegate.acceptOutcome(completion);
            ProductionAttempt attempt = attempts.get(completion.attemptSequence());
            if (attempt == null || !attempt.closed) throw new IOException("Original completed attempt is not fully closed");
            JobQueue.Outcome<LinuxBazelControl.Result> outcome = completion.outcome();
            if (outcome.aborted())
            {
                invocation.estimates.completed(name, completion.attemptSequence(), false);
                auditObservation(completion.attemptSequence());
                invocation.configuration.observer().record("original-run-retained-after-discard", Map.of("name", name,
                    "runSequence", ready.sequence(), "attemptSequence", completion.attemptSequence(),
                    "publicationAllowed", ready.publicationAllowed(), "privateAttemptDiscarded", !attempt.frozen));
                return;
            }
            LinuxBazelControl.Result result = outcome.result();
            namespace.finalOwnerStatus = result.signaled() ? 128 + result.status() : result.status();
            retireExecution();
            boolean success = !result.signaled() && result.status() == 0;
            boolean published = false;
            if (attempt.diagnostics == null) throw new IOException("Authentic final attempt has no frozen diagnostics");
            if (success && attempt.frozen)
                published = ready.publish(() -> attempt.diagnostics.publish(ready::publicationAllowed,
                    () -> attempt.workspace.promote(ready::publicationAllowed)), result.status());
            else ready.publish(() -> attempt.diagnostics.publish(ready::publicationAllowed, () -> {}),
                result.signaled() ? 128 + result.status() : result.status());
            if (!published && attempt.frozen) attempt.workspace.discard();
            invocation.estimates.completed(name, completion.attemptSequence(), success && published);
            auditObservation(completion.attemptSequence());
            invocation.configuration.observer().record("publication", Map.of("name", name,
                "attemptSequence", completion.attemptSequence(), "published", published,
                "status", result.status(), "signaled", result.signaled(), "executionRetired", executionRetired));
            close();
        }

        /**
         * Records the exact accepted prediction/sample pair after its native terminal eligibility decision.
         * @param sequence original queue attempt identity
         * @throws IOException if the durable event cannot be appended
         */
        private void auditObservation(long sequence) throws IOException
        {
            for (BazelMemoryEstimates.Observation observation : invocation.estimates.observations())
                if (observation.name().equals(name) && observation.sequence() == sequence)
                {
                    invocation.configuration.observer().record("estimate-outcome", observation);
                    return;
                }
            throw new IOException("Completed native attempt has no estimate observation");
        }

        @Override public void requestTermination() throws IOException
        { if (namespace != null && !executionRetired) namespace.terminate(); }
        @Override public boolean executionImpossible() throws IOException
        { return executionRetired || (namespace != null && namespace.zero()); }

        /**
         * Retires the original native supervisor and endpoints after FINAL and before terminal publication.
         * Private frozen output remains owned independently until promotion or disposal completes.
         * @throws IOException if any original native owner cannot acknowledge closure
         */
        private void retireExecution() throws IOException
        {
            if (executionRetired) return;
            if (delegate != null) delegate.close();
            if (namespace != null) namespace.close();
            if (connection != null) connection.close();
            if (listener != null) listener.close();
            if (listenerFailure != null) { listenerFailure.retryCleanup(); listenerFailure = null; }
            executionRetired = true;
        }

        @Override public void close() throws IOException
        {
            if (closed) return;
            retireExecution();
            if (workspace != null) workspace.close();
            ready.releaseClaim();
            closed = true;
        }
    }

    /** Per-attempt mapping and admission owner, installed before CREATE can have uncertain effects. */
    private static final class ProductionAttempt implements LinuxBazelAction.Resources
    {
        /** Original logical owner. */
        private final ProductionAction action;
        /** Global queue ordinal. */
        private final long sequence;
        /** Original independently authenticated containment and hooks. */
        private final LinuxBazelAdmission admission;
        /** Fresh private filesystem attempt. */
        private LinuxBazelWorkspace.Attempt workspace;
        /** Namespace mapping was acknowledged. */
        private boolean mapped;
        /** Mapping may have acquired a mount even without acknowledgement. */
        private boolean mappingAttempted;
        /** Successful output frozen before native unmap. */
        private boolean frozen;
        /** Frozen authentic non-aborted diagnostic bytes, outside the worker-visible input tree. */
        private Diagnostics diagnostics;
        /** Every attempt dependency was closed after unmap. */
        private boolean closed;
        /** Original cancellation withdrew grants and requested termination. */
        private boolean cancelled;

        /**
         * Creates a resource-free original attempt owner.
         * @param action original logical action
         * @param sequence global ordinal
         * @param waits original queue allocation batch owner
         */
        private ProductionAttempt(ProductionAction action, long sequence, AllocationWaits waits)
        {
            this.action = action;
            this.sequence = sequence;
            ProductionConfiguration configuration = action.invocation.configuration;
            admission = new LinuxBazelAdmission(action.authenticator, action.nativeOwner, action.prepared,
                action.held, configuration.runtime().cgroupParent(), action.invocation.authority, action.name,
                configuration.timeout(), new LinuxBazelNamespaceHooks(action.invocation.scope, UUID.randomUUID(),
                    action.nativeOwner, action.held, action.launchExecution, configuration.runtime().hooks(),
                    new LinuxBazelNamespaceHooks.ActionImages(action.ready.inputRoot(),
                        action.imageRoots()), configuration.timeout()), sequence, waits);
            admission.retainInvocation(action);
        }

        @Override public void prepareWorkspace() throws IOException
        {
            workspace = action.workspace.begin(sequence);
            for (Path stream : action.namespace.privateStreams())
                try (var channel = Files.newByteChannel(stream, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))
                { channel.truncate(0); }
            Path group = admission.reserveContainment();
            action.namespace.prepareOwnership(workspace);
            mappingAttempted = true;
            action.namespace.map(workspace, group);
            mapped = true;
            workspace.bindMerged(action.namespace.merged(), action.namespace.processRoot());
            action.invocation.configuration.observer().record("attempt-mapped", Map.of("name", action.name,
                "attemptSequence", sequence, "containment", group.toString(),
                "merged", action.namespace.merged().toString()));
        }

        @Override public void admit(LinuxBazelControl.Identity child) throws IOException { admission.admit(child); }
        @Override public void start() throws IOException
        {
            admission.start();
            action.invocation.configuration.observer().record("authenticated-hooks-ready-original-resumed",
                Map.of("name", action.name, "attemptSequence", sequence));
            checkCancellation();
        }
        @Override public void pump() throws IOException { checkCancellation(); admission.pump(); }
        @Override public boolean requiresRetryAfterDenial() { return admission.requiresRetryAfterDenial(); }

        @Override public void grant(AllocationWaits.Wait wait) throws IOException
        {
            checkCancellation();
            if (!cancelled)
            {
                admission.grant(wait);
                action.invocation.configuration.observer().record("allocation-granted", Map.of("name", action.name,
                    "attemptSequence", sequence, "installation", wait.installation(), "waitSequence", wait.sequence()));
            }
        }
        @Override public void stop() throws IOException { admission.stop(); }
        @Override public void requestTermination() throws IOException { admission.requestTermination(); }
        @Override public boolean zero() throws IOException { return admission.zero(); }
        @Override public void finishInstrumentation() throws IOException { admission.finishInstrumentation(); }

        /**
         * Treats original RPC cancellation as ordinary native termination, never queue-authorized retry.
         * @throws IOException if grant withdrawal or native termination is uncertain
         */
        private void checkCancellation() throws IOException
        {
            if (!cancelled && !action.ready.publicationAllowed())
            {
                cancelled = true;
                admission.stop();
                admission.requestTermination();
            }
        }

        @Override public void collectWorkspace(JobQueue.Outcome<LinuxBazelControl.Result> outcome) throws IOException
        {
            if (!admission.zero()) throw new IOException("Attempt mapping retains a potentially live action");
            admission.finishInstrumentation();
            if (!outcome.aborted())
                diagnostics = Diagnostics.freeze(action.namespace.privateStreams(), action.ready.streams(),
                    action.directory.resolve("diagnostics-" + sequence));
            if (!outcome.aborted() && !outcome.result().signaled() && outcome.result().status() == 0 &&
                action.ready.publicationAllowed())
            {
                workspace.freeze();
                frozen = true;
            }
            unmap();
            if (!frozen) workspace.discard();
            action.invocation.configuration.observer().record("attempt-zero-unmapped", Map.of("name", action.name,
                "attemptSequence", sequence, "frozen", frozen, "aborted", outcome.aborted()));
        }

        /**
         * Withdraws the acknowledged mount only after host zero and complete instrumentation closure.
         * @throws IOException if unmap acknowledgement remains uncertain
         */
        private void unmap() throws IOException
        {
            if (mapped)
            {
                action.namespace.unmap();
                mapped = false;
                mappingAttempted = false;
            }
            else if (mappingAttempted)
                throw new IOException("Partial native mapping retains its original namespace lease");
        }

        @Override public void close() throws IOException
        {
            if (closed) return;
            if (!admission.zero()) throw new IOException("Attempt resources await complete native zero");
            admission.finishInstrumentation();
            unmap();
            if (!frozen && workspace != null) workspace.discard();
            admission.close();
            closed = true;
        }
    }

    /**
     * Owns the trusted root helper outside the action cgroup and its private length-framed pipes.
     * A failed command permanently withdraws protocol authority; uncertain mounts retain their original lease.
     */
    private static final class NamespaceLease implements AutoCloseable
    {
        /** Host-selected immutable runtime. */
        private final ProductionConfiguration configuration;
        /** Exclusive ready-action host files. */
        private final Path directory;
        /** Original authenticated request. */
        private final LinuxBazelRunner.Ready ready;
        /** Original complete arguments. */
        private final List<String> arguments;
        /** Original complete environment, installed only after privilege drop. */
        private final Map<String, String> environment;
        /** Host-created pinned endpoint. */
        private final Path endpoint;
        /** Exact listener device. */
        private final long device;
        /** Exact listener inode. */
        private final long inode;
        /** Host-selected endpoint authentication secret. */
        private final String capability;
        /** Resolved visible executable; original argv zero remains separate. */
        private final Path executable;
        /** Namespace root skeleton, inaccessible to action writes. */
        private final Path root;
        /** Root supervisor-only containment. */
        private LinuxCgroup helperGroup;
        /** Unprivileged PID1 owner containment, separate from every attempt. */
        private LinuxCgroup ownerGroup;
        /** Exact original root helper process. */
        private Process process;
        /** Exact root helper birth before any pipe receipt is trusted. */
        private long birth;
        /** Original helper image hash pinned before launch. */
        private String helperDigest;
        /** Host PID independently supplied by the trusted helper's own fork result. */
        private long ownerPid;
        /** Owner birth supplied by trusted fork/proc observation. */
        private long ownerBirth;
        /** Authenticated private supervisor mount namespace after LAUNCHED. */
        private String mountNamespace;
        /** Authenticated private owner PID namespace, distinct from the supervisor namespace. */
        private String ownerPidNamespace;
        /** Root skeleton inode pinned before privileged launch. */
        private Object rootKey;
        /** First protocol failure; an uncertain command must never be resent. */
        private Throwable fault;
        /** A mapping has been acknowledged and not subsequently withdrawn. */
        private boolean mapped;
        /** Authentic FINAL projection, never inferred from a synthetic cancellation result. */
        private Integer finalOwnerStatus;
        /** Original CLOSE acknowledgement retained before process wait and descriptor cleanup can fail. */
        private boolean closeAcknowledged;
        /** Native CLOSE acknowledged and process collected. */
        private boolean closed;

        /**
         * Retains host policy without acquiring a process or namespace.
         * @param configuration original production configuration
         * @param directory exclusive host directory
         * @param ready original request
         * @param arguments unchanged original argv
         * @param environment unchanged original environment
         * @param endpoint immutable host listener
         * @param device listener device
         * @param inode listener inode
         * @param capability host-selected secret
         * @param executable independently resolved visible image
         */
        private NamespaceLease(ProductionConfiguration configuration, Path directory, LinuxBazelRunner.Ready ready,
            List<String> arguments, Map<String, String> environment, Path endpoint, long device, long inode,
            String capability, Path executable)
        {
            this.configuration = configuration;
            this.directory = directory;
            this.ready = ready;
            this.arguments = arguments;
            this.environment = environment;
            this.endpoint = endpoint;
            this.device = device;
            this.inode = inode;
            this.capability = capability;
            this.executable = executable;
            root = directory.resolve("root");
        }

        /**
         * Returns private append-only capture endpoints, never worker-visible outputs.
         * The caller truncates them only before CREATE, after the previous whole-attempt zero/unmap barrier.
         * @return stdout and stderr capture files owned by this logical action
         */
        private List<Path> privateStreams()
        { return List.of(directory.resolve("private.stdout"), directory.resolve("private.stderr")); }

        /**
         * Launches the sealed static root helper with no action-controlled environment or search path.
         * @throws IOException if any acquisition or original helper identity is uncertain
         */
        private void launch() throws IOException
        {
            LinuxBazelProducer.Runtime runtime = configuration.runtime();
            Files.createDirectory(root);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
            requireOutsidePrivateTemporary(ready.inputRoot(), ready.temporaryDirectory(), ready.directory(),
                endpoint, runtime.actionOwner(), runtime.actionWaiter(), executable);
            requireTrustedHelper(runtime.actionOwner());
            requireTrustedHelper(runtime.actionWaiter());
            rootKey = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            helperDigest = digest(runtime.actionOwner());
            List<LinuxBazelProducer.RuntimeBinding> bindings = new ArrayList<>(runtime.bindings());
            if (bindings.stream().noneMatch(binding -> binding.target().equals(runtime.actionWaiter())))
                bindings.add(new LinuxBazelProducer.RuntimeBinding(runtime.actionWaiter(), runtime.actionWaiter()));
            List<String> manifest = new ArrayList<>();
            Set<Path> targets = new HashSet<>();
            for (LinuxBazelProducer.RuntimeBinding binding : bindings)
            {
                Path source = binding.source();
                Path target = binding.target();
                requireAbsolute(source);
                requireAbsolute(target);
                requireOutsidePrivateTemporary(target);
                if (!targets.add(target)) throw new IOException("Runtime target is bound more than once: " + target);
                if (target.equals(Path.of("/")) || target.equals(Path.of("/home")) || target.equals(Path.of("/root")) ||
                    target.startsWith(Path.of("/proc")) || overlaps(target, ready.inputRoot()) ||
                    overlaps(target, ready.temporaryDirectory()) || overlaps(target, configuration.scratch()))
                    throw new IOException("Runtime binding exposes a mutable worker or host authority tree: " + target);
                skeleton(target, Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS));
                manifest.add(source.toString());
                manifest.add(target.toString());
            }
            skeleton(Path.of("/proc"), true);
            skeleton(ready.inputRoot(), true);
            skeleton(ready.temporaryDirectory(), true);
            skeleton(ready.directory(), true);
            skeleton(endpoint, false);
            Path runtimeManifest = directory.resolve("runtime.manifest");
            nulFile(runtimeManifest, manifest);
            List<String> nativeEnvironment = new ArrayList<>();
            // The dynamic parked image must never evaluate action-controlled loader variables.
            // The original complete action environment remains in launchExecution for sole manager execve.
            Path environmentManifest = directory.resolve("environment.manifest");
            nulFile(environmentManifest, nativeEnvironment);
            for (Path stream : privateStreams())
                Files.createFile(stream, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            helperGroup = LinuxCgroup.create(runtime.helperParent(), configuration.timeout());
            ownerGroup = LinuxCgroup.create(runtime.helperParent(), configuration.timeout());
            List<String> command = new ArrayList<>(List.of(runtime.actionOwner().toString(), "--launch",
                Integer.toString(runtime.actionUid()), Integer.toString(runtime.actionGid()),
                ownerGroup.directory().toString(), root.toString(), runtimeManifest.toString(),
                runtime.actionWaiter().toString(), endpoint.toString(), Long.toUnsignedString(device),
                Long.toUnsignedString(inode), capability, ready.directory().toString(), environmentManifest.toString(),
                privateStreams().get(0).toString(), privateStreams().get(1).toString(), "--", executable.toString()));
            command.addAll(arguments);
            ProcessBuilder launch = new ProcessBuilder(command);
            launch.environment().clear();
            launch.directory(directory.toFile());
            launch.redirectError(directory.resolve("namespace.stderr").toFile());
            process = launch.start();
            birth = Long.parseLong(LinuxBazelNamespaceHooks.birth(Path.of("/proc", Long.toString(process.pid()))));
            Files.writeString(helperGroup.directory().resolve("cgroup.procs"), Long.toString(process.pid()));
            requireLiveIdentity();
            exchange("INITIALIZE", "INITIALIZED");
            String[] receipt = readFrame().split(" ", -1);
            if (receipt.length == 1 && receipt[0].equals("SETUP_FAILED"))
                throw new IOException("Original root helper acknowledged setup failure before owner fork; retain CLOSE ownership");
            if (receipt.length != 3 || !receipt[0].equals("LAUNCHED"))
                throw new IOException("Root helper did not acknowledge original namespace owner launch");
            try
            {
                ownerPid = Long.parseLong(receipt[1]);
                ownerBirth = Long.parseLong(receipt[2]);
                if (ownerPid <= 0 || ownerBirth <= 0) throw new NumberFormatException("Nonpositive owner identity");
            }
            catch (NumberFormatException failure) { throw new IOException("Malformed native owner receipt", failure); }
            Path supervisorProc = Path.of("/proc", Long.toString(process.pid()));
            mountNamespace = Files.readSymbolicLink(supervisorProc.resolve("ns/mnt")).toString();
            if (mountNamespace.equals(Files.readSymbolicLink(Path.of("/proc/self/ns/mnt")).toString()))
                throw new IOException("Original supervisor did not establish its private mount namespace");
            requireLiveIdentity();
        }

        /**
         * Authenticates completed namespace setup after the exact original owner has connected.
         * LAUNCHED alone is a fork/birth receipt and never proves child setup completed.
         * @throws IOException if owner setup, original image, namespace, root or membership differs
         */
        private void authenticateOwner() throws IOException
        {
            Path proc = Path.of("/proc", Long.toString(ownerPid));
            Path supervisorProc = Path.of("/proc", Long.toString(process.pid()));
            if (!LinuxBazelNamespaceHooks.birth(proc).equals(Long.toString(ownerBirth)) ||
                NativeResources.parentPid(proc) != process.pid() ||
                !Files.isSameFile(proc.resolve("exe"), configuration.runtime().actionWaiter()))
                throw new IOException("Trusted namespace child differs from original helper fork identity");
            ownerPidNamespace = Files.readSymbolicLink(proc.resolve("ns/pid")).toString();
            if (ownerPidNamespace.equals(Files.readSymbolicLink(supervisorProc.resolve("ns/pid")).toString()) ||
                !mountNamespace.equals(Files.readSymbolicLink(proc.resolve("ns/mnt")).toString()) ||
                !Files.isSameFile(proc.resolve("root"), root))
                throw new IOException("Original owner lacks the exact authenticated private PID/mount/root boundaries");
            requireMembership(proc, ownerGroup.directory());
            requireLiveIdentity();
        }

        /**
         * Creates an exact path placeholder without introducing a symbolic-link ancestor.
         * @param target absolute process-visible target
         * @param isDirectory whether a directory mount placeholder is required
         * @throws IOException if any component is linked, mutable or has another type
         */
        private void skeleton(Path target, boolean isDirectory) throws IOException
        {
            requireAbsolute(target);
            Path current = root;
            for (int index = 0; index < target.getNameCount(); ++index)
            {
                current = current.resolve(target.getName(index));
                boolean last = index + 1 == target.getNameCount();
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                {
                    if (last && !isDirectory)
                        Files.createFile(current, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-r--r--")));
                    else Files.createDirectory(current);
                }
                if (Files.isSymbolicLink(current) || ((!last || isDirectory) &&
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Namespace skeleton has a linked or incompatible component: " + current);
                if (!last || isDirectory)
                    Files.setPosixFilePermissions(current, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }

        /**
         * Gives only private attempt directories to the unprivileged action, preserving immutable file inodes.
         * @param attempt fresh private workspace
         * @throws IOException if private ownership cannot be established
         */
        private void prepareOwnership(LinuxBazelWorkspace.Attempt attempt) throws IOException
        {
            LinuxBazelProducer.Runtime runtime = configuration.runtime();
            try (var entries = Files.walk(attempt.lower()))
            {
                for (Path entry : entries.toList())
                {
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                        ownership(entry, runtime.actionUid(), runtime.actionGid());
                    else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
                    {
                        int mode = ((Number) Files.getAttribute(entry, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
                        long links = ((Number) Files.getAttribute(entry, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue();
                        if (links == 1) ownership(entry, runtime.actionUid(), runtime.actionGid());
                        else
                        {
                            int uid = ((Number) Files.getAttribute(entry, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
                            int gid = ((Number) Files.getAttribute(entry, "unix:gid", LinkOption.NOFOLLOW_LINKS)).intValue();
                            int available = uid == runtime.actionUid() ? (mode >>> 6) & 7 :
                                gid == runtime.actionGid() ? (mode >>> 3) & 7 : mode & 7;
                            int original = (mode >>> 6) & 5;
                            if ((available & original) != original)
                                throw new IOException("Restricted ready file must be copied before action ownership: " + entry);
                        }
                    }
                }
            }
            ownership(attempt.upper(), runtime.actionUid(), runtime.actionGid());
            Files.setPosixFilePermissions(attempt.upper(), Files.getPosixFilePermissions(attempt.lower(), LinkOption.NOFOLLOW_LINKS));
            ownership(attempt.temporary(), runtime.actionUid(), runtime.actionGid());
            Files.setPosixFilePermissions(attempt.temporary(), PosixFilePermissions.fromString("rwx------"));
        }

        /**
         * Pins and maps the fresh snapshot at the original absolute worker names.
         * @param attempt fresh private filesystem attempt
         * @param group exact already-reserved empty action cgroup
         * @throws IOException if mapping has uncertain effects
         */
        private void map(LinuxBazelWorkspace.Attempt attempt, Path group) throws IOException
        {
            requireLiveIdentity();
            if (!ownerGroup.isPopulated()) throw new IOException("Original namespace owner cannot accept another attempt");
            String command = String.join("\t", "MAP", ready.inputRoot().toString(), ready.temporaryDirectory().toString(),
                attempt.lower().toString(), attempt.upper().toString(), attempt.work().toString(),
                attempt.temporary().toString(), group.toString());
            exchange(command, "MAPPED");
            mapped = true;
        }

        /**
         * Supplies the exact already authenticated proc-root anchor followed by workspace traversal.
         * @return original supervisor proc magiclink
         */
        private Path processRoot() { return Path.of("/proc", Long.toString(process.pid()), "root"); }

        /**
         * Supplies the helper's private merged view for authenticated snapshot freezing.
         * @return process-root anchored merged input path
         */
        private Path merged()
        {
            return Path.of("/proc", Long.toString(process.pid()), "root")
                .resolve(root.toString().substring(1)).resolve(ready.inputRoot().toString().substring(1));
        }

        /**
         * Unmaps only after the caller established native zero and instrumentation closure.
         * @throws IOException if the exact native unmap is not acknowledged
         */
        private void unmap() throws IOException
        {
            exchange("UNMAP", "UNMAPPED");
            mapped = false;
        }

        /**
         * Revalidates original helper PID birth and exact sealed executable before privileged commands.
         * @throws IOException if the independently owned helper changed or exited
         */
        private void requireLiveIdentity() throws IOException
        {
            if (process == null || !process.isAlive()) throw new IOException("Original root namespace helper exited");
            Path proc = Path.of("/proc", Long.toString(process.pid()));
            if (!LinuxBazelNamespaceHooks.birth(proc).equals(Long.toString(birth)) ||
                !Files.isSameFile(proc.resolve("exe"), configuration.runtime().actionOwner()) ||
                !helperDigest.equals(digest(proc.resolve("exe"))))
                throw new IOException("Root namespace helper identity changed");
            requireMembership(proc, helperGroup.directory());
            if (mountNamespace != null &&
                (!mountNamespace.equals(Files.readSymbolicLink(proc.resolve("ns/mnt")).toString()) ||
                !Objects.equals(rootKey, Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey())))
                throw new IOException("Original supervisor namespace or pinned filesystem root changed");
            if (ownerPidNamespace != null)
            {
                Path ownerProc = Path.of("/proc", Long.toString(ownerPid));
                if (Files.exists(ownerProc) && ownerGroup.isPopulated())
                {
                    if (!LinuxBazelNamespaceHooks.birth(ownerProc).equals(Long.toString(ownerBirth)) ||
                        !mountNamespace.equals(Files.readSymbolicLink(ownerProc.resolve("ns/mnt")).toString()) ||
                        !ownerPidNamespace.equals(Files.readSymbolicLink(ownerProc.resolve("ns/pid")).toString()) ||
                        !Files.isSameFile(ownerProc.resolve("root"), root))
                        throw new IOException("Original namespace owner identity or root changed");
                    requireMembership(ownerProc, ownerGroup.directory());
                }
            }
        }

        /**
         * Requires exact host-chosen unified cgroup membership, never a descendant or sibling group.
         * @param proc original process proc directory
         * @param expected exact retained host group
         * @throws IOException if native membership differs or cannot be established
         */
        private static void requireMembership(Path proc, Path expected) throws IOException
        {
            List<String> unified = Files.readAllLines(proc.resolve("cgroup")).stream()
                .filter(line -> line.startsWith("0::")).toList();
            if (unified.size() != 1) throw new IOException("Original helper has no unique unified membership");
            String relative = unified.getFirst().substring(3);
            if (!relative.startsWith("/") || !Files.isSameFile(Path.of("/sys/fs/cgroup")
                .resolve(relative.substring(1)), expected))
                throw new IOException("Original helper differs from exact host-chosen cgroup membership");
        }

        /**
         * Requests complete owner-tree termination without authorizing a retry or final result.
         * @throws IOException if native owner containment cannot be killed
         */
        private void terminate() throws IOException
        {
            if (ownerGroup != null) ownerGroup.terminate();
        }

        /**
         * Observes complete independent owner-tree zero before an attempt cgroup exists.
         * @return true only when its exact retained cgroup is unpopulated
         * @throws IOException if membership cannot be observed
         */
        private boolean zero() throws IOException { return ownerGroup != null && !ownerGroup.isPopulated(); }

        /**
         * Exchanges one private frame once; uncertainty permanently poisons subsequent commands.
         * @param command host-selected exact command
         * @param expected authentic exact acknowledgement
         * @throws IOException if write, read, identity or acknowledgement fails
         */
        private void exchange(String command, String expected) throws IOException
        {
            if (fault != null) throw new IOException("Original root helper protocol remains uncertain", fault);
            try
            {
                requireLiveIdentity();
                byte[] payload = command.getBytes(StandardCharsets.UTF_8);
                if (payload.length == 0 || payload.length > LinuxCargoTransport.MAX_FRAME_BYTES)
                    throw new IOException("Native helper command exceeds frame bound");
                for (int index = 0; index < command.length(); ++index)
                    if ((command.charAt(index) < 32 && command.charAt(index) != '\t') || command.charAt(index) == 127)
                        throw new IOException("Native helper path contains a protocol delimiter/control byte");
                OutputStream output = process.getOutputStream();
                pipeOperation(() ->
                {
                    output.write(ByteBuffer.allocate(4).putInt(payload.length).array());
                    output.write(payload);
                    output.flush();
                    return null;
                });
                String actual = readFrame();
                if (!actual.equals(expected)) throw new IOException("Root helper acknowledgement differs from " + expected);
            }
            catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
        }

        /**
         * Reads a bounded complete original pipe receipt without trusting unbounded native length.
         * @return exact UTF-8 frame
         * @throws IOException if framing, collection or bounded transfer fails
         */
        private String readFrame() throws IOException
        {
            return pipeOperation(() ->
            {
                InputStream input = process.getInputStream();
                byte[] header = input.readNBytes(4);
                if (header.length != 4) throw new IOException("Original root helper frame header reached EOF");
                int length = ByteBuffer.wrap(header).getInt();
                if (length < 1 || length > LinuxCargoTransport.MAX_FRAME_BYTES)
                    throw new IOException("Original root helper frame length is invalid");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("Original root helper frame reached EOF");
                return new String(bytes, StandardCharsets.UTF_8);
            });
        }

        /**
         * Bounds an original pipe operation without replay; timed-out threads retain the same original pipe.
         * @param operation exclusive original pipe operation
         * @param <T> operation result type
         * @return original operation result
         * @throws IOException if interrupted, expired or failed
         */
        private <T> T pipeOperation(java.util.concurrent.Callable<T> operation) throws IOException
        {
            var result = new java.util.concurrent.CompletableFuture<T>();
            Thread.ofVirtual().start(() ->
            {
                try { result.complete(operation.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            try { return result.get(configuration.timeout().toNanos(), TimeUnit.NANOSECONDS); }
            catch (InterruptedException failure)
            {
                Thread.currentThread().interrupt();
                var interrupted = new InterruptedIOException("Original root helper pipe operation interrupted");
                interrupted.initCause(failure);
                throw interrupted;
            }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure)
            {
                fault = failure;
                throw new IOException("Original root helper pipe transfer failed or remains uncertain", failure);
            }
        }

        /**
         * Collects the supervisor's authentic owner wait status during normal or failed-start cleanup.
         * @param expected original FINAL projection, or null for cleanup without a native result
         * @throws IOException if the original complete-close receipt differs or remains uncertain
         */
        private void exchangeClose(Integer expected) throws IOException
        {
            if (fault != null) throw new IOException("Original root helper protocol remains uncertain", fault);
            try
            {
                requireLiveIdentity();
                pipeOperation(() ->
                {
                    OutputStream output = process.getOutputStream();
                    byte[] bytes = "CLOSE".getBytes(StandardCharsets.US_ASCII);
                    output.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    output.write(bytes);
                    output.flush();
                    return null;
                });
                String[] receipt = readFrame().split(" ", -1);
                if (receipt.length != 2 || !receipt[0].equals("CLOSED"))
                    throw new IOException("Original root helper did not acknowledge complete closure");
                int actual;
                try { actual = Integer.parseInt(receipt[1]); }
                catch (NumberFormatException failure) { throw new IOException("Malformed original owner status", failure); }
                if (actual < 0 || actual > 255 || (expected != null && actual != expected))
                    throw new IOException("Original namespace owner status differs from authenticated FINAL");
                closeAcknowledged = true;
            }
            catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
        }

        @Override public void close() throws IOException
        {
            if (closed) return;
            if (mapped) throw new IOException("Original namespace retains an attempt mapping");
            if (process != null)
            {
                if (fault != null) throw new IOException("Uncertain native namespace retains original cleanup authority", fault);
                if (!closeAcknowledged)
                {
                    if (finalOwnerStatus == null)
                    {
                        terminate();
                        exchangeClose(null);
                    }
                    else exchangeClose(finalOwnerStatus);
                }
                try
                {
                    if (!process.waitFor(configuration.timeout().toNanos(), TimeUnit.NANOSECONDS) || process.exitValue() != 0)
                        throw new IOException("Original namespace helper has not exited successfully");
                }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Original namespace helper collection interrupted", failure);
                }
                if (ownerGroup.isPopulated() || helperGroup.isPopulated())
                    throw new IOException("Original native namespace lease remains populated");
                process.getOutputStream().close();
                process.getInputStream().close();
            }
            if (ownerGroup != null) ownerGroup.close();
            if (helperGroup != null) helperGroup.close();
            closed = true;
        }
    }

    /**
     * Resolves original PATH semantics while retaining the original argv vector unchanged.
     * @param argument original argv zero
     * @param directory original absolute cwd
     * @param environment complete original environment
     * @return absolute action-visible executable name
     * @throws IOException if no executable is visible at the original lookup position
     */
    static Path executable(String argument, Path directory, Map<String, String> environment) throws IOException
    {
        List<Path> candidates = new ArrayList<>();
        if (argument.indexOf('/') >= 0) candidates.add(directory.resolve(argument).normalize());
        else
        {
            String search = environment.getOrDefault("PATH", "/bin:/usr/bin");
            for (String component : search.split(":", -1))
                candidates.add(directory.resolve(component).resolve(argument).normalize());
        }
        for (Path candidate : candidates)
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
        throw new IOException("Original action executable is not visible through its exact PATH/cwd");
    }

    /**
     * Derives the exact image and argv that the Linux kernel exposes after nested shebang interpretation.
     * Each interpreter transition removes the previous argv zero and inserts the single optional shebang argument.
     * @param launch unchanged original execve launch
     * @param snapshot immutable private input root
     * @param visibleInput original action-visible input root
     * @param bindings exact trusted runtime bindings
     * @return actual held ELF execution and kernel-rewritten argument vector
     * @throws IOException if an image is outside the visible roots, malformed or exceeds kernel script depth
     */
    static LinuxBazelIdentity.Execution interpretedExecution(LinuxBazelIdentity.Execution launch,
        Path snapshot, Path visibleInput, List<LinuxBazelProducer.RuntimeBinding> bindings) throws IOException
    {
        Path image = launch.image().executable();
        List<String> arguments = launch.arguments();
        for (int depth = 0; depth <= 4; ++depth)
        {
            Path source = visibleSource(image, snapshot, visibleInput, bindings);
            byte[] prefix;
            try (InputStream input = Files.newInputStream(source)) { prefix = input.readNBytes(256); }
            if (prefix.length < 2 || prefix[0] != '#' || prefix[1] != '!')
                return new LinuxBazelIdentity.Execution(new LinuxBazelIdentity.Image(image, digest(source)), arguments,
                    launch.environment(), launch.directory(), launch.uid(), launch.gid());
            if (depth == 4) throw new IOException("Original action exceeds Linux's nested script interpreter limit");
            int end = 2;
            while (end < prefix.length && prefix[end] != '\n' && prefix[end] != 0) ++end;
            if (end == prefix.length && prefix.length == 256)
                throw new IOException("Original script interpreter line exceeds Linux's bounded shebang buffer");
            String line = new String(prefix, 2, end - 2, StandardCharsets.UTF_8);
            int first = 0;
            while (first < line.length() && (line.charAt(first) == ' ' || line.charAt(first) == '\t')) ++first;
            int last = line.length();
            while (last > first && (line.charAt(last - 1) == ' ' || line.charAt(last - 1) == '\t')) --last;
            int separator = first;
            while (separator < last && line.charAt(separator) != ' ' && line.charAt(separator) != '\t') ++separator;
            if (separator == first) throw new IOException("Original script has no kernel interpreter");
            String interpreter = line.substring(first, separator);
            int optional = separator;
            while (optional < last && (line.charAt(optional) == ' ' || line.charAt(optional) == '\t')) ++optional;
            List<String> rewritten = new ArrayList<>();
            rewritten.add(interpreter);
            if (optional < last) rewritten.add(line.substring(optional, last));
            rewritten.add(image.toString());
            rewritten.addAll(arguments.subList(1, arguments.size()));
            arguments = List.copyOf(rewritten);
            image = launch.directory().resolve(interpreter).normalize();
        }
        throw new IOException("Original script interpreter resolution did not terminate");
    }

    /**
     * Maps a process-visible executable to its immutable snapshot or exact sealed runtime source.
     * @param visible original absolute process-visible path
     * @param snapshot immutable private inputs
     * @param visibleInput original input mount name
     * @param bindings trusted runtime manifest
     * @return host-readable source of the exact mapped bytes
     * @throws IOException if the image is not part of the declared action filesystem
     */
    private static Path visibleSource(Path visible, Path snapshot, Path visibleInput,
        List<LinuxBazelProducer.RuntimeBinding> bindings) throws IOException
    {
        if (visible.startsWith(visibleInput)) return snapshot.resolve(visibleInput.relativize(visible));
        List<LinuxBazelProducer.RuntimeBinding> matches = bindings.stream()
            .filter(binding -> visible.startsWith(binding.target())).toList();
        if (matches.isEmpty()) throw new IOException("Action executable is outside its immutable visible filesystem: " + visible);
        LinuxBazelProducer.RuntimeBinding selected = matches.stream()
            .max(java.util.Comparator.comparingInt(binding -> binding.target().getNameCount())).orElseThrow();
        return selected.source().resolve(selected.target().relativize(visible));
    }

    /**
     * Computes a complete immutable executable digest without spawning a native process.
     * @param path exact executable bytes
     * @return lowercase SHA-256
     * @throws IOException if the complete bytes cannot be read
     */
    private static String digest(Path path) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path))
            {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError("Required SHA-256 is unavailable", failure); }
    }

    /**
     * Authenticates the executable path before privileged process creation can run any of its bytes.
     * @param helper host-selected helper executable
     * @throws IOException if the executable or any ancestor grants untrusted replacement authority
     */
    private static void requireTrustedHelper(Path helper) throws IOException
    {
        requireAbsolute(helper);
        for (Path path = helper; path != null; path = path.getParent())
        {
            if (Files.isSymbolicLink(path)) throw new IOException("Trusted helper path contains a symbolic link: " + path);
            Map<String, Object> attributes = Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            if (((Number) attributes.get("uid")).intValue() != 0 ||
                (((Number) attributes.get("mode")).intValue() & 0b10010) != 0 ||
                (!path.equals(helper) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Trusted helper path permits untrusted replacement: " + path);
        }
        if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(helper))
            throw new IOException("Trusted helper is not an executable regular file");
    }

    /**
     * Writes a root-owned private NUL manifest without allowing embedded delimiters.
     * @param path absent private destination
     * @param fields complete original strings
     * @throws IOException if a field contains NUL or publication fails
     */
    private static void nulFile(Path path, List<String> fields) throws IOException
    {
        for (String field : fields)
            if (field.indexOf(0) >= 0) throw new IOException("Native manifest field contains NUL");
        Files.write(path, (fields.isEmpty() ? "" : String.join("\0", fields) + "\0").getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Applies host-selected ownership without following a symbolic link.
     * @param path private entry
     * @param uid action user
     * @param gid action group
     * @throws IOException if the original entry cannot be changed
     */
    private static void ownership(Path path, int uid, int gid) throws IOException
    {
        Files.setAttribute(path, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(path, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Checks an exact host-selected absolute normalized path.
     * @param path original path
     * @throws IOException if a control delimiter or noncanonical form is present
     */
    private static void requireAbsolute(Path path) throws IOException
    {
        String text = path.toString();
        if (!path.isAbsolute() || !path.normalize().equals(path) ||
            text.chars().anyMatch(value -> value < 32 || value == 127))
            throw new IOException("Native runtime path is not an exact normalized absolute path");
    }

    /**
     * Indicates whether two absolute trees intersect.
     * @param first first tree
     * @param second second tree
     * @return whether either contains the other
     */
    private static boolean overlaps(Path first, Path second) { return first.startsWith(second) || second.startsWith(first); }

    /**
     * Waits briefly within an already established bounded acquisition deadline.
     * @param deadline monotonic acquisition deadline
     * @param operation original operation description
     * @throws IOException if expired or interrupted
     */
    private static void pause(long deadline, String operation) throws IOException
    {
        if (System.nanoTime() - deadline >= 0) throw new IOException(operation + " expired");
        try { Thread.sleep(1); }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " interrupted", failure);
        }
    }

    /**
     * Composes original supervisor, kernel socket discovery, authentication and namespace instrumentation.
     * Accepted actions transfer to the session; all pre-transfer and partial acquisitions stay here.
     */
    private static final class NativeResources implements Resources, LinuxBazelAdmission.InvocationTermination
    {
        /** Linux's unified hierarchy mount, used to interpret proc's hierarchy-relative membership. */
        private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
        /** Borrowed invocation pouch values. */
        private final ToolingScope scope;
        /** Immutable host-authorized launch and action declarations. */
        private final Configuration configuration;
        /** Original native connections retained even when initial decoding fails. */
        private final List<LinuxCargoTransport.Connection> connections = new ArrayList<>();
        /** Original action identities already transferred to the session. */
        private final Set<String> registered = new HashSet<>();
        /** Original endpoint, retained through uncertain binding or cleanup. */
        private LinuxCargoTransport.Listener listener;
        /** Partial native binding cleanup owner. */
        private LinuxBorrowedStreams.CleanupFailure listenerFailure;
        /** Original independent whole-invocation root and zero authority. */
        private LinuxSupervisor root;
        /** Partial original root preparation cleanup owner. */
        private LinuxSupervisor.PreparationFailure rootFailure;
        /** Original root birth pinned before its initial release. */
        private String rootBirth;
        /** Original host-created listener device, pinned before the unprivileged root starts. */
        private long endpointDevice;
        /** Original host-created listener inode, never supplied by an action. */
        private long endpointInode;
        /** Original privileged memory reader retained through all action registrations. */
        private LinuxAuthority authority;
        /** Complete native host and registered-attempt observations. */
        private LinuxMeasurements measurements;
        /** Authenticated discovered action retained until its session accepts ownership. */
        private LinuxBazelAction pending;
        /** Authentic original root result, never inferred from an action result. */
        private OptionalInt result = OptionalInt.empty();
        /** Indicates whether a native accept observed no arrivals after authentic root collection. */
        private boolean drained;
        /** Indicates whether original discovery withdrawal has begun. */
        private boolean stopped;
        /** Indicates whether successful endpoint cleanup has completed. */
        private boolean listenerClosed;
        /** Indicates whether every invocation owner closed successfully. */
        private boolean closed;

        /**
         * Retains configuration without acquiring external resources.
         * @param scope borrowed pouch scope
         * @param configuration trusted native launch policy
         */
        private NativeResources(ToolingScope scope, Configuration configuration)
        {
            this.scope = scope;
            this.configuration = configuration;
        }

        @Override public void acquire() throws IOException
        {
            requireImmutableEndpointParent(Path.of("/"));
            try { listener = LinuxCargoTransport.listen(scope, configuration.endpoint(), 16, configuration.timeout()); }
            catch (LinuxBorrowedStreams.CleanupFailure cause) { listenerFailure = cause; throw cause; }
            Files.setAttribute(configuration.endpoint(), "unix:uid", configuration.bazel().uid());
            Files.setAttribute(configuration.endpoint(), "unix:gid", configuration.bazel().gid());
            Files.setPosixFilePermissions(configuration.endpoint(), PosixFilePermissions.fromString("rw-------"));
            try { authority = LinuxAuthority.open(configuration.authority()); }
            catch (LinuxAuthority.AcquisitionFailure cause) { authority = cause.owner(); throw cause; }
            measurements = LinuxMeasurements.open(authority);
            requireImmutableEndpointParent(Path.of("/"));
            endpointDevice = ((Number) Files.getAttribute(configuration.endpoint(), "unix:dev", LinkOption.NOFOLLOW_LINKS)).longValue();
            endpointInode = ((Number) Files.getAttribute(configuration.endpoint(), "unix:ino", LinkOption.NOFOLLOW_LINKS)).longValue();
            LinuxProcessTree.Launch original = configuration.bazel();
            List<String> command = new ArrayList<>(original.command());
            command.add("--define=ACTION_OWNER_SOCKET_DEVICE=" + Long.toUnsignedString(endpointDevice));
            command.add("--define=ACTION_OWNER_SOCKET_INODE=" + Long.toUnsignedString(endpointInode));
            LinuxProcessTree.Launch bound = new LinuxProcessTree.Launch(original.cgroupParent(), original.scratchParent(),
                original.javaExecutable(), original.classPath(), command, original.environment(), original.directory(),
                original.uid(), original.gid(), original.executionMode());
            try { root = LinuxSupervisor.prepare(bound, configuration.files(), configuration.timeout()); }
            catch (LinuxSupervisor.PreparationFailure cause) { rootFailure = cause; throw cause; }
            rootBirth = LinuxBazelNamespaceHooks.birth(Path.of("/proc", Long.toString(root.rootPid())));
        }

        @Override public Controller.Measurements measurements() { return measurements; }
        @Override public void release() throws IOException { root.release(); }

        @Override public LinuxBazelSession.Action discover() throws IOException
        {
            if (stopped) throw new IOException("Original Bazel discovery has stopped");
            if (pending != null) throw new IOException("Previous discovered action has not transferred");
            Optional<LinuxCargoTransport.Connection> accepted = listener.poll();
            if (accepted.isEmpty())
            {
                if (result.isPresent()) drained = true;
                return null;
            }
            LinuxCargoTransport.Connection connection = accepted.orElseThrow();
            connections.add(connection);
            LinuxBorrowedStreams.Peer peer = connection.peer();
            Path proc = Path.of("/proc", Long.toString(peer.pid()));
            requireEndpointInSandbox(proc);
            LinuxBazelControl control = LinuxBazelControl.openNative(connection, configuration.capability());
            if (control.identity().birth() != peer.startTicks())
                throw new IOException("Original owner claimed another kernel peer birth");
            requireInvocationPeer(proc);
            List<String> arguments = LinuxBazelNamespaceHooks.nativeStrings(proc.resolve("cmdline"));
            List<Definition> matches = configuration.definitions().stream()
                .filter(definition -> boundArguments(definition).equals(arguments)).toList();
            if (matches.size() != 1) throw new IOException("Kernel peer argv does not select exactly one declared wrapped action");
            Definition definition = matches.getFirst();
            if (registered.contains(definition.name())) throw new IOException("Declared wrapped action connected more than once");
            Path directory = Files.readSymbolicLink(proc.resolve("cwd"));
            if (!directory.isAbsolute() || !directory.normalize().equals(directory) ||
                !directory.startsWith(configuration.sandboxRoot()))
                throw new IOException("Original wrapped action cwd is outside its exclusive sandbox parent");
            Path executable = directory.resolve(arguments.getFirst()).normalize();
            LinuxBazelIdentity.Execution prepared = new LinuxBazelIdentity.Execution(
                new LinuxBazelIdentity.Image(executable, definition.ownerSha256()), arguments,
                definition.environment(), directory, configuration.bazel().uid(), configuration.bazel().gid());
            LinuxBazelIdentity.Execution held = new LinuxBazelIdentity.Execution(definition.heldImage(),
                definition.heldArguments(), definition.environment(), directory,
                configuration.bazel().uid(), configuration.bazel().gid());
            long parent = parentPid(proc);
            LinuxBazelIdentity.Parent originalParent = new LinuxBazelIdentity.Parent(parent,
                Long.parseLong(LinuxBazelNamespaceHooks.birth(Path.of("/proc", Long.toString(parent)))),
                configuration.processWrapper());
            LinuxBazelIdentity authenticator = new LinuxBazelIdentity(originalParent, prepared);
            LinuxBazelIdentity.Owner owner = authenticator.authenticate(peer, control.identity().pid());
            requireInvocationPeer(proc);
            requireEndpointInSandbox(proc);
            UUID attempt = UUID.randomUUID();
            pending = new LinuxBazelAction(definition.name(), definition.peakBytes(), control, (sequence, waits) ->
            {
                LinuxBazelAdmission admission = new LinuxBazelAdmission(authenticator, owner, prepared, held,
                    configuration.bazel().cgroupParent(),
                    authority, definition.name(), configuration.timeout(),
                    new LinuxBazelNamespaceHooks(scope, attempt, owner, held, configuration.helper(),
                        configuration.timeout()), sequence, waits);
                admission.retainInvocation(this);
                return admission;
            }, configuration.timeout());
            return pending;
        }

        /**
         * Binds native argv to the listener created by this exact host invocation before root launch.
         * @param definition trusted declared wrapper argv before socket identity insertion
         * @return exact complete original native argv
         */
        private List<String> boundArguments(Definition definition)
        {
            List<String> arguments = new ArrayList<>(definition.ownerArguments());
            arguments.addAll(3, List.of("--socket-identity", Long.toUnsignedString(endpointDevice),
                Long.toUnsignedString(endpointInode)));
            return List.copyOf(arguments);
        }

        /**
         * Rejects endpoint pathname replacement authority in the host or authenticated action's filesystem.
         * Root ownership is interpreted by the host, including through the action's pinned proc root.
         * @param namespaceRoot host-visible root of the filesystem being checked
         * @throws IOException if an ancestor is mutable, linked or not an immutable root-owned directory
         */
        private void requireImmutableEndpointParent(Path namespaceRoot) throws IOException
        {
            Path directory = namespaceRoot.resolve(configuration.endpoint().getParent().toString().substring(1));
            for (Path path = directory; !path.equals(namespaceRoot); path = path.getParent())
            {
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Controller endpoint ancestor is not a direct directory: " + path);
                Map<String, Object> attributes = Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
                if (((Number) attributes.get("uid")).intValue() != 0 ||
                    (((Number) attributes.get("mode")).intValue() & 0022) != 0)
                    throw new IOException("Controller endpoint ancestor permits untrusted replacement: " + path);
            }
        }

        /**
         * Revalidates the exact listener and immutable ancestor directories across the actual action mount boundary.
         * @param proc authenticated kernel peer proc directory in the host namespace
         * @throws IOException if the action sees another endpoint, a symbolic link or replacement authority
         */
        private void requireEndpointInSandbox(Path proc) throws IOException
        {
            requireImmutableEndpointParent(Path.of("/"));
            Path namespaceRoot = proc.resolve("root");
            requireImmutableEndpointParent(namespaceRoot);
            Path endpoint = namespaceRoot.resolve(configuration.endpoint().toString().substring(1));
            if (Files.isSymbolicLink(endpoint) || !Files.isSameFile(configuration.endpoint(), endpoint) ||
                ((Number) Files.getAttribute(endpoint, "unix:dev", LinkOption.NOFOLLOW_LINKS)).longValue() != endpointDevice ||
                ((Number) Files.getAttribute(endpoint, "unix:ino", LinkOption.NOFOLLOW_LINKS)).longValue() != endpointInode ||
                !Files.isSameFile(configuration.endpoint().getParent(), endpoint.getParent()))
                throw new IOException("Sandbox controller endpoint differs from the original immutable host listener");
        }

        /**
         * Checks original root birth and exact subtree containment before trusting any connected action.
         * @param proc host proc directory of the actual kernel socket peer
         * @throws IOException if original root identity or peer containment changed or is unavailable
         */
        private void requireInvocationPeer(Path proc) throws IOException
        {
            Path rootProc = Path.of("/proc", Long.toString(root.rootPid()));
            if (!rootBirth.equals(LinuxBazelNamespaceHooks.birth(rootProc)))
                throw new IOException("Original Bazel root birth changed during discovery");
            if (!root.directory().startsWith(CGROUP_ROOT)) throw new IOException("Original root has another unified hierarchy");
            String expected = "/" + CGROUP_ROOT.relativize(root.directory());
            List<String> groups = Files.readAllLines(proc.resolve("cgroup"));
            List<String> unified = groups.stream().filter(line -> line.startsWith("0::")).toList();
            if (unified.size() != 1) throw new IOException("Kernel peer has no unique unified hierarchy membership");
            String actual = unified.getFirst().substring(3);
            if (!actual.equals(expected) && !actual.startsWith(expected + "/"))
                throw new IOException("Kernel peer is outside the original Bazel invocation containment");
        }

        /**
         * Reads the direct parent in the controller's proc namespace.
         * @param proc original kernel peer's proc directory
         * @return positive host parent PID
         * @throws IOException if native stat is malformed
         */
        private static long parentPid(Path proc) throws IOException
        {
            String stat = Files.readString(proc.resolve("stat"));
            int end = stat.lastIndexOf(')');
            if (end < 0) throw new IOException("Kernel peer stat has no command delimiter");
            String[] fields = stat.substring(end + 2).split(" ");
            try
            {
                long parent = Long.parseLong(fields[1]);
                if (parent <= 0) throw new IOException("Kernel peer has no positive direct parent");
                return parent;
            }
            catch (NumberFormatException | ArrayIndexOutOfBoundsException cause)
            {
                throw new IOException("Kernel peer stat parent is malformed", cause);
            }
        }

        @Override public void registered(LinuxBazelSession.Action action) throws IOException
        {
            if (pending != action) throw new IOException("Registered action differs from the original discovery owner");
            registered.add(action.name());
            pending = null;
        }

        @Override public boolean inputClosed() { return result.isPresent() && drained; }
        @Override public void requestTermination() throws IOException { root.requestTermination(); }
        @Override public boolean executionImpossible() throws IOException { return root.observeCompletion(); }
        @Override public OptionalInt pollToolResult() throws IOException
        {
            if (result.isEmpty() && root.observeCompletion()) result = OptionalInt.of(root.result().rootExit());
            return result;
        }

        @Override public void stopAccepting() throws IOException
        {
            stopped = true;
            // Keep sandbox PID1 alive until queue-owned helpers acknowledge native cleanup.
            // Pre-containment attempts independently retain original invocation termination authority.
            if (listener != null && !listenerClosed) { listener.close(); listenerClosed = true; }
        }

        @Override public void close() throws IOException
        {
            if (closed) return;
            if (rootFailure != null) { rootFailure.close(); rootFailure = null; }
            if (root != null) root.close();
            if (pending != null) { pending.close(); pending = null; }
            for (LinuxCargoTransport.Connection connection : connections) connection.close();
            if (listener != null && !listenerClosed) { listener.close(); listenerClosed = true; }
            if (listenerFailure != null) { listenerFailure.retryCleanup(); listenerFailure = null; }
            if (authority != null) authority.close();
            closed = true;
        }
    }
}
