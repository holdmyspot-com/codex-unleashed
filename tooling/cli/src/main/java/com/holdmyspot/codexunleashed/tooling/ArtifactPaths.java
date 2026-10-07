package com.holdmyspot.codexunleashed.tooling;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.StreamSupport;

/**
 * Preserves component-based ordering and portable artifact names for release metadata.
 */
public final class ArtifactPaths
{
	/**
	 * Prevents construction.
	 */
	private ArtifactPaths()
	{
	}

	/**
	 * Orders descendants by their relative components and Unicode code points.
	 * Windows comparison uses lowercase components while output names retain their spelling.
	 *
	 * @param root the common source directory
	 * @return the component comparator
	 * @throws NullPointerException if {@code root} is null
	 */
	public static Comparator<Path> comparator(Path root)
	{
		Objects.requireNonNull(root, "root");
		boolean windows = "\\".equals(root.getFileSystem().getSeparator());
		return (first, second) -> Arrays.compare(components(root.relativize(first), windows),
			components(root.relativize(second), windows),
			(left, right) -> Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray()));
	}

	/**
	 * Renders relative names using literal components and slash separators.
	 *
	 * @param root the source directory
	 * @param file the descendant path
	 * @return the portable artifact name
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the paths cannot be relativized
	 */
	public static String relativeName(Path root, Path file)
	{
		Objects.requireNonNull(root, "root");
		Objects.requireNonNull(file, "file");
		StringJoiner name = new StringJoiner("/");
		for (Path component : root.relativize(file))
			name.add(component.toString());
		return name.toString();
	}

	/**
	 * Extracts comparison components using the selected filesystem's case behavior.
	 *
	 * @param path the relative path
	 * @param windows indicates Windows case normalization
	 * @return the comparison components
	 */
	private static String[] components(Path path, boolean windows)
	{
		return StreamSupport.stream(path.spliterator(), false).map(Path::toString).
			map(value ->
			{
				if (windows)
					return value.toLowerCase(Locale.ROOT);
				return value;
			}).toArray(String[]::new);
	}
}
