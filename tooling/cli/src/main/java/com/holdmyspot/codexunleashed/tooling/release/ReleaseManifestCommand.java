package com.holdmyspot.codexunleashed.tooling.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Exposes release manifest generation through the retained long-option interface.
 */
public final class ReleaseManifestCommand
{
	private static final Map<String, String> DEFAULTS = Map.ofEntries(
		Map.entry("--patch-repository", "holdmyspot-com/codex-unleashed"),
		Map.entry("--upstream-repository", "openai/codex"), Map.entry("--upstream-tag", ""),
		Map.entry("--patched-tag", ""), Map.entry("--build-number", ""), Map.entry("--build-date", ""),
		Map.entry("--builder-type", "github-actions"), Map.entry("--workflow-path", ""), Map.entry("--workflow-ref", ""),
		Map.entry("--workflow-sha", ""), Map.entry("--workflow-run-id", ""), Map.entry("--workflow-run-attempt", ""),
		Map.entry("--supported-targets", ""), Map.entry("--consolidated-checksums", "codex-package_SHA256SUMS"),
		Map.entry("--attestation-bundle", "release-provenance.intoto.jsonl"));

	/**
	 * Prevents construction.
	 */
	private ReleaseManifestCommand()
	{
	}

	/**
	 * Parses release metadata and writes the manifest after artifact collection succeeds.
	 *
	 * @param args the command options
	 * @param out the help destination
	 * @param err the diagnostic destination
	 * @return zero on success or help, or two on invalid arguments
	 * @throws IOException if artifact collection or manifest writing fails
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name("generate-release-manifest");
		for (String option : new String[]{"--release-dir", "--patch-repo", "--output"})
			specification.addOption(OptionSpec.builder(option).type(Path.class).required(true).build());
		specification.addOption(OptionSpec.builder("--upstream-commit").type(String.class).required(true).build());
		for (String option : DEFAULTS.keySet().stream().sorted().toList())
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
		ReleaseManifest.Release release = new ReleaseManifest.Release(value(result, "--patch-repository"),
			value(result, "--patched-tag"), value(result, "--build-number"), value(result, "--build-date"),
			value(result, "--builder-type"), value(result, "--supported-targets"));
		ReleaseManifest.Upstream upstream = new ReleaseManifest.Upstream(value(result, "--upstream-repository"),
			value(result, "--upstream-tag"), result.matchedOptionValue("--upstream-commit", null));
		ReleaseManifest.Workflow workflow = new ReleaseManifest.Workflow(value(result, "--workflow-path"),
			value(result, "--workflow-ref"), value(result, "--workflow-sha"), value(result, "--workflow-run-id"),
			value(result, "--workflow-run-attempt"));
		ReleaseManifest.Verification verification = new ReleaseManifest.Verification(
			value(result, "--consolidated-checksums"), value(result, "--attestation-bundle"));
		ReleaseManifest.Request request = new ReleaseManifest.Request(result.matchedOptionValue("--release-dir", null),
			result.matchedOptionValue("--patch-repo", null), result.matchedOptionValue("--output", null), release,
			upstream, workflow, verification);
		ReleaseManifest.generate(request, Clock.systemUTC());
		return 0;
	}

	/**
	 * Reads an optional argument while preserving explicit empty values.
	 *
	 * @param result the parsed options
	 * @param option the declared optional argument
	 * @return the supplied value or its retained default
	 */
	private static String value(CommandLine.ParseResult result, String option)
	{
		return result.matchedOptionValue(option, DEFAULTS.get(option));
	}
}
