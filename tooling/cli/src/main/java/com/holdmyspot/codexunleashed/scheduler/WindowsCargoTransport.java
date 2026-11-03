package com.holdmyspot.codexunleashed.scheduler;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntSupplier;

/**
 * Owns bounded local Windows named-pipe control channels with kernel peer PID observation.
 * A PID is a selector for independent retained-creation authentication, never process authority by itself.
 * Native owners are thread confined. Pending OVERLAPPED storage remains owned until cancellation completes.
 */
public final class WindowsCargoTransport
{
    /** Maximum application payload, excluding its four-byte network-order length header. */
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** Private local pipe namespace; random invocation names never address remote hosts. */
    private static final String PREFIX = "\\\\.\\pipe\\codex-cargo-";

    /**
     * Prevents construction of the transport factory.
     */
    private WindowsCargoTransport() {}

    /**
     * Creates an invocation-random endpoint name without acquiring a handle.
     * @return local Windows named-pipe name
     */
    public static String endpoint()
    {
        return PREFIX + UUID.randomUUID();
    }

    /**
     * Starts a protected local listener and posts its first asynchronous connection.
     * @param scope borrowed services retained until every listener and connection closes
     * @param endpoint invocation-generated local name
     * @param timeout positive partial-frame and write deadline; idle connections do not expire
     * @return independently owned listener
     * @throws IOException if native acquisition fails
     */
    public static Listener listen(WindowsCargoScope scope, String endpoint, Duration timeout) throws IOException
    {
        validate(endpoint, timeout);
        return new Listener(new Native(scope), endpoint, timeout);
    }

