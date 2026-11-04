package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.PrepareReleaseSchedulerNative;
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
import java.util.Properties;
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
     * Actual receipt construction accepts unchanged products without inspecting application packaging.
     * @throws Exception if fixture creation, validation or cleanup fails
     */
    @Test
    public void reusesNativeInputsAcrossApplicationChanges() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path support = Files.createDirectory(tree.root.resolve("support"));
            Path output = fixtureProducts(tree.root, support, "Linux", "x86_64");
            PrepareReleaseSchedulerNative.validateReuse(output, support, "Linux", "amd64");
            Files.writeString(tree.root.resolve("application.java"), "changed application packaging input");
            PrepareReleaseSchedulerNative.validateReuse(output, support, "Linux", "amd64");
            requireThat(Files.readString(output.resolve("frida")), "retainedProduct").isEqualTo("fixture frida");
        }
    }

    /**
     * Old receipts, changed determining inputs, target mismatches and changed bytes are rejected without deletion.
     * @throws Exception if fixture creation, validation or cleanup fails
     */
    @Test
    public void rejectsInapplicableNativeProducts() throws Exception
    {
        for (String mutation : List.of("old", "producer", "host", "architecture", "product", "pin", "incomplete", "hook",
            "trampoline"))
        {
            try (Tree tree = new Tree())
            {
                Path support = Files.createDirectory(tree.root.resolve("support"));
                Path output = fixtureProducts(tree.root, support, "Linux", "x86_64");
                Path receiptPath = output.resolve("native-receipt.properties.xml");
                Properties receipt = new Properties();
                try (InputStream input = Files.newInputStream(receiptPath)) { receipt.loadFromXML(input); }
                switch (mutation)
                {
                    case "old" -> receipt.remove("applicability.version");
                    case "producer" -> receipt.setProperty("producer.sha256", "0".repeat(64));
                    case "host" -> receipt.setProperty("host", "Windows");
                    case "architecture" -> receipt.setProperty("architecture", "arm64");
                    case "pin" -> receipt.setProperty("frida.archive.sha256", "0".repeat(64));
                    case "incomplete" -> receipt.remove("production");
                    case "product" -> Files.writeString(output.resolve("frida"), "altered");
                    case "hook" -> Files.writeString(output.resolve("libbuildhook.so"), "altered");
                    case "trampoline" -> Files.delete(output.resolve("codex-build-hook-exec"));
                    default -> throw new IllegalStateException(mutation);
                }
                try (var target = Files.newOutputStream(receiptPath)) { receipt.storeToXML(target, "test mutation"); }
                boolean rejected = false;
                try { PrepareReleaseSchedulerNative.validateReuse(output, support, "Linux", "x86_64"); }
                catch (IOException expected) { rejected = true; }
                requireThat(rejected, mutation + "Rejected").isTrue();
                requireThat(Files.isRegularFile(output.resolve("frida")), "recoveryProductRetained").isTrue();
            }
        }
    }

    /**
     * Windows support bytes determine reuse; changing an application sibling does not.
     * @throws Exception if fixture creation, validation or cleanup fails
     */
    @Test
    public void rejectsChangedWindowsSupport() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path support = Files.createDirectory(tree.root.resolve("support"));
            for (String name : List.of("windows-producer.ps1", "exports.txt", "devkit-x64.sha256", "devkit-arm64.sha256"))
                Files.writeString(support.resolve(name), "fixture " + name);
            Path output = fixtureProducts(tree.root, support, "Windows", "arm64");
            PrepareReleaseSchedulerNative.validateReuse(output, support, "Windows", "aarch64");
            Files.writeString(support.resolve("exports.txt"), "changed support");
            boolean rejected = false;
            try { PrepareReleaseSchedulerNative.validateReuse(output, support, "Windows", "arm64"); }
            catch (IOException expected) { rejected = true; }
            requireThat(rejected, "changedSupportRejected").isTrue();
        }
    }

    /**
     * CLI dispatch accepts the actual completed receipt and rejects a different requested target.
     * @throws Exception if fixture creation, dispatch or cleanup fails
     */
    @Test
    public void dispatchesCompletedReuseAndRejectsOtherTarget() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path support = Files.createDirectory(tree.root.resolve("support"));
            Path output = fixtureProducts(tree.root, support, "macOS", "arm64");
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            try (var out = new java.io.PrintStream(new ByteArrayOutputStream());
                var err = new java.io.PrintStream(errors);
                var input = new ByteArrayInputStream(new byte[]{42}))
            {
                String[] arguments = {"validate-release-native", output.toString(), support.toString(), "macOS", "arm64"};
                requireThat(Main.run(arguments, input, out, err), "acceptedReuseStatus").isEqualTo(0);
                arguments[4] = "x86_64";
                requireThat(Main.run(arguments, input, out, err), "wrongRequestedTargetStatus").isEqualTo(1);
                requireThat(errors.toString(), "targetDiagnostic").contains("architecture");
                requireThat(input.read(), "borrowedInput").isEqualTo(42);
                out.print("still usable");
                err.print("still usable");
            }
        }
    }

    /**
     * A Linux receipt pins the official gum devkit and the build hook's sources, so changing either rejects reuse.
     * @throws Exception if the receipt cannot be built or a resource cannot be read
     */
    @Test
    public void pinsBuildHookInputs() throws Exception
    {
        try (Tree tree = new Tree())
        {
            Path support = Files.createDirectory(tree.root.resolve("support"));
            Properties x64 = PrepareReleaseSchedulerNative.inputReceipt("Linux", "x86_64", support);
            requireThat(x64.getProperty("gum.archive.url"), "x64Url").isEqualTo(
                "https://github.com/frida/frida/releases/download/17.24.3/frida-gum-devkit-17.24.3-linux-x86_64.tar.xz");
            requireThat(x64.getProperty("gum.archive.sha256"), "x64Digest").
                isEqualTo("d5ffd59ebc5f50535e5f5e9c04cd968e2876eb91aff40872fe5afaf56d02a886");
            Properties arm = PrepareReleaseSchedulerNative.inputReceipt("Linux", "arm64", support);
            requireThat(arm.getProperty("gum.archive.url"), "armUrl").isEqualTo(
                "https://github.com/frida/frida/releases/download/17.24.3/frida-gum-devkit-17.24.3-linux-arm64.tar.xz");
            requireThat(arm.getProperty("gum.archive.sha256"), "armDigest").
                isEqualTo("e73029dc3552cbcd370fbf251125bec7e4f6c56c3663c28251fa14bec59efb19");
            for (String name : List.of("linux-build-hook.c", "linux-build-hook-exec.c"))
            {
                // The main module does not open its resource package, so the test reads the source from the tree.
                byte[] source = Files.readAllBytes(Path.of(System.getProperty("basedir", ".")).resolve(
                    "src/main/resources/com/holdmyspot/codexunleashed/scheduler/" + name));
                String digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(source));
                requireThat(x64.getProperty("hook." + name + ".sha256"), name + "Digest").isEqualTo(digest);
            }
        }
    }

    /**
     * Constructs interpretation-only product bytes through the actual producer receipt boundaries.
     * @param root exclusively owned fixture root
     * @param support determining support inputs
     * @param platform supported canonical native host
     * @param architecture supported architecture
     * @return completed fixture product directory; bytes are not evidence of native ABI acceptance
     * @throws IOException if fixture bytes or the actual producer receipt cannot be written
     */
    private static Path fixtureProducts(Path root, Path support, String platform, String architecture) throws IOException
    {
        Path output = Files.createDirectory(root.resolve("output"));
        if (platform.equals("Windows")) Files.writeString(output.resolve("frida.dll"), "fixture frida");
        else Files.writeString(output.resolve("frida"), "fixture frida");
        if (platform.equals("Linux"))
        {
            Files.writeString(output.resolve("libcgroup.so"), "fixture libcgroup");
            Files.writeString(output.resolve("libbuildhook.so"), "fixture build hook");
            Files.writeString(output.resolve("codex-build-hook-exec"), "fixture trampoline");
        }
        Properties receipt = PrepareReleaseSchedulerNative.inputReceipt(platform, architecture, support);
        PrepareReleaseSchedulerNative.completeReceipt(output, receipt);
        return output;
    }

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
