package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Exchanges Cargo control frames over scoped Linux x64 or ARM64 glibc native sockets.
 * Each owner is confined to its operation thread. The caller keeps its Pouch scope open through cleanup and supplies
 * the endpoint namespace and permissions before starting wrappers. Captured peer credentials identify a connection;
 * the invocation must separately authenticate Cargo ancestry and compiler context before granting request authority.
 * This transport neither transfers standard descriptors nor interprets JSON or compiler outcomes.
 */
final class LinuxCargoTransport
{
    /** Largest complete Cargo registration or response payload; the four-byte header is separate. */
    static final int MAX_FRAME_BYTES = 1048576;

    /** Prevents construction of the socket acquisition entry points. */
    private LinuxCargoTransport() {}

    /**
     * Binds one owned controller endpoint without granting compiler or stream authority.
     * @param scope borrowed native functions kept open until this listener and accepted sockets close
     * @param endpoint absolute, representable Unix socket pathname in the caller's controlled namespace
     * @param backlog positive number of pending wrapper connections
     * @param timeout positive representable bound for each complete frame on accepted connections
     * @return listener polled by the controller's operation thread
     * @throws IllegalArgumentException if the pathname, backlog or timeout is invalid
     * @throws IOException if binding or owned cleanup fails
     * @throws LinuxBorrowedStreams.CleanupFailure if partial acquisition retains an endpoint cleanup owner
     */
    static Listener listen(ToolingScope scope, Path endpoint, int backlog, Duration timeout) throws IOException
    {
        validate(endpoint, timeout);
        if (backlog <= 0) throw new IllegalArgumentException("Cargo control backlog must be positive");
        var bindings = Objects.requireNonNull(scope, "scope").getLinuxBorrowedStreamBindings();
        Listener owner = new Listener(timeout);
        owner.nativeListener = bindings.open(endpoint, backlog);
        return owner;
    }

    /**
     * Connects to an already published controller and captures its kernel peer before sending any frame.
     * @param scope borrowed native functions retained open through connection cleanup
     * @param endpoint absolute trusted controller endpoint
     * @param timeout positive representable bound for each complete control frame
     * @return owned connection whose peer must be authenticated before use
     * @throws IllegalArgumentException if the endpoint or timeout is invalid
     * @throws IOException if connection, peer inspection or cleanup fails
     */
    static Connection connect(ToolingScope scope, Path endpoint, Duration timeout) throws IOException
    {
        validate(endpoint, timeout);
        var bindings = Objects.requireNonNull(scope, "scope").getLinuxBorrowedStreamBindings();
        Connection owner = new Connection(timeout);
        owner.socket = bindings.connect(endpoint);
        return owner;
    }

    /**
     * Rejects invalid context before any native descriptor is acquired.
     * @param endpoint complete absolute native endpoint
     * @param timeout complete-frame control bound
     */
    private static void validate(Path endpoint, Duration timeout)
    {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        int length = endpoint.toString().getBytes(StandardCharsets.UTF_8).length;
        if (!endpoint.isAbsolute() || length >= 108)
            throw new IllegalArgumentException("Cargo control endpoint must be absolute and shorter than 108 UTF-8 bytes");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Cargo control timeout must be positive");
        try { timeout.toNanos(); }
        catch (ArithmeticException cause) { throw new IllegalArgumentException("Cargo control timeout exceeds nanosecond range", cause); }
    }

    /** Retains one native listener; accepted connections have independent ownership. */
    static final class Listener implements AutoCloseable
    {
        /** Native descriptor and endpoint owner, retained immediately after acquisition and before publication. */
        private LinuxBorrowedStreams.NativeBindings.Listener nativeListener;
        /** Complete-frame bound inherited by every accepted connection. */
        private final Duration timeout;
        /** Whether cleanup has begun, permanently preventing further acceptance. */
        private boolean closeAttempted;
        /** Whether listener cleanup completed successfully. */
        private boolean closed;
        /** Sticky failure retained instead of replaying an uncertain native close. */
        private Throwable closeFailure;

        /**
         * Creates the controller owner before its native listener is acquired.
         * @param timeout accepted connections' complete-frame bound
         */
        private Listener(Duration timeout)
        {
            this.timeout = timeout;
        }

        /**
         * Accepts at most one connection without waiting for an absent wrapper.
         * @return independently owned connection, or empty when no connection is ready
         * @throws IOException if native acceptance or peer identity capture fails
         * @throws IllegalStateException if listener cleanup has begun
         */
        Optional<Connection> poll() throws IOException
        {
            if (closeAttempted) throw new IllegalStateException("Cargo listener cleanup has begun");
            Connection owner = new Connection(timeout);
            Optional<Connection> result = Optional.of(owner);
            owner.socket = nativeListener.accept().orElse(null);
            if (owner.socket == null) return Optional.empty();
            return result;
        }

        /**
         * Reports completed native descriptor and original-endpoint cleanup without performing effects.
         * @return whether this listener closed successfully
         */
        boolean isClosed() { return closed; }

