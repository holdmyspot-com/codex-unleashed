package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.JsonNode;

/**
 * Connects the unchanged Bazel dependency producer to one private Buildbarn backend and the real action coordinator.
 * Host runtime configuration is supplied outside all action roots; it never grants action-selected privileged paths.
 */
public final class LinuxBazelProducer
{
    /** Prevents construction of the invocation composition namespace. */
    private LinuxBazelProducer() {}

    /**
     * Runs the explicit installed producer entry point with one trusted profile followed by scheduler options.
     * Public scheduler selection remains gated separately; this entry point executes the same production composition.
     * @param arguments absolute runtime profile followed by the existing scheduler arguments
     * @throws IOException if configuration or the original producer fails after owned cleanup
     */
    public static void main(String[] arguments) throws IOException
    {
        if (arguments.length < 2) throw new IllegalArgumentException("Expected runtime profile and scheduler options");
        Scheduler.Settings settings = Scheduler.parse(Arrays.copyOfRange(arguments, 1, arguments.length));
        if (!settings.tool().equals("bazel")) throw new IllegalArgumentException("Expected Bazel scheduler selection");
        int result;
        try (ToolingScope scope = new DefaultToolingScope())
        {
            try
            {
                result = run(scope, configuration(scope, settings, Path.of(arguments[0])), System.out, System.err);
            }
            catch (OwnershipFailure failure)
            {
                retire(failure, System.err);
                throw failure;
            }
        }
        System.exit(result);
    }

