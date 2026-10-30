package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.testng.annotations.Test;

/** Exercises the production attempt lifecycle with controlled owners, not native Windows claims. */
public final class WindowsAttemptTest
{
    static WindowsAttempts factory(Resources resources)
    {
        return new WindowsAttempts(UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Map.of("compile", WindowsAttemptsTest.command("compile", 0)), Duration.ofMillis(20),
            (command, sequence, identity, timeout) -> resources);
    }

    @Test
    public void rootOnlyExitCannotPublishOrUnloadAndUnsignedResultIsPreserved() throws Exception
    {
        Resources resources = new Resources();
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        requireThat(resources.events.isEmpty(), "constructionHasNoAcquisition").isTrue();
        attempt.start();
        requireThat(resources.events.subList(0, 4), "acquisitionOrder").isEqualTo(List.of("prepare", "register", "hooks", "start"));
        requireThat(attempt.poll().isEmpty(), "rootExitWithLivingChildRemainsIncomplete").isTrue();
        requireThat(resources.events.contains("hook-close"), "noRootOnlyTeardown").isFalse();
        resources.result = new WindowsProcess.Result(0xC0000005L, false);
        var outcome = attempt.poll().orElseThrow();
        requireThat(outcome.result(), "authenticUnsignedResult").isEqualTo(resources.result);
        requireThat(outcome.aborted(), "nonzeroIsNotInferredAbortion").isFalse();
        attempt.close();
        requireThat(resources.events.indexOf("hook-close") < resources.events.indexOf("unregister"), "JobBarrierBeforeRegistrationRetirement").isTrue();
        requireThat(resources.events.indexOf("unregister") < resources.events.indexOf("process-close"), "registrationRetiredBeforeProcessRelease").isTrue();
        requireThat(factory.hasOpenAttempts(), "fullyCleanedOwnerForgotten").isFalse();
    }

    @Test
    public void terminationIsOneShotAndCloseCannotInventOutcome() throws Exception
    {
        Resources resources = new Resources();
        WindowsAttempt attempt = factory(resources).create("compile", 0);
        attempt.start();
        attempt.terminate();
        attempt.terminate();
        requireThat(resources.terminations, "oneTerminationRequest").isEqualTo(1);
        requireThat(attempt.poll().isEmpty(), "terminationRequestIsNotCollection").isTrue();
        resources.result = new WindowsProcess.Result(37, true);
        attempt.close();
        requireThat(resources.events.contains("process-close"), "closedOnlyAfterActualResult").isTrue();
    }

    @Test
    public void ambiguousTerminationIsNeverRetriedAndOwnerRemainsVisible() throws Exception
    {
        Resources resources = new Resources();
        resources.failTerminate = true;
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        attempt.start();
        requireException(IOException.class, attempt::terminate);
        requireException(IOException.class, attempt::terminate);
        requireException(IOException.class, attempt::close);
        requireThat(resources.terminations, "uncertainRequestNotRepeated").isEqualTo(1);
        requireThat(resources.events.contains("process-close"), "noDestructionWithoutJobZero").isFalse();
        requireThat(factory.hasOpenAttempts(), "unresolvedOwnerRemainsRegistered").isTrue();
    }

    @Test
    public void lateActualZeroReconcilesOwnersWithoutErasingFirstCleanupFault() throws Exception
    {
        Resources resources = new Resources();
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        attempt.start();
        IOException first = requireException(IOException.class, attempt::close);
        requireThat(factory.hasOpenAttempts(), "retainedBeforeActualZero").isTrue();
        requireThat(resources.events.contains("hook-close"), "noEarlyNativeDestruction").isFalse();
        resources.result = new WindowsProcess.Result(1, true);
        IOException retained = requireException(IOException.class, attempt::close);
        requireThat(resources.terminations, "noRepeatedCancellation").isEqualTo(1);
        requireThat(resources.events.stream().filter("hook-close"::equals).count(), "hookReleasedOnce").isEqualTo(1L);
        requireThat(resources.events.stream().filter("unregister"::equals).count(), "registrationReleasedOnce").isEqualTo(1L);
        requireThat(resources.events.stream().filter("process-close"::equals).count(), "processReleasedOnce").isEqualTo(1L);
        requireThat(factory.hasOpenAttempts(), "reconciledOwnerForgotten").isFalse();
        requireThat(retained, "firstCleanupFaultRetained").isEqualTo(first);
        requireException(IOException.class, attempt::poll);
        requireException(IOException.class, attempt::close);
    }

    @Test
    public void partialPreparationCapsuleRemainsReachableForCleanup() throws Exception
    {
        Resources resources = new Resources();
        resources.failPrepare = true;
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        requireException(IOException.class, attempt::start);
        requireException(IOException.class, attempt::close);
        requireThat(resources.events, "partialPrepareReconciledWithoutInventedProcess").isEqualTo(List.of("prepare", "preparation-close"));
        requireThat(factory.hasOpenAttempts(), "safelyCleanedNativeOwnershipForgotten").isFalse();
        requireException(IOException.class, attempt::close);
    }

