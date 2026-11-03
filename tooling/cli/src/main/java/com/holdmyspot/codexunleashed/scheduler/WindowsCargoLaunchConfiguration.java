package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads explicit Windows Cargo launch inputs without changing the caller's security identity.
 * Preflight pins the complete toolchain and installed runtime, including the sibling wrapper image, before execution.
 * @param source absolute caller configuration file
 * @param sourceDigest exact parsed document SHA256
 * @param cargo absolute pinned Cargo executable
 * @param rustc absolute pinned compiler executable
 * @param toolchainRoot absolute complete toolchain tree
 * @param environment complete explicit caller environment before invocation-owned entries
 * @param identities exact lowercase SHA256 values for cargo, rustc, toolchain, frida, javaRuntime and wrapper
 * @param memory explicit best-effort history settings, or null for unknown demand
 */
public record WindowsCargoLaunchConfiguration(Path source, String sourceDigest, Path cargo, Path rustc, Path toolchainRoot,
    Map<String, String> environment, Map<String, String> identities, CargoMemoryEstimates.Settings memory)
{
    /** Version-one fields; only memory may be absent. */
    private static final Set<String> FIELDS = Set.of("version", "cargo", "rustc", "toolchainRoot", "environment", "identities", "memory");
    /** Required pinned file and complete-tree identities. */
    private static final Set<String> IDENTITY_FIELDS = Set.of("cargo", "rustc", "toolchain", "frida", "javaRuntime", "wrapper");
    /** Names supplied exclusively by the invocation and jobserver. */
    private static final Set<String> OWNED_ENV = Set.of("RUSTC", "RUSTC_WRAPPER", "CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS");

    /**
     * Copies immutable Windows launch inputs without acquiring native resources.
     * @param source absolute caller configuration file
     * @param sourceDigest exact parsed document SHA256
     * @param cargo absolute Cargo executable
     * @param rustc absolute compiler executable
     * @param toolchainRoot complete pinned toolchain tree
     * @param environment complete explicit environment before invocation-owned entries
     * @param identities complete canonical pinned identities
     * @param memory history settings, or null for conservative unknown demand
     * @throws NullPointerException if a required value or collection member is null
     * @throws IllegalArgumentException if paths, hashes or Windows environment entries are invalid
     */
    public WindowsCargoLaunchConfiguration
    {
        for (Path path : List.of(source, cargo, rustc, toolchainRoot)) requireAbsolute(path);
        requireDigest(sourceDigest);
        environment = Map.copyOf(environment);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : environment.entrySet())
        {
            String key = entry.getKey();
            boolean driveDirectory = key.length() == 3 && key.charAt(0) == '=' && Character.isLetter(key.charAt(1)) && key.charAt(2) == ':';
            boolean invalidName = key.isEmpty() || key.indexOf('\0') >= 0 || (key.indexOf('=') >= 0 && !driveDirectory);
            if (invalidName || entry.getValue().indexOf('\0') >= 0 || !names.add(key))
                throw new IllegalArgumentException("Windows environment has an invalid or case-conflicting name/value: " + key);
            String upper = key.toUpperCase(java.util.Locale.ROOT);
            if (upper.equals("RUSTC_WORKSPACE_WRAPPER") && !entry.getValue().isEmpty())
                throw new IllegalArgumentException("Nested workspace wrappers are outside the approved launch chain");
            if (OWNED_ENV.contains(upper) || upper.startsWith("CODEX_CARGO_"))
                throw new IllegalArgumentException("Windows environment entry is invocation-owned: " + key);
        }
        identities = Map.copyOf(identities);
        if (!identities.keySet().equals(IDENTITY_FIELDS))
            throw new IllegalArgumentException("Windows identities must contain exactly " + IDENTITY_FIELDS);
        identities.values().forEach(WindowsCargoLaunchConfiguration::requireDigest);
        if (!cargo.startsWith(toolchainRoot) || !rustc.startsWith(toolchainRoot))
            throw new IllegalArgumentException("Cargo and rustc must be inside the complete toolchain root");
    }

    /**
     * Reads one bounded strict caller document without starting native acquisition.
     * @param scope borrowed invocation parser
     * @param source absolute regular caller configuration file
     * @return immutable represented inputs requiring identity preflight before execution
     * @throws IOException if file bytes, JSON or launch values are invalid
     */
    public static WindowsCargoLaunchConfiguration read(WindowsCargoScope scope, Path source) throws IOException
    {
        return decode(scope, source, readBytes(source));
    }

    /**
     * Decodes exact version-one fields without coercing numeric, path or string-map values.
     * @param scope borrowed bounded duplicate-rejecting parser
     * @param source absolute file represented by the document
     * @param bytes complete bounded document
     * @return immutable launch inputs
     * @throws IOException if fields or semantic values are invalid
     */
    public static WindowsCargoLaunchConfiguration decode(WindowsCargoScope scope, Path source, byte[] bytes) throws IOException
    {
        JsonNode root = document(scope, bytes, FIELDS, Set.of("memory"));
        version(root);
        try
        {
            if (root.has("memory") && !path(root.path("memory"), "toolchainRoot").equals(path(root, "toolchainRoot")))
                throw new IOException("Memory toolchainRoot must equal the pinned toolchain root");
            return new WindowsCargoLaunchConfiguration(source, digest(bytes), path(root, "cargo"), path(root, "rustc"),
                path(root, "toolchainRoot"), strings(root.path("environment")), strings(root.path("identities")),
                root.has("memory") ? memory(root.path("memory")) : null);
        }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid Windows launch configuration: " + invalid.getMessage(), invalid); }
    }

    /**
     * Verifies unchanged configuration, exact tool/library/wrapper bytes and complete toolchain/runtime trees.
     * The runtime tree includes its installed wrapper image; no elevation or foreign process handle is accepted.
     * @param frida supplied instrumentation library
     * @param runtime installed current runtime root
     * @param wrapper installed sibling wrapper executable inside that runtime
     * @throws IOException if an input is missing, changed or outside the installed runtime
     */
    public void requireIdentities(Path frida, Path runtime, Path wrapper) throws IOException
    {
        if (!wrapper.startsWith(runtime)) throw new IOException("Wrapper must be inside the installed runtime");
        if (!digest(readBytes(source)).equals(sourceDigest)) throw new IOException("Windows launch configuration changed after parsing");
        for (Map.Entry<String, Path> entry : Map.of("cargo", cargo, "rustc", rustc, "frida", frida, "wrapper", wrapper).entrySet())
        {
            Path file = entry.getValue();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Sha256.digest(file).equals(identities.get(entry.getKey())))
                throw new IOException("Pinned Windows input identity differs: " + entry.getKey() + " at " + file);
        }
        if (!LinuxCargoLaunchConfiguration.toolchainDigest(toolchainRoot).equals(identities.get("toolchain")))
            throw new IOException("Pinned complete Windows toolchain identity differs");
        if (!LinuxCargoLaunchConfiguration.toolchainDigest(runtime).equals(identities.get("javaRuntime")))
            throw new IOException("Pinned complete installed Windows runtime identity differs");
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
        if (!before.isRegularFile()) throw new IOException("Configuration must be a regular file: " + source);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            bytes = input.readNBytes(WindowsCargoTransport.MAX_FRAME_BYTES + 1);
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || !Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size() ||
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
    private static JsonNode document(WindowsCargoScope scope, byte[] bytes, Set<String> fields, Set<String> optional) throws IOException
    {
        requireBound(bytes);
        try
        {
            JsonNode root = scope.json().readTree(bytes);
            requireFields(root, fields, optional);
            return root;
        }
        catch (JacksonException invalid) { throw new IOException("Invalid bounded Windows configuration JSON", invalid); }
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
        if (bytes.length == 0 || bytes.length > WindowsCargoTransport.MAX_FRAME_BYTES)
            throw new IOException("Configuration document must contain between one byte and one MiB");
    }

    /**
     * Hashes complete configuration bytes.
     * @param bytes complete document
     * @return lowercase SHA256
     */
    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }

    /**
     * Acquires the Java platform's mandatory SHA256 implementation for exact document byte identity.
     * @return fresh independent digest state
     */
    private static MessageDigest sha256()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError("Java requires SHA-256", impossible); }
    }
}
