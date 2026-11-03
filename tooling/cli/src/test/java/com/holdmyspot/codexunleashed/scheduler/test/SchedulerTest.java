package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.Scheduler;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.secrets.SchedulerSecrets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies explicit scheduling selection without launching a native adapter. */
public final class SchedulerTest
{
    /**
     * Accepts an explicit Windows construction path without changing the literal tool argument tail.
     */
    @Test
    public void acceptsWindowsConfigurationOption()
    {
        List<String> options = new ArrayList<>(List.of(valid()));
        options.set(1, "cargo");
        options.addAll(0, List.of("--windows-launch-config", "windows launch.json"));
        Scheduler.Settings settings = Scheduler.parse(options.toArray(String[]::new));
        requireThat(settings.arguments(), "literalWindowsToolArguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
    }

    /**
     * Windows platform dispatch preserves adapter status and tool arguments after configuration and stream preflight.
     */
    @Test
    public void dispatchesExplicitWindowsCargoToNativeBoundary()
    {
        List<String> options = new ArrayList<>(List.of(valid()));
        options.set(1, "cargo");
        options.addAll(0, List.of("--windows-launch-config", "windows.json"));
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            int result = SchedulerSecrets.getSchedulerAccess().run(scope, options.toArray(String[]::new), System.in, System.out, System.err,
                "Windows 11", (services, settings) ->
                {
                    calls.incrementAndGet();
                    requireThat(settings.windowsLaunchConfiguration(), "explicitWindowsConfiguration")
                        .isEqualTo(Path.of("windows.json").toAbsolutePath().normalize());
                    requireThat(settings.arguments(), "literalNativeArguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
                    return 101;
                });
            requireThat(result, "originalNativeStatus").isEqualTo(101);
            requireThat(calls.get(), "oneSelectedNativeBoundary").isEqualTo(1);
        }
    }

    /**
     * Wrong-platform configuration and custom Java streams are refused before the native scheduling boundary runs.
     */
    @Test
    public void refusesWindowsWrongPlatformAndCustomStreams()
    {
        List<String> options = new ArrayList<>(List.of(valid()));
        options.set(1, "cargo");
        options.addAll(0, List.of("--windows-launch-config", "never-opened.json"));
        AtomicInteger calls = new AtomicInteger();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (DefaultToolingScope scope = new DefaultToolingScope(); PrintStream err = new PrintStream(errors))
        {
            Scheduler.Adapter nativeBoundary = (services, settings) -> calls.incrementAndGet();
            int wrongPlatform = SchedulerSecrets.getSchedulerAccess().run(scope, options.toArray(String[]::new), System.in, System.out,
                err, "Linux", nativeBoundary);
            requireThat(wrongPlatform, "wrongPlatformStatus").isEqualTo(2);
            requireThat(errors.toString(), "wrongPlatformDiagnostic").contains("requires Windows");
            errors.reset();
            int customStreams = SchedulerSecrets.getSchedulerAccess().run(scope, options.toArray(String[]::new), System.in, System.out,
                err, "Windows 11", nativeBoundary);
            requireThat(customStreams, "customStreamsStatus").isEqualTo(1);
            requireThat(errors.toString(), "customStreamsDiagnostic").contains("inherited System");
            requireThat(calls.get(), "noNativeAcquisitionBeforePreflight").isEqualTo(0);
        }
        options.addAll(0, List.of("--linux-launch-config", "linux.json"));
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(options.toArray(String[]::new)));
    }

