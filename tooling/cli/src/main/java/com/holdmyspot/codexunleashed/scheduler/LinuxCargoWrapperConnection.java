package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Drives the controller end of one native Cargo wrapper connection through protocol version 2.
 * <p>
 * The wrapper registers once, then reports each forked compiler child while the child is blocked. This owner checks
 * that every reported child is a live direct child of the connection's kernel peer with the reported start time, and
 * enforces the order of the protocol: one {@code release} or {@code abort} per child, then {@code exited}, then
 * {@code retry} or {@code final}. Any message out of order, any malformed message and any identity mismatch closes the
 * connection, which makes the wrapper kill and reap its child and exit 125. Because the wrapper never exits while
 * its child is unreaped, a verified child pid cannot be reused while this connection is open.
 * <p>
 * Only the constructing thread may call this owner. It owns the accepted connection.
 */
final class LinuxCargoWrapperConnection implements AutoCloseable
{
    /** Position of the connection in the protocol. */
    enum State
    {
        /** Accepted; the registration has not been read. */
        REGISTERING,
        /** The wrapper is forking the next attempt's child. */
        AWAITING_CHILD,
        /** A verified child is blocked until the controller releases or aborts it. */
        HELD,
        /** The child may run the compiler; the wrapper reports its exit. */
        RELEASED,
        /** The wrapper is killing the child; it reports its exit. */
        ABORTED,
        /** The child was reaped; the wrapper awaits {@code retry} or {@code final}. */
        EXITED,
        /** The final status was sent and the connection closed. */
        FINISHED
    }

    /** Exclusive calling thread. */
    private final Thread owner = Thread.currentThread();
    /** Owned accepted connection. */
    private final LinuxCargoTransport.Connection connection;
    /** Kernel peer captured when the connection was accepted. */
    private final LinuxBorrowedStreams.Peer peer;
    /** Current protocol position. */
    private State state = State.REGISTERING;
    /** Attempt number the wrapper reports next or is running, counting from 1. */
    private long attempt = 1;
    /** Verified child of the current attempt, or null before it is reported. */
    private LinuxCargoProtocol.Child child;
    /** Raw wait status of the current attempt, valid in {@link State#EXITED}. */
    private long waitStatus;
    /** First failure; the connection is closed once it is set. */
    private IOException failure;

    /**
     * Takes ownership of an accepted connection without reading from it.
     * @param connection accepted connection whose peer is the wrapper process
     * @throws NullPointerException if {@code connection} is null
     */
    LinuxCargoWrapperConnection(LinuxCargoTransport.Connection connection)
    {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.peer = connection.peer();
    }

    /**
     * Returns the kernel peer captured at acceptance; this alone authenticates nothing.
     * @return wrapper process identity and effective credentials
     */
    LinuxBorrowedStreams.Peer peer()
    {
        return peer;
    }

    /**
     * Reads the wrapper's registration, which it sends right after connecting.
     * @return decoded registration, not yet authenticated
     * @throws IllegalStateException if the registration was already read
     * @throws IOException           if the first frame is missing, late, malformed or not a registration
     */
    LinuxCargoProtocol.Register register() throws IOException
    {
        requireOwner();
        requireState(State.REGISTERING);
        requireHealthy();
        try
        {
            if (!(LinuxCargoProtocol.decode(connection.read()) instanceof LinuxCargoProtocol.Register register))
                throw new IOException("The first Cargo wrapper message is not a registration");
            state = State.AWAITING_CHILD;
            return register;
        }
        catch (IOException | RuntimeException cause)
        {
            throw fail(cause);
        }
    }

    /**
     * Consumes at most one message that is already readable, without waiting for an idle wrapper.
     * @throws IOException if the connection is lost or the wrapper breaks the protocol
     */
    void pump() throws IOException
    {
        requireOwner();
        requireHealthy();
        if (state == State.FINISHED || state == State.REGISTERING) return;
        try
        {
            var frame = connection.pollFrame();
            if (frame.isPresent()) accept(LinuxCargoProtocol.decode(frame.get()));
        }
        catch (IOException | RuntimeException cause)
        {
            throw fail(cause);
        }
    }

    /**
     * Returns the current attempt's child, waiting for its report within the frame timeout if needed.
     * The wrapper reports a child right after its registration or a retry, without waiting for the controller.
     * @return verified blocked child
     * @throws IllegalStateException if the connection is neither awaiting nor holding a child
     * @throws IOException           if the report is late or invalid, or the connection is lost
     */
    LinuxCargoProtocol.Child awaitChild() throws IOException
    {
        requireOwner();
        requireHealthy();
        if (state == State.AWAITING_CHILD)
        {
            try
            {
                accept(LinuxCargoProtocol.decode(connection.read()));
            }
            catch (IOException | RuntimeException cause)
            {
                throw fail(cause);
            }
        }
        requireState(State.HELD);
        return child;
    }

    /**
     * Returns the protocol position.
     * @return current state
     */
    State state()
    {
        requireOwner();
        return state;
    }

    /**
     * Returns the current attempt's verified child.
     * @return child reported for the current attempt
     * @throws IllegalStateException if the current attempt has not reported a child
     */
    LinuxCargoProtocol.Child child()
    {
        requireOwner();
        if (child == null) throw new IllegalStateException("The current attempt has no child");
        return child;
    }

    /**
     * Returns the raw wait status of the current attempt.
     * @return wait status reported by {@code exited}
     * @throws IllegalStateException if the current attempt has not exited
     */
    long waitStatus()
    {
        requireOwner();
        requireState(State.EXITED);
        return waitStatus;
    }

