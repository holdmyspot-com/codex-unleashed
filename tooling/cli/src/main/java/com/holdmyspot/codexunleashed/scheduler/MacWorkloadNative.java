package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Owns one launchd service, its non-forking anchor and original root's native streams and wait.
 * The owner retains the original Frida manager and every partial native acquisition through cleanup.
 * Implementations authenticate resource-coalition membership, unique incarnation, credentials and
 * executable identity independently of callback data before exposing image operations.
 * A successful terminal observation composes a no-user-producer cut with native exit observations
 * for every retained incarnation and the genuine original root wait. Neither a PID scan nor coalition
 * count alone establishes that observation. An orphan's exit observation never grants waitpid authority.
 */
public interface MacWorkloadNative extends MacImages.Operations, MacImages.WorkloadAuthority, AutoCloseable
{
    /**
     * Authenticates a copied held event using this invocation's native authority.
     * @param child copied native held-child event
     * @param observed independently acquired image lineage
     * @throws IOException if callback PID or live workload authority differs
     */
    @Override default void authenticateChild(FridaRuntime.Child child, MacImages.Identity observed) throws IOException
    {
        if (child.pid() != observed.pid()) throw new IOException("Held event names another process");
        authenticate(observed);
    }

    /**
     * Returns the original root retained before its gate can be released.
     * @return independently authenticated, still-held original root lineage
     * @throws IOException if held-root authority cannot be established
     */
    MacImages.Identity root() throws IOException;

    /**
     * Releases the original native root gate once after image instrumentation is ready.
     * An uncertain release must never be repeated, including during cleanup.
     * @throws IOException if identity revalidation or release cannot be confirmed
     */
    void releaseRoot() throws IOException;

    /**
     * Copies the next held-child event from the original retained Frida manager.
     * @return a copied event, or empty when no event is currently queued
     * @throws IOException if observation is lost or any callback copy failed
     */
    Optional<FridaRuntime.Child> pollChild() throws IOException;

    /**
     * Cancels owned active incarnations using retained service/anchor/coalition authority.
     * Discovery must work without the failed primary membership stream. Each signal uses a real
     * kernel audit token bound to the selected incarnation; foreign processes remain untouched.
     * Repeated calls may discover additional members but must not replay an uncertain signal.
     * @throws IOException if exact cancellation or retained cleanup ownership cannot be confirmed
     */
    void cancel() throws IOException;

    /**
     * Observes the stable terminal composition while retaining the non-forking anchor.
     * Once returned, a result must remain valid until this owner's close finishes: no user producer
     * can reappear. Retained native exits and original wait are required in addition to that cut.
     * @return genuine original root result after the complete terminal composition, or empty while
     * any producer, retained native exit or original root wait remains unresolved
     * @throws IOException if terminal observation cannot be authenticated
     */
    OptionalInt terminalResult() throws IOException;

    /**
     * Removes the exact owned service, anchor and streams after terminal observation and image cleanup.
     * Failed cleanup retains every unresolved handle and reports its original failure without replaying
     * an uncertain native effect. The original Frida manager closes only after its image sessions close.
     * @throws IOException if native resources or genuine original wait remain unresolved
     */
    @Override
    void close() throws IOException;
    /**
     * Borrows three open descriptors for acquisition; the native owner duplicates them before launch.
     * The caller retains its descriptors and keeps them open until root acquisition returns.
     * @param input readable original standard input descriptor
     * @param output writable original standard output descriptor
     * @param error writable original standard error descriptor
     */
    record Streams(int input, int output, int error)
    {
        /**
         * Rejects negative descriptors without acquiring or closing the borrowed resources.
         * @param input readable original standard input descriptor
         * @param output writable original standard output descriptor
         * @param error writable original standard error descriptor
         * @throws IllegalArgumentException if a descriptor is negative
         */
        public Streams
        {
            if (input < 0 || output < 0 || error < 0)
                throw new IllegalArgumentException("Borrowed stream descriptors must be nonnegative");
        }
    }

