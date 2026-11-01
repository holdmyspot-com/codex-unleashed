package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns one bounded transfer of the invoking process's inherited native standard descriptors.
 * The caller's descriptors remain borrowed. Close joins the sender before closing its private listener.
 * Construction and cleanup belong to the invocation thread; only the sender accesses the authorized transfer.
 */
final class LinuxCargoRootStreams implements AutoCloseable
{
    /** Immutable receipt containing the final jobserver environment. */
    private final LinuxBorrowedStreams.Binding binding;
    /** Owned endpoint cleanup, invoked only after the sender terminates. */
    private final LinuxAttempt.Close endpoint;
    /** Bounded native transfer and join wait. */
    private final Duration timeout;
    /** Sole asynchronous transfer owner. */
    private final Thread sender;
    /** Exact transfer failure published through thread termination. */
    private Throwable failure;
    /** Indicates whether endpoint closure has been attempted. */
    private boolean closeAttempted;
    /** Exact uncertain endpoint close failure, never replayed. */
    private Throwable closeFailure;

    /**
     * Creates and starts the native root sender after the final Cargo launch has been selected.
     * @param scope borrowed invocation services retained through close
     * @param directory existing controller-owned private0700 directory
     * @param launch final Cargo launch including the retained jobserver environment
     * @param timeout positive bounded transfer wait
     * @return retained transfer owner
     * @throws NullPointerException if an input is null
     * @throws IOException if listener acquisition or authorization fails
     */
    static LinuxCargoRootStreams open(LinuxCargoScope scope, Path directory, LinuxProcessTree.Launch launch,
        Duration timeout) throws IOException
    {
        UUID action = UUID.randomUUID();
        LinuxBorrowedStreams.Listener listener = LinuxBorrowedStreams.listen(scope.tooling(), directory, action);
        try
        {
            LinuxBorrowedStreams.Binding binding = new LinuxBorrowedStreams.Binding(action, 1, listener.wrapper(),
                listener.endpoint(), scope.capability(), listener.terminal(), launch);
            listener.authorize(binding);
            return new LinuxCargoRootStreams(binding, () -> listener.sendBorrowed(timeout), listener::close, timeout);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { listener.close(); }
            catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Starts one transfer using an owned endpoint and an immutable launch receipt.
     * @param binding approved final launch and authenticated sender identity
     * @param transfer one bounded descriptor send
     * @param endpoint owned listener close, consumed once after sender termination
     * @param timeout positive join bound representable as nanoseconds
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the timeout is nonpositive or exceeds signed nanoseconds
     */
    LinuxCargoRootStreams(LinuxBorrowedStreams.Binding binding, LinuxAttempt.Close transfer,
        LinuxAttempt.Close endpoint, Duration timeout)
    {
        this.binding = Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(transfer, "transfer");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Transfer timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("Transfer timeout exceeds signed nanoseconds", failure); }
        this.sender = new Thread(() ->
        {
            try { transfer.close(); }
            catch (IOException | RuntimeException | Error caught) { failure = caught; }
        }, "cargo-root-stream-transfer");
        sender.start();
    }

    /**
     * Returns the immutable root receipt supplied to the existing borrowed supervisor preparation.
     * @return final lease-adjusted launch receipt
     */
    LinuxBorrowedStreams.Binding binding() { return binding; }

    /**
     * Waits for transfer acknowledgement and propagates its original failure.
     * @throws IOException if transfer fails, remains live after the bound or the caller is interrupted
     */
    void await() throws IOException
    {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cargo root descriptor transfer interrupted");
        join();
        rethrow(failure);
    }

    /**
     * Interrupts and joins the sender before consuming endpoint cleanup once.
     * A live sender retains the endpoint; an uncertain endpoint close is never replayed.
     * @throws IOException if the sender remains live, joining is interrupted or endpoint closure fails
     */
    @Override public void close() throws IOException
    {
        sender.interrupt();
        join();
        if (!closeAttempted)
        {
            closeAttempted = true;
            try { endpoint.close(); }
            catch (IOException | RuntimeException | Error caught) { closeFailure = caught; }
        }
        rethrow(closeFailure);
    }

    /**
     * Establishes sender termination without swallowing the caller's interruption status.
     * Cleanup can still join a terminating sender when the coordinator already carries cancellation.
     * @throws IOException if the sender remains live or joining is interrupted
     */
    private void join() throws IOException
    {
        boolean interrupted = Thread.interrupted();
        try
        {
            if (!sender.join(timeout)) throw new IOException("Cargo root descriptor sender remains live after the control timeout");
        }
        catch (InterruptedException caught)
        {
            interrupted = true;
            throw new IOException("Interrupted while awaiting Cargo root descriptor transfer", caught);
        }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * Preserves checked and unchecked native failure identities.
     * @param failure exact failure, or null on success
     * @throws IOException if the original failure is checked
     */
    private static void rethrow(Throwable failure) throws IOException
    {
        if (failure instanceof IOException checked) throw checked;
        if (failure instanceof RuntimeException unchecked) throw unchecked;
        if (failure instanceof Error fatal) throw fatal;
    }
}
