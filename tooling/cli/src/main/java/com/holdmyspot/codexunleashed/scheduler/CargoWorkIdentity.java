package com.holdmyspot.codexunleashed.scheduler;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Describes compiler work for memory estimates: a key that stays the same across source edits, and the size of the
 * inputs the work reads.
 * <p>
 * A crate's peak memory changes little between nearby commits, so the key names the crate and the options that change
 * how much work the compiler does, and omits source contents, paths, hashes and the environment. Instances hold no
 * state and are safe to share between threads.
 */
public final class CargoWorkIdentity
{
    /** Options whose value names the crate or changes how much work the compiler does. */
    private static final Set<String> VALUE_OPTIONS = Set.of("--crate-name", "--crate-type", "--edition", "--target",
        "--emit", "--cfg");
    /** Codegen settings, given with {@code -C} or {@code --codegen}, that change how much work the compiler does. */
    private static final Set<String> CODEGEN_SETTINGS = Set.of("opt-level", "debuginfo", "codegen-units", "lto",
        "panic", "embed-bitcode", "strip");
    /** Version of the key's encoding; a change makes earlier history inapplicable. */
    private static final int FORMAT = 2;

    /**
     * Creates a stateless identity source.
     */
    public CargoWorkIdentity() {}

    /**
     * Returns the key under which this work's memory history is recorded. With a {@code --crate-name} option, the key
     * covers the compiler's file name, the crate's name, type, edition, target, emitted outputs and configuration
     * values, the codegen settings in {@link #CODEGEN_SETTINGS} and {@code --test}, in the order given. Without one,
     * as for a compiler probe, it covers the compiler's file name and every later argument.
     *
     * @param request authenticated compiler inputs
     * @return lowercase SHA-256 text
     * @throws NullPointerException if {@code request} is null
     */
    public String identify(CargoCompilerRequest request)
    {
        Objects.requireNonNull(request, "request");
        List<String> arguments = request.arguments();
        List<String> key = new ArrayList<>();
        key.add(fileName(arguments.getFirst()));
        List<String> options = arguments.subList(1, arguments.size());
        if (options.contains("--crate-name"))
            key.addAll(selectOptions(options));
        else
            key.addAll(options);

        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new ExceptionInInitializerError(failure); }
        try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)))
        {
            output.writeInt(FORMAT);
            output.writeInt(key.size());
            for (String part : key) output.writeUTF(part);
        }
        catch (IOException failure)
        {
            // A digest stream over a null stream does not fail.
            throw new AssertionError(failure);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns the size of the inputs the compiler reads, as a basis for estimating work without history. It sums the
     * {@code .rs} files below the directory of the crate's root file (the first argument that ends in {@code .rs} and
     * does not start with {@code -}), skipping subdirectories that hold a {@code Cargo.toml} because they are other
     * packages; every regular file named by {@code --extern name=path}; and the {@code .rs} files below the
     * {@code OUT_DIR} directory, which hold generated code. Missing or unreadable files count as empty, and symbolic
     * links are not followed.
     *
     * @param request authenticated compiler inputs
     * @return the input size in bytes, zero when none of these inputs exist
     * @throws NullPointerException if {@code request} is null
     */
    public long inputBytes(CargoCompilerRequest request)
    {
        Objects.requireNonNull(request, "request");
        List<String> options = request.arguments().subList(1, request.arguments().size());
        long total = 0;
        for (String option : options)
        {
            if (option.startsWith("-") || !option.endsWith(".rs"))
                continue;
            Path root = resolve(request.directory(), option);
            if (root != null && root.getParent() != null)
                total += rustSources(root.getParent());
            break;
        }
        for (int index = 0; index + 1 < options.size(); ++index)
        {
            if (!options.get(index).equals("--extern"))
                continue;
            String value = options.get(index + 1);
            int separator = value.indexOf('=');
            if (separator < 0)
                continue;
            Path file = resolve(request.directory(), value.substring(separator + 1));
            if (file != null)
                total += size(file);
        }
        String generated = request.environment().get("OUT_DIR");
        if (generated != null)
        {
            Path root = resolve(request.directory(), generated);
            if (root != null)
                total += rustSources(root);
        }
        return total;
    }

    /**
     * Selects the options that name the crate or change how much work the compiler does.
     *
     * @param options compiler arguments after the executable
     * @return each selected option as {@code name=value}, in the order given
     */
    private static List<String> selectOptions(List<String> options)
    {
        List<String> selected = new ArrayList<>();
        for (int index = 0; index < options.size(); ++index)
        {
            String option = options.get(index);
            if (option.equals("--test"))
            {
                selected.add(option);
                continue;
            }
            String name = option;
            String value = null;
            int separator = option.indexOf('=');
            if (option.startsWith("--") && separator > 0)
            {
                name = option.substring(0, separator);
                value = option.substring(separator + 1);
            }
            else if (option.startsWith("-C") && option.length() > 2)
            {
                name = "-C";
                value = option.substring(2);
            }
            if (name.equals("--codegen"))
                name = "-C";
            if (!name.equals("-C") && !VALUE_OPTIONS.contains(name))
                continue;
            if (value == null)
            {
                if (index + 1 >= options.size())
                    continue;
                ++index;
                value = options.get(index);
            }
            if (name.equals("-C"))
            {
                int assignment = value.indexOf('=');
                String setting = assignment < 0 ? value : value.substring(0, assignment);
                if (!CODEGEN_SETTINGS.contains(setting))
                    continue;
            }
            selected.add(name + "=" + value);
        }
        return selected;
    }

    /**
     * Returns the last name element of a compiler path.
     *
     * @param compiler the compiler path text
     * @return the file name, or the whole text if it is not path syntax
     */
    private static String fileName(String compiler)
    {
        try
        {
            Path name = Path.of(compiler).getFileName();
            if (name == null)
                return compiler;
            return name.toString();
        }
        catch (InvalidPathException _)
        {
            return compiler;
        }
    }

    /**
     * Resolves argument text against the compiler's working directory.
     *
     * @param directory the working directory
     * @param value the argument text
     * @return the normalized path, or null if the text is not path syntax
     */
    private static Path resolve(Path directory, String value)
    {
        try
        {
            return directory.resolve(value).normalize();
        }
        catch (InvalidPathException _)
        {
            return null;
        }
    }

    /**
     * Returns the size of a regular file.
     *
     * @param file the file
     * @return its size, or zero if it is missing, unreadable or not a regular file
     */
    private static long size(Path file)
    {
        try
        {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                return 0;
            return Files.size(file);
        }
        catch (IOException _)
        {
            return 0;
        }
    }

    /**
     * Sums the {@code .rs} files below a directory, skipping nested packages and unreadable entries.
     *
     * @param root the directory
     * @return the total size, or zero if the directory is missing
     */
    private static long rustSources(Path root)
    {
        long[] total = {0};
        try
        {
            Files.walkFileTree(root, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                {
                    if (!directory.equals(root) && Files.exists(directory.resolve("Cargo.toml"), LinkOption.NOFOLLOW_LINKS))
                        return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                {
                    if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".rs"))
                        total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure)
                {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        catch (IOException _)
        {
            // The visitor continues past entry failures; only an unreadable root reaches here, and it counts as empty.
        }
        return total[0];
    }
}
