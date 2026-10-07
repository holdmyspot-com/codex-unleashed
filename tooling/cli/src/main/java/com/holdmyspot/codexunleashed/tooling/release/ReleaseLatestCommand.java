package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.GitHubRepositories;
import com.holdmyspot.codexunleashed.tooling.github.GitHubApi;
import com.holdmyspot.codexunleashed.tooling.github.GitHubHttpClient;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks whether a stable vendor release can become GitHub's latest release without moving the pointer backwards.
 */
public final class ReleaseLatestCommand
{
	/**
	 * Prevents construction.
	 */
	private ReleaseLatestCommand()
	{
	}

	/**
	 * Prints the latest-pointer decision; the caller holds the publication lock through its subsequent release write.
	 *
	 * @param args repository, candidate tag, and optional API endpoint arguments
	 * @param out the boolean decision or help destination
	 * @param err parser diagnostics
	 * @return zero on success or help, or two for invalid command-line options
	 * @throws IOException if lookup fails or latest metadata is not a stable release
	 * @throws IllegalArgumentException if the repository or candidate tag is invalid
	 */
	public static int run(String[] args, PrintStream out, PrintStream err) throws IOException
	{
		CommandSpec spec = CommandSpec.create().name("release-is-latest");
		spec.addOption(OptionSpec.builder("--repository").type(String.class).required(true).build());
		spec.addOption(OptionSpec.builder("--tag").type(String.class).required(true).build());
		spec.addOption(OptionSpec.builder("--api-base").type(URI.class).defaultValue("https://api.github.com/").build());
		spec.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build());
		CommandLine parser = new CommandLine(spec).setExpandAtFiles(false);
		CommandLine.ParseResult options;
		try
		{
			options = parser.parseArgs(args);
		}
		catch (CommandLine.ParameterException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 2;
		}
		if (options.isUsageHelpRequested())
		{
			parser.usage(out);
			return 0;
		}
		String repository = options.matchedOptionValue("--repository", "");
		GitHubRepositories.validate(repository);
		String candidate = stableVersion(options.matchedOptionValue("--tag", ""));
		URI endpoint = options.matchedOptionValue("--api-base", URI.create("https://api.github.com/"));
		String token = System.getenv("GH_TOKEN");
		GitHubHttpClient transport = GitHubHttpClient.anonymous(endpoint);
		if (token != null && !token.isBlank())
		{
			transport.close();
			transport = new GitHubHttpClient(endpoint, token);
		}
		try (GitHubHttpClient api = transport)
		{
			GitHubApi.Response response = api.request(GitHubApi.Method.GET,
				"repos/" + repository + "/releases/latest", Map.of());
			if (response.statusCode() == 404)
			{
				GitHubApi.Response releases = api.request(GitHubApi.Method.GET, "repos/" + repository + "/releases", Map.of());
				if (releases.statusCode() != 200)
					throw new IOException("Cannot confirm an absent GitHub latest release: HTTP " + releases.statusCode());
				JsonNode inventory = JsonMapper.builder().build().readTree(releases.body());
				if (inventory == null || !inventory.isArray() || !inventory.isEmpty())
					throw new IOException("GitHub latest release is absent but the release inventory is not empty; " +
						"refusing to infer latest");
				out.println(true);
				return 0;
			}
			if (response.statusCode() != 200)
				throw new IOException("Cannot read GitHub latest release: HTTP " + response.statusCode());
			JsonNode latest = JsonMapper.builder().build().readTree(response.body());
			if (latest == null || !latest.isObject() || !latest.path("draft").isBoolean() ||
				latest.path("draft").booleanValue() || !latest.path("prerelease").isBoolean() ||
				latest.path("prerelease").booleanValue() || !latest.path("tag_name").isString())
				throw new IOException("GitHub latest metadata must identify a non-draft stable release");
			String current = stableVersion(latest.path("tag_name").stringValue());
			out.println(ReleaseOrder.compareVersions(candidate, current) >= 0);
			return 0;
		}
		catch (JacksonException failure)
		{
			throw new IOException("GitHub latest metadata is not valid JSON", failure);
		}
	}

	/**
	 * Validates a stable downstream release tag before comparing its version.
	 *
	 * @param tag the rust-v prefixed stable tag with an optional numeric vendor build
	 * @return the version without its tag prefix
	 * @throws IllegalArgumentException if the tag is unsupported
	 */
	private static String stableVersion(String tag)
	{
		if (!tag.startsWith("rust-v"))
			throw new IllegalArgumentException("Expected a stable rust-v release tag: " + tag);
		String version = NpmVersions.removeTagPrefix(tag);
		NpmVersions.toNpmVersion(version);
		return version;
	}
}
