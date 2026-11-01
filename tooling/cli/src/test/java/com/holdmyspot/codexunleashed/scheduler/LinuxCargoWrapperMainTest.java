package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises compiler request construction at the executable wrapper composition boundary without native acquisition.
 */
public final class LinuxCargoWrapperMainTest
{
    /**
     * Creates independent compiler request checks.
     */
    public LinuxCargoWrapperMainTest() {}

    /**
     * Launch composition preserves literal argument boundaries, initial environment and working directory.
     * @throws IOException if an approved request is rejected
     */
    @Test
    public void preservesExactCompilerRequest() throws IOException
    {
        List<String> arguments = new ArrayList<>(List.of(path("/tools/rustc").toString(), "a b.rs", "", "$literal", "--cfg=quote\"here"));
        Map<String, String> environment = new HashMap<>(Map.of("CARGO_MAKEFLAGS", "--jobserver-auth=fifo:/fifo", "TEXT", "\n$literal"));
        Path directory = path("/workspace with space");
        LinuxProcessTree.Launch launch = LinuxCargoWrapperMain.launch(wrapper(), arguments, environment, directory);
        requireThat(launch.command().equals(arguments), "literalArguments").isTrue();
        requireThat(launch.environment().equals(environment), "initialEnvironment").isTrue();
        requireThat(launch.directory().equals(directory), "initialDirectory").isTrue();
        requireThat(launch.executionMode(), "executionMode").isEqualTo(LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        arguments.clear(); environment.clear();
        requireThat(launch.command().size(), "retainedArguments").isEqualTo(5);
        requireThat(launch.environment().size(), "retainedEnvironment").isEqualTo(2);
    }

    /**
     * Unapproved compiler paths and NUL-bearing arguments are rejected before a session can open.
     */
    @Test
    public void rejectsUnapprovedCompilerCommand()
    {
        for (List<String> command : List.of(List.<String>of(), List.of("rustc"), List.of("/foreign/rustc"),
            List.of("/tools/../tools/rustc"), List.of(path("/tools/rustc").toString(), "bad\0argument")))
            requireException(IOException.class, () -> LinuxCargoWrapperMain.launch(wrapper(), command, Map.of(), path("/workspace")));
    }

    /**
     * Native-unrepresentable environment entries cannot become authenticated compiler requests.
     */
    @Test
    public void rejectsMalformedCompilerEnvironment()
    {
        for (Map<String, String> environment : List.of(Map.of("bad=name", "value"), Map.of("", "value"), Map.of("TEXT", "bad\0value")))
            requireException(IllegalArgumentException.class, () -> LinuxCargoWrapperMain.launch(wrapper(),
                List.of(path("/tools/rustc").toString()), environment, path("/workspace")));
    }

    /**
     * Supplies a complete resource-free invocation context.
     * @return independent immutable wrapper context
     */
    private static LinuxCargoLaunchConfiguration.Wrapper wrapper()
    {
        return new LinuxCargoLaunchConfiguration.Wrapper(path("/run/controller"), path("/run/registrations"),
            path("/cgroup/actions"), path("/run/invocation"), path("/runtime/bin/java"),
            path("/runtime/lib/modules"), path("/tools/rustc"), 1001, 1002, Duration.ofSeconds(3));
    }

    /**
     * Resolves a test-owned resource-free absolute path on the current filesystem provider.
     * @param value slash-prefixed names beneath the provider root
     * @return absolute path using the current provider's root and separators
     */
    private static Path path(String value)
    {
        Path root = Path.of("").toAbsolutePath().getRoot();
        return root.resolve(value.substring(1));
    }
}
