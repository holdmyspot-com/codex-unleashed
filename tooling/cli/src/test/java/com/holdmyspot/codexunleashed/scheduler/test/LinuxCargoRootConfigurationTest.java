package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxAuthority;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoRegistration;
import com.holdmyspot.codexunleashed.scheduler.LinuxProcessTree;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks root launch selection without acquiring native processes, streams or filesystem resources.
 */
public final class LinuxCargoRootConfigurationTest
{
    /**
     * Creates method-owned launch configuration checks.
     */
    public LinuxCargoRootConfigurationTest() {}

    /**
     * Accepts only the root mode whose original gate can be replaced through its retained native attachment.
     */
    @Test
    public void requiresAttachedRootLaunch()
    {
        LinuxCargoInvocation.Configuration configuration = configuration(LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        requireThat(configuration.cargo().executionMode(), "retainedRootMode").
            isEqualTo(LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        requireException(IllegalArgumentException.class, () -> configuration(LinuxProcessTree.ExecutionMode.DIRECT_EXEC));
    }

    /**
     * Selects borrowed root streams explicitly while retaining the existing file-root overload.
     */
    @Test public void selectsExactlyOneRootInput()
    {
        LinuxCargoInvocation.Configuration files = configuration(LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        requireThat(files.root() instanceof LinuxCargoInvocation.FileRoot, "fileRoot").isTrue();
        Path directory = Path.of("private-root-streams").toAbsolutePath().normalize();
        LinuxCargoInvocation.BorrowedRoot borrowed = new LinuxCargoInvocation.BorrowedRoot(directory);
        LinuxCargoInvocation.Configuration configuration = new LinuxCargoInvocation.Configuration(files.cargo(), borrowed,
            files.registration(), files.controllerEndpoint(), files.frida(), files.authority(), files.jobs(), files.timeout());
        requireThat(configuration.root(), "borrowedRoot").isEqualTo(borrowed);
        requireException(IllegalArgumentException.class, () -> new LinuxCargoInvocation.BorrowedRoot(Path.of("relative")));
    }

    /**
     * Constructs a consistent immutable policy using host-absolute modeled paths without opening them.
     * @param mode requested root execution mode
     * @return resource-free validated invocation policy
     */
    private static LinuxCargoInvocation.Configuration configuration(LinuxProcessTree.ExecutionMode mode)
    {
        Path root = Path.of("cargo-root-policy").toAbsolutePath().normalize();
        Path groups = root.resolve("groups");
        Path work = groups.resolve("work");
        Path scratch = root.resolve("scratch");
        Path java = root.resolve("java");
        Path jar = root.resolve("tooling.jar");
        Path cargo = root.resolve("cargo");
        Path rustc = root.resolve("rustc");
        Path wrapper = root.resolve("wrapper");
        Duration timeout = Duration.ofSeconds(5);
        Map<String, String> environment = Map.of("RUSTC", rustc.toString(), "RUSTC_WRAPPER", wrapper.toString());
        LinuxProcessTree.Launch context = new LinuxProcessTree.Launch(work, scratch, java, jar,
            List.of(rustc.toString()), environment, root, 1000, 1000, LinuxProcessTree.ExecutionMode.ATTACHED_LEADER_EXEC);
        LinuxProcessTree.Launch launch = new LinuxProcessTree.Launch(work, scratch, java, jar,
            List.of(cargo.toString()), environment, root, 1000, 1000, mode);
        LinuxCargoRegistration.Configuration registration = new LinuxCargoRegistration.Configuration(cargo, java, rustc,
            List.of(java.toString(), "Wrapper"), context, environment);
        LinuxAuthority.Settings authority = new LinuxAuthority.Settings(java, root.resolve("unshare"), root.resolve("libcgroup.so"),
            scratch, groups, root.resolve("proc"), work, groups.resolve("helper"), timeout);
        return new LinuxCargoInvocation.Configuration(launch, new LinuxProcessTree.StandardFiles(root.resolve("stdin"),
            root.resolve("stdout"), root.resolve("stderr")), registration, root.resolve("control.sock"),
            root.resolve("frida.so"), authority, 2, timeout);
    }
}
