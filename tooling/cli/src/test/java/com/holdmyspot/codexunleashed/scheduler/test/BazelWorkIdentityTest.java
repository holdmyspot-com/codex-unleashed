package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.BazelWorkIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises complete ready-input identity through the production identity operation.
 */
public final class BazelWorkIdentityTest
{
    /**
     * Skips this class on file systems without POSIX attributes, which its POSIX-only production code requires.
     *
     * @throws org.testng.SkipException if the default file system lacks the POSIX attribute view
     */
    @org.testng.annotations.BeforeClass
    public void requirePosix()
    {
        TestRequirements.requirePosixFileSystem();
    }

    /**
     * Creates independent test cases with no shared mutable fixtures.
     */
    public BazelWorkIdentityTest() {}

    /**
     * Invocation reuse preserves complete identity and invalidates mutable bytes and replaced readonly inodes.
     * @throws IOException if fixture creation, identification or cleanup fails
     */
    @Test
    public void trustedCachePreservesMutableContentAndReplacementInvalidation() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-trusted-cache-");
        try
        {
            Files.writeString(root.resolve("tool"), "image");
            Path mutable = Files.writeString(root.resolve("mutable"), "first");
            Path immutable = Files.writeString(root.resolve("immutable"), "first");
            Files.setPosixFilePermissions(immutable, PosixFilePermissions.fromString("r--r--r--"));
            var cache = new BazelWorkIdentity.ImmutableFiles();
            String original = cache.identify(root, root, List.of("./tool"), Map.of(), root, "platform");
            requireThat(original, "cachedEqualsCompleteUncachedIdentity").isEqualTo(BazelWorkIdentity.identify(root, root, List.of("./tool"), Map.of(), root, "platform"));
            var modified = Files.getLastModifiedTime(mutable);
            Files.writeString(mutable, "other");
            Files.setLastModifiedTime(mutable, modified);
            String changed = cache.identify(root, root, List.of("./tool"), Map.of(), root, "platform");
            requireThat(changed.equals(original), "mutableSameSizeAndMtimeReused").isFalse();
            Files.move(immutable, root.resolve("old-inode"));
            Files.writeString(immutable, "other");
            Files.setPosixFilePermissions(immutable, PosixFilePermissions.fromString("r--r--r--"));
            Files.delete(root.resolve("old-inode"));
            String replaced = cache.identify(root, root, List.of("./tool"), Map.of(), root, "platform");
            requireThat(replaced.equals(changed), "replacedReadonlyInodeReused").isFalse();
            requireThat(replaced, "replacementMatchesCompleteIdentity").isEqualTo(BazelWorkIdentity.identify(root, root, List.of("./tool"), Map.of(), root, "platform"));
            Path second = Files.createDirectory(root.resolve("second"));
            Files.createLink(second.resolve("immutable"), immutable);
            requireThat(Files.isSameFile(immutable, second.resolve("immutable")), "hardlinkFixture").isTrue();
            requireThat(cache.identify(root, root, List.of("./tool"), Map.of(), root, "platform"),
                "hardlinkCompleteTree").isEqualTo(BazelWorkIdentity.identify(root, root, List.of("./tool"), Map.of(), root, "platform"));
        }
        finally { remove(root); }
    }

    /**
     * Equal commands with changed source or parameter bytes cannot reuse a prediction.
     * @throws IOException if fixture creation, identity or cleanup fails
     */
    @Test
    public void sourceAndParameterBytesInvalidateEqualArgv() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-identity-");
        try
        {
            Files.writeString(root.resolve("tool"), "executable-image");
            Files.writeString(root.resolve("source.rs"), "first-source");
            Files.writeString(root.resolve("arguments.params"), "first-param");
            String original = identify(root);
            Files.writeString(root.resolve("source.rs"), "other-source");
            String changedSource = identify(root);
            requireThat(changedSource.equals(original), "changedSourceReusesKey").isFalse();
            Files.writeString(root.resolve("arguments.params"), "other-param");
            requireThat(identify(root).equals(changedSource), "changedParameterReusesKey").isFalse();
        }
        finally { remove(root); }
    }

    /**
     * Entry names, symlink targets, executable modes and generated executable bytes are part of identity.
     * @throws IOException if fixture operations or identity fails
     */
    @Test
    public void allTreeEntriesAndModesAreBound() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-tree-");
        try
        {
            Path tool = Files.writeString(root.resolve("tool"), "generated-tool-A");
            Files.writeString(root.resolve("a"), "same");
            Files.writeString(root.resolve("b"), "same");
            Files.createSymbolicLink(root.resolve("link"), Path.of("a"));
            String original = identify(root);
            Files.delete(root.resolve("link"));
            Files.createSymbolicLink(root.resolve("link"), Path.of("b"));
            String linkChanged = identify(root);
            requireThat(original.equals(linkChanged), "symlinkTargetCollapsed").isFalse();
            Files.setPosixFilePermissions(tool, PosixFilePermissions.fromString("rwx------"));
            String modeChanged = identify(root);
            requireThat(linkChanged.equals(modeChanged), "executableModeCollapsed").isFalse();
            Files.writeString(tool, "generated-tool-B");
            String imageChanged = identify(root);
            requireThat(modeChanged.equals(imageChanged), "generatedToolCollapsed").isFalse();
            Files.move(root.resolve("a"), root.resolve("renamed"));
            requireThat(imageChanged.equals(identify(root)), "relativeNameCollapsed").isFalse();
        }
        finally { remove(root); }
    }

    /**
     * Exact argument strings and environment values remain distinct, while map iteration order does not matter.
     * @throws IOException if fixture operations or identity fails
     */
    @Test
    public void preservesArgumentAndEnvironmentDistinctions() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-argv-");
        try
        {
            Files.writeString(root.resolve("tool"), "image");
            Map<String, String> first = new LinkedHashMap<>();
            first.put("A", "x");
            first.put("B", "y");
            Map<String, String> reverse = new LinkedHashMap<>();
            reverse.put("B", "y");
            reverse.put("A", "x");
            String key = BazelWorkIdentity.identify(root, List.of("./tool", "a/../b"), first, root, "platform");
            requireThat(BazelWorkIdentity.identify(root, List.of("./tool", "a/../b"), reverse, root,
                "platform"), "environmentOrderIndependent").isEqualTo(key);
            requireThat(BazelWorkIdentity.identify(root, List.of("./tool", "b"), first, root,
                "platform").equals(key), "meaningfulArgumentNormalized").isFalse();
            requireThat(BazelWorkIdentity.identify(root, List.of("./tool", "a/../b"), Map.of("A", "changed", "B", "y"),
                root, "platform").equals(key), "environmentValueCollapsed").isFalse();
            requireThat(BazelWorkIdentity.identify(root, List.of("./tool", "a/../b"), first, root,
                "other-platform").equals(key), "platformCollapsed").isFalse();
        }
        finally { remove(root); }
    }

    /**
     * The explicit mapping resolves generated absolute tools in the immutable copy without rewriting command text.
     * @throws IOException if fixture operations or identity fails
     */
    @Test
    public void mapsVisibleRootOnlyForFilesystemLookup() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-mapping-");
        try
        {
            Files.writeString(root.resolve("tool"), "generated");
            Path visible = root.resolve("unmaterialized-worker-root");
            List<String> argv = List.of(visible.resolve("tool").toString(), "--path=" + visible.resolve("source"));
            String key = BazelWorkIdentity.identify(root, visible, argv, Map.of(), visible, "platform");
            Files.writeString(root.resolve("tool"), "different");
            requireThat(BazelWorkIdentity.identify(root, visible, argv, Map.of(), visible,
                "platform").equals(key), "mappedGeneratedImageCollapsed").isFalse();
            requireThat(argv.getFirst(), "originalArgv0").isEqualTo(visible.resolve("tool").toString());
        }
        finally { remove(root); }
    }

    /**
     * External shebang interpreter bytes distinguish otherwise identical script actions.
     * @throws IOException if fixture operations or identity fails
     */
    @Test
    public void bindsExternalInterpreter() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-interpreter-");
        try
        {
            Path inputs = Files.createDirectory(root.resolve("inputs"));
            Path interpreter = Files.writeString(root.resolve("interpreter"), "image-A");
            Files.writeString(inputs.resolve("tool"), "#!" + interpreter + "\nbody");
            String key = identify(inputs);
            Files.writeString(interpreter, "image-B");
            requireThat(identify(inputs).equals(key), "interpreterCollapsed").isFalse();
        }
        finally { remove(root); }
    }

    /**
     * Escaping input symlinks fail closed before a reusable prediction is created.
     * @throws IOException if fixture setup or cleanup fails
     */
    @Test
    public void rejectsEscapingSymlink() throws IOException
    {
        Path root = Files.createTempDirectory("bazel-escape-");
        try
        {
            Files.writeString(root.resolve("tool"), "image");
            Files.createSymbolicLink(root.resolve("escape"), Path.of("../outside"));
            IOException failure = null;
            try { identify(root); }
            catch (IOException expected) { failure = expected; }
            requireThat(failure != null, "escapingSymlinkRejected").isTrue();
        }
        finally { remove(root); }
    }

    /**
     * Identifies one controlled generated command with its entire input tree.
     * @param root immutable tree for one observation
     * @return reusable identity
     * @throws IOException if complete identity cannot be observed
     */
    private static String identify(Path root) throws IOException
    {
        return BazelWorkIdentity.identify(root, List.of("./tool", "@arguments.params"), Map.of(), root, "test-toolchain");
    }

    /**
     * Deletes only the fixture tree owned by its calling test, without following symlinks.
     * @param root fixture root
     * @throws IOException if fixture cleanup fails
     */
    private static void remove(Path root) throws IOException
    {
        try (Stream<Path> entries = Files.walk(root))
        {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
