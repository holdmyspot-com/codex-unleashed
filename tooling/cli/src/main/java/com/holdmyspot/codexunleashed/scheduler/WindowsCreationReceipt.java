package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Borrows an authenticated descendant's retained original process authority from its Job owner.
 * The Job owner retains this authority and imported streams until whole-Job cleanup.
 */
public final class WindowsCreationReceipt
{
    /** Original creation authority retained by the exact Job owner. */
    private final WindowsProcess.CreatedSource source;
    /** Native observer facts joined to this exact authority before source acknowledgement. */
    private final WindowsAllocationHooks.Receipt observation;
    /** Native image path captured before this descendant can resume or exit. */
    private final Path executable;
    /** Actual child process state copied by its authenticated observer before gating readiness. */
    private NativeState state = null;

    /**
     * Binds native creation facts to successfully retained original authority.
     * @param source original controller-local authority owned by the Job
     * @param observation authenticated native observer facts
     * @throws IOException if the native image cannot be queried through original authority
     */
    WindowsCreationReceipt(WindowsProcess.CreatedSource source, WindowsAllocationHooks.Receipt observation) throws IOException
    {
        this.source = Objects.requireNonNull(source, "source");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.executable = source.executable();
    }

    /**
     * Returns the descendant process identifier.
     * @return unsigned DWORD process identifier
     */
    long pid() { return observation.childPid(); }

    /**
     * Returns the descendant's original creation time.
     * @return raw unsigned FILETIME bits
     */
    public long birth() { return observation.childBirth(); }

    /**
     * Returns the creating process identifier.
     * @return unsigned DWORD creator identifier
     */
    public long parentPid() { return observation.parentPid(); }

    /**
     * Returns the creating process's original creation time.
     * @return raw unsigned FILETIME bits
     */
    long parentBirth() { return observation.parentBirth(); }

    /**
     * Returns launch facts copied by the creating process before native creation.
     * @return immutable authentic launch facts
     * @throws IOException if the observer did not capture complete launch facts
     */
    public Launch launch() throws IOException
    {
        if (observation.launch() == null) throw new IOException("Native observer has no complete launch facts");
        return observation.launch();
    }

    /**
     * Seals actual child state obtained inside this exact original process before resume.
     * @param state native child command line, directory and environment
     * @throws IOException if a previous child-state receipt was already sealed
     */
    void seal(NativeState state) throws IOException
    {
        Objects.requireNonNull(state, "state");
        if (this.state != null) throw new IOException("Native child state was already sealed");
        source.requireLive();
        this.state = state;
    }

    /**
     * Returns actual child state copied inside the authenticated child before its application thread resumes.
     * Registration uses this state to resolve inherited-null creation arguments without a creator mutation race.
     * @return immutable actual native child state
     * @throws IOException if child observation has not sealed its state
     */
    public NativeState state() throws IOException
    {
        if (state == null) throw new IOException("Native child state has not been sealed before resume");
        return state;
    }

    /**
     * Copies this JVM's native state, preserving hidden drive-directory environment entries.
     * Native storage belongs to this invocation and no foreign process handle is acquired.
     * @param scope invocation scope supplying the native linker
     * @return exact native command line, working directory and ordered environment entries
     * @throws IOException if native capture or snapshot release fails or a bounded value is too large
     * @throws UnsupportedOperationException if native 64-bit Windows is unavailable
     */
    public static NativeState currentState(ToolingScope scope) throws IOException
    {
        try (WindowsProcess.Kernel kernel = new WindowsProcess.Kernel(scope))
        {
            return currentState(kernel);
        }
    }

