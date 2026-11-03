package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelBackendCredentials;
import com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the common native target boundary without claiming native execution.
 */
public final class BazelNativeBackendTest
{
    /**
     * Creates independent boundary checks.
     */
    public BazelNativeBackendTest() {}

    /**
     * The shared target contract supports both real architectures on every release operating system.
     */
    @Test
    public void resolvesEveryReleaseHost()
    {
        String[][] hosts = {
            {"Linux", "amd64", "x86_64-unknown-linux-musl"},
            {"Linux", "aarch64", "aarch64-unknown-linux-musl"},
            {"Mac OS X", "x86_64", "x86_64-apple-darwin"},
            {"Mac OS X", "aarch64", "aarch64-apple-darwin"},
            {"Windows 11", "amd64", "x86_64-pc-windows-msvc"},
            {"Windows 11", "aarch64", "aarch64-pc-windows-msvc"}
        };
        for (String[] host : hosts)
            requireThat(BazelNativeBackend.NativeTarget.forHost(host[0], host[1]).triple(), "nativeTarget")
                .isEqualTo(host[2]);
    }

    /**
     * Accepts exactly hashed native machine headers and rejects changed bytes before any launch authority.
     * These portable synthetic files exercise validation only; they are never executed.
     * @throws Exception if owned fixture creation or validation fails
     */
    @Test
    public void validatesNativeManifestAndArchitecture() throws Exception
    {
        for (String[] host : new String[][]{{"Mac OS X", "aarch64"}, {"Mac OS X", "x86_64"},
            {"Windows 11", "aarch64"}, {"Windows 11", "amd64"}})
        {
            BazelNativeBackend.NativeTarget target = BazelNativeBackend.NativeTarget.forHost(host[0], host[1]);
            try (Fixture fixture = Fixture.open(target))
            {
                BazelNativeBackend.ValidatedDistribution accepted = BazelNativeBackend.validateDistribution(
                    fixture.scope, target, fixture.root);
                requireThat(accepted.executables().keySet(), "exactNativeDaemonSet")
                    .isEqualTo(Set.of("bb_storage", "bb_scheduler", "bb_worker"));
                Path worker = fixture.root.resolve("bb_worker" + target.suffix());
                Files.writeString(worker, "changed actual bytes");
                requireException(IOException.class, () -> BazelNativeBackend.validateDistribution(fixture.scope, target, fixture.root));
            }
        }
    }

    /**
     * Correctly hashed files for the wrong processor do not become native launch authority.
     * @throws Exception if fixture creation or manifest serialization fails
     */
    @Test
    public void rejectsHashedWrongMachine() throws Exception
    {
        BazelNativeBackend.NativeTarget target = BazelNativeBackend.NativeTarget.forHost("Windows 11", "aarch64");
        try (Fixture fixture = Fixture.open(target))
        {
            Files.write(fixture.root.resolve("bb_worker.exe"), header(BazelNativeBackend.NativeTarget.forHost("Windows 11", "amd64")));
            fixture.writeManifest();
            requireException(IOException.class, () -> BazelNativeBackend.validateDistribution(fixture.scope, target, fixture.root));
        }
    }

    /**
     * Moving a validated distribution object away and recreating identical bytes cannot reuse its native authority.
     * @throws Exception if fixture creation or original object replacement fails
     */
    @Test
    public void rejectsReplacedOriginalRoot() throws Exception
    {
        BazelNativeBackend.NativeTarget target = BazelNativeBackend.NativeTarget.forHost("Mac OS X", "aarch64");
        try (Fixture fixture = Fixture.open(target))
        {
            BazelNativeBackend.ValidatedDistribution accepted = BazelNativeBackend.validateDistribution(fixture.scope, target, fixture.root);
            Path old = fixture.root.resolveSibling(fixture.root.getFileName() + "-original");
            Files.move(fixture.root, old);
            try
            {
                Files.createDirectory(fixture.root);
                try (java.util.stream.Stream<Path> files = Files.list(old))
                {
                    for (Path file : files.toList())
                    {
                        Path replacement = fixture.root.resolve(file.getFileName());
                        Files.copy(file, replacement);
                        if (!replacement.getFileName().toString().equals(BazelNativeBackend.MANIFEST))
                            requireThat(replacement.toFile().setExecutable(true), "replacementExecutable").isTrue();
                    }
                }
                requireException(IOException.class, () -> accepted.revalidate(fixture.scope));
            }
            finally { org.apache.commons.io.file.PathUtils.deleteDirectory(old); }
        }
    }

