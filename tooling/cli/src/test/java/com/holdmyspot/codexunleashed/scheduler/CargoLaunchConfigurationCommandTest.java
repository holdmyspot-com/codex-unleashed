package com.holdmyspot.codexunleashed.scheduler;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies the public configuration construction command against actual strict platform consumers and byte identities. */
public final class CargoLaunchConfigurationCommandTest
{
    /** Creates independent command checks. */
    public CargoLaunchConfigurationCommandTest() {}

    /**
     * The public entry publishes a complete Linux document with current environment and pinned actual files.
     * @throws IOException if fixture creation or decoding fails
     */
    @Test public void publicCommandProducesActualLinuxConsumerConfiguration() throws IOException
    {
        try (Fixture fixture = new Fixture(); var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            requireThat(fixture.run("Linux"), "publicConstructionStatus").isEqualTo(0);
            var configuration = LinuxCargoLaunchConfiguration.read(scope, fixture.output);
            requireThat(configuration.environment().get("PATH"), "currentCallerPath").isEqualTo(System.getenv("PATH"));
            requireThat(configuration.environment().containsKey("RUSTC_WRAPPER"), "noInheritedWrapperAuthority").isFalse();
            requireThat(configuration.environment().get("CODEX_BWRAP_SHA256"), "currentCompletedBwrapIdentity")
                .isEqualTo("fixture completed bwrap identity");
            requireThat(configuration.environment().get("EMPTY"), "explicitEmptyStageValue").isEqualTo("");
            requireThat(configuration.identities().get("toolchain"), "actualCompleteToolchain")
                .isEqualTo(LinuxCargoLaunchConfiguration.toolchainDigest(fixture.toolchain));
            requireThat(scope.json().readTree(Files.readAllBytes(fixture.output)).path("memory").path("inputRoot").stringValue(), "currentSourceBoundary").isEqualTo(fixture.workspace.toString());
        }
    }

    /**
     * The Windows document passes the real full runtime/toolchain/wrapper identity check without executing native code.
     * @throws IOException if fixture creation or consumer validation fails
     */
    @Test public void windowsConfigurationPassesActualIdentityConsumer() throws IOException
    {
        try (Fixture fixture = new Fixture(); var tooling = new DefaultToolingScope(); var scope = new WindowsCargoScope(tooling))
        {
            requireThat(fixture.run("Windows"), "windowsConstructionStatus").isEqualTo(0);
            var configuration = WindowsCargoLaunchConfiguration.read(scope, fixture.output);
            configuration.requireIdentities(fixture.frida, fixture.runtime, fixture.wrapper);
            requireThat(configuration.memory() != null, "generatedOutputBoundaryAccepted").isTrue();
        }
    }

    /**
     * The Mac document reaches the actual strict reader with identities for every installed bridge.
     * @throws IOException if fixture creation or decoding fails
     */
    @Test public void macConfigurationUsesEveryInstalledProduct() throws IOException
    {
        try (Fixture fixture = new Fixture(); var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            requireThat(fixture.run("macOS"), "macConstructionStatus").isEqualTo(0);
            var configuration = MacCargoLaunchConfiguration.read(scope, fixture.output);
            requireThat(configuration.identities().keySet(), "completeMacProducts")
                .isEqualTo(java.util.Set.of("cargo", "rustc", "toolchain", "cargoBridge", "workload", "wrapper", "anchor", "frida", "javaRuntime"));
        }
    }

