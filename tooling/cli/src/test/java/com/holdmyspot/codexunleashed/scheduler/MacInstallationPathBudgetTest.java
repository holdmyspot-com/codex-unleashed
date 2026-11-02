package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ValidateReleaseSchedulerInstallation;
import java.io.IOException;
import java.nio.file.Path;
import org.testng.annotations.Test;

/** Checks that the provisioned macOS installation leaves room for every native Cargo state and socket path. */
public final class MacInstallationPathBudgetTest
{
    /** Creates a resource-free test instance. */
    public MacInstallationPathBudgetTest() {}

    /**
     * The scratch directory of the longest installation name that provisioning creates must fit the Darwin path budget.
     * @throws IOException if the provisioned scratch namespace cannot hold the generated compiler paths
     */
    @Test
    public void provisionedScratchFitsNativePathBudget() throws IOException
    {
        TestRequirements.requirePosixFileSystem();
        Path root = Path.of("/Library", ValidateReleaseSchedulerInstallation.ROOT_PREFIX + "X".repeat(8));
        MacCargoSchedule.requirePathBudget(root.resolve("scratch"));
    }
}
