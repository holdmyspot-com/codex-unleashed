package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Composes one explicitly authorized installed Mac Cargo invocation while retaining unresolved native cleanup. */
public final class MacCargoSchedule
{
    /** Prevents construction of the command composition namespace. */
    private MacCargoSchedule() {}

    /**
     * Runs the actual installed Cargo invocation and coordinator with borrowed inherited standard descriptors.
     * The caller keeps tooling and descriptors alive through successful execution or pending cleanup.
     * @param tooling caller-owned shared services
     * @param settings exact parsed Cargo arguments, working directory and scheduling policy
     * @param launchConfiguration explicit immutable administrator launch document
     * @return genuine original Cargo status after complete collection
     * @throws IOException if authorization, streams, construction, execution or cleanup fails
     * @throws Scheduler.PendingCleanup if native or queue ownership remains; retain and close on the originating thread
     */
    public static int run(ToolingScope tooling, Scheduler.Settings settings, Path launchConfiguration) throws IOException
    {
        return run(tooling, settings, launchConfiguration, new NativeBoundary());
    }

    /**
     * Composes execution against the same explicit external acquisition boundary used by the installed command.
     * @param tooling borrowed services
     * @param settings exact parsed command settings
     * @param launchConfiguration explicit administrator document
     * @param boundary native preflight and acquisition boundary
     * @return genuine collected tool status
     * @throws IOException if preflight, execution or cleanup fails
     * @throws Scheduler.PendingCleanup if owned native resources cannot yet close
     */
    public static int run(ToolingScope tooling, Scheduler.Settings settings, Path launchConfiguration, Boundary boundary) throws IOException
    {
        Objects.requireNonNull(settings, "settings"); Objects.requireNonNull(boundary, "boundary");
        if (!"cargo".equals(settings.tool())) throw new IOException("Mac Cargo composition requires the Cargo tool");
        var scope = boundary.createScope(tooling);
        Namespace namespace = null;
        Execution execution = null;
        Throwable primary = null;
        try
        {
            var authorization = MacCargoLaunchConfiguration.read(scope, launchConfiguration);
            var installed = MacCargoLaunchConfiguration.installed(Path.of(System.getProperty("java.home")), settings.fridaLibrary());
            boundary.preflight(tooling, authorization, installed, settings);
            MacWorkloadNative.Streams streams = boundary.inheritedStreams(tooling);
            if (streams.input() != 0 || streams.output() != 1 || streams.error() != 2)
                throw new IOException("Public Mac Cargo composition borrows only actual inherited standard descriptors");
            namespace = boundary.createNamespace(settings.scratch(), authorization.gid());
            Path state = namespace.state(); Path scratch = namespace.attempts();
            Path socket = namespace.endpoint().resolve("c");
            var environment = new HashMap<>(authorization.environment());
            environment.put("RUSTC", authorization.rustc().toString());
            environment.put("RUSTC_WRAPPER", installed.wrapper().toString());
            environment.put("CODEX_CARGO_SOCKET", socket.toString());
            List<String> command = new ArrayList<>(); command.add(authorization.cargo().toString()); command.addAll(settings.arguments());
            var launch = new MacWorkloadNative.Launch(installed.workload(), installed.anchor(), installed.modules(), state,
                settings.workspace(), streams, command, environment.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList(),
                authorization.uid(), authorization.gid());
            var registration = new MacCargoRegistration.Configuration(authorization.cargo(), installed.wrapper(), authorization.rustc(),
                authorization.uid(), authorization.gid(), environment);
            var configuration = new MacCargoInvocation.Configuration(launch, registration, socket, installed.cargoBridge(),
                installed.frida(), scratch, settings.budget().jobs(), settings.controlTimeout());
            execution = boundary.acquire(scope, configuration, authorization.memory());
            execution.start();
            return execution.run(settings.budget());
        }
        catch (IOException | RuntimeException | Error failure)
        {
            primary = failure;
            throw failure;
        }
        finally
        {
            try
            {
                if (execution != null && !execution.isClosed()) execution.close();
                if (execution != null && !execution.isClosed()) throw new IOException("Mac Cargo native or queue ownership remains retained");
                if (namespace != null) { boundary.removeNamespace(namespace); namespace = null; }
                scope.close();
            }
            catch (IOException | RuntimeException | Error cleanup)
            {
                Throwable failure = primary == null ? cleanup : primary;
                if (failure != cleanup) failure.addSuppressed(cleanup);
                Execution retained = execution; Namespace retainedNamespace = namespace;
                throw new Scheduler.PendingCleanup(failure, () ->
                {
                    if (retained != null && !retained.isClosed()) retained.close();
                    if (retained != null && !retained.isClosed())
                        throw new IOException("Original Mac Cargo queue/native cleanup remains owned");
                    if (retainedNamespace != null) boundary.removeNamespace(retainedNamespace);
                    scope.close();
                });
            }
        }
    }

