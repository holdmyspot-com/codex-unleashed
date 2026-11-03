package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one Cargo compiler-wrapper request against its invocation's authenticated controller.
 * The installed launcher executes this module entry with the compiler executable followed by its literal arguments.
 * Original standard descriptors remain borrowed by the maintained wrapper session.
 */
public final class LinuxCargoWrapperMain
{
    /**
     * Prevents construction of the executable composition boundary.
     */
    private LinuxCargoWrapperMain() {}

    /**
     * Resolves the invocation-provisioned context and returns the controller's final compiler status to Cargo.
     * Operational input, authority or cleanup failure prints a diagnostic and exits 125; no compiler runs directly.
     * @param arguments exact compiler executable and literal arguments supplied by Cargo
     */
    public static void main(String[] arguments)
    {
        int status;
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            Map<String, String> environment = Map.copyOf(System.getenv());
            String locator = environment.get(LinuxCargoLaunchConfiguration.WRAPPER_CONFIG_ENV);
            if (locator == null) throw new IOException("Invocation wrapper configuration locator is missing");
            LinuxCargoLaunchConfiguration.Wrapper configuration = LinuxCargoLaunchConfiguration.readWrapper(scope,
                Path.of(locator));
            status = run(scope, configuration, List.of(arguments), environment, Path.of("").toAbsolutePath());
        }
        catch (IOException | RuntimeException failure)
        {
            System.err.println("Linux Cargo wrapper failed: " + failure.getMessage());
            status = 125;
        }
        System.exit(status);
    }

    /**
     * Composes the maintained wrapper and native session while retaining both scopes through cleanup.
     * @param scope caller-owned invocation services retained through session cleanup
     * @param configuration trusted immutable per-invocation wrapper context
     * @param arguments exact compiler executable and literal arguments supplied by Cargo
     * @param environment complete initial wrapper environment, preserved for native authentication and replay
     * @param directory exact initial wrapper working directory
     * @return the single controller-authorized final compiler status from zero through 255
     * @throws IOException if input, native acquisition, protocol or cleanup fails
     */
    static int run(LinuxCargoScope scope, LinuxCargoLaunchConfiguration.Wrapper configuration,
        List<String> arguments, Map<String, String> environment, Path directory) throws IOException
    {
        LinuxProcessTree.Launch launch = launch(configuration, arguments, environment, directory);
        ByteBuffer identity = ByteBuffer.wrap(scope.capability());
        UUID action = new UUID(identity.getLong(), identity.getLong());
        return new LinuxCargoWrapper(scope, launch, () -> LinuxCargoWrapperSession.open(scope.tooling(),
            configuration.controller(), configuration.registrationDirectory(), action, launch, configuration.timeout())).run();
    }

    /**
     * Validates the approved compiler and constructs its exact command, environment and working-directory context.
     * @param configuration immutable invocation context read from a root-controlled file
     * @param arguments nonempty compiler command beginning with the exact configured compiler path
     * @param environment complete initial wrapper environment including the invocation-owned locator
     * @param directory absolute initial wrapper working directory
     * @return immutable attached-execution launch preserving all compiler inputs
     * @throws IOException if the command selects a different compiler or contains unrepresentable native text
     * @throws NullPointerException if a required input or member is null
     * @throws IllegalArgumentException if the directory or environment is invalid
     */
    public static LinuxProcessTree.Launch launch(LinuxCargoLaunchConfiguration.Wrapper configuration,
        List<String> arguments, Map<String, String> environment, Path directory) throws IOException
    {
        if (arguments.isEmpty() || !arguments.getFirst().equals(configuration.rustc().toString()))
            throw new IOException("Cargo wrapper command must begin with the exact configured compiler executable");
        for (String argument : arguments)
            if (argument.indexOf('\0') >= 0) throw new IOException("Compiler argument contains NUL");
        for (Map.Entry<String, String> entry : environment.entrySet())
        {
            boolean invalidName = entry.getKey().isEmpty() || entry.getKey().indexOf('=') >= 0 || entry.getKey().indexOf('\0') >= 0;
            if (invalidName || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Compiler environment contains an unrepresentable native entry");
        }
        return new LinuxProcessTree.Launch(configuration.cgroupParent(), configuration.scratchParent(),
            configuration.javaExecutable(), configuration.classPath(), arguments, environment, directory,
            configuration.uid(), configuration.gid(), LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
    }
}
