package com.holdmyspot.codexunleashed.distribution;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises packaging decisions, native-stage dispatch and actual generated Java companion invocation. */
public final class DistributionPackagingTest
{
    /** Creates independent test methods without shared mutable fixtures. */
    public DistributionPackagingTest() {}

    /**
     * Names the six supported native consumers; architecture checking remains with the shared backend.
     * @return platform inputs and whether ordinary native sources are required
     */
    @DataProvider(parallel = true)
    public Object[][] targets()
    {
        return new Object[][] {
            {"linux-aarch64", "Linux", false}, {"linux-x86_64", "Linux", false},
            {"macos-aarch64", "Mac OS X", true}, {"macos-x86_64", "Darwin", true},
            {"windows-aarch64", "Windows 11", false}, {"windows-x86_64", "Windows 10", false}
        };
    }

    /**
     * Invokes the shared stager for each platform form and publishes the platform's companion only after success.
     * The injected Java collaborator represents the independently validated native stage, not native binaries.
     * @param target native consumer label
     * @param operatingSystem resolved operating system
     * @param needsSources whether the ordinary macOS support inputs remain required
     * @throws IOException if fixture staging or cleanup fails
     */
    @Test(dataProvider = "targets")
    public void dispatchesSharedStager(String target, String operatingSystem, boolean needsSources) throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            boolean unixPermissionsRequired = !operatingSystem.startsWith("Windows");
            boolean hasPosixPermissions = Files.getFileStore(fixture.root).supportsFileAttributeView("posix");
            if (unixPermissionsRequired && !hasPosixPermissions)
                throw new SkipException("Unix launcher mode requires a POSIX filesystem; Windows staging runs independently");
            List<String> argv = new ArrayList<>(List.of(fixture.modules.toString(), fixture.runtime.toString()));
            if (needsSources) argv.add(fixture.root.resolve("sources").toString());
            argv.addAll(List.of("--bazel-backend", fixture.backend.toString()));
            DistributionMain.PackagingArguments selected = DistributionMain.PackagingArguments.parse(
                argv.toArray(String[]::new), operatingSystem);
            requireThat(selected.backend(), target + "Backend").isEqualTo(fixture.backend);
            requireThat(selected.sources() != null, target + "OrdinarySupportRequired").isEqualTo(needsSources);
            boolean windows = operatingSystem.startsWith("Windows");
            String suffix = windows ? ".exe" : "";
            Files.writeString(fixture.jdk.resolve("bin/keytool" + suffix), "fixture keytool");
            List<List<String>> calls = new ArrayList<>();
            DistributionMain.stageBazelCompanion(fixture.modules, fixture.runtime, fixture.backend,
                operatingSystem, fixture.jdk, fixture.root, (command, diagnostic) -> {
                    calls.add(command);
                    // This owned collaborator is the external shared stager; reaching it creates its receipt.
                    Files.createDirectory(Path.of(command.getLast()));
                    Files.writeString(diagnostic, "shared stager invoked\n");
                    return 0;
                });
            requireThat(calls.size(), target + "StageCalls").isEqualTo(1);
            requireThat(calls.getFirst(), target + "InvokedSharedAuthority").isEqualTo(List.of(
                fixture.runtime.resolve("bin/java" + suffix).toString(), "-Djava.io.tmpdir=" + fixture.root,
                "-cp", fixture.runtime.resolve("lib/bazel-backend/codex-tooling.jar").toString(),
                "com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend", fixture.backend.toString(),
                fixture.runtime.resolve("lib/bazel-backend/native").toString()));
            requireThat(Files.isDirectory(fixture.runtime.resolve("lib/bazel-backend/native")),
                target + "ExternalStageCompleted").isTrue();
            requireThat(Files.exists(fixture.runtime.resolve("bin/keytool" + suffix)), target + "KeytoolPublished").isTrue();
            requireThat(Files.exists(fixture.runtime.resolve("bin/codex-tooling-bazel")), target + "ShellCompanionPublished").isTrue();
            requireThat(Files.exists(fixture.runtime.resolve("bin/codex-tooling-bazel.cmd")),
                target + "WindowsCompanionPublished").isEqualTo(windows);
        }
    }

    /**
     * Rejects a missing native stage result before publishing a launcher or application digest.
     * @throws IOException if fixture acquisition or cleanup fails
     */
    @Test
    public void failedNativeStagePreventsCompanionPublication() throws IOException
    {
        try (Fixture fixture = new Fixture())
        {
            IOException failure = null;
            try
            {
                DistributionMain.stageBazelCompanion(fixture.modules, fixture.runtime, fixture.backend,
                    "Linux", fixture.jdk, fixture.root, (command, diagnostic) -> {
                        Files.writeString(diagnostic, "genuine stage rejection");
                        return 23;
                    });
            }
            catch (IOException caught) { failure = caught; }
            requireThat(failure == null, "NativeFailureRequired").isFalse();
            requireThat(failure.getMessage(), "OriginalNativeFailure").isEqualTo(
                "Native backend package validation failed with status 23: genuine stage rejection");
            requireThat(Files.exists(fixture.runtime.resolve("bin/codex-tooling-bazel")), "NoCompanionAfterFailure").isFalse();
            requireThat(Files.exists(fixture.runtime.resolve("lib/bazel-backend/application.sha256")), "NoDigestAfterFailure").isFalse();
        }
    }

    /**
     * Retains ordinary argument forms and rejects macOS backend packaging without its independent native sources.
     */
    @Test
    public void preservesOrdinaryPackagingArguments()
    {
        for (String os : List.of("Linux", "Mac OS X", "Windows 11"))
        {
            DistributionMain.PackagingArguments ordinary = DistributionMain.PackagingArguments.parse(
                new String[] {"modules", "runtime", "sources"}, os);
            requireThat(ordinary.backend() == null, "OrdinaryPackagingHasNoBackend").isTrue();
            requireThat(ordinary.sources(), "OrdinaryNativeSource").isEqualTo(Path.of("sources").toAbsolutePath());
        }
        DistributionMain.PackagingArguments windows = DistributionMain.PackagingArguments.parse(
            new String[] {"modules", "runtime"}, "Windows 11");
        requireThat(windows.sources() == null, "WindowsOrdinarySourcesOptional").isTrue();
    }

    /** Rejects the backend-only form that would omit the four ordinary macOS native products. */
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void macBackendStillRequiresNativeSources()
    {
        DistributionMain.PackagingArguments.parse(new String[] {"modules", "runtime", "--bazel-backend", "backend"}, "Mac OS X");
    }

    /** Rejects an optional backend on an operating system the shared native backend does not support. */
    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void rejectsUnsupportedOptionalPlatform()
    {
        DistributionMain.PackagingArguments.parse(new String[] {"modules", "runtime", "--bazel-backend", "backend"}, "FreeBSD");
    }

    /**
     * Invokes the actual generated POSIX launcher and observes exact arguments, JVM temporary directory and exit23.
     * Java is the independent receiver; no shell script substitutes for the process under test.
     * @throws Exception if receiver compilation, staging, invocation or cleanup fails
     */
    @Test
    public void generatedCompanionInvokesBundledJava() throws Exception
    {
        if (System.getProperty("os.name").startsWith("Windows"))
            throw new SkipException("POSIX launcher invocation requires a POSIX worker; Windows cmd is a native consumer gate");
        try (Fixture fixture = new Fixture())
        {
            Path source = fixture.root.resolve("Main.java");
            Files.writeString(source, """
                package com.holdmyspot.codexunleashed.tooling;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        try (var out = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0])))) {
                            out.writeUTF(System.getProperty("java.io.tmpdir"));
                            for (int i = 1; i < args.length; ++i) out.writeUTF(args[i]);
                        }
                        System.exit(23);
                    }
                }
                """);
            Path receiver = Files.createDirectory(fixture.root.resolve("receiver"));
            requireThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "27", "-d",
                receiver.toString(), source.toString()), "IndependentReceiverCompile").isEqualTo(0);
            Path classFile = receiver.resolve("com/holdmyspot/codexunleashed/tooling/Main.class");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(fixture.modules.resolve("receiver.jar"))))
            {
                jar.putNextEntry(new JarEntry("com/holdmyspot/codexunleashed/tooling/Main.class"));
                jar.write(Files.readAllBytes(classFile));
                jar.closeEntry();
            }
            Path actualJdk = Path.of(System.getProperty("java.home"));
            Files.createSymbolicLink(fixture.runtime.resolve("bin/java"), actualJdk.resolve("bin/java"));
            Files.writeString(fixture.jdk.resolve("bin/keytool"), "fixture keytool");
            DistributionMain.stageBazelCompanion(fixture.modules, fixture.runtime, fixture.backend,
                System.getProperty("os.name"), fixture.jdk, fixture.root, (command, diagnostic) -> {
                    Files.createDirectory(Path.of(command.getLast())); Files.writeString(diagnostic, "staged"); return 0;
                });
            Path received = fixture.root.resolve("received");
            Path temporaryDirectory = Files.createDirectory(fixture.root.resolve("temp with spaces"));
            ProcessBuilder launch = new ProcessBuilder(fixture.runtime.resolve("bin/codex-tooling-bazel").toString(),
                received.toString(), "argument with spaces", "", "--literal=$value", "one\ntwo");
            launch.environment().put("TMPDIR", temporaryDirectory.toString());
            launch.redirectOutput(fixture.root.resolve("stdout").toFile()).redirectError(fixture.root.resolve("stderr").toFile());
            try (Process child = launch.start())
            {
                int status = child.waitFor();
                if (status != 23) System.err.print(Files.readString(fixture.root.resolve("stderr")));
                requireThat(status, "AuthenticReceiverExit").isEqualTo(23);
            }
            List<String> actual = new ArrayList<>();
            try (DataInputStream input = new DataInputStream(Files.newInputStream(received)))
            {
                while (input.available() > 0) actual.add(input.readUTF());
            }
            requireThat(actual, "ReceiverObservedExactValues").isEqualTo(List.of(temporaryDirectory.toString(),
                "argument with spaces", "", "--literal=$value", "one\ntwo"));
        }
    }

    /** Owns one exclusively created packaging tree through normal and exceptional completion. */
    private static final class Fixture implements AutoCloseable
    {
        /** Exclusive fixture root. */
        private final Path root;
        /** Source JAR directory. */
        private final Path modules;
        /** Linked runtime model consumed by production staging. */
        private final Path runtime;
        /** Original backend selected by the caller. */
        private final Path backend;
        /** Build JDK model supplying a named keytool file. */
        private final Path jdk;

        /**
         * Creates and immediately owns all independent fixture paths.
         * @throws IOException if acquisition fails
         */
        private Fixture() throws IOException
        {
            root = Files.createTempDirectory("distribution-stage-").toAbsolutePath();
            try
            {
                modules = Files.createDirectory(root.resolve("modules"));
                runtime = Files.createDirectories(root.resolve("nested space/runtime"));
                Files.createDirectory(runtime.resolve("bin")); Files.createDirectory(runtime.resolve("lib"));
                backend = Files.createDirectory(root.resolve("backend"));
                jdk = Files.createDirectory(root.resolve("jdk")); Files.createDirectory(jdk.resolve("bin"));
            }
            catch (IOException | RuntimeException failure)
            {
                try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Deletes only this test's original tree, without following symbolic links.
         * @throws IOException if cleanup fails
         */
        @Override public void close() throws IOException
        {
            try (var paths = Files.walk(root))
            {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
