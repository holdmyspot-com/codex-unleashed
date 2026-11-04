package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoCompilerRequest;
import com.holdmyspot.codexunleashed.scheduler.CargoMemoryEstimates;
import com.holdmyspot.codexunleashed.scheduler.CargoSession;
import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SchedulerCoordinator;
import com.holdmyspot.codexunleashed.scheduler.WindowsAttempt;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.WindowsProcess;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the production invocation lifecycle against independently controlled native boundary outcomes.
 * Each method owns its fixture; these checks do not establish native acquisition or execution.
 */
public final class WindowsCargoInvocationTest
{
    /**
     * Creates method-owned lifecycle checks.
     */
    public WindowsCargoInvocationTest() {}

    /**
     * Prevents jpackage restart through the approved PATH while replacing owned environment names case-insensitively.
     * The caller's unrelated values and original PATH spelling remain unchanged.
     */
    @Test
    public void approvedEnvironmentPreservesPathCaseAndUnrelatedEmptyValues()
    {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("Path", "existing;directory");
        input.put("EMPTY", "");
        input.put("rustc", "old compiler");
        input.put("rUsTc_WrApPeR", "old wrapper");
        input.put("codeX_Cargo_PiPe", "old endpoint");
        input.put("CodeX_Cargo_CONTROLLER_PID", "old pid");
        input.put("CodeX_Cargo_CONTROLLER_BIRTH", "old birth");
        input.put("CODEX_CARGO_OTHER", "old invocation value");
        input.put("RUSTC_WORKSPACE_WRAPPER", "");
        input.put("CODEX_CARGO", "unrelated");
        Map<String, String> original = Map.copyOf(input);
        WindowsCargoInvocation.Configuration configuration = environmentConfiguration(input);

        Map<String, String> actual = WindowsCargoInvocation.approvedEnvironment(configuration);

        requireThat(actual, "approvedLaunchEnvironment").isEqualTo(Map.of(
            "Path", configuration.wrapper().getParent().resolve("app") + ";existing;directory",
            "EMPTY", "", "RUSTC", configuration.rustc().toString(),
            "RUSTC_WRAPPER", configuration.wrapper().toString(), "RUSTC_WORKSPACE_WRAPPER", "",
            "CODEX_CARGO", "unrelated"));
        requireThat(input, "unchangedCallerEnvironment").isEqualTo(original);
        actual.put("CODEX_CARGO_PIPE", "fresh endpoint");
        requireThat(configuration.environment(), "unchangedRetainedConfiguration").isEqualTo(original);
    }

    /**
     * Preserves an explicitly empty PATH entry and creates uppercase PATH only when no spelling exists.
     */
    @Test
    public void approvedEnvironmentDistinguishesEmptyAndAbsentPath()
    {
        WindowsCargoInvocation.Configuration empty = environmentConfiguration(Map.of("pAtH", ""));
        WindowsCargoInvocation.Configuration absent = environmentConfiguration(Map.of("EMPTY", ""));

        Map<String, String> withEmpty = WindowsCargoInvocation.approvedEnvironment(empty);
        Map<String, String> withoutPath = WindowsCargoInvocation.approvedEnvironment(absent);

        requireThat(withEmpty.get("pAtH"), "explicitEmptyPathEntryRetained")
            .isEqualTo(empty.wrapper().getParent().resolve("app") + ";");
        requireThat(withEmpty.containsKey("PATH"), "noSecondPathSpelling").isFalse();
        requireThat(withoutPath.get("PATH"), "newPathForAbsentInput")
            .isEqualTo(absent.wrapper().getParent().resolve("app").toString());
        requireThat(withoutPath.get("EMPTY"), "unrelatedExplicitEmptyValue").isEqualTo("");
    }

    /**
     * Rejects duplicate names before owned values can hide the conflicting input.
     */
    @Test
    public void approvedEnvironmentRejectsCaseInsensitiveDuplicateNames()
    {
        for (Map<String, String> input : List.of(Map.of("Path", "", "PATH", ""),
            Map.of("RUSTC", "first", "rustc", "second"),
            Map.of("CODEX_CARGO_PIPE", "first", "codex_cargo_pipe", "second")))
        {
            WindowsCargoInvocation.Configuration configuration = environmentConfiguration(input);
            requireException(IllegalArgumentException.class,
                () -> WindowsCargoInvocation.approvedEnvironment(configuration));
        }
    }

