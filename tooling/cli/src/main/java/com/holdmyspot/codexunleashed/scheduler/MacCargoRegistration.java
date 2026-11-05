package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates actual native wrapper registration against retained Cargo workload authority before publication.
 * Construction is resource-free. Every operation belongs to the invocation's controller thread. The caller retains
 * the original Cargo owner, peers, immutable installed binary namespace and optional profile source through use.
 * This class grants no process, borrowed-stream, FIFO-token or terminal-result authority by decoding a frame.
 */
public final class MacCargoRegistration
{
    /**
     * Invocation-owned environment entries that a wrapper must keep at their trusted values even below a build-script
     * parent: they select the compiler and wrapper, carry the jobserver, or name the controller socket that registration
     * trusts.
     */
    private static final Set<String> FIXED_ENVIRONMENT = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS", "RUSTC",
        "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "CODEX_CARGO_SOCKET");

    /**
     * Requires a registering wrapper image to be a direct child of an image in the original Cargo image's workload.
     * <p>
     * The parent is the original Cargo image when Cargo compiles a crate, and another workload image, such as a build
     * script, when that image runs the compiler through the wrapper. Every image in Cargo's coalition descends from
     * Cargo and runs under the workload credentials.
     * <p>
     * Each authority holds the eighteen native image fields in the workload bridge's order: PID, unique incarnation,
     * image version, coalition, parent PID, start seconds and microseconds, effective, real and saved user, effective,
     * real and saved group, executable device and inode, the parent's image version when the image was created, and
     * two reserved fields.
     * @param cargo   original Cargo image authority
     * @param parent  authority of the wrapper's direct parent image
     * @param wrapper wrapper image authority
     * @param uid     workload user identifier
     * @param gid     workload group identifier
     * @throws IllegalArgumentException if an authority does not hold eighteen fields
     * @throws IOException              if the parent or wrapper is outside Cargo's coalition, the wrapper was not
     *                                  created by that exact parent image, or any image runs with other credentials
     */
    public static void requireLineage(List<Long> cargo, List<Long> parent, List<Long> wrapper, int uid, int gid)
        throws IOException
    {
        for (List<Long> image : List.of(cargo, parent, wrapper))
            if (image.size() != 18)
                throw new IllegalArgumentException("Native image authority requires eighteen fields: " + image.size());

        if (parent.get(3).longValue() != cargo.get(3).longValue() || wrapper.get(3).longValue() != cargo.get(3).longValue())
            throw new IOException("Native wrapper or its parent image is outside the Cargo workload");
        if (wrapper.get(4).longValue() != parent.get(0).longValue() || wrapper.get(15).longValue() != parent.get(2).longValue())
            throw new IOException("Native wrapper was not created by its retained parent image");
        for (List<Long> image : List.of(cargo, parent, wrapper))
        {
            for (int i = 7; i <= 9; ++i)
                if (image.get(i) != uid)
                    throw new IOException("Native Cargo or wrapper user credentials differ");
            for (int i = 10; i <= 12; ++i)
                if (image.get(i) != gid)
                    throw new IOException("Native Cargo or wrapper group credentials differ");
        }
    }

    /**
     * Requires a wrapper's initial environment to keep every invocation-owned entry.
     * <p>
     * Cargo gives a build script its own environment, for example without {@code RUSTFLAGS}, and the build script
     * passes it to a compiler it runs through the wrapper. Below such a parent, an entry may therefore differ from its
     * trusted value when the wrapper has exactly the parent's initial value, or both lack the entry. The compiler,
     * wrapper, jobserver and controller socket entries must always keep their trusted values.
     * @param required          invocation-owned entry names and their trusted values
     * @param environment       wrapper's complete initial environment
     * @param parentEnvironment initial environment of the wrapper's direct parent, or null when the original Cargo image
     *                          started the wrapper
     * @throws IOException if an invocation-owned entry is absent or has another value
     */
    public static void requireInvocationEnvironment(Map<String, String> required, Map<String, String> environment,
        Map<String, String> parentEnvironment) throws IOException
    {
        for (var entry : required.entrySet())
        {
            String name = entry.getKey();
            if (entry.getValue().equals(environment.get(name)))
                continue;
            if (parentEnvironment != null && !FIXED_ENVIRONMENT.contains(name) &&
                parentEnvironment.containsKey(name) == environment.containsKey(name) &&
                Objects.equals(parentEnvironment.get(name), environment.get(name)))
            {
                continue;
            }
            throw new IOException("Wrapper changed required invocation environment entry: " + name);
        }
    }

