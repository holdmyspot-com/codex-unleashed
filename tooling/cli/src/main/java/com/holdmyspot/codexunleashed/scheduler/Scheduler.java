package com.holdmyspot.codexunleashed.scheduler;

import java.io.PrintStream;
import java.io.InputStream;
import java.io.IOException;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates explicit scheduling options and dispatches supported native build-tool adapters.
 * Linux, Windows and macOS Cargo require explicit launch configuration and inherited native standard handles.
 * Unsupported adapters fail before launching work; native cleanup ownership is retained on failure.
 */
public final class Scheduler
{
    /** Options whose values must be supplied exactly once. */
    private static final Set<String> OPTIONS = Set.of("--tool", "--workspace", "--scratch", "--frida-library",
        "--headroom-bytes", "--max-jobs", "--control-timeout-ms");
    /** Optional Linux construction input, required only when activating that adapter. */
    private static final String LINUX_CONFIGURATION = "--linux-launch-config";

    /** Optional Windows construction input, required only when activating that adapter. */
    private static final String WINDOWS_CONFIGURATION = "--windows-launch-config";

    /** Optional macOS construction input, required only for the explicit Mac adapter. */
    private static final String MAC_CONFIGURATION = "--mac-launch-config";

    /** Prevents construction. */
    private Scheduler() {}

    /**
     * Validates scheduling options using caller-owned diagnostic streams.
     * This overload supplies no inherited native input context, so native execution is refused.
     * Shared tooling services close before return unless pending native cleanup retains their ownership.
     * @param arguments scheduler options followed by -- and unchanged tool arguments
     * @param out caller-owned output stream, retained open
     * @param err caller-owned diagnostic stream, retained open
     * @return two for invalid configuration, or one for an unsupported adapter or unavailable inherited input
     * @throws PendingCleanup if native ownership remains unresolved; close it on the originating thread
     */
    public static int run(String[] arguments, PrintStream out, PrintStream err)
    {
        ToolingScope scope = new DefaultToolingScope();
        boolean retained = false;
        try { return run(scope, arguments, InputStream.nullInputStream(), out, err); }
        catch (PendingCleanup pending)
        {
            pending.retain(scope);
            retained = true;
            throw pending;
        }
        finally { if (!retained) scope.close(); }
    }

    /**
     * Constructs an explicit native adapter with invocation-owned shared services and actual caller streams.
     * Native execution requires the original System descriptor streams; arbitrary Java streams are refused.
     * @param scope borrowed tooling services retained through native cleanup
     * @param arguments options and unchanged tool argument tail
     * @param in caller input retained open
     * @param out caller output retained open
     * @param err diagnostics retained open
     * @return actual Cargo status, two for invalid arguments, or one for unsupported/native operational failure
     * @throws NullPointerException if an input is null
     * @throws PendingCleanup if native cleanup remains unresolved; retain the supplied scope and close the owner
     * on the originating thread before closing the scope
     */
    public static int run(ToolingScope scope, String[] arguments, InputStream in, PrintStream out, PrintStream err)
    {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        return dispatch(scope, arguments, in, out, err, System.getProperty("os.name", ""), (services, settings) ->
            settings.macLaunchConfiguration() != null ? MacCargoSchedule.run(services, settings, settings.macLaunchConfiguration()) :
                settings.windowsLaunchConfiguration() != null ? WindowsCargoSchedule.run(services, settings) :
                    LinuxCargoSchedule.run(services, settings));
    }

