package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Composes the shared native backend with Linux trusted-file ownership and original daemon collection.
 * The supervisor retains its infrastructure containment until this owner and its Runner leases close.
 */
public final class LinuxBazelBackend implements AutoCloseable
{
    /** Linux invocation directories exclude every other identity. */
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
        Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    /** Linux credentials and configurations exclude every other identity. */
    private static final Set<PosixFilePermission> PRIVATE_FILE =
        Set.copyOf(PosixFilePermissions.fromString("rw-------"));
    /** Shared configuration, authenticated readiness and daemon lifecycle owner. */
    private final BazelNativeBackend backend;
    /** Original processes retained for infrastructure accounting. */
    private final List<Process> processes;

    /**
     * Retains a shared backend and its original Linux processes, including partially acquired owners.
     * @param backend shared backend whose cleanup remains owned
     * @param processes original acquired daemons in launch order
     */
    private LinuxBazelBackend(BazelNativeBackend backend, List<Process> processes)
    {
        this.backend = backend;
        this.processes = processes;
    }

    /**
     * Validates and stages one prebuilt backend for the optional distribution assembler.
     * @param args exactly source backend directory and absent destination directory
     * @throws IOException if copying or identity validation fails
     * @throws IllegalArgumentException if argument count is invalid
     * @throws UnsupportedOperationException if staging is attempted outside Linux x64 or ARM64
     */
    public static void main(String[] args) throws IOException
    {
        if (args.length != 2) throw new IllegalArgumentException("Expected native backend source and new staging directory");
        NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch"));
        BazelNativeBackend.main(args);
    }

    /**
     * Starts the shared native backend with Linux UID/GID authority.
     * Only the actual authenticated Runner callback may complete workerReadiness after native-path validation.
     * The supervisor and acquired daemons must already belong to caller-owned infrastructure containment.
     * @param scope borrowed Pouch services retained through closure
     * @param configuration Linux invocation paths, identities and cache policy
     * @param cleanup withdraws admissions and collects native Runner leases
     * @param workerReadiness actual authenticated Runner readiness acknowledgement
     * @return retained backend after genuine worker readiness
     * @throws IOException if native validation, acquisition, readiness or cleanup fails
     * @throws UnsupportedOperationException if the current host is not Linux x64 or ARM64
     */
    static LinuxBazelBackend start(ToolingScope scope, Configuration configuration, Cleanup cleanup,
        CompletableFuture<Void> workerReadiness) throws IOException
    {
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(workerReadiness, "workerReadiness");
        BazelNativeBackend.NativeTarget target = linuxTarget(System.getProperty("os.name"), System.getProperty("os.arch"));
        BazelNativeBackend.ValidatedDistribution distribution =
            BazelNativeBackend.validateDistribution(scope, target, configuration.distribution());
        LinuxOwner owner = new LinuxOwner(configuration);
        BazelNativeBackend.Configuration shared = new BazelNativeBackend.Configuration(distribution,
            configuration.invocationRoot(), configuration.runnerAddress(), configuration.credentials().shared(),
            configuration.concurrency(), configuration.casBytes(), configuration.actionCacheBytes(),
            BazelNativeBackend.CachePolicy.valueOf(configuration.cachePolicy().name()), workerReadiness, cleanup::close);
        try
        {
            return new LinuxBazelBackend(BazelNativeBackend.start(scope, shared, owner), owner.processes);
        }
        catch (BazelNativeBackend.AcquisitionFailure failure)
        {
            throw new AcquisitionFailure(new LinuxBazelBackend(failure.backend(), owner.processes), failure);
        }
    }

    /**
     * Selects exact native Linux products through the shared manifest and no-follow identity validator.
     * @param scope borrowed mapper services
     * @param distribution original installed backend directory
     * @param operatingSystem explicit host operating system
     * @param architecture explicit host processor
     * @return immutable exact native executable paths
     * @throws IOException if product identity, pins, membership or native format differs
     * @throws UnsupportedOperationException if the host is not Linux x64 or ARM64
     */
    public static Map<String, Path> validateDistribution(ToolingScope scope, Path distribution,
        String operatingSystem, String architecture) throws IOException
    {
        return BazelNativeBackend.validateDistribution(scope, linuxTarget(operatingSystem, architecture), distribution)
            .executables();
    }

