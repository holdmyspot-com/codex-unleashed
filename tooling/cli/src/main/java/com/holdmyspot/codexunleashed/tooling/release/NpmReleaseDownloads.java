package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.github.GitHubApi;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Downloads the six complete package archives required by both npm release families.
 */
public final class NpmReleaseDownloads
{
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

	/** Prevents construction. */
	private NpmReleaseDownloads()
	{
	}

	/**
	 * Looks up a release and downloads target archives in maintained order with owned partial-file cleanup.
	 * Existing completed archives remain caller-owned if a later target fails.
	 *
	 * @param repository the exact owner/repository text
	 * @param tag the exact release tag
	 * @param destination the archive output directory
	 * @param api the caller-owned GitHub transport
	 * @param progress the caller-owned progress stream
	 * @throws NullPointerException if an argument is null
	 * @throws IOException if lookup, metadata, a required asset, download, or cleanup fails
	 */
	public static void download(String repository, String tag, Path destination, GitHubApi api, PrintStream progress)
		throws IOException
	{
		Objects.requireNonNull(repository, "repository");
		Objects.requireNonNull(tag, "tag");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(progress, "progress");
		GitHubApi.Response response = api.request(GitHubApi.Method.GET,
			"repos/" + repository + "/releases/tags/" + tag, Map.of());
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException("GitHub release download failed: HTTP " + response.statusCode());
		Map<String, String> assets = assets(response.body());
		for (NpmPlatform platform : NpmPlatform.values())
		{
			String name = "codex-package-" + platform.target() + ".tar.gz";
			String url = assets.get(name);
			if (url == null || url.isEmpty())
				throw new IOException("Release " + repository + "@" + tag + " has no " + name);
			URI source;
			try
			{
				source = URI.create(url);
			}
			catch (IllegalArgumentException failure)
			{
				throw new IOException("Invalid release download URL for " + name, failure);
			}
			progress.println("Downloading " + name);
			ArtifactDownloads.download(source, destination.resolve(name), DOWNLOAD_TIMEOUT);
		}
	}

	/**
	 * Reads release asset names and URLs, retaining the last entry for duplicate names.
	 *
	 * @param body the release response JSON
	 * @return the asset-name mapping
	 * @throws IOException if the metadata is malformed or has missing asset fields
	 */
	private static Map<String, String> assets(String body) throws IOException
	{
		JsonNode release;
		try
		{
			release = JsonMapper.builder().build().readTree(body);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid GitHub release JSON", failure);
		}
		JsonNode entries = release.get("assets");
		if (entries == null || !entries.isArray())
			throw new IOException("GitHub release has no assets array");
		Map<String, String> result = new HashMap<>();
		for (JsonNode entry : entries)
		{
			JsonNode name = entry.get("name");
			JsonNode url = entry.get("browser_download_url");
			if (name == null || !name.isString() || url == null || !url.isString())
				throw new IOException("GitHub release asset requires a name and browser_download_url");
			result.put(name.stringValue(), url.stringValue());
		}
		return result;
	}
}
