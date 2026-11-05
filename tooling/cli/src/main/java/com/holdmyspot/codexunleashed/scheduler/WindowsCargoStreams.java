package com.holdmyspot.codexunleashed.scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Retains wrapper standard endpoints across retries and relays console handles and stderr through private anonymous
 * pipes. Redirected stdin and stdout remain borrowed. Console originals remain local and borrowed; offered pipe ends
 * are non-inherited owned handles. The controller must close imported output copies before delivering the final
 * response.
 * <p>
 * Every compiler attempt writes to the same offered stderr, and Cargo panics when one compiler invocation reports the
 * same artifact twice, as a retried attempt does. So the stderr relay forwards a line that starts with
 * {@code {"$message_type":"artifact"} only the first time its exact bytes are seen, and passes every other byte
 * through in order; an incomplete final line is forwarded when stderr ends. The stream carries no attempt boundary, so
 * an incomplete last line of a stopped attempt joins the next attempt's first line.
 */
public final class WindowsCargoStreams implements AutoCloseable
{
    /** Native binding owner, shared with relay workers and closed after they join. */
    private final Calls calls;
    /** Wrapper-local stream handles offered for controller import. */
    private final long[] offered = new long[3];
    /** Successfully acquired pipe ends keyed by native value; atomic removal consumes each close authority. */
    private final Map<Long, MemorySegment> handles = new java.util.concurrent.ConcurrentHashMap<>();
    /** Console copy workers, at most one per distinct console stream role. */
    private final List<Relay> relays = new ArrayList<>();
    /** First relay I/O failure, propagated before any successful final result. */
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    /** Indicates whether terminal cleanup began. */
    private boolean closed;
    /** Indicates whether the controller owns imported stream copies. */
    private boolean accepted;
    /** Original cleanup failure retained without repeating native effects. */
    private IOException closeFailure;

    /**
     * Borrows this controller process's original native standard handles for its direct Cargo child.
     * Console handles remain local and are inherited through WindowsProcess's owned launch duplicates.
     * @param scope invocation services retained until native launch borrowers finish
     * @return binding owner whose handles remain caller-owned
     * @throws IOException if an original standard handle is invalid
     */
    public static Root root(WindowsCargoScope scope) throws IOException
    {
        return root(new Native(scope));
    }

    /**
     * Captures original standard handles through transferred native bindings without importing another process's handles.
     * @param calls transferred binding owner; no standard handle close authority is transferred
     * @return original handle snapshot and its binding owner
     * @throws IOException if any native standard handle is zero or INVALID_HANDLE_VALUE
     */
    public static Root root(Calls calls) throws IOException
    {
        try
        {
            MemorySegment input = calls.address("GetStdHandle", -10);
            MemorySegment output = calls.address("GetStdHandle", -11);
            MemorySegment error = calls.address("GetStdHandle", -12);
            for (MemorySegment handle : List.of(input, output, error))
                if (handle.address() == 0 || handle.address() == -1)
                    throw new IOException("Cargo requires valid inherited native standard handles");
            return new Root(calls, input, output, error);
        }
        catch (IOException | RuntimeException | Error failure)
        {
            try { calls.close(); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Keeps bindings alive while the direct Cargo child borrows this process's original standard handles.
     * Closing this owner releases bindings once and never closes a borrowed native handle.
     */
    public static final class Root implements AutoCloseable
    {
        /** Binding lifetime, owned until all direct child borrowers have finished. */
        private final Calls calls;
        /** Original controller standard input, never owned here. */
        private final MemorySegment input;
        /** Original controller standard output, never owned here. */
        private final MemorySegment output;
        /** Original controller standard error, preserving native stdout aliases. */
        private final MemorySegment error;
        /** Indicates whether binding disposal has been attempted. */
        private boolean closed;
        /** Original uncertain binding failure; later cleanup must not reinterpret it as success. */
        private Throwable closeFailure;

        /**
         * Retains previously validated local native handles without acquiring or duplicating them.
         * @param calls transferred binding owner
         * @param input original local input handle
         * @param output original local output handle
         * @param error original local error handle
         */
        private Root(Calls calls, MemorySegment input, MemorySegment output, MemorySegment error)
        {
            this.calls = calls;
            this.input = input;
            this.output = output;
            this.error = error;
        }

        /**
         * Returns original local standard input for the direct Cargo child.
         * @return borrowed native input handle
         */
        public MemorySegment input() { return input; }
        /**
         * Returns original local standard output for the direct Cargo child.
         * @return borrowed native output handle
         */
        public MemorySegment output() { return output; }
        /**
         * Returns original local standard error for the direct Cargo child.
         * @return borrowed native error handle, including an unchanged stdout alias
         */
        public MemorySegment error() { return error; }

        @Override public void close()
        {
            if (closeFailure instanceof RuntimeException unchecked) throw unchecked;
            if (closeFailure instanceof Error fatal) throw fatal;
            if (closed) return;
            closed = true;
            try { calls.close(); }
            catch (RuntimeException | Error failure) { closeFailure = failure; throw failure; }
        }
    }

    /**
     * Captures original standard handles and constructs private relays for console endpoints and stderr.
     * @param scope borrowed invocation services retained through stream close
     * @return acquired persistent stream owner
     * @throws IOException if capture, pipe creation or cleanup fails
     */
    public static WindowsCargoStreams open(WindowsCargoScope scope) throws IOException
    {
        return open(new Native(scope));
    }

    /**
     * Acquires stream endpoints through one transferred operation owner.
     * @param calls transferred native operations, closed after every owned relay joins
     * @return acquired persistent stream owner
     * @throws IOException if capture, pipe creation or cleanup fails
     */
    public static WindowsCargoStreams open(Calls calls) throws IOException
    {
        WindowsCargoStreams owner = new WindowsCargoStreams(calls);
        try
        {
            for (int index = 0; index < 3; index++) owner.acquire(index);
            return owner;
        }
        catch (IOException | RuntimeException | Error problem)
        {
            try { owner.close(); } catch (IOException cleanup) { problem.addSuppressed(cleanup); }
            throw problem;
        }
    }

    /**
     * Retains native services before any stream acquisition.
     * @param calls transferred native services
     */
    private WindowsCargoStreams(Calls calls) { this.calls = calls; }

    /**
     * Captures a source endpoint and starts an owned relay when it is a console handle or stderr.
     * @param index stdin zero, stdout one, or stderr two
     * @throws IOException if native acquisition fails
     */
    private void acquire(int index) throws IOException
    {
        MemorySegment original = calls.address("GetStdHandle", -10 - index);
        if (original.address() == 0 || original.address() == -1) throw new IOException("Wrapper standard handle is invalid: " + index);
        boolean console = calls.console(original);
        boolean error = index == 2;
        if (!console && !error) { offered[index] = original.address(); return; }
        if (console && error && original.address() == calls.address("GetStdHandle", -11).address())
        {
            offered[index] = offered[1];
            return;
        }
        MemorySegment pair = calls.arena().allocate(ValueLayout.ADDRESS, 2);
        if (calls.integer("CreatePipe", pair, pair.asSlice(8), MemorySegment.NULL, 0) == 0)
            throw new IOException("CreatePipe(console relay) failed: " + calls.error());
        MemorySegment read = pair.get(ValueLayout.ADDRESS, 0);
        handles.put(read.address(), read);
        MemorySegment write = pair.get(ValueLayout.ADDRESS, 8);
        handles.put(write.address(), write);
        boolean input = index == 0;
        MemorySegment source = input ? original : read;
        MemorySegment destination = input ? write : original;
        offered[index] = input ? read.address() : write.address();
        Relay relay = new Relay(source, destination, input, error);
        relays.add(relay);
        relay.start();
    }

    /**
     * Returns the frozen wrapper-local source stdin handle bits.
     * @return handle value to import only using authenticated retained wrapper authority
     */
    public long input() { return offered[0]; }

    /**
     * Returns the frozen wrapper-local source stdout handle bits.
     * @return handle value to import only using authenticated retained wrapper authority
     */
    public long output() { return offered[1]; }

    /**
     * Returns the frozen wrapper-local source stderr handle bits, preserving stdout aliases.
     * @return handle value to import only using authenticated retained wrapper authority
     */
    public long error() { return offered[2]; }

    /**
     * Records imported-copy ownership without dropping source handles needed for retries.
     * @throws IOException if a relay already failed
     */
    public void accepted() throws IOException
    {
        if (closed || accepted) throw new IllegalStateException("Wrapper streams are closed or already accepted");
        accepted = true;
        checkFailure();
    }

    /**
     * Closes wrapper-owned offered output ends, stops stdin copying, then drains stdout and stderr to EOF.
     * The controller must already have closed its output copies after all compiler Jobs became zero.
     * @throws IOException if a relay or cleanup fails
     */
    public void finish() throws IOException
    {
        if (!accepted) throw new IOException("Wrapper output cannot finish before stream acceptance");
        close();
    }

    /**
     * Reports the earliest recorded relay failure.
     * @throws IOException if relay I/O failed
     */
    private void checkFailure() throws IOException
    {
        IOException problem = failure.get();
        if (problem != null) throw problem;
    }

    /**
     * Consumes one owned endpoint's close authority before attempting the native close.
     * @param value exact handle value owned in this stream owner
     * @throws IOException if closing fails
     */
    private void closeOwned(long value) throws IOException
    {
        MemorySegment handle = handles.remove(value);
        if (handle != null) calls.closeHandle(handle);
    }

    @Override public void close() throws IOException
    {
        if (closeFailure != null) throw closeFailure;
        if (closed) return;
        closed = true;
        try
        {
            for (Relay relay : relays) if (relay.input) relay.cancel();
            closeOwned(offered[1]);
            closeOwned(offered[2]);
            for (Relay relay : relays) relay.join();
            checkFailure();
        }
        catch (IOException problem) { closeFailure = problem; }
        finally
        {
            for (Relay relay : relays)
            {
                try { relay.cancel(); relay.join(); relay.closeThread(); }
                catch (IOException problem)
                {
                    if (closeFailure == null) closeFailure = problem;
                    else if (closeFailure != problem) closeFailure.addSuppressed(problem);
                }
            }
            for (long handle : List.copyOf(handles.keySet()))
            {
                try { closeOwned(handle); }
                catch (IOException problem)
                {
                    if (closeFailure == null) closeFailure = problem;
                    else closeFailure.addSuppressed(problem);
                }
            }
            calls.close();
        }
        if (closeFailure != null) throw closeFailure;
    }

    /**
     * Copies bytes between one borrowed endpoint and its owned private pipe endpoint.
     */
    private final class Relay implements Runnable
    {
        /** Size of the native read and write buffers. */
        private static final int BUFFER_SIZE = 65536;
        /** Borrowed or owned read endpoint kept open until the worker joins. */
        private final MemorySegment source;
        /** Borrowed or owned write endpoint kept open until the worker joins. */
        private final MemorySegment destination;
        /** Indicates whether this worker reads borrowed console stdin. */
        private final boolean input;
        /** Indicates whether this worker relays stderr through {@link #notifications}. */
        private final boolean filtered;
        /** Repeated artifact notifications removed from relayed stderr. */
        private final NotificationFilter notifications = new NotificationFilter();
        /** Worker-thread native handle published before its first blocking read. */
        private final CompletableFuture<MemorySegment> nativeThread = new CompletableFuture<>();
        /** Indicates whether cancellation was explicitly requested. */
        private final AtomicBoolean stopping = new AtomicBoolean();
        /** Owned platform worker; no detached worker outlives stream cleanup. */
        private final Thread thread;

        /**
         * Creates a resource-free relay worker.
         * @param source native read endpoint
         * @param destination native write endpoint
         * @param input       indicates whether this is the console-input worker
         * @param filtered    indicates whether this worker relays stderr, which drops repeated artifact notifications
         */
        Relay(MemorySegment source, MemorySegment destination, boolean input, boolean filtered)
        {
            this.source = source;
            this.destination = destination;
            this.input = input;
            this.filtered = filtered;
            this.thread = Thread.ofPlatform().name("cargo-console-relay").unstarted(this);
        }

        /**
         * Starts the owned copy worker.
         */
        void start() { thread.start(); }

        @Override public void run()
        {
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment handle = calls.address("OpenThread", 1, 0, calls.integer("GetCurrentThreadId"));
                if (handle.address() == 0) throw new IOException("OpenThread(console relay) failed: " + calls.error());
                nativeThread.complete(handle);
                MemorySegment buffer = temporary.allocate(BUFFER_SIZE);
                MemorySegment staging = temporary.allocate(BUFFER_SIZE);
                MemorySegment count = temporary.allocate(ValueLayout.JAVA_INT);
                while (!stopping.get())
                {
                    if (calls.integer("ReadFile", source, buffer, BUFFER_SIZE, count, MemorySegment.NULL) == 0)
                    {
                        int error = calls.error();
                        if (error == 109)
                        {
                            forward(notifications.finish(), staging, count);
                            return;
                        }
                        if (error == 995 && stopping.get()) return;
                        throw new IOException("Console relay read failed: " + error);
                    }
                    int length = count.get(ValueLayout.JAVA_INT, 0);
                    if (length == 0)
                    {
                        forward(notifications.finish(), staging, count);
                        return;
                    }
                    if (filtered)
                    {
                        byte[] read = buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
                        if (!forward(notifications.accept(read), staging, count)) return;
                    }
                    else if (!write(buffer, length, count)) return;
                }
            }
            catch (IOException | RuntimeException problem)
            {
                IOException reported = problem instanceof IOException io ? io : new IOException("Console relay failed", problem);
                failure.compareAndSet(null, reported);
                nativeThread.completeExceptionally(reported);
            }
            finally
            {
                if (input)
                {
                    try { closeOwned(destination.address()); }
                    catch (IOException problem) { failure.compareAndSet(null, problem); }
                }
            }
        }

        /**
         * Writes bytes to the destination through a native staging buffer.
         * @param bytes   bytes to write, in order
         * @param staging native buffer of {@link #BUFFER_SIZE} bytes
         * @param count   native storage for the written byte count
         * @return false if cancellation stopped the write
         * @throws IOException if writing fails
         */
        private boolean forward(byte[] bytes, MemorySegment staging, MemorySegment count) throws IOException
        {
            for (int offset = 0; offset < bytes.length; offset += BUFFER_SIZE)
            {
                int length = Math.min(BUFFER_SIZE, bytes.length - offset);
                MemorySegment.copy(bytes, offset, staging, ValueLayout.JAVA_BYTE, 0, length);
                if (!write(staging, length, count)) return false;
            }
            return true;
        }

        /**
         * Writes the start of a native buffer to the destination, repeating short writes.
         * @param data   native bytes to write
         * @param length number of bytes to write from the start of {@code data}
         * @param count  native storage for the written byte count
         * @return false if cancellation stopped the write
         * @throws IOException if writing fails or makes no progress
         */
        private boolean write(MemorySegment data, int length, MemorySegment count) throws IOException
        {
            int offset = 0;
            while (offset < length)
            {
                if (calls.integer("WriteFile", destination, data.asSlice(offset), length - offset, count,
                    MemorySegment.NULL) == 0)
                {
                    int error = calls.error();
                    if (error == 995 && stopping.get()) return false;
                    throw new IOException("Console relay write failed: " + error);
                }
                int written = count.get(ValueLayout.JAVA_INT, 0);
                if (written == 0) throw new IOException("Console relay write made no progress");
                offset += written;
            }
            return true;
        }

        /**
         * Cancels this worker's current native I/O without closing borrowed console handles.
         */
        void cancel()
        {
            stopping.set(true);
            if (nativeThread.isDone() && !nativeThread.isCompletedExceptionally())
                calls.integer("CancelSynchronousIo", nativeThread.join());
        }

        /**
         * Joins the owned worker, preserving interruption as a reported cleanup failure.
         * @throws IOException if joining is interrupted
         */
        void join() throws IOException
        {
            InterruptedException interruption = null;
            while (thread.isAlive())
            {
                try { thread.join(); }
                catch (InterruptedException problem) { interruption = problem; }
            }
            if (interruption != null)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Console relay cleanup interrupted after worker completion", interruption);
            }
        }

        /**
         * Closes the worker's retained native thread handle after the worker stops.
         * @throws IOException if native closure fails
         */
        void closeThread() throws IOException
        {
            if (nativeThread.isDone() && !nativeThread.isCompletedExceptionally()) calls.closeHandle(nativeThread.join());
        }
    }

    /**
     * Removes the rustc artifact notification lines of relayed stderr whose exact bytes it already forwarded, and passes
     * every other byte through in order. A line is held only while it may be, or is, an artifact notification.
     */
    private static final class NotificationFilter
    {
        /** Start of a rustc artifact notification line. */
        private static final byte[] PREFIX = "{\"$message_type\":\"artifact\"".getBytes(US_ASCII);
        /** Artifact notification lines already forwarded, each with its line feed. */
        private final Set<ByteBuffer> forwarded = new HashSet<>();
        /** The current line's bytes, held while the line may be, or is, an artifact notification. */
        private final ByteArrayOutputStream held = new ByteArrayOutputStream();
        /** Whether the current line is not an artifact notification, so its remaining bytes pass through. */
        private boolean passing;

        /**
         * Filters the next bytes read from stderr.
         * @param bytes bytes in stream order
         * @return bytes to forward now, in stream order
         */
        byte[] accept(byte[] bytes)
        {
            ByteArrayOutputStream forward = new ByteArrayOutputStream(bytes.length);
            for (byte value : bytes)
            {
                if (passing)
                {
                    forward.write(value);
                    passing = value != '\n';
                    continue;
                }
                held.write(value);
                int size = held.size();
                if (size <= PREFIX.length)
                {
                    if (value == PREFIX[size - 1])
                        continue;
                    forward.writeBytes(held.toByteArray());
                    held.reset();
                    passing = value != '\n';
                    continue;
                }
                if (value != '\n')
                    continue;
                byte[] line = held.toByteArray();
                held.reset();
                if (forwarded.add(ByteBuffer.wrap(line)))
                    forward.writeBytes(line);
            }
            return forward.toByteArray();
        }

        /**
         * Ends the stream.
         * @return the held bytes of an incomplete final line, which are forwarded unfiltered
         */
        byte[] finish()
        {
            byte[] rest = held.toByteArray();
            held.reset();
            return rest;
        }
    }

    /** Owns the documented native operations used by endpoint acquisition and relay workers. */
    public interface Calls extends AutoCloseable
    {
        /** Supplies shared storage.
         * @return storage retained until all relay workers join
         */
        Arena arena();
        /**
         * Invokes a pointer-valued operation.
         * @param name supported operation
         * @param arguments native arguments
         * @return native pointer bits
         */
        MemorySegment address(String name, Object... arguments);
        /**
         * Invokes an integer-valued operation.
         * @param name supported operation
         * @param arguments native arguments
         * @return native integer result
         */
        int integer(String name, Object... arguments);
        /** Reads the same-call native error.
         * @return the calling thread's captured native error
         */
        int error();
        /**
         * Tests whether a borrowed standard endpoint is a console.
         * @param handle original endpoint
         * @return whether a private pipe relay is needed
         */
        boolean console(MemorySegment handle);
        /**
         * Consumes one owned handle's native close attempt.
         * @param handle owned handle
         * @throws IOException if native closure fails
         */
        void closeHandle(MemorySegment handle) throws IOException;
        /** Releases bindings after all owned workers and handle close attempts finish. */
        @Override void close();
    }

    /**
     * Owns Windows pipe/console native bindings shared with independently owned relay workers.
     */
    private static final class Native implements Calls
    {
        /** Storage shared safely with relay threads; closed only after every worker joins. */
        private final Arena arena = Arena.ofShared();
        /** Platform call bindings resolved once for this stream owner. */
        private final Map<String, MethodHandle> functions = new HashMap<>();
        /** Windows linker-provided same-call error record layout. */
        private final MemoryLayout callState;
        /** Offset of captured GetLastError within each native call record. */
        private final long errorOffset;
        /** Per-worker native error, captured before control returns through Java. */
        private final ThreadLocal<Integer> lastError = ThreadLocal.withInitial(() -> 0);

        /**
         * Resolves Windows APIs through the invocation's Pouch-provided linker.
         * @param scope borrowed invocation services
         */
        // Windows console handles cannot be imported remotely; scoped FFM creates locally owned relay pipes.
        @SuppressWarnings("restricted")
        Native(WindowsCargoScope scope)
        {
            if (!System.getProperty("os.name", "").startsWith("Windows") || ValueLayout.ADDRESS.byteSize() != 8)
                throw new UnsupportedOperationException("Cargo console relay requires native 64-bit Windows");
            this.callState = Linker.Option.captureStateLayout();
            this.errorOffset = callState.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
            try
            {
                SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
                bind(scope, kernel, "GetStdHandle", ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                bind(scope, kernel, "GetConsoleMode", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "CreatePipe", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                bind(scope, kernel, "CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetCurrentThreadId", ValueLayout.JAVA_INT);
                bind(scope, kernel, "OpenThread", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
                bind(scope, kernel, "CancelSynchronousIo", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                for (String name : List.of("ReadFile", "WriteFile"))
                    bind(scope, kernel, name, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            }
            catch (RuntimeException | Error problem) { arena.close(); throw problem; }
        }

        /**
         * Resolves one supported Windows ABI operation.
         * @param scope borrowed native linker provider
         * @param library owning library
         * @param name exported symbol
         * @param result return-value layout
         * @param arguments argument layouts
         */
        // Java does not expose console HANDLE I/O or cancellation; only the documented relay operations are bound.
        @SuppressWarnings("restricted")
        private void bind(WindowsCargoScope scope, SymbolLookup library, String name,
            MemoryLayout result, MemoryLayout... arguments)
        {
            functions.put(name, scope.tooling().getNativeLinker().downcallHandle(library.find(name).orElseThrow(),
                FunctionDescriptor.of(result, arguments), Linker.Option.captureCallState("GetLastError")));
        }

        /**
         * Invokes one bound native function.
         * @param name exported operation
         * @param arguments native arguments
         * @return native result
         */
        private Object call(String name, Object... arguments)
        {
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment state = temporary.allocate(callState);
                List<Object> invocation = new ArrayList<>(arguments.length + 1);
                invocation.add(state);
                invocation.addAll(java.util.Arrays.asList(arguments));
                Object result = functions.get(name).invokeWithArguments(invocation);
                lastError.set(state.get(ValueLayout.JAVA_INT, errorOffset));
                return result;
            }
            catch (RuntimeException | Error problem) { throw problem; }
            catch (Throwable problem) { throw new IllegalStateException("Windows console call failed: " + name, problem); }
        }

        /**
         * Invokes a pointer-valued function.
         * @param name bound operation
         * @param arguments native arguments
         * @return native pointer bits
         */
        @Override public MemorySegment address(String name, Object... arguments) { return (MemorySegment) call(name, arguments); }

        /**
         * Invokes an int-valued function.
         * @param name bound operation
         * @param arguments native arguments
         * @return native int bits
         */
        @Override public int integer(String name, Object... arguments) { return (int) call(name, arguments); }

        /**
         * Reads the calling thread's native error.
         * @return Windows error code
         */
        @Override public int error() { return lastError.get(); }

        /**
         * Determines whether an endpoint is a local console handle.
         * @param handle original standard handle
         * @return true for a console endpoint, false for a redirected file or pipe
         */
        @Override public boolean console(MemorySegment handle)
        {
            try (Arena temporary = Arena.ofConfined())
            {
                return integer("GetConsoleMode", handle, temporary.allocate(ValueLayout.JAVA_INT)) != 0;
            }
        }

        /**
         * Closes one owned handle; borrowed standard handles never reach this operation.
         * @param handle owned handle, or NULL when its close authority was consumed
         * @throws IOException if closing fails
         */
        @Override public void closeHandle(MemorySegment handle) throws IOException
        {
            if (handle.address() != 0 && integer("CloseHandle", handle) == 0)
                throw new IOException("CloseHandle(console relay) failed: " + error());
        }

        @Override public Arena arena() { return arena; }

        @Override public void close() { arena.close(); }
    }
}
