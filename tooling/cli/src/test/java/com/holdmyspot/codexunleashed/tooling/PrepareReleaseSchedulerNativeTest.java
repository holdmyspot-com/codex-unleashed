package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises native producer guards with independent nonnative fixtures and no native build commands.
 */
public final class PrepareReleaseSchedulerNativeTest
{
    /**
     * Creates a test instance with no shared fixture state.
     */
    public PrepareReleaseSchedulerNativeTest()
    {
    }

    /**
     * Matching declared platforms accept actual JVM architecture aliases.
     */
    @Test
    public void acceptsSupportedMatchingHosts()
    {
        requireThat(PrepareReleaseSchedulerNative.selectPlatform("Linux", "Linux", "amd64"), "linux").isEqualTo("Linux");
        requireThat(PrepareReleaseSchedulerNative.selectPlatform("macOS", "Mac OS X", "aarch64"), "mac").isEqualTo("macOS");
        requireThat(PrepareReleaseSchedulerNative.selectPlatform("Windows", "Windows 11", "AMD64"), "windows").isEqualTo("Windows");
    }

    /**
     * A mismatched native runner fails before creating output or acquiring bytes.
     * @throws Exception if fixture construction or cleanup fails
     */
    @Test
    public void rejectsWrongHostBeforeOutputCreation() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path output = tree.root.resolve("output");
            boolean rejected = false;
            try
            {
                PrepareReleaseSchedulerNative.produce(output, tree.root, "Windows", "Linux", "amd64", tree.root);
            }
            catch (UnsupportedOperationException expected)
            {
                rejected = true;
            }
            requireThat(rejected, "mismatchedHostRejected").isTrue();
            requireThat(Files.exists(output), "outputCreated").isFalse();
        }
    }

    /**
     * An existing output is rejected before native acquisition and its content is preserved.
     * @throws Exception if fixture construction or cleanup fails
     */
    @Test
    public void preservesExistingOutputOnRejection() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path output = Files.createDirectory(tree.root.resolve("output"));
            Path sentinel = Files.writeString(output.resolve("sentinel"), "keep");
            boolean rejected = false;
            try
            {
                PrepareReleaseSchedulerNative.produce(output, tree.root, "Linux", "Linux", "amd64", tree.root);
            }
            catch (IllegalArgumentException expected)
            {
                rejected = true;
            }
            requireThat(rejected, "existingOutputRejected").isTrue();
            requireThat(Files.readString(sentinel), "sentinel").isEqualTo("keep");
        }
    }

    /**
     * Out-of-root and non-normalized output names are rejected before filesystem mutation.
     * @throws Exception if fixture construction or cleanup fails
     */
    @Test
    public void rejectsEscapingOutput() throws Exception
    {
        try (Tree tree = new Tree())
        {
            for (Path output : List.of(Path.of("relative"), tree.root.getParent().resolve("outside-native"),
                tree.root.resolve("child/../alias")))
            {
                boolean rejected = false;
                try
                {
                    PrepareReleaseSchedulerNative.validateOutput(output, tree.root);
                }
                catch (IllegalArgumentException expected)
                {
                    rejected = true;
                }
                requireThat(rejected, "unsafeOutputRejected").isTrue();
            }
        }
    }

    /**
     * Acquisition accepts exactly the byte limit and rejects larger input without writing excess bytes.
     * @throws IOException if the bounded copy unexpectedly fails
     */
    @Test
    public void enforcesAcquisitionBound() throws IOException
    {
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrepareReleaseSchedulerNative.copyBounded(new ByteArrayInputStream(input), output, 3);
        requireThat(output.toString(StandardCharsets.UTF_8), "exactBoundBytes").isEqualTo("abc");
        boolean rejected = false;
        ByteArrayOutputStream overflow = new ByteArrayOutputStream();
        try
        {
            PrepareReleaseSchedulerNative.copyBounded(new ByteArrayInputStream(input), overflow, 2);
        }
        catch (IOException expected)
        {
            rejected = true;
        }
        requireThat(rejected, "oversizedInputRejected").isTrue();
        requireThat(overflow.size(), "writtenBytes").isEqualTo(0);
    }

    /**
     * Authentication accepts known bytes and rejects a different expected identity.
     * @throws Exception if fixture construction or cleanup fails
     */
    @Test
    public void authenticatesExactBytes() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path input = Files.writeString(tree.root.resolve("input"), "abc");
            PrepareReleaseSchedulerNative.authenticate(input,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            boolean rejected = false;
            try
            {
                PrepareReleaseSchedulerNative.authenticate(input, "0".repeat(64));
            }
            catch (IOException expected)
            {
                rejected = true;
            }
            requireThat(rejected, "wrongIdentityRejected").isTrue();
        }
    }

    /**
     * Archive interpretation rejects traversal, normalized duplicate targets, links and ambiguous names.
     * @throws IOException if the safe regular-member listing is rejected
     */
    @Test
    public void rejectsUnsafeParsedMembers() throws IOException
    {
        PrepareReleaseSchedulerNative.validateMembers(List.of("file"), List.of("-rw-r--r-- 0/0 1 date file"));
        for (List<String> names : List.of(List.of("../escape"), List.of("a", "./a"), List.of("/absolute"),
            List.of("with space"), List.of("file")))
        {
            List<String> verbose = names.stream().map(name -> "-rw-r--r-- 0/0 1 date " + name).toList();
            if (names.equals(List.of("file")))
                verbose = List.of("lrwxrwxrwx 0/0 1 date file -> target");
            boolean rejected = false;
            try
            {
                PrepareReleaseSchedulerNative.validateMembers(names, verbose);
            }
            catch (IOException expected)
            {
                rejected = true;
            }
            requireThat(rejected, "unsafeMemberRejected").isTrue();
        }
    }

    /**
     * A harmless root directory in a real TAR passes the production listing validator.
     * The embedded fixture contains only a root directory header and no native product.
     * @throws Exception if fixture creation, validation or cleanup fails
     */
    @Test
    public void acceptsRootDirectoryMetadataThroughRealTar() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path archive = tree.root.resolve("root-directory.tar");
            byte[] compressedFixture = Base64.getDecoder().decode(
                "H4sIAAAAAAACA+3NywmAQBBEwQnFCHQVRuMxBT/57+JRvCoIVZfXt+6HeF1plsyrzb0Pex7LFF3GB879WLd2GQAAAAAAAAAAAPAfFfFs0OUAKAAA");
            try (InputStream fixture = new GZIPInputStream(new ByteArrayInputStream(compressedFixture)))
            {
                Files.copy(fixture, archive);
            }
            PrepareReleaseSchedulerNative.validateArchive(archive, new PrepareReleaseSchedulerNative.Commands(tree.root));
        }
    }

    /**
     * Root metadata accepts directories while rejecting regular files, links and repeated meaningful targets.
     * @throws IOException if harmless directory metadata is rejected
     */
    @Test
    public void rootMetadataPreservesMemberSafety() throws IOException
    {
        PrepareReleaseSchedulerNative.validateMembers(List.of("./", "./file"),
            List.of("drwxr-xr-x 0/0 0 date ./", "-rw-r--r-- 0/0 1 date ./file"));
        PrepareReleaseSchedulerNative.validateMembers(List.of(".", "././"),
            List.of("drwxr-xr-x 0/0 0 date .", "drwxr-xr-x 0/0 0 date ././"));
        for (List<String> details : List.of(
            List.of("-rw-r--r-- 0/0 0 date ./"),
            List.of("lrwxrwxrwx 0/0 0 date ./ -> target"),
            List.of("hrw-r--r-- 0/0 0 date ./ link to target"),
            List.of("drwxr-xr-x 0/0 0 date ./", "-rw-r--r-- 0/0 1 date file", "-rw-r--r-- 0/0 1 date ./file"),
            List.of("drwxr-xr-x 0/0 0 date ./", "-rw-r--r-- 0/0 1 date ../escape")))
        {
            List<String> names = switch (details.size())
            {
                case 1 -> List.of("./");
                case 2 -> List.of("./", "../escape");
                case 3 -> List.of("./", "file", "./file");
                default -> throw new IllegalStateException("Unexpected fixture member count");
            };
            boolean rejected = false;
            try
            {
                PrepareReleaseSchedulerNative.validateMembers(names, details);
            }
            catch (IOException expected)
            {
                rejected = true;
            }
            requireThat(rejected, "unsafeRootOrMemberRejected").isTrue();
        }
    }

    /**
     * The production installed-tar boundary extracts a nonnative fixture after its real listings pass validation.
     * @throws Exception if fixture acquisition, extraction or cleanup fails
     */
    @Test
    public void extractsNonnativeFixtureThroughRealTar() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path archive = tree.root.resolve("fixture.tar");
            try (InputStream fixture = getClass().getResourceAsStream("/safe-fixture.tar"))
            {
                if (fixture == null)
                    throw new IOException("Fixture resource missing");
                Files.copy(fixture, archive);
            }
            Path output = tree.root.resolve("extracted");
            PrepareReleaseSchedulerNative.extract(archive, output, new PrepareReleaseSchedulerNative.Commands(tree.root));
            requireThat(Files.readString(output.resolve("fixture.txt")), "extractedContent")
                .isEqualTo("portable fixture, no native content\n");
        }
    }

    /**
     * Owns the complete mutable filesystem state of one test method.
     */
    private static final class Tree implements AutoCloseable
    {
        /** Exclusive root removed by this fixture's close operation. */
        private final Path root = Files.createTempDirectory("native-producer-test-");

        /**
         * Creates an independent fixture root.
         * @throws IOException if its directory cannot be created
         */
        Tree() throws IOException
        {
        }

        /**
         * Removes this test's owned tree even after a failed assertion.
         * @throws IOException if an owned path cannot be deleted
         */
        @Override
        public void close() throws IOException
        {
            try (Stream<Path> paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }
}
