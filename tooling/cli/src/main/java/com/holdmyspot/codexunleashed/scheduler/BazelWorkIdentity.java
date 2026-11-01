package com.holdmyspot.codexunleashed.scheduler;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Identifies reusable Bazel work from its complete immutable ready-input tree and exact launch inputs.
 * The caller owns and freezes that tree and all external toolchain files throughout identification and execution.
 * This content identity predicts memory reuse; it never authenticates a logical queue entry or grants launch authority.
 * Relative tree names and working directories are independent of the snapshot's storage location. Argument and
 * environment strings are always hashed verbatim, so their meaningful path and flag distinctions remain intact.
 * Linux executable permission bits and symlink targets are included; special files and escaping input links fail closed.
 */
final class BazelWorkIdentity
{
    /** Versioned domain separating Bazel ready-tree keys from other work and future identity schemas. */
    private static final String DOMAIN = "bazel-ready-input-v3";

    /**
     * Prevents construction of this stateless content identity operation.
     */
    private BazelWorkIdentity() {}

    /**
     * Identifies an immutable input tree whose action-visible root is its storage root.
     * @param immutableReadyInputRoot absolute normalized complete immutable ready-input tree
     * @param argv original ordered command elements, including nonempty executable at index zero
     * @param environment original complete action environment, with no omitted or rewritten entries
     * @param actionWorkingDirectory absolute normalized action working directory inside the input root
     * @param backendToolchainPlatformIdentity nonblank trusted backend, toolchain, platform and release identity
     * @return lowercase SHA-256 content identity
     * @throws NullPointerException if any required input or collection member is null
     * @throws IllegalArgumentException if paths are relative or unnormalized, argv is empty or identity is blank
     * @throws IOException if the complete stable tree, executable or interpreter cannot be identified
     */
    static String identify(Path immutableReadyInputRoot, List<String> argv, Map<String, String> environment,
        Path actionWorkingDirectory, String backendToolchainPlatformIdentity) throws IOException
    {
        return identify(immutableReadyInputRoot, immutableReadyInputRoot, argv, environment, actionWorkingDirectory,
            backendToolchainPlatformIdentity);
    }

    /**
     * Identifies an immutable snapshot with an explicit original action-visible root mapping.
     * Filesystem lookup maps a path below {@code actionVisibleInputRoot} to the same relative path below
     * {@code immutableReadyInputRoot}; it never rewrites argv or environment bytes. The working directory is hashed
     * relative to the visible root. External executable/interpreter paths retain their exact absolute names and content.
     * Every snapshot entry is included, including parameter files whether or not an argument names them.
     * Symlinks are hashed as links and must resolve lexically within their respective authenticated input root;
     * executable resolution additionally rejects cycles and follows only that root or explicitly named external tools.
     * Mutation checks detect ordinary changes during reads; exclusive immutable ownership remains the caller's duty.
     * @param immutableReadyInputRoot absolute normalized independent immutable snapshot root
     * @param actionVisibleInputRoot absolute normalized worker root visible to the original action
     * @param argv exact ordered command elements, including executable at index zero
     * @param environment exact complete action environment
     * @param actionWorkingDirectory absolute normalized original action working directory below the visible root
     * @param backendToolchainPlatformIdentity nonblank trusted backend, toolchain, platform and release identity
     * @return lowercase SHA-256 content identity
     * @throws NullPointerException if any required input or collection member is null
     * @throws IllegalArgumentException if paths are relative or unnormalized, argv is empty or identity is blank
     * @throws IOException if an input escapes its root, an entry is unsupported, files change or a tool is unavailable
     */
    static String identify(Path immutableReadyInputRoot, Path actionVisibleInputRoot, List<String> argv,
        Map<String, String> environment, Path actionWorkingDirectory, String backendToolchainPlatformIdentity)
        throws IOException
    {
        return identify(immutableReadyInputRoot, actionVisibleInputRoot, argv, environment, actionWorkingDirectory,
            backendToolchainPlatformIdentity, null);
    }

