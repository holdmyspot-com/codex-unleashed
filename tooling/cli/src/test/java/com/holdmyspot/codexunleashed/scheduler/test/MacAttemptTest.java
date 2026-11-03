package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.AllocationMessages;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.MacAttempt;
import com.holdmyspot.codexunleashed.scheduler.MacAttempts;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises queue lifecycle decisions with controlled native execution observations.
 * Backend terminal outcomes model the MacWorkload complete barrier; native platform tests prove that barrier itself.
 */
public final class MacAttemptTest
{
    /** Creates stateless tests whose native boundary observations belong to each method. */
    public MacAttemptTest() {}

    /**
     * Keeps exact decoded native reports observable after native backend closure without pumping or granting.
     * @throws IOException if controlled native cleanup fails
     */
    @Test public void decodedReportsSurviveBackendClosure() throws IOException
    {
        var backend = new NativeBoundary();
        var denial = new AllocationHook.Denial(java.util.UUID.randomUUID(), 3216,
            new AllocationMessages.Blocked(new AllocationWaits.Wait(2, 3),
                java.math.BigInteger.valueOf(3262), java.math.BigInteger.valueOf(268435456), 12));
        backend.denials.add(denial);
        var attempt = new MacAttempt(backend, null, "compiler", 7);
        List<AllocationHook.Denial> snapshot = attempt.denials();
        requireThat(snapshot.getFirst() == denial, "sameDecodedRecordWithoutReconstruction").isTrue();
        requireException(UnsupportedOperationException.class, snapshot::clear);
        attempt.start(); attempt.close();
        requireThat(attempt.denials(), "reportSurvivesOriginalNativeClosure").isEqualTo(snapshot);
        requireThat(backend.resumes, "inspectionDoesNotGrant").isEqualTo(0);
    }

    /**
     * Keeps an already reported execution fault from masquerading as failed ownership cleanup.
     * @throws IOException if independently controlled cleanup fails
     */
    @Test public void completedCleanupDoesNotReplayExecutionFailure() throws IOException
    {
        MacAttempt.Fault fault = new MacAttempt.Fault("compiler", 3);
        IOException attach = new IOException("Frida get_thread_state protection failure");
        IOException original = fault.failed(attach);
        requireThat(requireException(IOException.class, fault::requireCertain) == original,
            "originalExecutionFaultRemainsReported").isTrue();
        List<String> cleanup = new ArrayList<>();
        fault.cleanup(() -> cleanup.add("terminal-instrumentation-native-tokens-registry-scratch"));
        requireThat(cleanup.size(), "allRequiredCleanupCompletedOnce").isEqualTo(1, "expected");
        requireThat(requireException(IOException.class, fault::requireCertain) == original,
            "successfulCleanupDoesNotEraseOriginalExecutionFault").isTrue();
        requireThat(original.getCause() == attach, "exactAttachFailureRetained").isTrue();
        requireThat(original.getSuppressed().length, "noFabricatedCleanupFailure").isEqualTo(0, "expected");
    }

    /** Preserves independent cleanup failure without certifying collection or losing the original attach fault. */
    @Test public void incompleteCleanupRetainsOriginalFaultAndIndependentEvidence()
    {
        MacAttempt.Fault fault = new MacAttempt.Fault("compiler", 3);
        IOException original = fault.failed(new IOException("Frida attachment failed"));
        IOException cleanup = new IOException("native instrumentation remains owned");
        IOException actual = requireException(IOException.class, () -> fault.cleanup(() -> { throw cleanup; }));
        requireThat(actual == original, "originalExecutionDiagnosticPreserved").isTrue();
        requireThat(List.of(actual.getSuppressed()), "independentUnresolvedCleanupEvidence").
            isEqualTo(List.of(cleanup), "expected");
    }

