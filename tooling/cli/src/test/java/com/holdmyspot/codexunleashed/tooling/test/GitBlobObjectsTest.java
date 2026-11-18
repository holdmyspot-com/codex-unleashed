package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that the public command reaches Git and returns its accepted result.
 */
public final class GitBlobObjectsTest
{
	/**
	 * Creates repository integration checks.
	 */
	public GitBlobObjectsTest()
	{
	}

	/**
	 * Returns success through Main after Git supplies the object IDs and batch-check records for a small commit.
	 *
	 * @throws IOException if the test repository cannot be created or removed
	 */
	@Test
	public void checksRealRepositoryThroughMain() throws IOException
	{
		try (TemporaryDirectory temporary = new TemporaryDirectory())
		{
			Path repository = temporary.path().resolve("repository");
			Files.createDirectory(repository);
			runGit(repository, "init", "-q");
			runGit(repository, "config", "user.email", "blob-test@example.invalid");
			runGit(repository, "config", "user.name", "Blob Test");
			Files.writeString(repository.resolve("small.txt"), "small blob");
			runGit(repository, "add", "small.txt");
			runGit(repository, "commit", "-q", "-m", "small blob");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"check-blob-size", "--base", "000000", "--head", "HEAD",
					"--repo", repository.toString()}, InputStream.nullInputStream(), out, err);
				requireThat(status, "status").isEqualTo(0);
				requireThat(output.toString(StandardCharsets.UTF_8), "output").isEmpty();
				requireThat(diagnostics.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
	}

	/**
	 * Owns the temporary repository tree used by the integration check.
	 */
	private static final class TemporaryDirectory implements AutoCloseable
	{
		/**
		 * Root of the temporary repository tree.
		 */
		private final Path path;

		/**
		 * Creates the fixture parent directory.
		 *
		 * @throws IOException if the temporary directory cannot be created
		 */
		private TemporaryDirectory() throws IOException
		{
			path = Files.createTempDirectory("blob-size-command-test-");
		}

		/**
		 * Returns the owned fixture directory.
		 *
		 * @return fixture directory
		 */
		private Path path()
		{
			return path;
		}

		/**
		 * Removes the fixture tree after the test.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			Files.walkFileTree(path, new SimpleFileVisitor<>()
			{
				@Override
				public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
				{
					DosFileAttributeView dosAttributeView = Files.getFileAttributeView(file, DosFileAttributeView.class,
						LinkOption.NOFOLLOW_LINKS);
					boolean hasReadOnlyDosAttributes = dosAttributeView != null &&
						dosAttributeView.readAttributes().isReadOnly();
					if (hasReadOnlyDosAttributes)
						dosAttributeView.setReadOnly(false);
					Files.delete(file);
					return java.nio.file.FileVisitResult.CONTINUE;
				}

				@Override
				public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
				{
					if (failure != null)
						throw failure;
					Files.delete(directory);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		}
	}

	/**
	 * Runs one real Git command in the fixture repository.
	 *
	 * @param repository Git working directory
	 * @param arguments command arguments after {@code git}
	 * @throws IOException if Git cannot start or the command fails
	 */
	private static void runGit(Path repository, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true).start();
		try (process)
		{
			byte[] output = process.getInputStream().readAllBytes();
			try
			{
				int status = process.waitFor();
				if (status != 0)
					throw new IOException("Git fixture command failed with status " + status + ": " +
						new String(output, StandardCharsets.UTF_8));
			}
			catch (InterruptedException failure)
			{
				process.destroyForcibly();
				while (process.isAlive())
				{
					try
					{
						process.waitFor();
					}
				catch (InterruptedException _)
					{
						process.destroyForcibly();
					}
				}
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while creating the Git fixture", failure);
			}
		}
	}
}
