package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Installs Darwin descriptor mediation while reusing the shared authenticated token protocol and attempt ledger.
 * The caller authenticates the held image and supplies the actual retained FIFO descriptor identity.
 * Live-image closure requires whole-workload execution impossibility; detached-image closure never retires the borrowed attempt capability.
 * Instances are confined to their constructing controller thread.
 */
public final class MacCargoTokenHook
{
    /** Packaged Darwin token mediation source. */
    private static final String RESOURCE = "mac-cargo-tokens.js";
    /** Shared protocol, delivery ordering, lifecycle and exact-byte accounting implementation. */
    private final CargoTokenHook delegate;

    /**
     * Retains a resource-free connection for one authenticated held Darwin image.
     * @param scope borrowed tooling services retained open through cleanup
     * @param attempt stable whole-attempt correlation
     * @param installation positive generation never reused within the attempt
     * @param pid authenticated positive unsigned32-bit process identifier
     * @param fifo identity independently read from the retained native FIFO descriptor
     * @param manager borrowed original native manager retained through cleanup
     * @param tokens borrowed whole-attempt accounting capability
     * @param program Darwin script without embedded NUL
     * @param timeout positive readiness duration representable in signed nanoseconds
     * @throws NullPointerException if a reference input is null
     * @throws IllegalArgumentException if identities, program or timeout are invalid
     */
    public MacCargoTokenHook(ToolingScope scope, UUID attempt, long installation, long pid,
        MacCargoNative.FifoIdentity fifo, FridaRuntime.Manager manager, LinuxCargoTokens.Attempt tokens,
        String program, Duration timeout)
    {
        this(scope, attempt, installation, pid, fifo, AllocationHook.newNativeConnection(manager, pid), tokens,
            program, timeout, System::nanoTime);
    }

    /**
     * Retains an owned resource-free transport for the same Darwin mediation operation.
     * @param scope borrowed tooling services
     * @param attempt whole-attempt correlation
     * @param installation positive generation
     * @param pid authenticated process identifier
     * @param fifo descriptor-bound native FIFO identity
     * @param connection resource-free connection whose ownership transfers on successful construction
     * @param tokens borrowed whole-attempt capability
     * @param program Darwin source without NUL
     * @param timeout positive readiness duration
     * @param nanoTime monotonic readiness clock
     * @throws NullPointerException if a reference input is null
     * @throws IllegalArgumentException if identity, program or timeout is invalid
     */
    public MacCargoTokenHook(ToolingScope scope, UUID attempt, long installation, long pid,
        MacCargoNative.FifoIdentity fifo, AllocationHook.Connection connection, LinuxCargoTokens.Attempt tokens,
        String program, Duration timeout, LongSupplier nanoTime)
    {
        Objects.requireNonNull(fifo, "fifo may not be null");
        // The shared mediator treats this triple as opaque text. Darwin uses dev:0:ino, not Linux device decomposition.
        LinuxFifoBindings.Identity wireIdentity = new LinuxFifoBindings.Identity(fifo.device(), 0, fifo.inode());
        this.delegate = new CargoTokenHook(scope, attempt, installation, pid, wireIdentity, connection, tokens,
            program, timeout, nanoTime);
    }

    /**
     * Reads the packaged Darwin source, preceded by the entry-patch functions it calls, before any native acquisition.
     * @return complete Darwin source
     * @throws IOException if a packaged resource is absent or unreadable
     */
    static String program() throws IOException
    {
        return MacEntryPatch.withPrelude(RESOURCE);
    }

    /**
     * Attempts native acquisition once and retains partial state on failure.
     * @throws IOException if acquisition fails or authority has been withdrawn
     * @throws IllegalStateException if off-thread or already attempted
     */
    public void start() throws IOException { delegate.start(); }

    /**
     * Awaits matching readiness while servicing peer images and gated children.
     * @param peers nonreentrant controller-thread peer pump
     * @throws IOException if readiness, delivery, peer service or interruption fails
     * @throws IllegalStateException if off-thread or start did not complete
     */
    public void awaitReady(AllocationHook.Pump peers) throws IOException { delegate.awaitReady(peers); }

    /**
     * Services bounded copied messages and available grants without blocking pending writes behind empty reads.
     * @throws IOException if framing, ordering, transfer or delivery fails, permanently withdrawing authority
     * @throws IllegalStateException if off-thread or start did not complete
     */
    public void pump() throws IOException { delegate.pump(); }

    /**
     * Permanently withdraws controls while retaining all native and attempt ownership.
     * @throws IllegalStateException if off-thread
     */
    void stop() { delegate.stop(); }

    /**
     * Destroys the image connection only after the complete workload can no longer execute.
     * @param barrier trusted whole-workload execution-impossibility observation
     * @throws IOException if the barrier is false or unknown, or destruction fails
     * @throws IllegalStateException if off-thread
     */
    public void close(AllocationHook.ExecutionBarrier barrier) throws IOException { delegate.close(barrier); }

    /**
     * Observes exact-image detachment without granting whole-workload terminal authority.
     * @return true only when the retained native connection reports detachment
     * @throws IOException if observation is uncertain
     * @throws IllegalStateException if called off-thread
     */
    public boolean detached() throws IOException { return delegate.detached(); }

    /**
     * Releases only an independently detached image, retaining the borrowed attempt capability.
     * @throws IOException if detachment is unproven or destruction remains uncertain
     * @throws IllegalStateException if called off-thread
     */
    public void closeDetached() throws IOException { delegate.closeDetached(); }

    /**
     * Observes the first retained operational failure.
     * @return first failure, or null if none has occurred
     * @throws IllegalStateException if off-thread
     */
    public IOException failure() { return delegate.failure(); }
}
