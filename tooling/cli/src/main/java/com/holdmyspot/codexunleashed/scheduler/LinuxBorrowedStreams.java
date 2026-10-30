package com.holdmyspot.codexunleashed.scheduler;

import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Borrows a registered wrapper's standard streams without reopening their files.
 * The controller must authenticate registration before supplying a binding; construction grants no authority.
 * This owner supports Linux x64 glibc only. Each listener and pending installation has one operation thread.
 */
public final class LinuxBorrowedStreams {
    /** Creates the owner before acquiring its native resources. */
    private LinuxBorrowedStreams() { }
    /** Fixed per-attempt capability length. */
    private static final int CAPABILITY_BYTES = 32;
    /** Exact handshake size: version, action UUID, sequence and capability. */
    private static final int FRAME_BYTES = 60;
    /** Prefix keeping descriptor configuration separate from normal launch fields. */
    private static final String CONFIG = "borrowed.";

    /**
     * A live wrapper identity in the controller's PID namespace.
     * @param pid live wrapper process ID
     * @param startTicks kernel process birth tick count
     * @param uid effective user ID
     * @param gid effective group ID
     */
    public record Peer(long pid, long startTicks, int uid, int gid) {
        /** Rejects invalid process identities before any acquisition. */
        public Peer {
            if (pid <= 0 || startTicks <= 0 || uid < 0 || gid < 0)
                throw new IllegalArgumentException("Wrapper identity must name a live process and valid credentials");
        }
        /**
         * Captures process birth identity and effective credentials, rejecting a changing process.
         * @return the validated operation result
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        public static Peer current() throws IOException { return capture(ProcessHandle.current().pid()); }
        /**
         * Captures a stable process birth identity and its effective credentials.
         * @param pid stable peer process identity
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        private static Peer capture(long pid) throws IOException {
            Path process = Path.of("/proc", Long.toString(pid));
            long birth = birth(process);
            int uid = -1, gid = -1;
            for (String line : Files.readAllLines(process.resolve("status"))) {
                if (line.startsWith("Uid:")) uid = Integer.parseInt(line.trim().split("\\s+")[2]);
                if (line.startsWith("Gid:")) gid = Integer.parseInt(line.trim().split("\\s+")[2]);
            }
            if (birth != birth(process)) throw new IOException("Wrapper identity changed during capture");
            return new Peer(pid, birth, uid, gid);
        }
        /**
         * Reads the process birth tick count without confusing spaces in the command name.
         * @param process kernel process information directory
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        private static long birth(Path process) throws IOException {
            String stat = Files.readString(process.resolve("stat"));
            return Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]);
        }
        /**
         * Rejects changed or inaccessible peer identity and an unestablished PID namespace.
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void requireLive() throws IOException {
            if (!equals(capture(pid))) throw new IOException("Registered wrapper identity is no longer live");
            Path own = Path.of("/proc/self/ns/pid");
            Path other = Path.of("/proc", Long.toString(pid), "ns/pid");
            if (!Files.readSymbolicLink(own).equals(Files.readSymbolicLink(other)))
                throw new IOException("Borrowed streams require an established common PID namespace");
        }
    }

    /**
     * Terminal semantics of one descriptor; attributes exclude padding bytes.
     * @param tty whether this descriptor refers to a terminal
     * @param device terminal device identity, or empty for a non-terminal
     * @param session terminal or process session ID
     * @param foreground terminal foreground process group
     * @param attributes semantic termios fields without padding bytes
     */
    public record Terminal(boolean tty, String device, long session, long foreground, List<Integer> attributes) {
        /** Copies semantic terminal fields; non-terminals have no terminal metadata. */
        public Terminal {
            Objects.requireNonNull(device, "device may not be null");
            attributes = List.copyOf(attributes);
            if (tty && (session <= 0 || foreground <= 0 || attributes.size() != 39))
                throw new IllegalArgumentException("Terminal requires identity, foreground group and semantic attributes");
            if (!tty && (!device.isEmpty() || session != 0 || foreground != 0 || !attributes.isEmpty()))
                throw new IllegalArgumentException("Non-terminal descriptor cannot carry terminal metadata");
        }
    }
    /**
     * Session and process group inherited from the original wrapper.
     * @param session terminal or process session ID
     * @param group wrapper process group ID
     * @param streams complete stream binding or descriptor context
     */
    public record TerminalContext(long session, long group, List<Terminal> streams) {
        /** Requires the complete three-descriptor context. */
        public TerminalContext {
            streams = List.copyOf(streams);
            if (session <= 0 || group <= 0 || streams.size() != 3)
                throw new IllegalArgumentException("Stream context requires a session, group and three descriptors");
        }
    }

