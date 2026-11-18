package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the {@code resolve-zstd} command through the public tooling entry point.
 */
public final class PackageZstdCommandTest
{
	/**
	 * Executable suffix that the fixture environment's {@code PATHEXT} lists on Windows.
	 */
	private static final String SUFFIX = suffix();
	/** Usage line that every rejected invocation starts its diagnostics with. */
	private static final String USAGE = "Usage: codex-tooling resolve-zstd --upstream-root PATH\n";

	/**
	 * Creates command tests.
	 */
	public PackageZstdCommandTest()
	{
	}

	/**
	 * Prints exactly one JSON array and a newline for the zstd executable on the search path.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void printsOneJsonArray() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-command-");
		try
		{
			Path tools = Files.createDirectory(root.resolve("tools with spaces"));
			Path zstd = executable(tools, "zstd");
			CommandOutput result = invoke(new String[]{"resolve-zstd", "--upstream-root", root.toString()},
				Map.of("PATH", tools.toString(), "PATHEXT", ".EXE"));
			requireThat(result.status(), "status").withContext(result.err(), "diagnostics").isEqualTo(0);
			requireThat(result.err(), "diagnostics").isEmpty();
			requireThat(result.out().lines().count(), "lineCount").isEqualTo(1L);
			requireThat(result.out(), "output").endsWith("\n");
			requireThat(decode(result.out()), "argv").isEqualTo(List.of(zstd.toString()));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Falls back to the DotSlash executable and the repository wrapper when no zstd executable is on the path.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void usesDotslashWrapper() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-command-");
		try
		{
			Path tools = Files.createDirectory(root.resolve("tools"));
			Path dotslash = executable(tools, "dotslash");
			Path wrapper = root.resolve(".github/workflows/zstd");
			Files.createDirectories(wrapper.getParent());
			Files.writeString(wrapper, "not a DotSlash document\n");
			CommandOutput result = invoke(new String[]{"resolve-zstd", "--upstream-root", root.toString()},
				Map.of("PATH", tools.toString(), "PATHEXT", ".EXE"));
			requireThat(result.status(), "status").withContext(result.err(), "diagnostics").isEqualTo(0);
			requireThat(decode(result.out()), "argv").isEqualTo(List.of(dotslash.toString(), wrapper.toString()));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects a missing, repeated, unknown or valueless option and an extra positional argument with status 2.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void invalidOptionsExitTwo() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-command-");
		try
		{
			String path = root.toString();
			List<String[]> invalid = List.of(
				new String[]{"resolve-zstd"},
				new String[]{"resolve-zstd", "--upstream-root", path, "--upstream-root", path},
				new String[]{"resolve-zstd", "--upstream-root", path, "--target", "x"},
				new String[]{"resolve-zstd", "--upstream-root"},
				new String[]{"resolve-zstd", "--upstream-root", path, "extra"},
				new String[]{"resolve-zstd", path});
			for (String[] arguments : invalid)
			{
				CommandOutput result = invoke(arguments, Map.of("PATH", ""));
				requireThat(result.status(), "status").withContext(String.join(" ", arguments), "arguments").
					isEqualTo(2);
				requireThat(result.out(), "output").isEmpty();
				requireThat(result.err(), "diagnostics").startsWith(USAGE);
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Reports the missing tools with status 1, the oracle's diagnostic and no standard output.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void missingToolsExitOne() throws IOException
	{
		Path root = Files.createTempDirectory("package-zstd-command-");
		try
		{
			CommandOutput result = invoke(new String[]{"resolve-zstd", "--upstream-root", root.toString()},
				Map.of("PATH", ""));
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "output").isEmpty();
			requireThat(result.err(), "diagnostics").isEqualTo("Package archive operation failed: zstd is required to " +
				"write .tar.zst archives. Install zstd, or install DotSlash so the repository wrapper can run: " +
				root.resolve(".github/workflows/zstd") + "\n");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Prints the usage text on standard output with status 0 for {@code --help}.
	 */
	@Test
	public void helpExitsZero()
	{
		CommandOutput result = invoke(new String[]{"resolve-zstd", "--help"}, Map.of());
		requireThat(result.status(), "status").isEqualTo(0);
		requireThat(result.out(), "output").startsWith(USAGE);
		requireThat(result.err(), "diagnostics").isEmpty();
	}

	/**
	 * Creates an empty executable file whose name the fixture's search path finds.
	 *
	 * @param directory the directory to create the file in
	 * @param name      the command name without a platform suffix
	 * @return the file
	 * @throws IOException if the file cannot be created or made executable
	 */
	private static Path executable(Path directory, String name) throws IOException
	{
		Path file = Files.createFile(directory.resolve(name + SUFFIX));
		requireThat(file.toFile().setExecutable(true), "executableSet").isTrue();
		return file;
	}

	/**
	 * Reads a command's standard output as a JSON array of strings.
	 *
	 * @param output the standard output text
	 * @return the array's elements
	 */
	private static List<String> decode(String output)
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			JsonNode array = scope.getJsonMapper().readTree(output);
			requireThat(array.isArray(), "isArray").isTrue();
			List<String> elements = new ArrayList<>();
			for (JsonNode element : array)
				elements.add(element.asString());
			return elements;
		}
	}

	/**
	 * Runs the command through the tooling entry point and captures both streams.
	 *
	 * @param arguments   the complete command line
	 * @param environment the process environment of the invocation
	 * @return the status and streams
	 */
	private static CommandOutput invoke(String[] arguments, Map<String, String> environment)
	{
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err, environment);
			return new CommandOutput(status, outBytes.toString(StandardCharsets.UTF_8),
				errBytes.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Returns the executable suffix that the fixture environments list in {@code PATHEXT}.
	 *
	 * @return {@code .EXE} on Windows, otherwise the empty string
	 */
	private static String suffix()
	{
		if (System.getProperty("os.name").startsWith("Windows"))
			return ".EXE";
		return "";
	}

	/**
	 * Deletes a fixture directory tree.
	 *
	 * @param root the tree to delete
	 * @throws IOException if a file cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.deleteIfExists(path);
		}
	}

	/**
	 * Captured command result.
	 *
	 * @param status the exit status
	 * @param out    standard output
	 * @param err    standard error
	 */
	private record CommandOutput(int status, String out, String err)
	{
	}
}
