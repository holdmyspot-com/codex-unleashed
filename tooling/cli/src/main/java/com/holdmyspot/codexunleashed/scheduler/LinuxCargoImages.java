package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Retains Linux Cargo producer image sessions before caller-controlled root release.
 * The caller owns process termination, the active manager and an authentic whole-workload barrier.
 * This owner validates held image lineage, credentials, executable and containment and waits for an
 * enrollment callback before release. It does not create actions, transfer streams, obtain wait status
 * or authorize retry. Callers deliver each native held event exactly once on the constructing thread.
 * Every retired or partial generation stays owned until the supplied barrier establishes execution impossibility.
 */
public final class LinuxCargoImages
{
    /**
     * Constructing thread required before every operation.
     */
    private final Thread thread = Thread.currentThread();
    /**
     * Platform-independent connection readiness and retained cleanup generations.
     */
    private final HeldImageConnections connections;
    /**
     * Native operations or independently controlled test observations.
     */
    private final Operations operations;
    /**
     * Required workload credentials and pinned Cargo image.
     */
    private final Policy policy;
    /**
     * Caller-owned readiness consumer; retained through the same workload barrier.
     */
    private final Enrollment enrollment;
    /** Positive unique generation across dynamic connections and native image holds. */
    private long nextGeneration;
    /**
     * Root birth retained by the caller's genuine process owner.
     */
    private Identity root;
    /**
     * Latest authenticated image for each retained process birth.
     */
    private final Map<Long, Identity> current = new LinkedHashMap<>();
    /**
     * Prevents callback reentry during attachment, enrollment and release.
     */
    private boolean advancing;
    /**
     * Whether root acquisition has consumed its single opportunity.
     */
    private boolean attempted;
    /**
     * Whether the root became ready, through a Frida connection or the native tracer.
     */
    private boolean armed;
    /**
     * Whether controls have been withdrawn.
     */
    private boolean stopped;
    /**
     * Whether all owned connections closed successfully.
     */
    private boolean closed;
    /**
     * First exact operation failure, retained without replay.
     */
    private Throwable failure;

    /**
     * Creates a resource-free owner borrowing an active manager and authenticated held root.
     * @param scope scoped serializer services retained through cleanup
     * @param manager active native manager retained through cleanup
     * @param root caller-authenticated prepared root identity before credential drop
     * @param containment owned retained cgroup containing the root and every supported image
     * @param supervisorPid original live native supervisor retained by the caller
     * @param supervisorBirth original supervisor start ticks authenticated by its process owner
     * @param policy required workload credentials and Cargo executable
     * @param enrollment callback returning only after its image consumer is ready
     * @param timeout positive readiness duration
     * @throws IOException if the original supervisor identity cannot be pinned
     */
    public LinuxCargoImages(ToolingScope scope, FridaRuntime.Manager manager, Identity root, Path containment,
        Policy policy, Enrollment enrollment, Duration timeout, long supervisorPid, long supervisorBirth) throws IOException
    {
        this(scope, new NativeOperations(scope, manager, containment, timeout, supervisorPid, supervisorBirth), root, policy, enrollment, timeout, System::nanoTime);
        if (root.parent() != supervisorPid) throw new IOException("Root differs from original supervisor ancestry");
    }

    /**
     * Supplies controlled observations without changing ownership or readiness policy.
     * @param scope scoped serializer services
     * @param operations native observation and retained connection boundary
     * @param root authenticated prepared root identity
     * @param policy required credentials and pinned Cargo executable
     * @param enrollment consumer readiness acknowledgment; return never authorizes native action adoption
     * @param timeout positive representable readiness duration
     * @param clock monotonic readiness observations
     */
    public LinuxCargoImages(ToolingScope scope, Operations operations, Identity root, Policy policy,
        Enrollment enrollment, Duration timeout, LongSupplier clock)
    {
        connections = new HeldImageConnections(scope, timeout, clock);
        this.operations = Objects.requireNonNull(operations, "operations");
        this.root = Objects.requireNonNull(root, "root");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.enrollment = Objects.requireNonNull(enrollment, "enrollment");
    }

    /**
     * Arms the caller-held root without releasing its external native gate.
     * @throws IOException if root identity, attachment or readiness cannot be established
     */
    public void start() throws IOException
    {
        requireThread();
        if (attempted || stopped) throw new IllegalStateException("Root arming is permitted once");
        attempted = true;
        advancing = true;
        try
        {
            Identity observed = operations.inspect(root.pid());
            if (!root.sameBirth(observed) || root.uid() != observed.uid() || root.gid() != observed.gid() ||
                !root.executable().equals(observed.executable()) || !observed.group().startsWith(root.group()))
                throw new IOException("Prepared root identity differs from its owned workload subgroup");
            root = observed;
            install(root, false);
            armed = true;
        }
        catch (IOException | RuntimeException | Error cause) { fail(cause); throw cause; }
        finally { advancing = false; }
    }

