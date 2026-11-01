package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies immutable native launch facts consumed by creation-based registration.
 * Native callback capture and original-manager gating require the Windows integration gates.
 */
public final class WindowsCreationObserverTest
{
    /** Creates a test instance whose methods independently own their fixtures. */
    public WindowsCreationObserverTest() {}

    /**
     * Accepts complete process environments while preserving strict framing, shared ownership and a finite bound.
     * @throws Exception if a valid process-state frame cannot be parsed
     */
    @Test
    public void completeEnvironmentHasItsOwnBoundedParser() throws Exception
    {
        var scope = new WindowsCreationObserver.MessageScope();
        try (scope)
        {
            var parser = scope.json();
            String value = "x".repeat(19000);
            String frame = "{\"type\":\"send\",\"payload\":{\"state\":{\"environment\":[\"PATH=" + value + "\"]}}}";
            requireThat(parser.readTree(frame).path("payload").path("state").path("environment").get(0).asString(),
                "completeNativeEnvironment").isEqualTo("PATH=" + value, "expected");
            requireThat(scope.json() == parser, "observerSharedMapper").isTrue();
            requireException(tools.jackson.core.JacksonException.class, () -> parser.readTree("{\"a\":1,\"a\":2}"));
            requireException(tools.jackson.core.JacksonException.class, () -> parser.readTree("{} {}"));
            requireException(tools.jackson.core.JacksonException.class,
                () -> parser.readTree(" ".repeat(WindowsCreationObserver.MessageScope.MAX_CHARACTERS) + "{}"));
        }
        requireThat(scope.isClosed(), "messageScopeDisposed").isTrue();
        requireException(IllegalStateException.class, scope::json);
    }

    /**
     * Reads current native state including hidden drive entries and releases the native environment snapshot.
     * @throws IOException if modeled native capture fails
     */
    @Test
    public void capturesCurrentNativeState() throws IOException
    {
        try (Arena arena = Arena.ofConfined())
        {
            MemorySegment command = arena.allocateFrom("wrapper.exe \"\"", java.nio.charset.StandardCharsets.UTF_16LE);
            MemorySegment environment = arena.allocateFrom("=C:=C:\\work\0EMPTY=\0\0",
                java.nio.charset.StandardCharsets.UTF_16LE);
            boolean[] freed = {false};
            try (WindowsProcess.Kernel kernel = new WindowsProcess.Kernel(Arena.ofConfined())
            {
                @Override Object call(String name, Object... arguments) throws IOException
                {
                    return switch (name)
                    {
                        case "GetCommandLineW" -> command;
                        case "GetEnvironmentStringsW" -> environment;
                        case "FreeEnvironmentStringsW" -> { freed[0] = true; yield 1; }
                        case "GetCurrentDirectoryW" -> {
                            MemorySegment target = (MemorySegment) arguments[1];
                            String path = "C:\\work";
                            for (int index = 0; index < path.length(); ++index)
                                target.setAtIndex(ValueLayout.JAVA_CHAR, index, path.charAt(index));
                            target.setAtIndex(ValueLayout.JAVA_CHAR, path.length(), '\0');
                            yield path.length();
                        }
                        default -> throw new IOException("Unexpected native call: " + name);
                    };
                }
            })
            {
                WindowsCreationReceipt.NativeState state = WindowsCreationReceipt.currentState(kernel);
                requireThat(state.commandLine(), "nativeCommandLine").isEqualTo("wrapper.exe \"\"");
                requireThat(state.directory(), "nativeDirectory").isEqualTo("C:\\work");
                requireThat(state.environment(), "nativeEnvironment").isEqualTo(List.of("=C:=C:\\work", "EMPTY="));
                requireThat(freed[0], "nativeSnapshotFreed").isTrue();
            }
        }
    }

    /**
     * Copies native environment entries while retaining empty values and drive-directory names.
     */
    @Test
    public void copiesNativeEnvironmentWithoutLosingEntries()
    {
        List<String> environment = new ArrayList<>(List.of("EMPTY=", "=C:=C:\\work", "Path=C:\\tools"));
        WindowsCreationReceipt.Launch launch = new WindowsCreationReceipt.Launch(null, "wrapper.exe \"\"", "C:\\work",
            environment, true, false, true, 71, 72, 72);
        environment.clear();
        requireThat(launch.environment(), "retainedNativeEnvironment").isEqualTo(
            List.of("EMPTY=", "=C:=C:\\work", "Path=C:\\tools"));
        requireThat(launch.output(), "originalOutputHandle").isEqualTo(launch.error());
        requireThat(launch.inheritedEnvironment(), "nativeInheritedEnvironment").isTrue();
        requireThat(launch.inheritedDirectory(), "nativeExplicitDirectory").isFalse();
    }

    /**
     * Keeps omitted native application text distinct from an explicitly empty argument.
     */
    @Test
    public void preservesOmittedAndEmptyNativeArguments()
    {
        WindowsCreationReceipt.Launch omitted = new WindowsCreationReceipt.Launch(null, null, "C:\\work",
            List.of(), false, true, false, 0, 0, 0);
        WindowsCreationReceipt.Launch empty = new WindowsCreationReceipt.Launch("", "", "C:\\work",
            List.of(), false, true, false, 0, 0, 0);
        requireThat(omitted.application() == null, "omittedNativeApplication").isTrue();
        requireThat(empty.application(), "explicitEmptyApplication").isEqualTo("");
        requireThat(omitted.commandLine() == null, "omittedNativeCommandLine").isTrue();
        requireThat(empty.commandLine(), "explicitEmptyCommandLine").isEqualTo("");
    }

    /**
     * Rejects a truncated native string before its value can become reusable launch authority.
     */
    @Test
    public void rejectsEmbeddedNativeStringTerminators()
    {
        requireException(IllegalArgumentException.class, () -> new WindowsCreationReceipt.Launch(
            "wrapper.exe", "wrapper.exe\0hidden", "C:\\work", List.of(), false, false, true, 71, 72, 73));
        requireException(IllegalArgumentException.class, () -> new WindowsCreationReceipt.Launch(
            "wrapper.exe", "wrapper.exe", "C:\\work", List.of("KEY=value\0hidden"), false, false, true, 71, 72, 73));
    }
}
