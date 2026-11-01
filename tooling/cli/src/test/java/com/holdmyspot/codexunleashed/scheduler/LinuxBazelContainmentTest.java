package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests host admission and cleanup decisions with independent modeled proc and cgroup observations.
 * These tests run the production identity and containment policies; they do not claim actual native migration,
 * kernel process termination, namespace mapping or memory-reader registration. Each test owns all mutable fixtures.
 */
public final class LinuxBazelContainmentTest
{
    /**
     * Creates the resource-free focused suite.
     */
    public LinuxBazelContainmentTest() {}

    /**
     * Moves only the authenticated child, retains its original owner outside, and exposes host memory-reader inputs.
     * @throws IOException if modeled authentication or admission unexpectedly fails
     */
    @Test
    public void admitsOnlyAuthenticatedChild() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            containment.requireContained();
            requireThat(fixture.group.moved, "migratedPids").isEqualTo(List.of(300L), "expected");
            requireThat(containment.rootPid(), "hostRootPid").isEqualTo(300L, "expected");
            requireThat(containment.directory(), "retainedGroup").isEqualTo(fixture.group.directory(), "expected");
            requireThat(containment.observeExecutionImpossible(), "zeroBeforeTermination").isFalse();
        }
        requireThat(fixture.group.closeCalls, "closeCalls").isEqualTo(1, "expected");
    }

    /**
     * Uses the already reserved mapping group without allocating a replacement during blocked-child admission.
     * @throws IOException if controlled authentication or migration fails
     */
    @Test
    public void admitsIntoExactPreviouslyReservedGroup() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = LinuxBazelContainment.admitReserved(fixture.identity,
            fixture.owner, fixture.child, fixture.execution, fixture.group, Duration.ofSeconds(1)))
        {
            requireThat(containment.directory(), "reservedGroup").isEqualTo(fixture.group.directory(), "expected");
            requireThat(fixture.allocations, "replacementAllocations").isEqualTo(0, "expected");
            requireThat(fixture.group.moved, "migrations").isEqualTo(List.of(300L), "expected");
        }
    }

    /**
     * Retains the supervisor-pinned group when migration fails, so native unmapping can precede storage removal.
     * @throws IOException if controlled initial authentication fails
     */
    @Test
    public void retainsReservedGroupUntilNamespaceCleanup() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.group.publishMembership = false;
        LinuxBazelContainment.AcquisitionFailure failure = requireException(
            LinuxBazelContainment.AcquisitionFailure.class, () -> LinuxBazelContainment.admitReserved(
                fixture.identity, fixture.owner, fixture.child, fixture.execution, fixture.group, Duration.ofSeconds(1)));
        requireThat(fixture.group.closeCalls, "prematureGroupRemoval").isEqualTo(0, "expected");
        // Represents the host releasing its native mapping after independent zero and helper closure.
        failure.owner().close();
        requireThat(fixture.group.closeCalls, "removalAfterNativeCleanup").isEqualTo(1, "expected");
    }

    /**
     * Rejects a changed gate image before allocating any group or moving any process.
     * @throws IOException if initial modeled authentication fails
     */
    @Test
    public void rejectsChangedGateBeforeAllocation() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.changedImage = true;
        requireException(IOException.class, fixture::admit);
        requireThat(fixture.allocations, "groupAllocations").isEqualTo(0, "expected");
        requireThat(fixture.group.moved.size(), "migrations").isEqualTo(0, "expected");
    }

    /**
     * Rejects a child birth change during migration and closes the allocated group.
     * @throws IOException if initial modeled authentication fails
     */
    @Test
    public void rejectsBirthChangeDuringMigration() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.group.afterMove = () -> fixture.childBirth++;
        requireException(IOException.class, fixture::admit);
        requireThat(fixture.group.closeCalls, "cleanupCalls").isEqualTo(1, "expected");
    }

    /**
     * Rejects a migration that does not produce actual observed membership and cleans its group.
     * @throws IOException if initial modeled authentication fails
     */
    @Test
    public void requiresObservedMembershipAfterMigration() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.group.publishMembership = false;
        requireException(IOException.class, fixture::admit);
        requireThat(fixture.group.closeCalls, "cleanupCalls").isEqualTo(1, "expected");
    }

    /**
     * Retains the cleanup owner when admission and immediate cleanup both fail, allowing another close attempt.
     * @throws IOException if initial modeled authentication fails
     */
    @Test
    public void preservesOwnerWhenAdmissionCleanupFails() throws IOException
    {
        Fixture fixture = new Fixture();
        fixture.group.publishMembership = false;
        fixture.group.closeFailures = 1;
        LinuxBazelContainment.AcquisitionFailure failure =
            requireException(LinuxBazelContainment.AcquisitionFailure.class, fixture::admit);
        requireThat(failure.getCause().getSuppressed().length, "cleanupDiagnostics").isEqualTo(1, "expected");
        failure.owner().close();
        requireThat(failure.owner().observeExecutionImpossible(), "verifiedZeroAfterClose").isTrue();
        requireThat(fixture.group.closeCalls, "retryCalls").isEqualTo(2, "expected");
    }

    /**
     * Keeps serialized failure diagnostics from granting live cleanup authority in another object graph.
     * @throws IOException if diagnostic serialization or modeled authentication fails
     * @throws ClassNotFoundException if the platform cannot restore the serialized diagnostic class
     */
    @Test
    public void serializedDiagnosticsCannotTransferCleanupAuthority() throws IOException, ClassNotFoundException
    {
        Fixture fixture = new Fixture();
        fixture.group.publishMembership = false;
        fixture.group.closeFailures = 1;
        LinuxBazelContainment.AcquisitionFailure failure =
            requireException(LinuxBazelContainment.AcquisitionFailure.class, fixture::admit);
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(failure); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())))
            {
                LinuxBazelContainment.AcquisitionFailure diagnostics =
                    (LinuxBazelContainment.AcquisitionFailure) input.readObject();
                requireException(IllegalStateException.class, diagnostics::owner);
            }
        }
        finally { failure.owner().close(); }
    }

    /**
     * Withholds kill and removal if the original native wait owner unexpectedly enters the attempt group.
     * @throws IOException if modeled admission unexpectedly fails
     */
    @Test
    public void preservesNativeWaitOwnerOutsideTermination() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelContainment containment = fixture.admit();
        try
        {
            fixture.group.ownerInside = true;
            requireException(IOException.class, containment::requireContained);
            requireException(IOException.class, containment::requestTermination);
            requireException(IOException.class, containment::close);
            requireThat(fixture.group.terminationRequests, "killRequests").isEqualTo(0, "expected");
            requireThat(fixture.group.closeCalls, "removalAttempts").isEqualTo(0, "expected");
        }
        finally
        {
            fixture.group.ownerInside = false;
            containment.close();
        }
    }

    /**
     * Accepts retained birth and namespace identity across a separately authorized image transition.
     * @throws IOException if modeled admission or retained-identity verification fails
     */
    @Test
    public void permitsExecWithoutTrustingChangedBirth() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            fixture.changedImage = true;
            containment.requireContained();
            requireException(IOException.class,
                () -> fixture.identity.requireHeld(fixture.owner, fixture.child, fixture.execution));
            fixture.childBirth++;
            requireException(IOException.class, containment::requireContained);
        }
    }

    /**
     * Rejects membership loss after admission without treating root disappearance as a subtree-zero observation.
     * @throws IOException if modeled admission unexpectedly fails
     */
    @Test
    public void rejectsMembershipLossAndRetainsNonzeroBarrier() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            fixture.group.member = false;
            requireException(IOException.class, containment::requireContained);
            requireThat(containment.observeExecutionImpossible(), "nonzeroDescendantBarrier").isFalse();
            fixture.group.populationFailure = true;
            requireException(IOException.class, containment::observeExecutionImpossible);
            fixture.group.populationFailure = false;
        }
    }

    /**
     * Publishes subtree kill without manufacturing zero, then retains genuine observed zero through group removal.
     * @throws IOException if modeled admission or lifecycle operation fails
     */
    @Test
    public void separatesKillRequestFromObservedZero() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelContainment containment = fixture.admit();
        try
        {
            containment.requestTermination();
            requireThat(fixture.group.terminationRequests, "killRequests").isEqualTo(1, "expected");
            requireThat(containment.observeExecutionImpossible(), "zeroBeforeKernelObservation").isFalse();
            fixture.group.populated = false;
            requireThat(containment.observeExecutionImpossible(), "actualZero").isTrue();
        }
        finally { containment.close(); }
        requireThat(containment.observeExecutionImpossible(), "retainedZero").isTrue();
        containment.close();
        requireThat(fixture.group.closeCalls, "idempotentCloseCalls").isEqualTo(1, "expected");
    }

    /**
     * Retains close ownership after a failed removal and observes zero only after the retry succeeds.
     * @throws IOException if modeled admission unexpectedly fails
     */
    @Test
    public void retriesFailedCloseWithoutLosingOwner() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelContainment containment = fixture.admit();
        fixture.group.closeFailures = 1;
        requireException(IOException.class, containment::close);
        requireThat(containment.observeExecutionImpossible(), "zeroAfterFailedClose").isFalse();
        containment.close();
        requireThat(containment.observeExecutionImpossible(), "zeroAfterSuccessfulRetry").isTrue();
        requireException(IllegalStateException.class, containment::requireContained);
        requireException(IllegalStateException.class, containment::requestTermination);
    }

    /**
     * Models proc facts from the Linux proc contract and retained host/sandbox PID mapping evidence.
     */
    private static final class Fixture implements LinuxBazelIdentity.Observer
    {
        /** Complete trusted workload execution used to authenticate the prepared gate. */
        private final LinuxBazelIdentity.Execution execution = new LinuxBazelIdentity.Execution(
            new LinuxBazelIdentity.Image(Path.of("/native/gate"), "a".repeat(64)), List.of("/native/gate"),
            Map.of("VALUE", "original"), Path.of("/work"), 1000, 1000);
        /** Real production authentication policy reading this test's independent controlled observations. */
        private final LinuxBazelIdentity identity = new LinuxBazelIdentity(
            new LinuxBazelIdentity.Parent(100, 10, execution.image()), execution, this);
        /** Private owner receipt produced by the production authentication decision. */
        private final LinuxBazelIdentity.Owner owner;
        /** Private child receipt produced by the production mapping decision. */
        private final LinuxBazelIdentity.Child child;
        /** Test-owned cgroup interaction boundary. */
        private final FakeGroup group = new FakeGroup();
        /** Mutable modeled child birth for stale-PID and migration-race checks. */
        private long childBirth = 30;
        /** Indicates whether a modeled child exec has changed its image while preserving identity. */
        private boolean changedImage;
        /** Number of group allocation requests made by the production admission operation. */
        private int allocations;

        /**
         * Produces genuine private receipts through the controlled-observation production policy.
         * @throws IOException if initial modeled authentication fails
         */
        private Fixture() throws IOException
        {
            this.owner = identity.authenticate(new LinuxBorrowedStreams.Peer(200, 20, 1000, 1000), 2);
            this.child = identity.authenticateChild(owner, 3, 30, execution);
        }

        /**
         * Invokes production admission with this test's exclusive group allocator.
         * @return verified production containment
         * @throws IOException if controlled observations or migration are rejected
         */
        private LinuxBazelContainment admit() throws IOException
        {
            return LinuxBazelContainment.SharedSecrets.admit(identity, owner, child, execution, Path.of("/groups"),
                Duration.ofSeconds(1), (parent, timeout) -> { allocations++; return group; });
        }

        @Override
        public LinuxBazelIdentity.Observation observe(long pid, Path executable) throws IOException
        {
            long birth;
            long parent;
            List<Long> pids;
            String namespace;
            if (pid == 100) { birth = 10; parent = 1; pids = List.of(100L); namespace = "host"; }
            else if (pid == 200) { birth = 20; parent = 100; pids = List.of(200L, 2L); namespace = "sandbox"; }
            else if (pid == 300) { birth = childBirth; parent = 200; pids = List.of(300L, 3L); namespace = "sandbox"; }
            else throw new IOException("Modeled process is absent");
            boolean childImageChanged = pid == 300 && changedImage;
            return new LinuxBazelIdentity.Observation(birth, parent,
                "Uid: 1000 1000 1000 1000\nGid: 1000 1000 1000 1000", pids, "pid:[" + namespace + "]",
                "mnt:[" + namespace + "]", !childImageChanged,
                childImageChanged ? "b".repeat(64) : execution.image().sha256(),
                childImageChanged ? List.of("/native/compiler") : execution.arguments(), execution.environment(),
                execution.directory());
        }

        @Override public List<Long> children(long pid) { return List.of(300L); }
        @Override public boolean sameDirectory(long pid, Path directory) { return execution.directory().equals(directory); }
    }

    /**
     * Controls cgroup observations and failures; production containment decides admission, cleanup and zero state.
     * Successful close models the existing LinuxCgroup contract of actual zero before removal.
     */
    private static final class FakeGroup implements LinuxBazelContainment.Group
    {
        /** Controller PIDs actually requested for migration. */
        private final List<Long> moved = new ArrayList<>();
        /** Indicates whether migration publishes the child in the modeled cgroup.procs list. */
        private boolean publishMembership = true;
        /** Indicates whether the child remains in the modeled immediate group membership. */
        private boolean member;
        /** Indicates whether the original native wait owner unexpectedly entered this group. */
        private boolean ownerInside;
        /** Independently controlled subtree population, including live descendants after root disappearance. */
        private boolean populated = true;
        /** Indicates whether the next actual-population observation is unavailable. */
        private boolean populationFailure;
        /** Remaining controlled close failures before bounded cleanup succeeds. */
        private int closeFailures;
        /** Number of cleanup requests that reached the group owner. */
        private int closeCalls;
        /** Number of termination requests actually sent to the group owner. */
        private int terminationRequests;
        /** Controlled native-state transition triggered by migration, with no default state change. */
        private Runnable afterMove = () -> {};

        /**
         * Creates independent group state without allocating a real filesystem or process resource.
         */
        private FakeGroup() {}

        @Override public Path directory() { return Path.of("/groups/attempt"); }
        @Override public void move(long pid)
        {
            moved.add(pid);
            member = publishMembership;
            afterMove.run();
        }
        @Override public boolean contains(long pid) { return (pid == 300 && member) || (pid == 200 && ownerInside); }
        @Override public boolean isPopulated() throws IOException
        {
            if (populationFailure) throw new IOException("Modeled population unavailable");
            return populated;
        }
        @Override public void terminate() { terminationRequests++; }
        @Override public void close() throws IOException
        {
            closeCalls++;
            if (closeFailures > 0)
            {
                closeFailures--;
                throw new IOException("Modeled group cleanup failed");
            }
            populated = false;
            member = false;
        }
    }
}
