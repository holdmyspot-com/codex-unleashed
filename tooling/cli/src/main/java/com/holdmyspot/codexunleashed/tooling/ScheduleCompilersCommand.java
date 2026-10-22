package com.holdmyspot.codexunleashed.tooling;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a build whose rustc invocations are scheduled by the free memory of the system and the build's control group.
 */
public final class ScheduleCompilersCommand
{
	/**
	 * The command name.
	 */
	public static final String NAME = "schedule-compilers";
	/**
	 * Prevents construction.
	 */
	private ScheduleCompilersCommand()
	{
	}

	/**
	 * Runs a build under the compiler scheduler.
	 *
	 * @param args the separator and the build command
	 * @param err the diagnostic destination
	 * @return the build's status, or two for invalid arguments
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if the scheduler or the build cannot start
	 */
	public static int run(String[] args, PrintStream err) throws IOException
	{
		Objects.requireNonNull(err, "err");
		for (String argument : args)
			Objects.requireNonNull(argument, "argument");
		if (args.length < 2 || !args[0].equals("--"))
		{
			err.println("Usage: " + NAME + " -- <command> [argument ...]");
			return 2;
		}
		Path temporaryDirectory = Path.of(System.getenv().getOrDefault("TMPDIR", System.getProperty("java.io.tmpdir"))).
			toAbsolutePath();
		Path directory = Files.createTempDirectory(temporaryDirectory, "cargo-compiler-");
		String filename = "compiler-wrapper";
		if (System.getProperty("os.name").startsWith("Windows"))
			filename += ".exe";
		Path wrapper = directory.resolve(filename);
		try (Closeable _ = () ->
		{
			Files.deleteIfExists(wrapper);
			Files.delete(directory);
		}; CompilerSchedulerProcess service = new CompilerSchedulerProcess(directory, err))
		{
			installWrapper(wrapper);
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("CODEX_COMPILER_SCHEDULER_SOCKET", directory.resolve("s").toString());
			environment.put("RUSTC_WRAPPER", wrapper.toString());
			int status = SystemCommands.execute(Arrays.asList(Arrays.copyOfRange(args, 1, args.length)),
				Path.of("").toAbsolutePath(), environment);
			service.checkAlive();
			return status;
		}
	}

	/**
	 * Extracts the host controller compiled into this platform's tooling runtime.
	 *
	 * @param wrapper the invocation-owned executable path
	 * @throws IOException if the controller is absent or cannot be installed
	 */
	private static void installWrapper(Path wrapper) throws IOException
	{
		try (InputStream source = ScheduleCompilersCommand.class.getResourceAsStream("native/" + wrapper.getFileName()))
		{
			if (source == null)
				throw new IOException("The tooling runtime does not contain its native compiler controller");
			Files.copy(source, wrapper);
		}
		if (Files.getFileStore(wrapper).supportsFileAttributeView("posix"))
			Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
	}
}