    /**
     * Authenticates and arms one copied held child event before its single explicit resume.
     * @param child copied native event, never itself an ownership credential
     * @throws IOException if lineage, readiness or resumption is uncertain
     */
    public void advance(FridaRuntime.Child child) throws IOException
    {
        requireThread();
        if (!armed || stopped || advancing) throw new IllegalStateException("Image owner is not active");
        Objects.requireNonNull(child, "child");
        advancing = true;
        try
        {
            if (child.origin() < 0 || child.origin() > 2) throw new IOException("Unknown native child origin");
            Identity observed = operations.inspect(child.pid());
            Lineage lineage = retainedParent(child, observed, current, operations);
            Identity parent = lineage.source();
            if (!observed.group().startsWith(root.group())) throw new IOException("Held image left retained root containment");
            if (observed.uid() != policy.uid() || observed.gid() != policy.gid())
                throw new IOException("Held image credentials differ from workload identity");
            if (child.origin() != 0 && operations.matchesExecutable(observed, policy.wrapper()))
            {
                resumeWrapper(child, observed, lineage);
                return;
            }
            Path executable = executable(child, parent == null ? null : parent.executable());
            ExecutableIdentity source = operations.executableIdentity(observed, executable);
            install(observed, true);
            if (!source.equals(operations.executableIdentity(observed, executable)))
                throw new IOException("Held executable source changed during image readiness");
            lineage.verify(observed, operations);
            if (!observed.equals(operations.inspect(child.pid())))
                throw new IOException("Native image changed before exact held resume");
            if (Thread.currentThread().isInterrupted()) throw new IOException("Held resume interrupted");
            operations.resume(child.pid());
        }
        catch (IOException | RuntimeException | Error cause) { fail(cause); throw cause; }
        finally { advancing = false; }
    }

    /**
     * Resumes a held start of the pinned native Cargo wrapper without attaching to it or enrolling it.
     * <p>
     * The wrapper allocates almost nothing itself and runs each compiler attempt in a forked child that the attempt's
     * own owner attaches to after moving it into the attempt's cgroup. Attaching here as well would give that child
     * two Frida agents and two exec gates. For the same reason the wrapper is released untraced: a compiler attempt
     * forked by a traced wrapper would already have a tracer when its owner attaches to it. The wrapper is removed from the retained images, which a forked Cargo image
     * that executes it was part of, so a later event that names it as a parent is rejected.
     * @param child copied held start event
     * @param observed independently inspected wrapper image with workload credentials inside root containment
     * @param lineage retained source lineage of the start
     * @throws IOException if lineage or the wrapper image changes before resumption, or resumption is uncertain
     */
    private void resumeWrapper(FridaRuntime.Child child, Identity observed, Lineage lineage) throws IOException
    {
        lineage.verify(observed, operations);
        if (!observed.equals(operations.inspect(child.pid())))
            throw new IOException("Native wrapper image changed before held resume");
        if (Thread.currentThread().isInterrupted()) throw new IOException("Held resume interrupted");
        current.remove(child.pid());
        operations.releaseWrapper(child.pid());
    }

    /** Authenticates live ancestry or the kernel's adoption of a held descendant by its original supervisor.
     * @param child copied notification with origin-specific parent shape
     * @param observed independently verified current image
     * @param retained current image identities owned by this controller
     * @param operations independent native observation and adoption authority
     * @return retained source lineage and its required final verification
     * @throws IOException if retained ancestry or adoption differs
     */
    private static Lineage retainedParent(FridaRuntime.Child child, Identity observed, Map<Long, Identity> retained,
        Operations operations) throws IOException
    {
        Identity previous = retained.get(child.pid());
        Identity parent;
        if (child.origin() == 1)
        {
            if (child.parentPid() != child.pid()) throw new IOException("Exec notification is not self-parented");
            if (previous != null)
            {
                if (previous.pid() != observed.pid() || previous.birth() != observed.birth())
                    throw new IOException("Exec replaced its retained original process birth");
                if (previous.parent() == observed.parent()) return new Lineage(null, null);
                parent = retained.get(previous.parent());
                if (parent == null) throw new IOException("Exec adoption lacks its retained original parent");
                var lineage = new Lineage(null, parent);
                lineage.verify(observed, operations);
                return lineage;
            }
            parent = retained.get(observed.parent());
        }
        else
        {
            parent = retained.get(child.parentPid());
            if (previous != null) throw new IOException("Child replaced an already retained birth");
        }
        if (parent == null) throw new IOException("Child lacks a retained original parent birth");
        var lineage = new Lineage(parent, observed.parent() == parent.pid() ? null : parent);
        lineage.verify(observed, operations);
        return lineage;
    }

    /** Keeps the original executable source distinct from a kernel-authenticated adoptive parent.
     * @param source original source parent, or null for an exec
     * @param retired original retired parent requiring supervisor adoption, or null for live ancestry
     */
    private record Lineage(Identity source, Identity retired)
    {
        /** Rechecks ancestry after readiness without treating a notification as admission authority.
         * @param child independently inspected held child
         * @param operations native observation authority
         * @throws IOException if live ancestry or original supervisor adoption no longer holds
         */
        private void verify(Identity child, Operations operations) throws IOException
        {
            if (retired != null) operations.requireAdoption(retired, child);
            else if (source != null && !source.equals(operations.inspect(source.pid())))
                throw new IOException("Parent birth changed before child release");
        }
    }