    /**
     * Carries an authenticated image's full native authority with one directly measured residency.
     * This value supplies neither a predicted peak nor a terminal certificate.
     * @param authority all eighteen native image fields in the bridge's documented order
     * @param residentBytes directly observed nonnegative resident bytes
     */
    record Resident(List<Long> authority, long residentBytes)
    {
        /**
         * Freezes a complete native image and validates represented residency.
         * @param authority all eighteen native fields with positive PID and unique incarnation
         * @param residentBytes directly observed nonnegative resident bytes
         * @throws NullPointerException if authority or a field is null
         * @throws IllegalArgumentException if the image is incomplete or its bytes are negative
         */
        public Resident
        {
            authority = List.copyOf(authority);
            if (authority.size() != 18 || authority.get(0) <= 0 || authority.get(1) <= 0 || residentBytes < 0)
                throw new IllegalArgumentException("Resident observation requires eighteen image fields, positive PID/incarnation and nonnegative bytes");
        }
    }

    /**
     * Reports a native cohort or image change that invalidates a measurement without granting completion.
     * A measurement adapter must correlate this change to its exact registered attempt before issuing retry permission.
     */
    public final class MembershipChanged extends IOException
    {
        /** Serialization version for this stateless checked native observation failure. */
        private static final long serialVersionUID = 1L;
        /** Creates the bridge's independently observed measurement-churn result. */
        public MembershipChanged()
        {
            super("Authenticated native workload membership or image changed during residency observation");
        }
    }

    /**
     * Reports independently incomplete native process or executable-region inventory without claiming churn or supplying partial rows.
     * The measurement adapter must correlate the rejection to the exact retained registered attempt.
     */
    public final class IncompleteInventory extends IOException
    {
        /** Serialization version for this stateless checked observation failure. */
        private static final long serialVersionUID = 1L;
        /** Creates the native incomplete-inventory result. */
        public IncompleteInventory()
        {
            super("Authenticated native process or executable-region inventory is incomplete");
        }
    }

    /**
     * Describes one trusted native launch without acquiring resources.
     * The caller creates a fresh root-owned mode-0700 directory and supplies trusted installed binaries.
     * @param library absolute trusted bridge library path
     * @param helper absolute trusted root-owned launchd anchor executable
     * @param modules trusted installed dylibs that the hook scripts load into each held image
     * @param directory fresh exclusive launch state directory with at most 90 UTF-8 bytes
     * @param workingDirectory absolute original invocation working directory
     * @param streams original borrowed standard stream descriptors
     * @param command nonempty executable name followed by its exact arguments; relative executable names
     * are interpreted in workingDirectory without searching PATH
     * @param environment complete explicit environment entries in NAME=value form
     * @param uid original workload real, effective and saved user identifier
     * @param gid original workload real, effective and saved group identifier
     */
    record Launch(Path library, Path helper, MacNativeModules modules, Path directory, Path workingDirectory, Streams streams,
        List<String> command, List<String> environment, int uid, int gid)
    {
        /**
         * Validates and freezes launch inputs before any native effect.
         * @param library trusted bridge path
         * @param helper trusted anchor path
         * @param modules trusted installed hook script dylibs
         * @param directory exclusive state directory with at most 90 UTF-8 bytes
         * @param workingDirectory absolute original invocation working directory
         * @param streams borrowed standard stream descriptors kept open through acquisition
         * @param command nonempty executable name and exact arguments, without NUL characters;
         * relative executable names use workingDirectory without PATH search
         * @param environment explicit complete environment
         * @param uid nonnegative target user identifier
         * @param gid nonnegative target group identifier
         * @throws IllegalArgumentException if a path, identifier or encoded argument is invalid
         * @throws NullPointerException if any input or entry is null
         */
        public Launch
        {
            Objects.requireNonNull(library, "library");
            Objects.requireNonNull(helper, "helper");
            Objects.requireNonNull(modules, "modules");
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(workingDirectory, "workingDirectory");
            Objects.requireNonNull(streams, "streams");
            command = List.copyOf(command);
            environment = List.copyOf(environment);
            if (!library.isAbsolute() || !helper.isAbsolute() || !directory.isAbsolute() || !workingDirectory.isAbsolute() || uid < 0 || gid < 0)
                throw new IllegalArgumentException("Absolute native launch paths and nonnegative credentials required");
            if (command.isEmpty() || command.getFirst().isEmpty())
                throw new IllegalArgumentException("Native launch requires a nonempty executable name");
            if (directory.toString().getBytes(StandardCharsets.UTF_8).length > 90)
                throw new IllegalArgumentException("Native launch directory exceeds the control socket UTF-8 byte budget");
            for (String value : command)
                if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Command contains a NUL character");
            Set<String> names = new HashSet<>();
            for (String value : environment)
            {
                int separator = value.indexOf('=');
                if (value.indexOf(0) >= 0 || separator <= 0)
                    throw new IllegalArgumentException("Environment requires NAME=value without NUL characters");
                if (!names.add(value.substring(0, separator)))
                    throw new IllegalArgumentException("Duplicate environment name: " + value.substring(0, separator));
            }
        }
    }

