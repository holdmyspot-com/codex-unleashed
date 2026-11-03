package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.MacCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.MacCargoRegistration;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks that compiler registration expects the environment Cargo actually received from its jobserver lease. */
public final class MacCargoRegistrationPolicyTest
{
    /** Creates a resource-free test instance. */
    public MacCargoRegistrationPolicyTest() {}

    /** A required entry that the lease rewrites, such as PATH naming the make front end, is expected with the lease value. */
    @Test
    public void requiredEntriesFollowTheLeaseEnvironment()
    {
        TestRequirements.requirePosixFileSystem();
        var trusted = new MacCargoRegistration.Configuration(Path.of("/opt/cargo"), Path.of("/opt/wrapper"),
            Path.of("/opt/rustc"), 501, 20, Map.of("PATH", "/usr/bin:/bin", "RUSTC", "/opt/rustc"));
        Map<String, String> lease = Map.of("PATH", "/scratch/make-front-end:/usr/bin:/bin", "RUSTC", "/opt/rustc",
            "CARGO_MAKEFLAGS", "-j --jobserver-auth=fifo:/scratch/jobserver");
        var policy = MacCargoInvocation.registrationFor(trusted, lease);
        requireThat(policy.requiredEnvironment(), "requiredEnvironment").isEqualTo(
            Map.of("PATH", "/scratch/make-front-end:/usr/bin:/bin", "RUSTC", "/opt/rustc"));
        requireThat(policy.cargoExecutable(), "cargoExecutable").isEqualTo(trusted.cargoExecutable());
        requireThat(policy.uid(), "uid").isEqualTo(501);
    }
}