    /**
     * Authenticates current ancestry while retaining strict known-PID incarnation checks.
     * @param child copied notification with origin-specific parent shape
     * @param observed independently verified current image
     * @param retained current image identities owned by this controller
     * @param inspector independent current identity reader
     * @return retained live actual parent for a new image, or null for a verified known-PID exec
     * @throws IOException if the notification or retained current ancestry differs
     */
    static Identity retainedParent(FridaRuntime.Child child, Identity observed, Map<Long, Identity> retained,
        Inspector inspector) throws IOException
    {
        Identity previous = retained.get(child.pid());
        Identity parent = null;
        if (child.origin() == 1)
        {
            if (child.parentPid() != child.pid()) throw new IOException("Exec notification is not self-parented");
            if (previous != null)
            {
                if (!previous.sameBirth(observed)) throw new IOException("Exec replaced its retained original process birth");
            }
            else parent = retained.get(observed.parent());
        }
        else
        {
            parent = retained.get(child.parentPid());
            if (previous != null || parent == null || observed.parent() != parent.pid())
                throw new IOException("Child lacks a retained live parent birth");
        }
        if (previous == null && (parent == null || !parent.equals(inspector.inspect(parent.pid()))))
            throw new IOException("Child lacks a retained live parent birth");
        return parent;
    }

    /** Independent current image observation, without action or continuation authority. */
    @FunctionalInterface
    interface Inspector
    {
        /** Reads a complete current native identity.
         * @param pid current process identity
         * @return independently verified current image
         * @throws IOException if identity cannot be established
         */
        Identity inspect(long pid) throws IOException;
    }

    /** Rejects live or recycled original-parent PIDs when proving kernel adoption.
     * @param expectedBirth independently retained original parent birth
     * @param stat current native stat record, or null only after native file absence
     * @throws IOException if the original process has not retired or the PID was recycled
     */
    public static void requireRetiredParent(long expectedBirth, String stat) throws IOException
    {
        if (stat == null) return;
        try
        {
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            if (Long.parseLong(fields[19]) != expectedBirth ||
                !(fields[0].equals("Z") || fields[0].equals("X")))
                throw new IOException("Original parent is live or its PID was recycled");
        }
        catch (IndexOutOfBoundsException | NumberFormatException cause)
        { throw new IOException("Original parent retirement is unavailable", cause); }
    }

    /**
     * Selects the original origin-specific executable, retaining relative spelling for independent native resolution.
     * @param child copied native event
     * @param parentExecutable retained actual fork-parent executable, or null for exec/spawn
     * @return original requested executable requiring independent native file comparison
     * @throws IOException if a supported origin lacks its required executable
     */
    static Path executable(FridaRuntime.Child child, Path parentExecutable) throws IOException
    {
        if (child.origin() == 0)
        {
            if (parentExecutable == null) throw new IOException("Fork image lacks its retained executable");
            return parentExecutable;
        }
        if (child.path() == null) throw new IOException("Held executable path is absent");
        try
        {
            Path path = Path.of(child.path());
            if (child.path().isEmpty()) throw new IOException("Held executable path is empty");
            return path;
        }
        catch (java.nio.file.InvalidPathException cause) { throw new IOException("Held executable path is malformed", cause); }
    }

    /** Keeps requested source, actual executable and kernel arguments distinct.
     * @param requested original requested path
     * @param actual independently observed executable path
     * @param arguments exact interpreter arguments, empty for a direct executable
     * @param source immutable source and interpreter observations
     */
    public record ExecutableIdentity(Path requested, Path actual, List<String> arguments, Map<String, Object> source)
    {
        /** Copies observations so readiness cannot mutate the retained identity.
         * @param requested original source path
         * @param actual independently observed executable
         * @param arguments exact kernel arguments
         * @param source source identity observations
         */
        public ExecutableIdentity
        {
            arguments = List.copyOf(arguments);
            source = Map.copyOf(source);
        }
    }

