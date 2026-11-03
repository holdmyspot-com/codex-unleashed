package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.LinuxCargoLaunchConfiguration;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
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
     * Wrapper encoding and decoding preserve the complete immutable invocation context.
     * @throws IOException if strict encoding or decoding fails
     */
    @Test
    public void roundTripsCompleteWrapperContext() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            LinuxCargoLaunchConfiguration.Wrapper expected = wrapper();
            byte[] encoded = LinuxCargoLaunchConfiguration.encodeWrapper(scope, expected);
            requireThat(LinuxCargoLaunchConfiguration.decodeWrapper(scope, encoded).equals(expected), "context").isTrue();
            String invalid = new String(encoded, StandardCharsets.UTF_8).replace("\"timeoutMillis\":3000", "\"timeoutMillis\":0");
            requireException(IOException.class, () -> LinuxCargoLaunchConfiguration.decodeWrapper(scope, invalid.getBytes(StandardCharsets.UTF_8)));
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
     * Supplies a complete resource-free invocation context.
     * @return independent immutable wrapper context
     */
    private static LinuxCargoLaunchConfiguration.Wrapper wrapper()
    {
        return new LinuxCargoLaunchConfiguration.Wrapper(path("/run/controller"), path("/run/registrations"),
            path("/cgroup/actions"), path("/run/invocation"), path("/runtime/bin/java"),
            path("/runtime/lib/modules"), path("/tools/rustc"), 1001, 1002, Duration.ofSeconds(3));
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
