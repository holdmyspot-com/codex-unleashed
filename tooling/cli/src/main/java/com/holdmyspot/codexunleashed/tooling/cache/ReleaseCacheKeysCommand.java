package com.holdmyspot.codexunleashed.tooling.cache;

import java.io.PrintStream;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Exposes release cache identity derivation through compatible long-option arguments.
 */
public final class ReleaseCacheKeysCommand
{
	/**
	 * Prevents construction.
	 */
	private ReleaseCacheKeysCommand()
	{
	}

	/**
	 * Parses release cache inputs and prints identities or usage diagnostics.
	 *
	 * @param args the command options
	 * @param out the identity and help destination
	 * @param err the diagnostic destination
	 * @return zero on success or help, or two on invalid arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name("release-cache-keys");
		for (String option : new String[]{"--target", "--compiler-fingerprint", "--v8-version"})
			specification.addOption(OptionSpec.builder(option).type(String.class).required(true).build());
		specification.addOption(OptionSpec.builder("--mode").type(String.class).build());
		specification.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(specification).setOverwrittenOptionsAllowed(true).
			setExpandAtFiles(false).setAbbreviatedOptionsAllowed(true);
		try
		{
			CommandLine.ParseResult result = parser.parseArgs(args);
			if (result.isUsageHelpRequested())
			{
				parser.usage(out);
				return 0;
			}
			for (String line : ReleaseCacheKeys.derive(result.matchedOptionValue("--target", ""),
				result.matchedOptionValue("--compiler-fingerprint", ""),
				result.matchedOptionValue("--v8-version", ""), result.matchedOptionValue("--mode", "off")))
				out.println(line);
			return 0;
		}
		catch (CommandLine.ParameterException | IllegalArgumentException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
	}
}