    /**
     * Identifies complete content with optional explicitly trusted immutable inode reuse.
     * @param immutableReadyInputRoot immutable snapshot root
     * @param actionVisibleInputRoot original worker root
     * @param argv original arguments
     * @param environment original environment
     * @param actionWorkingDirectory original working directory
     * @param backendToolchainPlatformIdentity trusted backend identity
     * @param cache invocation-owned immutable inode content cache, or null to read every byte
     * @return complete SHA-256 identity
     * @throws IOException if complete stable content cannot be identified
     */
    private static String identify(Path immutableReadyInputRoot, Path actionVisibleInputRoot, List<String> argv,
        Map<String, String> environment, Path actionWorkingDirectory, String backendToolchainPlatformIdentity,
        Map<FileIdentity, byte[]> cache) throws IOException
    {
        Path snapshot = absolute(immutableReadyInputRoot);
        Path visible = absolute(actionVisibleInputRoot);
        Path working = absolute(actionWorkingDirectory);
        List<String> arguments = List.copyOf(argv);
        Map<String, String> variables = new TreeMap<>(Map.copyOf(environment));
        Objects.requireNonNull(backendToolchainPlatformIdentity, "backendToolchainPlatformIdentity");
        if (arguments.isEmpty() || arguments.getFirst().isEmpty() || backendToolchainPlatformIdentity.isBlank())
            throw new IllegalArgumentException("Work requires a nonempty executable and nonblank backend identity");
        if (!working.startsWith(visible)) throw new IOException("Action working directory escapes its visible input root");
        if (!Files.isDirectory(snapshot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Immutable ready-input root is not a directory: " + snapshot);
        MessageDigest digest = sha256();
        try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)))
        {
            text(output, DOMAIN);
            text(output, backendToolchainPlatformIdentity);
            text(output, relative(visible, working));
            output.writeInt(arguments.size());
            for (String argument : arguments) text(output, argument);
            output.writeInt(variables.size());
            for (Map.Entry<String, String> variable : variables.entrySet())
            {
                text(output, variable.getKey());
                text(output, variable.getValue());
            }
            writeTree(output, snapshot, visible, cache);
            Path executable = executable(snapshot, visible, arguments.getFirst(), working, variables);
            writeExecutable(output, snapshot, visible, working, variables, executable, new HashSet<>(), cache);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Writes the complete sorted no-follow tree with length-delimited entry names and contents.
     * @param output framed digest sink
     * @param snapshot immutable storage root
     * @param visible original input root used to interpret absolute symlink targets
     * @param cache trusted immutable inode cache, or null
     * @throws IOException if complete stable supported content cannot be observed
     */
    private static void writeTree(DataOutputStream output, Path snapshot, Path visible, Map<FileIdentity, byte[]> cache) throws IOException
    {
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(snapshot))
        {
            entries = paths.sorted(Comparator.comparing(path -> relative(snapshot, path))).toList();
        }
        output.writeInt(entries.size());
        for (Path entry : entries)
        {
            text(output, relative(snapshot, entry));
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink())
            {
                output.writeByte(1);
                Path target = Files.readSymbolicLink(entry);
                Path visibleEntry = visible.resolve(snapshot.relativize(entry));
                Path resolved = target.isAbsolute() ? target.normalize() : visibleEntry.getParent().resolve(target).normalize();
                if (!resolved.startsWith(visible)) throw new IOException("Input symlink escapes its visible root: " + entry);
                text(output, target.toString());
            }
            else if (attributes.isDirectory()) output.writeByte(2);
            else if (attributes.isRegularFile())
            {
                output.writeByte(3);
                writeFile(output, entry, cache);
            }
            else throw new IOException("Input entry is not a directory, regular file or symlink: " + entry);
        }
    }

    /**
     * Resolves and hashes a generated or external executable and its kernel shebang interpreter chain.
     * External toolchain files must remain immutable under the caller's trusted backend identity.
     * @param output framed digest sink
     * @param snapshot immutable storage root
     * @param visible original action input root
     * @param working original action working directory
     * @param environment complete action environment for PATH-based interpreter resolution
     * @param executable original action-visible executable path
     * @param visited resolved chain entries, used to reject interpreter cycles
     * @param cache trusted immutable inode cache, or null
     * @throws IOException if resolution escapes through an input link, cycles, changes or is unsupported
     */
    private static void writeExecutable(DataOutputStream output, Path snapshot, Path visible, Path working,
        Map<String, String> environment, Path executable, Set<Path> visited, Map<FileIdentity, byte[]> cache) throws IOException
    {
        Path resolved = resolveTool(snapshot, visible, executable);
        if (!visited.add(resolved)) throw new IOException("Executable/interpreter cycle: " + executable);
        text(output, executable.startsWith(visible) ? relative(visible, executable) : executable.toString());
        writeFile(output, resolved, cache);
        byte[] prefix;
        try (InputStream input = Files.newInputStream(resolved)) { prefix = input.readNBytes(256); }
        if (prefix.length < 2 || prefix[0] != '#' || prefix[1] != '!') return;
        int end = 2;
        while (end < prefix.length && prefix[end] != '\n') end++;
        if (end == prefix.length) throw new IOException("Executable shebang is missing a newline within 256 bytes");
        String directive = new String(prefix, 2, end - 2, StandardCharsets.UTF_8).strip();
        int separator = 0;
        while (separator < directive.length() && !Character.isWhitespace(directive.charAt(separator))) separator++;
        String interpreter = directive.substring(0, separator);
        Path interpreterPath = Path.of(interpreter);
        if (!interpreterPath.isAbsolute()) throw new IOException("Executable shebang interpreter must be absolute");
        writeExecutable(output, snapshot, visible, working, environment, interpreterPath, visited, cache);
        if (interpreterPath.getFileName().toString().equals("env"))
        {
            String program = directive.substring(separator).strip();
            if (program.isEmpty() || program.startsWith("-") || program.chars().anyMatch(Character::isWhitespace))
                throw new IOException("env shebang requires one plain program name for reusable identity");
            writeExecutable(output, snapshot, visible, working, environment,
                executable(snapshot, visible, program, working, environment), visited, cache);
        }
    }

    /**
     * Resolves one command through its supplied working directory or explicit PATH, without reading host environment.
     * @param snapshot immutable input storage root
     * @param visible original action-visible root
     * @param command executable argument or shebang program
     * @param working original working directory
     * @param environment supplied environment including PATH for bare names
     * @return absolute original executable location
     * @throws IOException if a bare command has no explicit PATH candidate
     */
    private static Path executable(Path snapshot, Path visible, String command, Path working,
        Map<String, String> environment) throws IOException
    {
        Path path = Path.of(command);
        if (path.isAbsolute()) return path.normalize();
        if (path.getNameCount() > 1 || command.startsWith(".")) return working.resolve(path).normalize();
        String search = environment.get("PATH");
        if (search == null) throw new IOException("Bare executable requires an explicit action PATH: " + command);
        for (String directory : search.split(":", -1))
        {
            Path candidate = working.resolve(directory).resolve(path).normalize();
            try
            {
                if (Files.isRegularFile(resolveTool(snapshot, visible, candidate))) return candidate;
            }
            catch (NoSuchFileException absent) { /* Continue through the explicit PATH. */ }
        }
        throw new IOException("Executable is absent from the explicit action PATH: " + command);
    }

    /**
     * Resolves input symlinks through their visible-root mapping and external tool symlinks through the host filesystem.
     * @param snapshot immutable tree root
     * @param visible original input root
     * @param executable original absolute tool path
     * @return corresponding real file path
     * @throws IOException if a link escapes the snapshot or resolution is cyclic/unavailable
     */
    private static Path resolveTool(Path snapshot, Path visible, Path executable) throws IOException
    {
        if (!executable.startsWith(visible)) return executable.toRealPath();
        Path current = executable;
        Set<Path> followed = new HashSet<>();
        while (true)
        {
            Path component = visible;
            boolean foundLink = false;
            for (Path name : visible.relativize(current))
            {
                component = component.resolve(name);
                Path stored = snapshot.resolve(visible.relativize(component));
                if (Files.isSymbolicLink(stored))
                {
                    if (!followed.add(component)) throw new IOException("Executable input symlink cycle: " + component);
                    Path target = Files.readSymbolicLink(stored);
                    Path resolved = target.isAbsolute() ? target : component.getParent().resolve(target);
                    current = resolved.resolve(component.relativize(current)).normalize();
                    if (!current.startsWith(visible)) throw new IOException("Executable input symlink escapes immutable snapshot");
                    foundLink = true;
                    break;
                }
            }
            if (!foundLink) return snapshot.resolve(visible.relativize(current)).toRealPath();
        }
    }

    /**
     * Writes one regular file's length, executable bits and complete bytes and verifies ordinary read stability.
     * @param output framed digest sink
     * @param file immutable regular file
     * @param cache trusted immutable inode cache, or null
     * @throws IOException if reading fails, file changes or POSIX permissions are unavailable
     */
    private static void writeFile(DataOutputStream output, Path file, Map<FileIdentity, byte[]> cache) throws IOException
    {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Identity file is not regular: " + file);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        output.writeBoolean(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
        output.writeBoolean(permissions.contains(PosixFilePermission.GROUP_EXECUTE));
        output.writeBoolean(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
        output.writeLong(before.size());
        boolean immutable = cache != null && before.fileKey() != null &&
            !permissions.contains(PosixFilePermission.OWNER_WRITE) &&
            !permissions.contains(PosixFilePermission.GROUP_WRITE) &&
            !permissions.contains(PosixFilePermission.OTHERS_WRITE);
        FileIdentity key = immutable ? new FileIdentity(before.fileKey(), before.size(), before.lastModifiedTime(),
            before.creationTime(), Set.copyOf(permissions)) : null;
        byte[] content = immutable ? cache.get(key) : null;
        if (content == null)
        {
            MessageDigest contentDigest = sha256();
            long copied;
            try (InputStream input = Files.newInputStream(file);
                DigestOutputStream sink = new DigestOutputStream(OutputStream.nullOutputStream(), contentDigest))
            {
                copied = input.transferTo(sink);
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean stable = copied == before.size() && before.size() == after.size() &&
                before.lastModifiedTime().equals(after.lastModifiedTime()) && Objects.equals(before.fileKey(), after.fileKey()) &&
                permissions.equals(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)) &&
                (!immutable || before.creationTime().equals(after.creationTime()));
            if (!stable) throw new IOException("Identity file changed while being read: " + file);
            content = contentDigest.digest();
            if (immutable) cache.put(key, content);
        }
        output.write(content);
    }

    /**
     * Invocation-scoped content reuse for explicitly trusted immutable worker inputs and toolchain files.
     * The caller guarantees that every regular inode without any write permission remains immutable for this
     * object's entire lifetime, including aliases outside snapshots. Permissions alone do not establish trust.
     * Writable files are always reread, even when size and timestamps match. Names, modes and symlinks are
     * always traversed anew; only complete regular-file SHA-256 values are reused by stable inode metadata.
     * Keep this object confined to one invocation coordinator and release it when that invocation ends.
     */
    static final class ImmutableFiles
    {
        /** Full content digests retained only for this explicitly trusted invocation. */
        private final Map<FileIdentity, byte[]> contents = new HashMap<>();

        /** Creates an empty cache after the caller establishes the immutable-inode contract. */
        ImmutableFiles() {}

        /**
         * Identifies a complete tree using this invocation's trusted immutable file digests.
         * @param snapshot complete immutable ready snapshot
         * @param visibleRoot original action-visible input root
         * @param argv exact arguments
         * @param environment exact environment
         * @param workingDirectory original absolute working directory
         * @param platform trusted backend/toolchain/platform identity
         * @return complete content identity, equal to the uncached operation
         * @throws IOException if complete supported stable content cannot be identified
         */
        String identify(Path snapshot, Path visibleRoot, List<String> argv, Map<String, String> environment,
            Path workingDirectory, String platform) throws IOException
        {
            return BazelWorkIdentity.identify(snapshot, visibleRoot, argv, environment, workingDirectory, platform, contents);
        }
    }

    /**
     * Stable immutable inode cache identity; creation time also guards against inode recycling.
     * Link-count ctime changes are excluded so independently hardlinked snapshots reuse the same immutable inode.
     * @param inode provider file identity including device
     * @param size complete regular-file byte count
     * @param modified last modification time
     * @param created inode creation time when available
     * @param permissions complete POSIX permission set
     */
    private record FileIdentity(Object inode, long size, Object modified, Object created,
        Set<PosixFilePermission> permissions) {}

    /**
     * Writes unbounded UTF-8 text with an exact byte length rather than the writeUTF 65535-byte limit.
     * @param output framed digest sink
     * @param value exact text to preserve
     * @throws IOException if writing the sink fails
     */
    private static void text(DataOutputStream output, String value) throws IOException
    {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /**
     * Formats relative names with slash separators independently of the host path provider.
     * @param root root omitted from the key
     * @param path descendant whose names are included
     * @return relative slash-separated name, empty for the root
     */
    private static String relative(Path root, Path path)
    {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(path)) names.add(name.toString());
        return String.join("/", names);
    }

    /**
     * Validates an explicitly supplied absolute normalized filesystem boundary.
     * @param path caller-selected boundary
     * @return unchanged validated path
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if {@code path} is relative or unnormalized
     */
    private static Path absolute(Path path)
    {
        Objects.requireNonNull(path, "path");
        if (!path.isAbsolute() || !path.equals(path.normalize()))
            throw new IllegalArgumentException("Identity paths must be absolute and normalized");
        return path;
    }

    /**
     * Obtains the mandatory JDK SHA-256 implementation.
     * @return fresh digest state
     */
    private static MessageDigest sha256()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
