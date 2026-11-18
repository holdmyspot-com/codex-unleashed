package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Writes a direct process diagnostic report requested by the packaged quiet-build watcher.
 */
public final class QuietBuildDumpCommand
{
	/**
	 * Prevents construction.
	 */
	private QuietBuildDumpCommand()
	{
	}

	/**
	 * Runs a direct dump request using caller-owned output and the selected report writer.
	 *
	 * @param arguments dump protocol options, excluding the command name
	 * @param out caller-owned UTF-8 report output; remains open
	 * @param dump writes the report for the selected optional JVM diagnostic executable
	 * @return zero after the report is written
	 * @throws NullPointerException if an argument is null
	 * @throws IllegalArgumentException if {@code --dump} or a required option value is missing, or an argument is
	 *         unsupported
	 * @throws IOException if the report cannot be written
	 */
	public static int run(String[] arguments, PrintStream out, Dump dump) throws IOException
	{
		requireThat(arguments, "arguments").isNotNull();
		requireThat(out, "out").isNotNull();
		requireThat(dump, "dump").isNotNull();

		boolean dumpRequested = false;
		Path jcmd = null;
		for (int index = 0; index < arguments.length; index += 1)
		{
			String argument = arguments[index];
			requireThat(argument, "argument").isNotNull();
			if (argument.equals("--dump"))
				dumpRequested = true;
			else if (argument.equals("--jcmd"))
			{
				if (index + 1 == arguments.length)
					throw new IllegalArgumentException("Option --jcmd requires a PATH");
				String path = arguments[index + 1];
				requireThat(path, "jcmdPath").isNotNull();
				try
				{
					jcmd = Path.of(path);
				}
				catch (InvalidPathException failure)
				{
					throw new IllegalArgumentException("Option --jcmd requires a valid PATH", failure);
				}
				index += 1;
			}
			else
				throw new IllegalArgumentException("Unexpected option or positional argument '" + argument +
					"'; expected --dump or --jcmd PATH");
		}
		if (!dumpRequested)
			throw new IllegalArgumentException("Expected --dump");

		Writer output = new OutputStreamWriter(out, StandardCharsets.UTF_8);
		dump.dump(jcmd, output);
		output.flush();
		return 0;
	}

	/** Writes one host diagnostic report to the supplied writer. */
	@FunctionalInterface
	public interface Dump
	{
		/**
		 * Writes the report for the selected optional JVM diagnostic executable.
		 *
		 * @param jcmd JVM diagnostic executable, or null when no JVM dump is requested
		 * @param output UTF-8 report writer borrowed for this call; remains open
		 * @throws IOException if report generation or writing fails
		 */
		void dump(Path jcmd, Writer output) throws IOException;
	}
}
