package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.Objects;

/**
 * Owns one authenticated Windows wrapper control connection and persistent stream owner.
 * Construction transfers resources only on success; its caller retains partial-acquisition cleanup.
 */
final class WindowsCargoWrapperSession implements WindowsCargoWrapper.Session
{
    /** Immutable source registration held through every retry. */
    private final WindowsCargoMessages.Registration registration;
    /** Owned native control connection. */
    private final WindowsCargoTransport.Connection connection;
    /** Owned persistent wrapper stream endpoints. */
    private final WindowsCargoStreams streams;
    /** Indicates whether registration publication was attempted. */
    private boolean sent;
    /** Indicates whether stream-import acknowledgement was received. */
    private boolean accepted;
    /** Indicates whether terminal output drain was attempted. */
    private boolean finished;
    /** Indicates whether cleanup has begun. */
    private boolean closed;
    /** Original cleanup failure, retained across repeated calls. */
    private IOException closeFailure;

    /**
     * Takes a connection only after caller-owned native server authentication succeeds.
     * @param registration immutable request and source stream offer
     * @param connection transferred authenticated control connection
     * @param streams transferred stream owner whose offer equals registration handles
     */
    WindowsCargoWrapperSession(WindowsCargoMessages.Registration registration,
        WindowsCargoTransport.Connection connection, WindowsCargoStreams streams)
    {
        this.registration = Objects.requireNonNull(registration, "registration");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.streams = Objects.requireNonNull(streams, "streams");
        if (registration.input() != streams.input() || registration.output() != streams.output() ||
            registration.error() != streams.error()) throw new IllegalArgumentException("Registration differs from retained stream offer");
    }

    @Override public WindowsCargoMessages.Registration registration() { return registration; }

    @Override public void sendRegistration(byte[] bytes) throws IOException
    {
        requireOpen();
        if (sent) throw new IOException("Cargo registration publication was already attempted");
        sent = true;
        connection.send(bytes);
    }

    @Override public byte[] awaitFrame() throws IOException
    {
        requireOpen();
        if (!sent) throw new IOException("Cargo response cannot precede registration");
        return connection.awaitFrame();
    }

    @Override public void accepted() throws IOException
    {
        requireOpen();
        if (!sent || accepted) throw new IOException("Cargo acceptance is out of order or repeated");
        accepted = true;
        streams.accepted();
    }

    @Override public void finish() throws IOException
    {
        requireOpen();
        if (!accepted || finished) throw new IOException("Cargo output drain is out of order or repeated");
        finished = true;
        streams.finish();
    }

    /**
     * Rejects further protocol work after cleanup starts.
     * @throws IOException if cleanup already failed
     * @throws IllegalStateException if closed
     */
    private void requireOpen() throws IOException
    {
        if (closeFailure != null) throw closeFailure;
        if (closed) throw new IllegalStateException("Cargo wrapper session is closed");
    }

    @Override public void close() throws IOException
    {
        if (closeFailure != null) throw closeFailure;
        if (closed) return;
        closed = true;
        try { connection.close(); }
        catch (IOException failure) { closeFailure = failure; }
        try { streams.close(); }
        catch (IOException failure)
        {
            if (closeFailure == null) closeFailure = failure;
            else closeFailure.addSuppressed(failure);
        }
        if (closeFailure != null) throw closeFailure;
    }
}