    /**
     * Connects once to a local server; callers must authenticate the reported server PID before publication.
     * @param scope borrowed services retained until connection closes
     * @param endpoint approved invocation-local name
     * @param timeout positive frame and connection deadline
     * @return owned connection with observed server PID
     * @throws IOException if connection or acquisition fails
     */
    public static Connection connect(WindowsCargoScope scope, String endpoint, Duration timeout) throws IOException
    {
        validate(endpoint, timeout);
        Native nativeCalls = new Native(scope);
        MemorySegment acquired = MemorySegment.NULL;
        try
        {
            acquired = nativeCalls.open(endpoint, timeout.toNanos());
            return new Connection(nativeCalls, acquired, timeout, false, true);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { nativeCalls.closeHandle(acquired); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            nativeCalls.close();
            throw failure;
        }
    }

    /**
     * Validates caller-controlled endpoint and duration before resource acquisition.
     * @param endpoint approved local pipe name
     * @param timeout positive bounded-operation duration
     * @throws IllegalArgumentException if a name or duration is invalid
     */
    private static void validate(String endpoint, Duration timeout)
    {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(timeout, "timeout");
        String suffix = endpoint.startsWith(PREFIX) ? endpoint.substring(PREFIX.length()) : "";
        if (suffix.isEmpty() || suffix.indexOf('\\') >= 0 || endpoint.length() > 256 || endpoint.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Cargo endpoint must be a private local named pipe");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Cargo timeout must be positive");
        timeout.toNanos();
    }

    /** Separates stopping listener I/O from disposing bindings borrowed by accepted endpoints. */
    public static final class ListenerLifetime implements AutoCloseable
    {
        /** Stops pending acceptance without releasing bindings. */
        private final Closeable stop;
        /** Reports accepted endpoint borrowers on the controller thread. */
        private final IntSupplier peers;
        /** Disposes bindings only after all endpoint borrowers close. */
        private final Closeable dispose;
        /** Whether acceptance cancellation has been attempted. */
        private boolean stopAttempted;
        /** Exact uncertain cancellation or pending-instance release failure. */
        private Throwable stopFailure;
        /** Whether final binding disposal has been attempted. */
        private boolean disposeAttempted;
        /** Exact uncertain final binding disposal failure. */
        private Throwable disposeFailure;

        /**
         * Retains resource-free operations without starting or stopping native I/O.
         * @param stop stops pending acceptance while preserving shared bindings
         * @param peers counts accepted endpoints still borrowing the bindings
         * @param dispose releases bindings after every accepted endpoint closes
         * @throws NullPointerException if any operation or borrower count is null
         */
        public ListenerLifetime(Closeable stop, IntSupplier peers, Closeable dispose)
        {
            this.stop = Objects.requireNonNull(stop, "stop");
            this.peers = Objects.requireNonNull(peers, "peers");
            this.dispose = Objects.requireNonNull(dispose, "dispose");
        }

        /**
         * Reports whether polling may still transfer a newly accepted endpoint.
         * @return whether acceptance has not stopped
         */
        public boolean accepting() { return !stopAttempted; }

        /**
         * Stops acceptance once without disposing bindings borrowed by accepted peers.
         * @throws IOException if pending acceptance cancellation or release fails
         */
        public void stopAccepting() throws IOException
        {
            if (stopAttempted) { rethrow(stopFailure); return; }
            stopAttempted = true;
            try { stop.close(); }
            catch (IOException | RuntimeException | Error failure) { stopFailure = failure; throw failure; }
        }

        /**
         * Stops acceptance, then disposes shared bindings only after all accepted endpoints close.
         * @throws IOException if stopping or final disposal fails
         * @throws IllegalStateException if an accepted endpoint still borrows the bindings
         */
        @Override public void close() throws IOException
        {
            stopAccepting();
            if (peers.getAsInt() != 0)
                throw new IllegalStateException("Cargo listener still has open accepted connections");
            if (disposeAttempted) { rethrow(disposeFailure); return; }
            disposeAttempted = true;
            try { dispose.close(); }
            catch (IOException | RuntimeException | Error failure) { disposeFailure = failure; throw failure; }
        }

        /**
         * Reports an exact retained uncertainty without retrying its native effect.
         * @param failure retained checked, unchecked or fatal failure, or null
         * @throws IOException if the original failure was checked
         */
        private static void rethrow(Throwable failure) throws IOException
        {
            if (failure instanceof IOException checked) throw checked;
            if (failure instanceof RuntimeException unchecked) throw unchecked;
            if (failure instanceof Error fatal) throw fatal;
        }
    }

    /**
     * Holds one listening instance and shares native bindings with accepted connections.
     * Stop acceptance before retiring consumers; close bindings only after accepted borrowers close.
     * Connection ownership is transferred by poll.
     */
    public static final class Listener implements AutoCloseable
    {
        /** Native bindings shared with accepted borrowers. */
        private final Native calls;
        /** Invocation-local pipe name. */
        private final String endpoint;
        /** Per-frame deadline after the first incoming byte. */
        private final Duration timeout;
        /** Current pending connection handle, cleared on transfer. */
        private MemorySegment pending = MemorySegment.NULL;
        /** Pending connection's retained native storage. */
        private MemorySegment operation;
        /** Acceptance cancellation and final binding disposal have distinct lifetimes. */
        private final ListenerLifetime lifetime;
        /** First pipe instance guards name ownership. */
        private boolean first = true;
        /** Indicates whether ConnectNamedPipe completed synchronously or the peer connected before it. */
        private boolean connected;

        /**
         * Posts the initial connection and cleans up native ownership on failed acquisition.
         * @param calls transferred native binding owner
         * @param endpoint approved endpoint
         * @param timeout positive partial-frame deadline
         * @throws IOException if listener setup fails
         */
        private Listener(Native calls, String endpoint, Duration timeout) throws IOException
        {
            this.calls = calls;
            this.endpoint = endpoint;
            this.timeout = timeout;
            lifetime = new ListenerLifetime(this::stopPending, () -> calls.connections, this::disposeBindings);
            try { post(); }
            catch (IOException | RuntimeException | Error failure)
            {
                try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Observes an accepted connection without waiting for a peer to arrive.
         * The next server instance is posted before the accepted handle transfers to its consumer.
         * @return new owned connection, or null if no peer has connected
         * @throws IOException if native connection or replacement acquisition fails
         */
        public Connection poll() throws IOException
        {
            requireOpen();
            if (!connected && !calls.complete(pending, operation)) return null;
            MemorySegment accepted = pending;
            Connection connection = new Connection(calls, accepted, timeout, true, false);
            pending = MemorySegment.NULL;
            try { post(); }
            catch (IOException | RuntimeException | Error failure)
            {
                try { connection.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            return connection;
        }

        /**
         * Acquires and connects one native pipe instance, retaining ownership before issuing I/O.
         * @throws IOException if creation or asynchronous connection fails
         */
        private void post() throws IOException
        {
            operation = calls.arena.allocate(32, 8);
            pending = calls.pipe(endpoint, first);
            first = false;
            int result = calls.integer("ConnectNamedPipe", pending, operation);
            connected = result != 0;
            if (result == 0)
            {
                int error = calls.error();
                if (error == 535) connected = true;
                else if (error != 997) throw new IOException("ConnectNamedPipe failed: " + error);
            }
        }

        /**
         * Rejects acquisition once stopping acceptance has been attempted.
         * @throws IllegalStateException if acceptance has stopped
         */
        private void requireOpen()
        {
            if (!lifetime.accepting()) throw new IllegalStateException("Cargo listener is stopped");
        }

        /**
         * Cancels and drains pending acceptance while retaining bindings borrowed by accepted connections.
         * @throws IOException if cancellation or pending-instance release is uncertain
         */
        public void stopAccepting() throws IOException { lifetime.stopAccepting(); }

        /**
         * Stops one pending instance without releasing the accepted connections' native arena.
         * @throws IOException if cancellation or release fails
         */
        private void stopPending() throws IOException
        {
            if (pending.address() == 0) return;
            if (!connected) calls.cancel(pending, operation);
            MemorySegment handle = pending;
            pending = MemorySegment.NULL;
            calls.closeHandle(handle);
        }

        /**
         * Releases shared bindings at their effect boundary only after every accepted borrower closes.
         * @throws IllegalStateException if an accepted connection still borrows the arena
         */
        private void disposeBindings()
        {
            if (calls.connections != 0) throw new IllegalStateException("Cargo listener still has open accepted connections");
            calls.close();
        }

        @Override public void close() throws IOException { lifetime.close(); }
    }

    /**
     * Owns one byte-mode pipe and exactly-once frame operations.
     * Any uncertain frame failure permanently poisons this connection. No partial publication is replayed.
     */
    public static final class Connection implements AutoCloseable
    {
        /** Native library and retained asynchronous buffers. */
        private final Native calls;
        /** Owned pipe handle. */
        private final MemorySegment handle;
        /** Partial-frame deadline in nanoseconds. */
        private final long timeout;
        /** Indicates whether this connection releases the native library owner. */
        private final boolean ownsCalls;
        /** Kernel-observed opposite pipe endpoint PID; independent creation authentication is still required. */
        private final long peerPid;
        /** Four-byte header followed by one payload, accumulated across nonblocking polls. */
        private byte[] frame = new byte[4];
        /** Bytes accumulated in the current frame. */
        private int received;
        /** First-byte monotonic deadline, or zero while the channel is idle. */
        private long deadline;
        /** First failure permanently disallowing further I/O. */
        private IOException failure;
        /** Indicates whether handle closure was attempted. */
        private boolean closed;
        /** Original handle-close failure, retained without replay. */
        private IOException closeFailure;

        /**
         * Retains one pipe and observes its peer identity before any application I/O.
         * @param calls borrowed native binding owner
         * @param handle transferred pipe handle
         * @param timeout positive partial-frame deadline
         * @param server indicates whether the peer is a client
         * @param ownsCalls indicates whether closing this pipe also closes native services
         * @throws IOException if the peer PID cannot be observed
         */
        private Connection(Native calls, MemorySegment handle, Duration timeout, boolean server,
            boolean ownsCalls) throws IOException
        {
            this.calls = calls;
            this.handle = handle;
            this.timeout = timeout.toNanos();
            this.ownsCalls = ownsCalls;
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment pid = temporary.allocate(ValueLayout.JAVA_INT);
                String function = server ? "GetNamedPipeClientProcessId" : "GetNamedPipeServerProcessId";
                if (calls.integer(function, handle, pid) == 0) throw new IOException(function + " failed: " + calls.error());
                this.peerPid = Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0));
                if (peerPid == 0) throw new IOException("Cargo pipe reported a zero peer PID");
                calls.connections++;
            }
        }

        /**
         * Returns the kernel-reported peer PID for retained creation-receipt authentication.
         * @return positive peer PID
         */
        public long peerPid() { return peerPid; }

        /**
         * Reads available bytes without waiting for an idle wrapper or incomplete frame.
         * @return complete payload, or null while a frame has not finished
         * @throws IOException if the frame, deadline or native operation fails; future I/O remains poisoned
         */
        public byte[] poll() throws IOException
        {
            requireOpen();
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment available = temporary.allocate(ValueLayout.JAVA_INT);
                if (calls.integer("PeekNamedPipe", handle, MemorySegment.NULL, 0, MemorySegment.NULL,
                    available, MemorySegment.NULL) == 0) throw new IOException("PeekNamedPipe failed: " + calls.error());
                int count = available.get(ValueLayout.JAVA_INT, 0);
                if (count > 0)
                {
                    if (received == 0) deadline = System.nanoTime() + timeout;
                    int length = Math.min(count, frame.length - received);
                    MemorySegment buffer = temporary.allocate(length);
                    calls.readAvailable(handle, buffer, length);
                    MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, frame, received, length);
                    received += length;
                    if (received == 4 && frame.length == 4)
                    {
                        int payload = ByteBuffer.wrap(frame).getInt();
                        if (payload <= 0 || payload > MAX_FRAME_BYTES) throw new IOException("Cargo frame length is outside one byte through 1 MiB");
                        frame = Arrays.copyOf(frame, payload + 4);
                    }
                    if (received == frame.length)
                    {
                        byte[] payload = Arrays.copyOfRange(frame, 4, frame.length);
                        frame = new byte[4];
                        received = 0;
                        deadline = 0;
                        return payload;
                    }
                }
                if (received > 0 && System.nanoTime() - deadline >= 0) throw new IOException("Cargo partial frame deadline expired");
                return null;
            }
            catch (IOException problem) { failure = problem; throw problem; }
        }

        /**
         * Waits for one frame without assigning an idle queue deadline.
         * @return complete payload
         * @throws IOException if reception or interruption fails
         */
        public byte[] awaitFrame() throws IOException
        {
            while (true)
            {
                byte[] result = poll();
                if (result != null) return result;
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cargo frame wait interrupted");
                LockSupport.parkNanos(1_000_000);
            }
        }

        /**
         * Publishes one bounded frame using one overlapped write, without replay after uncertainty.
         * @param bytes complete payload of one byte through 1 MiB
         * @throws IOException if publication fails or exceeds its deadline
         */
        public void send(byte[] bytes) throws IOException
        {
            requireOpen();
            if (bytes.length == 0 || bytes.length > MAX_FRAME_BYTES) throw new IOException("Cargo payload length is outside one byte through 1 MiB");
            byte[] framed = ByteBuffer.allocate(bytes.length + 4).putInt(bytes.length).put(bytes).array();
            try { calls.write(handle, framed, timeout); }
            catch (IOException problem) { failure = problem; throw problem; }
        }

        /**
         * Rejects operations after disposal or the first uncertain I/O failure.
         * @throws IOException if an earlier I/O failed
         * @throws IllegalStateException if closed
         */
        private void requireOpen() throws IOException
        {
            if (closed) throw new IllegalStateException("Cargo connection is closed");
            if (failure != null) throw failure;
        }

        @Override public void close() throws IOException
        {
            if (closeFailure != null) throw closeFailure;
            if (closed) return;
            closed = true;
            try { calls.closeHandle(handle); }
            catch (IOException problem) { closeFailure = problem; throw problem; }
            finally
            {
                calls.connections--;
                if (ownsCalls && closeFailure == null) calls.close();
            }
        }
    }

