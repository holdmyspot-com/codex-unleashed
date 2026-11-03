package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoImages;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises current held-image membership without authenticating compiler actions or fabricating native evidence. */
public final class LinuxCargoImagesTest
{
    /**
     * Skips this class on file systems without POSIX attributes, which its POSIX-only production code requires.
     *
     * @throws org.testng.SkipException if the default file system lacks the POSIX attribute view
     */
    @org.testng.annotations.BeforeClass
    public void requirePosix()
    {
        TestRequirements.requirePosixFileSystem();
    }

    /** Creates tests whose scopes and image observations belong to each method. */
    public LinuxCargoImagesTest() {}

    /** The workload root retains its exact observed subgroup beneath the prepared owner.
     * @throws IOException if authenticated root readiness fails
     */
    @Test
    public void acceptsPreparedRootInNestedContainment() throws IOException
    {
        try (var fixture = new Fixture(true))
        {
            requireThat(fixture.resumed, "resumed").isEmpty();
        }
    }

    /** A native static image must become ready without creating a second Frida attachment.
     * @throws IOException if native image admission fails
     */
    @Test
    public void preservesSoleNativeStaticOwner() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 10);
            fixture.nativeImage = true;
            fixture.owner.advance(fixture.event(0));
            requireThat(fixture.nativePrepared, "nativePrepared").isTrue();
            requireThat(fixture.enrolled, "enrolled").isEqualTo(List.of(11L));
            requireThat(fixture.resumed, "resumed").isEqualTo(List.of(11L));
        }
    }

    /** Enumerates identities that subgroup selection must never repair.
     * @return independently changed root identity fields
     */
    @DataProvider(parallel = true)
    public Object[][] invalidRoots()
    { return new Object[][] {{"birth"}, {"parent"}, {"uid"}, {"gid"}, {"image"}, {"containment"}}; }

    /** Exact prepared-root identity remains required when retaining its actual subgroup.
     * @param mutation one independent identity replacement
     * @throws IOException if controlled fixture setup fails
     */
    @Test(dataProvider = "invalidRoots")
    public void rejectsChangedPreparedRoot(String mutation) throws IOException
    {
        try (var fixture = new Fixture())
        {
            var expected = fixture.identity(10, 1, 9, fixture.cargo);
            var actual = new LinuxCargoImages.Identity(10, mutation.equals("birth") ? 2 : 1,
                mutation.equals("parent") ? 8 : 9, mutation.equals("uid") ? 0 : 1000,
                mutation.equals("gid") ? 0 : 1001, mutation.equals("image") ? fixture.compiler : fixture.cargo,
                mutation.equals("containment") ? fixture.group.resolveSibling("foreign") : fixture.group.resolve("workload"));
            fixture.nativeImages.put(10L, actual);
            var owner = new LinuxCargoImages(fixture.scope, fixture, expected,
                new LinuxCargoImages.Policy(1000, 1001, fixture.cargo), (image, generation) -> {},
                Duration.ofSeconds(1), () -> 0L);
            requireException(IOException.class, owner::start);
            owner.close(() -> true);
        }
    }

    /** A held fork remains admissible after its authenticated parent retires into the owned subreaper.
     * @throws IOException if independently authenticated adoption is rejected
     */
    @Test
    public void acceptsHeldForkAfterOriginalParentRetires() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 9);
            fixture.nativeImages.remove(10L);
            fixture.adoptionAuthorized = true;
            fixture.owner.advance(fixture.event(0));
            requireThat(fixture.resumed, "resumed").isEqualTo(List.of(11L));
            requireThat(fixture.adoptions, "adoptions").isEqualTo(2);
        }
    }

    /** Retired-parent notifications cannot authorize an unrelated or replaced adoptive parent.
     * @throws IOException if fixture initialization fails
     */
    @Test
    public void rejectsUnprovedSupervisorAdoption() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 9);
            fixture.nativeImages.remove(10L);
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(0)));
            requireThat(fixture.resumed, "resumed").isEmpty();
        }
    }

    /** Adoption authority is checked again after consumer readiness before the sole resume.
     * @throws IOException if fixture initialization fails
     */
    @Test
    public void rejectsAdoptionReplacementDuringEnrollment() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 9);
            fixture.nativeImages.remove(10L);
            fixture.adoptionAuthorized = true;
            fixture.onEnrollment = () -> fixture.adoptionAuthorized = false;
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(0)));
            requireThat(fixture.resumed, "resumed").isEmpty();
        }
    }

    /** A known native birth may exec after kernel adoption without losing its original lineage.
     * @throws IOException if authenticated exec adoption fails
     */
    @Test
    public void acceptsRetainedExecAfterOriginalParentRetires() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.owner.advance(fixture.event(2));
            fixture.putChild(2, fixture.compiler, fixture.group, 1000, 1001, 9);
            fixture.nativeImages.remove(10L);
            fixture.adoptionAuthorized = true;
            fixture.owner.advance(fixture.event(1));
            requireThat(fixture.resumed, "resumed").isEqualTo(List.of(11L, 11L));
            requireThat(fixture.adoptions, "adoptions").isEqualTo(2);
        }
    }

    /** Enumerates native terminal and unsafe parent observations independently from child notifications.
     * @return stat records and whether the original parent has genuinely retired
     */
    @DataProvider(parallel = true)
    public Object[][] retiredParentObservations()
    {
        return new Object[][] {{null, true}, {parentStat("Z", 1), true}, {parentStat("X", 1), true},
            {parentStat("S", 1), false}, {parentStat("R", 1), false}, {parentStat("Z", 2), false},
            {"malformed", false}};
    }

    /** PID reuse and a live parent cannot be accepted as kernel retirement.
     * @param stat current independently read stat, or native file absence
     * @param retired whether the exact original birth is terminal
     * @throws IOException if a valid retirement observation fails
     */
    @Test(dataProvider = "retiredParentObservations")
    public void authenticatesOriginalParentRetirement(String stat, boolean retired) throws IOException
    {
        if (retired) LinuxCargoImages.requireRetiredParent(1, stat);
        else requireException(IOException.class, () -> LinuxCargoImages.requireRetiredParent(1, stat));
    }

    /** Constructs a stat record whose process name exercises the existing last-parenthesis parsing.
     * @param state native process state
     * @param birth native start ticks
     * @return a complete minimal native record through field22
     */
    private static String parentStat(String state, long birth)
    { return "10 (parent (git)) " + state + " 0".repeat(18) + " " + birth; }

    /** Supplies direct kernel script forms and their exact argument prefixes.
     * @return bounded direct shebang headers and kernel-inserted arguments
     */
    @DataProvider(parallel = true)
    public Object[][] directScripts()
    {
        return new Object[][] {{"#!/bin/sh\n", List.of("/bin/sh")},
            {"#! \t/bin/sh  -e -x \t\n", List.of("/bin/sh", "-e -x")},
            {"#!/bin/sh", List.of("/bin/sh")}};
    }

    /** Keeps the interpreter and its single optional kernel argument separate from the requested script.
     * @param header exact requested source header
     * @param prefix expected kernel argument prefix
     * @throws IOException if a valid direct header is rejected
     */
    @Test(dataProvider = "directScripts")
    public void resolvesDirectScriptHeader(String header, List<String> prefix) throws IOException
    {
        requireThat(LinuxCargoImages.directInterpreter(header.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "interpreterPrefix").isEqualTo(prefix);
    }

    /** Supplies unsupported source formats and incomplete direct interpreters.
     * @return malformed direct headers
     */
    @DataProvider(parallel = true)
    public Object[][] invalidScripts()
    { return new Object[][] {{"ELF"}, {"#!"}, {"#! \t\n"}, {"#!relative\n"}, {"#!/" + "a".repeat(253)}}; }

    /** Arbitrary binfmt and malformed direct scripts cannot select an interpreter.
     * @param header invalid source header
     */
    @Test(dataProvider = "invalidScripts")
    public void rejectsInvalidDirectScript(String header)
    {
        requireException(IOException.class, () -> LinuxCargoImages.directInterpreter(
            header.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Direct binaries retain their actual inode and never become interpreted images.
     * @throws IOException if native filesystem identity is unavailable
     */
    @Test
    public void acceptsExactNativeBinary() throws IOException
    {
        requireLinuxFiles();
        Path binary = Path.of(System.getProperty("java.home"), "bin", "java");
        var binding = LinuxCargoImages.executableIdentity(binary, binary, List.of(binary.toString()));
        requireThat(binding.requested(), "requested").isEqualTo(binary);
        requireThat(binding.actual(), "actual").isEqualTo(binary.toRealPath());
        requireThat(binding.arguments(), "arguments").isEmpty();
    }

    /** A direct script retains its actual interpreter inode, argv and requested source through readiness.
     * @throws IOException if native source identity is unavailable
     */
    @Test
    public void verifiesNativeScriptInterpreterAndSource() throws IOException
    {
        requireLinuxFiles();
        Path script = Files.createTempFile("held-script-", ".sh");
        try
        {
            Files.writeString(script, "#!/bin/sh\necho held\n");
            List<String> arguments = List.of("/bin/sh", script.toString(), "argument");
            var binding = LinuxCargoImages.executableIdentity(Path.of("/bin/sh"), script, arguments);
            requireThat(binding.requested(), "requestedScript").isEqualTo(script);
            requireThat(binding.actual(), "actualInterpreter").isEqualTo(Path.of("/bin/sh").toRealPath());
            requireThat(binding.arguments(), "kernelArgv").isEqualTo(arguments);
            requireException(IOException.class, () -> LinuxCargoImages.executableIdentity(Path.of("/bin/false"), script, arguments));
            requireException(IOException.class, () -> LinuxCargoImages.executableIdentity(Path.of("/bin/sh"), script,
                List.of("/bin/sh", script.resolveSibling("wrong").toString())));
            Files.writeString(script, "#!/bin/sh\n# changed source\n");
            requireThat(binding.equals(LinuxCargoImages.executableIdentity(Path.of("/bin/sh"), script, arguments)),
                "retainedSourceUnchanged").isFalse();
        }
        finally { Files.deleteIfExists(script); }
    }

    /** Native UNIX metadata checks run only on their owning platform.
     * @throws org.testng.SkipException if the current platform cannot expose Linux native identity
     */
    private static void requireLinuxFiles()
    {
        if (!System.getProperty("os.name").equals("Linux"))
            throw new org.testng.SkipException("Requires Linux executable inode and UNIX source metadata");
    }

    /** A requested-source replacement during image readiness never reaches the held resume.
     * @throws IOException if controlled root setup fails
     */
    @Test
    public void rejectsSourceReplacementDuringReadiness() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.trackSource = true;
            fixture.onReadiness = () -> fixture.sourceEpoch++;
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(fixture.resumed, "resumed").isEmpty();
        }
    }

    /** First EXEC uses its independent actual parent instead of the raw self-parent notification.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void acceptsFirstExecWithRetainedActualParent() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.owner.advance(fixture.event(1));
            requireThat(fixture.resumed, "resumed").isEqualTo(List.of(11L));
            requireThat(fixture.enrolled, "enrolled").isEqualTo(List.of(11L));
            requireThat(fixture.steps.subList(fixture.steps.size() - 4, fixture.steps.size()), "finalChecks").
                isEqualTo(List.of("inspect:11", "inspect:10", "inspect:11", "resume:11"));
        }
    }

    /** Supplies supported creation origins, each with its own fixture in parallel execution.
     * @return fork and spawn native origin values
     */
    @DataProvider(parallel = true)
    public Object[][] creations() { return new Object[][] {{0}, {2}}; }

    /** New children retain their independently verified parent through readiness.
     * @param origin fork or spawn native value
     * @throws IOException if controlled readiness unexpectedly fails
     */
    @Test(dataProvider = "creations")
    public void acceptsSupportedCreation(int origin) throws IOException
    {
        try (var fixture = new Fixture())
        {
            if (origin == 0) fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 10);
            fixture.owner.advance(fixture.event(origin));
            requireThat(fixture.resumed, "resumed").isEqualTo(List.of(11L));
        }
    }

    /** Supplies each supported new-image notification shape.
     * @return fork, first EXEC and spawn origins
     */
    @DataProvider(parallel = true)
    public Object[][] newImages() { return new Object[][] {{0}, {1}, {2}}; }

    /** Enumerates independent invalid membership inputs.
     * @return mutation applied before first-EXEC inspection
     */
    @DataProvider(parallel = true)
    public Object[][] invalidMembership()
    {
        return new Object[][] {{"unretained-parent"}, {"parent-birth"}, {"containment"}, {"credentials"},
            {"event-parent"}, {"event-path"}, {"absent-path"}, {"relative-path"}, {"dead-parent"}, {"origin"}};
    }

    /** Invalid current membership never reaches image resume.
     * @param mutation one controlled independent mismatch
     * @throws IOException if root fixture setup fails
     */
    @Test(dataProvider = "invalidMembership")
    public void rejectsInvalidFirstExec(String mutation) throws IOException
    {
        try (var fixture = new Fixture())
        {
            FridaRuntime.Child event = fixture.event(1);
            switch (mutation)
            {
                case "unretained-parent" -> fixture.putChild(2, fixture.compiler, fixture.group, 1000, 1001, 99);
                case "parent-birth" -> fixture.nativeImages.put(10L, fixture.identity(10, 99, 9, fixture.cargo));
                case "containment" -> fixture.putChild(2, fixture.compiler, fixture.group.resolveSibling("foreign"), 1000, 1001, 10);
                case "credentials" -> fixture.putChild(2, fixture.compiler, fixture.group, 0, 1001, 10);
                case "event-parent" -> event = new FridaRuntime.Child(11, 10, 1, fixture.compiler.toString());
                case "event-path" -> event = new FridaRuntime.Child(11, 11, 1, fixture.cargo.toString());
                case "absent-path" -> event = new FridaRuntime.Child(11, 11, 1, null);
                case "relative-path" -> event = new FridaRuntime.Child(11, 11, 1, "relative");
                case "dead-parent" -> fixture.nativeImages.remove(10L);
                case "origin" -> event = new FridaRuntime.Child(11, 11, 3, fixture.compiler.toString());
                default -> throw new IllegalArgumentException(mutation);
            }
            FridaRuntime.Child supplied = event;
            requireException(IOException.class, () -> fixture.owner.advance(supplied));
            requireThat(fixture.resumed.isEmpty(), "noResume").isTrue();
        }
    }

    /** An independently matched relative executable reaches readiness without changing its request.
     * @throws IOException if the legitimate held image is refused
     */
    @Test
    public void acceptsRelativeExecutableWithIndependentIdentity() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.owner.advance(new FridaRuntime.Child(11, 11, 1, fixture.compiler.getFileName().toString()));
            requireThat(fixture.resumed, "relativeResume").isEqualTo(List.of(11L));
        }
    }

    /** Parent replacement during consumer readiness remains a terminal pre-resume fault.
     * @throws IOException if controlled setup or expected successful readiness fails
     * @param origin supported new-image notification shape
     */
    @Test(dataProvider = "newImages")
    public void rejectsParentReplacementDuringEnrollment(int origin) throws IOException
    {
        try (var fixture = new Fixture())
        {
            if (origin == 0) fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 10);
            fixture.onEnrollment = () -> fixture.nativeImages.put(10L, fixture.identity(10, 99, 9, fixture.cargo));
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(origin)));
            requireThat(fixture.resumed.isEmpty(), "noResume").isTrue();
            requireThat(fixture.enrolled, "parentMutationReachedEnrollment").isEqualTo(List.of(11L));
            requireThat(fixture.closed, "connectionsRetainedBeforeZero").isEqualTo(0);
        }
    }

    /** Child replacement during readiness cannot inherit the prior image's enrollment.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsChildReplacementDuringEnrollment() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.onEnrollment = () -> fixture.putChild(99, fixture.compiler, fixture.group, 1000, 1001, 10);
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(fixture.enrolled, "childMutationReachedEnrollment").isEqualTo(List.of(11L));
            requireThat(fixture.resumed.isEmpty(), "noResume").isTrue();
        }
    }

    /** A known PID must preserve its original birth even when its current parent remains retained.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsKnownPidBirthReplacement() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.owner.advance(fixture.event(2));
            fixture.putChild(99, fixture.compiler, fixture.group, 1000, 1001, 10);
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(fixture.resumed, "onlyOriginalResume").isEqualTo(List.of(11L));
        }
    }

    /** A known same-birth process may replace its image without changing its retained ancestry.
     * @throws IOException if controlled readiness unexpectedly fails
     */
    @Test
    public void acceptsKnownSameBirthExec() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.owner.advance(fixture.event(2));
            fixture.owner.advance(fixture.event(1));
            requireThat(fixture.resumed, "oneResumePerDistinctTransition").isEqualTo(List.of(11L, 11L));
        }
    }

    /** A same-birth Cargo root may exec its independently matched useful output without compiler adoption.
     * @throws IOException if controlled root setup fails
     */
    @Test
    public void acceptsAuthenticatedRootPayloadExec() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.nativeImages.put(10L, fixture.identity(10, 1, 9, fixture.compiler));
            var event = new FridaRuntime.Child(10, 10, 1, fixture.compiler.toString());
            fixture.owner.advance(event);
            requireThat(fixture.resumed, "payloadResume").isEqualTo(List.of(10L));
        }
    }

    /** A possibly effective resume is never retried after its failure.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void retainsUncertainResumeWithoutReplay() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.failResume = true;
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireException(IllegalStateException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(fixture.resumed, "singleUncertainResume").isEqualTo(List.of(11L));
        }
    }

    /** Cancellation during enrollment retains both connections and never releases the held process.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsCancellationDuringEnrollment() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.onEnrollment = () -> Thread.currentThread().interrupt();
            try { requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1))); }
            finally { Thread.interrupted(); }
            requireThat(fixture.resumed.isEmpty(), "noCanceledResume").isTrue();
        }
    }

    /** Parent replacement while the image connection is becoming ready fails its final parent check.
     * @throws IOException if controlled setup or expected successful readiness fails
     * @param origin supported new-image notification shape
     */
    @Test(dataProvider = "newImages")
    public void rejectsParentReplacementDuringReadiness(int origin) throws IOException
    {
        try (var fixture = new Fixture())
        {
            if (origin == 0) fixture.putChild(2, fixture.cargo, fixture.group, 1000, 1001, 10);
            fixture.onReadiness = () -> fixture.nativeImages.put(10L, fixture.identity(10, 99, 9, fixture.cargo));
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(origin)));
            requireThat(fixture.resumed.isEmpty(), "noChangedParentResume").isTrue();
        }
    }

    /** A changed held child cannot inherit an earlier connection's ready receipt.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void rejectsChildReplacementDuringReadiness() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.onReadiness = () -> fixture.putChild(99, fixture.compiler, fixture.group, 1000, 1001, 10);
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(fixture.enrolled.isEmpty(), "noChangedChildEnrollment").isTrue();
            requireThat(fixture.resumed.isEmpty(), "noChangedChildResume").isTrue();
        }
    }

    /** Partial readiness failure retains every acquired connection until genuine whole-workload zero.
     * @throws IOException if controlled setup or expected successful readiness fails
     */
    @Test
    public void retainsFailedConnectionUntilWholeTreeZero() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.failReady = true;
            requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireException(IOException.class, () -> fixture.owner.close(() -> false));
            requireThat(fixture.closed, "noCloseBeforeZero").isEqualTo(0);
            fixture.owner.close(() -> true);
            requireThat(fixture.closed, "bothPartialAndRootClosed").isEqualTo(2);
        }
    }

    /**
     * Reports the independently authenticated held image when attachment fails, preserving the native cause.
     * @throws IOException if controlled fixture setup or cleanup fails
     */
    @Test
    public void identifiesNativeConnectionFailure() throws IOException
    {
        try (var fixture = new Fixture())
        {
            fixture.connectionFailure = new IOException("controlled-native-attachment-failure");
            var failure = requireException(IOException.class, () -> fixture.owner.advance(fixture.event(1)));
            requireThat(failure.getCause() == fixture.connectionFailure, "originalNativeFailureRetained").isTrue();
            requireThat(failure.getMessage().contains("pid=11,birth=2"), "exactHeldBirthReported").isTrue();
            requireThat(failure.getMessage().contains(fixture.compiler.toString()), "actualHeldExecutableReported").isTrue();
            requireThat(fixture.resumed.isEmpty(), "noResumeAfterAttachmentFailure").isTrue();
        }
    }

    /** Owns independent controlled observations and closes transports only through the explicit test barrier. */
    private static final class Fixture implements LinuxCargoImages.Operations, AutoCloseable
    {
        /** Method-owned Pouch values. */
        private final DefaultToolingScope scope = new DefaultToolingScope();
        /** Exact synthetic root containment. */
        private final Path group = Path.of("cargo-owned").toAbsolutePath();
        /** Pinned root executable. */
        private final Path cargo = Path.of("cargo-fixture").toAbsolutePath();
        /** Independently observed metadata executable. */
        private final Path compiler = Path.of("rustc-fixture").toAbsolutePath();
        /** Current native observations, independent of notification metadata. */
        private final Map<Long, LinuxCargoImages.Identity> nativeImages = new HashMap<>();
        /** Observable sequencing for post-readiness checks. */
        private final List<String> steps = new ArrayList<>();
        /** Actual calls to the controlled sole continuation owner. */
        private final List<Long> resumed = new ArrayList<>();
        /** Image-readiness acknowledgements, never compiler action identities. */
        private final List<Long> enrolled = new ArrayList<>();
        /** Mutation at the external readiness callback. */
        private Runnable onEnrollment = () -> {};
        /** Independent controlled supervisor adoption authority. */
        private boolean adoptionAuthorized;
        /** Number of independent adoption validations. */
        private int adoptions;
        @Override public void requireAdoption(LinuxCargoImages.Identity parent, LinuxCargoImages.Identity child)
            throws IOException
        {
            adoptions++;
            if (!adoptionAuthorized || parent.pid() != 10 || parent.birth() != 1 || child.parent() != 9 ||
                nativeImages.containsKey(parent.pid())) throw new IOException("Unproved controlled adoption");
        }
        /** Whether the child has an independently retained static native trace owner. */
        private boolean nativeImage;
        /** Whether that native owner established its held readiness boundary. */
        private boolean nativePrepared;
        /** Selects independently controlled native readiness without a Frida connection.
         * @param image independently authenticated held image
         * @return whether this image is native-owned
         */
        public boolean prepareNative(LinuxCargoImages.Identity image)
        {
            nativePrepared = nativeImage;
            return nativeImage;
        }
        /** Controlled mutation during connection readiness, before enrollment. */
        private Runnable onReadiness = () -> {};
        /** Whether a child connection reports an invalid ready receipt. */
        private boolean failReady;
        /** Exact native attachment failure selected by one method-local case, or null. */
        private IOException connectionFailure;
        /** Whether the next resume reports uncertain effects. */
        private boolean failResume;
        /** Whether the requested source has a separately observed mutable identity. */
        private boolean trackSource;
        /** Controlled requested-source revision across readiness. */
        private int sourceEpoch;
        /** Successfully closed controlled connections. */
        private int closed;
        /** Production image classification and lifecycle owner. */
        private final LinuxCargoImages owner;

        /** Establishes root readiness without acquiring any native resources.
         * @throws IOException if the controlled root connection fails
         */
        private Fixture() throws IOException { this(false); }

        /** Creates a root in the owner's immediate or nested containment.
         * @param nested whether the actual workload subgroup differs from the owner envelope
         * @throws IOException if root readiness fails
         */
        private Fixture(boolean nested) throws IOException
        {
            var root = identity(10, 1, 9, cargo);
            Path actualGroup = nested ? group.resolve("workload") : group;
            nativeImages.put(10L, new LinuxCargoImages.Identity(10, 1, 9, 1000, 1001, cargo, actualGroup));
            putChild(2, compiler, actualGroup, 1000, 1001, 10);
            owner = new LinuxCargoImages(scope, this, root, new LinuxCargoImages.Policy(1000, 1001, cargo),
                (image, generation) -> { enrolled.add(image.pid()); onEnrollment.run(); }, Duration.ofSeconds(1), () -> 0L);
            owner.start();
            steps.clear();
        }

        /** Constructs a complete immutable observation.
         * @param pid process identity
         * @param birth retained kernel ticks
         * @param parent independent actual parent
         * @param executable independently observed image
         * @return exact test identity
         */
        private LinuxCargoImages.Identity identity(long pid, long birth, long parent, Path executable)
        { return new LinuxCargoImages.Identity(pid, birth, parent, 1000, 1001, executable, group); }

        /** Changes the independent child's current observation.
         * @param birth kernel birth
         * @param executable actual image
         * @param containment actual cgroup
         * @param uid actual UID
         * @param gid actual GID
         * @param parent actual parent
         */
        private void putChild(long birth, Path executable, Path containment, int uid, int gid, long parent)
        { nativeImages.put(11L, new LinuxCargoImages.Identity(11, birth, parent, uid, gid, executable, containment)); }

        /** Copies one native event shape without treating it as authority.
         * @param origin native event kind
         * @return supported event shape
         */
        private FridaRuntime.Child event(int origin)
        { return new FridaRuntime.Child(11, origin == 1 ? 11 : 10, origin, origin == 0 ? null : compiler.toString()); }

        @Override public LinuxCargoImages.Identity inspect(long pid) throws IOException
        {
            steps.add("inspect:" + pid);
            var result = nativeImages.get(pid);
            if (result == null) throw new IOException("Missing controlled native identity");
            return result;
        }

        @Override public LinuxCargoImages.ExecutableIdentity executableIdentity(LinuxCargoImages.Identity image,
            Path expected) throws IOException
        {
            var source = LinuxCargoImages.Operations.super.executableIdentity(image,
                expected.isAbsolute() ? expected : compiler.getParent().resolve(expected));
            return trackSource ? new LinuxCargoImages.ExecutableIdentity(source.requested(), source.actual(),
                source.arguments(), Map.of("epoch", sourceEpoch)) : source;
        }

        @Override public AllocationHook.Connection connection(long pid)
        {
            if (nativeImage) throw new AssertionError("Static native image must not receive a Frida attachment");
            return new AllocationHook.Connection()
            {
                /** One copied exact receipt. */
                private Optional<String> receipt = Optional.empty();
                @Override public void start(String program) throws IOException
                {
                    if (connectionFailure != null) throw connectionFailure;
                    String marker = scope.getJsonMapper().readTree(program.substring(5, program.length() - 2)).asString();
                    receipt = Optional.of(scope.getJsonMapper().writeValueAsString(Map.of("type", "send", "payload",
                        failReady ? "wrong-marker" : marker)));
                    onReadiness.run();
                }
                @Override public Optional<String> pollMessage()
                { var result = receipt; receipt = Optional.empty(); return result; }
                @Override public void post(tools.jackson.databind.JsonNode message) { throw new IllegalStateException("No allocation controls for metadata images"); }
                @Override public void close() { closed++; }
            };
        }
        @Override public void resume(long pid) throws IOException
        {
            resumed.add(pid);
            steps.add("resume:" + pid);
            if (failResume) throw new IOException("Uncertain controlled continuation");
        }
        @Override public void close() throws IOException
        {
            try { owner.close(() -> true); }
            finally { scope.close(); }
        }
    }
}
