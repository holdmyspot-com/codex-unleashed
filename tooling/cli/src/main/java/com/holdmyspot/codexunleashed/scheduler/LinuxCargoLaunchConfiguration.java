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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads explicitly authorized Linux workload identities, tools and environment before native acquisition.
 * Parsing grants no authority. The elevated composition must call {@link #requireTrusted} before using the inputs.
 * Values are immutable; the supplied Cargo scope remains caller-owned.
 * @param source absolute configuration file retained through preflight
 * @param sourceDigest SHA256 of the exact parsed configuration bytes
 * @param uid positive workload user identity
 * @param gid positive workload group identity
 * @param cargo absolute pinned Cargo executable
 * @param rustc absolute pinned compiler executable
 * @param toolchainRoot absolute immutable complete toolchain tree
 * @param environment complete authorized workload environment before invocation-owned entries
 * @param unshare absolute pinned util-linux executable
 * @param libcgroup absolute pinned libcgroup-v3 library
 * @param cgroupRoot absolute cgroup-v2 mount
 * @param procRoot absolute proc mount
 * @param delegationParent absolute delegated parent for exclusive invocation containments
 * @param identities required lowercase SHA256 values for cargo, rustc, toolchain, unshare, libcgroup, frida and javaRuntime
 * @param memory explicit estimate settings, or null for unknown demand
 */
public record LinuxCargoLaunchConfiguration(Path source, String sourceDigest, int uid, int gid, Path cargo, Path rustc,
    Path toolchainRoot, Map<String, String> environment, Path unshare, Path libcgroup, Path cgroupRoot,
    Path procRoot, Path delegationParent, Map<String, String> identities, CargoMemoryEstimates.Settings memory)
{
    /** Environment locator supplied only by the invocation composition. */
    static final String WRAPPER_CONFIG_ENV = "CODEX_LINUX_CARGO_WRAPPER_CONFIG";
    /** Version-one administrator configuration members; memory is the sole optional member. */
    private static final Set<String> CONFIG_FIELDS = Set.of("version", "uid", "gid", "supplementaryGroups", "cargo",
        "rustc", "toolchainRoot", "environment", "unshare", "libcgroup", "cgroupRoot", "procRoot", "delegationParent",
        "identities", "memory");
    /** Pinned byte identities the administrator must supply before native acquisition. */
    private static final Set<String> IDENTITY_FIELDS = Set.of("cargo", "rustc", "toolchain", "unshare", "libcgroup",
        "frida", "javaRuntime");
    /** Wrapper context members, all required and immutable within one invocation. */
    private static final Set<String> WRAPPER_FIELDS = Set.of("version", "controller", "registrationDirectory",
        "cgroupParent", "scratchParent", "javaExecutable", "classPath", "rustc", "uid", "gid", "timeoutMillis");
    /** Environment members owned by invocation/controller construction, forbidden in administrator input. */
    private static final Set<String> OWNED_ENV = Set.of("RUSTC", "RUSTC_WRAPPER", WRAPPER_CONFIG_ENV,
        "CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS", "RUSTC_WORKSPACE_WRAPPER");
    /** Immutable permissions for a context readable by the workload but writable only before publication. */
    private static final Set<PosixFilePermission> WRAPPER_PERMISSIONS =
        Set.copyOf(PosixFilePermissions.fromString("r--r--r--"));

    /**
     * Copies stable configuration values and establishes path, identity and environment invariants.
     * @param source absolute file providing administrator authorization
     * @param sourceDigest exact parsed file SHA256
     * @param uid positive workload user identity
     * @param gid positive workload group identity
     * @param cargo absolute Cargo executable
     * @param rustc absolute compiler executable
     * @param toolchainRoot absolute complete toolchain tree
     * @param environment complete workload environment excluding invocation-owned entries
     * @param unshare absolute util-linux executable
     * @param libcgroup absolute libcgroup-v3 library
     * @param cgroupRoot absolute cgroup-v2 mount
     * @param procRoot absolute proc mount
     * @param delegationParent absolute delegated cgroup parent
     * @param identities complete pinned byte identities
     * @param memory complete explicit history settings, or null for unknown demand
     * @throws NullPointerException if a required value or collection member is null
     * @throws IllegalArgumentException if paths, identities, environment or credentials are invalid
     */
    public LinuxCargoLaunchConfiguration
    {
        for (Path path : List.of(source, cargo, rustc, toolchainRoot, unshare, libcgroup, cgroupRoot, procRoot,
            delegationParent)) requireAbsolute(path);
        requireDigest(sourceDigest);
        if (uid <= 0 || gid <= 0) throw new IllegalArgumentException("Workload UID and GID must be positive");
        environment = Map.copyOf(environment);
        for (Map.Entry<String, String> entry : environment.entrySet())
        {
            requireEnvironmentEntry(entry.getKey(), entry.getValue());
            if (OWNED_ENV.contains(entry.getKey()))
                throw new IllegalArgumentException("Environment entry is invocation-owned: " + entry.getKey());
        }
        identities = Map.copyOf(identities);
        if (!identities.keySet().equals(IDENTITY_FIELDS))
            throw new IllegalArgumentException("Identities must contain exactly " + IDENTITY_FIELDS);
        identities.values().forEach(LinuxCargoLaunchConfiguration::requireDigest);
        if (!cargo.startsWith(toolchainRoot) || !rustc.startsWith(toolchainRoot))
            throw new IllegalArgumentException("Cargo and rustc must be within the configured toolchain root");
        if (!delegationParent.startsWith(cgroupRoot) || delegationParent.equals(cgroupRoot))
            throw new IllegalArgumentException("Delegation parent must be strictly below the cgroup mount root");
    }

    /**
     * Reads one bounded strict administrator document without acquiring native resources.
     * @param scope caller-owned bounded JSON parser
     * @param source absolute regular configuration file, without a final symlink
     * @return validated represented inputs requiring authority preflight before use
     * @throws IOException if the file, document or semantic configuration is invalid
     */
    public static LinuxCargoLaunchConfiguration read(LinuxCargoScope scope, Path source) throws IOException
    {
        return decode(scope, source, readBytes(source));
    }

    /**
     * Decodes administrator input using the invocation's strict parser.
     * @param scope caller-owned bounded JSON parser
     * @param source absolute configuration file represented by the bytes
     * @param bytes complete bounded configuration document
     * @return immutable validated represented inputs
     * @throws IOException if JSON, fields, paths, values or credentials are invalid
     */
    public static LinuxCargoLaunchConfiguration decode(LinuxCargoScope scope, Path source, byte[] bytes) throws IOException
    {
        JsonNode root = document(scope, bytes, CONFIG_FIELDS, Set.of("memory"));
        version(root);
        JsonNode groups = root.path("supplementaryGroups");
        if (!groups.isArray() || !groups.isEmpty())
            throw new IOException("supplementaryGroups must be an empty array");
        try
        {
            if (root.has("memory") && !path(root.path("memory"), "toolchainRoot").equals(path(root, "toolchainRoot")))
                throw new IOException("Memory toolchainRoot must equal the pinned complete toolchain root");
            return new LinuxCargoLaunchConfiguration(source, digest(bytes), positiveInt(root, "uid"),
                positiveInt(root, "gid"), path(root, "cargo"), path(root, "rustc"), path(root, "toolchainRoot"),
                strings(root.path("environment")), path(root, "unshare"), path(root, "libcgroup"), path(root, "cgroupRoot"),
                path(root, "procRoot"), path(root, "delegationParent"), strings(root.path("identities")),
                root.has("memory") ? memory(root.path("memory")) : null);
        }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid Linux launch configuration: " + invalid.getMessage(), invalid); }
    }

    /**
     * Checks elevated authority, root-controlled namespaces and every selected pinned byte identity.
     * No workload may start before this succeeds. It does not acquire cgroups or grant native readiness.
     * @param suppliedFrida exact separately supplied native instrumentation library
     * @param bundledJava actual bundled Java executable
     * @param runtimeModules actual installed jlink lib/modules file
     * @throws IOException if credentials, namespace protection, config bytes or pinned inputs differ
     */
    void requireTrusted(Path suppliedFrida, Path bundledJava, Path runtimeModules) throws IOException
    {
        requireRootControlled(source);
        if (!digest(readBytes(source)).equals(sourceDigest)) throw new IOException("Linux launch configuration changed after parsing");
        List<String> status = Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8);
        String users = status.stream().filter(line -> line.startsWith("Uid:")).findFirst().
            orElseThrow(() -> new IOException("Current Linux UID fields are unavailable"));
        String[] values = users.substring(4).trim().split("\\s+");
        if (values.length != 4 || !values[0].equals("0") || !values[1].equals("0"))
            throw new IOException("Linux scheduling requires real and effective UID zero");
        for (Path path : List.of(cargo, rustc, unshare, libcgroup, suppliedFrida, bundledJava, runtimeModules,
            toolchainRoot, cgroupRoot, procRoot, delegationParent)) requireRootControlled(path);
        for (Path executable : List.of(cargo, rustc, unshare, bundledJava))
            if (!Files.isExecutable(executable)) throw new IOException("Configured executable is not executable: " + executable);
        for (Map.Entry<String, Path> entry : Map.of("cargo", cargo, "rustc", rustc, "unshare", unshare,
            "libcgroup", libcgroup, "frida", suppliedFrida, "javaRuntime", runtimeModules).entrySet())
        {
            if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Pinned input is not a regular file: " + entry.getValue());
            if (!fileDigest(entry.getValue()).equals(identities.get(entry.getKey())))
                throw new IOException("Pinned input identity changed: " + entry.getKey() + " at " + entry.getValue());
        }
        try (var entries = Files.walk(toolchainRoot))
        {
            for (Path entry : entries.toList())
            {
                if (Files.isSymbolicLink(entry))
                {
                    if (!Integer.valueOf(0).equals(Files.getAttribute(entry, "unix:uid", LinkOption.NOFOLLOW_LINKS)))
                        throw new IOException("Toolchain symlink must be root-owned: " + entry);
                    requireRootControlled(entry.toRealPath());
                }
                else requireRootControlled(entry);
            }
        }
        if (!toolchainDigest(toolchainRoot).equals(identities.get("toolchain")))
            throw new IOException("Pinned complete toolchain identity changed: " + toolchainRoot);
    }

    /**
     * Hashes sorted POSIX-relative file and symlink rows in the complete toolchain tree.
     * Each row is path, NUL, file or symlink, NUL, lowercase file SHA256 or exact link target, LF. Directories have no row.
     * Symlinks must resolve inside the root; special files are rejected.
     * @param root absolute existing complete toolchain directory
     * @return lowercase SHA256 of the UTF-8 row stream
     * @throws IOException if an entry is unreadable, special or escapes the root
     */
    public static String toolchainDigest(Path root) throws IOException
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
     * Supplies immutable per-invocation wrapper context; the workload cannot replace this context.
     * @param controller absolute root controller endpoint
     * @param registrationDirectory absolute workload-owned registration parent
     * @param cgroupParent absolute action containment parent
     * @param scratchParent absolute invocation runtime parent
     * @param javaExecutable absolute bundled Java executable
     * @param classPath absolute helper runtime identity
     * @param rustc exact approved absolute compiler executable
     * @param uid positive configured workload user
     * @param gid positive configured workload group
     * @param timeout positive bounded control-frame timeout
     */
    public record Wrapper(Path controller, Path registrationDirectory, Path cgroupParent, Path scratchParent,
        Path javaExecutable, Path classPath, Path rustc, int uid, int gid, Duration timeout)
    {
        /**
         * Establishes immutable wrapper path, credential and timeout invariants.
         * @param controller absolute controller endpoint
         * @param registrationDirectory absolute registration parent
         * @param cgroupParent absolute action containment parent
         * @param scratchParent absolute invocation runtime parent
         * @param javaExecutable absolute bundled Java executable
         * @param classPath absolute helper runtime identity
         * @param rustc absolute approved compiler
         * @param uid positive workload user
         * @param gid positive workload group
         * @param timeout positive control timeout representable as integral milliseconds
         * @throws NullPointerException if a required value is null
         * @throws IllegalArgumentException if a path, credential or timeout is invalid
         */
        public Wrapper
        {
            for (Path path : List.of(controller, registrationDirectory, cgroupParent, scratchParent, javaExecutable,
                classPath, rustc)) requireAbsolute(path);
            Objects.requireNonNull(timeout, "timeout");
            if (uid <= 0 || gid <= 0) throw new IllegalArgumentException("Wrapper UID and GID must be positive");
            long milliseconds;
            try { milliseconds = timeout.toMillis(); }
            catch (ArithmeticException overflow)
            {
                throw new IllegalArgumentException("Wrapper timeout must fit integral milliseconds in a long", overflow);
            }
            if (milliseconds <= 0 || !Duration.ofMillis(milliseconds).equals(timeout))
                throw new IllegalArgumentException("Wrapper timeout must be positive integral milliseconds");
        }
    }

    /**
     * Publishes a new read-only context; an existing path is never replaced.
     * The elevated caller owns removal after all wrapper consumers finish.
     * @param scope borrowed invocation parser and serializer
     * @param source new absolute context file within the invocation's root-controlled namespace
     * @param wrapper immutable complete wrapper context
     * @throws IOException if serialization, exclusive creation or permission setting fails
     */
    static void writeWrapper(LinuxCargoScope scope, Path source, Wrapper wrapper) throws IOException
    {
        requireAbsolute(source);
        byte[] bytes = encodeWrapper(scope, wrapper);
        Files.write(source, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(source, WRAPPER_PERMISSIONS);
    }

    /**
     * Reads a bounded root-controlled wrapper context without accepting workload-modifiable configuration.
     * @param scope borrowed strict invocation parser
     * @param source absolute root-controlled read-only context file
     * @return complete immutable wrapper context
     * @throws IOException if namespace authority, file identity, JSON or values are invalid
     */
    static Wrapper readWrapper(LinuxCargoScope scope, Path source) throws IOException
    {
        requireRootControlled(source);
        return decodeWrapper(scope, readBytes(source));
    }

    /**
     * Encodes the exact version-one wrapper context using scoped JSON services.
     * @param scope borrowed strict parser and serializer
     * @param wrapper immutable complete context
     * @return bounded encoded JSON document
     * @throws IOException if encoding exceeds the protocol bound
     */
    public static byte[] encodeWrapper(LinuxCargoScope scope, Wrapper wrapper) throws IOException
    {
        Map<String, Object> values = new HashMap<>();
        values.put("version", 1);
        values.put("controller", wrapper.controller().toString());
        values.put("registrationDirectory", wrapper.registrationDirectory().toString());
        values.put("cgroupParent", wrapper.cgroupParent().toString());
        values.put("scratchParent", wrapper.scratchParent().toString());
        values.put("javaExecutable", wrapper.javaExecutable().toString());
        values.put("classPath", wrapper.classPath().toString());
        values.put("rustc", wrapper.rustc().toString());
        values.put("uid", wrapper.uid()); values.put("gid", wrapper.gid());
        values.put("timeoutMillis", wrapper.timeout().toMillis());
        byte[] bytes = scope.json().writeValueAsBytes(values);
        requireBound(bytes);
        return bytes;
    }

    /**
     * Decodes exact wrapper fields without granting controller or native authority.
     * @param scope borrowed strict parser
     * @param bytes complete bounded version-one context document
     * @return validated represented wrapper inputs
     * @throws IOException if the context's JSON, fields or semantic values are invalid
     */
    public static Wrapper decodeWrapper(LinuxCargoScope scope, byte[] bytes) throws IOException
    {
        JsonNode root = document(scope, bytes, WRAPPER_FIELDS, Set.of());
        version(root);
        try
        {
            JsonNode timeout = root.path("timeoutMillis");
            if (!timeout.isIntegralNumber() || !timeout.canConvertToLong() || timeout.longValue() <= 0)
                throw new IOException("timeoutMillis must be a positive integer representable as a long");
            return new Wrapper(path(root, "controller"), path(root, "registrationDirectory"), path(root, "cgroupParent"),
                path(root, "scratchParent"), path(root, "javaExecutable"), path(root, "classPath"), path(root, "rustc"),
                positiveInt(root, "uid"), positiveInt(root, "gid"), Duration.ofMillis(timeout.longValue()));
        }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid wrapper context: " + invalid.getMessage(), invalid); }
    }

    /**
     * Checks each pathname component is root-owned, nonsymlink and not writable by group or other users.
     * @param source absolute existing path whose namespace conveys administrator authority
     * @throws IOException if any component has an untrusted owner, type or permissions
     */
    private static void requireRootControlled(Path source) throws IOException
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
            bytes = input.readNBytes(LinuxCargoTransport.MAX_FRAME_BYTES + 1);
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
    private static JsonNode document(LinuxCargoScope scope, byte[] bytes, Set<String> fields, Set<String> optional) throws IOException
    {
        requireBound(bytes);
        try
        {
            JsonNode root = scope.json().readTree(bytes);
            requireFields(root, fields, optional);
            return root;
        }
        catch (JacksonException invalid) { throw new IOException("Invalid bounded Linux configuration JSON", invalid); }
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
        if (bytes.length == 0 || bytes.length > LinuxCargoTransport.MAX_FRAME_BYTES)
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