    /**
     * Selects a supported Linux target without accepting another platform's backend.
     * @param operatingSystem host operating system
     * @param architecture host processor
     * @return supported native Linux target
     * @throws UnsupportedOperationException if the host is not Linux x64 or ARM64
     */
    private static BazelNativeBackend.NativeTarget linuxTarget(String operatingSystem, String architecture)
    {
        BazelNativeBackend.NativeTarget target = BazelNativeBackend.NativeTarget.forHost(operatingSystem, architecture);
        if (!target.isLinux()) throw new UnsupportedOperationException("Linux backend requires Linux: " + operatingSystem);
        return target;
    }

    /**
     * Returns the invocation-private authenticated execution frontend.
     * @return grpcs execution address
     */
    String executorAddress() { return backend.executorAddress(); }

    /**
     * Returns the invocation-private authenticated CAS frontend.
     * @return grpcs cache address
     */
    String cacheAddress() { return backend.cacheAddress(); }

    /**
     * Returns mandatory private Bazel startup policy.
     * @return original startup rc path
     */
    Path startupRc() { return backend.startupRc(); }

    /**
     * Returns the original worker root validated by the authenticated Runner.
     * @return private worker materialization root
     */
    Path workerBuildRoot() { return backend.workerBuildRoot(); }

    /**
     * Returns original native process handles for separate infrastructure accounting.
     * @return immutable acquired daemon handles
     */
    List<ProcessHandle> processes() { return processes.stream().map(Process::toHandle).toList(); }

    /**
     * Rejects an original backend daemon's unexpected exit.
     * @throws IOException if an original daemon exited
     */
    void requireAlive() throws IOException { backend.requireAlive(); }

    @Override public void close() throws IOException { backend.close(); }

    /**
     * Retains Linux cleanup access when shared native acquisition cannot establish quiescence.
     */
    @SuppressWarnings("serial")
    static final class AcquisitionFailure extends IOException
    {
        /** Exception format identity; live owners are never serialized. */
        private static final long serialVersionUID = 1L;
        /** Exact retained compatibility owner. */
        private final LinuxBazelBackend backend;

        /**
         * Preserves the original acquisition failure and its retained native cleanup owner.
         * @param backend original Linux owner requiring another close attempt
         * @param failure shared acquisition and suppressed cleanup failures
         */
        private AcquisitionFailure(LinuxBazelBackend backend, BazelNativeBackend.AcquisitionFailure failure)
        {
            super(failure.getMessage(), failure);
            this.backend = backend;
        }

        /**
         * Returns the original owner for explicit cleanup retry.
         * @return retained native backend owner
         */
        LinuxBazelBackend backend() { return backend; }
    }

    /**
     * Supplies Linux trusted UID/GID protection and original daemon process owners.
     */
    private static final class LinuxOwner implements BazelNativeBackend.PlatformOwner
    {
        /** Bounded Linux file ownership authority. */
        private final LinuxProtection protection;
        /** Native worker materialization identity. */
        private final BazelNativeBackend.PosixWorkerIdentity identity;
        /** Original daemon process handles in acquisition order. */
        private final List<Process> processes = new ArrayList<>();

        /**
         * Authenticates the existing private root before any native acquisition.
         * @param configuration Linux trusted invocation and identity
         * @throws IOException if the root lacks the required authority
         */
        private LinuxOwner(Configuration configuration) throws IOException
        {
            this.protection = new LinuxProtection(configuration.invocationRoot(), configuration.trustedUid(),
                configuration.trustedGid());
            this.identity = new BazelNativeBackend.PosixWorkerIdentity(configuration.trustedUid(), configuration.trustedGid());
        }

        @Override public BazelBackendCredentials.Protection protection() { return protection; }
        @Override public BazelNativeBackend.WorkerIdentity workerIdentity() { return identity; }