    /**
     * Constructs immutable, method-owned launch values without accessing files or acquiring native handles.
     * @param environment complete explicit environment used by the helper under test
     * @return validated launch values; stream addresses are never dereferenced by these environment tests
     */
    private static WindowsCargoInvocation.Configuration environmentConfiguration(Map<String, String> environment)
    {
        Path directory = Path.of("environment-fixture").toAbsolutePath().normalize();
        Path wrapper = directory.resolve("wrapper-image").resolve("codex-cargo-wrapper.exe");
        return new WindowsCargoInvocation.Configuration(List.of(directory.resolve("cargo.exe").toString()), directory,
            environment, MemorySegment.ofAddress(1), MemorySegment.ofAddress(2), MemorySegment.ofAddress(3),
            directory.resolve("rustc.exe"), wrapper, directory.resolve("frida.exe"), Map.of(wrapper, "0".repeat(64)),
            2, Duration.ofSeconds(1));
    }

    /**
     * Holds Cargo until the session exists and rejects replay of acquisition.
     * @throws IOException if startup or cleanup fails
     */
    @Test
    public void releasesOnlyAfterSessionConstruction() throws IOException
    {
        var resources = new Fixture();
        var owner = new WindowsCargoInvocation(resources);
        requireThat(resources.events.isEmpty(), "resourceFreeConstruction").isTrue();
        requireThat(owner.memoryObservations().isEmpty(), "unconfiguredHistoryHasNoObservations").isTrue();
        requireException(IllegalStateException.class, owner::session);
        owner.start();
        requireThat(resources.events, "startupOrder").isEqualTo(List.of("acquire", "factory", "measurements", "release"));
        requireThat(resources.session == owner.session(), "sameRetainedSession").isTrue();
        requireException(IllegalStateException.class, owner::start);
        owner.close();
        requireException(IllegalStateException.class, owner::start);
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
            var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
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
            var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
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
        var owner = new WindowsCargoInvocation(resources);
        var primary = requireException(IOException.class, owner::start);
        requireThat(primary == resources.acquisitionFailure, "originalFailure").isTrue();
        requireThat(owner.isClosed(), "partialOwnerRetained").isFalse();
        WindowsCargoInvocation.CleanupFailure marker = null;
        for (Throwable retained : primary.getSuppressed())
            if (retained instanceof WindowsCargoInvocation.CleanupFailure retainedOwner) marker = retainedOwner;
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
        var owner = new WindowsCargoInvocation(resources);
        var primary = requireException(IOException.class, owner::start);
        requireThat(primary == resources.acquisitionFailure, "exactOriginalFailure").isTrue();
        requireThat(List.of(primary.getSuppressed()).contains(resources.consumerFailure), "noSuppressionCycle").isFalse();
        requireThat(owner.isClosed(), "cyclicCleanupStillOwned").isFalse();
        resources.consumerFailure = null;
        owner.close();
        requireThat(owner.isClosed(), "resolvedConsumerCollection").isTrue();
    }

    /**
     * Attempts independent disposals once, retaining the first exact checked, unchecked or fatal failure.
     * A later call reports that same failure without repeating an uncertain effect.
     * @throws IOException if the successful cleanup boundary fails
     */
    @Test
    public void cleanupPreservesExactFailureAndAttemptsEveryIndependentOwnerOnce() throws IOException
    {
        for (Throwable primary : List.of(new IOException("uncertain close"),
            new IllegalStateException("uncertain close"), new AssertionError("uncertain close")))
        {
            var cleanup = new WindowsCargoInvocation.Cleanup();
            List<String> effects = new ArrayList<>();
            IOException secondary = new IOException("independent close");
            WindowsAttempt.Close[] owners = {
                () -> { effects.add("first"); rethrow(primary); },
                () -> { effects.add("second"); throw secondary; },
                () -> effects.add("last")
            };

            Throwable observed = requireException(primary.getClass(), () -> cleanup.close(owners));

            requireThat(observed == primary, "exactFirstFailure").isTrue();
            requireThat(List.of(observed.getSuppressed()), "independentFailure").isEqualTo(List.of(secondary));
            requireThat(effects, "everyIndependentOwnerAttempted").isEqualTo(List.of("first", "second", "last"));
            Throwable repeated = requireException(primary.getClass(), () -> cleanup.close(owners));
            requireThat(repeated == primary, "sameRetainedFailure").isTrue();
            requireThat(effects, "uncertainEffectsNeverReplayed").isEqualTo(List.of("first", "second", "last"));
        }
    }

