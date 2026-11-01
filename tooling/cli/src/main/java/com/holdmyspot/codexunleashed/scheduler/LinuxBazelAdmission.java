package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Retains one authenticated sandbox attempt from blocked native gate through complete host cleanup.
 * Construction and factory creation acquire no native resources. A failed operation retains this same owner;
 * uncertain authority registration never permits removal of storage still potentially borrowed by the reader.
 */
final class LinuxBazelAdmission implements LinuxBazelAction.Resources
{
    /** Authenticates kernel observations independently of native local claims. */
    private final LinuxBazelIdentity authenticator;
    /** Original authenticated native waiter outside containment. */
    private final LinuxBazelIdentity.Owner owner;
    /** Approved native gate image before host migration. */
    private final LinuxBazelIdentity.Execution prepared;
    /** Approved execution image held by the original instrumentation manager. */
    private final LinuxBazelIdentity.Execution held;
    /** Host cgroup parent owned by the scheduler. */
    private final Path cgroupParent;
    /** Borrowed original host measurement authority. */
    private final LinuxAuthority authority;
    /** Stable action identity. */
    private final String name;
    /** Positive bounded native cleanup duration. */
    private final Duration timeout;
    /** Resource-free original instrumentation owner factory. */
    private final HookFactory factory;
    /** Immutable queue-wide attempt ordinal. */
    private final long sequence;
    /** Original attempt wait collector. */
    private final AllocationWaits waits;
    /** Claim retained before authentication can fail. */
    private LinuxBazelControl.Identity localIdentity;
    /** Authenticated host identity, distinct from the local claim. */
    private LinuxBazelIdentity.Child child;
    /** Owned containment, including partial acquisition failures. */
    private LinuxBazelContainment containment;
    /** Empty group reserved before MAP; remains owned until native unmapping and admission cleanup. */
    private LinuxBazelContainment.Group reservedGroup;
    /** Acknowledged native reader registration. */
    private LinuxAuthority.Registration registration;
    /** Set before the potentially effective native registration request. */
    private boolean registrationAttempted;
    /** Original instrumentation owner retained before any native acquisition. */
    private HookOwner hooks;
    /** Indicates that native preparation has been attempted once. */
    private boolean startAttempted;
    /** Indicates that original image continuation completed successfully. */
    private boolean started;
    /** Prevents future allocation grants even when withdrawal fails. */
    private boolean stopped;
    /** Indicates acknowledged native grant withdrawal. */
    private boolean hooksStopped;
    /** Indicates acknowledged original hook destruction. */
    private boolean instrumentationClosed;
    /** Indicates complete cleanup. */
    private boolean closed;
    /** Independent original invocation termination and zero authority retained before native CREATE. */
    private InvocationTermination invocation;

    /**
     * Reserves a resource-free owner with immutable admission policy.
     * @param authenticator independent host identity observer
     * @param owner authenticated original native waiter
     * @param prepared exact approved gate image
     * @param held exact approved target image
     * @param cgroupParent absolute normalized host cgroup parent
     * @param authority borrowed original host measurement authority
     * @param name nonblank stable action identity
     * @param timeout positive bounded native cleanup duration
     * @param factory resource-free original hook owner factory
     * @param sequence nonnegative queue-wide attempt ordinal
     * @param waits original validated native event collector
     * @throws IllegalArgumentException if policy values are invalid
     */
    LinuxBazelAdmission(LinuxBazelIdentity authenticator, LinuxBazelIdentity.Owner owner,
        LinuxBazelIdentity.Execution prepared, LinuxBazelIdentity.Execution held, Path cgroupParent,
        LinuxAuthority authority, String name, Duration timeout, HookFactory factory, long sequence,
        AllocationWaits waits)
    {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.prepared = Objects.requireNonNull(prepared, "prepared");
        this.held = Objects.requireNonNull(held, "held");
        this.cgroupParent = Objects.requireNonNull(cgroupParent, "cgroupParent");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.name = Objects.requireNonNull(name, "name");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.sequence = sequence;
        this.waits = Objects.requireNonNull(waits, "waits");
        if (!cgroupParent.isAbsolute() || !cgroupParent.normalize().equals(cgroupParent))
            throw new IllegalArgumentException("Cgroup parent must be absolute and normalized");
        if (name.isBlank() || timeout.isNegative() || timeout.isZero() || sequence < 0)
            throw new IllegalArgumentException("Invalid Bazel admission policy");
        timeout.toNanos();
    }

