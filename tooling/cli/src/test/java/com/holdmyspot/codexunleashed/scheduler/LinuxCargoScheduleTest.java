package com.holdmyspot.codexunleashed.scheduler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;

/** Verifies resource-free preflight for the installed Linux command construction. */
public final class LinuxCargoScheduleTest
{
    /** Creates independent command construction checks. */
    public LinuxCargoScheduleTest() {}

    /** A short host-absolute namespace can represent all owned native socket names.
     * @throws IOException if the supported short namespace is rejected
     */
    @Test
    public void admitsShortSocketNamespace() throws IOException
    {
        LinuxCargoSchedule.requireSocketNamespace(Path.of(File.separator).toAbsolutePath());
    }

    /** An overlong namespace is rejected before any controller or workload is acquired. */
    @Test
    public void rejectsOverlongSocketNamespace()
    {
        requireException(IOException.class, () -> LinuxCargoSchedule.requireSocketNamespace(
            Path.of(File.separator).toAbsolutePath().resolve("x".repeat(108))));
    }
}
