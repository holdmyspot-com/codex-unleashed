package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Removes this workspace's Cargo outputs from an owned target copy before it is cached.
 */
public final class PruneWorkspaceOutputs
{
	/** The Cargo metadata executable and fixed arguments used by this command. */
	private static final List<String> CARGO_METADATA_COMMAND = List.of("cargo", "metadata", "--no-deps",
		"--format-version", "1");
	/** Matches a Cargo artifact owner and its complete lowercase 16-digit hash. */
	private static final Pattern HASHED_ENTRY = Pattern.compile("^(?:lib)?(?<owner>.+?)-[0-9a-f]{16}(?:\\..*)?$");
	/** Profile directories that always contain outputs owned by the current workspace. */
	private static final List<String> PROFILE_OUTPUT_DIRECTORIES = List.of("examples", "incremental");
	/** Profile subdirectories that contain hash-named Cargo artifacts in the flat layout used before Cargo 1.100. */
	private static final List<String> HASHED_OUTPUT_DIRECTORIES = List.of("deps", ".fingerprint", "build");
	/** Matches the complete lowercase 16-digit hash that names a per-unit directory in the Cargo 1.100 layout. */
	private static final Pattern UNIT_HASH = Pattern.compile("^[0-9a-f]{16}$");
	/** Usage shown when the named options are invalid. */
	private static final String USAGE = "Usage: prune-workspace-outputs --workspace-manifest-dir DIR " +
		"--target-copy COPY --live-target LIVE";

	/**
	 * The maintained workspace-output pruning command name.
	 */
	public static final String NAME = "prune-workspace-outputs";

	/**
	 * Prevents construction.
	 */
	private PruneWorkspaceOutputs()
	{
	}

	/**
	 * Parses and runs the workspace-output pruning command.
	 *
	 * @param scope shared tooling values borrowed until the command finishes
	 * @param arguments command arguments without the command name
	 * @param out progress output
	 * @param err diagnostics
	 * @return zero on success, one on metadata or filesystem failure, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		return run(scope, arguments, out, err, SystemCommands::capture);
	}

	/**
	 * Parses and runs the workspace-output pruning command with the supplied Cargo metadata process boundary.
	 *
	 * @param scope shared tooling values borrowed until the command finishes
	 * @param arguments command arguments without the command name
	 * @param out progress output
	 * @param err diagnostics
	 * @param metadataCommands process boundary used to obtain Cargo workspace metadata
	 * @return zero on success, one on metadata or filesystem failure, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err,
		SystemCommands.Capture metadataCommands)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(metadataCommands, "metadataCommands");

		Request request;
		try
			{
			request = parse(arguments);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			err.println(USAGE);
			return 2;
		}

		try
		{
			Path workspace = request.workspaceManifestDirectory().toRealPath();
			Path targetCopy = request.targetCopy().toAbsolutePath().normalize();
			Path liveTarget = request.liveTarget().toRealPath();
			validateTargetCopyPath(targetCopy);
			if (!Files.isDirectory(workspace) || !Files.isDirectory(targetCopy, LinkOption.NOFOLLOW_LINKS) ||
				!Files.isDirectory(liveTarget))
				throw new IOException("Workspace, target copy, and live target must be existing directories");
			Path resolvedCopy = targetCopy.toRealPath();
			validateOwnership(resolvedCopy, liveTarget, workspace);

			SystemCommands.Result result = metadataCommands.capture(CARGO_METADATA_COMMAND, workspace,
				Path.of(System.getProperty("java.io.tmpdir")), Map.of());
			if (result.status() != 0)
			{
				err.println("ERROR: cargo metadata failed with status " + result.status());
				out.print(result.stdout());
				if (!result.stderr().isEmpty())
					err.print(result.stderr());
				return 1;
			}

			Set<String> names = workspaceNames(scope, result.stdout());
			List<Path> profiles = profileDirectories(resolvedCopy);
			int total = 0;
			for (Path profile : profiles)
			{
				int removed = pruneProfile(profile, names);
				out.println(profile + ": removed " + removed + " workspace entries");
				total += removed;
			}
			out.println("Pruned " + total + " entries for " + names.size() + " workspace names.");
			return 0;
		}
		catch (IOException | SecurityException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Parses each required named argument once.
	 *
	 * @param arguments command arguments
	 * @return the request
	 * @throws IllegalArgumentException if any option is missing, repeated, unknown, or has an invalid path
	 */
	private static Request parse(String[] arguments)
	{
		if (arguments.length != 6)
			throw new IllegalArgumentException("Expected exactly three named options");
		Path workspace = null;
		Path copy = null;
		Path live = null;
		for (int index = 0; index < arguments.length; index += 2)
		{
			String option = Objects.requireNonNull(arguments[index], "argument");
			String value = Objects.requireNonNull(arguments[index + 1], "argument");
			if (value.isEmpty())
				throw new IllegalArgumentException("Option value must not be empty: " + option);
			try
			{
				switch (option)
				{
				case "--workspace-manifest-dir" ->
				{
					if (workspace != null)
						throw new IllegalArgumentException("Option repeated: " + option);
					workspace = Path.of(value);
				}
				case "--target-copy" ->
				{
					if (copy != null)
						throw new IllegalArgumentException("Option repeated: " + option);
					copy = Path.of(value);
				}
				case "--live-target" ->
				{
					if (live != null)
						throw new IllegalArgumentException("Option repeated: " + option);
					live = Path.of(value);
				}
				default -> throw new IllegalArgumentException("Unknown option: " + option);
				}
			}
			catch (IllegalArgumentException failure)
			{
				throw failure;
			}
			catch (RuntimeException failure)
			{
				throw new IllegalArgumentException("Invalid path for " + option, failure);
			}
		}
		if (workspace == null || copy == null || live == null)
			throw new IllegalArgumentException("All three named options are required");
		return new Request(workspace, copy, live);
	}

