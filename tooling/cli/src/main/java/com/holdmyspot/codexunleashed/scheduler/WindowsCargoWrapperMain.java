package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native Windows console app-image entry called by Cargo with rustc followed by its exact arguments.
 * The invocation supplies approved endpoint and controller native identity through the complete launch environment.
 * Compiler execution belongs to the controller; this wrapper keeps its Cargo slot and streams through retries.
 */
public final class WindowsCargoWrapperMain
{
    /** Invocation-owned local pipe name injected into the approved Cargo environment. */
    static final String ENDPOINT_ENV = "CODEX_CARGO_PIPE";
    /** Controller PID whose native birth must match the named-pipe server. */
    static final String CONTROLLER_PID_ENV = "CODEX_CARGO_CONTROLLER_PID";
    /** Controller creation FILETIME, expressed as a positive decimal long. */
    static final String CONTROLLER_BIRTH_ENV = "CODEX_CARGO_CONTROLLER_BIRTH";
    /** Deadline for partial frames and bounded writes, excluding idle queue time. */
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Prevents construction of the process entry point.
     */
    private WindowsCargoWrapperMain() {}

    /**
     * Captures native UTF-16 process state once, runs the wrapper, and exits with the exact unsigned compiler result bits.
     * @param arguments JVM-decoded arguments; native UTF-16 arguments are read independently
     * @throws IOException if authentication, protocol or stream ownership fails
     */
    public static void main(String[] arguments) throws IOException
    {
        try (DefaultToolingScope tooling = new DefaultToolingScope(); WindowsCargoScope scope = new WindowsCargoScope(tooling);
            Identity identity = new Identity(scope))
        {
            var nativeState = WindowsCreationReceipt.currentState(tooling);
            List<String> nativeArguments = WindowsCargoRegistration.parseCommandLine(tooling, nativeState.commandLine());
            long status = run(scope, identity, nativeArguments.subList(1, nativeArguments.size()),
                Path.of(nativeState.directory()).toAbsolutePath().normalize(),
                WindowsCargoRegistration.nativeEnvironment(nativeState.environment()));
            exit(scope, identity, status);
        }
    }

    /**
     * Releases invocation services before the irreversible native process exit.
     * The exit binding itself stays alive through TerminateProcess; failed exit returns to the enclosing cleanup owner.
     * @param scope owned invocation services whose stream borrowers have already closed
     * @param identity live native exit binding, independent of tooling after construction
     * @param status exact unsigned DWORD compiler result
     */
    private static void exit(WindowsCargoScope scope, Identity identity, long status)
    {
        ToolingScope tooling = scope.tooling();
        scope.close();
        tooling.close();
        identity.exit(status);
    }

    /**
     * Authenticates a server against explicit approved process inputs and runs one persistent wrapper.
     * @param scope borrowed invocation services
     * @param identity owned native identity access
     * @param arguments rustc executable followed by exact arguments
     * @param directory actual wrapper execution directory
     * @param environment complete captured wrapper environment including controller identity
     * @return native unsigned DWORD compiler status
     * @throws IOException if launch inputs, peer authentication or wrapper execution fails
     */
    static long run(WindowsCargoScope scope, Identity identity, List<String> arguments, Path directory,
        Map<String, String> environment) throws IOException
    {
        String endpoint = required(environment, ENDPOINT_ENV);
        long controller = positive(required(environment, CONTROLLER_PID_ENV));
        long birth = positive(required(environment, CONTROLLER_BIRTH_ENV));
        long pid = ProcessHandle.current().pid();
        long parent = ProcessHandle.current().parent().orElseThrow(() -> new IOException("Wrapper parent is unavailable")).pid();
        CargoCompilerRequest request = new CargoCompilerRequest(UUID.randomUUID(),
            new CargoCompilerRequest.Wrapper(pid, identity.birth(pid), parent, identity.birth(parent)), arguments,
            directory, environment, null);
        WindowsCargoStreams streams = WindowsCargoStreams.open(scope);
        WindowsCargoTransport.Connection connection = null;
        boolean transferred = false;
        try
        {
            connection = WindowsCargoTransport.connect(scope, endpoint, FRAME_TIMEOUT);
            if (connection.peerPid() != controller || identity.birth(controller) != birth)
                throw new IOException("Cargo pipe server differs from the approved controller incarnation");
            WindowsCargoMessages.Registration registration = new WindowsCargoMessages.Registration(request,
                streams.input(), streams.output(), streams.error());
            try (WindowsCargoWrapperSession session = new WindowsCargoWrapperSession(registration, connection, streams))
            {
                transferred = true;
                return new WindowsCargoWrapper(scope, registration, () -> session).run();
            }
        }
        catch (IOException | RuntimeException | Error problem)
        {
            if (!transferred)
            {
                try { if (connection != null) connection.close(); }
                catch (IOException cleanup) { problem.addSuppressed(cleanup); }
                try { streams.close(); }
                catch (IOException cleanup) { problem.addSuppressed(cleanup); }
            }
            throw problem;
        }
    }

