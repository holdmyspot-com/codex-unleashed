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
        requireAuthority(endpoint, wrapper);
        byte[] claimed = peer.receive();
        byte[] captured = peer.capture();
        if (!Arrays.equals(claimed, captured)) throw new IOException("Wrapper registration differs from independent kernel invocation capture");
        MacCargoMessages.Invocation invocation = MacCargoMessages.decode(captured);
        if (!invocation.arguments().getFirst().equals(configuration.rustcExecutable().toString()))
            throw new IOException("Wrapper requested a compiler outside the installed invocation policy");
        for (var entry : configuration.requiredEnvironment().entrySet())
            if (!entry.getValue().equals(invocation.environment().get(entry.getKey())))
                throw new IOException("Wrapper changed required invocation environment entry: " + entry.getKey());
        List<Long> cargoAuthority = owner.authority(cargo);
        requireAuthority(endpoint, wrapper);
        if (!endpoint.equals(peer.identity())) throw new IOException("Native socket endpoint image changed during authentication");
        CargoCompilerRequest request = new CargoCompilerRequest(UUID.randomUUID(),
            new CargoCompilerRequest.Wrapper(endpoint.pid(), endpoint.unique(), cargo.pid(), cargoAuthority.get(1)),
            invocation.arguments(), invocation.directory(), invocation.environment(), null);
        return estimates == null ? request : estimates.register(request);
    }

    /**
     * Checks full original Cargo and wrapper authority, direct parenting and immutable installed executable vnodes.
     * @param endpoint actual socket audit-token identity
     * @param wrapper independently observed wrapper lineage in the Cargo coalition
     * @throws IOException if credentials, workload, incarnation, image or direct parent authority differs
     */
    private void requireAuthority(MacCargoNative.KernelIdentity endpoint, MacImages.Identity wrapper) throws IOException
    {
        List<Long> root = owner.authority(cargo);
        List<Long> image = owner.authority(wrapper);
        if (endpoint.uid() != configuration.uid() || endpoint.gid() != configuration.gid() ||
            endpoint.pid() != image.get(0) || endpoint.unique() != image.get(1) || endpoint.version() != image.get(2) ||
            image.get(3).longValue() != root.get(3).longValue() || image.get(4) != cargo.pid() || image.get(15).longValue() != root.get(2).longValue())
            throw new IOException("Native wrapper identity or direct original Cargo parent image differs");
        for (int i = 7; i <= 9; ++i)
            if (image.get(i) != configuration.uid() || root.get(i) != configuration.uid())
                throw new IOException("Native Cargo or wrapper user credentials differ");
        for (int i = 10; i <= 12; ++i)
            if (image.get(i) != configuration.gid() || root.get(i) != configuration.gid())
                throw new IOException("Native Cargo or wrapper group credentials differ");
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
