package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import org.apache.commons.io.file.PathUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks portable privileged launch policy and Linux filesystem image observations without launching native processes.
 */
public final class LinuxBazelNamespaceHooksTest
{
    /**
     * Creates independent launch policies per invocation.
     */
    public LinuxBazelNamespaceHooksTest() {}

    /** Requires exact collected root identity and original-owner reparenting before admitting its descendant. */
    @Test public void authenticatesOrdinaryRootRetirementLineage()
    {
        TestRequirements.requirePosixFileSystem();
        var root = new LinuxBazelNamespaceHooks.ImageObservation(300, 3, 30, 200, Path.of("/program"),
            "a".repeat(64), Map.of(), List.of("/program"), "0::/attempt", "pid:[sandbox]", "mnt:[sandbox]");
        var terminal = new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(3, 30), 23 << 8);
        requireThat(LinuxBazelNamespaceHooks.authenticatedOrdinaryRootParent(terminal, root, 200, 300, 200),
            "originalOrdinaryRootLineage").isTrue();
        for (LinuxAllocationTrace.Terminal invalid : java.util.Arrays.asList(null,
            new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(4, 30), 23 << 8),
            new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(3, 31), 23 << 8),
            new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(3, 30), 5),
            new LinuxAllocationTrace.Terminal(new LinuxAllocationTrace.Identity(3, 30), 5 | 128)))
            requireThat(LinuxBazelNamespaceHooks.authenticatedOrdinaryRootParent(invalid, root, 200, 300, 200),
                "uncollectedOrDifferentOrSignaledRoot").isFalse();
        requireThat(LinuxBazelNamespaceHooks.authenticatedOrdinaryRootParent(terminal, root, 200, 301, 200),
            "unknownOriginalParent").isFalse();
        requireThat(LinuxBazelNamespaceHooks.authenticatedOrdinaryRootParent(terminal, root, 200, 300, 201),
            "foreignReplacementParent").isFalse();
    }

    /** Accepts both genuine Linux exec birth observations while preserving exact former-thread authority. */
    @Test public void authenticatesExactExecMigrationWithEitherRetainedBirth()
    {
        LinuxAllocationTrace.Identity authorized = new LinuxAllocationTrace.Identity(4, 31);
        for (long liveBirth : List.of(30L, 31L))
        {
            LinuxAllocationTrace.Held migration = new LinuxAllocationTrace.Held(
                new LinuxAllocationTrace.Identity(3, liveBirth), 3, 1, "/program", authorized);
            requireThat(LinuxBazelNamespaceHooks.authenticatedExecMigration(migration, authorized, 3, 30, liveBirth),
                "independentlyAuthenticatedExec").isTrue();
            requireThat(LinuxBazelNamespaceHooks.authenticatedExecMigration(migration,
                new LinuxAllocationTrace.Identity(5, 31), 3, 30, liveBirth), "unrelatedFormerThread").isFalse();
            requireThat(LinuxBazelNamespaceHooks.authenticatedExecMigration(migration, authorized, 3, 30, 32),
                "changedLiveBirth").isFalse();
        }
        LinuxAllocationTrace.Held unrelated = new LinuxAllocationTrace.Held(
            new LinuxAllocationTrace.Identity(3, 32), 3, 1, "/program", authorized);
        requireThat(LinuxBazelNamespaceHooks.authenticatedExecMigration(unrelated, authorized, 3, 30, 32),
            "unrelatedPostExecBirth").isFalse();
    }

    /**
     * Rejects relative or traversing executable inputs before any external effect can occur.
     */
    @Test public void executablePathsMustBeAbsoluteAndNormalized()
    {
        requireException(IllegalArgumentException.class,
            () -> new LinuxBazelNamespaceHooks.Seal(Path.of("java"), "ab".repeat(32)));
        requireException(IllegalArgumentException.class,
            () -> new LinuxBazelNamespaceHooks.Seal(Path.of("").toAbsolutePath().resolve("jdk/../java"), "ab".repeat(32)));
    }

    /**
     * Rejects malformed trusted hashes instead of treating an unchecked file as pinned.
     */
    @Test public void executableSealsRequireExactSha256()
    {
        requireException(IllegalArgumentException.class,
            () -> new LinuxBazelNamespaceHooks.Seal(Path.of("").toAbsolutePath().resolve("java"), "AB".repeat(32)));
        requireException(IllegalArgumentException.class,
            () -> new LinuxBazelNamespaceHooks.Seal(Path.of("").toAbsolutePath().resolve("java"), "ab".repeat(31)));
    }

    /**
     * Rejects a Java image outside its sealed native runtime tree or an ambient empty classpath.
     */
    @Test public void javaImageAndClasspathRequireSealedOwnership()
    {
        var nsenter = seal("/usr/bin/nsenter");
        var runtime = seal("/trusted/jdk");
        var producer = seal("/trusted/frida.so");
        requireException(IllegalArgumentException.class, () -> new LinuxBazelNamespaceHooks.Launch(
            nsenter, runtime, seal("/other/java"), List.of(seal("/trusted/app.jar")), producer));
        requireException(IllegalArgumentException.class, () -> new LinuxBazelNamespaceHooks.Launch(
            nsenter, runtime, seal("/trusted/jdk/bin/java"), List.of(), producer));
    }

    /**
     * Preserves explicit sealed classpath ordering and the sole supported helper mode without ambient properties.
     */
    @Test public void buildsOnlyThePinnedHelperEntrypoint()
    {
        var launch = new LinuxBazelNamespaceHooks.Launch(seal("/usr/bin/nsenter"), seal("/trusted/jdk"),
            seal("/trusted/jdk/bin/java"), List.of(seal("/trusted/classes"), seal("/trusted/dependency.jar")),
            seal("/trusted/frida.so"));
        requireThat(launch.arguments(), "exactJvmArgv").isEqualTo(List.of(seal("/trusted/jdk/bin/java").path().toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", seal("/trusted/classes").path() + ":" + seal("/trusted/dependency.jar").path(),
            "com.holdmyspot.codexunleashed.scheduler.LinuxBazelHelperMain", "--namespace-helper"));
    }

    /**
     * Accepts the queue's initial zero ordinal when retaining the original helper before native acquisition.
     * Native installation identities are positive; the factory must translate the queue identity itself.
     * @throws IOException if modeled authentication, ownership reservation or resource-free cleanup fails
     */
    @Test public void firstQueueAttemptCanReserveOriginalHelper() throws IOException
    {
        Path directory = Path.of("").toAbsolutePath().normalize();
        LinuxBazelIdentity.Image image = new LinuxBazelIdentity.Image(directory.resolve("modeled-gate"), "ab".repeat(32));
        LinuxBazelIdentity.Execution execution = new LinuxBazelIdentity.Execution(image,
            List.of(image.executable().toString()), Map.of(), directory, 1000, 1000);
        LinuxBazelIdentity.Observer observations = new LinuxBazelIdentity.Observer()
        {
            @Override public LinuxBazelIdentity.Observation observe(long pid, Path executable)
            {
                long parent = switch ((int) pid) { case 100 -> 1; case 200 -> 100; default -> 200; };
                List<Long> vector = switch ((int) pid)
                {
                    case 100 -> List.of(100L);
                    case 200 -> List.of(200L, 2L);
                    default -> List.of(300L, 3L);
                };
                String namespace = pid == 100 ? "host" : "sandbox";
                return new LinuxBazelIdentity.Observation(pid / 10, parent,
                    "Uid: 1000 1000 1000 1000\nGid: 1000 1000 1000 1000", vector, namespace, namespace,
                    true, image.sha256(), execution.arguments(), execution.environment(), directory);
            }
            @Override public List<Long> children(long pid) { return List.of(300L); }
            @Override public boolean sameDirectory(long pid, Path expected) { return directory.equals(expected); }
        };
        LinuxBazelIdentity authenticator = new LinuxBazelIdentity(
            new LinuxBazelIdentity.Parent(100, 10, image), execution, observations);
        LinuxBazelIdentity.Owner owner = authenticator.authenticate(new LinuxBorrowedStreams.Peer(200, 20, 1000, 1000), 2);
        LinuxBazelIdentity.Child child = authenticator.authenticateChild(owner, 3, 30, execution);
        LinuxBazelContainment.Group group = new LinuxBazelContainment.Group()
        {
            /** Models only the retained child's cgroup membership for this test. */
            private boolean moved;
            @Override public Path directory() { return directory.resolve("modeled-group"); }
            @Override public void move(long pid) { moved = true; }
            @Override public boolean contains(long pid) { return moved && pid == 300; }
            @Override public boolean isPopulated() { return moved; }
            @Override public void terminate() { moved = false; }
            @Override public void close() { moved = false; }
        };
        try (DefaultToolingScope scope = new DefaultToolingScope();
            LinuxBazelContainment containment = LinuxBazelContainment.SharedSecrets.admit(authenticator,
                owner, child, execution, directory, Duration.ofSeconds(1), (parent, timeout) -> group))
        {
            LinuxBazelNamespaceHooks.Launch launch = new LinuxBazelNamespaceHooks.Launch(seal("/usr/bin/nsenter"),
                seal("/trusted/jdk"), seal("/trusted/jdk/bin/java"), List.of(seal("/trusted/classes")), seal("/trusted/frida.so"));
            LinuxBazelAdmission.HookOwner hook = new LinuxBazelNamespaceHooks(scope, UUID.randomUUID(), owner,
                execution, launch, Duration.ofSeconds(1)).create(0, child, new LinuxBazelControl.Identity(3, 30),
                    containment, new AllocationWaits());
            hook.close(() -> true);
        }
    }

    /**
     * Admits generated programs only inside the private action or exact runtime roots.
     */
    @Test public void generatedProgramsRequireExplicitComponentRoots()
    {
        Path root = Path.of("").toAbsolutePath().normalize();
        LinuxBazelNamespaceHooks.ActionImages policy = new LinuxBazelNamespaceHooks.ActionImages(
            root.resolve("attempt"), List.of(root.resolve("runtime")));
        requireThat(policy.permits(root.resolve("attempt/out/build-script")), "privateGeneratedImage").isTrue();
        requireThat(policy.permits(root.resolve("runtime/bin/linker")), "mappedRuntimeImage").isTrue();
        requireThat(policy.permits(root.resolve("attempt-other/program")), "unrelatedSibling").isFalse();
        requireThat(policy.permits(root.resolve("unmapped/program")), "unmappedProgram").isFalse();
        requireException(IllegalArgumentException.class, () -> new LinuxBazelNamespaceHooks.ActionImages(
            Path.of("/"), List.of()));
    }

    /**
     * Requires actual inode agreement instead of accepting identical bytes at a replacement pathname.
     * @throws IOException if independent test-local filesystem observations fail
     */
    @Test public void replacementPathCannotAuthorizeTheHeldInode() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Path other = files.root.resolve("replacement");
            Files.writeString(other, Files.readString(files.executable));
            LinuxBazelNamespaceHooks.ActionImages policy = new LinuxBazelNamespaceHooks.ActionImages(files.root, List.of());
            LinuxBazelNamespaceHooks.requireRequestedImage(files.proc,
                new FridaRuntime.Child(7, 7, 1, files.executable.toString()), null, List.of("argv-zero"), policy);
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.requireRequestedImage(files.proc,
                new FridaRuntime.Child(7, 7, 1, other.toString()), null, List.of("argv-zero"), policy));
        }
    }

    /**
     * Authenticates an actual relative child exec against its independently observed working directory.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void mapsRelativeDescendantExecFromActualCwd() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.createSymbolicLink(files.proc.resolve("cwd"), files.root);
            LinuxBazelNamespaceHooks.ActionImages policy = new LinuxBazelNamespaceHooks.ActionImages(files.root, List.of());
            LinuxBazelNamespaceHooks.requireRequestedImage(files.proc,
                new FridaRuntime.Child(7, 7, 1, "program"), null, List.of("unchanged-argv-zero"), policy);
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.requireRequestedImage(files.proc,
                new FridaRuntime.Child(7, 7, 1, "../unmapped/program"), null, List.of("unchanged-argv-zero"), policy));
        }
    }

    /**
     * Preserves the exact relative script argument supplied to the kernel's direct interpreter.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void preservesRelativeShebangSourceArgument() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.createSymbolicLink(files.proc.resolve("cwd"), files.root);
            Files.writeString(files.root.resolve("source"), "#!" + files.executable + "\n");
            LinuxBazelNamespaceHooks.ActionImages policy = new LinuxBazelNamespaceHooks.ActionImages(files.root, List.of());
            FridaRuntime.Child event = new FridaRuntime.Child(7, 7, 1, "./source");
            LinuxBazelNamespaceHooks.requireRequestedImage(files.proc, event, null,
                List.of(files.executable.toString(), "./source", "original-argument"), policy);
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.requireRequestedImage(files.proc, event, null,
                List.of(files.executable.toString(), files.root.resolve("source").toString(), "original-argument"), policy));
        }
    }

    /**
     * Refuses relative execution when the held process cwd has no independently matching namespace inode.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void rejectsUnmappedRelativeExecCwd() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.delete(files.proc.resolve("root"));
            Files.createSymbolicLink(files.proc.resolve("root"), files.root);
            Files.createSymbolicLink(files.proc.resolve("cwd"), files.root.getParent());
            LinuxBazelNamespaceHooks.ActionImages policy = new LinuxBazelNamespaceHooks.ActionImages(Path.of("/attempt"), List.of());
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.requireRequestedImage(files.proc,
                new FridaRuntime.Child(7, 7, 1, "program"), null, List.of("program"), policy));
        }
    }

    /**
     * Maps a host-prefixed proc executable through the independently observed private process root.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void mapsControllerVisiblePrivateRootImage() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.delete(files.proc.resolve("root"));
            Files.createSymbolicLink(files.proc.resolve("root"), files.root);
            LinuxBazelNamespaceHooks.ImageObservation image = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            requireThat(image.executable(), "actionVisibleExecutable").isEqualTo(Path.of("/program"));
        }
    }

    /**
     * Refuses prefix text when the corresponding action-visible inode does not exist.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void rejectsUnmappedControllerVisibleImage() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.delete(files.proc.resolve("root"));
            Files.createSymbolicLink(files.proc.resolve("root"), Files.createDirectory(files.root.resolve("other-root")));
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7));
        }
    }

    /**
     * Refuses ambiguous aliases even when both possible path interpretations map to the same executable inode.
     * @throws IOException if per-method inode observations or cleanup fail
     */
    @Test public void rejectsAmbiguousControllerVisibleImage() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            Files.delete(files.proc.resolve("root"));
            Files.createSymbolicLink(files.proc.resolve("root"), files.root);
            Path alias = files.root.resolve(files.executable.toString().substring(1));
            Files.createDirectories(alias.getParent());
            Files.createLink(alias, files.executable);
            requireException(IOException.class, () -> LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7));
        }
    }

    /**
     * Detects mutation of the actual executable between independent readiness observations.
     * @throws IOException if test-local observations or cleanup fail
     */
    @Test public void changedHeldBytesCannotPassTheReadinessRecheck() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            LinuxBazelNamespaceHooks.ImageObservation original = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            original.requireUnchanged(LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7));
            Files.writeString(files.executable, "changed held program");
            LinuxBazelNamespaceHooks.ImageObservation changed = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            requireException(IOException.class, () -> original.requireUnchanged(changed));
        }
    }

    /**
     * Rejects a process birth or cgroup/namespace change even when executable bytes remain equal.
     * @throws IOException if test-local observations or cleanup fail
     */
    @Test public void changedBirthOrIsolationWithdrawsReadiness() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            LinuxBazelNamespaceHooks.ImageObservation original = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            Files.writeString(files.proc.resolve("cgroup"), "0::/other-action\n");
            LinuxBazelNamespaceHooks.ImageObservation moved = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            requireException(IOException.class, () -> original.requireUnchanged(moved));
            Files.writeString(files.proc.resolve("cgroup"), "0::/owned-action\n");
            Files.delete(files.proc.resolve("ns/pid"));
            Files.createSymbolicLink(files.proc.resolve("ns/pid"), Path.of("pid:[different]"));
            LinuxBazelNamespaceHooks.ImageObservation otherNamespace = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            requireException(IOException.class, () -> original.requireUnchanged(otherNamespace));
            Files.writeString(files.proc.resolve("stat"), ImageFiles.stat(31));
            LinuxBazelNamespaceHooks.ImageObservation reused = LinuxBazelNamespaceHooks.observeImage(files.proc, 7, 7);
            requireException(IOException.class, () -> original.requireUnchanged(reused));
        }
    }

    /**
     * Distinguishes a held executable from a dead unreaped child and a reused PID before hook retirement.
     * @throws IOException if independent test-local kernel observations fail
     */
    @Test public void zombieOrReusedBirthCanRetireWithoutReaping() throws IOException
    {
        try (ImageFiles files = new ImageFiles())
        {
            requireThat(LinuxBazelNamespaceHooks.originalImageEnded(files.proc, 30), "heldImageStillExecutes").isFalse();
            Files.writeString(files.proc.resolve("stat"), ImageFiles.stat(30).replace(") T ", ") Z "));
            requireThat(LinuxBazelNamespaceHooks.originalImageEnded(files.proc, 30), "unreapedZombieCannotExecute").isTrue();
            Files.writeString(files.proc.resolve("stat"), ImageFiles.stat(31));
            requireThat(LinuxBazelNamespaceHooks.originalImageEnded(files.proc, 30), "originalBirthEnded").isTrue();
            Files.delete(files.proc.resolve("stat"));
            requireThat(LinuxBazelNamespaceHooks.originalImageEnded(files.proc, 30), "reapedOriginalCannotExecute").isTrue();
        }
    }

    /**
     * Owns real test-local executable inodes and independently supplied proc text at the filesystem boundary.
     */
    private static final class ImageFiles implements AutoCloseable
    {
        /** Sole private filesystem root for this test. */
        private final Path root = linuxRoot();
        /** Real executable inode, never executed or loaded. */
        private final Path executable = root.resolve("program");
        /** Independent proc-file observation boundary. */
        private final Path proc = root.resolve("proc");

        /**
         * Creates isolated real inodes and complete immutable process observations.
         * @throws IOException if filesystem acquisition fails
         */
        private ImageFiles() throws IOException
        {
            try
            {
                Files.writeString(executable, "held program bytes");
                Files.createDirectory(proc);
                Files.createDirectory(proc.resolve("ns"));
                Files.createSymbolicLink(proc.resolve("root"), root.getRoot());
                Files.createSymbolicLink(proc.resolve("exe"), executable);
                Files.createSymbolicLink(proc.resolve("ns/pid"), Path.of("pid:[original]"));
                Files.createSymbolicLink(proc.resolve("ns/mnt"), Path.of("mnt:[original]"));
                Files.writeString(proc.resolve("stat"), stat(30));
                Files.writeString(proc.resolve("cmdline"), "original-argv-zero\0arg\0");
                Files.writeString(proc.resolve("cgroup"), "0::/owned-action\n");
            }
            catch (IOException | RuntimeException | Error cause)
            {
                try { PathUtils.deleteDirectory(root); }
                catch (IOException cleanup) { cause.addSuppressed(cleanup); }
                throw cause;
            }
        }

        /**
         * Requires Linux native inode observations before acquiring any platform fixture.
         * @return new test-local directory
         * @throws IOException if temporary directory creation fails
         * @throws org.testng.SkipException if this platform cannot exercise Linux proc/inode semantics
         */
        private static Path linuxRoot() throws IOException
        {
            if (!System.getProperty("os.name").equals("Linux"))
                throw new org.testng.SkipException("Held executable inode observations require native Linux");
            return Files.createTempDirectory("held-image-");
        }

        /**
         * Builds an independent Linux stat observation with the requested process birth.
         * @param birth kernel start tick identity
         * @return complete parent/birth fields, including a command containing spaces
         */
        private static String stat(long birth)
        {
            List<String> fields = new java.util.ArrayList<>(java.util.Collections.nCopies(20, "0"));
            fields.set(0, "T"); fields.set(1, "6"); fields.set(19, Long.toString(birth));
            return "7 (held program) " + String.join(" ", fields);
        }

        @Override public void close() throws IOException { PathUtils.deleteDirectory(root); }
    }

    /**
     * Creates a resource-free immutable policy value without consulting this OS's filesystem.
     * @param path trusted namespace-local normalized absolute pathname
     * @return immutable sealed location
     */
    private static LinuxBazelNamespaceHooks.Seal seal(String path)
    {
        return new LinuxBazelNamespaceHooks.Seal(Path.of("").toAbsolutePath().normalize().resolve("namespace-helper-policy").resolve(path.substring(1)), "ab".repeat(32));
    }
}
