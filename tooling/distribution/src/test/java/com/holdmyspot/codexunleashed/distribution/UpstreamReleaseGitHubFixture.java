package com.holdmyspot.codexunleashed.distribution;

import java.util.Arrays;
import java.util.List;

/**
 * Supplies release API responses and rejects commands outside the detector's read-only boundary.
 */
public final class UpstreamReleaseGitHubFixture
{
	/**
	 * Prevents construction.
	 */
	private UpstreamReleaseGitHubFixture()
	{
	}

	/**
	 * Supplies the stable release, a vendor inventory, or a failed inventory request.
	 *
	 * @param args the GitHub CLI arguments
	 */
	public static void main(String[] args)
	{
		List<String> arguments = Arrays.asList(args);
		if (arguments.equals(List.of("api", "repos/openai/codex/releases/latest")))
			System.out.println("{\"tag_name\":\"rust-v0.160.1\",\"draft\":false,\"prerelease\":false}");
		else if (arguments.equals(List.of("api", "--paginate", "--slurp",
			"repos/holdmyspot-com/codex-unleashed/releases?per_page=100")))
		{
			String mode = System.getenv("RELEASE_FIXTURE_MODE");
			switch (mode)
			{
				case "needed" -> System.out.println("[[]]");
				case "current" -> System.out.println("[[],[{\"tag_name\":\"rust-v0.160.1+27\"}]]");
				case "failure" ->
				{
					System.err.println("GitHub rate limit");
					System.exit(42);
				}
				default -> throw new IllegalArgumentException("Invalid release fixture mode: " + mode);
			}
		}
		else
			throw new IllegalArgumentException("Unexpected GitHub command: " + arguments);
	}
}
