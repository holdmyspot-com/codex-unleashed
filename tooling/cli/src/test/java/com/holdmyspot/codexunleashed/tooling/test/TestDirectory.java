package com.holdmyspot.codexunleashed.tooling.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.apache.commons.io.file.PathUtils;

/**
 * A temporary directory that belongs to one test and is deleted, with any symbolic links in it, when the test closes
 * it.
 */
public final class TestDirectory implements AutoCloseable
{
	/**
	 * The directory.
	 */
	private final Path path;

	/**
	 * Creates a temporary directory.
	 *
	 * @param prefix the start of the directory's name
	 * @throws NullPointerException if {@code prefix} is null
	 * @throws IOException          if the directory cannot be created
	 */
	public TestDirectory(String prefix) throws IOException
	{
		Objects.requireNonNull(prefix, "prefix");
		path = Files.createTempDirectory(prefix);
	}

	/**
	 * Returns the directory.
	 *
	 * @return the directory
	 */
	public Path getPath()
	{
		return path;
	}

	/**
	 * Creates a directory and its missing parents inside this one.
	 *
	 * @param relative the directory's path relative to this one
	 * @return the directory
	 * @throws NullPointerException if {@code relative} is null
	 * @throws IOException          if the directory cannot be created
	 */
	public Path createDirectories(String relative) throws IOException
	{
		return Files.createDirectories(path.resolve(relative));
	}

	/**
	 * Writes a UTF-8 file inside this directory, creating its parent directories.
	 *
	 * @param relative the file's path relative to this directory
	 * @param content  the file's content
	 * @return the file
	 * @throws NullPointerException if an argument is null
	 * @throws IOException          if the file cannot be written
	 */
	public Path write(String relative, String content) throws IOException
	{
		Path file = path.resolve(relative);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content);
	}

	@Override
	public void close() throws IOException
	{
		PathUtils.deleteDirectory(path);
	}
}