    /**
     * The compiler-free entry point selects this actual host and stages every original product and license.
     * @throws Exception if the owned fixture or staging entry point fails
     */
    @Test
    public void stagesCurrentHostDistribution() throws Exception
    {
        BazelNativeBackend.NativeTarget target = BazelNativeBackend.NativeTarget.forHost(
            System.getProperty("os.name"), System.getProperty("os.arch"));
        try (Fixture fixture = Fixture.open(target))
        {
            Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-stage");
            try
            {
                Files.createDirectory(fixture.root.resolve("licenses"));
                Files.writeString(fixture.root.resolve("licenses/NOTICE"), "retained upstream license");
                BazelNativeBackend.class.getMethod("main", String[].class).invoke(null,
                    (Object) new String[]{fixture.root.toString(), destination.toString()});
                requireThat(Files.readString(destination.resolve("licenses/NOTICE")), "stagedLicense")
                    .isEqualTo("retained upstream license");
                requireThat(BazelNativeBackend.validateDistribution(fixture.scope, target, destination).hashes(),
                    "stagedOriginalProducts").isEqualTo(BazelNativeBackend.validateDistribution(
                    fixture.scope, target, fixture.root).hashes());
            }
            finally { if (Files.exists(destination)) org.apache.commons.io.file.PathUtils.deleteDirectory(destination); }
        }
    }

