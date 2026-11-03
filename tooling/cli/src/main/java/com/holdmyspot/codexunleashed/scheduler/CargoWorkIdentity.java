package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.secrets.CargoWorkIdentitySecrets;
import java.io.IOException;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Derives reusable compiler-work identities from authenticated launch inputs and caller-pinned content roots.
 * Source inputs must remain in the declared input root; output roots contain generated compiler outputs only.
 * Standard-input compilation is inapplicable because its source bytes are not captured by authenticated registration.
 * Toolchain contents and configuration distinguish reusable work; native action UUIDs and births do not.
 */
public final class CargoWorkIdentity
{
	static
	{
		CargoWorkIdentitySecrets.setCargoWorkIdentityAccess(CargoWorkIdentity::new);
	}

    /** Complete caller-declared source and configuration input tree. */
    private final Path inputRoot;
    /** Compiler output trees excluded from the source-content identity. */
    private final List<Path> outputRoots;
    /** Caller-pinned compiler and sysroot content tree. */
    private final Path toolchainRoot;
    /** Resolves an argument value against the compiler's working directory. */
    private final Resolver resolver;

    /**
     * Retains explicit content boundaries without acquiring resources.
     * @param inputRoot absolute normalized source/configuration root
     * @param outputRoots absolute normalized generated output trees, strictly below the input root
     * @param toolchainRoot absolute normalized immutable compiler/sysroot root
     * @throws NullPointerException if a required value or collection member is null
     * @throws IllegalArgumentException if a path is relative, not normalized or an output is outside the input root
     */
    public CargoWorkIdentity(Path inputRoot, List<Path> outputRoots, Path toolchainRoot)
    {
        this(inputRoot, outputRoots, toolchainRoot, Path::resolve);
    }

    /**
     * Retains explicit content boundaries and the argument resolver.
     * @param inputRoot absolute normalized source/configuration root
     * @param outputRoots absolute normalized generated output trees, strictly below the input root
     * @param toolchainRoot absolute normalized immutable compiler/sysroot root
     * @param resolver resolves an argument value against the compiler's working directory
     * @throws NullPointerException if a required value or collection member is null
     * @throws IllegalArgumentException if a path is relative, not normalized or an output is outside the input root
     */
    private CargoWorkIdentity(Path inputRoot, List<Path> outputRoots, Path toolchainRoot, Resolver resolver)
    {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.inputRoot = absolute(inputRoot);
        this.toolchainRoot = absolute(toolchainRoot);
        this.outputRoots = List.copyOf(outputRoots);
        for (Path outputRoot : outputRoots)
        {
            absolute(outputRoot);
            if (outputRoot.equals(inputRoot) || !outputRoot.startsWith(inputRoot))
                throw new IllegalArgumentException("Generated output roots must be strictly below the input root");
        }
    }

