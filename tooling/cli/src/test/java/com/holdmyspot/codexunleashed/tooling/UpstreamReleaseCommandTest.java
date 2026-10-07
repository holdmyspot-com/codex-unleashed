package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/**
 * Verifies the release detector's workflow outputs and failure boundary.
 */
public final class UpstreamReleaseCommandTest
{
	/**
	 * Creates the command tests.
	 */
	public UpstreamReleaseCommandTest()
	{
	}

	/**
	 * Appends the exact workflow outputs and summary for both release decisions.
	 *
	 * @throws IOException if a fixture cannot be created or removed
	 */
	@Test
	public void appendsWorkflowReports() throws IOException
	{
		Path directory = Files.createTempDirectory("upstream-command-");
		Path output = directory.resolve("output");
		Path summary = directory.resolve("summary");
		try
		{
			for (boolean needed : new boolean[]{true, false})
			{
				Files.writeString(output, "existing output\n");
				Files.writeString(summary, "existing summary\n");
				String inventory = "[[]]";
				String decision = "True";
				if (!needed)
				{
					inventory = "[[{\"tag_name\":\"rust-v0.160.1+27\"}]]";
					decision = "False";
				}
				Deque<String> responses = new ArrayDeque<>(List.of(
					"{\"tag_name\":\"rust-v0.160.1\",\"draft\":false,\"prerelease\":false}",
					inventory));
				assertEquals(run(output, summary, _ -> responses.removeFirst()), 0);
				assertEquals(Files.readString(output), "existing output\nupstream_tag=rust-v0.160.1\nneeded=" +
					needed + "\n");
				assertEquals(Files.readString(summary), "existing summary\n## Upstream release\n\n" +
					"- Upstream: `rust-v0.160.1`\n- Public build needed: **" + decision +
					"**\n- No compilation performed. Use `build-release.yml` to publish; " +
					"automatic dispatch requires `AUTO_RELEASE=true`.\n");
			}
		}
		finally
		{
			Files.deleteIfExists(output);
			Files.deleteIfExists(summary);
			Files.delete(directory);
		}
	}

	/**
	 * Keeps existing reports unchanged when GitHub cannot supply the decision inputs.
	 *
	 * @throws IOException if a fixture cannot be created or removed
	 */
	@Test
	public void leavesReportsUnchangedOnApiFailure() throws IOException
	{
		Path directory = Files.createTempDirectory("upstream-failure-");
		Path output = directory.resolve("output");
		Path summary = directory.resolve("summary");
		try
		{
			Files.writeString(output, "existing output\n");
			Files.writeString(summary, "existing summary\n");
			assertEquals(run(output, summary, _ ->
			{
				throw new IOException("GitHub rate limit");
			}), 1);
			assertEquals(Files.readString(output), "existing output\n");
			assertEquals(Files.readString(summary), "existing summary\n");
		}
		finally
		{
			Files.deleteIfExists(output);
			Files.deleteIfExists(summary);
			Files.delete(directory);
		}
	}

	/**
	 * Runs the maintained command with isolated output streams.
	 *
	 * @param output the GitHub output destination
	 * @param summary the GitHub summary destination
	 * @param runner the API command boundary
	 * @return the command exit status
	 */
	private static int run(Path output, Path summary, CommandRunner runner)
	{
		try (PrintStream sink = new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8))
		{
			return Main.run(new String[]{"detect-upstream-release", "holdmyspot-com/codex-unleashed",
				output.toString(), summary.toString()}, new ByteArrayInputStream(new byte[0]), sink, sink, runner);
		}
	}
}
