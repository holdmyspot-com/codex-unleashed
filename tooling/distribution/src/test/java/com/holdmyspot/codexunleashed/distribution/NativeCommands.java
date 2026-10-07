package com.holdmyspot.codexunleashed.distribution;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes native test commands with literal arguments and the wrapper-selected Bash interpreter.
 */
final class NativeCommands
{
	/**
	 * Prevents construction.
	 */
	private NativeCommands()
	{
	}

	/**
	 * Creates a builder using the production argument encoder and an explicit native Bash path.
	 *
	 * @param command the executable and its literal arguments
	 * @return the configured builder
	 * @throws IOException if the native Bash interpreter or Windows argument policy is unavailable
	 */
	static ProcessBuilder createBuilder(String... command) throws IOException
	{
		return createBuilder(List.of(command));
	}

	/**
	 * Creates a builder using the production argument encoder and an explicit native Bash path.
	 *
	 * @param command the executable and its literal arguments
	 * @return the configured builder
	 * @throws IOException if the native Bash interpreter or Windows argument policy is unavailable
	 */
	static ProcessBuilder createBuilder(List<String> command) throws IOException
	{
		List<String> arguments = new ArrayList<>(command);
		if (!arguments.isEmpty() && "bash".equals(arguments.getFirst()))
			arguments.set(0, bashExecutable());
		return SystemCommands.createBuilder(arguments);
	}

	/**
	 * Creates a builder that runs a POSIX script through the selected native Bash interpreter.
	 *
	 * @param script the script path
	 * @param arguments the script's literal arguments
	 * @return the configured builder
	 * @throws IOException if the native command boundary is unavailable
	 */
	static ProcessBuilder scriptBuilder(Path script, String... arguments) throws IOException
	{
		String filename = script.toString();
		if (File.separatorChar == '\\')
			filename = filename.replace('\\', '/');
		List<String> command = new ArrayList<>(List.of("bash", filename));
		command.addAll(List.of(arguments));
		return createBuilder(command);
	}

	/**
	 * Selects the Bash executable supplied by the maintained Maven wrapper.
	 *
	 * @return the interpreter executable
	 * @throws IOException if Windows has no explicitly selected native Bash executable
	 */
	static String bashExecutable() throws IOException
	{
		String configured = System.getProperty("tooling.native.bash", "bash");
		if (File.separatorChar != '\\')
			return configured;

		Path executable = Path.of(configured);
		if (!executable.isAbsolute())
			throw new IOException("Windows tests require an absolute native Bash path; run them through tooling/mvnw");
		if (!Files.isRegularFile(executable))
			executable = executable.resolveSibling(executable.getFileName() + ".exe");
		if (!Files.isRegularFile(executable))
			throw new IOException("The configured native Bash executable is missing: " + executable);
		return executable.toString();
	}
}
