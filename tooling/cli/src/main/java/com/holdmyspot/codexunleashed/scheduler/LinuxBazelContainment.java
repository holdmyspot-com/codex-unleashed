package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Owns host cgroup containment for one authenticated sandbox child while its original owner retains wait authority.
 * The child must remain at its authenticated gate throughout admission. The privileged host caller creates and owns
 * the cgroup; the unprivileged native parent stays outside it and retains responsibility for collecting child status.
 * The root manager authenticates held executable transitions separately. Calls and group topology are confined to
 * one operation thread, and external process migration into or out of the retained group is unsupported.
 */
final class LinuxBazelContainment implements AutoCloseable
{
    /** Original authentication policy retained through the child lifetime. */
    private final LinuxBazelIdentity authenticator;
    /** Authenticated native parent whose wait authority remains outside the attempt group. */
    private final LinuxBazelIdentity.Owner owner;
    /** Independently mapped child birth retained across approved exec transitions. */
    private final LinuxBazelIdentity.Child child;
    /** Authenticated current kernel identity after any authorized original nonleader exec. */
    private LinuxBazelIdentity.Child currentChild;
    /** Exclusive host cgroup owner retained until successful cleanup. */
    private final Group group;
    /** Indicates whether actual empty population was observed before the owned group disappeared. */
    private boolean zeroObserved;
    /** Indicates whether owned group cleanup completed successfully. */
    private boolean closed;

    /**
     * Retains already allocated containment and authenticated identities without transferring child wait authority.
     * @param authenticator issuing authentication policy
     * @param owner original authenticated native parent
     * @param child authenticated prepared child
     * @param group allocated exclusive host containment
     */
    private LinuxBazelContainment(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Child child, Group group)
    {
        this.authenticator = authenticator;
        this.owner = owner;
        this.child = child;
        this.currentChild = child;
        this.group = group;
    }

    /**
     * Authenticates a held gate, places only that child in a new host cgroup, and verifies identity and membership.
     * Failed admission closes the allocated group. AcquisitionFailure retains its owner if that cleanup also fails;
     * the caller must retain the native parent and collect its child status even after successful group cleanup.
     * @param authenticator original issuing authentication policy
     * @param owner authenticated native action owner outside the attempt group
     * @param child authenticated child still held at its prepared gate
     * @param preparedExecution exact trusted gate image, argv, environment, directory and workload credentials
     * @param cgroupParent normalized absolute delegated host cgroup-v2 parent
     * @param timeout positive per-cleanup timeout representable as signed nanoseconds
     * @return exclusively owned verified child containment
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if identity ownership, parent path or timeout is invalid
     * @throws UnsupportedOperationException if the parent filesystem is not cgroup v2
     * @throws IOException if identity, allocation, migration or membership cannot be verified
     */
    static LinuxBazelContainment admit(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Path cgroupParent,
        Duration timeout) throws IOException
    {
        return admit(authenticator, owner, child, preparedExecution, cgroupParent, timeout, NativeGroup::create);
    }

    /**
     * Reserves an actual empty attempt group before the private filesystem is mounted or CREATE is sent.
     * The caller owns its cleanup immediately and must preserve it through native unmapping.
     * @param cgroupParent absolute delegated cgroup parent
     * @param timeout positive cleanup duration
     * @return exclusive empty group owner
     * @throws IOException if native group creation fails
     */
    static Group reserve(Path cgroupParent, Duration timeout) throws IOException
    {
        return NativeGroup.create(cgroupParent, timeout);
    }

    /**
     * Admits the authenticated blocked child into the exact group reserved before MAP and CREATE.
     * This uses the ordinary identity/migration/cleanup policy without allocating another group.
     * On failed admission the supplied group is closed or retained by AcquisitionFailure.
     * @param authenticator independent identity observer
     * @param owner original native waiter outside the group
     * @param child independently mapped blocked child
     * @param preparedExecution exact original gate execution
     * @param group exclusive group already pinned by the namespace supervisor
     * @param timeout positive native cleanup duration
     * @return verified containment over the same group
     * @throws IOException if authentication, migration or cleanup fails
     */
    static LinuxBazelContainment admitReserved(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Group group,
        Duration timeout) throws IOException
    {
        Objects.requireNonNull(group, "group");
        return admit(authenticator, owner, child, preparedExecution, group.directory().getParent(), timeout,
            (parent, duration) -> group, true);
    }

