package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.GitHubRepositories;
import com.holdmyspot.codexunleashed.tooling.github.GitHubApi;
import com.holdmyspot.codexunleashed.tooling.github.GitHubHttpClient;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Reserves the original artifact run's release source tag before printing publication provenance.
 */
public final class ReleaseTagCommand
{
	private static final URI DEFAULT_API_BASE = URI.create("https://api.github.com/");

	/**
	 * Prevents construction.
	 */
	private ReleaseTagCommand()
	{
	}

	/**
	 * Runs release reservation with the environment's {@code GH_TOKEN} credential.
	 *
	 * @param args the command options
	 * @param out the provenance and help destination
	 * @param err the usage diagnostic destination
	 * @return zero on success or help, or two on invalid command options
	 * @throws NullPointerException if {@code args}, {@code out}, or {@code err} are null
	 * @throws IllegalArgumentException if the repository or endpoint is invalid
	 * @throws IOException if credentials, source metadata, reservation, or HTTP processing fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		return run(args, out, err, System.getenv("GH_TOKEN"));
	}

	/**
	 * Runs reservation with an explicit credential and an owned HTTP client.
	 *
	 * @param args the command options
	 * @param out the provenance and help destination
	 * @param err the usage diagnostic destination
	 * @param token the bearer credential; may be null for help or invalid-option parsing
	 * @return zero on success or help, or two on invalid command options
	 * @throws NullPointerException if {@code args}, {@code out}, or {@code err} are null
	 * @throws IllegalArgumentException if the repository or endpoint is invalid
	 * @throws IOException if credentials, source metadata, reservation, or HTTP processing fails
	 */
	public static int run(String[] args, PrintStream out, PrintStream err, String token) throws IOException
	{
		Objects.requireNonNull(args, "args");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		CommandSpec specification = CommandSpec.create().name("ensure-release-tag");
		for (String option : new String[]{"--repository", "--tag"})
			specification.addOption(OptionSpec.builder(option).type(String.class).required(true).build());
		specification.addOption(OptionSpec.builder("--artifact-run-id").type(BigInteger.class).required(true).build());
		specification.addOption(OptionSpec.builder("--api-base").type(URI.class).
			defaultValue(DEFAULT_API_BASE.toString()).build());
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
		if (token == null || token.isBlank())
			throw new IOException("GH_TOKEN must contain the GitHub bearer credential");
		String repository = result.matchedOptionValue("--repository", "");
		GitHubRepositories.validate(repository);
		String tag = result.matchedOptionValue("--tag", "");
		BigInteger runId = result.matchedOptionValue("--artifact-run-id", BigInteger.ZERO);
		try (GitHubHttpClient api = new GitHubHttpClient(result.matchedOptionValue("--api-base", DEFAULT_API_BASE), token))
		{
			ReleaseTagReservation.Source source = ReleaseTagReservation.resolveBuildSource(
				ReleaseTagReservation.readResponse(api.request(GitHubApi.Method.GET,
					"repos/" + repository + "/actions/runs/" + runId, Map.of())), repository);
			ReleaseTagReservation.ensure(repository, tag, source.sourceSha(), api);
			out.println("source_sha=" + source.sourceSha());
			out.println("source_ref=" + source.sourceRef());
			out.println("source_run_attempt=" + source.sourceRunAttempt());
		}
		return 0;
	}
}
