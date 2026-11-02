package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/**
 * Checks the protected Unix installation before any installed executable receives administrator authority.
 * Run through the independently assembled bundled runtime: {@code codex-tooling validate-release-installation ROOT}.
 * The caller owns the assembled bootstrap runtime; this command checks filesystem authority and grants no privilege.
 */
public final class ValidateReleaseSchedulerInstallation
{
    /**
     * The installation directory name prefix; provisioning appends eight random characters.
     * Keep it short: macOS Cargo socket paths under the installation's scratch directory are limited to 104 bytes.
     */
    public static final String ROOT_PREFIX = "codex-rs-";

    /** Prevents construction of this command. */
    private ValidateReleaseSchedulerInstallation() {}

    /**
     * Rejects a foreign, mutable, linked or unsupported installation before the caller uses its executables.
     * @param arguments one absolute installation directory, or {@code --parent} and its supported namespace
     * @throws IOException if validation or a filesystem observation fails
     */
    public static void main(String[] arguments) throws IOException
    {
        if (arguments.length == 2 && arguments[0].equals("--parent"))
            validateParent(Path.of(arguments[1]));
        else if (arguments.length == 1)
            validate(Path.of(arguments[0]));
        else
            throw new IOException("Expected one installed scheduler directory or --parent with its protected namespace");
    }

    /**
     * Checks the immediate protected namespace, root ownership and every installed executable input.
     * @param root the installation directory, without normalization or symlink substitution
     * @throws IOException if any declared authority or input is unsafe or unreadable
     */
    static void validate(Path root) throws IOException
    {
        if (!root.isAbsolute() || !(Path.of("/var/lib").equals(root.getParent()) || Path.of("/Library").equals(root.getParent())) ||
            !root.getFileName().toString().startsWith(ROOT_PREFIX) || !root.normalize().equals(root))
            throw new IOException("Installation must be an immediate protected /var/lib or /Library scheduler child");
        validateParent(root.getParent());
        for (Path ancestor : List.of(root))
        {
            requireProtected(ancestor, 0);
            if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Installation ancestry must be nonsymlink directories");
        }
        for (String name : List.of("runtime", "toolchain", "native"))
            validateTree(root.resolve(name), 0);
    }

    /**
     * Checks a supported namespace and every ancestor before an administrator creates an installation.
     * @param parent exactly the Linux or macOS protected installation parent
     * @throws IOException if the namespace or any existing ancestor permits foreign replacement
     */
    static void validateParent(Path parent) throws IOException
    {
        if (!List.of(Path.of("/var/lib"), Path.of("/Library")).contains(parent))
            throw new IOException("Installation parent must be /var/lib or /Library");
        for (Path ancestor = parent; ancestor != null; ancestor = ancestor.getParent())
        {
            requireProtected(ancestor, 0);
            if (!Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Installation ancestry must be nonsymlink directories");
        }
    }

    /**
     * Checks one complete tree without following directory links during traversal.
     * @param tree a protected, nonsymlink directory
     * @param expectedOwner the Unix owner required for every directory, file and symbolic link
     * @throws IOException if a link escapes, ownership differs or any input is mutable or unsupported
     */
    static void validateTree(Path tree, int expectedOwner) throws IOException
    {
        if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installed tree must be a nonsymlink directory");
        Path realTree = tree.toRealPath();
        try (var entries = Files.walk(tree))
        {
            for (Path entry : entries.toList())
            {
                Map<String, Object> attributes = Files.readAttributes(entry, "unix:uid,mode",
                    LinkOption.NOFOLLOW_LINKS);
                if (!Integer.valueOf(expectedOwner).equals(attributes.get("uid")))
                    throw new IOException("Installed input must have the declared protected owner: " + entry);
                if (Files.isSymbolicLink(entry))
                {
                    if (!entry.toRealPath().startsWith(realTree))
                        throw new IOException("Installed symlink escapes its immutable tree: " + entry);
                }
                else
                {
                    requireProtected(entry, expectedOwner);
                    BasicFileAttributes type = Files.readAttributes(entry, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                    if (!type.isDirectory() && !type.isRegularFile())
                        throw new IOException("Installed input has an unsupported type: " + entry);
                }
            }
        }
    }

    /**
     * Rejects write authority held by another Unix owner or group.
     * @param entry the existing filesystem object, inspected without following links
     * @param expectedOwner the required numeric Unix owner
     * @throws IOException if owner or mode is unsafe, or observation fails
     */
    private static void requireProtected(Path entry, int expectedOwner) throws IOException
    {
        Map<String, Object> attributes = Files.readAttributes(entry, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS);
        if (!Integer.valueOf(expectedOwner).equals(attributes.get("uid")) ||
            (((Integer) attributes.get("mode")) & 0022) != 0)
            throw new IOException("Installed input must be protected against foreign writes: " + entry);
    }
}
