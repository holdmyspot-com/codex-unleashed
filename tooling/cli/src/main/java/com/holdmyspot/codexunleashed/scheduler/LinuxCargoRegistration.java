package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Authenticates current approved wrapper execution and direct retained compiler-parent image identity before publication.
 * Construction acquires no resources. Instances and their borrowed native observations belong to one operation
 * thread. The caller retains the compiler parent and immutable invocation configuration namespace through authentication and use.
 * This observation does not prove a historical spawn instruction or prevent later process state changes.
 * Duplicate action reservation, socket ownership and transfer-time kernel peer authentication remain caller duties.
 */
final class LinuxCargoRegistration
{
    /**
     * Supported byte bound for each native vector, matching the complete control payload bound.
     */
    private static final int MAX_VECTOR_BYTES = LinuxCargoTransport.MAX_FRAME_BYTES;
    /**
     * Invocation-owned execution and jobserver authority that a retained parent cannot replace.
     * PWD has its separate independently checked compiler-directory rule.
     */
    private static final Set<String> FIXED_ENVIRONMENT = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS",
        "RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "PWD");
    /**
     * Trusted immutable invocation policy, never derived from a receipt.
     */
    private final Configuration configuration;
    /**
     * Retained direct compiler-parent birth and credentials supplied by its invocation owner.
     */
    private final LinuxBorrowedStreams.Peer cargo;
    /**
     * Native observation boundary; production construction always supplies the actual observer.
     */
    private final Observer observer;
    /** Explicit invocation-owned best-effort profile source, or null when history was not supplied. */
    private final CargoMemoryEstimates estimates;
    /** Ready parent executable inode observations, or null for the existing immutable pinned-root policy. */
    private final Map<String, Object> parentImage;