    /**
     * Performs the same admission policy using an independently supplied group allocation boundary.
     * @param authenticator issuing authentication policy
     * @param owner authenticated native parent
     * @param child authenticated prepared child
     * @param preparedExecution complete trusted gate execution
     * @param cgroupParent absolute normalized delegated host cgroup parent
     * @param timeout positive cleanup timeout
     * @param groups exclusive group allocation boundary
     * @return verified containment retaining all allocated cleanup ownership
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if identity ownership, paths or timeout is invalid
     * @throws IOException if acquisition or authentication fails
     */
    private static LinuxBazelContainment admit(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Path cgroupParent,
        Duration timeout, Groups groups) throws IOException
    {
        return admit(authenticator, owner, child, preparedExecution, cgroupParent, timeout, groups, false);
    }

    /**
     * Preserves an externally pinned reserved group on failed admission until its namespace owner unmaps it.
     * @param authenticator issuing authentication policy
     * @param owner original native waiter
     * @param child authenticated prepared child
     * @param preparedExecution complete approved gate execution
     * @param cgroupParent absolute delegated parent
     * @param timeout positive cleanup duration
     * @param groups exclusive group allocator or existing reservation
     * @param retainFailedGroup indicates whether namespace cleanup must precede group removal on failure
     * @return authenticated admitted containment
     * @throws IOException if identity, migration or membership fails
     */
    private static LinuxBazelContainment admit(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Path cgroupParent,
        Duration timeout, Groups groups, boolean retainFailedGroup) throws IOException
    {
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(preparedExecution, "preparedExecution");
        Objects.requireNonNull(cgroupParent, "cgroupParent");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(groups, "groups");
        if (!cgroupParent.isAbsolute() || !cgroupParent.normalize().equals(cgroupParent))
            throw new IllegalArgumentException("Cgroup parent must be normalized and absolute");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Containment timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("Containment timeout exceeds signed nanoseconds", cause); }
        authenticator.requireHeld(owner, child, preparedExecution);
        Group group = groups.create(cgroupParent, timeout);
        LinuxBazelContainment containment;
        try
        {
            containment = new LinuxBazelContainment(authenticator, owner, child, group);
        }
        catch (RuntimeException | Error failure)
        {
            try { group.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try
        {
            authenticator.requireHeld(owner, child, preparedExecution);
            if (group.contains(owner.peer().pid())) throw new IOException("Native action owner entered the attempt cgroup");
            group.move(child.hostPid());
            authenticator.requireHeld(owner, child, preparedExecution);
            containment.requireContained();
            return containment;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            if (retainFailedGroup) throw new AcquisitionFailure(failure, containment);
            try { containment.close(); }
            catch (IOException cleanup)
            {
                failure.addSuppressed(cleanup);
                throw new AcquisitionFailure(failure, containment);
            }
            throw failure;
        }
    }

    /**
     * Returns the independently authenticated child PID used by the host memory reader and instrumentation.
     * @return positive child PID in the controller namespace
     */
    long rootPid() { return child.hostPid(); }

    /**
     * Returns the exclusive host group path, retaining its last location after successful close.
     * @return absolute retained cgroup directory
     */
    Path directory() { return group.directory(); }

    /**
     * Supplies the exact authenticated direct-group registration facts after current containment revalidation.
     * Only this concrete containment capability can issue the private sandbox reader request.
     * @return immutable retained kernel peer, root birth and pinned namespace facts
     * @throws IOException if authentic child membership or retained identity changed
     */
    java.util.Map<String, String> registrationFields() throws IOException
    {
        requireContained();
        return authenticator.registrationFields(owner, child);
    }

    /**
     * Checks retained child birth, parent, namespaces, credentials and actual cgroup membership across approved exec.
     * Held executable image authorization remains the root manager's responsibility.
     * @throws IllegalStateException if cleanup already completed
     * @throws IOException if identities or membership differ or cannot be observed
     */
    void requireContained() throws IOException
    {
        requireOpen();
        authenticator.requireChildIdentity(owner, currentChild);
        if (!group.contains(child.hostPid())) throw new IOException("Authenticated child is outside its retained attempt cgroup");
        if (group.contains(owner.peer().pid())) throw new IOException("Native action owner entered the attempt cgroup");
        authenticator.requireChildIdentity(owner, currentChild);
        if (!group.contains(child.hostPid())) throw new IOException("Authenticated child left its attempt cgroup during verification");
    }

    /**
     * Revalidates the original namespace owner and its exclusion from the pinned attempt group.
     * This authority remains valid for independently observed descendants after the workload root exits;
     * callers must still verify each member's birth, credentials, namespaces, membership and native lineage.
     * It grants neither root exec authority nor a whole-tree completion observation.
     * @throws IOException if the original owner changed or entered the attempt group
     */
    void requireImageObservationAuthority() throws IOException
    {
        requireOpen();
        authenticator.requireNamespaceOwner(owner);
        if (group.contains(owner.peer().pid())) throw new IOException("Native action owner entered the attempt cgroup");
        authenticator.requireNamespaceOwner(owner);
    }

    /**
     * Captures a held original nonleader exec thread while current root identity and containment still hold.
     * @param localThreadPid namespace-local thread ID from the original native exec-entry stop
     * @param startTicks native exec-entry birth to match against independent thread observations
     * @return one-use independently authenticated migration authority
     * @throws IOException if current identity, membership or original thread cannot be authenticated
     */
    LinuxBazelIdentity.ExecAuthorization prepareExec(long localThreadPid, long startTicks) throws IOException
    {
        requireContained();
        LinuxBazelIdentity.ExecAuthorization authorization = authenticator.prepareExec(owner, currentChild, localThreadPid, startTicks);
        requireContained();
        return authorization;
    }

    /**
     * Admits the held original root's authenticated nonleader exec without changing its native parent's wait receipt.
     * @param authorization exact original thread capability captured before native exec was authorized
     * @throws IOException if native identity, original parent exclusion or cgroup membership differs
     */
    void admitExec(LinuxBazelIdentity.ExecAuthorization authorization) throws IOException
    {
        requireOpen();
        if (!group.contains(child.hostPid()) || group.contains(owner.peer().pid()))
            throw new IOException("Held exec containment membership differs before authentication");
        LinuxBazelIdentity.Child migrated = authenticator.authenticateExec(owner, currentChild, authorization);
        if (!group.contains(child.hostPid()) || group.contains(owner.peer().pid()))
            throw new IOException("Authenticated exec containment membership differs");
        currentChild = migrated;
    }

    /**
     * Observes actual empty population for this entire retained subtree or returns its earlier verified observation.
     * Root exit, missing directories and uncollected child status do not establish this barrier.
     * @return true after an actual populated-zero read or successful group close, which itself observes zero
     * @throws IOException if population cannot be read
     */
    boolean observeExecutionImpossible() throws IOException
    {
        if (zeroObserved) return true;
        if (!group.isPopulated()) zeroObserved = true;
        return zeroObserved;
    }

    /**
     * Requests subtree termination without killing the original native parent or waiting for child status collection.
     * @throws IllegalStateException if group cleanup already completed
     * @throws IOException if the kernel cannot receive the request
     */
    void requestTermination() throws IOException
    {
        requireOpen();
        requireOwnerOutside();
        group.terminate();
    }

    /**
     * Terminates the owned subtree, observes actual zero and removes its groups within the configured close bound.
     * Failure retains this owner for another close attempt. Success preserves the genuine zero barrier for a borrowed
     * memory registration. The original native parent still owns collecting every child status.
     * @throws IOException if termination, empty-population observation or removal fails
     */
    @Override
    public void close() throws IOException
    {
        if (closed) return;
        requireOwnerOutside();
        group.close();
        zeroObserved = true;
        closed = true;
    }

    /**
     * Withholds subtree termination if the original native parent is unexpectedly inside it.
     * @throws IOException if parent exclusion cannot be established, retaining cleanup ownership
     */
    private void requireOwnerOutside() throws IOException
    {
        if (group.contains(owner.peer().pid()))
            throw new IOException("Native action owner is inside the attempt cgroup; termination is withheld");
    }

    /**
     * Rejects operations that require a live owned group after successful cleanup.
     * @throws IllegalStateException if group cleanup completed
     */
    private void requireOpen()
    {
        if (closed) throw new IllegalStateException("Bazel child containment is closed");
    }

    /**
     * Retains incomplete containment cleanup when admission and its immediate cleanup both fail.
     */
    static final class AcquisitionFailure extends IOException
    {
        /** Serialization identity for the retained-owner failure type. */
        private static final long serialVersionUID = 1L;
        /** Live cleanup authority retained only in this JVM; serialized diagnostics cannot transfer ownership. */
        private final transient LinuxBazelContainment owner;

        /**
         * Preserves acquisition diagnostics and the still-owned containment.
         * @param cause original failure with suppressed cleanup diagnostics
         * @param owner incomplete cleanup owner
         */
        private AcquisitionFailure(Throwable cause, LinuxBazelContainment owner)
        {
            super("Bazel containment admission failed and cleanup remains pending", cause);
            this.owner = owner;
        }

        /**
         * Returns the retained cleanup owner without granting admission success.
         * @return containment whose close must be retried
         * @throws IllegalStateException if deserialization retained only diagnostics without live ownership
         */
        LinuxBazelContainment owner()
        {
            if (owner == null) throw new IllegalStateException("Deserialized admission diagnostics contain no live cleanup owner");
            return owner;
        }
    }

    /**
     * Allocates an exclusive empty host group without moving the native owner.
     */
    @FunctionalInterface
    interface Groups
    {
        /**
         * Creates a new empty cgroup whose cleanup belongs to the returned owner.
         * @param parent delegated absolute parent directory
         * @param timeout positive cleanup bound
         * @return exclusive empty group owner
         * @throws IOException if allocation fails
         */
        Group create(Path parent, Duration timeout) throws IOException;
    }

    /**
     * Owns one cgroup, including authoritative population observation and bounded removal.
     */
    interface Group extends AutoCloseable
    {
        /**
         * Returns the owned group's absolute directory.
         * @return retained directory, including after removal
         */
        Path directory();
        /**
         * Moves exactly one controller-visible process into this owned group.
         * @param pid independently authenticated child host PID
         * @throws IOException if migration fails
         */
        void move(long pid) throws IOException;
        /**
         * Indicates whether the kernel reports the process in this group's immediate cgroup.procs file.
         * @param pid controller-visible process ID
         * @return true when independently listed by the kernel
         * @throws IOException if membership cannot be read
         */
        boolean contains(long pid) throws IOException;
        /**
         * Indicates whether the group subtree has live processes.
         * @return true for actual populated one, false for actual populated zero
         * @throws IOException if actual population is unavailable
         */
        boolean isPopulated() throws IOException;
        /**
         * Requests subtree termination without waiting for zero or collecting child status.
         * @throws IOException if the kernel rejects the request
         */
        void terminate() throws IOException;
        /**
         * Terminates this subtree, observes actual zero, and removes the owned groups within the configured bound.
         * Failure preserves ownership so close may be retried; successful repeated close has no effect.
         * @throws IOException if termination, zero observation or removal fails
         */
        @Override
        void close() throws IOException;
    }

    /**
     * Adapts the existing authoritative Linux cgroup owner without changing its termination or close policy.
     */
    private static final class NativeGroup implements Group
    {
        /** Actual cgroup owner whose lifecycle is retained by this adapter. */
        private final LinuxCgroup group;

        /**
         * Retains one already allocated Linux cgroup.
         * @param group exclusive actual group owner
         */
        private NativeGroup(LinuxCgroup group) { this.group = group; }

        /**
         * Allocates an actual empty cgroup and transfers cleanup only after adapter construction succeeds.
         * @param parent delegated host parent
         * @param timeout positive cleanup bound
         * @return actual cgroup adapter
         * @throws IOException if allocation or failed-construction cleanup fails
         */
        private static NativeGroup create(Path parent, Duration timeout) throws IOException
        {
            LinuxCgroup group = LinuxCgroup.create(parent, timeout);
            try { return new NativeGroup(group); }
            catch (RuntimeException | Error failure)
            {
                try { group.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        @Override public Path directory() { return group.directory(); }
        @Override public void move(long pid) throws IOException
        {
            Files.writeString(directory().resolve("cgroup.procs"), Long.toString(pid));
        }
        @Override public boolean contains(long pid) throws IOException
        {
            return Files.readAllLines(directory().resolve("cgroup.procs")).contains(Long.toString(pid));
        }
        @Override public boolean isPopulated() throws IOException { return group.isPopulated(); }
        @Override public void terminate() throws IOException { group.terminate(); }
        @Override public void close() throws IOException { group.close(); }
    }

    /**
     * Provides typed access to controlled allocation while exercising the single production admission operation.
     */
    static final class SharedSecrets
    {
        /**
         * Prevents construction of the focused access facade.
         */
        private SharedSecrets() {}

        /**
         * Runs production admission with a controlled independent group boundary.
         * @param authenticator issuing policy
         * @param owner authenticated native parent
         * @param child authenticated prepared child
         * @param preparedExecution complete trusted gate execution
         * @param cgroupParent absolute normalized delegated parent
         * @param timeout positive cleanup timeout
         * @param groups independent exclusive group allocator
         * @return production containment decision and retained lifecycle owner
         * @throws IOException if native facts, migration, membership or cleanup fail
         */
        static LinuxBazelContainment admit(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
            LinuxBazelIdentity.Child child, LinuxBazelIdentity.Execution preparedExecution, Path cgroupParent,
            Duration timeout, Groups groups) throws IOException
        {
            return LinuxBazelContainment.admit(authenticator, owner, child, preparedExecution, cgroupParent, timeout, groups);
        }
    }
}
