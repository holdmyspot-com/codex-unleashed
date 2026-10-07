package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Collects third-party payloads from Cargo's locked resolved dependency graph.
 */
public final class LicensePayloadsCommand
{
	/**
	 * Names the standalone license collection command.
	 */
	public static final String NAME = "collect-third-party-licenses";

	/**
	 * Prevents construction.
	 */
	private LicensePayloadsCommand()
	{
	}

	/**
	 * Parses source paths, runs locked Cargo metadata, and retains Cargo's failure diagnostics.
	 *
	 * @param args the command options
	 * @param out the help destination
	 * @param err the diagnostic destination
	 * @return zero on success or help, one on Cargo failure, or two on invalid arguments
	 * @throws IOException if process creation, capture, metadata, or collection fails
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name(NAME);
		for (String option : new String[]{"--manifest", "--output"})
			specification.addOption(OptionSpec.builder(option).type(Path.class).required(true).build());
		specification.addOption(OptionSpec.builder("--require-license-evidence").type(boolean.class).arity("0").build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(specification).setOverwrittenOptionsAllowed(true).
			setExpandAtFiles(false).setAbbreviatedOptionsAllowed(true);
		CommandLine.ParseResult result;
		try
		{
			result = parser.parseArgs(args);
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
		if (result.isUsageHelpRequested())
		{
			parser.usage(out);
			return 0;
		}
		Path manifest = result.matchedOptionValue("--manifest", null);
		Path output = result.matchedOptionValue("--output", null);
		SystemCommands.Result metadata = SystemCommands.capture(List.of("cargo", "metadata", "--format-version", "1",
			"--locked", "--manifest-path", manifest.toString()), Path.of(".").toAbsolutePath(),
			Path.of(System.getProperty("java.io.tmpdir")), Map.of());
		if (metadata.status() != 0)
		{
			err.print(metadata.stderr());
			err.println("ERROR: Cargo metadata failed with status " + metadata.status());
			return 1;
		}
		LicensePayloads.collect(metadata.stdout(), output, result.matchedOptionValue("--require-license-evidence", false));
		return 0;
	}
}
