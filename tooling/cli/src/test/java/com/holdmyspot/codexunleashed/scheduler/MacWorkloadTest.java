package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the production workload lifecycle using independently controlled native boundary observations.
 * Scripted terminal results represent the full native contract, never proof of the kernel implementation.
 */
public final class MacWorkloadTest
{
    /**
     * Creates stateless tests with method-owned scopes and native observations.
     */
    public MacWorkloadTest() {}

    /**
     * Rejects an absent descriptor before native acquisition can consume another resource.
     */
    @Test public void borrowedStreamsRejectNegativeDescriptors()
    {
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Streams(-1, 1, 2));
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Streams(0, -1, 2));
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Streams(0, 1, -1));
    }

    /**
     * Rejects ambiguous environment entries before a native launch can acquire resources.
     */
    @Test public void launchRejectsDuplicateEnvironmentNames()
    {
        Path absolute = Path.of(".").toAbsolutePath();
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Launch(
            absolute, absolute, absolute, absolute, new MacWorkloadNative.Streams(0, 1, 2), List.of(absolute.toString()), List.of("NAME=first", "NAME=second"), 501, 20));
    }

    /**
     * Checks the native control socket's UTF-8 byte boundary before acquiring a workload owner.
     */
    @Test public void launchValidatesControlSocketByteBudget()
    {
        Path root = Path.of(".").toAbsolutePath().getRoot();
        int prefixBytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        Path boundary = root.resolve("a".repeat(90 - prefixBytes));
        new MacWorkloadNative.Launch(root, root, boundary, root, new MacWorkloadNative.Streams(0, 1, 2),
            List.of(root.toString()), List.of(), 501, 20);
        Path overlong = root.resolve("a".repeat(91 - prefixBytes));
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Launch(
            root, root, overlong, root, new MacWorkloadNative.Streams(0, 1, 2), List.of(root.toString()), List.of(), 501, 20));
        Path multibyte = root.resolve("é".repeat(46));
        requireException(IllegalArgumentException.class, () -> new MacWorkloadNative.Launch(
            root, root, multibyte, root, new MacWorkloadNative.Streams(0, 1, 2), List.of(root.toString()), List.of(), 501, 20));
    }

    /**
     * Instruments the root before its one original release, then closes images before native ownership.
     * @throws IOException if the connected workload operation fails
     */
    @Test public void normalCompletionOrdersReleaseAndCleanup() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls();
            MacWorkload owner = new MacWorkload(scope, calls, calls::instrumentation, Duration.ofSeconds(1));
            owner.start();
            requireThat(calls.events.indexOf("instrument:10") < calls.events.indexOf("release-root"),
                "instrumentationBeforeOriginalRelease").isTrue();
            requireException(IllegalStateException.class, owner::start);
            requireThat(owner.advance().isEmpty(), "unresolvedTerminalCompositionRetainsOwnership").isTrue();
            requireThat(calls.events.contains("close-native"), "nativeOwnerStillRetained").isFalse();
            requireThat(owner.completedResult().isEmpty(), "noCompletionBeforeNativeAndImageCleanup").isTrue();
            calls.result = OptionalInt.of(17);
            requireThat(owner.advance().orElseThrow(), "genuineOriginalRootResult").isEqualTo(17, "expected");
            requireThat(calls.events.indexOf("close-connection:10") < calls.events.indexOf("close-native"),
                "imagesBeforeNativeOwner").isTrue();
            requireThat(owner.completedResult().orElseThrow(), "completedResultAvailableAfterCleanup").isEqualTo(17, "expected");
            requireThat(owner.advance().orElseThrow(), "completedResultRemainsAvailable").isEqualTo(17, "expected");
            requireThat(calls.events.stream().filter("release-root"::equals).count(), "oneOriginalRelease").isEqualTo(1L, "expected");
            requireThat(calls.events.stream().filter("close-native"::equals).count(), "oneNativeClose").isEqualTo(1L, "expected");
        }
    }

    /**
     * Starts native recovery when primary observation fails and retains ownership until terminal proof.
     * @throws IOException if initial setup fails
     */
    @Test public void observerLossCancelsBeforeCleanupAndPreservesFailure() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls();
            MacWorkload owner = new MacWorkload(scope, calls, calls::instrumentation, Duration.ofSeconds(1));
            owner.start();
            IOException loss = new IOException("Primary membership observation failed");
            calls.observationFailure = loss;
            requireThat(requireException(IOException.class, owner::advance) == loss, "exactObserverFailure").isTrue();
            requireThat(calls.events.contains("cancel-owned"), "retainedNativeAuthorityStartsRecovery").isTrue();
            requireThat(calls.events.contains("close-native"), "unresolvedWorkloadStillOwned").isFalse();
            requireThat(owner.advance().isEmpty(), "canceledWorkloadWaitsForNativeComposition").isTrue();
            calls.result = OptionalInt.of(9);
            requireThat(requireException(IOException.class, owner::advance) == loss, "failureSurvivesCleanup").isTrue();
            requireThat(calls.events.contains("close-native"), "terminalNativeOwnerClosed").isTrue();
            requireThat(owner.completedResult().orElseThrow(), "genuineResultSurvivesObservationFailure").isEqualTo(9, "expected");
            requireThat(calls.polls, "primaryObserverNeverReusedAfterLoss").isEqualTo(1, "expected");
        }
    }

    /**
     * Keeps the original root held and starts cancellation if required instrumentation fails.
     * @throws IOException if construction fails
     */
    @Test public void failedInstrumentationNeverReleasesOriginalRoot() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls();
            IOException denial = new IOException("Required root hook did not acknowledge readiness");
            calls.instrumentationFailure = denial;
            MacWorkload owner = new MacWorkload(scope, calls, calls::instrumentation, Duration.ofSeconds(1));
            requireThat(requireException(IOException.class, owner::start) == denial, "exactReadinessFailure").isTrue();
            requireThat(calls.events.contains("release-root"), "originalRootNeverReleased").isFalse();
            requireThat(calls.events.contains("cancel-owned"), "partialRootRemainsNativelyOwned").isTrue();
            calls.result = OptionalInt.of(9);
            requireThat(requireException(IOException.class, owner::advance) == denial, "readinessFailureRetained").isTrue();
            requireThat(calls.events.contains("close-native"), "partialAcquisitionsClosedAfterTerminal").isTrue();
        }
    }

    /**
     * Rejects instrumentation reentry before it can cancel a transition that will release the root.
     * @throws IOException if setup or cleanup fails
     */
    @Test public void instrumentationCannotReenterWorkloadControl() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls();
            MacWorkload owner = new MacWorkload(scope, calls, calls::instrumentation, Duration.ofSeconds(1));
            calls.duringInstrumentation = () ->
            {
                requireException(IllegalStateException.class, owner::cancel);
                requireException(IllegalStateException.class, owner::advance);
            };
            owner.start();
            requireThat(calls.events.contains("cancel-owned"), "reentryDidNotCancelActiveTransition").isFalse();
            calls.result = OptionalInt.of(0);
            requireThat(owner.advance().orElseThrow(), "normalResultAfterRejectedReentry").isEqualTo(0, "expected");
        }
    }

    /**
     * Keeps a failed native close retained without replaying it or signaling after the producer cut.
     * @throws IOException if setup fails
     */
    @Test public void failedNativeCloseRetainsOwnershipWithoutReplay() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            Calls calls = new Calls();
            MacWorkload owner = new MacWorkload(scope, calls, calls::instrumentation, Duration.ofSeconds(1));
            owner.start();
            calls.result = OptionalInt.of(0);
            IOException exact = new IOException("Service bootout effect is uncertain");
            calls.closeFailure = exact;
            requireThat(requireException(IOException.class, owner::advance) == exact, "exactNativeCloseFailure").isTrue();
            requireException(IOException.class, owner::advance);
            requireThat(owner.completedResult().isEmpty(), "failedCleanupDoesNotPublishCompletion").isTrue();
            requireThat(calls.events.stream().filter("close-native"::equals).count(), "uncertainCloseNeverReplayed").isEqualTo(1L, "expected");
            requireThat(calls.events.contains("cancel-owned"), "noSignalsAfterProducerCut").isFalse();
        }
    }

    /**
     * Supplies recorded native contract shapes without claiming to implement or verify the kernel behavior.
     * The accepted ARM64 fixture separately establishes genuine root wait17 and authenticated cancellation.
     */
    private static final class Calls implements MacWorkloadNative
    {
        /** Cross-component effects controlled by production ordering. */
        private final List<String> events = new ArrayList<>();
        /** Root result becomes present only when the test supplies the full native terminal contract. */
        private OptionalInt result = OptionalInt.empty();
        /** Optional primary membership observation failure. */
        private IOException observationFailure;
        /** Optional required instrumentation readiness failure. */
        private IOException instrumentationFailure;
        /** Number of original primary-observer polls. */
        private int polls;
        /** Optional instrumentation callback used to exercise production reentry guards. */
        private Runnable duringInstrumentation;
        /** Optional native cleanup failure after its effect becomes uncertain. */
        private IOException closeFailure;

        /**
         * Creates per-method native observations without acquiring external resources.
         */
        private Calls() {}

        @Override public MacImages.Identity root() { return new MacImages.Identity(10, Instant.ofEpochSecond(1), 9); }
        @Override public MacImages.Identity inspect(long pid) { return root(); }
        @Override public void authenticate(MacImages.Identity identity) { events.add("authenticate:" + identity.pid()); }
        @Override public void releaseRoot() { events.add("release-root"); }
        @Override public void resume(long pid) { events.add("resume:" + pid); }
        @Override public void cancel() { events.add("cancel-owned"); }
        @Override public OptionalInt terminalResult() { return result; }
        @Override public void close() throws IOException
        {
            events.add("close-native");
            if (closeFailure != null) throw closeFailure;
        }
        @Override public Optional<FridaRuntime.Child> pollChild() throws IOException
        {
            polls++;
            if (observationFailure != null) throw observationFailure;
            return Optional.empty();
        }
        @Override public AllocationHook.Connection connection(long pid)
        {
            return new AllocationHook.Connection()
            {
                /** Exact send payload emitted by the image readiness script. */
                private String payload;
                /** Indicates whether the simulated script still owes its readiness message. */
                private boolean pending;
                @Override public void start(String script)
                {
                    payload = script.substring(5, script.length() - 2);
                    pending = true;
                }
                @Override public Optional<String> pollMessage()
                {
                    if (!pending) return Optional.empty();
                    pending = false;
                    return Optional.of("{\"type\":\"send\",\"payload\":" + payload + "}");
                }
                @Override public void post(JsonNode message) { throw new UnsupportedOperationException("No allocation messages in this lifecycle fixture"); }
                @Override public void close() { events.add("close-connection:" + pid); }
            };
        }

        /**
         * Supplies one required per-image instrumentation owner whose order production must enforce.
         * @param identity image being instrumented
         * @return fresh resource-free instrumentation
         */
        private MacImages.Instrumentation instrumentation(MacImages.Identity identity)
        {
            return new MacImages.Instrumentation()
            {
                @Override public void start() throws IOException
                {
                    if (instrumentationFailure != null) throw instrumentationFailure;
                    if (duringInstrumentation != null) duringInstrumentation.run();
                    events.add("instrument:" + identity.pid());
                }
                @Override public void close() { events.add("close-instrument:" + identity.pid()); }
            };
        }
    }
}
