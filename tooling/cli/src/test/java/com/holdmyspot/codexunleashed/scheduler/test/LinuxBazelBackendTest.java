package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelBackendCredentials;
import com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelBackend;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises backend identity rejection before any native daemon is started.
 */
public final class LinuxBazelBackendTest
{
    /**
     * Creates a test instance without shared mutable fixtures.
     */
    public LinuxBazelBackendTest() {}

    /**
     * Selects all exact-hash ARM64 binaries from a complete native distribution.
     * @throws Exception if test-local files or validated selection fail
     */
    @Test public void selectsValidatedNativeExecutables() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            Map<String, Path> selected = LinuxBazelBackend.validateDistribution(scope, fixture.root,
                "Linux", "aarch64");
            requireThat(selected.keySet(), "nativeExecutables").isEqualTo(fixture.files.keySet());
            requireThat(selected.get("bb_worker"), "workerPath").isEqualTo(fixture.root.resolve("bb_worker"));
        }
    }

    /**
     * Selects exact-hash x64 products through the Linux compatibility boundary.
     * @throws Exception if fixture creation or native format validation fails
     */
    @Test public void selectsValidatedX64Executables() throws Exception
    {
        try (Fixture fixture = new Fixture(62); DefaultToolingScope scope = new DefaultToolingScope())
        {
            Map<String, Path> selected = LinuxBazelBackend.validateDistribution(scope, fixture.root, "Linux", "amd64");
            requireThat(selected.keySet(), "x64NativeExecutables").isEqualTo(fixture.files.keySet());
        }
    }

    /**
     * Resolves the shared native build settings used by the distribution driver for both supported host aliases.
     */
    @Test public void nativeTargetKeepsCompilerBazelHeadersAndManifestConsistent()
    {
        for (String architecture : List.of("aarch64", "arm64"))
        {
            LinuxBazelBackend.NativeTarget target = LinuxBazelBackend.NativeTarget.forHost("Linux", architecture);
            requireThat(List.of(target.triple(), target.goArchitecture(), target.bazelArchitecture(),
                target.systemHeaders().toString()), "arm64NativeBuildSettings").isEqualTo(List.of(
                "aarch64-unknown-linux-musl", "arm64", "arm64", Path.of("/usr/include/aarch64-linux-gnu").toString()));
        }
        for (String architecture : List.of("amd64", "x86_64"))
        {
            LinuxBazelBackend.NativeTarget target = LinuxBazelBackend.NativeTarget.forHost("Linux", architecture);
            requireThat(List.of(target.triple(), target.goArchitecture(), target.bazelArchitecture(),
                target.systemHeaders().toString()), "x64NativeBuildSettings").isEqualTo(List.of(
                "x86_64-unknown-linux-musl", "amd64", "x86_64", Path.of("/usr/include/x86_64-linux-gnu").toString()));
        }
    }

    /**
     * Rejects unsupported native hosts instead of selecting an available foreign release target.
     */
    @Test public void nativeTargetRejectsForeignHosts()
    {
        requireException(UnsupportedOperationException.class, () -> LinuxBazelBackend.NativeTarget.forHost("Linux", "riscv64"));
        requireException(UnsupportedOperationException.class, () -> LinuxBazelBackend.NativeTarget.forHost("Windows", "amd64"));
        requireException(UnsupportedOperationException.class, () -> LinuxBazelBackend.NativeTarget.forHost("Mac OS X", "arm64"));
        requireException(NullPointerException.class, () -> LinuxBazelBackend.NativeTarget.forHost(null, "amd64"));
        requireException(NullPointerException.class, () -> LinuxBazelBackend.NativeTarget.forHost("Linux", null));
    }

    /**
     * Rejects a manifest without the parked waiter before any native process can start.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsManifestWithoutParkedWaiter() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            fixture.files.remove("linux-bazel-action-waiter");
            fixture.writeManifest();
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
        }
    }

    /**
     * Rejects distributions that cannot supply the environment-preserving Java launchers.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsManifestWithoutLinuxJavaLaunchers() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            fixture.files.remove("codex-tooling");
            fixture.files.remove("codex-cargo-wrapper-linux");
            fixture.writeManifest();
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
        }
    }

    /**
     * Rejects replacement of the independently sealed unprivileged parked image.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsChangedParkedWaiter() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            Files.writeString(fixture.root.resolve("linux-bazel-action-waiter"), "changed");
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
        }
    }

    /**
     * Rejects changed executable bytes while retaining the distribution for diagnosis.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsChangedExecutable() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            Files.writeString(fixture.root.resolve("bb_worker"), "changed");
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
            requireThat(Files.readString(fixture.root.resolve("bb_worker")), "diagnosticBytes").isEqualTo("changed");
        }
    }

    /**
     * Rejects a matching hash when the ELF executable targets a different processor.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsWrongElfArchitecture() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            fixture.writeExecutable("bb_worker", 62);
            fixture.writeManifest();
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
        }
    }

    /**
     * Rejects executable symlinks even when their target has the expected bytes.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsExecutableSymlink() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            Path worker = fixture.root.resolve("bb_worker");
            Files.move(worker, fixture.root.resolve("original"));
            Files.createSymbolicLink(worker, Path.of("original"));
            requireException(IOException.class, () -> LinuxBazelBackend.validateDistribution(scope,
                fixture.root, "Linux", "aarch64"));
        }
    }

    /**
     * Rejects an unsupported platform before native launch.
     * @throws Exception if test-local fixture creation or cleanup fails
     */
    @Test public void rejectsUnsupportedHost() throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            requireException(UnsupportedOperationException.class, () ->
                LinuxBazelBackend.validateDistribution(scope, fixture.root, "Windows", "aarch64"));
        }
    }

    /**
     * Requires explicit recovery to disable matching remote action-cache results without changing the command.
     * @throws Exception if fixture validation or the maintained Bazel policy fails
     */
    @Test public void recoveryPolicyRequiresAnUncachedBazelBuild() throws Exception
    {
        LinuxBazelBackend.Configuration normal = configuration();
        LinuxBazelBackend.Configuration recovery = new LinuxBazelBackend.Configuration(normal.distribution(),
            normal.invocationRoot(), normal.runnerAddress(), normal.credentials(), normal.concurrency(),
            normal.trustedUid(), normal.trustedGid(), normal.casBytes(), normal.actionCacheBytes(),
            LinuxBazelBackend.CachePolicy.UNCACHED_RECOVERY);
        String ordinary = startupRc(normal, 12345);
        String uncached = startupRc(recovery, 12345);
        requireThat(ordinary.contains("remote_accept_cached"), "ordinaryCacheOverride").isFalse();
        requireThat(uncached.lines().filter(line -> line.equals("build --remote_accept_cached=false")).count(),
            "explicitRecoveryOverride").isEqualTo(1L);
        requireThat(uncached.replace("build --remote_accept_cached=false\n", ""), "preservedRemoteTlsAndExecutionPolicy")
            .isEqualTo(ordinary);
    }

    /**
     * Keeps cached-result acceptance for the existing configuration constructor.
     */
    @Test public void existingConfigurationAcceptsCachedActions()
    {
        requireThat(configuration().cachePolicy(), "existingCallerCachePolicy")
            .isEqualTo(LinuxBazelBackend.CachePolicy.ACCEPT_CACHED);
    }

    /**
     * Supplies the mandatory positive certificate refresh interval consumed by the pinned TLS file-key API.
     */
    @Test public void fileCertificatesHaveTheRequiredRefreshInterval()
    {
        Map<String, Object> pair = BazelNativeBackend.fileKeyPair(Path.of("server.pem"), Path.of("server.key"));
        Map<?, ?> files = (Map<?, ?>) pair.get("files");
        requireThat(files.get("refreshInterval"), "fileCertificateRefreshInterval").isEqualTo("3600s");
    }

    /**
     * Uses the mandatory identity transfer encoding when the private backend advertises no compressor.
     * @throws Exception if fixture validation or the maintained backend policy fails
     */
    @Test public void privateBackendOverridesUnsupportedCacheCompression() throws Exception
    {
        String startup = startupRc(configuration(), 12345);
        requireThat(startup.lines().filter(line -> line.equals("common --noremote_cache_compression")).count(),
            "explicitUncompressedCacheTransport").isEqualTo(1L);
    }

    /**
     * Renders shared startup policy using a real validated non-executed distribution fixture.
     * This tests request construction only; it does not claim a live backend or native worker.
     * @param configuration retained Linux caller settings
     * @param port selected private storage port
     * @return shared mandatory startup policy
     * @throws Exception if fixture validation or resource reading fails
     */
    private static String startupRc(LinuxBazelBackend.Configuration configuration, int port) throws Exception
    {
        try (Fixture fixture = new Fixture(); DefaultToolingScope scope = new DefaultToolingScope())
        {
            BazelNativeBackend.ValidatedDistribution distribution = BazelNativeBackend.validateDistribution(scope,
                BazelNativeBackend.NativeTarget.ARM64, fixture.root);
            LinuxBazelBackend.Credentials paths = configuration.credentials();
            BazelBackendCredentials credentials = new BazelBackendCredentials(paths.certificateAuthority(),
                paths.serverCertificate(), paths.serverKey(), paths.workerCertificate(), paths.workerKey());
            BazelNativeBackend.Configuration shared = new BazelNativeBackend.Configuration(distribution,
                configuration.invocationRoot(), configuration.runnerAddress(), credentials, configuration.concurrency(),
                configuration.casBytes(), configuration.actionCacheBytes(),
                BazelNativeBackend.CachePolicy.valueOf(configuration.cachePolicy().name()),
                new java.util.concurrent.CompletableFuture<>(), () -> {});
            return BazelNativeBackend.startupRc(shared, port);
        }
    }

    /**
     * Supplies resource-free invocation values; no path is read or native process acquired.
     * @return independent ordinary backend configuration
     */
    private static LinuxBazelBackend.Configuration configuration()
    {
        Path root = Path.of("").toAbsolutePath().normalize().resolve("cache-policy-invocation");
        LinuxBazelBackend.Credentials credentials = new LinuxBazelBackend.Credentials(root.resolve("ca.pem"),
            root.resolve("server.pem"), root.resolve("server.key"), root.resolve("worker.pem"), root.resolve("worker.key"));
        return new LinuxBazelBackend.Configuration(root.resolve("distribution"), root, "localhost:12346", credentials,
            2, 1000, 1000, 67108864, 67108864);
    }

    /**
     * Owns ELF-header inputs and a manifest; these files are never executed.
     */
    private static final class Fixture implements AutoCloseable
    {
        /** Native ELF machine represented by this fixture. */
        private final int machine;
        /** Root owned only by this test invocation. */
        private final Path root = Files.createTempDirectory("bazel-backend-");
        /** Executable identities placed into this fixture's manifest. */
        private final Map<String, Map<String, Object>> files = new LinkedHashMap<>();

        /**
         * Creates a complete test-local manifest with non-executed ELF-header inputs.
         * @throws Exception if fixture files cannot be created
         */
        private Fixture() throws Exception { this(183); }

        /**
         * Creates non-executed native identity inputs for the requested processor.
         * @param machine ELF machine identifier
         * @throws Exception if fixture creation fails
         */
        private Fixture(int machine) throws Exception
        {
            this.machine = machine;
            try
            {
                for (String name : List.of("bb_storage", "bb_scheduler", "bb_worker", "linux-bazel-action-owner", "linux-bazel-action-waiter",
                    "codex-tooling", "codex-cargo-wrapper-linux"))
                    writeExecutable(name, machine);
                writeManifest();
            }
            catch (Exception failure)
            {
                PathUtils.deleteDirectory(root);
                throw failure;
            }
        }

        /**
         * Writes an identity input whose ELF machine header has the requested processor value.
         * @param name manifest executable name
         * @param machine ELF processor value
         * @throws IOException if fixture writing fails
         * @throws NoSuchAlgorithmException if SHA-256 is absent
         */
        private void writeExecutable(String name, int machine) throws IOException, NoSuchAlgorithmException
        {
            byte[] bytes = new byte[64];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1});
            buffer.putShort(18, (short) machine);
            Path path = root.resolve(name);
            Files.write(path, bytes);
            path.toFile().setExecutable(true, true);
            files.put(name, Map.of("path", name, "sha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }

        /**
         * Serializes the fixture's recorded identities with an independently owned Pouch mapper.
         * @throws IOException if manifest writing fails
         */
        private void writeManifest() throws IOException
        {
            try (DefaultToolingScope scope = new DefaultToolingScope())
            {
                Map<String, Object> manifest = Map.of("schema", 1, "target", machine == 183 ? "aarch64-unknown-linux-musl" : "x86_64-unknown-linux-musl",
                    "executionCommit", "1fbffdc417797f6afdb7f67b1f0d5cc27352db43",
                    "storageCommit", "025ab8081c7f", "executables", files);
                Files.writeString(root.resolve("backend-manifest.json"),
                    scope.getJsonMapper().writeValueAsString(manifest));
            }
        }

        @Override public void close() throws IOException { PathUtils.deleteDirectory(root); }
    }
}
