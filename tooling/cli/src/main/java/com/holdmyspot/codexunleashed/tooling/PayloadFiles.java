package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.Objects;

/**
 * Copies payload bytes while preserving existing destination links and permissions.
 */
public final class PayloadFiles
{
	/**
	 * Prevents construction.
	 */
	private PayloadFiles()
	{
	}

	/**
	 * Overwrites the destination contents without replacing the destination filesystem entry.
	 * The caller creates the destination parent before copying.
	 *
	 * @param source the source file
	 * @param destination the destination file
	 * @throws IOException if the files are identical or opening, copying, or closing fails
	 * @throws NullPointerException if either argument is null
	 */
	public static void copy(Path source, Path destination) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		if (Files.exists(destination) && Files.isSameFile(source, destination))
			throw new IOException("Payload source and destination are the same file: " + source);
		try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(destination))
		{
			input.transferTo(output);
		}
	}

	/**
	 * Deletes a payload file or link if present, refusing to remove a directory.
	 *
	 * @param file the payload file or link
	 * @throws IOException if the entry is a directory or deletion fails
	 * @throws NullPointerException if {@code file} is null
	 */
	public static void delete(Path file) throws IOException
	{
		Objects.requireNonNull(file, "file");
		if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Payload path is a directory: " + file);
		Files.deleteIfExists(file);
	}
}
