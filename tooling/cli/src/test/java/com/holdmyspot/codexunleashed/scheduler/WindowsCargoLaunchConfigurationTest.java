package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks bounded Windows caller configuration and actual file/tree identity preflight on every platform.
 */
public final class WindowsCargoLaunchConfigurationTest
{
    /**
     * Creates independent configuration checks.
     */
    public WindowsCargoLaunchConfigurationTest() {}

    /**
     * Empty and drive-directory environment values survive strict parsing, while absent memory remains unknown demand.
     * @throws IOException if fixture setup or decoding fails
     */
    @Test
    public void preservesExplicitEnvironmentAndAbsentMemory() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            WindowsCargoLaunchConfiguration configuration = fixture.read();
            requireThat(configuration.environment(), "explicitWindowsEnvironment")
                .isEqualTo(Map.of("EMPTY", "", "Path", "caller path", "=C:", "C:\\work"));
            requireThat(configuration.memory() == null, "absentHistoryRemainsUnknown").isTrue();
            configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper);
        }
    }

    /**
     * Unknown fields, fractional versions, explicit-null memory and case-conflicting environment names are rejected.
     * @throws IOException if fixture setup or serialization fails
     */
    @Test
    public void rejectsMalformedOrAmbiguousConfiguration() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            for (String condition : List.of("unknown", "missing", "fractional", "null-memory", "case-conflict", "owned-env", "relative"))
            {
                Map<String, Object> document = fixture.document();
                switch (condition)
                {
                    case "unknown" -> document.put("extra", true);
                    case "missing" -> document.remove("environment");
                    case "fractional" -> document.put("version", 1.0);
                    case "null-memory" -> document.put("memory", null);
                    case "case-conflict" -> document.put("environment", Map.of("Path", "first", "PATH", "second"));
                    case "owned-env" -> document.put("environment", Map.of("codex_cargo_pipe", "foreign endpoint"));
                    case "relative" -> document.put("cargo", "relative.exe");
                    default -> throw new AssertionError(condition);
                }
                byte[] bytes = fixture.scope.json().writeValueAsBytes(document);
                requireException(IOException.class, () -> WindowsCargoLaunchConfiguration.decode(fixture.scope, fixture.config, bytes));
            }
            for (String malformed : List.of("{} {}", "{\"version\":1,\"version\":1}"))
                requireException(IOException.class, () -> WindowsCargoLaunchConfiguration.decode(fixture.scope,
                    fixture.config, malformed.getBytes(StandardCharsets.UTF_8)));
            requireException(IOException.class, () -> WindowsCargoLaunchConfiguration.decode(fixture.scope,
                fixture.config, new byte[WindowsCargoTransport.MAX_FRAME_BYTES + 1]));
        }
    }

    /**
     * An explicitly empty workspace wrapper remains compatible with the native invocation's approved launch contract.
     * @throws IOException if fixture setup, encoding or parsing fails
     */
    @Test
    public void preservesEmptyWorkspaceWrapper() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            Map<String, Object> document = fixture.document();
            document.put("environment", Map.of("RUSTC_WORKSPACE_WRAPPER", ""));
            byte[] bytes = fixture.scope.json().writeValueAsBytes(document);
            requireThat(WindowsCargoLaunchConfiguration.decode(fixture.scope, fixture.config, bytes).environment(),
                "emptyNestedWrapperRemainsRepresentable").isEqualTo(Map.of("RUSTC_WORKSPACE_WRAPPER", ""));
            document.put("environment", Map.of("RUSTC_WORKSPACE_WRAPPER", "foreign-wrapper.exe"));
            byte[] nested = fixture.scope.json().writeValueAsBytes(document);
            requireException(IOException.class, () -> WindowsCargoLaunchConfiguration.decode(fixture.scope, fixture.config, nested));
        }
    }

    /**
     * Changes to pinned tools, complete runtime contents and parsed configuration are rejected before execution.
     * @throws IOException if fixture setup, mutation or cleanup fails
     */
    @Test
    public void rejectsChangedPinnedInputs() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            WindowsCargoLaunchConfiguration configuration = fixture.read();
            Files.writeString(fixture.cargo, "changed tool");
            requireException(IOException.class, () -> configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper));
            Files.writeString(fixture.cargo, "cargo");
            Files.writeString(fixture.runtime.resolve("new-runtime-library.dll"), "changed runtime");
            requireException(IOException.class, () -> configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper));
            Files.delete(fixture.runtime.resolve("new-runtime-library.dll"));
            Files.writeString(fixture.toolchain.resolve("new-sysroot-file"), "changed sysroot");
            requireException(IOException.class, () -> configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper));
            Files.delete(fixture.toolchain.resolve("new-sysroot-file"));
            Files.writeString(fixture.config, "{}");
            requireException(IOException.class, () -> configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper));
        }
    }

    /**
     * Explicit complete memory settings use the same pinned toolchain and reject an inconsistent memory boundary.
     * @throws IOException if fixture setup or decoding fails
     */
    @Test
    public void acceptsOnlyConsistentExplicitMemory() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            Map<String, Object> document = fixture.document();
            Map<String, Object> memory = new HashMap<>(Map.of("historyDirectory", fixture.root.resolve("history").toString(),
                "inputRoot", fixture.root.resolve("source").toString(), "outputRoots", List.of(),
                "toolchainRoot", fixture.toolchain.toString()));
            document.put("memory", memory);
            byte[] bytes = fixture.scope.json().writeValueAsBytes(document);
            requireThat(WindowsCargoLaunchConfiguration.decode(fixture.scope, fixture.config, bytes).memory() != null,
                "explicitProductionHistorySettings").isTrue();
            memory.put("toolchainRoot", fixture.root.resolve("other-toolchain").toString());
            byte[] inconsistent = fixture.scope.json().writeValueAsBytes(document);
            requireException(IOException.class, () -> WindowsCargoLaunchConfiguration.decode(fixture.scope, fixture.config, inconsistent));
        }
    }

    /**
     * Owns real byte-identity inputs for configuration and schedule preflight; the files are never executed.
     */
    static final class Fixture implements AutoCloseable
    {
        /** Exclusive test-owned content root. */
        final Path root;
        /** Complete fake toolchain tree whose bytes production preflight hashes. */
        final Path toolchain;
        /** Fake pinned Cargo bytes, never executed. */
        final Path cargo;
        /** Fake pinned compiler bytes, never executed. */
        final Path rustc;
        /** Complete fake installed runtime, including the actual production wrapper layout. */
        final Path runtime;
        /** Installed wrapper location represented by byte-identity fixture content. */
        final Path wrapper;
        /** Fake pinned instrumentation bytes, never loaded. */
        final Path frida;
        /** Bounded caller input file. */
        final Path config;
        /** Test-owned shared services, closed after the borrowed Cargo scope. */
        final DefaultToolingScope tooling = new DefaultToolingScope();
        /** Test-owned scoped strict JSON parser. */
        final WindowsCargoScope scope = new WindowsCargoScope(tooling);

        /**
         * Creates real independent file inputs and registers cleanup even when later fixture creation fails.
         * @throws IOException if fixture creation fails
         */
        Fixture() throws IOException
        {
            this.root = Files.createTempDirectory("windows-launch-");
            try
            {
                this.toolchain = Files.createDirectory(root.resolve("toolchain"));
                this.cargo = Files.writeString(toolchain.resolve("cargo.exe"), "cargo");
                this.rustc = Files.writeString(toolchain.resolve("rustc.exe"), "rustc");
                this.runtime = Files.createDirectory(root.resolve("runtime"));
                Path application = Files.createDirectories(runtime.resolve("wrapper-image/codex-cargo-wrapper"));
                this.wrapper = Files.writeString(application.resolve("codex-cargo-wrapper.exe"), "wrapper");
                this.frida = Files.writeString(root.resolve("frida.dll"), "frida");
                this.config = root.resolve("launch.json");
                Files.write(config, scope.json().writeValueAsBytes(document()));
            }
            catch (IOException | RuntimeException | Error failure)
            {
                try { close(); } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Supplies a fresh version-one document with exact currently pinned fixture identities.
         * @return mutable top-level document for controlled input variation
         * @throws IOException if fixture byte identities cannot be read
         */
        Map<String, Object> document() throws IOException
        {
            Map<String, String> identities = Map.of("cargo", Sha256.digest(cargo), "rustc", Sha256.digest(rustc),
                "toolchain", LinuxCargoLaunchConfiguration.toolchainDigest(toolchain), "frida", Sha256.digest(frida),
                "javaRuntime", LinuxCargoLaunchConfiguration.toolchainDigest(runtime), "wrapper", Sha256.digest(wrapper));
            return new HashMap<>(Map.of("version", 1, "cargo", cargo.toString(), "rustc", rustc.toString(),
                "toolchainRoot", toolchain.toString(), "environment", Map.of("EMPTY", "", "Path", "caller path", "=C:", "C:\\work"),
                "identities", identities));
        }

        /**
         * Reads the fixture's exact original caller document through production bounded file input.
         * @return parsed launch inputs
         * @throws IOException if parsing fails
         */
        WindowsCargoLaunchConfiguration read() throws IOException { return WindowsCargoLaunchConfiguration.read(scope, config); }

        /**
         * Parses explicit command inputs whose tool argument tail includes spaces, empty text and an option.
         * @return immutable production command settings
         */
        Scheduler.Settings settings()
        {
            return Scheduler.parse(new String[]{"--tool", "cargo", "--workspace", root.toString(), "--scratch", root.toString(),
                "--frida-library", frida.toString(), "--headroom-bytes", "0", "--max-jobs", "2", "--control-timeout-ms", "1000",
                "--windows-launch-config", config.toString(), "--", "build", "a b", "", "--jobs=7"});
        }

        /**
         * Closes only this test's scopes and removes its exclusive fixture root.
         * @throws IOException if fixture removal fails
         */
        @Override public void close() throws IOException
        {
            try { scope.close(); }
            finally
            {
                tooling.close();
                try (Stream<Path> paths = Files.walk(root))
                {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
