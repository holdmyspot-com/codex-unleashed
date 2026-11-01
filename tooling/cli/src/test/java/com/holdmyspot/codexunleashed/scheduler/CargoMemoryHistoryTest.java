package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks completed compiler profile reuse through the production history boundary.
 */
public final class CargoMemoryHistoryTest
{
    /**
     * Creates independent per-test history checks.
     */
    public CargoMemoryHistoryTest() {}

    /**
     * A later reader uses the greatest successful profile rather than its own invocation identity.
     * @throws IOException if owned fixture setup or cleanup fails
     */
    @Test
    public void reusesMaximumCompletedObservation() throws IOException
    {
        Path directory = Files.createTempDirectory("cargo-history-");
        try
        {
            String identity = "a".repeat(64);
            CargoMemoryHistory history = new CargoMemoryHistory(directory);
            requireThat(history.estimate(identity) == null, "coldDemandIsUnknown").isTrue();
            history.record(identity, 180);
            history.record(identity, 120);
            requireThat(new CargoMemoryHistory(directory).estimate(identity), "reusedSampledDemand").isEqualTo(180L);
        }
        finally { remove(directory); }
    }

    /**
     * Concurrent successful publishers retain the largest observation, and corruption restores unknown demand.
     * @throws IOException if owned fixture setup, publication or cleanup fails
     * @throws InterruptedException if a publisher is interrupted
     * @throws ExecutionException if a publisher fails
     */
    @Test
    public void concurrentPublicationAndCorruption() throws IOException, InterruptedException, ExecutionException
    {
        Path directory = Files.createTempDirectory("cargo-history-parallel-");
        try
        {
            String identity = "b".repeat(64);
            try (ExecutorService publishers = Executors.newVirtualThreadPerTaskExecutor())
            {
                List<Future<?>> results = new ArrayList<>();
                for (long observed = 1; observed <= 16; observed++)
                {
                    long bytes = observed;
                    results.add(publishers.submit(() ->
                    {
                        new CargoMemoryHistory(directory).record(identity, bytes);
                        return null;
                    }));
                }
                for (Future<?> result : results) result.get();
            }
            requireThat(new CargoMemoryHistory(directory).estimate(identity), "concurrentMaximum").isEqualTo(16L);
            Path record;
            try (Stream<Path> files = Files.list(directory)) { record = files.findFirst().orElseThrow(); }
            Files.writeString(record, "corrupt");
            requireThat(new CargoMemoryHistory(directory).estimate(identity) == null, "corruptionRestoresUnknown").isTrue();
        }
        finally { remove(directory); }
    }

    /**
     * Removes only the fixture root owned by this test.
     * @param directory owned root
     * @throws IOException if fixture cleanup fails
     */
    private static void remove(Path directory) throws IOException
    {
        try (Stream<Path> files = Files.walk(directory))
        {
            List<Path> paths = files.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.delete(path);
        }
    }
}
