package com.holdmyspot.codexunleashed.scheduler.test;

import com.holdmyspot.codexunleashed.scheduler.CargoLaunchConfigurationCommand;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoLaunchConfiguration;
import com.holdmyspot.codexunleashed.scheduler.LinuxCargoScope;
import com.holdmyspot.codexunleashed.scheduler.MacCargoLaunchConfiguration;
import com.holdmyspot.codexunleashed.scheduler.MacCargoScope;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoLaunchConfiguration;
import com.holdmyspot.codexunleashed.scheduler.WindowsCargoScope;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        TestRequirements.requirePosixFileSystem();
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
            requireThat(fixture.runInProcess("Windows"), "windowsConstructionStatus").isEqualTo(0);
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
        TestRequirements.requirePosixFileSystem();
        try (Fixture fixture = new Fixture(); var tooling = new DefaultToolingScope(); var scope = new MacCargoScope(tooling))
        {
            requireThat(fixture.runInProcess("macOS"), "macConstructionStatus").isEqualTo(0);
            var configuration = MacCargoLaunchConfiguration.read(scope, fixture.output);
            requireThat(configuration.identities().keySet(), "completeMacProducts")
                .isEqualTo(java.util.Set.of("cargo", "rustc", "toolchain", "cargoBridge", "workload", "wrapper", "anchor", "frida", "javaRuntime"));
        }
    }

    /**
     * A Linux build hook is named in the document, and both the library and the trampoline installed beside it are
     * pinned by their actual bytes.
     * @throws IOException if fixture creation or decoding fails
     */
    @Test public void linuxBuildHookPinsLibraryAndTrampoline() throws IOException
    {
        TestRequirements.requirePosixFileSystem();
        try (Fixture fixture = new Fixture(); var tooling = new DefaultToolingScope(); var scope = new LinuxCargoScope(tooling))
        {
            Path library = fixture.file("native/libbuildhook.so", "hook library");
            Path trampoline = fixture.file("native/codex-build-hook-exec", "hook trampoline");
            requireThat(fixture.runInProcess("Linux", "--build-hook", library.toString()), "status").isEqualTo(0);
            var configuration = LinuxCargoLaunchConfiguration.read(scope, fixture.output);
            requireThat(configuration.buildHook().orElseThrow(), "buildHook").isEqualTo(library);
            requireThat(configuration.identities().get("buildHook"), "buildHookIdentity").isEqualTo(sha256(library));
            requireThat(configuration.identities().get("buildHookExec"), "buildHookExecIdentity").
                isEqualTo(sha256(trampoline));
        }
    }

    /**
     * A build hook without its trampoline is rejected before any configuration is published.
     * @throws IOException if fixture creation or inspection fails
     */
    @Test public void buildHookWithoutTrampolineRejectsPublication() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            Path library = fixture.file("native/libbuildhook.so", "hook library");
            requireThat(fixture.runInProcess("Linux", "--build-hook", library.toString()) != 0, "rejected").isTrue();
            requireThat(Files.exists(fixture.output), "noPartialAuthorization").isFalse();
        }
    }

    /**
     * Hashes a file.
     * @param path the file
     * @return its lowercase SHA-256
     * @throws IOException if it cannot be read
     */
    private static String sha256(Path path) throws IOException
    {
        try
        {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").
                digest(Files.readAllBytes(path)));
        }
        catch (java.security.NoSuchAlgorithmException unavailable)
        {
            throw new AssertionError(unavailable);
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
            requireThat(fixture.runInProcess("Linux") != 0, "existingPublicationRejected").isTrue();
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
            requireThat(fixture.runInProcess("Windows") != 0, "missingProductRejected").isTrue();
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
         * Builds the exact option list of one platform's construction command.
         * @param platform represented platform schema
         * @return command options following the command name
         */
        private List<String> arguments(String platform)
        {
            List<String> arguments = new ArrayList<>(List.of("--platform", platform,
                "--runtime", runtime.toString(), "--toolchain", toolchain.toString(), "--frida-library", frida.toString(),
                "--workspace", workspace.toString(), "--output-root", target.toString(), "--history", root.resolve("history").toString(),
                "--output", output.toString()));
            if (!platform.equals("Windows")) arguments.addAll(List.of("--uid", "1001", "--gid", "1001"));
            if (platform.equals("Linux")) arguments.addAll(List.of("--unshare", root.resolve("unshare").toString(),
                "--libcgroup", root.resolve("libcgroup").toString(), "--delegation-parent", "/sys/fs/cgroup/owned-release"));
            return arguments;
        }

        /**
         * Supplies the complete stage environment shared by both command invocations.
         * @return explicit representable environment
         */
        private static Map<String, String> environment()
        {
            var environment = new HashMap<String, String>();
            environment.put("PATH", System.getenv("PATH"));
            if (System.getenv("SystemRoot") != null) environment.put("SystemRoot", System.getenv("SystemRoot"));
            environment.put("STABLE_GIT_COMMIT", "dddc3c0a79528c4de8d9a20d51dd3bd89b02a3e4");
            environment.put("CODEX_BWRAP_SHA256", "fixture completed bwrap identity");
            environment.put("EMPTY", "");
            environment.put("RUSTC_WRAPPER", "foreign authority is excluded");
            return environment;
        }

        /**
         * Runs the command operation behind the public entry in this JVM, for claims about its inputs and published
         * document that do not depend on a fresh process environment. A rejected construction is reported as status one,
         * the status the public entry gives it.
         * @param platform represented platform schema
         * @param extra    options added after the platform's required options
         * @return zero after publication, otherwise one
         */
        private int runInProcess(String platform, String... extra)
        {
            try (var tooling = new DefaultToolingScope())
            {
                List<String> arguments = arguments(platform);
                arguments.addAll(List.of(extra));
                return CargoLaunchConfigurationCommand.run(tooling, arguments.toArray(String[]::new),
                    environment(), new PrintStream(OutputStream.nullOutputStream()));
            }
            catch (IOException | IllegalArgumentException rejected) { return 1; }
        }

        /**
         * Invokes actual public Main in an owned Java process with a complete explicit representable environment.
         * @param platform represented platform schema
         * @return actual public command status
         */
        private int run(String platform)
        {
            List<String> arguments = new ArrayList<>(List.of("prepare-cargo-launch"));
            arguments.addAll(arguments(platform));
            String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
                "-Djava.io.tmpdir=" + root, "--enable-native-access=ALL-UNNAMED", "-Xshare:auto", "-XX:+UseSerialGC",
                "-XX:TieredStopAtLevel=1"));
            if (Main.class.getModule().isNamed())
                command.addAll(List.of("--module-path", System.getProperty("jdk.module.path"), "-m",
                    Main.class.getModule().getName() + "/" + Main.class.getName()));
            else
                command.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
            command.addAll(arguments);
            var builder = new ProcessBuilder(command).directory(root.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment());
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