    /**
     * Connects the production bridge and original Frida manager on one constructing thread.
     * Construction acquires no resources. Retain this object before root acquisition; failed acquisition
     * retains its native handle and manager for cancellation and terminal observation.
     */
    final class Owner implements MacWorkloadNative
    {
        /** Constructing thread required for every native operation. */
        private final Thread thread = Thread.currentThread();
        /** Invocation scope supplying the shared native linker. */
        private final ToolingScope scope;
        /** Immutable trusted launch input. */
        private final Launch launch;
        /** Original manager retained through image closure. */
        private final FridaRuntime.Manager manager;
        /** Owned bridge library lifetime, retained on failure. */
        private Arena arena;
        /** Bound production native operations. */
        private final Map<String, MethodHandle> functions = new HashMap<>();
        /** Current authenticated full native image snapshots indexed by PID. */
        private final Map<Long, long[]> identities = new HashMap<>();
        /** Native partial owner retained before checking acquisition status. */
        private MemorySegment handle = MemorySegment.NULL;
        /** Indicates whether native acquisition has consumed its single opportunity. */
        private boolean attempted;
        /** Indicates whether loss recovery must continue discovering new ordinary descendants. */
        private boolean recovering;
        /** Indicates whether native owner destruction has completed. */
        private boolean nativeClosed;
        /** Indicates whether manager destruction has consumed its single opportunity. */
        private boolean managerCloseAttempted;
        /** Indicates whether all owned resources closed successfully. */
        private boolean closed;
        /** Exact uncertain destruction failure retained without replay. */
        private IOException closeFailure;

        /**
         * Retains resource-free launch configuration and the caller's original manager.
         * @param scope invocation scope retained through final native cleanup
         * @param launch trusted immutable native inputs
         * @param manager active original manager whose ownership transfers to this object
         * @throws NullPointerException if any input is null
         */
        public Owner(ToolingScope scope, Launch launch, FridaRuntime.Manager manager)
        {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.launch = Objects.requireNonNull(launch, "launch");
            this.manager = Objects.requireNonNull(manager, "manager");
        }

        @Override public MacImages.Identity root() throws IOException
        {
            requireThread();
            if (!attempted) acquire();
            requireHandle();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(18 * Long.BYTES, Long.BYTES);
                check("mw_root", invoke("mw_root", handle, output));
                return retain(output);
            }
        }

