package com.holdmyspot.codexunleashed.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Represents a strict version-one administrator authorization for one Mac Cargo command.
 * Parsing grants no authority: requireTrusted must finish before native construction or controller state creation.
 * @param source absolute nonsymlink administrator document
 * @param sourceDigest SHA256 of the exact bounded parsed document
 * @param uid positive nonroot workload user
 * @param gid positive nonroot workload group
 * @param cargo pinned absolute Cargo executable within toolchainRoot
 * @param rustc pinned absolute compiler executable within toolchainRoot
 * @param toolchainRoot immutable complete compiler/toolchain tree
 * @param environment complete authorized environment excluding invocation-owned entries
 * @param identities exact lowercase SHA256 identities for tools, installed bridges, runtime and toolchain
 * @param memory optional explicit production history boundary, or null for conservative unknown demand
 */
public record MacCargoLaunchConfiguration(Path source, String sourceDigest, int uid, int gid, Path cargo, Path rustc,
    Path toolchainRoot, Map<String, String> environment, Map<String, String> identities, CargoMemoryEstimates.Settings memory)
{
    /** Exact version-one fields; memory alone may be absent. */
    private static final Set<String> FIELDS = Set.of("version", "uid", "gid", "supplementaryGroups", "cargo",
        "rustc", "toolchainRoot", "environment", "identities", "memory");
    /** Required actual byte identities; toolchain covers every contained file and internal symlink. */
    private static final Set<String> IDENTITIES = Set.of("cargo", "rustc", "toolchain", "cargoBridge", "workload",
        "wrapper", "anchor", "frida", "javaRuntime");
    /** Native authorities and jobserver entries minted only by the invocation. */
    private static final Set<String> OWNED_ENV = Set.of("RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER",
        "CODEX_CARGO_SOCKET", "MAKEFLAGS", "CARGO_MAKEFLAGS", "MFLAGS", "LD_PRELOAD", "LD_LIBRARY_PATH");

    /**
     * Retains immutable validated values without acquiring native resources.
     * @param source administrator document
     * @param sourceDigest exact parsed digest
     * @param uid positive nonroot workload identity
     * @param gid positive nonroot workload group
     * @param cargo absolute pinned Cargo executable
     * @param rustc absolute pinned compiler
     * @param toolchainRoot complete immutable toolchain tree
     * @param environment exact authorized environment
     * @param identities complete pinned byte identities
     * @param memory optional invocation-owned history
     */
    public MacCargoLaunchConfiguration
    {
        for (Path path : List.of(source, cargo, rustc, toolchainRoot)) requireAbsolute(path);
        requireDigest(sourceDigest);
        if (uid <= 0 || gid <= 0) throw new IllegalArgumentException("Workload UID and GID must be positive");
        if (!cargo.startsWith(toolchainRoot) || !rustc.startsWith(toolchainRoot))
            throw new IllegalArgumentException("Cargo and rustc must be within the pinned toolchain root");
        environment = Map.copyOf(environment);
        environment.forEach((key, value) ->
        {
            requireEnvironmentEntry(key, value);
            if (OWNED_ENV.contains(key) || key.startsWith("DYLD_"))
                throw new IllegalArgumentException("Environment entry controls invocation or loader authority: " + key);
        });
        identities = Map.copyOf(identities);
        if (!identities.keySet().equals(IDENTITIES)) throw new IllegalArgumentException("Pinned identities must be exactly " + IDENTITIES);
        identities.values().forEach(MacCargoLaunchConfiguration::requireDigest);
    }

    /**
     * Reads an unchanged bounded regular administrator document without native acquisition.
     * @param scope borrowed strict parser scope
     * @param source absolute document path
     * @return parsed values that still require authority preflight
     * @throws IOException if file identity, size, JSON or semantic validation fails
     */
    public static MacCargoLaunchConfiguration read(MacCargoScope scope, Path source) throws IOException
    {
        return decode(scope, source, readBytes(source));
    }

    /**
     * Decodes exact strict versioned JSON, preserving integer types and optional-member presence.
     * @param scope borrowed bounded parser
     * @param source absolute represented document
     * @param bytes exact document bytes
     * @return immutable resource-free authorization values
     * @throws IOException if fields, paths, credentials, schema or identities are invalid
     */
    public static MacCargoLaunchConfiguration decode(MacCargoScope scope, Path source, byte[] bytes) throws IOException
    {
        JsonNode root = document(scope, bytes, FIELDS, Set.of("memory"));
        version(root);
        if (!root.path("supplementaryGroups").isArray() || !root.path("supplementaryGroups").isEmpty())
            throw new IOException("supplementaryGroups must be an empty array");
        try
        {
            if (root.has("memory") && !path(root.path("memory"), "toolchainRoot").equals(path(root, "toolchainRoot")))
                throw new IOException("History toolchain root must equal the pinned toolchain root");
            return new MacCargoLaunchConfiguration(source, digest(bytes), positiveInt(root, "uid"), positiveInt(root, "gid"),
                path(root, "cargo"), path(root, "rustc"), path(root, "toolchainRoot"), strings(root.path("environment")),
                strings(root.path("identities")), root.has("memory") ? memory(root.path("memory")) : null);
        }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid Mac launch authorization", invalid); }
    }

    /**
     * Validates administrator ownership, immutable document bytes, complete toolchain and installed runtime identities.
     * The command composition separately validates current controller credentials and inherited descriptors.
     * @param installed actual bundled runtime files and explicitly supplied Frida producer
     * @throws IOException if any input is mutable by workload identities, changed, symlinked or not pinned
     */
    public void requireTrusted(Installed installed) throws IOException
    {
        requireRootControlled(source);
        if (((int) Files.getAttribute(source, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0222) != 0)
            throw new IOException("Administrator launch document must have no write permission bits");
        if (!digest(readBytes(source)).equals(sourceDigest)) throw new IOException("Administrator launch document changed after parsing");
        Map<String, Path> files = new HashMap<>(installed.files());
        files.put("cargo", cargo); files.put("rustc", rustc);
        for (Map.Entry<String, Path> entry : files.entrySet())
        {
            requireRootControlled(entry.getValue());
            if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS) ||
                !fileDigest(entry.getValue()).equals(identities.get(entry.getKey())))
                throw new IOException("Pinned installed input differs: " + entry.getKey());
        }
        for (Path executable : List.of(cargo, rustc, installed.wrapper(), installed.anchor()))
        {
            if (!Files.isExecutable(executable)) throw new IOException("Required installed tool is not executable: " + executable);
            int mode = (int) Files.getAttribute(executable, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            int group = (int) Files.getAttribute(executable, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            int permissions = (mode >> (group == gid ? 3 : 0)) & 7;
            if ((permissions & 5) != 5) throw new IOException("Authorized workload cannot read/execute installed tool: " + executable);
            requireWorkloadTraversal(executable.getParent(), gid);
        }
        requireRootControlled(toolchainRoot);
        try (var entries = Files.walk(toolchainRoot))
        {
            for (Path entry : entries.toList())
            {
                if (Files.isSymbolicLink(entry))
                {
                    if (!Integer.valueOf(0).equals(Files.getAttribute(entry, "unix:uid", LinkOption.NOFOLLOW_LINKS)))
                        throw new IOException("Toolchain symlink is not administrator-owned: " + entry);
                    requireRootControlled(entry.toRealPath());
                }
                else requireRootControlled(entry);
            }
        }
        if (!toolchainDigest(toolchainRoot).equals(identities.get("toolchain")))
            throw new IOException("Complete pinned toolchain changed");
    }

    /**
     * Resolves native products only relative to the actual bundled Java runtime, retaining explicit Frida selection.
     * @param runtime actual absolute java.home, resolved through its real installed path
     * @param frida explicitly supplied absolute instrumentation producer
     * @return exact installed paths without source-tree or working-directory fallback
     * @throws IOException if the actual runtime cannot be resolved
     */
    public static Installed installed(Path runtime, Path frida) throws IOException
    {
        requireAbsolute(runtime); requireAbsolute(frida);
        Path home = runtime.toRealPath();
        return new Installed(home.resolve("lib/codex/libmac-cargo.dylib"), home.resolve("lib/codex/libmac-workload.dylib"),
            home.resolve("bin/codex-cargo-wrapper"), home.resolve("bin/codex-workload-anchor"), frida, home.resolve("lib/modules"));
    }

    /**
     * Carries exact installed runtime identities without granting execution authority.
     * @param cargoBridge installed Cargo descriptor bridge
     * @param workload installed native workload authority bridge
     * @param wrapper installed original Cargo wrapper
     * @param anchor installed non-forking workload anchor
     * @param frida explicit instrumentation producer
     * @param javaRuntime actual installed jlink modules file
     */
    public record Installed(Path cargoBridge, Path workload, Path wrapper, Path anchor, Path frida, Path javaRuntime)
    {
        /**
         * Rejects relative installed product paths.
         * @param cargoBridge Cargo bridge
         * @param workload workload bridge
         * @param wrapper installed wrapper
         * @param anchor workload anchor
         * @param frida instrumentation producer
         * @param javaRuntime installed runtime modules
         */
        public Installed
        {
            for (Path path : List.of(cargoBridge, workload, wrapper, anchor, frida, javaRuntime)) requireAbsolute(path);
        }
        /**
         * Supplies the complete installed-byte identity map.
         * @return immutable identity names and exact paths
         */
        Map<String, Path> files()
        {
            return Map.of("cargoBridge", cargoBridge, "workload", workload, "wrapper", wrapper,
                "anchor", anchor, "frida", frida, "javaRuntime", javaRuntime);
        }
    }

    /**
     * Hashes sorted POSIX-relative file and symlink rows in the complete toolchain tree.
     * Each row is path, NUL, file or symlink, NUL, lowercase file SHA256 or exact link target, LF. Directories have no row.
     * Symlinks must resolve inside the root; special files are rejected.
     * @param root absolute existing complete toolchain directory
     * @return lowercase SHA256 of the UTF-8 row stream
     * @throws IOException if an entry is unreadable, special or escapes the root
     */
    static String toolchainDigest(Path root) throws IOException
    {
        requireAbsolute(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Toolchain root must be a directory without a final symlink: " + root);
        Path realRoot = root.toRealPath();
        MessageDigest hash = sha256();
        try (var entries = Files.walk(root))
        {
            for (Path entry : entries.filter(value -> !value.equals(root)).sorted((first, second) ->
                relative(root, first).compareTo(relative(root, second))).toList())
            {
                BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) continue;
                String type;
                String value;
                if (attributes.isRegularFile()) { type = "file"; value = fileDigest(entry); }
                else if (attributes.isSymbolicLink())
                {
                    if (!entry.toRealPath().startsWith(realRoot)) throw new IOException("Toolchain symlink escapes root: " + entry);
                    type = "symlink";
                    value = Files.readSymbolicLink(entry).toString();
                }
                else throw new IOException("Toolchain contains a special file: " + entry);
                hash.update((relative(root, entry) + '\0' + type + '\0' + value + '\n').getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    /**
     * Checks each pathname component is root-owned, nonsymlink and not writable by group or other users.
     * @param source absolute existing path whose namespace conveys administrator authority
     * @throws IOException if any component has an untrusted owner, type or permissions
     */
    static void requireRootControlled(Path source) throws IOException
    {
        requireAbsolute(source);
        for (Path path = source; path != null; path = path.getParent())
        {
            Map<String, Object> attributes = Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean trustedType = basic.isDirectory() || basic.isRegularFile();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            boolean writableByWorkload = permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                permissions.contains(PosixFilePermission.OTHERS_WRITE);
            if (!Integer.valueOf(0).equals(attributes.get("uid")) || !trustedType || writableByWorkload)
                throw new IOException("Path namespace must be root-owned, nonsymlink and protected from group/other writes: " + path);
        }
    }

    /**
     * Requires actual workload-group or other search permission through an already trusted directory namespace.
     * Supplementary groups are empty, so only the explicitly configured workload group can grant group traversal.
     * @param directory trusted absolute directory
     * @param gid authorized positive workload group
     * @throws IOException if any parent cannot be searched by the workload
     */
    static void requireWorkloadTraversal(Path directory, int gid) throws IOException
    {
        for (Path path = directory; path != null; path = path.getParent())
        {
            int mode = (int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            int group = (int) Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            if (((mode >> (group == gid ? 3 : 0)) & 1) == 0)
                throw new IOException("Authorized workload cannot traverse trusted namespace: " + path);
        }
    }

    /**
     * Reads an unchanged regular file with no final symlink and a hard byte bound.
     * @param source absolute input file
     * @return complete original document bytes
     * @throws IOException if type, size or identity is invalid or changes during the read
     */
    private static byte[] readBytes(Path source) throws IOException
    {
        requireAbsolute(source);
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.fileKey() == null) throw new IOException("Configuration must be a regular file with stable identity: " + source);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            bytes = input.readNBytes(1048576 + 1);
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || !before.fileKey().equals(after.fileKey()) || before.size() != after.size() ||
            !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Configuration identity changed while reading: " + source);
        requireBound(bytes);
        return bytes;
    }

    /**
     * Parses one exact bounded object using the strict scope parser.
     * @param scope borrowed invocation parsing services
     * @param bytes complete document
     * @param fields accepted top-level members
     * @param optional members allowed to be omitted
     * @return parsed object containing exactly the required and present optional members
     * @throws IOException if JSON, bounds or member presence is invalid
     */
    private static JsonNode document(MacCargoScope scope, byte[] bytes, Set<String> fields, Set<String> optional) throws IOException
    {
        requireBound(bytes);
        try
        {
            JsonNode root = scope.launchJson().readTree(bytes);
            requireFields(root, fields, optional);
            return root;
        }
        catch (JacksonException invalid) { throw new IOException("Invalid bounded Mac configuration JSON", invalid); }
    }

    /**
     * Rejects unknown and omitted required object members.
     * @param root represented JSON object
     * @param fields exact permitted members
     * @param optional members that may be omitted
     * @throws IOException if the node is not an object or its members differ
     */
    private static void requireFields(JsonNode root, Set<String> fields, Set<String> optional) throws IOException
    {
        if (!root.isObject()) throw new IOException("Configuration value must be an object");
        Set<String> actual = new HashSet<>(root.propertyNames());
        Set<String> required = new HashSet<>(fields); required.removeAll(optional);
        if (!fields.containsAll(actual) || !actual.containsAll(required))
            throw new IOException("Configuration object fields differ from required members: " + fields);
    }

    /**
     * Requires the exact integral schema version supported by this consumer.
     * @param root parsed configuration object
     * @throws IOException if version is missing, fractional or different from one
     */
    private static void version(JsonNode root) throws IOException
    {
        if (positiveInt(root, "version") != 1) throw new IOException("Configuration version must be integer 1");
    }

    /**
     * Decodes one positive exact integer.
     * @param root parsed object
     * @param field positive integer member
     * @return value from one through Integer.MAX_VALUE
     * @throws IOException if the member is missing, fractional or out of range
     */
    private static int positiveInt(JsonNode root, String field) throws IOException
    {
        JsonNode value = root.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0)
            throw new IOException(field + " must be a positive integer representable as an int");
        return value.intValue();
    }

    /**
     * Decodes an absolute normalized local path without silently normalizing caller input.
     * @param root parsed object
     * @param field textual path member
     * @return exact absolute normalized path
     * @throws IOException if the field is missing, not text, relative or nonnormalized
     */
    private static Path path(JsonNode root, String field) throws IOException
    {
        JsonNode value = root.path(field);
        if (!value.isString()) throw new IOException(field + " must be an absolute path string");
        try
        {
            Path result = Path.of(value.stringValue());
            requireAbsolute(result);
            if (!result.toString().equals(value.stringValue())) throw new IllegalArgumentException("Path spelling is noncanonical");
            return result;
        }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid path in " + field, invalid); }
    }

    /**
     * Decodes a string-valued object without coercion.
     * @param node parsed string map
     * @return copied complete textual entries
     * @throws IOException if the node or any value has another type
     */
    private static Map<String, String> strings(JsonNode node) throws IOException
    {
        if (!node.isObject()) throw new IOException("Configuration string map must be an object");
        Map<String, String> values = new HashMap<>();
        for (String key : node.propertyNames())
        {
            JsonNode value = node.path(key);
            if (!value.isString()) throw new IOException("Configuration map entry must be a string: " + key);
            values.put(key, value.stringValue());
        }
        return Map.copyOf(values);
    }

    /**
     * Decodes a complete explicitly supplied history boundary.
     * @param node complete memory settings object; explicit null is invalid
     * @return resource-free history settings
     * @throws IOException if any setting is missing or malformed
     */
    private static CargoMemoryEstimates.Settings memory(JsonNode node) throws IOException
    {
        requireFields(node, Set.of("historyDirectory", "inputRoot", "outputRoots", "toolchainRoot"), Set.of());
        JsonNode outputs = node.path("outputRoots");
        if (!outputs.isArray()) throw new IOException("memory.outputRoots must be an array");
        List<Path> roots = new ArrayList<>();
        for (JsonNode output : outputs)
        {
            if (!output.isString()) throw new IOException("memory.outputRoots entries must be path strings");
            Path root = Path.of(output.stringValue()); requireAbsolute(root); roots.add(root);
        }
        return new CargoMemoryEstimates.Settings(path(node, "historyDirectory"), path(node, "inputRoot"), roots,
            path(node, "toolchainRoot"));
    }

    /**
     * Validates an absolute normalized path at its reusable object boundary.
     * @param path local path
     * @throws NullPointerException if path is null
     * @throws IllegalArgumentException if path is relative or contains redundant components
     */
    private static void requireAbsolute(Path path)
    {
        if (!path.isAbsolute() || !path.equals(path.normalize()))
            throw new IllegalArgumentException("Path must be absolute and normalized: " + path);
    }

    /**
     * Rejects environment text that native exec cannot represent.
     * @param key nonempty environment name without equals or NUL
     * @param value exact environment value without NUL
     * @throws IllegalArgumentException if the entry cannot be represented
     */
    private static void requireEnvironmentEntry(String key, String value)
    {
        if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf('\0') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Environment entry has an empty name, equals in its name, or NUL text");
    }

    /**
     * Validates a canonical lowercase SHA256 representation.
     * @param value exact 64-character lowercase hexadecimal digest
     * @throws IllegalArgumentException if the value is not canonical SHA256 text
     */
    private static void requireDigest(String value)
    {
        if (value.length() != 64) throw new IllegalArgumentException("SHA256 must contain 64 lowercase hexadecimal characters");
        try
        {
            byte[] bytes = HexFormat.of().parseHex(value);
            if (!HexFormat.of().formatHex(bytes).equals(value)) throw new IllegalArgumentException("SHA256 must use lowercase hexadecimal");
        }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("SHA256 must use lowercase hexadecimal", invalid); }
    }

    /**
     * Bounds the complete document independently of the parser's own limits.
     * @param bytes complete document
     * @throws IOException if empty or larger than one MiB
     */
    private static void requireBound(byte[] bytes) throws IOException
    {
        if (bytes.length == 0 || bytes.length > 1048576)
            throw new IOException("Configuration document must contain between one byte and one MiB");
    }

    /**
     * Hashes a regular file's complete bytes without following a final symlink.
     * @param path exact regular input
     * @return lowercase SHA256
     * @throws IOException if bytes cannot be read
     */
    private static String fileDigest(Path path) throws IOException
    {
        MessageDigest hash = sha256();
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) hash.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    /**
     * Hashes complete configuration bytes.
     * @param bytes complete document
     * @return lowercase SHA256
     */
    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }

    /**
     * Acquires the platform-mandated SHA256 implementation.
     * @return fresh independent digest state
     */
    private static MessageDigest sha256()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError("Java requires SHA-256", impossible); }
    }

    /**
     * Writes a relative path using the tree digest's platform-independent separator.
     * @param root tree root
     * @param entry contained entry
     * @return slash-separated relative path
     */
    private static String relative(Path root, Path entry)
    {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(entry)) names.add(name.toString());
        return String.join("/", names);
    }
}
