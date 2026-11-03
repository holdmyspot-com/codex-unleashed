package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owns one Darwin Cargo invocation's FIFO namespace and mediated whole-attempt token capabilities.
 * Retain this resource-free owner before calling open, including when open fails. Close it only after Cargo,
 * wrappers and every compiler descendant are collected; this component cannot establish that barrier itself.
 * The native library owner and parent namespace remain caller-owned. All operations use the constructing thread.
 */
public final class MacCargoJobserver implements AutoCloseable
{
    /** Root make variables exclusively supplied by this invocation. */
    private static final Set<String> MAKE_VARIABLES = Set.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS");
    /** Private setup access before publishing workload-group traversal. */
    private static final Set<PosixFilePermission> SETUP_PERMISSIONS =
        Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    /** Root-owned directory lets only the workload group traverse its retained FIFO name. */
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        Set.copyOf(PosixFilePermissions.fromString("rwx--x---"));
    /** Invocation-owned directory prefix. */
    private static final String DIRECTORY_PREFIX = "mac-cargo-jobserver-";
    /** Exact child name owned by the native FIFO. */
    private static final String FIFO_NAME = "tokens";
    /** Descriptor number on which the make front end opens the FIFO for jobserver reads. */
    public static final int READ_DESCRIPTOR = 200;
    /** Descriptor number on which the make front end opens the FIFO for jobserver writes. */
    public static final int WRITE_DESCRIPTOR = 201;
    /** Directory, inside the owned directory, holding the make front end that PATH names first. */
    private static final String FRONT_END_DIRECTORY = "bin";
    /** Front-end script access permitting the workload group to read and execute it. */
    private static final Set<PosixFilePermission> FRONT_END_PERMISSIONS =
        Set.copyOf(PosixFilePermissions.fromString("rwxr-x---"));
    /** Preferred jobserver authentication option. */
    private static final String AUTH_OPTION = "--jobserver-auth=";
    /** Borrowed native access and partial-owner inventory. */
    private final Provider provider;
    /** Constructing controller thread. */
    private final Thread owner = Thread.currentThread();
    /** Every acquired FIFO retained even when publication fails. */
    private final List<Endpoint> endpoints = new ArrayList<>();
    /** Exact directory created here, or null before acquisition and after removal. */
    private Path directory;
    /** Workload group granted access to the make front end. */
    private int workloadGroup = -1;
    /** Created make front end, or null when none was created or after its deletion. */
    private Path frontEnd;
    /** Created front-end directory, or null when none was created or after its deletion. */
    private Path frontEndDirectory;
    /** Published successful FIFO transport, or null when acquisition did not complete. */
    private Endpoint fifo;
    /** One published invocation lease, or null before publication and after release. */
    private CargoLease lease;
    /** Indicates that open has consumed its only acquisition opportunity. */
    private boolean attempted;
    /** Indicates that the invocation's only lease has ever been retained. */
    private boolean retained;
    /** Indicates that close has begun and new consumers are permanently forbidden. */
    private boolean closing;

    /**
     * Retains resource-free native access; the caller retains this object before acquisition.
     * @param nativeOwner borrowed native library/listener owner retained through final FIFO destruction
     * @throws NullPointerException if nativeOwner is null
     */
    MacCargoJobserver(MacCargoNative nativeOwner)
    {
        this(new NativeProvider(Objects.requireNonNull(nativeOwner, "nativeOwner may not be null")));
    }

    /**
     * Retains a resource-free FIFO creation boundary.
     * @param provider native effects and retained partial owners
     * @throws NullPointerException if provider is null
     */
    private MacCargoJobserver(Provider provider)
    {
        this.provider = Objects.requireNonNull(provider, "provider may not be null");
    }