    /** Immutable trusted-registration receipt; its constructor does not authenticate its caller. */
    public static final class Binding {
        /** Registered compiler action identity. */
        private final UUID actionId;
        /** Positive retry sequence for this registration. */
        private final long attemptSequence;
        /** Live wrapper identity captured for the registered endpoint. */
        private final Peer wrapper;
        /** Exclusively owned Unix socket pathname. */
        private final Path endpoint;
        /** Defensively copied per-attempt capability. */
        private final byte[] capability;
        /** Registered wrapper session and terminal semantics. */
        private final TerminalContext terminal;
        /** Immutable command and launch context approved by registration. */
        private final LinuxProcessTree.Launch approvedLaunch;
        /**
         * Copies an already authenticated registration without minting registration authority.
         * @param actionId registered compiler action identity
         * @param attemptSequence positive per-action retry ordinal
         * @param wrapper registered live wrapper identity
         * @param endpoint owned absolute Unix socket pathname
         * @param capability copied256-bit per-attempt capability
         * @param terminal registered session and terminal context
         * @param approvedLaunch immutable controller-approved launch
         */
        public Binding(UUID actionId, long attemptSequence, Peer wrapper, Path endpoint, byte[] capability,
            TerminalContext terminal, LinuxProcessTree.Launch approvedLaunch) {
            this.actionId = Objects.requireNonNull(actionId, "actionId may not be null");
            this.wrapper = Objects.requireNonNull(wrapper, "wrapper may not be null");
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint may not be null");
            this.terminal = Objects.requireNonNull(terminal, "terminal may not be null");
            this.approvedLaunch = Objects.requireNonNull(approvedLaunch, "approvedLaunch may not be null");
            if (attemptSequence <= 0 || !endpoint.isAbsolute() || capability.length != CAPABILITY_BYTES)
                throw new IllegalArgumentException("Binding requires a positive attempt, absolute endpoint and256-bit capability");
            this.attemptSequence = attemptSequence;
            this.capability = capability.clone();
            for (String argument : approvedLaunch.command()) requireNativeText(argument);
            approvedLaunch.environment().forEach((key, value) -> {
                requireNativeText(key); requireNativeText(value);
                if (key.isEmpty() || key.indexOf('=') >= 0) throw new IllegalArgumentException("Invalid native environment key");
            });
            for (Path path : List.of(endpoint, approvedLaunch.javaExecutable(), approvedLaunch.classPath(), approvedLaunch.directory()))
                requireNativeText(path.toString());
        }
        /**
         * Returns the approved immutable launch, never an independently supplied command.
         * @return the validated operation result
         */
        public LinuxProcessTree.Launch approvedLaunch() { return approvedLaunch; }
        /**
         * Returns the registered action identity.
         * @return the validated operation result
         */
        public UUID actionId() { return actionId; }
        /**
         * Returns the per-action retry sequence.
         * @return the validated operation result
         */
        public long attemptSequence() { return attemptSequence; }
        /**
         * Encodes the fixed-size action, attempt and capability handshake.
         * @return the validated result retained by this operation
         */
        byte[] frame() {
            return ByteBuffer.allocate(FRAME_BYTES).putInt(1).putLong(actionId.getMostSignificantBits()).
                putLong(actionId.getLeastSignificantBits()).putLong(attemptSequence).put(capability).array();
        }
        /**
         * Requires both privileged gate credentials and the authorized handshake.
         * @param peer required operation input
         * @param actual required operation input
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void requireGate(Peer peer, byte[] actual) throws IOException {
            if (peer.uid() != 0) throw new IOException("Stream connector is not a privileged trusted gate");
            requireFrame(actual);
        }
        /**
         * Rejects a handshake for another action, attempt or capability.
         * @param actual required operation input
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void requireFrame(byte[] actual) throws IOException {
            if (!MessageDigest.isEqual(frame(), actual))
                throw new IOException("Stream handshake has another action, attempt or capability");
        }
        /**
         * Rejects every execution-field change while permitting owned containment derivation.
         * @param launch immutable approved execution inputs
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void requireExecution(LinuxProcessTree.Launch launch) throws IOException {
            LinuxProcessTree.Launch approved = approvedLaunch;
            boolean sameExecution = approved.javaExecutable().equals(launch.javaExecutable()) &&
                approved.classPath().equals(launch.classPath()) && approved.command().equals(launch.command()) &&
                approved.environment().equals(launch.environment()) && approved.directory().equals(launch.directory()) &&
                approved.uid() == launch.uid() && approved.gid() == launch.gid() &&
                approved.executionMode() == launch.executionMode();
            if (!sameExecution) throw new IOException("Borrowed stream authorization does not match approved execution");
        }
    }
    /**
     * Rejects NUL that native argument and environment strings cannot preserve.
     * @param value native return value
     */
    private static void requireNativeText(String value) {
        if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Native launch text cannot contain NUL");
    }

