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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates exact native Buildbarn products and owns one invocation's authenticated backend daemons.
 * Native filesystem protection and genuine process collection belong to the supplied platform owner.
 * Backend availability does not establish native Bazel action scheduling or action memory isolation.
 */
public final class BazelNativeBackend implements AutoCloseable
{
    /** Exact Buildbarn execution source revision. */
    public static final String EXECUTION_COMMIT = "1fbffdc417797f6afdb7f67b1f0d5cc27352db43";
    /** Exact pinned Buildbarn storage module revision prefix. */
    public static final String STORAGE_COMMIT = "025ab8081c7f";
    /** Declared upstream Go toolchain. */
    public static final String GO_VERSION = "go1.27.3";
    /** Declared upstream storage Bazel toolchain. */
    public static final String STORAGE_BAZEL_VERSION = "9.1.0";
    /** Sole native product manifest name. */
    public static final String MANIFEST = "backend-manifest.json";
    /** Shared native daemon product names, independent of host executable suffix. */
    private static final Set<String> DAEMON_NAMES = Set.of("bb_storage", "bb_scheduler", "bb_worker");
    /** Legacy Linux native helper products retained by the existing producer. */
    private static final Set<String> LINUX_NAMES = Set.of("bb_storage", "bb_scheduler", "bb_worker",
        "linux-bazel-action-owner", "linux-bazel-action-waiter", "codex-tooling", "codex-cargo-wrapper-linux");
    /** Class-relative maintained configuration resource root. */
    private static final String RESOURCE_ROOT = "buildbarn/";
    /** Original native daemons in acquisition order, retained through uncertain cleanup. */
    private final List<Daemon> daemons = new ArrayList<>();
    /** Immutable invocation configuration. */
    private final Configuration configuration;
    /** Platform file authority and native process factory retained through cleanup. */
    private final PlatformOwner platformOwner;
    /** Private authenticated CAS/execution endpoint. */
    private final String address;
    /** Startup policy staged beneath the private invocation. */
    private final Path startupRc;
    /** Whether all original daemon owners have completed terminal collection. */
    private boolean closed;

    /**
     * Retains ownership before native acquisition.
     * @param configuration validated invocation settings
     * @param platformOwner native file and process authority
     * @param address private authenticated frontend address
     * @param startupRc private mandatory Bazel policy file
     */
    private BazelNativeBackend(Configuration configuration, PlatformOwner platformOwner, String address, Path startupRc)
    {
        this.configuration = configuration;
        this.platformOwner = platformOwner;
        this.address = address;
        this.startupRc = startupRc;
    }

    /**
     * Stages a prebuilt backend for the actual host without invoking a compiler or native product.
     * @param args exactly the source product directory and an absent staging destination
     * @throws IOException if original product validation, copying, staged validation or retained cleanup fails
     * @throws IllegalArgumentException if exactly two paths are not supplied
     * @throws UnsupportedOperationException if the actual host is not a supported native target
     */
    public static void main(String[] args) throws IOException
    {
        if (args.length != 2) throw new IllegalArgumentException("Expected native backend source and new staging directory");
        try (com.holdmyspot.codexunleashed.tooling.DefaultToolingScope scope =
            new com.holdmyspot.codexunleashed.tooling.DefaultToolingScope())
        {
            NativeTarget target = NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch"));
            stage(scope, validateDistribution(scope, target, Path.of(args[0])), Path.of(args[1]));
        }
    }