    /**
     * Consumes the start opportunity before an external acquisition fails.
     * @throws IOException if cleanup fails
     */
    @Test public void failedStartCannotReplayAcquisition() throws IOException
    {
        NativeBoundary backend = new NativeBoundary();
        backend.startFailure = new IOException("uncertain acquisition");
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 0);
        requireException(IOException.class, attempt::start);
        requireException(IllegalStateException.class, attempt::start);
        requireThat(backend.starts, "singleNativeAcquisitionOpportunity").isEqualTo(1, "expected");
        attempt.close();
    }

    /**
     * Prevents queue continuation from reentering a native start callback.
     * @throws IOException if the outer start or cleanup fails
     */
    @Test public void startCallbackCannotReenterResume() throws IOException
    {
        NativeBoundary backend = new NativeBoundary();
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 0);
        backend.startCallback = () -> requireException(IllegalStateException.class, () -> attempt.resume(0));
        attempt.start();
        requireThat(backend.resumes, "noPermissionDuringNativeStart").isEqualTo(0, "expected");
        attempt.close();
    }

    /**
     * Marks a genuinely collected root result aborted after queue cancellation and denies subsequent grants.
     * @throws IOException if native boundary control fails
     */
    @Test public void cancellationWithdrawsGrantsAndMarksCollectedResultAborted() throws IOException
    {
        NativeBoundary backend = new NativeBoundary();
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 0);
        attempt.start();
        attempt.terminate();
        attempt.terminate();
        requireException(IllegalStateException.class, () -> attempt.resume(0));
        requireThat(attempt.poll().isEmpty(), "noOutcomeBeforeNativeTerminalBarrier").isTrue();
        backend.terminal = new JobQueue.Outcome<>(23, false);
        JobQueue.Outcome<Integer> result = attempt.poll().orElseThrow();
        requireThat(result.aborted(), "queueCancellationMarksSupersededAttempt").isTrue();
        requireThat(result.result(), "genuineCollectedRootStatusPreserved").isEqualTo(23, "expected");
        requireThat(backend.terminations, "oneCancellationOpportunity").isEqualTo(1, "expected");
        attempt.close();
    }

    /**
     * Accepts a compiler that exited successfully before the queue's termination request stopped it.
     * @throws IOException if native boundary control fails
     */
    @Test public void successAfterTerminationRequestIsAccepted() throws IOException
    {
        NativeBoundary backend = new NativeBoundary();
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 0);
        attempt.start();
        attempt.terminate();
        backend.terminal = new JobQueue.Outcome<>(0, true);
        JobQueue.Outcome<Integer> result = attempt.poll().orElseThrow();
        requireThat(result.aborted(), "completedCompilerIsNotRetried").isFalse();
        requireThat(result.result(), "successfulRootStatus").isEqualTo(0, "expected");
        attempt.close();
    }

    /**
     * Disables further backend controls after successful cleanup.
     * @throws IOException if native boundary control fails
     */
    @Test public void successfulCleanupClosesOnceAndWithdrawsPolling() throws IOException
    {
        NativeBoundary backend = new NativeBoundary();
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 7);
        attempt.start();
        attempt.close();
        attempt.close();
        requireThat(attempt.poll().isEmpty(), "closedAttemptCannotPublishAnotherOutcome").isTrue();
        requireException(IllegalStateException.class, () -> attempt.resume(0));
        requireThat(backend.closes, "oneNativeCleanup").isEqualTo(1, "expected");
    }

    /**
     * Publishes original authority only after the execution boundary's complete successful cleanup.
     * @throws IOException if cleanup fails
     */
    @Test public void retirementPublicationRequiresSuccessfulCleanup() throws IOException
    {
        MacAttempts.Retirements history = new MacAttempts.Retirements();
        NativeBoundary backend = new NativeBoundary();
        backend.collected = collected(91, false);
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 7, history);
        attempt.start();
        requireThat(history.snapshot().isEmpty(), "authorityAloneDoesNotPublishRetirement").isTrue();
        attempt.close();
        List<MacAttempts.Retirement> receipts = history.snapshot();
        requireThat(receipts.size(), "oneRetirementAfterSuccessfulCleanup").isEqualTo(1, "expected");
        MacAttempts.Retirement receipt = receipts.getFirst();
        requireThat(receipt.name(), "authenticatedLogicalAction").isEqualTo("compiler", "expected");
        requireThat(receipt.attemptSequence(), "exactNativeAttemptOrdinal").isEqualTo(7L, "expected");
        requireThat(receipt.rootAuthority(), "allOriginalNativeFieldsPreserved").isEqualTo(
            backend.collected.rootAuthority(), "expected");
        requireThat(receipt.result(), "genuineOriginalStatusPreserved").isEqualTo(29, "expected");
        requireThat(receipt.aborted(), "normalCompletionRemainsSuccessful").isFalse();
        attempt.close();
        requireThat(history.snapshot().size(), "cleanupCannotPublishTwice").isEqualTo(1, "expected");
        requireException(UnsupportedOperationException.class, () -> receipts.clear());
        requireException(UnsupportedOperationException.class, () -> receipt.rootAuthority().clear());
    }

    /**
     * Withholds a retirement receipt when complete external cleanup fails despite available authority.
     * @throws IOException if the initial acquisition fails
     */
    @Test public void failedCleanupCannotPublishRetirement() throws IOException
    {
        MacAttempts.Retirements history = new MacAttempts.Retirements();
        NativeBoundary backend = new NativeBoundary();
        backend.collected = collected(92, true);
        backend.closeFailure = new IOException("native retirement remains unresolved");
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 1, history);
        attempt.start();
        requireException(IOException.class, attempt::close);
        requireThat(history.snapshot().isEmpty(), "failedNativeCleanupProducesNoReceipt").isTrue();
        backend.closeFailure = null;
        attempt.close();
        requireThat(history.snapshot().isEmpty(), "laterCleanupCannotCertifyEarlierUncertainRetirement").isTrue();
    }

    /**
     * Keeps partial acquisition cleanup separate from complete native workload retirement.
     * @throws IOException if partial resource cleanup fails
     */
    @Test public void partialAcquisitionCannotFabricateRetirement() throws IOException
    {
        MacAttempts.Retirements history = new MacAttempts.Retirements();
        NativeBoundary backend = new NativeBoundary();
        backend.startFailure = new IOException("partial acquisition");
        MacAttempt attempt = new MacAttempt(backend, null, "compiler", 2, history);
        requireException(IOException.class, attempt::start);
        attempt.close();
        requireThat(history.snapshot().isEmpty(), "partialResourcesDoNotEstablishWorkloadCompletion").isTrue();
    }

    /**
     * Orders retained receipts by actual successful cleanup rather than the queue's launch ordinal.
     * @throws IOException if native boundary cleanup fails
     */
    @Test public void retirementHistoryRetainsActualCleanupOrder() throws IOException
    {
        MacAttempts.Retirements history = new MacAttempts.Retirements();
        NativeBoundary older = new NativeBoundary();
        older.collected = collected(93, false);
        NativeBoundary newer = new NativeBoundary();
        newer.collected = collected(94, true);
        MacAttempt first = new MacAttempt(older, null, "older", 3, history);
        MacAttempt second = new MacAttempt(newer, null, "newer", 4, history);
        first.start();
        second.start();
        second.terminate();
        second.close();
        first.close();
        List<MacAttempts.Retirement> receipts = history.snapshot();
        requireThat(receipts.stream().map(MacAttempts.Retirement::name).toList(), "actualSuccessfulRetirementOrder").
            isEqualTo(List.of("newer", "older"), "expected");
        requireThat(receipts.stream().map(MacAttempts.Retirement::retirementSequence).toList(), "consecutivePublicationOrder").
            isEqualTo(List.of(0L, 1L), "expected");
        requireThat(receipts.getFirst().aborted(), "supersededAttemptRemainsAborted").isTrue();
    }

    /**
     * Closes pre-bootstrap native ownership without asking an absent root for cancellation or a result.
     * @throws IOException if the safe pre-bootstrap destruction fails
     */
    @Test public void partialOwnerBeforeRootUsesNativeDestructionOnly() throws IOException
    {
        List<String> calls = new ArrayList<>();
        MacAttempt.PartialOwner owner = new MacAttempt.PartialOwner()
        {
            @Override public void cancel() throws IOException
            {
                calls.add("cancel");
                throw new IOException("mw_cancel rejects owner without an acquired root");
            }
            @Override public OptionalInt terminalResult() throws IOException
            {
                calls.add("terminal");
                throw new IOException("No original root result exists");
            }
            @Override public void close() { calls.add("close"); }
        };
        MacAttempt.collectPartial(false, owner, Duration.ofSeconds(1));
        requireThat(calls, "preBootstrapOwnerDestructionWithoutRootOperations").isEqualTo(List.of("close"), "expected");
    }

    /**
     * Preserves uncertain pre-bootstrap native destruction without canceling, inventing a result or replaying close.
     */
    @Test public void uncertainPreBootstrapDestructionIsNotReplayed()
    {
        List<String> calls = new ArrayList<>();
        IOException failure = new IOException("native bootstrap state remains uncertain");
        MacAttempt.PartialOwner owner = new MacAttempt.PartialOwner()
        {
            @Override public void cancel() { calls.add("cancel"); }
            @Override public OptionalInt terminalResult() { calls.add("terminal"); return OptionalInt.empty(); }
            @Override public void close() throws IOException { calls.add("close"); throw failure; }
        };
        IOException actual = requireException(IOException.class,
            () -> MacAttempt.collectPartial(false, owner, Duration.ofSeconds(1)));
        requireThat(actual == failure, "originalUncertainNativeDestructionFailure").isTrue();
        requireThat(calls, "oneDestructionOpportunityWithoutRootOperations").isEqualTo(List.of("close"), "expected");
    }

    /**
     * Retains cancellation and complete native terminal collection after root acquisition succeeded.
     * @throws IOException if controlled native collection fails
     */
    @Test public void partialOwnerAfterRootRequiresTerminalCollection() throws IOException
    {
        List<String> calls = new ArrayList<>();
        MacAttempt.PartialOwner owner = new MacAttempt.PartialOwner()
        {
            @Override public void cancel() { calls.add("cancel"); }
            @Override public OptionalInt terminalResult()
            {
                calls.add("terminal");
                return OptionalInt.of(37);
            }
            @Override public void close() { calls.add("close"); }
        };
        MacAttempt.collectPartial(true, owner, Duration.ofSeconds(1));
        requireThat(calls, "rootCollectionBeforeNativeDestruction").isEqualTo(List.of("cancel", "terminal", "close"), "expected");
    }

    /**
     * Supplies complete authority-shaped observations from the documented eighteen-field native boundary.
     * These controlled values establish publication policy only; they grant no kernel authority.
     * @param pid represented original root identifier
     * @param aborted indicates whether the represented attempt was stopped
     * @return immutable controlled complete collection
     */
    private static MacAttempt.Collected collected(long pid, boolean aborted)
    {
        List<Long> authority = new ArrayList<>(Collections.nCopies(18, 0L));
        authority.set(0, pid);
        authority.set(1, pid + 100);
        authority.set(16, -1L);
        return new MacAttempt.Collected(authority, 29, aborted);
    }

    /** Supplies controlled external boundary observations rather than native kernel acceptance evidence. */
    private static final class NativeBoundary implements MacAttempt.Backend
    {
        /** Controlled decoded input, independent of native completion and cleanup. */
        private final List<AllocationHook.Denial> denials = new ArrayList<>();
        @Override public List<AllocationHook.Denial> denials() { return List.copyOf(denials); }

        /** Observed native acquisition calls. */
        private int starts;
        /** Observed native continuation calls. */
        private int resumes;
        /** Observed native cancellation calls. */
        private int terminations;
        /** Observed external cleanup calls. */
        private int closes;
        /** Controlled uncertain acquisition diagnostic. */
        private IOException startFailure;
        /** Controlled native callback during acquisition. */
        private Runnable startCallback;
        /** Controlled complete terminal composition, or null while unresolved. */
        private JobQueue.Outcome<Integer> terminal;
        /** Controlled native collection after all documented retirement gates. */
        private MacAttempt.Collected collected;
        /** Controlled external cleanup failure that must prevent publication. */
        private IOException closeFailure;

        /** Creates method-owned observations with no acquired resource. */
        private NativeBoundary() {}

        @Override public void start() throws IOException
        {
            starts++;
            if (startCallback != null) startCallback.run();
            if (startFailure != null) throw startFailure;
        }
        @Override public void resume(long waitSequence) { resumes++; }
        @Override public void terminate() { terminations++; }
        @Override public Optional<JobQueue.Outcome<Integer>> poll() { return Optional.ofNullable(terminal); }
        @Override public void close() throws IOException
        {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
        @Override public Optional<MacAttempt.Collected> collected() { return Optional.ofNullable(collected); }
        @Override public void pumpEvents() {}
        @Override public OptionalLong pollAllocationWait() { return OptionalLong.empty(); }
    }
}