    /**
     * Binds Windows x64 and ARM64 Kernel32/Advapi32 APIs; every native allocation is retained to owner close.
     */
    private static final class Native implements AutoCloseable
    {
        /** Native storage outlives all outstanding operations and accepted borrowers. */
        private final Arena arena = Arena.ofConfined();
        /** Named platform calls, bound through the borrowed Pouch linker. */
        private final java.util.Map<String, MethodHandle> functions = new java.util.HashMap<>();
        /** Protected descriptor shared by each server instance. */
        private MemorySegment security = MemorySegment.NULL;
        /** Live accepted/client connections retaining this native owner. */
        private int connections;
        /** Same-call error storage layout supplied by the Windows FFM linker. */
        private final MemoryLayout callState;
        /** Offset of captured GetLastError within each native call's state record. */
        private final long errorOffset;
        /** Last native error per calling thread, captured before returning through Java. */
        private final ThreadLocal<Integer> lastError = ThreadLocal.withInitial(() -> 0);

        /**
         * Resolves required native APIs without opening a pipe.
         * @param scope borrowed invocation services
         */
        // Java has no Windows protected-pipe API; scoped FFM binds only the documented native operations below.
        @SuppressWarnings("restricted")
        Native(WindowsCargoScope scope)
        {
            if (!System.getProperty("os.name", "").startsWith("Windows") || ValueLayout.ADDRESS.byteSize() != 8)
                throw new UnsupportedOperationException("Cargo named-pipe transport requires native 64-bit Windows");
            this.callState = Linker.Option.captureStateLayout();
            this.errorOffset = callState.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
            try
            {
                SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
                SymbolLookup advapi = SymbolLookup.libraryLookup("advapi32.dll", arena);
                bind(scope, kernel, "CreateNamedPipeW", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, kernel, "CreateFileW", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, kernel, "WaitNamedPipeW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                bind(scope, kernel, "ConnectNamedPipe", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetNamedPipeClientProcessId", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetNamedPipeServerProcessId", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetCurrentProcess", ValueLayout.ADDRESS);
                bind(scope, kernel, "CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, kernel, "LocalFree", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "CancelIoEx", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetOverlappedResult", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                bind(scope, kernel, "PeekNamedPipe", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                for (String name : new String[]{"ReadFile", "WriteFile"})
                    bind(scope, kernel, name, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, advapi, "GetSecurityDescriptorLength", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, advapi, "OpenProcessToken", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, advapi, "GetTokenInformation", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, advapi, "ConvertSidToStringSidW", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, advapi, "ConvertStringSecurityDescriptorToSecurityDescriptorW", ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            }
            catch (RuntimeException | Error failure) { arena.close(); throw failure; }
        }

        /**
         * Binds one platform function with its fixed Windows ABI.
         * @param scope borrowed linker provider
         * @param library owning library
         * @param name exact exported symbol
         * @param result native return layout
         * @param arguments native argument layouts
         */
        // Native downcalls are required for kernel peer identity and local protected pipe ownership.
        @SuppressWarnings("restricted")
        private void bind(WindowsCargoScope scope, SymbolLookup library, String name,
            java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... arguments)
        {
            functions.put(name, scope.tooling().getNativeLinker().downcallHandle(library.find(name).orElseThrow(),
                FunctionDescriptor.of(result, arguments), Linker.Option.captureCallState("GetLastError")));
        }

        /**
         * Calls a bound function and preserves ABI invocation failures.
         * @param name bound symbol
         * @param arguments exact native arguments
         * @return native return value
         */
        private Object call(String name, Object... arguments)
        {
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment state = temporary.allocate(callState);
                java.util.List<Object> invocation = new java.util.ArrayList<>(arguments.length + 1);
                invocation.add(state);
                invocation.addAll(java.util.Arrays.asList(arguments));
                Object result = functions.get(name).invokeWithArguments(invocation);
                lastError.set(state.get(ValueLayout.JAVA_INT, errorOffset));
                return result;
            }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Windows call failed: " + name, failure); }
        }

        /**
         * Calls a BOOL or DWORD function.
         * @param name bound native symbol
         * @param arguments exact native arguments
         * @return native int bits
         */
        int integer(String name, Object... arguments) { return (int) call(name, arguments); }

        /**
         * Reads the current thread's last Windows error.
         * @return native error code
         */
        int error() { return lastError.get(); }

        /**
         * Allocates a retained NUL-terminated UTF-16 native string.
         * @param value text without embedded NUL
         * @return native UTF-16 string
         */
        private MemorySegment wide(String value)
        {
            MemorySegment result = arena.allocate((value.length() + 1L) * 2, 2);
            for (int i = 0; i < value.length(); i++) result.set(ValueLayout.JAVA_CHAR, i * 2L, value.charAt(i));
            return result;
        }

        /**
         * Creates a logon-SID and SYSTEM protected security descriptor and local overlapped instance.
         * @param endpoint approved local name
         * @param first indicates whether name ownership must be acquired exclusively
         * @return owned pipe handle
         * @throws IOException if security or creation fails
         */
        MemorySegment pipe(String endpoint, boolean first) throws IOException
        {
            if (security.address() == 0) prepareSecurity();
            int flags = 3 | 0x40000000;
            if (first) flags |= 0x00080000;
            MemorySegment handle = (MemorySegment) call("CreateNamedPipeW", wide(endpoint), flags,
                8, 255, 65536, 65536, 0, security);
            if (handle.address() == -1) throw new IOException("CreateNamedPipeW failed: " + error());
            return handle;
        }

        /**
         * Builds a protected DACL for SYSTEM and the current process token's logon SID only.
         * @throws IOException if token or descriptor access fails
         */
        // Windows allocators return bounded descriptor/SID pointers; their documented lengths delimit these views.
        @SuppressWarnings("restricted")
        private void prepareSecurity() throws IOException
        {
            MemorySegment tokenSlot = arena.allocate(ValueLayout.ADDRESS);
            if (integer("OpenProcessToken", call("GetCurrentProcess"), 8, tokenSlot) == 0)
                throw new IOException("OpenProcessToken failed: " + error());
            MemorySegment token = tokenSlot.get(ValueLayout.ADDRESS, 0);
            try
            {
                MemorySegment size = arena.allocate(ValueLayout.JAVA_INT);
                integer("GetTokenInformation", token, 28, MemorySegment.NULL, 0, size);
                int bytes = size.get(ValueLayout.JAVA_INT, 0);
                if (bytes < 24) throw new IOException("TokenLogonSid size is invalid: " + bytes);
                MemorySegment groups = arena.allocate(bytes, 8);
                if (integer("GetTokenInformation", token, 28, groups, bytes, size) == 0)
                    throw new IOException("GetTokenInformation(TokenLogonSid) failed: " + error());
                if (groups.get(ValueLayout.JAVA_INT, 0) != 1) throw new IOException("TokenLogonSid must identify exactly one logon group");
                MemorySegment sid = groups.get(ValueLayout.ADDRESS, 8);
                MemorySegment textSlot = arena.allocate(ValueLayout.ADDRESS);
                if (integer("ConvertSidToStringSidW", sid, textSlot) == 0)
                    throw new IOException("ConvertSidToStringSidW failed: " + error());
                MemorySegment text = textSlot.get(ValueLayout.ADDRESS, 0);
                StringBuilder value = new StringBuilder();
                try
                {
                    MemorySegment readable = text.reinterpret(65536);
                    for (long offset = 0; ; offset += 2)
                    {
                        char character = readable.get(ValueLayout.JAVA_CHAR, offset);
                        if (character == 0) break;
                        value.append(character);
                    }
                }
                finally { call("LocalFree", text); }
                MemorySegment descriptorSlot = arena.allocate(ValueLayout.ADDRESS);
                if (integer("ConvertStringSecurityDescriptorToSecurityDescriptorW",
                    wide("D:P(A;;GA;;;SY)(A;;GA;;;" + value + ")"), 1, descriptorSlot, MemorySegment.NULL) == 0)
                    throw new IOException("Security descriptor conversion failed: " + error());
                MemorySegment descriptor = descriptorSlot.get(ValueLayout.ADDRESS, 0);
                try
                {
                    // Copy the self-relative descriptor before releasing the native allocator's result.
                    int descriptorLength = integer("GetSecurityDescriptorLength", descriptor);
                    if (descriptorLength <= 0) throw new IOException("Security descriptor length is invalid");
                    MemorySegment retained = arena.allocate(descriptorLength, 8);
                    MemorySegment.copy(descriptor.reinterpret(descriptorLength), 0, retained, 0, descriptorLength);
                    security = arena.allocate(24, 8);
                    security.set(ValueLayout.JAVA_INT, 0, 24);
                    security.set(ValueLayout.ADDRESS, 8, retained);
                    security.set(ValueLayout.JAVA_INT, 16, 0);
                }
                finally { call("LocalFree", descriptor); }
            }
            finally { closeHandle(token); }
        }

        /**
         * Opens an existing local pipe with non-inherited overlapped access.
         * @param endpoint approved name
         * @param timeout positive connection deadline in nanoseconds
         * @return owned pipe handle
         * @throws IOException if connection fails
         */
        MemorySegment open(String endpoint, long timeout) throws IOException
        {
            MemorySegment name = wide(endpoint);
            long deadline = System.nanoTime() + timeout;
            while (true)
            {
                MemorySegment handle = (MemorySegment) call("CreateFileW", name, 0xc0000000, 0,
                    MemorySegment.NULL, 3, 0x40000000, MemorySegment.NULL);
                if (handle.address() != -1) return handle;
                int error = error();
                if (error != 231) throw new IOException("CreateFileW(Cargo pipe) failed: " + error);
                if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
                    throw new IOException("Cargo pipe connection deadline expired or was interrupted");
                if (integer("WaitNamedPipeW", name, 50) == 0)
                {
                    int waitingError = error();
                    if (waitingError != 121) throw new IOException("WaitNamedPipeW failed: " + waitingError);
                }
            }
        }

        /**
         * Polls one OVERLAPPED completion without waiting.
         * @param handle operation's owned pipe
         * @param operation retained OVERLAPPED storage
         * @return true on completion, false while pending
         * @throws IOException if completion failed
         */
        boolean complete(MemorySegment handle, MemorySegment operation) throws IOException
        {
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment count = temporary.allocate(ValueLayout.JAVA_INT);
                if (integer("GetOverlappedResult", handle, operation, count, 0) != 0) return true;
                int error = error();
                if (error == 996) return false;
                throw new IOException("GetOverlappedResult failed: " + error);
            }
        }

        /**
         * Reads only bytes already observed available, retaining its asynchronous buffer until completion.
         * @param handle source pipe
         * @param buffer destination valid through completion
         * @param length exact available byte count
         * @throws IOException if the read fails or reports another count
         */
        void readAvailable(MemorySegment handle, MemorySegment buffer, int length) throws IOException
        {
            MemorySegment operation = arena.allocate(32, 8);
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int result = integer("ReadFile", handle, buffer, length, MemorySegment.NULL, operation);
            if (result == 0 && error() != 997) throw new IOException("ReadFile failed: " + error());
            if (integer("GetOverlappedResult", handle, operation, count, 1) == 0)
                throw new IOException("Cargo available read completion failed: " + error());
            if (count.get(ValueLayout.JAVA_INT, 0) != length) throw new IOException("Cargo available read returned a different byte count");
        }

        /**
         * Writes one frame and waits until its exact completion or cancels and drains before returning failure.
         * @param handle destination pipe
         * @param bytes full header and payload
         * @param timeout positive operation deadline in nanoseconds
         * @throws IOException if writing or cancellation fails
         */
        void write(MemorySegment handle, byte[] bytes, long timeout) throws IOException
        {
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            MemorySegment operation = arena.allocate(32, 8);
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int result = integer("WriteFile", handle, buffer, bytes.length, MemorySegment.NULL, operation);
            if (result == 0 && error() != 997) throw new IOException("WriteFile failed: " + error());
            long deadline = System.nanoTime() + timeout;
            while (!complete(handle, operation))
            {
                if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted())
                {
                    cancel(handle, operation);
                    throw new IOException("Cargo frame publication did not complete before cancellation");
                }
                LockSupport.parkNanos(1_000_000);
            }
            if (integer("GetOverlappedResult", handle, operation, count, 0) == 0 ||
                count.get(ValueLayout.JAVA_INT, 0) != bytes.length) throw new IOException("Cargo frame write completion was incomplete");
        }

        /**
         * Cancels a known pending operation and drains its completion before releasing native storage.
         * @param handle owned pipe
         * @param operation exact pending OVERLAPPED storage
         * @throws IOException if cancellation or completion cannot be reconciled
         */
        void cancel(MemorySegment handle, MemorySegment operation) throws IOException
        {
            int canceled = integer("CancelIoEx", handle, operation);
            if (canceled == 0 && error() != 1168) throw new IOException("CancelIoEx failed: " + error());
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            int complete = integer("GetOverlappedResult", handle, operation, count, 1);
            if (complete == 0 && error() != 995) throw new IOException("Canceled Cargo I/O completion failed: " + error());
        }

        /**
         * Closes one owned pipe or token handle exactly once after any pending I/O has been drained.
         * @param handle owned handle, or NULL when never acquired
         * @throws IOException if native close fails
         */
        void closeHandle(MemorySegment handle) throws IOException
        {
            if (handle.address() == 0) return;
            if (integer("CloseHandle", handle) == 0) throw new IOException("CloseHandle failed: " + error());
        }

        @Override public void close() { arena.close(); }
    }
}
