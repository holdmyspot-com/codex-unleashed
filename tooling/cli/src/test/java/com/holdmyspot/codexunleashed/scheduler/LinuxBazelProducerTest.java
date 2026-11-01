package com.holdmyspot.codexunleashed.scheduler;

import buildbarn.runner.RunnerOuterClass.RunResponse;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the producer-facing original Runner RPC and real workspace publication contract.
 * Native outcomes are controlled at the explicitly documented zero/unmap boundary; these tests do not run or prove
 * LinuxBazelProducer.run, the native action owner, allocator denial policy or release consumer acceptance.
 */
public final class LinuxBazelProducerTest
{
    /**
     * Creates independent connected publication cases.
     */
    public LinuxBazelProducerTest() {}

    /**
     * Replacing the queued original root cannot grant a new root publication authority.
     * @throws Exception if the real RPC, fixture acquisition or cleanup fails
     */
    @Test
    public void queuedOriginalRootReplacementIsRejected() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            ListenableFuture<RunResponse> result = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            Files.move(fixture.input, fixture.input.resolveSibling("detached-original"));
            Files.createDirectory(fixture.input);
            boolean rejected = false;
            try (LinuxBazelWorkspace ignored = ready.openWorkspace(fixture.root.resolve("attempts")))
            {
                ignored.immutableSnapshot();
            }
            catch (IOException expected) { rejected = true; }
            requireThat(rejected, "replacementRootRejected").isTrue();
            requireThat(Files.exists(fixture.root.resolve("attempts")), "rejectedAcquisitionUncreated").isFalse();
            ready.fail(new IOException("Original worker root replaced"));
            requireThat(LinuxBazelRunnerTest.failure(result), "originalRpcRejected").isEqualTo(Status.Code.INTERNAL);
        }
    }

    /**
     * One original wire Run remains pending through discarded failed bytes and a fresh successful attempt.
     * @throws Exception if the real transport, workspace or fixture cleanup fails
     */
    @Test
    public void oneOriginalRunSpansDiscardRetryAndSuccessfulPublication() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            Files.writeString(fixture.input.resolve("generated-tool"), "generated-image");
            Files.writeString(fixture.input.resolve("source"), "pristine-source");
            ListenableFuture<RunResponse> result = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            long logicalSequence = ready.sequence();
            try (LinuxBazelWorkspace workspace = ready.openWorkspace(
                fixture.root.resolve("attempts")))
            {
                LinuxBazelWorkspace.Attempt failed = workspace.begin(10);
                Files.writeString(failed.upper().resolve("failed-output"), "partial-before-denial");
                // The controlled native boundary now reports complete failed-attempt zero and unmapping.
                failed.discard();
                requireThat(result.isDone(), "originalRpcPendingThroughDiscard").isFalse();
                requireThat(Files.exists(fixture.input.resolve("failed-output")), "failedOutputExcluded").isFalse();
                LinuxBazelWorkspace.Attempt successful = workspace.begin(11);
                requireThat(Files.exists(successful.upper().resolve("failed-output")), "retryUpperIsFresh").isFalse();
                Path merged = Files.createDirectory(fixture.root.resolve("successful-view"));
                Files.writeString(merged.resolve("generated-tool"), "generated-image");
                Files.writeString(merged.resolve("source"), "pristine-source");
                Files.writeString(merged.resolve("output"), "accepted-result");
                successful.bindMerged(merged);
                // The controlled native boundary now reports successful whole-zero and closed hooks.
                successful.freeze();
                requireThat(result.isDone(), "originalRpcPendingUntilPublication").isFalse();
                requireThat(ready.sequence(), "logicalRunSequenceUnchanged").isEqualTo(logicalSequence);
                requireThat(ready.publish(() -> successful.promote(ready::publicationAllowed), 0), "originalPublicationAccepted")
                    .isTrue();
                requireThat(result.get(20, TimeUnit.SECONDS).getExitCode(), "authenticOriginalResult").isEqualTo(0L);
                requireThat(Files.readString(fixture.input.resolve("output")), "workerVisibleAcceptedBytes")
                    .isEqualTo("accepted-result");
                requireThat(Files.exists(fixture.input.resolve("failed-output")), "failedAttemptBytesAbsent").isFalse();
                requireThat(fixture.runner.poll().isEmpty(), "noReplacementRunRpc").isTrue();
            }
        }
    }

    /**
     * An authentic ordinary nonzero result returns unchanged and publishes no private generated output.
     * @throws Exception if transport, workspace or fixture cleanup fails
     */
    @Test
    public void authenticNonzeroReturnsWithoutGeneratedOutput() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            Files.writeString(fixture.input.resolve("source"), "pristine");
            ListenableFuture<RunResponse> result = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            try (LinuxBazelWorkspace workspace = ready.openWorkspace(
                fixture.root.resolve("attempts")))
            {
                LinuxBazelWorkspace.Attempt failed = workspace.begin(1);
                Files.writeString(failed.upper().resolve("output"), "failed-result");
                // The controlled native boundary reports authentic exit 17, whole-zero and complete unmapping.
                failed.discard();
                requireThat(ready.complete(17), "authenticFailureAccepted").isTrue();
                requireThat(result.get(20, TimeUnit.SECONDS).getExitCode(), "originalFailureCode").isEqualTo(17L);
                requireThat(Files.exists(fixture.input.resolve("output")), "nonzeroOutputAbsent").isFalse();
                requireThat(Files.readString(fixture.input.resolve("source")), "sourceUnchanged").isEqualTo("pristine");
                requireThat(fixture.runner.poll().isEmpty(), "ordinaryFailureDoesNotCreateAnotherRun").isTrue();
            }
        }
    }

    /**
     * A real client deadline withdraws publication before the separate private attempt cleanup lease finishes.
     * @throws Exception if transport, workspace or fixture cleanup fails
     */
    @Test
    public void deadlineReturnsBeforeOwnedCleanupAndRejectsLateSuccess() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            ListenableFuture<RunResponse> result = fixture.deadlineRun(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            try (LinuxBazelWorkspace workspace = ready.openWorkspace(
                fixture.root.resolve("attempts")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path retained = Files.writeString(attempt.upper().resolve("output"), "private-running-bytes");
                requireThat(LinuxBazelRunnerTest.failure(result), "realClientDeadline").isEqualTo(Status.Code.DEADLINE_EXCEEDED);
                LinuxBazelRunnerTest.await(() -> !ready.publicationAllowed());
                requireThat(Files.exists(retained), "cleanupLeaseRetainedAfterRpcDeadline").isTrue();
                requireThat(ready.publish(() ->
                {
                    throw new IOException("Cancelled request must not invoke publication");
                }, 0), "lateSuccessRejected").isFalse();
                requireThat(Files.exists(fixture.input.resolve("output")), "lateOutputAbsent").isFalse();
                // Only now does the controlled native boundary report whole-zero and completed unmapping.
                attempt.discard();
                requireThat(Files.exists(retained), "ownedStorageRemovedAfterBarrier").isFalse();
            }
        }
    }

    /**
     * Explicit client cancellation revokes a live original Run while retaining private cleanup ownership.
     * @throws Exception if transport, workspace or fixture cleanup fails
     */
    @Test
    public void clientCancellationRetainsCleanupAndCannotPromote() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            ListenableFuture<RunResponse> result = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            try (LinuxBazelWorkspace workspace = ready.openWorkspace(
                fixture.root.resolve("attempts")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path privateOutput = Files.writeString(attempt.upper().resolve("output"), "private");
                requireThat(result.cancel(true), "clientCancelAccepted").isTrue();
                LinuxBazelRunnerTest.await(() -> !ready.publicationAllowed());
                requireThat(Files.exists(privateOutput), "cancelRetainsCleanupLease").isTrue();
                requireThat(ready.complete(0), "cancelledOriginalCannotReturnSuccess").isFalse();
                requireThat(Files.exists(fixture.input.resolve("output")), "cancelOutputAbsent").isFalse();
                // The controlled native boundary reports whole-zero and unmapping after cancellation.
                attempt.discard();
            }
        }
    }

    /**
     * Real transport cancellation during the rollback-capable publication restores original worker bytes.
     * @throws Exception if transport, workspace or fixture cleanup fails
     */
    @Test
    public void cancellationDuringPublicationRollsBackWorkerTree() throws Exception
    {
        try (LinuxBazelRunnerTest.Fixture fixture = LinuxBazelRunnerTest.Fixture.open())
        {
            fixture.readiness();
            Files.writeString(fixture.input.resolve("a"), "original-a");
            Files.writeString(fixture.input.resolve("b"), "original-b");
            ListenableFuture<RunResponse> result = fixture.run(fixture.request().build());
            LinuxBazelRunner.Ready ready = fixture.ready();
            try (LinuxBazelWorkspace workspace = ready.openWorkspace(
                fixture.root.resolve("attempts")))
            {
                LinuxBazelWorkspace.Attempt attempt = workspace.begin(1);
                Path merged = Files.createDirectory(fixture.root.resolve("successful-view"));
                Files.writeString(merged.resolve("a"), "changed-a");
                Files.writeString(merged.resolve("c"), "new-c");
                attempt.bindMerged(merged);
                // The controlled native boundary reports whole-zero and closed hooks before staging/unmapping.
                attempt.freeze();
                AtomicInteger checks = new AtomicInteger();
                IOException failure = null;
                try
                {
                    ready.publish(() -> attempt.promote(() ->
                    {
                        if (checks.incrementAndGet() == 3)
                        {
                            result.cancel(true);
                            try { LinuxBazelRunnerTest.await(() -> !ready.publicationAllowed()); }
                            catch (IOException missingCancel) { throw new IllegalStateException(missingCancel); }
                        }
                        return ready.publicationAllowed();
                    }), 0);
                }
                catch (IOException expected) { failure = expected; }
                requireThat(failure != null, "wireCancellationRejectsPromotion").isTrue();
                requireThat(Files.readString(fixture.input.resolve("a")), "changedOutputRolledBack").isEqualTo("original-a");
                requireThat(Files.readString(fixture.input.resolve("b")), "deletedOutputRolledBack").isEqualTo("original-b");
                requireThat(Files.exists(fixture.input.resolve("c")), "newOutputRolledBack").isFalse();
                requireThat(result.isCancelled(), "originalClientCancelled").isTrue();
            }
        }
    }
    /**
     * Durable production records retain unknown estimates and the original native signal/status payload.
     * @throws Exception if independent evidence acquisition or cleanup fails
     */
    @Test
    public void productionAuditPreservesUnknownEstimateAndOriginalOutcome() throws Exception
    {
        Path root = Files.createTempDirectory("bazel-production-audit-");
        try (var scope = new com.holdmyspot.codexunleashed.tooling.DefaultToolingScope())
        {
            Path path = root.resolve("events.jsonl");
            try (var audit = new LinuxBazelProducer.Audit(scope, path))
            {
                audit.record("submission", new SchedulerCoordinator.Submission("generated-tool", null));
                audit.record("accepted", new JobQueue.Completion<>("generated-tool", 7,
                    new JobQueue.Outcome<>(new LinuxBazelControl.Result(true, 9), true)));
                audit.verify();
                var lines = Files.readAllLines(path);
                requireThat(lines.size(), "eventsFlushedWhileOwnerOpen").isEqualTo(2);
                var first = scope.getJsonMapper().readTree(lines.get(0));
                var second = scope.getJsonMapper().readTree(lines.get(1));
                requireThat(first.path("payload").path("peakBytes").isNull(), "unknownEstimatePreserved").isTrue();
                requireThat(second.path("sequence").asLong(), "orderedEventSequence").isEqualTo(1L);
                requireThat(second.path("payload").path("aborted").asBoolean(), "abortionPreserved").isTrue();
                requireThat(second.path("payload").path("result").path("status").asInt(), "originalSignalPreserved")
                    .isEqualTo(9);
            }
        }
        finally { deleteAudit(root); }
    }

    /**
     * Lost evidence aborts on its first append but permits subsequent physical cleanup to proceed independently.
     * @throws Exception if independent evidence acquisition or cleanup fails
     */
    @Test
    public void productionAuditFailureRemainsFatalWithoutRepeatedCleanupFailure() throws Exception
    {
        Path root = Files.createTempDirectory("bazel-production-audit-failure-");
        try (var scope = new com.holdmyspot.codexunleashed.tooling.DefaultToolingScope())
        {
            var audit = new LinuxBazelProducer.Audit(scope, root.resolve("events.jsonl"));
            audit.close();
            IOException original = null;
            try { audit.record("original", java.util.Map.of()); }
            catch (IOException expected) { original = expected; }
            requireThat(original != null, "firstAppendAbortsScheduling").isTrue();
            audit.record("physicalCleanup", java.util.Map.of("complete", true));
            IOException retained = null;
            try { audit.verify(); }
            catch (IOException expected) { retained = expected; }
            requireThat(retained == original, "firstEvidenceFailureRetainedAfterCleanup").isTrue();
        }
        finally { deleteAudit(root); }
    }

    /**
     * Removes one per-test evidence directory after all streams and scopes close.
     * @param root exclusive independent fixture root
     * @throws IOException if cleanup fails
     */
    private static void deleteAudit(Path root) throws IOException
    {
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

}
