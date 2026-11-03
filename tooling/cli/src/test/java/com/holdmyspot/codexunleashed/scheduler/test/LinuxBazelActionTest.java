package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelAction;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelControl;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelSession;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SchedulerCoordinator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the real queued action lifecycle and original control codec with controlled external native responses.
 * Each parallel method owns its fixtures. These decisions establish no native Frida, cgroup or Bazel behavior.
 */
public final class LinuxBazelActionTest
{
    /** Creates independent action lifecycle tests. */
    public LinuxBazelActionTest() {}

    /**
     * Preserves an independent trusted estimate instead of treating every action as unknown-memory work.
     * @throws IOException if the controlled native owner is rejected
     */
    @Test public void preservesTrustedPeakEstimate() throws IOException
    {
        var fixture = new Fixture();
        fixture.peakBytes = 268435456L;
        try (var action = fixture.action())
        {
            requireThat(action.peakBytes(), "trustedPeakEstimate").isEqualTo(fixture.peakBytes);
        }
    }

    /**
     * Distinguishes an unknown demand from an explicit zero estimate and rejects negative predictions.
     * @throws IOException if a controlled native owner is rejected
     */
    @Test public void validatesPeakWithoutCollapsingUnknownAndZero() throws IOException
    {
        var unknown = new Fixture();
        try (var action = unknown.action())
        {
            requireThat(action.peakBytes() == null, "unknownPeak").isTrue();
        }
        var zero = new Fixture();
        zero.peakBytes = 0L;
        try (var action = zero.action())
        {
            requireThat(action.peakBytes(), "explicitZeroPeak").isEqualTo(0L);
        }
        var negative = new Fixture();
        negative.peakBytes = -1L;
        requireException(IllegalArgumentException.class, negative::action);
    }

