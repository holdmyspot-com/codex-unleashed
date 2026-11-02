package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns one Linux Cargo wrapper's authenticated control connection and private stream endpoint.
 * The caller confines this owner to its operation thread and keeps its tooling scope open through cleanup.
 * Original standard descriptors remain borrowed. Acquisition authenticates the captured controller UID before
 * creating a stream endpoint or publishing registration; it does not establish Cargo ancestry for the controller.
 */
final class LinuxCargoWrapperSession implements LinuxCargoWrapper.Session
{
    /**
     * Immutable complete-frame and descriptor-transfer bound.
     */
    private final Duration timeout;
    /**
     * Owned controller connection, recorded immediately after acquisition.
     */
    private LinuxCargoTransport.Connection control;
    /**
     * Owned stream listener, recorded immediately after acquisition.
     */
    private LinuxBorrowedStreams.Listener streams;
    /**
     * Failed native acquisition whose remaining cleanup owner must stay reachable.
     */
    private LinuxBorrowedStreams.CleanupFailure partial;
    /**
     * Exclusively created directory retained until endpoint cleanup completes.
     */
    private Path directory;
    /**
     * Original directory identity, preventing removal of a replacement pathname.
     */
    private Object directoryIdentity;
    /**
     * Captured registration without an attempt capability.
     */
    private LinuxBorrowedStreams.Binding registration;
    /**
     * Prevents replay after registration publication was attempted.
     */
    private boolean registrationSent;
    /**
     * Permanently withdraws protocol authority once cleanup starts.
     */
    private boolean closing;
    /**
     * Native functions borrowed from the invocation scope.
     */
    private LinuxBorrowedStreams.NativeBindings bindings;
    /**
     * Relay of the compiler's standard error to Cargo across attempts, or null when the original standard error is a
     * terminal and attempts write to it directly.
     */
    private CompilerErrorRelay errorRelay;
    /**
     * Standard-error pipe of the last transferred attempt until its output ends, or null.
     */
    private LinuxCompilerErrorPipe errorPipe;

    /**
     * Creates the resource owner before acquiring its connection or filesystem state.
     * @param timeout immutable control-frame and descriptor-transfer bound
     */
    private LinuxCargoWrapperSession(Duration timeout)
    {
        this.timeout = timeout;
    }

