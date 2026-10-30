package com.holdmyspot.codexunleashed.scheduler;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Controlled production-coordinator transitions; these models do not establish native Windows behavior. */
public final class WindowsAllocationHooksTest
{
    @Test
    public void rootReadinessAndIdentityPrecedeSingleRelease() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        requireThat(backend.events.isEmpty(), "resourceFreeConstruction").isTrue();
        hooks.start();
        requireThat(backend.events, "rootLaunchOrder").isEqualTo(List.of("member:100", "manager", "manager-start",
            "hook:1:100", "live:100", "start:1", "ready:1", "live:100", "release"));
        requireException(IllegalStateException.class, hooks::start);
        requireThat(backend.releases, "singleRelease").isEqualTo(1);
        hooks.close(() -> true);
    }

    @Test
    public void childOfTerminalParentUsesRetainedContextAndReadiness() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.live.put(100L, false);
        backend.events.clear();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        hooks.pump(true);
        requireThat(backend.resumed, "heldChildResumed").isEqualTo(List.of(200L));
        requireThat(backend.events.contains("live:100"), "terminalParentIsNotReObserved").isFalse();
        requireThat(backend.events.indexOf("ready:2") < backend.events.indexOf("resume:200"), "childReadyBeforeResume").isTrue();
        requireThat(backend.events.subList(backend.events.indexOf("ready:2") + 1, backend.events.size()),
            "postReadinessMembershipRechecks").isEqualTo(List.of("live:200", "live:200", "resume:200"));
        hooks.close(() -> true);
    }

    @DataProvider
    public Object[][] unsupportedChildren()
    {
        return new Object[][] {
            {new FridaRuntime.Child(200, 100, 0, "child.exe")},
            {new FridaRuntime.Child(200, 100, 1, "child.exe")},
            {new FridaRuntime.Child(200, 100, 9, "child.exe")},
            {new FridaRuntime.Child(100, 100, 2, "root.exe")},
            {new FridaRuntime.Child(200, 999, 2, "child.exe")},
            {new FridaRuntime.Child(200, 100, 2, "")},
            {new FridaRuntime.Child(200, 100, 2, null)}};
    }

    @Test(dataProvider = "unsupportedChildren")
    public void unsupportedChildNeverResumes(FridaRuntime.Child event) throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.children.add(event);
        requireException(IOException.class, () -> hooks.pump(true));
        requireThat(backend.resumed.isEmpty(), "noUnprovenResume").isTrue();
        requireException(IOException.class, () -> hooks.grant(new AllocationWaits.Wait(1, 1)));
        hooks.close(() -> true);
    }

    @Test
    public void foreignMembershipAndDuplicateChildFailClosed() throws Exception
    {
        Backend foreign = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(foreign, () -> {});
        hooks.start();
        foreign.foreign = 200;
        foreign.children.add(new FridaRuntime.Child(200, 100, 2, "foreign.exe"));
        requireException(IOException.class, () -> hooks.pump(true));
        requireThat(foreign.resumed.isEmpty(), "foreignMemberNeverResumed").isTrue();
        hooks.close(() -> true);

        Backend duplicate = new Backend();
        WindowsAllocationHooks second = new WindowsAllocationHooks(duplicate, () -> {});
        second.start();
        duplicate.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        second.pump(true);
        duplicate.children.add(new FridaRuntime.Child(200, 100, 2, "replacement.exe"));
        requireException(IOException.class, () -> second.pump(true));
        requireThat(duplicate.resumed, "noDuplicateResume").isEqualTo(List.of(200L));
        second.close(() -> true);
    }

    @Test
    public void readinessFailureRetainsPartialHookAndNeverReleases() throws Exception
    {
        Backend backend = new Backend();
        backend.readyFailure = 1;
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        requireException(IOException.class, hooks::start);
        requireThat(backend.releases, "unreadyRootRemainsSuspended").isEqualTo(0);
        requireException(IOException.class, () -> hooks.close(() -> false));
        requireThat(backend.events.contains("close:1"), "noLiveScriptUnload").isFalse();
        hooks.close(() -> true);
        requireThat(backend.events.contains("close:1"), "partialHookRetainedForTeardown").isTrue();
    }

    @Test
    public void nestedPumpCopiesChildWithoutInstallingAndGrantUsesExactGeneration() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks[] holder = new WindowsAllocationHooks[1];
        holder[0] = new WindowsAllocationHooks(backend, () -> holder[0].pump(true));
        holder[0].start();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        backend.onGrant = () -> holder[0].pump(true);
        holder[0].grant(new AllocationWaits.Wait(1, 7));
        requireThat(backend.resumed.isEmpty(), "grantReentryCannotInstallChild").isTrue();
        requireThat(backend.events.contains("grant:1:7"), "exactGenerationRoute").isTrue();
        holder[0].pump(true);
        requireThat(backend.resumed, "childAdvancedInLaterBoundedRound").isEqualTo(List.of(200L));
        requireException(IOException.class, () -> holder[0].grant(new AllocationWaits.Wait(3, 7)));
        holder[0].close(() -> true);
    }

    @Test
    public void hookCleanupFailureBlocksDependentDestructionAndIsSticky() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        hooks.pump(true);
        backend.closeFailure = 1;
        requireException(IOException.class, () -> hooks.close(() -> true));
        requireThat(backend.events.contains("close:2"), "independentHookCleanupAttempted").isTrue();
        requireThat(backend.events.contains("manager-close"), "managerRetainedForFailedHook").isFalse();
        requireThat(backend.events.contains("member-close:100"), "identityRetainedForFailedHook").isFalse();
        int effects = backend.events.size();
        requireException(IOException.class, () -> hooks.close(() -> true));
        requireThat(backend.events.size(), "noRetryOfUncertainDestruction").isEqualTo(effects);
    }

    @Test
    public void descendantCodecBatchesAndAcknowledgmentsUseExactInstallation() throws Exception
    {
        AllocationWaits waits = new AllocationWaits();
        var connections = new LinkedHashMap<Long, AllocationHooksTest.Connection>();
        Backend backend = new Backend()
        {
            @Override public WindowsAllocationHooks.Hook hook(long generation, WindowsAllocationHooks.Member member,
                WindowsAllocationHooks.Manager manager)
            {
                var connection = new AllocationHooksTest.Connection();
                connections.put(generation, connection);
                connection.frames.add(frame("ready", generation, member.pid(), 0));
                var hook = new AllocationHook(AllocationHooksTest.IDENTITY, generation, member.pid(), connection,
                    waits, "", java.time.Duration.ofSeconds(1), System::nanoTime);
                return new WindowsAllocationHooks.Hook()
                {
                    @Override public void start() throws IOException { hook.start(); }
                    @Override public void awaitReady(AllocationHook.Pump peers) throws IOException { hook.awaitReady(peers); }
                    @Override public void pump() throws IOException { hook.pump(); }
                    @Override public void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers) throws IOException
                    { hook.grant(wait, peers); }
                    @Override public void stop() { hook.stop(); }
                    @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException { hook.close(barrier); }
                };
            }
        };
        WindowsAllocationHooks[] holder = new WindowsAllocationHooks[1];
        holder[0] = new WindowsAllocationHooks(backend, () -> holder[0].pump(false));
        holder[0].start();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        holder[0].pump(true);
        var root = connections.get(1L);
        var child = connections.get(2L);
        root.frames.add(frame("blocked", 1, 100, 1));
        child.frames.add(frame("blocked", 2, 200, 1));
        holder[0].pump(true);
        long batch = waits.poll().orElseThrow();
        root.onPost = _ ->
        {
            root.frames.add(frame("granted", 1, 100, 1));
            child.frames.add(frame("blocked", 2, 200, 2));
        };
        child.onPost = _ -> child.frames.add(frame("granted", 2, 200, 1));
        waits.resume(batch, holder[0]::grant);
        requireThat(root.posts.size(), "rootPermissionCount").isEqualTo(1);
        requireThat(child.posts.size(), "childPermissionCount").isEqualTo(1);
        requireThat(child.posts.getFirst().path("installation").asString(), "childPermissionGeneration").isEqualTo("2");
        requireThat(waits.poll().orElseThrow(), "followingBatch").isEqualTo(1L);
        child.postFailure = new IOException("Uncertain native child permission post");
        requireException(IOException.class, () -> waits.resume(1, holder[0]::grant));
        requireException(IOException.class, () -> holder[0].grant(new AllocationWaits.Wait(2, 2)));
        requireThat(child.posts.size(), "uncertainPermissionNeverResent").isEqualTo(2);
        holder[0].close(() -> true);
    }

    /** Reuses the accepted codec fixture while changing only the exact owner correlations. */
    private static String frame(String event, long generation, long pid, long sequence)
    {
        return AllocationHooksTest.frame(event, sequence).replace("\"installation\":\"1\"",
            "\"installation\":\"" + generation + "\"").replace("\"pid\":\"3216\"", "\"pid\":\"" + pid + "\"");
    }

    @Test
    public void advancesOnlyOneChildPerRound() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "first.exe"));
        backend.children.add(new FridaRuntime.Child(300, 100, 2, "second.exe"));
        hooks.pump(false);
        requireThat(backend.resumed.isEmpty(), "nestedFrameOnlyPump").isTrue();
        hooks.pump(true);
        requireThat(backend.resumed, "boundedFirstRound").isEqualTo(List.of(200L));
        hooks.pump(true);
        requireThat(backend.resumed, "boundedSecondRound").isEqualTo(List.of(200L, 300L));
        hooks.close(() -> true);
    }

    /** A controlled kernel/session model that records the actual production coordinator calls. */
    static class Backend implements WindowsAllocationHooks.Backend, WindowsAllocationHooks.Manager
    {
        final List<String> events = new ArrayList<>();
        final ArrayDeque<FridaRuntime.Child> children = new ArrayDeque<>();
        final List<Long> resumed = new ArrayList<>();
        final Map<Long, Boolean> live = new LinkedHashMap<>();
        long foreign = -1;
        long readyFailure = -1;
        long closeFailure = -1;
        int releases;
        AllocationHook.Pump onGrant = () -> {};
        @Override public long rootPid() { return 100; }
        @Override public WindowsAllocationHooks.Member member(long pid) throws IOException
        {
            events.add("member:" + pid);
            if (pid == foreign) throw new IOException("Not in owned Job");
            live.put(pid, true);
            return new WindowsAllocationHooks.Member()
            {
                @Override public long pid() { return pid; }
                @Override public void requireLive() throws IOException
                {
                    events.add("live:" + pid);
                    if (live.get(pid) == false) throw new IOException("Member is terminal");
                }
                @Override public void close() { events.add("member-close:" + pid); }
            };
        }
        @Override public WindowsAllocationHooks.Manager manager() { events.add("manager"); return this; }
        @Override public void start() { events.add("manager-start"); }
        @Override public Optional<FridaRuntime.Child> pollChild() { return Optional.ofNullable(children.pollFirst()); }
        @Override public void resume(long pid) { events.add("resume:" + pid); resumed.add(pid); }
        @Override public void close() { events.add("manager-close"); }
        @Override public void releaseRoot() { events.add("release"); ++releases; }
        @Override public WindowsAllocationHooks.Hook hook(long generation, WindowsAllocationHooks.Member member,
            WindowsAllocationHooks.Manager manager)
        {
            events.add("hook:" + generation + ":" + member.pid());
            return new WindowsAllocationHooks.Hook()
            {
                @Override public void start() { events.add("start:" + generation); }
                @Override public void awaitReady(AllocationHook.Pump peers) throws IOException
                {
                    events.add("ready:" + generation);
                    if (readyFailure == generation) throw new IOException("Ready failed");
                }
                @Override public void pump() { events.add("pump:" + generation); }
                @Override public void grant(AllocationWaits.Wait wait, AllocationHook.Pump peers) throws IOException
                { events.add("grant:" + generation + ":" + wait.sequence()); onGrant.pump(); }
                @Override public void stop() { events.add("stop:" + generation); }
                @Override public void close(AllocationHook.ExecutionBarrier barrier) throws IOException
                {
                    if (barrier.reached() == false) throw new IOException("Execution remains possible");
                    events.add("close:" + generation);
                    if (closeFailure == generation) throw new IOException("Script close uncertain");
                }
            };
        }
    }
}
