package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.CommandRunner;
import com.holdmyspot.codexunleashed.tooling.GitHubRepositories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Detects missing vendor builds of stable upstream releases without treating failed API requests as absence.
 */
public final class UpstreamReleaseDetector
{
	/**
	 * Prevents construction.
	 */
	private UpstreamReleaseDetector()
	{
	}

	/**
	 * Describes whether the downstream repository needs a build of the stable upstream release.
	 *
	 * @param upstreamTag the raw stable upstream release tag
	 * @param needed indicates whether a matching vendor build is absent
	 */
	public record Detection(String upstreamTag, boolean needed)
	{
		/**
		 * Creates a decision for a valid stable upstream tag.
		 *
		 * @param upstreamTag the raw stable upstream release tag
		 * @param needed indicates whether a matching vendor build is absent
		 * @throws NullPointerException if {@code upstreamTag} is null
		 * @throws IllegalArgumentException if the tag has an invalid form
		 */
		public Detection
		{
			ReleaseTags.validateStableUpstreamTag(upstreamTag);
		}
	}

	/**
	 * Reads the latest upstream release and the downstream repository's complete paginated release inventory.
	 *
	 * @param repository the downstream repository in owner/name form
	 * @param runner the GitHub command boundary
	 * @return the stable tag and whether a matching vendor build is absent
	 * @throws NullPointerException if {@code repository} or {@code runner} are null
	 * @throws IllegalArgumentException if the repository identifier is invalid
	 * @throws IOException if an API request fails or its response is malformed or unstable
	 */
	public static Detection detect(String repository, CommandRunner runner) throws IOException
	{
		Objects.requireNonNull(runner, "runner");
		GitHubRepositories.validate(repository);
		JsonNode latest = parse(runner.run(List.of("gh", "api", "repos/openai/codex/releases/latest")), "latest release");
		String tag = stringField(latest, "tag_name");
		boolean draft = booleanField(latest, "draft");
		boolean prerelease = booleanField(latest, "prerelease");
		if (draft || prerelease)
			throw new IOException("Not a stable upstream release: " + tag);
		try
		{
			ReleaseTags.validateStableUpstreamTag(tag);
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Not a stable upstream release: " + tag, failure);
		}

		JsonNode pages = parse(runner.run(List.of("gh", "api", "--paginate", "--slurp",
			"repos/" + repository + "/releases?per_page=100")), "release inventory");
		if (!pages.isArray())
			throw new IOException("GitHub release inventory must be an array of pages");
		Pattern vendorTag = Pattern.compile(Pattern.quote(tag) + "\\+\\d+", Pattern.UNICODE_CHARACTER_CLASS);
		boolean found = false;
		for (JsonNode page : pages)
		{
			if (!page.isArray())
				throw new IOException("GitHub release inventory pages must be arrays");
			for (JsonNode release : page)
			{
				if (vendorTag.matcher(stringField(release, "tag_name")).matches())
					found = true;
			}
		}
		return new Detection(tag, !found);
	}

	/**
	 * Appends a completed decision to GitHub's output and step-summary files.
	 *
	 * @param detection the completed release decision
	 * @param output the workflow output destination
	 * @param summary the workflow summary destination
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if a report cannot be appended
	 */
	public static void appendReports(Detection detection, Path output, Path summary) throws IOException
	{
		Objects.requireNonNull(detection, "detection");
		Objects.requireNonNull(output, "output");
		Objects.requireNonNull(summary, "summary");
		String decision = "False";
		if (detection.needed())
			decision = "True";
		Files.writeString(output, "upstream_tag=" + detection.upstreamTag() + "\nneeded=" + detection.needed() + "\n",
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		Files.writeString(summary, "## Upstream release\n\n- Upstream: `" + detection.upstreamTag() +
			"`\n- Public build needed: **" + decision + "**\n" +
			"- No compilation performed. Use `build-release.yml` to publish; " +
			"automatic dispatch requires `AUTO_RELEASE=true`.\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/**
	 * Parses GitHub JSON without replacing failures with empty inventories.
	 *
	 * @param json the serialized response
	 * @param label the diagnostic response label
	 * @return the response tree
	 * @throws IOException if the response is malformed
	 */
	private static JsonNode parse(String json, String label) throws IOException
	{
		try
		{
			return JsonMapper.builder().build().readTree(json);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse GitHub " + label + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * Requires a string field in a release object.
	 *
	 * @param release the release object
	 * @param field the required field name
	 * @return the field's unchanged string value
	 * @throws IOException if the object or field is malformed
	 */
	private static String stringField(JsonNode release, String field) throws IOException
	{
		if (release == null || !release.isObject())
			throw new IOException("GitHub release must be an object");
		JsonNode value = release.get(field);
		if (value == null || !value.isString())
			throw new IOException("GitHub release " + field + " must be a string");
		return value.stringValue();
	}

	/**
	 * Requires an explicit boolean field rather than substituting an absent or textual value.
	 *
	 * @param release the release object
	 * @param field the required field name
	 * @return the field's boolean value
	 * @throws IOException if the field is absent or malformed
	 */
	private static boolean booleanField(JsonNode release, String field) throws IOException
	{
		JsonNode value = release.get(field);
		if (value == null || !value.isBoolean())
			throw new IOException("GitHub release " + field + " must be an explicit boolean");
		return value.booleanValue();
	}
}