    /**
     * Reads a required explicit controller input without consulting global environment state.
     * @param environment captured complete environment
     * @param name required variable
     * @return nonempty variable value
     * @throws IOException if the variable is absent or empty
     */
    private static String required(Map<String, String> environment, String name) throws IOException
    {
        String value = environment.get(name);
        if (value == null || value.isEmpty()) throw new IOException("Cargo wrapper requires approved " + name);
        return value;
    }

    /**
     * Parses one positive native identity value.
     * @param value decimal native identity
     * @return positive long value
     * @throws IOException if the value is not a positive decimal long
     */
    private static long positive(String value) throws IOException
    {
        try
        {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException("nonpositive identity");
            return parsed;
        }
        catch (NumberFormatException failure) { throw new IOException("Cargo controller identity must be a positive long", failure); }
    }

    /**
     * Reads native FILETIME identities and exits with native DWORD status; it grants no source duplication authority.
     */
    static final class Identity implements AutoCloseable
    {
        /** Native binding lifetime, retained through wrapper execution and cleanup. */
        private final Arena arena = Arena.ofConfined();
        /** Required Windows operations resolved through the Pouch linker. */
        private final Map<String, MethodHandle> functions = new HashMap<>();
        /** Same-call native error record supplied by the Windows linker. */
        private final MemoryLayout callState;
        /** Offset of captured GetLastError in that record. */
        private final long errorOffset;
        /** Last native identity error, captured before returning through Java. */
        private int lastError;