    /**
     * Captures current state through the same scoped Windows bindings used by process ownership.
     * @param kernel invocation-owned native bindings; borrowed for this capture
     * @return copied current native state
     * @throws IOException if capture, native snapshot release or a size bound fails
     */
    public static NativeState currentState(WindowsProcess.Kernel kernel) throws IOException
    {
        String commandLine = nativeText(kernel.pointer("GetCommandLineW"), 32768);
        MemorySegment directory = kernel.arena.allocate(32768L * Character.BYTES, Character.BYTES);
        int length = kernel.integer("GetCurrentDirectoryW", 32768, directory);
        if (length <= 0 || length >= 32768) throw new IOException("GetCurrentDirectoryW failed or exceeded its bound");
        String currentDirectory = nativeText(directory, length + 1);
        MemorySegment environment = kernel.pointer("GetEnvironmentStringsW");
        if (environment.address() == 0) throw new IOException("GetEnvironmentStringsW returned NULL");
        Throwable failure = null;
        try
        {
            MemorySegment block = environment.reinterpret(262144L * Character.BYTES);
            List<String> entries = new ArrayList<>();
            StringBuilder entry = new StringBuilder();
            for (int index = 0; index < 262144; ++index)
            {
                char character = block.getAtIndex(ValueLayout.JAVA_CHAR, index);
                if (character != '\0') entry.append(character);
                else if (entry.isEmpty()) return new NativeState(commandLine, currentDirectory, entries);
                else { entries.add(entry.toString()); entry.setLength(0); }
            }
            throw new IOException("Native environment block exceeds its bound");
        }
        catch (IOException | RuntimeException | Error caught)
        {
            failure = caught;
            throw caught;
        }
        finally
        {
            try { kernel.require("FreeEnvironmentStringsW", kernel.call("FreeEnvironmentStringsW", environment)); }
            catch (IOException | RuntimeException | Error cleanup)
            {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    /**
     * Copies bounded native UTF-16 text before native storage can change or be released.
     * @param pointer native UTF-16 string
     * @param bound maximum number of UTF-16 units including its terminator
     * @return copied string
     * @throws IOException if the pointer is null or its terminator exceeds the bound
     */
    private static String nativeText(MemorySegment pointer, int bound) throws IOException
    {
        if (pointer.address() == 0) throw new IOException("Native string is NULL");
        MemorySegment text = pointer.reinterpret((long) bound * Character.BYTES);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < bound; ++index)
        {
            char character = text.getAtIndex(ValueLayout.JAVA_CHAR, index);
            if (character == '\0') return result.toString();
            result.append(character);
        }
        throw new IOException("Native string exceeds its bound");
    }

    /**
     * Holds actual native process state copied inside the held child image.
     * @param commandLine exact native UTF-16 command line
     * @param directory actual native working directory
     * @param environment complete actual native environment entries
     */
    public record NativeState(String commandLine, String directory, List<String> environment)
    {
        /**
         * Copies actual child state for registration after its native observer becomes ready.
         * @param commandLine exact child command line
         * @param directory actual child directory
         * @param environment complete actual child environment
         * @throws NullPointerException if a value or environment entry is null
         * @throws IllegalArgumentException if text contains NUL or the directory is empty
         */
        public NativeState
        {
            Objects.requireNonNull(commandLine, "commandLine");
            Launch copied = new Launch(null, commandLine, directory, environment, false, false, false, 0, 0, 0);
            environment = copied.environment();
        }
    }

    /**
     * Rechecks original process liveness, birth and exact Job membership.
     * @throws IOException if any original identity observation differs
     */
    void requireLive() throws IOException { source.requireLive(); }

    /**
     * Returns the native executable path captured through original authority before resume.
     * @return absolute native image path
     */
    public Path executable() { return executable; }

    /**
     * Imports authenticated stable redirected endpoints from this exact process.
     * @param input approved process-local standard input kept stable until acknowledgement
     * @param output approved process-local standard output kept stable until acknowledgement
     * @param error approved process-local standard error kept stable until acknowledgement
     * @return independently owned noninheritable controller copies retained across retries
     * @throws IOException if identity, duplication or cleanup cannot be established
     */
    WindowsProcess.StandardStreams importStandardStreams(MemorySegment input, MemorySegment output,
        MemorySegment error) throws IOException
    {
        return source.importStandardStreams(input, output, error);
    }

    /**
     * Holds native UTF-16 launch strings without applying the controller's operating-system path parser.
     * Environment entries preserve drive-directory names and exact native order.
     * @param application native application argument, or null when omitted
     * @param commandLine native command line, or null when omitted
     * @param directory effective native working directory copied in the creator
     * @param environment complete native environment entries copied in the creator
     * @param inheritedEnvironment indicates whether creation inherited its environment
     * @param inheritedDirectory indicates whether creation inherited its directory
     * @param standardHandles indicates whether STARTF_USESTDHANDLES supplied the three handle values
     * @param input raw original child standard-input handle bits
     * @param output raw original child standard-output handle bits
     * @param error raw original child standard-error handle bits
     */
    public record Launch(String application, String commandLine, String directory, List<String> environment,
        boolean inheritedEnvironment, boolean inheritedDirectory, boolean standardHandles,
        long input, long output, long error)
    {
        /**
         * Copies complete native launch facts before registration can consume them.
         * @param application native application argument, or null
         * @param commandLine native command line, or null
         * @param directory effective native working directory
         * @param environment complete native environment entries
         * @param inheritedEnvironment indicates whether the environment was inherited
         * @param inheritedDirectory indicates whether the directory was inherited
         * @param standardHandles indicates whether explicit standard handles were supplied
         * @param input raw standard-input handle bits
         * @param output raw standard-output handle bits
         * @param error raw standard-error handle bits
         * @throws NullPointerException if directory, environment or an environment entry is null
         * @throws IllegalArgumentException if native text contains NUL or the directory is empty
         */
        public Launch
        {
            Objects.requireNonNull(directory, "directory");
            environment = List.copyOf(environment);
            boolean invalidText = directory.isEmpty() || directory.indexOf('\0') >= 0 ||
                (application != null && application.indexOf('\0') >= 0) ||
                (commandLine != null && commandLine.indexOf('\0') >= 0) ||
                environment.stream().anyMatch(entry -> entry.indexOf('\0') >= 0);
            if (invalidText) throw new IllegalArgumentException("Native launch strings contain NUL or an empty directory");
        }
    }
}
