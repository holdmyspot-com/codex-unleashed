package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks production registration policy against independent modeled native observations.
 * These checks do not establish native process acceptance.
 */
public final class LinuxCargoRegistrationTest
{
    /**
     * Creates independent registration contract checks.
     */
    public LinuxCargoRegistrationTest() {}

    /**
     * Preserves exact compiler arguments and environment after authenticating native observations.
     * @throws IOException if a valid native observation is rejected
     */
    @Test
    public void authenticatesExactNativeCompilerInputs() throws IOException
    {
        var fixture = new Fixture();
        CargoCompilerRequest result = fixture.authenticator().authenticate(fixture.wrapper, fixture.binding());
        requireThat(result.arguments(), "exactArguments").isEqualTo(fixture.launch.command());
        requireThat(result.environment(), "completeEnvironment").isEqualTo(fixture.launch.environment());
        requireThat(result.wrapper(), "authenticatedBirths").isEqualTo(new CargoCompilerRequest.Wrapper(71, 81, 61, 91));
        requireThat(result.peakBytes() == null, "unknownDemand").isTrue();
    }

    /**
     * Preserves the authenticated compiler directory and shell PWD when Cargo changes into a dependency crate.
     * @throws IOException if valid independently observed compiler inputs are rejected
     */
    @Test
    public void authenticatesCargoSelectedCompilerDirectory() throws IOException
    {
        var fixture = new Fixture();
        fixture.nativeDirectory = fixture.root.resolve("registry-crate");
        fixture.nativeEnvironment.put("PWD", fixture.nativeDirectory.toString());
        var original = fixture.launch;
        var represented = new LinuxProcessTree.Launch(original.cgroupParent(), original.scratchParent(),
            original.javaExecutable(), original.classPath(), original.command(), fixture.nativeEnvironment,
            fixture.nativeDirectory, original.uid(), original.gid(), original.executionMode());
        var policy = fixture.configuration();
        var required = new LinkedHashMap<>(policy.requiredEnvironment());
        required.put("PWD", fixture.root.toString());
        var configuration = new LinuxCargoRegistration.Configuration(policy.cargoExecutable(), policy.wrapperExecutable(),
            policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), required);
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(configuration, fixture.cargo, fixture);
        CargoCompilerRequest result = authenticator.authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), represented));
        requireThat(result.directory(), "authenticatedCompilerDirectory").isEqualTo(fixture.nativeDirectory);
        requireThat(result.environment(), "completeAuthenticatedCompilerEnvironment").isEqualTo(fixture.nativeEnvironment);
    }

    /** Rejects a changed native PWD that does not identify the authenticated compiler directory. */
    @Test
    public void rejectsChangedPwdOutsideCompilerDirectory()
    {
        var fixture = new Fixture();
        fixture.nativeEnvironment.put("PWD", fixture.root.resolve("foreign-directory").toString());
        var policy = fixture.configuration();
        var required = new LinkedHashMap<>(policy.requiredEnvironment());
        required.put("PWD", fixture.root.toString());
        var configuration = new LinuxCargoRegistration.Configuration(policy.cargoExecutable(), policy.wrapperExecutable(),
            policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), required);
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(configuration, fixture.cargo, fixture);
        requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(),
                fixture.withEnvironment(fixture.nativeEnvironment))));
    }

    /**
     * A ready build-script parent authenticates its exact compiler request without pretending to be Cargo.
     * The method-local image models a descendant independently accepted by held-image readiness.
     * @throws IOException if valid retained build-script parentage is rejected
     */
    @Test
    public void authenticatesIndependentlyReadyBuildScriptParent() throws IOException
    {
        var fixture = new Fixture();
        fixture.nativeParentExecutable = fixture.root.resolve("target/release/build/libc/build-script-build");
        Map<Long, LinuxCargoRegistration.Parent> retained = new LinkedHashMap<>();
        var ready = new LinuxCargoImages.Identity(fixture.cargo.pid(), fixture.cargo.startTicks(), 51,
            fixture.cargo.uid(), fixture.cargo.gid(), fixture.nativeParentExecutable, fixture.root);
        LinuxCargoInvocation.retainReadyParent(retained, ready, 7, fixture.nativeParentImage);
        var parent = LinuxCargoInvocation.retainedCargoParent(retained, fixture.cargo.pid());
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(fixture.configuration(), parent, fixture);
        var request = authenticator.authenticate(fixture.wrapper, fixture.binding());
        requireThat(request.arguments(), "exactBuildScriptCompilerRequest").isEqualTo(fixture.launch.command());
        requireThat(request.wrapper().parentPid(), "independentlyRetainedBuildScriptPid").isEqualTo(fixture.cargo.pid());
    }

    /**
     * Accepts Cargo-selected build-script values only when independently observed on its retained parent.
     * @throws IOException if valid independently observed parent inputs are rejected
     */
    @Test
    public void authenticatesRetainedParentEnvironment() throws IOException
    {
        for (String key : List.of("TARGET", "RUSTFLAGS"))
        {
            var fixture = new Fixture();
            var policy = fixture.configuration();
            var required = new LinkedHashMap<>(policy.requiredEnvironment());
            required.put(key, "original-invocation-value");
            if (key.equals("TARGET"))
            {
                fixture.nativeEnvironment.put(key, "cargo-selected-host-target");
                fixture.nativeParentEnvironment.put(key, "cargo-selected-host-target");
            }
            var configuration = new LinuxCargoRegistration.Configuration(policy.cargoExecutable(), policy.wrapperExecutable(),
                policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), required);
            var parent = new LinuxCargoRegistration.Parent(fixture.cargo, fixture.nativeParentExecutable, 7, fixture.nativeParentImage);
            var authenticator = LinuxCargoRegistration.SharedSecrets.create(configuration, parent, fixture);
            var result = authenticator.authenticate(fixture.wrapper,
                fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), fixture.withEnvironment(fixture.nativeEnvironment)));
            requireThat(result.environment(), "completeNativeCompilerEnvironment").isEqualTo(fixture.nativeEnvironment);
        }
    }

    /** Rejects wrapper-only changes even when its forged receipt agrees with the changed native wrapper. */
    @Test
    public void rejectsEnvironmentNotInheritedFromRetainedParent()
    {
        var fixture = new Fixture();
        fixture.nativeEnvironment.put("ADDED", "wrapper-only-value");
        var policy = fixture.configuration();
        var required = new LinkedHashMap<>(policy.requiredEnvironment());
        required.put("ADDED", "a=b");
        var configuration = new LinuxCargoRegistration.Configuration(policy.cargoExecutable(), policy.wrapperExecutable(),
            policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), required);
        var parent = new LinuxCargoRegistration.Parent(fixture.cargo, fixture.nativeParentExecutable, 7, fixture.nativeParentImage);
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(configuration, parent, fixture);
        requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), fixture.withEnvironment(fixture.nativeEnvironment))));
    }

    /** Rejects changed jobserver authority even when the retained parent's environment contains the same change. */
    @Test
    public void rejectsRetainedParentJobserverChange()
    {
        var fixture = new Fixture();
        fixture.nativeEnvironment.put("CARGO_MAKEFLAGS", "--jobserver-auth=foreign");
        fixture.nativeParentEnvironment.put("CARGO_MAKEFLAGS", "--jobserver-auth=foreign");
        var parent = new LinuxCargoRegistration.Parent(fixture.cargo, fixture.nativeParentExecutable, 7, fixture.nativeParentImage);
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(fixture.configuration(), parent, fixture);
        requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), fixture.withEnvironment(fixture.nativeEnvironment))));
    }

    /** Rejects an independently observed parent environment that changes between native registration boundaries. */
    @Test
    public void rejectsChangingRetainedParentEnvironment()
    {
        var fixture = new Fixture();
        fixture.replaceParentEnvironmentAt = 2;
        var parent = new LinuxCargoRegistration.Parent(fixture.cargo, fixture.nativeParentExecutable, 7, fixture.nativeParentImage);
        var authenticator = LinuxCargoRegistration.SharedSecrets.create(fixture.configuration(), parent, fixture);
        requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper, fixture.binding()));
    }

    /**
     * A changed ready parent executable is rejected at either native identity boundary.
     */
    @Test
    public void rejectsChangedReadyParentImage()
    {
        for (int changedAt : List.of(1, 4))
        {
            var fixture = new Fixture();
            Path retainedExecutable = fixture.root.resolve("build-script-build");
            fixture.nativeParentExecutable = retainedExecutable;
            fixture.sameFileFailures.add(changedAt);
            var parent = new LinuxCargoRegistration.Parent(fixture.cargo, retainedExecutable, 7, fixture.nativeParentImage);
            var authenticator = LinuxCargoRegistration.SharedSecrets.create(fixture.configuration(), parent, fixture);
            requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Replacing an executable at the same pathname cannot reuse a previously ready parent's authority.
     */
    @Test
    public void rejectsReplacedParentInodeAtSamePath()
    {
        for (int changedAt : List.of(1, 2))
        {
            var fixture = new Fixture();
            var parent = new LinuxCargoRegistration.Parent(fixture.cargo, fixture.nativeParentExecutable, 7,
                fixture.nativeParentImage);
            fixture.replaceImageAt = changedAt;
            var authenticator = LinuxCargoRegistration.SharedSecrets.create(fixture.configuration(), parent, fixture);
            requireException(IOException.class, () -> authenticator.authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Rejects claimed identities that differ from the captured native socket credentials.
     */
    @Test
    public void rejectsClaimedAndCapturedIdentityChanges()
    {
        var fixture = new Fixture();
        for (var claimed : List.of(new LinuxBorrowedStreams.Peer(72, 81, 1000, 1000),
            new LinuxBorrowedStreams.Peer(71, 82, 1000, 1000), new LinuxBorrowedStreams.Peer(71, 81, 1001, 1000),
            new LinuxBorrowedStreams.Peer(71, 81, 1000, 1001)))
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper,
                fixture.binding(1, new byte[32], claimed, fixture.endpoint(), fixture.launch)));
        requireException(IOException.class, () -> fixture.authenticator().authenticate(
            new LinuxBorrowedStreams.Peer(71, 81, 1001, 1000), fixture.binding()));
        requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.cargo, fixture.binding()));
    }

    /**
     * Rejects attempted registration with a controller-only sequence or capability.
     */
    @Test
    public void rejectsInitialAttemptAuthority()
    {
        var fixture = new Fixture();
        byte[] capability = new byte[32];
        capability[7] = 42;
        for (var binding : List.of(fixture.binding(9, new byte[32], fixture.wrapper, fixture.endpoint(), fixture.launch),
            fixture.binding(1, capability, fixture.wrapper, fixture.endpoint(), fixture.launch)))
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper, binding));
    }

    /**
     * Requires the complete trusted prefix rather than accepting a compiler-command suffix.
     */
    @Test
    public void rejectsChangedNativeCommandPrefix()
    {
        for (int change = 0; change < 4; change++)
        {
            var fixture = new Fixture();
            switch (change)
            {
                case 0 -> fixture.nativeArguments = new ArrayList<>(fixture.launch.command());
                case 1 -> fixture.nativeArguments.addFirst("untrusted-prefix");
                case 2 -> fixture.nativeArguments.set(3, "AnotherWrapper");
                case 3 -> fixture.nativeArguments.add("extra-argument");
                default -> throw new AssertionError(change);
            }
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Checks actual Cargo/wrapper executable and working-directory identities, including the final recheck.
     */
    @Test
    public void rejectsExecutableAndDirectoryIdentityMismatch()
    {
        for (int failedObservation = 1; failedObservation <= 5; failedObservation++)
        {
            var fixture = new Fixture();
            fixture.sameFileFailures.add(failedObservation);
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Checks direct Cargo parentage before and after all compiler observations.
     */
    @Test
    public void rejectsParentChanges()
    {
        for (int failureAt : List.of(1, 2))
        {
            var fixture = new Fixture();
            fixture.parentFailureAt = failureAt;
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Preserves native live-identity rejection for both births, credentials and PID namespace at both boundaries.
     */
    @Test
    public void preservesLiveIdentityFailures()
    {
        for (int failureAt = 1; failureAt <= 4; failureAt++)
        {
            var fixture = new Fixture();
            fixture.liveFailureAt = failureAt;
            fixture.liveFailure = new IOException("Native birth, credentials or common namespace changed");
            requireThat(requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper,
                fixture.binding())), "exactNativeFailure").isEqualTo(fixture.liveFailure);
        }
    }

    /**
     * Unexpected unchecked native failures retain their exact throwable rather than becoming authentication IO.
     */
    @Test
    public void preservesUnexpectedNativeThrowables()
    {
        for (Throwable failure : List.of(new IllegalStateException("Unexpected observation failure"),
            new AssertionError("Unexpected native error")))
        {
            var fixture = new Fixture();
            fixture.liveFailureAt = 1;
            fixture.liveFailure = failure;
            requireThat(requireException(failure.getClass(), () -> fixture.authenticator().authenticate(fixture.wrapper,
                fixture.binding())), "exactUnexpectedFailure").isEqualTo(failure);
        }
    }

    /**
     * Rejects altered jobserver entries even when the actual environment agrees with the represented environment.
     */
    @Test
    public void rejectsChangedInvocationEnvironment()
    {
        var fixture = new Fixture();
        fixture.nativeEnvironment.put("CARGO_MAKEFLAGS", "--jobserver-auth=fifo:/other");
        var represented = fixture.withEnvironment(fixture.nativeEnvironment);
        requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), represented)));
        var mismatch = new Fixture();
        mismatch.nativeEnvironment.put("ADDED", "changed");
        requireException(IOException.class, () -> mismatch.authenticator().authenticate(mismatch.wrapper, mismatch.binding()));
    }

    /**
     * Uses trusted execution context while permitting self-reported containment to be discarded.
     * @throws IOException if valid trusted containment derivation fails
     */
    @Test
    public void reconstructsTrustedExecutionContext() throws IOException
    {
        var fixture = new Fixture();
        var context = fixture.launch;
        var derived = new LinuxProcessTree.Launch(fixture.root.resolve("untrusted-group"), fixture.root.resolve("untrusted-scratch"),
            context.javaExecutable(), context.classPath(), context.command(), context.environment(), context.directory(),
            context.uid(), context.gid(), context.executionMode());
        requireThat(fixture.authenticator().authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), derived)).arguments(), "verifiedCommand").
            isEqualTo(context.command());
        var changed = new LinuxProcessTree.Launch(context.cgroupParent(), context.scratchParent(), fixture.root.resolve("other-java"),
            context.classPath(), context.command(), context.environment(), context.directory(), context.uid(), context.gid(), context.executionMode());
        requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper,
            fixture.binding(1, new byte[32], fixture.wrapper, fixture.endpoint(), changed)));
    }

    /**
     * Requires the exact normalized PID endpoint rather than any socket inside the registration tree.
     */
    @Test
    public void rejectsWrongEndpointNames()
    {
        var fixture = new Fixture();
        for (Path path : List.of(fixture.endpoint().resolveSibling("other.sock"),
            fixture.endpoint().getParent().resolve("../71/streams.sock"), fixture.root.resolve("streams.sock")))
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper,
                fixture.binding(1, new byte[32], fixture.wrapper, path, fixture.launch)));
    }

    /**
     * Rejects wrong inode types, owners, permissions and observed directory or endpoint replacements.
     */
    @Test
    public void rejectsEndpointAttributesAndReplacements()
    {
        for (int change = 0; change < 10; change++)
        {
            var fixture = new Fixture();
            Path directory = fixture.endpoint().getParent();
            Path endpoint = fixture.endpoint();
            switch (change)
            {
                case 0 -> fixture.entries.put(directory, new LinuxCargoRegistration.Entry(directory, 1001, 0040700));
                case 1 -> fixture.entries.put(directory, new LinuxCargoRegistration.Entry(directory, 1000, 0040755));
                case 2 -> fixture.entries.put(directory, new LinuxCargoRegistration.Entry(directory, 1000, 0120700));
                case 3 -> fixture.entries.put(directory, new LinuxCargoRegistration.Entry(directory, 1000, 0100700));
                case 4 -> fixture.entries.put(endpoint, new LinuxCargoRegistration.Entry(endpoint, 1001, 0140600));
                case 5 -> fixture.entries.put(endpoint, new LinuxCargoRegistration.Entry(endpoint, 1000, 0120600));
                case 6 -> fixture.entries.put(endpoint, new LinuxCargoRegistration.Entry(endpoint, 1000, 0100600));
                case 7 -> fixture.replacements.put(directory, new LinuxCargoRegistration.Entry("replacement", 1000, 0040700));
                case 8 -> fixture.replacements.put(endpoint, new LinuxCargoRegistration.Entry("replacement", 1000, 0140600));
                case 9 -> fixture.replacements.put(directory, new LinuxCargoRegistration.Entry(directory, 1000, 0040755));
                default -> throw new AssertionError(change);
            }
            requireException(IOException.class, () -> fixture.authenticator().authenticate(fixture.wrapper, fixture.binding()));
        }
    }

    /**
     * Rejects invalid trusted configuration at resource-free construction time.
     */
    @Test
    public void rejectsInvalidTrustedConstruction()
    {
        var fixture = new Fixture();
        var policy = fixture.configuration();
        requireException(IllegalArgumentException.class, () -> new LinuxCargoRegistration.Configuration(Path.of("relative"),
            policy.wrapperExecutable(), policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), policy.requiredEnvironment()));
        requireException(IllegalArgumentException.class, () -> new LinuxCargoRegistration.Configuration(policy.cargoExecutable(),
            policy.wrapperExecutable(), policy.rustcExecutable(), List.of(), policy.registrationRoot(), policy.context(), policy.requiredEnvironment()));
        requireException(IllegalArgumentException.class, () -> new LinuxCargoRegistration.Configuration(policy.cargoExecutable(),
            policy.wrapperExecutable(), policy.rustcExecutable(), policy.wrapperPrefix(), policy.registrationRoot(), policy.context(), Map.of("A=B", "value")));
        requireException(IllegalArgumentException.class, () -> new LinuxCargoRegistration(policy,
            new LinuxBorrowedStreams.Peer(61, 91, 1001, 1000)));
        requireThat(fixture.liveCalls, "noNativeInspection").isEqualTo(0);
    }

    /**
     * Native vector decoding preserves explicit empty arguments, whitespace and embedded equals in values.
     * @throws IOException if exact native fixture bytes cannot be decoded
     */
    @Test
    public void readsExactNativeVectors() throws IOException
    {
        byte[] arguments = new byte[] {'a', 0, 0, ' ', 'b', ' ', 0};
        try (var input = new ByteArrayInputStream(arguments))
        {
            requireThat(LinuxCargoRegistration.SharedSecrets.arguments(input), "nativeArguments").isEqualTo(List.of("a", "", " b "));
        }
        try (var input = new ByteArrayInputStream(new byte[] {0}))
        {
            requireThat(LinuxCargoRegistration.SharedSecrets.arguments(input), "singleEmptyArgument").isEqualTo(List.of(""));
        }
        try (var input = new ByteArrayInputStream(new byte[0]))
        {
            requireThat(LinuxCargoRegistration.SharedSecrets.environment(input), "emptyEnvironment").isEqualTo(Map.of());
        }
        try (var input = bytes("EMPTY=\0EQ=a=b\0"))
        {
            requireThat(LinuxCargoRegistration.SharedSecrets.environment(input), "nativeEnvironment").isEqualTo(Map.of("EMPTY", "", "EQ", "a=b"));
        }
    }

    /**
     * Rejects malformed native framing, UTF-8, duplicate names and unsupported byte counts.
     * @throws IOException if fixture stream cleanup fails
     */
    @Test
    public void rejectsInvalidNativeVectors() throws IOException
    {
        for (byte[] invalid : List.of(new byte[0], new byte[] {'a'}, new byte[] {(byte) 0xc3, 0x28, 0},
            new byte[LinuxCargoTransport.MAX_FRAME_BYTES + 1]))
        {
            try (var input = new ByteArrayInputStream(invalid))
            {
                requireException(IOException.class, () -> LinuxCargoRegistration.SharedSecrets.arguments(input));
            }
        }
        for (String invalid : List.of("A=1\0A=2\0", "=value\0", "MISSING\0", "\0"))
        {
            try (var input = bytes(invalid))
            {
                requireException(IOException.class, () -> LinuxCargoRegistration.SharedSecrets.environment(input));
            }
        }
        byte[] maximum = new byte[LinuxCargoTransport.MAX_FRAME_BYTES];
        Arrays.fill(maximum, (byte) 'x');
        maximum[maximum.length - 1] = 0;
        try (var input = new ByteArrayInputStream(maximum))
        {
            requireThat(LinuxCargoRegistration.SharedSecrets.arguments(input).getFirst().length(), "supportedByteBoundary").
                isEqualTo(maximum.length - 1);
        }
    }

    /**
     * Encodes exact fixture vector text into a caller-owned stream.
     * @param text complete raw vector including native delimiters
     * @return stream owned by the individual fixture scope
     */
    private static ByteArrayInputStream bytes(String text)
    {
        return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Owns one portable policy case, including its modeled native facts.
     */
    private static final class Fixture implements LinuxCargoRegistration.Observer
    {
        /**
         * Platform-native absolute namespace representing trusted configuration.
         */
        private final Path root = Path.of("registration-fixture").toAbsolutePath();
        /**
         * Independently captured wrapper identity.
         */
        private final LinuxBorrowedStreams.Peer wrapper = new LinuxBorrowedStreams.Peer(71, 81, 1000, 1000);
        /**
         * Retained Cargo identity.
         */
        private final LinuxBorrowedStreams.Peer cargo = new LinuxBorrowedStreams.Peer(61, 91, 1000, 1000);
        /**
         * Trusted complete wrapper command prefix.
         */
        private final List<String> prefix = List.of(root.resolve("java").toString(), "-cp", root.resolve("bootstrap.jar").toString(), "Wrapper", "wrapper");
        /**
         * Exact compiler input containing explicit empty and whitespace arguments.
         */
        private final LinuxProcessTree.Launch launch = new LinuxProcessTree.Launch(root, root, root.resolve("java"),
            root.resolve("bootstrap.jar"), List.of(root.resolve("rustc").toString(), "", "a b", "  "),
            Map.of("CARGO_MAKEFLAGS", "--jobserver-auth=fifo:/fixture", "ADDED", "a=b"), root, 1000, 1000,
            LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        /**
         * Independent mutable native argv.
         */
        private List<String> nativeArguments = new ArrayList<>(prefix);
        /**
         * Independent mutable native initial environment.
         */
        private final Map<String, String> nativeEnvironment = new LinkedHashMap<>(launch.environment());
        /** Independently observed initial environment of the retained native compiler parent. */
        private final Map<String, String> nativeParentEnvironment = new LinkedHashMap<>(launch.environment());
        /** Number of independent parent environment observations. */
        private int parentEnvironmentCalls;
        /** Observation that supplies a changed parent environment, or zero when stable. */
        private int replaceParentEnvironmentAt;
        /**
         * Independent original no-follow inode observations.
         */
        private final Map<Path, LinuxCargoRegistration.Entry> entries = new LinkedHashMap<>();
        /**
         * Inodes observed instead at the final observation boundary.
         */
        private final Map<Path, LinuxCargoRegistration.Entry> replacements = new LinkedHashMap<>();
        /**
         * Number of inode observations for each independent path.
         */
        private final Map<Path, Integer> entryCalls = new LinkedHashMap<>();
        /**
         * File identity observations selected to fail.
         */
        private final Set<Integer> sameFileFailures = new HashSet<>();
        /**
         * File identity observations performed so far.
         */
        private int sameFileCalls;
        /** Number of independently requested executable image observations. */
        private int imageCalls;
        /** Executable image observation at which a replacement inode becomes visible, or zero without replacement. */
        private int replaceImageAt;
        /** Independent current executable inode observations, separate from a mutable path name. */
        private Map<String, Object> nativeParentImage = Map.of("dev", 1L, "ino", 2L, "size", 3L,
            "lastModifiedTime", 4L, "ctime", 5L);
        /** Independently observed current image of the direct compiler-wrapper parent. */
        private Path nativeParentExecutable = root.resolve("cargo");
        /** Independently observed working directory of the current compiler wrapper. */
        private Path nativeDirectory = launch.directory();
        /**
         * Direct parent observations performed so far.
         */
        private int parentCalls;
        /**
         * Direct parent observation selected to report a different parent.
         */
        private int parentFailureAt;
        /**
         * Live identity observations performed so far.
         */
        private int liveCalls;
        /**
         * Live identity observation selected to fail.
         */
        private int liveFailureAt;
        /**
         * Exact independently supplied native failure.
         */
        private Throwable liveFailure;

        /**
         * Creates independent mutable native observations.
         */
        private Fixture()
        {
            nativeArguments.addAll(launch.command());
            entries.put(endpoint().getParent(), new LinuxCargoRegistration.Entry(endpoint().getParent(), 1000, 0040700));
            entries.put(endpoint(), new LinuxCargoRegistration.Entry(endpoint(), 1000, 0140600));
        }
        /**
         * Constructs trusted policy independent of the receipt.
         * @return complete invocation configuration
         */
        private LinuxCargoRegistration.Configuration configuration()
        {
            return new LinuxCargoRegistration.Configuration(root.resolve("cargo"), root.resolve("java"), root.resolve("rustc"),
                prefix, root.resolve("registrations"), launch, Map.of("CARGO_MAKEFLAGS", "--jobserver-auth=fifo:/fixture"));
        }
        /**
         * Locates the one represented PID endpoint.
         * @return exact absolute endpoint
         */
        private Path endpoint() { return root.resolve("registrations").resolve("71").resolve("streams.sock"); }
        /**
         * Supplies untrusted receipt values matching the represented native process.
         * @return initial canonical binding
         */
        private LinuxBorrowedStreams.Binding binding()
        {
            return binding(1, new byte[32], wrapper, endpoint(), launch);
        }
        /**
         * Represents one changed receipt independently of trusted policy and actual native facts.
         * @param sequence claimed sequence
         * @param capability claimed capability
         * @param peer claimed native identity
         * @param endpoint claimed endpoint
         * @param represented claimed compiler execution context
         * @return untrusted receipt
         */
        private LinuxBorrowedStreams.Binding binding(long sequence, byte[] capability, LinuxBorrowedStreams.Peer peer,
            Path endpoint, LinuxProcessTree.Launch represented)
        {
            var stream = new LinuxBorrowedStreams.Terminal(false, "", 0, 0, List.of());
            return new LinuxBorrowedStreams.Binding(new UUID(1, 2), sequence, peer, endpoint, capability,
                new LinuxBorrowedStreams.TerminalContext(1, 1, List.of(stream, stream, stream)), represented);
        }
        /**
         * Copies represented execution with an independently supplied environment.
         * @param environment represented compiler environment
         * @return represented launch without mutating trusted policy
         */
        private LinuxProcessTree.Launch withEnvironment(Map<String, String> environment)
        {
            return new LinuxProcessTree.Launch(launch.cgroupParent(), launch.scratchParent(), launch.javaExecutable(),
                launch.classPath(), launch.command(), environment, launch.directory(), launch.uid(), launch.gid(), launch.executionMode());
        }
        /**
         * Supplies the production policy with modeled independent observations.
         * @return resource-free authenticator
         */
        private LinuxCargoRegistration authenticator()
        {
            return LinuxCargoRegistration.SharedSecrets.create(configuration(), cargo, this);
        }
        @Override public void requireLive(LinuxBorrowedStreams.Peer peer) throws IOException
        {
            if (++liveCalls != liveFailureAt) return;
            if (liveFailure instanceof IOException checked) throw checked;
            if (liveFailure instanceof RuntimeException unchecked) throw unchecked;
            if (liveFailure instanceof Error error) throw error;
            throw new AssertionError("Fixture failed to provide a native failure");
        }
        @Override public Map<String, Object> executableImage(long pid)
        {
            if (++imageCalls == replaceImageAt)
                return Map.of("dev", 1L, "ino", 99L, "size", 3L, "lastModifiedTime", 4L, "ctime", 5L);
            return nativeParentImage;
        }
        @Override public long parent(long pid) { return ++parentCalls == parentFailureAt ? 999 : cargo.pid(); }
        @Override public boolean sameFile(Path observed, Path expected)
        {
            if (sameFileFailures.contains(++sameFileCalls)) return false;
            if (observed.equals(nativeDirectory)) return expected.equals(nativeDirectory);
            if (observed.getFileName().toString().equals("cwd")) return expected.equals(nativeDirectory);
            Path actual = observed.getParent().getFileName().toString().equals("61") ? nativeParentExecutable : root.resolve("java");
            return expected.equals(actual);
        }
        @Override public List<String> arguments(long pid) { return List.copyOf(nativeArguments); }
        @Override public Map<String, String> environment(long pid)
        {
            if (pid != cargo.pid()) return Map.copyOf(nativeEnvironment);
            if (++parentEnvironmentCalls == replaceParentEnvironmentAt)
            {
                var changed = new LinkedHashMap<>(nativeParentEnvironment);
                changed.put("ADDED", "changed-after-first-observation");
                return Map.copyOf(changed);
            }
            return Map.copyOf(nativeParentEnvironment);
        }
        @Override public LinuxCargoRegistration.Entry entry(Path path) throws IOException
        {
            int observation = entryCalls.merge(path, 1, Integer::sum);
            var entry = observation > 1 && replacements.containsKey(path) ? replacements.get(path) : entries.get(path);
            if (entry == null) throw new IOException("No native fixture inode exists");
            return entry;
        }
    }
}
