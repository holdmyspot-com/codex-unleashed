package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the original namespace-local Bazel action owner's bounded control conversation.
 * The controller authenticates native identities independently before admitting a returned prepared child.
 * One thread performs all operations. A failed command or malformed receipt permanently withdraws authority;
 * uncertain sends are never repeated. Native reaping and host whole-tree zero are separate barriers.
 */
final class LinuxBazelControl implements AutoCloseable
{
    /** Thread exclusively operating this original action connection. */
    private final Thread owner = Thread.currentThread();
    /** Owned original action connection, retained across allocation-aborted attempts. */
    private final Connection connection;
    /** Next local attempt number; queue-wide ordinals remain the scheduler's responsibility. */
    private long nextAttempt = 1;
    /** Current local attempt, or zero before the first CREATE. */
    private long attempt;
    /** Original owner receipt; it is a claim until independent native authentication succeeds. */
    private final Identity identity;
    /** Indicates whether an attempt has been created and not yet reset or finalized. */
    private boolean active;
    /** Indicates whether result observation began for the prepared child. */
    private boolean waiting;
    /** Authentic owner's collected direct-child status, absent until its EXIT frame is consumed. */
    private Result result;
    /** Indicates whether final delivery was attempted, preventing any later command. */
    private boolean finalAttempted;
    /** Sticky first fault; subsequent calls must not repeat its possible effects. */
    private Throwable fault;
    /** Indicates whether owned connection cleanup was attempted. */
    private boolean closeAttempted;
    /** First uncertain cleanup failure, retained without repeating native close. */
    private Throwable closeFailure;