    /**
     * Preserves ordinary exit23 through the original final response, without creating a replay attempt.
     * @throws IOException if a valid action lifecycle is rejected
     */
    @Test public void ordinaryExit23IsFinalAfterZeroAndHookClosure() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            var attempt = action.create(10);
            fixture.frames.add("PREPARED 1 7 51");
            attempt.start();
            fixture.frames.add("EXIT 1 EXIT 23");
            requireThat(attempt.poll().isEmpty(), "resultBeforeZero").isTrue();
            fixture.latest.zero = true;
            var outcome = attempt.poll().orElseThrow();
            requireThat(outcome, "ordinaryOutcome").isEqualTo(new JobQueue.Outcome<>(new LinuxBazelControl.Result(false, 23), false));
            attempt.close();
            action.acceptOutcome(new JobQueue.Completion<>("generator", 10, outcome));
            requireException(IllegalStateException.class, () -> action.create(11));
            requireException(IllegalArgumentException.class,
                () -> action.acceptOutcome(new JobQueue.Completion<>("generator", 10, outcome)));
            requireThat(fixture.events, "effectOrder").isEqualTo(List.of("workspace-prepared", "CREATE 1", "admit:7:51", "start",
                "stop", "hooks-closed", "workspace-collected:ordinary", "FINAL 1 23", "resources-closed"));
            requireThat(action.hasOpenAttempt(), "cleanupReleased").isFalse();
        }
    }

    /**
     * Does not turn an ordinary native signal death into allocation-abort authority.
     * @throws IOException if a valid action lifecycle is rejected
     */
    @Test public void signalDeathDoesNotAuthorizeRetry() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var attempt = action.create(0);
            attempt.start();
            fixture.frames.add("EXIT 1 SIGNAL 9");
            fixture.latest.zero = true;
            requireThat(attempt.poll().orElseThrow().aborted(), "ordinarySignal").isFalse();
            attempt.close();
            requireThat(fixture.frames.sent, "originalSignalProjection").isEqualTo(List.of("CREATE 1", "FINAL 1 137"));
        }
    }

    /**
     * Pumps live hook observations, then ignores late queued admissions after independently observed whole-tree zero.
     * Both the session-facing pump and polling must still wait for and preserve the original native SIGNAL5 result.
     * @throws IOException if a valid controlled native lifecycle is rejected
     */
    @Test public void wholeTreeZeroSkipsLateAdmissionsAndPreservesOriginalSignal() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var attempt = action.create(0);
            attempt.start();
            action.pump();
            requireThat(fixture.latest.pumpCount, "livePumpDelivered").isEqualTo(1);

            fixture.latest.zero = true;
            fixture.latest.pumpFailure = new IOException("Late queued admission refers to an already absent parent");
            action.pump();
            requireThat(attempt.poll().isEmpty(), "zeroCannotInventParentResult").isTrue();
            requireThat(fixture.latest.hooksClosed, "hooksRetainedUntilOriginalResult").isFalse();
            fixture.frames.add("EXIT 1 SIGNAL 5");
            var outcome = attempt.poll().orElseThrow();
            requireThat(outcome, "originalSignalOutcome").isEqualTo(
                new JobQueue.Outcome<>(new LinuxBazelControl.Result(true, 5), false));
            requireThat(fixture.latest.pumpCount, "noPostZeroAdmission").isEqualTo(1);
            requireThat(fixture.latest.hooksClosed, "originalHooksClosedAfterResultAndZero").isTrue();
            attempt.close();
            action.acceptOutcome(new JobQueue.Completion<>("generator", 0, outcome));
            requireThat(fixture.frames.sent, "unalteredNativeSignalProjection").isEqualTo(
                List.of("CREATE 1", "FINAL 1 133"));
        }
    }

    /**
     * Requires authentic collection and zero before RESET, and accepts the old receipt after a new retry is created.
     * @throws IOException if a valid replay lifecycle is rejected
     */
    @Test public void selectedAbortResetsBeforeFreshCreateWithoutLosingOldReceipt() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var first = action.create(4);
            first.start();
            first.terminate();
            fixture.frames.add("EXIT 1 SIGNAL 9");
            fixture.latest.zero = false;
            requireThat(first.poll().isEmpty(), "notYetZero").isTrue();
            fixture.latest.zero = true;
            var aborted = first.poll().orElseThrow();
            requireThat(aborted.aborted(), "queueSelectedAbort").isTrue();
            fixture.frames.add("RESET 1");
            first.close();
            requireThat(fixture.events.indexOf("workspace-collected:aborted") < fixture.events.indexOf("RESET 1"),
                "privateOutputWithdrawalBeforeReset").isTrue();
            fixture.frames.add("PREPARED 2 8 52");
            var retry = action.create(9);
            retry.start();
            action.acceptOutcome(new JobQueue.Completion<>("generator", 4, aborted));
            requireThat(fixture.frames.sent, "invalidationBeforeFreshChild").isEqualTo(List.of("CREATE 1", "RESET 1", "CREATE 2"));
            fixture.frames.add("EXIT 2 EXIT 0");
            fixture.latest.zero = true;
            var success = retry.poll().orElseThrow();
            retry.close();
            action.acceptOutcome(new JobQueue.Completion<>("generator", 9, success));
            requireThat(fixture.frames.sent, "originalFinalConnection").isEqualTo(List.of("CREATE 1", "RESET 1", "CREATE 2", "FINAL 2 0"));
        }
    }

    /**
     * Routes only a genuine hook wait through batching and stops further grants before cancellation.
     * @throws IOException if a valid allocation-wait lifecycle is rejected
     */
    @Test public void genuineWaitGrantAndStopRetainOriginalAttempt() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var attempt = action.create(0);
            attempt.start();
            var wait = new AllocationWaits.Wait(1, 1);
            fixture.latest.waits.blocked(wait);
            long batch = action.pollBlocked().orElseThrow();
            attempt.resume(batch);
            requireThat(fixture.latest.granted, "exactGrant").isEqualTo(List.of(wait));
            attempt.terminate();
            requireException(IllegalStateException.class, () -> attempt.resume(batch));
            requireThat(action.pollBlocked().isEmpty(), "stoppedPublication").isTrue();
            attempt.close();
            requireThat(fixture.frames.sent, "cleanupInventsNoResult").isEqualTo(List.of("CREATE 1"));
        }
    }

    /**
     * Preserves a noncontinuable backend's denial through the session so the real queue authorizes abortion before
     * the action resets its original owner and creates a fresh attempt. Native statuses and zero are controlled inputs.
     * @throws IOException if a valid controlled allocation-retry lifecycle is rejected
     */
    @Test public void sessionPreservesQueueAuthorityForNoncontinuableDenial() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.peakBytes = 20L;
        Controller.Measurements measurements = new Controller.Measurements()
        {
            @Override public Controller.Memory readHost() { return new Controller.Memory(1000, 0, false); }
            @Override public long readResidentBytes(Policy.Job job) { return 10; }
        };
        LinuxBazelSession.Invocation invocation = new LinuxBazelSession.Invocation()
        {
            @Override public void pump() {}
            @Override public boolean inputClosed() { return true; }
            @Override public OptionalInt pollToolResult() { return OptionalInt.empty(); }
            @Override public void stopAccepting() {}
            @Override public void close() {}
        };
        try (LinuxBazelAction action = fixture.action();
            LinuxBazelSession session = new LinuxBazelSession(measurements, invocation);
            JobQueue<LinuxBazelControl.Result> queue = new JobQueue<>(new Policy.Budget(0, 1),
                session.measurements(), session.attempts()))
        {
            session.registerAuthenticated(action);
            queue.submit(action.name(), action.peakBytes());
            fixture.frames.add("PREPARED 1 7 51");
            queue.cycle();
            fixture.latest.retryAfterDenial = true;
            fixture.latest.waits.blocked(new AllocationWaits.Wait(1, 1));
            SchedulerCoordinator.Blocked blocked = session.pollBlocked().orElseThrow();
            queue.allocationBlocked(blocked.name(), blocked.attemptSequence(), blocked.waitSequence());

            requireThat(queue.cycle().control(), "queueAuthorizedRecovery").isEqualTo(Policy.Control.TERMINATE);
            requireThat(fixture.latest.granted.isEmpty(), "noSameAttemptGrant").isTrue();
            requireThat(queue.jobs().getFirst().state(), "awaitingOriginalNativeStatus").isEqualTo(Policy.State.STOPPING);
            fixture.frames.add("EXIT 1 SIGNAL 9");
            fixture.frames.add("RESET 1");
            fixture.frames.add("PREPARED 2 8 52");
            requireThat(queue.cycle().control(), "freshAttemptAfterCollectedAbort").isEqualTo(Policy.Control.START);
            requireThat(fixture.frames.sent, "originalOwnerResetBeforeRetry").isEqualTo(List.of("CREATE 1", "RESET 1", "CREATE 2"));
            requireThat(queue.completions(), "abortedAttemptDoesNotCount").isEqualTo(0);

            fixture.frames.add("EXIT 2 EXIT 0");
            fixture.latest.zero = true;
            queue.cycle();
            for (JobQueue.Completion<LinuxBazelControl.Result> completion : queue.completedAttempts())
                session.acceptOutcome(completion);
            requireThat(queue.completions(), "successfulRetryCountedOnce").isEqualTo(1);
            requireThat(fixture.frames.sent, "singleFinalPublication").isEqualTo(List.of("CREATE 1", "RESET 1", "CREATE 2", "FINAL 2 0"));
        }
    }

    /**
     * Keeps partially acquired resources reachable after failed admission, without inventing a native final result.
     * @throws IOException if final outer socket cleanup fails
     */
    @Test public void failedAdmissionRetainsCleanupOwnerAndNoSyntheticResult() throws IOException
    {
        var fixture = new Fixture();
        fixture.failAdmission = true;
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var attempt = action.create(0);
            requireException(IOException.class, attempt::start);
            requireThat(action.hasOpenAttempt(), "retainedBeforeCleanup").isTrue();
            requireException(IOException.class, action::pump);
            attempt.close();
            requireThat(action.hasOpenAttempt(), "releasedAfterAuthenticCleanup").isFalse();
            requireThat(fixture.events.contains("terminate"), "cleanupCancellation").isTrue();
            requireThat(fixture.frames.sent, "noFinalOrReset").isEqualTo(List.of("CREATE 1"));
        }
    }

    /**
     * Preserves uncertain final publication permanently and does not resend it through repeated cleanup.
     * @throws IOException if setup or authentic collection fails
     */
    @Test public void uncertainFinalNeverResendsOrReleasesOwner() throws IOException
    {
        var fixture = new Fixture();
        var action = fixture.action();
        fixture.frames.add("PREPARED 1 7 51");
        var attempt = action.create(0);
        attempt.start();
        fixture.frames.add("EXIT 1 EXIT 0");
        fixture.latest.zero = true;
        attempt.poll();
        fixture.frames.failFinal = true;
        requireException(IOException.class, attempt::close);
        requireException(IOException.class, attempt::close);
        requireException(IOException.class, action::close);
        requireThat(action.hasOpenAttempt(), "uncertainOwnerRetained").isTrue();
        requireThat(fixture.frames.sent, "oneUncertainFinal").isEqualTo(List.of("CREATE 1", "FINAL 1 0"));
    }

    /**
     * Withholds final publication when native instrumentation cannot be closed after whole-tree zero.
     * @throws IOException if preparation fails
     */
    @Test public void hookClosureFailureWithholdsFinalAndKeepsResources() throws IOException
    {
        var fixture = new Fixture();
        var action = fixture.action();
        fixture.frames.add("PREPARED 1 7 51");
        var attempt = action.create(0);
        attempt.start();
        fixture.frames.add("EXIT 1 EXIT 0");
        fixture.latest.zero = true;
        fixture.latest.failHookClose = true;
        requireException(IOException.class, attempt::poll);
        requireException(IOException.class, attempt::close);
        requireThat(action.hasOpenAttempt(), "hookOwnerRetained").isTrue();
        requireThat(fixture.frames.sent, "noPrematureFinal").isEqualTo(List.of("CREATE 1"));
    }

    /**
     * Retains partial mapping ownership without creating a child when private preparation fails.
     * @throws IOException if original owner setup or modeled cleanup fails
     */
    @Test public void workspacePreparationFailurePreventsNativeCreate() throws IOException
    {
        var fixture = new Fixture();
        fixture.failWorkspacePreparation = true;
        try (var action = fixture.action())
        {
            var attempt = action.create(0);
            requireException(IOException.class, attempt::start);
            requireThat(fixture.frames.sent.isEmpty(), "noChildBeforeMapping").isTrue();
            requireThat(action.hasOpenAttempt(), "partialMappingLeaseRetained").isTrue();
            attempt.close();
            requireThat(action.hasOpenAttempt(), "originalCleanupAcknowledged").isFalse();
        }
    }

    /**
     * Withholds FINAL and resource release when freezing or unmapping output remains uncertain after zero.
     * @throws IOException if modeled setup or authentic collection fails
     */
    @Test public void workspaceCollectionFailureWithholdsFinal() throws IOException
    {
        var fixture = new Fixture();
        var action = fixture.action();
        fixture.frames.add("PREPARED 1 7 51");
        var attempt = action.create(0);
        attempt.start();
        fixture.latest.failWorkspaceCollection = true;
        fixture.frames.add("EXIT 1 EXIT 0");
        fixture.latest.zero = true;
        attempt.poll().orElseThrow();
        requireException(IOException.class, attempt::close);
        requireException(IOException.class, attempt::close);
        requireThat(fixture.frames.sent, "noFinalAfterUncertainOutput").isEqualTo(List.of("CREATE 1"));
        requireThat(fixture.events.contains("resources-closed"), "namespaceLeaseRetained").isFalse();
        requireThat(action.hasOpenAttempt(), "attemptStillOwned").isTrue();
    }

    /**
     * Preserves a checked cleanup cause separately from an earlier event-delivery failure.
     * @throws IOException if controlled setup fails
     */
    @Test public void checkedCleanupFailurePreservesCauseWithoutReplay() throws IOException
    {
        requireCleanupFailureRetained(new IOException("Distinct uncertain native CLOSE"));
    }

    /**
     * Preserves an unchecked cleanup cause separately from an earlier event-delivery failure.
     * @throws IOException if controlled setup fails
     */
    @Test public void uncheckedCleanupFailurePreservesCauseWithoutReplay() throws IOException
    {
        requireCleanupFailureRetained(new IllegalStateException("Distinct uncertain native CLOSE"));
    }

    /**
     * Rethrows the original cleanup Error while retaining it for subsequent non-replayed close calls.
     * @throws IOException if controlled setup fails
     */
    @Test public void cleanupErrorPreservesCauseWithoutReplay() throws IOException
    {
        requireCleanupFailureRetained(new AssertionError("Distinct uncertain native CLOSE"));
    }

    /**
     * Retires acknowledged resources after an operational failure without inventing FINAL or retry authority.
     * @throws IOException if controlled setup or acknowledged cleanup fails
     */
    @Test public void acknowledgedCleanupAfterOperationalFailureRetiresOnlyOwnedResources() throws IOException
    {
        var fixture = new Fixture();
        try (var action = fixture.action())
        {
            fixture.frames.add("PREPARED 1 7 51");
            var attempt = action.create(0);
            attempt.start();
            fixture.latest.pumpFailure = new IOException("Distinct original event-delivery failure");
            var operational = requireException(IOException.class, action::pump);
            fixture.latest.zero = true;
            requireThat(action.hasOpenAttempt(), "zeroAloneRetainsOwnership").isTrue();
            requireThat(fixture.latest.hooksClosed, "zeroAloneDoesNotAcknowledgeHooks").isFalse();
            attempt.close();
            attempt.close();
            requireThat(action.hasOpenAttempt(), "acknowledgedResourcesRetired").isFalse();
            requireThat(fixture.events, "onlyAcknowledgedCleanupEffects").isEqualTo(List.of(
                "workspace-prepared", "CREATE 1", "admit:7:51", "start", "stop", "terminate", "hooks-closed", "resources-closed"));
            requireThat(fixture.frames.sent, "noFinalOrReset").isEqualTo(List.of("CREATE 1"));
            requireException(IllegalStateException.class, () -> action.create(1));
            requireThat(requireException(IOException.class, action::pump).getCause() == operational,
                "operationalFailureStillIndependent").isTrue();
        }
    }

    /**
     * Checks first cleanup-cause identity and retained uncertain effects with an independent fixture for each caller.
     * @param cleanupCause distinct modeled native resource-close failure
     * @throws IOException if controlled setup fails
     */
    private static void requireCleanupFailureRetained(Throwable cleanupCause) throws IOException
    {
        var fixture = new Fixture();
        var action = fixture.action();
        fixture.frames.add("PREPARED 1 7 51");
        var attempt = action.create(0);
        attempt.start();
        fixture.latest.pumpFailure = new IOException("Distinct original event-delivery failure");
        var operational = requireException(IOException.class, action::pump);
        fixture.latest.closeFailure = cleanupCause;
        if (cleanupCause instanceof Error)
        {
            requireThat(requireException(Error.class, attempt::close) == cleanupCause, "originalCleanupErrorRethrown").isTrue();
        }
        else
        {
            var first = requireException(IOException.class, attempt::close);
            requireThat(first.getMessage(), "cleanupContext").isEqualTo("Bazel generator attempt cleanup failed");
            requireThat(first.getCause() == cleanupCause, "actualFirstCleanupCause").isTrue();
        }
        var effects = List.copyOf(fixture.events);
        fixture.latest.closeFailure = null;
        var repeated = requireException(IOException.class, attempt::close);
        requireThat(repeated.getCause().getCause() == cleanupCause, "retainedOriginalCleanupCause").isTrue();
        requireThat(fixture.events, "uncertainCloseNeverReplayed").isEqualTo(effects);
        requireThat(fixture.latest.zero, "zeroDoesNotClearUncertainClose").isTrue();
        requireThat(action.hasOpenAttempt(), "uncertainOwnerStillRetained").isTrue();
        requireThat(requireException(IOException.class, action::pump).getCause() == operational,
            "operationalFailureStillIndependent").isTrue();
        requireException(IOException.class, action::close);
        requireException(IllegalStateException.class, () -> action.create(1));
        requireThat(fixture.frames.sent, "noFinalOrReset").isEqualTo(List.of("CREATE 1"));
        requireThat(fixture.events.contains("resources-closed"), "unacknowledgedResourcesRemainOwned").isFalse();
    }

    /** Independently owned fixture for host control decisions; native policy remains an explicit external input. */
    private static final class Fixture
    {
        /** Ordered observable control and lifecycle effects. */
        private final List<String> events = new ArrayList<>();
        /** Native framed response source. */
        private final Frames frames = new Frames(events);
        /** Most recently created resource owner, retained before admission. */
        private Resources latest;
        /** Independently supplied trusted estimate; null retains unknown-memory policy. */
        private Long peakBytes;
        /** Whether native admission should return after a partial external failure. */
        private boolean failAdmission;
        /** Whether mapping fails before native CREATE. */
        private boolean failWorkspacePreparation;

        /** Creates independent controlled native inputs for one test method. */
        private Fixture() {}

        /**
         * Creates production action policy with per-attempt controlled external ownership.
         * @return action using the real original native control codec
         * @throws IOException if initial owner receipt is rejected
         */
        private LinuxBazelAction action() throws IOException
        {
            return new LinuxBazelAction("generator", peakBytes, new LinuxBazelControl(frames, "ab".repeat(32)), (sequence, waits) ->
            {
                latest = new Resources(events, waits, failAdmission);
                latest.failWorkspacePreparation = failWorkspacePreparation;
                return latest;
            }, Duration.ofSeconds(1));
        }
    }

    /** Controlled external resource boundary; no cgroup or instrumentation success is claimed. */
    private static final class Resources implements LinuxBazelAction.Resources
    {
        /** Shared action-local observable effects. */
        private final List<String> events;
        /** Real batching owner receiving modeled validated native waits. */
        private final AllocationWaits waits;
        /** Exact waits whose acknowledged grant was requested. */
        private final List<AllocationWaits.Wait> granted = new ArrayList<>();
        /** Controlled installed backend capability; true requires queue-authorized whole-attempt retry. */
        private boolean retryAfterDenial;
        /** Whether actual execution impossibility is reported by the external boundary. */
        private boolean zero;
        /** Whether admission reports a partial acquisition failure. */
        private final boolean failAdmission;
        /** Whether original hook destruction reports unresolved ownership. */
        private boolean failHookClose;
        /** Whether original mapping preparation reports uncertain ownership. */
        private boolean failWorkspacePreparation;
        /** Whether private output freezing/unmap reports uncertain ownership. */
        private boolean failWorkspaceCollection;
        /** Distinct modeled event-delivery failure after successful start. */
        private IOException pumpFailure;
        /** Number of actual deliveries requested while exercising the resource boundary. */
        private int pumpCount;
        /** Distinct uncertain resource-close cause, including an original Error. */
        private Throwable closeFailure;
        /** Original hook closure returned successfully. */
        private boolean hooksClosed;

        /**
         * Retains independent external responses for one attempted admission.
         * @param events action-local effect log
         * @param waits real validated-wait collector
         * @param failAdmission whether admission should fail after acquisition
         */
        private Resources(List<String> events, AllocationWaits waits, boolean failAdmission)
        {
            this.events = events;
            this.waits = waits;
            this.failAdmission = failAdmission;
        }
        @Override public void prepareWorkspace() throws IOException
        {
            events.add("workspace-prepared");
            if (failWorkspacePreparation) throw new IOException("Controlled mapping preparation remains uncertain");
        }
        @Override public void collectWorkspace(JobQueue.Outcome<LinuxBazelControl.Result> outcome) throws IOException
        {
            if (!zero || !hooksClosed) throw new IOException("Workspace collection precedes complete zero/hook closure");
            events.add("workspace-collected:" + (outcome.aborted() ? "aborted" : "ordinary"));
            if (failWorkspaceCollection) throw new IOException("Controlled output freeze/unmap remains uncertain");
        }
        @Override public void admit(LinuxBazelControl.Identity child) throws IOException
        {
            events.add("admit:" + child.pid() + ":" + child.birth());
            if (failAdmission) throw new IOException("Controlled admission failed after acquisition");
        }
        @Override public void start() { events.add("start"); }
        @Override public void pump() throws IOException
        {
            ++pumpCount;
            if (pumpFailure != null) throw pumpFailure;
        }
        @Override public boolean requiresRetryAfterDenial() { return retryAfterDenial; }
        @Override public void grant(AllocationWaits.Wait wait) { granted.add(wait); }
        @Override public void stop() { events.add("stop"); }
        @Override public void requestTermination() { events.add("terminate"); zero = true; }
        @Override public boolean zero() { return zero; }
        @Override public void finishInstrumentation() throws IOException
        {
            events.add("hooks-closed");
            if (failHookClose) throw new IOException("Controlled native hook owner remains unresolved");
            hooksClosed = true;
        }
        @Override public void close() throws IOException
        {
            if (closeFailure != null)
            {
                events.add("resources-close-attempted");
                if (closeFailure instanceof IOException checked) throw checked;
                if (closeFailure instanceof RuntimeException unchecked) throw unchecked;
                if (closeFailure instanceof Error error) throw error;
                throw new IllegalStateException("Unsupported modeled cleanup cause", closeFailure);
            }
            events.add("resources-closed");
        }
    }

    /** Supplies only controlled external frames to the real bounded control parser. */
    private static final class Frames implements LinuxBazelControl.Connection
    {
        /** Incoming exact bounded native message payloads. */
        private final ArrayDeque<byte[]> received = new ArrayDeque<>();
        /** Host control payloads actually attempted. */
        private final List<String> sent = new ArrayList<>();
        /** Ordered controls and resource lifecycle log. */
        private final List<String> events;
        /** Whether final send reports unknown external effects. */
        private boolean failFinal;

        /**
         * Supplies the original owner's initial capability receipt.
         * @param events action-local ordered effect log
         */
        private Frames(List<String> events)
        {
            this.events = events;
            add("OWNER " + "ab".repeat(32) + " 2 50");
        }
        /**
         * Appends one native response payload.
         * @param frame complete native control message
         */
        private void add(String frame) { received.add(frame.getBytes(StandardCharsets.US_ASCII)); }
        @Override public void send(byte[] bytes) throws IOException
        {
            String value = new String(bytes, StandardCharsets.US_ASCII);
            sent.add(value); events.add(value);
            if (failFinal && value.startsWith("FINAL ")) throw new IOException("Controlled uncertain FINAL publication");
        }
        @Override public byte[] receive() throws IOException
        {
            if (received.isEmpty()) throw new IOException("No controlled native response");
            return received.remove();
        }
        @Override public Optional<byte[]> poll() { return Optional.ofNullable(received.poll()); }
        @Override public void close() {}
    }
}
