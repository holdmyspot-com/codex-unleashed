package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import org.testng.annotations.Test;
import org.testng.SkipException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises actual filesystem authority observations without administrator writes or native execution. */
public final class ValidateReleaseSchedulerInstallationTest
{
    /** Creates independent method-local tests. */
    public ValidateReleaseSchedulerInstallationTest() {}

    /**
     * A protected owned tree accepts regular files and internal links.
     * @throws IOException if fixture operations fail
     */
    @Test public void acceptsProtectedTreeAndInternalLink() throws IOException
    {
        try (Tree tree = new Tree())
        {
            Files.createSymbolicLink(tree.root.resolve("alias"), Path.of("file"));
            ValidateReleaseSchedulerInstallation.validateTree(tree.root, tree.owner);
        }
    }

    /**
     * A real group-writable file is rejected before any consumer executes it.
     * @throws IOException if fixture operations fail
     */
    @Test public void rejectsMutableFile() throws IOException
    {
        try (Tree tree = new Tree())
        {
            Files.setPosixFilePermissions(tree.root.resolve("file"), PosixFilePermissions.fromString("rw-rw-r--"));
            rejected(() -> ValidateReleaseSchedulerInstallation.validateTree(tree.root, tree.owner));
        }
    }

    /**
     * An internal pathname pointing to a foreign tree is rejected.
     * @throws IOException if fixture operations fail
     */
    @Test public void rejectsEscapingLink() throws IOException
    {
        try (Tree tree = new Tree())
        {
            Files.createSymbolicLink(tree.root.resolve("escape"), tree.root.getParent());
            rejected(() -> ValidateReleaseSchedulerInstallation.validateTree(tree.root, tree.owner));
        }
    }

    /**
     * A symlink cannot replace the tree whose contents grant execution authority.
     * @throws IOException if fixture operations fail
     */
    @Test public void rejectsLinkedTree() throws IOException
    {
        try (Tree tree = new Tree())
        {
            Path link = tree.root.resolve("alias");
            Files.createSymbolicLink(link, tree.root);
            rejected(() -> ValidateReleaseSchedulerInstallation.validateTree(link, tree.owner));
        }
    }

    /**
     * Root selection and numeric ownership are checked independently of tree content.
     * @throws IOException if fixture operations fail
     */
    @Test public void rejectsForeignNamespaceAndOwner() throws IOException
    {
        try (Tree tree = new Tree())
        {
            rejected(() -> ValidateReleaseSchedulerInstallation.validate(tree.root));
            rejected(() -> ValidateReleaseSchedulerInstallation.validateTree(tree.root, tree.owner + 1));
        }
    }

    /**
     * A foreign namespace is rejected on every platform before Unix attributes are inspected.
     * @throws IOException if independent temporary directory cleanup fails
     */
    @Test public void rejectsForeignNamespaceOnEveryPlatform() throws IOException
    {
        Path root = Files.createTempDirectory("foreign-installation-");
        try { rejected(() -> ValidateReleaseSchedulerInstallation.validate(root)); }
        finally { Files.delete(root); }
    }

    /**
     * Public parent preflight accepts the real protected platform namespace before installation writes.
     * @throws IOException if the host's declared filesystem attributes cannot be observed
     */
    @Test public void publicParentPreflightAcceptsProtectedHostNamespace() throws IOException
    {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
            throw new SkipException("Protected Unix parent observation requires Unix ownership attributes");
        Path parent = Path.of(System.getProperty("os.name").startsWith("Mac") ? "/Library" : "/var/lib");
        for (Path ancestor = parent; ancestor != null; ancestor = ancestor.getParent())
        {
            if (!Files.isDirectory(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new SkipException("Host does not provide the protected platform namespace fixture");
            var attributes = Files.readAttributes(ancestor, "unix:uid,mode", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!Integer.valueOf(0).equals(attributes.get("uid")) || (((Integer) attributes.get("mode")) & 0022) != 0)
                throw new SkipException("Host namespace fixture permits foreign writes and must remain rejected");
        }
        try (var output = new java.io.PrintStream(new java.io.ByteArrayOutputStream()))
        {
            requireThat(Main.run(new String[]{"validate-release-installation", "--parent", parent.toString()},
                java.io.InputStream.nullInputStream(), output, output), "protectedHostParentStatus").isEqualTo(0);
        }
    }

    /** Arbitrary parent paths cannot request administrator installation authority on any platform. */
    @Test public void publicParentPreflightRejectsForeignNamespace()
    {
        try (var output = new java.io.PrintStream(new java.io.ByteArrayOutputStream()))
        {
            requireThat(Main.run(new String[]{"validate-release-installation", "--parent", "/opt"},
                java.io.InputStream.nullInputStream(), output, output) != 0, "foreignParentRejected").isTrue();
        }
    }

    /**
     * Requires a real guard failure rather than accepting a silent success.
     * @param operation the filesystem observation to exercise
     * @throws IOException if an unrelated fixture operation fails
     */
    private static void rejected(Operation operation) throws IOException
    {
        boolean failed = false;
        try { operation.run(); }
        catch (IOException expected) { failed = true; }
        requireThat(failed, "unsafeInputRejected").isTrue();
    }

    /** One filesystem validation that may reject its input. */
    @FunctionalInterface private interface Operation
    {
        /**
         * Performs the filesystem guard operation.
         * @throws IOException if input authority is unsafe
         */
        void run() throws IOException;
    }

    /** Owns all mutable fixture state for one test method. */
    private static final class Tree implements AutoCloseable
    {
        /** The exclusive mutable tree. */
        private final Path root;
        /** The actual owner observed from the filesystem. */
        private final int owner;

        /**
         * Creates a protected independent tree owned by the current test process.
         * @throws IOException if fixture construction fails
         */
        private Tree() throws IOException
        {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix") ||
                !java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                throw new SkipException("Unix installation authority requires Unix ownership and POSIX permissions");
            root = Files.createTempDirectory("installation-authority-");
            owner = (Integer) Files.getAttribute(root, "unix:uid");
            Files.writeString(root.resolve("file"), "owned input");
            Files.setPosixFilePermissions(root.resolve("file"), PosixFilePermissions.fromString("rw-r--r--"));
        }

        /** @throws IOException if owned fixture removal fails */
        @Override public void close() throws IOException
        {
            try (var entries = Files.walk(root))
            {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(entry);
            }
        }
    }
}