    @Test
    public void uncertainCapsuleCleanupRemainsRetained() throws Exception
    {
        Resources resources = new Resources();
        resources.failPrepare = true;
        resources.failPreparationClose = true;
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        requireException(IOException.class, attempt::start);
        requireException(IOException.class, attempt::close);
        requireException(IOException.class, attempt::close);
        requireThat(resources.events, "noUncertainNativeReleaseRetry").isEqualTo(List.of("prepare", "preparation-close"));
        requireThat(resources.preparationReconciliations, "safeCapsuleReobservations").isEqualTo(2);
        requireThat(factory.hasOpenAttempts(), "failedCapsuleOwnerVisible").isTrue();
    }

    @Test
    public void attachFailureCancelsBeforeHookCleanupAndRetainsOriginalFault() throws Exception
    {
        Resources resources = new Resources();
        resources.failStart = true;
        resources.completeOnTerminate = true;
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        IOException original = requireException(IOException.class, attempt::start);
        IOException retained = requireException(IOException.class, attempt::close);
        requireThat(retained.getCause(), "originalAttachFaultRetained").isEqualTo(original);
        requireThat(resources.events.indexOf("terminate") < resources.events.indexOf("hook-close"), "cancelThenActualBarrierThenUnload").isTrue();
        requireThat(resources.events.indexOf("hook-close") < resources.events.indexOf("unregister"), "registrationRemainsThroughSTOPPING").isTrue();
        requireThat(factory.hasOpenAttempts(), "safeCleanupReleasesOwnershipDespiteOperationFailure").isFalse();
    }

    @Test
    public void failedHookDestructionPreventsRegistrationAndProcessRelease() throws Exception
    {
        Resources resources = new Resources();
        WindowsAttempts factory = factory(resources);
        WindowsAttempt attempt = factory.create("compile", 0);
        attempt.start();
        resources.result = new WindowsProcess.Result(23, false);
        resources.failHookClose = true;
        requireException(IOException.class, attempt::poll);
        requireException(IOException.class, attempt::close);
        requireThat(resources.events.contains("unregister"), "registrationRetainedForFailedInstrumentation").isFalse();
        requireThat(resources.events.contains("process-close"), "processOwnerRetained").isFalse();
        requireThat(factory.hasOpenAttempts(), "failedCloseRemainsVisible").isTrue();
    }

    /** Owned simulated Job/registration/hooks supporting precise lifecycle-failure injection. */
    static final class Resources implements WindowsAttempt.Resources, WindowsAttempt.Process, WindowsAttempt.Hooks
    {
        final List<String> events = new ArrayList<>();
        AllocationWaits waits;
        AllocationHook.Pump peers;
        WindowsProcess.Result result;
        int terminations;
        int preparationReconciliations;
        IOException preparationCloseFailure;
        IOException hookCloseFailure;
        boolean failPrepare;
        boolean failPreparationClose;
        boolean failStart;
        boolean failHookClose;
        boolean failTerminate;
        boolean completeOnTerminate;
        AllocationHook.Pump onStart = () -> {};
        AllocationHook.Pump onGrant = () -> {};
        @Override public WindowsAttempt.Process prepare() throws IOException
        { events.add("prepare"); if (failPrepare) throw new IOException("Owned partial preparation"); return this; }
        @Override public WindowsAttempt.Close register(WindowsAttempt.Process process)
        { events.add("register"); return () -> events.add("unregister"); }
        @Override public WindowsAttempt.Hooks hooks(WindowsAttempt.Process process, AllocationWaits waits, AllocationHook.Pump peers)
        { events.add("hooks"); this.waits = waits; this.peers = peers; return this; }
        @Override public void closePreparationFailure() throws IOException
        {
            if (failPrepare)
            {
                ++preparationReconciliations;
                // Model the accepted capsule: another observation cannot repeat an uncertain native release.
                if (preparationCloseFailure != null) throw new IOException("Retained preparation cleanup fault", preparationCloseFailure);
                events.add("preparation-close");
                if (failPreparationClose)
                {
                    preparationCloseFailure = new IOException("Unresolved preparation cleanup");
                    throw preparationCloseFailure;
                }
            }
        }
        @Override public void start() throws IOException
        { events.add("start"); onStart.pump(); if (failStart) throw new IOException("Attach failed"); }
        @Override public Optional<WindowsProcess.Result> poll() { events.add("poll"); return Optional.ofNullable(result); }
        @Override public void terminate() throws IOException
        {
            events.add("terminate"); ++terminations;
            if (failTerminate) throw new IOException("Native request ambiguous");
            if (completeOnTerminate) result = new WindowsProcess.Result(1, true);
        }
        @Override public void close() { events.add("process-close"); }
        @Override public void pump(boolean advance) { events.add("pump:" + advance); }
        @Override public void grant(AllocationWaits.Wait wait) throws IOException { events.add("grant:" + wait); onGrant.pump(); }
        @Override public void stop() { events.add("stop"); }
        @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
        {
            if (barrier.reached() == false) throw new IOException("Live Job");
            if (hookCloseFailure != null) throw new IOException("Retained script cleanup fault", hookCloseFailure);
            events.add("hook-close");
            if (failHookClose)
            {
                hookCloseFailure = new IOException("Script close uncertain");
                throw hookCloseFailure;
            }
        }
    }
}