	/**
	 * Rejects symbolic links in the target-copy path itself or any of its ancestors.
	 *
	 * @param targetCopy normalized absolute copy path
	 * @throws IOException if a path component is linked
	 */
	private static void validateTargetCopyPath(Path targetCopy) throws IOException
	{
		for (Path current = targetCopy; current != null; current = current.getParent())
		{
			if (Files.isSymbolicLink(current))
				throw new IOException("Target copy path traverses a symbolic link: " + current);
		}
	}

	/**
	 * Ensures the owned target copy cannot overlap the live target or contain the workspace source directory.
	 *
	 * @param targetCopy resolved owned copy
	 * @param liveTarget resolved live target
	 * @param workspace resolved workspace source
	 * @throws IOException if the requested paths do not establish separate ownership
	 */
	private static void validateOwnership(Path targetCopy, Path liveTarget, Path workspace) throws IOException
	{
		if (targetCopy.startsWith(liveTarget) || liveTarget.startsWith(targetCopy))
			throw new IOException("Target copy must not overlap the live target");
		if (workspace.startsWith(targetCopy))
			throw new IOException("Target copy must not equal or contain the workspace source directory");
	}

	/**
	 * Reads every workspace package and target name and normalizes Cargo hyphens to underscores.
	 *
	 * @param scope shared tooling values borrowed until the command finishes
	 * @param text Cargo metadata JSON
	 * @return normalized names
	 * @throws IOException if metadata is malformed or omits consumed fields
	 */
	private static Set<String> workspaceNames(ToolingScope scope, String text) throws IOException
	{
		JsonNode document;
		try
		{
			document = scope.getJsonMapper().readTree(text);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cargo metadata is not valid JSON", failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Cargo metadata must be a JSON object");
		JsonNode packages = array(document, "packages");
		Set<String> names = new LinkedHashSet<>();
		for (JsonNode packageNode : packages)
		{
			String packageName = text(packageNode, "name");
			names.add(normalize(packageName));
			for (JsonNode target : array(packageNode, "targets"))
				names.add(normalize(text(target, "name")));
		}
		return Set.copyOf(names);
	}

