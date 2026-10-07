package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Normalizes checkout access and modification times while preserving Git metadata.
 */
public final class SourceTimestamps
{
	/**
	 * Prevents construction.
	 */
	private SourceTimestamps()
	{
	}

	/**
	 * Sets checkout file and directory times, processing each directory after its contents.
	 * Symbolic directories are not traversed; symbolic files retain the operating system's follow-link behavior.
	 *
	 * @param checkout the checkout directory
	 * @param epochSeconds the desired access and modification time in seconds since the Unix epoch
	 * @throws NullPointerException if {@code checkout} is null
	 * @throws IOException if the directory is absent or a file timestamp cannot be changed
	 */
	public static void normalize(Path checkout, long epochSeconds) throws IOException
	{
		Path root = Objects.requireNonNull(checkout, "checkout").toRealPath();
		if (!Files.isDirectory(root))
			throw new IOException("Checkout is not a directory: " + root);
		FileTime timestamp = FileTime.from(epochSeconds, TimeUnit.SECONDS);
		Files.walkFileTree(root, new SimpleFileVisitor<>()
		{
			/**
			 * {@inheritDoc}
			 */
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				if (!directory.equals(root) && ".git".equals(directory.getFileName().toString()))
					return FileVisitResult.SKIP_SUBTREE;
				return FileVisitResult.CONTINUE;
			}

			/**
			 * {@inheritDoc}
			 */
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!".git".equals(file.getFileName().toString()) && !Files.isDirectory(file))
					setTimes(file, timestamp);
				return FileVisitResult.CONTINUE;
			}

			/**
			 * {@inheritDoc}
			 */
			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
			{
				if (failure != null)
					throw failure;
				setTimes(directory, timestamp);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Sets the modification and access times without changing creation time.
	 *
	 * @param path the file or directory
	 * @param timestamp the desired time
	 * @throws IOException if the timestamp update fails
	 */
	private static void setTimes(Path path, FileTime timestamp) throws IOException
	{
		Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(timestamp, timestamp, null);
	}
}