    /**
     * Validates platform and stream authority before selecting a native scheduling boundary.
     * @param scope borrowed invocation services
     * @param arguments explicit scheduler options and unchanged tool tail
     * @param in borrowed caller input
     * @param out borrowed caller output
     * @param err borrowed diagnostics
     * @param platform operating system resolved by the production process boundary
     * @param adapter native execution boundary selected only after valid preflight
     * @return original adapter status, two for invalid options, or one for operational/unavailable execution
     * @throws PendingCleanup if native borrowers retain their cleanup authority
     */
    private static int dispatch(ToolingScope scope, String[] arguments, InputStream in, PrintStream out, PrintStream err,
        String platform, Adapter adapter)
    {
        Settings settings;
        try { settings = parse(arguments); }
        catch (IllegalArgumentException failure)
        {
            err.println("ERROR: " + failure.getMessage());
            err.println("Usage: codex-tooling schedule --tool cargo|bazel --workspace PATH --scratch PATH " +
                "--frida-library PATH --headroom-bytes N --max-jobs N --control-timeout-ms N " +
                "[--linux-launch-config PATH | --windows-launch-config PATH | --mac-launch-config PATH] -- TOOL_ARGUMENTS...");
            return 2;
        }
        boolean linux = platform.equals("Linux");
        boolean windows = platform.startsWith("Windows");
        boolean mac = platform.equals("Mac OS X");
        if (settings.macLaunchConfiguration() != null && !mac)
        {
            err.println("ERROR: --mac-launch-config requires macOS");
            return 2;
        }
        if (settings.windowsLaunchConfiguration() != null && !windows)
        {
            err.println("ERROR: --windows-launch-config requires Windows");
            return 2;
        }
        if (settings.linuxLaunchConfiguration() != null && !linux)
        {
            err.println("ERROR: --linux-launch-config requires Linux");
            return 2;
        }
        boolean nativeCargo = settings.tool().equals("cargo") && (linux || windows || mac);
        if (nativeCargo)
        {
            if ((linux && settings.linuxLaunchConfiguration() == null) ||
                (windows && settings.windowsLaunchConfiguration() == null) ||
                (mac && settings.macLaunchConfiguration() == null))
            {
                err.println("ERROR: " + platform + " Cargo scheduling requires " +
                    (mac ? MAC_CONFIGURATION : windows ? WINDOWS_CONFIGURATION : LINUX_CONFIGURATION) + " PATH");
                return 2;
            }
            if (in != System.in || out != System.out || err != System.err)
            {
                err.println("ERROR: Native scheduling requires inherited System input, output and error descriptors");
                return 1;
            }
            try { return adapter.run(scope, settings); }
            catch (PendingCleanup pending) { throw pending; }
            catch (IOException | IllegalArgumentException | UnsupportedOperationException failure)
            {
                report(failure, err);
                return 1;
            }
        }
        err.println("ERROR: No accepted " + settings.tool() + " scheduler adapter is installed; scheduling was not started.");
        return 1;
    }

    /**
     * Native scheduling execution after command/platform/stream preflight.
     */
    @FunctionalInterface
    interface Adapter
    {
        /**
         * Executes the selected adapter while borrowing the supplied services.
         * @param scope borrowed services retained through native cleanup
         * @param settings validated selection and launch inputs
         * @return original native tool status
         * @throws IOException if native construction or execution fails
         */
        int run(ToolingScope scope, Settings settings) throws IOException;
    }

    /**
     * Typed access to command dispatch with explicit platform and native-boundary inputs.
     */
    static final class SharedSecrets
    {
        /**
         * Prevents construction.
         */
        private SharedSecrets() {}

        /**
         * Runs the production dispatcher with controlled external platform and native execution boundaries.
         * @param scope borrowed services
         * @param arguments scheduler options and tool arguments
         * @param in borrowed input
         * @param out borrowed output
         * @param err borrowed diagnostics
         * @param platform explicit operating-system observation
         * @param adapter native execution boundary
         * @return unchanged native status or production preflight failure status
         */
        static int run(ToolingScope scope, String[] arguments, InputStream in, PrintStream out, PrintStream err,
            String platform, Adapter adapter)
        {
            return dispatch(scope, arguments, in, out, err, platform, adapter);
        }
    }

