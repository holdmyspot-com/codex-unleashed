package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.github.GitHubApi;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Verifies immutable release tags and the original build's provenance across reservation retries.
 */
public final class ReleaseTagReservationTest
{
	private static final String SOURCE_SHA = "a".repeat(40);
	private static final String VENDOR_TAG = "rust-v0.159.1+23";

	/**
	 * Creates the reservation tests.
	 */
	public ReleaseTagReservationTest()
	{
	}

	/**
	 * Reserves only the exact build commit after a missing reference lookup.
	 *
	 * @throws IOException if the reservation fails
	 */
	@Test
	public void reservesExactBuildCommit() throws IOException
	{
		List<String> calls = new ArrayList<>();
		GitHubApi api = (method, path, payload) ->
		{
			calls.add(method + " " + path);
			if (method == GitHubApi.Method.GET)
				return new GitHubApi.Response(404, "{}");
			assertEquals(payload, Map.of("ref", "refs/tags/" + VENDOR_TAG, "sha", SOURCE_SHA));
			return reference(201, SOURCE_SHA);
		};
		ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA, api);
		assertEquals(calls, List.of("GET repos/owner/repo/git/ref/tags/" + VENDOR_TAG,
			"POST repos/owner/repo/git/refs"));
	}

	/**
	 * A lost creation response permits a safe retry but never replacement with another source commit.
	 *
	 * @throws IOException if the successful retry fails
	 */
	@Test
	public void retriesUnknownCreationResult() throws IOException
	{
		AtomicReference<String> stored = new AtomicReference<>();
		AtomicInteger creates = new AtomicInteger();
		IOException lostResponse = new IOException("response lost after GitHub creates the tag");
		GitHubApi api = (method, _, payload) ->
		{
			if (method == GitHubApi.Method.GET)
			{
				if (stored.get() == null)
					return new GitHubApi.Response(404, "{}");
				return reference(200, stored.get());
			}
			stored.set(payload.get("sha"));
			creates.incrementAndGet();
			throw lostResponse;
		};
		assertEquals(expectThrows(IOException.class, () ->
			ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA, api)), lostResponse);
		ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA, api);
		IOException conflict = expectThrows(IOException.class, () ->
			ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, "b".repeat(40), api));
		assertTrue(conflict.getMessage().contains("different commit"));
		assertEquals(stored.get(), SOURCE_SHA);
		assertEquals(creates.get(), 1);
	}

	/**
	 * Verifies concurrent creation and existing tags, while permission errors do not create a tag.
	 *
	 * @throws IOException if an accepted reservation fails
	 */
	@Test
	public void verifiesExistingAndConcurrentTags() throws IOException
	{
		AtomicInteger calls = new AtomicInteger();
		ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA, (method, _, _) ->
		{
			assertEquals(method, GitHubApi.Method.GET);
			calls.incrementAndGet();
			return reference(200, SOURCE_SHA);
		});
		assertEquals(calls.get(), 1);
		calls.set(0);
		ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA, (method, _, _) ->
		{
			int call = calls.incrementAndGet();
			return switch (call)
			{
				case 1 -> new GitHubApi.Response(404, "{}");
				case 2 ->
				{
					assertEquals(method, GitHubApi.Method.POST);
					yield new GitHubApi.Response(422, "{}");
				}
				case 3 -> reference(200, SOURCE_SHA);
				default -> throw new AssertionError("Unexpected API retry");
			};
		});
		assertEquals(calls.get(), 3);
		calls.set(0);
		expectThrows(IOException.class, () -> ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, SOURCE_SHA,
			(method, _, _) ->
			{
				assertEquals(method, GitHubApi.Method.GET);
				calls.incrementAndGet();
				return new GitHubApi.Response(403, "{\"message\":\"Forbidden\"}");
			}));
		assertEquals(calls.get(), 1);
	}

	/**
	 * Rejects invalid parameter spelling before calling GitHub.
	 */
	@Test
	public void rejectsInvalidInputs()
	{
		GitHubApi api = (_, _, _) ->
		{
			throw new AssertionError("Invalid input reached GitHub");
		};
		expectThrows(IllegalArgumentException.class, () ->
			ReleaseTagReservation.ensure("owner/repo", "../other", SOURCE_SHA, api));
		expectThrows(IllegalArgumentException.class, () ->
			ReleaseTagReservation.ensure("owner/repo", VENDOR_TAG, "A".repeat(40), api));
		expectThrows(IllegalArgumentException.class, () ->
			ReleaseTagReservation.ensure("owner/repo/other", VENDOR_TAG, SOURCE_SHA, api));
	}

	/**
	 * Preserves artifact-run provenance and rejects artifacts from another repository or workflow.
	 *
	 * @throws IOException if valid build metadata cannot be resolved
	 */
	@Test
	public void resolvesOriginalBuildSource() throws IOException
	{
		String run = "{\"repository\":{\"full_name\":\"owner/repo\"}," +
			"\"path\":\".github/workflows/build-release.yml\",\"head_sha\":\"" + SOURCE_SHA +
			"\",\"head_branch\":\"release/topic\",\"run_attempt\":1}";
		ReleaseTagReservation.Source source = ReleaseTagReservation.resolveBuildSource(
			JsonMapper.builder().build().readTree(run), "owner/repo");
		assertEquals(source, new ReleaseTagReservation.Source(SOURCE_SHA, "refs/heads/release/topic", BigInteger.ONE));
		expectThrows(IOException.class, () -> ReleaseTagReservation.resolveBuildSource(
			JsonMapper.builder().build().readTree(run.replace("build-release.yml", "other.yml")), "owner/repo"));
		expectThrows(IOException.class, () -> ReleaseTagReservation.resolveBuildSource(
			JsonMapper.builder().build().readTree(run), "Owner/repo"));
	}

	/**
	 * Creates a commit reference API response.
	 *
	 * @param status the HTTP response status
	 * @param sha the referenced commit
	 * @return the serialized reference response
	 */
	private static GitHubApi.Response reference(int status, String sha)
	{
		return new GitHubApi.Response(status, "{\"object\":{\"type\":\"commit\",\"sha\":\"" + sha + "\"}}");
	}
}
