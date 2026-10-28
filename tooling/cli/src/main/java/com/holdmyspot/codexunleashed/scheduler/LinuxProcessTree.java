package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Owns one Linux action tree in a dedicated supervisor JVM with no unrelated children.
 * The caller must provide elevated cgroup-management authority and native access.
 * Root observation does not imply descendant completion. Instances are single-thread confined.
 */
public final class LinuxProcessTree implements AutoCloseable {
    /**
     * Explicit launch context. Executable, Java runtime, class path, directory and parents must be absolute.
     * The workload's positive UID/GID are distinct from the privileged supervisor identity.
     * Command arguments and the complete workload environment are copied. No PATH search is performed.
     * @param cgroupParent absolute delegated cgroup-v2 parent
     * @param scratchParent absolute parent for exclusive gate state
     * @param javaExecutable absolute Java executable for the trusted gate
     * @param classPath absolute class directory or JAR containing this owner and its trusted gate
     * @param command workload command beginning with an absolute executable
     * @param environment complete workload environment, copied without ambient inheritance
     * @param directory absolute workload working directory
     * @param uid positive unprivileged workload user identity
     * @param gid positive unprivileged workload group identity
     */
    public record Launch(Path cgroupParent, Path scratchParent, Path javaExecutable, Path classPath,
                         List<String> command, Map<String,String> environment, Path directory, int uid, int gid) {
        /**
         * Copies launch collections and rejects relative paths, missing command or privileged workload identities.
         * @param cgroupParent absolute delegated cgroup-v2 parent
         * @param scratchParent absolute parent for exclusive gate state
         * @param javaExecutable absolute Java executable for the trusted gate
         * @param classPath absolute class directory or JAR containing this owner and its trusted gate
         * @param command workload command beginning with an absolute executable
         * @param environment complete workload environment, copied without ambient inheritance
         * @param directory absolute workload working directory
         * @param uid positive unprivileged workload user identity
         * @param gid positive unprivileged workload group identity
         * @throws NullPointerException if any required input or collection element is null
         * @throws IllegalArgumentException if a path or executable is relative, command is empty or identity is nonpositive
         */
        public Launch {
            for (Path path : List.of(cgroupParent, scratchParent, javaExecutable, classPath, directory)) {
                if (path.isAbsolute() == false) throw new IllegalArgumentException("Launch paths must be absolute: " + path);
            }
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            if (command.isEmpty() || Path.of(command.getFirst()).isAbsolute() == false)
                throw new IllegalArgumentException("The workload requires an absolute executable");
            if (uid <= 0 || gid <= 0) throw new IllegalArgumentException("Workload UID and GID must be positive");
        }
    }

    /**
     * Immutable workload inputs used by the trusted gate and supervisor.
     */
    private final Launch launch;
    /**
     * Linux libc access used only by this dedicated supervisor and its trusted gate.
     */
    private final NativeCalls nativeCalls;
    /**
     * Exclusive action cgroup, acquired during preparation and removed on successful close.
     */
    private Path group;
    /**
     * Exclusive gate configuration, readiness, release and workload-log directory.
     */
    private Path scratch;
    /**
     * Java-owned trusted gate, replaced in place by the workload executable after release.
     */
    private Process root;
    /**
     * Actual Java process exit status; null until the root has been waited.
     */
    private Integer rootExit;
    /**
     * Whether the one-time workload release file has been published.
     */
    private boolean released;
    /**
     * Whether termination was requested, independently of the root exit status.
     */
    private boolean terminationRequested;
    /**
     * Whether native waiting has established that no adopted child status remains.
     */
    private boolean adoptedDrained;
    /**
     * Whether all owned resource cleanup has succeeded.
     */
    private boolean closed;
    /**
     * Adopted child PID to ordinary exit code or negative terminating signal, collected by this supervisor.
     */
    private final Map<Long,Integer> adoptedExits = new LinkedHashMap<>();

