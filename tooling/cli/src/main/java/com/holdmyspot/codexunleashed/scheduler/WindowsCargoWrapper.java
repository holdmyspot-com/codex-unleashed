package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.util.Objects;

/**
 * Registers one Windows compiler invocation and keeps its wrapper alive across controller-owned retries.
 * The one-shot operation never launches a compiler locally and never replays uncertain protocol effects.
 */
public final class WindowsCargoWrapper
{
    /** Codec borrowing invocation services. */
    private final WindowsCargoMessages messages;
    /** Immutable original compiler request and offered stream handles. */
    private final WindowsCargoMessages.Registration registration;
    /** Authenticated session acquisition boundary. */
    private final Opener opener;
    /** Indicates whether execution authority was consumed. */
    private boolean started;

    /**
     * Creates a resource-free wrapper coordinator.
     * @param scope borrowed invocation services
     * @param registration exact request and source stream offer
     * @param opener authenticated session acquisition owner
     */
    public WindowsCargoWrapper(WindowsCargoScope scope, WindowsCargoMessages.Registration registration, Opener opener)
    {
        this.messages = new WindowsCargoMessages(scope);
        this.registration = Objects.requireNonNull(registration, "registration");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Registers once, waits through retries, drains terminal output, and returns the exact native result.
     * A final result before stream-import acknowledgement is a protocol failure.
     * @return unsigned DWORD compiler result
     * @throws IOException if acquisition, protocol, stream completion or cleanup fails
     * @throws IllegalStateException if run was already attempted
     */
    public long run() throws IOException
    {
        if (started) throw new IllegalStateException("Cargo wrapper execution was already attempted");
        started = true;
        try (Session session = opener.open())
        {
            if (!registration.equals(session.registration())) throw new IOException("Cargo session changed the original registration");
            session.sendRegistration(messages.registration(registration));
            boolean accepted = false;
            while (true)
            {
                switch (messages.readResponse(session.awaitFrame()))
                {
                    case WindowsCargoMessages.Accepted ignored ->
                    {
                        if (accepted) throw new IOException("Cargo stream acceptance was repeated");
                        accepted = true;
                        session.accepted();
                    }
                    case WindowsCargoMessages.Final result ->
                    {
                        if (!accepted) throw new IOException("Cargo final result arrived before stream acceptance");
                        session.finish();
                        return result.status();
                    }
                }
            }
        }
    }

    /**
     * Acquires one fully authenticated wrapper session and cleans up partial acquisition on failure.
     */
    @FunctionalInterface
    public interface Opener
    {
        /**
         * Acquires owned streams and an independently authenticated server connection.
         * @return fully acquired session
         * @throws IOException if acquisition fails
         */
        Session open() throws IOException;
    }

    /**
     * Owns the wrapper's persistent stream endpoints and control connection through one logical compilation.
     */
    public interface Session extends AutoCloseable
    {
        /**
         * Returns the immutable source offer retained by this session.
         * @return represented request and source handle values
         */
        WindowsCargoMessages.Registration registration();

        /**
         * Publishes registration once without retry after uncertain publication.
         * @param bytes complete encoded registration
         * @throws IOException if publication fails
         */
        void sendRegistration(byte[] bytes) throws IOException;

        /**
         * Waits for a complete response without expiring an idle queued request.
         * @return complete encoded response
         * @throws IOException if reception fails
         */
        byte[] awaitFrame() throws IOException;

        /**
         * Records that the controller imported the frozen source stream endpoints.
         * @throws IOException if the stream owner cannot retain them
         */
        void accepted() throws IOException;

        /**
         * Closes unused producer ends and drains compiler output before reporting terminal status.
         * @throws IOException if stream completion fails
         */
        void finish() throws IOException;

        /**
         * Closes only owned resources and retains failures without replay of uncertain native closes.
         * @throws IOException if cleanup fails
         */
        @Override void close() throws IOException;
    }
}