    /**
     * Makes a successful disposal batch idempotent without replaying owners supplied on a later call.
     * @throws IOException if independent owner disposal fails
     */
    @Test
    public void cleanupDoesNotReplaySuccessfulDisposals() throws IOException
    {
        var cleanup = new WindowsCargoInvocation.Cleanup();
        List<String> effects = new ArrayList<>();
        cleanup.close(() -> effects.add("first"), () -> effects.add("second"));
        cleanup.close(() -> effects.add("replayed"));
        requireThat(effects, "oneSuccessfulDisposalBatch").isEqualTo(List.of("first", "second"));
    }

    /**
     * Invocation completion callbacks publish accepted samples for equivalent work in a later invocation.
     * Native tree residency and whole-Job completion are controlled inputs; this checks their production consumption.
     * @throws IOException if fixture setup, history publication or cleanup fails
     */
    @Test
    public void acceptedCompletionSeedsLaterInvocation() throws IOException
    {
        Path root = Files.createTempDirectory("windows-cargo-history-");
        try
        {
            HistoryFixture resources = new HistoryFixture(root);
            WindowsCargoInvocation owner = new WindowsCargoInvocation(resources);
            owner.start();
            try
            {
                CargoCompilerRequest raw = resources.request("first pipe");
                CargoCompilerRequest projected = resources.estimates.register(raw);
                requireThat(projected.peakBytes() == null, "coldUnknown").isTrue();
                owner.session().registerAuthenticated(projected, result -> {});
                owner.session().attempts().create(projected.name(), 0);
                resources.sample(projected, 0, 200);

                owner.session().acceptOutcome(new JobQueue.Completion<>(projected.name(), 0,
                    new JobQueue.Outcome<>(new WindowsProcess.Result(0, false), false)));

                requireThat(resources.estimates.observations().size(), "completionObservedByInvocation").isEqualTo(1);
                requireThat(resources.estimates.observations().getFirst().status(), "successfulAcceptedTraining")
                    .isEqualTo(CargoMemoryEstimates.Status.RECORDED);
                List<CargoMemoryEstimates.Observation> snapshot = owner.memoryObservations();
                requireThat(snapshot, "ownerExposesCompletedObservations").isEqualTo(resources.estimates.observations());
                requireException(UnsupportedOperationException.class, snapshot::clear);
                requireException(IllegalArgumentException.class, () -> owner.session().acceptOutcome(
                    new JobQueue.Completion<>(projected.name(), 0,
                        new JobQueue.Outcome<>(new WindowsProcess.Result(0, false), false))));
                requireThat(owner.memoryObservations().size(), "duplicateOutcomeCannotTrainTwice").isEqualTo(1);
                CargoMemoryEstimates later = resources.settings.observe(resources.samples);
                CargoCompilerRequest fresh = resources.request("fresh pipe");
                CargoCompilerRequest warm = later.register(fresh);
                requireThat(warm.peakBytes(), "laterInvocationPrediction").isEqualTo(250L);
                requireThat(warm.environment(), "rawAuthenticatedEnvironmentPreserved").isEqualTo(fresh.environment());
                requireThat(warm.wrapper(), "rawNativeIdentityPreserved").isEqualTo(fresh.wrapper());
                requireThat(warm.identity(), "rawActionIdentityPreserved").isEqualTo(fresh.identity());
            }
            finally { owner.close(); }
        }
        finally { remove(root); }
    }