    /**
     * Stores immutable launch context; acquisition and failed acquisition cleanup belong to prepare.
     * @param launch immutable workload and containment inputs
     * @throws NullPointerException if {@code launch} is null
     * @throws IOException if native binding initialization fails
     */
    private LinuxProcessTree(Launch launch) throws IOException {
        this.launch = Objects.requireNonNull(launch, "launch");
        nativeCalls = new NativeCalls();
    }

    /**
     * Creates an exclusive cgroup and a trusted Java helper; verifies placement before returning.
     * Workload execution remains gated until release and self-expires after 60 seconds without release.
     * On failure, owned work is terminated/reaped and acquired storage is removed; cleanup errors are suppressed.
     * @throws IOException if native authority, process creation, containment or readiness fails
     * @param launch immutable workload and containment inputs
     * @return the prepared owner with execution still gated
     */
    public static LinuxProcessTree prepare(Launch launch) throws IOException {
        LinuxProcessTree tree = new LinuxProcessTree(launch);
        try {
            if (tree.nativeCalls.uid() != 0) throw new IOException("This candidate requires an elevated standalone supervisor");
            tree.nativeCalls.setSubreaper();
            tree.scratch = Files.createTempDirectory(launch.scratchParent(), "java-owner-state-");
            tree.group = Files.createTempDirectory(launch.cgroupParent(), "java-owner-");
            tree.writeConfiguration();

            ProcessBuilder builder = new ProcessBuilder(launch.javaExecutable().toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", launch.classPath().toString(),
                LinuxProcessTree.class.getName(), "--gate", tree.scratch.toString());
            builder.directory(launch.directory().toFile());
            builder.environment().clear();
            builder.redirectErrorStream(true);
            builder.redirectOutput(tree.scratch.resolve("workload.log").toFile());
            tree.root = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (Files.exists(tree.scratch.resolve("ready")) == false) {
                if (tree.root.isAlive() == false) throw new IOException("Trusted gate exited before readiness: " + tree.root.waitFor());
                if (System.nanoTime() >= deadline) throw new IOException("Trusted gate did not become ready within 10 seconds");
                Thread.sleep(10);
            }

            Files.writeString(tree.group.resolve("cgroup.procs"), Long.toString(tree.root.pid()), StandardOpenOption.WRITE);
            if (tree.members().contains(tree.root.pid()) == false)
                throw new IOException("Trusted gate is absent from its owned cgroup: " + tree.group);
            return tree;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException("Interrupted during trusted-gate readiness", failure);
            tree.closeAfterFailure(interrupted);
            throw interrupted;
        } catch (IOException | RuntimeException | Error failure) {
            tree.closeAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * Stores the workload data for the trusted helper, using the JDK XML properties format.
     * @throws IOException if writing gate configuration fails
     */
    private void writeConfiguration() throws IOException {
        Properties values = new Properties();
        values.setProperty("uid", Integer.toString(launch.uid()));
        values.setProperty("gid", Integer.toString(launch.gid()));
        values.setProperty("command.count", Integer.toString(launch.command().size()));
        for (int index = 0; index < launch.command().size(); index++)
            values.setProperty("command." + index, launch.command().get(index));
        for (var entry : launch.environment().entrySet()) values.setProperty("environment." + entry.getKey(), entry.getValue());
        try (OutputStream output = Files.newOutputStream(scratch.resolve("launch.xml"))) {
            values.storeToXML(output, "Trusted helper workload inputs");
        }
    }

    /**
     * Releases exactly one contained helper; repeated release or use after close is rejected.
     * @throws IllegalStateException if already released or closed
     * @throws IOException if containment validation or release publication fails
     */
    public void release() throws IOException {
        requireOpen();
        if (released) throw new IllegalStateException("The workload gate is already released");
        if (members().contains(root.pid()) == false) throw new IOException("Gate lost owned cgroup membership before release");
        Files.writeString(scratch.resolve("release"), "release\n", StandardOpenOption.CREATE_NEW);
        released = true;
    }

    /**
     * Waits up to 10 seconds for the actual exec-replaced root and preserves its actual Java process exit status.
     * @return the actual root process exit status
     * @throws IllegalStateException if closed
     * @throws IOException if the wait times out or is interrupted
     */
    public int awaitRootExit() throws IOException {
        requireOpen();
        if (rootExit != null) return rootExit;
        try {
            if (root.waitFor(10, TimeUnit.SECONDS) == false) throw new IOException("Workload root did not exit within 10 seconds");
            rootExit = root.exitValue();
            return rootExit;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while observing workload root", failure);
        }
    }

    /**
     * Returns current kernel cgroup membership, retaining detached descendants independently of parent PID.
     * @return an immutable snapshot of kernel-reported group member PIDs
     * @throws IOException if the membership file cannot be read
     */
    public List<Long> members() throws IOException {
        List<Long> result = new ArrayList<>();
        for (String line : Files.readAllLines(group.resolve("cgroup.procs"))) result.add(Long.parseLong(line));
        return List.copyOf(result);
    }

    /**
     * Indicates whether the observed root has exited, no owned task remains, and adopted statuses have been collected.
     * @return true only after root waiting, empty population and adopted-status collection
     * @throws IllegalStateException if closed
     * @throws IOException if population cannot be read
     */
    public boolean isComplete() throws IOException {
        requireOpen();
        return rootExit != null && populated() == false && adoptedDrained;
    }

    /**
     * Observes natural completion without termination, collecting at most one adopted status.
     * Returns false while the root or descendants remain, and true only after Java root waiting,
     * native ECHILD and populated0. Call again to advance pending adopted-status collection.
     * No signal or cgroup.kill write occurs. Observation failures leave cleanup with the caller.
     * @return true once root and descendant completion have been established
     * @throws IllegalStateException if closed
     * @throws IOException if root observation, native waiting or population sampling fails
     */
    public boolean observeCompletion() throws IOException {
        requireOpen();
        if (isComplete()) return true;
        if (rootExit == null) {
            try {
                if (root.waitFor(0, TimeUnit.NANOSECONDS) == false) return false;
                rootExit = root.exitValue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while observing natural root completion", failure);
            }
        }
        if (nativeCalls.observeAdoptedCompletion(adoptedExits) == false) return false;
        if (populated()) return false;
        adoptedDrained = true;
        return true;
    }

    /**
     * Indicates whether public termination was requested; this remains distinct from a successful root exit.
     * @return whether explicit or cleanup termination was requested
     */
    public boolean terminationRequested() { return terminationRequested; }

    /**
     * Returns collected adopted statuses: negative signal numbers or ordinary nonnegative exit codes.
     * @return an immutable snapshot of adopted child status by PID
     */
    public Map<Long,Integer> adoptedExits() { return Map.copyOf(adoptedExits); }

    /**
     * Returns the owned cgroup path for direct identity and lifecycle observation.
     * @return the absolute owned cgroup path, retained after close
     */
    public Path group() { return group; }

    /**
     * Returns the helper/workload root PID, unchanged by the helper's native execve.
     * @return the trusted gate and workload root process identity
     */
    public long rootPid() { return root.pid(); }

    /**
     * Returns the owned helper scratch path; it is removed by close.
     * @return the absolute owned gate-state directory, retained as a location after close
     */
    public Path scratch() { return scratch; }

    /**
     * Terminates only this cgroup and collects root/adopted statuses; termination remains a distinct outcome.
     * @throws IllegalStateException if closed
     * @throws IOException if termination or child-status collection fails
     */
    public void terminate() throws IOException {
        requireOpen();
        terminationRequested = true;
        stopAndReap();
    }

    /**
     * Reads the authoritative populated field without treating an absent field as an empty group.
     * @return whether the kernel reports live processes in the action cgroup subtree
     * @throws IOException if events cannot be decoded or population is absent or invalid
     */
    private boolean populated() throws IOException {
        Properties events = new Properties();
        try (var input = Files.newBufferedReader(group.resolve("cgroup.events"))) {
            events.load(input);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Owned cgroup events cannot be decoded: " + group, failure);
        }
        return switch (events.getProperty("populated")) {
            case "0" -> false;
            case "1" -> true;
            case null -> throw new IOException("Owned cgroup population field is missing: " + group);
            default -> throw new IOException("Owned cgroup has no valid populated field: " + group);
        };
    }

    /**
     * Kills group members, waits the Java-owned root first, then drains adopted statuses in this standalone JVM.
     * @throws IOException if termination, waiting or population sampling fails
     */
    private void stopAndReap() throws IOException {
        if (group != null) Files.writeString(group.resolve("cgroup.kill"), "1\n", StandardOpenOption.WRITE);
        if (root != null) {
            // A gate may fail before placement; its direct owner still has to stop and reap it.
            if (root.isAlive()) root.destroyForcibly();
            awaitRootExit();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (group != null && populated()) {
                if (System.nanoTime() >= deadline) throw new IOException("Owned cgroup remains populated after termination");
                Thread.sleep(10);
            }
            nativeCalls.drainAdopted(adoptedExits, deadline);
            adoptedDrained = true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during owned descendant cleanup", failure);
        }
    }

    /**
     * Attaches cleanup failures without replacing the original acquisition failure.
     * @param failure the original acquisition failure to retain
     */
    private void closeAfterFailure(Throwable failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    /**
     * Rejects lifecycle operations after resources were closed.
     * @throws IllegalStateException if successful close already occurred
     */
    private void requireOpen() {
        if (closed) throw new IllegalStateException("Owned process tree is closed");
    }

    /**
     * Stops/reaps acquired work and removes only the exclusive group and scratch; cleanup failures remain visible.
     * @throws IOException if any termination, waiting or resource-removal operation fails
     */
    @Override public void close() throws IOException {
        if (closed) return;
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        try {
            try {
                // Recorded completion requires root waiting, populated0 and ECHILD before cleanup.
                if (rootExit == null || adoptedDrained == false) {
                    terminationRequested = true;
                    stopAndReap();
                }
            } catch (IOException cleanup) { failure = cleanup; }
            if (group != null) {
                try { Files.delete(group); } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (root != null) {
                try { root.getOutputStream().close(); } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (scratch != null) {
                try (var entries = Files.walk(scratch)) {
                    for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(entry);
                } catch (IOException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw failure;
            closed = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs only the trusted helper. Configuration is data; arbitrary workload execution follows release and identity drop.
     * @param args the gate selector and absolute owned scratch directory
     * @throws Exception if configuration, release, credential drop or native execution fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || args[0].equals("--gate") == false)
            throw new IllegalArgumentException("Expected --gate and the owned scratch directory");
        Path scratch = Path.of(args[1]);
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(scratch.resolve("launch.xml"))) { values.loadFromXML(input); }
        Files.writeString(scratch.resolve("ready"), Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (Files.exists(scratch.resolve("release")) == false) {
            if (System.nanoTime() >= deadline) throw new IOException("Unreleased trusted gate self-expired after 60 seconds");
            Thread.sleep(10);
        }
        List<String> command = new ArrayList<>();
        int count = Integer.parseInt(values.getProperty("command.count"));
        for (int index = 0; index < count; index++) command.add(values.getProperty("command." + index));
        Map<String,String> environment = new LinkedHashMap<>();
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith("environment.")) environment.put(key.substring("environment.".length()), values.getProperty(key));
        }
        NativeCalls calls = new NativeCalls();
        calls.dropIdentity(Integer.parseInt(values.getProperty("uid")), Integer.parseInt(values.getProperty("gid")));
        calls.exec(command, environment);
    }

    /**
     * Linux libc boundary; native errno is captured per invocation, without JNI or Java process-reaper competition.
     */
    private static final class NativeCalls {
        /**
         * JDK Linux-native ABI linker used to resolve and call libc operations.
         */
        private final Linker linker = Linker.nativeLinker();
        /**
         * Byte offset of errno in the JDK per-invocation captured-call-state layout.
         */
        private final long errnoOffset = Linker.Option.captureStateLayout().byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
        /**
         * Linux prctl binding used to enable child-subreaper adoption before launching work.
         */
        private final MethodHandle prctl = bind("prctl", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG), true);
        /**
         * Linux waitpid binding used only after Java has collected its direct root status.
         */
        private final MethodHandle waitpid = bind("waitpid", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT), false);
        /**
         * Linux getuid binding for the calling process real user identity.
         */
        private final MethodHandle getuid = bind("getuid", FunctionDescriptor.of(ValueLayout.JAVA_INT), false);
        /**
         * Linux getgid binding for verifying the workload real group identity.
         */
        private final MethodHandle getgid = bind("getgid", FunctionDescriptor.of(ValueLayout.JAVA_INT), false);
        /**
         * Linux setgroups binding for removing privileged supplementary memberships.
         */
        private final MethodHandle setgroups = bind("setgroups", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS), false);
        /**
         * Linux setgid binding for dropping the trusted gate to the workload group.
         */
        private final MethodHandle setgid = bind("setgid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
        /**
         * Linux setuid binding for dropping the trusted gate to the workload user.
         */
        private final MethodHandle setuid = bind("setuid", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), false);
        /**
         * Linux execve binding that replaces the trusted gate without changing its PID.
         */
        private final MethodHandle execve = bind("execve", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), false);

        /**
         * Binds a libc symbol with captured errno; prctl's trailing arguments are C varargs.
         * @param name the libc symbol to resolve
         * @param descriptor the Linux-native function signature
         * @param variadic whether trailing arguments use C variable-argument ABI rules
         * @return the downcall handle with per-invocation errno capture
         * @throws IllegalStateException if the required symbol is missing
         */
        private MethodHandle bind(String name, FunctionDescriptor descriptor, boolean variadic) {
            MemorySegment symbol = linker.defaultLookup().find(name).orElseThrow(() -> new IllegalStateException("Missing Linux libc symbol: " + name));
            if (variadic) return linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"), Linker.Option.firstVariadicArg(1));
            return linker.downcallHandle(symbol, descriptor, Linker.Option.captureCallState("errno"));
        }

        /**
         * Returns an operation result and captured errno; unexpected binding failures are reported with their cause.
         * @param operation the errno-capturing libc downcall
         * @param arguments the operation arguments after its captured-state pointer
         * @return the native integer result followed by captured errno
         * @throws IOException if downcall invocation fails
         */
        private int[] call(MethodHandle operation, Object... arguments) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment captured = arena.allocate(Linker.Option.captureStateLayout());
                List<Object> values = new ArrayList<>();
                values.add(captured);
                values.addAll(List.of(arguments));
                int result = (Integer) operation.invokeWithArguments(values);
                return new int[] { result, captured.get(ValueLayout.JAVA_INT, errnoOffset) };
            } catch (Throwable failure) {
                throw new IOException("Linux native invocation failed", failure);
            }
        }

