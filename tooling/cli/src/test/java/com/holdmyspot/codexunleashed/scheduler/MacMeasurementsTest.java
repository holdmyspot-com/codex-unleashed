package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Tests complete-batch controller decisions using independently controlled native observation outcomes. */
public final class MacMeasurementsTest
{
    /** Creates tests with independent thread-owned native-source observations. */
    public MacMeasurementsTest() {}

    /**
     * Requires every exact live attempt before accepting a complete host observation.
     * @throws IOException if setup fails
     */
    @Test public void incompleteBatchCannotBeAccepted() throws IOException
    {
        Readings source = new Readings();
        MacMeasurements measurements = new MacMeasurements(source);
        measurements.readHost();
        requireException(IOException.class, measurements::acceptReadings);
        measurements.readHost();
        requireException(IOException.class, () -> measurements.readResidentBytes(running(1)));
        requireException(IOException.class, measurements::acceptReadings);
    }

    /**
     * Rejects an independently observed image change between residency and final acceptance.
     * @throws IOException if setup fails
     */
    @Test public void imageChangeRejectsWholeBatch() throws IOException
    {
        Readings source = new Readings();
        MacMeasurements measurements = new MacMeasurements(source);
        measurements.readHost();
        measurements.readResidentBytes(running(0));
        source.version = 2;
        ObservationRejected failure = requireException(ObservationRejected.class, measurements::acceptReadings);
        requireThat(failure.operation(), "rejectedBoundary").isEqualTo(ObservationRejected.Operation.ACCEPT, "expected");
        requireThat(failure.attempts(), "exactAttempt").isEqualTo(List.of(new ObservationRejected.Attempt("compile", 0)), "expected");
        requireException(IOException.class, measurements::acceptReadings);
    }

    /**
     * Preserves an unavailable native reading as a fatal failure and discards its pending batch.
     * @throws IOException if setup fails
     */
    @Test public void unavailableReadCannotGrantRetryOrLeaveAcceptableBatch() throws IOException
    {
        Readings source = new Readings();
        MacMeasurements measurements = new MacMeasurements(source);
        measurements.readHost();
        IOException unavailable = new IOException("Native task access unavailable");
        source.failure = unavailable;
        requireThat(requireException(IOException.class, () -> measurements.readResidentBytes(running(0))) == unavailable,
            "exactNativeFailure").isTrue();
        source.failure = null;
        requireException(IOException.class, measurements::acceptReadings);
    }

    /**
     * Correlates a native-confirmed cohort change without representing it as zero residency or completion.
     * @throws IOException if setup fails
     */
    @Test public void confirmedNativeChurnRejectsExactAttempt() throws IOException
    {
        Readings source = new Readings();
        MacMeasurements measurements = new MacMeasurements(source);
        measurements.readHost();
        source.failure = new MacWorkloadNative.MembershipChanged();
        ObservationRejected failure = requireException(ObservationRejected.class, () -> measurements.readResidentBytes(running(0)));
        requireThat(failure.reason(), "confirmedChurn").isEqualTo(ObservationRejected.Reason.MEMBERSHIP_CHANGED, "expected");
        requireThat(failure.operation(), "residentBoundary").isEqualTo(ObservationRejected.Operation.RESIDENT, "expected");
        requireException(IOException.class, measurements::acceptReadings);
    }

    /**
     * Rejects incomplete inventories at both boundaries, discards partial batches, and accepts only a fresh full batch.
     * @throws IOException if the fresh complete batch fails
     */
    @Test public void incompleteInventoryRejectsWithoutClaimingChurn() throws IOException
    {
        for (boolean atAcceptance : List.of(false, true))
        {
            Readings source = new Readings();
            MacMeasurements measurements = new MacMeasurements(source);
            measurements.readHost();
            if (atAcceptance) measurements.readResidentBytes(running(0));
            source.failure = new MacWorkloadNative.IncompleteInventory();
            ObservationRejected failure = requireException(ObservationRejected.class, () ->
            {
                if (atAcceptance) measurements.acceptReadings();
                else measurements.readResidentBytes(running(0));
            });
            requireThat(failure.reason(), "incompleteWithoutClaimingChurn").isEqualTo(
                ObservationRejected.Reason.INCOMPLETE_INVENTORY, "expected");
            requireThat(failure.operation(), "exactBoundary").isEqualTo(atAcceptance ?
                ObservationRejected.Operation.ACCEPT : ObservationRejected.Operation.RESIDENT, "expected");
            requireThat(failure.attempts(), "exactAttempt").isEqualTo(
                List.of(new ObservationRejected.Attempt("compile", 0)), "expected");
            source.failure = null;
            requireException(IOException.class, measurements::acceptReadings);
            measurements.readHost();
            requireThat(measurements.readResidentBytes(running(0)), "freshMeasuredBytes").isEqualTo(16L, "expected");
            measurements.acceptReadings();
        }
    }

    /**
     * Accepts directly measured zero bytes while treating registry changes as invalid observations.
     * @throws IOException if a valid batch fails
     */
    @Test public void measuredZeroAndRegistrationChangesRemainDistinct() throws IOException
    {
        Readings source = new Readings();
        MacMeasurements measurements = new MacMeasurements(source);
        source.bytes = 0;
        requireThat(measurements.readHost().memoryPressure(), "nativePressure").isTrue();
        requireThat(measurements.readResidentBytes(running(0)), "measuredZero").isEqualTo(0L, "expected");
        measurements.acceptReadings();
        measurements.readHost();
        measurements.readResidentBytes(running(0));
        source.generation++;
        requireException(ObservationRejected.class, measurements::acceptReadings);
        measurements.readHost();
        measurements.readResidentBytes(running(0));
        measurements.acceptReadings();
        requireException(IOException.class, measurements::acceptReadings);
    }

    /**
     * Creates the exact controller job represented by a test's native registration.
     * @param sequence live attempt ordinal
     * @return running action with its exact ordinal
     */
    private static Policy.Job running(long sequence)
    {
        return new Policy.Job("compile", Policy.State.RUNNING, 9, 100L, 0, sequence, null, 0);
    }

    /** Supplies boundary outcomes without claiming to emulate or verify macOS kernel identity or memory readings. */
    private static final class Readings implements MacMeasurements.Source
    {
        /** Current directly measured bytes. */
        private long bytes = 16;
        /** Native executable image version, changed independently between boundary calls. */
        private long version = 1;
        /** Current native-owner registry generation. */
        private long generation;
        /** Exact optional producer failure. */
        private IOException failure;
        /** Creates one test-owned producer. */
        private Readings() {}

        @Override public MacMeasurements.Observation read()
        {
            return new MacMeasurements.Observation(new MacMemory.Sample(100, 50, 10, 0, MacMemory.Pressure.WARNING), generation,
                Set.of(new MacMeasurements.Attempt("compile", 0)));
        }

        @Override public List<MacWorkloadNative.Resident> residents(MacMeasurements.Attempt attempt) throws IOException
        {
            if (failure != null) throw failure;
            return List.of(new MacWorkloadNative.Resident(List.of(10L, 11L, version, 12L, 13L, 14L, 15L, 16L, 17L,
                18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L, 26L), bytes));
        }

        @Override public long generation() { return generation; }
    }
}