    /**
     * Copies validated original products and direct regular license files into an absent owned directory.
     * Retained original identities and bytes are checked again after copying, and the staged manifest is validated
     * by the same native product authority. A failed stage deletes only still-identical objects created by this
     * call. Uncertain cleanup remains attached to the original failure and identifies the retained destination.
     * Explicit target fixtures may exercise file formats portably; this method never executes a product.
     * @param scope borrowed caller-owned Pouch mapper scope
     * @param source validated original products with their native no-follow identity authority
     * @param destination absent staging directory beneath an existing direct parent
     * @return validated staged products retaining their new native identities
     * @throws IOException if original files changed, paths are linked or reparsed, copying fails or staged validation fails
     */
    public static ValidatedDistribution stage(ToolingScope scope, ValidatedDistribution source, Path destination)
        throws IOException
    {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        source.revalidate(scope);
        FileIdentity authority = source.authority();
        Path target = destination.toAbsolutePath().normalize();
        if (target.getParent() == null) throw new IOException("Staging destination has no existing parent: " + target);
        Map<Path, Object> parentIdentities = new LinkedHashMap<>();
        captureDirectoryChain(target.getParent(), parentIdentities, authority);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Staging destination must be absent: " + target);
        Map<Path, Object> originals = new LinkedHashMap<>(source.identities());
        Map<Path, String> copies = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : source.executables().entrySet())
            copies.put(entry.getValue(), source.hashes().get(entry.getKey()));
        copies.put(source.root().resolve(MANIFEST), source.manifestHash());
        Path licenses = source.root().resolve("licenses");
        boolean hasLicenses = Files.exists(licenses, LinkOption.NOFOLLOW_LINKS);
        if (hasLicenses)
        {
            captureDirectoryChain(licenses, originals, authority);
            try (java.util.stream.Stream<Path> files = Files.list(licenses))
            {
                for (Path file : files.toList())
                {
                    requireRegularFile(file);
                    originals.put(file, authority.identity(file));
                    copies.put(file, sha256(file));
                }
            }
        }
        Map<Path, Object> created = new LinkedHashMap<>();
        boolean acquired = false;
        try
        {
            revalidateStagingObjects(parentIdentities, authority);
            Files.createDirectory(target);
            acquired = true;
            created.put(target, authority.identity(target));
            if (hasLicenses)
            {
                Path stagedLicenses = target.resolve("licenses");
                Files.createDirectory(stagedLicenses);
                created.put(stagedLicenses, authority.identity(stagedLicenses));
            }
            for (Map.Entry<Path, String> entry : copies.entrySet())
            {
                revalidateStagingObjects(parentIdentities, authority);
                revalidateStagingObjects(created, authority);
                Path original = entry.getKey();
                if (!Objects.equals(originals.get(original), authority.identity(original)))
                    throw new IOException("Original native staging file was substituted: " + original);
                Path output = target.resolve(source.root().relativize(original));
                copyStagingFile(original, output, entry.getValue(), source.executables().containsValue(original),
                    authority, created);
            }
            source.revalidate(scope);
            revalidateStagingObjects(originals, authority);
            for (Map.Entry<Path, String> entry : copies.entrySet())
                if (!entry.getValue().equals(sha256(entry.getKey())))
                    throw new IOException("Original native staging bytes changed: " + entry.getKey());
            revalidateStagingObjects(parentIdentities, authority);
            revalidateStagingObjects(created, authority);
            return validateDistribution(scope, source.target(), target, authority);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            if (acquired)
            {
                try
                {
                    revalidateStagingObjects(parentIdentities, authority);
                    for (Map.Entry<Path, Object> entry : new ArrayList<>(created.entrySet()).reversed())
                    {
                        revalidateStagingObjects(created, authority);
                        if (!Objects.equals(entry.getValue(), authority.identity(entry.getKey())))
                            throw new IOException("Created staging object was substituted: " + entry.getKey());
                        Files.delete(entry.getKey());
                        created.remove(entry.getKey());
                    }
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Uncollected staging objects remain: " + target);
                }
                catch (IOException | RuntimeException | Error cleanup)
                {
                    failure.addSuppressed(new IOException("Retain uncertain native staging destination: " + target, cleanup));
                }
            }
            throw failure;
        }
    }

    /**
     * Revalidates retained direct native filesystem objects without following aliases.
     * @param identities original native object identities
     * @param authority original no-follow native identity reader
     * @throws IOException if an object changed or cannot be authenticated
     */
    private static void revalidateStagingObjects(Map<Path, Object> identities, FileIdentity authority) throws IOException
    {
        for (Map.Entry<Path, Object> entry : identities.entrySet())
            if (!Objects.equals(entry.getValue(), authority.identity(entry.getKey())))
                throw new IOException("Native staging object changed: " + entry.getKey());
    }

    /**
     * Creates one owned direct file, retains its identity before writing, and verifies copied bytes.
     * POSIX executable metadata is applied only when the actual destination filesystem exposes a POSIX view;
     * Windows preserves its inherited native ACL and native executable filename without POSIX operations.
     * @param original direct original product or license
     * @param output absent owned destination file
     * @param expectedHash validated original content digest
     * @param executable whether this file is a validated native executable
     * @param authority existing no-follow native identity authority
     * @param created retained objects created by this staging call
     * @throws IOException if exclusive creation, copying, metadata or content validation fails
     */
    private static void copyStagingFile(Path original, Path output, String expectedHash, boolean executable,
        FileIdentity authority, Map<Path, Object> created) throws IOException
    {
        try (InputStream input = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS);
            SeekableByteChannel channel = Files.newByteChannel(output,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))
        {
            created.put(output, authority.identity(output));
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) >= 0)
            {
                ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, count);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
        if (executable && Files.getFileAttributeView(output, java.nio.file.attribute.PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS) != null)
            Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        if (!Objects.equals(created.get(output), authority.identity(output)) || !expectedHash.equals(sha256(output)))
            throw new IOException("Copied native staging file differs from its original: " + output);
    }

    /**
     * Validates a pinned distribution without starting a native process.
     * Explicit targets make format rejection portable; start separately requires the actual host to match.
     * Paths and original no-follow identities remain pinned for revalidation before acquisition.
     * @param scope borrowed caller-owned Pouch mapper scope
     * @param target exact expected native release target
     * @param distribution trusted installed backend directory
     * @return immutable validated original product identities
     * @throws IOException if membership, source pins, paths, hashes or executable architecture differ
     */
    public static ValidatedDistribution validateDistribution(ToolingScope scope, NativeTarget target, Path distribution)
        throws IOException
    {
        return validateDistribution(scope, target, distribution, BazelBackendCredentials.nativeIdentity(scope));
    }

    /**
     * Validates products using the caller's existing native no-follow file authority.
     * Windows callers supply handle-backed identities because its JDK provider exposes no file keys.
     * @param scope borrowed caller-owned mapper scope
     * @param target exact native release target
     * @param distribution trusted installed backend directory
     * @param authority native identity authority rejecting every linked or reparse object
     * @return immutable validated original product identities
     * @throws IOException if products, native authority or determining identities differ
     */
    public static ValidatedDistribution validateDistribution(ToolingScope scope, NativeTarget target, Path distribution,
        FileIdentity authority) throws IOException
    {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(authority, "authority");
        Path root = distribution.toAbsolutePath().normalize();
        Map<Path, Object> identities = new LinkedHashMap<>();
        captureDirectoryChain(root, identities, authority);
        Path manifest = root.resolve(MANIFEST);
        requireRegularFile(manifest);
        String manifestDigest = sha256(manifest);
        JsonNode document = scope.getJsonMapper().readTree(Files.readAllBytes(manifest));
        if (document.path("schema").asInt() != 1 || !target.triple().equals(document.path("target").asString()) ||
            !EXECUTION_COMMIT.equals(document.path("executionCommit").asString()) ||
            !STORAGE_COMMIT.equals(document.path("storageCommit").asString()))
            throw new IOException("Native backend manifest target, schema or source pins differ: " + manifest);
        JsonNode entries = document.path("executables");
        Set<String> expected = target.isLinux() ? LINUX_NAMES : DAEMON_NAMES;
        Set<String> names = new java.util.HashSet<>();
        entries.propertyNames().forEach(names::add);
        if (!names.equals(expected)) throw new IOException("Native backend executable membership differs: " + names);
        Map<String, Path> selected = new LinkedHashMap<>();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : expected)
        {
            String filename = name + target.suffix();
            if (!filename.equals(entries.path(name).path("path").asString()))
                throw new IOException("Native executable path must be its exact target filename: " + name);
            Path executable = root.resolve(filename);
            requireRegularFile(executable);
            if (!Files.isExecutable(executable)) throw new IOException("Native product is not executable: " + executable);
            String digest = sha256(executable);
            if (!digest.equals(entries.path(name).path("sha256").asString()))
                throw new IOException("Native backend executable SHA-256 differs: " + executable);
            validateMachine(executable, target);
            identities.put(executable, authority.identity(executable));
            selected.put(name, executable);
            hashes.put(name, digest);
        }
        identities.put(manifest, authority.identity(manifest));
        return new ValidatedDistribution(target, root, selected, hashes, manifestDigest, identities, authority);
    }

    /**
     * Requires direct ancestors and captures stable provider identities without following symbolic links.
     * The platform owner additionally rejects native aliases such as Windows reparse points at acquisition.
     * @param directory original trusted directory
     * @param identities original identity registry
     * @param authority existing native no-follow identity authority
     * @throws IOException if an ancestor is linked, unavailable or lacks a stable identity
     */
    private static void captureDirectoryChain(Path directory, Map<Path, Object> identities, FileIdentity authority)
        throws IOException
    {
        Path current = directory.getRoot();
        for (Path component : directory)
        {
            current = current.resolve(component);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current))
                throw new IOException("Native backend ancestry is not a direct directory: " + current);
            identities.put(current, authority.identity(current));
        }
    }

    /**
     * Checks the narrow architecture fields of a hashed native executable, using official ELF/Mach-O/PE layouts.
     * This check does not parse action inputs or claim that a synthetic header is a runnable native product.
     * @param executable exact hashed product file
     * @param target expected native target
     * @throws IOException if the native header is truncated, malformed or targets another architecture
     */
    private static void validateMachine(Path executable, NativeTarget target) throws IOException
    {
        try (SeekableByteChannel channel = Files.newByteChannel(executable, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining() && channel.read(header) >= 0) {}
            if (header.position() != 64) throw new IOException("Native executable header is truncated: " + executable);
            byte[] bytes = header.array();
            boolean matches;
            if (target.isLinux())
                matches = bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F' &&
                    bytes[4] == 2 && bytes[5] == 1 && Short.toUnsignedInt(header.getShort(18)) == target.machine;
            else if (target.goOperatingSystem().equals("darwin"))
                matches = header.getInt(0) == 0xfeedfacf && header.getInt(4) == target.machine && header.getInt(12) == 2;
            else
            {
                long offset = Integer.toUnsignedLong(header.getInt(60));
                if (bytes[0] != 'M' || bytes[1] != 'Z' || offset < 64 || offset > channel.size() - 26)
                    throw new IOException("Native PE header offset is invalid: " + executable);
                channel.position(offset);
                ByteBuffer coff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
                while (coff.hasRemaining() && channel.read(coff) >= 0) {}
                matches = coff.position() == 26 && coff.getInt(0) == 0x00004550 &&
                    Short.toUnsignedInt(coff.getShort(4)) == target.machine &&
                    (Short.toUnsignedInt(coff.getShort(22)) & 0x0002) != 0 &&
                    Short.toUnsignedInt(coff.getShort(24)) == 0x020b;
            }
            if (!matches) throw new IOException("Native backend executable architecture differs from " + target.triple() + ": " + executable);
        }
    }

    /**
     * Requires a no-follow regular product file.
     * @param path trusted original product file
     * @throws IOException if the input is absent, linked or nonregular
     */
    private static void requireRegularFile(Path path) throws IOException
    {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Native backend file is absent, linked or nonregular: " + path);
    }

    /**
     * Hashes exact original file bytes using the JDK SHA-256 implementation.
     * @param path no-follow original file
     * @return lowercase SHA-256
     * @throws IOException if the original bytes cannot be read
     */
    private static String sha256(Path path) throws IOException
    {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new AssertionError("JDK lacks SHA-256", failure); }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Retains validated original executable and ancestry identities without acquiring a process.
     * @param target exact native target
     * @param root original trusted distribution directory
     * @param executables original product paths indexed by fixed logical name
     * @param hashes original exact product hashes
     * @param manifestHash original manifest hash
     * @param identities original no-follow ancestry and file keys
     * @param authority retained native no-follow identity authority
     */
    public record ValidatedDistribution(NativeTarget target, Path root, Map<String, Path> executables,
        Map<String, String> hashes, String manifestHash, Map<Path, Object> identities, FileIdentity authority)
    {
        /**
         * Freezes validated source identities for later acquisition checks.
         * @param target original native target
         * @param root original distribution directory
         * @param executables fixed native product paths
         * @param hashes exact product byte identities
         * @param manifestHash exact original manifest identity
         * @param identities no-follow native file identities
         * @param authority original native no-follow authority
         */
        public ValidatedDistribution
        {
            Objects.requireNonNull(target, "target");
            root = root.toAbsolutePath().normalize();
            executables = Map.copyOf(executables);
            hashes = Map.copyOf(hashes);
            Objects.requireNonNull(manifestHash, "manifestHash");
            identities = Map.copyOf(identities);
            Objects.requireNonNull(authority, "authority");
        }

        /**
         * Revalidates every determining byte and native identity before first acquisition.
         * @param scope borrowed mapper scope
         * @throws IOException if any original product or directory object changed
         */
        public void revalidate(ToolingScope scope) throws IOException
        {
            if (!equals(validateDistribution(scope, target, root, authority)))
                throw new IOException("Original validated native backend distribution was substituted or changed");
        }
    }

    /**
     * Selects ordinary action-cache acceptance independently of the untouched Bazel argument vector.
     */
    public enum CachePolicy
    {
        /** Accept matching ordinary remote action-cache results. */
        ACCEPT_CACHED,
        /** Require real action execution for a selected recovery build. */
        UNCACHED_RECOVERY
    }

    /**
     * Requires the caller to retire native Runner cleanup leases before backend shutdown.
     */
    @FunctionalInterface
    public interface Cleanup
    {
        /**
         * Withdraws admissions and establishes native cleanup completion.
         * @throws IOException if original cleanup ownership remains unresolved
         */
        void close() throws IOException;
    }

    /**
     * Native worker materialization identity interpreted by the pinned worker configuration.
     */
    public sealed interface WorkerIdentity permits PosixWorkerIdentity, WindowsWorkerIdentity {}

    /**
     * Explicit POSIX owner of worker-materialized files, independent of action ownership.
     * @param userId trusted worker-file user ID
     * @param groupId trusted worker-file group ID
     */
    public record PosixWorkerIdentity(int userId, int groupId) implements WorkerIdentity
    {
        /**
         * Requires actual nonnegative native IDs supplied by the platform profile.
         * @param userId trusted worker-file user ID
         * @param groupId trusted worker-file group ID
         */
        public PosixWorkerIdentity
        {
            if (userId < 0 || groupId < 0) throw new IllegalArgumentException("Worker POSIX IDs must be nonnegative");
        }
    }

    /**
     * Windows worker ownership is enforced by native token/ACL authority, without POSIX UID fields.
     */
    public record WindowsWorkerIdentity() implements WorkerIdentity {}

    /**
     * Reads original native identities without accepting symbolic links, aliases or reparse points.
     * Implementations must return immutable equality-comparable volume/file identities and fail closed
     * when the original object cannot be authenticated. Authority and native services remain caller-owned.
     */
    @FunctionalInterface
    public interface FileIdentity
    {
        /**
         * Reads one existing original object's native identity without following indirection.
         * @param path original file or directory, including installed distribution ancestors
         * @return non-null immutable native identity
         * @throws IOException if identity cannot be authenticated or the object is indirect
         */
        Object identity(Path path) throws IOException;
    }

    /**
     * Supplies one native file authority and authentic daemon acquisition strategy.
     * Implementations retain scope, containment and native handles until every daemon closes.
     * Credential protection is the single shared credential component's contract.
     */
    public interface PlatformOwner
    {
        /**
         * Returns required protection rooted in the original private invocation directory.
         * @return required native protection authority
         */
        BazelBackendCredentials.Protection protection();

        /**
         * Returns the native worker materialization identity.
         * @return POSIX IDs or Windows native ownership variant
         */
        WorkerIdentity workerIdentity();

        /**
         * Starts an original native daemon and retains all partial acquisition ownership.
         * Failure before return must collect the acquisition or throw a retained native-owner failure.
         * @param executable authenticated native product
         * @param configuration protected daemon configuration
         * @param log protected native diagnostics file
         * @param workingDirectory trusted invocation directory
         * @param environment required invocation-local variables, layered only on required host system variables
         * @return retained original native daemon owner
         * @throws IOException if native acquisition or ownership fails
         */
        Daemon launch(Path executable, Path configuration, Path log, Path workingDirectory,
            Map<String, String> environment) throws IOException;
    }

    /**
     * Original daemon process ownership, independent of action retry and memory domains.
     */
    public interface Daemon extends AutoCloseable
    {
        /**
         * Returns whether the original daemon remains live.
         * @return whether the original daemon is live
         */
        boolean isAlive();

        /**
         * Returns its genuine original exit status after terminal observation.
         * @return authentic original native status
         * @throws IOException if the original status is unavailable
         */
        long exitValue() throws IOException;

        /**
         * Returns original terminal observation used to wake readiness waits.
         * Completion cannot substitute for close's required native collection barrier.
         * @return future completed by authentic original native termination
         */
        CompletableFuture<Long> onExit();

        /**
         * Terminates if needed and collects original native ownership and required descendants.
         * A failed close retains authority for explicit retry and does not claim quiescence.
         * @throws IOException if genuine terminal collection or handle disposal remains unresolved
         */
        @Override void close() throws IOException;
    }

    /**
     * Defines one complete backend invocation before a native process is acquired.
     * Only the authenticated Runner Boundary.checkReadiness callback may complete workerReadiness in production.
     * @param distribution pinned and validated native product identities
     * @param invocationRoot existing protected invocation directory
     * @param runnerAddress listening shared Runner host:port endpoint
     * @param credentials original invocation-private TLS identity material
     * @param concurrency positive worker request capacity
     * @param casBytes explicit CAS capacity, at least 64 MiB
     * @param actionCacheBytes explicit action-cache capacity, at least 64 MiB
     * @param cachePolicy explicit ordinary or uncached recovery policy
     * @param workerReadiness authenticated Runner callback acknowledgement, owned by caller
     * @param cleanup revokes Runner admission and collects native cleanup leases
     */
    public record Configuration(ValidatedDistribution distribution, Path invocationRoot, String runnerAddress,
        BazelBackendCredentials credentials, int concurrency, long casBytes, long actionCacheBytes,
        CachePolicy cachePolicy, CompletableFuture<Void> workerReadiness, Cleanup cleanup)
    {
        /**
         * Validates immutable settings without acquiring native state.
         * @param distribution exact native distribution identities
         * @param invocationRoot existing private invocation directory
         * @param runnerAddress already-listening Runner endpoint
         * @param credentials invocation-private identities
         * @param concurrency positive worker request capacity
         * @param casBytes explicit CAS capacity
         * @param actionCacheBytes explicit action-cache capacity
         * @param cachePolicy explicit action-cache behavior
         * @param workerReadiness authenticated Runner callback acknowledgement
         * @param cleanup required original cleanup barrier
         */
        public Configuration
        {
            Objects.requireNonNull(distribution, "distribution");
            invocationRoot = invocationRoot.toAbsolutePath().normalize();
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            Objects.requireNonNull(workerReadiness, "workerReadiness");
            Objects.requireNonNull(cleanup, "cleanup");
            if (runnerAddress.isBlank() || concurrency <= 0 || casBytes < 67108864 || actionCacheBytes < 67108864)
                throw new IllegalArgumentException("Native backend endpoint, concurrency or cache capacity is invalid");
        }
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
        BazelBackendCredentials credentials = configuration.credentials();
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
        if (platformOwner.workerIdentity() instanceof PosixWorkerIdentity identity)
        {
            values.put("TRUSTED_UID", identity.userId());
            values.put("TRUSTED_GID", identity.groupId());
        }
        return values;
    }

    /**
     * Configures an invocation-owned certificate pair using the pinned daemon's file-key API.
     * The positive refresh duration is required even for certificates whose bytes remain fixed for this invocation.
     * @param certificate exact host-owned PEM certificate path
     * @param privateKey exact host-owned private key path
     * @return typed file-certificate configuration shared by server and client TLS
     */
    public static Map<String, Object> fileKeyPair(Path certificate, Path privateKey)
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
     * Renders maintained remote-only startup policy independently of native daemon acquisition.
     * @param configuration exact invocation credentials, concurrency and explicit action-cache policy
     * @param storagePort private backend storage/frontend port
     * @return complete private Bazel rc, preserving the original consumer argument vector
     * @throws IOException if the packaged policy template cannot be read
     */
    public static String startupRc(Configuration configuration, int storagePort) throws IOException
    {
        String template;
        try (InputStream input = BazelNativeBackend.class.getResourceAsStream(RESOURCE_ROOT + "backend.bazelrc.template"))
        {
            if (input == null) throw new IOException("Packaged backend Bazel policy is absent");
            template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        BazelBackendCredentials credentials = configuration.credentials();
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
     * Starts the complete backend only after all original distribution and credential identities validate.
     * The caller's shared Runner must already listen with its native build root at workerBuildRoot().
     * The authenticated worker readiness callback is required before this method returns.
     * @param scope borrowed caller-owned Pouch scope
     * @param configuration complete invocation configuration
     * @param platformOwner required native protection and daemon ownership boundary
     * @return backend retaining all original daemon and callback ownership
     * @throws IOException if validation, native startup, authenticated readiness or cleanup fails
     * @throws UnsupportedOperationException if the actual host differs from the selected native product
     */
    public static BazelNativeBackend start(ToolingScope scope, Configuration configuration, PlatformOwner platformOwner)
        throws IOException
    {
        Objects.requireNonNull(platformOwner, "platformOwner");
        NativeTarget actual = NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch"));
        if (actual != configuration.distribution().target())
            throw new UnsupportedOperationException("Native backend distribution does not match actual host: " + actual.triple());
        configuration.distribution().revalidate(scope);
        boolean windows = actual.goOperatingSystem().equals("windows");
        if (windows != (platformOwner.workerIdentity() instanceof WindowsWorkerIdentity))
            throw new IOException("Native worker ownership variant differs from the selected operating system");
        BazelBackendCredentials.Protection protection = platformOwner.protection();
        Path root = configuration.invocationRoot();
        protection.requireDirectory(root);
        for (Path credential : configuration.credentials().paths())
        {
            if (!credential.startsWith(root)) throw new IOException("Native backend credential lies outside invocation: " + credential);
            protection.requireFile(credential);
            Path parent = credential.getParent();
            while (parent.startsWith(root))
            {
                protection.requireDirectory(parent);
                if (parent.equals(root)) break;
                parent = parent.getParent();
            }
        }
        protection.requireDirectory(root.resolve("worker-builds"));
        for (String name : List.of("backend", "cas", "action-cache", "worker-cache", "file-pool"))
            protection.createDirectory(root.resolve(name));
        int storagePort = unusedPort();
        int schedulerPort = unusedPort();
        int workerPort = unusedPort();
        while (schedulerPort == storagePort) schedulerPort = unusedPort();
        while (workerPort == storagePort || workerPort == schedulerPort) workerPort = unusedPort();
        Path backendRoot = root.resolve("backend");
        BazelNativeBackend backend = new BazelNativeBackend(configuration, platformOwner,
            "grpcs://localhost:" + storagePort, backendRoot.resolve("backend.bazelrc"));
        try
        {
            Map<String, Object> values = backend.configurationValues(storagePort, schedulerPort, workerPort);
            for (String name : List.of("storage", "scheduler", "worker"))
            {
                String resource = name.equals("worker") && windows ? "worker-windows.json.template" : name + ".json.template";
                backend.writeConfiguration(scope.getJsonMapper(), backendRoot.resolve(name + ".json"), resource, values);
            }
            protection.createFile(backend.startupRc);
            Files.writeString(backend.startupRc, startupRc(configuration, storagePort));
            protection.requireFile(backend.startupRc);
            backend.launch("bb_storage", backendRoot.resolve("storage.json"));
            backend.awaitEndpoint("localhost:" + storagePort);
            backend.launch("bb_scheduler", backendRoot.resolve("scheduler.json"));
            backend.awaitEndpoint("localhost:" + schedulerPort);
            backend.awaitEndpoint("localhost:" + workerPort);
            backend.launch("bb_worker", backendRoot.resolve("worker.json"));
            backend.awaitWorkerReadiness();
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
     * Renders the maintained JSON resource with typed scope-owned serialization and protected output creation.
     * @param mapper borrowed scope mapper
     * @param output new protected native configuration file
     * @param resource maintained configuration template name
     * @param values complete native substitutions
     * @throws IOException if configuration rendering or protection validation fails
     */
    private void writeConfiguration(JsonMapper mapper, Path output, String resource, Map<String, Object> values)
        throws IOException
    {
        String text;
        try (InputStream input = BazelNativeBackend.class.getResourceAsStream(RESOURCE_ROOT + resource))
        {
            if (input == null) throw new IOException("Packaged Buildbarn configuration is absent: " + resource);
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, Object> value : values.entrySet())
            text = text.replace('"' + "@" + value.getKey() + "@" + '"', mapper.writeValueAsString(value.getValue()));
        if (text.contains("\"@")) throw new IOException("Buildbarn configuration contains an unresolved substitution: " + resource);
        JsonNode parsed = mapper.readTree(text);
        platformOwner.protection().createFile(output);
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        platformOwner.protection().requireFile(output);
    }

    /**
     * Acquires one authenticated original daemon before another acquisition can begin.
     * @param name fixed native product name
     * @param config original protected configuration
     * @throws IOException if native acquisition or protected diagnostics fail
     */
    private void launch(String name, Path config) throws IOException
    {
        Path log = config.resolveSibling(config.getFileName() + ".log");
        platformOwner.protection().createFile(log);
        Map<String, String> environment = new LinkedHashMap<>();
        String temporary = configuration.invocationRoot().resolve("file-pool").toString();
        environment.put("TMPDIR", temporary);
        if (configuration.distribution().target().goOperatingSystem().equals("windows"))
        {
            environment.put("TEMP", temporary);
            environment.put("TMP", temporary);
        }
        Daemon daemon = platformOwner.launch(configuration.distribution().executables().get(name), config, log,
            configuration.invocationRoot(), Map.copyOf(environment));
        daemons.add(Objects.requireNonNull(daemon, "native daemon owner"));
    }

    /**
     * Waits for real authenticated HTTP/2 transport while observing each original daemon.
     * @param endpoint private native endpoint
     * @throws IOException if TLS, daemon lifetime or the waiting thread fails
     */
    private void awaitEndpoint(String endpoint) throws IOException
    {
        BazelBackendCredentials credentials = configuration.credentials();
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
                CountDownLatch changed = new CountDownLatch(1);
                channel.notifyWhenStateChanged(state, changed::countDown);
                for (Daemon daemon : daemons) daemon.onExit().thenRun(changed::countDown);
                try { changed.await(); }
                catch (InterruptedException failure)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while connecting to native backend " + endpoint, failure);
                }
            }
        }
        finally
        {
            channel.shutdownNow();
            boolean interrupted = false;
            try
            {
                while (!channel.isTerminated())
                {
                    try { channel.awaitTermination(1, java.util.concurrent.TimeUnit.DAYS); }
                    catch (InterruptedException failure) { interrupted = true; }
                }
            }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Requires the pinned actual worker to acknowledge the original shared Runner boundary.
     * @throws IOException if a daemon exits, readiness fails or the caller is interrupted
     */
    private void awaitWorkerReadiness() throws IOException
    {
        List<CompletableFuture<?>> wakeups = new ArrayList<>();
        wakeups.add(configuration.workerReadiness());
        for (Daemon daemon : daemons) wakeups.add(daemon.onExit());
        try
        {
            CompletableFuture.anyOf(wakeups.toArray(CompletableFuture<?>[]::new)).get();
            requireAlive();
            configuration.workerReadiness().get();
        }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted before authentic native worker Runner readiness", failure);
        }
        catch (ExecutionException failure)
        {
            throw new IOException("Authenticated native worker readiness failed", failure.getCause());
        }
    }

    /**
     * Returns the private authenticated execution frontend.
     * @return invocation-private grpcs address
     */
    public String executorAddress() { return address; }

    /**
     * Returns the private authenticated cache frontend.
     * @return invocation-private grpcs address
     */
    public String cacheAddress() { return address; }

    /**
     * Returns the protected mandatory Bazel startup policy.
     * @return original invocation-private policy file
     */
    public Path startupRc() { return startupRc; }

    /**
     * Returns the original worker path used by authenticated Runner validation.
     * @return original invocation-owned worker build root
     */
    public Path workerBuildRoot() { return configuration.invocationRoot().resolve("worker-builds"); }

    /**
     * Rejects any original daemon exit without inventing native success.
     * @throws IOException if an original daemon has terminated
     */
    public void requireAlive() throws IOException
    {
        for (Daemon daemon : daemons)
            if (!daemon.isAlive()) throw new IOException("Native backend daemon exited with status " + daemon.exitValue());
    }

    /**
     * Withdraws Runner admissions and collects its native leases before closing original daemon owners.
     * Failure retains every uncollected owner for a later explicit cleanup attempt.
     * @throws IOException if the original cleanup barrier or native collection remains unresolved
     */
    @Override public synchronized void close() throws IOException
    {
        if (closed) return;
        configuration.cleanup().close();
        IOException unresolved = null;
        for (Daemon daemon : daemons.reversed())
        {
            try { daemon.close(); }
            catch (IOException failure)
            {
                if (unresolved == null) unresolved = failure;
                else unresolved.addSuppressed(failure);
            }
        }
        if (unresolved != null) throw unresolved;
        closed = true;
    }

    /**
     * Retains the original partially acquired backend when terminal cleanup cannot yet be proved.
     */
    @SuppressWarnings("serial")
    public static final class AcquisitionFailure extends IOException
    {
        /** Exception format identifier; its live native owner is never serialized. */
        private static final long serialVersionUID = 1L;
        /** Original backend whose unresolved native owners remain retained. */
        private final BazelNativeBackend backend;

        /**
         * Retains acquisition and cleanup failures with the actual native owner.
         * @param backend original partially acquired backend
         * @param acquisition original acquisition failure
         * @param cleanup original cleanup failure
         */
        private AcquisitionFailure(BazelNativeBackend backend, Throwable acquisition, Throwable cleanup)
        {
            super("Native backend acquisition failed and cleanup remains incomplete", acquisition);
            this.backend = backend;
            addSuppressed(cleanup);
        }

        /**
         * Returns the original retained native owner for explicit cleanup retry.
         * @return original partially acquired backend
         */
        public BazelNativeBackend backend() { return backend; }
    }

    /**
     * Exact native release target shared by producer and runtime validation.
     */
    public enum NativeTarget
    {
        /** Native ARM64 Linux backend and existing Linux helper payload. */
        ARM64("aarch64-unknown-linux-musl", "linux", "arm64", "arm64", 183),
        /** Native x64 Linux backend and existing Linux helper payload. */
        X64("x86_64-unknown-linux-musl", "linux", "amd64", "x86_64", 62),
        /** Native ARM64 macOS backend. */
        MACOS_ARM64("aarch64-apple-darwin", "darwin", "arm64", "arm64", 0x0100000c),
        /** Native Intel macOS backend. */
        MACOS_X64("x86_64-apple-darwin", "darwin", "amd64", "x86_64", 0x01000007),
        /** Native ARM64 Windows backend. */
        WINDOWS_ARM64("aarch64-pc-windows-msvc", "windows", "arm64", "arm64", 0xaa64),
        /** Native x64 Windows backend. */
        WINDOWS_X64("x86_64-pc-windows-msvc", "windows", "amd64", "x86_64", 0x8664);

        /** Release target stored in manifests. */
        private final String triple;
        /** Native operating-system name used by the upstream tools. */
        private final String operatingSystem;
        /** Official Go processor identifier. */
        private final String goArchitecture;
        /** Official Bazel processor identifier. */
        private final String bazelArchitecture;
        /** Required ELF, Mach-O or PE machine identifier. */
        private final int machine;

        /**
         * Retains one complete native target mapping.
         * @param triple release target
         * @param operatingSystem native upstream operating-system identifier
         * @param goArchitecture official Go processor identifier
         * @param bazelArchitecture official Bazel processor identifier
         * @param machine expected native executable machine identifier
         */
        NativeTarget(String triple, String operatingSystem, String goArchitecture, String bazelArchitecture, int machine)
        {
            this.triple = triple;
            this.operatingSystem = operatingSystem;
            this.goArchitecture = goArchitecture;
            this.bazelArchitecture = bazelArchitecture;
            this.machine = machine;
        }

        /**
         * Selects the actual native host and rejects unsupported operating systems or processors.
         * @param operatingSystem actual host operating system
         * @param architecture actual host processor
         * @return matching native release target
         * @throws UnsupportedOperationException if either host component is unsupported
         */
        public static NativeTarget forHost(String operatingSystem, String architecture)
        {
            Objects.requireNonNull(operatingSystem, "operatingSystem");
            Objects.requireNonNull(architecture, "architecture");
            boolean arm = architecture.equals("aarch64") || architecture.equals("arm64");
            boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64");
            if (arm || x64)
            {
                if (operatingSystem.equals("Linux")) return arm ? ARM64 : X64;
                if (operatingSystem.equals("Mac OS X") || operatingSystem.equals("Darwin"))
                    return arm ? MACOS_ARM64 : MACOS_X64;
                if (operatingSystem.startsWith("Windows")) return arm ? WINDOWS_ARM64 : WINDOWS_X64;
            }
            throw new UnsupportedOperationException("Unsupported native Bazel backend host: " + operatingSystem + "/" + architecture);
        }

        /**
         * Returns the exact release target.
         * @return release target triple
         */
        public String triple() { return triple; }

        /**
         * Returns the native operating system used by Go and its archives.
         * @return linux, darwin or windows
         */
        public String goOperatingSystem() { return operatingSystem; }

        /**
         * Returns the processor used by Go and its archives.
         * @return official Go processor identifier
         */
        public String goArchitecture() { return goArchitecture; }

        /**
         * Returns the operating system used by official Bazel release filenames.
         * @return linux, darwin or windows
         */
        public String bazelOperatingSystem() { return operatingSystem; }

        /**
         * Returns the processor used by official Bazel release filenames.
         * @return official Bazel processor identifier
         */
        public String bazelArchitecture() { return bazelArchitecture; }

        /**
         * Returns the target's executable filename suffix.
         * @return .exe on Windows, otherwise empty text
         */
        public String suffix() { return operatingSystem.equals("windows") ? ".exe" : ""; }

        /**
         * Returns whether the target includes the existing Linux native helper payload.
         * @return whether this is a Linux target
         */
        public boolean isLinux() { return operatingSystem.equals("linux"); }

        /**
         * Returns the existing Linux native system headers searched after musl headers.
         * @return absolute native Linux header path
         * @throws UnsupportedOperationException if this target does not use the Linux helper build
         */
        public Path systemHeaders()
        {
            if (!isLinux()) throw new UnsupportedOperationException("Native system headers belong to Linux helper builds");
            return Path.of(this == ARM64 ? "/usr/include/aarch64-linux-gnu" : "/usr/include/x86_64-linux-gnu");
        }
    }
}
