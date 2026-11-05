package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Owns one native registration listener and every accepted partial peer on its constructing thread.
 * Construction acquires no resources. The invocation retains this owner before opening it and retains its
 * borrowed scope through final closure. Imported descriptors remain owned by their peer across compiler retries;
 * only the consumer's verified whole-attempt barrier permits closing that peer. Native captures grant neither
 * direct Cargo parenting nor compiler executable authority, which registration must independently authenticate.
 */
public final class MacCargoNative implements AutoCloseable
{
    /** Shared native linker borrowed through final closure. */
    private final ToolingScope scope;
    /** Absolute trusted installed native bridge. */
    private final Path library;
    /** Constructing thread; native arguments and handles never cross threads. */
    private final Thread thread = Thread.currentThread();
    /** Library lifetime retained through every partial native owner. */
    private Arena arena;
    /** Indicates that every required native symbol bound successfully. */
    private boolean libraryReady;
    /** Native entry points bound once from the trusted library. */
    private final Map<String, MethodHandle> functions = new HashMap<>();
    /** Every partial accepted peer retained before checking native status. */
    private final LinkedHashSet<Peer> peers = new LinkedHashSet<>();
    /** Partial FIFO owners retained before native acquisition status is checked. */
    private final LinkedHashSet<Fifo> fifos = new LinkedHashSet<>();
    /** Partial native listener retained before checking open status. */
    private MemorySegment server = MemorySegment.NULL;
    /** Acquisition is never replayed after an uncertain result. */
    private boolean attempted;
    /** Indicates successful final native and library closure. */
    private boolean closed;
    /** Listener close attempt consumed once, including failures. */
    private boolean closeAttempted;
    /** Exact first listener destruction failure, retained without replay. */
    private Throwable closeFailure;

    /**
     * Retains trusted resource-free inputs for later native acquisition.
     * @param scope caller-owned invocation scope
     * @param library absolute trusted installed bridge path
     * @throws NullPointerException if an input is null
     * @throws IllegalArgumentException if the library is relative
     */
    MacCargoNative(ToolingScope scope, Path library)
    {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.library = Objects.requireNonNull(library, "library");
        if (!library.isAbsolute()) throw new IllegalArgumentException("Cargo bridge path must be absolute");
    }

    /**
     * Acquires a fresh listener once, retaining partial ownership on failure.
     * @param endpoint fresh absolute socket pathname in the invocation-owned namespace
     * @param uid original positive workload user identifier
     * @param gid original positive workload group identifier
     * @throws IOException if the platform, binding or listener acquisition fails
     * @throws IllegalStateException if called from another thread or after an earlier open or closure
     */
    void open(Path endpoint, int uid, int gid) throws IOException
    {
        requireThread();
        if (attempted || closed) throw new IllegalStateException("Cargo listener acquisition already consumed");
        Objects.requireNonNull(endpoint, "endpoint");
        if (!endpoint.isAbsolute() || uid <= 0 || gid <= 0)
            throw new IllegalArgumentException("Cargo listener requires an absolute path and positive workload credentials");
        attempted = true;
        load();
        try (Arena call = Arena.ofConfined())
        {
            MemorySegment output = call.allocate(ValueLayout.ADDRESS);
            int status;
            try { status = invoke("mc_listen", call.allocateFrom(endpoint.toString()), uid, gid, output); }
            finally { server = output.get(ValueLayout.ADDRESS, 0); }
            check("mc_listen", status);
        }
    }

    /**
     * Accepts one actual kernel peer or reports an empty listener queue.
     * @return retained peer or empty when no connection is queued
     * @throws IOException if native acceptance or kernel credentials fail
     * @throws IllegalStateException if called from another thread
     */
    Optional<Peer> accept() throws IOException
    {
        requireThread();
        if (closed || server.equals(MemorySegment.NULL)) throw new IOException("Cargo native listener is unavailable");
        try (Arena call = Arena.ofConfined())
        {
            MemorySegment output = call.allocate(ValueLayout.ADDRESS);
            MemorySegment pid = call.allocate(ValueLayout.JAVA_INT);
            MemorySegment uid = call.allocate(ValueLayout.JAVA_INT);
            MemorySegment gid = call.allocate(ValueLayout.JAVA_INT);
            int status;
            Peer peer;
            try { status = invoke("mc_accept", server, output, pid, uid, gid); }
            finally
            {
                MemorySegment handle = output.get(ValueLayout.ADDRESS, 0);
                peer = handle.equals(MemorySegment.NULL) ? null : new Peer(handle);
                if (peer != null) peers.add(peer);
            }
            if (status == -1 && peer == null) return Optional.empty();
            check("mc_accept", status);
            if (peer == null) throw new IOException("Native accept succeeded without a retained peer");
            return Optional.of(peer);
        }
    }

