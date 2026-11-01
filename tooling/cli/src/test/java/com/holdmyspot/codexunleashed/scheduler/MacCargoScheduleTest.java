package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Portable command-composition checks using controlled external native observations; no native acceptance is claimed. */
public final class MacCargoScheduleTest
{
    /** Creates independent method fixtures. */
    public MacCargoScheduleTest() {}

    /**
     * Passes exact command inputs, original descriptors and installed paths to the retained native invocation boundary.
     * @throws IOException if fixture setup, command composition or cleanup fails
     */
    @Test public void composesActualInvocationInputsAndReturnsOriginalStatus() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            int status = MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture);
            requireThat(status, "originalCollectedCargoStatus").isEqualTo(37, "expected");
            var configuration = fixture.configuration;
            requireThat(configuration.cargo().command(), "exactToolArgv").isEqualTo(
                List.of("/trusted/tools/cargo", "build", "--package", "name with space"), "expected");
            requireThat(configuration.cargo().workingDirectory(), "exactWorkingDirectory").isEqualTo(Path.of("/authorized/work"), "expected");
            requireThat(configuration.cargo().streams(), "actualBorrowedInheritedDescriptors").isEqualTo(new MacWorkloadNative.Streams(0, 1, 2), "expected");
            requireThat(configuration.cargo().environment().contains("KEY=space λ=kept"), "exactAuthorizedEnvironment").isTrue();
            requireThat(configuration.registration().wrapperExecutable(), "actualBundledWrapper").isEqualTo(
                Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/codex-cargo-wrapper"), "expected");
            requireThat(configuration.cargo().uid(), "positiveWorkloadUid").isEqualTo(501, "expected");
            requireThat(configuration.cargo().gid(), "positiveWorkloadGid").isEqualTo(20, "expected");
            requireThat(fixture.events, "authorizedAcquisitionAndCleanupOrder").isEqualTo(
                List.of("preflight", "streams", "namespace", "acquire", "start", "run", "close", "remove"), "expected");
            requireThat(fixture.scope.isClosed(), "scopeClosesAfterNativeBorrowers").isTrue();
            requireThat(fixture.tooling.isClosed(), "callerRetainsTooling").isFalse();
        }
    }

    /**
     * Stops before namespace creation or native acquisition when external administrator trust is rejected.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test public void trustFailurePreventsAllAcquisition() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.trustFailure = new IOException("administrator namespace is mutable");
            requireThat(requireException(IOException.class,
                () -> MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture)) == fixture.trustFailure,
                "originalTrustFailure").isTrue();
            requireThat(fixture.events, "noResourcesBeforeTrust").isEqualTo(List.of("preflight"), "expected");
        }
    }

    /**
     * Refuses caller-selected native descriptor numbers before even preparing a namespace.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test public void nonInheritedStreamsPreventAllAcquisition() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.streams = new MacWorkloadNative.Streams(20, 21, 22);
            requireException(IOException.class, () -> MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture));
            requireThat(fixture.events, "noResourcesForUntrustedDescriptors").isEqualTo(List.of("preflight", "streams"), "expected");
        }
    }

    /**
     * Refuses closed or incompatible inherited streams before preparing native state.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test public void unavailableInheritedStreamPreventsAllAcquisition() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.streamFailure = new IOException("inherited stdin is closed");
            requireException(IOException.class, () -> MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture));
            requireThat(fixture.events, "noResourcesForClosedStream").isEqualTo(List.of("preflight", "streams"), "expected");
        }
    }

    /**
     * Keeps original operational failure, native ownership, namespace and scope alive until explicit cleanup succeeds.
     * @throws IOException if fixture setup or eventual cleanup fails
     */
    @Test public void pendingCleanupRetainsNativeLifetimeAndOriginalFailure() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.runFailure = new IOException("original coordinator failure");
            fixture.closeFailure = new IOException("tree remains owned");
            Scheduler.PendingCleanup pending = requireException(Scheduler.PendingCleanup.class,
                () -> MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture));
            requireThat(pending.getCause() == fixture.runFailure, "originalExecutionFailureRetained").isTrue();
            requireThat(List.of(fixture.runFailure.getSuppressed()).contains(fixture.closeFailure), "cleanupFailureRetained").isTrue();
            requireThat(fixture.scope.isClosed(), "scopeRetainedForLiveNativeBorrowers").isFalse();
            requireThat(fixture.events.contains("remove"), "ownedNamespaceRetained").isFalse();
            requireException(IOException.class, pending::close);
            requireThat(fixture.scope.isClosed(), "scopeRemainsOpenAfterIncompleteCleanup").isFalse();
            fixture.closeFailure = null;
            pending.close();
            int events = fixture.events.size(); pending.close();
            requireThat(fixture.events.size(), "completedCleanupDoesNotReplay").isEqualTo(events, "expected");
            requireThat(fixture.scope.isClosed(), "scopeClosesAfterActualBoundaryCollection").isTrue();
            requireThat(fixture.tooling.isClosed(), "borrowedToolingRemainsCallerOwned").isFalse();
        }
    }

    /**
     * Retains final scope disposal and its original operational failure until a real borrower returns.
     * @throws IOException if fixture setup or supported cleanup fails
     */
    @Test public void finalScopeDisposalRetainsCleanupUntilBorrowerReturns() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.retainRuntimeBorrow = true;
            fixture.runFailure = new IOException("original Cargo coordination failure");
            Scheduler.PendingCleanup pending = requireException(Scheduler.PendingCleanup.class,
                () -> MacCargoSchedule.run(fixture.tooling, fixture.settings, fixture.source, fixture));
            requireThat(pending.getCause() == fixture.runFailure, "originalFailureRetained").isTrue();
            requireThat(fixture.runFailure.getSuppressed().length, "scopeCleanupFailureRetained").isEqualTo(1, "expected");
            requireThat(fixture.scope.isClosed(), "scopeRetained").isFalse();
            requireException(IllegalStateException.class, pending::close);
            fixture.runtimeBorrow.close();
            pending.close(); pending.close();
            requireThat(fixture.scope.isClosed(), "scopeActuallyDestroyed").isTrue();
            requireThat(fixture.runtimeCloses, "sharedRuntimeDestroyedOnce").isEqualTo(1, "expected");
            requireThat(fixture.events.stream().filter("remove"::equals).count(), "namespaceRemovedOnce").isEqualTo(1L, "expected");
            requireThat(fixture.tooling.isClosed(), "callerRetainsTooling").isFalse();
        }
    }

    /** Rejects overlong UTF-8 paths before any native bootstrap, including multibyte names. */
    @Test public void rejectsNativePathBudgetOverflow()
    {
        requireException(IOException.class, () -> MacCargoSchedule.requirePathBudget(Path.of("/" + "λ".repeat(30))));
    }

    /** Owns one complete controlled acquisition boundary and real method-local administrator document. */
    private static final class Fixture implements MacCargoSchedule.Boundary, AutoCloseable
    {
        /** Caller-owned shared services. */
        private final DefaultToolingScope tooling = new DefaultToolingScope();
        /** Actual temporary document namespace. */
        private final Path documents;
        /** Actual document input. */
        private final Path source;
        /** Exact application inputs before composition. */
        private final Scheduler.Settings settings = new Scheduler.Settings("cargo", Path.of("/authorized/work"),
            Path.of("/authorized/scratch"), Path.of("/trusted/frida"), new Policy.Budget(73, 2),
            Duration.ofSeconds(1), List.of("build", "--package", "name with space"), null, null);
        /** Ordered controlled external observations. */
        private final List<String> events = new ArrayList<>();
        /** Controlled external trust failure. */
        private IOException trustFailure;
        /** Controlled inherited-descriptor observation failure. */
        private IOException streamFailure;
        /** Controlled original coordination failure. */
        private IOException runFailure;
        /** Controlled unresolved native collection. */
        private IOException closeFailure;
        /** Controlled inherited descriptor observations. */
        private MacWorkloadNative.Streams streams = new MacWorkloadNative.Streams(0, 1, 2);
        /** Whether a genuine scoped lease outlives execution cleanup. */
        private boolean retainRuntimeBorrow;
        /** Retained real scoped lease, returned only by the test owner. */
        private MacCargoScope.RuntimeLease runtimeBorrow;
        /** Observed supported external runtime destruction count. */
        private int runtimeCloses;
        /** Actual composed immutable native inputs. */
        private MacCargoInvocation.Configuration configuration;
        /** Actual scope retained by the public composition. */
        private MacCargoScope scope;

        /**
         * Creates method-local document bytes without acquiring a native workload.
         * @throws IOException if fixture storage or encoding fails
         */
        private Fixture() throws IOException
        {
            documents = Files.createTempDirectory("mac-schedule-"); source = documents.resolve("launch.json");
            try (var parser = new MacCargoScope(tooling)) { Files.write(source, MacCargoLaunchConfigurationTest.document(parser)); }
        }
        @Override public MacCargoScope createScope(ToolingScope borrowed)
        {
            return new MacCargoScope(borrowed, (services, producer) -> new MacCargoScope.RuntimeOwner()
            {
                /** Resource-free original runtime; this portable boundary performs no native initialization. */
                private final FridaRuntime original = new FridaRuntime(services, producer);
                @Override public FridaRuntime value() { return original; }
                @Override public void start() {}
                @Override public void close() throws IOException { ++runtimeCloses; original.close(); }
            });
        }
        @Override public void preflight(ToolingScope borrowed, MacCargoLaunchConfiguration authorization,
            MacCargoLaunchConfiguration.Installed installed, Scheduler.Settings inputs) throws IOException
        {
            events.add("preflight");
            if (trustFailure != null) throw trustFailure;
        }
        @Override public MacWorkloadNative.Streams inheritedStreams(ToolingScope borrowed) throws IOException
        {
            events.add("streams"); if (streamFailure != null) throw streamFailure; return streams;
        }
        @Override public MacCargoSchedule.Namespace createNamespace(Path scratch, int gid)
        {
            events.add("namespace");
            Path root = Path.of("/controlled/m-" + UUID.randomUUID());
            return new MacCargoSchedule.Namespace(root, root.resolve("o"), root.resolve("a"), root.resolve("e"));
        }
        @Override public void removeNamespace(MacCargoSchedule.Namespace namespace) { events.add("remove"); }
        @Override public MacCargoSchedule.Execution acquire(MacCargoScope retained, MacCargoInvocation.Configuration inputs,
            CargoMemoryEstimates.Settings memory) throws IOException
        {
            events.add("acquire"); scope = retained; configuration = inputs;
            if (retainRuntimeBorrow) runtimeBorrow = scope.runtime(inputs.frida());
            return new MacCargoSchedule.Execution()
            {
                /** Indicates complete controlled native collection. */
                private boolean closed;
                @Override public void start() { events.add("start"); }
                @Override public int run(Policy.Budget budget) throws IOException
                {
                    events.add("run"); if (runFailure != null) throw runFailure; return 37;
                }
                @Override public boolean isClosed() { return closed; }
                @Override public void close() throws IOException
                {
                    events.add("close"); if (closeFailure != null) throw closeFailure; closed = true;
                }
            };
        }
        @Override public void close() throws IOException
        {
            tooling.close(); Files.deleteIfExists(source); Files.delete(documents);
        }
    }
}
