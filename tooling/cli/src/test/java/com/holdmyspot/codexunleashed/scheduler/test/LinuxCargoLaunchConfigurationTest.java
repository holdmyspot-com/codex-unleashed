package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoLaunchConfiguration;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises strict administrator configuration and immutable wrapper context without native acquisition.
 */
public final class LinuxCargoLaunchConfigurationTest
{
    /**
     * Creates independent configuration checks.
     */
    public LinuxCargoLaunchConfigurationTest() {}

    /**
     * Administrator decoding retains explicitly authorized credentials, environment and unknown demand.
     * @throws IOException if accepted configuration cannot be decoded
     */
    @Test
    public void retainsExplicitAuthorityInputs() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            LinuxCargoLaunchConfiguration configuration = decode(scope, configuration(scope));
            requireThat(configuration.uid(), "uid").isEqualTo(1001);
            requireThat(configuration.gid(), "gid").isEqualTo(1002);
            requireThat(configuration.environment().equals(Map.of("TEXT", "white space\n$literal")), "environment").isTrue();
            requireThat(configuration.memory() == null, "unknownDemand").isTrue();
            requireThat(configuration.cargo().equals(path("/tools/cargo")), "cargo").isTrue();
        }
    }

    /**
     * The decoder rejects fractional versions, wrong types, missing and unknown fields and nonempty groups.
     * @throws IOException if baseline serialization fails
     */
    @Test
    public void rejectsInvalidAuthorityFields() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            String baseline = new String(configuration(scope), StandardCharsets.UTF_8);
            for (String invalid : List.of(baseline.replace("\"version\":1", "\"version\":1.0"),
                baseline.replace("\"version\":1", "\"version\":2"),
                baseline.replace("\"uid\":1001", "\"uid\":0"),
                baseline.replace("\"supplementaryGroups\":[]", "\"supplementaryGroups\":[3]"),
                baseline.replace("\"supplementaryGroups\":[]", "\"supplementaryGroups\":null"),
                baseline.replace(scope.json().writeValueAsString(path("/tools/cargo").toString()), "\"relative\""),
                baseline.replace("\"version\":1", "\"unrecognized\":1"),
                baseline.substring(0, baseline.length() - 1) + ",\"extra\":true}"))
                requireException(IOException.class, () -> decode(scope, invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Duplicate members, trailing documents and explicit null memory never become accepted defaults.
     * @throws IOException if baseline serialization fails
     */
    @Test
    public void rejectsAmbiguousDocuments() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            String baseline = new String(configuration(scope), StandardCharsets.UTF_8);
            for (String invalid : List.of(baseline.replace("\"version\":1", "\"version\":1,\"version\":1"),
                baseline + " {}", baseline.substring(0, baseline.length() - 1) + ",\"memory\":null}", "{]"))
                requireException(IOException.class, () -> decode(scope, invalid.getBytes(StandardCharsets.UTF_8)));
            requireException(IOException.class, () -> decode(scope, new byte[0]));
            requireException(IOException.class, () -> decode(scope, new byte[1048577]));
        }
    }

    /**
     * Invocation-owned environment variables and malformed pinned digests are rejected before native work.
     * @throws IOException if baseline serialization fails
     */
    @Test
    public void rejectsPrivilegeSelectingEnvironmentAndDigest() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            String baseline = new String(configuration(scope), StandardCharsets.UTF_8);
            requireException(IOException.class, () -> decode(scope, baseline.replace("\"TEXT\":", "\"RUSTC\":").getBytes(StandardCharsets.UTF_8)));
            requireException(IOException.class, () -> decode(scope, baseline.replace("0".repeat(64), "ABC").getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Complete memory boundaries enable explicit history while mismatching toolchains are rejected.
     * @throws IOException if baseline serialization or accepted decoding fails
     */
    @Test
    public void acceptsOnlyCompleteMatchingMemorySettings() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            String baseline = new String(configuration(scope), StandardCharsets.UTF_8);
            Map<String, Object> memory = Map.of("historyDirectory", path("/history").toString(),
                "inputRoot", path("/source").toString(), "outputRoots", List.of(path("/source/target").toString()),
                "toolchainRoot", path("/tools").toString());
            String complete = baseline.substring(0, baseline.length() - 1) + ",\"memory\":" +
                scope.json().writeValueAsString(memory) + "}";
            requireThat(decode(scope, complete.getBytes(StandardCharsets.UTF_8)).memory() == null,
                "explicitMemoryAbsent").isFalse();
            Map<String, Object> other = new java.util.HashMap<>(memory);
            other.put("toolchainRoot", path("/other-tools").toString());
            String mismatched = baseline.substring(0, baseline.length() - 1) + ",\"memory\":" +
                scope.json().writeValueAsString(other) + "}";
            byte[] rejected = mismatched.getBytes(StandardCharsets.UTF_8);
            requireException(IOException.class, () -> decode(scope, rejected));
        }
    }

    /**
     * The wrapper configuration is the exact three-line version 2 document the native wrapper parses.
     */
    @Test
    public void encodesVersionTwoWrapperConfiguration()
    {
        requireThat(new String(LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c"), 3000),
            StandardCharsets.US_ASCII), "configuration").isEqualTo("version=2\ncontroller=/run/c\ntimeout_millis=3000\n");
        requireThat(new String(LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c"),
            LinuxCargoLaunchConfiguration.WRAPPER_TIMEOUT_MILLIS), StandardCharsets.US_ASCII), "largest")
            .isEqualTo("version=2\ncontroller=/run/c\ntimeout_millis=" + Long.MAX_VALUE / 4 + "\n");
    }

    /**
     * Values the native parser cannot represent are rejected before any file is created.
     */
    @Test
    public void rejectsUnrepresentableWrapperConfiguration()
    {
        requireException(IllegalArgumentException.class, () -> LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("run/c"), 1));
        requireException(IllegalArgumentException.class, () -> LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c\nx"), 1));
        requireException(IllegalArgumentException.class, () -> LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/\u00e9"), 1));
        requireException(IllegalArgumentException.class, () -> LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c"), 0));
        requireException(IllegalArgumentException.class, () -> LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c"),
            Long.MAX_VALUE / 4 + 1));
    }

    /**
     * Publication creates a new world-readable, read-only file holding the exact encoding and never replaces one.
     * @throws IOException if test-local file creation, reading or removal fails
     */
    @Test
    public void publishesReadOnlyWrapperConfiguration() throws IOException
    {
        TestRequirements.requirePosixFileSystem();
        Path directory = Files.createTempDirectory("wrapper-config-");
        Path source = directory.resolve("w");
        try
        {
            LinuxCargoLaunchConfiguration.writeWrapper(source, Path.of("/run/c"), 5);
            requireThat(Files.readAllBytes(source), "bytes").isEqualTo(
                LinuxCargoLaunchConfiguration.encodeWrapper(Path.of("/run/c"), 5));
            requireThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(source)),
                "permissions").isEqualTo("r--r--r--");
            requireException(java.nio.file.FileAlreadyExistsException.class,
                () -> LinuxCargoLaunchConfiguration.writeWrapper(source, Path.of("/run/c"), 5));
        }
        finally
        {
            Files.deleteIfExists(source);
            Files.delete(directory);
        }
    }

    /**
     * Tree hashing includes relative path and complete file bytes and ignores empty directories.
     * @throws Exception if isolated file creation or hashing fails
     */
    @Test
    public void hashesCompleteToolchainContent() throws Exception
    {
        Path root = Files.createTempDirectory("toolchain-digest-");
        try
        {
            Path file = root.resolve("compiler");
            Files.writeString(file, "compiler bytes", StandardCharsets.UTF_8);
            String fileHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("compiler bytes".getBytes(StandardCharsets.UTF_8)));
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("compiler\0file\0" + fileHash + "\n").getBytes(StandardCharsets.UTF_8)));
            requireThat(LinuxCargoLaunchConfiguration.toolchainDigest(root), "exactRows").isEqualTo(expected);
            Files.createDirectory(root.resolve("empty"));
            requireThat(LinuxCargoLaunchConfiguration.toolchainDigest(root), "emptyDirectory").isEqualTo(expected);
            Files.writeString(file, "changed", StandardCharsets.UTF_8);
            requireThat(LinuxCargoLaunchConfiguration.toolchainDigest(root).equals(expected), "changedBytes").isFalse();
        }
        finally
        {
            try (var entries = Files.walk(root))
            {
                for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(entry);
            }
        }
    }

    /**
     * A file cannot stand in for the complete toolchain directory.
     * @throws IOException if test-local file creation or removal fails
     */
    @Test
    public void rejectsToolchainFileRoot() throws IOException
    {
        Path file = Files.createTempFile("toolchain-file-", ".txt");
        try { requireException(IOException.class, () -> LinuxCargoLaunchConfiguration.toolchainDigest(file)); }
        finally { Files.delete(file); }
    }

    /**
     * Produces an accepted administrator document through existing scoped serialization.
     * @param scope caller-owned JSON services
     * @return complete version-one configuration document
     * @throws IOException if serialization fails
     */
    private static byte[] configuration(LinuxCargoScope scope) throws IOException
    {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("version", 1); values.put("uid", 1001); values.put("gid", 1002);
        values.put("supplementaryGroups", List.of()); values.put("cargo", path("/tools/cargo").toString());
        values.put("rustc", path("/tools/rustc").toString()); values.put("toolchainRoot", path("/tools").toString());
        values.put("environment", Map.of("TEXT", "white space\n$literal"));
        values.put("unshare", path("/usr/bin/unshare").toString()); values.put("libcgroup", path("/lib/libcgroup.so").toString());
        values.put("cgroupRoot", path("/sys/fs/cgroup").toString()); values.put("procRoot", path("/proc").toString());
        values.put("delegationParent", path("/sys/fs/cgroup/delegated").toString());
        values.put("identities", Map.of("cargo", "0".repeat(64), "rustc", "0".repeat(64), "toolchain", "0".repeat(64),
            "unshare", "0".repeat(64), "libcgroup", "0".repeat(64), "frida", "0".repeat(64), "javaRuntime", "0".repeat(64)));
        return scope.json().writeValueAsBytes(values);
    }

    /**
     * Invokes the production decoder with a test-owned source locator.
     * @param scope borrowed parsing services
     * @param bytes complete administrator document
     * @return represented configuration without any native authority claim
     * @throws IOException if the document is rejected
     */
    private static LinuxCargoLaunchConfiguration decode(LinuxCargoScope scope, byte[] bytes) throws IOException
    {
        return LinuxCargoLaunchConfiguration.decode(scope, path("/admin/launch.json"), bytes);
    }

    /**
     * Resolves a test-owned resource-free absolute path on the current filesystem provider.
     * @param value slash-prefixed names beneath the provider root
     * @return absolute path using the current provider's root and separators
     */
    private static Path path(String value)
    {
        Path root = Path.of("").toAbsolutePath().getRoot();
        return root.resolve(value.substring(1));
    }
}
