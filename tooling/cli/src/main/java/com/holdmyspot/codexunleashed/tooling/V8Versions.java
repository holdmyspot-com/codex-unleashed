package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the exact V8 crate version used by an upstream checkout.
 */
public final class V8Versions
{
	private static final Pattern CRATE_URL = Pattern.compile(
		"https://static\\.crates\\.io/crates/v8/v8-([0-9]+\\.[0-9]+\\.[0-9]+)\\.crate");

	/**
	 * Prevents construction.
	 */
	private V8Versions()
	{
	}

	/**
	 * Resolves a direct or nested Cargo lockfile, falling back to the pinned module URL when V8 is absent.
	 *
	 * @param checkout the upstream checkout or its containing project
	 * @return the single resolved V8 version
	 * @throws NullPointerException if {@code checkout} is null
	 * @throws IOException if an input is unreadable, malformed, or contains ambiguous versions
	 */
	public static String resolve(Path checkout) throws IOException
	{
		Objects.requireNonNull(checkout, "checkout");
		Path lockfile = checkout.resolve("codex-rs/Cargo.lock");
		if (!Files.isRegularFile(lockfile))
			lockfile = checkout.resolve("upstream/codex-rs/Cargo.lock");
		SortedSet<String> versions = lockfileVersions(lockfile);
		if (versions.size() == 1)
			return versions.first();
		if (versions.size() > 1)
			throw new IOException("expected exactly one resolved v8 version, found: " + versions);

		Matcher urls = CRATE_URL.matcher(Files.readString(checkout.resolve("MODULE.bazel")));
		while (urls.find())
			versions.add(urls.group(1));
		if (versions.size() != 1)
			throw new IOException("expected exactly one pinned v8 crate version in MODULE.bazel, found: " + versions);
		return versions.first();
	}

	/**
	 * Resolves exactly one V8 version from serialized Cargo.lock without a module URL fallback.
	 *
	 * @param cargoLock the decoded lockfile content
	 * @return the single unchanged V8 version
	 * @throws NullPointerException if {@code cargoLock} is null
	 * @throws IOException if the lockfile is malformed or has zero or multiple V8 versions
	 */
	public static String resolveLockfile(String cargoLock) throws IOException
	{
		Objects.requireNonNull(cargoLock, "cargoLock");
		SortedSet<String> versions = lockfileVersions(cargoLock, "");
		if (versions.size() != 1)
			throw new IOException("expected exactly one resolved v8 version, found: " + versions);
		return versions.first();
	}

	/**
	 * Reads the distinct V8 package versions without normalizing their spelling.
	 *
	 * @param lockfile the Cargo lockfile
	 * @return the sorted distinct version strings
	 * @throws IOException if the file or required package fields are invalid
	 */
	private static SortedSet<String> lockfileVersions(Path lockfile) throws IOException
	{
		try
		{
			return lockfileVersions(Files.readString(lockfile), " at " + lockfile);
		}
		catch (CharacterCodingException failure)
		{
			throw new IOException("Cargo.lock is not valid UTF-8 at " + lockfile, failure);
		}
	}

	/**
	 * Parses distinct package versions with a caller-specific diagnostic location.
	 *
	 * @param cargoLock the decoded lockfile content
	 * @param location the optional file location
	 * @return the sorted distinct V8 version strings
	 * @throws IOException if the lockfile or required fields are malformed
	 */
	private static SortedSet<String> lockfileVersions(String cargoLock, String location) throws IOException
	{
		JsonNode document;
		try
		{
			document = TomlDocuments.parse(cargoLock);
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse Cargo.lock" + location + ": " + failure.getMessage(), failure);
		}
		JsonNode packages = document.get("package");
		if (packages == null || !packages.isArray())
			throw new IOException("Cargo.lock must contain a package array" + location);
		SortedSet<String> versions = new TreeSet<>();
		for (JsonNode entry : packages)
		{
			JsonNode name = entry.get("name");
			if (name == null || !name.isString())
				throw new IOException("Cargo.lock package name must be a string" + location);
			if ("v8".equals(name.stringValue()))
			{
				JsonNode version = entry.get("version");
				if (version == null || !version.isString())
					throw new IOException("Cargo.lock V8 version must be a string" + location);
				versions.add(version.stringValue());
			}
		}
		return versions;
	}
}
