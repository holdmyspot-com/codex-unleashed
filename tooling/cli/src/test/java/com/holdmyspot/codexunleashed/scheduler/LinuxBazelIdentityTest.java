package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests authentication decisions over controlled proc observations, without claiming native process authentication.
 * Namespace vectors model the retained sandbox socket evidence: the controller and sandbox see different PIDs.
 * Every invocation owns its independent mutable observation fixture and is safe to run concurrently.
 */
public final class LinuxBazelIdentityTest
{
    /**
     * Creates the resource-free test suite.
     */
    public LinuxBazelIdentityTest() {}

    /**
     * Accepts a correctly authenticated owner and direct child despite distinct host and namespace-local PIDs.
     * @throws IOException if modeled authentication unexpectedly fails
     */
    @Test
    public void mapsDifferentNamespaceAndAcceptsHeldExec() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity identity = fixture.identity();
        LinuxBazelIdentity.Owner owner = identity.authenticate(fixture.peer(), 2);
        LinuxBazelIdentity.Child child = identity.authenticateChild(owner, 3, 30, fixture.execution());
        identity.requireHeld(owner, child, fixture.execution());
        requireThat(owner.peer().pid(), "ownerHostPid").isEqualTo(200L, "expected");
        requireThat(owner.localPid(), "ownerLocalPid").isEqualTo(2L, "expected");
        requireThat(child.hostPid(), "childHostPid").isEqualTo(300L, "expected");
        requireThat(child.startTicks(), "childBirth").isEqualTo(30L, "expected");
    }

    /**
     * Names independently required native facts that must not grant owner or child authority when altered.
     */
    public enum Mismatch
    {
        /** Changed process birth. */
        BIRTH,
        /** Changed direct parent. */
        PARENT,
        /** Changed saved native credentials. */
        CREDENTIALS,
        /** Claimed local PID differs from native mapping. */
        PID_MAPPING,
        /** Actual image inode differs from its process-root path. */
        IMAGE_INODE,
        /** Actual image bytes differ from the trusted seal. */
        IMAGE_HASH,
        /** Changed complete native argv. */
        ARGUMENTS,
        /** Changed complete native environment. */
        ENVIRONMENT,
        /** Changed working directory inode. */
        DIRECTORY,
        /** Changed PID namespace inode. */
        PID_NAMESPACE,
        /** Changed mount namespace inode. */
        MOUNT_NAMESPACE
    }

    /**
     * Supplies each independent native mismatch to isolated parallel test invocations.
     * @return one mismatch per invocation
     */
    @DataProvider(parallel = true)
    public Object[][] mismatches()
    {
        Mismatch[] values = Mismatch.values();
        Object[][] result = new Object[values.length][1];
        for (int index = 0; index < values.length; index++) result[index][0] = values[index];
        return result;
    }

    /**
     * Rejects each inconsistent owner fact rather than deriving authority from a claimed local PID.
     * @param mismatch native fact to alter independently
     */
    @Test(dataProvider = "mismatches")
    public void rejectsOwnerMismatch(Mismatch mismatch)
    {
        Fixture fixture = new Fixture();
        if (mismatch == Mismatch.PID_NAMESPACE || mismatch == Mismatch.MOUNT_NAMESPACE)
        {
            fixture.changeOnSecondRead(200, mismatch);
        }
        else fixture.alter(200, mismatch);
        requireException(IOException.class, () -> fixture.identity().authenticate(fixture.peer(), 2));
    }

    /**
     * Rejects each inconsistent prepared child fact before returning a host containment identity.
     * @param mismatch native fact to alter independently
     * @throws IOException if initial modeled owner authentication fails
     */
    @Test(dataProvider = "mismatches")
    public void rejectsPreparedChildMismatch(Mismatch mismatch) throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity identity = fixture.identity();
        LinuxBazelIdentity.Owner owner = identity.authenticate(fixture.peer(), 2);
        fixture.alter(300, mismatch);
        requireException(IOException.class, () -> identity.authenticateChild(owner, 3, 30, fixture.execution()));
    }

    /**
     * Rejects an untrusted local owner PID and a host peer that does not match the observed namespace vector.
     */
    @Test
    public void rejectsForgedLocalAndHostIds()
    {
        Fixture fixture = new Fixture();
        requireException(IOException.class, () -> fixture.identity().authenticate(fixture.peer(), 200));
        fixture.observations.put(999L, fixture.observations.get(200L));
        LinuxBorrowedStreams.Peer forged = new LinuxBorrowedStreams.Peer(999, 20, 1000, 1000);
        requireException(IOException.class, () -> fixture.identity().authenticate(forged, 2));
    }

    /**
     * Rejects internally matching connection credentials that differ from the trusted workload identity.
     */
    @Test
    public void rejectsConnectionUnderAnotherWorkloadIdentity()
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity.Observation original = fixture.observations.get(200L);
        fixture.observations.put(200L, new LinuxBazelIdentity.Observation(original.startTicks(), original.parentPid(),
            "Uid: 1001 1001 1001 1001\nGid: 1001 1001 1001 1001", original.namespacePids(),
            original.pidNamespace(), original.mountNamespace(), original.sameExecutable(), original.executableSha256(),
            original.arguments(), original.environment(), original.directory()));
        LinuxBorrowedStreams.Peer wrongIdentity = new LinuxBorrowedStreams.Peer(200, 20, 1001, 1001);
        requireException(IOException.class, () -> fixture.identity().authenticate(wrongIdentity, 2));
    }

    /**
     * Rejects a trusted execution policy that would run the sandbox owner as root.
     */
    @Test
    public void rejectsPrivilegedWorkloadPolicy()
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity.Execution execution = fixture.execution();
        requireException(IllegalArgumentException.class, () -> new LinuxBazelIdentity.Execution(execution.image(),
            execution.arguments(), execution.environment(), execution.directory(), 0, 0));
    }

    /**
     * Rejects a stale original parent birth or changed trusted parent executable.
     */
    @Test
    public void rejectsChangedOriginalParent()
    {
        for (Mismatch mismatch : List.of(Mismatch.BIRTH, Mismatch.IMAGE_INODE, Mismatch.IMAGE_HASH))
        {
            Fixture fixture = new Fixture();
            fixture.alter(100, mismatch);
            requireException(IOException.class, () -> fixture.identity().authenticate(fixture.peer(), 2));
        }
    }

    /**
     * Rejects duplicate direct-child candidates even when each separately claims the requested local PID and birth.
     * @throws IOException if modeled owner authentication fails
     */
    @Test
    public void rejectsAmbiguousChildMapping() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity identity = fixture.identity();
        LinuxBazelIdentity.Owner owner = identity.authenticate(fixture.peer(), 2);
        fixture.children = List.of(300L, 300L);
        requireException(IOException.class, () -> identity.authenticateChild(owner, 3, 30, fixture.execution()));
    }

    /**
     * Rejects birth or parent changes between the first prepared-child observation and its final verification.
     * @throws IOException if modeled owner authentication fails
     */
    @Test
    public void rejectsChangingChildDuringMapping() throws IOException
    {
        for (Mismatch mismatch : List.of(Mismatch.BIRTH, Mismatch.PARENT))
        {
            Fixture fixture = new Fixture();
            LinuxBazelIdentity identity = fixture.identity();
            LinuxBazelIdentity.Owner owner = identity.authenticate(fixture.peer(), 2);
            fixture.changeOnSecondRead(300, mismatch);
            requireException(IOException.class, () -> identity.authenticateChild(owner, 3, 30, fixture.execution()));
        }
    }

    /**
     * Rejects a child that changes birth, parent, image or namespace after prepared authentication.
     * @throws IOException if modeled prepared authentication fails
     */
    @Test
    public void rejectsHeldChildChanges() throws IOException
    {
        for (Mismatch mismatch : List.of(Mismatch.BIRTH, Mismatch.PARENT, Mismatch.IMAGE_INODE,
            Mismatch.IMAGE_HASH, Mismatch.PID_NAMESPACE, Mismatch.MOUNT_NAMESPACE))
        {
            Fixture fixture = new Fixture();
            LinuxBazelIdentity identity = fixture.identity();
            LinuxBazelIdentity.Owner owner = identity.authenticate(fixture.peer(), 2);
            LinuxBazelIdentity.Child child = identity.authenticateChild(owner, 3, 30, fixture.execution());
            fixture.alter(300, mismatch);
            requireException(IOException.class, () -> identity.requireHeld(owner, child, fixture.execution()));
        }
    }

    /**
     * Rejects receipt reuse under another authenticator before trusting child enumeration.
     * @throws IOException if initial modeled authentication fails
     */
    @Test
    public void rejectsOwnerFromAnotherPolicy() throws IOException
    {
        Fixture fixture = new Fixture();
        LinuxBazelIdentity.Owner owner = fixture.identity().authenticate(fixture.peer(), 2);
        requireException(IllegalArgumentException.class,
            () -> fixture.identity().authenticateChild(owner, 3, 30, fixture.execution()));
    }

    /**
     * Supplies proc-shaped facts documented by Linux proc and the retained sandbox namespace socket evidence.
     * This fixture controls observations only; all acceptance and rejection decisions run in production policy.
     */
    private static final class Fixture implements LinuxBazelIdentity.Observer
    {
        /** Test-local native process observations indexed by controller-visible PID. */
        private final Map<Long, LinuxBazelIdentity.Observation> observations = new HashMap<>();
        /** Test-local read counts used to simulate a changing process at a second independent read. */
        private final Map<Long, Integer> reads = new HashMap<>();
        /** Initial direct-child snapshot returned by the simulated owner task's children file. */
        private List<Long> children = List.of(300L);
        /** Process whose second observation changes, or zero when no change is scheduled. */
        private long changingPid;
        /** Independently selected change applied at the next simulated native transition. */
        private UnaryOperator<LinuxBazelIdentity.Observation> change = observation -> observation;

        /**
         * Creates independent retained-parent, sandbox-owner and prepared-child observations.
         */
        private Fixture()
        {
            observations.put(100L, observation(10, 1, List.of(100L), "pid:[host]", "mnt:[host]"));
            observations.put(200L, observation(20, 100, List.of(200L, 2L), "pid:[sandbox]", "mnt:[sandbox]"));
            observations.put(300L, observation(30, 200, List.of(300L, 3L), "pid:[sandbox]", "mnt:[sandbox]"));
        }

        /**
         * Creates a trusted immutable image policy with a synthetic sealed digest.
         * @return test-owned approved image
         */
        private LinuxBazelIdentity.Image image()
        {
            return new LinuxBazelIdentity.Image(Path.of("/native/gate"), "a".repeat(64));
        }

        /**
         * Creates complete trusted execution inputs for the modeled process.
         * @return test-owned immutable execution
         */
        private LinuxBazelIdentity.Execution execution()
        {
            return new LinuxBazelIdentity.Execution(image(), List.of("/native/gate", "", "argument"),
                Map.of("EMPTY", "", "VALUE", "original"), Path.of("/work"), 1000, 1000);
        }

        /**
         * Creates the production authenticator using this test's independent observation source.
         * @return new policy issuing receipts scoped to its own instance
         */
        private LinuxBazelIdentity identity()
        {
            return new LinuxBazelIdentity(new LinuxBazelIdentity.Parent(100, 10, image()), execution(), this);
        }

        /**
         * Models the host PID, birth and effective credentials captured by the accepted Unix connection.
         * @return test connection peer input
         */
        private LinuxBorrowedStreams.Peer peer()
        {
            return new LinuxBorrowedStreams.Peer(200, 20, 1000, 1000);
        }

        /**
         * Creates a stable proc-shaped observation with complete native credentials and execution inputs.
         * @param birth process birth ticks
         * @param parent host parent PID
         * @param pids independently read namespace hierarchy
         * @param pidNamespace PID namespace inode text
         * @param mountNamespace mount namespace inode text
         * @return immutable test-owned native facts
         */
        private LinuxBazelIdentity.Observation observation(long birth, long parent, List<Long> pids,
            String pidNamespace, String mountNamespace)
        {
            return new LinuxBazelIdentity.Observation(birth, parent,
                "Uid: 1000 1000 1000 1000\nGid: 1000 1000 1000 1000", pids, pidNamespace, mountNamespace,
                true, image().sha256(), execution().arguments(), execution().environment(), execution().directory());
        }

        /**
         * Changes one native fact while keeping unrelated observations constant.
         * @param pid simulated host process PID
         * @param mismatch independently selected mismatch
         */
        private void alter(long pid, Mismatch mismatch)
        {
            LinuxBazelIdentity.Observation original = observations.get(pid);
            observations.put(pid, changed(original, mismatch));
        }

        /**
         * Returns an observation whose one selected fact violates authentication policy.
         * @param original complete previous native facts
         * @param mismatch independently selected change
         * @return changed immutable facts
         */
        private LinuxBazelIdentity.Observation changed(LinuxBazelIdentity.Observation original, Mismatch mismatch)
        {
            return new LinuxBazelIdentity.Observation(
                mismatch == Mismatch.BIRTH ? (original.startTicks() + 1) : original.startTicks(),
                mismatch == Mismatch.PARENT ? 999 : original.parentPid(),
                mismatch == Mismatch.CREDENTIALS ? "Uid: 1000 1000 0 1000\nGid: 1000 1000 1000 1000" : original.status(),
                mismatch == Mismatch.PID_MAPPING ? List.of(original.namespacePids().getFirst(), 999L) : original.namespacePids(),
                mismatch == Mismatch.PID_NAMESPACE ? "pid:[other]" : original.pidNamespace(),
                mismatch == Mismatch.MOUNT_NAMESPACE ? "mnt:[other]" : original.mountNamespace(),
                mismatch != Mismatch.IMAGE_INODE,
                mismatch == Mismatch.IMAGE_HASH ? "b".repeat(64) : original.executableSha256(),
                mismatch == Mismatch.ARGUMENTS ? List.of("/native/gate", "changed") : original.arguments(),
                mismatch == Mismatch.ENVIRONMENT ? Map.of("VALUE", "changed") : original.environment(),
                mismatch == Mismatch.DIRECTORY ? Path.of("/elsewhere") : original.directory());
        }

        /**
         * Schedules a modeled process change at its second independent observation.
         * @param pid simulated process to change
         * @param mismatch independently selected native fact to change
         */
        private void changeOnSecondRead(long pid, Mismatch mismatch)
        {
            changingPid = pid;
            reads.remove(pid);
            change = original -> changed(original, mismatch);
        }

        @Override
        public LinuxBazelIdentity.Observation observe(long pid, Path executable) throws IOException
        {
            int count = reads.merge(pid, 1, Integer::sum);
            if (pid == changingPid && count == 2) observations.compute(pid, (key, original) -> change.apply(original));
            LinuxBazelIdentity.Observation observation = observations.get(pid);
            if (observation == null) throw new IOException("Modeled native process is absent");
            return observation;
        }

        @Override
        public List<Long> children(long pid) { return children; }

        @Override
        public boolean sameDirectory(long pid, Path directory)
        {
            return observations.get(pid).directory().equals(directory);
        }
    }
}
