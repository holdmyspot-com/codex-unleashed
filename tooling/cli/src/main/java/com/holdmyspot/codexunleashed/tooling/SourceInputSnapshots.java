package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Persists schema-one source snapshots and records only unchanged prepared inputs. */
public final class SourceInputSnapshots
{
	static final String SCHEMA_VERSION = "schema_version";
	static final String FILES = "files";
	static final String MTIMES = "mtimes";
	static final int CURRENT_SCHEMA = 1;
	private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
		build();
	private static final String REFUSED_RECORDING =
		"Source inputs changed during the build or were not prepared; refusing cache snapshot.";

	/** Prevents construction. */
	private SourceInputSnapshots()
	{
	}

	/**
	 * Reads a complete UTF-8 snapshot without transforming its JSON value types.
	 *
	 * @param path the snapshot
	 * @return its parsed document
	 * @throws NullPointerException if the path is null
	 * @throws IOException if reading or JSON parsing fails
	 */
	public static JsonNode read(Path path) throws IOException
	{
		Objects.requireNonNull(path, "path");
		try
		{
			JsonNode document = JSON.readTree(Files.readString(path));
			if (document == null || document.isMissingNode())
				throw new IOException("Source-input snapshot is empty: " + path);
			return document;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Cannot parse source-input snapshot " + path + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * Writes exact digests and integer nanoseconds through an owned sibling and atomic replacement.
	 *
	 * @param path the destination
	 * @param snapshot the collected inputs
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if parent creation, writing, atomic replacement, or cleanup fails
	 */
	public static void write(Path path, SourceInputInventory.Snapshot snapshot) throws IOException
	{
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(snapshot, "snapshot");
		Path destination = path.toAbsolutePath();
		Files.createDirectories(destination.getParent());
		Map<String, Object> document = Map.of(SCHEMA_VERSION, CURRENT_SCHEMA, FILES, snapshot.files(),
			MTIMES, snapshot.mtimes());
		String text = ArtifactJson.format(document);
		Path temporary = Files.createTempFile(destination.getParent(), ".source-input-snapshot-", ".tmp");
		try
		{
			Files.writeString(temporary, text);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Replaces successful state only after comparing all prepared digests except lockfiles.
	 *
	 * @param path the successful snapshot destination
	 * @param current inputs collected after successful compilation
	 * @return the number of recorded source files, including lockfiles
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if preparation is missing, source inputs differ, or snapshot I/O fails
	 */
	public static int record(Path path, SourceInputInventory.Snapshot current) throws IOException
	{
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(current, "current");
		Path pending = pending(path);
		if (!Files.isRegularFile(pending))
			throw new IOException(REFUSED_RECORDING);
		JsonNode prepared = read(pending).path(FILES);
		if (!prepared.isObject())
			throw new IOException("Expected source file mapping in " + pending);
		Map<String, JsonNode> preparedFiles = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : prepared.properties())
		{
			if (!isLockfile(entry.getKey()))
				preparedFiles.put(entry.getKey(), entry.getValue());
		}
		Map<String, JsonNode> currentFiles = new LinkedHashMap<>();
		JsonNode digests = JSON.valueToTree(current.files());
		for (Map.Entry<String, JsonNode> entry : digests.properties())
		{
			if (!isLockfile(entry.getKey()))
				currentFiles.put(entry.getKey(), entry.getValue());
		}
		if (!preparedFiles.equals(currentFiles))
			throw new IOException(REFUSED_RECORDING);
		write(path, current);
		Files.delete(pending);
		return current.files().size();
	}

	/**
	 * Retains the prepared filename beside the successful snapshot.
	 *
	 * @param snapshot the successful snapshot path
	 * @return the path with its final extension replaced by pending
	 * @throws NullPointerException if the snapshot is null
	 */
	public static Path pending(Path snapshot)
	{
		Objects.requireNonNull(snapshot, "snapshot");
		String name = snapshot.getFileName().toString();
		int extension = name.lastIndexOf('.');
		if (extension > 0)
			name = name.substring(0, extension);
		return snapshot.resolveSibling(name + ".pending");
	}

	/**
	 * Classifies lockfile basenames at every depth using the filesystem's path spelling.
	 *
	 * @param name a source-relative name
	 * @return whether recording ignores this lockfile's digest
	 */
	private static boolean isLockfile(String name)
	{
		return Path.of(name).getFileName().toString().equals("Cargo.lock");
	}
}
