package com.holdmyspot.codexunleashed.scheduler;

import buildbarn.runner.RunnerGrpc;
import buildbarn.runner.RunnerOuterClass.CheckReadinessRequest;
import buildbarn.runner.RunnerOuterClass.RunRequest;
import buildbarn.runner.RunnerOuterClass.RunResponse;
import com.google.protobuf.Empty;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioIoHandler;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerSocketChannel;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSession;

/**
 * Accepts the pinned Buildbarn ready-action protocol over mutually authenticated loopback TLS.
 * RPC threads enqueue immutable requests; only the scheduler thread creates native owners or touches its queue.
 * Cancellation withdraws publication authority while the producer retains the independent native cleanup lease.
 */
final class LinuxBazelRunner implements AutoCloseable
{
    /** Trusted worker directory, outside all action roots. */
    private final Path buildRoot;
    /** Invocation-private server and exact allowed client identities. */
    private final Credentials credentials;
    /** Accepted requests in actual discovery order. */
    private final ConcurrentLinkedQueue<Ready> requests = new ConcurrentLinkedQueue<>();
    /** Overlapping original roots remain exclusive until terminal authenticated cleanup. */
    private final RootClaims rootClaims = new RootClaims();
    /** Monotonic logical identities within this invocation. */
    private final AtomicLong sequence = new AtomicLong();
    /** New RPC admission authority. */
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    /** Sole actual bound server, retained through failed cleanup. */
    private Server server;
    /** Explicit portable transport owner, retained even when listener acquisition fails. */
    private MultiThreadIoEventLoopGroup transport;

