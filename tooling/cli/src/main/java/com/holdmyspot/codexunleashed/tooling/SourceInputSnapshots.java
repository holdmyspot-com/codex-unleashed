package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Persists schema-one source snapshots and records only unchanged prepared inputs.
 */
public final class SourceInputSnapshots
{
	static final String SCHEMA_VERSION = "schema_version";
	static final String FILES = "files";
	static final String MTIMES = "mtimes";
	static final int CURRENT_SCHEMA = 1;
	private static final String REFUSED_RECORDING =
		"Source inputs changed during the build or were not prepared; refusing cache snapshot.";

	/**
	 * Prevents construction.
	 */
	private SourceInputSnapshots()
	{
	}

	/**
	 * Reads a complete UTF-8 snapshot without transforming its JSON value types.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param path the snapshot
	 * @return its parsed document
	 * @throws NullPointerException if the path is null
	 * @throws IOException if reading or JSON parsing fails
	 */
	public static JsonNode read(ToolingScope scope, Path path) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(path, "path");
		try
		{
			JsonNode document = scope.getJsonMapper().readTree(Files.readString(path));
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
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param path the destination
	 * @param snapshot the collected inputs
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if parent creation, writing, atomic replacement, or cleanup fails
	 */
	public static void write(ToolingScope scope, Path path, SourceInputInventory.Snapshot snapshot) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(snapshot, "snapshot");
		Path destination = path.toAbsolutePath();
		Files.createDirectories(destination.getParent());
		Map<String, Object> document = Map.of(SCHEMA_VERSION, CURRENT_SCHEMA, FILES, snapshot.files(),
			MTIMES, snapshot.mtimes());
		String text = ArtifactJson.format(scope, document);
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
	 * Replaces the successful snapshot only when current digests match the pending inputs, excluding
	 * paths named {@code Cargo.lock} at any depth. Then deletes pending and completed-preparation state.
	 * The caller must establish build success before invoking this method. If deletion fails after replacement,
	 * the new successful snapshot remains written even though the method reports a failure.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param path the successful snapshot destination
	 * @param current inputs collected after successful compilation
	 * @return the number of recorded source files, including lockfiles
	 * @throws NullPointerException if either argument is null
	 * @throws IOException if preparation is missing, source inputs differ, or snapshot I/O fails
	 */
	public static int record(ToolingScope scope, Path path, SourceInputInventory.Snapshot current) throws IOException
	{
		verifyPrepared(scope, path, current);
		write(scope, path, current);
		Files.delete(pending(path));
		Files.deleteIfExists(resumable(path));
		return current.files().size();
	}

	/**
	 * Retains effective input timestamps after preparation without recording build success.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param path the successful snapshot path that identifies these inputs
	 * @param current inputs collected after all preparation operations complete
	 * @throws IOException if prepared contents drift or checkpoint writing fails
	 */
	public static void checkpoint(ToolingScope scope, Path path, SourceInputInventory.Snapshot current) throws IOException
	{
		verifyPrepared(scope, path, current);
		write(scope, resumable(path), current);
	}

	/**
	 * Supplies the distinct completed-preparation checkpoint path.
	 *
	 * @param snapshot the successful snapshot path
	 * @return the sibling filename with a resumable suffix
	 * @throws NullPointerException if the snapshot is null
	 */
	public static Path resumable(Path snapshot)
	{
		Objects.requireNonNull(snapshot, "snapshot");
		return snapshot.resolveSibling(snapshot.getFileName() + ".resumable");
	}

	/**
	 * Compares current and pending source digests, ignoring paths whose basename is {@code Cargo.lock}.
	 *
	 * @param scope borrowed shared values; the caller retains ownership through all operations
	 * @param path the successful snapshot path
	 * @param current content whose effective timestamps will be persisted
	 * @throws IOException if prepared inputs are missing, malformed or changed
	 */
	private static void verifyPrepared(ToolingScope scope, Path path, SourceInputInventory.Snapshot current) throws IOException
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(current, "current");
		Path pending = pending(path);
		if (!Files.isRegularFile(pending))
			throw new IOException(REFUSED_RECORDING);
		JsonNode prepared = read(scope, pending).path(FILES);
		if (!prepared.isObject())
			throw new IOException("Expected source file mapping in " + pending);
		Map<String, JsonNode> preparedFiles = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : prepared.properties())
		{
			if (!isLockfile(entry.getKey()))
				preparedFiles.put(entry.getKey(), entry.getValue());
		}
		Map<String, JsonNode> currentFiles = new LinkedHashMap<>();
		JsonNode digests = scope.getJsonMapper().valueToTree(current.files());
		for (Map.Entry<String, JsonNode> entry : digests.properties())
		{
			if (!isLockfile(entry.getKey()))
				currentFiles.put(entry.getKey(), entry.getValue());
		}
		if (!preparedFiles.equals(currentFiles))
		{
			Set<String> changedPaths = new TreeSet<>(preparedFiles.keySet());
			changedPaths.addAll(currentFiles.keySet());
			changedPaths.removeIf(name -> Objects.equals(preparedFiles.get(name), currentFiles.get(name)));
			throw new IOException(REFUSED_RECORDING + " Differing paths: " + changedPaths);
		}
	}

	/**
	 * Derives the pending-input filename beside the successful snapshot.
	 *
	 * @param snapshot the successful snapshot path
	 * @return the sibling path with its final extension replaced by {@code .pending}, or {@code .pending} appended when no dot follows the first character
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
	 * Indicates whether the path basename is exactly {@code Cargo.lock}.
	 *
	 * @param name a source-relative name
	 * @return whether recording ignores this lockfile's digest
	 */
	private static boolean isLockfile(String name)
	{
		return Path.of(name).getFileName().toString().equals("Cargo.lock");
	}
}