    /** Dispatches the explicit Mac authority and exact argument tail after inherited stream preflight. */
    @Test public void dispatchesExplicitMacCargoAndPreservesStatus()
    {
        List<String> options = macOptions();
        AtomicInteger calls = new AtomicInteger();
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            int result = SchedulerSecrets.getSchedulerAccess().run(scope, options.toArray(String[]::new), System.in, System.out, System.err,
                "Mac OS X", (services, settings) ->
                {
                    calls.incrementAndGet();
                    requireThat(services == scope, "originalBorrowedPouchScope").isTrue();
                    requireThat(settings.macLaunchConfiguration(), "exactMacAuthority")
                        .isEqualTo(Path.of("mac launch.json").toAbsolutePath().normalize());
                    requireThat(settings.linuxLaunchConfiguration(), "noLinuxFallbackAuthority").isNull();
                    requireThat(settings.windowsLaunchConfiguration(), "noWindowsFallbackAuthority").isNull();
                    requireThat(settings.arguments(), "exactMacToolArguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
                    return 79;
                });
            requireThat(result, "originalMacCargoStatus").isEqualTo(79);
            requireThat(calls.get(), "oneNativeAcquisition").isEqualTo(1);
            requireThat(scope.isClosed(), "borrowedScopeRetained").isFalse();
        }
    }

