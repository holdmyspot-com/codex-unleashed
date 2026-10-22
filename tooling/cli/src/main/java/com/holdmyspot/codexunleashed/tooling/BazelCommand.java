package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Executes Bazel with the selected BuildBuddy configuration and caller-owned standard streams. */
public final class BazelCommand
{
	/** The maintained Bazel wrapper command. */
	public static final String NAME = "run-bazel-with-buildbuddy";
	/** Allows only the invoking user to access the scheduler's IPC directory on POSIX filesystems. */
	private static final Set<PosixFilePermission> IPC_PERMISSIONS = PosixFilePermissions.fromString("rwx------");
	// WORKAROUND: https://github.com/bazelbuild/bazel/issues/31786
	// Remove this exact diagnostic filter when Bazel's launcher stops adding -Xverify:none.
	private static final byte[] OBSOLETE_VERIFICATION_WARNING = ("OpenJDK 64-Bit Server VM warning: " +
		"Options -Xverify:none and -noverify were deprecated in JDK 13 and will likely be removed in a future release.").
		getBytes(StandardCharsets.US_ASCII);

	/** Prevents construction. */
	private BazelCommand()
	{
	}

	/**
	 * Runs the direct Bazel child and returns its ordinary status without shell argument reparsing.
	 *
	 * @param arguments literal Bazel arguments
	 * @param err diagnostic output
	 * @return child exit status, or two when no command is supplied
	 * @throws NullPointerException if arguments, an argument element or diagnostics are null
	 * @throws IOException if event encoding, process startup or waiting fails
	 */
	public static int run(String[] arguments, PrintStream err) throws IOException
	{
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(err, "err");
		List<String> values = List.copyOf(Arrays.asList(arguments));
		if (values.stream().allMatch(value -> value.startsWith("-")))
		{
			err.println("Usage: " + NAME + " [startup options] <Bazel command> [arguments]");
			return 2;
		}
		Map<String, String> environment = System.getenv();
		Path directory = Path.of("").toAbsolutePath();
		if (BazelCommands.buildsCompilers(values))
			return scheduleCompilers(values, environment, directory, err);
		return execute(values, environment, directory, err);
	}

	/**
	 * Keeps the scheduler host alive for the complete child invocation and removes its private IPC directory afterward.
	 *
	 * @param arguments literal Bazel arguments
	 * @param environment the invoking process environment
	 * @param directory the child working directory
	 * @param err diagnostic output
	 * @return the child's ordinary status
	 * @throws IOException if the scheduler, build, or IPC cleanup fails
	 */
	private static int scheduleCompilers(List<String> arguments, Map<String, String> environment,
		Path directory, PrintStream err) throws IOException
	{
		Path temporaryDirectory = Path.of(environment.getOrDefault("TMPDIR", System.getProperty("java.io.tmpdir"))).
			toAbsolutePath();
		StringBuilder identity = new StringBuilder(directory.toRealPath().toString()).append('\0').
			append(environment.getOrDefault("BAZEL_OUTPUT_USER_ROOT", ""));
		for (String argument : arguments.stream().takeWhile(value -> value.startsWith("-")).toList())
		{
			if (argument.startsWith("--output_base=") || argument.startsWith("--output_user_root="))
				identity.append('\0').append(argument);
		}
		String name = "bazel-compiler-" + UUID.nameUUIDFromBytes(identity.toString().getBytes(StandardCharsets.UTF_8));
		Path ipcDirectory = temporaryDirectory.resolve(name);
		// Stable socket arguments preserve compiler cache keys. Keep the lock inode for any waiting invocation.
		Path locks = Path.of(environment.getOrDefault("XDG_CACHE_HOME", temporaryDirectory.toString())).
			resolve("codex-compiler-locks");
		Files.createDirectories(locks);
		try (FileChannel channel = FileChannel.open(locks.resolve(name + ".lock"),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock _ = channel.lock())
		{
			if (Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"))
				Files.createDirectory(ipcDirectory, PosixFilePermissions.asFileAttribute(IPC_PERMISSIONS));
			else
				Files.createDirectory(ipcDirectory);
			try (Closeable _ = () -> Files.delete(ipcDirectory);
				CompilerSchedulerProcess service = new CompilerSchedulerProcess(ipcDirectory, err))
			{
				Map<String, String> scheduledEnvironment = new HashMap<>(environment);
				scheduledEnvironment.put(BazelCommands.SCHEDULER_DIRECTORY, ipcDirectory.toString());
				int status = execute(arguments, scheduledEnvironment, directory, err);
				service.checkAlive();
				return status;
			}
		}
	}

	/**
	 * Runs a planned Bazel invocation with explicit environmental inputs and streams its diagnostics.
	 *
	 * @param values literal Bazel arguments
	 * @param environment the child's complete environment
	 * @param directory the child working directory
	 * @param err diagnostic output
	 * @return the child's ordinary status
	 * @throws IOException if planning, process startup, diagnostics, or waiting fails
	 */
	private static int execute(List<String> values, Map<String, String> environment, Path directory, PrintStream err)
		throws IOException
	{
		BazelCommands.Invocation invocation = BazelCommands.plan(values, environment, directory,
			System.getProperty("os.name"));
		if (invocation.remoteConfig().isEmpty())
			err.println("BuildBuddy key unavailable; using local Bazel configuration.");
		else
		{
			String host = "generic";
			if (invocation.openaiHost())
				host = "OpenAI tenant";
			err.println("Using " + host + " BuildBuddy configuration: " + invocation.remoteConfig().orElseThrow() + ".");
		}
		ProcessBuilder builder = SystemCommands.createBuilder(invocation.command()).directory(directory.toFile()).
			inheritIO().redirectError(ProcessBuilder.Redirect.PIPE);
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try (InputStream diagnostics = new BufferedInputStream(process.getErrorStream()))
			{
				copyDiagnostics(diagnostics, err);
			}
			try
			{
				return process.waitFor();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Bazel", failure);
			}
		}
	}

	/**
	 * Streams raw diagnostic bytes, removing only the complete obsolete launcher warning with LF, CRLF, or EOF.
	 * A matching prefix is bounded by the warning length; other output streams immediately without line buffering.
	 *
	 * @param input the child's diagnostic stream
	 * @param output the caller's diagnostic stream
	 * @throws IOException if reading the child diagnostics fails
	 */
	private static void copyDiagnostics(InputStream input, PrintStream output) throws IOException
	{
		int matched = 0;
		for (int value = input.read(); value != -1; value = input.read())
		{
			if (matched >= 0 && matched < OBSOLETE_VERIFICATION_WARNING.length &&
				value == OBSOLETE_VERIFICATION_WARNING[matched])
			{
				matched += 1;
				continue;
			}
			if (matched == OBSOLETE_VERIFICATION_WARNING.length)
			{
				if (value == '\n')
				{
					matched = 0;
					continue;
				}
				if (value == '\r')
				{
					int following = input.read();
					if (following == '\n')
					{
						matched = 0;
						continue;
					}
					output.write(OBSOLETE_VERIFICATION_WARNING);
					output.write(value);
					if (following >= 0)
						output.write(following);
					matched = -1;
					continue;
				}
			}
			if (matched > 0)
				output.write(OBSOLETE_VERIFICATION_WARNING, 0, matched);
			output.write(value);
			matched = -1;
			if (value == '\n')
				matched = 0;
		}
		if (matched > 0 && matched < OBSOLETE_VERIFICATION_WARNING.length)
			output.write(OBSOLETE_VERIFICATION_WARNING, 0, matched);
		output.flush();
	}
}