    /**
     * Consumes the original owner claim and checks invocation capability before any command is sent.
     * Construction transfers connection ownership, including when receipt validation fails.
     * @param connection original independently kernel-identified connection
     * @param capability exact 64-character lowercase hexadecimal invocation capability
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the capability is malformed
     * @throws IOException if receipt validation or connection cleanup fails
     */
    LinuxBazelControl(Connection connection, String capability) throws IOException
    {
        Objects.requireNonNull(capability, "capability");
        if (!capability.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bazel invocation capability must contain 64 lowercase hexadecimal characters");
        this.connection = Objects.requireNonNull(connection, "connection");
        try
        {
            String[] fields = fields(connection.receive(), 4, "OWNER");
            if (!MessageDigest.isEqual(capability.getBytes(StandardCharsets.US_ASCII),
                fields[1].getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("Bazel owner invocation capability differs");
            this.identity = new Identity(positive(fields[2]), positive(fields[3]));
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { connection.close(); }
            catch (IOException | RuntimeException | Error cleanup)
            {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Adapts the retained native socket transport without changing its kernel peer receipt or namespace guards.
     * The caller retains the original peer receipt for independent Bazel authentication; framing grants no admission.
     * @param connection original native connection, retained by the caller before acquisition can fail
     * @param capability exact invocation capability expected in the owner's initial frame
     * @return control owner borrowing no native service beyond the caller's retained tooling scope
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the capability is malformed
     * @throws IOException if owner receipt validation or connection cleanup fails
     */
    static LinuxBazelControl openNative(LinuxCargoTransport.Connection connection, String capability) throws IOException
    {
        return new LinuxBazelControl(new NativeConnection(connection), capability);
    }

    /**
     * Supplies the owner's untrusted namespace-local identity for independent host authentication.
     * @return original local PID and birth claim
     */
    Identity identity() { requireOwner(); return identity; }

    /**
     * Requests one fresh direct child, still blocked before workload execution.
     * The caller must authenticate and contain this child and establish hooks before releasing its image.
     * @return new child's local PID and birth claim
     * @throws IllegalStateException if another attempt is active or the connection is terminal
     * @throws IOException if publication or receipt is uncertain or malformed
     */
    Identity prepare() throws IOException
    {
        requireHealthy();
        if (active) throw new IllegalStateException("Bazel action already has an active attempt");
        long successor = Math.incrementExact(nextAttempt);
        attempt = nextAttempt;
        nextAttempt = successor;
        active = true;
        try
        {
            connection.send(frame("CREATE " + attempt));
            String[] receipt = fields(connection.receive(), 4, "PREPARED");
            requireAttempt(receipt[1]);
            return new Identity(positive(receipt[2]), positive(receipt[3]));
        }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }

    /**
     * Begins observing the native owner's actual wait status after host hook preparation.
     * The native owner reaps independently; this operation does not release or resume the workload.
     * @throws IllegalStateException if no attempt is prepared or collection was already requested
     * @throws IOException if an earlier control operation has uncertain effects
     */
    void collect() throws IOException
    {
        requireHealthy();
        if (!active || waiting) throw new IllegalStateException("Bazel attempt cannot begin collection");
        waiting = true;
    }

    /**
     * Consumes at most one collected native status without waiting for an absent frame.
     * Root collection alone does not establish whole-tree completion or an allocation abort.
     * @return unchanged collected status, or empty while the owner has no complete result
     * @throws IllegalStateException if collection was not requested
     * @throws IOException if the original receipt is malformed or native transport fails
     */
    Optional<Result> pollResult() throws IOException
    {
        requireHealthy();
        if (!waiting) throw new IllegalStateException("Bazel attempt collection was not requested");
        if (result != null) return Optional.of(result);
        try
        {
            Optional<byte[]> incoming = connection.poll();
            if (incoming.isEmpty()) return Optional.empty();
            String[] receipt = fields(incoming.orElseThrow(), 4, "EXIT");
            requireAttempt(receipt[1]);
            int status = unsigned(receipt[3]);
            boolean signaled = switch (receipt[2])
            {
                case "EXIT" -> false;
                case "SIGNAL" -> true;
                default -> throw new IOException("Bazel owner returned an unsupported wait status kind");
            };
            if (status > (signaled ? 64 : 255) || (signaled && status == 0))
                throw new IOException("Bazel owner wait status is outside its native range");
            result = new Result(signaled, status);
            return Optional.of(result);
        }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }

    /**
     * Invalidates declared partial outputs only after an allocation-aborted attempt was reaped and reached zero.
     * The scheduler must supply actual allocation-abort authority; an exit code alone never authorizes this call.
     * A successful reset retains the original owner and permits one fresh CREATE at its original action position.
     * @param barrier independent kernel-backed actual whole-attempt zero observation
     * @throws NullPointerException if the barrier is null
     * @throws IllegalStateException if no native status was collected
     * @throws IOException if zero is unknown, invalidation fails or a command has uncertain effects
     */
    void resetAborted(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireCollected(barrier);
        try
        {
            connection.send(frame("RESET " + attempt));
            String[] receipt = fields(connection.receive(), 2, "RESET");
            requireAttempt(receipt[1]);
            active = false;
            waiting = false;
            result = null;
        }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }

    /**
     * Returns a non-aborted collected action status through the original owner and Bazel process wrapper once.
     * Ordinary status 23 is preserved and never converted to a retry. Signal deaths use shell-compatible 128+signal.
     * @param barrier independent kernel-backed actual whole-attempt zero observation
     * @return final original action status, from zero through 255
     * @throws NullPointerException if the barrier is null
     * @throws IllegalStateException if no native status was collected
     * @throws IOException if zero is unknown or final delivery has uncertain effects
     */
    int finish(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireCollected(barrier);
        int status = result.signaled() ? 128 + result.status() : result.status();
        finalAttempted = true;
        try { connection.send(frame("FINAL " + attempt + " " + status)); return status; }
        catch (IOException | RuntimeException | Error failure) { fault = failure; throw failure; }
    }

    /**
     * Checks native collection and actual workload zero before finalization or partial-output invalidation.
     * @param barrier independent kernel-backed actual whole-attempt zero observation
     * @throws IOException if the observation is unknown or has not reached zero
     */
    private void requireCollected(AllocationHook.ExecutionBarrier barrier) throws IOException
    {
        requireHealthy();
        Objects.requireNonNull(barrier, "barrier");
        if (result == null) throw new IllegalStateException("Bazel attempt has not been reaped by its native owner");
        if (!barrier.reached()) throw new IOException("Bazel attempt still has executable workload processes");
    }

    /**
     * Checks that a native receipt belongs to this exact current local attempt.
     * @param value canonical positive local attempt number
     * @throws IOException if the receipt is stale, future or malformed
     */
    private void requireAttempt(String value) throws IOException
    {
        if (positive(value) != attempt) throw new IOException("Bazel receipt names another attempt");
    }

    /**
     * Decodes a complete small canonical ASCII control message.
     * @param bytes complete native frame payload
     * @param count exact field count
     * @param verb required first field
     * @return exact validated fields
     * @throws IOException if the message is malformed or names another operation
     */
    private static String[] fields(byte[] bytes, int count, String verb) throws IOException
    {
        if (bytes.length == 0 || bytes.length > 256) throw new IOException("Bazel control message exceeds its bound");
        for (byte value : bytes)
            if (value < 32 || value > 126) throw new IOException("Bazel control message is not canonical ASCII");
        String[] fields = new String(bytes, StandardCharsets.US_ASCII).split(" ", -1);
        if (fields.length != count || !fields[0].equals(verb))
            throw new IOException("Bazel control receipt has another operation or field count");
        for (String field : fields) if (field.isEmpty()) throw new IOException("Bazel control receipt has an empty field");
        return fields;
    }

    /**
     * Reads a canonical positive PID, birth or attempt value without accepting aliases.
     * @param value canonical positive decimal text
     * @return positive signed-long value
     * @throws IOException if the value is malformed, zero or too large
     */
    private static long positive(String value) throws IOException
    {
        try
        {
            long number = Long.parseLong(value);
            if (number <= 0 || !Long.toString(number).equals(value)) throw new NumberFormatException(value);
            return number;
        }
        catch (NumberFormatException failure) { throw new IOException("Bazel control identity is not canonical positive decimal", failure); }
    }

    /**
     * Reads a canonical nonnegative status without accepting signs or leading zeroes.
     * @param value canonical nonnegative decimal text
     * @return nonnegative status
     * @throws IOException if the status is malformed or too large
     */
    private static int unsigned(String value) throws IOException
    {
        if (value.equals("0")) return 0;
        long number = positive(value);
        if (number > Integer.MAX_VALUE) throw new IOException("Bazel control status is too large");
        return (int) number;
    }

    /**
     * Encodes one internally constructed canonical message.
     * @param message complete ASCII message without a newline
     * @return opaque frame payload
     */
    private static byte[] frame(String message) { return message.getBytes(StandardCharsets.US_ASCII); }

    /**
     * Rejects controls after uncertain effects, final delivery or cleanup.
     * @throws IOException if a prior operation failed
     */
    private void requireHealthy() throws IOException
    {
        requireOwner();
        if (fault != null) throw new IOException("Bazel action control retains an earlier uncertain operation", fault);
        if (finalAttempted || closeAttempted) throw new IllegalStateException("Bazel action control is terminal");
    }

    /**
     * Rejects cross-thread protocol operations.
     * @throws IllegalStateException if called by another thread
     */
    private void requireOwner()
    {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Bazel action control belongs to another thread");
    }

    /**
     * Closes the original connection once; it does not fabricate a result or retry a failed command.
     * Native owner EOF handling remains responsible for collecting any unfinished child.
     * @throws IOException if native descriptor cleanup fails or was previously unresolved
     */
    @Override public void close() throws IOException
    {
        requireOwner();
        if (closeFailure != null) throw new IOException("Bazel action connection cleanup remains unresolved", closeFailure);
        if (closeAttempted) return;
        closeAttempted = true;
        try { connection.close(); }
        catch (IOException | RuntimeException | Error failure) { closeFailure = failure; throw failure; }
    }

    /**
     * Namespace-local identity claim, requiring independent host mapping before admission.
     * @param pid positive PID in the original action namespace
     * @param birth positive kernel start ticks in that namespace's proc view
     */
    record Identity(long pid, long birth)
    {
        /**
         * Rejects nonpositive native identity claims.
         * @param pid positive namespace-local PID
         * @param birth positive native birth ticks
         * @throws IllegalArgumentException if either value is nonpositive
         */
        Identity { if (pid <= 0 || birth <= 0) throw new IllegalArgumentException("Bazel process identities must be positive"); }
    }

    /**
     * Original waitpid result; allocation abortion is established independently by the scheduler.
     * @param signaled indicates whether the child died from a signal rather than exiting
     * @param status original signal from 1 through 64, or original exit status from zero through 255
     */
    record Result(boolean signaled, int status)
    {
        /**
         * Validates the native status domain without inferring retries.
         * @param signaled indicates whether native wait reported signal termination
         * @param status original native status value
         * @throws IllegalArgumentException if the value lies outside its native status domain
         */
        Result
        {
            if (status < (signaled ? 1 : 0) || status > (signaled ? 64 : 255))
                throw new IllegalArgumentException("Bazel wait result has an invalid native status");
        }
    }

    /**
     * Reuses the original native framing, peer lifetime and ready-only complete-frame deadline.
     */
    private static final class NativeConnection implements Connection
    {
        /** Original socket retained through control cleanup and caller-owned scope lifetime. */
        private final LinuxCargoTransport.Connection connection;

        /**
         * Retains the existing socket without duplicating or importing numeric descriptors.
         * @param connection original native socket connection
         * @throws NullPointerException if the connection is null
         */
        private NativeConnection(LinuxCargoTransport.Connection connection)
        {
            this.connection = Objects.requireNonNull(connection, "connection");
        }
        @Override public void send(byte[] bytes) throws IOException { connection.send(bytes); }
        @Override public byte[] receive() throws IOException { return connection.read(); }
        @Override public Optional<byte[]> poll() throws IOException { return connection.pollFrame(); }
        @Override public void close() throws IOException { connection.close(); }
    }

    /**
     * Owns bounded opaque frames; implementations retain kernel peer evidence separately for authentication.
     * A failed operation must prohibit later frames, and polling must not wait for absent input.
     */
    interface Connection extends AutoCloseable
    {
        /**
         * Sends one complete bounded frame without retry after uncertain delivery.
         * @param bytes complete opaque payload
         * @throws IOException if delivery fails
         */
        void send(byte[] bytes) throws IOException;
        /**
         * Reads the expected next complete frame using the configured native frame deadline.
         * @return complete owned payload
         * @throws IOException if the frame is missing, malformed or transport fails
         */
        byte[] receive() throws IOException;
        /**
         * Reads one ready frame without waiting for absent input.
         * @return ready complete payload, or empty
         * @throws IOException if readiness or frame transport fails
         */
        Optional<byte[]> poll() throws IOException;
        /**
         * Retires this original owned connection once.
         * @throws IOException if cleanup remains unresolved
         */
        @Override void close() throws IOException;
    }
}