    /**
     * Queue abortion, native Job abortion and nonzero Windows statuses never publish successful history.
     * @throws IOException if fixture setup, sampling or cleanup fails
     */
    @Test
    public void unsuccessfulCompletionCannotTrainHistory() throws IOException
    {
        for (String condition : List.of("queue-aborted", "native-aborted", "nonzero"))
        {
            Path root = Files.createTempDirectory("windows-cargo-unsuccessful-");
            try
            {
                HistoryFixture resources = new HistoryFixture(root);
                WindowsCargoInvocation owner = new WindowsCargoInvocation(resources);
                owner.start();
                try
                {
                    CargoCompilerRequest request = resources.estimates.register(resources.request("first pipe"));
                    owner.session().registerAuthenticated(request, result -> {});
                    owner.session().attempts().create(request.name(), 0);
                    resources.sample(request, 0, 900);
                    boolean queueAborted = condition.equals("queue-aborted");
                    boolean nativeAborted = condition.equals("native-aborted");
                    long status = condition.equals("nonzero") ? 0xc0000005L : 0;

                    owner.session().acceptOutcome(new JobQueue.Completion<>(request.name(), 0,
                        new JobQueue.Outcome<>(new WindowsProcess.Result(status, nativeAborted), queueAborted)));

                    requireThat(resources.estimates.observations().size(), "unsuccessfulOutcomeObserved").isEqualTo(1);
                    requireThat(resources.estimates.observations().getFirst().status(), condition)
                        .isEqualTo(CargoMemoryEstimates.Status.UNSUCCESSFUL);
                    CargoMemoryEstimates later = resources.settings.observe(resources.samples);
                    requireThat(later.register(resources.request("fresh pipe")).peakBytes() == null,
                        "failedWorkRemainsUnknown").isTrue();
                }
                finally { owner.close(); }
            }
            finally { remove(root); }
        }
    }

    /**
     * A successful retry trains only its own accepted residency rather than the superseded attempt's larger sample.
     * @throws IOException if fixture setup, callbacks or cleanup fail
     */
    @Test
    public void successfulRetryExcludesSupersededSample() throws IOException
    {
        Path root = Files.createTempDirectory("windows-cargo-retry-history-");
        try
        {
            HistoryFixture resources = new HistoryFixture(root);
            WindowsCargoInvocation owner = new WindowsCargoInvocation(resources);
            owner.start();
            try
            {
                CargoCompilerRequest request = resources.estimates.register(resources.request("first pipe"));
                List<Long> finalStatuses = new ArrayList<>();
                owner.session().registerAuthenticated(request, result -> finalStatuses.add(result.rootExit()));
                owner.session().attempts().create(request.name(), 0);
                resources.sample(request, 0, 900);
                owner.session().acceptOutcome(new JobQueue.Completion<>(request.name(), 0,
                    new JobQueue.Outcome<>(new WindowsProcess.Result(125, true), true)));
                requireThat(finalStatuses.isEmpty(), "abortedAttemptDoesNotAnswerWrapper").isTrue();
                owner.session().attempts().create(request.name(), 1);
                resources.sample(request, 1, 200);

                owner.session().acceptOutcome(new JobQueue.Completion<>(request.name(), 1,
                    new JobQueue.Outcome<>(new WindowsProcess.Result(0, false), false)));

                requireThat(finalStatuses, "onlySuccessfulRetryAnswersWrapper").isEqualTo(List.of(0L));
                requireThat(resources.estimates.observations().size(), "bothAcceptedOutcomesObserved").isEqualTo(2);
                CargoMemoryEstimates later = resources.settings.observe(resources.samples);
                requireThat(later.register(resources.request("fresh pipe")).peakBytes(), "retrySampleOnly").isEqualTo(250L);
            }
            finally { owner.close(); }
        }
        finally { remove(root); }
    }

    /**
     * Removes only the tree allocated by one history test, including assertion-failure cleanup.
     * @param root test-owned fixture tree
     * @throws IOException if deletion fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /**
     * Supplies controlled native memory values to the real history and session consumers.
     * A complete reading models the Windows Job residency already verified by the native owner; it grants no native claim.
     */
    private static final class Samples implements Controller.Measurements
    {
        /** Current complete-Job resident byte observation supplied by the test. */
        private long resident;

        /**
         * Creates method-owned native observation inputs.
         */
        private Samples() {}

        @Override public Controller.Memory readHost() { return new Controller.Memory(10000, 0, false); }
        @Override public long readResidentBytes(Policy.Job job) { return resident; }
    }

