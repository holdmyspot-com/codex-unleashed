package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the original privileged instrumentation owner inside an authenticated action PID/mount namespace.
 * Only the launching host's original anonymous pipes carry controls. Native callbacks only copy events; this
 * process's main thread performs protocol and native transitions. Whole-workload termination remains host authority.
 */
final class LinuxBazelHelperMain
{
    /** Maximum encoded frame bytes retained by either pipe endpoint; oversized bursts fail closed. */
    static final int MAX_FRAME_BYTES = 1_048_576;
    /** Fixed correlation schema for this package-owned helper and its host. */
    static final String SCHEMA = "codex-bazel-namespace-helper-v1";

    /**
     * Prevents construction of the process entrypoint.
     */
    private LinuxBazelHelperMain() {}

    /**
     * Serves only the package-owned helper mode with an independently checked namespace-local proc view.
     * @param arguments exact singleton mode argument; no workload executable or native library is selected here
     * @throws Exception if namespace visibility, framing or native ownership cannot be established
     */
    public static void main(String[] arguments) throws Exception
    {
        if (arguments.length != 1 || !arguments[0].equals("--namespace-helper"))
            throw new IllegalArgumentException("Expected the package-owned namespace helper mode");
        requireLocalProc();
        OutputStream originalControl = System.out;
        System.setOut(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            serve(scope, System.in, originalControl, configuration -> new NativeBackend(scope, configuration));
        }
    }

    /**
     * Requires proc lookup by this JVM's local PID to identify this same process before Frida can start.
     * @throws IOException if proc belongs to another PID view or root credentials are unavailable
     */
    private static void requireLocalProc() throws IOException
    {
        long pid = ProcessHandle.current().pid();
        String self = Files.readString(Path.of("/proc/self/stat"));
        String local = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        if (!self.startsWith(pid + " ") || !local.startsWith(pid + " ") ||
            !Files.isSameFile(Path.of("/proc/self"), Path.of("/proc", Long.toString(pid))))
            throw new IOException("Namespace helper requires its actual namespace-local proc mount");
        String status = Files.readString(Path.of("/proc/self/status"));
        String uid = status.lines().filter(line -> line.startsWith("Uid:")).findFirst()
            .orElseThrow(() -> new IOException("Namespace helper proc credentials are absent"));
        if (!List.of(uid.substring(4).strip().split("\\s+")).equals(List.of("0", "0", "0", "0")))
            throw new IOException("Namespace instrumentation helper requires retained root credentials");
    }

    /**
     * Runs the actual bounded framed service using a resource-free backend factory.
     * INIT is consumed once before any native owner is acquired. A failed command latches authority withdrawal;
     * only STOP and CLOSE remain accepted, so an uncertain native effect is never replayed.
     * @param scope borrowed shared serialization values
     * @param incoming owned host input borrowed until the service returns
     * @param outgoing owned host output borrowed until the service returns
     * @param factory creates one original resource-free native owner from host configuration
     * @throws IOException if framing, command identity, native delivery or final cleanup fails
     */
    static void serve(ToolingScope scope, InputStream incoming, OutputStream outgoing, BackendFactory factory)
        throws IOException
    {
        JsonMapper json = scope.getAllocationEnvelopeMapper();
        DataInputStream input = new DataInputStream(incoming);
        DataOutputStream output = new DataOutputStream(outgoing);
        write(output, json, json.createObjectNode().put("schema", SCHEMA).put("kind", "hello")
            .put("localPid", Long.toString(ProcessHandle.current().pid())));
        Backend backend = null;
        IOException operationalFailure = null;
        long next = 1;
        boolean closed = false;
        boolean closeAttempted = false;
        while (!closed)
        {
            ObjectNode request = read(input, json);
            long id = positive(request, "id");
            if (id != next) throw new IOException("Namespace helper command ID differs from its sole next ID");
            next = Math.incrementExact(next);
            String operation = text(request, "operation");
            ObjectNode response = json.createObjectNode().put("schema", SCHEMA).put("id", Long.toString(id));
            try
            {
                if (operationalFailure != null && !operation.equals("stop") && !operation.equals("close"))
                    throw new IOException("Namespace helper command authority was withdrawn", operationalFailure);
                if (operation.equals("init"))
                {
                    if (backend != null) throw new IOException("Namespace helper INIT cannot be repeated");
                    backend = Objects.requireNonNull(factory.create(request), "backend");
                }
                else
                {
                    if (backend == null) throw new IOException("Namespace helper has not received INIT");
                    switch (operation)
                    {
                        case "prepareHeld" -> backend.prepareHeld();
                        case "install" -> backend.install();
                        case "resumeImage" -> backend.resumeImage();
                        case "pump" -> backend.pump();
                        case "installHeld" -> backend.installHeld(request);
                        case "resumeHeld" -> backend.resumeHeld(request);
                        case "grant" -> backend.grant(new AllocationWaits.Wait(positive(request, "installation"),
                            positive(request, "sequence")));
                        case "stop" -> backend.stop();
                        case "close" ->
                        {
                            if (closeAttempted) throw new IOException("Original native CLOSE cannot be replayed");
                            if (!request.path("executionImpossible").isBoolean() || !request.path("executionImpossible").asBoolean())
                                throw new IOException("Native cleanup requires the original host execution barrier");
                            closeAttempted = true;
                            backend.close();
                            closed = true;
                        }
                        default -> throw new IOException("Unknown namespace helper operation: " + operation);
                    }
                }
                response.put("ok", true);
                if (backend != null)
                {
                    Snapshot snapshot = backend.drain();
                    response.set("frames", json.valueToTree(snapshot.frames()));
                    response.set("held", json.valueToTree(snapshot.held()));
                    response.set("retired", json.valueToTree(snapshot.retired()));
                }
            }
            catch (IOException | RuntimeException failure)
            {
                if (operationalFailure == null)
                    operationalFailure = new IOException("Original namespace helper operation failed: " + operation, failure);
                response.put("ok", false).put("error", operationalFailure.getMessage())
                    .put("cause", causes(operationalFailure.getCause()));
            }
            write(output, json, response);
        }
    }

