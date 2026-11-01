package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises isolated Bazel prediction persistence and conservative history failure behavior.
 */
public final class BazelMemoryHistoryTest
{
    /**
     * Creates test cases with independently owned history directories.
     */
    public BazelMemoryHistoryTest() {}

    /**
     * A later instance reuses the greatest measured demand, without reading Cargo-domain samples.
     * @throws IOException if fixture operations or publication fails
     */
    @Test
    public void reusesBazelMaximumWithoutCargoCollision() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-history-");
        try
        {
            String identity = "a".repeat(64);
            new CargoMemoryHistory(root).record(identity, 999);
            BazelMemoryHistory history = new BazelMemoryHistory(root);
            requireThat(history.estimate(identity) == null, "cargoHistoryExcluded").isTrue();
            history.record(identity, 120);
            history.record(identity, 180);
            requireThat(new BazelMemoryHistory(root).estimate(identity), "reusedMaximum").isEqualTo(180L);
            requireThat(new CargoMemoryHistory(root).estimate(identity), "cargoHistoryUnchanged").isEqualTo(999L);
        }
        finally { remove(root); }
    }

    /**
     * Corrupt matching history yields unknown demand.
     * @throws IOException if fixture operations fail
     */
    @Test
    public void corruptHistoryIsUnknown() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-corrupt-");
        try
        {
            String identity = "b".repeat(64);
            BazelMemoryHistory history = new BazelMemoryHistory(root);
            history.record(identity, 42);
            try (Stream<Path> entries = Files.list(root))
            {
                Files.writeString(entries.findFirst().orElseThrow(), "corrupt");
            }
            requireThat(history.estimate(identity) == null, "corruptHistoryUnknown").isTrue();
        }
        finally { remove(root); }
    }

    /**
     * Removes only one test's owned directory.
     * @param root owned fixture directory
     * @throws IOException if cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
