package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads Git commit and object metadata for the blob size policy.
 */
public final class GitBlobObjects
{
	/**
	 * Maximum diagnostic bytes retained from a failed Git command.
	 */
	private static final int MAX_DIAGNOSTIC_BYTES = 4096;

	/**
	 * Prevents construction.
	 */
	private GitBlobObjects()
	{
	}

	/**
	 * Resolves the selected commits, then streams every newly reachable object's metadata through the policy.
	 *
	 * @param repository Git repository
	 * @param base supplied excluded ancestor revision, or a nonempty all-zero revision for all HEAD history
	 * @param head proposed commit revision
	 * @param temporaryDirectory parent for the object-ID spool and Git diagnostics
	 * @param err diagnostics for oversized blobs
	 * @return zero when accepted, or one when a newly reachable blob exceeds the size limit
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if Git cannot resolve or enumerate the requested objects, or a temporary file fails
	 */
	public static int check(Path repository, String base, String head, Path temporaryDirectory, PrintStream err)
		throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(head, "head");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Objects.requireNonNull(err, "err");
		String resolvedHead = resolveCommit(repository, head, temporaryDirectory);
		String resolvedBase = null;
		if (!BlobSizeCommand.isAllZero(base))
			resolvedBase = resolveCommit(repository, base, temporaryDirectory);
		List<String> revList = BlobSizeCommand.revListArguments(resolvedHead, base, resolvedBase);
		try (TemporaryDirectory temporary = new TemporaryDirectory(temporaryDirectory))
		{
			Path objectIds = temporary.path().resolve("object-ids");
			Path diagnostics = temporary.path().resolve("git-stderr");
			Files.createFile(objectIds);
			Files.createFile(diagnostics);
			runRevList(revList, repository, objectIds, diagnostics);
			return runCatFile(repository, objectIds, diagnostics, err);
		}
	}

	/**
	 * Resolves a revision to a commit using Git's end-of-options boundary.
	 *
	 * @param repository Git repository
	 * @param revision revision supplied by the caller
	 * @param temporaryDirectory parent for bounded Git capture files
	 * @return resolved commit object ID
	 * @throws IOException if Git cannot resolve the revision to a commit
	 */
	private static String resolveCommit(Path repository, String revision, Path temporaryDirectory) throws IOException
	{
		List<String> command = List.of("git", "rev-parse", "--verify", "--end-of-options", revision + "^{commit}");
		SystemCommands.Result result = SystemCommands.capture(command, repository, temporaryDirectory, Map.of());
		if (result.status() != 0)
			throw new IOException("git rev-parse could not resolve " + revision + " to a commit: " +
				trimDiagnostic(result.stderr()));
		String objectId = result.stdout().strip();
		if (objectId.isEmpty())
			throw new IOException("git rev-parse returned no commit ID for " + revision);
		return objectId;
	}

	/**
	 * Spools the object IDs reachable from HEAD and checks Git's exit status.
	 *
	 * @param command direct Git argument vector
	 * @param repository Git repository
	 * @param objectIds destination file for object IDs
	 * @param diagnostics destination file for Git diagnostics
	 * @throws IOException if Git cannot enumerate the selected range
	 */
	private static void runRevList(List<String> command, Path repository, Path objectIds, Path diagnostics)
		throws IOException
	{
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(repository.toFile()).
			redirectInput(ProcessBuilder.Redirect.from(ProcessBuilder.Redirect.DISCARD.file())).
			redirectOutput(objectIds.toFile()).
			redirectError(diagnostics.toFile());
		try (Process process = builder.start())
		{
			int status = waitFor(process, "git rev-list");
			if (status != 0)
				throw new IOException("git rev-list failed with status " + status + ": " +
					readDiagnostic(diagnostics));
		}
	}

	/**
	 * Streams batch-check output as it arrives and checks the process result after end-of-file.
	 *
	 * @param repository Git repository
	 * @param objectIds file containing object IDs from rev-list
	 * @param diagnostics destination file for Git diagnostics
	 * @param err diagnostics for oversized blobs
	 * @return zero when accepted, or one when a newly reachable blob exceeds the size limit
	 * @throws IOException if Git cannot inspect the object IDs or a returned record is malformed
	 */
	private static int runCatFile(Path repository, Path objectIds, Path diagnostics, PrintStream err)
		throws IOException
	{
		List<String> command = List.of("git", "cat-file", "--batch-check");
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(repository.toFile()).
			redirectInput(objectIds.toFile()).redirectError(diagnostics.toFile());
		try (Process process = builder.start())
		{
			try (BufferedReader records = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				int result = BlobSizePolicy.check(records, err);
				int status = waitFor(process, "git cat-file");
				if (status != 0)
					throw new IOException("git cat-file failed with status " + status + ": " + readDiagnostic(diagnostics));
				return result;
			}
			catch (IOException | RuntimeException failure)
			{
				if (process.isAlive())
					process.destroyForcibly();
				reap(process);
				throw failure;
			}
		}
	}

	/**
	 * Waits for a Git process and restores interruption after forcing its cleanup.
	 *
	 * @param process running Git process
	 * @param name command name for the interruption diagnostic
	 * @return process exit status
	 * @throws IOException if waiting is interrupted
	 */
	private static int waitFor(Process process, String name) throws IOException
	{
		try
		{
			return process.waitFor();
		}
		catch (InterruptedException failure)
		{
			process.destroyForcibly();
			reap(process);
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + name, failure);
		}
	}

	/**
	 * Reaps a child after its output cannot be consumed successfully.
	 *
	 * @param process child process to reap
	 */
	private static void reap(Process process)
	{
		boolean interrupted = Thread.interrupted();
		while (process.isAlive())
		{
			try
			{
				process.waitFor();
			}
			catch (InterruptedException _)
			{
				interrupted = true;
				process.destroyForcibly();
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	/**
	 * Reads at most the supported diagnostic size from a Git error file.
	 *
	 * @param file Git diagnostic file
	 * @return UTF-8 diagnostic text, clipped at the fixed limit
	 * @throws IOException if the diagnostic file cannot be read
	 */
	private static String readDiagnostic(Path file) throws IOException
	{
		byte[] bytes;
		try (InputStream input = Files.newInputStream(file))
		{
			bytes = input.readNBytes(MAX_DIAGNOSTIC_BYTES);
		}
		return trimDiagnostic(new String(bytes, StandardCharsets.UTF_8));
	}

	/**
	 * Removes trailing whitespace from a diagnostic.
	 *
	 * @param diagnostic decoded diagnostic text
	 * @return diagnostic without trailing whitespace
	 */
	private static String trimDiagnostic(String diagnostic)
	{
		return diagnostic.stripTrailing();
	}

	/**
	 * Owns the directory containing the object-ID spool and Git diagnostic files.
	 */
	private static final class TemporaryDirectory implements AutoCloseable
	{
		/**
		 * Directory containing this operation's temporary files.
		 */
		private final Path path;

		/**
		 * Creates a private directory beneath the supplied parent.
		 *
		 * @param parent existing temporary-file parent
		 * @throws IOException if the directory cannot be created
		 */
		private TemporaryDirectory(Path parent) throws IOException
		{
			path = Files.createTempDirectory(parent, "blob-policy-");
		}

		/**
		 * Returns the directory owned by this scope.
		 *
		 * @return owned temporary directory
		 */
		private Path path()
		{
			return path;
		}

		/**
		 * Removes the owned files and directory.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			Files.walkFileTree(path, new SimpleFileVisitor<>()
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
	}
}