    /**
     * Every supported target stages its exact filenames, manifest and direct licenses through one authority.
     * @throws Exception if an independent synthetic target fixture or staging check fails
     */
    @Test
    public void stagesEveryTargetWithoutExecutingProducts() throws Exception
    {
        for (BazelNativeBackend.NativeTarget target : BazelNativeBackend.NativeTarget.values())
            try (Fixture fixture = Fixture.open(target))
            {
                Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-stage");
                try
                {
                    Files.createDirectory(fixture.root.resolve("licenses"));
                    Files.writeString(fixture.root.resolve("licenses/LICENSE"), target.triple());
                    BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                        fixture.scope, target, fixture.root);
                    BazelNativeBackend.ValidatedDistribution staged = BazelNativeBackend.stage(fixture.scope, original, destination);
                    requireThat(staged.hashes(), "originalNativeProducts").isEqualTo(original.hashes());
                    requireThat(staged.manifestHash(), "originalManifest").isEqualTo(original.manifestHash());
                    requireThat(Files.readString(destination.resolve("licenses/LICENSE")), "originalLicense")
                        .isEqualTo(target.triple());
                    for (Map.Entry<String, Path> executable : staged.executables().entrySet())
                        requireThat(executable.getValue().getFileName().toString(), "targetNativeFilename")
                            .isEqualTo(executable.getKey() + target.suffix());
                }
                finally { if (Files.exists(destination)) org.apache.commons.io.file.PathUtils.deleteDirectory(destination); }
            }
    }

    /**
     * Existing caller-owned destination contents survive rejected staging unchanged.
     * @throws Exception if fixture ownership or rejection verification fails
     */
    @Test
    public void preservesExistingStagingDestination() throws Exception
    {
        try (Fixture fixture = Fixture.open(BazelNativeBackend.NativeTarget.forHost("Mac OS X", "aarch64")))
        {
            Path destination = fixture.root.resolve("already-owned");
            Files.createDirectory(destination);
            Files.writeString(destination.resolve("sentinel"), "caller-owned");
            BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                fixture.scope, fixture.target, fixture.root);
            requireException(IOException.class, () -> BazelNativeBackend.stage(fixture.scope, original, destination));
            requireThat(Files.readString(destination.resolve("sentinel")), "existingCallerFile").isEqualTo("caller-owned");
        }
    }

    /**
     * A changed original cannot cause the creation of any staging destination.
     * @throws Exception if fixture creation or changed-source verification fails
     */
    @Test
    public void rejectsChangedSourceBeforeCreatingDestination() throws Exception
    {
        try (Fixture fixture = Fixture.open(BazelNativeBackend.NativeTarget.forHost("Windows 11", "amd64")))
        {
            Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-absent");
            BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                fixture.scope, fixture.target, fixture.root);
            Files.writeString(fixture.root.resolve("bb_worker.exe"), "changed original");
            requireException(IOException.class, () -> BazelNativeBackend.stage(fixture.scope, original, destination));
            requireThat(Files.exists(destination), "destinationCreatedAfterRejectedOriginal").isFalse();
        }
    }

    /**
     * Linked license files and linked destination ancestry never become copy authority.
     * @throws Exception if owned link fixtures or rejection checks fail
     */
    @Test
    public void rejectsLinkedLicenseAndDestinationParent() throws Exception
    {
        try (Fixture fixture = Fixture.open(BazelNativeBackend.NativeTarget.forHost("Mac OS X", "aarch64")))
        {
            Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-absent");
            Path licenses = Files.createDirectory(fixture.root.resolve("licenses"));
            Path linked = licenses.resolve("linked");
            try { Files.createSymbolicLink(linked, fixture.root.resolve("bb_worker")); }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable)
            {
                throw new org.testng.SkipException("Native symbolic links unavailable for this test fixture", unavailable);
            }
            BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                fixture.scope, fixture.target, fixture.root);
            requireException(IOException.class, () -> BazelNativeBackend.stage(fixture.scope, original, destination));
            requireThat(Files.exists(destination), "destinationFromLinkedLicense").isFalse();
            Files.delete(linked);
            Path alias = fixture.root.resolve("alias");
            Files.createSymbolicLink(alias, licenses);
            requireException(IOException.class, () -> BazelNativeBackend.stage(fixture.scope, original, alias.resolve("stage")));
            requireThat(Files.exists(licenses.resolve("stage")), "destinationThroughLinkedParent").isFalse();
        }
    }

    /**
     * A failed copy retains the exact primary failure while collecting all authenticated created objects.
     * @throws Exception if fixture ownership or cleanup verification fails
     */
    @Test
    public void collectsOwnedStageAfterCopyFailure() throws Exception
    {
        try (Fixture fixture = Fixture.open(BazelNativeBackend.NativeTarget.forHost("Mac OS X", "aarch64")))
        {
            Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-failed");
            IOException primary = new IOException("fixture copy identity failure");
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            BazelNativeBackend.FileIdentity nativeIdentity = BazelBackendCredentials.nativeIdentity(fixture.scope);
            BazelNativeBackend.FileIdentity authority = path ->
            {
                if (path.equals(destination.resolve("bb_worker")) && reads.incrementAndGet() == 2) throw primary;
                return nativeIdentity.identity(path);
            };
            BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                fixture.scope, fixture.target, fixture.root, authority);
            IOException actual = requireException(IOException.class,
                () -> BazelNativeBackend.stage(fixture.scope, original, destination));
            requireThat(actual, "originalPrimaryFailure").isEqualTo(primary);
            requireThat(actual.getSuppressed().length, "cleanupFailures").isEqualTo(0);
            requireThat(Files.exists(destination), "collectedStagingDestination").isFalse();
        }
    }

    /**
     * Unknown created-file identity prevents recursive deletion and remains attached to the primary failure.
     * @throws Exception if fixture setup or retained-cleanup verification fails
     */
    @Test
    public void retainsUncertainStageWithoutMaskingPrimaryFailure() throws Exception
    {
        try (Fixture fixture = Fixture.open(BazelNativeBackend.NativeTarget.forHost("Windows 11", "amd64")))
        {
            Path destination = fixture.root.resolveSibling(fixture.root.getFileName() + "-uncertain");
            try
            {
                IOException primary = new IOException("fixture refuses new manifest identity");
                BazelNativeBackend.FileIdentity nativeIdentity = BazelBackendCredentials.nativeIdentity(fixture.scope);
                BazelNativeBackend.FileIdentity authority = path ->
                {
                    if (path.equals(destination.resolve(BazelNativeBackend.MANIFEST))) throw primary;
                    return nativeIdentity.identity(path);
                };
                BazelNativeBackend.ValidatedDistribution original = BazelNativeBackend.validateDistribution(
                    fixture.scope, fixture.target, fixture.root, authority);
                IOException actual = requireException(IOException.class,
                    () -> BazelNativeBackend.stage(fixture.scope, original, destination));
                requireThat(actual, "originalPrimaryFailure").isEqualTo(primary);
                requireThat(actual.getSuppressed().length, "retainedCleanupFailure").isEqualTo(1);
                requireThat(actual.getSuppressed()[0].getMessage().contains(destination.toString()),
                    "retainedDestinationLocator").isTrue();
                requireThat(Files.exists(destination.resolve(BazelNativeBackend.MANIFEST)), "unverifiedObjectRetained").isTrue();
            }
            finally { if (Files.exists(destination)) org.apache.commons.io.file.PathUtils.deleteDirectory(destination); }
        }
    }

    /**
     * Produces a deliberately synthetic minimal header for portable rejection checks.
     * @param target selected format and native processor
     * @return synthetic header bytes, never a runnable product
     */
    private static byte[] header(BazelNativeBackend.NativeTarget target)
    {
        ByteBuffer bytes = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        if (target.isLinux())
        {
            bytes.put(0, (byte) 0x7f); bytes.put(1, (byte) 'E'); bytes.put(2, (byte) 'L'); bytes.put(3, (byte) 'F');
            bytes.put(4, (byte) 2); bytes.put(5, (byte) 1);
            bytes.putShort(18, (short) (target.goArchitecture().equals("arm64") ? 183 : 62));
        }
        else if (target.goOperatingSystem().equals("darwin"))
        {
            bytes.putInt(0, 0xfeedfacf);
            bytes.putInt(4, target.goArchitecture().equals("arm64") ? 0x0100000c : 0x01000007);
            bytes.putInt(12, 2);
        }
        else
        {
            bytes.put(0, (byte) 'M'); bytes.put(1, (byte) 'Z'); bytes.putInt(60, 64);
            bytes.putInt(64, 0x00004550);
            bytes.putShort(68, (short) (target.goArchitecture().equals("arm64") ? 0xaa64 : 0x8664));
            bytes.putShort(86, (short) 2); bytes.putShort(88, (short) 0x020b);
        }
        return bytes.array();
    }

    /**
     * Supplies synthetic fixture membership from the existing target distinction without running products.
     * @param target expected native release target
     * @return fixed synthetic fixture product names
     */
    private static Set<String> fixtureNames(BazelNativeBackend.NativeTarget target)
    {
        if (target.isLinux()) return Set.of("bb_storage", "bb_scheduler", "bb_worker", "linux-bazel-action-owner",
            "linux-bazel-action-waiter", "codex-tooling", "codex-cargo-wrapper-linux");
        return Set.of("bb_storage", "bb_scheduler", "bb_worker");
    }

    /**
     * Owns a portable manifest fixture and one borrowed-service scope per test invocation.
     */
    private static final class Fixture implements AutoCloseable
    {
        /** Unique fixture root, removed after each check. */
        private final Path root;
        /** Test-local Pouch scope for shared JSON services. */
        private final DefaultToolingScope scope;
        /** Exact expected native target for fixture serialization. */
        private final BazelNativeBackend.NativeTarget target;

        /**
         * Retains already-acquired fixture ownership.
         * @param root unique owned fixture directory
         * @param scope original test-owned Pouch scope
         * @param target exact native format under test
         */
        private Fixture(Path root, DefaultToolingScope scope, BazelNativeBackend.NativeTarget target)
        {
            this.root = root;
            this.scope = scope;
            this.target = target;
        }

        /**
         * Creates synthetic validator inputs without launching or extracting a native executable.
         * @param target exact native format under test
         * @return fully owned portable fixture
         * @throws Exception if fixture creation fails after retained cleanup
         */
        private static Fixture open(BazelNativeBackend.NativeTarget target) throws Exception
        {
            Path root = Files.createTempDirectory("native-backend-validator-");
            DefaultToolingScope scope = new DefaultToolingScope();
            Fixture fixture = new Fixture(root, scope, target);
            try
            {
                for (String name : fixtureNames(target))
                {
                    Path path = root.resolve(name + target.suffix());
                    Files.write(path, header(target));
                    requireThat(path.toFile().setExecutable(true), "fixtureExecutable").isTrue();
                }
                fixture.writeManifest();
                return fixture;
            }
            catch (Exception | Error failure)
            {
                try { fixture.close(); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Serializes the exact current file identities into the fixture manifest.
         * @throws Exception if hashing or scope-owned serialization fails
         */
        private void writeManifest() throws Exception
        {
            Map<String, Object> files = new LinkedHashMap<>();
            for (String name : fixtureNames(target))
            {
                String filename = name + target.suffix();
                files.put(name, Map.of("path", filename, "sha256", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(filename))))));
            }
            scope.getJsonMapper().writeValue(root.resolve(BazelNativeBackend.MANIFEST).toFile(), Map.of(
                "schema", 1, "target", target.triple(), "executionCommit", BazelNativeBackend.EXECUTION_COMMIT,
                "storageCommit", BazelNativeBackend.STORAGE_COMMIT, "executables", files));
        }

        @Override public void close() throws IOException
        {
            try { org.apache.commons.io.file.PathUtils.deleteDirectory(root); }
            finally { scope.close(); }
        }
    }
}
