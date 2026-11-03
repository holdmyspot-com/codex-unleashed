package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import com.holdmyspot.codexunleashed.scheduler.MacCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises invocation-scoped access to shared tooling without transferring its lifecycle ownership.
 */
public final class MacCargoScopeTest
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
     * Creates independent method fixtures.
     */
    public MacCargoScopeTest() {}

    /**
     * An outstanding invocation prevents shared-runtime destruction and further acquisition after disposal begins.
     * @throws java.io.IOException if controlled native ownership fails
     */
    @Test public void retainedBorrowPreventsScopeDestruction() throws java.io.IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            var scope = new MacCargoScope(tooling, (services, producer) -> calls);
            try (scope)
            {
                var first = scope.runtime(java.nio.file.Path.of("/trusted/frida"));
                requireThat(first.value() == calls.runtime, "retainedOriginalRuntime").isTrue();
                requireException(IllegalStateException.class, scope::close);
                first.close();
                requireException(IllegalStateException.class, () -> scope.runtime(java.nio.file.Path.of("/trusted/frida")));
            }
            requireThat(calls.starts, "oneOriginalInitialization").isEqualTo(1);
            requireThat(calls.closes, "originalDestroyedAfterLastBorrow").isEqualTo(1);
        }
    }

    /**
     * Warm matching invocations never initialize or destroy the original runtime between builds.
     * @throws java.io.IOException if controlled ownership fails
     */
    @Test public void warmInvocationBorrowsInitializedOriginal() throws java.io.IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            try (var scope = new MacCargoScope(tooling, (services, producer) -> calls))
            {
                var first = scope.runtime(java.nio.file.Path.of("/trusted/frida"));
                var original = first.value();
                first.close();
                requireThat(calls.closes, "noInvocationOwnedGlobalDestruction").isEqualTo(0);
                try (var second = scope.runtime(java.nio.file.Path.of("/trusted/frida")))
                {
                    requireThat(second.value() == original, "sameInitializedOriginal").isTrue();
                    requireThat(calls.starts, "oncePerScopeInitialization").isEqualTo(1);
                    requireException(java.io.IOException.class, () -> scope.runtime(java.nio.file.Path.of("/different/frida")));
                }
            }
            requireThat(calls.closes, "sharedOwnerClosedExactlyOnce").isEqualTo(1);
        }
    }

    /**
     * Failed original initialization stays retained and is never replayed by a later borrow.
     * @throws java.io.IOException if resource-free cleanup fails
     */
    @Test public void failedInitializationRetainsOriginalWithoutReplay() throws java.io.IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            calls.failure = new java.io.IOException("Original native init failed");
            try (var scope = new MacCargoScope(tooling, (services, producer) -> calls))
            {
                requireThat(requireException(java.io.IOException.class,
                    () -> scope.runtime(java.nio.file.Path.of("/trusted/frida"))), "originalFailure").isEqualTo(calls.failure);
                requireThat(requireException(java.io.IOException.class,
                    () -> scope.runtime(java.nio.file.Path.of("/trusted/frida"))).getCause(), "retainedFailure").isEqualTo(calls.failure);
                requireThat(calls.starts, "failedInitializationNeverReplayed").isEqualTo(1);
            }
            requireThat(calls.closes, "partialOriginalOwnerStillCollected").isEqualTo(1);
        }
    }

    /**
     * Retains uncertain shared destruction without replay or a false successful scope disposal.
     * @throws java.io.IOException if controlled borrowing fails
     */
    @Test public void uncertainSharedDestructionIsNeverReplayed() throws java.io.IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            var scope = new MacCargoScope(tooling, (services, producer) -> calls);
            scope.runtime(java.nio.file.Path.of("/trusted/frida")).close();
            calls.closeFailure = new java.io.IOException("Uncertain global destruction");
            RuntimeException original = requireException(RuntimeException.class, scope::close);
            requireThat(requireException(RuntimeException.class, scope::close), "sameUncertainOutcome").isEqualTo(original);
            requireThat(calls.closes, "globalDestructionNotReplayed").isEqualTo(1);
            requireThat(scope.isClosed(), "uncertaintyRetainsScopeOwnership").isFalse();
        }
    }

    /**
     * Original initialization retains scoped ownership before callbacks and excludes reentrant disposal/acquisition.
     * @throws java.io.IOException if accepted ownership fails
     */
    @Test public void originalInitializationCannotReenterOwnership() throws java.io.IOException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            try (var scope = new MacCargoScope(tooling, (services, producer) -> calls))
            {
                calls.onStart = () ->
                {
                    requireException(IllegalStateException.class, scope::close);
                    requireException(IllegalStateException.class, () -> scope.runtime(java.nio.file.Path.of("/trusted/frida")));
                };
                scope.runtime(java.nio.file.Path.of("/trusted/frida")).close();
                requireThat(calls.starts, "oneOriginalInitialization").isEqualTo(1);
            }
            requireThat(calls.closes, "retainedOriginalClosesAfterInitialization").isEqualTo(1);
        }
    }

    /**
     * Runtime borrows and their destruction remain confined to the original controller thread.
     * @throws java.io.IOException if accepted borrowing fails
     * @throws InterruptedException if the rejected caller cannot be joined
     */
    @Test public void sharedRuntimeRetainsOriginalControllerThread() throws java.io.IOException, InterruptedException
    {
        try (var tooling = new DefaultToolingScope())
        {
            var calls = new RuntimeCalls(tooling);
            try (var scope = new MacCargoScope(tooling, (services, producer) -> calls);
                var lease = scope.runtime(java.nio.file.Path.of("/trusted/frida")))
            {
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread other = Thread.ofPlatform().start(() ->
                {
                    try
                    {
                        requireException(IllegalStateException.class, () -> scope.runtime(java.nio.file.Path.of("/trusted/frida")));
                        requireException(IllegalStateException.class, lease::value);
                        requireException(IllegalStateException.class, lease::close);
                        requireException(IllegalStateException.class, scope::close);
                    }
                    catch (Throwable cause) { failure.set(cause); }
                });
                other.join();
                requireThat(failure.get(), "otherThreadRejectedWithoutChangingOwnership").isNull();
                requireThat(calls.starts, "noOtherThreadInitialization").isEqualTo(1);
            }
            requireThat(calls.closes, "originalControllerStillCloses").isEqualTo(1);
        }
    }

    /** Controls only native runtime acquisition; all scope and lease lifetime policy is production code. */
    private static final class RuntimeCalls implements MacCargoScope.RuntimeOwner
    {
        /** Resource-free original Java native owner. */
        private final FridaRuntime runtime;
        /** Native initialization boundary calls. */
        private int starts;
        /** Original destruction boundary calls. */
        private int closes;
        /** Optional original acquisition failure. */
        private java.io.IOException failure;
        /** Optional uncertain original destruction result. */
        private java.io.IOException closeFailure;
        /** Optional reentrant callback at the controlled native initialization boundary. */
        private Runnable onStart = () -> {};
        /** Creates resource-free controlled native ownership.
         * @param tooling per-method borrowed immutable services */
        private RuntimeCalls(DefaultToolingScope tooling)
        { runtime = new FridaRuntime(tooling, java.nio.file.Path.of("/trusted/frida")); }
        @Override public FridaRuntime value() { return runtime; }
        @Override public void start() throws java.io.IOException { starts++; onStart.run(); if (failure != null) throw failure; }
        @Override public void close() throws java.io.IOException { closes++; runtime.close(); if (closeFailure != null) throw closeFailure; }
    }

    /**
     * Two invocation scopes borrow the same caller-owned immutable parser through the tooling scope.
     */
    @Test public void borrowsSharedToolingServices()
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope())
        {
            MacCargoScope first = new MacCargoScope(tooling);
            try (first; MacCargoScope second = new MacCargoScope(tooling))
            {
            requireThat(first.tooling() == tooling, "originalTooling").isTrue();
            requireThat(first.tooling().getAllocationMessageMapper() == second.tooling().getAllocationMessageMapper(),
                "sharedBoundedParser").isTrue();
            first.close();
            requireThat(second.tooling() == tooling, "independentInvocationLifetime").isTrue();
            }
        }
    }

    /**
     * Retains one strict launch parser per invocation while preserving caller-owned shared tooling.
     */
    @Test public void launchParserIsSharedStrictAndInvocationOwned()
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope())
        {
            MacCargoScope scope = new MacCargoScope(tooling);
            try (scope)
            {
                requireThat(scope.launchJson() == scope.launchJson(), "scopedSharedLaunchParser").isTrue();
                requireException(tools.jackson.core.JacksonException.class,
                    () -> scope.launchJson().readTree("{\"version\":1,\"version\":1}"));
                scope.close();
                requireException(IllegalStateException.class, scope::launchJson);
                requireThat(tooling.isClosed(), "borrowedToolingRemainsOpen").isFalse();
            }
        }
    }

    /**
     * Disposal rejects further access, remains idempotent and preserves borrowed tooling ownership.
     */
    @Test public void closesOnlyItsInvocationScope()
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope())
        {
            MacCargoScope scope = new MacCargoScope(tooling);
            try (scope)
            {
            scope.close();
            scope.close();
            requireThat(scope.isClosed(), "closed").isTrue();
            requireException(IllegalStateException.class, scope::tooling);
            requireThat(tooling.isClosed(), "borrowedToolingClosed").isFalse();
            requireThat(tooling.getAllocationMessageMapper(), "borrowedParserStillAvailable").isNotNull();
            }
        }
        requireException(NullPointerException.class, () -> new MacCargoScope(null));
    }
}
