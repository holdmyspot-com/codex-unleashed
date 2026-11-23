package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Lists the GitHub release assets that a DotSlash resource manifest downloads, with the size and SHA-256 digest that the
 * manifest declares for each.
 */
final class ReleaseResourceManifest
{
	/**
	 * Prevents construction.
	 */
	private ReleaseResourceManifest()
	{
	}

	/**
	 * Reads the first release provider of each platform.
	 *
	 * @param scope    supplies the JSON mapper
	 * @param manifest a DotSlash manifest: an optional {@code #!} line followed by a JSON document
	 * @return the release assets, in platform order
	 * @throws NullPointerException     if any argument is null
	 * @throws IOException              if the manifest cannot be read
	 * @throws IllegalArgumentException if the manifest is not JSON or a platform declares no release provider
	 */
	static List<Asset> assets(ToolingScope scope, Path manifest) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(manifest, "manifest");
		String content = Files.readString(manifest);
		if (content.startsWith("#!"))
		{
			int lineEnd = content.indexOf('\n');
			if (lineEnd < 0)
				lineEnd = content.length();
			content = content.substring(lineEnd);
		}
		JsonNode document;
		try
		{
			document = scope.getJsonMapper().readTree(content);
		}
		catch (RuntimeException failure)
		{
			throw new IllegalArgumentException("Malformed resource manifest " + manifest + ": " + failure.getMessage());
		}
		JsonNode platforms = document.get("platforms");
		if (platforms == null || !platforms.isObject() || platforms.isEmpty())
			throw new IllegalArgumentException("Resource manifest " + manifest + " declares no platforms");
		List<Asset> assets = new ArrayList<>();
		for (var platform : platforms.properties())
		{
			JsonNode entry = platform.getValue();
			String size = size(entry.get("size"));
			JsonNode digest = entry.get("digest");
			if (size == null || digest == null || !digest.isString())
				throw new IllegalArgumentException("Platform " + platform.getKey() + " of " + manifest +
					" lacks a size or digest");
			Asset asset = null;
			JsonNode providers = entry.get("providers");
			if (providers != null && providers.isArray())
				for (JsonNode provider : providers)
				{
					asset = asset(provider, size, digest.stringValue());
					if (asset != null)
						break;
				}
			if (asset == null)
				throw new IllegalArgumentException("Platform " + platform.getKey() + " of " + manifest +
					" has no GitHub release provider");
			assets.add(asset);
		}
		return List.copyOf(assets);
	}

	/**
	 * Converts a declared size to text without losing digits of a value that a floating-point number cannot hold.
	 *
	 * @param value the declared size
	 * @return the decimal size, or {@code null} if the value is absent or neither an integer nor a string
	 */
	private static String size(JsonNode value)
	{
		if (value == null)
			return null;
		if (value.isIntegralNumber())
			return value.bigIntegerValue().toString();
		if (value.isString())
			return value.stringValue();
		return null;
	}

	/**
	 * Reads one provider if it names a GitHub release asset.
	 *
	 * @param provider the manifest provider
	 * @param size     the declared size
	 * @param digest   the declared SHA-256 digest
	 * @return the asset, or {@code null} if the provider is not a GitHub release download
	 */
	private static Asset asset(JsonNode provider, String size, String digest)
	{
		JsonNode url = provider.get("url");
		if (url != null && url.isString())
		{
			URI uri;
			try
			{
				uri = new URI(url.stringValue());
			}
			catch (URISyntaxException _)
			{
				return null;
			}
			// The raw path keeps percent-encoding, so the asset name matches the spelling that GitHub reports.
			String path = uri.getRawPath();
			if (!"github.com".equals(uri.getHost()) || path == null)
				return null;
			String[] parts = path.split("/");
			// "", owner, repository, "releases", "download", tag, asset name
			if (parts.length != 7 || !parts[3].equals("releases") || !parts[4].equals("download"))
				return null;
			return new Asset(parts[1] + "/" + parts[2], parts[5], parts[6], size, digest);
		}
		JsonNode type = provider.get("type");
		JsonNode repository = provider.get("repo");
		JsonNode tag = provider.get("tag");
		JsonNode name = provider.get("name");
		if (type != null && type.isString() && "github-release".equals(type.stringValue()) && repository != null && repository.isString() &&
			tag != null && tag.isString() && name != null && name.isString())
		{
			return new Asset(repository.stringValue(), tag.stringValue(), name.stringValue(), size, digest);
		}
		return null;
	}

	/**
	 * A release asset that a manifest downloads.
	 *
	 * @param repository the {@code owner/name} of the repository that publishes the release
	 * @param tag        the release tag
	 * @param name       the asset file name
	 * @param size       the size in bytes that the manifest declares
	 * @param digest     the lowercase hexadecimal SHA-256 digest that the manifest declares
	 */
	record Asset(String repository, String tag, String name, String size, String digest)
	{
	}
}
