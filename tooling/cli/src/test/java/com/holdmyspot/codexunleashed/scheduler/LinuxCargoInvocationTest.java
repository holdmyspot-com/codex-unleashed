package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the production invocation lifecycle against independently controlled native boundary outcomes.
 * Each method owns its fixture; these checks do not establish native acquisition or execution.
 */
public final class LinuxCargoInvocationTest
{
    /**
     * Creates method-owned lifecycle checks.
     */
    public LinuxCargoInvocationTest() {}

    /**
     * Holds Cargo until the session exists and rejects replay of acquisition.
     * @throws IOException if startup or cleanup fails
     */
    @Test
    public void releasesOnlyAfterSessionConstruction() throws IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        requireThat(resources.events.isEmpty(), "resourceFreeConstruction").isTrue();
        requireException(IllegalStateException.class, owner::session);
        owner.start();
        requireThat(resources.events, "startupOrder").isEqualTo(List.of("acquire", "factory", "measurements", "release"));
        requireThat(resources.session == owner.session(), "sameRetainedSession").isTrue();
        requireException(IllegalStateException.class, owner::start);
        owner.close();
        requireException(IllegalStateException.class, owner::start);
    }

    /**
     * Delivers native-boundary fixture notifications once, unchanged and in their observed order.
     * The method-owned queue represents already authenticated native notifications; polling must
     * neither manufacture another notification nor change either attempt or wait identity.
     * @throws IOException if invocation startup, polling or cleanup fails
     */
    @Test
    public void preservesObservedAllocationNotificationOrder() throws IOException
    {
        var resources = new Fixture();
        var oldest = new SchedulerCoordinator.Blocked("oldest", 3, 7);
        var newest = new SchedulerCoordinator.Blocked("newest", 5, 0);
        resources.blocked.addLast(oldest);
        resources.blocked.addLast(newest);
        try (var owner = new LinuxCargoInvocation(resources))
        {
            requireException(IllegalStateException.class, owner::pollBlocked);
            owner.start();
            requireThat(owner.pollBlocked().orElseThrow() == oldest, "sameOldestNotification").isTrue();
            requireThat(owner.pollBlocked().orElseThrow() == newest, "sameNewestNotification").isTrue();
            requireThat(owner.pollBlocked().isEmpty(), "noManufacturedNotification").isTrue();
        }
    }

    /**
     * Retains exact acquisition failures while independently attempting cleanup.
     */
    @Test
    public void preservesPrimaryAcquisitionThrowable()
    {
        for (Throwable primary : List.of(new IOException("acquire"), new IllegalStateException("acquire"), new AssertionError("acquire")))
        {
            var resources = new Fixture();
            resources.acquisitionFailure = primary;
            resources.stopFailure = new IOException("stop");
            var owner = new LinuxCargoInvocation(resources);
            Throwable actual = requireException(Throwable.class, owner::start);
            requireThat(actual == primary, "exactPrimary").isTrue();
            requireThat(List.of(actual.getSuppressed()).contains(resources.stopFailure), "retainedCleanupFailure").isTrue();
            requireThat(resources.events.contains("consumers"), "collectionProgressDespiteStopFailure").isTrue();
            requireThat(owner.isClosed(), "uncertainListenerRetained").isFalse();
            requireException(IllegalStateException.class, owner::start);
        }
    }

    /**
     * Retains all outer dependencies while queue-owned attempts remain live.
     * @throws IOException if startup or eventual cleanup fails
     */
    @Test
    public void liveAttemptsPreventConsumerAndDependencyClosure() throws IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        owner.start();
        resources.events.clear();
        resources.liveAttempts = true;
        requireException(IOException.class, owner::close);
        requireThat(resources.events, "onlyStopBeforeAttemptRetirement").isEqualTo(List.of("stop"));
        requireThat(owner.isClosed(), "retainedLiveDependencies").isFalse();
        resources.liveAttempts = false;
        owner.close();
        requireThat(resources.events, "retiredConsumerCleanup").isEqualTo(List.of("stop", "consumers", "endpoints", "dependencies"));
        requireThat(owner.isClosed(), "closedAfterRetirement").isTrue();
    }

    /**
     * Retries safe collection without repeating an already successful stop operation.
     * @throws IOException if startup or eventual cleanup fails
     */
    @Test
    public void collectionFailureRetainsEndpointsAndRetriesSafely() throws IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        owner.start();
        resources.events.clear();
        resources.consumerFailure = new IOException("uncollected tree");
        requireThat(requireException(IOException.class, owner::close) == resources.consumerFailure, "exactCleanupFailure").isTrue();
        requireThat(resources.events, "noPrematureEndpointClosure").isEqualTo(List.of("stop", "consumers"));
        var before = List.copyOf(resources.events);
        requireThat(owner.isClosed(), "incompleteCollection").isFalse();
        requireThat(resources.events, "pureClosureObservation").isEqualTo(before);
        resources.consumerFailure = null;
        owner.close();
        requireThat(resources.events, "safeRetryOrder").isEqualTo(List.of("stop", "consumers", "consumers", "endpoints", "dependencies"));
        int effects = resources.events.size();
        owner.close();
        requireThat(resources.events.size(), "idempotentSuccessfulClose").isEqualTo(effects);
    }

    /**
     * Preserves unchecked cleanup failures and keeps dependencies retained after endpoint uncertainty.
     * @throws IOException if startup fails
     */
    @Test
    public void endpointFailurePreservesExactThrowableAndRetainsDependencies() throws IOException
    {
        for (Throwable failure : List.of(new IOException("endpoint"), new IllegalStateException("endpoint"), new AssertionError("endpoint")))
        {
            var resources = new Fixture();
            var owner = new LinuxCargoInvocation(resources);
            owner.start();
            resources.events.clear();
            resources.endpointFailure = failure;
            requireThat(requireException(Throwable.class, owner::close) == failure, "exactCleanupFailure").isTrue();
            requireThat(resources.events, "dependenciesRemainBorrowable").isEqualTo(List.of("stop", "consumers", "endpoints"));
            requireThat(owner.isClosed(), "uncertainEndpointRetained").isFalse();
        }
    }

    /**
     * Allows the outer composition to resolve safe cleanup after CargoSession's once-only close call.
     * @throws IOException if startup or eventual cleanup fails
     */
    @Test
    public void sessionObservesOuterCleanupWithoutReplayingIt() throws IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        owner.start();
        resources.consumerFailure = new IOException("collection pending");
        requireException(IOException.class, owner.session()::close);
        int effects = resources.events.size();
        requireException(IOException.class, owner.session()::close);
        requireThat(resources.events.size(), "noSessionCleanupReplay").isEqualTo(effects);
        resources.consumerFailure = null;
        owner.close();
        owner.session().close();
        requireThat(owner.isClosed(), "resolvedOuterOwner").isTrue();
    }

    /**
     * Rejects another thread before accessing native collaborators.
     * @throws InterruptedException if joining the test thread is interrupted
     * @throws IOException if startup or cleanup fails
     */
    @Test
    public void rejectsWrongThreadBeforeNativeEffects() throws InterruptedException, IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        var failures = new ArrayList<Throwable>();
        Thread other = new Thread(() -> failures.add(requireException(IllegalStateException.class, owner::start)));
        other.start();
        other.join();
        requireThat(failures.size(), "wrongThreadRejected").isEqualTo(1);
        requireThat(resources.events.isEmpty(), "noNativeEffects").isTrue();
        owner.start();
        owner.close();
    }

    /**
     * Prevents resource acquisition after a resource-free owner has been closed.
     * @throws IOException if resource-free cleanup fails
     */
    @Test
    public void closingBeforeStartPreventsAcquisition() throws IOException
    {
        var resources = new Fixture();
        var owner = new LinuxCargoInvocation(resources);
        owner.close();
        requireThat(owner.isClosed(), "closedUnstartedOwner").isTrue();
        requireException(IllegalStateException.class, owner::start);
        requireThat(resources.events.isEmpty(), "noUnacquiredCleanupCalls").isTrue();
    }

    /**
     * Keeps partial acquisition cleanup reachable from the original failure and resolves it without replaying start.
     * @throws IOException if safe cleanup cannot finish
     */
    @Test
    public void retainedMarkerResolvesSafePartialAcquisitionCleanup() throws IOException
    {
        var resources = new Fixture();
        resources.acquisitionFailure = new IOException("original acquisition failure");
        resources.consumerFailure = new IOException("consumer collection pending");
        var owner = new LinuxCargoInvocation(resources);
        var primary = requireException(IOException.class, owner::start);
        requireThat(primary == resources.acquisitionFailure, "originalFailure").isTrue();
        requireThat(owner.isClosed(), "partialOwnerRetained").isFalse();
        LinuxCargoInvocation.CleanupFailure marker = null;
        for (Throwable retained : primary.getSuppressed())
            if (retained instanceof LinuxCargoInvocation.CleanupFailure retainedOwner) marker = retainedOwner;
        requireThat(marker, "reachableCleanupOwner").isNotNull();
        requireThat(marker.getCause(), "causeFreeOwnerMarker").isNull();
        resources.consumerFailure = null;
        marker.close();
        requireThat(owner.isClosed(), "resolvedPartialAcquisition").isTrue();
        requireThat(resources.events.stream().filter("acquire"::equals).count(), "acquisitionNeverReplayed").isEqualTo(1L);
        requireException(IllegalStateException.class, owner::start);
    }

    /**
     * Avoids adding a suppression edge back through the original acquisition failure.
     * @throws IOException if safe cleanup cannot finish
     */
    @Test
    public void cleanupReferencingPrimaryDoesNotCreateThrowableCycle() throws IOException
    {
        var resources = new Fixture();
        resources.acquisitionFailure = new IOException("original acquisition failure");
        resources.consumerFailure = new IOException("cleanup context", resources.acquisitionFailure);
        var owner = new LinuxCargoInvocation(resources);
        var primary = requireException(IOException.class, owner::start);
        requireThat(primary == resources.acquisitionFailure, "exactOriginalFailure").isTrue();
        requireThat(List.of(primary.getSuppressed()).contains(resources.consumerFailure), "noSuppressionCycle").isFalse();
        requireThat(owner.isClosed(), "cyclicCleanupStillOwned").isFalse();
        resources.consumerFailure = null;
        owner.close();
        requireThat(owner.isClosed(), "resolvedConsumerCollection").isTrue();
    }

    /**
     * Ready descendant enrollment retains the exact image and replaces only with a later readiness generation.
     * Fixture image values represent independently authenticated held callbacks, never wrapper receipt claims.
     * @throws IOException if a retained ready parent cannot be selected
     */
    @Test
    public void retainsBuildScriptImagesAndAdvancingExecGenerations() throws IOException
    {
        Map<Long, LinuxCargoRegistration.Parent> retained = new LinkedHashMap<>();
        Path root = Path.of("ready-image-fixture").toAbsolutePath();
        var original = new LinuxCargoImages.Identity(61, 91, 51, 1000, 1000, root.resolve("build-script"), root);
        Map<String, Object> inode = Map.of("dev", 1L, "ino", 2L, "size", 3L, "lastModifiedTime", 4L, "ctime", 5L);
        LinuxCargoInvocation.retainReadyParent(retained, original, 7, inode);
        var first = LinuxCargoInvocation.retainedCargoParent(retained, original.pid());
        requireThat(first.peer(), "retainedExactBirth").isEqualTo(new LinuxBorrowedStreams.Peer(61, 91, 1000, 1000));
        requireThat(first.executable(), "retainedActualBuildScript").isEqualTo(original.executable());
        var replacement = new LinuxCargoImages.Identity(61, 91, 51, 1000, 1000, root.resolve("replacement"), root);
        LinuxCargoInvocation.retainReadyParent(retained, replacement, 8, inode);
        var current = LinuxCargoInvocation.retainedCargoParent(retained, original.pid());
        requireThat(current.executable(), "latestReadyExecImage").isEqualTo(replacement.executable());
        requireThat(current.generation(), "latestReadinessGeneration").isEqualTo(8L);
        requireException(IllegalArgumentException.class, () -> LinuxCargoInvocation.retainReadyParent(retained, original, 7, inode));
        requireThat(LinuxCargoInvocation.retainedCargoParent(retained, original.pid()), "staleReadinessCannotReplaceCurrent")
            .isEqualTo(current);
        requireException(IOException.class, () -> LinuxCargoInvocation.retainedCargoParent(retained, 999));
    }

    /**
     * Parent failure diagnostics distinguish actual observations from readiness records and bound arbitrary path text.
     */
    @Test
    public void boundsParentIdentityFailureDiagnostic()
    {
        var wrapper = new LinuxBorrowedStreams.Peer(71, 81, 1000, 1000);
        Path executable = Path.of("identity-fixture/build-script").toAbsolutePath();
        var parent = new LinuxCargoRegistration.Parent(new LinuxBorrowedStreams.Peer(61, 91, 1000, 1000), executable, 7,
            Map.of("dev", 1L, "ino", 2L, "size", 3L, "lastModifiedTime", 4L, "ctime", 5L));
        String message = LinuxCargoInvocation.parentFailureMessage(wrapper, 61, "62", "observed\n" + "x".repeat(4000),
            "92", parent);
        requireThat(message.contains("currentWrapperParent=62"), "observedCurrentParent").isTrue();
        requireThat(message.contains("observedSelectedParentBirth=92"), "observedBirth").isTrue();
        requireThat(message.contains("birth=91,generation=7"), "retainedBirthAndGeneration").isTrue();
        requireThat(message.contains("\n"), "singleLineDiagnostic").isFalse();
        requireThat(message.length() < 2000, "boundedDiagnosticLength").isTrue();
        String unenrolled = LinuxCargoInvocation.parentFailureMessage(wrapper, 61, "unavailable", "unavailable",
            "unavailable", null);
        requireThat(unenrolled.contains("retainedParent=unenrolled"), "noFallbackIdentity").isTrue();
    }

    /**
     * Supplies method-owned native outcomes without starting any process or native library.
     */
    private static final class Fixture implements LinuxCargoInvocation.Resources
    {
        /** Method-owned authenticated-notification fixture inputs in actual observation order. */
        private final ArrayDeque<SchedulerCoordinator.Blocked> blocked = new ArrayDeque<>();
        /**
         * Ordered externally visible boundary effects.
         */
        private final List<String> events = new ArrayList<>();
        /**
         * Exact primary failure after partial acquisition.
         */
        private Throwable acquisitionFailure;
        /**
         * Independent stop failure.
         */
        private IOException stopFailure;
        /**
         * Safely retryable consumer collection failure.
         */
        private IOException consumerFailure;
        /**
         * Uncertain endpoint close failure.
         */
        private Throwable endpointFailure;
        /**
         * Whether an attempt still borrows dependencies.
         */
        private boolean liveAttempts;
        /**
         * Session installed before Cargo release.
         */
        private CargoSession<LinuxSupervisor.Result> session;

        /**
         * Creates independent native boundary outcomes.
         */
        private Fixture() {}

        @Override public void acquire() throws IOException { events.add("acquire"); rethrow(acquisitionFailure); }
        @Override public JobQueue.AttemptFactory<LinuxSupervisor.Result> factory()
        {
            events.add("factory");
            return (name, sequence) -> { throw new IllegalStateException("No attempt requested by lifecycle tests"); };
        }
        @Override public Controller.Measurements measurements()
        {
            events.add("measurements");
            return new Controller.Measurements()
            {
                @Override public Controller.Memory readHost() { throw new IllegalStateException("No measurement requested"); }
                @Override public long readResidentBytes(Policy.Job job) { throw new IllegalStateException("No measurement requested"); }
            };
        }
        @Override public void register(CargoCompilerRequest request, long position) {}
        @Override public void release(CargoSession<LinuxSupervisor.Result> value) { session = value; events.add("release"); }
        @Override public void pump() {}
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() { return Optional.ofNullable(blocked.pollFirst()); }
        @Override public OptionalInt pollToolResult() { return OptionalInt.empty(); }
        @Override public boolean inputClosed() { return false; }
        @Override public boolean hasOpenAttempts() { return liveAttempts; }
        @Override public void stopAccepting() throws IOException { events.add("stop"); if (stopFailure != null) throw stopFailure; }
        @Override public void closeConsumers() throws IOException { events.add("consumers"); if (consumerFailure != null) throw consumerFailure; }
        @Override public void closeEndpoints() throws IOException { events.add("endpoints"); rethrow(endpointFailure); }
        @Override public void closeDependencies() { events.add("dependencies"); }
    }

    /**
     * Throws an independently chosen boundary outcome without translating its type or identity.
     * @param failure exact exception or error, or null
     * @throws IOException if the selected failure is checked
     */
    private static void rethrow(Throwable failure) throws IOException
    {
        if (failure instanceof IOException checked) throw checked;
        if (failure instanceof RuntimeException unchecked) throw unchecked;
        if (failure instanceof Error fatal) throw fatal;
    }
}
