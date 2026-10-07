package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Copies raw payload trees into new caller-owned directories, following source links and retaining attributes.
 */
public final class PayloadDirectories
{
	/**
	 * Prevents construction.
	 */
	private PayloadDirectories()
	{
	}

	/**
	 * Copies a source directory into a new destination and restores directory attributes after population.
	 * The caller owns the output directory, including partial output after failure.
	 *
	 * @param source the source directory
	 * @param destination the new destination directory whose parent already exists
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if the source is invalid, the destination exists, or copying or traversal fails
	 */
	public static void copy(Path source, Path destination) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		if (!Files.isDirectory(source))
			throw new IOException("Payload source must be a directory: " + source);
		Files.createDirectory(destination);
		try (Stream<Path> paths = Files.walk(source, FileVisitOption.FOLLOW_LINKS))
		{
			List<Path> files = paths.toList();
			for (Path file : files)
			{
				if (file.equals(source))
					continue;
				Path target = destination.resolve(source.relativize(file));
				if (Files.isDirectory(file))
					Files.createDirectory(target);
				else
				{
					if (!Files.isRegularFile(file))
						throw new IOException("Payload must be a regular file: " + file);
					Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
			for (Path file : files.reversed())
				if (Files.isDirectory(file))
					copyDirectoryAttributes(file, destination.resolve(source.relativize(file)));
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/**
	 * Restores directory timestamps and supported permissions after creating children.
	 *
	 * @param source the source directory
	 * @param destination the populated destination directory
	 * @throws IOException if attributes cannot be read or written
	 */
	private static void copyDirectoryAttributes(Path source, Path destination) throws IOException
	{
		BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
		Files.getFileAttributeView(destination, BasicFileAttributeView.class).
			setTimes(attributes.lastModifiedTime(), attributes.lastAccessTime(), null);
		PosixFileAttributeView sourcePermissions = Files.getFileAttributeView(source, PosixFileAttributeView.class);
		PosixFileAttributeView destinationPermissions =
			Files.getFileAttributeView(destination, PosixFileAttributeView.class);
		if (sourcePermissions != null && destinationPermissions != null)
			destinationPermissions.setPermissions(sourcePermissions.readAttributes().permissions());
	}
}
