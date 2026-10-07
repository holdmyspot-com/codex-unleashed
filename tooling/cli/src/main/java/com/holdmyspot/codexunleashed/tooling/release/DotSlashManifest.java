package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.TextLines;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selects the first URL provider for a requested platform in a checked-in DotSlash manifest.
 */
public final class DotSlashManifest
{
	/**
	 * Prevents construction.
	 */
	private DotSlashManifest()
	{
	}

	/**
	 * Describes declared archive integrity and extraction inputs.
	 *
	 * @param size the declared compressed archive size
	 * @param digest the declared SHA-256 digest
	 * @param format the declared archive format
	 * @param member the requested archive member
	 * @param url the first provider URL
	 */
	public record Artifact(BigInteger size, String digest, String format, String member, URI url)
	{
		/**
		 * Validates required artifact fields.
		 *
		 * @param size the declared compressed archive size
		 * @param digest the declared SHA-256 digest
		 * @param format the declared archive format
		 * @param member the requested archive member
		 * @param url the first provider URL
		 * @throws NullPointerException if any argument is null
		 */
		public Artifact
		{
			Objects.requireNonNull(size, "size");
			Objects.requireNonNull(digest, "digest");
			Objects.requireNonNull(format, "format");
			Objects.requireNonNull(member, "member");
			Objects.requireNonNull(url, "url");
		}
	}

	/**
	 * Supplies the release asset identity and exact declared size/digest text for upstream inventory checks.
	 *
	 * @param name provider asset filename
	 * @param size declared byte size text
	 * @param digest declared digest text
	 */
	public record ReleaseAsset(String name, String size, String digest)
	{
		/**
		 * Requires every release comparison field.
		 *
		 * @param name asset filename
		 * @param size byte size text
		 * @param digest digest text
		 * @throws NullPointerException if any field is null
		 */
		public ReleaseAsset
		{
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(size, "size");
			Objects.requireNonNull(digest, "digest");
		}
	}

	/**
	 * Extracts every platform's first URL or GitHub release provider without sorting platform insertion order.
	 *
	 * @param manifest checked-in resource manifest
	 * @return immutable release comparison records
	 * @throws NullPointerException if the manifest is null
	 * @throws IOException if parsing or required provider metadata fails
	 */
	public static List<ReleaseAsset> releaseAssets(Path manifest) throws IOException
	{
		JsonNode platforms = readDocument(manifest, "resource").get("platforms");
		if (platforms == null || !platforms.isObject())
			throw new IOException("Resource manifest must contain a platforms object: " + manifest);
		var result = new ArrayList<ReleaseAsset>();
		for (JsonNode platform : platforms)
		{
			JsonNode providers = platform.get("providers");
			if (providers == null || !providers.isArray())
				throw new IOException("Resource manifest platform must contain providers: " + manifest);
			JsonNode selected = null;
			for (JsonNode provider : providers)
			{
				if (provider.isObject() && (provider.path("type").asString().equals("github-release") || provider.has("url")))
				{
					selected = provider;
					break;
				}
			}
			if (selected == null)
				throw new IOException("Resource manifest has no URL or GitHub release provider: " + manifest);
			result.add(new ReleaseAsset(releaseName(selected), scalarText(platform.get("size"), "size", "resource"),
				scalarText(platform.get("digest"), "digest", "resource")));
		}
		return List.copyOf(result);
	}

	/**
	 * Retains URL path basename spelling without percent decoding or reading query and fragment components.
	 *
	 * @param provider selected eligible provider
	 * @return raw URL basename or declared GitHub release filename
	 * @throws IOException if provider metadata or URL syntax is invalid
	 */
	private static String releaseName(JsonNode provider) throws IOException
	{
		if (!provider.has("url"))
			return scalarText(provider.get("name"), "name", "resource");
		try
		{
			URI url = URI.create(scalarText(provider.get("url"), "url", "resource"));
			String rawPath = url.getRawPath();
			if (rawPath == null)
				throw new IOException("Resource release provider URL must contain a path: " + url);
			Path filename = Path.of(rawPath).getFileName();
			if (filename == null)
				return "";
			return filename.toString();
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Invalid resource release provider URL", failure);
		}
	}

