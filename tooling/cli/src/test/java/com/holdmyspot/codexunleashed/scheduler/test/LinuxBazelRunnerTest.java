package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelWorkIdentity;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelBackend;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelRunner;
import buildbarn.runner.RunnerGrpc;
import buildbarn.runner.RunnerOuterClass.CheckReadinessRequest;
import buildbarn.runner.RunnerOuterClass.RunRequest;
import buildbarn.runner.RunnerOuterClass.RunResponse;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig;
import io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the actual loopback mTLS Runner protocol and immutable original request boundary.
 * Every test issues private credentials and owns its server, channels and filesystem state.
 */
public final class LinuxBazelRunnerTest
{
    /**
     * Skips this class on file systems without POSIX attributes, which its POSIX-only production code requires.
     * @throws org.testng.SkipException if the default file system lacks the POSIX attribute view
     */
    @org.testng.annotations.BeforeClass
    public void requirePosix()
    {
        TestRequirements.requirePosixFileSystem();
    }

    /**
     * Creates independently owned protocol tests.
     */
    public LinuxBazelRunnerTest() {}

    /**
     * Rejects traversal and ambiguous path spellings while preserving the documented empty cwd.
     * @throws IOException if an accepted path cannot be validated
     */
    @Test
    public void preservesExactRelativePathContract() throws IOException
    {
        for (String value : new String[]{"../outside", "a/../b", "/etc/passwd", "a//b", "./a", "a/", ".", ""})
            requireException(IOException.class, () -> LinuxBazelRunner.relative(value, false));
        requireThat(LinuxBazelRunner.relative("build/input", false), "workerInput").isEqualTo(Path.of("build/input"));
        requireThat(LinuxBazelRunner.relative("", true), "rootCwd").isEqualTo(Path.of(""));
    }