    /** Resolves the original executable through an independently authenticated held process view.
     * Retains kernel arguments, cwd identity, source bytes and interpreter identity for readiness revalidation.
     * @param image independently authenticated held process
     * @param expected original requested executable spelling
     * @param inspector independent current process identity reader
     * @return immutable complete source identity
     * @throws IOException if arguments, source, cwd or original process identity changes
     */
    public static ExecutableIdentity executableIdentity(Identity image, Path expected, Inspector inspector) throws IOException
    {

        Path proc = Path.of("/proc", Long.toString(image.pid()));
        byte[] command = Files.readAllBytes(proc.resolve("cmdline"));
        if (command.length == 0 || command[command.length - 1] != 0)
            throw new IOException("Held image command line is incomplete");
        String text = utf8(command);
        List<String> arguments = List.of(text.substring(0, text.length() - 1).split(String.valueOf((char) 0), -1));
        if (!image.equals(inspector.inspect(image.pid()))) throw new IOException("Held source birth changed before resolution");
        Map<String, Object> directoryIdentity = Files.readAttributes(proc.resolve("cwd"), "unix:dev,ino");
        Path directory = Files.readSymbolicLink(proc.resolve("cwd"));
        if (!directory.isAbsolute()) throw new IOException("Held working directory is not absolute");
        Path resolved = expected.isAbsolute() ? expected : directory.resolve(expected);
        ExecutableIdentity verified = LinuxCargoImages.executableIdentity(proc.resolve("exe"), resolved,
            expected.isAbsolute() ? arguments : NativeOperations.relativeInterpreterArguments(arguments, expected, resolved));
        if (!directoryIdentity.equals(Files.readAttributes(proc.resolve("cwd"), "unix:dev,ino")) ||
            !directory.equals(Files.readSymbolicLink(proc.resolve("cwd"))) ||
            !java.util.Arrays.equals(command, Files.readAllBytes(proc.resolve("cmdline"))) ||
            !image.equals(inspector.inspect(image.pid())))
            throw new IOException("Held working directory, arguments or birth changed during source resolution");
        var source = new java.util.LinkedHashMap<>(verified.source());
        source.put("workingDirectoryIdentity", directoryIdentity);
        source.put("workingDirectory", directory.toString());
        return new ExecutableIdentity(expected, verified.actual(), arguments, source);
    }