        /**
         * Closes only this listener, leaving accepted connections independently owned.
         * @throws IOException if cleanup fails or an earlier uncertain cleanup remains retained
         */
        @Override public void close() throws IOException
        {
            if (closed) return;
            if (closeAttempted) throw new IOException("Earlier Cargo listener cleanup did not complete", closeFailure);
            closeAttempted = true;
            try
            {
                nativeListener.close();
                closed = true;
            }
            catch (IOException | RuntimeException | Error failure)
            {
                closeFailure = failure;
                throw failure;
            }
        }
    }

    /** Owns a peer connection; a failed frame permanently prevents further protocol operations. */
    static final class Connection implements AutoCloseable
    {
        /** Native connection, retained immediately after acquisition and independently of the listener. */
        private LinuxBorrowedStreams.NativeBindings.Socket socket;
        /** Immutable bound applied to each complete header-and-payload operation. */
        private final Duration timeout;
        /** Retained first frame failure; no later read or send can replay an uncertain operation. */
        private Throwable fault;

        /**
         * Creates the frame owner before a native socket is acquired.
         * @param timeout complete frame bound
         */
        private Connection(Duration timeout)
        {
            this.timeout = timeout;
        }

        /**
         * Returns the original kernel peer receipt; this does not authenticate a compiler request.
         * @return captured PID, birth ticks and effective credentials
         */
        LinuxBorrowedStreams.Peer peer() { return socket.peer(); }

        /**
         * Sends one complete opaque frame using a shared header-and-payload deadline.
         * The caller may not retry this connection after a transfer failure.
         * @param bytes payload of one through {@value LinuxCargoTransport#MAX_FRAME_BYTES} bytes
         * @throws IllegalArgumentException if payload length is outside the protocol bound
         * @throws IOException if this frame fails or an earlier frame has failed
         */
        void send(byte[] bytes) throws IOException
        {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES)
                throw new IllegalArgumentException("Cargo control payload must contain 1 through " + MAX_FRAME_BYTES + " bytes");
            requireHealthy();
            try
            {
                long deadline = LinuxBorrowedStreams.NativeBindings.deadline(timeout);
                socket.write(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array(), deadline);
                socket.write(bytes, deadline);
            }
            catch (IOException | RuntimeException | Error failure)
            {
                fault = failure;
                throw failure;
            }
        }

        /**
         * Reads one complete opaque frame without allocating an unbounded peer-supplied payload.
         * @return newly owned exact payload bytes
         * @throws IOException if length, EOF, deadline or native transfer fails, or an earlier frame failed
         */
        byte[] read() throws IOException
        {
            requireHealthy();
            try
            {
                long deadline = LinuxBorrowedStreams.NativeBindings.deadline(timeout);
                byte[] header = new byte[Integer.BYTES];
                socket.read(header, deadline);
                int length = ByteBuffer.wrap(header).getInt();
                if (length < 1 || length > MAX_FRAME_BYTES)
                    throw new IOException("Cargo control frame length is outside 1 through " + MAX_FRAME_BYTES + ": " + length);
                byte[] bytes = new byte[length];
                socket.read(bytes, deadline);
                return bytes;
            }
            catch (IOException | RuntimeException | Error failure)
            {
                fault = failure;
                throw failure;
            }
        }

        /**
         * Consumes one ready complete frame without waiting for an idle action peer.
         * A frame deadline starts only after native readiness; incomplete ready frames retain the existing bound.
         * The single operation thread must also service scheduler and instrumentation events between calls.
         * @return complete payload, or empty when no data, closure or error is ready
         * @throws IOException if native polling or a ready frame fails, or an earlier frame failed
         */
        Optional<byte[]> pollFrame() throws IOException
        {
            requireHealthy();
            try
            {
                if (!socket.readable()) return Optional.empty();
                return Optional.of(read());
            }
            catch (IOException | RuntimeException | Error failure)
            {
                fault = failure;
                throw failure;
            }
        }

        /**
         * Waits interruptibly for a response, then applies the complete-frame transfer bound.
         * An idle compiler or queue may exceed the frame timeout. Once native readiness reports data, closure or an
         * error, the existing bounded read consumes the frame or permanently faults this connection. Each connection
         * has one operation thread and one reader, so another reader cannot consume data between these operations.
         * @return newly owned exact payload bytes
         * @throws IOException if interrupted, EOF or an invalid or stalled frame occurs, or an earlier frame failed
         */
        byte[] awaitFrame() throws IOException
        {
            requireHealthy();
            try
            {
                socket.awaitReadable();
                return read();
            }
            catch (IOException | RuntimeException | Error failure)
            {
                fault = failure;
                throw failure;
            }
        }

        /**
         * Rejects replay after a failed complete-frame operation.
         * @throws IOException if this connection has an earlier frame failure
         */
        private void requireHealthy() throws IOException
        {
            if (fault != null) throw new IOException("Cargo control connection retains an earlier frame failure", fault);
        }

        /**
         * Reports successful descriptor cleanup without replaying effects.
         * @return whether the native connection closed successfully
         */
        boolean isClosed() { return socket.isClosed(); }

        /**
         * Closes this connection's descriptor once, including after a protocol fault.
         * @throws IOException if native cleanup is unresolved
         */
        @Override public void close() throws IOException { socket.close(); }
    }
}