    /**
     * Retains independent whole-invocation cleanup before native child creation can have uncertain effects.
     * This capability remains outside the attempt and never supplies an ordinary action result.
     * @param invocation original retained supervisor's termination and actual whole-invocation zero observations
     * @throws NullPointerException if {@code invocation} is null
     * @throws IllegalStateException if admission or capability attachment was already attempted
     */
    void retainInvocation(InvocationTermination invocation)
    {
        if (this.invocation != null || localIdentity != null || startAttempted || closed)
            throw new IllegalStateException("Original invocation cleanup must be retained once before admission");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
    }

    /**
     * Reserves the exact empty host group before the namespace supervisor maps an attempt.
     * Creation failure remains with the independent native invocation cleanup owner.
     * @return exclusive group directory supplied unchanged to MAP
     * @throws IOException if group allocation fails
     * @throws IllegalStateException if reservation, admission or closure already began
     */
    Path reserveContainment() throws IOException
    {
        if (reservedGroup != null || localIdentity != null || closed)
            throw new IllegalStateException("Attempt containment must be reserved once before CREATE");
        reservedGroup = LinuxBazelContainment.reserve(cgroupParent, timeout);
        return reservedGroup.directory();
    }

    /**
     * Authenticates the local claim and retains every acquired native owner before its next fallible operation.
     * @param claim untrusted original waiter's namespace-local PID and birth
     * @throws IOException if authentication, containment or registration cannot be established
     */
    @Override public void admit(LinuxBazelControl.Identity claim) throws IOException
    {
        if (closed || localIdentity != null) throw new IllegalStateException("Admission was already attempted");
        localIdentity = Objects.requireNonNull(claim, "claim");
        child = authenticator.authenticateChild(owner, claim.pid(), claim.birth(), prepared);
        try
        {
            if (reservedGroup == null)
                containment = LinuxBazelContainment.admit(authenticator, owner, child, prepared, cgroupParent, timeout);
            else
                containment = LinuxBazelContainment.admitReserved(authenticator, owner, child, prepared,
                    reservedGroup, timeout);
        }
        catch (LinuxBazelContainment.AcquisitionFailure failure)
        {
            containment = failure.owner();
            throw failure;
        }
        registrationAttempted = true;
        registration = authority.register(name, sequence, containment);
        hooks = Objects.requireNonNull(factory.create(sequence, child, localIdentity, containment, waits), "hooks");
    }

    /**
     * Establishes approved held-image readiness, revalidates host authority and continues the original image once.
     * @throws IOException if any native transition or independent host revalidation fails
     */
    @Override public void start() throws IOException
    {
        if (closed || stopped || hooks == null || startAttempted)
            throw new IllegalStateException("Bazel image cannot start");
        startAttempted = true;
        hooks.prepareHeld();
        requireHeld();
        hooks.install();
        requireHeld();
        hooks.resumeImage();
        started = true;
    }

    /**
     * Revalidates the exact target image and its retained host containment before continuation.
     * @throws IOException if either independent proof fails
     */
    private void requireHeld() throws IOException
    {
        authenticator.requireHeld(owner, child, held);
        containment.requireContained();
    }

    /**
     * Copies actual native events only after successful original continuation.
     * @throws IOException if original hook delivery fails
     */
    @Override public void pump() throws IOException
    {
        if (started && !stopped && !closed) hooks.pump();
    }

    /**
     * Grants the exact original validated wait while this attempt remains active.
     * @param wait exact validated native allocation wait
     * @throws IOException if native permission or acknowledgement is uncertain
     */
    @Override public void grant(AllocationWaits.Wait wait) throws IOException
    {
        Objects.requireNonNull(wait, "wait");
        if (!started || stopped || closed) throw new IllegalStateException("Bazel attempt cannot grant allocations");
        hooks.grant(wait);
    }

    /**
     * Withdraws future grants before native hook withdrawal, preserving failed withdrawal as retryable cleanup.
     * @throws IOException if original hook withdrawal fails
     */
    @Override public void stop() throws IOException
    {
        stopped = true;
        waits.stop();
        if (hooks != null && !hooksStopped && !instrumentationClosed)
        {
            hooks.stop();
            hooksStopped = true;
        }
    }

    /**
     * Requests whole-tree cancellation through the retained host containment owner.
     * @throws IOException if cancellation cannot be established
     */
    @Override public void requestTermination() throws IOException
    {
        if (containment != null) containment.requestTermination();
        else if (invocation != null) invocation.requestTermination();
        else if (localIdentity != null) throw new IOException("Uncontained native child remains owned by original waiter");
    }