    /** Resolves direct binary or kernel shebang execution without granting action authority.
     * @param actual native executable inode, including a proc executable link
     * @param requested absolute original requested path
     * @param arguments actual native argv including argv zero
     * @return immutable source and actual image observations
     * @throws IOException if source, interpreter, argv or source stability cannot be verified
     */
    public static ExecutableIdentity executableIdentity(Path actual, Path requested, List<String> arguments) throws IOException
    {
        if (!requested.isAbsolute()) throw new IOException("Absolute requested executable required");
        Map<String, Object> before = Files.readAttributes(requested, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid");
        Path image = actual.toRealPath();
        if (Files.isSameFile(actual, requested))
        {
            if (!before.equals(Files.readAttributes(requested, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid")))
                throw new IOException("Direct executable source changed");
            return new ExecutableIdentity(requested, image, List.of(), before);
        }
        byte[] header;
        try (var input = Files.newInputStream(requested)) { header = input.readNBytes(256); }
        List<String> prefix = directInterpreter(header);
        Path interpreter = Path.of(prefix.getFirst());
        if (!Files.isSameFile(actual, interpreter)) throw new IOException("Requested script differs from native interpreter inode");
        int script = prefix.size();
        if (arguments.size() <= script || !arguments.subList(0, script).equals(prefix) ||
            !arguments.get(script).equals(requested.toString()))
            throw new IOException("Requested script differs from kernel interpreter arguments");
        if (!before.equals(Files.readAttributes(requested, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid")))
            throw new IOException("Requested script source changed");
        Map<String, Object> source = new java.util.LinkedHashMap<>(before);
        source.put("header", java.util.HexFormat.of().formatHex(header));
        source.put("interpreter", Files.readAttributes(interpreter, "unix:dev,ino,size,lastModifiedTime,ctime,mode,uid,gid"));
        return new ExecutableIdentity(requested, image, arguments, source);
    }

    /** Recognizes the Linux kernel's bounded direct shebang and single optional argument.
     * @param header at most 256 bytes from the requested file
     * @return exact kernel-inserted interpreter argument prefix
     * @throws IOException if the direct shebang is absent, malformed or truncated
     */
    public static List<String> directInterpreter(byte[] header) throws IOException
    {
        if (header.length < 3 || header.length > 256 || header[0] != '#' || header[1] != '!')
            throw new IOException("Requested source is not a direct kernel script");
        int end = 2;
        while (end < header.length && header[end] != '\n' && header[end] != 0) end++;
        if (end == 256)
        {
            int start = 2;
            while (start < end && (header[start] == ' ' || header[start] == '\t')) start++;
            int separator = start;
            while (separator < end && header[separator] != ' ' && header[separator] != '\t') separator++;
            if (separator == end) throw new IOException("Direct interpreter path is truncated");
            end--;
        }
        while (end > 2 && (header[end - 1] == ' ' || header[end - 1] == '\t')) end--;
        int start = 2;
        while (start < end && (header[start] == ' ' || header[start] == '\t')) start++;
        int separator = start;
        while (separator < end && header[separator] != ' ' && header[separator] != '\t') separator++;
        if (separator == start) throw new IOException("Direct interpreter path is missing");
        String interpreter = utf8(java.util.Arrays.copyOfRange(header, start, separator));
        if (!Path.of(interpreter).isAbsolute()) throw new IOException("Absolute direct interpreter required");
        start = separator;
        while (start < end && (header[start] == ' ' || header[start] == '\t')) start++;
        return start == end ? List.of(interpreter) :
            List.of(interpreter, utf8(java.util.Arrays.copyOfRange(header, start, end)));
    }

    /** Decodes native path and argument bytes without merging malformed encodings.
     * @param bytes complete UTF8 bytes
     * @return exact decoded text
     * @throws IOException if native bytes do not round-trip
     */
    static String utf8(byte[] bytes) throws IOException
    {
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new IOException("Native executable path or argv is not valid UTF8");
        return value;
    }

    /**
     * Selects a current containment member for dispatch; this observation alone never enrolls or resumes it.
     * @param pid candidate native process
     * @return whether its current membership selects this image controller
     * @throws IOException if membership cannot be observed
     */
    boolean owns(long pid) throws IOException
    {
        requireThread();
        return operations.contains(pid, root.group());
    }

    /**
     * Retains a connection before starting it and checks readiness before final birth verification.
     * @param expected independently authenticated image lineage
     * @param enroll whether an actual held workload image requires consumer readiness
     * @throws IOException if acquisition, readiness or identity changes
     */
    private void install(Identity expected, boolean enroll) throws IOException
    {
        if (!expected.equals(operations.inspect(expected.pid()))) throw new IOException("Held image identity changed");
        long generation;
        try
        {
            if (!operations.prepareNative(expected))
                connections.start(operations.connection(expected.pid()), "linux-cargo-image-ready:" + expected.pid());
            generation = Math.incrementExact(nextGeneration);
            nextGeneration = generation;
        }
        catch (IOException cause)
        {
            throw new IOException("Held image connection failed: pid=" + expected.pid() + ",birth=" + expected.birth() +
                ",executable=" + expected.executable(), cause);
        }
        if (!expected.equals(operations.inspect(expected.pid()))) throw new IOException("Image changed before enrollment");
        if (enroll) enrollment.ready(expected, generation);
        if (Thread.currentThread().isInterrupted()) throw new IOException("Image enrollment interrupted");
        if (!expected.equals(operations.inspect(expected.pid()))) throw new IOException("Image changed before release");
        current.put(expected.pid(), expected);
    }

    /**
     * Withdraws controls and closes independent images only after a trusted whole-workload barrier.
     * Each connection is attempted once: a failed script deliberately retains its dependent session.
     * A connection failure never prevents cleanup of another independent image connection.
     * @param barrier caller-owned kernel-backed whole-workload execution observation
     * @throws IOException if the barrier is unknown or any cleanup remains incomplete
     */
    public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireThread();
        Objects.requireNonNull(barrier, "barrier");
        if (advancing) throw new IllegalStateException("Image callback cannot close its owner");
        if (closed) return;
        stopped = true;
        advancing = true;
        try
        {
            if (!barrier.reached()) throw new IOException("Native image cleanup requires authentic whole-workload zero");
            Throwable closing = null;
            try { connections.close(barrier); }
            catch (IOException | RuntimeException | Error cause) { closing = cause; }
            try { operations.closeNative(); }
            catch (IOException | RuntimeException | Error cause)
            {
                if (closing == null) closing = cause;
                else HeldImageConnections.suppress(closing, cause);
            }
            if (closing instanceof IOException checked) throw checked;
            if (closing instanceof RuntimeException unchecked) throw unchecked;
            if (closing instanceof Error fatal) throw fatal;
        }
        catch (IOException | RuntimeException | Error cause)
        {
            if (failure != null) HeldImageConnections.suppress(failure, cause);
            throw cause;
        }
        finally { advancing = false; }
        closed = connections.isClosed();
    }

    /**
     * Reports successful closure without retrying native effects.
     * @return whether every retained connection closed
     */
    boolean isClosed() { requireThread(); return closed; }

    /** Copies one genuine native fork or exec stop for independent image admission.
     * @return native held transition, or null when none is pending
     * @throws IOException if native tracing or a producer allocation fails
     */
    FridaRuntime.Child pollNativeChild() throws IOException
    {
        requireThread();
        return operations.pollNativeChild();
    }

    /**
     * Preserves the first exact operation failure for subsequent cleanup diagnostics.
     * @param cause first uncertain operation
     */
    private void fail(Throwable cause) { stopped = true; if (failure == null) failure = cause; }

    /**
     * Requires the constructing thread before observing or changing ownership.
     */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("LinuxCargoImages belongs to its constructing thread");
    }

    /**
     * Carries independently observed native image identity; its construction establishes no authority.
     * @param pid positive process identifier
     * @param birth positive Linux start ticks
     * @param parent positive actual parent PID
     * @param uid established real/effective/saved/filesystem UID
     * @param gid established real/effective/saved/filesystem GID
     * @param executable absolute native executable path
     * @param group absolute actual owned cgroup membership
     */
    public record Identity(long pid, long birth, long parent, int uid, int gid, Path executable, Path group)
    {
        /**
         * Rejects incomplete image observations.
         * @param pid native PID
         * @param birth Linux start ticks
         * @param parent live parent PID
         * @param uid complete UID identity
         * @param gid complete GID identity
         * @param executable native executable
         * @param group actual owned cgroup
         */
        public Identity
        {
            if (pid <= 0 || birth <= 0 || parent <= 0 || pid == parent || uid < 0 || gid < 0)
                throw new IllegalArgumentException("Invalid native identity");
            if (!executable.isAbsolute() || !group.isAbsolute()) throw new IllegalArgumentException("Absolute image paths required");
        }
        /**
         * Checks stable process birth across exec while permitting image and credential transitions.
         * @param other independently observed replacement image
         * @return whether PID, birth and actual parent are unchanged
         */
        boolean sameBirth(Identity other) { return pid == other.pid && birth == other.birth && parent == other.parent; }
    }

    /**
     * Defines exact required workload credentials, the pinned root Cargo image and the pinned native compiler wrapper.
     * @param uid workload UID
     * @param gid workload GID
     * @param cargo absolute pinned Cargo executable
     * @param wrapper absolute pinned native Cargo wrapper executable, whose held starts resume without attachment
     */
    public record Policy(int uid, int gid, Path cargo, Path wrapper)
    {
        /**
         * Rejects incomplete workload policy.
         * @param uid workload UID
         * @param gid workload GID
         * @param cargo pinned Cargo executable
         * @param wrapper pinned native Cargo wrapper executable
         * @throws NullPointerException if {@code cargo} or {@code wrapper} is null
         * @throws IllegalArgumentException if a credential is negative or an executable path is relative
         */
        public Policy
        {
            if (uid < 0 || gid < 0 || !cargo.isAbsolute() || !wrapper.isAbsolute())
                throw new IllegalArgumentException("Invalid workload policy");
        }
    }

    /**
     * Consumer retained by the caller through the same authentic workload barrier.
     */
    @FunctionalInterface
    public interface Enrollment
    {
        /**
         * Acknowledges readiness for the exact held image before any explicit resume.
         * Partial callback resources remain caller-owned through the authentic workload barrier.
         * The callback must preserve native image identity and containment and must not resume the process.
         * @param image independently verified current image
         * @param generation positive distinct retained connection ordinal
         * @throws IOException if enrollment readiness is absent or uncertain
         */
        void ready(Identity image, long generation) throws IOException;
    }

    /**
     * Supplies actual native operations or independent Java-controlled observations.
     */
    public interface Operations
    {
        /**
         * Reads one live image lineage without accepting callback fields as authority.
         * @param pid process to inspect
         * @return live native lineage
         * @throws IOException if native observations are unavailable
         */
        Identity inspect(long pid) throws IOException;
        /** Authenticates kernel adoption by the caller-owned original supervisor after parent retirement.
         * @param parent independently retained original parent birth
         * @param child independently observed held child
         * @throws IOException if retirement or original supervisor adoption is not authenticated
         */
        default void requireAdoption(Identity parent, Identity child) throws IOException
        { throw new IOException("No authenticated supervisor adoption authority"); }

        /**
         * Compares the observed executable with the expected immutable native file.
         * @param image independently inspected image
         * @param expected expected executable
         * @return whether both identify the same executable
         * @throws IOException if file identity is unavailable
         */
        default boolean matchesExecutable(Identity image, Path expected) throws IOException
        { return image.executable().equals(expected); }
        /** Retains requested source separately from its current executable image.
         * @param image independently authenticated held identity
         * @param expected requested executable or script
         * @return immutable source and executable binding
         * @throws IOException if the requested image cannot be independently matched
         */
        public default ExecutableIdentity executableIdentity(Identity image, Path expected) throws IOException
        {
            if (!expected.isAbsolute() || !matchesExecutable(image, expected))
                throw new IOException("Held executable differs from independently observed image");
            return new ExecutableIdentity(expected, image.executable(), List.of(), Map.of());
        }

        /**
         * Selects a containment member before its full independent image authentication.
         * @param pid current native process
         * @param containment retained root subtree
         * @return whether the process currently belongs to this subtree
         * @throws IOException if the observation cannot be established
         */
        default boolean contains(long pid, Path containment) throws IOException
        { return inspect(pid).group().startsWith(containment); }
        /** Establishes exact sole-native-tracer readiness when this is a static or already native-owned image.
         * @param image independently authenticated held process
         * @return true for native-held readiness; false when the caller must acquire a Frida connection
         * @throws IOException if native handoff is uncertain
         */
        default boolean prepareNative(Identity image) throws IOException { return false; }
        /** Copies one native held transition without authorizing it.
         * @return pending actual event, or null
         * @throws IOException if native ownership or producer allocation fails
         */
        default FridaRuntime.Child pollNativeChild() throws IOException { return null; }
        /** Joins original trace owners after the caller's authentic workload-zero barrier.
         * @throws IOException if native collection remains incomplete
         */
        default void closeNative() throws IOException {}
        /**
         * Creates a resource-free connection subsequently retained before start.
         * @param pid authenticated process
         * @return existing allocation transport connection
         */
        AllocationHook.Connection connection(long pid);
        /**
         * Releases a genuinely gated child once after readiness.
         * @param pid held child
         * @throws IOException if release has uncertain effects
         */
        void resume(long pid) throws IOException;
        /**
         * Releases a held start of the native Cargo wrapper once, leaving neither it nor its descendants traced by this
         * owner, so that each compiler attempt it forks can be attached by its own attempt owner.
         * @param pid held wrapper image
         * @throws IOException if release has uncertain effects
         */
        default void releaseWrapper(long pid) throws IOException { resume(pid); }
    }

    /**
     * Uses existing Frida connections and exact targeted proc/cgroup observations.
     */
    private static final class NativeOperations implements Operations
    {
        /** Sole native owners retained through the same whole-workload execution barrier. */
        private final LinuxAllocationTrace.Images nativeImages;
        /**
         * Active borrowed manager retained through every connection lifetime.
         */
        private final FridaRuntime.Manager manager;
        /**
         * Caller-owned cgroup retained through the workload barrier.
         */
        private final Path containment;
        /** Original native supervisor birth and credentials, pinned before any workload release. */
        private final LinuxBorrowedStreams.Peer supervisor;
        /** Original supervisor executable inode and device. */
        private final Map<String, Object> supervisorExecutable;
        /**
         * Retains native observations without acquiring resources.
         * @param scope borrowed native linker services
         * @param timeout positive native readiness and collection interval
         * @param manager active manager
         * @param containment normalized absolute owned cgroup
         * @param supervisorPid original caller-owned native supervisor
         * @param supervisorBirth original authenticated supervisor birth
         * @throws IOException if the supervisor cannot be independently pinned
         */
        private NativeOperations(ToolingScope scope, FridaRuntime.Manager manager, Path containment, Duration timeout,
            long supervisorPid, long supervisorBirth) throws IOException
        {
            this.manager = Objects.requireNonNull(manager, "manager");
            nativeImages = new LinuxAllocationTrace.Images(scope.getNativeLinker(), timeout);
            this.containment = Objects.requireNonNull(containment, "containment");
            if (!containment.isAbsolute() || !containment.normalize().equals(containment))
                throw new IllegalArgumentException("Normalized absolute containment required");
            Path proc = Path.of("/proc", Long.toString(supervisorPid));
            String status = Files.readString(proc.resolve("status"));
            supervisor = new LinuxBorrowedStreams.Peer(supervisorPid, supervisorBirth,
                credentials(status, "Uid:"), credentials(status, "Gid:"));
            supervisor.requireLive();
            supervisorExecutable = Files.readAttributes(proc.resolve("exe"), "unix:dev,ino");
            supervisor.requireLive();
        }
        @Override public Identity inspect(long pid) throws IOException
        {
            if (!System.getProperty("os.name", "").equals("Linux")) throw new IOException("Linux is required");
            Path proc = Path.of("/proc", Long.toString(pid));
            String before = Files.readString(proc.resolve("stat"));
            String status = Files.readString(proc.resolve("status"));
            long birth = birth(before);
            long parent = Long.parseLong(field(status, "PPid:").strip());
            int uid = credentials(status, "Uid:");
            int gid = credentials(status, "Gid:");
            new LinuxBorrowedStreams.Peer(pid, birth, uid, gid).requireLive();
            Path executable = Files.readSymbolicLink(proc.resolve("exe"));
            String membership = Files.readString(proc.resolve("cgroup"));
            Path member = null;
            try (var directories = Files.walk(containment))
            {
                for (Path directory : directories.filter(Files::isDirectory).toList())
                    if (Files.readAllLines(directory.resolve("cgroup.procs")).contains(Long.toString(pid)))
                    {
                        if (member != null) throw new IOException("Ambiguous native cgroup membership");
                        member = directory;
                    }
            }
            if (member == null || !membership.equals(Files.readString(proc.resolve("cgroup"))) ||
                !sameStatus(status, Files.readString(proc.resolve("status"))) ||
                birth != birth(Files.readString(proc.resolve("stat"))))
                throw new IOException("Image lacks stable owned containment or credentials");
            new LinuxBorrowedStreams.Peer(pid, birth, uid, gid).requireLive();
            if (!executable.equals(Files.readSymbolicLink(proc.resolve("exe")))) throw new IOException("Native executable changed");
            return new Identity(pid, birth, parent, uid, gid, executable, member);
        }
        @Override public void requireAdoption(Identity parent, Identity child) throws IOException
        {
            requireSupervisor();
            if (child.parent() != supervisor.pid() || child.birth() < parent.birth())
                throw new IOException("Child was not adopted by its original supervisor");
            String stat;
            try { stat = Files.readString(Path.of("/proc", Long.toString(parent.pid()), "stat")); }
            catch (java.nio.file.NoSuchFileException absent) { stat = null; }
            requireRetiredParent(parent.birth(), stat);
            if (!child.equals(inspect(child.pid()))) throw new IOException("Adopted child changed during authentication");
            requireSupervisor();
        }
        /** Checks the original process owner rather than inferring a supervisor from the child callback.
         * @throws IOException if birth, credentials or original executable identity changed
         */
        private void requireSupervisor() throws IOException
        {
            supervisor.requireLive();
            if (!supervisorExecutable.equals(Files.readAttributes(
                Path.of("/proc", Long.toString(supervisor.pid()), "exe"), "unix:dev,ino")))
                throw new IOException("Original supervisor executable changed");
            supervisor.requireLive();
        }
        @Override public boolean contains(long pid, Path ignored) throws IOException
        { return AllocationHooks.contains(containment, pid); }
        @Override public boolean matchesExecutable(Identity image, Path expected) throws IOException
        { return Files.isSameFile(Path.of("/proc", Long.toString(image.pid()), "exe"), expected); }
        @Override public ExecutableIdentity executableIdentity(Identity image, Path expected) throws IOException
        { return LinuxCargoImages.executableIdentity(image, expected, this::inspect); }

        /** Resolves only the kernel's requested script operand while retaining original argv elsewhere.
         * Direct binary validation does not use this rewritten comparison view. A relative script
         * operand must match the exact original requested spelling before its authenticated cwd is applied.
         * @param arguments independently read kernel argv
         * @param requested original relative executable spelling
         * @param resolved executable beneath the independently read held working directory
         * @return comparison argv with only a matching requested operand resolved
         */
        private static List<String> relativeInterpreterArguments(List<String> arguments, Path requested, Path resolved)
        {
            var result = new java.util.ArrayList<>(arguments);
            for (int index = 1; index < Math.min(3, result.size()); index++)
                if (result.get(index).equals(requested.toString()))
                { result.set(index, resolved.toString()); break; }
            return result;
        }

        /**
         * Compares identity fields while permitting ordinary volatile process statistics to change.
         * @param before earlier status observation
         * @param after later status observation
         * @return whether parent and every credential form remain equal
         * @throws IOException if required identity fields are unavailable
         */
        private static boolean sameStatus(String before, String after) throws IOException
        {
            return field(before, "PPid:").equals(field(after, "PPid:")) &&
                credentials(before, "Uid:") == credentials(after, "Uid:") &&
                credentials(before, "Gid:") == credentials(after, "Gid:");
        }
        /**
         * Reads Linux stat start ticks without confusing spaces or parentheses in comm.
         * @param stat complete native stat text
         * @return positive process birth ticks
         * @throws IOException if the native record is malformed
         */
        private static long birth(String stat) throws IOException
        {
            try { return Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]); }
            catch (RuntimeException cause) { throw new IOException("Native birth is unavailable", cause); }
        }
        /**
         * Reads one exact native status field.
         * @param status complete native process status
         * @param key required field prefix
         * @return complete field value
         * @throws IOException if absent
         */
        private static String field(String status, String key) throws IOException
        {
            return status.lines().filter(line -> line.startsWith(key)).map(line -> line.substring(key.length())).
                findFirst().orElseThrow(() -> new IOException("Native identity field is absent: " + key));
        }
        /**
         * Requires all four native credential forms to agree.
         * @param status native status record
         * @param key UID or GID field
         * @return exact common credential
         * @throws IOException if incomplete or inconsistent
         */
        private static int credentials(String status, String key) throws IOException
        {
            try
            {
                String[] values = field(status, key).strip().split("\\s+");
                int first = Integer.parseInt(values[0]);
                if (values.length != 4) throw new IOException("Incomplete native credentials");
                for (String value : values) if (Integer.parseInt(value) != first)
                    throw new IOException("Native credential forms differ");
                return first;
            }
            catch (NumberFormatException cause) { throw new IOException("Malformed native credentials", cause); }
        }
        @Override public AllocationHook.Connection connection(long pid) { return AllocationHook.newNativeConnection(manager, pid); }
        /** Hands every producer image to the ptrace owner, dynamic ones included.
         * Frida's agent re-injects itself into each fork child of an instrumented process, and when that fails it keeps
         * the child stopped under its own tracer with no event, so a fork-heavy build script such as autoconf
         * {@code configure} would hang (frida-core {@code RejuvenateTask}). A denied allocation in a traced image is
         * reported and ends the build rather than waiting, as it already does for a static image.
         * @param image independently authenticated held process
         * @return true, since this owner holds every image
         * @throws IOException if native handoff is uncertain
         */
        @Override public boolean prepareNative(Identity image) throws IOException
        {
            return nativeImages.install(image.pid(), image.birth(), () -> manager.resume(image.pid()), true);
        }
        @Override public void releaseWrapper(long pid) throws IOException
        {
            if (nativeImages.owns(pid)) nativeImages.detach(pid, inspect(pid).birth());
            else manager.resume(pid);
        }
        @Override public FridaRuntime.Child pollNativeChild() throws IOException
        {
            LinuxAllocationTrace.Denial denial = nativeImages.pollDenial();
            if (denial != null) throw new IOException("Cargo producer allocation denied at held native trap: " + denial);
            return nativeImages.pollChild();
        }
        @Override public void closeNative() throws IOException { nativeImages.close(); }
        @Override public void resume(long pid) throws IOException
        {
            if (nativeImages.owns(pid)) nativeImages.resume(pid, inspect(pid).birth());
            else manager.resume(pid);
        }
    }

}
