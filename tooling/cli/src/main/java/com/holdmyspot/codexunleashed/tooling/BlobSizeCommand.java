package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses and runs the Git blob size command.
 */
public final class BlobSizeCommand
{
	/**
	 * Command name registered by the tooling entry point.
	 */
	public static final String NAME = "check-blob-size";

	/**
	 * Prevents construction.
	 */
	private BlobSizeCommand()
	{
	}

	/**
	 * Runs the size policy against the selected repository and commit range.
	 *
	 * @param arguments command arguments without the command name
	 * @param currentDirectory current directory used to resolve a relative repository path
	 * @param temporaryDirectory parent for files used to stream Git object IDs
	 * @param out help output
	 * @param err diagnostics
	 * @return zero for help or an accepted range, one for oversized blobs, or two for invalid arguments or Git failures
	 * @throws NullPointerException if an argument or stream is null
	 */
	public static int run(String[] arguments, Path currentDirectory, Path temporaryDirectory, PrintStream out,
		PrintStream err)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(currentDirectory, "currentDirectory");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (arguments.length == 1 && arguments[0].equals("--help"))
		{
			writeHelp(out);
			return 0;
		}

		Arguments parsed;
		try
		{
			parsed = parseArguments(arguments, currentDirectory);
		}
		catch (IllegalArgumentException failure)
		{
			writeUsage(err);
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}

		try
		{
			return GitBlobObjects.check(parsed.repository(), parsed.base(), parsed.head(), temporaryDirectory, err);
		}
		catch (IOException failure)
		{
			err.println("Blob policy could not read the requested Git objects: " + failure.getMessage() +
				". Fetch both commits and retry from the repository checkout.");
			return 2;
		}
	}

	/**
	 * Parses required revisions and the optional repository path.
	 *
	 * @param arguments command arguments without the command name
	 * @param currentDirectory current directory used to resolve a relative repository path
	 * @return the validated command arguments
	 * @throws NullPointerException if an argument or current directory is null
	 * @throws IllegalArgumentException if an option is unknown, repeated, or valueless, or if base/head are absent
	 */
	public static Arguments parseArguments(String[] arguments, Path currentDirectory)
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(currentDirectory, "currentDirectory");
		String base = null;
		String head = null;
		Path repository = currentDirectory.toAbsolutePath().normalize();
		boolean hasRepository = false;
		for (int index = 0; index < arguments.length; index += 2)
		{
			String option = Objects.requireNonNull(arguments[index], "option");
			if (index + 1 >= arguments.length)
				throw new IllegalArgumentException(option + " requires a value");
			String value = Objects.requireNonNull(arguments[index + 1], "value");
			if (value.isBlank())
				throw new IllegalArgumentException(option + " requires a non-blank value");
			switch (option)
			{
			case "--base" ->
			{
				if (base != null)
					throw new IllegalArgumentException("--base may appear only once");
				base = value;
			}
			case "--head" ->
			{
				if (head != null)
					throw new IllegalArgumentException("--head may appear only once");
				head = value;
			}
			case "--repo" ->
			{
				if (hasRepository)
					throw new IllegalArgumentException("--repo may appear only once");
				Path requestedRepository = Path.of(value);
				if (requestedRepository.isAbsolute())
					repository = requestedRepository;
				else
					repository = currentDirectory.toAbsolutePath().resolve(requestedRepository);
				repository = repository.toAbsolutePath().normalize();
				hasRepository = true;
			}
			default -> throw new IllegalArgumentException("Unknown option: " + option);
			}
		}
		if (base == null || head == null)
			throw new IllegalArgumentException("--base and --head are required");
		return new Arguments(repository, base, head);
	}

	/**
	 * Builds the object enumeration command for the resolved commit range.
	 *
	 * @param head resolved commit to include
	 * @param base supplied base revision
	 * @param resolvedBase resolved base commit, or null when {@code base} contains only zeroes
	 * @return Git arguments that enumerate new reachable object IDs
	 * @throws NullPointerException if a required parameter is null (resolvedBase is required for nonzero base)
	 */
	public static List<String> revListArguments(String head, String base, String resolvedBase)
	{
		Objects.requireNonNull(head, "head");
		Objects.requireNonNull(base, "base");
		List<String> command = new ArrayList<>(List.of("git", "rev-list", "--objects", "--no-object-names", head));
		if (!isAllZero(base))
			command.add("^" + Objects.requireNonNull(resolvedBase, "resolvedBase"));
		return List.copyOf(command);
	}

	/**
	 * Determines whether the base revision selects all objects reachable from the head.
	 *
	 * @param base supplied base revision
	 * @return true when the nonempty revision contains only zero characters
	 * @throws NullPointerException if {@code base} is null
	 */
	public static boolean isAllZero(String base)
	{
		Objects.requireNonNull(base, "base");
		return !base.isEmpty() && base.chars().allMatch(character -> character == '0');
	}

	/**
	 * Writes successful command help.
	 *
	 * @param out help output
	 */
	private static void writeHelp(PrintStream out)
	{
		out.println("Usage: codex-tooling " + NAME + " --base BASE --head HEAD [--repo PATH]");
		out.println("Checks newly reachable Git blobs; BASE may be an all-zero revision for all HEAD history.");
		out.println("Blobs larger than 512000 bytes are rejected; blobs already reachable from BASE are grandfathered.");
	}

	/**
	 * Writes invalid-argument usage.
	 *
	 * @param err diagnostics
	 */
	private static void writeUsage(PrintStream err)
	{
		err.println("Usage: codex-tooling " + NAME + " --base BASE --head HEAD [--repo PATH]");
	}

	/**
	 * Contains the repository and revisions selected by the caller.
	 *
	 * @param repository repository to inspect
	 * @param base excluded ancestor revision, or a nonempty all-zero revision for all HEAD history
	 * @param head proposed commit to inspect
	 */
	public record Arguments(Path repository, String base, String head)
	{
	}
}