    /**
     * Checks the longest native compiler state path before any invocation directory or native owner is created.
     * @param scratch trusted absolute scratch parent
     * @throws IOException if Darwin socket/state path byte limits cannot accommodate every generated suffix
     */
    public static void requirePathBudget(Path scratch) throws IOException
    {
        Path longest = scratch.resolve("m-" + "9".repeat(20)).resolve("a").resolve("compiler-" + "9".repeat(20));
        if (longest.toString().getBytes(StandardCharsets.UTF_8).length > 90 ||
            longest.resolve("control.sock").toString().getBytes(StandardCharsets.UTF_8).length >= 104)
            throw new IOException("Mac Cargo scratch namespace exceeds native state/socket path limits: " + scratch);
    }

    /**
     * Removes only the exclusive owned invocation tree after all native and queue borrowers have closed.
     * @param directory exclusively created root
     * @throws IOException if any owned path cannot be removed
     */
    private static void removeDirectory(Path directory) throws IOException
    {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(directory))
        {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }

    /**
     * Retains the exclusive root-owned namespace paths without exposing caller-provided native handles.
     * @param directory exclusive invocation directory
     * @param state original Cargo state directory
     * @param attempts compiler attempt scratch parent
     * @param endpoint workload-group searchable controller endpoint parent
     */
    public record Namespace(Path directory, Path state, Path attempts, Path endpoint)
    {
        /**
         * Requires the fixed exclusively owned namespace layout used by the native composition.
         * @param directory absolute invocation root
         * @param state exact original state child
         * @param attempts exact compiler scratch child
         * @param endpoint exact endpoint child
         */
        public Namespace
        {
            if (!directory.isAbsolute() || !directory.equals(directory.normalize()) ||
                !state.equals(directory.resolve("o")) || !attempts.equals(directory.resolve("a")) ||
                !endpoint.equals(directory.resolve("e")))
                throw new IllegalArgumentException("Exact absolute owned Mac namespace layout required");
        }
    }

    /** External platform preflight and resource acquisition; controlled portable checks do not grant native acceptance. */
    public interface Boundary
    {
        /**
         * Retains resource-free scoped services before any native initialization.
         * @param tooling caller-owned services
         * @return original scope retained through completed native destruction
         */
        default MacCargoScope createScope(ToolingScope tooling) { return new MacCargoScope(tooling); }
        /**
         * Validates platform, credentials, namespace and pinned configuration before creating resources.
         * @param tooling borrowed native services
         * @param authorization parsed administrator document
         * @param installed actual bundled runtime files
         * @param settings parsed command settings
         * @throws IOException if authority or namespace validation fails
         */
        void preflight(ToolingScope tooling, MacCargoLaunchConfiguration authorization,
            MacCargoLaunchConfiguration.Installed installed, Scheduler.Settings settings) throws IOException;
        /**
         * Borrows actual native standard descriptors after validating readable/writable access.
         * @param tooling borrowed native services
         * @return inherited descriptors zero, one and two, never document-provided handles
         * @throws IOException if any descriptor is closed or has incompatible access
         */
        MacWorkloadNative.Streams inheritedStreams(ToolingScope tooling) throws IOException;
        /**
         * Creates an exclusively owned native namespace after complete authority and stream preflight.
         * @param scratch trusted scratch parent
         * @param gid authorized positive workload group for endpoint traversal
         * @return complete fresh root-owned paths
         * @throws IOException if exclusive creation or group assignment fails
         */
        Namespace createNamespace(Path scratch, int gid) throws IOException;
        /**
         * Removes exclusive namespace state only after every native and queue borrower has closed.
         * @param namespace retained exclusive namespace
         * @throws IOException if owned state cannot be removed
         */
        void removeNamespace(Namespace namespace) throws IOException;
        /**
         * Retains the actual invocation owner before acquisition begins.
         * @param scope invocation-owned services retained through cleanup
         * @param configuration complete native invocation inputs
         * @param memory optional explicit production estimate settings
         * @return retained execution owner with resource-free construction
         * @throws IOException if construction cannot retain ownership
         */
        Execution acquire(MacCargoScope scope, MacCargoInvocation.Configuration configuration,
            CargoMemoryEstimates.Settings memory) throws IOException;
    }

    /** Retains invocation/coordinator lifetime across execution, failure and pending cleanup. */
    public interface Execution extends AutoCloseable
    {
        /**
         * Acquires and releases the actual invocation under this retained owner.
         * @throws IOException if native startup fails
         */
        void start() throws IOException;
        /**
         * Runs the actual coordinator against the retained invocation session.
         * @param budget parsed scheduler policy
         * @return genuine original Cargo status after complete collection
         * @throws IOException if coordination or cleanup fails
         */
        int run(Policy.Budget budget) throws IOException;
        /**
         * Reports successful complete native and queue closure without performing effects.
         * @return true only after every borrowed native resource is no longer owned
         */
        boolean isClosed();
        /**
         * Progresses supported cleanup while preserving unresolved native and queue ownership.
         * @throws IOException if collection or destruction remains unresolved
         */
        @Override void close() throws IOException;
    }

