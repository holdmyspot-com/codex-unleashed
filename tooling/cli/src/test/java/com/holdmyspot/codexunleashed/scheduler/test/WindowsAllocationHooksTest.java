package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.AllocationHook;
import com.holdmyspot.codexunleashed.scheduler.AllocationWaits;
import com.holdmyspot.codexunleashed.scheduler.FridaRuntime;
import com.holdmyspot.codexunleashed.scheduler.WindowsAllocationHooks;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Controlled production-coordinator transitions; these models do not establish native Windows behavior. */
public final class WindowsAllocationHooksTest
{
    /** Creates a resource-free TestNG instance; each method owns its fixtures. */
    public WindowsAllocationHooksTest() {}
    /**
     * Keeps a held child suspended until its source creation receipt is available.
     *
     * @throws Exception if fixture setup or cleanup fails
     */
    @Test
    public void childEventBeforeReceiptWaitsWithoutResume() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            hooks.pump(true);
            requireThat(backend.resumed.isEmpty(), "heldChildWithoutCreationReceipt").isTrue();
        }
        finally
        {
            hooks.close(() -> true);
        }
    }

    /**
     * Distinguishes a successful original continuation from a possibly uncertain failed call.
     * @throws Exception if fixture setup or cleanup fails
     */
    @Test
    public void completedResumeRequiresSuccessfulOriginalReturn() throws Exception
    {
        for (boolean fail : List.of(false, true))
        {
            Backend backend = new Backend();
            backend.failResume = fail;
            WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
            try
            {
                hooks.start(); backend.receipt(100, 200);
                backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
                if (fail) requireException(IOException.class, () -> hooks.pump(true));
                else hooks.pump(true);
                hooks.stop();
                if (fail) requireException(IOException.class, () -> hooks.pump(true));
                requireThat(backend.resumed, "exactlyOneOriginalResumeAttempt").isEqualTo(List.of(200L));
            }
            finally { hooks.close(() -> true); }
        }
    }

    /**
     * Root observer and allocation readiness precede the single root release.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void rootReadinessAndIdentityPrecedeSingleRelease() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        requireThat(backend.events.isEmpty(), "resourceFreeConstruction").isTrue();
        hooks.start();
        requireThat(backend.events, "rootLaunchOrder").isEqualTo(List.of("member:100", "manager", "manager-start",
            "observer-start:100", "observer-ready:100", "hook:1:100", "live:100", "start:1", "ready:1", "live:100", "release"));
        requireException(IllegalStateException.class, hooks::start);
        requireThat(backend.releases, "singleRelease").isEqualTo(1);
        hooks.close(() -> true);
    }

    /**
     * Transferred child authority remains usable after its creating parent exits.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void childOfTerminalParentUsesRetainedContextAndReadiness() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.receipt(100, 200);
        hooks.pump(false);
        backend.live.put(100L, false);
        backend.events.clear();
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        hooks.pump(true);
        requireThat(backend.resumed, "heldChildResumed").isEqualTo(List.of(200L));
        requireThat(backend.events.contains("live:100"), "terminalParentIsNotReObserved").isFalse();
        requireThat(backend.events.indexOf("ready:2") < backend.events.indexOf("resume:200"), "childReadyBeforeResume").isTrue();
        requireThat(backend.events.subList(backend.events.indexOf("ready:2") + 1, backend.events.size()),
            "postReadinessMembershipRechecks").isEqualTo(List.of("live:200", "resume:200"));
        hooks.close(() -> true);
    }

    /**
     * Supplies malformed origins, identities and image paths that cannot authorize continuation.
     * @return rejected held-child notifications
     */
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

    /**
     * Malformed or unowned held-child events never receive continuation authority.
     * @param event malformed or unsupported held-child notification
     * @throws Exception if setup or cleanup fails
     */
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

    /**
     * Foreign membership and duplicate child delivery permanently withdraw continuation authority.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void foreignMembershipAndDuplicateChildFailClosed() throws Exception
    {
        Backend foreign = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(foreign, () -> {});
        hooks.start();
        foreign.foreign = 200;
        foreign.receipt(100, 200);
        foreign.children.add(new FridaRuntime.Child(200, 100, 2, "foreign.exe"));
        requireException(IOException.class, () -> hooks.pump(true));
        requireThat(foreign.resumed.isEmpty(), "foreignMemberNeverResumed").isTrue();
        hooks.close(() -> true);

        Backend duplicate = new Backend();
        WindowsAllocationHooks second = new WindowsAllocationHooks(duplicate, () -> {});
        second.start();
        duplicate.receipt(100, 200);
        duplicate.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
        second.pump(true);
        duplicate.receipt(100, 200);
        duplicate.children.add(new FridaRuntime.Child(200, 100, 2, "replacement.exe"));
        requireException(IOException.class, () -> second.pump(true));
        requireThat(duplicate.resumed, "noDuplicateResume").isEqualTo(List.of(200L));
        second.close(() -> true);
    }

    /**
     * Failed hook readiness retains partially acquired owners until actual Job zero.
     * @throws Exception if setup or cleanup fails
     */
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

    /**
     * Grant reentry copies child events while deferring child installation to a later owner round.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void nestedPumpCopiesChildWithoutInstallingAndGrantUsesExactGeneration() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks[] holder = new WindowsAllocationHooks[1];
        holder[0] = new WindowsAllocationHooks(backend, () -> holder[0].pump(true));
        holder[0].start();
        backend.receipt(100, 200);
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

    /**
     * Uncertain hook destruction retains dependent manager and member owners without retry.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void hookCleanupFailureBlocksDependentDestructionAndIsSticky() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.receipt(100, 200);
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

    /**
     * Root and child allocation frames route permissions to their exact installation owners.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void descendantCodecBatchesAndAcknowledgmentsUseExactInstallation() throws Exception
    {
        try (ToolingScope scope = new DefaultToolingScope())
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
                var hook = new AllocationHook(scope, AllocationHooksTest.IDENTITY, generation, member.pid(), connection,
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
        backend.receipt(100, 200);
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
    }

    /**
     * Creates an accepted allocation frame with exact modeled owner correlations.
     * @param event allocation protocol event name
     * @param generation installation ordinal
     * @param pid target process identifier
     * @param sequence allocation wait ordinal
     * @return serialized Frida allocation frame
     */
    private static String frame(String event, long generation, long pid, long sequence)
    {
        return AllocationHooksTest.frame(event, sequence).replace("\"installation\":\"1\"",
            "\"installation\":\"" + generation + "\"").replace("\"pid\":\"3216\"", "\"pid\":\"" + pid + "\"");
    }

    /**
     * A bounded owner round resumes at most one fully authorized held child.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void advancesOnlyOneChildPerRound() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        backend.receipt(100, 200);
        backend.children.add(new FridaRuntime.Child(200, 100, 2, "first.exe"));
        backend.receipt(100, 300);
        backend.children.add(new FridaRuntime.Child(300, 100, 2, "second.exe"));
        hooks.pump(false);
        requireThat(backend.resumed.isEmpty(), "nestedFrameOnlyPump").isTrue();
        hooks.pump(true);
        requireThat(backend.resumed, "boundedFirstRound").isEqualTo(List.of(200L));
        hooks.pump(true);
        requireThat(backend.resumed, "boundedSecondRound").isEqualTo(List.of(200L, 300L));
        hooks.close(() -> true);
    }

    /**
     * A source receipt transfers ownership and closes its parent-local copy before child continuation.
     * @throws Exception if coordinator setup or cleanup fails
     */
    @Test
    public void receiptBeforeChildEventTransfersAndReleasesBeforeResume() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.receipt(100, 200);
            hooks.pump(false);
            requireThat(backend.resumed.isEmpty(), "receiptAloneCannotResume").isTrue();
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            hooks.pump(true);
            requireThat(backend.resumed, "matchedChild").isEqualTo(List.of(200L));
            requireThat(backend.events.indexOf("transfer:200") < backend.events.indexOf("source-release:200"), "transferBeforeRelease").isTrue();
            requireThat(backend.events.indexOf("source-release:200") < backend.events.indexOf("resume:200"), "releaseBeforeResume").isTrue();
            requireThat(backend.events.indexOf("observer-ready:200") < backend.events.indexOf("hook:2:200"), "observerBeforeGating").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * A held child that loads a Cygwin-family runtime resumes without an observer or allocation hook.
     * @throws Exception if coordinator setup or cleanup fails
     */
    @Test
    public void cygwinRuntimeChildResumesUninstrumented() throws Exception
    {
        Backend backend = new Backend();
        backend.cygwinImages.add("C:\\Git\\usr\\bin\\sh.exe");
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.receipt(100, 200);
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "C:\\Git\\usr\\bin\\sh.exe"));
            hooks.pump(true);
            requireThat(backend.resumed, "resumedChild").isEqualTo(List.of(200L));
            requireThat(backend.events, "events").doesNotContain("observer-start:200").doesNotContain("hook:2:200");
            requireThat(backend.events.indexOf("source-release:200") < backend.events.indexOf("resume:200"), "releaseBeforeResume").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * A held 32-bit child resumes without an observer or allocation hook, because both support only 64-bit layouts.
     * @throws Exception if coordinator setup or cleanup fails
     */
    @Test
    public void child32BitResumesUninstrumented() throws Exception
    {
        Backend backend = new Backend();
        backend.images32Bit.add("C:\\registry\\protoc-bin-vendored-win32\\bin\\protoc.exe");
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.receipt(100, 200);
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "C:\\registry\\protoc-bin-vendored-win32\\bin\\protoc.exe"));
            hooks.pump(true);
            requireThat(backend.resumed, "resumedChild").isEqualTo(List.of(200L));
            requireThat(backend.events, "events").doesNotContain("observer-start:200").doesNotContain("hook:2:200");
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * Reordered source receipts join their exact child events, with one continuation per bounded round.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void twoConcurrentChildrenJoinByParentAndBirthDespiteReorderedFrames() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "first.exe"));
            backend.children.add(new FridaRuntime.Child(300, 100, 2, "second.exe"));
            backend.receipt(100, 300);
            backend.receipt(100, 200);
            hooks.pump(true);
            requireThat(backend.resumed, "firstMatchedChild").isEqualTo(List.of(200L));
            hooks.pump(true);
            requireThat(backend.resumed, "bothExactChildren").isEqualTo(List.of(200L, 300L));
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * An absent receipt fails at the injected monotonic deadline without releasing the held child.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void missingReceiptTimesOutByInjectedTicker() throws Exception
    {
        Backend backend = new Backend();
        long[] now = {0};
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {}, () -> now[0]);
        try
        {
            hooks.start();
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            hooks.pump(true);
            now[0] = Duration.ofSeconds(1).toNanos() - 1;
            hooks.pump(true);
            now[0]++;
            requireException(IOException.class, () -> hooks.pump(true));
            requireThat(backend.resumed.isEmpty(), "timedOutChildRemainsHeld").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * An uncertain source release remains one-shot and withdraws every child continuation.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void uncertainReleasePostNeverRetriesOrResumes() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.releaseFailure = true;
            backend.receipt(100, 200);
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            requireException(IOException.class, () -> hooks.pump(true));
            requireException(IOException.class, () -> hooks.pump(true));
            requireThat(backend.events.stream().filter("source-release:200"::equals).count(), "singleReleaseAttempt").isEqualTo(1L);
            requireThat(backend.resumed.isEmpty(), "uncertainReleaseRemainsHeld").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * Duplicate creation authority withdraws continuation before either held event can run.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void duplicateReceiptNeverResumes() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.receipt(100, 200);
            backend.receipt(100, 200);
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            requireException(IOException.class, () -> hooks.pump(true));
            requireThat(backend.resumed.isEmpty(), "duplicateAuthorityRemainsHeld").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * Wrong retained birth fails source authorization before a child can resume.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void mismatchedCreatedBirthNeverResumes() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        try
        {
            hooks.start();
            backend.receipt(100, 200);
            backend.receipts.get(100L).clear();
            backend.receipts.get(100L).add(new WindowsAllocationHooks.Receipt(new UUID(0, 100), 1, 100, 1100, 200, 1201, 2200));
            backend.children.add(new FridaRuntime.Child(200, 100, 2, "child.exe"));
            requireException(IOException.class, () -> hooks.pump(true));
            requireThat(backend.resumed.isEmpty(), "wrongBirthRemainsHeld").isTrue();
        }
        finally { hooks.close(() -> true); }
    }

    /**
     * Actual Job zero permits observer teardown only after hooks and before manager and member destruction.
     * @throws Exception if setup or cleanup fails
     */
    @Test
    public void wholeJobZeroClosesHooksThenObserversThenManagerAndMembers() throws Exception
    {
        Backend backend = new Backend();
        WindowsAllocationHooks hooks = new WindowsAllocationHooks(backend, () -> {});
        hooks.start();
        try
        {
            requireException(IOException.class, () -> hooks.close(() -> false));
            requireThat(backend.events.contains("observer-close:100"), "observerRetainedWhileExecutionPossible").isFalse();
        }
        finally { hooks.close(() -> true); }
        requireThat(backend.events.indexOf("close:1") < backend.events.indexOf("observer-close:100"), "hooksBeforeObservers").isTrue();
        requireThat(backend.events.indexOf("observer-close:100") < backend.events.indexOf("manager-close"), "observerBeforeManager").isTrue();
        requireThat(backend.events.indexOf("manager-close") < backend.events.indexOf("member-close:100"), "managerBeforeMembers").isTrue();
    }

    /** A controlled kernel/session model that records the actual production coordinator calls. */
    static class Backend implements WindowsAllocationHooks.Backend, WindowsAllocationHooks.Manager
    {
        /** Creates one independent modeled native-operation fixture. */
        Backend() {}
        /** Production coordinator requests in owner-thread order. */
        final List<String> events = new ArrayList<>();
        /** Copied Frida held-child events awaiting controller polling. */
        final ArrayDeque<FridaRuntime.Child> children = new ArrayDeque<>();
        /** Retained source receipt queues modeled independently of Frida child events. */
        final Map<Long, ArrayDeque<WindowsAllocationHooks.Receipt>> receipts = new LinkedHashMap<>();
        /** Next per-parent receipt sequence. */
        final Map<Long, Long> ordinals = new LinkedHashMap<>();
        /** Models an uncertain source release post. */
        boolean releaseFailure;
        /** Child PIDs for which production requested continuation. */
        final List<Long> resumed = new ArrayList<>();
        /** Models uncertain native continuation without authorizing a second attempt. */
        boolean failResume;
        /** Modeled live status for each retained member identity. */
        final Map<Long, Boolean> live = new LinkedHashMap<>();
        /** PID whose exact Job membership is rejected, or minus one. */
        long foreign = -1;
        /** Installation whose readiness fails, or minus one. */
        long readyFailure = -1;
        /** Installation whose destruction is uncertain, or minus one. */
        long closeFailure = -1;
        /** Image paths modeled as loading a Cygwin-family runtime. */
        final List<String> cygwinImages = new ArrayList<>();
        /** Image paths modeled as 32-bit executables. */
        final List<String> images32Bit = new ArrayList<>();
        /** Number of root continuation requests. */
        int releases;
        /** Peer callback invoked while a modeled permission acknowledgement is pending. */
        AllocationHook.Pump onGrant = () -> {};
        /**
         * Supplies modeled retained-handle authority independently of held event delivery.
         * @param parent creating process identifier
         * @param child created process identifier
         */
        void receipt(long parent, long child)
        {
            long sequence = ordinals.merge(parent, 1L, Long::sum);
            receipts.computeIfAbsent(parent, _ -> new ArrayDeque<>()).add(new WindowsAllocationHooks.Receipt(
                new UUID(0, parent), sequence, parent, parent + 1000, child, child + 1000, child + 2000));
        }
        @Override public Duration joinTimeout() { return Duration.ofSeconds(1); }
        @Override public WindowsAllocationHooks.Observer observer(WindowsAllocationHooks.Member parent,
            WindowsAllocationHooks.Manager manager)
        {
            return new WindowsAllocationHooks.Observer()
            {
                @Override public void start() { events.add("observer-start:" + parent.pid()); }
                @Override public void awaitReady(AllocationHook.Pump peers) { events.add("observer-ready:" + parent.pid()); }
                @Override public Optional<WindowsAllocationHooks.Receipt> pollReceipt()
                {
                    return Optional.ofNullable(receipts.computeIfAbsent(parent.pid(), _ -> new ArrayDeque<>()).pollFirst());
                }
                @Override public void release(WindowsAllocationHooks.Receipt receipt, AllocationHook.Pump peers) throws IOException
                {
                    events.add("source-release:" + receipt.childPid());
                    if (releaseFailure) throw new IOException("Uncertain source release post");
                }
                @Override public void close(AllocationHook.ExecutionBarrier barrier) { events.add("observer-close:" + parent.pid()); }
            };
        }
        @Override public WindowsAllocationHooks.Member createdMember(WindowsAllocationHooks.Member parent,
            WindowsAllocationHooks.Receipt receipt) throws IOException
        {
            events.add("transfer:" + receipt.childPid());
            parent.requireLive();
            if (receipt.parentBirth() != parent.pid() + 1000 || receipt.childBirth() != receipt.childPid() + 1000)
                throw new IOException("Wrong native creation birth");
            return member(receipt.childPid());
        }
        @Override public boolean usesCygwinRuntime(String image) { return cygwinImages.contains(image); }
        @Override public boolean is32Bit(String image) { return images32Bit.contains(image); }
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
        @Override public void resume(long pid) throws IOException
        { events.add("resume:" + pid); resumed.add(pid); if (failResume) throw new IOException("injected native continuation failure"); }
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