    /**
     * Completes backend readiness only after authenticated native-path validation succeeds.
     * @throws Exception if the private real Runner fixture or RPC fails
     */
    @Test
    public void readinessWaitsForAuthenticatedValidNativePath() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            java.util.concurrent.CompletableFuture<Void> readiness = fixture.runner.workerReadiness();
            requireThat(readiness.isDone(), "listenerIsNotWorkerReadiness").isFalse();
            fixture.runner.workerReadiness().complete(null);
            requireThat(readiness.isDone(), "observerCannotCompleteOriginalReadiness").isFalse();
            requireException(StatusRuntimeException.class, () -> RunnerGrpc.newBlockingStub(fixture.client.channel)
                .withDeadlineAfter(20, TimeUnit.SECONDS).checkReadiness(
                    CheckReadinessRequest.newBuilder().setPath("../escape").build()));
            requireThat(readiness.isDone(), "rejectedNativePathIsNotReadiness").isFalse();
            fixture.readiness();
            requireThat(readiness.isDone(), "validAuthenticatedNativePathAcknowledged").isTrue();
        }
    }

    /**
     * A pending cancellation still permits bounded transport cleanup and survives that cleanup.
     * @throws Exception if the authenticated listener cannot start or finish cleanup
     */
    @Test
    public void pendingCancellationStillClosesRunnerAndRestoresInterrupt() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            Thread.currentThread().interrupt();
            try
            {
                fixture.runner.close();
                requireThat(Thread.currentThread().isInterrupted(), "pendingCancellationPreserved").isTrue();
            }
            finally
            {
                // The fixture owns subsequent cleanup; keep this test's cancellation off its worker thread.
                Thread.interrupted();
            }
        }
    }

    /**
     * Real authenticated wire admission retains generated-tool argv, environment and empty cwd exactly.
     * @throws Exception if TLS, filesystem or wire operations fail
     */
    @Test
    public void authenticatedWirePreservesOriginalReadyInputs() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            Files.createDirectory(fixture.input.resolve("generated"));
            Files.writeString(fixture.input.resolve("generated/tool"), "generated-image");
            Files.writeString(fixture.input.resolve("parameters"), "--exact=a/../b");
            List<String> arguments = List.of("./generated/tool", "@parameters", "a/../b", "", " spaced argument ");
            Map<String, String> environment = Map.of("A", "a/../b", "EMPTY", "", "PATH", "/explicit");
            RunRequest request = fixture.request().clearArguments().addAllArguments(arguments)
                .putAllEnvironmentVariables(environment).setWorkingDirectory("").build();
            ListenableFuture<RunResponse> response = fixture.run(request);
            LinuxBazelRunner.Ready ready = fixture.ready();
            requireThat(ready.request().getArgumentsList(), "exactArgv").isEqualTo(arguments);
            requireThat(ready.request().getEnvironmentVariablesMap(), "exactEnvironment").isEqualTo(environment);
            requireThat(ready.directory(), "emptyCwdMeansInputRoot").isEqualTo(fixture.input);
            String original = BazelWorkIdentity.identify(fixture.input, arguments, environment, ready.directory(), "platform");
            Files.writeString(fixture.input.resolve("generated/tool"), "different-image");
            requireThat(BazelWorkIdentity.identify(fixture.input, arguments, environment, ready.directory(),
                "platform").equals(original), "readyGeneratedImageChangeInvalidates").isFalse();
            requireThat(ready.complete(0), "authenticResultAccepted").isTrue();
            requireThat(response.get(20, TimeUnit.SECONDS).getExitCode(), "originalWireResult").isEqualTo(0L);
        }
    }

    /**
     * A successful transaction publishes its authentic status once and releases original path ownership.
     * @throws Exception if fixture setup or authenticated operations fail
     */
    @Test
    public void publicationCommitsOnceAndReleasesClaim() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            ListenableFuture<RunResponse> response = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
            requireThat(ready.publish(commits::incrementAndGet, 23), "publicationAccepted").isTrue();
            requireThat(response.get(20, TimeUnit.SECONDS).getExitCode(), "authenticNonzeroResult").isEqualTo(23L);
            requireThat(ready.publish(commits::incrementAndGet, 99), "terminalPublicationRejected").isFalse();
            requireThat(commits.get(), "oneTransaction").isEqualTo(1);
            ListenableFuture<RunResponse> successor = fixture.run(fixture.request().build());
            requireThat(fixture.ready().complete(0), "claimReleasedAfterResult").isTrue();
            requireThat(successor.get(20, TimeUnit.SECONDS).getExitCode(), "successorResult").isEqualTo(0L);
        }
    }

    /**
     * Host failure withholds publication and preserves the native cleanup lease until explicitly retired.
     * @throws Exception if fixture setup or authenticated operations fail
     */
    @Test
    public void failureRetainsClaimUntilCleanup() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            ListenableFuture<RunResponse> response = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            ready.fail(new IOException("owned native cleanup remains pending"));
            requireThat(failure(response), "hostFailureStatus").isEqualTo(Status.Code.INTERNAL);
            requireThat(ready.publicationAllowed(), "failedPublicationAuthority").isFalse();
            requireThat(failure(fixture.run(fixture.request().build())), "failedClaimStillExclusive")
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
            ready.releaseClaim();
            ListenableFuture<RunResponse> successor = fixture.run(fixture.request().build());
            requireThat(fixture.ready().complete(0), "cleanupRetiredClaim").isTrue();
            requireThat(successor.get(20, TimeUnit.SECONDS).getExitCode(), "successorResult").isEqualTo(0L);
        }
    }

    /**
     * Cancellation during a rollback-capable publication is visible to its final authority check.
     * @throws Exception if fixture setup or authenticated operations fail
     */
    @Test
    public void publicationRechecksCancellationBeforeCommit() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            ListenableFuture<RunResponse> response = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            requireException(IOException.class, () -> ready.publish(() ->
            {
                response.cancel(true);
                await(() -> !ready.publicationAllowed());
                if (!ready.publicationAllowed()) throw new IOException("transaction rolled back after cancellation");
            }, 0));
            requireThat(ready.publicationAllowed(), "cancelledPublicationAuthority").isFalse();
            requireThat(failure(fixture.run(fixture.request().build())), "cancelledClaimStillExclusive")
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
            ready.releaseClaim();
        }
    }

    /**
     * Invalid request roots, stream collisions, environment and cwd links are rejected on the real wire.
     * @throws Exception if fixture setup or wire operations fail
     */
    @Test
    public void wireRejectsPathsLinksAndInvalidLaunchValues() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            Files.createSymbolicLink(fixture.build.resolve("linked-input"), Path.of("slot/input"));
            Files.createSymbolicLink(fixture.input.resolve("linked-cwd"), Path.of("."));
            List<RunRequest> invalid = List.of(
                fixture.request().setInputRootDirectory("../outside").build(),
                fixture.request().setInputRootDirectory("linked-input").build(),
                fixture.request().setWorkingDirectory("linked-cwd").build(),
                fixture.request().setStderrPath("slot/stdout").build(),
                fixture.request().setStdoutPath("slot/input/output").build(),
                fixture.request().setTemporaryDirectory("slot/input").build(),
                fixture.request().clearArguments().build(),
                fixture.request().putEnvironmentVariables("INVALID=NAME", "value").build());
            for (RunRequest request : invalid)
            {
                requireThat(failure(fixture.run(request)), "invalidWireRequest").isEqualTo(Status.Code.INVALID_ARGUMENT);
                requireThat(fixture.runner.poll().isEmpty(), "invalidRequestNotQueued").isTrue();
            }
        }
    }

    /**
     * A second authenticated request cannot acquire another active action's input or temporary root.
     * @throws Exception if fixture or real wire operations fail
     */
    @Test
    public void rejectsOverlappingActiveRoots() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            ListenableFuture<RunResponse> original = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            requireThat(failure(fixture.run(fixture.request().build())), "duplicateOriginalRootRejected")
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
            requireThat(fixture.runner.poll().isEmpty(), "overlapNeverQueued").isTrue();
            requireThat(ready.complete(0), "originalStillOwnsResult").isTrue();
            requireThat(original.get(20, TimeUnit.SECONDS).getExitCode(), "originalResult").isEqualTo(0L);
            ListenableFuture<RunResponse> reused = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready next = fixture.ready();
            requireThat(next.sequence() > ready.sequence(), "rootReusedForNewOriginalIdentity").isTrue();
            next.complete(0);
            requireThat(reused.get(20, TimeUnit.SECONDS).getExitCode(), "reusedRootResult").isEqualTo(0L);
        }
    }

    /**
     * Cancellation revokes publication immediately while the original root claim waits for owned cleanup.
     * @throws Exception if fixture or real wire operations fail
     */
    @Test
    public void cancellationRetainsRootClaimUntilOriginalCleanup() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            ListenableFuture<RunResponse> original = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            original.cancel(true);
            await(() -> !ready.publicationAllowed());
            requireThat(failure(fixture.run(fixture.request().build())), "cancelDidNotInventCleanup")
                .isEqualTo(Status.Code.INVALID_ARGUMENT);
            ready.releaseClaim();
            ListenableFuture<RunResponse> reused = fixture.run(fixture.request().build());
            fixture.ready().complete(0);
            requireThat(reused.get(20, TimeUnit.SECONDS).getExitCode(), "cleanupReleasedOriginalRoot").isEqualTo(0L);
        }
    }

    /**
     * Another clientAuth leaf signed by the same trusted CA cannot act as the exact authorized worker.
     * @throws Exception if certificate issuance or wire operations fail
     */
    @Test
    public void rejectsDifferentValidClientLeafFromSameAuthority() throws Exception
    {
        try (Fixture fixture = Fixture.open())
        {
            fixture.readiness();
            List<Path> wrong = TestCredentials.otherWorker(fixture.credentials);
            try (Client client = fixture.client(wrong.getFirst(), wrong.getLast()))
            {
                StatusRuntimeException failure = requireException(StatusRuntimeException.class,
                    () -> RunnerGrpc.newBlockingStub(client.channel).checkReadiness(
                        CheckReadinessRequest.newBuilder().setPath("slot").build()));
                requireThat(failure.getStatus().getCode(), "exactLeafRejected").isEqualTo(Status.Code.UNAUTHENTICATED);
                requireThat(failure.getStatus().getDescription(), "reachedExactLeafValidation")
                    .isEqualTo("Wrong invocation worker identity");
            }
            requireThat(fixture.runner.poll().isEmpty(), "wrongWorkerNeverQueued").isTrue();
        }
    }

    /**
     * Obtains the application-level status from a completed rejected wire request.
     * @param response original future
     * @return actual gRPC rejection status
     * @throws InterruptedException if waiting is interrupted
     * @throws TimeoutException if the wire boundary does not return
     */
    static Status.Code failure(ListenableFuture<RunResponse> response) throws InterruptedException, TimeoutException
    {
        try
        {
            response.get(20, TimeUnit.SECONDS);
            throw new IllegalStateException("Request unexpectedly returned an action result");
        }
        catch (ExecutionException rejected) { return Status.fromThrowable(rejected.getCause()).getCode(); }
    }

    /**
     * Waits for a transport event without asserting elapsed-time performance.
     * @param condition event predicate
     * @throws IOException if the event does not arrive within the fixture's escape bound
     */
    static void await(BooleanSupplier condition) throws IOException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean())
        {
            if (System.nanoTime() >= deadline) throw new IOException("Wire fixture event did not arrive");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /**
     * Owns one actual channel and joins its transport on cleanup.
     */
    static final class Client implements AutoCloseable
    {
        /** Actual per-test mTLS channel. */
        final ManagedChannel channel;

        /**
         * Transfers channel cleanup ownership.
         * @param channel newly created channel
         */
        private Client(ManagedChannel channel) { this.channel = channel; }

        @Override public void close() throws IOException
        {
            channel.shutdownNow();
            try
            {
                if (!channel.awaitTermination(20, TimeUnit.SECONDS))
                    throw new IOException("Wire fixture channel failed to terminate");
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Wire fixture channel cleanup interrupted", interrupted);
            }
        }
    }

    /**
     * Owns private invocation credentials, real Runner listener and worker-visible directory fixtures.
     */
    static final class Fixture implements AutoCloseable
    {
        /** Fresh root removed only after all channels and the server terminate. */
        final Path root;
        /** Trusted worker build root used by real protocol validation. */
        final Path build;
        /** Worker-owned real input directory whose visible bytes assertions inspect. */
        final Path input;
        /** Worker-owned temporary directory separate from its input root. */
        final Path temporary;
        /** Private server/worker credentials copied from fixed test resources. */
        final LinuxBazelBackend.Credentials credentials;
        /** Actual per-test protocol listener. */
        final LinuxBazelRunner runner;
        /** Exact authorized client channel. */
        private final Client client;

        /**
         * Retains successfully acquired fixture resources.
         * @param root fresh private fixture root
         * @param build trusted worker root
         * @param input worker input directory
         * @param temporary worker temporary directory
         * @param credentials production-issued private identities
         * @param runner started real listener
         * @param client exact authorized channel
         */
        private Fixture(Path root, Path build, Path input, Path temporary,
            LinuxBazelBackend.Credentials credentials, LinuxBazelRunner runner, Client client)
        {
            this.root = root;
            this.build = build;
            this.input = input;
            this.temporary = temporary;
            this.credentials = credentials;
            this.runner = runner;
            this.client = client;
        }

        /**
         * Creates a complete real wire fixture with immediate acquisition-failure cleanup.
         * @return independently owned fixture
         * @throws IOException if credentials, directories or server startup fails
         */
        static Fixture open() throws IOException
        {
            Path root = Files.createTempDirectory("bazel-runner-wire-");
            LinuxBazelRunner runner = null;
            try
            {
                Path build = Files.createDirectory(root.resolve("worker-builds"));
                Path slot = Files.createDirectory(build.resolve("slot"));
                Path input = Files.createDirectory(slot.resolve("input"));
                Path temporary = Files.createDirectory(slot.resolve("temporary"));
                Files.createDirectory(slot.resolve("logs"));
                LinuxBazelBackend.Credentials credentials = TestCredentials.copy(root.resolve("credentials"));
                runner = new LinuxBazelRunner(build, new LinuxBazelRunner.Credentials(credentials.certificateAuthority(),
                    credentials.serverCertificate(), credentials.serverKey(), credentials.workerCertificate()));
                runner.start();
                Client client = connect(runner.endpoint(), credentials, credentials.workerCertificate(), credentials.workerKey());
                return new Fixture(root, build, input, temporary, credentials, runner, client);
            }
            catch (IOException | RuntimeException | Error failure)
            {
                if (runner != null)
                    try { runner.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                try { remove(root); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Proves real mTLS readiness before an action request or deliberate deadline begins.
         */
        void readiness()
        {
            RunnerGrpc.newBlockingStub(client.channel).withDeadlineAfter(20, TimeUnit.SECONDS)
                .checkReadiness(CheckReadinessRequest.newBuilder().setPath("slot").build());
        }

        /**
         * Supplies valid original paths and a generated command placeholder for one independently owned request.
         * @return mutable protocol builder for the caller's scenario
         */
        RunRequest.Builder request()
        {
            return RunRequest.newBuilder().addArguments("./generated-tool").setInputRootDirectory("slot/input")
                .setTemporaryDirectory("slot/temporary").setServerLogsDirectory("slot/logs")
                .setStdoutPath("slot/stdout").setStderrPath("slot/stderr");
        }

        /**
         * Sends an original asynchronous Run over the exact authorized client channel.
         * @param request exact protocol request
         * @return future retained through retries/cancellation until the real response or transport outcome
         */
        ListenableFuture<RunResponse> run(RunRequest request)
        {
            return RunnerGrpc.newFutureStub(client.channel).run(request);
        }

        /**
         * Sends an original Run with a real transport deadline after readiness has established its channel.
         * @param request exact protocol request
         * @return original future whose deadline may expire before native cleanup is allowed to finish
         */
        ListenableFuture<RunResponse> deadlineRun(RunRequest request)
        {
            return RunnerGrpc.newFutureStub(client.channel).withDeadlineAfter(500, TimeUnit.MILLISECONDS).run(request);
        }

        /**
         * Transfers the actual dynamically enqueued original RPC to the scenario's coordinator thread.
         * @return next original Ready object
         * @throws IOException if admission never arrives
         */
        LinuxBazelRunner.Ready ready() throws IOException
        {
            LinuxBazelRunner.Ready[] found = new LinuxBazelRunner.Ready[1];
            await(() ->
            {
                found[0] = runner.poll().orElse(null);
                return found[0] != null;
            });
            return found[0];
        }

        /**
         * Opens an additional same-CA channel with caller-selected leaf material.
         * @param certificate clientAuth leaf certificate
         * @param key matching PKCS8 key
         * @return owned actual mTLS channel
         * @throws IOException if TLS setup fails
         */
        Client client(Path certificate, Path key) throws IOException
        {
            return connect(runner.endpoint(), credentials, certificate, key);
        }

        /**
         * Connects an actual channel using the fixture CA and one supplied client leaf.
         * @param endpoint actual listener address
         * @param credentials invocation CA and server identity
         * @param certificate selected client certificate
         * @param key matching client PKCS8 key
         * @return owned actual channel
         * @throws IOException if client TLS configuration fails
         */
        private static Client connect(String endpoint, LinuxBazelBackend.Credentials credentials,
            Path certificate, Path key) throws IOException
        {
            return new Client(NettyChannelBuilder.forTarget(endpoint).overrideAuthority("localhost")
                .sslContext(GrpcSslContexts.forClient().sslProvider(SslProvider.JDK)
                    .applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT, ApplicationProtocolNames.HTTP_2))
                    .trustManager(credentials.certificateAuthority().toFile()).keyManager(certificate.toFile(), key.toFile())
                    .build()).build());
        }

        @Override public void close() throws IOException
        {
            try { client.close(); }
            finally
            {
                try { runner.close(); }
                finally { remove(root); }
            }
        }

        /**
         * Deletes only this fixture's private tree after transport cleanup.
         * @param root owned fixture root
         * @throws IOException if fixture cleanup fails
         */
        private static void remove(Path root) throws IOException
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
