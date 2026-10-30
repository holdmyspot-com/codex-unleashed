package com.holdmyspot.codexunleashed.scheduler;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates explicit scheduler selection without enabling unaccepted native build-tool integrations.
 * Existing Cargo/Bazel invocations remain unscheduled. The coordinator is a separate package-private operation.
 */
public final class Scheduler
{
    /** Options whose values must be supplied exactly once. */
    private static final Set<String> OPTIONS = Set.of("--tool", "--workspace", "--scratch", "--frida-library",
        "--headroom-bytes", "--max-jobs", "--control-timeout-ms");

    /** Prevents construction. */
    private Scheduler() {}

    /**
     * Validates a scheduled invocation and reports unavailable integration without launching a process.
     * No adapter is installed by this initial CLI/coordinator slice. There is no static or unscheduled fallback.
     * @param arguments scheduler options followed by -- and unchanged tool arguments
     * @param out caller-owned output stream, retained open
     * @param err caller-owned diagnostic stream, retained open
     * @return two for invalid configuration, or one for an unavailable native adapter
     */
    public static int run(String[] arguments, PrintStream out, PrintStream err)
    {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Settings settings;
        try { settings = parse(arguments); }
        catch (IllegalArgumentException failure)
        {
            err.println("ERROR: " + failure.getMessage());
            err.println("Usage: codex-tooling schedule --tool cargo|bazel --workspace PATH --scratch PATH " +
                "--frida-library PATH --headroom-bytes N --max-jobs N --control-timeout-ms N -- TOOL_ARGUMENTS...");
            return 2;
        }
        err.println("ERROR: No accepted " + settings.tool() + " scheduler adapter is installed; scheduling was not started.");
        return 1;
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
        while (index < arguments.length && arguments[index].equals("--") == false)
        {
            String option = arguments[index++];
            if (OPTIONS.contains(option) == false || index == arguments.length)
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            if (options.putIfAbsent(option, arguments[index++]) != null)
                throw new IllegalArgumentException("Duplicate scheduler option: " + option);
        }
        if (options.keySet().equals(OPTIONS) == false)
            throw new IllegalArgumentException("Every scheduler option shown in usage is required");
        if (index == arguments.length || ++index == arguments.length)
            throw new IllegalArgumentException("Expected -- followed by build-tool arguments");
        String tool = options.get("--tool");
        if (tool.equals("cargo") == false && tool.equals("bazel") == false)
            throw new IllegalArgumentException("Scheduler tool must be cargo or bazel");
        long headroom = Long.parseLong(options.get("--headroom-bytes"));
        int jobs = Integer.parseInt(options.get("--max-jobs"));
        long milliseconds = Long.parseLong(options.get("--control-timeout-ms"));
        Duration timeout = Duration.ofMillis(milliseconds);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Control timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("Control timeout exceeds nanosecond range", cause); }
        return new Settings(tool, path(options, "--workspace"), path(options, "--scratch"),
            path(options, "--frida-library"), new Policy.Budget(headroom, jobs), timeout,
            List.copyOf(Arrays.asList(Arrays.copyOfRange(arguments, index, arguments.length))));
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
     * @param arguments unchanged tool arguments, copied by the parser
     */
    record Settings(String tool, Path workspace, Path scratch, Path fridaLibrary, Policy.Budget budget,
        Duration controlTimeout, List<String> arguments) {}
}