    /**
     * Returns retained peers, including partial acquisitions, for ordered invocation cleanup.
     * @return immutable peer owners retained by this listener
     * @throws IllegalStateException if called from another thread
     */
    List<Peer> peers()
    {
        requireThread();
        return List.copyOf(peers);
    }

    /**
     * Closes the native listener only after every peer and compiler consumer has retired.
     * @throws IOException if peers remain or native destruction failed
     * @throws IllegalStateException if called from another thread
     */
    @Override public void close() throws IOException
    {
        requireThread();
        if (closed) return;
        if (!peers.isEmpty() || !fifos.isEmpty()) throw new IOException("Cargo peers remain owned; collect attempts before closing their streams");
        if (closeAttempted)
        {
            if (closeFailure instanceof IOException problem) throw problem;
            if (closeFailure != null) throw new IOException("Cargo listener destruction remains uncertain", closeFailure);
            return;
        }
        closeAttempted = true;
        try
        {
            if (!server.equals(MemorySegment.NULL)) check("mc_close_server", invoke("mc_close_server", server));
            server = MemorySegment.NULL;
            if (arena != null) arena.close();
            closed = true;
        }
        catch (IOException | RuntimeException | Error failure) { closeFailure = failure; throw failure; }
    }

    /**
     * Creates one invocation-owned native FIFO, retaining partial ownership before checking native status.
     * @param path fresh absolute FIFO pathname inside the invocation-owned namespace
     * @param uid positive original workload user identifier
     * @param gid positive original workload group identifier
     * @param jobs total implicit and extra jobserver slots, from one through1024
     * @return retained native FIFO owner with jobs minus one initial extra opaque bytes
     * @throws IOException if native creation or initial capacity publication fails
     * @throws IllegalArgumentException if path or capacity inputs are invalid
     * @throws IllegalStateException if called from another thread or after closure
     */
    Fifo createFifo(Path path, int uid, int gid, int jobs) throws IOException
    {
        requireThread();
        Objects.requireNonNull(path, "path");
        if (closed || closeAttempted) throw new IllegalStateException("Cargo native owner is closed");
        if (!path.isAbsolute() || uid <= 0 || gid <= 0 || jobs < 1 || jobs > 1024)
            throw new IllegalArgumentException("Native FIFO requires an absolute path, positive credentials and one through1024 slots");
        load();
        try (Arena call = Arena.ofConfined())
        {
            MemorySegment output = call.allocate(ValueLayout.ADDRESS);
            Fifo fifo;
            int status;
            try { status = invoke("mc_fifo_create", call.allocateFrom(path.toString()), uid, gid, jobs, output); }
            finally
            {
                MemorySegment handle = output.get(ValueLayout.ADDRESS, 0);
                fifo = handle.equals(MemorySegment.NULL) ? null : new Fifo(handle);
                if (fifo != null) fifos.add(fifo);
            }
            check("mc_fifo_create", status);
            if (fifo == null) throw new IOException("Native FIFO acquisition succeeded without a retained owner");
            return fifo;
        }
    }

    /**
     * Returns every retained partial FIFO for invocation cleanup after all consumers are collected.
     * @return immutable retained FIFO owners
     * @throws IllegalStateException if called from another thread
     */
    List<Fifo> fifos() { requireThread(); return List.copyOf(fifos); }

    /**
     * Carries a descriptor-bound FIFO vnode identity without granting token or process authority.
     * @param device native FIFO device identifier
     * @param inode exact native FIFO inode identifier
     */
    public record FifoIdentity(long device, long inode) {}

    /** Owns exact native FIFO transport; token accounting and consumer terminal authority belong to the invocation. */
    final class Fifo implements LinuxCargoTokens.Transfer, AutoCloseable
    {
        /** Partial native FIFO owner retained before checking acquisition status. */
        private final MemorySegment handle;
        /** Indicates successful destruction. */
        private boolean fifoClosed;
        /** Destruction consumes its single opportunity even when its effect is uncertain. */
        private boolean attemptedClose;
        /** Exact first destruction failure retained without replay. */
        private Throwable failure;