    /**
     * Existing authorization is never overwritten, even after a failed publication attempt.
     * @throws IOException if fixture creation or inspection fails
     */
    @Test public void existingAuthorizationIsPreserved() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            Files.writeString(fixture.output, "original authorization");
            requireThat(fixture.run("Linux") != 0, "existingPublicationRejected").isTrue();
            requireThat(Files.readString(fixture.output), "originalAuthorization").isEqualTo("original authorization");
        }
    }

    /**
     * A missing pinned product rejects construction before any configuration is published.
     * @throws IOException if fixture creation or inspection fails
     */
    @Test public void missingRuntimeProductRejectsPublication() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            Files.delete(fixture.wrapper);
            requireThat(fixture.run("Windows") != 0, "missingProductRejected").isTrue();
            requireThat(Files.exists(fixture.output), "noPartialAuthorization").isFalse();
        }
    }

    /** Owns all mutable command inputs and removes only its exclusive temporary directory. */
    private static final class Fixture implements AutoCloseable
    {
        /** Exclusive caller-owned fixture root. */
        private final Path root;
        /** Complete fake installed runtime whose files are hashed, never executed. */
        private final Path runtime;
        /** Complete compiler tree. */
        private final Path toolchain;
        /** Explicit native library identity input. */
        private final Path frida;
        /** Installed Windows wrapper image. */
        private final Path wrapper;
        /** Pristine logical source boundary. */
        private final Path workspace;
        /** Declared generated output root. */
        private final Path target;
        /** New configuration destination. */
        private final Path output;

        /**
         * Creates independent regular files representing all three platforms' configuration inputs.
         * @throws IOException if fixture creation fails
         */
        private Fixture() throws IOException
        {
            root = Files.createTempDirectory("cargo-launch-command-").toAbsolutePath();
            runtime = Files.createDirectories(root.resolve("runtime"));
            toolchain = Files.createDirectories(root.resolve("toolchain"));
            workspace = Files.createDirectory(root.resolve("workspace"));
            target = workspace.resolve("target"); output = root.resolve("launch.json");
            frida = file("frida", "native producer");
            for (String name : List.of("toolchain/bin/cargo", "toolchain/bin/rustc", "toolchain/bin/cargo.exe", "toolchain/bin/rustc.exe",
                "runtime/lib/modules", "runtime/lib/codex/libmac-cargo.dylib", "runtime/lib/codex/libmac-workload.dylib",
                "runtime/bin/codex-cargo-wrapper", "runtime/bin/codex-workload-anchor", "unshare", "libcgroup")) file(name, name);
            wrapper = file("runtime/wrapper-image/codex-cargo-wrapper/codex-cargo-wrapper.exe", "wrapper image");
        }

        /**
         * Creates one regular fixture product, with missing parents created only inside the owned root.
         * @param name relative owned product
         * @param content exact bytes represented as text
         * @return complete product path
         * @throws IOException if creation fails
         */
        private Path file(String name, String content) throws IOException
        {
            Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, content); return path;
        }

        /**
         * Invokes actual public Main in an owned Java process with a complete explicit representable environment.
         * @param platform represented platform schema
         * @return actual public command status
         */
        private int run(String platform)
        {
            List<String> arguments = new ArrayList<>(List.of("prepare-cargo-launch", "--platform", platform,
                "--runtime", runtime.toString(), "--toolchain", toolchain.toString(), "--frida-library", frida.toString(),
                "--workspace", workspace.toString(), "--output-root", target.toString(), "--history", root.resolve("history").toString(),
                "--output", output.toString()));
            if (!platform.equals("Windows")) arguments.addAll(List.of("--uid", "1001", "--gid", "1001"));
            if (platform.equals("Linux")) arguments.addAll(List.of("--unshare", root.resolve("unshare").toString(),
                "--libcgroup", root.resolve("libcgroup").toString(), "--delegation-parent", "/sys/fs/cgroup/owned-release"));
            String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
                "-Djava.io.tmpdir=" + root, "--enable-native-access=ALL-UNNAMED"));
            if (Main.class.getModule().isNamed())
                command.addAll(List.of("--module-path", System.getProperty("jdk.module.path"), "-m",
                    Main.class.getModule().getName() + "/" + Main.class.getName()));
            else
                command.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
            command.addAll(arguments);
            var builder = new ProcessBuilder(command).directory(root.toFile());
            builder.environment().clear();
            builder.environment().put("PATH", System.getenv("PATH"));
            if (System.getenv("SystemRoot") != null) builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
            builder.environment().put("STABLE_GIT_COMMIT", "dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4");
            builder.environment().put("CODEX_BWRAP_SHA256", "fixture completed bwrap identity");
            builder.environment().put("EMPTY", "");
            builder.environment().put("RUSTC_WRAPPER", "foreign authority is excluded");
            builder.redirectOutput(root.resolve("command.out").toFile());
            builder.redirectError(root.resolve("command.err").toFile());
            Process child = null;
            try
            {
                child = builder.start();
                if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                {
                    child.destroyForcibly(); child.waitFor();
                    throw new IllegalStateException("Owned Java configuration child exceeded its deadline");
                }
                return child.exitValue();
            }
            catch (IOException failure) { throw new IllegalStateException("Configuration child launch failed", failure); }
            catch (InterruptedException failure)
            {
                if (child != null)
                {
                    child.destroyForcibly();
                    boolean interrupted = false;
                    while (child.isAlive())
                    {
                        try { child.waitFor(); }
                        catch (InterruptedException repeated) { interrupted = true; }
                    }
                    if (interrupted) failure.addSuppressed(new InterruptedException("Interrupted during owned child cleanup"));
                }
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        }

        /**
         * Deletes fixture files after every command/consumer has finished borrowing them.
         * @throws IOException if owned fixture cleanup fails
         */
        @Override public void close() throws IOException
        {
            try (var entries = Files.walk(root))
            {
                for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