    /** Rejects missing, wrong-platform, duplicate and conflicting Mac authority before native acquisition. */
    @Test public void rejectsInvalidMacAuthorityAndStreams()
    {
        try (DefaultToolingScope scope = new DefaultToolingScope();
            PrintStream errors = new PrintStream(new ByteArrayOutputStream()))
        {
            AtomicInteger calls = new AtomicInteger();
            Scheduler.Adapter boundary = (services, settings) -> calls.incrementAndGet();
            String[] configured = macOptions().toArray(String[]::new);
            requireThat(SchedulerSecrets.getSchedulerAccess().run(scope, configured, System.in, System.out, errors,
                "Linux", boundary), "wrongMacPlatform").isEqualTo(2);
            requireThat(SchedulerSecrets.getSchedulerAccess().run(scope, configured, System.in, System.out, errors,
                "Mac OS X", boundary), "customMacStreams").isEqualTo(1);
            List<String> missing = new ArrayList<>(List.of(valid())); missing.set(1, "cargo");
            requireThat(SchedulerSecrets.getSchedulerAccess().run(scope, missing.toArray(String[]::new), System.in, System.out, errors,
                "Mac OS X", boundary), "missingMacAuthority").isEqualTo(2);
            requireThat(calls.get(), "noUnauthorizedNativeEffects").isEqualTo(0);
        }
        for (String conflicting : List.of("--linux-launch-config", "--windows-launch-config", "--mac-launch-config"))
        {
            List<String> options = macOptions(); options.addAll(0, List.of(conflicting, "other.json"));
            requireException(IllegalArgumentException.class, () -> Scheduler.parse(options.toArray(String[]::new)));
        }
        List<String> bazel = macOptions(); bazel.set(bazel.indexOf("cargo"), "bazel");
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(bazel.toArray(String[]::new)));
    }

    /**
     * Preserves the original pending owner without translating it to a successful public status.
     * @throws IOException if actual owned cleanup fails
     */
    @Test public void macDispatchPreservesPendingCleanup() throws IOException
    {
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            AtomicInteger closes = new AtomicInteger();
            Scheduler.PendingCleanup pending = new Scheduler.PendingCleanup(new IOException("Mac native owner retained"), closes::incrementAndGet);
            Scheduler.PendingCleanup observed = requireException(Scheduler.PendingCleanup.class, () ->
                SchedulerSecrets.getSchedulerAccess().run(scope, macOptions().toArray(String[]::new), System.in, System.out, System.err,
                    "Mac OS X", (services, settings) -> { throw pending; }));
            requireThat(observed == pending, "originalMacCleanupOwner").isTrue();
            requireThat(scope.isClosed(), "borrowedServicesOutliveNativeOwner").isFalse();
            observed.close(); observed.close();
            requireThat(closes.get(), "nativeCleanupOnce").isEqualTo(1);
        }
    }

    /**
     * Supplies explicit Mac Cargo authority without changing the build-tool argument tail.
     * @return independent mutable application argument list
     */
    private static List<String> macOptions()
    {
        List<String> options = new ArrayList<>(List.of(valid())); options.set(1, "cargo");
        options.addAll(0, List.of("--mac-launch-config", "mac launch.json")); return options;
    }

    /** Exercises the actual Main command parser without granting native stream or platform authority. */
    @Test public void mainRecognizesExplicitMacCommand()
    {
        List<String> options = macOptions(); options.addFirst("schedule");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        try (PrintStream errors = new PrintStream(diagnostics); PrintStream output = new PrintStream(new ByteArrayOutputStream()))
        {
            int result = Main.run(options.toArray(String[]::new), new ByteArrayInputStream(new byte[0]), output, errors);
            boolean mac = System.getProperty("os.name", "").equals("Mac OS X");
            requireThat(result, "publicMainPreflightStatus").isEqualTo(mac ? 1 : 2);
            requireThat(diagnostics.toString(), "actualMainPreflightDiagnostic")
                .contains(mac ? "inherited System" : "requires macOS");
        }
    }

    /** Preserves explicit Linux routing and authentic argument/status boundaries after adding Mac activation. */
    @Test public void preservesExplicitLinuxDispatch()
    {
        List<String> options = new ArrayList<>(List.of(valid())); options.set(1, "cargo");
        options.addAll(0, List.of("--linux-launch-config", "linux.json"));
        try (DefaultToolingScope scope = new DefaultToolingScope())
        {
            int status = SchedulerSecrets.getSchedulerAccess().run(scope, options.toArray(String[]::new), System.in, System.out, System.err,
                "Linux", (services, settings) ->
                {
                    requireThat(settings.macLaunchConfiguration(), "noMacFallback").isNull();
                    requireThat(settings.linuxLaunchConfiguration(), "originalLinuxAuthority")
                        .isEqualTo(Path.of("linux.json").toAbsolutePath().normalize());
                    return 23;
                });
            requireThat(status, "originalLinuxStatus").isEqualTo(23);
        }
    }

    /** Creates command-boundary tests. */
    public SchedulerTest() {}

    /** A well-formed scheduled request reports unavailable integration, rather than unknown-command usage. */
    @Test
    public void explicitSelectionReportsUnavailableAdapter()
    {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(new String[]{"schedule", "--tool", "bazel", "--workspace", ".",
            "--scratch", ".", "--frida-library", "frida.so", "--headroom-bytes", "0",
            "--max-jobs", "2", "--control-timeout-ms", "1000", "--", "build"},
            new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(errors));
        requireThat(result, "status").isEqualTo(1);
        requireThat(errors.toString(), "diagnostic").contains("No accepted bazel scheduler adapter");
    }

    /** Tool arguments are copied unchanged after the separator; zero remains a known reserve. */
    @Test
    public void preservesToolArgumentsAndKnownZero()
    {
        String[] arguments = valid();
        var settings = Scheduler.parse(arguments);
        requireThat(settings.arguments(), "arguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
        requireThat(settings.budget().headroomBytes(), "headroomBytes").isEqualTo(0L);
        arguments[arguments.length - 1] = "changed";
        requireThat(settings.arguments().getLast(), "copiedArgument").isEqualTo("");
    }

    /** Missing, duplicate, overflowing and unapproved estimate-format options fail before integration selection. */
    @Test
    public void rejectsInvalidConfiguration()
    {
        for (String option : List.of("--max-jobs", "--control-timeout-ms", "--headroom-bytes"))
        {
            var invalid = valid();
            int index = Arrays.asList(invalid).indexOf(option);
            invalid[index + 1] = "-1";
            requireException(IllegalArgumentException.class, () -> Scheduler.parse(invalid));
        }
        var overflow = valid();
        overflow[Arrays.asList(overflow).indexOf("--control-timeout-ms") + 1] = Long.toString(Long.MAX_VALUE);
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(overflow));
        var duplicate = new ArrayList<>(List.of(valid()));
        duplicate.addFirst("cargo");
        duplicate.addFirst("--tool");
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(duplicate.toArray(String[]::new)));
        var estimates = valid();
        estimates[0] = "--estimates";
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(estimates));
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(new String[0]));
    }

    /** Existing unscheduled command selection retains its status and does not select a native scheduler. */
    @Test
    public void preservesDefaultDispatch()
    {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(new String[0], new ByteArrayInputStream(new byte[0]),
            new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(result, "status").isEqualTo(2);
        requireThat(errors.toString().contains("No accepted"), "schedulerSelected").isFalse();
        errors.reset();
        result = Scheduler.run(new String[0], new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        requireThat(result, "invalidStatus").isEqualTo(2);
    }

    /**
     * Copies the optional authorization path and rejects duplicate option authority before native work.
     */
    @Test public void copiesLinuxConfigurationAndRejectsDuplicates()
    {
        List<String> options = new ArrayList<>(List.of(valid()));
        options.addAll(0, List.of("--linux-launch-config", "configuration with spaces.json"));
        Scheduler.Settings settings = Scheduler.parse(options.toArray(String[]::new));
        requireThat(settings.linuxLaunchConfiguration(), "configurationPath").
            isEqualTo(Path.of("configuration with spaces.json").toAbsolutePath().normalize());
        requireThat(settings.arguments(), "literalArguments").isEqualTo(List.of("build", "--jobs=7", "a b", ""));
        options.addAll(0, List.of("--linux-launch-config", "other.json"));
        requireException(IllegalArgumentException.class, () -> Scheduler.parse(options.toArray(String[]::new)));
    }

    /**
     * Preserves the original cause, retains tooling after native failure and closes it once after native success.
     * The callback represents the retained invocation's documented safe cleanup operation.
     * @throws IOException if final cleanup fails
     */
    @Test public void pendingCleanupRetainsScopeUntilNativeClosure() throws IOException
    {
        IOException original = new IOException("operational failure");
        IOException incomplete = new IOException("native consumers remain");
        ToolingScope scope = new DefaultToolingScope();
        AtomicInteger attempts = new AtomicInteger();
        Scheduler.PendingCleanup pending = new Scheduler.PendingCleanup(original, () ->
        {
            if (attempts.getAndIncrement() == 0) throw incomplete;
            requireThat(scope.isClosed(), "scopeClosedBeforeNativeCompletion").isFalse();
        });
        pending.retain(scope);
        try
        {
            requireThat(pending.getCause(), "originalFailure").isEqualTo(original);
            requireThat(requireException(IOException.class, pending::close), "nativeFailure").isEqualTo(incomplete);
            requireThat(scope.isClosed(), "scopeRetainedAfterFailure").isFalse();
            pending.close();
            requireThat(scope.isClosed(), "scopeClosedAfterNativeCompletion").isTrue();
            pending.close();
            requireThat(attempts.get(), "nativeCloseAttempts").isEqualTo(2);
        }
        finally { pending.close(); }
    }

    /**
     * Rejects cleanup from another thread before invoking native cleanup or disposing shared scope services.
     * @throws Exception if the test-owned worker cannot terminate or final cleanup fails
     */
    @Test public void pendingCleanupRequiresOriginatingThread() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Scheduler.PendingCleanup pending = new Scheduler.PendingCleanup(new IOException("original"), attempts::incrementAndGet);
        try
        {
            Thread other = new Thread(() ->
            {
                try { pending.close(); }
                catch (Throwable failure) { observed.set(failure); }
            });
            other.start();
            try { requireThat(other.join(Duration.ofSeconds(2)), "workerTerminated").isTrue(); }
            finally { other.join(); }
            requireThat(observed.get(), "wrongThreadFailure").isInstanceOf(IllegalStateException.class);
            requireThat(attempts.get(), "nativeCloseAttempts").isEqualTo(0);
        }
        finally { pending.close(); }
        requireThat(attempts.get(), "owningThreadCleanup").isEqualTo(1);
    }

    /**
     * Supplies complete scheduler options and literal quoted and empty build-tool arguments.
     * @return an independently mutable command argument array
     */
    private static String[] valid()
    {
        return new String[]{"--tool", "bazel", "--workspace", ".", "--scratch", ".", "--frida-library",
            "frida.so", "--headroom-bytes", "0", "--max-jobs", "2", "--control-timeout-ms", "1000",
            "--", "build", "--jobs=7", "a b", ""};
    }
}