	/**
	 * Returns a required JSON array.
	 *
	 * @param object containing object
	 * @param field field name
	 * @return array value
	 * @throws IOException if the field is absent or not an array
	 */
	private static JsonNode array(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isArray())
			throw new IOException("Cargo metadata field must be an array: " + field);
		return value;
	}

	/**
	 * Returns a required JSON string.
	 *
	 * @param object containing object
	 * @param field field name
	 * @return string value
	 * @throws IOException if the field is absent or not a string
	 */
	private static String text(JsonNode object, String field) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString())
			throw new IOException("Cargo metadata field must be a string: " + field);
		return value.stringValue();
	}

	/**
	 * Replaces Cargo name separators with the Rust artifact filename separator.
	 *
	 * @param name Cargo package or target name
	 * @return artifact owner name
	 */
	private static String normalize(String name)
	{
		return name.replace('-', '_');
	}

	/**
	 * Captures every profile directory before mutations begin, without following directory symlinks.
	 *
	 * @param targetCopy owned target copy
	 * @return profiles in deterministic path order
	 * @throws IOException if a copy entry cannot be read
	 */
	private static List<Path> profileDirectories(Path targetCopy) throws IOException
	{
		List<Path> profiles = new ArrayList<>();
		Files.walkFileTree(targetCopy, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
				throws IOException
			{
				if (isProfile(directory))
					profiles.add(directory);
				return FileVisitResult.CONTINUE;
			}
		});
		profiles.sort(Comparator.comparing(Path::toString));
		return List.copyOf(profiles);
	}

	/**
	 * Determines whether a directory is a Cargo profile directory in either supported layout.
	 * <p>
	 * Cargo before 1.100 keeps {@code deps} and {@code .fingerprint} directly in the profile. Cargo 1.100 removes both
	 * and keeps each unit's files in {@code build/<package>/<hash>/}.
	 *
	 * @param directory candidate directory
	 * @return true if the directory has either layout
	 * @throws IOException if the directory cannot be read
	 */
	private static boolean isProfile(Path directory) throws IOException
	{
		if (Files.isDirectory(directory.resolve("deps"), LinkOption.NOFOLLOW_LINKS) &&
			Files.isDirectory(directory.resolve(".fingerprint"), LinkOption.NOFOLLOW_LINKS))
			return true;
		Path build = directory.resolve("build");
		if (!Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS))
			return false;
		try (Stream<Path> packages = Files.list(build))
		{
			for (Path packageDirectory : packages.toList())
			{
				if (hasUnitDirectory(packageDirectory))
					return true;
			}
			return false;
		}
	}

	/**
	 * Determines whether a package directory under {@code build} contains a Cargo 1.100 per-unit directory.
	 *
	 * @param packageDirectory candidate package directory
	 * @return true if it contains a hash-named directory holding a {@code fingerprint} directory
	 * @throws IOException if the directory cannot be read
	 */
	private static boolean hasUnitDirectory(Path packageDirectory) throws IOException
	{
		if (!Files.isDirectory(packageDirectory, LinkOption.NOFOLLOW_LINKS))
			return false;
		try (Stream<Path> units = Files.list(packageDirectory))
		{
			for (Path unit : units.toList())
			{
				if (UNIT_HASH.matcher(unit.getFileName().toString()).matches() &&
					Files.isDirectory(unit.resolve("fingerprint"), LinkOption.NOFOLLOW_LINKS))
					return true;
			}
			return false;
		}
	}

	/**
	 * Removes selected direct entries from one profile and counts each removed top-level entry once.
	 *
	 * @param profile profile directory
	 * @param names workspace artifact owners
	 * @return number of removed entries
	 * @throws IOException if a selected entry cannot be removed
	 */
	private static int pruneProfile(Path profile, Set<String> names) throws IOException
	{
		int removed = 0;
		for (String subdirectory : HASHED_OUTPUT_DIRECTORIES)
		{
			Path directory = profile.resolve(subdirectory);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
				continue;
			try (Stream<Path> entries = Files.list(directory))
			{
				for (Path entry : entries.toList())
				{
					String owner = owner(entry.getFileName().toString());
					if (owner != null && names.contains(normalize(owner)))
					{
						deleteEntry(entry);
						removed += 1;
					}
				}
			}
		}
		removed += pruneUnitDirectories(profile.resolve("build"), names);
		for (String subdirectory : PROFILE_OUTPUT_DIRECTORIES)
		{
			Path entry = profile.resolve(subdirectory);
			if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS))
			{
				deleteEntry(entry);
				removed += 1;
			}
		}
		try (Stream<Path> entries = Files.list(profile))
		{
			for (Path entry : entries.toList())
			{
				if (!entry.getFileName().toString().startsWith(".") && Files.isRegularFile(entry))
				{
					Files.delete(entry);
					removed += 1;
				}
			}
		}
		return removed;
	}

	/**
	 * Removes each workspace package's directory from the Cargo 1.100 {@code build/<package>/<hash>/} layout, which
	 * holds that package's fingerprints, compiled outputs and build-script runs.
	 *
	 * @param build profile {@code build} directory, which may be absent
	 * @param names workspace artifact owners
	 * @return number of removed package directories
	 * @throws IOException if a selected entry cannot be removed
	 */
	private static int pruneUnitDirectories(Path build, Set<String> names) throws IOException
	{
		if (!Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS))
			return 0;
		int removed = 0;
		try (Stream<Path> entries = Files.list(build))
		{
			for (Path entry : entries.toList())
			{
				String name = entry.getFileName().toString();
				if (owner(name) == null && names.contains(normalize(name)) && hasUnitDirectory(entry))
				{
					deleteEntry(entry);
					removed += 1;
				}
			}
		}
		return removed;
	}

	/**
	 * Returns an artifact owner only for a complete recognized 16-character lowercase hexadecimal hash.
	 *
	 * @param name entry name
	 * @return owner before the hash, or null for an unrelated entry
	 */
	private static String owner(String name)
	{
		Matcher matcher = HASHED_ENTRY.matcher(name);
		if (!matcher.matches())
			return null;
		return matcher.group("owner");
	}

	/**
	 * Deletes an entry tree without following symbolic links.
	 *
	 * @param entry selected entry
	 * @throws IOException if any entry cannot be removed
	 */
	private static void deleteEntry(Path entry) throws IOException
	{
		if (Files.isSymbolicLink(entry) || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
		{
			Files.delete(entry);
			return;
		}
		Files.walkFileTree(entry, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Parsed paths that identify Cargo's workspace source and the two target trees.
	 *
	 * @param workspaceManifestDirectory workspace source directory
	 * @param targetCopy owned target copy to prune
	 * @param liveTarget live target protected from pruning
	 */
	private record Request(Path workspaceManifestDirectory, Path targetCopy, Path liveTarget)
	{
	}
}