    /**
     * Keeps the original scope and cleanup lease alive until native closure is acknowledged.
     * An operational error cannot make process exit abandon an uncertain namespace or containment owner.
     * @param failure exact original failure retaining its cleanup lease
     * @param err caller diagnostic stream
     */
    private static void retire(OwnershipFailure failure, PrintStream err)
    {
        failure.printStackTrace(err);
        boolean interrupted = Thread.interrupted();
        long nextDiagnostic = 0;
        try
        {
            while (true)
            {
                try { failure.retryCleanup(); return; }
                catch (IOException | RuntimeException | Error cleanup)
                {
                    long now = System.nanoTime();
                    if (nextDiagnostic == 0 || now - nextDiagnostic >= 0)
                    {
                        err.println("Bazel producer retains original cleanup lease: " + cleanup);
                        nextDiagnostic = now + 10_000_000_000L;
                    }
                    interrupted |= Thread.interrupted();
                    LockSupport.parkNanos(1_000_000_000L);
                    interrupted |= Thread.interrupted();
                }
            }
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * Loads an explicit trusted installed-runtime profile for the existing scheduler options.
     * @param scope original invocation Pouch scope retained through native cleanup
     * @param settings validated unchanged scheduler arguments
     * @param profile absolute host-owned runtime profile, inaccessible to action namespaces
     * @return copied concrete production inputs
     * @throws IOException if a trusted runtime value or manifest is missing or invalid
     */
    static Configuration configuration(ToolingScope scope, Scheduler.Settings settings, Path profile) throws IOException
    {
        requireTrusted(profile);
        requireTrusted(settings.scratch());
        if (!Files.isDirectory(settings.scratch(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Producer scratch must be an existing trusted host directory");
        JsonNode root = scope.getJsonMapper().readTree(Files.readString(profile));
        if (root.path("schema").asInt() != 1) throw new IOException("Unsupported Bazel runtime profile");
        Path java = path(root, "java");
        Path classpath = path(root, "classpath");
        Path nsenter = path(root, "nsenter");
        Path runtimeRoot = path(root, "runtimeRoot");
        var hooks = new LinuxBazelNamespaceHooks.Launch(
            new LinuxBazelNamespaceHooks.Seal(nsenter, text(root, "nsenterSha256")),
            new LinuxBazelNamespaceHooks.Seal(runtimeRoot, text(root, "runtimeRootSha256")),
            new LinuxBazelNamespaceHooks.Seal(java, text(root, "javaSha256")),
            List.of(new LinuxBazelNamespaceHooks.Seal(classpath, text(root, "classpathSha256"))),
            new LinuxBazelNamespaceHooks.Seal(settings.fridaLibrary(), text(root, "fridaSha256")));
        List<RuntimeBinding> bindings = new ArrayList<>();
        for (JsonNode binding : root.path("bindings"))
            bindings.add(new RuntimeBinding(path(binding, "source"), path(binding, "target")));
        var runtime = new Runtime(java, classpath, path(root, "unshare"), nsenter, path(root, "libcgroup"),
            path(root, "cgroupParent"), path(root, "helperParent"), path(root, "actionOwner"), hooks,
            bindings, integer(root, "trustedUid"), integer(root, "trustedGid"), integer(root, "actionUid"),
            integer(root, "actionGid"), text(root, "backendIdentity"));
        return new Configuration(settings, path(root, "distribution"), path(root, "bazel"), runtime,
            Map.copyOf(System.getenv()), capacity(root, "casBytes"), capacity(root, "actionCacheBytes"),
            cachePolicy(root));
    }

    /**
     * Runs one original consumer and preserves its independently collected result through complete owned cleanup.
     * @param scope original Pouch scope borrowed until every native owner closes
     * @param configuration copied trusted configuration
     * @param out caller-owned output stream
     * @param err caller-owned diagnostic stream
     * @return authentic original Bazel consumer status
     * @throws IOException if admission, execution, publication or cleanup remains uncertain
     */
    static int run(ToolingScope scope, Configuration configuration, PrintStream out, PrintStream err) throws IOException
    {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Scheduler.Settings settings = configuration.settings();
        Runtime runtime = configuration.runtime();
        Path invocation = Files.createTempDirectory(settings.scratch(), "bazel-producer-");
        Files.setPosixFilePermissions(invocation, PosixFilePermissions.fromString("rwx--x--x"));
        Path backendRoot = Files.createDirectory(invocation.resolve("backend-root"));
        Files.setPosixFilePermissions(backendRoot, PosixFilePermissions.fromString("rwx------"));
        Path buildRoot = Files.createDirectory(backendRoot.resolve("worker-builds"));
        Files.setPosixFilePermissions(buildRoot, PosixFilePermissions.fromString("rwx------"));
        Files.setAttribute(buildRoot, "unix:uid", runtime.trustedUid());
        Files.setAttribute(buildRoot, "unix:gid", runtime.trustedGid());
        LinuxBazelBackend.Credentials credentials = LinuxBazelBackend.Credentials.generate(
            backendRoot.resolve("credentials"), runtime.javaExecutable().getParent().resolve("keytool"));
        for (Path path : credentials.paths())
        {
            Files.setAttribute(path, "unix:uid", runtime.trustedUid());
            Files.setAttribute(path, "unix:gid", runtime.trustedGid());
        }
        Files.setAttribute(backendRoot.resolve("credentials"), "unix:uid", runtime.trustedUid());
        Files.setAttribute(backendRoot.resolve("credentials"), "unix:gid", runtime.trustedGid());
        Files.setAttribute(backendRoot, "unix:uid", runtime.trustedUid());
        Files.setAttribute(backendRoot, "unix:gid", runtime.trustedGid());
        var runner = new LinuxBazelRunner(buildRoot, new LinuxBazelRunner.Credentials(credentials.certificateAuthority(),
            credentials.serverCertificate(), credentials.serverKey(), credentials.workerCertificate()));
        var audit = new Audit(scope, invocation.resolve("producer-events.jsonl"));
        var ownership = new Ownership(scope, runner, audit);
        try
        {
            audit.record("invocation", Map.of("arguments", settings.arguments(),
                "workspace", settings.workspace().toString(), "backendIdentity", runtime.backendIdentity(),
                "cachePolicy", configuration.cachePolicy().name(), "jobs", settings.budget().jobs()));
            runner.start();
            var backendSettings = new LinuxBazelBackend.Configuration(configuration.distribution(), backendRoot,
                runner.endpoint(), credentials, settings.budget().jobs(), runtime.trustedUid(), runtime.trustedGid(),
                configuration.casBytes(), configuration.actionCacheBytes(), configuration.cachePolicy());
            try { ownership.backend = LinuxBazelBackend.start(scope, backendSettings, ownership::closeActions, runner.workerReadiness()); }
            catch (LinuxBazelBackend.AcquisitionFailure failure)
            {
                ownership.backend = failure.backend();
                throw failure;
            }
            Path launcher = bazelLauncher(invocation.resolve("bazel"), configuration.bazelExecutable(),
                ownership.backend.startupRc());
            Map<String, String> environment = new HashMap<>(configuration.environment());
            environment.put("CODEX_BAZEL_BIN", launcher.toString());
            List<String> command = new ArrayList<>(settings.arguments());
            Path selected = executable(command.getFirst(), environment, settings.workspace());
            if (Files.isSameFile(selected, configuration.bazelExecutable())) selected = launcher;
            command.set(0, selected.toString());
            Path stdout = invocation.resolve("consumer.stdout");
            Path stderr = invocation.resolve("consumer.stderr");
            var launch = new LinuxProcessTree.Launch(runtime.cgroupParent(), invocation, runtime.javaExecutable(),
                runtime.classPath(), command, environment, settings.workspace(), runtime.trustedUid(), runtime.trustedGid());
            var files = new LinuxProcessTree.StandardFiles(Path.of("/dev/null"), stdout, stderr);
            var authority = new LinuxAuthority.Settings(runtime.javaExecutable(), runtime.unshare(), runtime.libcgroup(),
                invocation, Path.of("/sys/fs/cgroup"), Path.of("/proc"), runtime.cgroupParent(), runtime.helperParent(),
                settings.controlTimeout());
            ownership.original = new LinuxBazelInvocation(scope, new LinuxBazelInvocation.ProductionConfiguration(runner, launch,
                files, authority, invocation, runtime, settings.controlTimeout(), audit));
            ownership.original.start();
            var coordinator = new SchedulerCoordinator<>(ownership.original.session(), settings.budget(),
                () -> LockSupport.parkNanos(1_000_000), audit);
            int result = coordinator.run();
            ownership.close();
            audit.verify();
            copy(stdout, out);
            copy(stderr, err);
            return result;
        }
        catch (IOException | RuntimeException | Error cause)
        {
            try { ownership.close(); }
            catch (IOException | RuntimeException | Error cleanup)
            {
                if (cleanup != cause) cause.addSuppressed(cleanup);
                throw new OwnershipFailure(cause, ownership);
            }
            throw cause;
        }
    }

    /** Retains the exact original owners when cleanup cannot establish every required barrier. */
    static final class OwnershipFailure extends IOException
    {
        /** Serializable exception format identity. */
        private static final long serialVersionUID = 1L;
        /** Exact cleanup lease, including the borrowed scope reference. */
        private final transient Ownership ownership;

        /**
         * Preserves the first failure and all still-owned resources.
         * @param cause operational failure with suppressed cleanup diagnostics
         * @param ownership exact invocation cleanup lease
         */
        private OwnershipFailure(Throwable cause, Ownership ownership)
        {
            super("Bazel producer cleanup remains owned; retain its scope and retry the original lease", cause);
            this.ownership = ownership;
        }

        /**
         * Retries cleanup on the original coordinator thread without issuing a new invocation.
         * @throws IOException if any native or provider-independent local owner remains unresolved
         */
        void retryCleanup() throws IOException { ownership.close(); }
    }

    /**
     * Appends ordered production events outside action namespaces without allowing a failed writer to prevent cleanup.
     * The first append failure aborts scheduling; later cleanup events are omitted and the original failure remains fatal.
     */
    public static final class Audit implements SchedulerCoordinator.Observer, AutoCloseable
    {
        /** Original invocation Pouch mapper. */
        private final tools.jackson.databind.json.JsonMapper mapper;
        /** Exclusively owned append stream. */
        private final BufferedWriter writer;
        /** Coordination thread allowed to publish observations. */
        private final Thread owner = Thread.currentThread();
        /** Monotonic local observation order. */
        private long sequence;
        /** First durable append failure, retained through resource cleanup. */
        private IOException failure;

        /**
         * Creates a new evidence stream using the original Pouch mapper.
         * @param scope original invocation scope
         * @param path absent host-owned evidence file outside action roots
         * @throws IOException if the exclusive stream cannot be created
         */
        public Audit(ToolingScope scope, Path path) throws IOException
        {
            mapper = scope.getJsonMapper();
            writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }

        /**
         * Appends one actual owner-thread event and flushes it before further scheduling work.
         * @param event concrete production event name
         * @param payload copied event fields or accepted coordinator record
         * @throws IOException on the first failed append; subsequent cleanup observations preserve that first failure
         */
        @Override public void record(String event, Object payload) throws IOException
        {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Audit belongs to coordination thread");
            if (failure != null) return;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("sequence", sequence++);
            record.put("nanoTime", System.nanoTime());
            record.put("event", event);
            record.put("payload", fields(payload));
            try
            {
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
                writer.flush();
            }
            catch (IOException cause) { failure = cause; throw cause; }
        }

        /**
         * Converts package-private records to explicit fields without reflective module grants.
         * @param payload accepted copied record or ordinary map/scalar
         * @return explicit JSON-compatible representation
         */
        private static Object fields(Object payload)
        {
            if (payload instanceof SchedulerCoordinator.Submission value)
            {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("name", value.name()); fields.put("peakBytes", value.peakBytes());
                return fields;
            }
            if (payload instanceof SchedulerCoordinator.Blocked value)
                return Map.of("name", value.name(), "attemptSequence", value.attemptSequence(),
                    "waitSequence", value.waitSequence());
            if (payload instanceof Policy.Decision value)
                return Map.of("control", value.control().name(), "jobName", value.jobName(), "reason", value.reason());
            if (payload instanceof JobQueue.Completion<?> value)
                return Map.of("name", value.name(), "attemptSequence", value.attemptSequence(),
                    "aborted", value.outcome().aborted(), "result", fields(value.outcome().result()));
            if (payload instanceof LinuxBazelControl.Result value)
                return Map.of("signaled", value.signaled(), "status", value.status());
            if (payload instanceof BazelMemoryEstimates.Observation value)
            {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("name", value.name()); fields.put("attemptSequence", value.sequence());
                fields.put("identity", value.identity()); fields.put("predictedBytes", value.predictedBytes());
                fields.put("observedBytes", value.observedBytes()); fields.put("status", value.status().name());
                return fields;
            }
            return payload;
        }

        /**
         * Rejects incomplete evidence after native cleanup has had its independent chance to finish.
         * @throws IOException if an earlier append failed
         */
        public void verify() throws IOException { if (failure != null) throw failure; }

        /**
         * Closes the owned stream after every native resource has acknowledged retirement.
         * @throws IOException if the close fails
         */
        @Override public void close() throws IOException { writer.close(); }
    }

    /** Original acquisition and cleanup owner retained before external listener/backend startup. */
    private static final class Ownership implements AutoCloseable
    {
        /** Original shared runtime scope; caller must retain it while this owner remains live. */
        private final ToolingScope scope;
        /** Exact authenticated service, also retained when binding fails. */
        private final LinuxBazelRunner runner;
        /** Durable evidence outside action roots, closed only after the native owners. */
        private final Audit audit;
        /** Exact backend after acknowledged successful acquisition. */
        private LinuxBazelBackend backend;
        /** Original independent Bazel root and every transferred native action lease. */
        private LinuxBazelInvocation original;
        /** Indicates every original resource acknowledged closure. */
        private boolean closed;

        /**
         * Retains pre-acquisition owners before a native operation can fail.
         * @param scope borrowed original Pouch scope
         * @param runner original service owner
         * @param audit retained durable event writer
         */
        private Ownership(ToolingScope scope, LinuxBazelRunner runner, Audit audit)
        {
            this.scope = scope;
            this.runner = runner;
            this.audit = audit;
        }

        /**
         * Withdraws admission and closes the original consumer/actions before backend termination.
         * @throws IOException if native actions retain namespace, helper or containment ownership
         */
        private void closeActions() throws IOException
        {
            runner.stopAccepting();
            if (original != null) original.close();
        }

        /**
         * Closes dependencies only after complete original action cleanup succeeds.
         * @throws IOException if any original cleanup lease remains unresolved
         */
        @Override public void close() throws IOException
        {
            if (closed) return;
            closeActions();
            if (backend != null) backend.close();
            runner.close();
            audit.record("owned-cleanup", Map.of("complete", true));
            audit.close();
            closed = true;
        }
    }

    /**
     * Writes the supported wrapper hook without changing incoming Bazel arguments or their ordering.
     * @param output absent trusted launcher path
     * @param bazel pinned real Bazel image
     * @param rc owned startup configuration
     * @return executable launcher path
     * @throws IOException if publication fails
     */
    static Path bazelLauncher(Path output, Path bazel, Path rc) throws IOException
    {
        Files.writeString(output, "#!/bin/sh\nexec " + quote(bazel.toString()) + " --batch --bazelrc=" +
            quote(rc.toString()) + " \"$@\"\n");
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-xr-x"));
        return output;
    }

    /**
     * Quotes a trusted pathname as one literal POSIX shell argument.
     * @param value trusted pathname
     * @return literal shell token
     */
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /**
     * Resolves the caller's original executable using only its explicit environment and cwd.
     * @param value original executable name
     * @param environment copied original environment
     * @param directory original cwd
     * @return direct existing executable path
     * @throws IOException if no executable is available
     */
    private static Path executable(String value, Map<String, String> environment, Path directory) throws IOException
    {
        if (value.contains("/"))
        {
            Path path = directory.resolve(value).normalize();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toRealPath();
        }
        else
            for (String entry : environment.getOrDefault("PATH", "").split(":", -1))
            {
                Path path = directory.resolve(entry).resolve(value).normalize();
                if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toRealPath();
            }
        throw new IOException("Original consumer executable is unavailable: " + value);
    }

    /**
     * Copies a collected original stream without closing the caller's stream.
     * @param path original stream file
     * @param output borrowed user stream
     * @throws IOException if reading fails
     */
    private static void copy(Path path, PrintStream output) throws IOException
    {
        try (var input = Files.newInputStream(path)) { input.transferTo(output); }
    }

    /**
     * Reads one explicit absolute host pathname.
     * @param root trusted JSON object
     * @param name field name
     * @return absolute normalized path
     * @throws IOException if the field is absent, relative or noncanonical
     */
    private static Path path(JsonNode root, String name) throws IOException
    {
        Path path = Path.of(text(root, name));
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("Invalid runtime pathname: " + name);
        return path;
    }

    /**
     * Reads the explicit normal/recovery action-cache policy without mutating the original consumer arguments.
     * @param root trusted profile
     * @return supported backend action-cache policy
     * @throws IOException if the named policy is unsupported
     */
    private static LinuxBazelBackend.CachePolicy cachePolicy(JsonNode root) throws IOException
    {
        try { return LinuxBazelBackend.CachePolicy.valueOf(text(root, "cachePolicy")); }
        catch (IllegalArgumentException failure) { throw new IOException("Invalid backend cache policy", failure); }
    }

    /**
     * Reads one nonempty trusted text field.
     * @param root trusted JSON object
     * @param name field name
     * @return exact field text
     * @throws IOException if the value is absent or malformed
     */
    private static String text(JsonNode root, String name) throws IOException
    {
        JsonNode value = root.path(name);
        if (!value.isString() || value.asString().isEmpty()) throw new IOException("Missing runtime text: " + name);
        return value.asString();
    }

    /**
     * Reads an explicit backend store capacity without choosing a hidden disk allocation policy.
     * @param root trusted JSON profile
     * @param name store capacity field
     * @return configured capacity in bytes, at least 64 MiB for the pinned block layout
     * @throws IOException if capacity is missing or below the supported minimum
     */
    private static long capacity(JsonNode root, String name) throws IOException
    {
        JsonNode value = root.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 67_108_864)
            throw new IOException("Invalid backend store capacity: " + name);
        return value.asLong();
    }

    /**
     * Reads one unprivileged numeric credential.
     * @param root trusted JSON object
     * @param name field name
     * @return positive UID or GID
     * @throws IOException if the value is absent or privileged
     */
    private static int integer(JsonNode root, String name) throws IOException
    {
        JsonNode value = root.path(name);
        if (!value.canConvertToInt() || value.asInt() <= 0) throw new IOException("Invalid runtime credential: " + name);
        return value.asInt();
    }

    /**
     * Requires root ownership and no unprivileged replacement authority over the host profile.
     * @param path absolute host profile
     * @throws IOException if the profile or an ancestor can be replaced by an action user
     */
    private static void requireTrusted(Path path) throws IOException
    {
        if (!path.isAbsolute()) throw new IOException("Runtime profile must be absolute");
        for (Path current = path; current != null; current = current.getParent())
        {
            if (Files.isSymbolicLink(current)) throw new IOException("Runtime profile ancestry is symbolic");
            var attributes = Files.readAttributes(current, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            if (((Number) attributes.get("uid")).intValue() != 0 ||
                (((Number) attributes.get("mode")).intValue() & 0022) != 0)
                throw new IOException("Runtime profile ancestry permits untrusted replacement");
        }
    }

    /**
     * Complete trusted invocation configuration; no member originates at the action registration boundary.
     * @param settings validated original scheduler options
     * @param distribution installed pinned backend directory
     * @param bazelExecutable trusted actual Bazel image
     * @param runtime trusted native helper/runtime policy
     * @param environment complete original consumer environment
     * @param casBytes explicit CAS disk capacity in bytes
     * @param actionCacheBytes explicit action-cache disk capacity in bytes
     * @param cachePolicy explicit normal or uncached recovery policy
     */
    record Configuration(Scheduler.Settings settings, Path distribution, Path bazelExecutable, Runtime runtime,
        Map<String, String> environment, long casBytes, long actionCacheBytes, LinuxBazelBackend.CachePolicy cachePolicy)
    {
        /**
         * Copies original environment before backend setup.
         * @param settings original options
         * @param distribution backend bundle
         * @param bazelExecutable trusted Bazel image
         * @param runtime native runtime
         * @param environment original consumer environment
         * @param casBytes explicit CAS capacity in bytes
         * @param actionCacheBytes explicit action-cache capacity in bytes
         * @param cachePolicy explicit normal or uncached recovery policy
         */
        Configuration
        {
            environment = Map.copyOf(environment);
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            if (casBytes < 67_108_864 || actionCacheBytes < 67_108_864)
                throw new IllegalArgumentException("Backend stores require explicit supported capacities");
        }
    }

    /**
     * Immutable host-selected native execution capabilities.
     * @param javaExecutable trusted JDK launcher
     * @param classPath trusted application/helper jar
     * @param unshare trusted util-linux unshare
     * @param nsenter trusted util-linux nsenter
     * @param libcgroup trusted libcgroup-v3 library
     * @param cgroupParent delegated action and original-consumer parent
     * @param helperParent separate privileged-reader delegation
     * @param actionOwner packaged trusted native owner/helper
     * @param hooks sealed original Frida helper launch
     * @param bindings explicit read-only runtime mounts
     * @param trustedUid backend and original-consumer UID
     * @param trustedGid backend and original-consumer GID
     * @param actionUid distinct unprivileged action UID
     * @param actionGid distinct unprivileged action GID
     * @param backendIdentity pinned toolchain/backend/platform content identity
     */
    record Runtime(Path javaExecutable, Path classPath, Path unshare, Path nsenter, Path libcgroup,
        Path cgroupParent, Path helperParent, Path actionOwner, LinuxBazelNamespaceHooks.Launch hooks,
        List<RuntimeBinding> bindings, int trustedUid, int trustedGid, int actionUid, int actionGid,
        String backendIdentity)
    {
        /**
         * Returns the separately sealed unprivileged glibc parked image beside the static supervisor.
         * Its fixed manifest name cannot be supplied by an action or selected through PATH.
         * @return absolute packaged waiter image
         */
        Path actionWaiter() { return actionOwner.resolveSibling("linux-bazel-action-waiter"); }

        /**
         * Copies runtime mounts and rejects shared action/backend credentials.
         * @param javaExecutable trusted JDK image
         * @param classPath trusted helper classpath
         * @param unshare trusted namespace utility
         * @param nsenter trusted namespace entry utility
         * @param libcgroup trusted reader library
         * @param cgroupParent action parent
         * @param helperParent reader parent
         * @param actionOwner native helper
         * @param hooks original hook owner policy
         * @param bindings explicit runtime mounts
         * @param trustedUid backend UID
         * @param trustedGid backend GID
         * @param actionUid action UID
         * @param actionGid action GID
         * @param backendIdentity exact reusable backend identity
         */
        Runtime
        {
            bindings = List.copyOf(bindings);
            if (trustedUid <= 0 || trustedGid <= 0 || actionUid <= 0 || actionGid <= 0 || trustedUid == actionUid)
                throw new IllegalArgumentException("Backend and action require distinct unprivileged UIDs");
        }
    }

    /**
     * Explicit trusted runtime mount; destination semantics are retained inside the action root.
     * @param source host runtime inode/tree
     * @param target unchanged absolute action-visible runtime path
     */
    public record RuntimeBinding(Path source, Path target) {}
}
