package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.secrets.LinuxCargoRegistrationAccess;
import com.holdmyspot.codexunleashed.secrets.LinuxCargoRegistrationSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates a native wrapper registration against the wrapper process and its retained compiler parent.
 * Construction acquires no resources. Instances and their borrowed native observations belong to one operation
 * thread. The caller retains the compiler parent and immutable invocation configuration namespace through authentication and use.
 * This observation does not prove a historical spawn instruction or prevent later process state changes.
 * Socket ownership and kernel peer capture at acceptance remain caller duties.
 */
public final class LinuxCargoRegistration
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
	static
	{
		LinuxCargoRegistrationSecrets.setLinuxCargoRegistrationAccess(new LinuxCargoRegistrationAccess()
		{
			@Override
			public LinuxCargoRegistration create(Configuration configuration, LinuxBorrowedStreams.Peer cargo, Observer observer)
			{
				return new LinuxCargoRegistration(configuration, cargo, observer, null);
			}

			@Override
			public LinuxCargoRegistration create(Configuration configuration, Parent parent, Observer observer)
			{
				return new LinuxCargoRegistration(parent.policy(configuration), parent.peer(), observer, null, parent.image());
			}

			@Override
			public LinuxCargoRegistration create(Configuration configuration, LinuxBorrowedStreams.Peer cargo, Observer observer,
				CargoMemoryEstimates estimates)
			{
				return new LinuxCargoRegistration(configuration, cargo, observer,
					Objects.requireNonNull(estimates, "estimates"));
			}

			@Override
			public List<String> arguments(InputStream input) throws IOException
			{
				return vector(input, false);
			}

			@Override
			public Map<String, String> environment(InputStream input) throws IOException
			{
				return environmentVector(input);
			}
		});
	}

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
    public LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo)
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
    public LinuxCargoRegistration(Configuration configuration, LinuxBorrowedStreams.Peer cargo, CargoMemoryEstimates estimates)
    {
        this(configuration, cargo, new NativeObserver(), Objects.requireNonNull(estimates, "estimates"));
    }

    /**
     * Authenticates a wrapper against an independently retained ready compiler-parent image.
     * @param configuration trusted invocation policy
     * @param parent independently retained parent birth, image and readiness generation
     */
    public LinuxCargoRegistration(Configuration configuration, Parent parent)
    {
        this(parent.policy(configuration), parent.peer(), new NativeObserver(), null, parent.image());
    }

    /**
     * Authenticates a ready compiler-parent image before consulting invocation-owned history.
     * @param configuration trusted invocation policy
     * @param parent independently retained ready image
     * @param estimates explicitly owned compiler profile source
     */
    public LinuxCargoRegistration(Configuration configuration, Parent parent, CargoMemoryEstimates estimates)
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
    public record Parent(LinuxBorrowedStreams.Peer peer, Path executable, long generation, Map<String, Object> image)
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
        public Parent
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
                invocation.wrapperPrefix(), invocation.context(), invocation.requiredEnvironment());
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
     * Authenticates one wrapper registration against the wrapper's own native process before it is queued.
     * <p>
     * The registration is accepted only when the captured peer runs the trusted wrapper executable as a direct child of
     * the retained compiler parent, under the workload credentials, and its native argv, initial environment and working
     * directory match the registration. Invocation-owned environment entries must keep their trusted values, except that
     * PWD may name the registered directory and, below an independently retained parent image, an ordinary entry may
     * take that parent's observed value.
     * @param captured kernel-captured peer of the accepted wrapper connection
     * @param register untrusted decoded registration from that connection
     * @return compiler request with a fresh action identity, the wrapper and parent births and a best-effort
     * completed-work estimate, or unknown demand
     * @throws NullPointerException if {@code captured} or {@code register} is null
     * @throws IOException if native observations are unavailable, changing or inconsistent with the registration or
     * the invocation policy
     */
    public CargoCompilerRequest authenticate(LinuxBorrowedStreams.Peer captured, LinuxCargoProtocol.Register register)
        throws IOException
    {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(register, "register");
        var context = configuration.context();
        if (captured.pid() == cargo.pid() || captured.uid() != context.uid() || captured.gid() != context.gid())
            throw new IOException("Wrapper credentials differ from invocation policy");
        if (!configuration.rustcExecutable().toString().equals(register.arguments().getFirst()))
            throw new IOException("Registration names another compiler");

        requireIdentity(captured);
        // Cargo gives build scripts target-specific inputs, including TARGET and removed RUSTFLAGS.
        // Borrow only the independently observed environment of this exact retained native parent.
        Map<String, String> parentEnvironment = parentImage == null ? null : Map.copyOf(observer.environment(cargo.pid()));
        var arguments = List.copyOf(observer.arguments(captured.pid()));
        var expectedArguments = new ArrayList<>(configuration.wrapperPrefix());
        expectedArguments.addAll(register.arguments());
        if (!arguments.equals(expectedArguments))
            throw new IOException("Native wrapper command differs from approved prefix and compiler command");
        var environment = Map.copyOf(observer.environment(captured.pid()));
        if (!environment.equals(register.environment()))
            throw new IOException("Native initial environment differs from registration");
        requireInvocationEnvironment(environment, parentEnvironment, register.directory());
        if (!observer.sameFile(process(captured.pid()).resolve("cwd"), register.directory()))
            throw new IOException("Native working directory differs from registration");
        if (parentEnvironment != null && !parentEnvironment.equals(observer.environment(cargo.pid())))
            throw new IOException("Retained compiler-parent initial environment changed during registration");
        requireIdentity(captured);

        CargoCompilerRequest request = new CargoCompilerRequest(UUID.randomUUID(),
            new CargoCompilerRequest.Wrapper(captured.pid(), captured.startTicks(), cargo.pid(), cargo.startTicks()),
            register.arguments(), register.directory(), environment, null);
        return estimates == null ? request : estimates.register(request);
    }

    /**
     * Requires every invocation-owned environment entry to keep its trusted value.
     * @param environment complete native initial environment of the wrapper
     * @param parentEnvironment initial environment of an independently retained parent image, or null for the root
     * @param directory registered compiler working directory
     * @throws IOException if an entry changed in a way neither PWD nor the retained parent accounts for
     */
    private void requireInvocationEnvironment(Map<String, String> environment, Map<String, String> parentEnvironment,
        Path directory) throws IOException
    {
        for (var entry : configuration.requiredEnvironment().entrySet())
        {
            if (entry.getValue().equals(environment.get(entry.getKey()))) continue;
            // Cargo selects a dependency compiler's directory; the installed shell then updates PWD.
            // Preserve the complete native environment and accept that update only with independent file identity.
            if (entry.getKey().equals("PWD") && environment.containsKey("PWD"))
            {
                Path compilerDirectory = Path.of(environment.get("PWD"));
                if (compilerDirectory.isAbsolute() && observer.sameFile(compilerDirectory, directory)) continue;
            }
            if (parentEnvironment != null && !FIXED_ENVIRONMENT.contains(entry.getKey()) &&
                parentEnvironment.containsKey(entry.getKey()) == environment.containsKey(entry.getKey()) &&
                Objects.equals(parentEnvironment.get(entry.getKey()), environment.get(entry.getKey()))) continue;
            throw new IOException("Native environment changed an invocation-owned entry: " + entry.getKey());
        }
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
     * @param context trusted attached launch context used to reconstruct execution authority
     * @param requiredEnvironment invocation-owned entries, including borrowed jobserver flags; independently retained
     * parent images may supply Cargo-selected ordinary values or omissions, while execution and jobserver authority
     * stays fixed and PWD must identify the actual compiler directory
     */
    public record Configuration(Path cargoExecutable, Path wrapperExecutable, Path rustcExecutable,
        List<String> wrapperPrefix, LinuxProcessTree.Launch context,
        Map<String, String> requiredEnvironment)
    {
        /**
         * Copies collections and rejects invalid policy before any native observation.
         * @param cargoExecutable expected independently retained compiler-parent executable; root policy uses pinned Cargo
         * @param wrapperExecutable pinned executable actually running the wrapper
         * @param rustcExecutable exact allowed compiler executable
         * @param wrapperPrefix complete native argv prefix before the compiler executable
         * @param context trusted attached execution context, with compiler inputs supplied during authentication
         * @param requiredEnvironment invocation entries requiring exact preservation, except a compiler PWD authenticated by file identity
         * @throws NullPointerException if any required value or collection member is null
         * @throws IllegalArgumentException if paths, prefix, native text or attached execution mode are invalid
         */
        public Configuration
        {
            for (Path path : List.of(cargoExecutable, wrapperExecutable, rustcExecutable))
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
     * Supplies independent native facts without receiving authority from a registration.
     */
    public interface Observer
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
    }

    /**
     * Reads actual Linux proc entries and file identities.
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
    static List<String> vector(InputStream input, boolean allowEmpty) throws IOException
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
    static Map<String, String> environmentVector(InputStream input) throws IOException
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
}
