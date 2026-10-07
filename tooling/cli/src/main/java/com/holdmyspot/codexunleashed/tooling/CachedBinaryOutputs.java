package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;

/** Invalidates cached executable units while retaining the owner's library and sibling units. */
public final class CachedBinaryOutputs
{
	/** Prevents construction. */
	private CachedBinaryOutputs()
	{
	}

	/**
	 * Deletes the requested binary's markers, executable outputs and debug bundle.
	 *
	 * @param releaseDirectory the Cargo target's release output directory
	 * @param binary one executable filename
	 * @return the number of fingerprint units whose binary markers are invalidated
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the binary name is empty, absolute, or contains path traversal
	 * @throws IOException if any matching marker, executable, or debug bundle cannot be removed
	 */
	public static int invalidate(Path releaseDirectory, String binary) throws IOException
	{
		Objects.requireNonNull(releaseDirectory, "releaseDirectory");
		Objects.requireNonNull(binary, "binary");
		Path name = Path.of(binary);
		if (binary.isEmpty() || binary.equals(".") || binary.equals("..") || name.isAbsolute() ||
			name.getNameCount() != 1 || !name.getFileName().toString().equals(binary))
			throw new IllegalArgumentException("Expected one cached binary filename: " + binary);
		String marker = "bin-" + binary;
		List<String> markers = List.of(marker, marker + ".json", "dep-" + marker, "output-" + marker);
		int invalidated = invalidateMarkers(releaseDirectory.resolve(".fingerprint"), markers);

		PayloadFiles.delete(releaseDirectory.resolve(binary));
		PayloadFiles.delete(releaseDirectory.resolve(binary + ".exe"));
		Path symbols = releaseDirectory.resolve(binary + ".dSYM");
		if (Files.isDirectory(symbols, LinkOption.NOFOLLOW_LINKS))
			FileUtils.deleteDirectory(symbols.toFile());
		else
			PayloadFiles.delete(symbols);
		return invalidated;
	}

	/**
	 * Removes only matching executable markers from each Cargo fingerprint directory.
	 *
	 * @param fingerprints the fingerprint directory
	 * @param markers binary markers followed by dependency and diagnostic markers
	 * @return the number of matching units
	 * @throws IOException if discovery or marker deletion fails
	 */
	private static int invalidateMarkers(Path fingerprints, List<String> markers) throws IOException
	{
		if (!Files.isDirectory(fingerprints))
			return 0;
		int invalidated = 0;
		try (Stream<Path> units = Files.list(fingerprints))
		{
			for (Path unit : units.sorted().toList())
			{
				if (!Files.isDirectory(unit) || (!Files.isRegularFile(unit.resolve(markers.get(0))) &&
					!Files.isRegularFile(unit.resolve(markers.get(1)))))
					continue;
				for (String marker : markers)
					PayloadFiles.delete(unit.resolve(marker));
				invalidated += 1;
			}
		}
		return invalidated;
	}
}
