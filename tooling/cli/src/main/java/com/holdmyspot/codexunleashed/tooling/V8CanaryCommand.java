package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * Exposes canary decisions with explicit checkout and retained comparison or force options.
 */
public final class V8CanaryCommand
{
	/**
	 * Prevents construction.
	 */
	private V8CanaryCommand()
	{
	}

	/**
	 * Prints the four workflow decision lines or usage diagnostics.
	 *
	 * @param args the checkout and command options
	 * @param out the decision and help destination
	 * @param err the diagnostic destination
	 * @param runner the Git command boundary
	 * @return zero on success or help, one on a missing range, or two on invalid arguments
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if Git or lockfile processing fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err, CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(runner, "runner");
		CommandSpec specification = CommandSpec.create().name("v8-canary-changes");
		specification.addPositional(PositionalParamSpec.builder().index("0").type(Path.class).required(true).build());
		for (String option : new String[]{"--base", "--head"})
			specification.addOption(OptionSpec.builder(option).type(String.class).build());
		specification.addOption(OptionSpec.builder("--force").type(boolean.class).arity("0").build());
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

		V8CanaryChanges.Decision decision;
		if (result.matchedOptionValue("--force", false))
			decision = V8CanaryChanges.forced();
		else
		{
			String base = result.matchedOptionValue("--base", "");
			String head = result.matchedOptionValue("--head", "");
			if (base.isEmpty() || head.isEmpty())
			{
				err.println("--base and --head are required unless --force is set");
				return 1;
			}
			decision = V8CanaryChanges.compare(result.matchedPositionalValue(0, Path.of(".")), base, head, runner);
		}
		out.println("canary_required=" + decision.canaryRequired());
		out.println("canary_reason=" + decision.canaryReason());
		out.println("windows_source_required=" + decision.windowsSourceRequired());
		out.println("windows_source_reason=" + decision.windowsSourceReason());
		return 0;
	}
}