    /** Uses actual installed Mac native invocation and scheduler coordinator without fallback execution. */
    private static final class NativeBoundary implements Boundary
    {
        /** Creates a resource-free boundary. */
        private NativeBoundary() {}
        @Override public void preflight(ToolingScope tooling, MacCargoLaunchConfiguration authorization,
            MacCargoLaunchConfiguration.Installed installed, Scheduler.Settings settings) throws IOException
        {
            if (!System.getProperty("os.name").equals("Mac OS X")) throw new IOException("Mac Cargo scheduling requires macOS");
            if (nativeCall(tooling, "getuid") != 0 || nativeCall(tooling, "geteuid") != 0)
                throw new IOException("Mac Cargo scheduling requires actual real and effective controller UID zero");
            authorization.requireTrusted(installed);
            MacCargoLaunchConfiguration.requireRootControlled(settings.scratch());
            MacCargoLaunchConfiguration.requireWorkloadTraversal(settings.scratch(), authorization.gid());
            requirePathBudget(settings.scratch());
            if (!settings.workspace().isAbsolute() || !settings.workspace().equals(settings.workspace().normalize()) ||
                !Files.isDirectory(settings.workspace(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Cargo working directory must be an existing absolute normalized nonsymlink directory");
        }
        @Override public MacWorkloadNative.Streams inheritedStreams(ToolingScope tooling) throws IOException
        {
            for (int fd = 0; fd < 3; ++fd)
            {
                int flags = nativeCall(tooling, "fcntl", fd, 3, 0);
                int access = flags & 3;
                if (flags < 0 || (fd == 0 ? access == 1 : access == 0))
                    throw new IOException("Inherited standard descriptor is closed or has incompatible access: " + fd);
            }
            return new MacWorkloadNative.Streams(0, 1, 2);
        }
        @Override public Namespace createNamespace(Path scratch, int gid) throws IOException
        {
            Path directory = Files.createTempDirectory(scratch, "m-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x--x")));
            try
            {
                Path state = Files.createDirectory(directory.resolve("o"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path attempts = Files.createDirectory(directory.resolve("a"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path endpoint = Files.createDirectory(directory.resolve("e"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx--x---")));
                Files.setAttribute(endpoint, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
                return new Namespace(directory, state, attempts, endpoint);
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { removeDirectory(directory); }
                catch (IOException | RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(cleanup);
                    throw new Scheduler.PendingCleanup(failure, () -> removeDirectory(directory));
                }
                throw failure;
            }
        }
        @Override public void removeNamespace(Namespace namespace) throws IOException { removeDirectory(namespace.directory()); }
        @Override public Execution acquire(MacCargoScope scope, MacCargoInvocation.Configuration configuration,
            CargoMemoryEstimates.Settings memory)
        {
            MacCargoInvocation invocation = memory == null ? new MacCargoInvocation(scope, configuration) :
                new MacCargoInvocation(scope, configuration, memory);
            return new Execution()
            {
                @Override public void start() throws IOException { invocation.start(); }
                @Override public int run(Policy.Budget budget) throws IOException
                {
                    return new SchedulerCoordinator<>(invocation.session(), budget, () -> Thread.sleep(10),
                        new FailureReport(System.err::println)).run();
                }
                @Override public boolean isClosed() { return invocation.isClosed(); }
                @Override public void close() throws IOException { invocation.close(); }
            };
        }
    }

    /**
     * Reads required native Darwin credentials or descriptor flags through the borrowed native linker.
     * The descriptor call uses Darwin F_GETFL and three integer arguments; only its final argument is variadic.
     * @param tooling borrowed native linker
     * @param operation exact libc operation selected by this implementation
     * @param arguments exact native integer arguments
     * @return original native integer result
     * @throws IOException if binding or native observation fails
     */
    // Required JDK foreign downcalls use explicit Darwin ABI layouts and never grant cleanup authority themselves.
    @SuppressWarnings("restricted")
    private static int nativeCall(ToolingScope tooling, String operation, int... arguments) throws IOException
    {
        Linker linker = tooling.getNativeLinker();
        var symbol = linker.defaultLookup().find(operation).orElseThrow(() -> new IOException("Missing native preflight operation: " + operation));
        FunctionDescriptor descriptor = arguments.length == 0 ? FunctionDescriptor.of(ValueLayout.JAVA_INT) :
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
        MethodHandle function = arguments.length == 0 ? linker.downcallHandle(symbol, descriptor) :
            linker.downcallHandle(symbol, descriptor, Linker.Option.firstVariadicArg(2));
        try
        {
            return arguments.length == 0 ? (int) function.invokeExact() :
                (int) function.invokeExact(arguments[0], arguments[1], arguments[2]);
        }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IOException("Native Mac preflight observation failed: " + operation, failure); }
    }
}
