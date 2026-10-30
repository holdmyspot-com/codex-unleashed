package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;

/** Checks resource-free identities and notification deferral using production attempt adapters. */
public final class WindowsAttemptsTest
{
    static WindowsAttempts.Command command(String name, long position)
    {
        return new WindowsAttempts.Command(new Controller.Action(name, position), java.util.List.of("compiler.exe"),
            Path.of("not-opened"), Map.of("CARGO_MAKEFLAGS", "-j --jobserver-auth=borrowed"),
            MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
    }

    @Test
    public void reservesUnusedOrdinalEvenWhenResourcePlanCreationFails()
    {
        UUID build = UUID.randomUUID();
        WindowsAttempts factory = new WindowsAttempts(build, Map.of("compile", command("compile", 0)),
            Duration.ofSeconds(1), (command, sequence, identity, timeout) -> { throw new IllegalStateException("No native acquisition"); });
        requireException(IllegalStateException.class, () -> factory.create("compile", 7));
        requireException(IllegalArgumentException.class, () -> factory.create("compile", 7));
        requireThat(factory.hasOpenAttempts(), "noAttemptOwnerWasReturned").isFalse();
        UUID expected = UUID.nameUUIDFromBytes(ByteBuffer.allocate(24).putLong(build.getMostSignificantBits()).
            putLong(build.getLeastSignificantBits()).putLong(7).array());
        requireThat(WindowsAttempts.attemptIdentity(build, 7), "fixedWidthIdentity").isEqualTo(expected);
        requireException(IllegalArgumentException.class, () -> WindowsAttempts.attemptIdentity(build, -1));
    }

    @Test
    public void validatesActionMappingPositionsAndTimeoutBeforeAcquisition()
    {
        WindowsAttempts.ResourcesFactory unused = (command, sequence, identity, timeout) -> new WindowsAttemptTest.Resources();
        requireException(IllegalArgumentException.class, () -> new WindowsAttempts(UUID.randomUUID(),
            Map.of("wrong", command("compile", 0)), Duration.ofSeconds(1), unused));
        requireException(IllegalArgumentException.class, () -> new WindowsAttempts(UUID.randomUUID(),
            Map.of("a", command("a", 0), "b", command("b", 0)), Duration.ofSeconds(1), unused));
        requireException(IllegalArgumentException.class, () -> WindowsAttempts.checkedTimeout(Duration.ZERO));
        requireException(IllegalArgumentException.class, () -> WindowsAttempts.checkedTimeout(Duration.ofSeconds(Long.MAX_VALUE)));
        requireException(IllegalArgumentException.class, () -> new WindowsAttempts.Blocked(" ", 0, 0));
    }

    @Test
    public void defersOwnFreshBatchWhileAcknowledgmentServicesPeer() throws Exception
    {
        Map<String, WindowsAttemptTest.Resources> resources = new HashMap<>();
        resources.put("a", new WindowsAttemptTest.Resources());
        resources.put("b", new WindowsAttemptTest.Resources());
        WindowsAttempts factory = new WindowsAttempts(UUID.randomUUID(),
            Map.of("a", command("a", 0), "b", command("b", 1)), Duration.ofSeconds(1),
            (command, sequence, identity, timeout) -> resources.get(command.action().name()));
        WindowsAttempt a = factory.create("a", 0);
        WindowsAttempt b = factory.create("b", 1);
        a.start(); b.start();
        resources.get("a").waits.blocked(new AllocationWaits.Wait(1, 1));
        factory.pumpAll();
        requireThat(factory.pollBlocked().orElseThrow(), "initialABatch").isEqualTo(new WindowsAttempts.Blocked("a", 0, 0));
        resources.get("a").onGrant = () ->
        {
            resources.get("b").waits.blocked(new AllocationWaits.Wait(1, 1));
            resources.get("a").waits.blocked(new AllocationWaits.Wait(1, 2));
            factory.pumpAll();
            requireThat(factory.pollBlocked().orElseThrow(), "peerFirstDuringOwnAcknowledgment").isEqualTo(new WindowsAttempts.Blocked("b", 1, 0));
            requireThat(factory.pollBlocked().isEmpty(), "ownFreshBatchRemainsDeferredDuringControl").isTrue();
        };
        a.resume(0);
        factory.pumpAll();
        requireThat(factory.pollBlocked().orElseThrow(), "ownFollowingBatchAfterAcknowledgedControl").isEqualTo(new WindowsAttempts.Blocked("a", 0, 1));
        for (var resource : resources.values()) resource.result = new WindowsProcess.Result(0, false);
        a.close(); b.close();
        requireThat(factory.hasOpenAttempts(), "queueOwnersCleaned").isFalse();
    }

    @Test
    public void queueCollectsAuthenticOutcomeBeforeRetryWithFreshIdentity() throws Exception
    {
        var creations = new java.util.ArrayList<WindowsAttemptTest.Resources>();
        var identities = new java.util.ArrayList<UUID>();
        WindowsAttempts factory = new WindowsAttempts(UUID.randomUUID(), Map.of("compile", command("compile", 0)),
            Duration.ofSeconds(1), (command, sequence, identity, timeout) ->
            {
                var resources = new WindowsAttemptTest.Resources();
                resources.completeOnTerminate = true;
                creations.add(resources);
                identities.add(identity);
                return resources;
            });
        Controller.Measurements measurements = new Controller.Measurements()
        {
            @Override public Controller.Memory readHost() { return new Controller.Memory(1000, 0, false); }
            @Override public long readResidentBytes(Policy.Job job) { return 1; }
        };
        try (var queue = new JobQueue<WindowsProcess.Result>(new Policy.Budget(0, 2), measurements, factory))
        {
            queue.submit("compile", 10L);
            queue.cycle();
            var first = creations.getFirst();
            first.result = new WindowsProcess.Result(0xFFFFFFFFL, true);
            queue.cycle();
            requireThat(creations.size(), "freshRetryOwner").isEqualTo(2);
            requireThat(identities.get(0).equals(identities.get(1)), "freshAttemptIdentity").isFalse();
            requireThat(first.events.contains("process-close"), "reapedBeforeRetry").isTrue();
            creations.get(1).result = new WindowsProcess.Result(23, false);
            queue.cycle();
            requireThat(queue.completedAttempts().size(), "nativeCompletions").isEqualTo(2);
            requireThat(queue.completedAttempts().get(1).outcome().result(), "nativeNonzeroRootStatus").
                isEqualTo(new WindowsProcess.Result(23, false));
        }
        requireThat(factory.hasOpenAttempts(), "allQueueOwnersCleaned").isFalse();
    }

    @Test
    public void constructorsCopyInputsAndDoNotTouchPaths()
    {
        var arguments = new java.util.ArrayList<>(java.util.List.of("compiler.exe"));
        var environment = new HashMap<>(Map.of("CARGO_MAKEFLAGS", "borrowed"));
        var command = new WindowsAttempts.Command(new Controller.Action("a", 5), arguments,
            Path.of("does-not-exist"), environment, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        arguments.clear(); environment.clear();
        requireThat(command.arguments(), "copiedArguments").isEqualTo(java.util.List.of("compiler.exe"));
        requireThat(command.environment(), "borrowedInvocationFlagsPreserved").isEqualTo(Map.of("CARGO_MAKEFLAGS", "borrowed"));
    }
}
