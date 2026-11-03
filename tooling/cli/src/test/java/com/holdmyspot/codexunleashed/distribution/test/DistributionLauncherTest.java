package com.holdmyspot.codexunleashed.distribution.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
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
     * Turns the staged TOML parser and ANTLR runtime into explicit modules that resolve without checker-qual,
     * keeping every original JAR entry unchanged.
     * <p>
     * Resolution uses the JDK's module system, which jlink also uses; the JDK's own tools are trusted to work as
     * documented, so the test neither links an image nor runs the parser.
     * @throws Exception if module staging, resolution or independent cleanup fails
     */
    @Test
    public void stagedTomlBecomesExplicitModules() throws Exception
    {
        try (Directory directory = new Directory())
        {
            Path modules = Files.createDirectory(directory.path.resolve("modules"));
            for (String jar : TOML_JARS)
                Files.copy(dependencyJar(jar), modules.resolve(jar));
            java.util.Map<String, java.util.Map<String, String>> original = libraryPayloads(modules);
            com.holdmyspot.codexunleashed.distribution.DistributionMain.modularizeToml(modules);
            requireThat(libraryPayloads(modules), "allOriginalLibraryEntries").isEqualTo(original);
            var finder = java.lang.module.ModuleFinder.of(modules.resolve("tomlj-1.1.1.jar"),
                modules.resolve("antlr4-runtime-4.11.1.jar"));
            for (var reference : finder.findAll())
                requireThat(reference.descriptor().isAutomatic(), reference.descriptor().name() + ".isAutomatic()")
                    .isFalse();
            var configuration = ModuleLayer.boot().configuration().resolve(finder,
                java.lang.module.ModuleFinder.of(), java.util.Set.of("org.tomlj"));
            requireThat(configuration.findModule("org.antlr.antlr4.runtime").isPresent(), "antlrResolved").isTrue();
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
	 * Stages the companion in-process: validates the backend through the given tool with the bundled Java, copies the
	 * build JDK's keytool and writes the companion script.
	 * @throws Exception if fixture IO or staging fails
	 */
	@Test
	public void stagesCompanionThroughBundledJava() throws Exception
	{
		if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
			throw new SkipException("The POSIX companion's mode requires a POSIX file system");
		try (Directory directory = new Directory())
		{
			Path root = directory.path;
			Path modules = Files.createDirectory(root.resolve("modules"));
			try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(modules.resolve("fixture.jar"))))
			{
				jar.finish();
			}
			Path runtime = Files.createDirectories(root.resolve("runtime"));
			Path bin = Files.createDirectory(runtime.resolve("bin"));
			Files.createDirectory(runtime.resolve("lib"));
			Path javaHome = root.resolve("jdk");
			Files.writeString(Files.createDirectories(javaHome.resolve("bin")).resolve("keytool"), "keytool image");
			Path backend = root.resolve("backend");
			Path temporary = root.resolve("temp with spaces");
			List<List<String>> commands = new ArrayList<>();
			com.holdmyspot.codexunleashed.distribution.DistributionMain.stageBazelCompanion(modules, runtime, backend, "Linux",
				javaHome, temporary, (command, diagnostic) ->
				{
					commands.add(command);
					return 0;
				});
			Path lib = runtime.resolve("lib/bazel-backend");
			requireThat(commands, "validationCommands").isEqualTo(List.of(List.of(bin.resolve("java").toString(),
				"-Djava.io.tmpdir=" + temporary, "-cp", lib.resolve("codex-tooling.jar").toString(),
				"com.holdmyspot.codexunleashed.scheduler.BazelNativeBackend", backend.toString(),
				lib.resolve("native").toString())));
			requireThat(Files.readString(bin.resolve("keytool")), "copiedKeytool").isEqualTo("keytool image");
			Path launcher = bin.resolve("codex-tooling-bazel");
			requireThat(Files.readString(launcher), "companion").isEqualTo(
				com.holdmyspot.codexunleashed.distribution.DistributionMain.companionScript(""));
			requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(launcher)), "companionMode")
				.isEqualTo("rwxr-xr-x");
			requireThat(Files.exists(bin.resolve("codex-tooling-bazel.cmd")), "windowsCompanion").isFalse();
		}
	}

	/**
	 * Reports a failed backend validation with its status and diagnostic, before writing any launcher.
	 * @throws Exception if fixture IO fails
	 */
	@Test
	public void failedValidationStopsStaging() throws Exception
	{
		try (Directory directory = new Directory())
		{
			Path root = directory.path;
			Path modules = Files.createDirectory(root.resolve("modules"));
			try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(modules.resolve("fixture.jar"))))
			{
				jar.finish();
			}
			Path runtime = Files.createDirectories(root.resolve("runtime"));
			Files.createDirectory(runtime.resolve("bin"));
			Files.createDirectory(runtime.resolve("lib"));
			try
			{
				com.holdmyspot.codexunleashed.distribution.DistributionMain.stageBazelCompanion(modules, runtime,
					root.resolve("backend"), "Linux", root.resolve("jdk"), root, (command, diagnostic) ->
					{
						Files.writeString(diagnostic, "unsupported host");
						return 7;
					});
				throw new AssertionError("Staging accepted a failed validation");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failure").isEqualTo(
					"Native backend package validation failed with status 7: unsupported host");
			}
			requireThat(Files.exists(runtime.resolve("bin/codex-tooling-bazel")), "launcherWritten").isFalse();
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