        /**
         * Returns the calling process's real UID.
         * @return the real user identity of the calling process
         * @throws IOException if the libc call fails
         */
        private int uid() throws IOException { return call(getuid)[0]; }

        /**
         * Enables child adoption before any owned root is started.
         * @throws IOException if Linux rejects child-subreaper activation
         */
        private void setSubreaper() throws IOException {
            requireSuccess("prctl(PR_SET_CHILD_SUBREAPER)", call(prctl, 36, 1L, 0L, 0L, 0L));
        }

        /**
         * Drops all supplementary groups before GID/UID, then verifies the resulting real identities.
         * @param uid the positive unprivileged workload user identity
         * @param gid the positive unprivileged workload group identity
         * @throws IOException if dropping or verifying credentials fails
         */
        private void dropIdentity(int uid, int gid) throws IOException {
            requireSuccess("setgroups", call(setgroups, 0L, MemorySegment.NULL));
            requireSuccess("setgid", call(setgid, gid));
            requireSuccess("setuid", call(setuid, uid));
            if (uid() != uid || call(getgid)[0] != gid) throw new IOException("Workload credential drop did not establish requested identity");
        }

        /**
         * Replaces the gated JVM with the workload, preserving its PID and original-process wait ownership.
         * @param command the workload command beginning with an absolute executable
         * @param environment the complete workload environment
         * @throws IOException if native process replacement fails or unexpectedly returns
         */
        private void exec(List<String> command, Map<String,String> environment) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment arguments = strings(arena, command);
                List<String> variables = environment.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
                MemorySegment env = strings(arena, variables);
                int[] result = call(execve, arena.allocateFrom(command.getFirst()), arguments, env);
                requireSuccess("execve(" + command.getFirst() + ")", result);
                throw new IOException("execve unexpectedly returned success");
            }
        }

        /**
         * Allocates a null-terminated C pointer vector and UTF-8 strings in the caller-owned arena.
         * @param arena the caller-owned storage lifetime
         * @param values the ordered UTF-8 strings
         * @return a native null-terminated pointer vector
         */
        private MemorySegment strings(Arena arena, List<String> values) {
            MemorySegment vector = arena.allocate(ValueLayout.ADDRESS, values.size() + 1L);
            for (int index = 0; index < values.size(); index++) vector.setAtIndex(ValueLayout.ADDRESS, index, arena.allocateFrom(values.get(index)));
            vector.setAtIndex(ValueLayout.ADDRESS, values.size(), MemorySegment.NULL);
            return vector;
        }

        /**
         * Collects at most one ready adopted status; true means native ECHILD, not merely no ready status.
         * @param exits the adopted-status collection updated for one ready child
         * @return true on ECHILD; false for a collected status, running child or interrupted native wait
         * @throws IOException if native waiting fails
         */
        private boolean observeAdoptedCompletion(Map<Long,Integer> exits) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
                int[] result = call(waitpid, -1, status, 1);
                if (result[0] > 0) {
                    recordAdoptedExit(exits, result[0], status.get(ValueLayout.JAVA_INT, 0));
                    return false;
                }
                if (result[0] == -1 && result[1] == 10) return true;
                if (result[0] == -1 && result[1] != 4) requireSuccess("waitpid", result);
                return false;
            }
        }

        /**
         * Preserves actual ordinary exit or negative signal status for an adopted child.
         * @param exits the adopted-status collection to update
         * @param pid the child identity returned by Linux waitpid
         * @param raw the raw Linux wait status
         */
        private void recordAdoptedExit(Map<Long,Integer> exits, int pid, int raw) {
            int signal = raw & 127;
            exits.put((long) pid, signal == 0 ? raw >> 8 : -signal);
        }

        /**
         * Drains only after Java's direct root was waited; ECHILD is the authoritative end of adopted status collection.
         * @param exits the adopted-status collection to update
         * @param deadline the absolute System.nanoTime deadline in nanoseconds
         * @throws IOException if waiting fails or the deadline expires
         * @throws InterruptedException if cleanup polling is interrupted
         */
        private void drainAdopted(Map<Long,Integer> exits, long deadline) throws IOException, InterruptedException {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
                while (true) {
                    int[] result = call(waitpid, -1, status, 1);
                    if (result[0] > 0) {
                        recordAdoptedExit(exits, result[0], status.get(ValueLayout.JAVA_INT, 0));
                        continue;
                    }
                    if (result[0] == -1 && result[1] == 10) return;
                    if (result[0] == -1 && result[1] != 4) requireSuccess("waitpid", result);
                    if (System.nanoTime() >= deadline) throw new IOException("Adopted children remained unreaped after termination deadline");
                    Thread.sleep(10);
                }
            }
        }

        /**
         * Converts a failing libc result into a precise operation/errno diagnostic.
         * @param operation the libc operation name for diagnostics
         * @param result the integer return value and captured errno
         * @throws IOException if the return value indicates native failure
         */
        private void requireSuccess(String operation, int[] result) throws IOException {
            if (result[0] == -1) throw new IOException(operation + " failed with errno " + result[1]);
        }
    }
}
