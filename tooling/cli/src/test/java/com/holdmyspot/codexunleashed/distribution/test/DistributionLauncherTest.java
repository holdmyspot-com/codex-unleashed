package com.holdmyspot.codexunleashed.distribution.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.holdmyspot.codexunleashed.tooling.QuietBuildDump;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingLauncher;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies the generated tooling launcher with independent, nonnative Java receiver fixtures. */
public final class DistributionLauncherTest
{
	/** The TOML parser's JAR, which is already an explicit module and needs the other Jackson modules. */
	private static final String TOML_JAR = "jackson-dataformat-toml-3.3.0.jar";
	/** The Jackson JARs that the TOML parser requires, which the distribution stages alongside it. */
	private static final List<String> TOML_DEPENDENCY_JARS = List.of("jackson-core-3.3.0.jar",
		"jackson-databind-3.3.0.jar", "jackson-annotations-2.23.jar");

	/** Creates independently owned launcher checks. */
	public DistributionLauncherTest()
	{
	}

    /**
     * Verifies that the staged TOML parser is an explicit module that resolves with the Jackson modules the
     * distribution stages alongside it, so the distribution needs no module descriptor generation.
     * <p>
     * Resolution uses the JDK's module system, which jlink also uses; the JDK's own tools are trusted to work as
     * documented, so the test neither links an image nor runs the parser.
     *
     * @throws Exception if module staging, resolution or independent cleanup fails
     */
    @Test
    public void stagedTomlIsExplicitModule() throws Exception
    {
        try (Directory directory = new Directory())
        {
            Path modules = Files.createDirectory(directory.path.resolve("modules"));
            Files.copy(dependencyJar(TOML_JAR), modules.resolve(TOML_JAR));
            for (String jar : TOML_DEPENDENCY_JARS)
                Files.copy(dependencyJar(jar), modules.resolve(jar));
            java.lang.module.ModuleFinder finder = java.lang.module.ModuleFinder.of(modules);
            java.util.Set<java.lang.module.ModuleReference> references = finder.findAll();
            requireThat(references.size(), "stagedModuleCount").isEqualTo(1 + TOML_DEPENDENCY_JARS.size());
            for (java.lang.module.ModuleReference reference : references)
                requireThat(reference.descriptor().isAutomatic(), reference.descriptor().name() + ".isAutomatic()").
                    isFalse();
            java.lang.module.Configuration configuration = ModuleLayer.boot().configuration().resolve(finder,
                java.lang.module.ModuleFinder.of(), java.util.Set.of("tools.jackson.dataformat.toml"));
            requireThat(configuration.modules().size(), "resolvedModuleCount").isEqualTo(references.size());
        }
    }

    /**
     * Preserves supplied environment entries and arguments through the generated Linux tooling launcher.
     * <p>
     * An independent Java receiver observes the values after the packaged launcher has executed. The shell launcher
     * assigns {@code LAUNCHER}, {@code DIR} and {@code JAVA_TEMP}, a POSIX shell resets {@code IFS}, and a shell drops
     * variables whose names it cannot represent, so the fixture supplies only other portable names.
     *
     * @throws Exception if fixture compilation, production packaging, launch or cleanup fails
     */
    @Test
    public void linuxLauncherPreservesInvocation() throws Exception
    {
        if (!System.getProperty("os.name").equals("Linux"))
            throw new SkipException("Linux launcher preservation requires actual Linux");
        try (Directory directory = new Directory())
        {
            Path root = directory.path;
            RuntimeFixture fixture = createRuntime(root);
            Path runtime = fixture.runtime();

            Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put("NORMAL", "spaces 'single' \"double\" $literal");
            supplied.put("MULTILINE", "first\nsecond");
            supplied.put("EMPTY", "");
            supplied.put("SHELLOPTS", "braceexpand");
            supplied.put("BASHOPTS", "checkwinsize");
            supplied.put("SHLVL", "97");
            supplied.put("_", "original underscore");
            supplied.put("LD_LIBRARY_PATH", "/synthetic/original/library");
            supplied.put("_JPACKAGE_LAUNCHER", "");
            supplied.put("TMPDIR", root.toString());
            supplied.put("PATH", "/usr/bin:/bin");
            List<String> arguments = List.of("argument with spaces", "", "--literal=$value", "line one\nline two");

            Path captured = root.resolve("codex-tooling.properties");
            List<String> command = new ArrayList<>(List.of(runtime.resolve("bin/codex-tooling").toString(),
                captured.toString()));
            command.addAll(arguments);
            ProcessBuilder launch = new ProcessBuilder(command).directory(root.toFile());
            launch.environment().clear();
            launch.environment().putAll(supplied);
            Path output = root.resolve("codex-tooling-stdout.log");
            Path error = root.resolve("codex-tooling-stderr.log");
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
            try (java.io.InputStream input = Files.newInputStream(captured))
            {
                result.load(input);
            }
            requireThat(result.getProperty("stdin"), "actualStandardInput").isEqualTo("authentic stdin\n");
            requireThat(result.getProperty("launcher"), "actualPackagedLauncher").
                isEqualTo(runtime.resolve("bin/codex-tooling").toString());
            requireThat(Files.readString(output), "actualStandardOutput").isEqualTo("authentic stdout");
            String diagnostic = Files.readString(error);
            requireThat(diagnostic, "actualStandardError").contains("authentic stderr");
            requireThat(diagnostic, "standardOutputAbsentFromError").doesNotContain("authentic stdout");
            Map<String, String> observed = new LinkedHashMap<>();
            for (String key : supplied.keySet()) observed.put(key, result.getProperty("env." + key));
            requireThat(observed, "actualLauncherEnvironment").isEqualTo(supplied);
            for (int index = 0; index < arguments.size(); ++index)
                requireThat(result.getProperty("arg." + index), "actualUserArgument").isEqualTo(arguments.get(index));
        }
    }