	/**
	 * Reads a UTF-8 manifest and selects the target's archive, allowing absence only when requested.
	 *
	 * @param manifest the explicit manifest path
	 * @param target the selected package target
	 * @param label the resource name used in failures
	 * @param missingOk whether an absent platform is optional
	 * @return the selected artifact, or permitted platform absence
	 * @throws IOException if parsing, platform selection, hash policy, or required metadata fails
	 * @throws NullPointerException if any reference argument is null
	 */
	public static Optional<Artifact> select(Path manifest, PackageTarget target, String label, boolean missingOk)
		throws IOException
	{
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(label, "label");
		JsonNode document = readDocument(manifest, label);
		JsonNode platforms = document.get("platforms");
		JsonNode platform = null;
		if (platforms != null)
		{
			if (!platforms.isObject())
				throw new IOException("Invalid " + label + " manifest platforms: " + manifest);
			platform = platforms.get(target.dotslashPlatform());
		}
		if (platform == null || platform.isNull())
		{
			if (missingOk)
				return Optional.empty();
			throw new IOException(label + " manifest " + manifest + " is missing platform '" +
				target.dotslashPlatform() + "'");
		}
		if (!platform.isObject())
			throw new IOException("Invalid " + label + " manifest platform: " + target.dotslashPlatform());
		JsonNode providers = platform.get("providers");
		if (providers == null || !providers.isArray() || providers.isEmpty())
			throw new IOException(label + " manifest " + manifest + " has no providers for '" +
				target.dotslashPlatform() + "'");
		String hash = scalarText(platform.get("hash"), "hash", label);
		if (!hash.equals("sha256"))
			throw new IOException("Unsupported " + label + " hash '" + hash + "'; expected sha256");
		JsonNode provider = providers.get(0);
		if (!provider.isObject())
			throw new IOException("Invalid first " + label + " provider");
		URI url;
		try
		{
			url = URI.create(scalarText(provider.get("url"), "url", label));
		}
		catch (IllegalArgumentException failure)
		{
			throw new IOException("Invalid first " + label + " provider URL", failure);
		}
		return Optional.of(new Artifact(integerSize(platform.get("size"), label), scalarText(platform.get("digest"),
			"digest", label), scalarText(platform.get("format"), "format", label), scalarText(platform.get("path"),
			"path", label), url));
	}

	/**
	 * Reads the shared UTF-8 DotSlash JSON document with optional interpreter header and strict trailing content.
	 *
	 * @param manifest resource manifest
	 * @param label diagnostic resource name
	 * @return root object
	 * @throws NullPointerException if the manifest is null
	 * @throws IOException if reading, JSON parsing or root validation fails
	 */
	private static JsonNode readDocument(Path manifest, String label) throws IOException
	{
		Objects.requireNonNull(manifest, "manifest");
		String text = Files.readString(manifest);
		if (text.startsWith("#!"))
		{
			List<String> lines = TextLines.split(text);
			text = String.join("\n", lines.subList(1, lines.size()));
		}
		JsonNode document;
		try
		{
			document = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readTree(text);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid " + label + " manifest: " + manifest, failure);
		}
		if (document == null || !document.isObject())
			throw new IOException("Invalid " + label + " manifest object: " + manifest);
		return document;
	}

	/**
	 * Converts supported scalar metadata to the retained textual representation.
	 *
	 * @param value the metadata value
	 * @param field the required field name
	 * @param label the resource name
	 * @return the scalar text
	 * @throws IOException if the field is absent or has a compound value
	 */
	private static String scalarText(JsonNode value, String field, String label) throws IOException
	{
		if (value == null)
			throw new IOException("Missing " + label + " manifest field: " + field);
		if (value.isString())
			return value.stringValue();
		if (value.isNull())
			return "None";
		if (value.isBoolean())
		{
			if (value.booleanValue())
				return "True";
			return "False";
		}
		if (value.isIntegralNumber())
			return value.bigIntegerValue().toString();
		if (value.isNumber())
			return ArchiveNumberText.formatDouble(value.doubleValue());
		throw new IOException("Invalid " + label + " manifest field: " + field);
	}

	/**
	 * Converts declared sizes with the retained decimal integer conversion semantics.
	 *
	 * @param value the declared size
	 * @param label the resource name
	 * @return the exact integer size
	 * @throws IOException if the size cannot be converted to an integer
	 */
	private static BigInteger integerSize(JsonNode value, String label) throws IOException
	{
		if (value == null)
			throw new IOException("Missing " + label + " manifest field: size");
		if (value.isIntegralNumber())
			return value.bigIntegerValue();
		if (value.isString())
			return ArchiveNumberText.parseInteger(value.stringValue());
		if (value.isBoolean())
		{
			if (value.booleanValue())
				return BigInteger.ONE;
			return BigInteger.ZERO;
		}
		if (value.isNumber() && Double.isFinite(value.doubleValue()))
			return new BigDecimal(value.doubleValue(), MathContext.UNLIMITED).toBigInteger();
		throw new IOException("Invalid " + label + " manifest size");
	}
}