    /** Native stream transfer boundary owned by the listener, replaceable in focused policy fixtures. */
    interface Transfer extends AutoCloseable {
        /**
         * Retains the state required by this owned native operation.
         * @param timeout positive bounded control timeout
         * @param binding authenticated action, peer and launch receipt
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void sendBorrowed(Duration timeout, Binding binding) throws IOException;
        @Override void close() throws IOException;
    }
    /** Wrapper-owned endpoint; original0/1/2 and Cargo capacity remain borrowed. */
    public static final class Listener implements AutoCloseable {
        /** Owned native transfer and endpoint cleanup boundary. */
        private final Transfer transport;
        /** Live wrapper identity captured for the registered endpoint. */
        private final Peer wrapper;
        /** Registered compiler action identity. */
        private final UUID actionId;
        /** Exclusively owned Unix socket pathname. */
        private final Path endpoint;
        /** Registered wrapper session and terminal semantics. */
        private final TerminalContext terminal;
        /** Installed receipt consumed by the next transfer. */
        private Binding authorized;
        /** Highest sequence whose transfer authority has been consumed. */
        private long lastSequence;
        /** Whether cleanup or installation consumption already ended this owner operation. */
        private boolean closed;
        /**
         * Creates the owner before acquiring its native resources.
         * @param transport required operation input
         * @param wrapper required operation input
         * @param actionId required operation input
         * @param endpoint owned pathname Unix socket endpoint
         * @param terminal required operation input
         */
        Listener(Transfer transport, Peer wrapper, UUID actionId, Path endpoint, TerminalContext terminal) {
            this.transport = transport; this.wrapper = wrapper; this.actionId = actionId;
            this.endpoint = endpoint; this.terminal = terminal;
        }
        /**
         * Returns the wrapper identity captured before binding.
         * @return the validated operation result
         */
        public Peer wrapper() { return wrapper; }
        /**
         * Returns the owned endpoint.
         * @return the validated operation result
         */
        public Path endpoint() { return endpoint; }
        /**
         * Returns the original terminal context for authenticated registration.
         * @return the validated operation result
         */
        public TerminalContext terminal() { return terminal; }
        /**
         * Installs one controller-authenticated receipt before accepting an attempt.
         * @param binding controller-authenticated registration receipt
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        public void authorize(Binding binding) throws IOException {
            Objects.requireNonNull(binding, "binding may not be null");
            if (closed || authorized != null) throw new IllegalStateException("Listener cannot replace a live authorization");
            boolean sameWrapper = actionId.equals(binding.actionId) && wrapper.equals(binding.wrapper) &&
                endpoint.equals(binding.endpoint) && terminal.equals(binding.terminal);
            if (!sameWrapper || binding.attemptSequence <= lastSequence)
                throw new IOException("Authorization does not identify this listener and a new attempt");
            wrapper.requireLive();
            authorized = binding;
        }
        /**
         * Consumes installed authorization once, including failed or interrupted transfers.
         * @param timeout positive bounded handoff deadline
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        public void sendBorrowed(Duration timeout) throws IOException {
            deadline(timeout);
            if (closed || authorized == null) throw new IllegalStateException("No installed stream authorization");
            Binding binding = authorized;
            authorized = null; lastSequence = binding.attemptSequence;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Stream transfer was interrupted before native acquisition");
            wrapper.requireLive();
            transport.sendBorrowed(timeout, binding);
        }
        /** Closes only owned socket references and removes only the original endpoint identity. */
        @Override public void close() throws IOException {
            closed = true; authorized = null;
            transport.close();
        }
    }
    /**
     * Binds a private wrapper endpoint; binding alone does not authorize a transfer.
     * @param privateDirectory wrapper-owned0700 endpoint directory
     * @param actionId registered compiler action identity
     * @return the validated operation result
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    public static Listener listen(Path privateDirectory, UUID actionId) throws IOException {
        Objects.requireNonNull(actionId, "actionId may not be null");
        if (!privateDirectory.isAbsolute()) throw new IllegalArgumentException("Endpoint directory must be absolute");
        if (!Files.getPosixFilePermissions(privateDirectory).equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Wrapper endpoint directory must have0700 permissions");
        Peer wrapper = Peer.current();
        if (((Number) Files.getAttribute(privateDirectory, "unix:uid")).intValue() != wrapper.uid())
            throw new IOException("Wrapper must own its endpoint directory");
        TerminalContext terminal = context(new int[] {0, 1, 2});
        Path endpoint = privateDirectory.resolve("streams.sock");
        Native.Listener transport = Native.Listener.open(endpoint);
        try { return new Listener(transport, wrapper, actionId, endpoint, terminal); }
        catch (RuntimeException | Error failure) {
            try { transport.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Installation boundary shared by real native descriptors and focused failure fixtures. */
    interface Installation extends AutoCloseable {
        /**
         * Checks the registered terminal context before descriptor installation.
         * @param expected authorized frame or terminal context
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void validate(TerminalContext expected) throws IOException;
        /**
         * Installs the pending descriptors in the dedicated gate.
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void install() throws IOException;
        @Override void close() throws IOException;
    }
    /** Gate-owned pending references. Installation is irreversible and may never be retried in this gate. */
    static final class Pending implements AutoCloseable {
        /** Owned pending descriptor installation. */
        private final Installation installation;
        /** Whether irreversible installation has begun or cleanup has consumed it. */
        private boolean consumed;
        /** Whether cleanup or installation consumption already ended this owner operation. */
        private boolean closed;
        /**
         * Creates the owner before acquiring its native resources.
         * @param installation owned pending descriptor installation
         */
        Pending(Installation installation) { this.installation = Objects.requireNonNull(installation); }
        /**
         * Retains the state required by this owned native operation.
         * @param expected authorized frame or terminal context
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void installBeforeReady(TerminalContext expected) throws IOException {
            if (consumed || closed) throw new IllegalStateException("Descriptor installation is irreversible and already consumed");
            consumed = true;
            installation.validate(expected);
            installation.install();
        }
        @Override public void close() throws IOException { if (closed) return; closed = true; consumed = true; installation.close(); }
    }
    /**
     * Authenticates the registered wrapper and acquires its three descriptor references.
     * @param binding authenticated action, peer and launch receipt
     * @param timeout positive bounded control timeout
     * @return the validated result retained by this operation
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    private static Pending receive(Binding binding, Duration timeout) throws IOException {
        Native.Received received = Native.receive(binding.endpoint, timeout, binding);
        try {
            return new Pending(new Installation() {
                @Override public void validate(TerminalContext expected) throws IOException {
                    binding.wrapper.requireLive();
                    if (!expected.equals(context(Arrays.copyOf(received.owned.fds, 3))))
                        throw new IOException("Borrowed terminal context is incompatible with this trusted gate");
                }
                @Override public void install() throws IOException { received.install(); }
                @Override public void close() throws IOException { received.close(); }
            });
        } catch (RuntimeException | Error failure) {
            try { received.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    /**
     * Imports and installs only inside the dedicated trusted gate, before readiness publication.
     * @param binding controller-authenticated registration receipt
     * @param timeout positive bounded handoff deadline
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    static void installBeforeReady(Binding binding, Duration timeout) throws IOException {
        try (Pending pending = receive(binding, timeout)) { pending.installBeforeReady(binding.terminal); }
    }
    /**
     * Captures session, foreground and semantic terminal attributes without changing them.
     * @param descriptors three borrowed descriptor references
     * @return the validated result retained by this operation
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    private static TerminalContext context(int[] descriptors) throws IOException {
        List<Terminal> streams = new ArrayList<>();
        for (int fd : descriptors) {
            Native.Outcome tty = Native.call("isatty", fd);
            if (tty.value() == 0) {
                if (tty.errno() != 25) throw new IOException("Descriptor is unavailable for terminal inspection");
                streams.add(new Terminal(false, "", 0, 0, List.of()));
            } else {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment attributes = arena.allocate(60, 4);
                    Native.require("tcgetattr", fd, attributes);
                    List<Integer> semantic = new ArrayList<>();
                    for (int offset = 0; offset < 16; offset += 4) semantic.add(attributes.get(ValueLayout.JAVA_INT, offset));
                    semantic.add(Byte.toUnsignedInt(attributes.get(ValueLayout.JAVA_BYTE, 16)));
                    for (int offset = 17; offset < 49; offset++) semantic.add(Byte.toUnsignedInt(attributes.get(ValueLayout.JAVA_BYTE, offset)));
                    semantic.add(attributes.get(ValueLayout.JAVA_INT, 52)); semantic.add(attributes.get(ValueLayout.JAVA_INT, 56));
                    String device = Files.getAttribute(Path.of("/proc/self/fd", Integer.toString(fd)), "unix:rdev").toString();
                    streams.add(new Terminal(true, device, Native.require("tcgetsid", fd), Native.require("tcgetpgrp", fd), semantic));
                }
            }
        }
        return new TerminalContext(Native.require("getsid", 0), Native.require("getpgrp"), streams);
    }
    /**
     * Validates a positive duration and computes its monotonic deadline.
     * @param timeout positive bounded control timeout
     * @return the validated result retained by this operation
     */
    private static long deadline(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
        return System.nanoTime() + timeout.toNanos();
    }