    /**
     * Constructs a resource-free authenticator using actual proc and filesystem observations.
     * @param configuration trusted invocation policy in a caller-controlled immutable namespace
     * @param cargo retained Cargo identity captured by its process owner
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if Cargo credentials differ from the trusted workload identity
     */
    LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo)
    {
        this(configuration, cargo, new NativeObserver(), null);
    }

    /**
     * Authenticates native inputs before consulting an explicitly owned compiler profile source.
     * @param configuration trusted immutable native policy
     * @param cargo retained Cargo native identity
     * @param estimates invocation-owned profile source
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if Cargo credentials differ from the trusted workload identity
     */
    LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo, CargoMemoryEstimates estimates)
    {
        this(configuration, cargo, new NativeObserver(), Objects.requireNonNull(estimates, "estimates"));
    }

    /**
     * Authenticates a wrapper against an independently retained ready compiler-parent image.
     * @param configuration trusted invocation policy
     * @param parent independently retained parent birth, image and readiness generation
     */
    LinuxCargoRegistration(Configuration configuration, Parent parent)
    {
        this(parent.policy(configuration), parent.peer(), new NativeObserver(), null, parent.image());
    }

    /**
     * Authenticates a ready compiler-parent image before consulting invocation-owned history.
     * @param configuration trusted invocation policy
     * @param parent independently retained ready image
     * @param estimates explicitly owned compiler profile source
     */
    LinuxCargoRegistration(Configuration configuration, Parent parent, CargoMemoryEstimates estimates)
    {
        this(parent.policy(configuration), parent.peer(), new NativeObserver(), Objects.requireNonNull(estimates, "estimates"), parent.image());
    }

    /**
     * Carries one independently retained compiler-parent image without deriving authority from a receipt.
     * Native registration must recheck this exact birth and executable before and after observing the wrapper.
     * @param peer independently retained process birth and complete workload credentials
     * @param executable independently checked executable of the ready image
     * @param generation retained image readiness ordinal, or zero for the independently prepared root
     * @param image immutable executable inode observations captured independently during readiness
     */
    record Parent(LinuxBorrowedStreams.Peer peer, Path executable, long generation, Map<String, Object> image)
    {
        /**
         * Rejects incomplete retained image observations before they can select registration policy.
         * @param peer independently retained process birth and credentials
         * @param executable absolute normalized observed image
         * @param generation positive readiness ordinal, or zero for the prepared root
         * @param image exact independent dev, inode, size, modification and change-time observations
         * @throws NullPointerException if a required observation is null
         * @throws IllegalArgumentException if the executable or generation is invalid
         */
        Parent
        {
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(executable, "executable");
            image = Map.copyOf(image);
            if (!image.keySet().equals(java.util.Set.of("dev", "ino", "size", "lastModifiedTime", "ctime")))
                throw new IllegalArgumentException("Retained parent requires complete executable inode observations");
            if (!executable.isAbsolute() || !executable.equals(executable.normalize()) || generation < 0)
                throw new IllegalArgumentException("Retained parent requires a normalized absolute executable and nonnegative generation");
        }

        /**
         * Selects this independently retained executable while preserving every other invocation boundary.
         * @param invocation trusted root invocation policy, including exact wrapper, compiler and environment
         * @return immutable per-parent registration policy
         */
        private Configuration policy(Configuration invocation)
        {
            return new Configuration(executable, invocation.wrapperExecutable(), invocation.rustcExecutable(),
                invocation.wrapperPrefix(), invocation.registrationRoot(), invocation.context(), invocation.requiredEnvironment());
        }
    }

    /**
     * Supplies the same authentication policy with an independently controlled observation boundary.
     * @param configuration validated trusted policy
     * @param cargo retained Cargo identity
     * @param observer native observation boundary
     * @param estimates explicit profile source, or null without history
     */
    private LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo, Observer observer,
        CargoMemoryEstimates estimates)
    {
        this(configuration, cargo, observer, estimates, null);
    }

    /**
     * Retains independent ready-image inode identity alongside the complete invocation policy.
     * @param configuration trusted per-parent invocation policy
     * @param cargo retained direct compiler-parent birth
     * @param observer independent native observations
     * @param estimates explicit profile source, or null without history
     * @param parentImage independently retained executable inode observations, or null for pinned-root policy
     */
    private LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo, Observer observer,
        CargoMemoryEstimates estimates, Map<String, Object> parentImage)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.cargo = Objects.requireNonNull(cargo, "cargo");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.estimates = estimates;
        this.parentImage = parentImage == null ? null : Map.copyOf(parentImage);
        if (cargo.uid() != configuration.context().uid() || cargo.gid() != configuration.context().gid())
            throw new IllegalArgumentException("Retained Cargo credentials differ from invocation policy");
    }

    /**
     * Authenticates one decoded receipt without publishing it or granting an attempt capability.
     * @param captured kernel-captured peer from the accepted control connection
     * @param registration untrusted canonical receipt from LinuxCargoMessages
     * @return independently checked compiler inputs and wrapper/Cargo births, with a best-effort completed-work estimate, or unknown demand
     * @throws NullPointerException if an input is null
     * @throws IOException if native observations are unavailable, unsupported, changing or inconsistent
     */
    CargoCompilerRequest authenticate(LinuxBorrowedStreams.Peer captured,
        LinuxBorrowedStreams.Binding registration) throws IOException
    {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(registration, "registration");
        var context = configuration.context();
        if (captured.pid() == cargo.pid() || captured.uid() != context.uid() || captured.gid() != context.gid())
            throw new IOException("Wrapper credentials differ from invocation policy");
        var properties = new Properties();
        LinuxBorrowedStreams.writeTo(properties, registration);
        if (!Long.toString(captured.pid()).equals(properties.getProperty("borrowed.pid")) ||
            !Long.toString(captured.startTicks()).equals(properties.getProperty("borrowed.birth")) ||
            !Integer.toString(captured.uid()).equals(properties.getProperty("borrowed.uid")) ||
            !Integer.toString(captured.gid()).equals(properties.getProperty("borrowed.gid")))
            throw new IOException("Registration differs from captured native wrapper identity");
        if (registration.attemptSequence() != 1 ||
            !Base64.getEncoder().encodeToString(new byte[32]).equals(properties.getProperty("borrowed.capability")))
            throw new IOException("Initial registration must not carry an attempt capability");
        var launch = registration.approvedLaunch();
        if (!configuration.rustcExecutable().toString().equals(launch.command().getFirst()))
            throw new IOException("Registration names another compiler");
        var expected = new LinuxProcessTree.Launch(context.cgroupParent(), context.scratchParent(),
            context.javaExecutable(), context.classPath(), launch.command(), launch.environment(), launch.directory(),
            context.uid(), context.gid(), context.executionMode());
        registration.requireExecution(expected);
        Path directory = configuration.registrationRoot().resolve(Long.toString(captured.pid()));
        Path endpoint = directory.resolve("streams.sock");
        if (!endpoint.toString().equals(properties.getProperty("borrowed.endpoint")))
            throw new IOException("Registration names another stream endpoint");

        requireIdentity(captured);
        // Cargo gives build scripts target-specific inputs, including TARGET and removed RUSTFLAGS.
        // Borrow only the independently observed environment of this exact retained native parent.
        Map<String, String> parentEnvironment = parentImage == null ? null : Map.copyOf(observer.environment(cargo.pid()));
        var arguments = List.copyOf(observer.arguments(captured.pid()));
        var expectedArguments = new ArrayList<>(configuration.wrapperPrefix());
        expectedArguments.addAll(launch.command());
        if (!arguments.equals(expectedArguments)) throw new IOException("Native wrapper command differs from approved prefix and compiler command");
        var environment = Map.copyOf(observer.environment(captured.pid()));
        if (!environment.equals(launch.environment())) throw new IOException("Native initial environment differs from registration");
        for (var entry : configuration.requiredEnvironment().entrySet())
        {
            if (entry.getValue().equals(environment.get(entry.getKey()))) continue;
            // Cargo selects a dependency compiler's directory; the installed shell then updates PWD.
            // Preserve the complete native environment and accept that update only with independent file identity.
            if (entry.getKey().equals("PWD") && environment.containsKey("PWD"))
            {
                Path compilerDirectory = Path.of(environment.get("PWD"));
                if (compilerDirectory.isAbsolute() && observer.sameFile(compilerDirectory, launch.directory())) continue;
            }
            if (parentEnvironment != null && !FIXED_ENVIRONMENT.contains(entry.getKey()) &&
                parentEnvironment.containsKey(entry.getKey()) == environment.containsKey(entry.getKey()) &&
                Objects.equals(parentEnvironment.get(entry.getKey()), environment.get(entry.getKey()))) continue;
            throw new IOException("Native environment changed an invocation-owned entry: " + entry.getKey());
        }
        if (!observer.sameFile(process(captured.pid()).resolve("cwd"), launch.directory()))
            throw new IOException("Native working directory differs from registration");
        Entry directoryBefore = observer.entry(directory);
        Entry endpointBefore = observer.entry(endpoint);
        if (directoryBefore.uid() != captured.uid() || (directoryBefore.mode() & 0170000) != 0040000 ||
            (directoryBefore.mode() & 07777) != 0700 || endpointBefore.uid() != captured.uid() ||
            (endpointBefore.mode() & 0170000) != 0140000)
            throw new IOException("Stream endpoint is not an owned socket in an exclusive 0700 wrapper directory");
        if (parentEnvironment != null && !parentEnvironment.equals(observer.environment(cargo.pid())))
            throw new IOException("Retained compiler-parent initial environment changed during registration");
        requireIdentity(captured);
        if (!directoryBefore.equals(observer.entry(directory)) || !endpointBefore.equals(observer.entry(endpoint)))
            throw new IOException("Stream endpoint identity changed during registration");
        CargoCompilerRequest request = new CargoCompilerRequest(registration.actionId(), new CargoCompilerRequest.Wrapper(captured.pid(),
            captured.startTicks(), cargo.pid(), cargo.startTicks()),
            arguments.subList(configuration.wrapperPrefix().size(), arguments.size()), launch.directory(), environment, null);
        return estimates == null ? request : estimates.register(request);
    }

    /**
     * Checks both retained process births, direct parentage and executable identities at an observation boundary.
     * @param captured kernel-captured wrapper identity
     * @throws IOException if any identity is unavailable or differs from trusted invocation policy
     */
    private void requireIdentity(LinuxBorrowedStreams.Peer captured) throws IOException
    {
        observer.requireLive(cargo);
        observer.requireLive(captured);
        if (parentImage != null && !parentImage.equals(observer.executableImage(cargo.pid())))
            throw new IOException("Retained compiler-parent executable inode changed after readiness");
        if (observer.parent(captured.pid()) != cargo.pid())
            throw new IOException("Wrapper is not a direct child of the retained compiler-parent birth");
        if (!observer.sameFile(process(cargo.pid()).resolve("exe"), configuration.cargoExecutable()) ||
            !observer.sameFile(process(captured.pid()).resolve("exe"), configuration.wrapperExecutable()))
            throw new IOException("Native executable differs from the trusted invocation executable");
    }

    /**
     * Immutable native namespace policy. Compiler command, environment and directory in context are placeholders;
     * its Java executable, classpath, credentials, attached mode and containment parents are trusted authority.
     * The complete wrapper prefix precedes the exact compiler command in the actual native argv.
     * @param cargoExecutable expected independently retained compiler-parent executable; root policy uses pinned Cargo
     * @param wrapperExecutable pinned executable actually running the wrapper, such as the Java executable
     * @param rustcExecutable exact allowed compiler executable
     * @param wrapperPrefix complete immutable native argv prefix before the compiler executable
     * @param registrationRoot normalized absolute parent of exclusive PID directories
     * @param context trusted attached launch context used to reconstruct execution authority
     * @param requiredEnvironment invocation-owned entries, including borrowed jobserver flags; independently retained
     * parent images may supply Cargo-selected ordinary values or omissions, while execution and jobserver authority
     * stays fixed and PWD must identify the actual compiler directory
     */
    record Configuration(Path cargoExecutable, Path wrapperExecutable, Path rustcExecutable,
        List<String> wrapperPrefix, Path registrationRoot, LinuxProcessTree.Launch context,
        Map<String, String> requiredEnvironment)
    {
        /**
         * Copies collections and rejects invalid policy before any native observation.
         * @param cargoExecutable expected independently retained compiler-parent executable; root policy uses pinned Cargo
         * @param wrapperExecutable pinned executable actually running the wrapper
         * @param rustcExecutable exact allowed compiler executable
         * @param wrapperPrefix complete native argv prefix before the compiler executable
         * @param registrationRoot normalized absolute parent of exclusive PID directories
         * @param context trusted attached execution context, with compiler inputs supplied during authentication
         * @param requiredEnvironment invocation entries requiring exact preservation, except a compiler PWD authenticated by file identity
         * @throws NullPointerException if any required value or collection member is null
         * @throws IllegalArgumentException if paths, prefix, native text or attached execution mode are invalid
         */
        Configuration
        {
            for (Path path : List.of(cargoExecutable, wrapperExecutable, rustcExecutable, registrationRoot))
            {
                if (!path.isAbsolute() || !path.normalize().equals(path))
                    throw new IllegalArgumentException("Trusted paths must be normalized and absolute");
                requireText(path.toString());
            }
            wrapperPrefix = List.copyOf(wrapperPrefix);
            if (wrapperPrefix.isEmpty() || !Path.of(wrapperPrefix.getFirst()).isAbsolute())
                throw new IllegalArgumentException("The complete wrapper prefix requires an absolute argv executable");
            wrapperPrefix.forEach(LinuxCargoRegistration::requireText);
            Objects.requireNonNull(context, "context");
            if (context.executionMode() != LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC)
                throw new IllegalArgumentException("Cargo compiler execution must use attached leader exec");
            for (Path path : List.of(context.cgroupParent(), context.scratchParent(), context.javaExecutable(), context.classPath()))
                requireText(path.toString());
            requiredEnvironment = Map.copyOf(requiredEnvironment);
            requiredEnvironment.forEach((name, value) ->
            {
                requireName(name);
                requireText(value);
            });
        }
    }

    /**
     * Rejects native strings that cannot survive a NUL-terminated native representation.
     * @param text native text to validate
     */
    private static void requireText(String text)
    {
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Native text contains NUL");
    }

    /**
     * Rejects environment names that cannot be represented in the native environment vector.
     * @param name complete variable name
     */
    private static void requireName(String name)
    {
        requireText(name);
        if (name.isEmpty() || name.indexOf('=') >= 0)
            throw new IllegalArgumentException("Native environment name is empty or contains '='");
    }

    /**
     * Represents one no-follow native inode observation; mode includes type and permission bits.
     * @param key filesystem identity of this specific inode
     * @param uid native inode owner
     * @param mode native inode type and permission bits
     */
    record Entry(Object key, int uid, int mode)
    {
        /**
         * Requires an established native file identity.
         * @param key filesystem identity of this specific inode
         * @param uid native inode owner
         * @param mode native inode type and permission bits
         */
        Entry { Objects.requireNonNull(key, "key"); }
    }

    /**
     * Supplies independent native facts without receiving authority from a registration.
     */
    interface Observer
    {
        /**
         * Checks live birth, credentials and common PID namespace.
         * @param peer retained identity to check
         * @throws IOException if native identity cannot be established
         */
        void requireLive(LinuxBorrowedStreams.Peer peer) throws IOException;
        /**
         * Reads current executable inode identity independently of mutable pathname spelling.
         * @param pid retained compiler-parent process
         * @return complete current executable observations
         * @throws IOException if independent executable identity cannot be supplied
         */
        default Map<String, Object> executableImage(long pid) throws IOException
        {
            throw new IOException("Independent current executable inode observation is unavailable");
        }
        /**
         * Reads the live direct parent PID.
         * @param pid process being observed
         * @return direct parent PID
         * @throws IOException if parent observation is unavailable
         */
        long parent(long pid) throws IOException;
        /**
         * Checks native file identity rather than pathname spelling.
         * @param observed native proc link or path
         * @param expected trusted or independently checked path
         * @return whether both refer to the same file
         * @throws IOException if identity observation fails
         */
        boolean sameFile(Path observed, Path expected) throws IOException;
        /**
         * Reads one bounded native argument vector.
         * @param pid process being observed
         * @return complete native argv including explicit empty arguments
         * @throws IOException if reading, framing or decoding fails
         */
        List<String> arguments(long pid) throws IOException;
        /**
         * Reads the bounded native initial environment without collapsing names.
         * @param pid process being observed
         * @return complete native environment
         * @throws IOException if reading, framing, names or decoding fails
         */
        Map<String, String> environment(long pid) throws IOException;
        /**
         * Captures native inode attributes without following the final link.
         * @param path exact pathname to inspect
         * @return independently captured inode identity, owner and native mode
         * @throws IOException if observation is unavailable or unsupported
         */
        Entry entry(Path path) throws IOException;
    }

    /**
     * Reads actual Linux proc entries and no-follow filesystem attributes.
     */
    private static final class NativeObserver implements Observer
    {
        /**
         * Creates a stateless observer without acquiring resources.
         */
        private NativeObserver() {}
        @Override public void requireLive(LinuxBorrowedStreams.Peer peer) throws IOException { peer.requireLive(); }
        @Override public Map<String, Object> executableImage(long pid) throws IOException
        {
            return imageIdentity(process(pid).resolve("exe"));
        }
        @Override public long parent(long pid) throws IOException
        {
            return ProcessHandle.of(pid).flatMap(ProcessHandle::parent).
                orElseThrow(() -> new IOException("Wrapper parent is unavailable")).pid();
        }
        @Override public boolean sameFile(Path observed, Path expected) throws IOException
        {
            return Files.isSameFile(observed, expected);
        }
        @Override public List<String> arguments(long pid) throws IOException
        {
            try (InputStream input = Files.newInputStream(process(pid).resolve("cmdline")))
            {
                return vector(input, false);
            }
        }
        @Override public Map<String, String> environment(long pid) throws IOException
        {
            try (InputStream input = Files.newInputStream(process(pid).resolve("environ")))
            {
                return environmentVector(input);
            }
        }
        @Override public Entry entry(Path path) throws IOException
        {
            Map<String, Object> values = Files.readAttributes(path, "unix:fileKey,uid,mode", LinkOption.NOFOLLOW_LINKS);
            Object key = values.get("fileKey");
            if (key == null) throw new IOException("Native inode identity is unavailable");
            return new Entry(key, ((Number) values.get("uid")).intValue(), ((Number) values.get("mode")).intValue());
        }
    }

    /**
     * Reads the exact executable inode attributes retained during held-image readiness and rechecked at registration.
     * Following the proc executable link identifies the running inode even when its pathname is later replaced.
     * @param executable actual proc executable link, or immutable pinned root executable before launch
     * @return immutable device, inode, size, modification-time and change-time observations
     * @throws IOException if executable identity cannot be observed
     */
    static Map<String, Object> imageIdentity(Path executable) throws IOException
    {
        return Map.copyOf(Files.readAttributes(executable, "unix:dev,ino,size,lastModifiedTime,ctime"));
    }

    /**
     * Locates native information for a kernel-captured positive process ID.
     * @param pid native process identifier
     * @return absolute proc directory
     */
    private static Path process(long pid) { return Path.of("/proc", Long.toString(pid)); }

    /**
     * Decodes a bounded native NUL vector without losing explicit empty arguments.
     * The limit applies to raw bytes including delimiters; a one-byte lookahead rejects overflow.
     * @param input caller-owned stream, left open on success or failure
     * @param allowEmpty whether zero bytes represent a valid empty vector
     * @return immutable complete decoded entries
     * @throws IOException if input exceeds 1 MiB or has malformed UTF-8 or missing framing
     */
    private static List<String> vector(InputStream input, boolean allowEmpty) throws IOException
    {
        byte[] bytes = input.readNBytes(MAX_VECTOR_BYTES + 1);
        if (bytes.length > MAX_VECTOR_BYTES) throw new IOException("Native vector exceeds supported 1 MiB limit");
        if (bytes.length == 0)
        {
            if (allowEmpty) return List.of();
            throw new IOException("Native command vector is empty");
        }
        if (bytes[bytes.length - 1] != 0) throw new IOException("Native vector lacks its final delimiter");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
            onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1)).toString();
        return List.copyOf(Arrays.asList(text.split("\u0000", -1)));
    }

    /**
     * Reads a complete initial environment using the actual bounded vector parser.
     * @param input caller-owned native vector stream
     * @return immutable native variable values
     * @throws IOException if reading, framing, decoding or names are invalid
     */
    private static Map<String, String> environmentVector(InputStream input) throws IOException
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : vector(input, true))
        {
            int separator = entry.indexOf('=');
            if (separator <= 0 || result.putIfAbsent(entry.substring(0, separator), entry.substring(separator + 1)) != null)
                throw new IOException("Native environment has an invalid or duplicate name");
        }
        return Map.copyOf(result);
    }

    /**
     * Exposes observation injection and real vector reading to focused contract checks.
     */
    static final class SharedSecrets
    {
        /**
         * Prevents construction of the scoped test access facade.
         */
        private SharedSecrets() {}
        /**
         * Constructs the real authentication policy with controlled independent native observations.
         * @param configuration validated trusted policy
         * @param cargo retained Cargo identity
         * @param observer independent observation source
         * @return authenticator executing the production policy
         */
        static LinuxCargoRegistration create(Configuration configuration, LinuxBorrowedStreams.Peer cargo, Observer observer)
        {
            return new LinuxCargoRegistration(configuration, cargo, observer, null);
        }
        /**
         * Exercises ready-image parent composition with independent native observations.
         * @param configuration trusted invocation policy
         * @param parent independently retained ready parent image
         * @param observer controlled native observation boundary
         * @return production authenticator for that retained image
         */
        static LinuxCargoRegistration create(Configuration configuration, Parent parent, Observer observer)
        {
            return new LinuxCargoRegistration(parent.policy(configuration), parent.peer(), observer, null, parent.image());
        }
        /**
         * Exercises native authentication followed by explicit production profile lookup.
         * @param configuration trusted immutable policy
         * @param cargo retained native Cargo identity
         * @param observer controlled independent native observations
         * @param estimates actual owned profile source
         * @return production authenticator with profile composition
         */
        static LinuxCargoRegistration create(Configuration configuration, LinuxBorrowedStreams.Peer cargo,
            Observer observer, CargoMemoryEstimates estimates)
        {
            return new LinuxCargoRegistration(configuration, cargo, observer, Objects.requireNonNull(estimates, "estimates"));
        }
        /**
         * Exercises the production native argument reader with a caller-owned bounded fixture stream.
         * @param input caller-owned raw argument vector
         * @return exact decoded argument entries
         * @throws IOException if native framing or decoding fails
         */
        static List<String> arguments(InputStream input) throws IOException { return vector(input, false); }
        /**
         * Exercises the production native environment reader with a caller-owned fixture stream.
         * @param input caller-owned raw environment vector
         * @return exact decoded environment
         * @throws IOException if native framing, names or decoding fails
         */
        static Map<String, String> environment(InputStream input) throws IOException { return environmentVector(input); }
    }
}