    /**
     * Identifies equivalent authenticated compiler work without granting execution authority.
     * @param request already authenticated compiler inputs
     * @return lowercase SHA-256 identity for the declared inputs
     * @throws IOException if complete stable content cannot be read or work is outside the declared roots
     */
    public String identify(CargoCompilerRequest request) throws IOException
    {
        Objects.requireNonNull(request, "request");
        if (request.arguments().contains("-"))
            throw new IOException("Compiler standard-input source is not captured for reusable work identity");
        Path compiler = Path.of(request.arguments().getFirst()).normalize();
        if (!request.directory().normalize().startsWith(inputRoot) || !compiler.startsWith(toolchainRoot))
            throw new IOException("Compiler work lies outside its declared source or toolchain root");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new ExceptionInInitializerError(failure); }
        try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)))
        {
            output.writeInt(1);
            output.writeUTF(request.directory().toString());
            output.writeInt(request.arguments().size());
            for (String argument : request.arguments()) output.writeUTF(argument);
            Map<String, String> environment = new TreeMap<>(request.environment());
            // Jobserver capabilities and wrapper controller authority change between equivalent invocations.
            // Registration authenticates their complete values before this work-only identity is computed.
            environment.keySet().removeAll(List.of("CARGO_MAKEFLAGS", "MAKEFLAGS", "MFLAGS",
                "CODEX_LINUX_CARGO_WRAPPER_CONFIG", "CODEX_CARGO_SOCKET", "CODEX_CARGO_PIPE",
                "CODEX_CARGO_CONTROLLER_PID", "CODEX_CARGO_CONTROLLER_BIRTH"));
            output.writeInt(environment.size());
            for (Map.Entry<String, String> entry : environment.entrySet())
            {
                output.writeUTF(entry.getKey());
                output.writeUTF(entry.getValue());
            }
            writeTree(output, inputRoot, outputRoots);
            writeTree(output, toolchainRoot, List.of());
            for (String argument : request.arguments())
            {
                int separator = argument.indexOf('=');
                String value = separator < 0 ? argument : argument.substring(separator + 1);
                Path candidate;
                try
                {
                    candidate = resolver.resolve(request.directory(), value).normalize();
                }
                catch (InvalidPathException ignored)
                {
                    // Exact option text was already hashed; only this optional file lookup is inapplicable.
                    continue;
                }
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) writeFile(output, candidate);
            }
            String generatedInputs = request.environment().get("OUT_DIR");
            if (generatedInputs != null) writeTree(output, Path.of(generatedInputs), List.of());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Writes complete stable content and relative names, excluding only declared generated output trees.
     * Symlinks are inapplicable because their targets can escape the declared content boundary.
     * @param output framed identity sink
     * @param root complete content root
     * @param excluded generated directories omitted from source observations
     * @throws IOException if a root, entry or stable complete file observation is unavailable
     */
    private static void writeTree(DataOutputStream output, Path root, List<Path> excluded) throws IOException
    {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Compiler content root is unavailable: " + root);
        List<Path> entries = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
            {
                if (excluded.stream().anyMatch(directory::startsWith)) return FileVisitResult.SKIP_SUBTREE;
                entries.add(directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                if (!attributes.isRegularFile()) throw new IOException("Compiler input is not a regular file: " + file);
                entries.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        entries.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        output.writeUTF(root.toString());
        output.writeInt(entries.size());
        for (Path entry : entries)
        {
            output.writeUTF(root.relativize(entry).toString());
            boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            output.writeBoolean(directory);
            if (!directory) writeFile(output, entry);
        }
    }

    /**
     * Writes one file's bytes and rejects replacement or modification during the read.
     * @param output framed content sink
     * @param file existing regular compiler input
     * @throws IOException if the file cannot be read completely and consistently
     */
    private static void writeFile(DataOutputStream output, Path file) throws IOException
    {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IOException("Compiler input is not a regular file: " + file);
        output.writeUTF(file.toString());
        output.writeLong(before.size());
        long copied;
        try (InputStream input = Files.newInputStream(file)) { copied = input.transferTo(output); }
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (copied != before.size() || before.size() != after.size() ||
            !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Compiler input changed during content observation: " + file);
    }

    /**
     * Validates a caller-owned root before retaining it.
     * @param path root to validate
     * @return unchanged path
     * @throws NullPointerException if {@code path} is null
     * @throws IllegalArgumentException if {@code path} is relative or not normalized
     */
    private static Path absolute(Path path)
    {
        Objects.requireNonNull(path, "path");
        if (!path.isAbsolute() || !path.equals(path.normalize()))
            throw new IllegalArgumentException("Compiler identity roots must be absolute and normalized");
        return path;
    }

    /** Resolves an argument value against a working directory. */
    @FunctionalInterface
    public interface Resolver
    {
        /**
         * Resolves a value.
         * @param directory the compiler's working directory
         * @param value argument text
         * @return the resolved path
         * @throws InvalidPathException if the value is not path syntax on this file system
         */
        Path resolve(Path directory, String value);
    }
}