    /**
     * Preserves a bounded original native cause chain across the owned helper transport.
     * @param failure original operational cause retained after authority withdrawal
     * @return bounded class and message chain, without discarding the selecting native failure
     */
    private static String causes(Throwable failure)
    {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; failure != null && depth < 8 && result.length() < 8192; depth++)
        {
            if (!result.isEmpty()) result.append("; caused by ");
            result.append(failure.getClass().getName()).append(": ").append(failure.getMessage());
            failure = failure.getCause();
        }
        return result.substring(0, Math.min(result.length(), 8192));
    }

    /**
     * Reads one complete bounded JSON object; EOF and partial frames are failures, never empty observations.
     * @param input original anonymous pipe input
     * @param json borrowed scope mapper
     * @return one complete schema-matching request object
     * @throws IOException if framing, JSON shape or schema differs
     */
    static ObjectNode read(DataInputStream input, JsonMapper json) throws IOException
    {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("Namespace helper frame size is outside its bound");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Namespace helper frame ended before its declared length");
        JsonNode value = json.readTree(bytes);
        if (!(value instanceof ObjectNode object) || !SCHEMA.equals(text(object, "schema")))
            throw new IOException("Namespace helper frame schema differs");
        return object;
    }

    /**
     * Writes one encoded bounded frame once and flushes its original output without retrying a partial effect.
     * @param output original anonymous pipe output
     * @param json borrowed scope mapper
     * @param value complete protocol object
     * @throws IOException if encoding exceeds the bound or output has uncertain effects
     */
    static void write(DataOutputStream output, JsonMapper json, ObjectNode value) throws IOException
    {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) throw new IOException("Namespace helper response exceeds its frame bound");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    /**
     * Reads one required textual field without scalar coercion.
     * @param value received object
     * @param name required field name
     * @return exact received string
     * @throws IOException if the field is absent or has another JSON type
     */
    static String text(ObjectNode value, String name) throws IOException
    {
        JsonNode member = value.path(name);
        if (!member.isString()) throw new IOException("Namespace helper field must be text: " + name);
        return member.asString();
    }

    /**
     * Parses canonical positive decimal identities without accepting numeric JSON coercion.
     * @param value received object
     * @param name required textual field name
     * @return positive signed-long identity
     * @throws IOException if the field is malformed, nonpositive or out of range
     */
    static long positive(ObjectNode value, String name) throws IOException
    {
        long result = nonnegative(value, name);
        if (result == 0) throw new IOException("Namespace helper identity must be positive: " + name);
        return result;
    }

    /**
     * Parses canonical nonnegative decimal values without JSON scalar coercion.
     * @param value received object
     * @param name required textual field name
     * @return nonnegative signed-long value
     * @throws IOException if the field is malformed, negative or out of range
     */
    static long nonnegative(ObjectNode value, String name) throws IOException
    {
        String encoded = text(value, name);
        try
        {
            long result = Long.parseLong(encoded);
            if (result < 0 || !Long.toString(result).equals(encoded)) throw new NumberFormatException("noncanonical identity");
            return result;
        }
        catch (NumberFormatException failure) { throw new IOException("Namespace helper identity is invalid: " + name, failure); }
    }

    /**
     * Indicates whether a copied event identifies the original attachment's expected held self-exec.
     * @param pid namespace-local original attachment identity
     * @param execution expected held executable and native arguments
     * @param launchExecution original resolved launch executable and native arguments
     * @param child copied original manager event
     * @return {@code true} for the expected self-exec event
     */
    static boolean matchesInitialExec(long pid, ObjectNode execution, ObjectNode launchExecution,
        FridaRuntime.Child child)
    {
        boolean expectedExecutable = Objects.equals(child.path(), launchExecution.path("executable").asString()) ||
            Objects.equals(child.path(), execution.path("executable").asString());
        return child.pid() == pid && child.parentPid() == pid && child.origin() == 1 && expectedExecutable;
    }

    /**
     * Rejects libc introduced by instrumentation as proof of the original image's allocator.
     * The packaged hook covers the original glibc ABI, so a static or musl image cannot become
     * ready merely because Frida injected a library exporting malloc into that process.
     * @param image retained descriptor for the original held executable before attachment
     * @throws IOException if its original ELF image has no supported glibc interpreter
     */
    static void requireGlibcAllocatorImage(FileChannel image) throws IOException
    {
        java.nio.ByteBuffer header = imageBytes(image, 0, 64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != 0x464c457f || header.get(4) != 2 || header.get(5) != 1)
            throw new IOException("Allocation hooks require the original little-endian ELF64 image");
        int machine = Short.toUnsignedInt(header.getShort(18));
        String loader = switch (machine)
        {
            case 183 -> "ld-linux-aarch64.so.1";
            case 62 -> "ld-linux-x86-64.so.2";
            default -> throw new IOException("Original allocation image architecture is unsupported");
        };
        long offset = header.getLong(32);
        int entrySize = Short.toUnsignedInt(header.getShort(54));
        int count = Short.toUnsignedInt(header.getShort(56));
        if (offset < 64 || entrySize != 56 || count == 0 ||
            offset > image.size() - (long) count * entrySize)
            throw new IOException("Original ELF program header table is malformed");
        for (int index = 0; index < count; ++index)
        {
            java.nio.ByteBuffer entry = imageBytes(image, offset + (long) index * entrySize, entrySize)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (entry.getInt(0) != 3) continue;
            long length = entry.getLong(32);
            if (length < 2 || length > 4096) throw new IOException("Original ELF interpreter length is invalid");
            byte[] bytes = imageBytes(image, entry.getLong(8), (int) length).array();
            if (bytes[bytes.length - 1] != 0) throw new IOException("Original ELF interpreter lacks its delimiter");
            String interpreter = new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            if (interpreter.indexOf(0) >= 0 || !interpreter.startsWith("/") ||
                !Path.of(interpreter).getFileName().toString().equals(loader))
                throw new IOException("Original image does not declare the supported glibc loader");
            return;
        }
        throw new IOException("Original static image has no glibc allocator contract; injected libc readiness is insufficient");
    }

    /**
     * Reads a bounded region from the retained original executable without changing descriptor position.
     * @param image original held image descriptor
     * @param offset nonnegative byte offset
     * @param length bounded requested byte count
     * @return complete region, ready for absolute field reads
     * @throws IOException if the original image is truncated or its range is invalid
     */
    private static java.nio.ByteBuffer imageBytes(FileChannel image, long offset, int length) throws IOException
    {
        if (offset < 0 || length < 0 || offset > image.size() - length)
            throw new IOException("Original ELF image range is invalid");
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(length);
        while (bytes.hasRemaining())
        {
            int count = image.read(bytes, offset + bytes.position());
            if (count <= 0) throw new IOException("Original ELF image ended before its declared range");
        }
        return bytes;
    }

    /**
     * Creates the packaged leader's launch program from host-approved native arguments and environment.
     * @param scope borrowed serialization values
     * @param launchExecution exact host-approved launch execution, borrowed without modification
     * @return complete leader program for the original held attachment
     * @throws IOException if the packaged template is absent or its insertion point differs
     */
    static String leaderProgram(ToolingScope scope, ObjectNode launchExecution) throws IOException
    {
        String template;
        try (InputStream input = AllocationHooks.class.getResourceAsStream("linux-leader.js"))
        {
            if (input == null) throw new IOException("Packaged Linux leader is unavailable");
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        template = replaceOnce(template, "Memory.allocUtf8String(launch.command[0])",
            "Memory.allocUtf8String(launch.executable)");
        // A static leader has no dynamic exports. Only these three package-selected libc functions may use
        // its preserved exact-name symbol table; workload-supplied names never select a native function.
        String resolver = """
            function trustedSymbol(name) {
              if (!['execve', 'getuid', 'getgid'].includes(name))
                throw new Error('Untrusted leader symbol: ' + name);
              try {
                return Module.getGlobalExportByName(name);
              } catch (missingExport) {
                const matches = Process.mainModule.enumerateSymbols().filter(symbol =>
                  symbol.name === name && symbol.type === 'function');
                if (matches.length !== 1 || matches[0].address.isNull())
                  throw new Error('Leader symbol must have one exact definition: ' + name);
                return matches[0].address;
              }
            }
            """;
        for (String symbol : List.of("execve", "getuid", "getgid"))
            template = replaceOnce(template, "Module.getGlobalExportByName('" + symbol + "')",
                "trustedSymbol('" + symbol + "')");
        template = replaceOnce(template, "const storage = [];", resolver + "const storage = [];");
        ObjectNode launch = scope.getLeaderJsonMapper().createObjectNode();
        launch.put("executable", text(launchExecution, "executable"));
        launch.set("command", launchExecution.path("arguments"));
        launch.set("environment", launchExecution.path("environment"));
        return replaceOnce(template, "__LAUNCH__", scope.getLeaderJsonMapper().writeValueAsString(launch));
    }

    /**
     * Specializes one known packaged-template insertion point without accepting template drift.
     * @param template original or partly specialized packaged leader program
     * @param expected exact insertion text that must occur once
     * @param replacement package-controlled specialization
     * @return specialized program
     * @throws IOException if the insertion text is absent or repeated
     */
    private static String replaceOnce(String template, String expected, String replacement) throws IOException
    {
        int first = template.indexOf(expected);
        if (first < 0 || first != template.lastIndexOf(expected))
            throw new IOException("Packaged leader insertion point must occur exactly once: " + expected);
        return template.substring(0, first) + replacement + template.substring(first + expected.length());
    }

    /**
     * Supplies the sole original helper backend before native acquisition.
     */
    @FunctionalInterface
    interface BackendFactory
    {
        /**
         * Creates a resource-free backend from the host's authenticated original pipe configuration.
         * @param configuration trusted host INIT frame
         * @return original exclusive native owner
         * @throws IOException if configuration cannot be validated
         */
        Backend create(ObjectNode configuration) throws IOException;
    }

    /**
     * Owns helper-native operations; implementations are called only by the service thread.
     */
    interface Backend
    {
        /**
         * Establishes the original held target before independent host authentication.
         * @throws IOException if held-image preparation cannot be established
         */
        void prepareHeld() throws IOException;
        /**
         * Installs the original hook and requires its matching readiness acknowledgement.
         * @throws IOException if native readiness cannot be established
         */
        void install() throws IOException;
        /**
         * Attempts the original manager's sole image resume after host authentication.
         * @throws IOException if the original sole resume has uncertain effects
         */
        void resumeImage() throws IOException;
        /**
         * Services bounded copied native records without queue callbacks.
         * @throws IOException if bounded copied-message service fails
         */
        void pump() throws IOException;
        /**
         * Installs an independently host-authorized held image without resuming it.
         * @param request exact original host image and generation authorization
         * @throws IOException if the backend does not support authenticated descendants or readiness fails
         */
        default void installHeld(ObjectNode request) throws IOException
        { throw new IOException("Authenticated held installation is unsupported"); }
        /**
         * Resumes one installed image only after a separate independent host recheck.
         * @param request exact repeated host image and generation authorization
         * @throws IOException if image readiness or sole continuation authority is absent
         */
        default void resumeHeld(ObjectNode request) throws IOException
        { throw new IOException("Authenticated held resume is unsupported"); }
        /**
         * Grants one previously observed native wait and requires its exact acknowledgement.
         * @param wait selected native wait
         * @throws IOException if permission has uncertain effects
         */
        void grant(AllocationWaits.Wait wait) throws IOException;
        /**
         * Withdraws native grant authority while retaining every native owner.
         * @throws IOException if authority withdrawal fails
         */
        void stop() throws IOException;
        /**
         * Destroys original native owners after the caller establishes actual workload zero.
         * @throws IOException if original native destruction remains uncertain
         */
        void close() throws IOException;
        /**
         * Transfers bounded copied callback records to the host without queue callbacks.
         * @return copied records since the preceding response
         */
        Snapshot drain();
    }

    /**
     * Copied helper reports, owned by one protocol response.
     * @param frames raw allocation envelopes with their original attempt/installation/local PID identities
     * @param held real manager child events, still requiring independent host authentication
     * @param retired generations whose original process birth no longer exists
     */
    record Snapshot(List<String> frames, List<FridaRuntime.Child> held, List<Long> retired)
    {
        /**
         * Copies report collections before transport serialization.
         * @param frames original allocation frames
         * @param held original copied child events
         * @param retired independently observed dead generations
         */
        Snapshot { frames = List.copyOf(frames); held = List.copyOf(held); retired = List.copyOf(retired); }
        /**
         * Copies a report batch without completed process generations.
         * @param frames original allocation frames
         * @param held copied original manager child events
         */
        Snapshot(List<String> frames, List<FridaRuntime.Child> held) { this(frames, held, List.of()); }
    }

    /**
     * Keeps the one original runtime/manager and partial scripts until the host permits teardown.
     */
    private static final class NativeBackend implements Backend
    {
        /** Borrowed helper invocation values. */
        private final ToolingScope scope;
        /** Host-approved namespace-local library path, never taken from the action. */
        private final Path producer;
        /** Exact host-approved native producer digest. */
        private final String producerSha256;
        /** Host-approved action correlation identity. */
        private final UUID attempt;
        /** Host-reserved installation generation, never reused across child lifecycles. */
        private final long installation;
        /** Independently mapped local attachment PID. */
        private final long pid;
        /** Trusted exact native argv/env and credentials for the held image. */
        private final ObjectNode execution;
        /** Original resolved launch executable with its unchanged native arguments and environment. */
        private final ObjectNode launchExecution;
        /** Explicit action-visible image mappings; null permits only the initial held image. */
        private final LinuxBazelNamespaceHooks.ActionImages imagePolicy;
        /** All acquired partial/retired hooks retained through the whole-action barrier. */
        private final Map<Long, Generation> generations = new LinkedHashMap<>();
        /** Current authenticated generation per local process, retaining actual birth. */
        private final Map<Long, Generation> currentImages = new LinkedHashMap<>();
        /** Original native held events, never treated as admission authority by themselves. */
        private final Map<Long, FridaRuntime.Child> pendingImages = new LinkedHashMap<>();
        /** Initial action isolation/birth remains pinned across root self exec. */
        private LinuxBazelNamespaceHooks.ImageObservation rootImage;
        /** Last consumed host generation, never reused after partial acquisition. */
        private long lastInstallation;
        /** Positive startup/readiness/acknowledgement budget. */
        private final Duration timeout;
        /** Helper-local native wait history; host owns separate queue publication. */
        private final AllocationWaits waits = new AllocationWaits();
        /** Bounded copied frames awaiting the next host response. */
        private final List<String> frames = new ArrayList<>();
        /** Bounded copied held events awaiting independent host admission. */
        private final List<FridaRuntime.Child> held = new ArrayList<>();
        /** Dead original generations awaiting independent host retirement verification. */
        private final List<Long> retired = new ArrayList<>();
        /** Authenticated action owner anchor outside the executable subtree. */
        private final long ownerPid, ownerBirth;
        /** Runtime retained before its first fallible native load. */
        private FridaRuntime runtime;
        /** Original manager retained before start; never replaced or recreated. */
        private FridaRuntime.Manager manager;
        /** Original prepared image attachment, retained across the target exec. */
        private FridaRuntime.Manager.Session gate;
        /** Packaged leader program retained before its first fallible load. */
        private FridaRuntime.Manager.Session.Script leader;
        /** Target allocation hook retained before attach/load/readiness. */
        private AllocationHook hook;
        /** One-shot preparation, installation and resume reservations. */
        private boolean preparedAttempted, installAttempted, resumeAttempted;
        /** Successful native held-image and hook-ready observations. */
        private boolean prepared, ready;
        /** Permanent withdrawal of future grants and installation. */
        private boolean stopped;

        /**
         * Validates immutable host configuration without native acquisition.
         * @param scope borrowed helper values
         * @param configuration trusted original host INIT
         * @throws IOException if configuration cannot be represented exactly
         */
        NativeBackend(ToolingScope scope, ObjectNode configuration) throws IOException
        {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.producer = Path.of(text(configuration, "producer"));
            this.producerSha256 = text(configuration, "producerSha256");
            this.attempt = UUID.fromString(text(configuration, "attempt"));
            this.installation = positive(configuration, "installation");
            this.pid = positive(configuration, "localPid");
            this.ownerPid = positive(configuration, "ownerLocalPid");
            this.ownerBirth = positive(configuration, "ownerBirth");
            this.timeout = Duration.ofNanos(positive(configuration, "timeoutNanos"));
            if (!producer.isAbsolute() || pid > 4294967295L || !producerSha256.matches("[0-9a-f]{64}"))
                throw new IOException("Namespace helper INIT producer identity is invalid");
            this.execution = readExecution(configuration, "execution");
            this.launchExecution = readExecution(configuration, "launchExecution");
            this.lastInstallation = installation;
            JsonNode policy = configuration.path("actionImages");
            if (policy.isMissingNode()) imagePolicy = null;
            else
            {
                if (!(policy instanceof ObjectNode object) || !object.path("runtimeRoots").isArray())
                    throw new IOException("Explicit action image policy differs");
                List<Path> roots = new ArrayList<>();
                for (JsonNode root : object.path("runtimeRoots"))
                {
                    if (!root.isString()) throw new IOException("Runtime image root must be textual");
                    roots.add(Path.of(root.asString()));
                }
                imagePolicy = new LinuxBazelNamespaceHooks.ActionImages(Path.of(text(object, "inputRoot")), roots);
            }
        }

        /**
         * Validates and copies one independently represented host execution before native acquisition.
         * @param configuration trusted INIT frame
         * @param name held or original launch execution field
         * @return owned exact execution record
         * @throws IOException if executable, native vectors or credentials cannot be represented exactly
         */
        private static ObjectNode readExecution(ObjectNode configuration, String name) throws IOException
        {
            JsonNode configuredExecution = configuration.path(name);
            if (!(configuredExecution instanceof ObjectNode object))
                throw new IOException("Namespace helper execution must be an object: " + name);
            ObjectNode execution = object.deepCopy();
            Path executable = Path.of(text(execution, "executable"));
            JsonNode arguments = execution.path("arguments");
            JsonNode environment = execution.path("environment");
            if (!executable.isAbsolute() || !executable.normalize().equals(executable) ||
                !arguments.isArray() || arguments.isEmpty() || !environment.isObject() ||
                positive(execution, "uid") > Integer.MAX_VALUE || nonnegative(execution, "gid") > Integer.MAX_VALUE)
                throw new IOException("Namespace helper held execution shape differs");
            for (JsonNode argument : arguments)
                if (!argument.isString() || argument.asString().indexOf(0) >= 0)
                    throw new IOException("Namespace helper native argv cannot be represented exactly");
            for (Map.Entry<String, JsonNode> member : environment.properties())
                if (member.getKey().isEmpty() || member.getKey().indexOf('=') >= 0 || member.getKey().indexOf(0) >= 0 ||
                    !member.getValue().isString() || member.getValue().asString().indexOf(0) >= 0)
                    throw new IOException("Namespace helper native environment cannot be represented exactly");
            return execution;
        }

        @Override public void prepareHeld() throws IOException
        {
            if (preparedAttempted || stopped) throw new IOException("Original held preparation cannot be replayed");
            preparedAttempted = true;
            if (!digest(producer).equals(producerSha256)) throw new IOException("Namespace producer bytes differ from host seal");
            runtime = new FridaRuntime(scope, producer);
            runtime.start();
            manager = runtime.newManager();
            manager.start();
            gate = manager.newSession(pid);
            gate.start();
            gate.enableChildGating();
            leader = gate.newScript(leaderProgram(scope, launchExecution));
            leader.start();
            awaitLeader("leader-script-ready:" + pid);
            leader.post(scope.getLeaderJsonMapper().createObjectNode().put("type", "execute"));
            awaitLeader("leader-enter:" + pid + ":" + pid + ":" + positive(execution, "uid") + ":" + nonnegative(execution, "gid"));
            long began = System.nanoTime();
            while (true)
            {
                Optional<FridaRuntime.Child> event = manager.pollChild();
                if (event.isPresent())
                {
                    FridaRuntime.Child child = event.orElseThrow();
                    if (!matchesInitialExec(pid, execution, launchExecution, child))
                        throw new IOException("Original target did not produce its matching held self-exec: " + child);
                    prepared = true;
                    return;
                }
                waitWithin(began, "Original target held exec was not observed");
            }
        }

        @Override public void install() throws IOException
        {
            if (!prepared || installAttempted || stopped) throw new IOException("Original hook installation cannot start");
            installAttempted = true;
            rootImage = LinuxBazelNamespaceHooks.observeImage(Path.of("/proc", Long.toString(pid)), pid, pid);
            Generation original = new Generation(installation, rootImage);
            generations.put(installation, original);
            currentImages.put(pid, original);
            installHook(original);
            hook = original.hook;
            ready = true;
        }

        /**
         * Retains and installs one native hook, copying original frames while preserving the shared action waits.
         * @param generation already retained partial image owner
         * @throws IOException if attachment, actual allocation-hook readiness or framing fails
         */
        private void installHook(Generation generation) throws IOException
        {
            generation.imageFile = FileChannel.open(Path.of("/proc", Long.toString(generation.image.localPid()), "exe"), StandardOpenOption.READ);
            AllocationHook.Connection original = AllocationHook.newNativeConnection(manager, generation.image.localPid());
            AllocationHook.Connection copied = new AllocationHook.Connection()
            {
                @Override public void start(String source) throws IOException
                {
                    try
                    {
                        original.start(source);
                        // Only the retained original image can establish allocator eligibility after real attachment.
                        requireGlibcAllocatorImage(generation.imageFile);
                    }
                    catch (IOException cause)
                    {
                        throw new IOException("Original allocation image " + generation.image.executable() +
                            " sha256=" + generation.image.sha256() + " localPid=" + generation.image.localPid() +
                            " birth=" + generation.image.birth(), cause);
                    }
                }
                @Override public Optional<String> pollMessage() throws IOException
                {
                    Optional<String> next = original.pollMessage();
                    if (next.isPresent())
                    {
                        String frame = next.orElseThrow();
                        if (frames.size() >= 64 || frame.length() > 8192)
                            throw new IOException("Namespace copied allocation frame bound exceeded");
                        frames.add(frame);
                    }
                    return next;
                }
                @Override public void post(JsonNode permission) throws IOException { original.post(permission); }
                @Override public void close() throws IOException { original.close(); }
            };
            generation.hook = new AllocationHook(scope, attempt, generation.installation, generation.image.localPid(),
                copied, waits, AllocationHook.linuxProgram(), timeout, System::nanoTime);
            generation.hook.start();
            generation.hook.awaitReady(this::copyHeld);
            generation.ready = true;
        }

        @Override public void installHeld(ObjectNode request) throws IOException
        {
            if (!ready || !resumeAttempted || stopped || imagePolicy == null)
                throw new IOException("Held descendant installation has no active explicit action policy");
            long target = positive(request, "localPid");
            FridaRuntime.Child event = pendingImages.get(target);
            if (event == null || event.parentPid() != positive(request, "eventParent") ||
                !request.path("origin").isIntegralNumber() || request.path("origin").asInt() != event.origin() ||
                !Objects.equals(event.path(), request.path("requested").isNull() ? null : text(request, "requested")))
                throw new IOException("Host held admission differs from the original manager event");
            long generationId = positive(request, "installation");
            if (generationId <= lastInstallation) throw new IOException("Held installation identity was reused or reordered");
            lastInstallation = generationId;
            LinuxBazelNamespaceHooks.ImageObservation observed = inspectLocal(target);
            requireHostImage(request, observed);
            Generation previous = currentImages.get(target);
            Generation parent = null;
            if (event.origin() == 1)
            {
                if (event.parentPid() != target) throw new IOException("Held exec event must be self-parented");
                if (previous != null)
                {
                    if (previous.image.birth() != observed.birth() || previous.image.parent() != observed.parent())
                        throw new IOException("Held exec changed its original process incarnation");
                }
                else parent = currentImages.get(observed.parent());
            }
            else if (event.origin() == 0 || event.origin() == 2)
            {
                parent = currentImages.get(event.parentPid());
                if (previous != null || parent == null || parent.image.localPid() != observed.parent())
                    throw new IOException("Held child lacks an original retained local parent");
            }
            else throw new IOException("Unknown held image origin");
            if (previous == null && parent == null) throw new IOException("Held image has no authenticated local lineage");
            if (parent != null) parent.image.requireUnchanged(inspectLocal(parent.image.localPid()));
            LinuxBazelNamespaceHooks.requireRequestedImage(Path.of("/proc", Long.toString(target)), event,
                parent == null ? null : parent.image.executable(), observed.arguments(), imagePolicy);
            if (previous != null)
            {
                previous.retired = true;
                previous.hook.stop();
                waits.retireInstallation(previous.installation);
            }
            Generation generation = new Generation(generationId, observed);
            generations.put(generationId, generation);
            generation.parent = parent;
            installHook(generation);
            observed.requireUnchanged(inspectLocal(target));
            if (parent != null) parent.image.requireUnchanged(inspectLocal(parent.image.localPid()));
        }

        @Override public void resumeHeld(ObjectNode request) throws IOException
        {
            long generationId = positive(request, "installation");
            Generation generation = generations.get(generationId);
            if (stopped || generation == null || !generation.ready || generation.resumeAttempted || generation.retired)
                throw new IOException("Held image has no fresh ready continuation authority");
            requireHostImage(request, generation.image);
            generation.image.requireUnchanged(inspectLocal(generation.image.localPid()));
            if (generation.parent != null)
                generation.parent.image.requireUnchanged(inspectLocal(generation.parent.image.localPid()));
            if (Thread.currentThread().isInterrupted()) throw new IOException("Held image resume interrupted");
            generation.resumeAttempted = true;
            manager.resume(generation.image.localPid());
            currentImages.put(generation.image.localPid(), generation);
            pendingImages.remove(generation.image.localPid());
        }

        /**
         * Rechecks the host's exact admitted image against this helper's independent local proc view.
         * @param request original host authorization
         * @param image independent local observation
         * @throws IOException if birth, mapped executable, bytes, argv or containment differs
         */
        private void requireHostImage(ObjectNode request, LinuxBazelNamespaceHooks.ImageObservation image) throws IOException
        {
            if (positive(request, "localPid") != image.localPid() || positive(request, "birth") != image.birth() ||
                !text(request, "executable").equals(image.executable().toString()) || !text(request, "sha256").equals(image.sha256()) ||
                !text(request, "cgroup").equals(image.cgroup()) ||
                !scope.getAllocationEnvelopeMapper().valueToTree(image.arguments()).equals(request.path("arguments")))
                throw new IOException("Host-authorized held image differs from independent namespace-local facts");
        }

        /**
         * Reads stable local action image facts while independently requiring original isolation and credentials.
         * @param target local PID selected from the manager's retained held event
         * @return authenticated local process and actual mapped executable facts
         * @throws IOException if process birth, credentials, namespace, image policy or cgroup differs
         */
        private LinuxBazelNamespaceHooks.ImageObservation inspectLocal(long target) throws IOException
        {
            Path proc = Path.of("/proc", Long.toString(target));
            String status = Files.readString(proc.resolve("status"));
            if (!AllocationHooks.credentialsMatch(status, (int) positive(execution, "uid"), (int) nonnegative(execution, "gid")))
                throw new IOException("Held local process credentials differ from action identity");
            LinuxBazelNamespaceHooks.ImageObservation observed = LinuxBazelNamespaceHooks.observeImage(proc, target, target);
            if (rootImage == null || !observed.cgroup().equals(rootImage.cgroup()) ||
                !observed.pidNamespace().equals(rootImage.pidNamespace()) || !observed.mountNamespace().equals(rootImage.mountNamespace()) ||
                imagePolicy == null || !imagePolicy.permits(observed.executable()) ||
                !LinuxBazelNamespaceHooks.birth(Path.of("/proc", Long.toString(ownerPid))).equals(Long.toString(ownerBirth)))
                throw new IOException("Held local process escaped original action image/isolation policy");
            String after = Files.readString(proc.resolve("status"));
            if (!AllocationHooks.credentialsMatch(after, (int) positive(execution, "uid"), (int) nonnegative(execution, "gid")))
                throw new IOException("Held local credentials changed across image observation");
            return observed;
        }

        /**
         * Retains every partial generation, its actual image inode and its sole continuation reservation.
         */
        private static final class Generation
        {
            /** Never-reused native allocation-hook identity. */
            private final long installation;
            /** Independently observed image before attachment. */
            private final LinuxBazelNamespaceHooks.ImageObservation image;
            /** Authenticated retained parent for a new process. */
            private Generation parent;
            /** Partial hook owner retained before native attachment. */
            private AllocationHook hook;
            /** Actual held executable inode retained through whole-action zero. */
            private FileChannel imageFile;
            /** Actual hook readiness was acknowledged. */
            private boolean ready;
            /** Sole manager continuation has been reserved. */
            private boolean resumeAttempted;
            /** A verified held exec made this older image impossible to execute. */
            private boolean retired;

            /**
             * Reserves image ownership without acquiring native resources.
             * @param installation unique hook generation
             * @param image independently authenticated image
             */
            private Generation(long installation, LinuxBazelNamespaceHooks.ImageObservation image)
            { this.installation = installation; this.image = image; }
        }

        @Override public void resumeImage() throws IOException
        {
            if (!ready || resumeAttempted || stopped) throw new IOException("Original image has no fresh ready resume authority");
            resumeAttempted = true;
            generations.get(installation).resumeAttempted = true;
            manager.resume(pid);
        }

        @Override public void pump() throws IOException
        {
            retireCompleted();
            if (!stopped)
                for (Generation generation : generations.values())
                    if (generation.hook != null && !generation.retired && generation.resumeAttempted) generation.hook.pump();
            copyHeld();
        }

        @Override public void grant(AllocationWaits.Wait wait) throws IOException
        {
            if (!ready || stopped) throw new IOException("Original hook grant authority is unavailable");
            Generation generation = generations.get(wait.installation());
            if (generation == null || generation.retired || !generation.ready || !generation.resumeAttempted)
                throw new IOException("Allocation grant belongs to an unavailable image generation");
            generation.hook.grant(wait, this::copyHeld);
        }

        @Override public void stop()
        {
            stopped = true;
            for (Generation generation : generations.values()) if (generation.hook != null) generation.hook.stop();
            waits.stop();
        }

        @Override public void close() throws IOException
        {
            stop();
            IOException cleanup = null;
            for (Generation generation : new ArrayList<>(generations.values()).reversed())
            {
                try
                {
                    if (generation.hook != null) generation.hook.close(() -> true);
                    if (generation.imageFile != null) generation.imageFile.close();
                }
                catch (IOException | RuntimeException | Error cause)
                {
                    if (cleanup == null) cleanup = new IOException("Held image cleanup remains incomplete", cause);
                    else HeldImageConnections.suppress(cleanup, cause);
                }
            }
            if (cleanup != null) throw cleanup;
            if (leader != null) leader.close();
            if (gate != null) gate.close();
            if (manager != null) manager.close();
            if (runtime != null) runtime.close();
        }

        @Override public Snapshot drain()
        {
            Snapshot snapshot = new Snapshot(frames, held, retired);
            frames.clear();
            held.clear();
            retired.clear();
            return snapshot;
        }

        /**
         * Retires only hooks whose original kernel process incarnation has actually ended.
         * @throws IOException if a surviving process cannot be independently observed
         */
        private void retireCompleted() throws IOException
        {
            for (Generation generation : generations.values())
            {
                if (generation.retired || !generation.resumeAttempted) continue;
                Path proc = Path.of("/proc", Long.toString(generation.image.localPid()));
                boolean ended = LinuxBazelNamespaceHooks.originalImageEnded(proc, generation.image.birth());
                if (ended)
                {
                    generation.retired = true;
                    generation.hook.stop();
                    waits.retireInstallation(generation.installation);
                    currentImages.remove(generation.image.localPid(), generation);
                    retired.add(generation.installation);
                }
            }
        }

        /**
         * Copies at most32 original held events without authorizing any descendant continuation.
         * @throws IOException if callbacks fail or the bounded pending list is exhausted
         */
        private void copyHeld() throws IOException
        {
            if (manager == null) return;
            for (int i = 0; i < 32; i++)
            {
                Optional<FridaRuntime.Child> child = manager.pollChild();
                if (child.isEmpty()) return;
                if (held.size() >= 32) throw new IOException("Namespace held-event bound exceeded");
                FridaRuntime.Child event = child.orElseThrow();
                if (pendingImages.putIfAbsent(event.pid(), event) != null)
                    throw new IOException("Original manager repeated an unresolved held image event");
                held.add(event);
            }
        }

        /**
         * Requires one exact packaged leader receipt before allowing the next native transition.
         * @param expected complete expected message payload
         * @throws IOException if a different frame, script error or deadline is observed
         */
        private void awaitLeader(String expected) throws IOException
        {
            long began = System.nanoTime();
            while (true)
            {
                Optional<String> copied = leader.pollMessage();
                if (copied.isPresent())
                {
                    JsonNode envelope = scope.getLeaderJsonMapper().readTree(copied.orElseThrow());
                    if (envelope == null || !envelope.path("type").isString() ||
                        !envelope.path("type").asString().equals("send") ||
                        !envelope.path("payload").isString() || !envelope.path("payload").asString().equals(expected))
                        throw new IOException("Unexpected original leader receipt");
                    return;
                }
                waitWithin(began, "Original leader receipt deadline exceeded");
            }
        }

        /**
         * Waits briefly inside the positive native control interval, preserving interruption as failure.
         * @param began monotonic nanosecond start
         * @param diagnostic deadline failure context
         * @throws IOException if interrupted or the control interval expired
         */
        private void waitWithin(long began, String diagnostic) throws IOException
        {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Namespace helper native wait interrupted");
            if (System.nanoTime() - began >= timeout.toNanos()) throw new IOException(diagnostic);
            LockSupport.parkNanos(1_000_000);
        }
    }

    /**
     * Hashes the actual namespace-local producer bytes before native loading.
     * @param file host-pinned namespace-local regular file
     * @return lowercase SHA-256
     * @throws IOException if the file cannot be read
     */
    static String digest(Path file) throws IOException
    {
        try (InputStream input = Files.newInputStream(file))
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[65536];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("Java SHA-256 provider is unavailable", failure); }
    }
}
