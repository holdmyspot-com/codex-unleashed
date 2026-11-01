package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns one invocation's pinned Buildbarn storage, scheduler and worker processes.
 * Daemons inherit the root supervisor UID; the separate trusted consumer UID owns their private
 * paths and TLS leaf files, while action UIDs receive neither. The caller retains infrastructure
 * containment and the Runner service until this owner closes.
 */
final class LinuxBazelBackend implements AutoCloseable
{
    /** Distribution manifest emitted only after all native binaries have been built and hashed. */
    private static final String MANIFEST = "backend-manifest.json";
    /** Supported real Codex release target; every executable must also have its native ARM64 ELF header. */
    private static final String TARGET = "aarch64-unknown-linux-musl";
    /** Buildbarn source identity whose configuration and Runner protocol this adapter implements. */
    private static final String EXECUTION_COMMIT = "1fbffdc417797f6afdb7f67b1f0d5cc27352db43";
    /** bb-storage module revision pinned by the selected Buildbarn checkout. */
    private static final String STORAGE_COMMIT = "025ab8081c7f";
    /** Complete executable set required before any backend process may start. */
    private static final Set<String> EXECUTABLES = Set.of("bb_storage", "bb_scheduler", "bb_worker",
        "linux-bazel-action-owner", "linux-bazel-action-waiter", "codex-tooling", "codex-cargo-wrapper-linux");
    /** Invocation state and keys remain inaccessible to the unprivileged action user. */
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    /** Credentials remain readable and writable only by their trusted owner. */
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");
    /** Class-relative maintained configuration resources. */
    private static final String RESOURCE_ROOT = "buildbarn/";
    /** Processes in acquisition order; retained after uncertain shutdown. */
    private final List<Process> daemons = new ArrayList<>();
    /** Caller-owned Runner cleanup must reach zero before its backend disappears. */
    private final Cleanup cleanup;
    /** Invocation configuration retained for endpoint and directory ownership. */
    private final Configuration configuration;
    /** Private storage endpoint also forwards execution to the scheduler. */
    private final String address;
    /** Host-selected startup configuration consumed by the trusted Bazel launcher. */
    private final Path startupRc;
    /** Indicates whether Runner cleanup and daemon reaping completed. */
    private boolean closed;

    /**
     * Retains initialized ownership before any subprocess acquisition.
     * @param configuration validated invocation settings
     * @param cleanup owner of outstanding Runner cleanup leases
     * @param address private storage address
     * @param startupRc private Bazel policy file
     */
    private LinuxBazelBackend(Configuration configuration, Cleanup cleanup, String address, Path startupRc)
    {
        this.configuration = configuration;
        this.cleanup = cleanup;
        this.address = address;
        this.startupRc = startupRc;
    }

