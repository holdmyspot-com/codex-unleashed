package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.JobQueue;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelControl;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelIdentity;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelInvocation;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelNamespaceHooks;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelProducer;
import com.holdmyspot.codexunleashed.scheduler.LinuxBazelSession;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks real invocation-to-session discovery decisions with isolated native boundary substitutes.
 * The substitutes model the Resources and Action contracts; no native execution is claimed.
 */
public final class LinuxBazelInvocationTest
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
     * Creates a test instance with no shared mutable fixture.
     */
    public LinuxBazelInvocationTest() {}

    /**
     * Registers an authenticated discovery before acknowledging its ownership transfer.
     * @throws IOException if valid invocation operations fail
     */
    @Test public void discoveredActionReachesOriginalSession() throws IOException
    {
        Resources resources = new Resources();
        LinuxBazelInvocation invocation = new LinuxBazelInvocation(resources);
        try
        {
            invocation.start();
            invocation.pump();
            requireThat(invocation.session().pollSubmission().orElseThrow().name(), "registeredAction").isEqualTo("generator");
            requireThat(resources.events, "discoveryTransferOrder").isEqualTo(List.of("acquire", "release", "discover", "registered"));
        }
        finally { invocation.session().close(); }
    }

    /**
     * Keeps native dependencies reachable while a queue-issued attempt remains open.
     * @throws IOException if startup or discovery fails
     */
    @Test public void openAttemptPreventsOuterDependencyClose() throws IOException
    {
        Resources resources = new Resources();
        LinuxBazelInvocation invocation = new LinuxBazelInvocation(resources);
        invocation.start();
        invocation.pump();
        JobQueue.Attempt<LinuxBazelControl.Result> attempt = invocation.session().attempts().create("generator", 0);
        try
        {
            requireException(IOException.class, invocation::close);
            requireThat(resources.events.contains("close"), "dependenciesRetained").isFalse();
        }
        finally
        {
            attempt.close();
            invocation.session().close();
        }
    }

    /**
     * Withdraws discovery permanently after a transfer acknowledgement has uncertain effects.
     * @throws IOException if startup fails
     */
    @Test public void uncertainTransferCannotDiscoverAgain() throws IOException
    {
        Resources resources = new Resources();
        resources.failTransfer = true;
        LinuxBazelInvocation invocation = new LinuxBazelInvocation(resources);
        invocation.start();
        try
        {
            requireException(IOException.class, invocation::pump);
            requireException(IOException.class, invocation::pump);
            requireThat(resources.events.stream().filter("discover"::equals).count(), "soleDiscovery").isEqualTo(1L);
            requireThat(invocation.session().pollSubmission().orElseThrow().name(), "sessionRetainsAction").isEqualTo("generator");
        }
        finally { invocation.session().close(); }
    }

    /**
     * Resolves an original bare executable using its complete declared PATH rather than the controller environment.
     * @throws IOException if the current Java executable cannot be selected
     */
    @Test public void originalPathSelectsExecutable() throws IOException
    {
        Path java = javaExecutable();
        Path directory = java.getParent().getParent();
        Path resolved = LinuxBazelInvocation.executable(java.getFileName().toString(), directory,
            Map.of("PATH", "absent:bin"));
        requireThat(resolved, "originalPathResolution").isEqualTo(java);
    }

    /**
     * Resolves an explicit relative executable at the original cwd even when its PATH names no executable.
     * @throws IOException if the current Java executable cannot be selected
     */
    @Test public void relativeExecutableUsesOriginalDirectory() throws IOException
    {
        Path java = javaExecutable();
        Path directory = java.getParent().getParent();
        Path resolved = LinuxBazelInvocation.executable(directory.relativize(java).toString().replace('\\', '/'), directory,
            Map.of("PATH", "absent"));
        requireThat(resolved, "originalWorkingDirectory").isEqualTo(java);
    }

    /**
     * Rejects an executable absent from the original PATH without selecting the controller's installation.
     */
    @Test public void absentOriginalExecutableIsRejected()
    {
        Path directory = javaExecutable().getParent();
        requireException(IOException.class, () -> LinuxBazelInvocation.executable("missing-original-action-executable",
            directory, Map.of("PATH", "absent")));
    }

    /**
     * Derives kernel shebang argv while preserving the original launch vector and complete environment.
     * @throws IOException if private script setup or identity derivation fails
     */
    @Test public void shebangHeldImagePreservesKernelArgumentSemantics() throws IOException
    {
        Path directory = Files.createTempDirectory("bazel-script-identity-");
        Path script = directory.resolve("original-script");
        Path interpreter = javaExecutable().getRoot().resolve("bazel-test-interpreter");
        try
        {
            Files.writeString(script, "#!" + interpreter + " one two\nignored script bytes\n");
            List<String> originalArguments = List.of("original-relative-argv-zero", "original argument");
            Map<String, String> environment = Map.of("ORIGINAL", "complete value");
            var launch = new LinuxBazelIdentity.Execution(new LinuxBazelIdentity.Image(script, "ab".repeat(32)),
                originalArguments, environment, directory, 1, 1);
            var held = LinuxBazelInvocation.interpretedExecution(launch, directory, directory,
                List.of(new LinuxBazelProducer.RuntimeBinding(javaExecutable(), interpreter)));
            requireThat(held.image().executable(), "actualInterpreterImage").isEqualTo(interpreter);
            requireThat(held.arguments(), "kernelShebangArgumentVector").isEqualTo(List.of(interpreter.toString(),
                "one two", script.toString(), "original argument"));
            requireThat(launch.arguments(), "originalLaunchArguments").isEqualTo(originalArguments);
            requireThat(held.environment(), "originalEnvironment").isEqualTo(environment);
            requireThat(held.directory(), "originalCwd").isEqualTo(directory);
        }
        finally { Files.deleteIfExists(script); Files.deleteIfExists(directory); }
    }

    /**
     * Rejects an interpreter absent from the immutable input and exact runtime manifest.
     * @throws IOException if private script setup fails
     */
    @Test public void shebangCannotSelectUnboundRuntimeImage() throws IOException
    {
        Path directory = Files.createTempDirectory("bazel-script-runtime-");
        Path script = directory.resolve("original-script");
        try
        {
            Files.writeString(script, "#!" + javaExecutable() + "\n");
            var launch = new LinuxBazelIdentity.Execution(new LinuxBazelIdentity.Image(script, "ab".repeat(32)),
                List.of(script.toString()), Map.of(), directory, 1, 1);
            requireException(IOException.class, () -> LinuxBazelInvocation.interpretedExecution(launch,
                directory, directory, List.of()));
        }
        finally { Files.deleteIfExists(script); Files.deleteIfExists(directory); }
    }

    /**
     * Supplies a real executable already required by this test process, without acquiring a fixture process.
     * @return current runtime's absolute executable path
     */
    private static Path javaExecutable()
    {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    /**
     * Models invocation-owned native acquisition and authenticated action discovery only.
     */
    private static final class Resources implements LinuxBazelInvocation.Resources
    {
        /** Observable calls used to check production ownership ordering. */
        private final List<String> events = new ArrayList<>();
        /** One modeled authenticated action awaiting transfer. */
        private final Action action = new Action();
        /** Indicates whether the original action has transferred. */
        private boolean transferred;
        /** Indicates whether transfer acknowledgement reports an uncertain effect. */
        private boolean failTransfer;

        /**
         * Creates one isolated native boundary substitute.
         */
        private Resources() {}

        @Override public void acquire() { events.add("acquire"); }
        @Override public Controller.Measurements measurements()
        {
            return new Controller.Measurements()
            {
                @Override public Controller.Memory readHost() { throw new UnsupportedOperationException("Not sampled"); }
                @Override public long readResidentBytes(Policy.Job job) { throw new UnsupportedOperationException("Not sampled"); }
            };
        }
        @Override public void release() { events.add("release"); }
        @Override public LinuxBazelSession.Action discover()
        {
            events.add("discover");
            return transferred ? null : action;
        }
        @Override public void registered(LinuxBazelSession.Action action) throws IOException
        {
            events.add("registered");
            if (failTransfer) throw new IOException("Modeled uncertain transfer acknowledgement");
            transferred = true;
        }
        @Override public boolean inputClosed() { return false; }
        @Override public OptionalInt pollToolResult() { return OptionalInt.empty(); }
        @Override public void stopAccepting() { events.add("stop"); }
        @Override public void close() { events.add("close"); }
    }

    /**
     * Models a declared action's queue-owned lifetime without claiming native work ran.
     */
    private static final class Action implements LinuxBazelSession.Action
    {
        /** Indicates whether a queue-issued test-local attempt remains open. */
        private boolean open;

        /**
         * Creates a resource-free isolated action substitute.
         */
        private Action() {}

        @Override public String name() { return "generator"; }
        @Override public Long peakBytes() { return null; }
        @Override public JobQueue.Attempt<LinuxBazelControl.Result> create(long sequence)
        {
            open = true;
            return new JobQueue.Attempt<>()
            {
                @Override public void start() {}
                @Override public void resume(long sequence) {}
                @Override public void terminate() {}
                @Override public Optional<JobQueue.Outcome<LinuxBazelControl.Result>> poll() { return Optional.empty(); }
                @Override public void close() { open = false; }
            };
        }
        @Override public void pump() {}
        @Override public OptionalLong pollBlocked() { return OptionalLong.empty(); }
        @Override public boolean hasOpenAttempt() { return open; }
        @Override public void acceptOutcome(JobQueue.Completion<LinuxBazelControl.Result> completion) {}
        @Override public void close() {}
    }
    /**
     * Private temporary mapping cannot conceal original control, input, cwd or runtime path semantics.
     * @throws Exception if a supported unrelated original path is rejected
     */
    @org.testng.annotations.Test
    public void privateTemporaryAliasRejectsOnlyIntersectingOriginalPaths() throws Exception
    {
        LinuxBazelInvocation.requireOutsidePrivateTemporary(java.nio.file.Path.of("/home/runner/owned/input"),
            java.nio.file.Path.of("/usr/bin/cc"), java.nio.file.Path.of("/tmp-independent"));
        for (String value : java.util.List.of("/", "/tmp", "/tmp/input", "/tmp/control.sock", "relative", "/usr/../tmp/x"))
        {
            boolean rejected = false;
            try { LinuxBazelInvocation.requireOutsidePrivateTemporary(java.nio.file.Path.of(value)); }
            catch (java.io.IOException expected) { rejected = true; }
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(rejected,
                "privateTemporaryOverlapRejected").isTrue();
        }
    }

    /**
     * Independently mapped private temporary executable membership remains component bounded.
     */
    @org.testng.annotations.Test
    public void privateTemporaryImagesStayWithinExplicitAuthenticatedRoots()
    {
        var policy = new LinuxBazelNamespaceHooks.ActionImages(java.nio.file.Path.of("/home/runner/owned/input"),
            java.util.List.of(java.nio.file.Path.of("/home/runner/owned/temp"), java.nio.file.Path.of("/tmp")));
        io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(
            policy.permits(java.nio.file.Path.of("/tmp/generated-tool")), "privateTmpGeneratedImagePermitted").isTrue();
        io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(
            policy.permits(java.nio.file.Path.of("/tmp-other/tool")), "privateTmpSiblingExcluded").isFalse();
        io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(
            policy.permits(java.nio.file.Path.of("/home/runner/owned/temp/generated-tool")),
            "originalPrivateTemporaryImagePermitted").isTrue();
    }

    /**
     * Retry diagnostics remain private and final publication uses only the frozen authentic attempt.
     * The fixture controls the documented zero barrier and does not claim native execution.
     * @throws Exception if independent fixture acquisition or cleanup fails
     */
    @org.testng.annotations.Test
    public void retryDiagnosticsExcludeDiscardedBytesAndFreezeAuthenticAttempt() throws Exception
    {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bazel-diagnostics-retry-");
        try
        {
            var capture = java.util.List.of(root.resolve("capture-out"), root.resolve("capture-err"));
            var original = java.util.List.of(root.resolve("worker-out"), root.resolve("worker-err"));
            for (var path : capture) java.nio.file.Files.writeString(path, "failed-partial");
            // Whole failed-attempt zero precedes truncation and the fresh original retry.
            java.nio.file.Files.writeString(capture.get(0), "authentic-output");
            java.nio.file.Files.writeString(capture.get(1), "authentic-error");
            var diagnostics = LinuxBazelInvocation.Diagnostics.freeze(capture, original, root.resolve("frozen"));
            for (var path : original)
                io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(java.nio.file.Files.exists(path),
                    "workerCannotUploadPrivateRetryBytes").isFalse();
            java.nio.file.Files.writeString(capture.get(0), "later-unrelated-capture");
            diagnostics.publish(() -> true, () -> {});
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(
                java.nio.file.Files.readString(original.get(0)), "authenticFrozenStdout").isEqualTo("authentic-output");
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(
                java.nio.file.Files.readString(original.get(1)), "authenticFrozenStderr").isEqualTo("authentic-error");
        }
        finally { removeDiagnosticFixture(root); }
    }

    /**
     * Failed output promotion or a withdrawn original RPC rolls diagnostic destinations back before any response.
     * @throws Exception if independent fixture acquisition or cleanup fails
     */
    @org.testng.annotations.Test
    public void diagnosticPublicationRollsBackOnOutputFailureAndCancellation() throws Exception
    {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("bazel-diagnostics-rollback-");
        try
        {
            var capture = java.util.List.of(root.resolve("capture-out"), root.resolve("capture-err"));
            var original = java.util.List.of(root.resolve("worker-out"), root.resolve("worker-err"));
            for (var path : capture) java.nio.file.Files.writeString(path, "authentic");
            var diagnostics = LinuxBazelInvocation.Diagnostics.freeze(capture, original, root.resolve("frozen"));
            boolean failed = false;
            try { diagnostics.publish(() -> true, () -> { throw new java.io.IOException("Promotion failed"); }); }
            catch (java.io.IOException expected) { failed = true; }
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(failed, "promotionFailureRetained").isTrue();
            for (var path : original)
                io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(java.nio.file.Files.exists(path),
                    "failedDiagnosticPublicationRolledBack").isFalse();
            var grants = new java.util.concurrent.atomic.AtomicInteger();
            failed = false;
            try { diagnostics.publish(() -> grants.incrementAndGet() < 2, () -> {}); }
            catch (java.io.IOException expected) { failed = true; }
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(failed, "cancellationRetained").isTrue();
            for (var path : original)
                io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(java.nio.file.Files.exists(path),
                    "cancelledDiagnosticPublicationRolledBack").isFalse();
            java.nio.file.Files.writeString(original.get(1), "foreign-worker-file");
            failed = false;
            try { diagnostics.publish(() -> true, () -> {}); }
            catch (java.io.IOException expected) { failed = true; }
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(failed, "occupiedDestinationRejected").isTrue();
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(java.nio.file.Files.exists(original.get(0)),
                "earlierDestinationRolledBack").isFalse();
            io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat(java.nio.file.Files.readString(original.get(1)),
                "foreignDestinationPreserved").isEqualTo("foreign-worker-file");
        }
        finally { removeDiagnosticFixture(root); }
    }

    /**
     * Removes a per-test diagnostics fixture after all opened files close.
     * @param root exclusive fixture directory
     * @throws java.io.IOException if an owned file cannot be removed
     */
    private static void removeDiagnosticFixture(java.nio.file.Path root) throws java.io.IOException
    {
        try (var paths = java.nio.file.Files.walk(root))
        {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
    }

}
