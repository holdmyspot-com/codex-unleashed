package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes external tools with direct argument boundaries and inherited input.
 */
public final class SystemCommands
{
	/**
	 * Prevents construction.
	 */
	private SystemCommands()
	{
	}

	/**
	 * Creates a process builder with literal arguments on Unix and explicit C runtime argument encoding on Windows.
	 *
	 * @param command the executable followed by literal arguments
	 * @return the builder with platform-specific argument encoding
	 * @throws NullPointerException if the command or an element is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if the Windows executable or JDK quoting policy is incompatible with direct execution
	 */
	public static ProcessBuilder createBuilder(List<String> command) throws IOException
	{
		List<String> arguments = List.copyOf(command);
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");
		return new ProcessBuilder(WindowsArguments.prepare(arguments));
	}

	/**
	 * Executes a command with inherited streams and only the supplied environment variables.
	 *
	 * @param command the executable followed by its arguments
	 * @param workingDirectory the child working directory
	 * @param environment the complete child environment
	 * @return the ordinary process exit status, including unsuccessful outcomes
	 * @throws NullPointerException if an argument, command element, environment key, or value is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if startup, waiting, or process cleanup fails
	 */
	public static int execute(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException
	{
		List<String> arguments = List.copyOf(command);
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Map<String, String> variables = Map.copyOf(environment);
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");
		ProcessBuilder builder = createBuilder(arguments).directory(workingDirectory.toFile()).inheritIO();
		builder.environment().clear();
		builder.environment().putAll(variables);
		try (Process process = builder.start())
		{
			try
			{
				return process.waitFor();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for external command " + arguments.getFirst(), failure);
			}
		}
	}

	/**
	 * Contains a completed command's exit status and separate decoded output streams.
	 *
	 * @param status the process exit status, including nonzero outcomes
	 * @param stdout the UTF-8 standard output
	 * @param stderr the UTF-8 diagnostic output
	 */
	public record Result(int status, String stdout, String stderr)
	{
		/**
		 * Creates a completed command result.
		 *
		 * @param status the process exit status
		 * @param stdout the decoded standard output
		 * @param stderr the decoded diagnostic output
		 * @throws NullPointerException if {@code stdout} or {@code stderr} are null
		 */
		public Result
		{
			Objects.requireNonNull(stdout, "stdout");
			Objects.requireNonNull(stderr, "stderr");
		}
	}

	/**
	 * Captures a command's streams and status in the selected working directory.
	 * Disk-backed capture avoids blocking a child on an unread output pipe. Capture files are removed on every exit.
	 *
	 * @param command the executable followed by its arguments
	 * @param workingDirectory the child working directory
	 * @param temporaryDirectory the existing parent directory for owned capture files
	 * @return the completed status and separate UTF-8 output streams
	 * @throws NullPointerException if any argument or command element is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if startup, waiting, UTF-8 decoding, or capture cleanup fails
	 */
	public static Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory) throws IOException
	{
		return capture(command, workingDirectory, temporaryDirectory, Map.of());
	}

