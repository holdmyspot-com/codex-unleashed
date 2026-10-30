package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the Windows adapter's host mapping and owned-attempt selection.
 */
public final class WindowsMeasurementsTest
{
    /**
     * A signaled retained in-Job member rejects a batch without claiming whole-Job completion.
     * @throws IOException if a complete modeled sample or native-owner cleanup fails
     */
    @Test
    public void rejectsTrustedMembershipChurn() throws IOException
    {
        var kernel = new Kernel(40);
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(1000, 500, 0, false));
        try (var process = launch(kernel);
             var registration = measurements.register(new Controller.Action("A", 0), 2, process))
        {
            kernel.signaledMember = true;
            var failure = requireException(IOException.class,
                () -> measurements.readResidentBytes(live("A", 0, 2, Policy.State.RUNNING)));
            requireThat(failure instanceof ObservationRejected, "trustedBatchRejected").isTrue();
            var rejected = (ObservationRejected) failure;
            requireThat(rejected.attempts(), "exactRegisteredAttempt")
                .isEqualTo(List.of(new ObservationRejected.Attempt("A", 2)));
            requireThat(process.poll().isEmpty(), "noInventedWholeJobCompletion").isTrue();
            kernel.signaledMember = false;
            requireThat(measurements.readResidentBytes(live("A", 0, 2, Policy.State.RUNNING)), "freshCompleteSample")
                .isEqualTo(40L);
            kernel.empty = true;
        }
    }
    /**
     * Creates adapter composition checks.
     */
    public WindowsMeasurementsTest()
    {
    }

    /**
     * Maps available bytes rather than total capacity, preserving actual swap and low-memory pressure.
     * @throws IOException if the complete controlled sample is rejected
     */
    @Test
    public void mapsInstantaneousHostSample() throws IOException
    {
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(4096, 1024, 512, true));

        requireThat(measurements.readHost(), "mappedHost")
            .isEqualTo(new Controller.Memory(1024, 512, true), "expected");
    }

    /**
     * Preserves a successfully measured zero and false instead of treating them as unavailable.
     * @throws IOException if a complete zero sample is rejected
     */
    @Test
    public void preservesSuccessfulZeroAndFalse() throws IOException
    {
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(4096, 0, 0, false));

        requireThat(measurements.readHost(), "successfulZeroHost")
            .isEqualTo(new Controller.Memory(0, 0, false), "expected");
    }

    /**
     * Prevents admission and history publication when host acquisition is absent or fails.
     */
    @Test
    public void rejectsUnavailableHostBeforeControl()
    {
        var failure = new IOException("Controlled missing Windows host counter");
        for (WindowsMeasurements.Host host : List.<WindowsMeasurements.Host>of(() -> null, () -> { throw failure; }))
        {
            var controls = new ArrayList<Policy.Decision>();
            var controller = new Controller(new Policy.Budget(0, 1), new WindowsMeasurements(host), controls::add);

            requireException(IOException.class, () -> controller.cycle(List.of(ready("A", 0, 1)), 0));

            requireThat(controls.isEmpty(), "noControlAfterMissingHost").isTrue();
            requireThat(controller.memoryDemands().isEmpty(), "noHistoryAfterMissingHost").isTrue();
        }
    }

    /**
     * Selects distinct owners by complete action identity and current attempt, retaining STOPPING observations.
     * @throws IOException if a matching registered owner cannot be read
     */
    @Test
    public void routesCurrentAttemptsAndRetainsStopping() throws IOException
    {
        var firstKernel = new Kernel(40);
        var secondKernel = new Kernel(90);
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(1000, 500, 0, false));
        try (var first = launch(firstKernel); var second = launch(secondKernel);
             var firstRegistration = measurements.register(new Controller.Action("A", 0), 2, first);
             var secondRegistration = measurements.register(new Controller.Action("B", 1), 3, second))
        {
            requireThat(measurements.readResidentBytes(live("A", 0, 2, Policy.State.RUNNING)), "firstOwnerBytes")
                .isEqualTo(40L, "expected");
            requireThat(measurements.readResidentBytes(live("B", 1, 3, Policy.State.BLOCKED)), "secondOwnerBytes")
                .isEqualTo(90L, "expected");
            requireThat(measurements.readResidentBytes(live("A", 0, 2, Policy.State.STOPPING)), "stoppingOwnerBytes")
                .isEqualTo(40L, "expected");
            requireException(IOException.class, () -> measurements.readResidentBytes(live("A", 0, 1, Policy.State.RUNNING)));
            requireException(IOException.class, () -> measurements.readResidentBytes(live("A", 9, 2, Policy.State.RUNNING)));
            requireException(IOException.class, () -> measurements.readResidentBytes(live("C", 2, 4, Policy.State.RUNNING)));
            requireException(IOException.class, () -> measurements.readResidentBytes(ready("A", 0, 1)));
            requireException(IllegalArgumentException.class,
                () -> measurements.register(new Controller.Action("C", 2), 4, first));
            firstKernel.empty = true;
            secondKernel.empty = true;
            requireThat(first.poll().isPresent(), "firstWholeTreeCompletion").isTrue();
            requireThat(second.poll().isPresent(), "secondWholeTreeCompletion").isTrue();
        }
    }

    /**
     * Removes a reaped attempt without closing its owner and rejects stale replacement registrations and snapshots.
     * @throws IOException if controlled owners cannot be constructed or sampled
     */
    @Test
    public void retiresRegistrationWithoutClosingBorrowedOwner() throws IOException
    {
        var oldKernel = new Kernel(20);
        var newKernel = new Kernel(60);
        var action = new Controller.Action("A", 0);
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(1000, 500, 0, false));
        try (var oldOwner = launch(oldKernel); var newOwner = launch(newKernel))
        {
            var registration = measurements.register(action, 2, oldOwner);
            requireException(IllegalArgumentException.class, () -> measurements.register(action, 3, newOwner));
            oldKernel.empty = true;
            requireThat(oldOwner.poll().isPresent(), "wholeOldTreeCompleted").isTrue();
            registration.close();
            registration.close();

            requireThat(oldKernel.arena.scope().isAlive(), "borrowedNativeOwnerRemainsOpen").isTrue();
            requireThat(oldKernel.terminations, "noAdapterTermination").isEqualTo(0, "expected");
            requireThat(oldOwner.readResidentBytes(), "borrowedOwnerStillReadable").isEqualTo(0L, "expected");
            requireException(IOException.class, () -> measurements.readResidentBytes(live("A", 0, 2, Policy.State.RUNNING)));
            requireException(IllegalArgumentException.class, () -> measurements.register(action, 2, newOwner));
            requireException(IllegalArgumentException.class,
                () -> measurements.register(new Controller.Action("A", 1), 3, newOwner));
            try (var replacement = measurements.register(action, 3, newOwner))
            {
                requireException(IOException.class,
                    () -> measurements.readResidentBytes(live("A", 0, 2, Policy.State.RUNNING)));
                requireThat(measurements.readResidentBytes(live("A", 0, 3, Policy.State.RUNNING)), "replacementBytes")
                    .isEqualTo(60L, "expected");
                newKernel.empty = true;
                requireThat(newOwner.poll().isPresent(), "wholeReplacementTreeCompleted").isTrue();
            }
        }
    }

    /**
     * Converts a closed native owner's rejection to unavailable measurements rather than successful zero residency.
     * @throws IOException if the controlled owner cannot be constructed or closed
     */
    @Test
    public void rejectsClosedBorrowedOwner() throws IOException
    {
        var kernel = new Kernel(10);
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(1000, 500, 0, false));
        try (var owner = launch(kernel);
             var registration = measurements.register(new Controller.Action("A", 0), 0, owner))
        {
            kernel.empty = true;
            owner.close();

            var failure = requireException(IOException.class,
                () -> measurements.readResidentBytes(live("A", 0, 0, Policy.State.STOPPING)));

            requireThat(failure.getCause(), "closedOwnerCause").isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Leaves Controller demand and swap histories unchanged when a later owned tree cannot be sampled.
     * @throws IOException if complete controlled batches fail
     */
    @Test
    public void incompleteTreeBatchPreservesHistoriesAndIssuesNoControl() throws IOException
    {
        var host = new Host();
        var measurements = new WindowsMeasurements(host);
        var controls = new ArrayList<Policy.Decision>();
        var controller = new Controller(new Policy.Budget(0, 2), measurements, controls::add);
        var firstKernel = new Kernel(10);
        var secondKernel = new Kernel(20);
        try (var first = launch(firstKernel); var second = launch(secondKernel);
             var firstRegistration = measurements.register(new Controller.Action("A", 0), 0, first);
             var secondRegistration = measurements.register(new Controller.Action("B", 1), 1, second))
        {
            var jobs = List.of(live("A", 0, 0, Policy.State.RUNNING), live("B", 1, 1, Policy.State.RUNNING));
            controller.cycle(jobs, 0);
            var acceptedDemands = controller.memoryDemands();
            host.sample = new WindowsMemory.Sample(1000, 500, 100, true);
            firstKernel.bytes = 80;
            secondKernel.unavailable = true;

            requireException(IOException.class, () -> controller.cycle(jobs, 0));

            requireThat(controller.memoryDemands(), "historyBeforeIncompleteBatch")
                .isEqualTo(acceptedDemands, "expected");
            requireThat(controls.isEmpty(), "noControlAfterIncompleteTreeBatch").isTrue();
            host.sample = new WindowsMemory.Sample(1000, 500, 50, false);
            requireThat(controller.cycle(List.of(ready("C", 2, 10)), 0).control(), "growthFromLastAcceptedSwap")
                .isEqualTo(Policy.Control.WAIT, "expected");
            requireThat(controls.isEmpty(), "noAdmissionDuringRetainedSwapGrowth").isTrue();
            secondKernel.unavailable = false;
            firstKernel.empty = true;
            secondKernel.empty = true;
        }
    }

    /**
     * Admits an action from already-available host memory without subtracting the running tree's RSS again.
     * @throws IOException if complete controlled measurements fail
     */
    @Test
    public void doesNotSubtractCurrentResidencyFromAvailableBytes() throws IOException
    {
        var measurements = new WindowsMeasurements(() -> new WindowsMemory.Sample(1000, 100, 0, false));
        var controls = new ArrayList<Policy.Decision>();
        var controller = new Controller(new Policy.Budget(0, 2), measurements, controls::add);
        var kernel = new Kernel(80);
        try (var owner = launch(kernel);
             var registration = measurements.register(new Controller.Action("A", 0), 0, owner))
        {
            var running = new Policy.Job("A", Policy.State.RUNNING, 0, 80L, 0, 0L, null, 0);

            var decision = controller.cycle(List.of(running, ready("B", 1, 100)), 0);

            requireThat(decision.control(), "admissionUsingAvailablePhysicalBytes")
                .isEqualTo(Policy.Control.START, "expected");
            requireThat(decision.jobName(), "admittedAction").isEqualTo("B", "expected");
            requireThat(controls, "oneAdmissionControl").isEqualTo(List.of(decision), "expected");
            kernel.empty = true;
        }
    }

    /**
     * Creates a live job snapshot with an exact attempt identity.
     * @param name stable action name
     * @param position original submission position
     * @param startedAt current attempt sequence
     * @param state live lifecycle state
     * @return a live snapshot with a known estimate
     */
    private static Policy.Job live(String name, long position, long startedAt, Policy.State state)
    {
        Long blockedAt = state == Policy.State.BLOCKED ? 0L : null;
        return new Policy.Job(name, state, position, 100L, 0, startedAt, blockedAt, 0);
    }

    /**
     * Creates an action waiting for its first process attempt.
     * @param name stable action name
     * @param position original submission position
     * @param peakBytes estimated whole-tree peak bytes
     * @return a ready action
     */
    private static Policy.Job ready(String name, long position, long peakBytes)
    {
        return new Policy.Job(name, Policy.State.READY, position, peakBytes, 0, null, null, 0);
    }

    /**
     * Creates a real Java process owner through its existing controlled native-call boundary.
     * @param kernel invocation-owned model of Win32 replies
     * @return the owner whose lifetime the caller closes
     * @throws IOException if modeled launch fails
     */
    private static WindowsProcess launch(Kernel kernel) throws IOException
    {
        return WindowsProcess.launch(List.of("compiler"), Path.of("."), Map.of(),
            MemorySegment.ofAddress(31), MemorySegment.ofAddress(32), MemorySegment.ofAddress(33), kernel);
    }

    /**
     * Supplies complete WindowsMemory samples at the adapter's host boundary; native acquisition is established separately.
     */
    private static final class Host implements WindowsMeasurements.Host
    {
        private WindowsMemory.Sample sample = new WindowsMemory.Sample(1000, 500, 0, false);

        /**
         * Creates independent controlled host observations.
         */
        private Host()
        {
        }

        @Override
        public WindowsMemory.Sample read()
        {
            return sample;
        }
    }

    /**
     * Models the Win32 ABI replies consumed by WindowsProcess, following its accepted residency and launch fixtures.
     * This controls Java owner behavior only; it does not establish native Windows containment or memory readings.
     */
    private static final class Kernel extends WindowsProcess.Kernel
    {
        /** Current modeled working-set bytes of the one job member. */
        private long bytes;
        /** Indicates whether the modeled whole job has completed. */
        private boolean empty = false;
        /** Indicates whether the modeled job accounting call is unavailable. */
        private boolean unavailable = false;
        /** Retained member has exited while the modeled Job remains active. */
        private boolean signaledMember;
        /** Native termination calls made by the Java process owner. */
        private int terminations = 0;
        /** Number of standard-handle duplicates allocated for this launch. */
        private int duplicates = 0;

        /**
         * Allocates isolated native-layout storage without loading Windows libraries.
         * @param bytes modeled current working-set bytes
         */
        private Kernel(long bytes)
        {
            super(Arena.ofConfined());
            this.bytes = bytes;
        }

        @Override
        Object call(String name, Object... arguments) throws IOException
        {
            return switch (name)
            {
                case "CreateJobObjectW" -> MemorySegment.ofAddress(10);
                case "GetCurrentProcess" -> MemorySegment.ofAddress(-1);
                case "DuplicateHandle" -> {
                    ((MemorySegment) arguments[3]).set(ValueLayout.ADDRESS, 0,
                        MemorySegment.ofAddress(100 + ++duplicates));
                    yield 1;
                }
                case "InitializeProcThreadAttributeList" -> {
                    ((MemorySegment) arguments[3]).set(ValueLayout.JAVA_LONG, 0, 64);
                    yield ((MemorySegment) arguments[0]).address() == 0 ? 0 : 1;
                }
                case "SetInformationJobObject", "UpdateProcThreadAttribute", "CloseHandle" -> 1;
                case "DeleteProcThreadAttributeList" -> null;
                case "CreateProcessW" -> {
                    var info = (MemorySegment) arguments[9];
                    info.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(20));
                    info.set(ValueLayout.ADDRESS, 8, MemorySegment.ofAddress(21));
                    info.set(ValueLayout.JAVA_INT, 16, 42);
                    yield 1;
                }
                case "QueryInformationJobObject" -> queryJob(arguments);
                case "OpenProcess" -> MemorySegment.ofAddress(1042);
                case "IsProcessInJob" -> {
                    ((MemorySegment) arguments[2]).set(ValueLayout.JAVA_INT, 0, 1);
                    yield 1;
                }
                case "WaitForSingleObject" -> ((MemorySegment) arguments[0]).address() == 1042 && !signaledMember ? 258 : 0;
                case "K32GetProcessMemoryInfo" -> {
                    ((MemorySegment) arguments[1]).set(ValueLayout.JAVA_LONG, 16, bytes);
                    yield 1;
                }
                case "TerminateJobObject" -> {
                    ++terminations;
                    empty = true;
                    yield 1;
                }
                case "GetExitCodeProcess" -> {
                    ((MemorySegment) arguments[1]).set(ValueLayout.JAVA_INT, 0, 0);
                    yield 1;
                }
                default -> throw new AssertionError("Unmodeled Win32 request: " + name);
            };
        }

        /**
         * Supplies complete accounting and membership replies, or a controlled acquisition failure.
         * @param arguments QueryInformationJobObject arguments
         * @return the modeled Win32 BOOL
         */
        private int queryJob(Object[] arguments)
        {
            if (unavailable)
                return 0;
            var output = (MemorySegment) arguments[2];
            var length = (MemorySegment) arguments[4];
            int active = empty ? 0 : 1;
            if ((int) arguments[1] == 1)
            {
                output.set(ValueLayout.JAVA_INT, 36, 1);
                output.set(ValueLayout.JAVA_INT, 40, active);
                if (length.address() != 0)
                    length.set(ValueLayout.JAVA_INT, 0, 48);
            }
            else
            {
                output.set(ValueLayout.JAVA_INT, 0, active);
                output.set(ValueLayout.JAVA_INT, 4, active);
                if (active > 0)
                    output.set(ValueLayout.JAVA_LONG, 8, 42);
                length.set(ValueLayout.JAVA_INT, 0, 8 + (active * 8));
            }
            return 1;
        }

        @Override
        long lastError()
        {
            return 122;
        }
    }
}
