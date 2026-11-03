package com.holdmyspot.codexunleashed.distribution.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarOutputStream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies actual generated companion launchers with independent, nonnative Java receiver fixtures. */
public final class DistributionLauncherTest
{
	/** The staged TOML parser and its dependencies, as resolved by the build. */
	private static final List<String> TOML_JARS = List.of("tomlj-1.1.1.jar", "antlr4-runtime-4.11.1.jar",
		"checker-qual-3.21.2.jar");

	/** Creates independently owned launcher checks. */
	public DistributionLauncherTest()
	{
	}


    /**
     * Links the actual staged TOML parser and executes its typed API using only the generated runtime.
     * Every original dependency entry must survive descriptor publication unchanged.
     * @throws Exception if module staging, linking, parsing or independent cleanup fails
     */
    @Test
    public void stagedTomlLinksAndRetainsLibraryPayload() throws Exception
    {
        try (Directory directory = new Directory())
        {
            Path root = directory.path;
            Path modules = Files.createDirectory(root.resolve("modules"));
            for (String jar : TOML_JARS)
                Files.copy(dependencyJar(jar), modules.resolve(jar));
            java.util.Map<String, java.util.Map<String, String>> original = libraryPayloads(modules);
            Path source = root.resolve("Stage.java");
            Files.writeString(source, """
                /** Invokes the exact production staged-module boundary. */
                public final class Stage {
                    /** Prevents construction. */ private Stage() {}
                    /**
                     * Stages real parser dependencies.
                     * @param args owned module directory
                     * @throws Exception if the production operation fails
                     */
                    public static void main(String[] args) throws Exception {
                        com.holdmyspot.codexunleashed.distribution.DistributionMain.modularizeToml(
                            java.nio.file.Path.of(args[0]));
                    }
                }
                """);
            Path classes = Files.createDirectory(root.resolve("classes"));
            Path buildSource = Path.of(System.getProperty("basedir", ".")).resolve(
                "src/build/java/com/holdmyspot/codexunleashed/distribution/DistributionMain.java").toAbsolutePath();
            checked(root, tool("javac"), "--release", "27", "-Xlint:all", "-Werror", "-Xdoclint:all/private",
                "-d", classes.toString(), buildSource.toString(), source.toString());
            checked(root, tool("java"), "-cp", classes.toString(), "Stage", modules.toString());
            requireThat(libraryPayloads(modules), "allOriginalLibraryEntries").isEqualTo(original);
            Path probe = Files.createDirectory(root.resolve("probe"));
            Path main = probe.resolve("Probe.java");
            Files.writeString(probe.resolve("module-info.java"), "module probe.toml { requires org.tomlj; }");
            Files.writeString(main, """
                package probe;
                public final class Probe {
                    public static void main(String[] args) {
                        var parsed = org.tomlj.Toml.parse("integer=4\\nzero=-0.0");
                        if (parsed.hasErrors()) throw new IllegalStateException(parsed.errors().toString());
                        System.out.print(parsed.getLong("integer") + ":" +
                            Double.doubleToRawLongBits(parsed.getDouble("zero")));
                    }
                }
                """);
            Path probeClasses = Files.createDirectory(root.resolve("probe-classes"));
            checked(root, tool("javac"), "--module-path", modules.toString(), "-d", probeClasses.toString(),
                probe.resolve("module-info.java").toString(), main.toString());
            Path runtime = root.resolve("runtime");
            checked(root, tool("jlink"), "--module-path", modules.toString(), "--add-modules", "org.tomlj",
                "--output", runtime.toString(), "--no-header-files", "--no-man-pages");
            String actual = checked(root, runtime.resolve("bin").resolve(
                "java" + (java.io.File.separatorChar == 92 ? ".exe" : "")).toString(),
                "--module-path", probeClasses.toString(), "-m", "probe.toml/probe.Probe");
            requireThat(actual, "bundledTypedTomlResult").isEqualTo("4:-9223372036854775808");
        }
    }

