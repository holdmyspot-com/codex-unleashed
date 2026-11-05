package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Authenticates claimed compiler launch facts against retained original-process authority.
 * This class neither opens a process by PID nor submits to the queue. A source must originate in the
 * invocation's native creation observer, retaining its process handle and approved launcher ancestry.
 */
public final class WindowsCargoRegistration
{
    /**
     * Prevents construction of stateless authentication operations.
     */
    private WindowsCargoRegistration() {}

    /**
     * Verifies a request before its registration can acquire streams or change queue state.
     * @param request untrusted wrapper registration, including unknown demand
     * @param source original-process native observations retained by the invocation
     * @param configuration trusted Cargo and compiler boundaries
     * @return the exact authenticated immutable request
     * @throws IOException if native identity, liveness or launch facts do not agree
     * @throws NullPointerException if an input is null
     */
    public static CargoCompilerRequest authenticate(CargoCompilerRequest request, Source source,
        Configuration configuration) throws IOException
    {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(configuration, "configuration");
        if (request.peakBytes() != null)
            throw new IOException("Wrapper registration cannot supply a compiler memory estimate");
        source.requireLive();
        var wrapper = request.wrapper();
        if (wrapper.pid() != source.pid() || wrapper.birth() != source.birth() ||
            wrapper.parentPid() != source.parentPid() || wrapper.parentBirth() != source.parentBirth() ||
            configuration.cargoPid() != source.cargoPid() || configuration.cargoBirth() != source.cargoBirth())
            throw new IOException("Wrapper identity does not match its retained native Cargo creation chain");
        if (!request.arguments().equals(source.compilerArguments()) ||
            !request.arguments().getFirst().equals(configuration.rustc().toString()) ||
            !request.directory().equals(source.directory()) || !request.environment().equals(source.environment()))
            throw new IOException("Compiler launch differs from independently observed native creation facts");
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String name : request.environment().keySet())
            if (!names.add(name)) throw new IOException("Compiler environment contains conflicting Windows names");
        for (var required : configuration.requiredEnvironment().entrySet())
            if (!required.getValue().equals(request.environment().get(required.getKey())))
                throw new IOException("Compiler environment differs from caller-owned requirements: " + required.getKey());
        source.requireLive();
        return request;
    }

    /**
     * Derives launch authentication only from original retained creation authorities and sealed child state.
     * The peer and its consecutive ancestors must be the pinned packaged launcher, which jpackage may restart; any
     * higher ancestor, such as a build script, must be a retained process of the Cargo Job.
     * @param scope borrowed linker provider
     * @param cargo retained original Cargo Job
     * @param observer creation/event join owner that sealed actual child state before resume
     * @param peerPid kernel-observed pipe client process ID
     * @param wrapper pinned packaged executable
     * @param imageHashes permitted executable identities, keyed by absolute native paths
     * @return independently observed native source
     * @throws IOException if original identity, executable, ancestry, launch state or image hash differs
     */
    static Source nativeSource(ToolingScope scope, WindowsProcess cargo, WindowsCreationObserver observer,
        long peerPid, Path wrapper, Map<Path, String> imageHashes) throws IOException
    {
        WindowsCreationReceipt peer = observer.receipt(peerPid).orElseThrow(
            () -> new IOException("Pipe peer has no retained original creation receipt"));
        Link start = link(peer);
        List<Link> wrappers = wrapperChain(start, pid -> cargo.creationReceipt(pid).map(WindowsCargoRegistration::link),
            cargo.pid(), cargo.birth(), wrapper);
        List<String> arguments = null;
        WindowsCreationReceipt cursor = peer;
        for (int index = 0; index < wrappers.size(); ++index)
        {
            if (index > 0)
            {
                long pid = wrappers.get(index).pid();
                cursor = cargo.creationReceipt(pid).orElseThrow(
                    () -> new IOException("Packaged launcher has no retained original creator"));
            }
            String expectedHash = imageHashes.get(cursor.executable());
            if (expectedHash == null || !expectedHash.equals(hash(cursor.executable())))
                throw new IOException("Packaged wrapper executable differs from its immutable image identity");
            List<String> command = parseCommandLine(scope, cursor.state().commandLine());
            if (command.size() < 2) throw new IOException("Wrapper command line has no compiler executable");
            List<String> compiler = List.copyOf(command.subList(1, command.size()));
            if (arguments == null) arguments = compiler;
            else if (!arguments.equals(compiler)) throw new IOException("Restarted launcher changed logical compiler arguments");
        }
        var state = peer.state();
        var environment = nativeEnvironment(state.environment());
        peer.requireLive();
        return new NativeSource(peer, cargo.pid(), cargo.birth(), arguments,
            Path.of(state.directory()).toAbsolutePath().normalize(), environment);
    }

    /**
     * Retained creation facts of one process in the original Cargo Job.
     * @param pid process identifier
     * @param birth process creation time
     * @param parentPid creator process identifier
     * @param parentBirth creator creation time
     * @param executable absolute native image path
     */
    public record Link(long pid, long birth, long parentPid, long parentBirth, Path executable)
    {
        /**
         * Validates the link.
         * @throws NullPointerException if executable is null
         */
        public Link { Objects.requireNonNull(executable, "executable"); }
    }

    /**
     * Copies the ancestry facts of a retained creation receipt.
     * @param receipt retained original creation authority
     * @return immutable link
     */
    private static Link link(WindowsCreationReceipt receipt)
    {
        return new Link(receipt.pid(), receipt.birth(), receipt.parentPid(), receipt.parentBirth(), receipt.executable());
    }

    /**
     * Walks a registering wrapper's retained ancestry to the original Cargo process.
     * The peer and each consecutive ancestor up to the first other image must be the pinned wrapper, because jpackage
     * may restart it. Any remaining ancestors, such as a build script that runs the compiler, must each be a process the
     * Cargo Job created, so the chain reaches Cargo only through retained original creation authority.
     * @param peer retained creation facts of the registering process
     * @param creator retained creation facts for a process created inside the Cargo Job, or empty
     * @param cargoPid original Cargo process identifier
     * @param cargoBirth original Cargo creation time
     * @param wrapper pinned packaged wrapper executable
     * @return the peer followed by its consecutive wrapper ancestors
     * @throws IOException if the peer is not the wrapper, a wrapper reappears above another image, a creator is not
     *     retained, a birth differs, or the ancestry contains a cycle
     */
    public static List<Link> wrapperChain(Link peer, java.util.function.LongFunction<java.util.Optional<Link>> creator,
        long cargoPid, long cargoBirth, Path wrapper) throws IOException
    {
        List<Link> wrappers = new ArrayList<>();
        var seen = new HashSet<Long>();
        Link cursor = peer;
        while (true)
        {
            if (!seen.add(cursor.pid())) throw new IOException("Packaged wrapper ancestry contains a cycle");
            boolean isWrapper = cursor.executable().equals(wrapper);
            if (isWrapper && wrappers.size() == seen.size() - 1) wrappers.add(cursor);
            else if (wrappers.isEmpty() || isWrapper)
                throw new IOException("Registration peer delegates through an unexpected executable: " + cursor.executable());
            if (cursor.parentPid() == cargoPid)
            {
                if (cursor.parentBirth() != cargoBirth) throw new IOException("Original Cargo ancestor birth differs");
                return List.copyOf(wrappers);
            }
            Link parent = creator.apply(cursor.parentPid()).orElseThrow(
                () -> new IOException("Wrapper ancestor has no retained original creator"));
            if (parent.birth() != cursor.parentBirth()) throw new IOException("Retained wrapper creator birth differs");
            cursor = parent;
        }
    }

    /**
     * Reads the exact byte identity of a permitted executable through the platform file API.
     * @param image pinned executable path
     * @return lowercase SHA256 digest
     * @throws IOException if file bytes cannot be read
     */
    private static String hash(Path image) throws IOException
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("Required JDK SHA-256 is unavailable", failure); }
    }

    /**
     * Converts native environment entries without discarding drive-directory names or empty values.
     * @param entries complete UTF-16 environment entries captured in the held child
     * @return immutable environment preserving exact native key spelling and values
     * @throws IOException if an entry is malformed or Windows names collide
     */
    static Map<String, String> nativeEnvironment(List<String> entries) throws IOException
    {
        var result = new LinkedHashMap<String, String>();
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String entry : entries)
        {
            int separator = entry.indexOf('=', 1);
            if (separator <= 0) throw new IOException("Native environment entry has no name/value separator");
            String name = entry.substring(0, separator);
            if (!names.add(name)) throw new IOException("Native environment contains conflicting Windows names");
            result.put(name, entry.substring(separator + 1));
        }
        return Map.copyOf(result);
    }

    /**
     * Uses the same Windows parser as the pinned jpackage executable for native argument interpretation.
     * The result is copied before LocalFree; no foreign allocation escapes on success.
     * @param scope borrowed native linker
     * @param text actual native command line
     * @return complete argv including the executable
     * @throws IOException if parsing, result bounds or native allocation release fails
     */
    // The platform parser owns the argv allocation; copied strings outlive its single LocalFree operation.
    @SuppressWarnings("restricted")
    static List<String> parseCommandLine(ToolingScope scope, String text) throws IOException
    {
        if (text == null) throw new IOException("Native wrapper command line is absent");
        try (Arena arena = Arena.ofConfined())
        {
            var shell = SymbolLookup.libraryLookup("shell32.dll", arena);
            var kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
            var parse = scope.getNativeLinker().downcallHandle(shell.find("CommandLineToArgvW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var free = scope.getNativeLinker().downcallHandle(kernel.find("LocalFree").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment values = (MemorySegment) parse.invokeExact(arena.allocateFrom(text, StandardCharsets.UTF_16LE), count);
            if (values.address() == 0) throw new IOException("CommandLineToArgvW failed");
            try
            {
                int length = count.get(ValueLayout.JAVA_INT, 0);
                if (length < 1 || length > 65536) throw new IOException("Native argument count exceeds the protocol bound");
                MemorySegment array = values.reinterpret(Math.multiplyExact((long) length, ValueLayout.ADDRESS.byteSize()));
                var result = new ArrayList<String>(length);
                for (int index = 0; index < length; index++)
                    result.add(NativeStrings.utf16(array.getAtIndex(ValueLayout.ADDRESS, index),
                        WindowsCargoTransport.MAX_FRAME_BYTES));
                return List.copyOf(result);
            }
            finally
            {
                MemorySegment unresolved = (MemorySegment) free.invokeExact(values);
                if (unresolved.address() != 0) throw new IOException("LocalFree failed for the native argument allocation");
            }
        }
        catch (IOException | RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IOException("Native command-line interpretation failed", failure); }
    }

    /**
     * Borrows original native authority while exposing immutable independently observed compiler facts.
     * @param receipt registering JVM's retained original process authority
     * @param cargoPid original Cargo ancestor process ID
     * @param cargoBirth original Cargo ancestor creation FILETIME
     * @param compilerArguments independently parsed logical compiler argv
     * @param directory actual held-child working directory
     * @param environment actual held-child complete environment
     */
    private record NativeSource(WindowsCreationReceipt receipt, long cargoPid, long cargoBirth,
        List<String> compilerArguments, Path directory, Map<String, String> environment) implements Source
    {
        @Override public long pid() { return receipt.pid(); }
        @Override public long birth() { return receipt.birth(); }
        @Override public long parentPid() { return receipt.parentPid(); }
        @Override public long parentBirth() { return receipt.parentBirth(); }
        @Override public void requireLive() throws IOException { receipt.requireLive(); }
    }

    /**
     * Trusted native admission boundary, immutable through all request retries.
     * @param rustc absolute normalized compiler executable selected by caller
     * @param cargoPid retained original Cargo process ID
     * @param cargoBirth retained Cargo creation FILETIME
     * @param requiredEnvironment complete required caller-owned environment entries
     */
    public record Configuration(Path rustc, long cargoPid, long cargoBirth, Map<String, String> requiredEnvironment)
    {
        /**
         * Checks caller boundaries without opening files or acquiring native resources.
         * @param rustc absolute normalized compiler executable
         * @param cargoPid original Cargo process ID
         * @param cargoBirth original Cargo creation FILETIME
         * @param requiredEnvironment required environment entries copied for all retries
         * @throws NullPointerException if an input is null
         * @throws IllegalArgumentException if a native identity or executable boundary is invalid
         */
        public Configuration
        {
            Objects.requireNonNull(rustc, "rustc");
            requiredEnvironment = Map.copyOf(requiredEnvironment);
            if (!rustc.isAbsolute() || !rustc.equals(rustc.normalize()) || cargoPid <= 0 ||
                cargoPid > 0xffff_ffffL || cargoBirth <= 0)
                throw new IllegalArgumentException("Cargo admission requires an absolute compiler and retained identity");
        }
    }

    /**
     * Retained native observations, never reconstructed from the registration frame.
     * Implementations authenticate the permitted packaged-launcher-to-JVM ancestry and executable images
     * before exposing this boundary; PID values alone cannot construct native authority.
     */
    public interface Source
    {
        /**
         * Supplies the registering JVM's native process ID.
         * @return unsigned nonzero process ID
         */
        long pid();
        /**
         * Supplies the registering JVM's creation FILETIME from its retained original handle.
         * @return original creation FILETIME
         */
        long birth();
        /**
         * Supplies the actual creator process ID.
         * @return native parent process ID
         */
        long parentPid();
        /**
         * Supplies the actual creator creation FILETIME.
         * @return native parent creation FILETIME
         */
        long parentBirth();
        /**
         * Supplies the original Cargo ancestor whose retained receipt anchors this chain.
         * @return original Cargo process ID
         */
        long cargoPid();
        /**
         * Supplies the original Cargo ancestor's creation FILETIME.
         * @return original Cargo creation FILETIME
         */
        long cargoBirth();
        /**
         * Supplies the logical compiler argv authenticated from the original launcher creation.
         * @return complete compiler argv, including executable and empty arguments
         */
        List<String> compilerArguments();
        /**
         * Supplies actual inherited current directory from native creation observations.
         * @return absolute normalized directory
         */
        Path directory();
        /**
         * Supplies complete actual compiler environment after approved packaging transformations.
         * @return immutable environment entries preserving values and empty strings
         */
        Map<String, String> environment();
        /**
         * Verifies the retained original process handle is live inside this exact Cargo Job.
         * @throws IOException if native authority is stale, exited, uncertain or outside containment
         */
        void requireLive() throws IOException;
    }
}
