package com.holdmyspot.codexunleashed.distribution;

import java.util.List;

/**
 * Models GitHub tags, releases, and paginated failure markers for release preparation.
 */
public final class GitHubCommandFixture
{
	/**
	 * Prevents construction.
	 */
	private GitHubCommandFixture()
	{
	}

	/**
	 * Emits a controlled inventory or rejects an unexpected GitHub request.
	 *
	 * @param args the GitHub CLI arguments
	 */
	public static void main(String[] args)
	{
		List<String> arguments = List.of(args);
		if (arguments.contains("--slurp") && (arguments.contains("-q") || arguments.contains("--jq")))
			throw new IllegalArgumentException("The --slurp option is not supported with --jq");

		switch (args[0])
		{
			case "release" ->
			{
				if (!"view".equals(args[1]))
					throw new IllegalArgumentException("Unexpected release operation: " + args[1]);
				if ("true".equals(System.getenv("INPUT_AUTOMATIC")))
					System.exit(1);
			}
			case "api" ->
			{
				String path = arguments.stream().filter(argument -> argument.startsWith("repos/")).
					findFirst().orElseThrow();
				if (path.endsWith("/tags"))
					System.out.println("rust-v0.160.0+29\nrust-v0.159.1+23");
				else if (path.contains("/releases/tags/"))
					System.out.println("false");
				else if (path.endsWith("/actions/artifacts"))
				{
					String marker = System.getenv("failure_marker_name");
					System.out.println("""
						[{"artifacts":[{"id":1,"expired":true,"name":"%s"}]},
						 {"artifacts":[{"id":2,"expired":false,"name":"unrelated"},
						 {"id":3,"expired":false,"name":"%s"}]}]
						""".formatted(marker, marker));
				}
				else if (!path.endsWith("/actions/runs"))
					throw new IllegalArgumentException("Unexpected GitHub endpoint: " + path);
			}
			default -> throw new IllegalArgumentException("Unexpected GitHub command: " + args[0]);
		}
	}
}