    /**
     * Preserves supplied environment entries and arguments through both actual generated Linux entry points.
     * Independent Java receivers observe values after the packaged launcher has executed.
     * @throws Exception if fixture compilation, production packaging, launch or cleanup fails
     */
    @Test
    public void linuxLaunchersPreserveEnvironmentAndArguments() throws Exception
    {
        if (!System.getProperty("os.name").equals("Linux"))
            throw new SkipException("Linux launcher preservation requires actual Linux");
        try (Directory directory = new Directory())
        {
            Path root = directory.path;
            Path modules = Files.createDirectory(root.resolve("modules"));
            for (String jar : TOML_JARS)
                Files.copy(dependencyJar(jar), modules.resolve(jar));
            Path fixture = Files.createDirectory(root.resolve("receiver-source"));
            Path receiver = fixture.resolve("Main.java");
            Files.writeString(receiver, """
                package com.holdmyspot.codexunleashed.tooling;
                public final class Main {
                    public static void main(String[] args) throws Exception {
                        var result = new java.util.Properties();
                        result.setProperty("stdin", new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        System.out.print("authentic stdout");
                        System.err.print("authentic stderr");
                        System.getenv().forEach((name, value) -> result.setProperty("env." + name, value));
                        for (int i = 1; i < args.length; ++i) result.setProperty("arg." + (i - 1), args[i]);
                        try (var output = java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0]))) {
                            result.store(output, "Independent actual child environment and arguments");
                        }
                        System.exit(23);
                    }
                }
                """);
            Path wrapper = fixture.resolve("LinuxCargoWrapperMain.java");
            Files.writeString(wrapper, """
                package com.holdmyspot.codexunleashed.scheduler;
                public final class LinuxCargoWrapperMain {
                    public static void main(String[] args) throws Exception {
                        com.holdmyspot.codexunleashed.tooling.Main.main(args);
                    }
                }
                """);
            Files.writeString(fixture.resolve("module-info.java"), "module com.holdmyspot.codexunleashed.tooling {}\n");
            Path receiverClasses = Files.createDirectory(root.resolve("receiver-classes"));
            checked(root, tool("javac"), "-d", receiverClasses.toString(), fixture.resolve("module-info.java").toString(),
                receiver.toString(), wrapper.toString());
            checked(root, tool("jar"), "--create", "--file", modules.resolve("tooling.jar").toString(),
                "-C", receiverClasses.toString(), ".");
            Files.writeString(fixture.resolve("module-info.java"), "module ch.qos.logback.classic {}\n");
            Path loggingClasses = Files.createDirectory(root.resolve("logging-classes"));
            checked(root, tool("javac"), "-d", loggingClasses.toString(), fixture.resolve("module-info.java").toString());
            checked(root, tool("jar"), "--create", "--file", modules.resolve("logging.jar").toString(),
                "-C", loggingClasses.toString(), ".");
            Path driver = Files.createDirectory(root.resolve("driver-classes"));
            Path buildSource = Path.of(System.getProperty("basedir", ".")).resolve(
                "src/build/java/com/holdmyspot/codexunleashed/distribution/DistributionMain.java").toAbsolutePath();
            checked(root, tool("javac"), "--release", "27", "-Xlint:all", "-Werror", "-Xdoclint:all/private",
                "-d", driver.toString(), buildSource.toString());
            Path parent = Files.createDirectories(root.resolve("nested space/quote'\""));
            Path runtime = parent.resolve("runtime");
            checked(root, tool("java"), "-cp", driver.toString(),
                "com.holdmyspot.codexunleashed.distribution.DistributionMain", modules.toString(), runtime.toString(), Path.of(System.getProperty("basedir"),
                    "src/main/resources/com/holdmyspot/codexunleashed/scheduler").toAbsolutePath().toString());

            java.util.Map<String, String> supplied = new java.util.LinkedHashMap<>();
            supplied.put("INPUT_UPTERM-VERSION", "one");
            supplied.put("INPUT_WAIT-TIMEOUT-MINUTES", "");
            supplied.put("INPUT_LIMIT-ACCESS-TO-ACTOR", "first\nsecond");
            supplied.put("INPUT_KNOWN-HOSTS", "spaces 'single' \"double\" $literal");
            supplied.put("INPUT_LIMIT-ACCESS-TO-USERS", "five");
            supplied.put("INPUT_UPTERM-SERVER", "six");
            supplied.put("NORMAL", "normal value");
            supplied.put("IFS", "original separators");
            supplied.put("SHELLOPTS", "braceexpand");
            supplied.put("BASHOPTS", "checkwinsize");
            supplied.put("SHLVL", "97");
            supplied.put("_", "original underscore");
            supplied.put("LD_LIBRARY_PATH", "/synthetic/original/library");
            supplied.put("_JPACKAGE_LAUNCHER", "");
            supplied.put("DIR", "original directory");
            supplied.put("LAUNCHER", "original launcher");
            supplied.put("JAVA_TEMP", "original Java temporary value");
            supplied.put("TMPDIR", root.toString());
            supplied.put("PATH", "/usr/bin:/bin");
            List<java.util.Map<String, String>> observations = new ArrayList<>();
            List<String> arguments = List.of("argument with spaces", "", "--literal=$value", "line one\nline two");
            for (String name : List.of("codex-tooling", "codex-cargo-wrapper-linux"))
            {
                Path captured = root.resolve(name + ".properties");
                List<String> command = new ArrayList<>(List.of(runtime.resolve("bin").resolve(name).toString(),
                    captured.toString()));
                command.addAll(arguments);
                ProcessBuilder launch = new ProcessBuilder(command).directory(root.toFile());
                launch.environment().clear();
                launch.environment().putAll(supplied);
                Path output = root.resolve(name + "-stdout.log");
                Path error = root.resolve(name + "-stderr.log");
                launch.redirectOutput(output.toFile()).redirectError(error.toFile());
                try (Process child = launch.start())
                {
                    try (java.io.OutputStream input = child.getOutputStream())
                    {
                        input.write("authentic stdin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    int exit = child.waitFor();
                    if (exit != 23)
                    {
                        System.err.println(Files.readString(output));
                        System.err.println(Files.readString(error));
                    }
                    requireThat(exit, "actualLauncherExit").isEqualTo(23);
                }
                java.util.Properties result = new java.util.Properties();
                try (java.io.InputStream input = Files.newInputStream(captured)) { result.load(input); }
                requireThat(result.getProperty("stdin"), "actualStandardInput").isEqualTo("authentic stdin\n");
                requireThat(Files.readString(output), "actualStandardOutput").isEqualTo("authentic stdout");
                String diagnostic = Files.readString(error);
                requireThat(diagnostic.contains("authentic stderr"), "actualStandardError").isTrue();
                requireThat(diagnostic.contains("authentic stdout"), "standardOutputAbsentFromError").isFalse();
                System.out.println(name + " captured " + result);
                java.util.Map<String, String> observed = new java.util.LinkedHashMap<>();
                for (String key : supplied.keySet()) observed.put(key, result.getProperty("env." + key));
                observations.add(observed);
                for (int index = 0; index < arguments.size(); ++index)
                    requireThat(result.getProperty("arg." + index), "actualUserArgument").isEqualTo(arguments.get(index));
            }
            requireThat(observations, "bothActualLauncherEnvironments").isEqualTo(List.of(supplied, supplied));
        }
    }

    /**
     * Finds a dependency JAR that the build placed on this JVM's module or class path.
     * @param name the JAR's file name
     * @return the JAR
     * @throws IOException if no path entry has that name
     */
    private static Path dependencyJar(String name) throws IOException
    {
        for (String property : List.of("jdk.module.path", "java.class.path"))
            for (String entry : System.getProperty(property, "").split(java.io.File.pathSeparator))
                if (!entry.isEmpty() && Path.of(entry).getFileName().toString().equals(name))
                    return Path.of(entry);
        throw new IOException("The build did not supply " + name);
    }

    /**
     * Resolves a required tool from the actual selected JDK, including native Windows spelling.
     * @param name tool basename
     * @return absolute JDK tool path
     */
    private static String tool(String name)
    {
        return Path.of(System.getProperty("java.home"), "bin",
            name + (java.io.File.separatorChar == 92 ? ".exe" : "")).toString();
    }

    /**
     * Runs a real packaging operation in an owned fixture and retains a failed result in the assertion.
     * @param root owned diagnostic parent
     * @param arguments exact process arguments
     * @return exact UTF-8 standard output
     * @throws Exception if acquisition, execution, collection or status validation fails
     */
    private static String checked(Path root, String... arguments) throws Exception
    {
        Path stdout = Files.createTempFile(root, "stdout-", ".log");
        Path stderr = Files.createTempFile(root, "stderr-", ".log");
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        try (Process child = builder.start())
        {
            int status = child.waitFor();
            if (status != 0) System.err.print(Files.readString(stderr));
            requireThat(status, "packagingStatus").isEqualTo(0);
        }
        return Files.readString(stdout);
    }

    /**
     * Records all original ZIP entries, excluding only the newly supplied module descriptor.
     * @param modules owned staged dependencies
     * @return exact payload hash indexed by artifact and entry
     * @throws Exception if enumeration or hashing fails
     */
    private static java.util.Map<String, java.util.Map<String, String>> libraryPayloads(Path modules) throws Exception
    {
        java.util.Map<String, java.util.Map<String, String>> result = new java.util.TreeMap<>();
        try (var files = Files.list(modules))
        {
            for (Path file : files.toList())
            {
                java.util.Map<String, String> entries = new java.util.TreeMap<>();
                try (var jar = new java.util.jar.JarFile(file.toFile()))
                {
                    for (var entry : jar.stream().filter(value -> !value.getName().equals("module-info.class")).toList())
                        try (var input = jar.getInputStream(entry))
                        {
                            entries.put(entry.getName(), java.util.HexFormat.of().formatHex(
                                java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
                        }
                }
                result.put(file.getFileName().toString(), entries);
            }
        }
        return result;
    }

	/**
	 * Invokes the actual generated companion by its absolute path, preserving exact user arguments.
	 * @throws Exception if fixture assembly or execution fails
	 */
	@Test
	public void absoluteCompanionResolvesBundledJava() throws Exception
	{
		verifyLauncher(true);
	}

	/**
	 * Invokes the actual generated companion through a nested relative path.
	 * @throws Exception if fixture assembly or execution fails
	 */
	@Test
	public void nestedRelativeCompanionResolvesBundledJava() throws Exception
	{
		verifyLauncher(false);
	}

	/**
	 * Exercises production staging and shell execution without changing shared platform properties.
	 * @param absolute whether the actual invocation uses an absolute path
	 * @throws Exception if fixture IO or process execution fails
	 */
	private static void verifyLauncher(boolean absolute) throws Exception
	{
		if (!System.getProperty("os.name").equals("Linux"))
			throw new SkipException("Generated POSIX companion requires Linux; no shared platform properties are changed");
		try (Directory directory = new Directory())
		{
			Path root = directory.path;
			Path modules = Files.createDirectory(root.resolve("modules"));
			try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(modules.resolve("fixture.jar"))))
			{
				jar.finish();
			}
			Path runtime = Files.createDirectories(root.resolve("nested/runtime"));
			Path bin = Files.createDirectory(runtime.resolve("bin"));
			Files.createDirectory(runtime.resolve("lib"));
			Path receiver = bin.resolve("java");
			Files.writeString(receiver, "#!/bin/sh\nset -eu\nprintf '%s\\000' \"$@\" > \"$RECEIVER_OUTPUT\"\n");
			Files.setPosixFilePermissions(receiver, PosixFilePermissions.fromString("rwxr-xr-x"));
			Path received = root.resolve("received-argv");
			Path stageLog = root.resolve("stage.log");
			Path stagingClasses = Files.createDirectory(root.resolve("staging-classes"));
			Path stagingSource = root.resolve("CompanionStage.java");
			Files.writeString(stagingSource, """
				/** Invokes the actual build-only companion generator in an independent fixture process. */
				public final class CompanionStage
				{
				    /** Prevents construction. */
				    private CompanionStage() {}
				    /**
				     * Stages the real companion using the three independently owned fixture paths.
				     * @param args modules, runtime and backend paths
				     * @throws Exception if the actual generator fails
				     */
				    public static void main(String[] args) throws Exception
				    {
				        com.holdmyspot.codexunleashed.distribution.DistributionMain.stageBazelCompanion(
				            java.nio.file.Path.of(args[0]), java.nio.file.Path.of(args[1]), java.nio.file.Path.of(args[2]));
				    }
				}
				""");
			Path buildSource = Path.of(System.getProperty("basedir", ".")).resolve(
				"src/build/java/com/holdmyspot/codexunleashed/distribution/DistributionMain.java").toAbsolutePath();
			ProcessBuilder compile = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/javac").toString(),
				"--release", "27", "-Xlint:all", "-Werror", "-Xdoclint:all/private", "-d", stagingClasses.toString(),
				buildSource.toString(), stagingSource.toString());
			compile.redirectErrorStream(true).redirectOutput(stageLog.toFile());
			try (Process child = compile.start())
			{
				int exit = child.waitFor();
				if (exit != 0) System.err.println(Files.readString(stageLog));
				requireThat(exit, "buildSourceCompilationExit").isEqualTo(0);
			}
			ProcessBuilder stage = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
				"-Dos.arch=aarch64", "-cp", stagingClasses.toString(), "CompanionStage", modules.toString(),
				runtime.toString(), root.resolve("backend").toString());
			stage.environment().put("RECEIVER_OUTPUT", received.toString());
			stage.redirectErrorStream(true).redirectOutput(stageLog.toFile());
			try (Process child = stage.start())
			{
				requireThat(child.waitFor(), "productionStagingExit").isEqualTo(0);
			}
			Files.delete(received);
			Path launcher = bin.resolve("codex-tooling-bazel");
			String invocation = absolute ? launcher.toString() : root.relativize(launcher).toString();
			List<String> command = new ArrayList<>(List.of(invocation, "arg with spaces", "", "--literal=$value"));
			Path log = root.resolve("launcher.log");
			ProcessBuilder launch = new ProcessBuilder(command).directory(root.toFile());
			launch.environment().put("RECEIVER_OUTPUT", received.toString());
			launch.environment().put("TMPDIR", root.resolve("temp with spaces").toString());
			launch.redirectErrorStream(true).redirectOutput(log.toFile());
			try (Process child = launch.start())
			{
				int exit = child.waitFor();
				if (exit != 0) System.err.println(Files.readString(log));
				requireThat(exit, "generatedLauncherExit").isEqualTo(0);
			}
			List<String> actual = Arrays.asList(new String(Files.readAllBytes(received), StandardCharsets.UTF_8).split("\u0000", -1));
			List<String> expected = List.of("-Djava.io.tmpdir=" + root.resolve("temp with spaces"),
				"-Xlog:all=off:stdout", "-Xlog:all=warning:stderr", "--enable-native-access=ALL-UNNAMED", "-cp",
				bin + "/../lib/bazel-backend/codex-tooling.jar", "com.holdmyspot.codexunleashed.tooling.Main",
				"arg with spaces", "", "--literal=$value", "");
			requireThat(actual, "exactGeneratedLauncherArguments").isEqualTo(expected);
		}
	}

	/** Owns and removes one test's filesystem independently. */
	private static final class Directory implements AutoCloseable
	{
		/** Exclusive absolute fixture root. */
		private final Path path;

		/**
		 * Creates one exclusive fixture.
		 * @throws IOException if the fixture cannot be created
		 */
		private Directory() throws IOException
		{
			path = Files.createTempDirectory("distribution-launcher-").toAbsolutePath();
		}

		/**
		 * Removes only this fixture's paths.
		 * @throws IOException if owned fixture removal fails
		 */
		@Override
		public void close() throws IOException
		{
			try (var files = Files.walk(path))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