    /**
     * Observes actual whole-tree zero. Before containment, a retained invocation capability must establish its
     * complete zero even when no PREPARED claim arrived. An absent claim cannot prove uncertain CREATE had no effect.
     * @return true for actual containment or retained invocation zero; without an invocation, true before any claim
     * @throws IOException if native containment observation is unknown
     */
    @Override public boolean zero() throws IOException
    {
        if (containment == null)
        {
            if (invocation != null) return invocation.executionImpossible();
            return localIdentity == null;
        }
        return containment.observeExecutionImpossible();
    }

    /**
     * Closes the original instrumentation only after an independently observed whole-tree zero barrier.
     * @throws IOException if actual zero or original hook destruction remains uncertain
     */
    @Override public void finishInstrumentation() throws IOException
    {
        if (instrumentationClosed) return;
        stop();
        if (!zero()) throw new IOException("Original Bazel instrumentation still owns a potentially executing child");
        if (hooks != null) hooks.close(this::zero);
        instrumentationClosed = true;
    }

    /**
     * Removes native reader borrowing before containment storage and retains all owners on any failure.
     * An uncertain registration acknowledgement requires original authority cleanup before storage can be removed.
     * @throws IOException if hook, registration or containment ownership remains unresolved
     */
    @Override public void close() throws IOException
    {
        if (closed) return;
        finishInstrumentation();
        if (registrationAttempted && registration == null && !authority.isClosed())
            throw new IOException("Bazel registration acknowledgement is unresolved; containment remains retained");
        if (registration != null) registration.close();
        if (containment != null) containment.close();
        else if (reservedGroup != null) reservedGroup.close();
        closed = true;
    }

    /**
     * Creates a resource-free original instrumentation owner after authenticated containment and registration.
     */
    @FunctionalInterface
    interface HookFactory
    {
        /**
         * Retains immutable identities without acquiring a native manager, process, descriptor or script.
         * @param sequence immutable queue-wide attempt ordinal
         * @param child independently authenticated host child identity
         * @param localIdentity independently verified original namespace-local claim; never a host PID
         * @param containment retained host whole-tree owner
         * @param waits original validated event collector
         * @return exclusive resource-free original instrumentation owner
         */
        HookOwner create(long sequence, LinuxBazelIdentity.Child child, LinuxBazelControl.Identity localIdentity,
            LinuxBazelContainment containment, AllocationWaits waits);
    }

    /**
     * Retains independent original supervisor authority before an attempt containment can exist.
     */
    interface InvocationTermination
    {
        /**
         * Requests termination through the original whole-invocation supervisor.
         * @throws IOException if original termination has uncertain effects
         */
        void requestTermination() throws IOException;
        /**
         * Observes original whole-invocation execution impossibility and collection.
         * @return true only after the original supervisor establishes actual complete zero
         * @throws IOException if original zero or collection cannot be established
         */
        boolean executionImpossible() throws IOException;
    }

    /**
     * Retains the original manager, host-owned transport and every partial native acquisition until actual zero.
     * Its implementation pins trusted executable, classpath, environment and pipe ownership before native use.
     */
    interface HookOwner
    {
        /**
         * Acquires the original manager and holds the approved target image without continuing it.
         * @throws IOException if native acquisition or held preparation fails
         */
        void prepareHeld() throws IOException;
        /**
         * Installs native hooks and returns only after matching original readiness acknowledgement.
         * @throws IOException if hooks or their readiness cannot be established
         */
        void install() throws IOException;
        /**
         * Continues the original approved image once after host revalidation.
         * @throws IOException if original continuation has uncertain effects
         */
        void resumeImage() throws IOException;
        /**
         * Copies and validates actual native allocation reports.
         * @throws IOException if event delivery fails
         */
        void pump() throws IOException;
        /**
         * Grants one exact actual native allocation and awaits its acknowledgement.
         * @param wait original validated native allocation wait
         * @throws IOException if permission has uncertain effects
         */
        void grant(AllocationWaits.Wait wait) throws IOException;
        /**
         * Withdraws allocation permissions without resuming blocked native allocations.
         * @throws IOException if withdrawal fails
         */
        void stop() throws IOException;
        /**
         * Closes all original native resources only after the supplied independent whole-tree zero barrier.
         * @param barrier actual host whole-tree zero observation
         * @throws IOException if original native ownership remains unresolved
         */
        void close(AllocationHook.ExecutionBarrier barrier) throws IOException;
    }
}
