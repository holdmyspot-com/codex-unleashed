package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;

/**
 * Owns one macOS workload's instrumentation, original root release, cancellation and terminal cleanup.
 * All operations belong to the constructing thread. The native owner and its original Frida manager
 * remain retained after failures until exact cancellation, native terminal observation and cleanup finish.
 */
final class MacWorkload
{
    /** Constructing thread required before every operation. */
    private final Thread thread = Thread.currentThread();
    /** Owns the service, anchor, original streams/wait and original Frida manager. */
    private final MacWorkloadNative nativeOwner;
    /** Owns all held image instrumentation generations until the terminal composition. */
    private final MacImages images;
    /** Indicates whether the original start opportunity was consumed. */
    private boolean attempted;
    /** Indicates whether a public transition is executing native or instrumentation code. */
    private boolean transitioning;
    /** Indicates whether image admissions have been withdrawn. */
    private boolean canceled;
    /** Indicates whether native cancellation has consumed its one opportunity. */
    private boolean cancellationAttempted;
    /** Indicates whether a native close was entered; uncertain effects are never replayed. */
    private boolean closeAttempted;
    /** Indicates whether both image and native ownership closed successfully. */
    private boolean closed;
    /** Original root result established only by the complete native terminal composition. */
    private OptionalInt result = OptionalInt.empty();
    /** First failure retained through independently necessary terminal cleanup. */
    private Throwable failure;

    /**
     * Retains native ownership before any instrumentation or original-root release.
     * @param scope caller-owned scoped shared services retained through cleanup
     * @param nativeOwner caller-retained native service owner; exclusive lifecycle transfers on successful construction
     * @param instrumentation required instrumentation for each authenticated held image
     * @param timeout positive representable image readiness bound
     * @throws NullPointerException if any input is null
     * @throws IllegalArgumentException if timeout is zero or negative
     * @throws ArithmeticException if timeout exceeds signed nanoseconds
     * @throws IOException if the original held root cannot be authenticated
     */
    MacWorkload(ToolingScope scope, MacWorkloadNative nativeOwner,
        MacImages.InstrumentationFactory instrumentation, Duration timeout) throws IOException
    {
        this.nativeOwner = Objects.requireNonNull(nativeOwner, "nativeOwner");
        this.images = new MacImages(scope, nativeOwner, nativeOwner, instrumentation,
            nativeOwner.root(), timeout, System::nanoTime);
    }

    /**
     * Arms the original held root before releasing its native gate once.
     * @throws IllegalStateException if called by another thread or after the first attempt
     * @throws IOException if authentication, instrumentation or release fails
     */
    void start() throws IOException
    {
        requireIdle();
        transitioning = true;
        try
        {
            if (attempted || canceled) throw new IllegalStateException("Workload start is permitted once");
            attempted = true;
            try
            {
                images.start();
                nativeOwner.releaseRoot();
            }
            catch (IOException | RuntimeException | Error cause)
            {
                fail(cause);
                throw cause;
            }
        }
        finally { transitioning = false; }
    }

    /**
     * Processes currently queued held images and attempts the complete terminal composition.
     * An observation failure withdraws admissions and starts exact native cancellation.
     * @return genuine original result after images and native resources close, or empty while active
     * @throws IllegalStateException if called by another thread or before start
     * @throws IOException if observation, instrumentation, cancellation or cleanup fails
     */
    OptionalInt advance() throws IOException
    {
        requireIdle();
        transitioning = true;
        try
        {
            if (!attempted && !canceled) throw new IllegalStateException("Workload has not started");
            if (closed)
            {
                rethrow(failure);
                return result;
            }
            try
            {
                if (!canceled)
                {
                    Optional<FridaRuntime.Child> child;
                    while ((child = nativeOwner.pollChild()).isPresent()) images.advance(child.orElseThrow());
                }
                OptionalInt observed = nativeOwner.terminalResult();
                if (observed.isEmpty()) return OptionalInt.empty();
                result = observed;
                images.close(() -> result.isPresent());
                if (closeAttempted) throw new IOException("Native workload cleanup previously failed; ownership remains retained", failure);
                closeAttempted = true;
                nativeOwner.close();
                closed = true;
            }
            catch (IOException | RuntimeException | Error cause)
            {
                fail(cause);
                throw cause;
            }
            rethrow(failure);
            return result;
        }
        finally { transitioning = false; }
    }

    /**
     * Returns the genuine root result only after image and native ownership close successfully.
     * This remains observable when cleanup completed after an earlier observation failure.
     * @return original root result after complete cleanup, or empty while any owned resource remains
     * @throws IllegalStateException if another thread calls or a transition reenters observation
     */
    OptionalInt completedResult()
    {
        requireIdle();
        return closed ? result : OptionalInt.empty();
    }

    /**
     * Withdraws further image admissions and starts identity-conditioned native cancellation.
     * @throws IllegalStateException if called by another thread
     * @throws IOException if exact cancellation cannot be confirmed
     */
    void cancel() throws IOException
    {
        requireIdle();
        transitioning = true;
        try
        {
            cancelOwned();
        }
        finally { transitioning = false; }
    }

    /**
     * Withdraws admissions and consumes the native cancellation opportunity inside an owned transition.
     * @throws IOException if exact cancellation cannot be confirmed
     */
    private void cancelOwned() throws IOException
    {
        canceled = true;
        if (closed || cancellationAttempted || result.isPresent()) return;
        cancellationAttempted = true;
        try { nativeOwner.cancel(); }
        catch (IOException | RuntimeException | Error cause)
        {
            if (failure == null) failure = cause;
            throw cause;
        }
    }

    /**
     * Retains the first failure and withdraws admissions before attempting independent cancellation.
     * @param cause operation failure to preserve through native terminal cleanup
     */
    private void fail(Throwable cause)
    {
        if (failure == null) failure = cause;
        try { cancelOwned(); }
        catch (IOException | RuntimeException | Error cleanup)
        {
            if (cleanup != cause) cause.addSuppressed(cleanup);
        }
    }

    /**
     * Rethrows the retained operation failure after independently required cleanup finishes.
     * @param cause retained failure, or null after normal completion
     * @throws IOException if the retained failure is checked
     */
    private static void rethrow(Throwable cause) throws IOException
    {
        if (cause instanceof IOException checked) throw checked;
        if (cause instanceof RuntimeException unchecked) throw unchecked;
        if (cause instanceof Error fatal) throw fatal;
    }

    /**
     * Requires thread confinement and an idle transition before touching owned native resources.
     * @throws IllegalStateException if another thread calls or an active transition is reentered
     */
    private void requireIdle()
    {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("MacWorkload belongs to its constructing thread");
        if (transitioning) throw new IllegalStateException("Workload controls cannot reenter an active transition");
    }
}