    /**
     * Preserves literal Java arguments, JVM properties and separate process streams through the packaged Windows
     * runtime's direct Java entry.
     * <p>
     * The fixture uses the production Windows argument encoder and a receiver packaged independently by jlink. A
     * non-Windows host skips this test; that result does not establish native Windows acceptance.
     *
     * @throws Exception if fixture compilation, production packaging, launch or cleanup fails
     */
    @Test
    public void windowsJavaPreservesInvocation() throws Exception
    {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new SkipException("Windows launcher preservation requires actual Windows");
        try (Directory directory = new Directory("distribution launcher '100% & !-"))
        {
            Path root = directory.path;
            RuntimeFixture fixture = createRuntime(root);
            Path launcher = fixture.launcher();
            Path temporaryDirectory = Files.createDirectory(root.resolve("temporary directory '100% & !"));
            Path captured = root.resolve("receiver output.properties");
            List<String> arguments = List.of("", "whitespace value", "line one\nline two",
                "embedded \"double quotes\"", "trailing backslash\\", "%CODEX_LITERAL%", "ampersand&",
                "pipe|", "caret^", "exclamation!", "dollar$", "parentheses()", "percent%middle%");
            List<String> command = new ArrayList<>(ToolingLauncher.command(QuietBuildDump.Platform.WINDOWS,
                launcher, temporaryDirectory));
            command.add(captured.toString());
            command.addAll(arguments);

            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isEmpty())
                throw new IllegalStateException("Windows SystemRoot is required to start the packaged Java runtime");
            Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put("SystemRoot", systemRoot);
            supplied.put("CODEX_LITERAL", "expanded-value");

            ProcessBuilder launch = SystemCommands.createBuilder(command).directory(root.toFile());
            launch.environment().clear();
            launch.environment().putAll(supplied);
            Path inputPath = root.resolve("receiver-stdin.txt");
            Path output = root.resolve("receiver-stdout.log");
            Path error = root.resolve("receiver-stderr.log");
            launch.redirectInput(inputPath.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile());
            Files.writeString(inputPath, "windows receiver input\n");
            try (Process child = launch.start())
            {
                int exit = child.waitFor();
                if (exit != 23)
                {
                    System.err.println(Files.readString(output));
                    System.err.println(Files.readString(error));
                }
                requireThat(exit, "actualWindowsJavaExit").isEqualTo(23);
            }

            java.util.Properties result = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(captured))
            {
                result.load(input);
            }
            requireThat(result.getProperty("stdin"), "actualWindowsStandardInput").
                isEqualTo("windows receiver input\n");
            requireThat(result.getProperty("launcher"), "actualWindowsPackagedLauncher").isEqualTo(launcher.toString());
            requireThat(result.getProperty("temporaryDirectory"), "actualWindowsTemporaryDirectory").
                isEqualTo(temporaryDirectory.toString());
            requireThat(result.getProperty("allowAmbiguousCommands"), "actualWindowsArgumentEncodingProperty").
                isEqualTo("true");
            requireThat(Files.readString(output), "actualWindowsStandardOutput").isEqualTo("authentic stdout");
            String diagnostic = Files.readString(error);
            requireThat(diagnostic, "actualWindowsStandardError").contains("authentic stderr");
            requireThat(diagnostic, "standardOutputAbsentFromWindowsError").doesNotContain("authentic stdout");
            requireThat(result.getProperty("argumentCount"), "actualWindowsArgumentCount").
                isEqualTo(Integer.toString(arguments.size()));
            for (int index = 0; index < arguments.size(); ++index)
                requireThat(result.getProperty("arg." + index), "actualWindowsUserArgument").
                    isEqualTo(arguments.get(index));
            requireThat(result.getProperty("env.CODEX_LITERAL"), "actualWindowsControlledEnvironment").
                isEqualTo("expanded-value");
            requireThat(result.getProperty("arg." + arguments.indexOf("%CODEX_LITERAL%")),
                "percentArgumentRemainsLiteral").isEqualTo("%CODEX_LITERAL%");
        }
    }

    /**
     * Converts an MSYS temporary path before passing it to the JVM, as Git for Windows requires, and gives the JVM
     * the converted path of the launcher.
     * <p>
     * A {@code cygpath} adapter on the search path stands in for MSYS: it maps the fixture's MSYS spelling to a
     * native directory. The adapter needs a POSIX shell, so other hosts skip the test.
     *
     * @throws Exception if fixture compilation, production packaging, launch or cleanup fails
     */
    @Test
    public void convertsMsysTemporaryDirectory() throws Exception
    {
        if (!Path.of("/bin/sh").toFile().canExecute())
            throw new SkipException("The cygpath adapter requires a POSIX shell");
        try (Directory directory = new Directory())
        {
            Path root = directory.path;
            RuntimeFixture fixture = createRuntime(root);
            Path adapters = Files.createDirectory(root.resolve("adapters"));
            Path cygpath = adapters.resolve("cygpath");
            Files.writeString(cygpath, """
                #!/bin/sh
                if [ "$1" != -m ]; then echo "Expected -m, received $1" >&2; exit 2; fi
                case "$2" in
                    /hosted-msys-temporary) printf '%s\\n' "$FIXTURE_NATIVE_TEMP" ;;
                    *) printf '%s\\n' "$2" ;;
                esac
                """);
            requireThat(cygpath.toFile().setExecutable(true), "adapterExecutable").isTrue();
            Path nativeTemporary = Files.createDirectory(root.resolve("native temporary"));
            Path captured = root.resolve("msys.properties");
            ProcessBuilder launch = new ProcessBuilder("/bin/sh", fixture.launcher().toString(), captured.toString()).
                directory(root.toFile()).redirectErrorStream(true);
            launch.environment().clear();
            launch.environment().put("PATH", adapters + java.io.File.pathSeparator + "/usr/bin:/bin");
            launch.environment().put("TMPDIR", "/hosted-msys-temporary");
            launch.environment().put("FIXTURE_NATIVE_TEMP", nativeTemporary.toString());
            Path output = root.resolve("msys-output.log");
            launch.redirectOutput(output.toFile());
            try (Process child = launch.start())
            {
                child.getOutputStream().close();
                int exit = child.waitFor();
                if (exit != 23)
                    System.err.println(Files.readString(output));
                requireThat(exit, "actualLauncherExit").isEqualTo(23);
            }
            java.util.Properties result = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(captured))
            {
                result.load(input);
            }
            requireThat(result.getProperty("temporaryDirectory"), "actualTemporaryDirectory").
                isEqualTo(nativeTemporary.toString());
            requireThat(result.getProperty("launcher"), "actualPackagedLauncher").
                isEqualTo(fixture.launcher().toString());
        }
    }

    /**
     * Builds an independently owned receiver module and jlink runtime beneath one fixture root.
     *
     * @param root owned fixture root
     * @return the runtime and its packaged launcher identity
     * @throws Exception if compilation or packaging fails
     */
    private static RuntimeFixture createRuntime(Path root) throws Exception
    {
        Path modules = Files.createDirectory(root.resolve("modules"));
        Path source = Files.createDirectory(root.resolve("receiver-source"));
        Path receiver = source.resolve("Main.java");
        Files.writeString(receiver, """
            package com.holdmyspot.codexunleashed.tooling;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    var result = new java.util.Properties();
                    result.setProperty("launcher", System.getProperty("codex.tooling.launcher", ""));
                    result.setProperty("temporaryDirectory", System.getProperty("java.io.tmpdir", ""));
                    result.setProperty("allowAmbiguousCommands",
                        System.getProperty("jdk.lang.Process.allowAmbiguousCommands", ""));
                    String standardInput = new String(System.in.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    result.setProperty("stdin", standardInput);
                    System.out.print("authentic stdout");
                    System.err.print("authentic stderr");
                    System.getenv().forEach((name, value) -> result.setProperty("env." + name, value));
                    result.setProperty("argumentCount", Integer.toString(args.length - 1));
                    for (int i = 1; i < args.length; ++i) result.setProperty("arg." + (i - 1), args[i]);
                    try (var output = java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0]))) {
                        result.store(output, "Independent actual child environment and arguments");
                    }
                    System.exit(23);
                }
            }
            """);
        Files.writeString(source.resolve("module-info.java"),
            "module com.holdmyspot.codexunleashed.tooling {}\n");
        Path receiverClasses = Files.createDirectory(root.resolve("receiver-classes"));
        checked(root, tool("javac"), "-d", receiverClasses.toString(), source.resolve("module-info.java").toString(),
            receiver.toString());
        checked(root, tool("jar"), "--create", "--file", modules.resolve("tooling.jar").toString(),
            "-C", receiverClasses.toString(), ".");
        Files.writeString(source.resolve("module-info.java"), "module ch.qos.logback.classic {}\n");
        Path loggingClasses = Files.createDirectory(root.resolve("logging-classes"));
        checked(root, tool("javac"), "-d", loggingClasses.toString(), source.resolve("module-info.java").toString());
        checked(root, tool("jar"), "--create", "--file", modules.resolve("logging.jar").toString(),
            "-C", loggingClasses.toString(), ".");
        Path driver = Files.createDirectory(root.resolve("driver-classes"));
        Path buildSource = Path.of(System.getProperty("basedir", ".")).resolve(
            "src/build/java/com/holdmyspot/codexunleashed/distribution/DistributionMain.java").toAbsolutePath();
        checked(root, tool("javac"), "--release", "27", "-Xlint:all", "-Werror", "-Xdoclint:all/private",
            "-d", driver.toString(), buildSource.toString());
        Path parent = Files.createDirectories(root.resolve("installation space '100% & !"));
        Path runtime = parent.resolve("runtime");
        checked(root, tool("java"), "-cp", driver.toString(),
            "com.holdmyspot.codexunleashed.distribution.DistributionMain", modules.toString(), runtime.toString());
        return new RuntimeFixture(runtime, runtime.resolve("bin/codex-tooling"));
    }

    /**
     * Finds a dependency JAR that the build placed on this JVM's module or class path.
     *
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
     *
     * @param name tool basename
     * @return absolute JDK tool path
     */
    private static String tool(String name)
    {
        String executableName = name;
        if (java.io.File.separatorChar == 92)
            executableName += ".exe";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    /**
     * Runs a real packaging operation in an owned fixture and retains a failed result in the assertion.
     *
     * @param root owned diagnostic parent
     * @param arguments exact process arguments
     * @return exact UTF-8 standard output
     * @throws Exception if acquisition, execution, collection or status validation fails
     */
	private static String checked(Path root, String... arguments) throws Exception
	{
        Path stdout = Files.createTempFile(root, "stdout-", ".log");
        Path stderr = Files.createTempFile(root, "stderr-", ".log");
        ProcessBuilder builder = new ProcessBuilder(arguments).
            redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        try (Process child = builder.start())
        {
            int status = child.waitFor();
            if (status != 0) System.err.print(Files.readString(stderr));
            requireThat(status, "packagingStatus").isEqualTo(0);
        }
		return Files.readString(stdout);
	}

	/**
	 * Independently packaged image paths owned by one test fixture.
	 *
	 * @param runtime the linked image root
	 * @param launcher the packaged wrapper identity
	 */
	private record RuntimeFixture(Path runtime, Path launcher)
	{
	}

	/** Owns and removes one test's filesystem independently. */
	private static final class Directory implements AutoCloseable
	{
		/** Exclusive absolute fixture root. */
		private final Path path;

		/**
		 * Creates one exclusive fixture.
		 *
		 * @throws IOException if the fixture cannot be created
		 */
		private Directory() throws IOException
		{
			this("distribution-launcher-");
		}

		/**
		 * Creates one exclusive fixture with a caller-selected, platform-legal path prefix.
		 *
		 * @param prefix platform-legal root name prefix
		 * @throws IOException if the fixture cannot be created
		 */
		private Directory(String prefix) throws IOException
		{
			path = Files.createTempDirectory(prefix).toAbsolutePath();
		}

		/**
		 * Removes only this fixture's paths.
		 *
		 * @throws IOException if owned fixture removal fails
		 */
		@Override
		public void close() throws IOException
		{
			try (java.util.stream.Stream<Path> files = Files.walk(path))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
