package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Portable administrator-document checks; parsing and rejection do not establish native execution readiness. */
public final class MacCargoLaunchConfigurationTest
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

    /** Creates method-owned parser and document fixtures. */
    public MacCargoLaunchConfigurationTest() {}

    /**
     * Preserves exact authorized values and conservative unknown history when memory is absent.
     * @throws IOException if valid strict JSON cannot be decoded
     */
    @Test public void preservesAuthorizedValues() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            var result = decode(scope, document(scope));
            requireThat(result.uid(), "workloadUid").isEqualTo(501, "expected");
            requireThat(result.gid(), "workloadGid").isEqualTo(20, "expected");
            requireThat(result.environment(), "exactEnvironment").isEqualTo(Map.of("KEY", "space λ=kept"), "expected");
            requireThat(result.memory() == null, "unknownDemandWithoutExplicitHistory").isTrue();
            requireException(UnsupportedOperationException.class, () -> result.environment().clear());
        }
    }

    /**
     * Rejects duplicate, unknown, missing, coerced and caller-owned authority fields before native acquisition.
     * @throws IOException if fixture JSON cannot be encoded
     */
    @Test public void rejectsUnsafeOrNonexactDocuments() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            String valid = new String(document(scope), StandardCharsets.UTF_8);
            for (String invalid : List.of(valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"), valid + " {}",
                valid.substring(0, valid.length() - 1) + ",\"stdin\":0}",
                valid.substring(0, valid.length() - 1) + ",\"memory\":null}",
                valid.replace("/trusted/tools/cargo", "relative/cargo"),
                valid.replace("\"uid\":501", "\"uid\":0"),
                valid.replace("\"supplementaryGroups\":[]", "\"supplementaryGroups\":[20]"),
                valid.replace("\"KEY\":", "\"CODEX_CARGO_SOCKET\":"),
                valid.replace("\"KEY\":", "\"DYLD_INSERT_LIBRARIES\":"), "{bad"))
                requireException(IOException.class, () -> decode(scope, invalid.getBytes(StandardCharsets.UTF_8)));
            requireException(IOException.class, () -> decode(scope, new byte[1048577]));
        }
    }

    /**
     * Constructs explicit production history only when complete paths and pinned toolchain authority agree.
     * @throws IOException if fixture encoding or valid history decoding fails
     */
    @Test public void acceptsOnlyCompleteMatchingHistory() throws IOException
    {
        try (var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            Map<String, Object> values = values();
            values.put("memory", Map.of("historyDirectory", "/trusted/history", "inputRoot", "/trusted/source",
                "outputRoots", List.of("/trusted/source/target"), "toolchainRoot", "/trusted/tools"));
            requireThat(decode(scope, scope.launchJson().writeValueAsBytes(values)).memory(), "explicitHistory").isNotNull();
            values.put("memory", Map.of("historyDirectory", "/trusted/history", "inputRoot", "/trusted/source",
                "outputRoots", List.of(), "toolchainRoot", "/other/tools"));
            byte[] mismatched = scope.launchJson().writeValueAsBytes(values);
            requireException(IOException.class, () -> decode(scope, mismatched));
        }
    }

    /**
     * Resolves installed native products relative to the real bundled runtime rather than the working directory.
     * @throws IOException if the current runtime cannot be resolved
     */
    @Test public void resolvesOnlyActualRuntimeProducts() throws IOException
    {
        Path home = Path.of(System.getProperty("java.home")).toRealPath();
        var installed = MacCargoLaunchConfiguration.installed(home, Path.of("/trusted/frida"));
        requireThat(installed.wrapper(), "bundledWrapper").isEqualTo(home.resolve("bin/codex-cargo-wrapper"), "expected");
        requireThat(installed.workload(), "bundledAuthorityBridge").isEqualTo(home.resolve("lib/codex/libmac-workload.dylib"), "expected");
        requireThat(installed.javaRuntime(), "actualRuntimeIdentity").isEqualTo(home.resolve("lib/modules"), "expected");
    }

    /**
     * Rejects a caller-mutable configuration namespace before trusting any represented pinned file.
     * @throws IOException if method-owned fixture setup or cleanup fails
     */
    @Test public void rejectsCallerMutableAdministratorNamespace() throws IOException
    {
        Path directory = Files.createTempDirectory("mac-launch-config-");
        Path source = directory.resolve("launch.json");
        try (var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            Files.write(source, document(scope));
            var configuration = MacCargoLaunchConfiguration.read(scope, source);
            var installed = MacCargoLaunchConfiguration.installed(Path.of(System.getProperty("java.home")), Path.of("/trusted/frida"));
            requireException(IOException.class, () -> configuration.requireTrusted(installed));
        }
        finally { Files.deleteIfExists(source); Files.delete(directory); }
    }

    /**
     * Encodes complete method-owned valid administrator input through the scoped selected JSON implementation.
     * @param scope borrowed parser and encoder
     * @return complete document bytes
     * @throws IOException if encoding fails
     */
    static byte[] document(MacCargoScope scope) throws IOException { return scope.launchJson().writeValueAsBytes(values()); }

    /**
     * Creates independent valid document values without native authority or shared mutable fixtures.
     * @return caller-owned fixture members
     */
    private static Map<String, Object> values()
    {
        Map<String, String> identities = new HashMap<>();
        for (String name : List.of("cargo", "rustc", "toolchain", "cargoBridge", "workload", "wrapper", "anchor", "frida", "javaRuntime"))
            identities.put(name, "0".repeat(64));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("version", 1); result.put("uid", 501); result.put("gid", 20); result.put("supplementaryGroups", List.of());
        result.put("cargo", "/trusted/tools/cargo"); result.put("rustc", "/trusted/tools/rustc"); result.put("toolchainRoot", "/trusted/tools");
        result.put("environment", Map.of("KEY", "space λ=kept")); result.put("identities", identities);
        return result;
    }

    /**
     * Invokes the final application operation with a represented immutable administrator source location.
     * @param scope caller-owned strict parser
     * @param bytes complete document
     * @return parsed configuration without native authority
     * @throws IOException if decoding fails
     */
    private static MacCargoLaunchConfiguration decode(MacCargoScope scope, byte[] bytes) throws IOException
    {
        return MacCargoLaunchConfiguration.decode(scope, Path.of("/trusted/launch.json"), bytes);
    }
}
