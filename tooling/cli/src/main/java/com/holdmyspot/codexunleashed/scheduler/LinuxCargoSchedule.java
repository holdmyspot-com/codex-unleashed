package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;

/** Constructs one explicitly privileged, installed Linux Cargo invocation. */
public final class LinuxCargoSchedule
{
    /** Prevents construction of this command composition boundary. */
    private LinuxCargoSchedule() {}

    /**
     * Runs the actual Cargo command and returns its collected status after native cleanup.
     * @param tooling caller-owned services retained throughout execution
     * @param settings explicitly parsed command settings
     * @return actual Cargo status
     * @throws IOException if authority, construction, execution or cleanup cannot be established
     */
    static int run(ToolingScope tooling, Scheduler.Settings settings) throws IOException
    {
        requireElevated();
        var scope = new LinuxCargoScope(tooling);
        boolean pending = false;
        try
        {
            var configuration = LinuxCargoLaunchConfiguration.read(scope, settings.linuxLaunchConfiguration());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
            Path modules = Path.of(System.getProperty("java.home"), "lib", "modules").toRealPath();
            configuration.requireTrusted(settings.fridaLibrary(), java, modules);
            requireTrustedScratch(settings.scratch());
            requireSocketNamespace(settings.scratch());
            Path wrapper = java.getParent().resolve("codex-cargo-wrapper-linux");
            if (!Files.isRegularFile(wrapper, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(wrapper))
                throw new IOException("Installed Linux Cargo wrapper is missing: " + wrapper);
            Path directory = Files.createTempDirectory(settings.scratch(), "c-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            LinuxCgroup group = null;
            LinuxCargoInvocation invocation = null;
            Throwable primary = null;
            try
            {
                group = LinuxCgroup.create(configuration.delegationParent(), settings.controlTimeout());
                Path containment = group.directory();
                if (!List.of(Files.readString(containment.resolve("cgroup.controllers")).trim().split("\\s+")).contains("memory"))
                    throw new IOException("Delegated containment lacks the memory controller");
                Files.writeString(containment.resolve("cgroup.subtree_control"), "+memory");
                Path actions = Files.createDirectory(containment.resolve("actions"));
                Path helpers = Files.createDirectory(containment.resolve("helpers"));
                Path cargo = Files.createDirectory(containment.resolve("cargo"));
                Path streams = Files.createDirectory(directory.resolve("s"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Path controller = directory.resolve("c");
                Path contextPath = directory.resolve("w.conf");
                var environment = new HashMap<>(configuration.environment());
                environment.put("RUSTC", configuration.rustc().toString());
                environment.put("RUSTC_WRAPPER", wrapper.toString());
                environment.put("CODEX_LINUX_CARGO_WRAPPER_CONFIG", contextPath.toString());
                var context = new LinuxProcessTree.Launch(actions, directory, java, modules,
                    List.of(configuration.rustc().toString()), environment, settings.workspace(),
                    configuration.uid(), configuration.gid(), LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
                // The wrapper waits on the controller while the queue holds its attempt, so its read bound must not
                // expire; a lost controller still ends the wrapper through end of file.
                LinuxCargoLaunchConfiguration.writeWrapper(contextPath, controller,
                    LinuxCargoLaunchConfiguration.WRAPPER_TIMEOUT_MILLIS);
                var command = new ArrayList<String>();
                command.add(configuration.cargo().toString());
                command.addAll(settings.arguments());
                var root = new LinuxProcessTree.Launch(cargo, directory, java, modules, command, environment,
                    settings.workspace(), configuration.uid(), configuration.gid(),
                    LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
                var registration = new LinuxCargoRegistration.Configuration(configuration.cargo(), wrapper,
                    configuration.rustc(), List.of(wrapper.toString()), context, environment);
                var authority = new LinuxAuthority.Settings(java, configuration.unshare(), configuration.libcgroup(),
                    directory, configuration.cgroupRoot(), configuration.procRoot(), actions, helpers, settings.controlTimeout());
                var inputs = new LinuxCargoInvocation.Configuration(root, new LinuxCargoInvocation.BorrowedRoot(streams),
                    registration, controller, settings.fridaLibrary(), authority, settings.budget().jobs(),
                    settings.controlTimeout());
                invocation = configuration.memory() == null ? new LinuxCargoInvocation(scope, inputs) :
                    new LinuxCargoInvocation(scope, inputs, configuration.memory());
                invocation.start();
                return new SchedulerCoordinator<>(invocation.session(), settings.budget(), () -> Thread.sleep(10)).run();
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
                    if (invocation != null && !invocation.isClosed()) invocation.close();
                    if (invocation == null || invocation.isClosed())
                    {
                        if (group != null) group.close();
                        try (var entries = Files.walk(directory))
                        {
                            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
                        }
                    }
                }
                catch (IOException | RuntimeException | Error cleanup)
                {
                    Throwable retained = primary == null ? cleanup : primary;
                    if (cleanup != retained) retained.addSuppressed(cleanup);
                    LinuxCargoInvocation retainedInvocation = invocation;
                    LinuxCgroup retainedGroup = group;
                    pending = true;
                    throw new Scheduler.PendingCleanup(retained, () ->
                    {
                        if (retainedInvocation != null && !retainedInvocation.isClosed()) retainedInvocation.close();
                        if (retainedInvocation != null && !retainedInvocation.isClosed())
                            throw new IOException("Cargo attempts remain owned; original queue reconciliation is required");
                        if (retainedGroup != null) retainedGroup.close();
                        try (var entries = Files.walk(directory))
                        {
                            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
                        }
                        scope.close();
                    });
                }
            }
        }
        finally
        {
            if (!pending) scope.close();
        }
    }

    /**
     * Rejects scratch paths whose longest owned socket would exceed Linux sockaddr_un.
     * The longest is the Cargo root's stream socket below the invocation directory, whose exclusive suffix the bound
     * takes at its full twenty digits; the wrapper controller socket is shorter.
     * @param scratch absolute caller-selected scratch parent
     * @throws IOException if UTF-8 pathname bytes cannot fit before the required terminating NUL
     */
    public static void requireSocketNamespace(Path scratch) throws IOException
    {
        String longest = scratch.resolve("c-" + "9".repeat(20)).resolve("s").resolve("streams.sock").toString();
        if (longest.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 108)
            throw new IOException("Linux scheduler scratch path is too long for authenticated wrapper sockets: " + scratch);
    }

    /**
     * Rejects a workload-mutable parent namespace before creating controller authority files.
     * @param scratch absolute normalized existing scratch directory
     * @throws IOException if an ancestor is a symlink, non-directory, non-root-owned or writable by another identity
     */
    static void requireTrustedScratch(Path scratch) throws IOException
    {
        for (Path path = scratch; path != null; path = path.getParent())
        {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS).equals(0) ||
                ((int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0022) != 0)
                throw new IOException("Linux scheduler scratch requires a root-controlled directory namespace: " + path);
        }
    }

    /**
     * Refuses an unsupported controller identity before any workload resource is acquired.
     * @throws IOException if proc credentials are unavailable or real/effective identities are not root
     */
    static void requireElevated() throws IOException
    {
        String ids = Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("Uid:")).findFirst().orElseThrow(() -> new IOException("Missing native UID identity"));
        String[] values = ids.substring(4).trim().split("\\s+");
        if (values.length != 4 || !values[0].equals("0") || !values[1].equals("0"))
            throw new IOException("Linux scheduling requires an explicitly elevated controller with real and effective UID zero");
    }
}
