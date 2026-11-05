package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.MacCargoInvocation;
import com.holdmyspot.codexunleashed.scheduler.MacCargoRegistration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks which wrapper lineage and environment compiler registration accepts, including wrappers that a Cargo build
 * script starts.
 */
public final class MacCargoRegistrationPolicyTest
{
    /** Creates a resource-free test instance. */
    public MacCargoRegistrationPolicyTest() {}

    /**
     * Workload user identifier shared by every simulated image.
     */
    private static final int UID = 501;
    /**
     * Workload group identifier shared by every simulated image.
     */
    private static final int GID = 20;

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

    /**
     * A wrapper started by a build script, which Cargo started in its own workload, is accepted.
     * @throws IOException if the lineage is rejected
     */
    @Test
    public void buildScriptParentInCargoWorkloadIsAccepted() throws IOException
    {
        List<Long> cargo = authority(100, 7, 9, 1, 3);
        List<Long> script = authority(200, 11, 9, 100, 7);
        List<Long> wrapper = authority(300, 13, 9, 200, 11);
        MacCargoRegistration.requireLineage(cargo, script, wrapper, UID, GID);
    }

    /**
     * A parent outside Cargo's coalition cannot authorize a wrapper, even under the workload credentials.
     * @throws IOException if the lineage is rejected, as expected
     */
    @Test(expectedExceptions = IOException.class)
    public void parentOutsideCargoCoalitionIsRejected() throws IOException
    {
        List<Long> cargo = authority(100, 7, 9, 1, 3);
        List<Long> script = authority(200, 11, 8, 100, 7);
        List<Long> wrapper = authority(300, 13, 9, 200, 11);
        MacCargoRegistration.requireLineage(cargo, script, wrapper, UID, GID);
    }

    /**
     * A wrapper created by an earlier image of the same parent PID is rejected.
     * @throws IOException if the lineage is rejected, as expected
     */
    @Test(expectedExceptions = IOException.class)
    public void wrapperFromEarlierParentImageIsRejected() throws IOException
    {
        List<Long> cargo = authority(100, 7, 9, 1, 3);
        List<Long> script = authority(200, 11, 9, 100, 7);
        List<Long> wrapper = authority(300, 13, 9, 200, 10);
        MacCargoRegistration.requireLineage(cargo, script, wrapper, UID, GID);
    }

    /**
     * An entry that Cargo removed from a build script's environment, such as RUSTFLAGS, may also be absent from the
     * wrapper that the build script starts.
     * @throws IOException if the environment is rejected
     */
    @Test
    public void entryRemovedForBuildScriptIsAccepted() throws IOException
    {
        Map<String, String> required = Map.of("RUSTFLAGS", "-C debuginfo=0", "RUSTC", "/opt/rustc");
        Map<String, String> script = Map.of("RUSTC", "/opt/rustc", "OUT_DIR", "/target/out");
        Map<String, String> wrapper = Map.of("RUSTC", "/opt/rustc", "OUT_DIR", "/target/out");
        MacCargoRegistration.requireInvocationEnvironment(required, wrapper, script);
    }

    /**
     * A parent cannot change the compiler, wrapper or jobserver entries that registration authority depends on.
     * @throws IOException if the environment is rejected, as expected
     */
    @Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*RUSTC_WRAPPER")
    public void fixedEntryCannotFollowParent() throws IOException
    {
        Map<String, String> required = Map.of("RUSTC_WRAPPER", "/opt/wrapper");
        Map<String, String> changed = Map.of("RUSTC_WRAPPER", "/tmp/other");
        MacCargoRegistration.requireInvocationEnvironment(required, changed, changed);
    }

    /**
     * A wrapper that Cargo itself started must keep every invocation-owned entry.
     * @throws IOException if the environment is rejected, as expected
     */
    @Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*RUSTFLAGS")
    public void cargoWrapperMustKeepEveryEntry() throws IOException
    {
        Map<String, String> required = Map.of("RUSTFLAGS", "-C debuginfo=0");
        MacCargoRegistration.requireInvocationEnvironment(required, Map.of(), null);
    }

    /**
     * Builds one eighteen-field native image authority under the workload credentials.
     * @param pid           process identifier
     * @param version       image version
     * @param coalition     workload coalition identifier
     * @param parent        parent process identifier
     * @param parentVersion parent's image version when this image was created
     * @return the authority fields in the workload bridge's order
     */
    private static List<Long> authority(long pid, long version, long coalition, long parent, long parentVersion)
    {
        Map<Integer, Long> fields = new HashMap<>(Map.of(0, pid, 1, pid * 1000, 2, version, 3, coalition, 4, parent,
            13, 1L, 14, pid + 50, 15, parentVersion));
        for (int i = 7; i <= 9; ++i)
            fields.put(i, (long) UID);
        for (int i = 10; i <= 12; ++i)
            fields.put(i, (long) GID);
        Long[] result = new Long[18];
        for (int i = 0; i < result.length; ++i)
            result[i] = fields.getOrDefault(i, 0L);
        return List.of(result);
    }
}
