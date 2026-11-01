package com.holdmyspot.codexunleashed.distribution;

import com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend.NativeTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises producer rejection before acquisition or native compilation can create output.
 */
public final class BazelNativeBackendMainTest
{
    /**
     * Creates an instance without shared mutable fixtures.
     */
    public BazelNativeBackendMainTest() {}

    /**
     * Rejects missing arguments and an unknown resume option at the public producer entry point.
     */
    @Test public void rejectsInvalidArgumentShape()
    {
        for (String[] arguments : new String[][] { {}, { "target" }, { "target", "build" },
            { "target", "build", "staging", "--unknown" }, { "target", "build", "staging", "--resume", "extra" } })
            requireException(IllegalArgumentException.class, () -> BazelNativeBackendMain.main(arguments));
    }

    /**
     * Rejects a requested target that differs from the current native host before touching the owned roots.
     * @throws IOException if the test-local root cannot be created or removed
     */
    @Test public void rejectsForeignTargetBeforeCreatingOutputs() throws IOException
    {
        Path root = Files.createTempDirectory("backend-producer-target-");
        try
        {
            Path build = root.resolve("build");
            Path staging = root.resolve("staging");
            requireException(UnsupportedOperationException.class, () -> BazelNativeBackendMain.main(
                new String[] { "unsupported-target", build.toString(), staging.toString() }));
            requireThat(Files.exists(build), "buildCreated").isFalse();
            requireThat(Files.exists(staging), "stagingCreated").isFalse();
        }
        finally { PathUtils.deleteDirectory(root); }
    }

    /**
     * Rejects overlapping build and product roots before either root is created.
     * @throws IOException if the test-local root cannot be created or removed
     */
    @Test public void rejectsOverlappingRootsBeforeCreatingOutputs() throws IOException
    {
        Path root = Files.createTempDirectory("backend-producer-overlap-");
        try
        {
            String target = NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch")).triple();
            Path build = root.resolve("build");
            Path staging = build.resolve("staging");
            requireException(IllegalArgumentException.class, () -> BazelNativeBackendMain.main(
                new String[] { target, build.toString(), staging.toString() }));
            requireThat(Files.exists(build), "buildCreated").isFalse();
        }
        finally { PathUtils.deleteDirectory(root); }
    }

    /**
     * Rejects resume without its original source identity receipt and staging directory.
     * @throws IOException if the test-local root cannot be created or removed
     */
    @Test public void rejectsResumeWithoutOriginalReceipt() throws IOException
    {
        Path root = Files.createTempDirectory("backend-producer-resume-");
        try
        {
            String target = NativeTarget.forHost(System.getProperty("os.name"), System.getProperty("os.arch")).triple();
            Path build = root.resolve("build");
            Path staging = root.resolve("staging");
            requireException(IOException.class, () -> BazelNativeBackendMain.main(
                new String[] { target, build.toString(), staging.toString(), "--resume" }));
            requireThat(Files.exists(build), "buildCreated").isFalse();
            requireThat(Files.exists(staging), "stagingCreated").isFalse();
        }
        finally { PathUtils.deleteDirectory(root); }
    }
}