    /**
     * Acquires a session after checking the captured controller's effective UID is zero.
     * The supplied parent must be an absolute directory in the invocation's controlled namespace. Its wrapper
     * directory is named for the current PID, matching controller authentication; an existing name is rejected.
     * Failure preserves the original thrown object and retains any incomplete cleanup through a suppressed owner.
     * @param scope caller-owned native services retained open through cleanup
     * @param controller trusted controller endpoint
     * @param registrationDirectory caller-controlled parent for an exclusive 0700 wrapper directory
     * @param action immutable compiler action identity
     * @param launch exact compiler command, environment and execution context
     * @param timeout positive representable control-frame and descriptor-transfer bound
     * @return fully acquired session owning both endpoints and its private directory
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if a path or timeout is invalid
     * @throws IOException if acquisition, authentication or partial cleanup fails
     */
    static LinuxCargoWrapper.Session open(ToolingScope scope, Path controller, Path registrationDirectory,
        UUID action, LinuxProcessTree.Launch launch, Duration timeout) throws IOException
    {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(registrationDirectory, "registrationDirectory");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(timeout, "timeout");
        if (!registrationDirectory.isAbsolute())
            throw new IllegalArgumentException("Cargo registration parent must be absolute");
        var owner = new LinuxCargoWrapperSession(timeout);
        try
        {
            owner.control = LinuxCargoTransport.connect(scope, controller, timeout);
            if (owner.control.peer().uid() != 0)
                throw new IOException("Cargo controller must have effective UID zero");
            owner.directory = Files.createDirectory(registrationDirectory.resolve(Long.toString(ProcessHandle.current().pid())),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            owner.directoryIdentity = Files.readAttributes(owner.directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            if (owner.directoryIdentity == null)
                throw new IOException("Cargo wrapper directory identity is unavailable");
            owner.streams = LinuxBorrowedStreams.listen(scope, owner.directory, action);
            owner.registration = new LinuxBorrowedStreams.Binding(action, 1, owner.streams.wrapper(),
                owner.streams.endpoint(), new byte[32], owner.streams.terminal(), launch);
            owner.bindings = scope.getLinuxBorrowedStreamBindings();
            // An attempt receiving a pipe instead of a terminal would change the compiler's output, such as its colors.
            if (!owner.streams.terminal().streams().get(2).tty())
                owner.errorRelay = new CompilerErrorRelay(new FileOutputStream(FileDescriptor.err));
            return owner;
        }
        catch (IOException | RuntimeException | Error failure)
        {
            if (failure instanceof LinuxBorrowedStreams.CleanupFailure retained)
                owner.partial = retained;
            try { owner.close(); }
            catch (IOException | RuntimeException | Error cleanup)
            {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Returns this wrapper's captured registration without granting an attempt capability.
     * @return immutable original endpoint and launch receipt
     * @throws IllegalStateException if cleanup has begun
     */
    @Override
    public LinuxBorrowedStreams.Binding registration()
    {
        requireOpen();
        return registration;
    }

    /**
     * Consumes the one registration publication before attempting its native send.
     * @param bytes complete bounded registration payload
     * @throws IllegalStateException if publication was attempted or cleanup has begun
     * @throws IOException if publication fails
     */
    @Override
    public void sendRegistration(byte[] bytes) throws IOException
    {
        requireOpen();
        if (registrationSent) throw new IllegalStateException("Cargo registration publication was already attempted");
        registrationSent = true;
        control.send(bytes);
    }

    /**
     * Awaits an interruptible controller response after registration publication.
     * @return complete response payload
     * @throws IllegalStateException if registration was not attempted or cleanup has begun
     * @throws IOException if reception fails
     */
    @Override
    public byte[] awaitFrame() throws IOException
    {
        requireRegistered();
        return control.awaitFrame();
    }

    /**
     * Authorizes and consumes one strictly newer descriptor transfer through the retained listener.
     * The wrapper protocol owner checks the binding's exact launch before calling this operation.
     * @param binding controller-authorized binding matching this listener's captured identity
     * @throws IllegalStateException if registration was not attempted or cleanup has begun
     * @throws IOException if authorization or native transfer fails
     */
    @Override
    public void transfer(LinuxBorrowedStreams.Binding binding) throws IOException
    {
        requireRegistered();
        // The controller sends another attempt only after the previous one was aborted and its processes collected.
        endAttempt(true);
        streams.authorize(binding);
        if (errorRelay == null)
        {
            streams.sendBorrowed(timeout);
            return;
        }
        errorPipe = new LinuxCompilerErrorPipe(bindings, errorRelay);
        try { streams.sendBorrowed(timeout, errorPipe.writeEnd()); }
        finally { errorPipe.closeWriteEnd(); }
    }

    /**
     * Delivers the accepted attempt's remaining standard-error output, waiting up to the session timeout for its
     * processes to close it.
     * @throws IllegalStateException if registration was not attempted or cleanup has begun
     * @throws IOException if the attempt's standard error stays open or cannot be relayed
     */
    @Override
    public void finish() throws IOException
    {
        requireRegistered();
        endAttempt(false);
    }

    /**
     * Ends the relayed standard error of the last transferred attempt, if any.
     * @param superseded {@code true} if the scheduler will retry the compiler in another attempt
     * @throws IOException if the attempt's standard error stays open or cannot be relayed
     */
    private void endAttempt(boolean superseded) throws IOException
    {
        if (errorPipe == null)
            return;
        var pipe = errorPipe;
        errorPipe = null;
        pipe.end(superseded, timeout);
    }

    /**
     * Attempts all independent endpoint cleanup before removing the original private directory.
     * Failed cleanup retains this owner for retry, without replaying an uncertain native descriptor close.
     * @throws IOException if owned cleanup remains incomplete; the exact first failure is rethrown
     */
    @Override
    public void close() throws IOException
    {
        closing = true;
        Throwable failure = null;
        if (control != null)
        {
            try { control.close(); control = null; }
            catch (IOException | RuntimeException | Error cleanup) { failure = cleanup; }
        }
        if (streams != null)
        {
            try { streams.close(); streams = null; }
            catch (IOException | RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
        }
        if (partial != null)
        {
            try { partial.retryCleanup(); partial = null; }
            catch (IOException | RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
        }
        if (control == null && streams == null && partial == null && directory != null)
        {
            try { removeDirectory(); }
            catch (IOException | RuntimeException | Error cleanup) { failure = collect(failure, cleanup); }
        }
        if (failure != null)
        {
            failure.addSuppressed(new CleanupFailure(this));
            if (failure instanceof IOException checked) throw checked;
            if (failure instanceof RuntimeException unchecked) throw unchecked;
            if (failure instanceof Error error) throw error;
            throw new AssertionError("Unexpected cleanup failure category", failure);
        }
    }

    /**
     * Removes only the original exclusively owned directory after all endpoint cleanup completes.
     * @throws IOException if the retained pathname was replaced or cannot be removed
     */
    private void removeDirectory() throws IOException
    {
        BasicFileAttributes current;
        try { current = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { directory = null; directoryIdentity = null; return; }
        if (!current.isDirectory() || directoryIdentity == null || !directoryIdentity.equals(current.fileKey()))
            throw new IOException("Cargo wrapper directory identity changed; foreign path retained");
        Files.delete(directory);
        directory = null;
        directoryIdentity = null;
    }

    /**
     * Keeps the first exact cleanup failure while retaining failures from independent resources.
     * @param primary first cleanup failure, or null before any failure
     * @param additional failure from another owned resource
     * @return the original primary, or the first additional failure
     */
    private static Throwable collect(Throwable primary, Throwable additional)
    {
        if (primary == null) return additional;
        if (primary != additional) primary.addSuppressed(additional);
        return primary;
    }

    /**
     * Rejects protocol operations after cleanup has begun.
     * @throws IllegalStateException if closing
     */
    private void requireOpen()
    {
        if (closing) throw new IllegalStateException("Cargo wrapper session cleanup has begun");
    }

    /**
     * Requires registration publication before receiving or handing off an attempt.
     * @throws IllegalStateException if registration was not attempted or cleanup has begun
     */
    private void requireRegistered()
    {
        requireOpen();
        if (!registrationSent) throw new IllegalStateException("Cargo wrapper registration was not published");
    }

    /**
     * Retains incomplete session cleanup without replacing the primary acquisition or protocol failure.
     */
    static final class CleanupFailure extends IOException
    {
        /**
         * Exception serialization format identifier.
         */
        private static final long serialVersionUID = 1L;
        /**
         * Live cleanup owner, intentionally confined to this process rather than serialized.
         */
        private final transient LinuxCargoWrapperSession owner;

        /**
         * Retains the failed cleanup and its remaining native and filesystem ownership.
         * @param owner session retaining every unfinished cleanup stage
         */
        private CleanupFailure(LinuxCargoWrapperSession owner)
        {
            super("Cargo wrapper session cleanup remains incomplete");
            this.owner = owner;
        }

        /**
         * Retries unfinished cleanup while retaining the native owners' no-replay guarantees.
         * @throws IOException if any cleanup remains incomplete
         */
        void retryCleanup() throws IOException
        {
            owner.close();
        }
    }
}
