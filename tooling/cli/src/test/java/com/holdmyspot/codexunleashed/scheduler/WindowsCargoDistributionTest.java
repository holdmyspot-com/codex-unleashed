package com.holdmyspot.codexunleashed.scheduler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the distribution builder and its Windows executable at the native process boundary.
 * The fixture module checks launcher behavior; it does not model a Cargo session or compiler.
 */
public final class WindowsCargoDistributionTest
{
	/**
	 * Creates independent distribution checks.
	 */
	public WindowsCargoDistributionTest()
	{
	}

	/**
	 * Packages a native console launcher whose native UTF16 ingress preserves arguments, binary pipes and status.
	 * The caller supplies its app directory in PATH so the launcher retains the original command line.
	 * Native process creation acceptance additionally covers backslashes before literal quotes: this JDK
	 * ProcessBuilder changes that combination before the packaged launcher receives it.
	 *
	 * @throws Exception if fixture compilation, packaging, process execution or cleanup fails
	 */
	@Test
	public void launchesPackagedConsoleWithExactArgumentsAndBinaryStreams() throws Exception
	{
		if (File.separatorChar == '/')
			throw new SkipException("The Windows executable requires a Windows worker");
		String configuredSource = System.getProperty("codex.distribution.source");
		if (configuredSource == null)
			throw new SkipException("Set codex.distribution.source to the distribution builder source");
		Path distributionSource = Path.of(configuredSource).toAbsolutePath();
		Path fixture = Files.createTempDirectory("windows-cargo-distribution-");
		try
		{
			Path modules = fixture.resolve("modules");
			Files.createDirectory(modules);
			compileFixtureModules(fixture, modules);
			Path image = fixture.resolve("runtime");
			run(fixture, "distribution", List.of(tool("java").toString(),
				"-Djava.io.tmpdir=" + fixture, distributionSource.toString(), modules.toString(), image.toString()));

			Path wrapper = image.resolve("wrapper-image/codex-cargo-wrapper/codex-cargo-wrapper.exe");
			List<String> arguments = List.of("rustc path.exe", "", "two words", "a\\b", "尾\\");
			List<String> command = new ArrayList<>();
			command.add(wrapper.toString());
			command.addAll(arguments);
			Path output = fixture.resolve("wrapper.stdout");
			Path error = fixture.resolve("wrapper.stderr");
			byte[] binary = new byte[256];
			for (int index = 0; index < binary.length; index++)
				binary[index] = (byte) index;
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.directory(fixture.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile());
			builder.environment().put("PATH", wrapper.getParent().resolve("app").toString());
			try (Process process = builder.start())
			{
				try (OutputStream input = process.getOutputStream())
				{
					input.write(binary);
				}
				requireThat(process.waitFor(), "wrapperExit").isEqualTo(37, "nativeExit");
			}
			StringBuilder prefix = new StringBuilder();
			for (String argument : arguments)
				prefix.append(argument.length()).append(':').append(argument).append('\n');
			byte[] prefixBytes = prefix.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			byte[] expected = Arrays.copyOf(prefixBytes, prefixBytes.length + binary.length);
			System.arraycopy(binary, 0, expected, prefixBytes.length, binary.length);
			requireThat(java.util.HexFormat.of().formatHex(Files.readAllBytes(output)), "actualOutput")
				.isEqualTo(java.util.HexFormat.of().formatHex(expected), "expectedOutput");
			requireThat(Files.readString(error).contains("fixture-stderr"), "separateStandardError").isTrue();
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(fixture))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Compiles minimal modules consumed by the real distribution command and native launcher.
	 *
	 * @param fixture the test-owned source and diagnostics directory
	 * @param modules the compiled module destination
	 * @throws Exception if compilation fails
	 */
	private static void compileFixtureModules(Path fixture, Path modules) throws Exception
	{
		Path source = Files.createDirectory(fixture.resolve("source"));
		Path tooling = source.resolve("com.holdmyspot.codexunleashed.tooling");
		Files.createDirectories(tooling.resolve("com/holdmyspot/codexunleashed/scheduler"));
		Files.writeString(tooling.resolve("module-info.java"), "module com.holdmyspot.codexunleashed.tooling {}\n");
		Files.createDirectories(tooling.resolve("com/holdmyspot/codexunleashed/tooling"));
		Files.writeString(tooling.resolve("com/holdmyspot/codexunleashed/tooling/Main.java"),
			"package com.holdmyspot.codexunleashed.tooling; public final class Main { public static void main(String[] args) {} }\n");
		Path main = tooling.resolve("com/holdmyspot/codexunleashed/scheduler/WindowsCargoWrapperMain.java");
		Files.writeString(main, """
			package com.holdmyspot.codexunleashed.scheduler;
			import java.lang.foreign.Arena;
			import java.lang.foreign.FunctionDescriptor;
			import java.lang.foreign.Linker;
			import java.lang.foreign.MemorySegment;
			import java.lang.foreign.SymbolLookup;
			import java.lang.foreign.ValueLayout;
			import java.lang.invoke.MethodHandle;
			import java.nio.charset.StandardCharsets;

			/**
			 * Reports exact native arguments and binary streams through the packaged launcher.
			 */
			public final class WindowsCargoWrapperMain
			{
			    /**
			     * Reports native arguments, copies binary input and exits with native status 37.
			     * @param ignored arguments converted by the JVM, replaced with native UTF16 arguments
			     * @throws Throwable if native ingress or stream transfer fails
			     */
			    public static void main(String[] ignored) throws Throwable
			    {
			        for (String argument : readNativeArguments())
			            System.out.write((argument.length() + ":" + argument + "\\n").getBytes(StandardCharsets.UTF_8));
			        System.in.transferTo(System.out);
			        System.out.flush();
			        System.err.print("fixture-stderr");
			        System.err.flush();
			        System.exit(37);
			    }

			    /**
			     * Reads application arguments from the current process's native UTF16 command line.
			     * @return application arguments, excluding the executable path
			     * @throws Throwable if native parsing or LocalFree fails
			     */
			    private static String[] readNativeArguments() throws Throwable
			    {
			        try (Arena arena = Arena.ofConfined())
			        {
			            Linker linker = Linker.nativeLinker();
			            SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", arena);
			            SymbolLookup shell = SymbolLookup.libraryLookup("shell32", arena);
			            MethodHandle commandLine = linker.downcallHandle(kernel.find("GetCommandLineW").orElseThrow(),
			                FunctionDescriptor.of(ValueLayout.ADDRESS));
			            MethodHandle parse = linker.downcallHandle(shell.find("CommandLineToArgvW").orElseThrow(),
			                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			            MethodHandle free = linker.downcallHandle(kernel.find("LocalFree").orElseThrow(),
			                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			            MemorySegment line = (MemorySegment) commandLine.invokeExact();
			            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
			            MemorySegment arguments = (MemorySegment) parse.invokeExact(line, count);
			            if (arguments.address() == 0) throw new IllegalStateException("CommandLineToArgvW returned null");
			            try
			            {
			                int length = count.get(ValueLayout.JAVA_INT, 0);
			                MemorySegment array = arguments.reinterpret((long) length * ValueLayout.ADDRESS.byteSize());
			                String[] result = new String[length - 1];
			                for (int index = 1; index < length; index++)
			                {
			                    MemorySegment element = array.getAtIndex(ValueLayout.ADDRESS, index);
			                    MemorySegment text = element.reinterpret(65536);
			                    int characters = 0;
			                    while (text.getAtIndex(ValueLayout.JAVA_CHAR, characters) != 0) characters++;
			                    byte[] bytes = text.asSlice(0, (long) characters * Character.BYTES).toArray(ValueLayout.JAVA_BYTE);
			                    result[index - 1] = new String(bytes, StandardCharsets.UTF_16LE);
			                }
			                return result;
			            }
			            finally
			            {
			                MemorySegment failure = (MemorySegment) free.invokeExact(arguments);
			                if (failure.address() != 0) throw new IllegalStateException("LocalFree failed");
			            }
			        }
			    }
			}
			""");
		Path logging = Files.createDirectory(source.resolve("ch.qos.logback.classic"));
		Files.writeString(logging.resolve("module-info.java"), "module ch.qos.logback.classic {}\n");
		run(fixture, "compile", List.of(tool("javac").toString(), "--module-source-path", source.toString(),
			"-d", modules.toString(), "-m", "com.holdmyspot.codexunleashed.tooling,ch.qos.logback.classic"));
	}

	/**
	 * Waits for a build command while keeping its independent diagnostics until test cleanup.
	 *
	 * @param fixture the test-owned working directory
	 * @param name the diagnostics file prefix
	 * @param command the exact executable and argument vector
	 * @throws IOException if process creation fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static void run(Path fixture, String name, List<String> command) throws IOException, InterruptedException
	{
		Path diagnostics = fixture.resolve(name + ".log");
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(diagnostics.toFile());
		try (Process process = builder.start())
		{
			int status = process.waitFor();
			if (status != 0)
				throw new IOException("Build command failed with status " + status + ": " + Files.readString(diagnostics));
		}
	}

	/**
	 * Locates a tool in the JDK executing this test.
	 *
	 * @param name the JDK executable name without its Windows extension
	 * @return the Windows JDK executable
	 */
	private static Path tool(String name)
	{
		return Path.of(System.getProperty("java.home"), "bin", name + ".exe");
	}
}