    /**
     * Creates a fresh root-owned namespace and publishes exactly jobs minus one extra native bytes.
     * The caller provides workload traversal through all parent ancestors without allowing workload renames.
     * A failed acquisition is never repeated; close this same retained owner and the native owner on failure.
     * @param scratchParent existing absolute trusted parent with a lossless whitespace-free real pathname
     * @param uid original positive workload user identifier
     * @param gid original positive workload group identifier
     * @param jobs initial implicit plus extra capacity, one through1024
     * @throws NullPointerException if scratchParent is null
     * @throws IllegalArgumentException if the path or numeric inputs are invalid
     * @throws IllegalStateException if off-thread, already attempted or closing
     * @throws IOException if filesystem preparation, native publication or access verification fails
     */
    public void open(Path scratchParent, int uid, int gid, int jobs) throws IOException
    {
        requireOwner();
        Objects.requireNonNull(scratchParent, "scratchParent may not be null");
        if (!scratchParent.isAbsolute() || uid <= 0 || gid <= 0 || jobs < 1 || jobs > 1024)
            throw new IllegalArgumentException("Jobserver requires an absolute parent, positive credentials and one through1024 slots");
        Path parent = scratchParent.toRealPath();
        String text = parent.toString();
        if (text.codePoints().anyMatch(Character::isWhitespace) || text.contains(AUTH_OPTION) ||
            !Path.of(text).equals(parent))
            throw new IllegalArgumentException("Jobserver parent must have a lossless whitespace-free auth pathname: " + parent);
        if (attempted || closing) throw new IllegalStateException("Jobserver acquisition already attempted or closing");
        attempted = true;
        workloadGroup = gid;
        directory = provider.createDirectory(parent);
        provider.publishDirectory(directory, gid);
        try { fifo = provider.create(directory.resolve(FIFO_NAME), uid, gid, jobs); }
        finally { endpoints.addAll(provider.retained()); }
    }

    /**
     * Publishes the only Cargo invocation environment and owns its logical attempt ledger.
     * Existing make variables, even empty values, are rejected without consuming the single lease opportunity.
     * <p>
     * The make flags name the FIFO by path, so Cargo's children start without a fork-forcing pre-exec hook. GNU Make
     * before 4.4, including Darwin's 3.81, rejects a FIFO path, so when {@code PATH} names a {@code make}, the lease
     * prepends an owned directory whose {@code make} front end opens the FIFO onto {@link #READ_DESCRIPTOR} and
     * {@link #WRITE_DESCRIPTOR}, names them in the make flags and executes that make.
     * @param environment complete immutable-copyable environment without make variables
     * @return invocation borrow retained until every native consumer and logical attempt retires
     * @throws NullPointerException if the map or an entry is null
     * @throws IllegalArgumentException if any owned make variable is present, or the make that {@code PATH} names
     *   has a path that a shell word cannot carry
     * @throws IllegalStateException if off-thread, unavailable, closing or already retained
     * @throws IOException if the make front end cannot be created
     */
    public CargoLease retainCargo(Map<String, String> environment) throws IOException
    {
        requireOwner();
        Map<String, String> copy = new HashMap<>(Map.copyOf(environment));
        for (String name : MAKE_VARIABLES)
            if (copy.containsKey(name)) throw new IllegalArgumentException("Root Cargo environment already contains " + name);
        if (fifo == null || closing || retained) throw new IllegalStateException("Jobserver unavailable or already retained");
        String auth = "fifo:" + directory.resolve(FIFO_NAME);
        String flags = "-j --jobserver-fds=" + auth + " " + AUTH_OPTION + auth;
        for (String name : MAKE_VARIABLES) copy.put(name, flags);
        String search = copy.get("PATH");
        if (search != null)
        {
            Path make = CargoMakeFrontEnd.findMake(search);
            if (make != null)
                copy.put("PATH", createFrontEnd(make) + ":" + search);
        }
        lease = new CargoLease(Map.copyOf(copy));
        retained = true;
        return lease;
    }

    /**
     * Creates the make front end that opens the FIFO onto fixed descriptors for GNU Make before 4.4.
     * The workload group can look the front end up and execute it but cannot modify or replace it.
     * @param make real path of the make to execute
     * @return front-end directory
     * @throws IOException if creation or access publication fails
     */
    private Path createFrontEnd(Path make) throws IOException
    {
        String script = CargoMakeFrontEnd.script(make, directory.resolve(FIFO_NAME), READ_DESCRIPTOR, WRITE_DESCRIPTOR, true);
        frontEndDirectory = Files.createDirectory(directory.resolve(FRONT_END_DIRECTORY),
            PosixFilePermissions.asFileAttribute(SETUP_PERMISSIONS));
        frontEnd = Files.createFile(frontEndDirectory.resolve("make"),
            PosixFilePermissions.asFileAttribute(SETUP_PERMISSIONS));
        Files.writeString(frontEnd, script);
        Files.setAttribute(frontEnd, "unix:gid", workloadGroup);
        Files.setPosixFilePermissions(frontEnd, FRONT_END_PERMISSIONS);
        Files.setAttribute(frontEndDirectory, "unix:gid", workloadGroup);
        Files.setPosixFilePermissions(frontEndDirectory, DIRECTORY_PERMISSIONS);
        if (!Files.getAttribute(frontEnd, "unix:gid").equals(workloadGroup) ||
            !Files.getPosixFilePermissions(frontEnd).equals(FRONT_END_PERMISSIONS) ||
            !Files.getPosixFilePermissions(frontEndDirectory).equals(DIRECTORY_PERMISSIONS))
            throw new IOException("Make front end access differs from requested group/permissions: " + frontEnd);
        return frontEndDirectory;
    }

