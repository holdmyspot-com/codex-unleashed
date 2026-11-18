package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Selects the command that decompresses and writes {@code .tar.zst} package archives.
 * <p>
 * A {@code zstd} executable on the host search path is preferred. Otherwise a {@code dotslash} executable runs the
 * repository's {@code .github/workflows/zstd} wrapper, which qualifies when it is a file or a link to a file. The
 * wrapper's contents are DotSlash's to validate and are never read here. The selection starts no process: an
 * executable that is selected but cannot run fails in the process that runs it, without a retry through the other
 * route.
 * <p>
 * {@link PackageZstdLookup} supplies the host search; the {@code Executables} parameter lets callers and tests supply
 * recorded tool tokens.
 */
public final class PackageZstd
{
	/** Command name. */
	public static final String NAME = "resolve-zstd";
	/** Option that names the patched package repository containing the DotSlash wrapper. */
	private static final String UPSTREAM_ROOT = "--upstream-root";
	/** Usage line. */
	private static final String USAGE = "Usage: codex-tooling " + NAME + " " + UPSTREAM_ROOT + " PATH\n";
	/** Complete help text. */
	private static final String HELP = USAGE +
		"Prints one JSON array holding the command that runs zstd: the zstd executable on the search path, or\n" +
		"the dotslash executable followed by the repository wrapper PATH/.github/workflows/zstd.\n" +
		"Status: 0 selected; 1 no zstd route is available; 2 invalid arguments.\n";
	/** Executable name that is preferred. */
	private static final String ZSTD = "zstd";
	/** Executable name that runs the repository wrapper. */
	private static final String DOTSLASH = "dotslash";
	/** Repository-relative location of the DotSlash wrapper for zstd. */
	private static final String MANIFEST = ".github/workflows/zstd";

	/** Prevents construction. */
	private PackageZstd()
	{
	}

	/**
	 * Finds executables by their fixed command names.
	 */
	@FunctionalInterface
	public interface Executables
	{
		/**
		 * Finds the executable that the host runs for a command name.
		 *
		 * @param name the command name, either {@code zstd} or {@code dotslash}
		 * @return the command token to run, spelled as the search produced it, or empty when the search finds none
		 * @throws IOException if the host search cannot be performed
		 */
		Optional<String> find(String name) throws IOException;
	}

	/**
	 * Selects the zstd command for the supplied repository.
	 * <p>
	 * The lookup is asked for {@code zstd} first. {@code dotslash} is asked for, and the wrapper checked, only when
	 * {@code zstd} is absent. Tokens are returned as the lookup spelled them.
	 *
	 * @param upstreamRoot the patched package repository containing {@code .github/workflows/zstd}
	 * @param executables  the host search
	 * @return the immutable, nonempty command: the {@code zstd} token, or the {@code dotslash} token followed by the
	 *         wrapper path
	 * @throws NullPointerException if an argument is null
	 * @throws IOException          if neither route is available or the host search fails
	 */
	public static List<String> resolve(Path upstreamRoot, Executables executables) throws IOException
	{
		Objects.requireNonNull(upstreamRoot, "upstreamRoot");
		Objects.requireNonNull(executables, "executables");
		Optional<String> zstd = executables.find(ZSTD);
		if (zstd.isPresent())
			return List.of(zstd.get());

		Path manifest = upstreamRoot.resolve(MANIFEST);
		String manifestText = manifestText(manifest);
		Optional<String> dotslash = executables.find(DOTSLASH);
		if (dotslash.isPresent() && Files.isRegularFile(manifest))
			return List.of(dotslash.get(), manifestText);
		throw new IOException("zstd is required to write .tar.zst archives. Install zstd, or install DotSlash so the " +
			"repository wrapper can run: " + manifestText);
	}

	/**
	 * Prints the selected command as one JSON array, using the host search of the supplied environment.
	 *
	 * @param scope            borrowed shared values; the caller retains ownership
	 * @param arguments        command arguments after the command name
	 * @param workingDirectory the directory that a relative repository path and relative search-path entries resolve
	 *                         against
	 * @param environment      the complete process environment whose search path decides the selection
	 * @param out              receives the help text or the JSON array and a final newline
	 * @param err              receives usage or failure diagnostics
	 * @return the exit status: 0 for a selection or help, 1 when no route is available, 2 for invalid arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Path workingDirectory,
		Map<String, String> environment, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		return run(scope, arguments, workingDirectory, new PackageZstdLookup(scope, workingDirectory, environment),
			out, err);
	}

	/**
	 * Prints the selected command as one JSON array, using a supplied host search.
	 * <p>
	 * The JSON is ASCII, so its text does not depend on the encoding of {@code out}. Nothing is written to {@code out}
	 * when the command fails.
	 *
	 * @param scope            borrowed shared values; the caller retains ownership
	 * @param arguments        command arguments after the command name
	 * @param workingDirectory the directory that a relative repository path resolves against
	 * @param executables      the host search
	 * @param out              receives the help text or the JSON array and a final newline
	 * @param err              receives usage or failure diagnostics
	 * @return the exit status: 0 for a selection or help, 1 when no route is available, 2 for invalid arguments
	 * @throws NullPointerException if an argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Path workingDirectory, Executables executables,
		PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(executables, "executables");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 1 && arguments[0].equals("--help"))
		{
			out.print(HELP);
			out.flush();
			return 0;
		}

		String upstreamRoot;
		try
		{
			upstreamRoot = parseUpstreamRoot(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.print(USAGE + NAME + ": " + failure.getMessage() + "\n");
			err.flush();
			return 2;
		}
		try
		{
			List<String> command = resolve(workingDirectory.resolve(upstreamRoot), executables);
			out.print(scope.getArtifactJsonMapper().writeValueAsString(command) + "\n");
			out.flush();
			return 0;
		}
		catch (IOException | IllegalArgumentException failure)
		{
			err.print("Package archive operation failed: " + failure.getMessage() + "\n");
			err.flush();
			return 1;
		}
	}

	/**
	 * Reads the one required option.
	 *
	 * @param arguments command arguments after the command name
	 * @return the repository path text
	 * @throws IllegalArgumentException if the option is missing, repeated, valueless or not the only argument
	 */
	private static String parseUpstreamRoot(String[] arguments)
	{
		String value = null;
		for (int index = 0; index < arguments.length; index += 2)
		{
			if (!arguments[index].equals(UPSTREAM_ROOT))
				throw new IllegalArgumentException("Unknown option " + arguments[index]);
			if (index + 1 >= arguments.length)
				throw new IllegalArgumentException("Option " + UPSTREAM_ROOT + " needs a value");
			if (value != null)
				throw new IllegalArgumentException("Option " + UPSTREAM_ROOT + " was given more than once");
			value = arguments[index + 1];
		}
		if (value == null)
			throw new IllegalArgumentException("Option " + UPSTREAM_ROOT + " is required");
		return value;
	}

	/**
	 * Spells the wrapper path the way the retired Python resolver printed it, without {@code .} components.
	 * <p>
	 * Python's {@code str(Path)} drops {@code .} components, while {@code Path.toString()} keeps them. The text is
	 * both the second argument of the DotSlash command and the path in the failure message.
	 *
	 * @param manifest the wrapper path
	 * @return the path text
	 */
	private static String manifestText(Path manifest)
	{
		StringJoiner names = new StringJoiner(manifest.getFileSystem().getSeparator());
		for (Path name : manifest)
		{
			String text = name.toString();
			if (!text.equals("."))
				names.add(text);
		}
		Path root = manifest.getRoot();
		if (root == null)
			return names.toString();
		return root + names.toString();
	}
}