        /**
         * Resolves native Windows identity operations without retaining arbitrary process handles.
         * @param scope borrowed invocation services
         */
        // Exact FILETIME identity and DWORD process exit require the Windows API, unavailable through ProcessHandle.
        @SuppressWarnings("restricted")
        Identity(WindowsCargoScope scope)
        {
            if (!System.getProperty("os.name", "").startsWith("Windows") || ValueLayout.ADDRESS.byteSize() != 8)
                throw new UnsupportedOperationException("Cargo wrapper entry requires native 64-bit Windows");
            this.callState = Linker.Option.captureStateLayout();
            this.errorOffset = callState.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
            try
            {
                SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
                bind(scope, kernel, "OpenProcess", ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
                bind(scope, kernel, "CloseHandle", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
                bind(scope, kernel, "GetProcessTimes", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                bind(scope, kernel, "WaitForSingleObject", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
                bind(scope, kernel, "GetCurrentProcess", ValueLayout.ADDRESS);
                bind(scope, kernel, "TerminateProcess", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
            }
            catch (RuntimeException | Error failure) { arena.close(); throw failure; }
        }

        /**
         * Resolves a fixed native ABI function.
         * @param scope borrowed linker provider
         * @param kernel owning Windows library
         * @param name native symbol
         * @param result return-value layout
         * @param arguments argument layouts
         */
        // Query-only native identity handles never grant compiler source-stream duplication authority.
        @SuppressWarnings("restricted")
        private void bind(WindowsCargoScope scope, SymbolLookup kernel, String name, MemoryLayout result,
            MemoryLayout... arguments)
        {
            functions.put(name, scope.tooling().getNativeLinker().downcallHandle(kernel.find(name).orElseThrow(),
                FunctionDescriptor.of(result, arguments), Linker.Option.captureCallState("GetLastError")));
        }

        /**
         * Invokes one bound native identity operation.
         * @param name bound symbol
         * @param arguments native arguments
         * @return native result, or null for void operations
         */
        private Object call(String name, Object... arguments)
        {
            if (name.equals("GetLastError")) return lastError;
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment state = temporary.allocate(callState);
                List<Object> invocation = new java.util.ArrayList<>(arguments.length + 1);
                invocation.add(state);
                invocation.addAll(java.util.Arrays.asList(arguments));
                Object result = functions.get(name).invokeWithArguments(invocation);
                lastError = state.get(ValueLayout.JAVA_INT, errorOffset);
                return result;
            }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Windows identity call failed: " + name, failure); }
        }

        /**
         * Reads creation FILETIME from one live process using query-only access and closes that query handle.
         * This observation authenticates the expected pipe server; it is never offered for stream duplication.
         * @param pid positive native PID
         * @return exact creation FILETIME bits
         * @throws IOException if the PID is invalid, process exited, or native observation fails
         */
        long birth(long pid) throws IOException
        {
            if (pid <= 0 || pid > 0xffffffffL) throw new IOException("Windows PID is outside the positive DWORD range");
            MemorySegment process = (MemorySegment) call("OpenProcess", 0x101000, 0, (int) pid);
            if (process.address() == 0) throw new IOException("OpenProcess(identity) failed: " + call("GetLastError"));
            IOException observationFailure = null;
            try (Arena temporary = Arena.ofConfined())
            {
                MemorySegment times = temporary.allocate(32, 8);
                if ((int) call("GetProcessTimes", process, times, times.asSlice(8), times.asSlice(16), times.asSlice(24)) == 0)
                    throw new IOException("GetProcessTimes(identity) failed: " + call("GetLastError"));
                if ((int) call("WaitForSingleObject", process, 0) != 258) throw new IOException("Windows identity source is no longer live");
                return times.get(ValueLayout.JAVA_LONG, 0);
            }
            catch (IOException problem)
            {
                observationFailure = problem;
                throw problem;
            }
            finally
            {
                if ((int) call("CloseHandle", process) == 0)
                {
                    IOException cleanup = new IOException("CloseHandle(identity) failed: " + call("GetLastError"));
                    if (observationFailure == null) throw cleanup;
                    observationFailure.addSuppressed(cleanup);
                }
            }
        }

        /**
         * Terminates the native wrapper process using exact compiler DWORD result bits.
         * Call only after every wrapper-owned stream and control resource has closed.
         * @param status unsigned DWORD compiler result
         */
        void exit(long status)
        {
            new WindowsCargoMessages.Final(status);
            // ExitProcess ends the other threads first and then runs every DLL's detach code, which deadlocks when one
            // of those threads held a lock the detach code takes; a wrapper was left with one thread in that state.
            // TerminateProcess runs no detach code, and nothing here needs it: every wrapper stream is already closed.
            // https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-exitprocess
            MemorySegment self = (MemorySegment) call("GetCurrentProcess");
            if ((int) call("TerminateProcess", self, (int) status) == 0)
                throw new IllegalStateException("TerminateProcess(self) failed: " + call("GetLastError"));
            throw new IllegalStateException("TerminateProcess(self) returned unexpectedly");
        }

        @Override public void close() { arena.close(); }
    }
}