    /**
     * Lets the held child execute the compiler.
     * @throws IllegalStateException if no child is held
     * @throws IOException           if sending fails
     */
    void release() throws IOException
    {
        command(State.HELD, LinuxCargoProtocol.release(attempt), State.RELEASED);
    }

    /**
     * Makes the wrapper kill the held child before it executes anything.
     * @throws IllegalStateException if no child is held
     * @throws IOException           if sending fails
     */
    void abort() throws IOException
    {
        command(State.HELD, LinuxCargoProtocol.abort(attempt), State.ABORTED);
    }

    /**
     * Makes the wrapper discard the exited attempt and fork the next one.
     * @throws IllegalStateException if the current attempt has not exited
     * @throws IOException           if sending fails or the attempt number would overflow
     */
    void retry() throws IOException
    {
        long next;
        try
        {
            next = Math.incrementExact(attempt);
        }
        catch (ArithmeticException cause)
        {
            throw fail(new IOException("Cargo wrapper attempt number overflows", cause));
        }
        command(State.EXITED, LinuxCargoProtocol.retry(), State.AWAITING_CHILD);
        attempt = next;
        child = null;
    }

    /**
     * Sends the wrapper's exit status and closes the connection.
     * @param status exit status from 0 through 255
     * @throws IllegalArgumentException if {@code status} is outside 0 through 255
     * @throws IllegalStateException    if the current attempt has not exited
     * @throws IOException              if sending or closing fails
     */
    void finish(int status) throws IOException
    {
        command(State.EXITED, LinuxCargoProtocol.finalStatus(status), State.FINISHED);
        connection.close();
    }

    /**
     * Reports whether the connection is closed, either after the final status or after a failure.
     * @return whether no further message can be exchanged
     */
    boolean isClosed()
    {
        return connection.isClosed();
    }

    /**
     * Closes the connection; a wrapper that has not received its final status then kills its child and exits 125.
     * @throws IOException if native cleanup fails
     */
    @Override public void close() throws IOException
    {
        if (!connection.isClosed()) connection.close();
    }

    /**
     * Applies one wrapper message to the current state.
     * @param message decoded message
     * @throws IOException if the message is out of order or its child cannot be verified
     */
    private void accept(LinuxCargoProtocol.Message message) throws IOException
    {
        switch (message)
        {
            case LinuxCargoProtocol.Child reported when state == State.AWAITING_CHILD && reported.attempt() == attempt ->
            {
                verify(reported);
                child = reported;
                state = State.HELD;
            }
            case LinuxCargoProtocol.Exited exited when (state == State.RELEASED || state == State.ABORTED) &&
                exited.attempt() == attempt ->
            {
                waitStatus = exited.waitStatus();
                state = State.EXITED;
            }
            default -> throw new IOException("Cargo wrapper sent " + message.getClass().getSimpleName() +
                " out of order in state " + state + " for attempt " + attempt);
        }
    }

    /**
     * Requires the reported child to be a live direct child of the wrapper with the reported start time.
     * @param reported child report
     * @throws IOException if the process record differs or the wrapper's identity changed
     */
    private void verify(LinuxCargoProtocol.Child reported) throws IOException
    {
        peer.requireLive();
        String stat;
        try
        {
            stat = Files.readString(Path.of("/proc", Long.toString(reported.pid()), "stat"));
        }
        catch (IOException cause)
        {
            throw new IOException("Cargo wrapper child " + reported.pid() + " is not live", cause);
        }
        int end = stat.lastIndexOf(')');
        String[] fields = end < 0 ? new String[0] : stat.substring(end + 1).strip().split(" ");
        if (fields.length < 20) throw new IOException("Cargo wrapper child record is malformed: " + reported.pid());
        if (!fields[1].equals(Long.toString(peer.pid())))
            throw new IOException("Cargo wrapper child " + reported.pid() + " is not a child of wrapper " + peer.pid());
        if (!fields[19].equals(Long.toString(reported.startTime())))
            throw new IOException("Cargo wrapper child " + reported.pid() + " start time differs from its report");
    }

    /**
     * Sends one command from the required state and enters the next state.
     * @param required state the command is valid in
     * @param payload  encoded command
     * @param next     state after a successful send
     * @throws IllegalStateException if the connection is not in {@code required}
     * @throws IOException           if sending fails
     */
    private void command(State required, byte[] payload, State next) throws IOException
    {
        requireOwner();
        requireHealthy();
        requireState(required);
        try
        {
            connection.send(payload);
        }
        catch (IOException | RuntimeException cause)
        {
            throw fail(cause);
        }
        state = next;
    }

    /**
     * Records the first failure and closes the connection so the wrapper stops its child.
     * @param cause failure
     * @return contextual failure to throw
     */
    private IOException fail(Throwable cause)
    {
        IOException failed = new IOException("Cargo wrapper connection from pid " + peer.pid() + " failed in state " +
            state, cause);
        if (failure == null) failure = failed;
        try
        {
            close();
        }
        catch (IOException | RuntimeException cleanup)
        {
            failed.addSuppressed(cleanup);
        }
        return failed;
    }

    /**
     * Rejects use after a failure.
     * @throws IOException if an earlier operation failed
     */
    private void requireHealthy() throws IOException
    {
        if (failure != null) throw new IOException("Cargo wrapper connection failed earlier", failure);
    }

    /**
     * Rejects a step from the wrong state without touching the connection.
     * @param required expected state
     * @throws IllegalStateException if the state differs
     */
    private void requireState(State required)
    {
        if (state != required)
            throw new IllegalStateException("Cargo wrapper connection is " + state + ", not " + required);
    }

    /**
     * Requires the constructing thread.
     */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Cargo wrapper connection belongs to its constructing thread");
    }
}
