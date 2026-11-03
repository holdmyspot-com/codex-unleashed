package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelMemoryEstimates;
import com.holdmyspot.codexunleashed.scheduler.BazelMemoryHistory;
import com.holdmyspot.codexunleashed.scheduler.Controller;
import com.holdmyspot.codexunleashed.scheduler.Policy;
import com.holdmyspot.codexunleashed.scheduler.SchedulerCoordinator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the queue estimate and accepted complete-batch training boundary.
 */
public final class BazelMemoryEstimatesTest
{
    /**
     * Creates independent cases with no shared measurement or history state.
     */
    public BazelMemoryEstimatesTest() {}

    /**
     * Cold work remains unknown and successful accepted observations are reused under a different logical name.
     * Failed attempts and unaccepted samples do not train history.
     * @throws IOException if fixture operations or measurement forwarding fails
     */
    @Test
    public void onlyAcceptedSuccessfulAttemptsTrainContentIdentity() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-estimates-");
        try
        {
            String identity = "c".repeat(64);
            BazelMemoryHistory history = new BazelMemoryHistory(root);
            BazelMemoryEstimates estimates = new BazelMemoryEstimates(new FixedMeasurements(), history);
            SchedulerCoordinator.Submission cold = estimates.register("invocation-1/action-1", identity);
            requireThat(cold.name(), "hostLogicalName").isEqualTo("invocation-1/action-1");
            requireThat(cold.peakBytes() == null, "coldPeakUnknown").isTrue();
            estimates.readHost();
            estimates.readResidentBytes(job(cold.name(), 1));
            estimates.completed(cold.name(), 1, true);
            requireThat(history.estimate(identity) == null, "unacceptedSampleExcluded").isTrue();
            estimates.readHost();
            estimates.readResidentBytes(job(cold.name(), 2));
            estimates.acceptReadings();
            estimates.completed(cold.name(), 2, false);
            requireThat(history.estimate(identity) == null, "failedAttemptExcluded").isTrue();
            estimates.readHost();
            estimates.readResidentBytes(job(cold.name(), 3));
            estimates.acceptReadings();
            estimates.completed(cold.name(), 3, true);
            requireThat(estimates.register("invocation-2/action-7", identity).peakBytes(), "warmContentEstimate")
                .isEqualTo(320L);
            requireThat(estimates.register("invocation-2/action-8", "d".repeat(64)).peakBytes() == null,
                "otherContentUnknown").isTrue();
        }
        finally { remove(root); }
    }

    /**
     * Produces a live attempt identity for a complete sampling batch.
     * @param name host-generated queue identity
     * @param sequence queue-wide attempt number
     * @return live job snapshot
     */
    private static Policy.Job job(String name, long sequence)
    {
        return new Policy.Job(name, Policy.State.RUNNING, 0, null, 0, sequence, null, 0);
    }

    /**
     * Supplies valid complete host/tree observations through the real measurements contract.
     */
    private static final class FixedMeasurements implements Controller.Measurements
    {
        /**
         * Creates a stateless complete-batch observation source.
         */
        private FixedMeasurements() {}

        @Override public Controller.Memory readHost() { return new Controller.Memory(1000, 0, false); }
        @Override public long readResidentBytes(Policy.Job job) { return 320; }
    }

    /**
     * Deletes only the test-owned fixture tree.
     * @param root owned fixture directory
     * @throws IOException if fixture cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
