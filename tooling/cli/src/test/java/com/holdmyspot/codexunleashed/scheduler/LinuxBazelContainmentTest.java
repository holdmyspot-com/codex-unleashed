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
     * Keeps descendant observation authority after root exit without granting root exec or whole-tree completion.
     * @throws IOException if original namespace ownership or modeled group exclusion is rejected
     */
    @Test public void observesDescendantsAfterOrdinaryRootExit() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            fixture.childGone = true;
            fixture.group.member = false;
            requireException(java.nio.file.NoSuchFileException.class, containment::requireContained);
            containment.requireImageObservationAuthority();
            requireThat(containment.observeExecutionImpossible(), "liveDescendantRemains").isFalse();
            requireException(java.nio.file.NoSuchFileException.class, () -> containment.prepareExec(4, 31));
            fixture.group.ownerInside = true;
            requireException(IOException.class, containment::requireImageObservationAuthority);
            fixture.group.ownerInside = false;
            fixture.ownerGone = true;
            requireException(java.nio.file.NoSuchFileException.class, containment::requireImageObservationAuthority);
        }
    }

    /**
     * Advances only live identity after modeled original nonleader exec while preserving the native wait receipt.
     * @throws IOException if modeled original membership or authenticated migration is rejected
     */
    @Test public void admitsAuthenticatedExecWithoutReplacingPreparedReceipt() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            LinuxBazelIdentity.ExecAuthorization authorization = containment.prepareExec(4, 31);
            fixture.childBirth = 31;
            fixture.changedImage = true;
            containment.admitExec(authorization);
            containment.requireContained();
            requireThat(fixture.child.startTicks(), "originalWaitReceiptBirth").isEqualTo(30L);
            requireThat(containment.rootPid(), "unchangedWaitedProcess").isEqualTo(300L);
            requireException(IOException.class, () -> containment.admitExec(authorization));
            containment.requestTermination();
            requireThat(fixture.group.terminationRequests, "originalGroupTermination").isEqualTo(1);
        }
    }

    /**
     * Preserves immutable PREPARED while admitting an authorized nonleader exec with unchanged leader birth.
     * @throws IOException if retained native membership or independent original birth is rejected
     */
    @Test public void admitsExecRetainingOriginalLeaderBirth() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            LinuxBazelIdentity.ExecAuthorization authorization = containment.prepareExec(4, 31);
            fixture.changedImage = true;
            containment.admitExec(authorization);
            containment.requireContained();
            requireThat(fixture.child.startTicks(), "immutablePreparedBirth").isEqualTo(30L);
            requireException(IOException.class, () -> containment.admitExec(authorization));
        }
    }

    /**
     * Completes admission when the root disappears after an earlier nonzero observation and actual subtree zero follows.
     * @throws IOException if the independently empty original tree cannot retire image observation
     */
    @Test public void toleratesVanishedImageOnlyAfterFreshWholeTreeZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            requireThat(containment.observeExecutionImpossible(), "liveBeforeAdmission").isFalse();
            LinuxBazelNamespaceHooks.admitLiveImages(containment, () ->
            {
                fixture.group.populated = false;
                throw new java.nio.file.NoSuchFileException("/proc/300/cmdline");
            });
            requireThat(containment.observeExecutionImpossible(), "independentlyObservedZeroAfterRace").isTrue();
        }
    }

    /**
     * Preserves the exact vanished-root error while live descendants remain or zero observation is unavailable.
     * @throws IOException if original admission or cleanup fails
     */
    @Test public void preservesVanishedImageFailureWithLiveDescendants() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            java.nio.file.NoSuchFileException failure = new java.nio.file.NoSuchFileException("/proc/300/cmdline");
            java.nio.file.NoSuchFileException caught = requireException(java.nio.file.NoSuchFileException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "originalObservationFailure").isTrue();
            requireThat(containment.observeExecutionImpossible(), "descendantStillExecutes").isFalse();
        }
    }

    /**
     * Retains a vanished-image failure when whole-tree population cannot be independently observed.
     * @throws IOException if initial original ownership or later cleanup fails
     */
    @Test public void preservesVanishedImageWhenZeroIsUncertain() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            java.nio.file.NoSuchFileException failure = new java.nio.file.NoSuchFileException("/proc/300/cmdline");
            fixture.group.populationFailure = true;
            java.nio.file.NoSuchFileException caught = requireException(java.nio.file.NoSuchFileException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "originalObservationFailure").isTrue();
            requireThat(caught.getSuppressed().length, "uncertainZeroEvidence").isEqualTo(1);
            fixture.group.populationFailure = false;
        }
    }

    /**
     * Never hides a surviving-process authentication failure even if the tree becomes empty later.
     * @throws IOException if initial original ownership or cleanup fails
     */
    @Test public void preservesOtherAdmissionFailuresEvenWithZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            IOException failure = new IOException("Surviving image credentials changed");
            fixture.group.populated = false;
            IOException caught = requireException(IOException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "survivingIdentityFailureRetained").isTrue();
        }
    }

    /**
     * Keeps missing sealed-image or runtime faults fatal even after the original workload becomes empty.
     * @throws IOException if initial original ownership or cleanup fails
     */
    @Test public void preservesMissingRuntimeFailureEvenWithZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            java.nio.file.NoSuchFileException failure = new java.nio.file.NoSuchFileException("/runtime/sealed-image");
            fixture.group.populated = false;
            java.nio.file.NoSuchFileException caught = requireException(java.nio.file.NoSuchFileException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "missingRuntimeFailureRetained").isTrue();
        }
    }

    /**
     * Settles an exact empty proc command stream only after independent whole-tree zero following that read.
     * @throws IOException if original ownership or independently empty workload retirement fails
     */
    @Test public void toleratesEmptyProcVectorOnlyAfterFreshWholeTreeZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            requireThat(containment.observeExecutionImpossible(), "liveBeforeCommandObservation").isFalse();
            LinuxBazelNamespaceHooks.admitLiveImages(containment, () ->
            {
                fixture.group.populated = false;
                LinuxBazelIdentity.readNativeArguments(300, new java.io.ByteArrayInputStream(new byte[0]));
            });
            requireThat(containment.observeExecutionImpossible(), "zeroAfterEmptyVector").isTrue();
        }
    }

    /**
     * Preserves an exact empty-command failure while descendants remain alive or whole-tree zero is uncertain.
     * @throws IOException if original ownership or final cleanup fails
     */
    @Test public void preservesEmptyVectorFailureWithoutVerifiedZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            LinuxBazelIdentity.EmptyCommandVectorException failure = requireException(
                LinuxBazelIdentity.EmptyCommandVectorException.class,
                () -> LinuxBazelIdentity.readNativeArguments(300, new java.io.ByteArrayInputStream(new byte[0])));
            IOException caught = requireException(IOException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "liveDescendantFailureRetained").isTrue();
            fixture.group.populationFailure = true;
            caught = requireException(IOException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw failure; }));
            requireThat(caught == failure, "uncertainZeroFailureRetained").isTrue();
            requireThat(caught.getSuppressed().length, "independentObservationFailure").isEqualTo(1);
            fixture.group.populationFailure = false;
        }
    }

    /**
     * Never treats malformed command data or an unrelated same-message failure as an exact empty proc vector.
     * @throws IOException if original ownership or cleanup fails
     */
    @Test public void preservesUnrelatedCommandFailuresEvenWithZero() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            fixture.group.populated = false;
            IOException unrelated = new IOException("Native command vector is empty");
            IOException caught = requireException(IOException.class,
                () -> LinuxBazelNamespaceHooks.admitLiveImages(containment, () -> { throw unrelated; }));
            requireThat(caught == unrelated, "sameMessageIsNotNativeEvidence").isTrue();
            caught = requireException(IOException.class, () -> LinuxBazelNamespaceHooks.admitLiveImages(containment,
                () -> LinuxBazelIdentity.readNativeArguments(300, new java.io.ByteArrayInputStream(new byte[] {'x'}))));
            requireThat(caught instanceof LinuxBazelIdentity.EmptyCommandVectorException,
                "malformedVectorRemainsFatal").isFalse();
        }
    }

    /**
     * Withholds live identity migration when the held process has left the original cgroup.
     * @throws IOException if initial modeled admission fails
     */
    @Test public void rejectsExecOutsideOriginalContainment() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            LinuxBazelIdentity.ExecAuthorization authorization = containment.prepareExec(4, 31);
            fixture.childBirth = 31;
            fixture.group.member = false;
            requireException(IOException.class, () -> containment.admitExec(authorization));
            fixture.group.member = true;
            requireException(IOException.class, containment::requireContained);
            containment.admitExec(authorization);
            containment.requireContained();
            requireThat(fixture.child.startTicks(), "unchangedPreparedWaitReceipt").isEqualTo(30L);
        }
    }

    /**
     * Rejects an unapproved root birth change without replacing the current identity.
     * @throws IOException if initial modeled admission fails
     */
    @Test public void rejectsUnapprovedExecBirth() throws IOException
    {
        Fixture fixture = new Fixture();
        try (LinuxBazelContainment containment = fixture.admit())
        {
            fixture.childBirth = 31;
            requireException(IOException.class, containment::requireContained);
            requireThat(fixture.child.startTicks(), "originalPreparedBirth").isEqualTo(30L);
        }
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
        /** Indicates the modeled original root has been collected while a descendant remains. */
        private boolean childGone;
        /** Indicates the original namespace owner can no longer be authenticated. */
        private boolean ownerGone;
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
            if ((pid == 300 && childGone) || (pid == 200 && ownerGone))
                throw new java.nio.file.NoSuchFileException("/proc/" + pid + "/stat");
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

        @Override public LinuxBazelIdentity.ThreadObservation observeThread(long hostPid, long localThreadPid,
            Path executable) throws IOException
        {
            if (hostPid != 300 || localThreadPid != 4) throw new IOException("Modeled original thread is absent");
            LinuxBazelIdentity.Observation group = observe(300, executable);
            return new LinuxBazelIdentity.ThreadObservation(301, 300, new LinuxBazelIdentity.Observation(31,
                group.parentPid(), group.status(), List.of(301L, 4L), group.pidNamespace(), group.mountNamespace(),
                group.sameExecutable(), group.executableSha256(), group.arguments(), group.environment(), group.directory()));
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