    /**
     * Retains host-owned configuration without opening a listener.
     * @param buildRoot absolute private directory materialized by the trusted worker
     * @param credentials private TLS material, never mounted into an action
     */
    LinuxBazelRunner(Path buildRoot, Credentials credentials)
    {
        this.buildRoot = Objects.requireNonNull(buildRoot, "buildRoot").toAbsolutePath().normalize();
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Starts one JDK TLS listener on a kernel-selected loopback port.
     * @throws IOException if identity material or listener setup fails
     */
    void start() throws IOException
    {
        if (server != null || !accepting.get()) throw new IllegalStateException("Runner starts once");
        requireDirectory(buildRoot);
        byte[] client = certificate(credentials.workerCertificate());
        var tls = GrpcSslContexts.configure(SslContextBuilder.forServer(credentials.serverCertificate().toFile(),
            credentials.serverPrivateKey().toFile()), SslProvider.JDK)
            .applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT, ApplicationProtocolNames.HTTP_2))
            .trustManager(credentials.authority().toFile()).clientAuth(ClientAuth.REQUIRE).build();
        transport = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .channelType(NioServerSocketChannel.class).bossEventLoopGroup(transport).workerEventLoopGroup(transport)
            .sslContext(tls).maxInboundMessageSize(16 * 1024 * 1024)
            .intercept(new ClientIdentity(client)).addService(new Service()).build();
        server.start();
    }

    /**
     * Returns the bound loopback endpoint without a transport scheme.
     * @return numeric loopback host and port
     */
    String endpoint()
    {
        if (server == null) throw new IllegalStateException("Runner has not started");
        return "127.0.0.1:" + server.getPort();
    }

    /**
     * Transfers the oldest RPC to the constructing coordinator without executing it.
     * @return the next original ready request, if any
     */
    Optional<Ready> poll()
    {
        Ready ready;
        while ((ready = requests.poll()) != null)
        {
            if (ready.publicationAllowed()) return Optional.of(ready);
            ready.releaseClaim();
        }
        return Optional.empty();
    }

    /**
     * Withdraws future RPCs and fails undiscovered requests without discarding live cleanup owners.
     */
    void stopAccepting()
    {
        accepting.set(false);
        Ready request;
        while ((request = requests.poll()) != null)
        {
            request.fail(new IOException("Bazel invocation stopped accepting ready actions"));
            request.releaseClaim();
        }
    }

    /**
     * Stops the owned transport; native actions must have been collected by the producer first.
     * @throws IOException if the listener cannot be joined
     */
    @Override public void close() throws IOException
    {
        stopAccepting();
        if (server != null) server.shutdownNow();
        if (transport != null) transport.shutdownGracefully(0, 10, TimeUnit.SECONDS);
        try
        {
            if (server != null && !server.awaitTermination(10, TimeUnit.SECONDS))
                throw new IOException("Runner listener termination remains unresolved");
            if (transport != null && !transport.awaitTermination(10, TimeUnit.SECONDS))
                throw new IOException("Runner transport termination remains unresolved");
        }
        catch (InterruptedException cause)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while joining Runner listener", cause);
        }
    }

    /**
     * Validates the protocol's relative pathname without substituting any action argument.
     * @param value protocol pathname
     * @param emptyAllowed whether the empty cwd denotes the input root
     * @return normalized relative path
     * @throws IOException if the pathname can escape or has ambiguous lexical semantics
     */
    static Path relative(String value, boolean emptyAllowed) throws IOException
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
     * Reads one exact pinned leaf certificate encoding.
     * @param path certificate PEM path
     * @return DER bytes
     * @throws IOException if certificate parsing fails
     */
    private static byte[] certificate(Path path) throws IOException
    {
        try (var input = Files.newInputStream(path))
        {
            return CertificateFactory.getInstance("X.509").generateCertificate(input).getEncoded();
        }
        catch (GeneralSecurityException cause) { throw new IOException("Invalid Runner certificate", cause); }
    }

    /**
     * Invocation-private JDK TLS inputs.
     * @param authority trusted invocation CA
     * @param serverCertificate loopback server identity
     * @param serverPrivateKey PKCS8 server key
     * @param workerCertificate exact leaf certificate authorized to enqueue work
     */
    record Credentials(Path authority, Path serverCertificate, Path serverPrivateKey, Path workerCertificate) {}

    /** Exclusive host pathname ownership for original authenticated requests. */
    private static final class RootClaims
    {
        /** Exact original request and its independent worker-visible roots and streams. */
        private final Map<Ready, List<Path>> owners = new LinkedHashMap<>();

        /** Creates an empty registry owned by this exact invocation. */
        private RootClaims() {}

        /**
         * Reserves disjoint worker-visible paths before a request can reach the coordinator.
         * @param ready original authenticated request
         * @throws IOException if any active path overlaps another original request
         */
        synchronized void acquire(Ready ready) throws IOException
        {
            List<Path> paths = new ArrayList<>(List.of(ready.inputRoot(), ready.temporaryDirectory()));
            paths.addAll(ready.streams());
            for (List<Path> existing : owners.values())
                for (Path path : paths)
                    for (Path original : existing)
                        if (path.startsWith(original) || original.startsWith(path))
                            throw new IOException("Worker request overlaps an active original root or stream");
            owners.put(ready, List.copyOf(paths));
        }

        /**
         * Releases only the exact original request's paths after its execution authority is retired.
         * @param ready exact original owner
         */
        synchronized void release(Ready ready) { owners.remove(ready); }
    }

    /** Original RPC and publication authority, independent of any particular native retry. */
    static final class Ready
    {
        /** Original logical Run sequence. */
        private final long sequence;
        /** Original path lease survives cancellation until native cleanup retires it. */
        private final RootClaims claims;
        /** Exact original protocol fields. */
        private final RunRequest request;
        /** Original worker-visible input root. */
        private final Path inputRoot;
        /** Original worker-visible temporary directory. */
        private final Path temporaryDirectory;
        /** Trusted worker build root retained from authenticated admission. */
        private final Path admittedBuildRoot;
        /** Admission directory object identities in build/input/temporary order. */
        private final List<Object> admittedKeys;
        /** Exact absolute action-visible cwd. */
        private final Path directory;
        /** Original worker log destinations, never opened by an action. */
        private final List<Path> streams;
        /** Original RPC observer retained until terminal publication. */
        private final ServerCallStreamObserver<RunResponse> observer;
        /** Cancellation/terminal publication share one synchronization boundary. */
        private boolean terminal;

        /**
         * Retains validated original fields and installs cancellation before queue publication.
         * @param claims original host pathname registry
         * @param buildRoot trusted worker build root
         * @param sequence original unique Run ordinal
         * @param request exact protocol request
         * @param inputRoot absolute original input directory
         * @param temporaryDirectory absolute original temporary directory
         * @param directory absolute original cwd
         * @param streams original stdout and stderr paths
         * @param observer original response authority
         * @throws IOException if admission directory identities cannot be captured
         */
        private Ready(RootClaims claims, Path buildRoot, long sequence, RunRequest request, Path inputRoot, Path temporaryDirectory, Path directory,
            List<Path> streams, ServerCallStreamObserver<RunResponse> observer) throws IOException
        {
            this.claims = claims;
            this.admittedBuildRoot = buildRoot;
            this.admittedKeys = List.of(directoryKey(buildRoot), directoryKey(inputRoot), directoryKey(temporaryDirectory));
            this.sequence = sequence;
            this.request = request;
            this.inputRoot = inputRoot;
            this.temporaryDirectory = temporaryDirectory;
            this.directory = directory;
            this.streams = List.copyOf(streams);
            this.observer = observer;
            observer.setOnCancelHandler(this::cancel);
        }

        /**
         * Opens the original admitted directory objects as a private retry workspace.
         * Trusted worker-owned host ancestry and serialized materialization remain mandatory: these checks
         * detect replacement at acquisition boundaries, while workspace descriptors protect subsequent promotion.
         * @param ownedRoot absent private workspace directory on the original input filesystem
         * @return workspace anchored to the original admitted input and temporary directories
         * @throws IOException if admission objects changed or snapshot acquisition fails
         */
        LinuxBazelWorkspace openWorkspace(Path ownedRoot) throws IOException
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

        /** Withdraws publication while leaving native cleanup with its existing owner. */
        private synchronized void cancel() { terminal = true; }

        /**
         * Returns whether the original RPC still permits successful output promotion.
         * @return true only before cancellation or a terminal response
         */
        synchronized boolean publicationAllowed() { return !terminal && !observer.isCancelled(); }

        /**
         * Publishes an authentic final result after output promotion and whole-attempt cleanup.
         * @param exitCode original ordinary action exit status
         * @return whether the original RPC accepted the terminal result
         */
        synchronized boolean complete(int exitCode)
        {
            if (!publicationAllowed()) { terminal = true; return false; }
            terminal = true;
            try
            {
                observer.onNext(RunResponse.newBuilder().setExitCode(exitCode).build());
                observer.onCompleted();
            }
            finally { releaseClaim(); }
            return true;
        }

        /**
         * Serializes the final authenticated promotion and response against cancellation notification.
         * The publication itself must recheck {@link #publicationAllowed()} during its rollback-capable transaction.
         * @param publication frozen output transaction after native zero and original waiter acknowledgement
         * @param exitCode authentic original ordinary result
         * @return whether publication completed before cancellation
         * @throws IOException if promotion fails; no response is invented
         */
        synchronized boolean publish(Publication publication, int exitCode) throws IOException
        {
            if (!publicationAllowed()) return false;
            publication.commit();
            return complete(exitCode);
        }

        /**
         * Reports a host failure without inventing an action exit or releasing its cleanup lease.
         * @param cause original host failure
         */
        synchronized void fail(Throwable cause)
        {
            if (terminal) return;
            terminal = true;
            if (!observer.isCancelled()) observer.onError(Status.INTERNAL.withDescription("Owned action failed")
                .withCause(cause).asRuntimeException());
        }

        /**
         * Retires the original path lease after native execution, mappings and publication can no longer use it.
         * Cancellation and generic failure alone do not establish this barrier.
         */
        void releaseClaim() { claims.release(this); }

        /**
         * Returns the original invocation-local Run ordinal.
         * @return original invocation-local Run ordinal
         */
        long sequence() { return sequence; }
        /**
         * Returns the immutable original Runner request.
         * @return immutable original Runner protocol request
         */
        RunRequest request() { return request; }
        /**
         * Returns the original worker input root.
         * @return original worker input root
         */
        Path inputRoot() { return inputRoot; }
        /**
         * Returns the original action temporary directory.
         * @return original action temporary directory
         */
        Path temporaryDirectory() { return temporaryDirectory; }
        /**
         * Returns the original absolute action working directory.
         * @return original absolute action cwd
         */
        Path directory() { return directory; }
        /**
         * Returns the original stdout and stderr locations.
         * @return original stdout and stderr locations
         */
        List<Path> streams() { return streams; }
    }

    /** Final output transaction owned by the coordinator, never by an RPC thread. */
    @FunctionalInterface
    interface Publication
    {
        /**
         * Commits authenticated output or rolls back before throwing.
         * @throws IOException if publication authority or filesystem finalization fails
         */
        void commit() throws IOException;
    }

    /** Rejects even another CA-signed client when it is not the exact invocation worker certificate. */
    private static final class ClientIdentity implements ServerInterceptor
    {
        /** Exact authorized leaf DER bytes. */
        private final byte[] expected;

        /**
         * Retains exact worker identity.
         * @param expected authorized DER bytes
         */
        private ClientIdentity(byte[] expected) { this.expected = expected.clone(); }

        @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next)
        {
            SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            try
            {
                if (session != null && MessageDigest.isEqual(expected, session.getPeerCertificates()[0].getEncoded()))
                    return next.startCall(call, headers);
            }
            catch (GeneralSecurityException | javax.net.ssl.SSLPeerUnverifiedException cause)
            {
                call.close(Status.UNAUTHENTICATED.withCause(cause), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            call.close(Status.UNAUTHENTICATED.withDescription("Wrong invocation worker identity"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
    }

    /** Supported pinned Buildbarn service; it grants no native execution authority on RPC threads. */
    private final class Service extends RunnerGrpc.RunnerImplBase
    {
        /** Creates the service backed by the invocation's request queue. */
        private Service() {}

        @Override public void checkReadiness(CheckReadinessRequest request, StreamObserver<Empty> observer)
        {
            try
            {
                if (!accepting.get()) throw new IOException("Runner admission stopped");
                resolve(request.getPath(), true);
                observer.onNext(Empty.getDefaultInstance());
                observer.onCompleted();
            }
            catch (IOException | RuntimeException cause)
            {
                observer.onError(Status.FAILED_PRECONDITION.withCause(cause).asRuntimeException());
            }
        }

        @Override public void run(RunRequest request, StreamObserver<RunResponse> observer)
        {
            try
            {
                if (!accepting.get()) throw new IOException("Runner admission stopped");
                if (request.getArgumentsCount() == 0 || request.getArgumentsList().stream().anyMatch(v -> v.indexOf(0) >= 0)
                    || request.getEnvironmentVariablesMap().entrySet().stream().anyMatch(e ->
                        e.getKey().isEmpty() || e.getKey().contains("=") || e.getKey().indexOf(0) >= 0 || e.getValue().indexOf(0) >= 0))
                    throw new IOException("Invalid native argv or environment");
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
                var ready = new Ready(rootClaims, buildRoot, sequence.getAndIncrement(), request, input, temporary, cwd,
                    List.of(stdout, stderr),
                    (ServerCallStreamObserver<RunResponse>) observer);
                rootClaims.acquire(ready);
                requests.add(ready);
                if (!accepting.get() && requests.remove(ready))
                {
                    ready.fail(new IOException("Runner admission stopped"));
                    ready.releaseClaim();
                }
            }
            catch (IOException | RuntimeException cause)
            {
                observer.onError(Status.INVALID_ARGUMENT.withCause(cause).asRuntimeException());
            }
        }
    }
}
