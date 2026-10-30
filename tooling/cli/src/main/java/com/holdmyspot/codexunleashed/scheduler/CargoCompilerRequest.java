package com.holdmyspot.codexunleashed.scheduler;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Copies one compiler invocation after its producer authenticates the live wrapper and parent.
 * Construction validates data only: claimed process identities do not establish native authority.
 * No transport, stream handles, jobserver capacity or process ownership is acquired here.
 * @param identity action identity, stable across retries
 * @param wrapper independently authenticated wrapper and parent birth identities
 * @param arguments exact executable and ordered arguments, including explicit empty arguments
 * @param directory absolute compiler working directory
 * @param environment complete compiler environment, including unchanged borrowed jobserver flags
 * @param peakBytes nonnegative whole-tree demand estimate, or null when unknown
 */
record CargoCompilerRequest(UUID identity, Wrapper wrapper, List<String> arguments, Path directory,
    Map<String, String> environment, Long peakBytes)
{
    /**
     * Copies mutable collections without normalizing arguments, environment or paths.
     * @throws IllegalArgumentException if a path is relative, command is empty, native text contains NUL,
     * or a demand estimate is negative
     */
    CargoCompilerRequest
    {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(wrapper, "wrapper");
        arguments = List.copyOf(arguments);
        if (arguments.isEmpty() || arguments.getFirst().isBlank() || !Path.of(arguments.getFirst()).isAbsolute())
            throw new IllegalArgumentException("Compiler command requires an absolute executable");
        for (String argument : arguments) requireNativeText(argument);
        Objects.requireNonNull(directory, "directory");
        if (!directory.isAbsolute()) throw new IllegalArgumentException("Compiler directory must be absolute");
        requireNativeText(directory.toString());
        environment = Map.copyOf(environment);
        for (var entry : environment.entrySet())
        {
            if (entry.getKey().isEmpty()) throw new IllegalArgumentException("Environment name must contain text");
            requireNativeText(entry.getKey());
            requireNativeText(entry.getValue());
        }
        if (peakBytes != null && peakBytes < 0) throw new IllegalArgumentException("Demand must be nonnegative or unknown");
    }

    /**
     * Returns the invocation-unique name used by the queue and platform command registry.
     * @return exact UUID text, unchanged across retries
     */
    String name() { return identity.toString(); }

    /**
     * Rejects text that native null-terminated interfaces cannot preserve.
     * @param text complete argument, name, value or path
     */
    private static void requireNativeText(String text)
    {
        if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("Native text contains NUL");
    }

    /**
     * Carries identities already checked by the native registration producer.
     * Birth units follow that platform's accepted receipt; they are compared, never converted or timed here.
     * @param pid live wrapper process identifier
     * @param birth wrapper birth identity
     * @param parentPid live owning Cargo or delegated parent process identifier
     * @param parentBirth parent birth identity
     */
    record Wrapper(long pid, long birth, long parentPid, long parentBirth)
    {
        /** Rejects missing identities without claiming to authenticate them. */
        Wrapper
        {
            if (pid <= 0 || birth <= 0 || parentPid <= 0 || parentBirth <= 0 || pid == parentPid)
                throw new IllegalArgumentException("Wrapper and parent require distinct positive live birth identities");
        }
    }
}