    /**
     * Composes actual history around controlled native measurements and an invocation's lifecycle seam.
     * The factory lends resource-free attempts; tests supply accepted whole-tree outcomes to the real CargoSession.
     */
    private static final class HistoryFixture extends Fixture
    {
        /** Actual history settings shared only by this test's successive invocations. */
        private final CargoMemoryEstimates.Settings settings;
        /** Complete source identity directory. */
        private final Path source;
        /** Pinned compiler identity file. */
        private final Path compiler;
        /** Controlled native observations consumed by the real estimator. */
        private final Samples samples = new Samples();
        /** Invocation-local production estimator. */
        private final CargoMemoryEstimates estimates;

        /**
         * Creates independent real source, toolchain and history identity inputs.
         * @param root test-owned temporary root, cleaned by the test
         * @throws IOException if the fixture cannot be created
         */
        private HistoryFixture(Path root) throws IOException
        {
            this.source = Files.createDirectory(root.resolve("source"));
            Path toolchain = Files.createDirectory(root.resolve("toolchain"));
            this.compiler = Files.writeString(toolchain.resolve("rustc.exe"), "compiler");
            Files.writeString(source.resolve("main.rs"), "fn main() {}\n");
            Path history = Files.createDirectory(root.resolve("history"));
            this.settings = new CargoMemoryEstimates.Settings(history, source, List.of(), toolchain);
            this.estimates = settings.observe(samples);
        }

        /**
         * Supplies an authenticated-data model with invocation-varying pipe authority and fresh native action identity.
         * @param pipe native registration pipe name excluded only from reusable work identity
         * @return raw immutable authenticated-data request; no Windows authentication is claimed by this fixture
         */
        private CargoCompilerRequest request(String pipe)
        {
            return new CargoCompilerRequest(UUID.randomUUID(), new CargoCompilerRequest.Wrapper(71, 81, 61, 91),
                List.of(compiler.toString(), "main.rs"), source, Map.of("LANG", "C", "CODEX_CARGO_PIPE", pipe), null);
        }

        /**
         * Supplies and accepts one complete measurement batch for the named live attempt.
         * @param request previously authenticated and projected compiler inputs
         * @param sequence queue-wide native attempt ordinal
         * @param resident observed whole-Job resident bytes
         * @throws IOException if a production measurement consumer rejects the sample
         */
        private void sample(CargoCompilerRequest request, long sequence, long resident) throws IOException
        {
            samples.resident = resident;
            Controller.Measurements measurements = session.measurements();
            measurements.readHost();
            measurements.readResidentBytes(new Policy.Job(request.name(), Policy.State.RUNNING, 0,
                request.peakBytes(), 0, sequence, null, 0));
            measurements.acceptReadings();
        }

        /**
         * Supplies the real invocation-local estimator to completion processing.
         * @return this fixture's production estimator
         */
        @Override public CargoMemoryEstimates memoryEstimates() { return estimates; }

        @Override public Controller.Measurements measurements() { return estimates; }
        @Override public JobQueue.AttemptFactory<WindowsProcess.Result> factory()
        {
            return (name, sequence) -> new JobQueue.Attempt<>()
            {
                @Override public void start() {}
                @Override public void resume(long waitSequence) {}
                @Override public void terminate() {}
                @Override public Optional<JobQueue.Outcome<WindowsProcess.Result>> poll() { return Optional.empty(); }
                @Override public void close() {}
            };
        }
    }

    /**
     * Supplies method-owned native outcomes without starting any process or native library.
     */
    private static class Fixture implements WindowsCargoInvocation.Resources
    {
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
        protected CargoSession<WindowsProcess.Result> session;

        /**
         * Creates independent native boundary outcomes.
         */
        private Fixture() {}

        @Override public void acquire() throws IOException { events.add("acquire"); rethrow(acquisitionFailure); }
        @Override public JobQueue.AttemptFactory<WindowsProcess.Result> factory()
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
        @Override public void release(CargoSession<WindowsProcess.Result> value) { session = value; events.add("release"); }
        @Override public void pump() {}
        @Override public Optional<SchedulerCoordinator.Blocked> pollBlocked() { return Optional.empty(); }
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
