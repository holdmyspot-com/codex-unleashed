package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retains independent held-image readiness connections until a caller-authenticated execution barrier.
 * This internal connection owner has no platform identity, image admission or resumption authority.
 * Its caller serializes operations on its constructing thread and prevents callback reentry.
 */
final class HeldImageConnections
{
    /**
     * Scoped serializer borrowed through closure.
     */
    private final JsonMapper json;
    /**
     * Independent partial and retired connection generations.
     */
    private final List<Image> images = new ArrayList<>();
    /**
     * Positive monotonic receipt deadline in nanoseconds.
     */
    private final long timeout;
    /**
     * Caller-selected monotonic clock.
     */
    private final LongSupplier clock;
    /**
     * Whether every connection has closed successfully.
     */
    private boolean closed;
    /**
     * Retains scoped services and a validated deadline without acquiring native resources.
     * @param scope scoped serializer services
     * @param timeout positive representable receipt timeout
     * @param clock monotonic observations
     */
    HeldImageConnections(ToolingScope scope, Duration timeout, LongSupplier clock)
    {
        json = Objects.requireNonNull(scope, "scope").getLeaderJsonMapper();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive readiness timeout required");
        this.timeout = timeout.toNanos();
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    /**
     * Retains before acquisition and awaits the exact connection's distinct readiness marker.
     * @param connection resource-free connection owned on entry, including partial acquisition
     * @param prefix caller-owned protocol marker prefix
     * @return positive connection generation ordinal
     * @throws IOException if readiness is absent, interrupted, malformed or uncertain
     */
    long start(AllocationHook.Connection connection, String prefix) throws IOException
    {
        if (closed) throw new IllegalStateException("Connection owner is closed");
        var image = new Image(connection);
        images.add(image);
        String marker = prefix + ":" + images.size();
        image.connection.start("send(" + json.writeValueAsString(marker) + ");");
        long began = clock.getAsLong();
        while (true)
        {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Image readiness interrupted");
            if (clock.getAsLong() - began >= timeout) throw new IOException("Image readiness deadline exceeded");
            var frame = image.connection.pollMessage();
            if (Thread.currentThread().isInterrupted()) throw new IOException("Image readiness interrupted");
            if (clock.getAsLong() - began >= timeout) throw new IOException("Image readiness deadline exceeded");
            if (frame.isPresent())
            {
                var message = json.readTree(frame.orElseThrow());
                if (!message.isObject() || message.size() != 2 || !message.path("type").isString() ||
                    !message.path("type").asString().equals("send") || !message.path("payload").isString() ||
                    !message.path("payload").asString().equals(marker))
                    throw new IOException("Image did not acknowledge exact readiness");
                return images.size();
            }
            if (clock.getAsLong() - began >= timeout) throw new IOException("Image readiness deadline exceeded");
            try { Thread.sleep(1); }
            catch (InterruptedException cause)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Image readiness interrupted", cause);
            }
        }
    }
    /**
     * Returns the number of retained connection generations, including failed acquisition.
     * @return nonnegative retained generation count
     */
    int size() { return images.size(); }
    /**
     * Closes independent connections in reverse acquisition order only after authentic whole-workload zero.
     * Every connection receives at most one close attempt; failure retains its dependent ownership.
     * @param barrier caller-authenticated whole-workload execution observation
     * @throws IOException if the barrier is absent or a checked connection close failed
     */
    void close(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        if (closed) return;
        if (!barrier.reached()) throw new IOException("Whole-workload execution barrier is not established");
        Throwable cleanup = null;
        for (Image image : images.reversed())
        {
            if (!image.closeAttempted)
            {
                image.closeAttempted = true;
                try { image.connection.close(); image.closed = true; }
                catch (IOException | RuntimeException | Error cause) { image.failure = cause; }
            }
            if (image.failure != null)
            {
                if (cleanup == null) cleanup = image.failure;
                else suppress(cleanup, image.failure);
            }
        }
        closed = images.stream().allMatch(image -> image.closed);
        rethrow(cleanup);
    }
    /**
     * Reports successful independent native closure without replaying effects.
     * @return whether every retained generation closed successfully
     */
    boolean isClosed() { return closed; }
    /**
     * Adds independent cleanup evidence without self-suppression or duplicate references.
     * @param primary earlier failure
     * @param secondary independent later failure
     */
    static void suppress(Throwable primary, Throwable secondary)
    {
        if (primary == secondary || references(secondary, primary, Collections.newSetFromMap(new IdentityHashMap<>()))) return;
        for (Throwable prior : primary.getSuppressed()) if (prior == secondary) return;
        primary.addSuppressed(secondary);
    }

    /**
     * Avoids cycles when a cleanup exception already references the original operation failure.
     * @param source throwable graph being inspected
     * @param target original failure
     * @param visited identities already inspected
     * @return whether the source graph already retains the target
     */
    private static boolean references(Throwable source, Throwable target, Set<Throwable> visited)
    {
        if (source == target) return true;
        if (source == null || !visited.add(source)) return false;
        if (references(source.getCause(), target, visited)) return true;
        for (Throwable suppressed : source.getSuppressed()) if (references(suppressed, target, visited)) return true;
        return false;
    }

    /**
     * Rethrows a retained cleanup failure with its original type and identity.
     * @param cause retained failure or null
     * @throws IOException if the original failure is checked
     */
    private static void rethrow(Throwable cause) throws IOException
    {
        if (cause instanceof IOException checked) throw checked;
        if (cause instanceof RuntimeException unchecked) throw unchecked;
        if (cause instanceof Error fatal) throw fatal;
    }

    /**
     * Retains one independent image connection and its exact once-only cleanup outcome.
     */
    private static final class Image
    {
        /**
         * Connection owning its dependent script/session resources.
         */
        private final AllocationHook.Connection connection;
        /**
         * Whether native close has consumed its single opportunity.
         */
        private boolean closeAttempted;
        /**
         * Whether native close succeeded.
         */
        private boolean closed;
        /**
         * Exact retained uncertain close failure.
         */
        private Throwable failure;
        /**
         * Retains a resource-free connection before start.
         * @param connection independent image owner
         */
        private Image(AllocationHook.Connection connection) { this.connection = Objects.requireNonNull(connection, "connection"); }
    }
}
