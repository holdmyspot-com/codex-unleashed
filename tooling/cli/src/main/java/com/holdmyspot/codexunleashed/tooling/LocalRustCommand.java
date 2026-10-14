package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs local Rust verification with checkout-owned compiler outputs and shared dependency downloads.
 */
public final class LocalRustCommand
{
	/**
	 * Prevents construction.
	 */
	private LocalRustCommand()
	{
	}

	/**
	 * Validates cache ownership before starting a command in the selected checkout.
	 *
	 * @param args the project root, checkout, cache name, separator, and literal command arguments
	 * @param err the usage diagnostic destination
	 * @return the child status, or two for invalid command arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if cache validation or child execution fails
	 */
	public static int run(String[] args, PrintStream err) throws IOException
	{
		Objects.requireNonNull(err, "err");
		for (String argument : args)
			Objects.requireNonNull(argument, "argument");
		if (args.length < 5 || !args[3].equals("--") || !args[2].matches("[a-zA-Z0-9][a-zA-Z0-9_-]*"))
		{
			err.println("Usage: run-local-rust <project-root> <checkout> <cache-name> -- <command> [argument ...]");
			return 2;
		}
		Path project = Path.of(args[0]).toRealPath();
		Path checkout = Path.of(args[1]).toRealPath();
		if (!Files.isDirectory(project) || !Files.isDirectory(checkout))
			throw new IOException("The project root and checkout must be existing directories");
		Path cache = createConfinedDirectories(project, Path.of(".cat/work/temp/build-caches", args[2]));
		String identity = UUID.nameUUIDFromBytes(checkout.toString().getBytes(StandardCharsets.UTF_8)).toString();
		Path outputs = createConfinedDirectories(project, project.relativize(cache.resolve("checkouts")));
		Path target = outputs.resolve(identity);
		claimOutputs(target, checkout);

		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.put("CARGO_TARGET_DIR", target.toString());
		for (Map.Entry<String, String> entry : Map.of("CARGO_HOME", "cargo-home", "UV_CACHE_DIR", "uv",
			"XDG_CACHE_HOME", "xdg", "DOTSLASH_CACHE", "dotslash").entrySet())
		{
			Path directory = createConfinedDirectories(project, project.relativize(cache.resolve(entry.getValue())));
			environment.put(entry.getKey(), directory.toString());
		}
		return SystemCommands.execute(Arrays.asList(Arrays.copyOfRange(args, 4, args.length)), checkout, environment);
	}

	/**
	 * Creates cache parents while rejecting symbolic links in cache paths.
	 *
	 * @param project the canonical project root
	 * @param relative the path beneath the project
	 * @return the canonical cache directory
	 * @throws IOException if creation fails or a component is a symbolic link
	 */
	private static Path createConfinedDirectories(Path project, Path relative) throws IOException
	{
		Path directory = project;
		for (Path component : relative)
		{
			directory = directory.resolve(component);
			try
			{
				Files.createDirectory(directory);
			}
			catch (FileAlreadyExistsException failure)
			{
				if (!Files.isDirectory(directory))
					throw new IOException("Cache path is not a directory: " + directory, failure);
			}
			if (!directory.toRealPath().equals(directory))
				throw new IOException("Cache directory must not be a symbolic link: " + directory);
		}
		return directory;
	}

	/**
	 * Claims a new output directory or verifies its persisted checkout owner before reuse.
	 *
	 * @param target the checkout-specific compiler output directory
	 * @param checkout the canonical checkout path
	 * @throws IOException if ownership is missing, mismatched, or cannot be established
	 */
	private static void claimOutputs(Path target, Path checkout) throws IOException
	{
		Path marker = target.resolve(".checkout-owner");
		boolean created;
		try
		{
			Files.createDirectory(target);
			created = true;
		}
		catch (FileAlreadyExistsException _)
		{
			created = false;
		}
		if (!target.toRealPath().equals(target))
			throw new IOException("Compiler output directory must not be a symbolic link: " + target);
		if (created)
			Files.writeString(marker, checkout.toString(), StandardOpenOption.CREATE_NEW);
		else if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker))
			throw new IOException("Compiler outputs have no valid checkout owner: " + target +
				". Select a new cache name or remove these outputs after their last consumer exits.");
		String owner = Files.readString(marker);
		if (!owner.equals(checkout.toString()))
			throw new IOException("Compiler outputs belong to checkout " + owner + "; requested checkout is " +
				checkout + ". Select a new cache name or remove foreign outputs after their last consumer exits.");
	}
}