        @Override public BazelNativeBackend.Daemon launch(Path executable, Path configuration, Path log,
            Path workingDirectory, Map<String, String> environment) throws IOException
        {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), configuration.toString());
            builder.directory(workingDirectory.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            Process process = builder.start();
            processes.add(process);
            return new LinuxDaemon(process);
        }
    }

    /**
     * Retains and collects the original Linux daemon process, preserving interruption across cleanup.
     * The caller retains the separately owned infrastructure containment until all daemon owners close.
     */
    private static final class LinuxDaemon implements BazelNativeBackend.Daemon
    {
        /** Original acquired daemon, never replaced by a PID lookup. */
        private final Process process;

        /**
         * Transfers original process collection ownership.
         * @param process original acquired native process
         */
        private LinuxDaemon(Process process) { this.process = process; }

        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public long exitValue() { return process.exitValue(); }
        @Override public CompletableFuture<Long> onExit()
        {
            return process.onExit().thenApply(original -> (long) original.exitValue());
        }

        @Override public synchronized void close()
        {
            boolean interrupted = Thread.interrupted();
            try
            {
                process.destroy();
                while (true)
                {
                    try { process.waitFor(); return; }
                    catch (InterruptedException failure) { interrupted = true; }
                }
            }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Protects private Linux objects owned by the trusted worker UID/GID rather than the supervisor UID.
     * Every admitted path retains its original no-follow file key until backend cleanup finishes.
     */
    private static final class LinuxProtection implements BazelBackendCredentials.Protection
    {
        /** Private invocation boundary. */
        private final Path root;
        /** Trusted original consumer and worker-file UID. */
        private final int userId;
        /** Trusted original consumer and worker-file GID. */
        private final int groupId;
        /** Original no-follow keys of admitted directories and files. */
        private final Map<Path, Object> identities = new HashMap<>();

        /**
         * Retains and authenticates the original private Linux root.
         * @param root invocation-private original directory
         * @param userId trusted file UID
         * @param groupId trusted file GID
         * @throws IOException if native root authority is unsafe
         */
        private LinuxProtection(Path root, int userId, int groupId) throws IOException
        {
            this.root = root;
            this.userId = userId;
            this.groupId = groupId;
            requireDirectory(root);
        }

        @Override public synchronized void createDirectory(Path path) throws IOException
        {
            Path target = bounded(path);
            requireDirectory(target.getParent());
            Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY));
            assignOwnership(target);
            requireDirectory(target);
        }

        @Override public synchronized void createFile(Path path) throws IOException
        {
            Path target = bounded(path);
            requireDirectory(target.getParent());
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
            assignOwnership(target);
            requireFile(target);
        }

        @Override public synchronized void protectFile(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, false, false);
            Files.setPosixFilePermissions(target, PRIVATE_FILE);
            validate(target, false, true);
        }

        @Override public synchronized void requireDirectory(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, true, true);
        }

        @Override public synchronized void requireFile(Path path) throws IOException
        {
            Path target = bounded(path);
            ancestors(target);
            validate(target, false, true);
        }

        /**
         * Resolves a path inside the private invocation without permitting escape.
         * @param path original private file or directory
         * @return normalized path bounded by the invocation root
         * @throws IOException if the path escapes the private root
         */
        private Path bounded(Path path) throws IOException
        {
            Path target = path.toAbsolutePath().normalize();
            if (!target.startsWith(root)) throw new IOException("Linux private path escapes invocation root: " + target);
            return target;
        }

        /**
         * Authenticates every retained parent before accessing its child.
         * @param path bounded original object
         * @throws IOException if an ancestor's identity or authority differs
         */
        private void ancestors(Path path) throws IOException
        {
            validate(root, true, true);
            if (path.equals(root)) return;
            Path current = root;
            for (Path component : root.relativize(path.getParent()))
            {
                if (component.toString().isEmpty()) continue;
                current = current.resolve(component);
                validate(current, true, true);
            }
        }

        /**
         * Assigns a newly created object to the invocation's trusted worker identity.
         * @param path newly created private object
         * @throws IOException if UID or GID assignment fails
         */
        private void assignOwnership(Path path) throws IOException
        {
            Files.setAttribute(path, "unix:uid", userId, LinkOption.NOFOLLOW_LINKS);
            Files.setAttribute(path, "unix:gid", groupId, LinkOption.NOFOLLOW_LINKS);
        }

        /**
         * Authenticates type, original no-follow identity, Linux owner and required private permissions.
         * @param path original object
         * @param directory indicates whether a directory is required
         * @param permissions indicates whether exact owner-only permissions are required
         * @throws IOException if the object or any required authority property differs
         */
        private void validate(Path path, boolean directory, boolean permissions) throws IOException
        {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean expectedType = directory ? attributes.isDirectory() : attributes.isRegularFile();
            if (!expectedType || attributes.fileKey() == null)
                throw new IOException("Linux private object has wrong native type or no stable identity: " + path);
            Object previous = identities.putIfAbsent(path, attributes.fileKey());
            if (previous != null && !previous.equals(attributes.fileKey()))
                throw new IOException("Linux private object was replaced: " + path);
            int actualUid = (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            int actualGid = (Integer) Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            if (actualUid != userId || actualGid != groupId)
                throw new IOException("Linux private object UID/GID differs from trusted worker identity: " + path);
            if (permissions)
            {
                Set<PosixFilePermission> expected = directory ? PRIVATE_DIRECTORY : PRIVATE_FILE;
                if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected))
                    throw new IOException("Linux private object permissions differ from owner-only mode: " + path);
            }
        }
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
     * Preserves Linux native-target callers while the shared backend owns all target mappings.
     */
    public enum NativeTarget
    {
        /** Native 64-bit ARM Linux distribution. */
        ARM64(BazelNativeBackend.NativeTarget.ARM64),
        /** Native 64-bit x86 Linux distribution. */
        X64(BazelNativeBackend.NativeTarget.X64);

        /** Shared authority for native build-tool and release target values. */
        private final BazelNativeBackend.NativeTarget target;

        /**
         * Retains the common target without copying its platform mapping.
         * @param target authoritative shared Linux target
         */
        NativeTarget(BazelNativeBackend.NativeTarget target) { this.target = target; }

        /**
         * Selects the supported Linux target from the shared native-host mapping.
         * @param operatingSystem actual native host operating system
         * @param architecture actual native host processor
         * @return matching Linux x64 or ARM64 target
         * @throws NullPointerException if an argument is null
         * @throws UnsupportedOperationException if the host is not supported Linux x64 or ARM64
         */
        public static NativeTarget forHost(String operatingSystem, String architecture)
        {
            return switch (linuxTarget(operatingSystem, architecture))
            {
                case ARM64 -> ARM64;
                case X64 -> X64;
                default -> throw new AssertionError("Linux target selection returned a foreign target");
            };
        }

        /**
         * Returns the release target required by the shared native manifest.
         * @return Linux native release target triple
         */
        public String triple() { return target.triple(); }

        /**
         * Returns the processor identifier used by official Go archives and GOARCH.
         * @return authoritative native Go processor identifier
         */
        public String goArchitecture() { return target.goArchitecture(); }

        /**
         * Returns the processor identifier used by official Bazel release filenames.
         * @return authoritative native Bazel processor identifier
         */
        public String bazelArchitecture() { return target.bazelArchitecture(); }

        /**
         * Returns native system headers searched after musl headers by the Linux builder.
         * @return authoritative absolute native system-header directory
         */
        public Path systemHeaders() { return target.systemHeaders(); }
    }

    /**
     * Selects action-cache acceptance separately from the original Bazel consumer argv.
     */
    public enum CachePolicy
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
    public record Configuration(Path distribution, Path invocationRoot, String runnerAddress, Credentials credentials,
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
        public Configuration(Path distribution, Path invocationRoot, String runnerAddress, Credentials credentials,
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
        public Configuration
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
    public record Credentials(Path certificateAuthority, Path serverCertificate, Path serverKey,
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
        public Credentials
        {
            certificateAuthority = certificateAuthority.toAbsolutePath().normalize();
            serverCertificate = serverCertificate.toAbsolutePath().normalize();
            serverKey = serverKey.toAbsolutePath().normalize();
            workerCertificate = workerCertificate.toAbsolutePath().normalize();
            workerKey = workerKey.toAbsolutePath().normalize();
        }

        /**
         * Issues invocation-private TLS identities using the shared credential authority.
         * The parent must belong to the creating supervisor until issuance finishes.
         * @param credentialRoot new directory beneath a private supervisor-owned root
         * @param keytool trusted JDK certificate tool
         * @return issued credentials retained until every native consumer closes
         * @throws IOException if credential issuance or parent protection fails
         */
        static Credentials generate(Path credentialRoot, Path keytool) throws IOException
        {
            BazelBackendCredentials issued = BazelBackendCredentials.generate(credentialRoot, keytool,
                BazelBackendCredentials.nativeProtection(credentialRoot.toAbsolutePath().normalize().getParent()));
            return new Credentials(issued.certificateAuthority(), issued.serverCertificate(), issued.serverKey(),
                issued.workerCertificate(), issued.workerKey());
        }

        /**
         * Returns every original credential file for caller-owned identity assignment.
         * @return immutable credential paths
         */
        List<Path> paths() { return shared().paths(); }

        /**
         * Adapts the retained Linux caller representation to the shared credential contract.
         * @return same original credential paths
         */
        private BazelBackendCredentials shared()
        {
            return new BazelBackendCredentials(certificateAuthority, serverCertificate, serverKey,
                workerCertificate, workerKey);
        }
    }
}