    /** Explicit immutable installed binary and workload policy. */
    private final Configuration configuration;
    /** Original native Cargo owner retained through all wrapper and attempt cleanup. */
    private final MacWorkloadNative.Owner owner;
    /** Cargo lineage captured before releasing its original native root. */
    private final MacImages.Identity cargo;
    /** Optional actual completed-work profile source, or null for unknown demand. */
    private final CargoMemoryEstimates estimates;
    /** Registration opportunities consumed before any fallible native observation. */
    private final Set<MacCargoNative.Peer> consumed = new HashSet<>();
    /** Original controller thread; registration never transfers native owners between threads. */
    private final Thread thread = Thread.currentThread();

    /**
     * Retains explicit authentication inputs with unknown compiler demand.
     * @param configuration trusted immutable installed binary and workload policy
     * @param owner original retained native Cargo workload owner
     * @param cargo original held Cargo root lineage
     * @throws NullPointerException if an input is null
     */
    MacCargoRegistration(Configuration configuration, MacWorkloadNative.Owner owner, MacImages.Identity cargo)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.cargo = Objects.requireNonNull(cargo, "cargo");
        this.estimates = null;
    }

    /**
     * Retains explicit authentication inputs and the invocation's actual completed-work estimate source.
     * @param configuration trusted immutable installed binary and workload policy
     * @param owner original retained native Cargo workload owner
     * @param cargo original held Cargo root lineage
     * @param estimates same actual measurement/history wrapper used by the coordinator
     * @throws NullPointerException if an input is null
     */
    MacCargoRegistration(Configuration configuration, MacWorkloadNative.Owner owner, MacImages.Identity cargo,
        CargoMemoryEstimates estimates)
    {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.cargo = Objects.requireNonNull(cargo, "cargo");
        this.estimates = Objects.requireNonNull(estimates, "estimates");
    }

    /**
     * Consumes one peer registration after independent native authentication and publishes immutable compiler inputs.
     * @param peer actual accepted native peer retained by the invocation
     * @return unique action stable across retries, with a completed-work prediction or unknown demand
     * @throws IOException if any native identity, exact context, trusted vnode or required value is unavailable or differs
     * @throws IllegalStateException if called from another thread or this peer was already consumed
     */
    CargoCompilerRequest authenticate(MacCargoNative.Peer peer) throws IOException
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Cargo registration belongs to its constructing thread");
        Objects.requireNonNull(peer, "peer");
        if (!consumed.add(peer)) throw new IllegalStateException("Cargo peer registration already consumed");
        MacCargoNative.KernelIdentity endpoint = peer.identity();
        MacImages.Identity wrapper = owner.inspect(endpoint.pid());
        MacImages.Identity parent = wrapper.parent() == cargo.pid() ? cargo : owner.inspect(wrapper.parent());
        requireAuthority(endpoint, parent, wrapper);
        byte[] claimed = peer.receive();
        byte[] captured = peer.capture();
        if (!Arrays.equals(claimed, captured)) throw new IOException("Wrapper registration differs from independent kernel invocation capture");
        MacCargoMessages.Invocation invocation = MacCargoMessages.decode(captured);
        if (!invocation.arguments().getFirst().equals(configuration.rustcExecutable().toString()))
            throw new IOException("Wrapper requested a compiler outside the installed invocation policy");
        Map<String, String> parentEnvironment = null;
        if (!parent.equals(cargo))
            parentEnvironment = MacCargoMessages.environment(peer.parentEnvironment(parent.pid(), owner.authority(parent).get(2)));
        requireInvocationEnvironment(configuration.requiredEnvironment(), invocation.environment(), parentEnvironment);
        List<Long> parentAuthority = owner.authority(parent);
        requireAuthority(endpoint, parent, wrapper);
        if (!endpoint.equals(peer.identity())) throw new IOException("Native socket endpoint image changed during authentication");
        CargoCompilerRequest request = new CargoCompilerRequest(UUID.randomUUID(),
            new CargoCompilerRequest.Wrapper(endpoint.pid(), endpoint.unique(), parent.pid(), parentAuthority.get(1)),
            invocation.arguments(), invocation.directory(), invocation.environment(), null);
        return estimates == null ? request : estimates.register(request);
    }

    /**
     * Checks full original Cargo, parent and wrapper authority, direct parenting and immutable installed executable vnodes.
     * @param endpoint actual socket audit-token identity
     * @param parent independently observed lineage of the wrapper's direct parent: the original Cargo image or another
     * Cargo workload image
     * @param wrapper independently observed wrapper lineage in the Cargo coalition
     * @throws IOException if credentials, workload, incarnation, image or direct parent authority differs
     */
    private void requireAuthority(MacCargoNative.KernelIdentity endpoint, MacImages.Identity parent,
        MacImages.Identity wrapper) throws IOException
    {
        List<Long> root = owner.authority(cargo);
        List<Long> parentImage = parent.equals(cargo) ? root : owner.authority(parent);
        List<Long> image = owner.authority(wrapper);
        if (endpoint.uid() != configuration.uid() || endpoint.gid() != configuration.gid() ||
            endpoint.pid() != image.get(0) || endpoint.unique() != image.get(1) || endpoint.version() != image.get(2))
            throw new IOException("Native wrapper endpoint differs from its workload image");
        requireLineage(root, parentImage, image, configuration.uid(), configuration.gid());
        executable(configuration.cargoExecutable(), root);
        executable(configuration.wrapperExecutable(), image);
    }

    /**
     * Matches a mapped native executable vnode with its trusted root-owned immutable installed regular file.
     * @param path trusted absolute installed binary
     * @param image full authenticated native image authority
     * @throws IOException if metadata is unsupported, unavailable, writable by another identity or names another vnode
     */
    private static void executable(Path path, List<Long> image) throws IOException
    {
        Map<String, Object> attributes = Files.readAttributes(path, "unix:dev,ino,mode,uid", LinkOption.NOFOLLOW_LINKS);
        long device = ((Number) attributes.get("dev")).longValue();
        long inode = ((Number) attributes.get("ino")).longValue();
        int mode = ((Number) attributes.get("mode")).intValue();
        int uid = ((Number) attributes.get("uid")).intValue();
        if (uid != 0 || (mode & 0170000) != 0100000 || (mode & 0022) != 0 || device != image.get(13) || inode != image.get(14))
            throw new IOException("Mapped executable differs from its trusted immutable installed vnode: " + path);
    }

    /**
     * Stores trusted immutable installed binaries and workload credentials, independently of any registration claim.
     * @param cargoExecutable absolute root-owned installed Cargo binary
     * @param wrapperExecutable absolute root-owned installed native wrapper
     * @param rustcExecutable absolute installed compiler command
     * @param uid original positive workload user identifier
     * @param gid original positive workload group identifier
     * @param requiredEnvironment invocation-owned required environment names and values
     */
    public record Configuration(Path cargoExecutable, Path wrapperExecutable, Path rustcExecutable, int uid, int gid,
        Map<String, String> requiredEnvironment)
    {
        /**
         * Copies policy values without acquiring files, processes or native resources.
         * @param cargoExecutable absolute trusted Cargo binary
         * @param wrapperExecutable absolute trusted wrapper binary
         * @param rustcExecutable absolute trusted compiler command
         * @param uid positive workload user identifier
         * @param gid positive workload group identifier
         * @param requiredEnvironment complete required invocation-owned entries
         * @throws NullPointerException if a value or collection entry is null
         * @throws IllegalArgumentException if a path is relative, credentials are nonpositive or an environment entry is invalid
         */
        public Configuration
        {
            if (!cargoExecutable.isAbsolute() || !wrapperExecutable.isAbsolute() || !rustcExecutable.isAbsolute() || uid <= 0 || gid <= 0)
                throw new IllegalArgumentException("Cargo registration requires absolute installed binaries and positive workload credentials");
            requiredEnvironment = Map.copyOf(requiredEnvironment);
            for (var entry : requiredEnvironment.entrySet())
                if (entry.getKey().isEmpty() || entry.getKey().indexOf('=') >= 0 || entry.getKey().indexOf('\0') >= 0 || entry.getValue().indexOf('\0') >= 0)
                    throw new IllegalArgumentException("Required Cargo environment contains invalid native text");
        }
    }
}
