package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.GitHubRepositories;
import com.holdmyspot.codexunleashed.tooling.github.GitHubApi;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reserves an immutable release tag for its exact original build commit.
 */
public final class ReleaseTagReservation
{
	private static final Pattern VENDOR_TAG = Pattern.compile("rust-v\\d+\\.\\d+\\.\\d+\\+\\d+",
		Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern SOURCE_SHA = Pattern.compile("[0-9a-f]{40}");
	private static final String HEAD_REF_PREFIX = "refs/heads/";

	/**
	 * Prevents construction.
	 */
	private ReleaseTagReservation()
	{
	}

	/**
	 * Identifies the original artifact build's source and attempt.
	 *
	 * @param sourceSha the original build commit
	 * @param sourceRef the original branch reference
	 * @param sourceRunAttempt the original run attempt
	 */
	public record Source(String sourceSha, String sourceRef, BigInteger sourceRunAttempt)
	{
		/**
		 * Creates complete build provenance.
		 *
		 * @param sourceSha the original build commit
		 * @param sourceRef the original branch reference
		 * @param sourceRunAttempt the original run attempt
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if the SHA, branch reference, or run attempt is invalid
		 */
		public Source
		{
			validateSourceSha(sourceSha);
			Objects.requireNonNull(sourceRef, "sourceRef");
			Objects.requireNonNull(sourceRunAttempt, "sourceRunAttempt");
			if (!sourceRef.startsWith(HEAD_REF_PREFIX) || sourceRef.length() == HEAD_REF_PREFIX.length())
				throw new IllegalArgumentException("source reference must identify a branch");
			if (sourceRunAttempt.signum() <= 0)
				throw new IllegalArgumentException("source run attempt must be positive");
		}
	}

	/**
	 * Resolves provenance only from this repository's release build workflow.
	 *
	 * @param buildRun the GitHub Actions run metadata
	 * @param repository the exact downstream repository spelling
	 * @return the original build source
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the repository identifier is invalid
	 * @throws IOException if metadata is malformed or belongs to another repository or workflow
	 */
	public static Source resolveBuildSource(JsonNode buildRun, String repository) throws IOException
	{
		Objects.requireNonNull(buildRun, "buildRun");
		GitHubRepositories.validate(repository);
		if (!buildRun.isObject())
			throw new IOException("GitHub build run must be an object");
		if (!repository.equals(stringField(buildRun.get("repository"), "full_name")) ||
			!".github/workflows/build-release.yml".equals(stringField(buildRun, "path")))
			throw new IOException("artifacts must come from this repository's release build workflow");
		JsonNode attempt = buildRun.get("run_attempt");
		if (attempt == null || !attempt.isIntegralNumber())
			throw new IOException("GitHub run_attempt must be an integer");
		try
		{
			return new Source(stringField(buildRun, "head_sha"), HEAD_REF_PREFIX + stringField(buildRun, "head_branch"),
				attempt.bigIntegerValue());
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Invalid GitHub build source: " + failure.getMessage(), failure);
		}
	}

	/**
	 * Creates a missing tag and verifies existing or concurrently created references without replacing them.
	 *
	 * @param repository the downstream repository
	 * @param tag the raw vendor release tag
	 * @param sha the exact build commit
	 * @param api the GitHub request boundary
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if repository, tag, or SHA spelling is invalid
	 * @throws IOException if GitHub fails, metadata is malformed, or the tag identifies another commit
	 */
	public static void ensure(String repository, String tag, String sha, GitHubApi api) throws IOException
	{
		GitHubRepositories.validate(repository);
		Objects.requireNonNull(tag, "tag");
		validateSourceSha(sha);
		Objects.requireNonNull(api, "api");
		if (!VENDOR_TAG.matcher(tag).matches())
			throw new IllegalArgumentException("tag must be rust-vX.Y.Z+N");
		String path = "repos/" + repository + "/git/ref/tags/" + tag;
		GitHubApi.Response response = api.request(GitHubApi.Method.GET, path, Map.of());
		if (response.statusCode() == 404)
		{
			response = api.request(GitHubApi.Method.POST, "repos/" + repository + "/git/refs",
				Map.of("ref", "refs/tags/" + tag, "sha", sha));
			if (response.statusCode() == 422)
				response = api.request(GitHubApi.Method.GET, path, Map.of());
		}
		JsonNode reference = readResponse(response);
		if (!reference.isObject())
			throw new IOException("GitHub reference must be an object");
		JsonNode object = reference.get("object");
		if (!"commit".equals(stringField(object, "type")) || !sha.equals(stringField(object, "sha")))
			throw new IOException("release tag " + tag + " identifies a different commit; refusing replacement");
	}

	/**
	 * Parses successful GitHub JSON while retaining HTTP failure status and diagnostics.
	 *
	 * @param response the completed response
	 * @return the successful response document
	 * @throws NullPointerException if {@code response} is null
	 * @throws IOException if the status fails or JSON is malformed
	 */
	public static JsonNode readResponse(GitHubApi.Response response) throws IOException
	{
		Objects.requireNonNull(response, "response");
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException("GitHub HTTP " + response.statusCode() + ": " + response.body());
		try
		{
			JsonNode document = JsonMapper.builder().build().readTree(response.body());
			if (document == null || document.isMissingNode() || document.isNull())
				throw new IOException("GitHub response must contain JSON");
			return document;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse GitHub response: " + failure.getMessage(), failure);
		}
	}

	/**
	 * Requires an unchanged string field in a GitHub object.
	 *
	 * @param object the response object
	 * @param field the required field
	 * @return the string value
	 * @throws IOException if the object or field is malformed
	 */
	private static String stringField(JsonNode object, String field) throws IOException
	{
		if (object == null || !object.isObject())
			throw new IOException("GitHub metadata must be an object");
		JsonNode value = object.get(field);
		if (value == null || !value.isString())
			throw new IOException("GitHub " + field + " must be a string");
		return value.stringValue();
	}

	/**
	 * Validates the exact lowercase commit SHA spelling.
	 *
	 * @param sha the source commit
	 * @throws NullPointerException if {@code sha} is null
	 * @throws IllegalArgumentException if the SHA has an invalid form
	 */
	private static void validateSourceSha(String sha)
	{
		Objects.requireNonNull(sha, "sha");
		if (!SOURCE_SHA.matcher(sha).matches())
			throw new IllegalArgumentException("source SHA must contain 40 lowercase hexadecimal characters");
	}
}
