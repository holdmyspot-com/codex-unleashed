package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**

 * Exercises image lineage and independent cleanup with per-test scopes and no native processes.

 */
public final class MacImagesTest
{
    /**
     * Skips this class on file systems without POSIX attributes, which its POSIX-only production code requires.
     *
     * @throws org.testng.SkipException if the default file system lacks the POSIX attribute view
     */
    @org.testng.annotations.BeforeClass
    public void requirePosix()
    {
        TestRequirements.requirePosixFileSystem();
    }

    /**
     * Creates stateless tests whose fixtures are owned independently by each method.
     */
    public MacImagesTest() {}

    /**
     * Retires only an independently authenticated replaced generation before the successor can execute.
     * @throws IOException if accepted image admission or eventual cleanup fails
     */
    @Test public void authenticatedExecRetiresOnlyExactPredecessor() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            MacImages.WorkloadAuthority authority = new MacImages.WorkloadAuthority()
            {
                @Override public void authenticate(MacImages.Identity identity) throws IOException
                {
                    if (!identity.equals(calls.inspect(identity.pid()))) throw new IOException("Changed native image");
                }
                @Override public void authenticateChild(FridaRuntime.Child child, MacImages.Identity observed) throws IOException
                {
                    authenticate(observed);
                    if (child.pid() != observed.pid()) throw new IOException("Foreign held event");
                }
            };
            var owner = new MacImages(scope, calls, authority, calls::instrumentation, root,
                Duration.ofSeconds(1), System::nanoTime);
            owner.start();
            owner.advance(new FridaRuntime.Child(11, 10, 0, "/fixture/child"));
            var predecessor = calls.connections.get(1);
            predecessor.detached = true;
            calls.instrumentationOwners.get(1).detached = true;
            owner.advance(new FridaRuntime.Child(11, 11, 1, "/fixture/successor"));
            requireThat(predecessor.closed, "authenticatedPredecessorReferencesReleasedBeforeWholeWorkloadEnd").isTrue();
            requireThat(calls.connections.getFirst().closed, "unrelatedRootStillOwned").isFalse();
            requireThat(calls.connections.getLast().closed, "successorStillOwned").isFalse();
            requireThat(calls.resumed, "successorReleasedOnlyAfterRequiredReadiness").isEqualTo(List.of(11L, 11L), "expected");
            owner.close(() -> true);
            requireThat(calls.connections.stream().allMatch(value -> value.closeCalls == 1),
                "predecessorDestructionNeverRepeatedAtWholeWorkloadBarrier").isTrue();
        }
    }

    /**
     * Keeps a live or uncertain exact predecessor owned and never installs or releases its successor.
     * @throws IOException if controlled setup or eventual whole-workload cleanup fails
     */
    @Test public void authenticatedExecRequiresEveryExactSessionDetached() throws IOException
    {
        for (int missing = 0; missing < 3; missing++)
        {
            try (var scope = new DefaultToolingScope())
            {
                var calls = new Calls(scope.getLeaderJsonMapper());
                var root = calls.add(10, 1, 9);
                calls.add(11, 2, 10);
                MacImages.WorkloadAuthority authority = new MacImages.WorkloadAuthority()
                {
                    @Override public void authenticate(MacImages.Identity identity) throws IOException
                    {
                        if (!identity.equals(calls.inspect(identity.pid()))) throw new IOException("Changed image");
                    }
                    @Override public void authenticateChild(FridaRuntime.Child child, MacImages.Identity observed) throws IOException
                    { authenticate(observed); }
                };
                var owner = new MacImages(scope, calls, authority, calls::instrumentation, root,
                    Duration.ofSeconds(1), System::nanoTime);
                owner.start();
                owner.advance(new FridaRuntime.Child(11, 10, 0, "/fixture/child"));
                calls.connections.get(1).detached = missing != 0;
                calls.instrumentationOwners.get(1).detached = missing != 1;
                if (missing == 2) calls.connections.get(1).detachFailure = new IOException("Native observation unavailable");
                requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 11, 1, "/fixture/next")));
                requireThat(calls.connections.size(), "noSuccessorAttachmentWithoutDetachedProof").isEqualTo(2);
                requireThat(calls.connections.get(1).closeCalls, "uncertainPredecessorRetained").isEqualTo(0);
                requireThat(calls.resumed.size(), "successorNeverReleased").isEqualTo(1);
                requireException(IOException.class, () -> owner.close(() -> false));
                owner.close(() -> true);
                requireThat(calls.connections.get(1).closeCalls, "laterRealWorkloadBarrierAllowsCleanup").isEqualTo(1);
            }
        }
    }

    /**
     * Preserves an uncertain exact-image destruction outcome and continues independent whole-workload cleanup.
     * @throws IOException if controlled setup fails
     */
    @Test public void detachedPredecessorFailureNeverReplaysDestruction() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            MacImages.WorkloadAuthority authority = new MacImages.WorkloadAuthority()
            {
                @Override public void authenticate(MacImages.Identity identity) throws IOException
                { if (!identity.equals(calls.inspect(identity.pid()))) throw new IOException("Changed native image"); }
                @Override public void authenticateChild(FridaRuntime.Child child, MacImages.Identity observed) throws IOException
                { authenticate(observed); }
            };
            var owner = new MacImages(scope, calls, authority, calls::instrumentation, root,
                Duration.ofSeconds(1), System::nanoTime);
            owner.start();
            owner.advance(new FridaRuntime.Child(11, 10, 0, "/fixture/child"));
            var predecessor = calls.instrumentationOwners.get(1);
            predecessor.detached = true;
            calls.connections.get(1).detached = true;
            IOException destruction = new IOException("Uncertain exact predecessor release");
            predecessor.closeFailure = destruction;
            requireThat(requireException(IOException.class,
                () -> owner.advance(new FridaRuntime.Child(11, 11, 1, "/fixture/next"))), "originalFailure").isEqualTo(destruction);
            requireException(IOException.class, () -> owner.close(() -> true));
            requireException(IOException.class, () -> owner.close(() -> true));
            requireThat(predecessor.closeCalls, "uncertainExactImageReleaseNeverReplayed").isEqualTo(1);
            requireThat(calls.connections.get(1).closeCalls, "dependentConnectionRetained").isEqualTo(0);
            requireThat(calls.connections.getFirst().closeCalls, "independentRootCleanupStillRuns").isEqualTo(1);
            requireThat(owner.isClosed(), "uncertainOwnerStillRetained").isFalse();
        }
    }

    /**
     * Admits an ordinary orphan using native workload authority after its original parent has exited.
     * @throws IOException if accepted instrumentation or cleanup fails
     */
    @Test public void nativeWorkloadAdmitsAuthenticatedOrphan() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 1);
            MacImages.WorkloadAuthority authority = new MacImages.WorkloadAuthority()
            {
                @Override public void authenticate(MacImages.Identity identity) throws IOException
                {
                    if (identity.pid() != 10 && identity.pid() != 11) throw new IOException("Foreign coalition");
                }
                @Override public void authenticateChild(FridaRuntime.Child child, MacImages.Identity observed) throws IOException
                {
                    authenticate(observed);
                    if (child.pid() != observed.pid()) throw new IOException("Wrong held child");
                }
            };
            var owner = new MacImages(scope, calls, authority, identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root,
                Duration.ofSeconds(1), System::nanoTime);
            owner.start();
            calls.identities.remove(10L);
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            requireThat(calls.resumed.equals(List.of(11L)), "authenticatedOrphanReleasedOnce").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Treats a child that exited while Frida attached to it as an ordinary exit: it is neither resumed nor a failure,
     * and the owner keeps admitting the workload's other children.
     * @throws IOException if the owner rejects the vanished child or fails cleanup
     */
    @Test public void childVanishingDuringAttachIsAnOrdinaryExit() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            calls.add(12, 3, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            // Frida reports this failure when the attached child exits before its threads can be read.
            calls.startFailure = new FridaRuntime.NativeFailure("frida_device_attach_sync", "frida-error-quark", 10,
                "get_thread_state returned '(os/kern) protection failure'");
            calls.beforeStartFailure = () -> calls.identities.remove(11L);
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            calls.startFailure = null;
            owner.advance(new FridaRuntime.Child(12, 10, 0, ""));
            requireThat(calls.resumed, "onlyLiveChildResumed").isEqualTo(List.of(12L), "expected");
            owner.close(() -> true);
            requireThat(owner.isClosed(), "vanishedChildConnectionClosed").isTrue();
        }
    }

    /**
     * Treats a child whose process identifier was reused while Frida attached to it as an ordinary exit.
     * @throws IOException if the owner rejects the replaced child or fails cleanup
     */
    @Test public void childReplacedDuringAttachIsAnOrdinaryExit() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.startFailure = new FridaRuntime.NativeFailure("frida_device_attach_sync", "frida-error-quark", 10,
                "get_thread_state returned '(os/kern) protection failure'");
            calls.beforeStartFailure = () -> calls.add(11, 5, 10);
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            requireThat(calls.resumed.isEmpty(), "replacedChildNotResumed").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Keeps an attach failure on a child that is still live a failure of the image owner.
     * @throws IOException if cleanup fails
     */
    @Test public void attachFailureOnLiveChildFails() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            var failure = new FridaRuntime.NativeFailure("frida_device_attach_sync", "frida-error-quark", 10,
                "get_thread_state returned '(os/kern) protection failure'");
            calls.startFailure = failure;
            requireThat(requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, ""))) ==
                failure, "originalAttachFailure").isTrue();
            requireThat(calls.resumed.isEmpty(), "liveChildNotResumed").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Arms the held root, a child and its same-birth exec before each explicit child resume.
     * @throws IOException if accepted observations are refused
     */
    @Test public void rootChildAndExecPreserveReadinessOrder() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            requireThat(calls.resumed.isEmpty(), "rootRemainsHeldByCaller").isTrue();
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            owner.advance(new FridaRuntime.Child(11, 11, 1, "/fixture/child"));
            requireThat(calls.resumed.equals(List.of(11L, 11L)), "oneResumePerAcceptedImage").isTrue();
            requireThat(calls.connections.size(), "retainedGenerations").isEqualTo(3, "expected");
            requireThat(calls.instrumentationOwners.size(), "freshInstrumentationPerGeneration").isEqualTo(3, "expected");
            requireThat(calls.instrumentationOwners.get(1).identity.equals(calls.instrumentationOwners.get(2).identity),
                "sameBirthExecGetsDistinctOwner").isTrue();
            requireThat(calls.instrumentationOwners.get(1) == calls.instrumentationOwners.get(2), "ownersAreDistinct").isFalse();
            owner.close(() -> true);
            requireThat(owner.isClosed(), "allGenerationsClosed").isTrue();
            requireThat(calls.connections.stream().allMatch(c -> c.closeCalls == 1), "eachIndependentCloseOnce").isTrue();
            requireThat(calls.instrumentationOwners.stream().allMatch(c -> c.closeCalls == 1), "everyInstrumentationGenerationClosed").isTrue();
        }
    }

    /**
     * Requires caller instrumentation readiness before releasing a held child.
     * @throws IOException if image readiness or cleanup fails
     */
    @Test public void instrumentationAcknowledgedBeforeChildResume() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.requireInstrumentation = true;
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            requireThat(calls.resumed.equals(List.of(11L)), "instrumentedChildReleasedOnce").isTrue();
            requireThat(calls.instrumentationOwners.getLast().identity.equals(calls.identities.get(11L)), "exactValidatedImage").isTrue();
            requireThat(calls.events.indexOf("instrumentation-ready:11") < calls.events.indexOf("resume:11"), "acknowledgementPrecedesRelease").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Refuses foreign parents and then permanently withdraws child control authority.
     * @throws IOException if root setup or safe cleanup fails
     */
    @Test public void foreignParentStopsLaterControls() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 99);
            var owner = owner(scope, calls, root); owner.start();
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireException(IllegalStateException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireThat(calls.connections.size(), "noForeignAttachment").isEqualTo(1, "expected");
            requireThat(calls.resumed.isEmpty(), "noForeignResume").isTrue(); owner.close(() -> true);
        }
    }

    /**
     * Rejects a same-PID exec with a different native birth.
     * @throws IOException if root setup or cleanup fails
     */
    @Test public void changedExecBirthIsRejected() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
            var owner = owner(scope, calls, root); owner.start(); calls.add(10, 3, 9);
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(10, 10, 1, "/fixture/root")));
            requireThat(calls.resumed.isEmpty(), "reusedPidNeverResumed").isTrue(); owner.close(() -> true);
        }
    }

    /**
     * Observes another native birth after readiness and refuses release.
     * @throws IOException if root setup or cleanup fails
     */
    @Test public void revalidatesAfterReadiness() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9); calls.add(11, 2, 10);
            var owner = owner(scope, calls, root); owner.start();
            calls.afterReady = () -> calls.add(11, 7, 10);
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireThat(calls.resumed.isEmpty(), "changedBirthNeverResumed").isTrue(); owner.close(() -> true);
        }
    }

    /**
     * Retains partial acquisition and closes it only after the trusted barrier.
     * @throws IOException if eventual cleanup fails
     */
    @Test public void partialStartRemainsOwned() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
            var original = new IOException("partial start"); calls.startFailure = original;
            var owner = owner(scope, calls, root);
            requireThat(requireException(IOException.class, owner::start) == original, "exactStartFailure").isTrue();
            requireException(IOException.class, () -> owner.close(() -> false));
            requireThat(calls.connections.getFirst().closeCalls, "noCloseWithoutBarrier").isEqualTo(0, "expected");
            owner.close(() -> true); requireThat(owner.isClosed(), "partialOwnerClosed").isTrue();
        }
    }

    /**
     * Unknown barriers preserve every connection and permit a later independent observation.
     * @throws IOException if root setup or eventual cleanup fails
     */
    @Test public void unknownBarrierDoesNotDestroyOwners() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
            var owner = owner(scope, calls, root); owner.start(); var unknown = new IOException("unknown barrier");
            requireThat(requireException(IOException.class, () -> owner.close(() -> { throw unknown; })) == unknown, "exactBarrierFailure").isTrue();
            requireThat(calls.connections.getFirst().closeCalls, "ownersRetained").isEqualTo(0, "expected");
            owner.close(() -> true);
        }
    }

    /**
     * Cleanup continues across independent failed images and never retries uncertain connection closes.
     * @throws IOException if initial accepted images fail
     */
    @Test public void independentFailuresRetainOwnersWithoutReplay() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9); calls.add(11, 2, 10);
            var owner = owner(scope, calls, root); owner.start(); owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            var first = new IOException("child close"); var second = new IOException("root close");
            calls.connections.get(1).closeFailure = first; calls.connections.getFirst().closeFailure = second;
            requireThat(requireException(IOException.class, () -> owner.close(() -> true)) == first, "exactFirstCloseFailure").isTrue();
            requireThat(first.getSuppressed().length, "secondaryRetained").isEqualTo(1, "expected");
            requireThat(first.getSuppressed()[0] == second, "exactSecondary").isTrue();
            requireException(IOException.class, () -> owner.close(() -> true));
            requireThat(calls.connections.stream().allMatch(c -> c.closeCalls == 1), "uncertainClosesNotReplayed").isTrue();
            requireThat(owner.isClosed(), "failedOwnersRemainOpen").isFalse();
        }
    }

    /**
     * A failed connection does not block successful closure of an unrelated image.
     * @throws IOException if initial images fail
     */
    @Test public void failedImageDoesNotBlockPeerCleanup() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9); calls.add(11, 2, 10);
            var owner = owner(scope, calls, root); owner.start(); owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            calls.connections.get(1).closeFailure = new IOException("child close");
            requireException(IOException.class, () -> owner.close(() -> true));
            requireThat(calls.connections.getFirst().closed, "independentRootClosed").isTrue();
        }
    }

    /**
     * Readiness and native resumption failures never result in a second resume attempt.
     * @throws IOException if setup or cleanup fails
     */
    @Test public void uncertainResumeStopsReplay() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9); calls.add(11, 2, 10);
            var owner = owner(scope, calls, root); owner.start(); calls.resumeFailure = new IOException("uncertain resume");
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireException(IllegalStateException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireThat(calls.resumed.size(), "onePotentialResume").isEqualTo(1, "expected"); owner.close(() -> true);
        }
    }

    /**
     * Rejects a wrong readiness receipt and a bounded missing receipt without native resumption.
     * @throws IOException if cleanup fails
     */
    @Test public void malformedAndAbsentReadinessNeverRelease() throws IOException
    {
        for (boolean silent : new boolean[] {false, true})
        {
            try (var scope = new DefaultToolingScope())
            {
                var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
                calls.silent = silent; calls.badReady = !silent;
                var ticks = new java.util.concurrent.atomic.AtomicLong();
                var owner = new MacImages(scope, calls, identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root, Duration.ofNanos(2), ticks::getAndIncrement);
                requireException(IOException.class, owner::start);
                requireThat(calls.resumed.isEmpty(), "unreadyNeverResumed").isTrue();
                requireThat(calls.instrumentationOwners.isEmpty(), "unvalidatedImageNeverReachesCaller").isTrue();
                owner.close(() -> true);
            }
        }
    }

    /**
     * Rejects an exact readiness frame observed at the deadline before invoking caller instrumentation.
     * @throws IOException if trusted post-failure cleanup fails
     */
    @Test public void lateExactReadinessNeverReleases() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
            var ticks = new java.util.concurrent.atomic.AtomicLong();
            calls.afterReady = () -> ticks.set(10);
            var owner = new MacImages(scope, calls, identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root, Duration.ofNanos(10), ticks::get);
            requireException(IOException.class, owner::start);
            requireThat(calls.instrumentationOwners.isEmpty(), "lateReadinessNeverReachesCaller").isTrue();
            requireThat(calls.resumed.isEmpty(), "lateReadinessNeverResumes").isTrue();
            owner.close(() -> true);
            requireThat(owner.isClosed(), "failedReadinessClosedAfterBarrier").isTrue();
        }
    }

    /**
     * Preserves the acquisition failure while retaining a separate cleanup error and cleaning its peer.
     * @throws IOException if initial root setup fails
     */
    @Test public void uncheckedCleanupPreservesPrimaryAndIndependentPeer() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9); calls.add(11, 2, 10);
            var owner = owner(scope, calls, root); owner.start();
            var primary = new IOException("child acquisition"); calls.startFailure = primary;
            requireThat(requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, ""))) == primary, "exactPrimary").isTrue();
            var cleanup = new AssertionError("uncertain child destruction"); calls.connections.get(1).closeFailure = cleanup;
            requireThat(requireException(AssertionError.class, () -> owner.close(() -> true)) == cleanup, "exactUncheckedCleanup").isTrue();
            requireThat(calls.connections.getFirst().closed, "independentRootCleaned").isTrue();
            requireThat(primary.getSuppressed()[0] == cleanup, "cleanupRetainedOnOriginal").isTrue();
        }
    }

    /**
     * Rejects a foreign root and wrong-thread calls before connection reservation.
     * @throws Exception if thread collection or safe cleanup fails
     */
    @Test public void rootAndThreadAuthorityPrecedeAcquisition() throws Exception
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper()); var root = calls.add(10, 1, 9);
            var owner = owner(scope, calls, root);
            var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread thread = Thread.ofPlatform().start(() -> { try { owner.start(); } catch (Throwable failure) { result.set(failure); } });
            thread.join(); requireThat(result.get() instanceof IllegalStateException, "wrongThreadRejected").isTrue();
            calls.add(10, 9, 9); requireException(IOException.class, owner::start);
            requireThat(calls.connections.isEmpty(), "foreignRootNeverAttached").isTrue(); owner.close(() -> true);
        }
    }

    /**
     * Retains partially acquired caller instrumentation and leaves the child held after any failure category.
     * @throws IOException if accepted root setup or eventual cleanup fails
     */
    @Test public void partialInstrumentationFailureRetainsOwnerAndPreventsRelease() throws IOException
    {
        for (Throwable primary : List.of(new IOException("partial installation"),
            new IllegalStateException("partial installation"), new AssertionError("partial installation")))
        {
            try (var scope = new DefaultToolingScope())
            {
                var calls = new Calls(scope.getLeaderJsonMapper());
                var root = calls.add(10, 1, 9);
                calls.add(11, 2, 10);
                var owner = owner(scope, calls, root);
                owner.start();
                calls.instrumentationStartFailure = primary;
                requireThat(requireException(Throwable.class,
                    () -> owner.advance(new FridaRuntime.Child(11, 10, 0, ""))) == primary, "exactInstrumentationFailure").isTrue();
                requireThat(calls.resumed.isEmpty(), "failedInstrumentationLeavesImageHeld").isTrue();
                requireException(IllegalStateException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
                var partial = calls.instrumentationOwners.getLast();
                requireThat(partial.startCalls, "partialOwnerStartedOnce").isEqualTo(1, "expected");
                requireException(IOException.class, () -> owner.close(() -> false));
                requireThat(partial.closeCalls, "partialOwnerRetainedBeforeBarrier").isEqualTo(0, "expected");
                calls.events.clear();
                owner.close(() -> true);
                requireThat(calls.events.getFirst().equals("instrumentation-close:11"), "partialInstrumentationClosesFirst").isTrue();
                requireThat(calls.events.get(1).equals("connection-close"), "dependentConnectionClosesAfterInstrumentation").isTrue();
                requireThat(owner.isClosed(), "partialInstallationEventuallyClosed").isTrue();
            }
        }
    }

    /**
     * Rechecks the exact held birth after the caller has acknowledged instrumentation readiness.
     * @throws IOException if accepted setup or cleanup fails
     */
    @Test public void changedBirthAfterInstrumentationNeverReleases() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.afterInstrumentation = () -> calls.add(11, 3, 10);
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireThat(calls.resumed.isEmpty(), "changedInstrumentedBirthRemainsHeld").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * A failed caller destruction retains its dependent image connection while independent images close.
     * @throws IOException if accepted setup fails
     */
    @Test public void uncertainInstrumentationCloseRetainsConnectionWithoutReplay() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            var exact = new IOException("uncertain instrumentation destruction");
            calls.instrumentationOwners.getLast().closeFailure = exact;
            requireThat(requireException(IOException.class, () -> owner.close(() -> true)) == exact, "exactDestructionFailure").isTrue();
            requireThat(calls.connections.getLast().closeCalls, "dependentConnectionRetained").isEqualTo(0, "expected");
            requireThat(calls.connections.getFirst().closed, "independentRootClosed").isTrue();
            requireThat(owner.isClosed(), "uncertainOwnerRemainsOpen").isFalse();
            requireThat(requireException(IOException.class, () -> owner.close(() -> true)) == exact, "stickyDestructionFailure").isTrue();
            requireThat(calls.instrumentationOwners.getLast().closeCalls, "uncertainDestructionNotReplayed").isEqualTo(1, "expected");
            requireThat(calls.connections.getLast().closeCalls, "dependencyStillRetained").isEqualTo(0, "expected");
        }
    }

    /**
     * Caller instrumentation cannot reenter child release or cleanup while the validated image is being armed.
     * @throws IOException if accepted setup or cleanup fails
     */
    @Test public void instrumentationCannotReenterControls() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.duringInstrumentation = () ->
            {
                requireException(IllegalStateException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
                requireException(IllegalStateException.class, () -> owner.close(() -> true));
            };
            owner.advance(new FridaRuntime.Child(11, 10, 0, ""));
            requireThat(calls.resumed.equals(List.of(11L)), "singleReleaseAfterCallerReturns").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * A failed required root initializer leaves the caller's root gate unreleased and retains partial owners.
     * @throws IOException if eventual cleanup fails
     */
    @Test public void rootInstrumentationFailureRetainsGateAndOwners() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            var exact = new IOException("root initializer readiness");
            calls.instrumentationStartFailure = exact;
            var owner = owner(scope, calls, root);
            requireThat(requireException(IOException.class, owner::start) == exact, "exactRootReadinessFailure").isTrue();
            requireThat(calls.resumed.isEmpty(), "rootGateNeverReleasedByImages").isTrue();
            requireThat(calls.instrumentationOwners.getFirst().closeCalls, "rootPartialOwnerRetained").isEqualTo(0, "expected");
            owner.close(() -> true);
            requireThat(owner.isClosed(), "rootPartialOwnerClosedAfterBarrier").isTrue();
        }
    }

    /**
     * Preserves a caller installation failure while retaining cleanup errors and independently closing peers.
     * @throws IOException if root setup fails
     */
    @Test public void installationPrimaryRetainsDependentCleanupFailure() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            var primary = new IOException("partial child instrumentation");
            calls.instrumentationStartFailure = primary;
            requireThat(requireException(IOException.class,
                () -> owner.advance(new FridaRuntime.Child(11, 10, 0, ""))) == primary, "exactInstallationPrimary").isTrue();
            var cleanup = new AssertionError("uncertain child instrumentation destruction");
            calls.instrumentationOwners.getLast().closeFailure = cleanup;
            requireThat(requireException(AssertionError.class, () -> owner.close(() -> true)) == cleanup, "exactCleanupError").isTrue();
            requireThat(primary.getSuppressed()[0] == cleanup, "cleanupAttachedToPrimary").isTrue();
            requireThat(calls.connections.getLast().closeCalls, "dependentChildConnectionRetained").isEqualTo(0, "expected");
            requireThat(calls.connections.getFirst().closed, "independentRootStillCloses").isTrue();
        }
    }

    /**
     * Reentrant destruction cannot skip an active instrumentation close or close its dependency early.
     * @throws IOException if setup or cleanup fails
     */
    @Test public void instrumentationCloseCannotReenterCleanup() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.duringInstrumentationClose = () ->
            {
                requireException(IllegalStateException.class, () -> owner.close(() -> true));
                requireThat(calls.connections.getFirst().closeCalls, "connectionRetainedWhileInstrumentationClosing").isEqualTo(0, "expected");
            };
            owner.close(() -> true);
            requireThat(owner.isClosed(), "independentOuterCleanupCompletes").isTrue();
        }
    }

    /**
     * Rejects a root denied by independent workload authority before creating any attachment.
     * @throws IOException if cleanup fails
     */
    @Test public void workloadAuthorityRejectsRootBeforeAttachment() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            IOException denial = new IOException("Root belongs to a foreign resource coalition");
            MacImages owner = new MacImages(scope, calls, identity -> { throw denial; },
                identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root, Duration.ofSeconds(1), System::nanoTime);
            requireThat(requireException(IOException.class, owner::start) == denial, "exactAuthorityDenial").isTrue();
            requireThat(calls.connections.isEmpty(), "noForeignAttachmentCreated").isTrue();
            requireThat(calls.resumed.isEmpty(), "noForeignResume").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Reauthenticates a child after instrumentation so changed credentials prevent its release.
     * @throws IOException if setup or cleanup fails
     */
    @Test public void changedAuthorityAfterInstrumentationPreventsResume() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            boolean[] childCredentialChanged = {false};
            IOException denial = new IOException("Child credentials changed while held");
            MacImages owner = new MacImages(scope, calls, identity ->
            {
                if (identity.pid() == 11 && childCredentialChanged[0]) throw denial;
            }, identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root, Duration.ofSeconds(1), System::nanoTime);
            owner.start();
            calls.afterInstrumentation = () -> childCredentialChanged[0] = true;
            requireThat(requireException(IOException.class,
                () -> owner.advance(new FridaRuntime.Child(11, 10, 0, ""))) == denial,
                "exactAuthorityChangeFailure").isTrue();
            requireThat(calls.resumed.isEmpty(), "changedAuthorityNeverResumed").isTrue();
            requireThat(calls.connections.size(), "partialChildRetainedForCleanup").isEqualTo(2, "expected");
            owner.close(() -> true);
            requireThat(owner.isClosed(), "partialConnectionsCloseAfterBarrier").isTrue();
        }
    }

    /**
     * Keeps a child held and retains its owners when final descriptor agreement names another generation.
     * @throws IOException if accepted root setup or barrier cleanup fails
     */
    @Test public void finalDescriptorAgreementFailurePreventsChildResume() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            MacImages owner = owner(scope, calls, root);
            owner.start();
            calls.badValidation = true;
            requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
            requireThat(calls.resumed.isEmpty(), "childHeldAfterRejectedFinalAgreement").isTrue();
            requireThat(calls.instrumentationOwners.getLast().closeCalls, "partialChildInstrumentationRetained").isEqualTo(0);
            requireThat(calls.connections.getLast().closeCalls, "partialChildConnectionRetained").isEqualTo(0);
            owner.close(() -> true);
            requireThat(calls.connections.getLast().closed, "childClosedAfterNativeBarrier").isTrue();
            requireThat(calls.instrumentationOwners.getLast().closeCalls, "childInstrumentationClosedAfterBarrier").isEqualTo(1);
        }
    }

    /**
     * Destroys the separate runtime-worker Script before publishing a held image as ready.
     * @throws IOException if setup or barrier cleanup fails
     */
    @Test public void bootstrapScriptClosesBeforeImageAdmission() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            MacImages owner = owner(scope, calls, root);
            owner.start();
            requireThat(calls.events.contains("bootstrap-close"), "workerScriptDestroyedBeforeAdmission").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Repeats complete native observations when runtime teardown changes a private lifetime during either agreement.
     * @throws IOException if setup, admission or barrier cleanup fails
     */
    @Test public void retriesNativeDescriptorChurnBeforeAdmission() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            calls.sealRetries = 1;
            calls.validationRetries = 1;
            MacImages owner = owner(scope, calls, root);
            owner.start();
            requireThat(calls.events.contains("mac-descriptor-seal-retry"), "initialInventoryRaceObserved").isTrue();
            requireThat(calls.events.contains("mac-descriptor-validate-retry"), "finalInventoryRaceObserved").isTrue();
            owner.close(() -> true);
        }
    }

    /**
     * Keeps a partially started bootstrap until the native barrier, then destroys it exactly once.
     * @throws IOException if controlled barrier cleanup fails
     */
    @Test public void failedBootstrapStartRetainsCleanupOwnership() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            calls.bootstrapStartFailure = new IOException("Partial bootstrap Script installation");
            MacImages owner = owner(scope, calls, root);
            requireThat(requireException(IOException.class, owner::start) == calls.bootstrapStartFailure,
                "originalBootstrapStartFailure").isTrue();
            requireThat(calls.bootstraps.getFirst().closeCalls, "partialBootstrapRetainedBeforeBarrier").isEqualTo(0);
            requireThat(calls.connections.getFirst().closed, "primaryRetainedBeforeBarrier").isFalse();
            owner.close(() -> true);
            owner.close(() -> true);
            requireThat(calls.bootstraps.getFirst().closeCalls, "bootstrapDestructionOnceAtBarrier").isEqualTo(1);
            requireThat(calls.connections.getFirst().closed, "primaryClosesAfterBootstrap").isTrue();
        }
    }

    /**
     * Preserves an uncertain bootstrap destruction result without replay or premature primary release.
     */
    @Test public void uncertainBootstrapCloseRetainsPrimaryWithoutReplay()
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls(scope.getLeaderJsonMapper());
            MacImages.Identity root = calls.add(10, 1, 9);
            calls.bootstrapCloseFailure = new IOException("Uncertain bootstrap Script destruction");
            MacImages owner = owner(scope, calls, root);
            requireThat(requireException(IOException.class, owner::start) == calls.bootstrapCloseFailure,
                "originalBootstrapCloseFailure").isTrue();
            requireException(IOException.class, () -> owner.close(() -> true));
            requireException(IOException.class, () -> owner.close(() -> true));
            requireThat(calls.bootstraps.getFirst().closeCalls, "uncertainBootstrapDestructionNeverRepeated").isEqualTo(1);
            requireThat(calls.connections.getFirst().closed, "primaryRetainedAfterUncertainBootstrap").isFalse();
            requireThat(calls.resumed.isEmpty(), "noReleaseAfterUncertainBootstrap").isTrue();
            requireThat(owner.isClosed(), "uncertainBootstrapOwnerRemainsRetained").isFalse();
        }
    }

    /**
     * Builds a resource-free owner with an advancing test clock and fresh test operations.
     * @param scope this test's owned services
     * @param calls independent observations
     * @param root retained root lineage
     * @return unstarted owner
     */
    private static MacImages owner(DefaultToolingScope scope, Calls calls, MacImages.Identity root)
    {
        return new MacImages(scope, calls, identity -> { var value = new InstrumentationOwner(calls, identity); calls.instrumentationOwners.add(value); return value; }, root, Duration.ofSeconds(1), System::nanoTime);
    }

    /**

     * Supplies independently controlled native observations and resource-free connections.

     */
    private static final class Calls implements MacImages.Operations
    {
        /**
         * Scoped mapper borrowed from the test.
         */
        private final JsonMapper json;
        /**
         * Current independently controlled process identities.
         */
        private final Map<Long, MacImages.Identity> identities = new HashMap<>();
        /**
         * Retained fake image connections in reservation order.
         */
        private final List<Connection> connections = new ArrayList<>();
        /**
         * Native resume attempts, including uncertain ones.
         */
        private final List<Long> resumed = new ArrayList<>();
        /**
         * Indicates whether the simulated native release requires acknowledged instrumentation.
         */
        private boolean requireInstrumentation;
        /**
         * Image PIDs whose caller instrumentation has acknowledged readiness.
         */
        private final List<Long> instrumented = new ArrayList<>();
        /**
         * One retained caller instrumentation owner for each image generation.
         */
        private final List<InstrumentationOwner> instrumentationOwners = new ArrayList<>();
        /**
         * Cross-owner acquisition, acknowledgement and destruction observations.
         */
        private final List<String> events = new ArrayList<>();
        /**
         * Optional caller instrumentation start failure, including partial acquisition.
         */
        private Throwable instrumentationStartFailure;
        /**
         * Optional mutation after caller instrumentation readiness.
         */
        private Runnable afterInstrumentation;
        /**
         * Optional reentrant caller operation during instrumentation start.
         */
        private Runnable duringInstrumentation;
        /** Optional callback modeling caller reentry during native destruction. */
        private Runnable duringInstrumentationClose;
        /**
         * Optional acquisition failure.
         */
        private IOException startFailure;
        /** Optional change to the observed processes when a connection start fails, before it reports the failure. */
        private Runnable beforeStartFailure;
        /**
         * Optional uncertain resumption failure.
         */
        private IOException resumeFailure;
        /**
         * Mutation after a readiness receipt has been consumed.
         */
        private Runnable afterReady;
        /**
         * Whether readiness is absent.
         */
        private boolean silent;
        /**
         * Whether readiness has an incorrect marker.
         */
        private boolean badReady;
        /** Indicates whether the native final agreement reply names another generation. */
        private boolean badValidation;
        /** Number of controlled initial inventory lifetime changes still to report. */
        private int sealRetries;
        /** Number of controlled final inventory lifetime changes still to report. */
        private int validationRetries;
        /** Bootstrap Script connections retained separately from the primary image sessions. */
        private final List<Connection> bootstraps = new ArrayList<>();
        /** Indicates whether the next connection belongs to the current image's bootstrap. */
        private boolean nextBootstrap;
        /** Optional partial bootstrap start failure. */
        private IOException bootstrapStartFailure;
        /** Optional uncertain bootstrap Script destruction failure. */
        private IOException bootstrapCloseFailure;
        /**
         * Borrows one test's scoped mapper.
         * @param json borrowed mapper
         */
        private Calls(JsonMapper json) { this.json = json; }
        /**
         * Publishes an independent process observation.
         * @param pid observed PID
         * @param birth observed epoch second
         * @param parent actual parent PID
         * @return published lineage
         */
        private MacImages.Identity add(long pid, long birth, long parent)
        {
            var result = new MacImages.Identity(pid, Instant.ofEpochSecond(birth), parent); identities.put(pid, result); return result;
        }
        /**
         * Creates a resource-free stand-in for a caller-owned native instrumentation installation.
         * @param identity exact identity selected by MacImages after readiness validation
         * @return fresh owner whose start models native acquisition and acknowledgement
         */
        private MacImages.Instrumentation instrumentation(MacImages.Identity identity)
        {
            events.add("configure:" + identity.pid());
            var result = new InstrumentationOwner(this, identity);
            instrumentationOwners.add(result);
            return result;
        }
        @Override public MacImages.Identity inspect(long pid) throws IOException
        {
            var value = identities.get(pid); if (value == null) throw new IOException("missing observation"); return value;
        }
        @Override public MacDescriptorProtection.Inventory descriptors(MacImages.Identity identity)
        {
            return new MacDescriptorProtection.Inventory(362, 5, List.of());
        }
        @Override public AllocationHook.Connection connection(long pid)
        {
            Connection value = new Connection(this, nextBootstrap);
            if (nextBootstrap) bootstraps.add(value);
            else connections.add(value);
            nextBootstrap = !nextBootstrap;
            return value;
        }
        @Override public void resume(long pid) throws IOException
        {
            if (requireInstrumentation && !instrumented.contains(pid))
                throw new IOException("Held child reached resume before caller instrumentation readiness");
            events.add("resume:" + pid); resumed.add(pid); if (resumeFailure != null) throw resumeFailure;
        }
    }

    /**
     * Models caller native instrumentation with explicit partial start and uncertain destruction.
     * MacImages must retain this owner before invoking start; close models destruction after the workload barrier.
     */
    private static final class InstrumentationOwner implements MacImages.Instrumentation
    {
        /**
         * Owning test's isolated observations.
         */
        private final Calls calls;
        /**
         * Exact validated held image, including same-PID exec birth.
         */
        private final MacImages.Identity identity;
        /**
         * Number of native installation attempts.
         */
        private int startCalls;
        /** Independently supplied image-session detach observation. */
        private boolean detached;
        /**
         * Number of native destruction attempts.
         */
        private int closeCalls;
        /**
         * Optional exact uncertain destruction failure.
         */
        private Throwable closeFailure;
        /**
         * Retains test-local state without simulating any native acquisition.
         * @param calls owning test observations
         * @param identity validated image selected by the production owner
         */
        private InstrumentationOwner(Calls calls, MacImages.Identity identity)
        {
            this.calls = calls;
            this.identity = identity;
        }
        @Override public boolean detached() { return detached; }
        @Override public void closeDetached() throws IOException { if (!detached) throw new IOException("Not detached"); close(); }
        @Override public void start() throws IOException
        {
            startCalls++;
            calls.events.add("instrumentation-start:" + identity.pid());
            if (calls.duringInstrumentation != null) calls.duringInstrumentation.run();
            if (calls.instrumentationStartFailure instanceof IOException checked) throw checked;
            if (calls.instrumentationStartFailure instanceof RuntimeException unchecked) throw unchecked;
            if (calls.instrumentationStartFailure instanceof Error fatal) throw fatal;
            calls.instrumented.add(identity.pid());
            calls.events.add("instrumentation-ready:" + identity.pid());
            if (calls.afterInstrumentation != null) calls.afterInstrumentation.run();
        }
        @Override public void close() throws IOException
        {
            closeCalls++;
            calls.events.add("instrumentation-close:" + identity.pid());
            if (calls.duringInstrumentationClose != null) calls.duringInstrumentationClose.run();
            if (closeFailure instanceof IOException checked) throw checked;
            if (closeFailure instanceof RuntimeException unchecked) throw unchecked;
            if (closeFailure instanceof Error fatal) throw fatal;
        }
    }

    /**

     * Represents independent image resources and uncertain destruction, without creating native effects.

     */
    private static final class Connection implements AllocationHook.Connection
    {
        /**
         * Test-owned observations and scoped parser.
         */
        private final Calls calls;
        /** Indicates whether this connection owns the temporary runtime-worker Script. */
        private final boolean bootstrap;
        /**
         * Queued readiness frames.
         */
        private final ArrayDeque<String> frames = new ArrayDeque<>();
        /** Exact held-image configuration emitted by the maintained protection Script. */
        private JsonNode configuration;
        /** Indicates whether the first readiness frame still awaits consumption. */
        private boolean readinessPending;
        /**
         * Optional retained destruction failure.
         */
        private Throwable closeFailure;
        /**
         * Number of destruction attempts.
         */
        private int closeCalls;
        /**
         * Whether destruction succeeded.
         */
        private boolean closed;
        /** Optional unavailable exact-session observation. */
        private IOException detachFailure;
        /** Independently supplied readiness-session detach observation. */
        private boolean detached;
        /**
         * Retains one test's operation state.
         * @param calls owning test operations
         * @param bootstrap indicates whether this Script owns the runtime worker
         */
        private Connection(Calls calls, boolean bootstrap) { this.calls = calls; this.bootstrap = bootstrap; }
        @Override public boolean detached() throws IOException { if (detachFailure != null) throw detachFailure; return detached; }
        @Override public void start(String source) throws IOException
        {
            if (bootstrap && calls.bootstrapStartFailure != null) throw calls.bootstrapStartFailure;
            if (calls.startFailure != null)
            {
                if (calls.beforeStartFailure != null) calls.beforeStartFailure.run();
                // Descriptor protection requests no bootstrap connection for an image whose readiness start failed.
                if (!bootstrap) calls.nextBootstrap = false;
                throw calls.startFailure;
            }
            calls.events.add(bootstrap ? "bootstrap-start" : "connection-start");
            String declaration = "const configuration = ";
            int start = source.indexOf(declaration) + declaration.length();
            configuration = calls.json.readTree(source.substring(start, source.indexOf(";\n", start)));
            Object payload;
            if (bootstrap)
            {
                // The pinned Gum worker runtime imports the second asset and invokes await w.run().
                // This request fixture accepts the generated export contract and copies its send argument;
                // actual JavaScript execution and worker termination remain native acceptance checks.
                String separator = "\n✄\n";
                String worker = source.substring(source.lastIndexOf(separator) + separator.length());
                String prefix = "export function run() { send(";
                String suffix = "); }";
                requireThat(worker.startsWith(prefix) && worker.endsWith(suffix),
                    "bootstrapRequestExportsTheRuntimeRunEntrypoint").isTrue();
                JsonNode sent = calls.json.readTree(worker.substring(prefix.length(), worker.length() - suffix.length()));
                requireThat(sent.isString(), "workerRunSendsAnExactGenerationChallenge").isTrue();
                payload = sent.asString();
            }
            else
                payload = Map.of("kind", "mac-descriptor-installed", "marker",
                    calls.badReady ? "wrong" : configuration.path("marker").asString(),
                    "pid", configuration.path("pid").asLong(), "registry", "0x1000", "inheritedDescriptors", List.of());
            if (!calls.silent) frames.add(calls.json.writeValueAsString(Map.of("type", "send", "payload", payload)));
            readinessPending = true;
        }
        @Override public Optional<String> pollMessage()
        {
            Optional<String> value = Optional.ofNullable(frames.poll());
            if (value.isPresent() && readinessPending)
            {
                readinessPending = false;
                if (calls.afterReady != null) calls.afterReady.run();
            }
            return value;
        }
        /** Exact native lifetime generation expected in the next host agreement. */
        private int mutationVersion;
        @Override public void post(JsonNode permission) throws IOException
        {
            String kind = switch (permission.path("type").asString())
            {
                case "mac-descriptor-bootstrap-completed" -> "mac-descriptor-ready";
                case "mac-descriptor-seal" -> "mac-descriptor-sealed";
                case "mac-descriptor-observe" -> "mac-descriptor-observed";
                case "mac-descriptor-validate" -> "mac-descriptor-validated";
                default -> throw new IOException("Unexpected descriptor protocol request");
            };
            if (kind.equals("mac-descriptor-sealed") || kind.equals("mac-descriptor-validated"))
            {
                if (permission.path("mutations").asInt() != mutationVersion)
                    throw new IOException("Host reused a stale native descriptor generation");
                boolean retry = kind.equals("mac-descriptor-sealed") ? calls.sealRetries-- > 0 : calls.validationRetries-- > 0;
                if (retry)
                {
                    kind = permission.path("type").asString() + "-retry";
                    mutationVersion++;
                    calls.events.add(kind);
                }
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("kind", kind);
            payload.put("marker", calls.badValidation && kind.equals("mac-descriptor-validated")
                ? "wrong" : configuration.path("marker").asString());
            payload.put("pid", configuration.path("pid").asLong());
            if (kind.equals("mac-descriptor-ready"))
            {
                payload.put("registry", "0x1000");
                payload.put("mutations", 0);
                payload.put("creators", 1);
                payload.put("closers", 1);
                payload.put("inheritedDescriptors", List.of());
            }
            if (kind.endsWith("-retry")) payload.put("mutations", mutationVersion);
            if (kind.equals("mac-descriptor-observed"))
            {
                payload.put("mutations", mutationVersion);
                payload.put("fault", 0);
                payload.put("sealed", 1);
            }
            frames.add(calls.json.writeValueAsString(Map.of("type", "send", "payload", payload)));
        }
        @Override public void close() throws IOException
        {
            closeCalls++;
            calls.events.add(bootstrap ? "bootstrap-close" : "connection-close");
            if (bootstrap && calls.bootstrapCloseFailure != null) throw calls.bootstrapCloseFailure;
            if (closeFailure instanceof IOException checked) throw checked;
            if (closeFailure instanceof RuntimeException unchecked) throw unchecked;
            if (closeFailure instanceof Error fatal) throw fatal;
            closed = true;
        }
    }
    /**
     * Prevents release when required instrumentation leaves an interruption pending.
     * @throws IOException if root setup or barrier cleanup fails
     */
    @Test public void instrumentationInterruptionPreventsResume() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            calls.add(11, 2, 10);
            var owner = owner(scope, calls, root);
            owner.start();
            calls.afterInstrumentation = Thread.currentThread()::interrupt;
            try
            {
                requireException(IOException.class, () -> owner.advance(new FridaRuntime.Child(11, 10, 0, "")));
                requireThat(calls.resumed.isEmpty(), "interruptedImageRemainsHeld").isTrue();
            }
            finally
            {
                Thread.interrupted();
                owner.close(() -> true);
            }
        }
    }

    /**
     * Refuses recursive cleanup while authentic whole-workload evidence is being observed.
     * @throws IOException if root setup or barrier cleanup fails
     */
    @Test public void barrierCannotReenterCleanup() throws IOException
    {
        try (var scope = new DefaultToolingScope())
        {
            var calls = new Calls(scope.getLeaderJsonMapper());
            var root = calls.add(10, 1, 9);
            var owner = owner(scope, calls, root);
            owner.start();
            owner.close(() ->
            {
                requireException(IllegalStateException.class, () -> owner.close(() -> true));
                return true;
            });
            requireThat(owner.isClosed(), "outerCleanupClosesOwners").isTrue();
            requireThat(calls.connections.getFirst().closeCalls, "nativeCloseOnce").isEqualTo(1, "expected");
        }
    }
}
