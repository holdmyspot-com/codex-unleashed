package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Audits release sources using compatible long-option arguments.
 */
public final class SourceProvenanceCommand
{
	/**
	 * Prevents construction.
	 */
	private SourceProvenanceCommand()
	{
	}

	/**
	 * Parses source paths and metadata, then writes a report only for verified sources.
	 *
	 * @param args the command options
	 * @param out the help destination
	 * @param err the diagnostic destination
	 * @return zero on success or help, or two on invalid arguments
	 * @throws IOException if source auditing or report creation fails
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name("generate-source-provenance");
		for (String option : new String[]{"--upstream-checkout", "--patch-repo", "--output"})
			specification.addOption(OptionSpec.builder(option).type(Path.class).required(true).build());
		for (String option : new String[]{"--upstream-repository", "--upstream-ref", "--workflow-path", "--workflow-sha",
			"--workflow-run-id"})
			specification.addOption(OptionSpec.builder(option).type(String.class).build());
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
		SourceProvenance.Metadata metadata = new SourceProvenance.Metadata(
			result.matchedOptionValue("--upstream-repository", "openai/codex"),
			result.matchedOptionValue("--upstream-ref", ""), result.matchedOptionValue("--workflow-path", ""),
			result.matchedOptionValue("--workflow-sha", ""), result.matchedOptionValue("--workflow-run-id", ""));
		SourceProvenance.Request request = new SourceProvenance.Request(
			result.matchedOptionValue("--upstream-checkout", null), result.matchedOptionValue("--patch-repo", null),
			result.matchedOptionValue("--output", null), metadata);
		SourceProvenance.generate(request, Path.of(System.getProperty("java.io.tmpdir")), Map.of());
		return 0;
	}
}