    /**
     * Prints a failure and each distinct cause, one message per line, so the root cause reaches the diagnostic stream.
     * @param failure failure to report
     * @param err diagnostic stream
     * @throws NullPointerException if an argument is null
     */
    public static void report(Throwable failure, PrintStream err)
    {
        err.println("ERROR: " + failure.getMessage());
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        seen.add(failure);
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause())
            err.println("Caused by: " + cause.getMessage());
    }

    /**
     * Progresses cleanup of an invocation retained after its original failure.
     * Implementations belong to the invoking thread and retain all resources until closure is established.
     */
    @FunctionalInterface
    public interface Cleanup
    {
        /**
         * Progresses only safely retryable cleanup and returns after all borrowed resources are released.
         * @throws IOException if native closure remains incomplete
         */
        void close() throws IOException;
    }

    /**
     * Retains native cleanup and any transferred tooling scopes after an operational failure.
     * The caller must keep this object reachable and call close only on its constructing thread.
     * A failed native cleanup retains every scope; successful cleanup closes transferred scopes once.
     */
    public static final class PendingCleanup extends IllegalStateException implements AutoCloseable
    {
        /** Exception serialization identifier; serialized markers carry no native cleanup authority. */
        private static final long serialVersionUID = 1L;
        /** Originating native coordination thread. */
        private final transient Thread owner = Thread.currentThread();
        /** Retained native owner callback; absent only after serialization. */
        private final transient Cleanup cleanup;
        /** Transferred scopes, released only after native borrowers finish. */
        private final transient List<ToolingScope> scopes = new ArrayList<>();
        /** Indicates whether the native callback established complete closure. */
        private boolean nativeClosed;
        /** Indicates whether native cleanup and every transferred scope completed closure. */
        private boolean closed;
        /** Uncertain transferred-scope close failure, never replayed. */
        private transient Throwable scopeFailure;

        /**
         * Retains the original failure and its owning-thread cleanup operation.
         * @param failure original operational or cleanup failure
         * @param cleanup retained native closure operation; normal return proves complete native closure
         * @throws NullPointerException if an input is null
         */
        public PendingCleanup(Throwable failure, Cleanup cleanup)
        {
            super("Native scheduling cleanup remains pending; retain this owner and its scopes", Objects.requireNonNull(failure, "failure"));
            this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        }

        /**
         * Transfers ownership of one tooling scope into this pending native lifetime.
         * The caller must stop closing the transferred scope independently.
         * @param scope open caller-owned tooling services borrowed by the pending invocation
         * @throws NullPointerException if scope is null
         * @throws IllegalStateException if called from another thread, after closure or with a closed scope
         */
        public void retain(ToolingScope scope)
        {
            requireOwner();
            Objects.requireNonNull(scope, "scope");
            if (closed || scope.isClosed()) throw new IllegalStateException("Pending cleanup requires an open tooling scope before closure");
            if (!scopes.contains(scope)) scopes.add(scope);
        }

        /**
         * Retries the retained native cleanup on its original thread, then closes transferred scopes.
         * Native failure propagates unchanged and retains all scopes for a later safe attempt. After success,
         * repeated calls are inert. An uncertain scope close propagates unchanged and is never replayed.
         * @throws IllegalStateException if called from another thread or a scope close failed unchecked
         * @throws IOException if native cleanup remains incomplete or this marker was deserialized
         */
        @Override public void close() throws IOException
        {
            if (cleanup == null) throw new IOException("Serialized pending cleanup carries no native authority");
            requireOwner();
            if (closed) return;
            if (!nativeClosed) { cleanup.close(); nativeClosed = true; }
            if (scopeFailure instanceof RuntimeException unchecked) throw unchecked;
            if (scopeFailure instanceof Error fatal) throw fatal;
            while (!scopes.isEmpty())
            {
                ToolingScope scope = scopes.getLast();
                try { scope.close(); scopes.removeLast(); }
                catch (RuntimeException | Error failure) { scopeFailure = failure; throw failure; }
            }
            closed = true;
        }

        /**
         * Rejects cross-thread cleanup before touching retained native owners or scopes.
         * @throws IllegalStateException if another thread calls
         */
        private void requireOwner()
        {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Pending native cleanup requires its originating thread");
        }
    }

    /**
     * Parses only application-owned options; it does not interpret tool arguments or estimate documents.
     * @param arguments complete scheduler arguments
     * @return copied, validated resource-free settings
     * @throws IllegalArgumentException if configuration is missing, duplicate, unknown or invalid
     */
    static Settings parse(String[] arguments)
    {
        Map<String, String> options = new HashMap<>();
        int index = 0;
        while (index < arguments.length && !arguments[index].equals("--"))
        {
            String option = arguments[index++];
            if ((!OPTIONS.contains(option) && !option.equals(LINUX_CONFIGURATION) && !option.equals(WINDOWS_CONFIGURATION) && !option.equals(MAC_CONFIGURATION)) || index == arguments.length)
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            if (options.putIfAbsent(option, arguments[index++]) != null)
                throw new IllegalArgumentException("Duplicate scheduler option: " + option);
        }
        if (!options.keySet().containsAll(OPTIONS))
            throw new IllegalArgumentException("Every scheduler option shown in usage is required");
        if (index == arguments.length || ++index == arguments.length)
            throw new IllegalArgumentException("Expected -- followed by build-tool arguments");
        String tool = options.get("--tool");
        if (!Set.of("cargo", "bazel").contains(tool))
            throw new IllegalArgumentException("Scheduler tool must be cargo or bazel");
        long headroom = Long.parseLong(options.get("--headroom-bytes"));
        int jobs = Integer.parseInt(options.get("--max-jobs"));
        long milliseconds = Long.parseLong(options.get("--control-timeout-ms"));
        Duration timeout = Duration.ofMillis(milliseconds);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Control timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("Control timeout exceeds nanosecond range", cause); }
        long configurations = List.of(LINUX_CONFIGURATION, WINDOWS_CONFIGURATION, MAC_CONFIGURATION).stream()
            .filter(options::containsKey).count();
        if (configurations > 1 || (options.containsKey(WINDOWS_CONFIGURATION) || options.containsKey(MAC_CONFIGURATION)) && !tool.equals("cargo"))
            throw new IllegalArgumentException("Explicit native Cargo launch configurations are mutually exclusive and require Cargo");
        return new Settings(tool, path(options, "--workspace"), path(options, "--scratch"),
            path(options, "--frida-library"), new Policy.Budget(headroom, jobs), timeout,
            List.copyOf(Arrays.asList(Arrays.copyOfRange(arguments, index, arguments.length))),
            options.containsKey(LINUX_CONFIGURATION) ? path(options, LINUX_CONFIGURATION) : null,
            options.containsKey(WINDOWS_CONFIGURATION) ? path(options, WINDOWS_CONFIGURATION) : null,
            options.containsKey(MAC_CONFIGURATION) ? path(options, MAC_CONFIGURATION) : null);
    }

    /**
     * Resolves a required path without opening or inspecting it.
     * @param options validated option presence
     * @param name option key
     * @return absolute normalized path
     */
    private static Path path(Map<String, String> options, String name)
    {
        String value = options.get(name);
        if (value.isBlank()) throw new IllegalArgumentException("Path must contain text: " + name);
        return Path.of(value).toAbsolutePath().normalize();
    }

    /**
     * Copied selection inputs; platform acquisition remains a separate accepted-adapter responsibility.
     * @param tool cargo or bazel
     * @param workspace absolute working directory
     * @param scratch absolute owned scratch parent
     * @param fridaLibrary absolute native producer path
     * @param budget reserved physical memory and live-attempt ceiling
     * @param controlTimeout positive bounded native control interval
     * @param linuxLaunchConfiguration optional trusted Linux construction document, absent until explicit activation
     * @param windowsLaunchConfiguration optional explicit Windows construction document
     * @param macLaunchConfiguration optional explicit macOS construction document
     * @param arguments unchanged tool arguments, copied by the parser
     */
    record Settings(String tool, Path workspace, Path scratch, Path fridaLibrary, Policy.Budget budget,
        Duration controlTimeout, List<String> arguments, Path linuxLaunchConfiguration, Path windowsLaunchConfiguration, Path macLaunchConfiguration)
    {
        /**
         * Preserves existing explicit Linux and Windows construction inputs without enabling a Mac adapter.
         * @param tool original tool selection
         * @param workspace original working directory
         * @param scratch original owned scratch parent
         * @param fridaLibrary selected producer path
         * @param budget original admission policy
         * @param controlTimeout original control budget
         * @param arguments unchanged tool arguments
         * @param linuxLaunchConfiguration optional Linux authority
         * @param windowsLaunchConfiguration optional Windows authority
         */
        Settings(String tool, Path workspace, Path scratch, Path fridaLibrary, Policy.Budget budget,
            Duration controlTimeout, List<String> arguments, Path linuxLaunchConfiguration, Path windowsLaunchConfiguration)
        {
            this(tool, workspace, scratch, fridaLibrary, budget, controlTimeout, arguments,
                linuxLaunchConfiguration, windowsLaunchConfiguration, null);
        }
    }
}
