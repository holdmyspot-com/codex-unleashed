package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Finds the latest modification time among files below one watched build directory, excluding two configured paths.
 */
public final class BuildOutput
{
	/** Shared native timestamp reader borrowed from the caller. */
	private final ToolingScope scope;
	/** Absolute lexical root whose files contribute to the latest output time. */
	private final Path directory;
	/** Absolute lexical file paths omitted from output observations. */
	private final Set<String> exclusions;

	/**
	 * Creates a build-output observer.
	 *
	 * @param scope borrowed native timestamp bindings that must remain open while {@link #latest()} runs
	 * @param directory directory whose files are observed after absolute lexical normalization
	 * @param report report path excluded when its normalized absolute spelling matches a visited path
	 * @param samples resource-samples path excluded when its normalized absolute spelling matches a visited path
	 * @throws NullPointerException if any required argument is null
	 */
	public BuildOutput(ToolingScope scope, Path directory, Path report, Path samples)
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(report, "report").isNotNull();
		requireThat(samples, "samples").isNotNull();

		this.scope = scope;
		this.directory = directory.toAbsolutePath().normalize();
		Set<String> normalizedExclusions = new HashSet<>();
		normalizedExclusions.add(normalizeAbsolutePath(report));
		normalizedExclusions.add(normalizeAbsolutePath(samples));
		this.exclusions = Set.copyOf(normalizedExclusions);
	}

	/**
	 * Returns the greatest timestamp observed among readable files below the configured directory. Ordinary nested
	 * directories are traversed, file links contribute their targets' times, and nested directory links are skipped. A
	 * root that is itself a directory link is scanned. Missing or unreadable roots return the epoch; inaccessible or
	 * disappeared entries are skipped while timestamps already observed remain eligible.
	 *
	 * @return the latest file timestamp, or {@link Instant#EPOCH} when no readable file has a later timestamp
	 */
	public Instant latest()
	{
		return latestUnder(directory, Instant.EPOCH);
	}

	/**
	 * Visits readable files under one directory while carrying forward the greatest timestamp observed so far.
	 *
	 * @param currentDirectory directory currently being visited
	 * @param latest greatest timestamp observed before this directory
	 * @return greatest timestamp observed before or under this directory
	 */
	private Instant latestUnder(Path currentDirectory, Instant latest)
	{
		try (DirectoryStream<Path> children = Files.newDirectoryStream(currentDirectory))
		{
			for (Path child : children)
			{
				try
				{
					if (exclusions.contains(normalizeAbsolutePath(child)))
						continue;
					if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
					{
						latest = latestUnder(child, latest);
						continue;
					}
					if (!Files.isRegularFile(child))
						continue;

					Instant modified = SourceInputTimes.lastModified(scope, child);
					if (modified.isAfter(latest))
						latest = modified;
				}
				catch (IOException | SecurityException _)
				{
				}
			}
		}
		catch (DirectoryIteratorException | IOException | SecurityException _)
		{
		}
		return latest;
	}

	/**
	 * Returns the path's absolute lexical spelling without resolving symbolic links.
	 *
	 * @param path path to normalize
	 * @return normalized absolute path string
	 */
	private static String normalizeAbsolutePath(Path path)
	{
		return path.toAbsolutePath().normalize().toString();
	}
}
