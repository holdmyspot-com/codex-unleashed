package com.holdmyspot.codexunleashed.distribution;

/**
 * Models only GitHub tag and release lookups used by the build-number workflow test.
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
		switch (args[0])
		{
			case "release" ->
			{
				if (!"view".equals(args[1]))
					throw new IllegalArgumentException("Unexpected release operation: " + args[1]);
			}
			case "api" ->
			{
				String path = args[1];
				if (path.endsWith("/tags"))
					System.out.println("rust-v0.160.0+29\nrust-v0.159.1+23");
				else if (path.contains("/releases/tags/"))
					System.out.println("false");
				else if (!path.endsWith("/actions/runs"))
					throw new IllegalArgumentException("Unexpected GitHub endpoint: " + path);
			}
			default -> throw new IllegalArgumentException("Unexpected GitHub command: " + args[0]);
		}
	}
}