    /**
     * Validates and stages one prebuilt backend for the optional distribution assembler.
     * @param args exactly source backend directory and absent destination directory
     * @throws IOException if copying or identity validation fails
     * @throws IllegalArgumentException if argument count is invalid
     * @throws UnsupportedOperationException if staging is attempted outside Linux ARM64
     */
    public static void main(String[] args) throws IOException
    {
        if (args.length != 2) throw new IllegalArgumentException("Expected native backend source and new staging directory");
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        try (com.holdmyspot.codexunleashed.tooling.DefaultToolingScope scope =
            new com.holdmyspot.codexunleashed.tooling.DefaultToolingScope())
        {
            Map<String, Path> selected = validateDistribution(scope, source,
                System.getProperty("os.name"), System.getProperty("os.arch"));
            byte[] manifest = Files.readAllBytes(source.resolve(MANIFEST));
            Files.createDirectory(target);
            try
            {
                for (Map.Entry<String, Path> executable : selected.entrySet())
                {
                    Path output = target.resolve(executable.getKey());
                    Files.copy(executable.getValue(), output);
                    Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
                Files.write(target.resolve(MANIFEST), manifest);
                validateDistribution(scope, target, System.getProperty("os.name"), System.getProperty("os.arch"));
                Path licenses = source.resolve("licenses");
                if (Files.isDirectory(licenses, LinkOption.NOFOLLOW_LINKS))
                {
                    Files.createDirectory(target.resolve("licenses"));
                    try (java.util.stream.Stream<Path> files = Files.list(licenses))
                    {
                        for (Path file : files.toList())
                        {
                            requireRegularFile(file);
                            Files.copy(file, target.resolve("licenses").resolve(file.getFileName()));
                        }
                    }
                }
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { org.apache.commons.io.file.PathUtils.deleteDirectory(target); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }
    }

    /**
     * Starts native daemons after validating all binaries, credentials and invocation directories.
     * The Runner endpoint must already be listening and its build-root descriptor must refer to
     * {@code configuration.invocationRoot().resolve("worker-builds")} before this call.
     * The root supervisor and its trusted infrastructure containment must contain this thread and
     * inherited daemon children; the configured trusted UID owns worker files, not daemon processes.
     * Failed acquisition closes acquired daemons and retains its cause, including uncertain cleanup.
     * @param scope borrowed Pouch mapper scope
     * @param configuration native distribution, paths, identities and explicit cache capacities
     * @param cleanup cancels Runner admissions and waits for all native cleanup leases
     * @return started backend; close it after the original Bazel producer terminates
     * @throws IOException if identity validation, configuration, startup or readiness fails
     * @throws UnsupportedOperationException if the current host is not Linux ARM64
     */
    static LinuxBazelBackend start(ToolingScope scope, Configuration configuration, Cleanup cleanup)
        throws IOException
    {
        Objects.requireNonNull(cleanup, "cleanup");
        Map<String, Path> executables = validateDistribution(scope, configuration.distribution(),
            System.getProperty("os.name"), System.getProperty("os.arch"));
        Path root = configuration.invocationRoot();
        requirePrivateDirectory(root);
        validateCredentials(configuration);
        Path backendRoot = root.resolve("backend");
        Files.createDirectory(backendRoot, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
        trustedOwnership(backendRoot, configuration);
        for (String name : List.of("cas", "action-cache", "worker-cache", "file-pool"))
        {
            Files.createDirectory(root.resolve(name), PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
            trustedOwnership(root.resolve(name), configuration);
        }
        Path workerRoot = root.resolve("worker-builds");
        Files.setPosixFilePermissions(workerRoot, PRIVATE_DIRECTORY);
        trustedOwnership(workerRoot, configuration);
        requirePrivateDirectory(workerRoot);

        int storagePort = unusedPort();
        int schedulerPort = unusedPort();
        int workerPort = unusedPort();
        while (schedulerPort == storagePort) schedulerPort = unusedPort();
        while (workerPort == storagePort || workerPort == schedulerPort) workerPort = unusedPort();
        Path rc = backendRoot.resolve("backend.bazelrc");
        LinuxBazelBackend backend = new LinuxBazelBackend(configuration, cleanup,
            "grpcs://localhost:" + storagePort, rc);
        try
        {
            JsonMapper mapper = scope.getJsonMapper();
            Map<String, Object> values = backend.configurationValues(storagePort, schedulerPort, workerPort);
            for (String name : List.of("storage", "scheduler", "worker"))
                writeConfiguration(mapper, backendRoot.resolve(name + ".json"), name + ".json.template", values);
            backend.writeBazelRc(storagePort);
            try (java.util.stream.Stream<Path> files = Files.list(backendRoot))
            {
                for (Path file : files.toList()) trustedOwnership(file, configuration);
            }
            backend.launch(executables.get("bb_storage"), backendRoot.resolve("storage.json"));
            backend.awaitEndpoint("localhost:" + storagePort);
            backend.launch(executables.get("bb_scheduler"), backendRoot.resolve("scheduler.json"));
            backend.awaitEndpoint("localhost:" + schedulerPort);
            backend.awaitEndpoint("localhost:" + workerPort);
            backend.launch(executables.get("bb_worker"), backendRoot.resolve("worker.json"));
            backend.requireAlive();
            return backend;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { backend.close(); }
            catch (IOException | RuntimeException | Error cleanupFailure)
            {
                throw new AcquisitionFailure(backend, failure, cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Retains a partially acquired backend when cleanup has not established native quiescence.
     */
    @SuppressWarnings("serial") // A live process owner cannot be serialized; callers retain this original exception.
    static final class AcquisitionFailure extends IOException
    {
        /** Local exception format identity; the retained live owner is never serialized. */
        private static final long serialVersionUID = 1L;
        /** Exact process owner retained until a later close establishes quiescence. */
        private final LinuxBazelBackend backend;

        /**
         * Retains both the initial acquisition failure and the failed cleanup attempt.
         * @param backend owner that must be retained and closed again
         * @param acquisitionFailure original acquisition failure
         * @param cleanupFailure cleanup failure that prevented ownership release
         */
        private AcquisitionFailure(LinuxBazelBackend backend, Throwable acquisitionFailure, Throwable cleanupFailure)
        {
            super("Backend acquisition failed and cleanup remains incomplete", acquisitionFailure);
            this.backend = backend;
            addSuppressed(cleanupFailure);
        }

        /**
         * Returns the original retained owner for cleanup retry.
         * @return backend whose original processes and cleanup callback remain owned
         */
        LinuxBazelBackend backend() { return backend; }
    }

    /**
     * Validates source pins, exact executable membership, no-follow hashes and ARM64 ELF headers.
     * @param scope borrowed Pouch mapper scope
     * @param distribution directory containing the manifest and all executables
     * @param operatingSystem host operating system, resolved at the process boundary
     * @param architecture host processor, resolved at the process boundary
     * @return immutable native executable paths indexed by manifest name
     * @throws IOException if the manifest, file identity or architecture does not match
     * @throws UnsupportedOperationException if the host is not Linux ARM64
     */
    static Map<String, Path> validateDistribution(ToolingScope scope, Path distribution,
        String operatingSystem, String architecture) throws IOException
    {
        boolean arm64 = architecture.equals("aarch64") || architecture.equals("arm64");
        if (!operatingSystem.equals("Linux") || !arm64)
            throw new UnsupportedOperationException("Production Bazel backend requires Linux ARM64: " +
                operatingSystem + "/" + architecture);
        Path root = distribution.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native backend directory is absent or is a symbolic link: " + root);
        Path manifest = root.resolve(MANIFEST);
        requireRegularFile(manifest);
        JsonNode document = scope.getJsonMapper().readTree(Files.readAllBytes(manifest));
        boolean correctPins = document.path("schema").asInt() == 1 && TARGET.equals(document.path("target").asString()) &&
            EXECUTION_COMMIT.equals(document.path("executionCommit").asString()) &&
            STORAGE_COMMIT.equals(document.path("storageCommit").asString());
        if (!correctPins) throw new IOException("Native backend manifest has unsupported target, schema or source pins: " + manifest);
        JsonNode entries = document.path("executables");
        Set<String> names = new java.util.HashSet<>();
        entries.propertyNames().forEach(names::add);
        if (!names.equals(EXECUTABLES)) throw new IOException("Native backend manifest executable set differs: " + names);
        Map<String, Path> selected = new LinkedHashMap<>();
        for (String name : EXECUTABLES)
        {
            JsonNode entry = entries.path(name);
            if (!entry.path("path").asString().equals(name))
                throw new IOException("Native backend executable path must be its fixed manifest name: " + name);
            Path executable = root.resolve(name);
            requireRegularFile(executable);
            if (!Files.isExecutable(executable)) throw new IOException("Native backend executable lacks execute permission: " + executable);
            String digest = sha256(executable);
            if (!digest.equals(entry.path("sha256").asString()))
                throw new IOException("Native backend executable SHA-256 differs: " + executable);
            try (InputStream input = Files.newInputStream(executable, LinkOption.NOFOLLOW_LINKS))
            {
                byte[] header = input.readNBytes(64);
                boolean nativeElf = header.length == 64 && header[0] == 0x7f && header[1] == 'E' &&
                    header[2] == 'L' && header[3] == 'F' && header[4] == 2 && header[5] == 1 &&
                    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18) == 183;
                if (!nativeElf) throw new IOException("Native backend executable is not little-endian ARM64 ELF64: " + executable);
            }
            selected.put(name, executable);
        }
        return Map.copyOf(selected);
    }

    /**
     * Returns the mTLS execution endpoint selected by the private storage frontend.
     * @return invocation-private grpcs address
     */
    String executorAddress() { return address; }

    /**
     * Returns the mTLS CAS/action-cache endpoint.
     * @return invocation-private grpcs address
     */
    String cacheAddress() { return address; }

    /**
     * Returns the startup rc containing mandatory execution/cache/TLS policy.
     * @return host-owned rc path passed through the launcher's --bazelrc startup argument
     */
    Path startupRc() { return startupRc; }

    /**
     * Returns the pinned worker directory containing request-relative input roots.
     * @return existing invocation-owned worker build directory
     */
    Path workerBuildRoot() { return configuration.invocationRoot().resolve("worker-builds"); }

    /**
     * Returns backend process identities for separately reserved infrastructure accounting.
     * @return immutable handles, never action estimates
     */
    List<ProcessHandle> processes() { return daemons.stream().map(Process::toHandle).toList(); }

    /**
     * Rejects a backend whose original daemon has exited unexpectedly.
     * @throws IOException if a backend daemon has exited
     */
    void requireAlive() throws IOException
    {
        for (Process daemon : daemons)
            if (!daemon.isAlive()) throw new IOException("Native backend daemon exited with status " + daemon.exitValue() +
                "; inspect invocation backend logs");
    }

    /**
     * Revokes Runner work and waits for its independent cleanup leases before stopping native daemons.
     * A failed cleanup retains live backend ownership; callers must retry closure after resolving it.
     * @throws IOException if cleanup authority remains open or a daemon cannot be reaped
     */
    @Override public synchronized void close() throws IOException
    {
        if (closed) return;
        cleanup.close();
        boolean interrupted = Thread.interrupted();
        try
        {
            for (Process daemon : daemons.reversed())
            {
                daemon.destroy();
                boolean terminated = false;
                while (!terminated)
                {
                    try { daemon.waitFor(); terminated = true; }
                    catch (InterruptedException failure) { interrupted = true; }
                }
            }
            closed = true;
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * Builds values for maintained configurations using the pinned Buildbarn protobuf field contracts.
     * @param storagePort storage frontend TCP port
     * @param schedulerPort scheduler client TCP port
     * @param workerPort scheduler worker TCP port
     * @return typed substitutions consumed by the JSON templates
     * @throws IOException if a credential cannot be read
     */
    private Map<String, Object> configurationValues(int storagePort, int schedulerPort, int workerPort)
        throws IOException
    {
        Path root = configuration.invocationRoot();
        Credentials credentials = configuration.credentials();
        String ca = Files.readString(credentials.certificateAuthority());
        Map<String, Object> keyPair = fileKeyPair(credentials.serverCertificate(), credentials.serverKey());
        Map<String, Object> clientTls = Map.of("serverCertificateAuthorities", ca, "serverName", "localhost",
            "clientKeyPair", fileKeyPair(credentials.workerCertificate(), credentials.workerKey()));
        Map<String, Object> authentication = Map.of("tlsClientCertificate", Map.of("clientCertificateAuthorities", ca,
            "validationJmespathExpression", Map.of("expression", "`true`"),
            "metadataExtractionJmespathExpression", Map.of("expression", "`{}`")));
        Map<String, Object> storageClient = Map.of("address", "localhost:" + storagePort, "tls", clientTls);
        Map<String, Object> schedulerClient = Map.of("address", "localhost:" + schedulerPort, "tls", clientTls);
        Map<String, Object> schedulerWorker = Map.of("address", "localhost:" + workerPort, "tls", clientTls);
        Map<String, Object> runnerClient = Map.of("address", configuration.runnerAddress(), "tls", clientTls);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("STORAGE_SERVER", server(storagePort, keyPair, authentication));
        values.put("SCHEDULER_SERVER", server(schedulerPort, keyPair, authentication));
        values.put("WORKER_SERVER", server(workerPort, keyPair, authentication));
        values.put("STORAGE_CLIENT", storageClient);
        values.put("SCHEDULER_CLIENT", schedulerClient);
        values.put("SCHEDULER_WORKER", schedulerWorker);
        values.put("RUNNER_CLIENT", runnerClient);
        values.put("CAS_LOCAL", localStore(root.resolve("cas"), configuration.casBytes(), 3));
        values.put("AC_LOCAL", localStore(root.resolve("action-cache"), configuration.actionCacheBytes(), 1));
        values.put("WORKER_ROOT", workerBuildRoot().toString());
        values.put("WORKER_CACHE", root.resolve("worker-cache").toString());
        values.put("FILE_POOL", root.resolve("file-pool").resolve("data").toString());
        values.put("CONCURRENCY", configuration.concurrency());
        values.put("TRUSTED_UID", configuration.trustedUid());
        values.put("TRUSTED_GID", configuration.trustedGid());
        return values;
    }

    /**
     * Configures an invocation-owned certificate pair using the pinned daemon's file-key API.
     * The positive refresh duration is required even for certificates whose bytes remain fixed for this invocation.
     * @param certificate exact host-owned PEM certificate path
     * @param privateKey exact host-owned private key path
     * @return typed file-certificate configuration shared by server and client TLS
     */
    static Map<String, Object> fileKeyPair(Path certificate, Path privateKey)
    {
        return Map.of("files", Map.of("certificatePath", certificate.toString(),
            "privateKeyPath", privateKey.toString(), "refreshInterval", "3600s"));
    }

    /**
     * Constructs a loopback-only TLS server with mandatory client-certificate authentication.
     * @param port invocation-selected TCP port
     * @param keyPair trusted server certificate and key
     * @param authentication invocation CA client verifier
     * @return pinned Buildbarn ServerConfiguration JSON members
     */
    private static Map<String, Object> server(int port, Map<String, Object> keyPair,
        Map<String, Object> authentication)
    {
        return Map.of("listenAddresses", List.of("127.0.0.1:" + port), "tls", Map.of("serverKeyPair", keyPair),
            "authenticationPolicy", authentication, "maximumReceivedMessageSizeBytes", 67108864);
    }

    /**
     * Configures a disk-backed private circular store using upstream recommended block/hash settings.
     * @param directory owned store directory
     * @param bytes explicit data capacity in bytes
     * @param newBlocks CAS uses three new blocks; mutable action cache uses one
     * @return supported LocalBlobAccessConfiguration wrapper
     */
    private static Map<String, Object> localStore(Path directory, long bytes, int newBlocks)
    {
        Map<String, Object> map = Map.of("onBlockDevice", Map.of("file", Map.of("path",
            directory.resolve("keys").toString(), "sizeBytes", Math.max(1048576, bytes / 64))),
            "maximumGetAttempts", 16, "maximumPutAttempts", 64);
        return Map.of("local", Map.of("keyLocationMap", map, "oldBlocks", 8, "currentBlocks", 24,
            "newBlocks", newBlocks, "blocksOnBlockDevice", Map.of("source", Map.of("file",
                Map.of("path", directory.resolve("data").toString(), "sizeBytes", bytes)), "spareBlocks", 3),
            "persistent", Map.of("stateDirectoryPath", directory.toString(), "minimumEpochInterval", "300s")));
    }

    /**
     * Renders a maintained template using JSON serialization for every typed substitution.
     * @param mapper borrowed scope mapper
     * @param output new invocation-owned configuration path
     * @param resource class-relative template name
     * @param values complete host-selected JSON substitutions
     * @throws IOException if a resource, substitution or output is invalid
     */
    private static void writeConfiguration(JsonMapper mapper, Path output, String resource,
        Map<String, Object> values) throws IOException
    {
        String text;
        try (InputStream input = LinuxBazelBackend.class.getResourceAsStream(RESOURCE_ROOT + resource))
        {
            if (input == null) throw new IOException("Packaged Buildbarn configuration is absent: " + resource);
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, Object> value : values.entrySet())
            text = text.replace('"' + "@" + value.getKey() + "@" + '"', mapper.writeValueAsString(value.getValue()));
        if (text.contains("\"@")) throw new IOException("Buildbarn configuration contains an unresolved substitution: " + resource);
        JsonNode parsed = mapper.readTree(text);
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        Files.setPosixFilePermissions(output, PRIVATE_FILE);
    }

    /**
     * Writes mandatory remote-only Bazel policy using host-issued TLS credentials.
     * @param storagePort invocation-private storage frontend port
     * @throws IOException if the maintained template cannot be read or written
     */
    private void writeBazelRc(int storagePort) throws IOException
    {
        Files.writeString(startupRc, startupRc(configuration, storagePort));
        Files.setPosixFilePermissions(startupRc, PRIVATE_FILE);
    }

    /**
     * Renders maintained remote-only startup policy independently of native daemon acquisition.
     * @param configuration exact invocation credentials, concurrency and explicit action-cache policy
     * @param storagePort private backend storage/frontend port
     * @return complete private Bazel rc, preserving the original consumer argument vector
     * @throws IOException if the packaged policy template cannot be read
     */
    static String startupRc(Configuration configuration, int storagePort) throws IOException
    {
        String template;
        try (InputStream input = LinuxBazelBackend.class.getResourceAsStream(RESOURCE_ROOT + "backend.bazelrc.template"))
        {
            if (input == null) throw new IOException("Packaged backend Bazel policy is absent");
            template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Credentials credentials = configuration.credentials();
        Map<String, String> values = Map.of("ENDPOINT", "grpcs://localhost:" + storagePort,
            "CA", rcQuote(credentials.certificateAuthority()), "CERT", rcQuote(credentials.workerCertificate()),
            "KEY", rcQuote(credentials.workerKey()), "JOBS", Integer.toString(configuration.concurrency()));
        for (Map.Entry<String, String> value : values.entrySet())
            template = template.replace("@" + value.getKey() + "@", value.getValue());
        if (configuration.cachePolicy() == CachePolicy.UNCACHED_RECOVERY)
            template += "build --remote_accept_cached=false\n";
        return template;
    }

    /**
     * Quotes a host path for Bazel's documented shell-like rc token syntax.
     * @param path host-selected file path
     * @return quoted rc argument, preserving spaces and literal backslashes
     */
    private static String rcQuote(Path path)
    {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Starts an exact-hash native daemon with one supported configuration argument and owned logs.
     * @param executable validated pinned daemon executable
     * @param config invocation-owned JSON configuration
     * @throws IOException if startup fails
     */
    private void launch(Path executable, Path config) throws IOException
    {
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), config.toString());
        builder.directory(configuration.invocationRoot().toFile());
        builder.environment().clear();
        builder.environment().put("TMPDIR", configuration.invocationRoot().resolve("file-pool").toString());
        builder.redirectErrorStream(true).redirectOutput(config.resolveSibling(config.getFileName() + ".log").toFile());
        daemons.add(builder.start());
    }

    /**
     * Waits for authenticated HTTP/2 transport availability while also observing every original daemon.
     * Actual worker synchronization and real command behavior remain native acceptance conditions.
     * @param endpoint private backend address
     * @throws IOException if TLS, a daemon, or the waiting thread fails
     */
    private void awaitEndpoint(String endpoint) throws IOException
    {
        Credentials credentials = configuration.credentials();
        SslContext ssl = GrpcSslContexts.configure(GrpcSslContexts.forClient(), SslProvider.JDK).
            trustManager(credentials.certificateAuthority().toFile()).
            keyManager(credentials.workerCertificate().toFile(), credentials.workerKey().toFile()).build();
        ManagedChannel channel = NettyChannelBuilder.forTarget(endpoint).overrideAuthority("localhost").sslContext(ssl).build();
        try
        {
            while (true)
            {
                requireAlive();
                ConnectivityState state = channel.getState(true);
                if (state == ConnectivityState.READY) return;
                CountDownLatch change = new CountDownLatch(1);
                channel.notifyWhenStateChanged(state, change::countDown);
                for (Process daemon : daemons) daemon.onExit().thenRun(change::countDown);
                try { change.await(); }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while connecting to private backend " + endpoint, failure);
                }
            }
        }
        finally { channel.shutdownNow(); }
    }

    /**
     * Chooses a currently unbound loopback port; daemon startup detects acquisition races.
     * @return available local TCP port
     * @throws IOException if loopback binding fails
     */
    private static int unusedPort() throws IOException
    {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            return socket.getLocalPort();
        }
    }

    /**
     * Validates that every key stays in an owner-only tree with the trusted worker UID.
     * @param configuration invocation credentials and trusted identity
     * @throws IOException if credential paths, ownership or permissions are unsafe
     */
    private static void validateCredentials(Configuration configuration) throws IOException
    {
        Path root = configuration.invocationRoot();
        for (Path path : configuration.credentials().paths())
        {
            requireRegularFile(path);
            if (!path.startsWith(root)) throw new IOException("Backend credential lies outside the invocation: " + path);
            Path parent = path.getParent();
            while (parent.startsWith(root))
            {
                requirePrivateDirectory(parent);
                if (parent.equals(root)) break;
                parent = parent.getParent();
            }
            int uid = (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            if (uid != configuration.trustedUid() || !PRIVATE_FILE.containsAll(Files.getPosixFilePermissions(path)))
                throw new IOException("Backend credential ownership or permissions differ from its trusted owner: " + path);
        }
    }

    /**
     * Assigns a new invocation object to the trusted original-consumer and worker-file UID and GID.
     * @param path new invocation-owned file or directory
     * @param configuration trusted file-owner identity
     * @throws IOException if ownership cannot be established
     */
    private static void trustedOwnership(Path path, Configuration configuration) throws IOException
    {
        Files.setAttribute(path, "unix:uid", configuration.trustedUid(), LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(path, "unix:gid", configuration.trustedGid(), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Requires a real invocation directory whose group and other users cannot change its entries.
     * Execute-only traversal permits distinct trusted producer/backend UIDs without exposing secrets.
     * @param path host-owned invocation directory
     * @throws IOException if its type or write permissions are unsafe
     */
    private static void requireInvocationDirectory(Path path) throws IOException
    {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invocation directory is absent or is a symlink: " + path);
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (mode.contains(PosixFilePermission.GROUP_WRITE) || mode.contains(PosixFilePermission.OTHERS_WRITE))
            throw new IOException("Invocation directory is writable by a different identity: " + path);
    }

    /**
     * Requires an existing no-follow owner-only directory.
     * @param path trusted invocation directory
     * @throws IOException if its type or permissions are unsafe
     */
    private static void requirePrivateDirectory(Path path) throws IOException
    {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
            !PRIVATE_DIRECTORY.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("Backend directory must be a real owner-only directory: " + path);
    }

    /**
     * Requires a regular file without accepting a symbolic link.
     * @param path trusted input file
     * @throws IOException if its no-follow type is not regular
     */
    private static void requireRegularFile(Path path) throws IOException
    {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Backend file is absent, nonregular or a symbolic link: " + path);
    }

    /**
     * Computes exact file bytes using the platform SHA-256 implementation.
     * @param path no-follow file to hash
     * @return lowercase SHA-256 hexadecimal digest
     * @throws IOException if reading fails
     */
    private static String sha256(Path path) throws IOException
    {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError("JDK lacks SHA-256", failure); }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) >= 0) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Cancels Runner admissions and waits for all native cleanup leases to reach zero.
     */
    @FunctionalInterface interface Cleanup
    {
        /**
         * Completes cleanup or retains authority for a later explicit closure attempt.
         * @throws IOException if zero cannot be established
         */
        void close() throws IOException;
    }

    /**
     * Selects action-cache acceptance separately from the original Bazel consumer argv.
     */
    enum CachePolicy
    {
        /** Ordinary builds may accept matching remote action-cache entries. */
        ACCEPT_CACHED,
        /** Explicit recovery builds must execute actions instead of accepting cached action results. */
        UNCACHED_RECOVERY
    }

    /**
     * Defines one private native backend with caller-selected cache capacities.
     * @param distribution trusted native executable directory
     * @param invocationRoot existing owner-only invocation directory
     * @param runnerAddress already-listening authenticated Runner host:port endpoint
     * @param credentials invocation-private CA, server and trusted worker key pair
     * @param concurrency maximum simultaneously admitted worker RPCs
     * @param trustedUid trusted original-consumer and worker-directory UID, distinct from the action UID
     * @param trustedGid trusted original-consumer and worker-directory primary GID
     * @param casBytes explicit CAS data-file capacity in bytes
     * @param actionCacheBytes explicit action-cache data-file capacity in bytes
     * @param cachePolicy explicit ordinary or uncached recovery behavior
     */
    record Configuration(Path distribution, Path invocationRoot, String runnerAddress, Credentials credentials,
        int concurrency, int trustedUid, int trustedGid, long casBytes, long actionCacheBytes, CachePolicy cachePolicy)
    {
        /**
         * Preserves ordinary action-cache acceptance for existing production callers.
         * @param distribution trusted executable directory
         * @param invocationRoot owner-only invocation directory
         * @param runnerAddress authenticated Runner endpoint
         * @param credentials invocation-private credentials
         * @param concurrency positive worker RPC capacity
         * @param trustedUid original-consumer and worker-file UID
         * @param trustedGid original-consumer and worker-file GID
         * @param casBytes explicit CAS capacity
         * @param actionCacheBytes explicit action-cache capacity
         */
        Configuration(Path distribution, Path invocationRoot, String runnerAddress, Credentials credentials,
            int concurrency, int trustedUid, int trustedGid, long casBytes, long actionCacheBytes)
        {
            this(distribution, invocationRoot, runnerAddress, credentials, concurrency, trustedUid, trustedGid,
                casBytes, actionCacheBytes, CachePolicy.ACCEPT_CACHED);
        }

        /**
         * Validates configuration before it can acquire a process or modify state.
         * @param distribution trusted native executable directory
         * @param invocationRoot existing owner-only invocation directory
         * @param runnerAddress authenticated Runner host:port address
         * @param credentials trusted invocation credentials
         * @param concurrency positive worker RPC capacity
         * @param trustedUid nonnegative worker UID
         * @param trustedGid nonnegative worker GID
         * @param casBytes CAS bytes, at least 64 MiB for the configured block layout
         * @param actionCacheBytes action-cache bytes, at least 64 MiB
         * @param cachePolicy explicit ordinary or uncached recovery behavior
         * @throws IllegalArgumentException if address, capacity or identity is invalid
         */
        Configuration
        {
            distribution = distribution.toAbsolutePath().normalize();
            invocationRoot = invocationRoot.toAbsolutePath().normalize();
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            if (runnerAddress.isBlank()) throw new IllegalArgumentException("Runner address is blank");
            if (concurrency <= 0 || trustedUid < 0 || trustedGid < 0 || casBytes < 67108864 || actionCacheBytes < 67108864)
                throw new IllegalArgumentException("Backend concurrency, trusted identity or cache capacity is invalid");
        }
    }

    /**
     * Carries invocation-private PEM paths supplied by the trusted producer.
     * @param certificateAuthority PEM CA certificate used for this invocation only
     * @param serverCertificate PEM server certificate valid for localhost
     * @param serverKey PKCS8 PEM server private key
     * @param workerCertificate PEM authenticated worker/client certificate
     * @param workerKey PKCS8 PEM worker private key
     */
    record Credentials(Path certificateAuthority, Path serverCertificate, Path serverKey,
        Path workerCertificate, Path workerKey)
    {
        /**
         * Retains absolute normalized host-owned credential paths.
         * @param certificateAuthority PEM invocation CA
         * @param serverCertificate PEM localhost server certificate
         * @param serverKey PEM server key
         * @param workerCertificate PEM client certificate
         * @param workerKey PEM client key
         */
        Credentials
        {
            certificateAuthority = certificateAuthority.toAbsolutePath().normalize();
            serverCertificate = serverCertificate.toAbsolutePath().normalize();
            serverKey = serverKey.toAbsolutePath().normalize();
            workerCertificate = workerCertificate.toAbsolutePath().normalize();
            workerKey = workerKey.toAbsolutePath().normalize();
        }

        /**
         * Issues an invocation CA and localhost server/trusted-worker leaf certificates with JDK keytool.
         * The caller owns the new directory and must remove it after every server and native process closes.
         * Certificate validity is one day; each invocation receives a new CA and private keys.
         * @param credentialRoot new directory under an already private invocation root
         * @param keytool absolute trusted JDK keytool executable
         * @return issued PEM credentials; signing stores remain private beneath the owned directory
         * @throws IOException if issuance or protected-file creation fails
         */
        static Credentials generate(Path credentialRoot, Path keytool) throws IOException
        {
            Path root = credentialRoot.toAbsolutePath().normalize();
            requireInvocationDirectory(root.getParent());
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            Path password = root.resolve("store-password");
            char[] chars = HexFormat.of().formatHex(secret).toCharArray();
            try
            {
                Files.writeString(password, new String(chars));
                Files.setPosixFilePermissions(password, PRIVATE_FILE);
                issue(keytool, root, "-genkeypair", "-alias", "ca", "-dname", "CN=Invocation CA",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1", "-ext", "BC=ca:true",
                    "-keystore", root.resolve("ca.p12").toString(), "-storetype", "PKCS12", "-storepass:file", password.toString());
                issue(keytool, root, "-exportcert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
                    "-storepass:file", password.toString(), "-file", root.resolve("ca.pem").toString());
                for (String name : List.of("server", "worker")) issueLeaf(keytool, root, name, password, chars);
                try (java.util.stream.Stream<Path> paths = Files.list(root))
                {
                    for (Path path : paths.toList()) Files.setPosixFilePermissions(path, PRIVATE_FILE);
                }
                return new Credentials(root.resolve("ca.pem"), root.resolve("server-cert.pem"), root.resolve("server-key.pem"),
                    root.resolve("worker-cert.pem"), root.resolve("worker-key.pem"));
            }
            catch (GeneralSecurityException failure)
            {
                IOException reported = new IOException("JDK could not read the invocation certificate keystore", failure);
                try { org.apache.commons.io.file.PathUtils.deleteDirectory(root); }
                catch (IOException cleanupFailure) { reported.addSuppressed(cleanupFailure); }
                throw reported;
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { org.apache.commons.io.file.PathUtils.deleteDirectory(root); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
            finally { java.util.Arrays.fill(chars, '\0'); java.util.Arrays.fill(secret, (byte) 0); }
        }

        /**
         * Issues one CA-signed leaf and exports its PKCS8 key without exposing a password in argv.
         * @param keytool trusted JDK keytool
         * @param root private credential directory containing the CA store
         * @param name server or worker leaf role
         * @param password protected keystore password file
         * @param chars in-memory keystore password
         * @throws IOException if issuance or PEM writing fails
         * @throws GeneralSecurityException if the generated keystore cannot be read
         */
        private static void issueLeaf(Path keytool, Path root, String name, Path password, char[] chars)
            throws IOException, GeneralSecurityException
        {
            Path store = root.resolve(name + ".p12");
            issue(keytool, root, "-genkeypair", "-alias", name, "-dname", "CN=" + name,
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1", "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass:file", password.toString());
            Path request = root.resolve(name + ".csr");
            issue(keytool, root, "-certreq", "-alias", name, "-keystore", store.toString(),
                "-storepass:file", password.toString(), "-file", request.toString());
            String extendedUsage = name.equals("server") ? "serverAuth" : "clientAuth";
            issue(keytool, root, "-gencert", "-rfc", "-alias", "ca", "-keystore", root.resolve("ca.p12").toString(),
                "-storepass:file", password.toString(), "-infile", request.toString(), "-outfile",
                root.resolve(name + "-cert.pem").toString(), "-validity", "1", "-ext", "KU=digitalSignature",
                "-ext", "EKU=" + extendedUsage, "-ext", "SAN=DNS:localhost,IP:127.0.0.1");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(store)) { keys.load(input, chars); }
            byte[] encoded = keys.getKey(name, chars).getEncoded();
            String pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded) + "\n-----END PRIVATE KEY-----\n";
            Files.writeString(root.resolve(name + "-key.pem"), pem);
            java.util.Arrays.fill(encoded, (byte) 0);
        }

        /**
         * Runs the supported JDK certificate tool with owned diagnostics and waits for its original exit.
         * @param keytool trusted JDK executable
         * @param root private credential working directory
         * @param arguments exact keytool argument elements
         * @throws IOException if keytool cannot run or reports a failure
         */
        private static void issue(Path keytool, Path root, String... arguments) throws IOException
        {
            List<String> command = new ArrayList<>();
            command.add(keytool.toAbsolutePath().toString());
            command.addAll(List.of(arguments));
            Path log = root.resolve("keytool.log");
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).
                redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process process = builder.start();
            boolean interrupted = false;
            int result;
            while (true)
            {
                try { result = process.waitFor(); break; }
                catch (InterruptedException failure) { interrupted = true; process.destroy(); }
            }
            if (interrupted)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during invocation certificate issuance; keytool was reaped");
            }
            if (result != 0) throw new IOException("JDK keytool failed with status " + result + ": " + Files.readString(log));
        }

        /**
         * Returns every credential file for no-follow permission validation.
         * @return immutable credential paths
         */
        List<Path> paths() { return List.of(certificateAuthority, serverCertificate, serverKey, workerCertificate, workerKey); }
    }
}