    /**
     * Closes every retained FIFO after all consumers and token attempts retire, then removes only its exact directory.
     * Uncertain native destruction stays retained without repeating effects. Directory deletion can be retried.
     * @throws IllegalStateException if off-thread or a Cargo lease remains live
     * @throws IOException if FIFO destruction or exact owned-directory removal fails
     */
    @Override public void close() throws IOException
    {
        requireOwner();
        if (lease != null) throw new IllegalStateException("Cargo consumers still retain their jobserver lease");
        closing = true;
        IOException failure = null;
        for (Endpoint endpoint : List.copyOf(endpoints))
        {
            try { endpoint.close(); endpoints.remove(endpoint); }
            catch (IOException cleanup)
            {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (frontEnd != null)
        {
            try { Files.delete(frontEnd); frontEnd = null; }
            catch (IOException cleanup)
            {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (frontEnd == null && frontEndDirectory != null)
        {
            try { Files.delete(frontEndDirectory); frontEndDirectory = null; }
            catch (IOException cleanup)
            {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (endpoints.isEmpty() && frontEndDirectory == null && directory != null)
        {
            try { Files.delete(directory); directory = null; }
            catch (IOException cleanup)
            {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * Confines lifecycle and native operations to the controller thread.
     * @throws IllegalStateException if off-thread
     */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Jobserver belongs to its constructing thread");
    }

    /**
     * Borrows the native FIFO through the entire root invocation and every whole-attempt capability.
     */
    public final class CargoLease implements AutoCloseable
    {
        /** Exact immutable environment inherited by root Cargo and compiler contexts. */
        private final Map<String, String> environment;
        /** Shared opaque byte accounting; the root implicit slot has no ledger entry. */
        private final LinuxCargoTokens tokens = new LinuxCargoTokens(fifo);
        /** Indicates the invocation released this borrow after complete consumer collection. */
        private boolean released;

        /**
         * Retains an already validated immutable launch environment.
         * @param environment complete invocation launch environment
         */
        private CargoLease(Map<String, String> environment) { this.environment = environment; }

        /**
         * Returns this invocation's complete environment.
         * @return immutable environment with exact FIFO make flags
         * @throws IllegalStateException if off-thread or released
         */
        public Map<String, String> environment() { requireLive(); return environment; }

        /**
         * Reads descriptor-bound FIFO identity before installing an authenticated image hook.
         * @return exact native device and unsigned inode bits
         * @throws IllegalStateException if off-thread or released
         * @throws IOException if the descriptor no longer proves its retained FIFO identity
         */
        MacCargoNative.FifoIdentity identity() throws IOException { requireLive(); return fifo.identity(); }

        /**
         * Retains accounting for a whole authenticated attempt, including root Cargo when mediated.
         * The caller retires it only after preventing new starts and establishing every descendant cannot execute.
         * @return fresh attempt capability sharing this invocation's exact byte ledger
         * @throws IllegalStateException if off-thread or released
         * @throws IOException if an earlier transfer left the FIFO state uncertain
         */
        public LinuxCargoTokens.Attempt retainAttempt() throws IOException { requireLive(); return tokens.retainAttempt(); }

        /**
         * Requires a live invocation borrow on the controller thread.
         * @throws IllegalStateException if off-thread or released
         */
        private void requireLive()
        {
            requireOwner();
            if (released) throw new IllegalStateException("Cargo jobserver lease was released");
        }

        /**
         * Releases the invocation borrow after all native consumers are collected and token attempts retired.
         * A retained attempt rejects closure without releasing this lease. Repeated successful closure has no effect.
         * @throws IllegalStateException if off-thread or a whole-attempt capability remains live
         */
        @Override public void close()
        {
            requireOwner();
            if (released) return;
            tokens.close();
            released = true;
            lease = null;
        }
    }

    /**
     * Retains partial native acquisition and supplies successful native FIFO transports.
     */
    public interface Provider
    {
        /**
         * Creates a private fresh directory owned by this invocation.
         * @param parent caller-owned existing absolute real parent
         * @return fresh directory whose cleanup transfers to the jobserver before access publication
         * @throws IOException if directory creation fails
         */
        Path createDirectory(Path parent) throws IOException;

        /**
         * Publishes and verifies root ownership and workload-group traversal without granting renames.
         * @param directory exact directory just created by this provider
         * @param gid positive workload group
         * @throws IOException if publication or authority verification fails
         */
        void publishDirectory(Path directory, int gid) throws IOException;

        /**
         * Creates the invocation FIFO once, retaining partial ownership before any fallible native effect.
         * @param path fresh absolute trusted FIFO name
         * @param uid workload user
         * @param gid workload group
         * @param jobs capacity including the implicit slot
         * @return successful owned endpoint
         * @throws IOException if creation or seeding fails
         */
        Endpoint create(Path path, int uid, int gid, int jobs) throws IOException;

        /**
         * Returns every partial and successful endpoint introduced by this provider.
         * @return immutable owned endpoint inventory, including after create throws
         */
        List<Endpoint> retained();
    }

    /**
     * Owns native transport and descriptor identity through the invocation's terminal barrier.
     */
    public interface Endpoint extends LinuxCargoTokens.Transfer, AutoCloseable
    {
        /**
         * Reads exact retained descriptor identity without pathname authority.
         * @return native device and unsigned inode bits
         * @throws IOException if native descriptor authority cannot be revalidated
         */
        MacCargoNative.FifoIdentity identity() throws IOException;

        /**
         * Destroys this native FIFO after all consumers retire; uncertain effects must never replay.
         * @throws IOException if destruction fails or remains uncertain
         */
        @Override void close() throws IOException;
    }

    /**
     * Adapts the actual native owner's partial FIFO inventory without acquiring another native owner.
     */
    private static final class NativeProvider implements Provider
    {
        /** Borrowed actual library/listener owner. */
        private final MacCargoNative nativeOwner;
        /** Existing unrelated FIFOs excluded from this invocation's cleanup authority. */
        private List<MacCargoNative.Fifo> previous = List.of();

        /**
         * Borrows the original native owner before acquisition.
         * @param nativeOwner borrowed original native owner
         */
        private NativeProvider(MacCargoNative nativeOwner)
        {
            this.nativeOwner = nativeOwner;

        }

        @Override public Path createDirectory(Path parent) throws IOException
        {
            return Files.createTempDirectory(parent, DIRECTORY_PREFIX,
                PosixFilePermissions.asFileAttribute(SETUP_PERMISSIONS));
        }

        @Override public void publishDirectory(Path directory, int gid) throws IOException
        {
            Files.setAttribute(directory, "unix:gid", gid);
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
            if (!Files.getAttribute(directory, "unix:uid").equals(0) ||
                !Files.getAttribute(directory, "unix:gid").equals(gid) ||
                !Files.getPosixFilePermissions(directory).equals(DIRECTORY_PERMISSIONS))
                throw new IOException("Jobserver directory must be root-owned and workload-group searchable: " + directory);
        }

        @Override public Endpoint create(Path path, int uid, int gid, int jobs) throws IOException
        {
            previous = nativeOwner.fifos();
            return new NativeEndpoint(nativeOwner.createFifo(path, uid, gid, jobs));
        }

        @Override public List<Endpoint> retained()
        {
            List<Endpoint> result = new ArrayList<>();
            for (MacCargoNative.Fifo candidate : nativeOwner.fifos())
                if (!previous.contains(candidate)) result.add(new NativeEndpoint(candidate));
            return List.copyOf(result);
        }
    }

    /**
     * Binds the native transport and destruction operations to one retained native FIFO.
     */
    private static final class NativeEndpoint implements Endpoint
    {
        /** Actual native endpoint retained through its single destruction opportunity. */
        private final MacCargoNative.Fifo fifo;
        /**
         * Retains a nonnull native endpoint without acquiring anything.
         * @param fifo native endpoint
         * @throws NullPointerException if fifo is null
         */
        private NativeEndpoint(MacCargoNative.Fifo fifo)
        {
            this.fifo = Objects.requireNonNull(fifo, "fifo may not be null");
        }
        @Override public MacCargoNative.FifoIdentity identity() throws IOException { return fifo.identity(); }
        @Override public java.util.OptionalInt tryRead() throws IOException { return fifo.tryRead(); }
        @Override public void write(byte token) throws IOException { fifo.write(token); }
        @Override public void close() throws IOException { fifo.close(); }
    }

    /**
     * Provides typed access to the real acquisition boundary for isolated native-result fixtures.
     */
    public static final class SharedSecrets
    {
        /**
         * Prevents construction.
         */
        private SharedSecrets() {}

        /**
         * Retains a resource-free provider for the same normal filesystem and accounting operations.
         * @param provider controlled native boundary
         * @return new unacquired owner
         */
        public static MacCargoJobserver create(Provider provider) { return new MacCargoJobserver(provider); }
    }
}
