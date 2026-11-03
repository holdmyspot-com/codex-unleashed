package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.ObservationRejected;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SchedulerCoordinator;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;

/** Exercises the real queue and policy with modeled action and protocol owners. */
public final class SchedulerCoordinatorTest
{
    /** Creates coordinator lifecycle checks. */
    public SchedulerCoordinatorTest() {}

    /**
     * Durable events preserve native fields and bracket submission, blocked control, delivery and final cleanup.
     * @throws IOException if modeled coordination or durable recording fails
     */
    @Test
    public void durableEventsPreservePayloadsAndBoundaryOrder() throws IOException
    {
        var session = new Model();
        session.blockFirst = true;
        List<Object> payloads = new ArrayList<>();
        Thread owner = Thread.currentThread();
        new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}, (event, payload) ->
        {
            requireThat(Thread.currentThread(), "observerOwnerThread").isEqualTo(owner);
            requireThat(payload instanceof Policy.Decision decision && decision.control() == Policy.Control.WAIT,
                "waitNoise").isFalse();
            payloads.add(payload);
            String suffix = payload instanceof JobQueue.Completion<?> completion ? ":" + completion.name() + ":" +
                completion.attemptSequence() : payload instanceof SchedulerCoordinator.Submission submission ?
                ":" + submission.name() : "";
            session.events.add("observe:" + event + suffix);
        }).run();
        requireThat(session.events.indexOf("observe:submission:A"), "submissionBeforeLaunch")
            .isLessThan(session.events.indexOf("start:A:0"));
        requireThat(session.events.indexOf("observe:blocked"), "blockedBeforeControl")
            .isLessThan(session.events.indexOf("resume:A:0:9"));
        requireThat(session.events.indexOf("start:A:0"), "decisionAfterSuccessfulControl")
            .isLessThan(session.events.indexOf("observe:decision"));
        requireThat(session.events.indexOf("observe:completion-before:A:0"), "beforeOriginalDelivery")
            .isLessThan(session.events.indexOf("deliver:A:0"));
        requireThat(session.events.indexOf("deliver:A:0"), "afterOriginalDelivery")
            .isLessThan(session.events.indexOf("observe:completion-after:A:0"));
        requireThat(session.events.getLast(), "terminalAfterCleanup").isEqualTo("observe:terminal");
        requireThat(session.events.get(session.events.size() - 2), "allOwnersClosedFirst").isEqualTo("session-close");
        requireThat(payloads.getLast(), "authenticRootStatus").isEqualTo(23);
        var blocked = (SchedulerCoordinator.Blocked) payloads.stream().filter(SchedulerCoordinator.Blocked.class::isInstance)
            .findFirst().orElseThrow();
        requireThat(blocked, "completeBlockedCorrelation").isEqualTo(new SchedulerCoordinator.Blocked("A", 0, 9));
        List<Object> completions = payloads.stream().filter(JobQueue.Completion.class::isInstance).toList();
        requireThat(completions.size(), "twoObservationsPerCompletion").isEqualTo(4);
        for (int index = 0; index < completions.size(); index += 2)
            requireThat(completions.get(index + 1), "sameNativeCompletionAfterDelivery").isEqualTo(completions.get(index));
    }

    /** A recorder fault with unresolved native cleanup retains the session and preserves its original cause. */
    @Test
    public void observerFailureRetainsUnresolvedCleanup()
    {
        var session = new Model();
        session.failClose = true;
        var original = new IOException("durable decision unavailable");
        IOException failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
            new Policy.Budget(0, 2), () -> {}, (event, payload) ->
            { if (event.equals("decision")) throw original; }).run());
        requireThat(failure, "originalRecorderFault").isEqualTo(original);
        requireThat(session.events.contains("stop-input"), "producerStopped").isTrue();
        requireThat(session.events.contains("close:A:0"), "queueOwnerCleanupAttempted").isTrue();
        requireThat(session.events.contains("session-close"), "unresolvedDependenciesRetained").isFalse();
        requireThat(failure.getSuppressed().length > 0, "cleanupFailureRetained").isTrue();
    }

    /** Completion observation failures preserve at-most-once uncertain delivery and clean all resolved owners. */
    @Test
    public void completionObserverFailureNeverResends()
    {
        for (String boundary : List.of("completion-before", "completion-after"))
        {
            var session = new Model();
            var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}, (event, payload) ->
            { if (event.equals(boundary)) throw new IOException(boundary); });
            requireThat(requireException(IOException.class, coordinator::run).getMessage(), "originalCompletionFault")
                .isEqualTo(boundary);
            requireThat(session.delivered.size(), "atMostOneDeliveryAtBoundary")
                .isEqualTo(boundary.equals("completion-before") ? 0 : 1);
            requireThat(coordinator.acceptedAttempts().isEmpty(), "authenticPayloadRetained").isFalse();
            requireThat(session.events.getLast(), "resolvedSessionClosed").isEqualTo("session-close");
            requireThat(session.events.contains("stop-input"), "noFurtherAdmission").isTrue();
        }
    }

    /**
     * Empty discovery WAIT loops do not generate decision noise and a terminal record fault follows complete cleanup.
     */
    @Test
    public void terminalObservationFollowsCleanupWithoutWaitNoise()
    {
        var session = new Model();
        session.seeded = true;
        session.rootEarly = true;
        session.discoveryDelay = 3;
        List<String> records = new ArrayList<>();
        IOException failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
            new Policy.Budget(0, 2), () -> {}, (event, payload) ->
            {
                records.add(event);
                requireThat(session.events.getLast(), "terminalAfterResolvedCleanup").isEqualTo("session-close");
                throw new IOException("terminal unavailable");
            }).run());
        requireThat(failure.getMessage(), "terminalRecorderFault").isEqualTo("terminal unavailable");
        requireThat(records, "terminalOnlyNoWaitNoise").isEqualTo(List.of("terminal"));
        requireThat(session.events.stream().filter("session-close"::equals).count(), "singleOuterClose").isEqualTo(1L);
    }

    /**
     * Root exit cannot finish an invocation before all owned action outcomes are delivered.
     * @throws IOException if modeled coordination fails
     */
    @Test
    public void retainsActionsAfterRootExit() throws IOException
    {
        var session = new Model();
        session.rootEarly = true;
        int result = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run();
        requireThat(result, "rootstatus").isEqualTo(23);
        requireThat(session.delivered, "acceptedresults").isEqualTo(List.of("A:0:7", "B:1:7"));
        requireThat(session.events.getLast(), "terminalowner").isEqualTo("session-close");
        requireThat(session.events.indexOf("close:A:0"), "firstownerclosure").isLessThan(
            session.events.indexOf("deliver:A:0"));
    }

    /**
     * Native peer service queues a denial; the coordinator delivers it after the control returns.
     * @throws IOException if modeled coordination fails
     */
    @Test
    public void defersBlockedDeliveryAndPreservesRetryOrder() throws IOException
    {
        var session = new Model();
        session.blockFirst = true;
        session.abortFirst = true;
        new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run();
        requireThat(session.events.contains("resume:A:0:9"), "exactresumedwait").isTrue();
        requireThat(session.delivered, "naturalresultsonly").isEqualTo(List.of("B:1:7", "A:2:7"));
        requireThat(session.observed, "allattemptreceipts").isEqualTo(List.of("A:0:true", "B:1:false", "A:2:false"));
    }

    /** Failed complete memory acquisition stops control and preserves the original cause during cleanup. */
    @Test
    public void failedLaterReadingStopsControlsAndCleansOwners()
    {
        var session = new Model();
        session.failSecondTree = true;
        IOException failure = requireException(IOException.class, () ->
            new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run());
        requireThat(failure.getMessage(), "measurementcause").isEqualTo("tree B unavailable");
        requireThat(session.delivered.size(), "unpublishedresults").isEqualTo(0);
        requireThat(session.events.contains("stop-input"), "producerstopped").isTrue();
        requireThat(session.events.getLast(), "outerclose").isEqualTo("session-close");
    }

    /** An uncertain response is sent once and does not cause the coordinator to repeat an action. */
    @Test
    public void uncertainDeliveryIsNotResent()
    {
        var session = new Model();
        session.failDelivery = true;
        requireException(IOException.class, () ->
            new SchedulerCoordinator<>(session, new Policy.Budget(0, 1), () -> {}).run());
        requireThat(session.delivered.size(), "singledeliveryattempt").isEqualTo(1);
        requireThat(session.events.contains("stop-input"), "producerstopped").isTrue();
    }

    /**
     * Unknown demand remains distinct from a supplied zero estimate.
     * @throws IOException if modeled coordination fails
     */
    @Test
    public void preservesUnknownEstimates() throws IOException
    {
        var session = new Model();
        session.unknown = true;
        new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run();
        requireThat(session.maximumLive, "exclusiveunknownadmission").isEqualTo(1);
        var known = new Model();
        new SchedulerCoordinator<>(known, new Policy.Budget(0, 2), () -> {}).run();
        requireThat(known.maximumLive, "knownzeroadmission").isEqualTo(2);
    }

    /** Cancellation retains interruption and does not release dependencies beneath a failed owner. */
    @Test
    public void retainsDependenciesAfterFailedCleanup()
    {
        var session = new Model();
        session.failClose = true;
        try
        {
            requireException(java.io.InterruptedIOException.class, () ->
                new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () ->
                { throw new InterruptedException("cancelled"); }).run());
            requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
            requireThat(session.events.contains("session-close"), "unsafeouterrelease").isFalse();
            requireThat(session.events.contains("stop-input"), "producerstopped").isTrue();
        }
        finally { Thread.interrupted(); }
    }

    /** Original cancellation stays primary while each mandatory cleanup boundary receives a clear interrupt. */
    @Test
    public void interruptedObserverAllowsCleanupAndRestoresCancellation()
    {
        var session = new Model();
        session.rejectInterruptedCleanup = true;
        session.interruptAfterStop = true;
        var original = new IOException("cancel original held allocation");
        try
        {
            IOException failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
                new Policy.Budget(0, 2), () -> {}, (event, payload) ->
                {
                    if (event.equals("decision"))
                    {
                        Thread.currentThread().interrupt();
                        throw original;
                    }
                }).run());
            requireThat(failure, "originalCancellationIdentity").isEqualTo(original);
            requireThat(failure.getSuppressed().length, "noInterruptInducedCleanupFailure").isEqualTo(0);
            requireThat(Thread.currentThread().isInterrupted(), "cancellationInterruptRestored").isTrue();
            requireThat(session.events.contains("stop-input"), "admissionStopped").isTrue();
            requireThat(session.events.contains("close:A:0"), "originalAttemptClosed").isTrue();
            requireThat(session.events.getLast(), "dependenciesReleasedAfterOwners").isEqualTo("session-close");
            requireThat(session.hasOpenAttempts(), "noRetainedResolvedLease").isFalse();
            requireThat(session.delivered.isEmpty(), "cancelledOutputWithheld").isTrue();
        }
        finally { Thread.interrupted(); }
    }

    /** Clearing cancellation for cleanup does not hide a genuine owner failure or release its dependencies. */
    @Test
    public void interruptedCancellationRetainsGenuineCleanupFailure()
    {
        var session = new Model();
        session.rejectInterruptedCleanup = true;
        session.failClose = true;
        var original = new IOException("cancel before native cleanup");
        try
        {
            IOException failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
                new Policy.Budget(0, 2), () -> {}, (event, payload) ->
                {
                    if (event.equals("decision"))
                    {
                        Thread.currentThread().interrupt();
                        throw original;
                    }
                }).run());
            requireThat(failure, "originalCancellationIdentity").isEqualTo(original);
            requireThat(failure.getSuppressed().length, "realFailureAndUnresolvedLease").isEqualTo(2);
            requireThat(failure.getSuppressed()[0].getCause().getMessage(), "realCleanupFailureVisible")
                .isEqualTo("owner close failed");
            requireThat(Thread.currentThread().isInterrupted(), "cancellationInterruptRestored").isTrue();
            requireThat(session.events.contains("stop-input"), "admissionStopped").isTrue();
            requireThat(session.events.contains("session-close"), "unresolvedDependenciesRetained").isFalse();
            requireThat(session.hasOpenAttempts(), "failedLeaseRetained").isTrue();
            requireThat(session.delivered.isEmpty(), "cancelledOutputWithheld").isTrue();
        }
        finally { Thread.interrupted(); }
    }

    /** An interrupted idle wait permits bounded owner cleanup before restoring the original interruption. */
    @Test
    public void interruptedIdleAllowsCleanupAndRestoresOriginalCause()
    {
        var session = new Model();
        session.rejectInterruptedCleanup = true;
        var original = new InterruptedException("cancel scheduler wait");
        try
        {
            InterruptedIOException failure = requireException(InterruptedIOException.class, () ->
                new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> { throw original; }).run());
            requireThat(failure.getCause(), "originalIdleCancellation").isEqualTo(original);
            requireThat(failure.getSuppressed().length, "cleanupAcknowledged").isEqualTo(0);
            requireThat(Thread.currentThread().isInterrupted(), "idleInterruptRestored").isTrue();
            requireThat(session.events.getLast(), "dependenciesClosedAfterOwners").isEqualTo("session-close");
            requireThat(session.hasOpenAttempts(), "allOriginalLeasesReleased").isFalse();
            requireThat(session.delivered.isEmpty(), "cancelledOutputWithheld").isTrue();
        }
        finally { Thread.interrupted(); }
    }

    /** A producer failure remains primary when producer shutdown and outer cleanup also fail. */
    @Test
    public void preservesPrimaryAndSuppressedFailures()
    {
        var session = new Model();
        session.failPump = true;
        session.failStop = true;
        session.failOuter = true;
        IOException failure = requireException(IOException.class, () ->
            new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run());
        requireThat(failure.getMessage(), "primary").isEqualTo("pump failed");
        requireThat(failure.getSuppressed().length, "cleanupfailures").isEqualTo(2);
    }

    /** A later sampling failure cannot publish an earlier accepted outcome before a successful cycle. */
    @Test
    public void acceptedOutcomeWaitsForSuccessfulCycle()
    {
        var session = new Model();
        session.failAfterCompletion = true;
        var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {});
        requireException(IOException.class, coordinator::run);
        requireThat(coordinator.acceptedAttempts().size(), "retainedPayloads").isEqualTo(1);
        requireThat(coordinator.acceptedAttempts().getFirst().outcome().result(), "nativePayload").isEqualTo(7);
        requireThat(session.events.contains("close:A:0"), "acceptedOwnerCleaned").isTrue();
        requireThat(session.delivered.size(), "prematureDelivery").isEqualTo(0);
        requireThat(session.events.stream().filter(event -> event.equals("close:A:0")).count(), "singleClose").isEqualTo(1L);
    }

    /**
     * A terminal root with open discovery waits; one coordinator cannot be reused.
     * @throws IOException if modeled coordination fails
     */
    @Test
    public void waitsForDiscoveryAndRejectsReuse() throws IOException
    {
        var session = new Model();
        session.seeded = true;
        session.rootEarly = true;
        session.discoveryDelay = 3;
        int[] waits = {0};
        var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> waits[0]++);
        requireThat(coordinator.run(), "status").isEqualTo(23);
        requireThat(waits[0], "boundedWaits").isEqualTo(2);
        requireException(IllegalStateException.class, coordinator::run);
    }

    /**
     * The wait observer sees every policy wait, with its reason and queue snapshot, immediately before each idle wait.
     * @throws IOException if modeled coordination fails
     */
    @Test
    public void reportsEveryPolicyWaitBeforeIdling() throws IOException
    {
        var session = new Model();
        session.seeded = true;
        session.rootEarly = true;
        session.discoveryDelay = 3;
        List<String> order = new ArrayList<>();
        List<Policy.Decision> decisions = new ArrayList<>();
        new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> order.add("idle"), (event, payload) -> { },
            (decision, snapshot) ->
            {
                order.add("wait");
                decisions.add(decision);
                requireThat(snapshot.budget(), "snapshotBudget").isEqualTo(new Policy.Budget(0, 2));
            }).run();
        requireThat(order, "observerPrecedesEachIdle").isEqualTo(List.of("wait", "idle", "wait", "idle"));
        requireThat(decisions.getFirst().control(), "observedControl").isEqualTo(Policy.Control.WAIT);
    }

    /**
     * Trusted rejected samples wait and reconcile again without losing completed payloads.
     * @throws IOException if modeled observation recovery fails
     */
    @Test
    public void reconcilesAfterTrustedRejection() throws IOException
    {
        var session = new Model();
        session.rejectAfterCompletion = true;
        int[] waits = {0};
        var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> waits[0]++);
        requireThat(coordinator.run(), "rootStatus").isEqualTo(23);
        requireThat(session.delivered, "oneDeliveryPerAuthenticOutcome").isEqualTo(List.of("A:0:7", "B:1:7"));
        requireThat(coordinator.acceptedAttempts().size(), "retainedPayloadCount").isEqualTo(2);
        requireThat(waits[0] > 0, "cooperativeObservationRetry").isTrue();
        requireThat(session.events.contains("stop-input"), "noTerminalFailure").isFalse();
    }

    /**
     * Multiple completed owners are reconciled in discovery order after one rejected complete batch.
     * @throws IOException if modeled owner reconciliation or complete sampling fails
     */
    @Test
    public void reconcilesMultipleCompletedOwners() throws IOException
    {
        var session = new Model();
        session.completeOnRejection = true;
        int[] waits = {0};
        new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> waits[0]++).run();
        requireThat(session.delivered, "orderedAuthenticOutcomes").isEqualTo(List.of("A:0:7", "B:1:7"));
        requireThat(session.events.subList(0, 2), "unchangedDiscoveryOrder").isEqualTo(List.of("start:A:0", "start:B:1"));
        requireThat(waits[0], "singleRejectedBatchWait").isEqualTo(1);
        requireThat(session.hostReads, "freshBatchAfterReconciliation").isEqualTo(4);
    }

    /** Every rejected batch consumes the finite budget even when the idle operation returns immediately. */
    @Test
    public void boundsRepeatedRejections()
    {
        var session = new Model();
        session.rejectEveryHost = true;
        int[] waits = {0};
        var failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
            new Policy.Budget(0, 2), () -> waits[0]++).run());
        requireThat(failure instanceof ObservationRejected, "exhaustionIsTerminal").isFalse();
        requireThat(failure.getCause(), "lastRejectedBatchRetained").isInstanceOf(ObservationRejected.class);
        requireThat(session.hostReads, "exactRejectionBound").isEqualTo(32);
        requireThat(waits[0], "boundedRetryWaits").isEqualTo(31);
        requireThat(session.maximumLive, "noAdmissionDuringRejections").isEqualTo(0);
    }

    /** Every incomplete inventory consumes the unchanged finite recovery budget without admitting work. */
    @Test public void boundsIncompleteInventories()
    {
        var session = new Model();
        session.rejectEveryHost = true;
        session.rejectionReason = ObservationRejected.Reason.INCOMPLETE_INVENTORY;
        var failure = requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
            new Policy.Budget(0, 2), () -> {}).run());
        var rejected = (ObservationRejected) failure.getCause();
        requireThat(rejected.reason(), "retainedIncompleteReason").isEqualTo(ObservationRejected.Reason.INCOMPLETE_INVENTORY);
        requireThat(session.hostReads, "unchangedFiniteBound").isEqualTo(32);
        requireThat(session.maximumLive, "noAdmissionFromPartialInventory").isEqualTo(0);
    }

    /**
     * A complete accepted batch resets the consecutive bound; time or partial readings cannot reset it.
     * @throws IOException if modeled complete sampling fails
     */
    @Test
    public void resetsBoundOnlyAfterCompleteBatch() throws IOException
    {
        var session = new Model();
        session.seeded = true;
        session.rootEarly = true;
        session.discoveryDelay = 64;
        session.rejectedReads = IntStream.rangeClosed(1, 63).filter(value -> value != 32).boxed()
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        requireThat(new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {}).run(), "rootStatus")
            .isEqualTo(23);
        requireThat(session.hostReads, "twoAcceptedBatches").isEqualTo(64);
    }

    /** Partial samples and authentic owner progress do not renew the rejected-batch budget. */
    @Test
    public void completedOwnersDoNotResetRejectedBatchBound()
    {
        var session = new Model();
        session.rejectHostAfter = 3;
        var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {});
        requireException(IOException.class, coordinator::run);
        requireThat(session.hostReads, "exactBoundDespiteOwnerProgress").isEqualTo(34);
        requireThat(coordinator.acceptedAttempts().size(), "retainedAuthenticOutcomes").isEqualTo(2);
        requireThat(session.delivered.isEmpty(), "noPublicationBeforeCompleteBatch").isTrue();
    }

    /** Repeated late-tree rejection cannot become an infinite loop through successful host reads. */
    @Test
    public void partialSamplesDoNotResetRejectedBatchBound()
    {
        var session = new Model();
        session.rejectResident = true;
        var coordinator = new SchedulerCoordinator<>(session, new Policy.Budget(0, 2), () -> {});
        requireException(IOException.class, coordinator::run);
        requireThat(session.hostReads, "hostSuccessCannotResetRecovery").isEqualTo(33);
        requireThat(session.events.stream().filter(event -> event.startsWith("start:")).count(), "noFurtherAdmission")
            .isEqualTo(1L);
    }

    /** Rejected-batch waiting retains interruption and follows normal owner cleanup. */
    @Test
    public void interruptionStopsObservationRecovery()
    {
        var session = new Model();
        session.rejectEveryHost = true;
        try
        {
            requireException(java.io.InterruptedIOException.class, () -> new SchedulerCoordinator<>(session,
                new Policy.Budget(0, 2), () -> { throw new InterruptedException("cancel observation retry"); }).run());
            requireThat(Thread.currentThread().isInterrupted(), "interruptionRetained").isTrue();
            requireThat(session.hostReads, "noSampleAfterInterruption").isEqualTo(1);
        }
        finally { Thread.interrupted(); }
    }

    /** Typed exceptions from protocol operations are terminal and uncertain delivery is never repeated. */
    @Test
    public void rejectsRetryOutsideMeasurements()
    {
        for (String boundary : List.of("pump", "poll", "close", "delivery", "start"))
        {
            var session = new Model();
            session.typedFault = boundary;
            requireException(IOException.class, () -> new SchedulerCoordinator<>(session,
                new Policy.Budget(0, 2), () -> {}).run());
            requireThat(session.events.contains("stop-input"), "terminalNonMeasurementFault:" + boundary).isTrue();
            requireThat(session.delivered.size() <= 1, "noUncertainResponseRetry").isTrue();
        }
    }

    /** Controlled session; no native or external build-tool behavior is represented as accepted evidence. */
    private static final class Model implements SchedulerCoordinator.Session<Integer>
    {
        /** Ordered lifecycle events for boundary assertions. */
        private final List<String> events = new ArrayList<>();
        /** Natural native outcomes already delivered to the original action protocol. */
        private final List<String> delivered = new ArrayList<>();
        /** All accepted native attempt receipts, including aborted attempts. */
        private final List<String> observed = new ArrayList<>();
        /** Copied newly discovered submissions awaiting coordinator admission. */
        private final ArrayDeque<SchedulerCoordinator.Submission> inputs = new ArrayDeque<>();
        /** Copied allocation notifications delivered outside native control calls. */
        private final ArrayDeque<SchedulerCoordinator.Blocked> blocked = new ArrayDeque<>();
        /** Modeled native owners retained until successful close. */
        private final Map<Long, Owner> owners = new LinkedHashMap<>();
        /** Whether initial action discovery has occurred. */
        private boolean seeded;
        /** Whether the authentic root status arrives before action completion. */
        private boolean rootEarly;
        /** Whether the first native owner reports a blocked allocation. */
        private boolean blockFirst;
        /** Whether the resumed first attempt reports an aborted result. */
        private boolean abortFirst;
        /** Whether memory acquisition fails for action B. */
        private boolean failSecondTree;
        /** Whether publication fails after its modeled visible effect. */
        private boolean failDelivery;
        /** Whether native owners retain unresolved cleanup. */
        private boolean failClose;
        /** Whether producer service fails before discovery. */
        private boolean failPump;
        /** Whether stopping producer admission fails. */
        private boolean failStop;
        /** Whether closing outer dependencies fails. */
        private boolean failOuter;
        /** Whether interruptible cleanup rejects a pending owner-thread cancellation flag. */
        private boolean rejectInterruptedCleanup;
        /** Whether producer stop supplies a new interrupt that later owner cleanup must preserve and clear. */
        private boolean interruptAfterStop;
        /** Whether discovered actions lack memory estimates. */
        private boolean unknown;
        /** Largest number of simultaneously retained modeled owners. */
        private int maximumLive;
        /** Number of fresh complete host measurement attempts. */
        private int hostReads;
        /** Remaining service calls before discovery may close. */
        private int discoveryDelay;
        /** Whether a later complete host read fails after authentic owner progress. */
        private boolean failAfterCompletion;
        /** Rejects one batch after the queue has accepted the first authentic outcome. */
        private boolean rejectAfterCompletion;
        /** Models both owners completing between reconciliation and host acquisition. */
        private boolean completeOnRejection;
        /** Rejects every host batch to exercise bounded recovery. */
        private boolean rejectEveryHost;
        /** Independently established reason retained by the modeled provider. */
        private ObservationRejected.Reason rejectionReason = ObservationRejected.Reason.COMPLETED_LEAVES;
        /** First host observation rejected through the end of this invocation. */
        private int rejectHostAfter = Integer.MAX_VALUE;
        /** Rejects live-tree sampling while keeping modeled owners active. */
        private boolean rejectResident;
        /** Exact host-read ordinals rejected to test streak reset. */
        private Set<Integer> rejectedReads = Set.of();
        /** Nonmeasurement boundary that fails with an accidentally typed exception. */
        private String typedFault = "";

        /** Creates an independent modeled invocation with no acquired owners. */
        private Model() {}

        /**
         * Models a provider's independently established churn; it supplies no sample or completion.
         * @return controlled rejection for the measurement boundary under test
         */
        private ObservationRejected rejection()
        {
            return new ObservationRejected(ObservationRejected.Operation.HOST,
                rejectionReason,
                List.of(new ObservationRejected.Attempt("A", 0)), new IOException("trusted churn"));
        }

        @Override public JobQueue.AttemptFactory<Integer> attempts()
        {
            return (name, sequence) ->
            {
                var owner = new Owner(name, sequence);
                owners.put(sequence, owner);
                return owner;
            };
        }
        @Override public Controller.Measurements measurements()
        {
            return new Controller.Measurements()
            {
                @Override public Controller.Memory readHost() throws IOException
                {
                    if (++hostReads == 4 && failAfterCompletion) throw new IOException("later host unavailable");
                    if (rejectEveryHost || hostReads >= rejectHostAfter || rejectedReads.contains(hostReads)) throw rejection();
                    if (hostReads == 3 && completeOnRejection)
                    {
                        owners.values().forEach(owner -> owner.completed = true);
                        throw new ObservationRejected(ObservationRejected.Operation.HOST,
                            ObservationRejected.Reason.COMPLETED_LEAVES,
                            List.of(new ObservationRejected.Attempt("A", 0), new ObservationRejected.Attempt("B", 1)),
                            new IOException("two genuine modeled owner completions"));
                    }
                    if (hostReads == 4 && rejectAfterCompletion)
                        throw new ObservationRejected(ObservationRejected.Operation.HOST,
                            ObservationRejected.Reason.COMPLETED_LEAVES,
                            List.of(new ObservationRejected.Attempt("B", 1)), new IOException("trusted churn"));
                    return new Controller.Memory(100, 0, false);
                }
                @Override public long readResidentBytes(Policy.Job job) throws IOException
                {
                    if (rejectResident) throw rejection();
                    if (failSecondTree && job.name().equals("B")) throw new IOException("tree B unavailable");
                    return 0;
                }
            };
        }
        @Override public void pump() throws IOException
        {
            if (typedFault.equals("pump")) throw rejection();
            if (failPump) throw new IOException("pump failed");
            if (discoveryDelay > 0) discoveryDelay--;
            if (seeded == false)
            {
                inputs.add(new SchedulerCoordinator.Submission("A", unknown ? null : 0L));
                inputs.add(new SchedulerCoordinator.Submission("B", unknown ? null : 0L));
                seeded = true;
            }
        }
        @Override public Optional<SchedulerCoordinator.Submission> pollSubmission()
        { return Optional.ofNullable(inputs.pollFirst()); }
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked()
        { return Optional.ofNullable(blocked.pollFirst()); }
        @Override public void acceptOutcome(JobQueue.Completion<Integer> completion) throws IOException
        {
            observed.add(completion.name() + ":" + completion.attemptSequence() + ":" + completion.outcome().aborted());
            if (completion.outcome().aborted()) return;
            String name = completion.name() + ":" + completion.attemptSequence();
            events.add("deliver:" + name);
            delivered.add(name + ":" + completion.outcome().result());
            if (failDelivery) throw new IOException("uncertain response");
            if (typedFault.equals("delivery")) throw rejection();
        }
        @Override public boolean inputClosed() { return seeded && inputs.isEmpty() && discoveryDelay == 0; }
        @Override public OptionalInt pollToolResult()
        { return rootEarly || delivered.size() == 2 ? OptionalInt.of(23) : OptionalInt.empty(); }
        @Override public boolean hasOpenAttempts() { return owners.isEmpty() == false; }
        @Override public void stopAccepting() throws IOException
        {
            events.add("stop-input");
            requireCleanupUninterrupted();
            if (failStop) throw new IOException("stop failed");
            if (interruptAfterStop) Thread.currentThread().interrupt();
        }
        @Override public void close() throws IOException
        {
            events.add("session-close");
            requireCleanupUninterrupted();
            if (failOuter) throw new IOException("outer close failed");
        }

        /**
         * Models an interruptible native cleanup boundary without clearing the caller's flag itself.
         * @throws InterruptedIOException if cancellation would prevent a bounded cleanup acknowledgement
         */
        private void requireCleanupUninterrupted() throws InterruptedIOException
        {
            if (rejectInterruptedCleanup && Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("cleanup entered with cancellation interrupt");
        }

        /** Modeled exclusive owner retained until its successful cleanup. */
        private final class Owner implements JobQueue.Attempt<Integer>
        {
            /** Original logical action name of this exclusive owner. */
            private final String name;
            /** Original native attempt ordinal of this owner. */
            private final long sequence;
            /** Number of authentic modeled outcome polls. */
            private int polls;
            /** Whether this owner awaits an allocation response. */
            private boolean waiting;
            /** Whether the exact blocked allocation has been resumed. */
            private boolean resumed;
            /** Authentic modeled owner completion, independent of measurement values. */
            private boolean completed;
            /**
             * Creates a resource-free owner for one original action attempt.
             * @param name original logical action name
             * @param sequence original attempt ordinal
             */
            private Owner(String name, long sequence) { this.name = name; this.sequence = sequence; }
            @Override public void start() throws IOException
            {
                events.add("start:" + name + ":" + sequence);
                if (typedFault.equals("start")) throw rejection();
                maximumLive = Math.max(maximumLive, owners.size());
                if (blockFirst && sequence == 0)
                {
                    waiting = true;
                    blocked.add(new SchedulerCoordinator.Blocked(name, sequence, 9));
                }
            }
            @Override public void resume(long wait)
            {
                events.add("resume:" + name + ":" + sequence + ":" + wait);
                waiting = false;
                resumed = true;
            }
            @Override public void terminate() { events.add("terminate:" + name + ":" + sequence); }
            @Override public Optional<JobQueue.Outcome<Integer>> poll() throws IOException
            {
                if (typedFault.equals("poll")) throw rejection();
                if (rejectResident) return Optional.empty();
                if (!completed && (waiting || ++polls < 3)) return Optional.empty();
                boolean aborted = abortFirst && sequence == 0 && resumed;
                return Optional.of(new JobQueue.Outcome<>(7, aborted));
            }
            @Override public void close() throws IOException
            {
                events.add("close:" + name + ":" + sequence);
                requireCleanupUninterrupted();
                if (typedFault.equals("close")) throw rejection();
                if (failClose) throw new IOException("owner close failed");
                owners.remove(sequence);
            }
        }
    }
}
