package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.Objects;

/**
 * Registers one Linux Cargo compiler wrapper and retains its session through authorized attempts and final status.
 * Instances are confined to one operation thread and execute once. The caller retains its Cargo scope through
 * execution; the session opener establishes native controller authority and owns cleanup of partial acquisition.
 */
final class LinuxCargoWrapper
{
    /** Codec borrowing invocation-scoped parsing services. */
    private final LinuxCargoMessages messages;
    /** Exact immutable compiler invocation that every authorized attempt must preserve. */
    private final LinuxProcessTree.Launch launch;
    /** Acquisition boundary for this wrapper's native session. */
    private final Opener opener;
    /** Indicates whether this wrapper has already consumed its one execution. */
    private boolean started;

    /**
     * Creates the wrapper before any session resources are acquired.
     * @param scope caller-owned Cargo services retained open through execution and cleanup
     * @param launch exact immutable compiler command, environment and execution context
     * @param opener creates one authenticated owned session and cleans up any partial acquisition
     * @throws NullPointerException if any input is null
     */
    LinuxCargoWrapper(LinuxCargoScope scope, LinuxProcessTree.Launch launch, Opener opener)
    {
        this.messages = new LinuxCargoMessages(Objects.requireNonNull(scope, "scope"));
        this.launch = Objects.requireNonNull(launch, "launch");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Runs registration and authorized attempts until the controller delivers the compiler's final result.
     * The session closes on success or failure. No failed operation or execution is replayed.
     * @return final compiler status from zero through 255
     * @throws IllegalStateException if execution was already attempted or the borrowed scope is closed
     * @throws IOException if acquisition, protocol interpretation, transfer or cleanup fails
     */
    int run() throws IOException
    {
        if (started) throw new IllegalStateException("Cargo wrapper execution was already attempted");
        started = true;
        try (Session session = opener.open())
        {
            var registration = session.registration();
            registration.requireExecution(launch);
            session.sendRegistration(messages.registration(registration));
            while (true)
            {
                switch (messages.readResponse(session.awaitFrame()))
                {
                    case LinuxCargoMessages.Attempt attempt ->
                    {
                        attempt.binding().requireExecution(launch);
                        session.transfer(attempt.binding());
                    }
                    case LinuxCargoMessages.Final result ->
                    {
                        session.finish();
                        return result.status();
                    }
                }
            }
        }
    }

    /**
     * Acquires one native session only after its controller authority has been established.
     */
    @FunctionalInterface
    interface Opener
    {
        /**
         * Opens a session or retains responsibility for cleaning up partial acquisition.
         * @return fully acquired, independently owned session with authenticated controller credentials
         * @throws IOException if acquisition or partial cleanup fails
         */
        Session open() throws IOException;
    }

    /**
     * Owns one wrapper's stream listener, control connection and private directory.
     * Originals 0, 1 and 2 and invocation scopes remain borrowed. Each failed transfer consumes its authority.
     */
    interface Session extends AutoCloseable
    {
        /**
         * Supplies the captured wrapper receipt without an attempt capability.
         * @return immutable initial binding with this session's wrapper, endpoint and terminal identity
         */
        LinuxBorrowedStreams.Binding registration();

        /**
         * Publishes one encoded registration without replay after uncertain publication.
         * @param bytes complete bounded registration payload, without a transport header
         * @throws IOException if publication fails
         */
        void sendRegistration(byte[] bytes) throws IOException;

        /**
         * Awaits a response interruptibly, applying the frame deadline after native readiness.
         * @return one complete bounded response payload without its transport header
         * @throws IOException if reception fails
         */
        byte[] awaitFrame() throws IOException;

        /**
         * Authorizes and consumes one descriptor transfer for this session and a strictly newer attempt.
         * @param binding controller-selected attempt receipt matching this session's immutable identity
         * @throws IOException if authorization or descriptor transfer fails
         */
        void transfer(LinuxBorrowedStreams.Binding binding) throws IOException;

        /**
         * Delivers the accepted attempt's remaining standard-error output to Cargo after the controller's final result.
         * A later transfer instead discards the incomplete output of the attempt it supersedes.
         * @throws IOException if the output cannot be delivered
         */
        void finish() throws IOException;

        /**
         * Closes owned endpoints before removing the original private directory.
         * Cleanup failures retain ownership and never replay an uncertain native close.
         * @throws IOException if owned cleanup fails
         */
        @Override void close() throws IOException;
    }
}
