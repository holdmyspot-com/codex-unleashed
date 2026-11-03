package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoSchedule;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoScope;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoStreams;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises production installed-input preflight and launch construction through a controlled native execution boundary.
 * These portable checks do not claim that Windows starts a native process or accepts the modeled handle values.
 */
public final class WindowsCargoScheduleTest
{
    /**
     * Creates independent installed-command checks.
     */
    public WindowsCargoScheduleTest() {}

    /**
     * Preflight delivers verified inputs and constructs exact native argv, cwd, environment and stream roles.
     * @throws IOException if fixture setup, preflight or cleanup fails
     */
    @Test
    public void constructsExactInvocationAfterIdentityPreflight() throws IOException
    {
        try (WindowsCargoLaunchConfigurationTest.Fixture fixture = new WindowsCargoLaunchConfigurationTest.Fixture())
        {
            Scheduler.Settings settings = fixture.settings();
            int status = WindowsCargoSchedule.run(fixture.tooling, settings, fixture.runtime,
                (scope, selected, configuration, wrapper) ->
                {
                    requireThat(scope.isClosed(), "servicesOpenAtNativeBoundary").isFalse();
                    try (WindowsCargoStreams.Root streams = streams())
                    {
                        WindowsCargoInvocation.Configuration inputs = WindowsCargoSchedule.inputs(selected, configuration, wrapper, streams);
                        requireThat(inputs.cargo(), "unchangedNativeCommand").isEqualTo(List.of(fixture.cargo.toString(),
                            "build", "a b", "", "--jobs=7"));
                        requireThat(inputs.directory(), "unchangedNativeWorkingDirectory").isEqualTo(fixture.root);
                        requireThat(inputs.environment(), "completeExplicitEnvironment").isEqualTo(configuration.environment());
                        requireThat(inputs.input().address(), "originalLocalInput").isEqualTo(10L);
                        requireThat(inputs.output().address(), "originalLocalOutput").isEqualTo(11L);
                        requireThat(inputs.error().address(), "originalLocalError").isEqualTo(12L);
                        requireThat(inputs.wrapper(), "installedSiblingWrapper").isEqualTo(fixture.wrapper);
                        requireThat(inputs.jobs(), "configuredCapacity").isEqualTo(2);
                        requireThat(inputs.timeout(), "configuredControlTimeout").isEqualTo(settings.controlTimeout());
                    }
                    return 101;
                });
            requireThat(status, "originalCargoFailureStatus").isEqualTo(101);
        }
    }

    /**
     * Changed native library bytes prevent the execution boundary from receiving any launch authority.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void identityFailurePreventsNativeAcquisition() throws IOException
    {
        try (WindowsCargoLaunchConfigurationTest.Fixture fixture = new WindowsCargoLaunchConfigurationTest.Fixture())
        {
            AtomicInteger calls = new AtomicInteger();
            Files.writeString(fixture.frida, "changed library");
            requireException(IOException.class, () -> WindowsCargoSchedule.run(fixture.tooling, fixture.settings(), fixture.runtime,
                (scope, selected, configuration, wrapper) -> calls.incrementAndGet()));
            requireThat(calls.get(), "noNativeAcquisition").isEqualTo(0);
        }
    }

    /**
     * A retained native-boundary cleanup owner keeps its borrowed invocation scope open until its callback completes.
     * The callback models ownership retained by the native executor; no real native cleanup is claimed.
     * @throws IOException if fixture setup or retained cleanup fails
     */
    @Test
    public void pendingCleanupRetainsInvocationScope() throws IOException
    {
        try (WindowsCargoLaunchConfigurationTest.Fixture fixture = new WindowsCargoLaunchConfigurationTest.Fixture())
        {
            AtomicReference<WindowsCargoScope> borrowed = new AtomicReference<>();
            Scheduler.PendingCleanup pending = requireException(Scheduler.PendingCleanup.class, () ->
                WindowsCargoSchedule.run(fixture.tooling, fixture.settings(), fixture.runtime, (scope, selected, configuration, wrapper) ->
                {
                    borrowed.set(scope);
                    throw new Scheduler.PendingCleanup(new IOException("controlled native borrowers remain"), scope::close);
                }));
            try { requireThat(borrowed.get().isClosed(), "scopeRetainedWhileCleanupPending").isFalse(); }
            finally { pending.close(); }
            requireThat(borrowed.get().isClosed(), "scopeClosedByRetainedOwner").isTrue();
        }
    }

    /**
     * An ordinary executor failure closes the invocation scope when no native borrower retains it.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void ordinaryFailureClosesInvocationScope() throws IOException
    {
        try (WindowsCargoLaunchConfigurationTest.Fixture fixture = new WindowsCargoLaunchConfigurationTest.Fixture())
        {
            AtomicReference<WindowsCargoScope> borrowed = new AtomicReference<>();
            IOException original = new IOException("controlled execution failure");
            IOException observed = requireException(IOException.class, () ->
                WindowsCargoSchedule.run(fixture.tooling, fixture.settings(), fixture.runtime, (scope, selected, configuration, wrapper) ->
                {
                    borrowed.set(scope);
                    throw original;
                }));
            requireThat(observed, "originalExecutorFailure").isEqualTo(original);
            requireThat(borrowed.get().isClosed(), "scopeClosedAfterOrdinaryFailure").isTrue();
        }
    }

    /**
     * Models this process's original standard handles for launch-input construction without native acquisition.
     * @return binding owner whose supplied handle values are never dereferenced or passed to a native process
     * @throws IOException if production capture rejects the controlled handle values
     */
    private static WindowsCargoStreams.Root streams() throws IOException
    {
        return WindowsCargoStreams.root(new WindowsCargoStreams.Calls()
        {
            @Override public MemorySegment address(String name, Object... arguments)
            {
                int role = (int) arguments[0];
                return MemorySegment.ofAddress(-role);
            }
            @Override public Arena arena() { throw new AssertionError("No native storage required"); }
            @Override public int integer(String name, Object... arguments) { throw new AssertionError("No native I/O requested"); }
            @Override public int error() { throw new AssertionError("No native I/O requested"); }
            @Override public boolean console(MemorySegment handle) { throw new AssertionError("Root handles remain local"); }
            @Override public void closeHandle(MemorySegment handle) { throw new AssertionError("Original handle remains borrowed"); }
            @Override public void close() {}
        });
    }
}
