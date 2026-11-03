package com.holdmyspot.codexunleashed.scheduler;

import buildbarn.runner.RunnerOuterClass.RunRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies Linux pathname and directory authority to the shared authenticated Runner service.
 * Native workspace acquisition remains with the coordinator after immutable admission.
 */
public final class LinuxBazelRunner implements AutoCloseable
{
    /** Sole common transport and publication authority. */
    private final BazelRunner<NativeAdmission> runner;
    /** Trusted worker directory, outside all action roots. */
    private final Path buildRoot;

    /**
     * Retains host-owned configuration without opening a listener.
     * @param buildRoot absolute private directory materialized by the trusted worker
     * @param credentials private TLS material, never mounted into an action
     */
    public LinuxBazelRunner(Path buildRoot, Credentials credentials)
    {
        this.buildRoot = Objects.requireNonNull(buildRoot, "buildRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(credentials, "credentials");
        this.runner = new BazelRunner<>(new NativeBoundary(), new BazelRunner.Credentials(credentials.authority(),
            credentials.serverCertificate(), credentials.serverPrivateKey(), credentials.workerCertificate()));
    }

    /**
     * Observes authenticated worker readiness after Linux pathname validation.
     * @return independent future completed by the actual shared Runner readiness callback
     */
    public java.util.concurrent.CompletableFuture<Void> workerReadiness() { return runner.workerReadiness(); }

    /**
     * Starts one JDK TLS listener on a kernel-selected loopback port.
     * @throws IOException if identity material or listener setup fails
     */
    public void start() throws IOException { runner.start(); }

    /**
     * Returns the bound loopback endpoint without a transport scheme.
     * @return numeric loopback host and port
     */
    public String endpoint() { return runner.endpoint(); }

    /**
     * Transfers the oldest authenticated request without executing it.
     * @return next original Linux ready request, if any
     */
    public Optional<Ready> poll() { return runner.poll().map(Ready::new); }

    /**
     * Withdraws future RPCs while preserving live native cleanup leases.
     */
    void stopAccepting() { runner.stopAccepting(); }

    @Override public void close() throws IOException { runner.close(); }

    /**
     * Validates the protocol's relative pathname without substituting any action argument.
     * @param value protocol pathname
     * @param emptyAllowed whether the empty cwd denotes the input root
     * @return normalized relative path
     * @throws IOException if the pathname can escape or has ambiguous lexical semantics
     */
    public static Path relative(String value, boolean emptyAllowed) throws IOException
    {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() && emptyAllowed) return Path.of("");
        if (value.isEmpty() || value.startsWith("/") || value.indexOf(0) >= 0)
            throw new IOException("Worker protocol pathname must be relative and nonempty");
        for (String component : value.split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IOException("Worker protocol pathname has an ambiguous component");
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path))
            throw new IOException("Worker protocol pathname must be a normalized relative path");
        return path;
    }

    /**
     * Resolves a trusted request path while rejecting symlink ancestors and an escape from the worker root.
     * @param value protocol pathname
     * @param directory whether the final component must already be a directory
     * @return absolute unchanged worker-visible location
     * @throws IOException if a component is missing, linked or outside the root
     */
    private Path resolve(String value, boolean directory) throws IOException
    {
        Path relative = relative(value, false);
        Path result = buildRoot.resolve(relative);
        Path checked = buildRoot;
        for (Path component : relative)
        {
            checked = checked.resolve(component);
            if (Files.isSymbolicLink(checked)) throw new IOException("Worker protocol pathname is a symbolic link");
            if (!checked.equals(result) || directory) requireDirectory(checked);
        }
        if (!directory && Files.exists(result, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Worker output stream already exists");
        return result;
    }

    /**
     * Requires a direct existing directory.
     * @param path trusted filesystem location
     * @throws IOException if the location is linked or not a directory
     */
    private static void requireDirectory(Path path) throws IOException
    {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Runner path is not a direct existing directory: " + path);
    }

    /**
     * Invocation-private JDK TLS inputs.
     * @param authority trusted invocation CA
     * @param serverCertificate loopback server identity
     * @param serverPrivateKey PKCS8 server key
     * @param workerCertificate exact leaf certificate authorized to enqueue work
     */
    public record Credentials(Path authority, Path serverCertificate, Path serverPrivateKey, Path workerCertificate) {}

    /**
     * Linux-only interpretation of worker paths and directory identity authority.
     */
    private final class NativeBoundary implements BazelRunner.Boundary<NativeAdmission>
    {
        /**
         * Creates the validation boundary without acquiring native resources.
         */
        private NativeBoundary() {}

        @Override public void validateRoot() throws IOException { requireDirectory(buildRoot); }

        @Override public void checkReadiness(String path) throws IOException { resolve(path, true); }

        @Override public NativeAdmission admit(RunRequest request) throws IOException
        {
            Path input = resolve(request.getInputRootDirectory(), true);
            Path temporary = resolve(request.getTemporaryDirectory(), true);
            Path cwd = input;
            for (Path component : relative(request.getWorkingDirectory(), true))
            {
                cwd = cwd.resolve(component);
                requireDirectory(cwd);
            }
            resolve(request.getServerLogsDirectory(), true);
            Path stdout = resolve(request.getStdoutPath(), false);
            Path stderr = resolve(request.getStderrPath(), false);
            if (input.startsWith(temporary) || temporary.startsWith(input) || stdout.equals(stderr) ||
                stdout.startsWith(input) || stderr.startsWith(input) || stdout.startsWith(temporary) ||
                stderr.startsWith(temporary))
                throw new IOException("Worker input, temporary and stream paths must be independent");
            return new NativeAdmission(buildRoot, input, temporary, cwd, List.of(stdout, stderr));
        }
    }

    /**
     * Original admitted Linux directories and their stable identities, independent of RPC state.
     */
    private static final class NativeAdmission implements BazelRunner.Admission
    {
        /** Original worker-visible input root. */
        private final Path inputRoot;
        /** Original worker-visible temporary directory. */
        private final Path temporaryDirectory;
        /** Trusted worker build root retained from authenticated admission. */
        private final Path admittedBuildRoot;
        /** Admission directory identities in build/input/temporary order. */
        private final List<Object> admittedKeys;
        /** Exact absolute action-visible cwd. */
        private final Path directory;
        /** Original stdout and stderr destinations. */
        private final List<Path> streams;

        /**
         * Captures original admitted directory identities without acquiring a native owner.
         * @param buildRoot trusted worker build root
         * @param inputRoot original input directory
         * @param temporaryDirectory original temporary directory
         * @param directory original action cwd
         * @param streams original stdout and stderr paths
         * @throws IOException if stable directory identities cannot be captured
         */
        private NativeAdmission(Path buildRoot, Path inputRoot, Path temporaryDirectory, Path directory,
            List<Path> streams) throws IOException
        {
            this.admittedBuildRoot = buildRoot;
            this.admittedKeys = List.of(directoryKey(buildRoot), directoryKey(inputRoot), directoryKey(temporaryDirectory));
            this.inputRoot = inputRoot;
            this.temporaryDirectory = temporaryDirectory;
            this.directory = directory;
            this.streams = List.copyOf(streams);
        }

        @Override public List<Path> claimedPaths()
        {
            return List.of(inputRoot, temporaryDirectory, streams.get(0), streams.get(1));
        }

        /**
         * Opens the original admitted directory objects as a private retry workspace.
         * Trusted worker-owned host ancestry and serialized materialization remain mandatory: these checks
         * detect replacement at acquisition boundaries, while workspace descriptors protect subsequent promotion.
         * @param ownedRoot absent private workspace directory on the original input filesystem
         * @return workspace anchored to the original admitted input and temporary directories
         * @throws IOException if admission objects changed or snapshot acquisition fails
         */
        public LinuxBazelWorkspace openWorkspace(Path ownedRoot) throws IOException
        {
            verifyAdmission();
            LinuxBazelWorkspace workspace = LinuxBazelWorkspace.open(inputRoot, temporaryDirectory, ownedRoot);
            try
            {
                verifyAdmission();
                return workspace;
            }
            catch (IOException | RuntimeException failure)
            {
                try { workspace.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Checks all original host directory identities before admitting output authority.
         * @throws IOException if a path was detached, replaced or substituted by a symbolic link
         */
        private void verifyAdmission() throws IOException
        {
            List<Object> current = List.of(directoryKey(admittedBuildRoot), directoryKey(inputRoot),
                directoryKey(temporaryDirectory));
            if (!current.equals(admittedKeys)) throw new IOException("Original admitted worker directories were replaced");
        }

        /**
         * Captures a stable directory identity after rejecting every symlink component.
         * @param path absolute trusted worker directory
         * @return nonnull provider file key
         * @throws IOException if a component is not a directory or stable identity is unavailable
         */
        private static Object directoryKey(Path path) throws IOException
        {
            Path current = path.getRoot();
            requireDirectory(current);
            for (Path component : path)
            {
                current = current.resolve(component);
                requireDirectory(current);
            }
            Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            if (key == null) throw new IOException("Worker directory has no stable file identity: " + path);
            return key;
        }

    }

    /**
     * Linux facade preserving producer signatures while delegating all RPC state to the common service.
     */
    public static final class Ready
    {
        /** Sole original request and publication authority. */
        private final BazelRunner.Ready<NativeAdmission> ready;

        /**
         * Retains the shared original ready request.
         * @param ready immutable authenticated request authority
         */
        private Ready(BazelRunner.Ready<NativeAdmission> ready) { this.ready = ready; }

        /**
         * Opens a workspace anchored to the original admitted directory objects.
         * @param ownedRoot absent private retry directory on the original input filesystem
         * @return private workspace for the original directories
         * @throws IOException if identity verification or workspace acquisition fails
         */
        public LinuxBazelWorkspace openWorkspace(Path ownedRoot) throws IOException { return ready.admission().openWorkspace(ownedRoot); }

        /**
         * Returns whether original RPC authority still permits output promotion.
         * @return whether publication remains authorized
         */
        public boolean publicationAllowed() { return ready.publicationAllowed(); }

        /**
         * Publishes an authentic final result after promotion and cleanup.
         * @param exitCode authentic original ordinary action exit status
         * @return whether the original RPC accepted the result
         */
        public boolean complete(int exitCode) { return ready.complete(exitCode); }

        /**
         * Serializes authenticated promotion against cancellation notification.
         * The transaction must recheck {@link #publicationAllowed()} before its final commit.
         * @param publication rollback-capable original output transaction
         * @param exitCode authentic original ordinary result
         * @return whether publication completed before cancellation
         * @throws IOException if promotion fails
         */
        public boolean publish(Publication publication, int exitCode) throws IOException { return ready.publish(publication, exitCode); }

        /**
         * Reports host failure without releasing its independent native cleanup lease.
         * @param cause original host failure
         */
        public void fail(Throwable cause) { ready.fail(cause); }

        /**
         * Retires original path ownership only after native cleanup is complete.
         */
        public void releaseClaim() { ready.releaseClaim(); }

        /**
         * Returns the original invocation-local Run ordinal.
         * @return original invocation-local Run ordinal
         */
        public long sequence() { return ready.sequence(); }

        /**
         * Returns the immutable original protocol request.
         * @return immutable original protocol request
         */
        public RunRequest request() { return ready.request(); }

        /**
         * Returns the original worker input root.
         * @return original worker input root
         */
        Path inputRoot() { return ready.admission().inputRoot; }

        /**
         * Returns the original action temporary directory.
         * @return original action temporary directory
         */
        Path temporaryDirectory() { return ready.admission().temporaryDirectory; }

        /**
         * Returns the original absolute action cwd.
         * @return original absolute action cwd
         */
        public Path directory() { return ready.admission().directory; }

        /**
         * Returns the original stdout and stderr paths.
         * @return original stdout and stderr paths
         */
        List<Path> streams() { return ready.admission().streams; }

    }

    /**
     * Original coordinator-owned transaction delegated to the common publication authority.
     */
    @FunctionalInterface
    public interface Publication extends BazelRunner.Publication {}
}