    /**
     * Stores the complete approved receipt only in protected controller-owned configuration.
     * @param values protected configuration properties
     * @param binding controller-authenticated registration receipt
     */
    static void writeTo(Properties values, Binding binding) {
        if (binding == null) return;
        values.setProperty(CONFIG + "action", binding.actionId.toString());
        values.setProperty(CONFIG + "sequence", Long.toString(binding.attemptSequence));
        values.setProperty(CONFIG + "pid", Long.toString(binding.wrapper.pid()));
        values.setProperty(CONFIG + "birth", Long.toString(binding.wrapper.startTicks()));
        values.setProperty(CONFIG + "uid", Integer.toString(binding.wrapper.uid()));
        values.setProperty(CONFIG + "gid", Integer.toString(binding.wrapper.gid()));
        values.setProperty(CONFIG + "endpoint", binding.endpoint.toString());
        values.setProperty(CONFIG + "capability", Base64.getEncoder().encodeToString(binding.capability));
        values.setProperty(CONFIG + "session", Long.toString(binding.terminal.session()));
        values.setProperty(CONFIG + "group", Long.toString(binding.terminal.group()));
        for (int i = 0; i < 3; i++) {
            Terminal terminal = binding.terminal.streams().get(i);
            String prefix = CONFIG + "terminal." + i + ".";
            values.setProperty(prefix + "tty", Boolean.toString(terminal.tty()));
            values.setProperty(prefix + "device", terminal.device());
            values.setProperty(prefix + "session", Long.toString(terminal.session()));
            values.setProperty(prefix + "foreground", Long.toString(terminal.foreground()));
            values.setProperty(prefix + "attributes", terminal.attributes().stream().map(Object::toString).collect(java.util.stream.Collectors.joining(",")));
        }
        LinuxProcessTree.Launch launch = binding.approvedLaunch;
        values.setProperty(CONFIG + "cgroup", launch.cgroupParent().toString());
        values.setProperty(CONFIG + "scratch", launch.scratchParent().toString());
        values.setProperty(CONFIG + "java", launch.javaExecutable().toString());
        values.setProperty(CONFIG + "classpath", launch.classPath().toString());
        values.setProperty(CONFIG + "directory", launch.directory().toString());
        values.setProperty(CONFIG + "workloadUid", Integer.toString(launch.uid()));
        values.setProperty(CONFIG + "workloadGid", Integer.toString(launch.gid()));
        values.setProperty(CONFIG + "mode", launch.executionMode().name());
        values.setProperty(CONFIG + "commandCount", Integer.toString(launch.command().size()));
        for (int i = 0; i < launch.command().size(); i++) values.setProperty(CONFIG + "arg." + i, launch.command().get(i));
        launch.environment().forEach((key, value) -> values.setProperty(CONFIG + "env." + key, value));
    }
    /**
     * Reconstructs a protected receipt; presence is distinct from an empty required value.
     * @param values protected configuration properties
     * @return the validated operation result
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    static Binding readFrom(Properties values) throws IOException {
        if (!values.containsKey(CONFIG + "action")) {
            for (String key : values.stringPropertyNames())
                if (key.startsWith(CONFIG)) throw new IOException("Borrowed configuration has no action identity");
            return null;
        }
        try {
            int count = Integer.parseInt(required(values, "commandCount"));
            if (count <= 0 || count > 65536) throw new IOException("Invalid approved command length");
            List<String> command = new ArrayList<>();
            for (int i = 0; i < count; i++) command.add(required(values, "arg." + i));
            Map<String, String> environment = new HashMap<>();
            for (String key : values.stringPropertyNames())
                if (key.startsWith(CONFIG + "env.")) environment.put(key.substring((CONFIG + "env.").length()), values.getProperty(key));
            LinuxProcessTree.Launch launch = new LinuxProcessTree.Launch(Path.of(required(values, "cgroup")),
                Path.of(required(values, "scratch")), Path.of(required(values, "java")), Path.of(required(values, "classpath")),
                command, environment, Path.of(required(values, "directory")), Integer.parseInt(required(values, "workloadUid")),
                Integer.parseInt(required(values, "workloadGid")), LinuxProcessTree.ExecutionMode.valueOf(required(values, "mode")));
            List<Terminal> terminals = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String prefix = "terminal." + i + ".";
                String tty = required(values, prefix + "tty");
                if (!tty.equals("true") && !tty.equals("false")) throw new IOException("Invalid terminal kind");
                String text = required(values, prefix + "attributes");
                List<Integer> attributes = text.isEmpty() ? List.of() : Arrays.stream(text.split(",", -1)).map(Integer::valueOf).toList();
                terminals.add(new Terminal(Boolean.parseBoolean(tty), required(values, prefix + "device"),
                    Long.parseLong(required(values, prefix + "session")), Long.parseLong(required(values, prefix + "foreground")), attributes));
            }
            return new Binding(UUID.fromString(required(values, "action")), Long.parseLong(required(values, "sequence")),
                new Peer(Long.parseLong(required(values, "pid")), Long.parseLong(required(values, "birth")),
                    Integer.parseInt(required(values, "uid")), Integer.parseInt(required(values, "gid"))),
                Path.of(required(values, "endpoint")), Base64.getDecoder().decode(required(values, "capability")),
                new TerminalContext(Long.parseLong(required(values, "session")), Long.parseLong(required(values, "group")), terminals), launch);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid borrowed stream configuration", failure);
        }
    }
    /**
     * Reads a required protected property while retaining an explicitly empty value.
     * @param values required operation input
     * @param key required protected property name
     * @return the validated result retained by this operation
     * @throws java.io.IOException if validation, native access or owned cleanup fails
     */
    private static String required(Properties values, String key) throws IOException {
        String value = values.getProperty(CONFIG + key);
        if (value == null) throw new IOException("Borrowed stream configuration is incomplete: " + key);
        return value;
    }