	/**
	 * Captures UTF-8 output with explicit overrides of inherited environment variables.
	 *
	 * @param command the executable followed by its arguments
	 * @param workingDirectory the child working directory
	 * @param temporaryDirectory the existing parent for owned capture files
	 * @param environment the explicit environment overrides
	 * @return the completed status and separate UTF-8 output streams
	 * @throws NullPointerException if any argument, command element, environment key, or value is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if startup, waiting, decoding, or cleanup fails
	 */
	public static Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
		Map<String, String> environment) throws IOException
	{
		return captureOutput(command, workingDirectory, temporaryDirectory, environment,
			(status, files) -> new Result(status, Files.readString(files.stdout), Files.readString(files.stderr)));
	}

	/**
	 * Contains a completed command's status, raw stdout digest, and decoded diagnostics.
	 *
	 * @param status the ordinary process exit status
	 * @param stdoutSha256 the lowercase SHA-256 of raw stdout bytes
	 * @param stderr the UTF-8 diagnostic output
	 */
	public record DigestResult(int status, String stdoutSha256, String stderr)
	{
		/**
		 * Creates a completed digest result.
		 *
		 * @param status the ordinary process exit status
		 * @param stdoutSha256 the raw output digest
		 * @param stderr the diagnostic output
		 * @throws NullPointerException if either output is null
		 */
		public DigestResult
		{
			Objects.requireNonNull(stdoutSha256, "stdoutSha256");
			Objects.requireNonNull(stderr, "stderr");
		}
	}

	/**
	 * Hashes stdout as raw bytes without decoding it or loading the whole stream into memory.
	 *
	 * @param command the executable followed by its arguments
	 * @param workingDirectory the child working directory
	 * @param temporaryDirectory the existing parent for owned capture files
	 * @param environment the explicit overrides of inherited environment variables
	 * @return the completed status, raw output digest, and UTF-8 diagnostics
	 * @throws NullPointerException if any argument, command element, environment key, or value is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if startup, waiting, reading, diagnostic decoding, or cleanup fails
	 * @throws IllegalStateException if the Java platform does not supply SHA-256
	 */
	public static DigestResult digest(List<String> command, Path workingDirectory, Path temporaryDirectory,
		Map<String, String> environment) throws IOException
	{
		return captureOutput(command, workingDirectory, temporaryDirectory, environment,
			(status, files) -> new DigestResult(status, Sha256.digest(files.stdout), Files.readString(files.stderr)));
	}

	/**
	 * Runs a command and consumes its files while capture ownership remains local.
	 *
	 * @param <T> the completed result type
	 * @param command the executable and arguments
	 * @param workingDirectory the child working directory
	 * @param temporaryDirectory the capture parent
	 * @param environment the environment overrides
	 * @param reader the result reader
	 * @return the completed result
	 * @throws IOException if process handling, result reading, or cleanup fails
	 */
	private static <T> T captureOutput(List<String> command, Path workingDirectory, Path temporaryDirectory,
		Map<String, String> environment, CaptureReader<T> reader) throws IOException
	{
		List<String> arguments = List.copyOf(command);
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
		Map<String, String> overrides = Map.copyOf(environment);
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");
		try (CaptureFiles files = new CaptureFiles(Files.createTempDirectory(temporaryDirectory, "command-capture-")))
		{
			ProcessBuilder builder = createBuilder(arguments).directory(workingDirectory.toFile()).
				redirectInput(ProcessBuilder.Redirect.INHERIT).redirectOutput(files.stdout.toFile()).
				redirectError(files.stderr.toFile());
			builder.environment().putAll(overrides);
			try (Process process = builder.start())
			{
				try
				{
					int status = process.waitFor();
					return reader.read(status, files);
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for external command " + arguments.getFirst(), failure);
				}
			}
		}
	}

	/**
	 * Reads a completed command's owned capture files.
	 *
	 * @param <T> the result type
	 */
	@FunctionalInterface
	private interface CaptureReader<T>
	{
		/**
		 * Produces a result before capture cleanup.
		 *
		 * @param status the command status
		 * @param files the owned capture files
		 * @return the completed result
		 * @throws IOException if capture reading fails
		 */
		T read(int status, CaptureFiles files) throws IOException;
	}

	/**
	 * Executes a command and reads its UTF-8 standard output without invoking a shell.
	 *
	 * @param command the executable followed by its arguments
	 * @return the command's standard output
	 * @throws NullPointerException if {@code command} or any element is null
	 * @throws IllegalArgumentException if the command is empty
	 * @throws IOException if launching, reading, or waiting fails, or the process exits unsuccessfully
	 */
	public static String run(List<String> command) throws IOException
	{
		List<String> arguments = List.copyOf(command);
		if (arguments.isEmpty())
			throw new IllegalArgumentException("An external command requires an executable");
		Process process = createBuilder(arguments).redirectInput(ProcessBuilder.Redirect.INHERIT).
			redirectError(ProcessBuilder.Redirect.INHERIT).start();
		try
		{
			byte[] output;
			try (InputStream stdout = process.getInputStream())
			{
				output = stdout.readAllBytes();
			}
			int status = process.waitFor();
			if (status != 0)
				throw new IOException("External command " + arguments.getFirst() + " failed with status " + status);
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).
				onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output)).toString();
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for external command " + arguments.getFirst(), failure);
		}
		finally
		{
			if (process.isAlive())
				process.destroyForcibly();
		}
	}

	/**
	 * Owns the two capture files and their containing directory.
	 */
	private static final class CaptureFiles implements AutoCloseable
	{
		private final Path root;
		private final Path stdout;
		private final Path stderr;

		/**
		 * Assigns capture paths beneath the newly allocated directory.
		 *
		 * @param root the owned directory
		 */
		private CaptureFiles(Path root)
		{
			this.root = root;
			stdout = root.resolve("stdout");
			stderr = root.resolve("stderr");
		}

		/**
		 * Removes all capture resources, retaining every cleanup failure.
		 *
		 * @throws IOException if one or more resources cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			IOException failure = null;
			for (Path path : new Path[]{stdout, stderr, root})
			{
				try
				{
					Files.deleteIfExists(path);
				}
				catch (IOException cleanupFailure)
				{
					if (failure == null)
						failure = cleanupFailure;
					else
						failure.addSuppressed(cleanupFailure);
				}
			}
			if (failure != null)
				throw failure;
		}
	}
}
