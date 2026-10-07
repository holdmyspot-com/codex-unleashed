package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies upstream release detection without interpreting failed API requests as missing releases.
 */
public final class UpstreamReleaseDetectorTest
{
	/**
	 * Creates the detector tests.
	 */
	public UpstreamReleaseDetectorTest()
	{
	}

	/**
	 * Requires an exact vendor tag on any release page, preserving Unicode decimal digit spelling.
	 *
	 * @throws IOException if a valid fixture cannot be parsed
	 */
	@Test
	public void detectsExactVendorBuilds() throws IOException
	{
		for (String tag : new String[]{"rust-v0.160.1", "rust-v٠.١٦٠.١"})
		{
			for (String suffix : new String[]{"+0", "+27", "+٢٧"})
			{
				List<List<String>> commands = new ArrayList<>();
				Deque<String> responses = new ArrayDeque<>(List.of(latest(tag),
					"[[{\"tag_name\":\"rust-v0.159.2+99\"}],[{\"tag_name\":\"" + tag + suffix + "\"}]]"));
				CommandRunner runner = command ->
				{
					commands.add(command);
					return responses.removeFirst();
				};
				UpstreamReleaseDetector.Detection result = UpstreamReleaseDetector.detect(
					"holdmyspot-com/codex-unleashed", runner);
				assertEquals(result.upstreamTag(), tag);
				assertFalse(result.needed());
				assertEquals(commands, List.of(List.of("gh", "api", "repos/openai/codex/releases/latest"),
					List.of("gh", "api", "--paginate", "--slurp",
						"repos/holdmyspot-com/codex-unleashed/releases?per_page=100")));
			}
			for (String suffix : new String[]{"", "+27-beta", "+27 ", " +27"})
			{
				Deque<String> responses = new ArrayDeque<>(List.of(latest(tag),
					"[[{\"tag_name\":\"" + tag + suffix + "\"}]]"));
				assertTrue(UpstreamReleaseDetector.detect("holdmyspot-com/codex-unleashed",
					_ -> responses.removeFirst()).needed());
			}
		}
	}

	/**
	 * Rejects malformed or unstable upstream responses before requesting downstream releases.
	 */
	@Test
	public void rejectsInvalidLatestResponses()
	{
		for (String response : new String[]{"{}", "null", latest("rust-v0.160.1+2"),
			latest("rust-v0.160.1").replace("\"draft\":false", "\"draft\":true"),
			latest("rust-v0.160.1").replace("\"prerelease\":false", "\"prerelease\":null"),
			latest("rust-v0.160.1").replace("\"draft\":false", "\"draft\":\"false\"")})
		{
			List<List<String>> commands = new ArrayList<>();
			expectThrows(IOException.class, () -> UpstreamReleaseDetector.detect("holdmyspot-com/codex-unleashed", command ->
			{
				commands.add(command);
				return response;
			}));
			assertEquals(commands.size(), 1);
		}
	}

	/**
	 * Preserves API failures and rejects damaged downstream pages instead of requesting a release build.
	 */
	@Test
	public void rejectsApiAndInventoryFailures()
	{
		IOException apiFailure = new IOException("GitHub rate limit");
		assertEquals(expectThrows(IOException.class, () -> UpstreamReleaseDetector.detect(
			"holdmyspot-com/codex-unleashed", _ ->
			{
				throw apiFailure;
			})), apiFailure);
		for (String response : new String[]{"{}", "[{}]", "[[{}]]", "[[{\"tag_name\":null}]]", "["})
		{
			Deque<String> responses = new ArrayDeque<>(List.of(latest("rust-v0.160.1"), response));
			expectThrows(IOException.class, () -> UpstreamReleaseDetector.detect("holdmyspot-com/codex-unleashed",
				_ -> responses.removeFirst()));
		}
	}

	/**
	 * Constructs a stable latest-release fixture.
	 *
	 * @param tag the raw upstream tag
	 * @return the serialized latest-release object
	 */
	private static String latest(String tag)
	{
		return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false}";
	}
}