    /** Consumes one descriptor number with the Linux close-once contract. */
    @FunctionalInterface
    interface CloseDescriptor {
        /**
         * Closes only remaining owned references, preserving native close failures.
         * @param descriptor required operation input
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void close(int descriptor) throws IOException;
    }
    /** Retains acquired descriptor numbers in preallocated ownership slots. */
    static final class Descriptors implements AutoCloseable {
        /** Preallocated owned descriptor slots. */
        final int[] fds=new int[32];
        /** Number of descriptor slots still owned. */
        int size;
        /** Native once-only descriptor close boundary. */
        private final CloseDescriptor closer;
        /** Creates the owner before acquiring its native resources. */
        Descriptors(){
            this(new Native.CloseOperation());
        }
        /**
         * Creates the owner before acquiring its native resources.
         * @param closer once-only descriptor close boundary
         */
        Descriptors(CloseDescriptor closer){
            this.closer=Objects.requireNonNull(closer);
        }
        /**
         * Records an acquired descriptor in preallocated ownership storage.
         * @param fd native descriptor already retained by its owner
         * @return the validated result retained by this operation
         */
        int add(int fd){
            fds[size++]=fd;
            return fd;
        }
        /**
         * Consumes one owned descriptor before invoking its once-only close.
         * @param fd native descriptor already retained by its owner
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        void closeOne(int fd)throws IOException {
            int at=0;
            while(at<size&&fds[at]!=fd)at++;
            if(at==size)throw new IllegalStateException("not owned");
            fds[at]=fds[--size];
            closer.close(fd);
        }
        public void close()throws IOException {
            IOException failure=null;
            while(size>0){
                int fd=fds[--size];
                try{
                    closer.close(fd);
                }
                catch(IOException e){
                    if(failure==null)failure=e;
                    else failure.addSuppressed(e);
                }
            }
            if(closer instanceof Native.CloseOperation nativeClose)nativeClose.releaseStorage();
            if(failure!=null)throw failure;
        }
        /**
         * Captures a newly acquired descriptor before constructing a Java result.
         * @param name native libc symbol name
         * @param first first integer native argument
         * @param second second integer native argument
         * @param third third integer native argument
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        int acquire(String name,int first,int second,int third)throws IOException {
            try(var a=Arena.ofConfined()){
                var state=a.allocate(Native.ERRORS);
                int fd;
                try{
                    fd=(int)Native.CALLS.get(name).invokeExact(state,first,second,third);
                }
                catch(Throwable t){
                    throw new IOException(name,t);
                }
                if(fd>=0)return add(fd);
                throw new IOException(name+" errno="+state.get(ValueLayout.JAVA_INT,Native.ERRNO));
            }
        }
        /**
         * Captures an accepted socket before constructing a Java result.
         * @param server owned listening socket
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        Native.Outcome accept(int server)throws IOException {
            try(var a=Arena.ofConfined()){
                var state=a.allocate(Native.ERRORS);
                int fd;
                try{
                    fd=(int)Native.CALLS.get("accept4").invokeExact(state,server,MemorySegment.NULL,MemorySegment.NULL,524288|2048);
                }
                catch(Throwable t){
                    throw new IOException("accept4",t);
                }
                if(fd>=0)add(fd);
                return new Native.Outcome(fd,state.get(ValueLayout.JAVA_INT,Native.ERRNO));
            }
        }
    }
    /** Retains a failed-acquisition cleanup owner when endpoint cleanup itself failed. */
    public static final class CleanupFailure extends IOException {
        /** Exception serialization format identifier. */
        private static final long serialVersionUID = 1L;
        /** Cleanup owner retained after failed acquisition. */
        private final AutoCloseable owner;
        /**
         * Creates the owner before acquiring its native resources.
         * @param original primary acquisition failure
         * @param cleanup cleanup failure to retain
         * @param owner retained cleanup owner
         */
        private CleanupFailure(Throwable original, IOException cleanup, AutoCloseable owner) {
            super("Stream acquisition failed with retained cleanup ownership", original);
            addSuppressed(cleanup);
            this.owner = owner;
        }
        /**
         * Retries remaining cleanup; already-closed native descriptors are never retried.
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        public void retryCleanup() throws IOException {
            try {
                owner.close();
            }
            catch (IOException failure) {
                throw failure;
            }
            catch (Exception failure) {
                throw new IOException("Retained stream cleanup failed", failure);
            }
        }
    }
    /** Implements the verified Linux x64 glibc descriptor transport ABI. */
    private static final class Native {
        /** Creates the owner before acquiring its native resources. */
        private Native() {
        }
        /** Rejects unverified platforms before resolving native operations. */
        static final Linker LINKER=checkedLinker();
        /**
         * Rejects unverified platforms before resolving native operations.
         * @return the validated result retained by this operation
         */
        static Linker checkedLinker() {
            if (!System.getProperty("os.name").equals("Linux") || !Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))
            throw new UnsupportedOperationException("Borrowed stream ABI is verified only on Linux x64");
            var linker=Linker.nativeLinker();
            if (linker.defaultLookup().find("gnu_get_libc_version").isEmpty() || linker.canonicalLayouts().get("long").byteSize()!=8 || ValueLayout.ADDRESS.byteSize()!=8)
            throw new UnsupportedOperationException("Borrowed streams require verified glibc LP64");
            return linker;
        }
        /** Default libc symbol lookup for this process. */
        static final SymbolLookup LIB=LINKER.defaultLookup();
        /** Exact libc downcall handles indexed by symbol name. */
        static final Map<String,MethodHandle> CALLS=new HashMap<>();
        /** Native captured-error memory layout. */
        static final MemoryLayout ERRORS=Linker.Option.captureStateLayout();
        /** Offset of captured errno in that layout. */
        static final long ERRNO=ERRORS.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        /**
         * Binds an exact libc signature with captured errno.
         * @param name native libc symbol name
         * @param result native return layout
         * @param args exact native argument layouts or values
         * @return the validated result retained by this operation
         */
        static MethodHandle bind(String name,MemoryLayout result,MemoryLayout...args) {
            return LINKER.downcallHandle(LIB.find(name).orElseThrow(),FunctionDescriptor.of(result,args),Linker.Option.captureCallState("errno"));
        }
        static {
            var I=ValueLayout.JAVA_INT;
            var L=ValueLayout.JAVA_LONG;
            var P=ValueLayout.ADDRESS;
            CALLS.put("getsockopt",bind("getsockopt",I,I,I,I,P,P));
            CALLS.put("read",bind("read",L,I,P,L));
            CALLS.put("send",bind("send",L,I,P,L,I));
            CALLS.put("socket",bind("socket",I,I,I,I));
            CALLS.put("bind",bind("bind",I,I,P,I));
            CALLS.put("listen",bind("listen",I,I,I));
            CALLS.put("accept4",bind("accept4",I,I,P,P,I));
            CALLS.put("connect",bind("connect",I,I,P,I));
            CALLS.put("close",bind("close",I,I));
            CALLS.put("poll",bind("poll",I,P,L,I));
            CALLS.put("sendmsg",bind("sendmsg",L,I,P,I));
            CALLS.put("recvmsg",bind("recvmsg",L,I,P,I));
            CALLS.put("dup2",bind("dup2",I,I,I));
            CALLS.put("fcntl",bind("fcntl",I,I,I,I));
            CALLS.put("isatty",bind("isatty",I,I));
            CALLS.put("getsid",bind("getsid",I,I));
            CALLS.put("getpgrp",bind("getpgrp",I));
            CALLS.put("tcgetsid",bind("tcgetsid",I,I));
            CALLS.put("tcgetpgrp",bind("tcgetpgrp",I,I));
            CALLS.put("tcgetattr",bind("tcgetattr",I,I,P));
        }
        /** Preallocates captured errno storage before any descriptor can be acquired by its owner. */
        static final class CloseOperation implements CloseDescriptor {
            /** Confined captured-error storage allocated before descriptor acquisition. */
            private final Arena arena=Arena.ofConfined();
            /** Preallocated captured errno memory for descriptor close. */
            private final MemorySegment state;
            /** Whether cleanup or installation consumption already ended this owner operation. */
            private boolean closed;
            /** Creates the owner before acquiring its native resources. */
            CloseOperation() {
                try{
                    state=arena.allocate(ERRORS);
                }
                catch(RuntimeException|Error failure){
                    arena.close();
                    throw failure;
                }
            }
            public void close(int fd)throws IOException {
                int answer;
                try{
                    answer=(int)CALLS.get("close").invokeExact(state,fd);
                }
                catch(Throwable failure){
                    throw new IOException("Native close invocation failed",failure);
                }
                if(answer<0)throw new IOException("Native close failed with errno="+state.get(ValueLayout.JAVA_INT,ERRNO));
            }
            /** Closes captured-error storage after every descriptor number has been consumed. */
            void releaseStorage(){
                if(!closed){
                    closed=true;
                    arena.close();
                }
            }
        }
        /**
         * Carries a non-acquisition native result and its captured error.
         * @param value native return value
         * @param errno captured native error number
         */
        record Outcome(long value,int errno) {
        }
        /**
         * Invokes a non-acquisition native operation and captures its result.
         * @param name native libc symbol name
         * @param args exact native argument layouts or values
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static Outcome call(String name,Object...args)throws IOException {
            try(var arena=Arena.ofConfined()) {
                var state=arena.allocate(ERRORS);
                var values=new ArrayList<Object>();
                values.add(state);
                Collections.addAll(values,args);
                Object answer;
                try {
                    answer=CALLS.get(name).invokeWithArguments(values);
                }
                catch(Throwable t){
                    throw new IOException(name,t);
                }
                return new Outcome(((Number)answer).longValue(),state.get(ValueLayout.JAVA_INT,ERRNO));
            }
        }
        /**
         * Requires a successful native operation result.
         * @param name native libc symbol name
         * @param args exact native argument layouts or values
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static int require(String name,Object...args)throws IOException {
            var r=call(name,args);
            if(r.value()<0)throw new IOException(name+" errno="+r.errno());
            return Math.toIntExact(r.value());
        }
        /**
         * Validates a positive duration and computes its monotonic deadline.
         * @param timeout positive bounded control timeout
         * @return the validated result retained by this operation
         */
        static long deadline(Duration timeout) {
            if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("positive timeout");
            return System.nanoTime()+timeout.toNanos();
        }
        /**
         * Waits in bounded slices while respecting interruption and the handoff deadline.
         * @param fd native descriptor already retained by its owner
         * @param event required poll readiness event
         * @param deadline monotonic operation deadline
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static void ready(int fd,short event,long deadline)throws IOException {
            try(var a=Arena.ofConfined()){
                var poll=a.allocate(8,4);
                poll.set(ValueLayout.JAVA_INT,0,fd);
                poll.set(ValueLayout.JAVA_SHORT,4,event);
                while(true){
                    if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Stream handoff interrupted before release");
                    long left=deadline-System.nanoTime();
                    if(left<=0)throw new IOException("Control deadline before release");
                    var r=call("poll",poll,1L,(int)Math.min(25,Math.max(1,left/1000000)));
                    if(r.value()>0)return;
                    if(r.value()<0&&r.errno()!=4)throw new IOException("poll errno="+r.errno());
                }
            }
        }
        /**
         * Builds a bounded pathname Unix socket address.
         * @param a confined native memory owner
         * @param endpoint owned pathname Unix socket endpoint
         * @return the validated result retained by this operation
         */
        static MemorySegment address(Arena a,Path endpoint) {
            byte[] b=endpoint.toString().getBytes(StandardCharsets.UTF_8);
            if(b.length==0||b.length>=108)throw new IllegalArgumentException("Unix pathname length");
            var s=a.allocate(110,2);
            s.set(ValueLayout.JAVA_SHORT,0,(short)1);
            MemorySegment.copy(b,0,s,ValueLayout.JAVA_BYTE,2,b.length);
            return s;
        }
        /** Retains the state required by this owned native operation. */
        public static final class Listener implements Transfer {
            /** Creates the owner before acquiring its native resources. */
            private final Descriptors owned=new Descriptors();
            /** Exclusively owned Unix socket pathname. */
            private final Path endpoint;
            /** Whether this owner successfully created its endpoint pathname. */
            private boolean bound;
            /** Filesystem identity captured for the owned endpoint. */
            private Object endpointIdentity;
            /**
             * Creates the owner before acquiring its native resources.
             * @param endpoint owned pathname Unix socket endpoint
             */
            private Listener(Path endpoint){
                this.endpoint=Objects.requireNonNull(endpoint);
            }
            /**
             * Acquires a listening socket and retains any partial cleanup ownership.
             * @param endpoint owned pathname Unix socket endpoint
             * @return the validated result retained by this operation
             * @throws java.io.IOException if validation, native access or owned cleanup fails
             */
            public static Listener open(Path endpoint)throws IOException {
                Listener owner=new Listener(endpoint);
                try(var a=Arena.ofConfined()) {
                    int fd=owner.owned.acquire("socket",1,1|524288|2048,0);
                    var address=address(a,endpoint);
                    owner.bindOwned(fd,address,2+endpoint.toString().getBytes(StandardCharsets.UTF_8).length+1);
                    owner.endpointIdentity=Files.readAttributes(endpoint,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                    require("listen",fd,1);
                    return owner;
                }
                catch(IOException|RuntimeException|Error e){
                    try{
                        owner.close();
                    }
                    catch(IOException cleanup){
                        throw new CleanupFailure(e,cleanup,owner);
                    }
                    throw e;
                }
            }
            /**
             * Records successful pathname binding before publishing a Java result.
             * @param fd native descriptor already retained by its owner
             * @param address required operation input
             * @param length required operation input
             * @throws java.io.IOException if validation, native access or owned cleanup fails
             */
            private void bindOwned(int fd,MemorySegment address,int length)throws IOException {
                try(var arena=Arena.ofConfined()) {
                    var state=arena.allocate(ERRORS);
                    int answer;
                    try{
                        answer=(int)CALLS.get("bind").invokeExact(state,fd,address,length);
                    }
                    catch(Throwable failure){
                        throw new IOException("Native bind invocation failed",failure);
                    }
                    if(answer==0){
                        bound=true;
                        return;
                    }
                    throw new IOException("Native socket bind failed with errno="+state.get(ValueLayout.JAVA_INT,ERRNO));
                }
            }
            public void sendBorrowed(Duration timeout, Binding binding)throws IOException {
                long end=deadline(timeout);
                int server=owned.fds[0];
                try(var accepted=new Descriptors()) {
                    int socket;
                    while(true){
                        ready(server,(short)1,end);
                        var r=accepted.accept(server);
                        if(r.value()>=0){
                            socket=(int)r.value();
                            break;
                        }
                        if(r.errno()!=4&&r.errno()!=11)throw new IOException("accept errno="+r.errno());
                    }
                    Peer connected=peer(socket);
                    binding.requireGate(connected,readFrame(socket,end));
                    transfer(socket,binding.frame(),true,end);
                    try(var a=Arena.ofConfined()){
                        var header=message(a);
                        var control=header.get(ValueLayout.ADDRESS,32).reinterpret(32);
                        control.set(ValueLayout.JAVA_LONG,0,28);
                        control.set(ValueLayout.JAVA_INT,8,1);
                        control.set(ValueLayout.JAVA_INT,12,1);
                        for(int i=0;i<3;i++)control.set(ValueLayout.JAVA_INT,16+4*i,i);
                        header.set(ValueLayout.JAVA_LONG,40,32);
                        while(true){
                            ready(socket,(short)4,end);
                            var r=call("sendmsg",socket,header,0x4000);
                            if(r.value()==1)break;
                            if(r.value()<0&&(r.errno()==4||r.errno()==11))continue;
                            throw new IOException("sendmsg value="+r.value()+" errno="+r.errno());
                        }
                    }
                }
            }
            public void close()throws IOException {
                IOException failure=null;
                try{
                    owned.close();
                }
                catch(IOException e){
                    failure=e;
                }
                if(bound){
                    try{
                        if(Files.exists(endpoint,LinkOption.NOFOLLOW_LINKS)) {
                            Object current=Files.readAttributes(endpoint,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
                            if(endpointIdentity==null || !endpointIdentity.equals(current))throw new IOException("Owned endpoint identity changed; foreign path retained");
                            Files.delete(endpoint);
                        }
                        bound=false;
                    }
                    catch(IOException e){
                        if(failure==null)failure=e;
                        else failure.addSuppressed(e);
                    }
                }
                if(failure!=null)throw failure;
            }
        }
        /**
         * Verifies socket peer credentials against a stable live kernel identity.
         * The privileged receiver separately verifies the wrapper's PID namespace before sending its authorization
         * frame. The unprivileged wrapper cannot read the privileged gate's namespace link; it authenticates that
         * gate using root socket credentials and the action, attempt and capability frame instead.
         * @param fd native descriptor already retained by its owner
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static Peer peer(int fd)throws IOException {
            try(var a=Arena.ofConfined()) {
                var credentials=a.allocate(12,4);
                var length=a.allocate(ValueLayout.JAVA_INT);
                length.set(ValueLayout.JAVA_INT,0,12);
                require("getsockopt",fd,1,17,credentials,length);
                if(length.get(ValueLayout.JAVA_INT,0)!=12)throw new IOException("Invalid peer credential size");
                long pid=Integer.toUnsignedLong(credentials.get(ValueLayout.JAVA_INT,0));
                Peer live=Peer.capture(pid);
                if(live.uid()!=credentials.get(ValueLayout.JAVA_INT,4)||live.gid()!=credentials.get(ValueLayout.JAVA_INT,8))
                throw new IOException("Socket peer credentials changed during identity verification");
                if (!live.equals(Peer.capture(pid)))
                    throw new IOException("Socket peer identity changed during verification");
                return live;
            }
        }
        /**
         * Receives exactly one bounded authorization frame.
         * @param fd native descriptor already retained by its owner
         * @param end monotonic deadline in nanoseconds
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static byte[] readFrame(int fd,long end)throws IOException {
            byte[] actual=new byte[FRAME_BYTES];
            transfer(fd,actual,false,end);
            return actual;
        }
        /**
         * Exchanges and validates the exact authorized frame before any rights transfer.
         * @param fd native descriptor already retained by its owner
         * @param expected authorized frame or terminal context
         * @param initiator whether this peer initiates the bounded handshake
         * @param end monotonic deadline in nanoseconds
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static void exchange(int fd,byte[] expected,boolean initiator,long end)throws IOException {
            if(initiator)transfer(fd,expected,true,end);
            byte[] actual=new byte[FRAME_BYTES];
            transfer(fd,actual,false,end);
            if(!MessageDigest.isEqual(expected,actual))throw new IOException("Stream handshake has another action, attempt or capability");
            if(!initiator)transfer(fd,expected,true,end);
        }
        /**
         * Transfers a bounded authorization buffer with deadline and interruption checks.
         * @param fd native descriptor already retained by its owner
         * @param bytes bounded authorization buffer
         * @param sending whether to send rather than receive bytes
         * @param end monotonic deadline in nanoseconds
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static void transfer(int fd,byte[] bytes,boolean sending,long end)throws IOException {
            try(var a=Arena.ofConfined()) {
                var buffer=a.allocate(bytes.length);
                if(sending)MemorySegment.copy(bytes,0,buffer,ValueLayout.JAVA_BYTE,0,bytes.length);
                long offset=0;
                while(offset<bytes.length) {
                    ready(fd,(short)(sending?4:1),end);
                    var answer=sending?call("send",fd,buffer.asSlice(offset),bytes.length-offset,0x4000):call("read",fd,buffer.asSlice(offset),bytes.length-offset);
                    if(answer.value()<0&&(answer.errno()==4||answer.errno()==11))continue;
                    if(answer.value()<=0)throw new IOException("Stream peer disconnected during authorization");
                    offset+=answer.value();
                }
                if(!sending)MemorySegment.copy(buffer,ValueLayout.JAVA_BYTE,0,bytes,0,bytes.length);
            }
        }
        /**
         * Preallocates the three-descriptor ancillary receive layout.
         * @param a confined native memory owner
         * @return the validated result retained by this operation
         */
        static MemorySegment message(Arena a) {
            var header=a.allocate(56,8);
            var data=a.allocate(1);
            data.set(ValueLayout.JAVA_BYTE,0,(byte)90);
            var vector=a.allocate(16,8);
            vector.set(ValueLayout.ADDRESS,0,data);
            vector.set(ValueLayout.JAVA_LONG,8,1);
            header.set(ValueLayout.ADDRESS,16,vector);
            header.set(ValueLayout.JAVA_LONG,24,1);
            header.set(ValueLayout.ADDRESS,32,a.allocate(32,8));
            header.set(ValueLayout.JAVA_LONG,40,32);
            return header;
        }
        /**
         * Claims every delivered descriptor before constructing results or reporting malformed ancillary data.
         * @param owned preallocated received descriptor owner
         * @param fd native descriptor already retained by its owner
         * @param header bounded ancillary message layout
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        static Outcome receiveOwned(Descriptors owned,int fd,MemorySegment header)throws IOException {
            try(var a=Arena.ofConfined()){
                var state=a.allocate(ERRORS);
                long answer;
                try {
                    answer=(long)CALLS.get("recvmsg").invokeExact(state,fd,header,0x40000000);
                }
                catch(Throwable t){
                    throw new IOException("recvmsg",t);
                }
                // Native writes references into this preallocated buffer. Claim them before constructing result/errors.
                if(answer>=0){
                    long bytes=header.get(ValueLayout.JAVA_LONG,40);
                    var control=header.get(ValueLayout.ADDRESS,32).reinterpret(32);
                    for(long at=0;at+16<=Math.min(bytes,32);){
                        long length=control.get(ValueLayout.JAVA_LONG,at);
                        if(length<16||at+length>Math.min(bytes,32))break;
                        if(control.get(ValueLayout.JAVA_INT,at+8)==1&&control.get(ValueLayout.JAVA_INT,at+12)==1)
                        for(long pos=at+16;pos+4<=at+length;pos+=4)owned.add(control.get(ValueLayout.JAVA_INT,pos));
                        at+=(length+7)&~7L;
                    }
                }
                return new Outcome(answer,state.get(ValueLayout.JAVA_INT,ERRNO));
            }
        }
        /**
         * Authenticates the registered wrapper and acquires its three descriptor references.
         * @param endpoint owned pathname Unix socket endpoint
         * @param timeout positive bounded control timeout
         * @param binding authenticated action, peer and launch receipt
         * @return the validated result retained by this operation
         * @throws java.io.IOException if validation, native access or owned cleanup fails
         */
        public static Received receive(Path endpoint,Duration timeout,Binding binding)throws IOException {
            var result=new Received();
            try(var socket=new Descriptors();var a=Arena.ofConfined()) {
                int fd=socket.acquire("socket",1,1|524288|2048,0);
                var address=address(a,endpoint);
                // Local cooperative listener is published before this call, so AF_UNIX connect needs no path polling.
                require("connect",fd,address,2+endpoint.toString().getBytes(StandardCharsets.UTF_8).length+1);
                long end=deadline(timeout);
                Peer listener=peer(fd);
                if(!listener.equals(binding.wrapper))throw new IOException("Stream listener does not match registered wrapper identity");
                binding.wrapper.requireLive();
                exchange(fd,binding.frame(),true,end);
                var header=message(a);
                Outcome received;
                while(true){
                    ready(fd,(short)1,end);
                    header.set(ValueLayout.JAVA_LONG,40,32);
                    header.set(ValueLayout.JAVA_INT,48,0);
                    received=receiveOwned(result.owned,fd,header);
                    if(received.value()<0&&(received.errno()==4||received.errno()==11))continue;
                    break;
                }
                if(received.value()<0)throw new IOException("recvmsg errno="+received.errno());
                long bytes=header.get(ValueLayout.JAVA_LONG,40);
                var control=header.get(ValueLayout.ADDRESS,32).reinterpret(32);
                if(received.value()!=1||(header.get(ValueLayout.JAVA_INT,48)&~0x40000000)!=0||bytes!=32||control.get(ValueLayout.JAVA_LONG,0)!=28||result.owned.size!=3)
                throw new IOException("Expected exactly3 untruncated rights before release: bytes="+bytes+" flags="+header.get(ValueLayout.JAVA_INT,48)+" count="+result.owned.size);
                return result;
            }
            catch(IOException|RuntimeException|Error e){
                try{
                    result.close();
                }
                catch(IOException cleanup){
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
        /** Owns the imported references until installation or terminal cleanup. */
        public static final class Received implements AutoCloseable {
            /** Creates the owner before acquiring its native resources. */
            private final Descriptors owned=new Descriptors();
            /** Whether installation has begun, including an unsuccessful attempt. */
            private boolean installed;
            /** Retains the state required by this owned native operation. */
            private Received(){
            }
            /**
             * Installs the pending descriptors in the dedicated gate.
             * @throws java.io.IOException if validation, native access or owned cleanup fails
             */
            public void install()throws IOException {
                if(installed||owned.size!=3)throw new IllegalStateException("not a pending triple");
                installed=true;
                int[] original=Arrays.copyOf(owned.fds,3);
                int[] staged=new int[3];
                for(int i=0;i<3;i++)staged[i]=owned.acquire("fcntl",original[i],1030,3);
                for(int fd:original)owned.closeOne(fd);
                for(int i=0;i<3;i++)require("dup2",staged[i],i);
                owned.close();
            }
            public void close()throws IOException {
                owned.close();
            }
        }
    }
}