        /**
         * Acquires the native owner once, retaining a returned partial handle even when open fails.
         * @throws IOException if the platform, binding or native launch cannot be established
         */
        // FFM library loading is inherently restricted; this owner retains the trusted bridge arena on failure.
        @SuppressWarnings("restricted")
        private void acquire() throws IOException
        {
            attempted = true;
            if (!System.getProperty("os.name", "").startsWith("Mac")) throw new IOException("Mac workload requires macOS");
            arena = Arena.ofConfined();
            SymbolLookup library = SymbolLookup.libraryLookup(launch.library(), arena);
            bind(library, "mw_open", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind(library, "mw_root", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind(library, "mw_snapshot", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind(library, "mw_inventory", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind(library, "mw_descriptors", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind(library, "mw_platform", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind(library, "mw_release", ValueLayout.ADDRESS);
            bind(library, "mw_cancel", ValueLayout.ADDRESS);
            bind(library, "mw_terminal", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind(library, "mw_close", ValueLayout.ADDRESS);
            try (Arena call = Arena.ofConfined())
            {
                byte[] encoded = payload();
                MemorySegment bytes = call.allocateFrom(ValueLayout.JAVA_BYTE, encoded);
                MemorySegment output = call.allocate(ValueLayout.ADDRESS);
                MemorySegment streams = call.allocateFrom(ValueLayout.JAVA_INT, launch.streams().input(),
                    launch.streams().output(), launch.streams().error());
                int status;
                try
                {
                    status = invoke("mw_open", call.allocateFrom(launch.directory().toString()),
                        call.allocateFrom(launch.helper().toString()), bytes, encoded.length, launch.uid(), launch.gid(), streams, output);
                }
                finally { handle = output.get(ValueLayout.ADDRESS, 0); }
                check("mw_open", status);
            }
        }

        /**
         * Encodes exact argv, environment and working directory into the anchor's owned native-endian protocol.
         * @return bounded binary input containing credentials and exact NUL-terminated UTF-8 entries
         * @throws IOException if an encoded input exceeds native bounds
         */
        private byte[] payload() throws IOException
        {
            var output = new ByteArrayOutputStream();
            output.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(launch.uid()).putInt(launch.gid()).array());
            for (List<String> vector : List.of(launch.command(), launch.environment(), List.of(launch.workingDirectory().toString())))
            {
                if (vector.size() > 65536) throw new IOException("Native launch has too many entries");
                output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(vector.size()).array());
                for (String value : vector)
                {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length >= 1024 * 1024) throw new IOException("Native launch entry exceeds one MiB");
                    output.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(bytes.length + 1).array());
                    output.writeBytes(bytes);
                    output.write(0);
                }
            }
            return output.toByteArray();
        }

        @Override public MacImages.Identity inspect(long pid) throws IOException
        {
            requireThread(); requireHandle();
            if (pid <= 0 || pid > Integer.MAX_VALUE) throw new IOException("Native PID is outside its signed range");
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(18 * Long.BYTES, Long.BYTES);
                int status = invoke("mw_snapshot", handle, (int) pid, output);
                if (status == -3) throw new MacImages.IncompleteImage(pid);
                check("mw_snapshot", status);
                return retain(output);
            }
        }

        /**
         * Reads the complete descriptor table of one retained held image.
         * The native bridge rejects capacity exhaustion, changed descriptor rows and any change to
         * the retained eighteen-field authority. Its SDK supplies the kqueue syscall and type.
         * Callers must keep the application's image held while obtaining and using this inventory.
         * @param identity independently observed retained image lineage
         * @return complete immutable descriptor rows ordered by descriptor number with native kqueue metadata
         * @throws IOException if authority changed, enumeration is incomplete or the bridge fails
         * @throws IllegalStateException if called from another thread
         */
        @Override public MacDescriptorProtection.Inventory descriptors(MacImages.Identity identity) throws IOException
        {
            requireThread(); requireHandle();
            authenticate(identity);
            long[] expected = identities.get(identity.pid()).clone();
            try (Arena call = Arena.ofConfined())
            {
                int capacity = 65536;
                MemorySegment authority = call.allocateFrom(ValueLayout.JAVA_LONG, expected);
                MemorySegment output = call.allocate((long) capacity * 2 * Integer.BYTES, Integer.BYTES);
                MemorySegment count = call.allocate(ValueLayout.JAVA_INT);
                MemorySegment metadata = call.allocate(2L * Integer.BYTES, Integer.BYTES);
                check("mw_descriptors", invoke("mw_descriptors", handle, authority, output, capacity, count, metadata));
                authenticate(identity);
                if (!Arrays.equals(expected, identities.get(identity.pid())))
                    throw new IOException("Native image authority changed during descriptor inventory");

                int size = count.get(ValueLayout.JAVA_INT, 0);
                int kqueueSyscall = metadata.getAtIndex(ValueLayout.JAVA_INT, 0);
                int kqueueType = metadata.getAtIndex(ValueLayout.JAVA_INT, 1);
                if (size < 0 || size > capacity || kqueueSyscall <= 0 || kqueueType <= 0)
                    throw new IOException("Native descriptor inventory has invalid count or kqueue metadata");
                List<MacDescriptorProtection.Descriptor> result = new ArrayList<>(size);
                int previous = -1;
                for (int row = 0; row < size; ++row)
                {
                    int number = output.getAtIndex(ValueLayout.JAVA_INT, 2L * row);
                    int type = output.getAtIndex(ValueLayout.JAVA_INT, (2L * row) + 1);
                    if (number <= previous || number >= capacity || type < 0)
                        throw new IOException("Native descriptor rows are unordered or outside the supported range");
                    result.add(new MacDescriptorProtection.Descriptor(number, type));
                    previous = number;
                }
                return new MacDescriptorProtection.Inventory(kqueueSyscall, kqueueType, result);
            }
        }

        /**
         * Reads every currently authenticated user incarnation and its native resident bytes.
         * The bridge brackets residency with complete cohort observations and rejects detected changes.
         * An empty result remains a memory observation; terminalResult alone establishes completion.
         * @return immutable complete observed cohort excluding the non-forking anchor
         * @throws IncompleteInventory if native observations independently establish incomplete process or executable-region inventory
         * @throws MembershipChanged if native observations establish cohort or image churn
         * @throws IOException if no native handle remains or authority, access, capacity or a required reading fails
         * @throws IllegalStateException if called from another thread
         */
        public List<Resident> residents() throws IOException
        {
            requireThread(); requireHandle();
            try (Arena call = Arena.ofConfined())
            {
                int capacity = 32768;
                MemorySegment output = call.allocate((long) capacity * 19 * Long.BYTES, Long.BYTES);
                MemorySegment count = call.allocate(ValueLayout.JAVA_INT);
                int status = invoke("mw_inventory", handle, output, capacity, count);
                if (status == -2) throw new MembershipChanged();
                if (status == -3) throw new IncompleteInventory();
                check("mw_inventory", status);
                int size = count.get(ValueLayout.JAVA_INT, 0);
                if (size < 0 || size > capacity) throw new IOException("Native residency row count exceeds supplied capacity: " + size);
                List<Resident> result = new ArrayList<>(size);
                for (int row = 0; row < size; ++row)
                {
                    long offset = (long) row * 19 * Long.BYTES;
                    List<Long> authority = new ArrayList<>(18);
                    for (int field = 0; field < 18; ++field)
                        authority.add(output.get(ValueLayout.JAVA_LONG, offset + (long) field * Long.BYTES));
                    long bytes = output.get(ValueLayout.JAVA_LONG, offset + 18L * Long.BYTES);
                    if (bytes < 0) throw new IOException("Native resident bytes exceed the signed supported range");
                    result.add(new Resident(authority, bytes));
                }
                return List.copyOf(result);
            }
        }

        /**
         * Retains all independently authenticated native fields rather than reducing authority to PID and birth.
         * @param output complete native snapshot
         * @return Java lineage for the image state machine
         * @throws IOException if native birth fields are invalid
         */
        private MacImages.Identity retain(MemorySegment output) throws IOException
        {
            long[] snapshot = output.toArray(ValueLayout.JAVA_LONG);
            if (snapshot[5] < 0 || snapshot[6] < 0 || snapshot[6] >= 1_000_000)
                throw new IOException("Invalid native process birth");
            long[] previous = identities.get(snapshot[0]);
            if (previous != null)
            {
                long previousParent = previous[4];
                previous[4] = snapshot[4];
                boolean unchanged = Arrays.equals(previous, snapshot);
                previous[4] = previousParent;
                if (!unchanged) throw new IOException("Held native image authority changed without a new held event");
            }
            identities.put(snapshot[0], snapshot);
            return new MacImages.Identity(snapshot[0], Instant.ofEpochSecond(snapshot[5], snapshot[6] * 1000), snapshot[4]);
        }

        @Override public void authenticate(MacImages.Identity identity) throws IOException
        {
            requireThread();
            long[] before = identities.get(identity.pid());
            if (before == null) throw new IOException("Image has no retained native authority");
            MacImages.Identity current = inspect(identity.pid());
            if (!current.equals(identity) || !Arrays.equals(before, identities.get(identity.pid())))
                throw new IOException("Native workload image changed before effect");
        }

        /**
         * Reauthenticates one retained image and copies all native authority fields without reducing its identity.
         * @param identity independently observed retained image lineage
         * @return immutable complete eighteen-field native authority
         * @throws IOException if workload, image, credentials or retained incarnation changed
         * @throws IllegalStateException if called from another thread
         */
        public List<Long> authority(MacImages.Identity identity) throws IOException
        {
            authenticate(identity);
            return Arrays.stream(identities.get(identity.pid())).boxed().toList();
        }

        @Override public AllocationHook.Connection connection(long pid)
        {
            requireThread();
            return AllocationHook.newNativeConnection(manager, pid);
        }

        @Override public MacNativeModules modules() { return launch.modules(); }

        /**
         * Reads the kernel code-signing status of one retained held image.
         * The native bridge requires the retained eighteen-field authority before and after the query, so the
         * status belongs to this exact incarnation.
         * @param identity independently observed retained image lineage
         * @return {@code true} if the kernel marks the image as an Apple platform binary
         * @throws IOException if authority changed or the kernel query fails
         * @throws IllegalStateException if called from another thread
         */
        @Override public boolean platformImage(MacImages.Identity identity) throws IOException
        {
            requireThread(); requireHandle();
            authenticate(identity);
            long[] expected = identities.get(identity.pid()).clone();
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment authority = call.allocateFrom(ValueLayout.JAVA_LONG, expected);
                MemorySegment platform = call.allocate(ValueLayout.JAVA_INT);
                check("mw_platform", invoke("mw_platform", handle, authority, platform));
                authenticate(identity);
                if (!Arrays.equals(expected, identities.get(identity.pid())))
                    throw new IOException("Native image authority changed during code-signing inspection");
                return platform.get(ValueLayout.JAVA_INT, 0) != 0;
            }
        }

        @Override public void resume(long pid) throws IOException
        {
            authenticate(inspect(pid));
            manager.resume(pid);
        }

        @Override public void releaseRoot() throws IOException
        {
            requireThread(); requireHandle();
            check("mw_release", invoke("mw_release", handle));
        }

        @Override public Optional<FridaRuntime.Child> pollChild() throws IOException
        {
            requireThread();
            Optional<FridaRuntime.Child> child = manager.pollChild();
            child.ifPresent(event -> identities.remove(event.pid()));
            return child;
        }

        @Override public void cancel() throws IOException
        {
            requireThread(); requireHandle();
            recovering = true;
            check("mw_cancel", invoke("mw_cancel", handle));
        }

        @Override public OptionalInt terminalResult() throws IOException
        {
            requireThread(); requireHandle();
            if (recovering) check("mw_cancel", invoke("mw_cancel", handle));
            try (Arena call = Arena.ofConfined())
            {
                MemorySegment output = call.allocate(ValueLayout.JAVA_INT);
                int status = invoke("mw_terminal", handle, output);
                if (status == -1) return OptionalInt.empty();
                check("mw_terminal", status);
                return OptionalInt.of(output.get(ValueLayout.JAVA_INT, 0));
            }
        }

        @Override public void close() throws IOException
        {
            requireThread();
            if (closed) return;
            if (closeFailure != null) throw closeFailure;
            try
            {
                requireHandle();
                if (!nativeClosed)
                {
                    check("mw_close", invoke("mw_close", handle));
                    nativeClosed = true;
                    handle = MemorySegment.NULL;
                }
                if (!managerCloseAttempted)
                {
                    managerCloseAttempted = true;
                    manager.close();
                }
                arena.close();
                closed = true;
            }
            catch (IOException failure) { closeFailure = failure; throw failure; }
        }

        /**
         * Reports whether all native resources and the original manager closed successfully.
         * @return true only after complete successful destruction
         * @throws IllegalStateException if another thread calls
         */
        public boolean isClosed()
        {
            requireThread();
            return closed;
        }

        /**
         * Binds a required integer-returning production bridge operation before native acquisition.
         * @param library retained bridge lookup
         * @param name exported function name
         * @param arguments exact native argument layouts
         * @throws IOException if a required bridge symbol is absent
         */
        // The JDK marks every downcall binding restricted; exact layouts are this bridge's explicit ABI contract.
        @SuppressWarnings("restricted")
        private void bind(SymbolLookup library, String name, java.lang.foreign.MemoryLayout... arguments) throws IOException
        {
            MemorySegment symbol = library.find(name).orElseThrow(() -> new IOException("Missing workload bridge symbol: " + name));
            functions.put(name, scope.getNativeLinker().downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments)));
        }

        /**
         * Invokes a bound bridge operation while preserving checked invocation failures.
         * @param operation exported native operation
         * @param arguments exact ABI arguments
         * @return operation status
         * @throws IOException if invocation cannot return a native status
         */
        private int invoke(String operation, Object... arguments) throws IOException
        {
            try { return (int) functions.get(operation).invokeWithArguments(arguments); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("Native invocation failed: " + operation, failure); }
        }

        /**
         * Requires native success without discarding its exact error number.
         * @param operation native operation
         * @param status native status; zero means successful completion
         * @throws IOException if the native operation failed
         */
        private static void check(String operation, int status) throws IOException
        {
            if (status != 0) throw new IOException(operation + " failed with native status " + status);
        }

        /**
         * Refuses operations without a retained native owner.
         * @throws IOException if no native handle was acquired or it already closed
         */
        private void requireHandle() throws IOException
        {
            if (handle.equals(MemorySegment.NULL)) throw new IOException("Native workload owner is unavailable");
        }

        /**
         * Requires the constructing thread before observing or changing native ownership.
         * @throws IllegalStateException if another thread calls
         */
        private void requireThread()
        {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Native workload belongs to its constructing thread");
        }
    }

}