        /**
         * Retains one partial native FIFO owner.
         * @param handle nonnull actual native owner
         */
        private Fifo(MemorySegment handle) { this.handle = handle; }

        /**
         * Revalidates the retained actual descriptor before copying its exact FIFO vnode identity.
         * @return descriptor-bound native device and inode identifiers
         * @throws IOException if descriptor metadata changed or cannot be read
         * @throws IllegalStateException if called from another thread or after destruction began
         */
        FifoIdentity identity() throws IOException
        {
            available();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(2L * Long.BYTES, Long.BYTES);
                check("mc_fifo_identity", invoke("mc_fifo_identity", handle, output));
                return new FifoIdentity(output.get(ValueLayout.JAVA_LONG, 0), output.get(ValueLayout.JAVA_LONG, Long.BYTES));
            }
        }

        /**
         * Reads one opaque byte without waiting for capacity.
         * @return exact unsigned byte, or empty only for native-confirmed no-effect empty capacity
         * @throws IOException if native read or returned byte is invalid
         * @throws IllegalStateException if called from another thread or after destruction began
         */
        @Override public OptionalInt tryRead() throws IOException
        {
            available();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(ValueLayout.JAVA_INT);
                check("mc_fifo_read", invoke("mc_fifo_read", handle, output));
                int value = output.get(ValueLayout.JAVA_INT, 0);
                if (value == -1) return OptionalInt.empty();
                if (value < 0 || value > 255) throw new IOException("Native FIFO returned an invalid opaque byte");
                return OptionalInt.of(value);
            }
        }

        /**
         * Returns exactly the original opaque byte after its consumer can no longer execute.
         * @param token original byte authorized by the shared token ledger
         * @throws IOException if a complete native write cannot be confirmed
         * @throws IllegalStateException if called from another thread or after destruction began
         */
        @Override public void write(byte token) throws IOException
        {
            available();
            check("mc_fifo_write", invoke("mc_fifo_write", handle, Byte.toUnsignedInt(token)));
        }

        /**
         * Destroys this exact FIFO only after every token consumer and accounting owner retires.
         * @throws IOException if native destruction remains unresolved
         * @throws IllegalStateException if called from another thread
         */
        @Override public void close() throws IOException
        {
            requireThread();
            if (fifoClosed) return;
            if (attemptedClose)
            {
                if (failure instanceof IOException problem) throw problem;
                if (failure != null) throw new IOException("Native FIFO destruction remains uncertain", failure);
                return;
            }
            attemptedClose = true;
            try
            {
                check("mc_fifo_close", invoke("mc_fifo_close", handle));
                fifoClosed = true;
                fifos.remove(this);
            }
            catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        }

        /**
         * Requires constructing-thread access and a live transport owner.
         * @throws IllegalStateException if called from another thread or after destruction began
         */
        private void available()
        {
            requireThread();
            if (attemptedClose || fifoClosed) throw new IllegalStateException("Native FIFO is closed or destruction is uncertain");
        }
    }

    /**
     * Loads one trusted bridge lifetime and binds the exact production ABI.
     * @throws IOException if the platform or any required bridge symbol is unavailable
     */
    // Native library lookup requires explicit JVM native-access authority; the arena remains owned on failure.
    @SuppressWarnings("restricted")
    private void load() throws IOException
    {
        if (libraryReady) return;
        if (arena != null) throw new IOException("Cargo bridge binding previously failed; retain the existing owner for cleanup");
        if (!System.getProperty("os.name", "").startsWith("Mac")) throw new IOException("Mac Cargo requires macOS");
        arena = Arena.ofConfined();
        SymbolLookup symbols = SymbolLookup.libraryLookup(library, arena);
        bind(symbols, "mc_listen", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        bind(symbols, "mc_accept", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        bind(symbols, "mc_receive", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        bind(symbols, "mc_capture", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        bind(symbols, "mc_parent_environment", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        bind(symbols, "mc_peer_identity", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        bind(symbols, "mc_streams", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        bind(symbols, "mc_respond", ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        bind(symbols, "mc_close_peer", ValueLayout.ADDRESS);
        bind(symbols, "mc_close_server", ValueLayout.ADDRESS);
        bind(symbols, "mc_fifo_create", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
        bind(symbols, "mc_fifo_read", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        bind(symbols, "mc_fifo_identity", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        bind(symbols, "mc_fifo_write", ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        bind(symbols, "mc_fifo_close", ValueLayout.ADDRESS);
        libraryReady = true;
    }

    /**
     * Binds one required native entry point using the borrowed scoped linker.
     * @param symbols trusted loaded library
     * @param name exact exported operation
     * @param arguments exact ABI argument layouts
     * @throws IOException if the required symbol is absent
     */
    // Downcall binding inherently requires JVM native-access authority for this fixed trusted bridge ABI.
    @SuppressWarnings("restricted")
    private void bind(SymbolLookup symbols, String name, MemoryLayout... arguments) throws IOException
    {
        MemorySegment symbol = symbols.find(name).orElseThrow(() -> new IOException("Missing Cargo bridge symbol: " + name));
        functions.put(name, scope.getNativeLinker().downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments)));
    }

    /**
     * Invokes a native operation without hiding its original unchecked failure.
     * @param name exact bound operation
     * @param arguments exact ABI arguments
     * @return native status
     * @throws IOException if invocation cannot produce a status
     */
    private int invoke(String name, Object... arguments) throws IOException
    {
        try { return (int) functions.get(name).invokeWithArguments(arguments); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IOException("Native Cargo invocation failed: " + name, failure); }
    }

    /**
     * Preserves the exact native failure number.
     * @param operation native operation
     * @param status zero for success, otherwise native error
     * @throws IOException if status is nonzero
     */
    private static void check(String operation, int status) throws IOException
    {
        if (status != 0) throw new IOException(operation + " failed with native status " + status);
    }

    /**
     * Carries actual socket endpoint kernel authority, requiring separate workload and Cargo authentication.
     * @param pid actual peer process identifier
     * @param unique exact native incarnation identifier
     * @param version actual socket audit image version
     * @param uid kernel peer effective user identifier
     * @param gid kernel peer effective group identifier
     */
    record KernelIdentity(long pid, long unique, long version, int uid, int gid) {}

    /**
     * Rejects access from another thread before observing native ownership.
     * @throws IllegalStateException if another thread calls
     */
    private void requireThread()
    {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Cargo native owner belongs to its constructing thread");
    }

    /**
     * Retains an accepted partial socket and its imported stream descriptions until whole-attempt collection.
     * This owner never launches, resumes or kills a process and never infers compiler result authority.
     */
    final class Peer implements AutoCloseable
    {
        /** Native partial peer retained immediately after accept. */
        private final MemorySegment handle;
        /** Indicates whether the registration receive opportunity was consumed. */
        private boolean received;
        /** Indicates whether the final response opportunity was consumed. */
        private boolean responded;
        /** Indicates whether native destruction consumed its single attempt. */
        private boolean attemptedClose;
        /** Indicates successful native destruction. */
        private boolean peerClosed;
        /** Exact first uncertain close failure, retained without replay. */
        private Throwable failure;

        /**
         * Retains one partial native peer before any follow-up operation.
         * @param handle actual nonnull native peer handle
         */
        private Peer(MemorySegment handle) { this.handle = handle; }

        /**
         * Reads the actual socket audit-token incarnation before registration grants authority.
         * @return complete kernel endpoint identity
         * @throws IOException if native identity is unavailable or outside supported signed ranges
         * @throws IllegalStateException if called from another thread or after closure
         */
        KernelIdentity identity() throws IOException
        {
            available();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(5L * Long.BYTES, Long.BYTES);
                check("mc_peer_identity", invoke("mc_peer_identity", handle, output));
                long[] fields = output.toArray(ValueLayout.JAVA_LONG);
                if (fields[0] <= 0 || fields[1] <= 0 || fields[2] < 0 || fields[3] < 0 || fields[3] > Integer.MAX_VALUE || fields[4] < 0 || fields[4] > Integer.MAX_VALUE)
                    throw new IOException("Native peer identity exceeds supported ranges");
                return new KernelIdentity(fields[0], fields[1], fields[2], (int) fields[3], (int) fields[4]);
            }
        }

        /**
         * Receives exactly one claimed frame and retains imported descriptors before checking native status.
         * @return complete untrusted registration frame
         * @throws IOException if native receive or complete frame bounds fail
         * @throws IllegalStateException if called from another thread, after closure or after an earlier receive
         */
        byte[] receive() throws IOException
        {
            available();
            if (received) throw new IllegalStateException("Cargo registration receive already consumed");
            received = true;
            return frame("mc_receive");
        }

        /**
         * Captures independent actual kernel argv, environment and cwd for this same endpoint incarnation.
         * @return complete kernel-captured frame
         * @throws IOException if native capture or complete frame bounds fail
         * @throws IllegalStateException if called from another thread or after closure
         */
        byte[] capture() throws IOException { available(); return frame("mc_capture"); }

        /**
         * Captures the initial kernel environment of this endpoint's direct parent image, such as a Cargo build script.
         * @param pid     parent process identifier
         * @param version parent image version from its native authority
         * @return complete kernel-captured frame holding one environment vector
         * @throws IllegalArgumentException if {@code pid} or {@code version} is outside the native range
         * @throws IOException              if the parent exited, runs another image, or native capture or frame
         *                                  bounds fail
         * @throws IllegalStateException    if called from another thread, after closure or before receive
         */
        byte[] parentEnvironment(long pid, long version) throws IOException
        {
            if (pid <= 0 || pid > Integer.MAX_VALUE || version < 0 || version > 0xFFFF_FFFFL)
                throw new IllegalArgumentException("Parent image identity is outside the native range");
            available();
            return frame("mc_parent_environment", (int) pid, (int) version);
        }

        /**
         * Copies one complete bounded native frame without trusting its returned length.
         * @param operation exact native frame-producing operation
         * @param leading   arguments that precede the output buffer, after the peer handle
         * @return copied complete frame
         * @throws IOException if native status or complete length is invalid
         */
        private byte[] frame(String operation, Object... leading) throws IOException
        {
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(1024L * 1024);
                MemorySegment size = call.allocate(ValueLayout.JAVA_INT);
                List<Object> arguments = new ArrayList<>();
                arguments.add(handle);
                arguments.addAll(List.of(leading));
                arguments.add(output);
                arguments.add((int) output.byteSize());
                arguments.add(size);
                check(operation, invoke(operation, arguments.toArray()));
                int bytes = size.get(ValueLayout.JAVA_INT, 0);
                if (bytes <= 0 || bytes > output.byteSize()) throw new IOException("Native registration frame length exceeds capacity");
                return output.asSlice(0, bytes).toArray(ValueLayout.JAVA_BYTE);
            }
        }

        /**
         * Borrows actual imported descriptions retained across every compiler retry.
         * @return three descriptors kept open until this peer closes
         * @throws IOException if native stream acquisition fails
         * @throws IllegalStateException if called from another thread or after closure
         */
        MacWorkloadNative.Streams streams() throws IOException
        {
            available();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(3L * Integer.BYTES, Integer.BYTES);
                check("mc_streams", invoke("mc_streams", handle, output));
                int[] values = output.toArray(ValueLayout.JAVA_INT);
                return new MacWorkloadNative.Streams(values[0], values[1], values[2]);
            }
        }

        /**
         * Responds once with a genuine accepted compiler root result after the complete attempt barrier.
         * @param result genuine native original-root status from zero through255
         * @throws IOException if response effect cannot be confirmed
         * @throws IllegalArgumentException if result is outside zero through255
         * @throws IllegalStateException if called from another thread, after closure or after a prior response
         */
        void respond(int result) throws IOException
        {
            available();
            if (result < 0 || result > 255) throw new IllegalArgumentException("Compiler result must be zero through255");
            if (responded) throw new IllegalStateException("Cargo response opportunity already consumed");
            responded = true;
            check("mc_respond", invoke("mc_respond", handle, result));
        }

        /**
         * Requires the constructing thread and a live retained peer.
         * @throws IllegalStateException if ownership ended or another thread calls
         */
        private void available()
        {
            requireThread();
            if (attemptedClose || peerClosed) throw new IllegalStateException("Cargo native peer is closed or destruction is uncertain");
        }

        /**
         * Closes this peer only after every attempt borrowing its streams can no longer execute.
         * @throws IOException if native destruction remains unresolved
         * @throws IllegalStateException if called from another thread
         */
        @Override public void close() throws IOException
        {
            requireThread();
            if (peerClosed) return;
            if (attemptedClose)
            {
                if (failure instanceof IOException problem) throw problem;
                if (failure != null) throw new IOException("Cargo peer destruction remains uncertain", failure);
                return;
            }
            attemptedClose = true;
            try
            {
                check("mc_close_peer", invoke("mc_close_peer", handle));
                peerClosed = true;
                peers.remove(this);
            }
            catch (IOException | RuntimeException | Error problem) { failure = problem; throw problem; }
        }
    }
}
