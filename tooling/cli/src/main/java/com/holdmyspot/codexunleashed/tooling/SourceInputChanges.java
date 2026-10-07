package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Restores timestamps for identical sources and marks content changes or interrupted builds for Cargo. */
public final class SourceInputChanges
{
	/** Prevents construction. */
	private SourceInputChanges()
	{
	}

	/**
	 * Compares successful and interrupted inputs before applying confined timestamp updates.
	 *
	 * @param current current raw-file inventory
	 * @param previous successful snapshot
	 * @param interrupted optional inputs used by a build that did not record success
	 * @param clock timestamp authority for actual content changes
	 * @return all changed, added, removed, or interrupted-build source names
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the schema, mappings, paths, or filesystem timestamp operations fail
	 */
	public static Set<String> apply(SourceInputInventory.Snapshot current, JsonNode previous,
		Optional<JsonNode> interrupted, Clock clock) throws IOException
	{
		Objects.requireNonNull(current, "current");
		Objects.requireNonNull(previous, "previous");
		Objects.requireNonNull(interrupted, "interrupted");
		Objects.requireNonNull(clock, "clock");
		JsonNode schema = previous.path(SourceInputSnapshots.SCHEMA_VERSION);
		boolean supported = schema.isBoolean() && schema.booleanValue();
		if (schema.isNumber())
			supported = schema.decimalValue().compareTo(BigDecimal.valueOf(SourceInputSnapshots.CURRENT_SCHEMA)) == 0;
		if (!supported)
			throw new IOException("Unsupported source-input cache snapshot; refusing reuse.");
		JsonNode now = JsonMapper.builder().build().valueToTree(current.files());
		Map<String, JsonNode> currentFiles = files(now);
		Set<String> changed = differences(files(previous.path(SourceInputSnapshots.FILES)), currentFiles);
		if (interrupted.isPresent())
			changed.addAll(differences(files(interrupted.orElseThrow().path(SourceInputSnapshots.FILES)), currentFiles));
		Path root = current.root().toRealPath();
		restore(root, previous.path(SourceInputSnapshots.MTIMES), changed);
		mark(root, changed, FileTime.from(clock.instant()));
		return Set.copyOf(changed);
	}

	/**
	 * Reads mappings while preserving JSON scalar types for digest equality.
	 *
	 * @param values the source mapping
	 * @return its named values, retaining null and other scalar nodes
	 * @throws IOException if the mapping is absent or not an object
	 */
	private static Map<String, JsonNode> files(JsonNode values) throws IOException
	{
		if (!values.isObject())
			throw new IOException("Expected source file mapping in cache snapshot");
		Map<String, JsonNode> result = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : values.properties())
			result.put(entry.getKey(), entry.getValue());
		return result;
	}

	/**
	 * Compares the union of keys with the retained absent-or-null lookup behavior.
	 *
	 * @param previous earlier source values
	 * @param current current source values
	 * @return mutable source names with differing values
	 */
	private static Set<String> differences(Map<String, JsonNode> previous, Map<String, JsonNode> current)
	{
		Set<String> names = new LinkedHashSet<>(previous.keySet());
		names.addAll(current.keySet());
		Set<String> changed = new LinkedHashSet<>();
		for (String name : names)
		{
			if (!Objects.equals(value(previous, name), value(current, name)))
				changed.add(name);
		}
		return changed;
	}

	/**
	 * Retains the original mapping lookup's equality between absent and explicit null values.
	 *
	 * @param files source values
	 * @param name the source name
	 * @return its non-null JSON value, or null for absence or a JSON null value
	 */
	private static JsonNode value(Map<String, JsonNode> files, String name)
	{
		JsonNode result = files.get(name);
		if (result != null && result.isNull())
			return null;
		return result;
	}

	/**
	 * Restores unchanged source and directory timestamps from the successful build.
	 *
	 * @param root the canonical checkout
	 * @param times optional stored timestamps
	 * @param changed names requiring new timestamps
	 * @throws IOException if timestamps are malformed, escape the checkout, or cannot be restored
	 */
	private static void restore(Path root, JsonNode times, Set<String> changed) throws IOException
	{
		if (times.isMissingNode())
			return;
		if (!times.isObject())
			throw new IOException("Expected nanosecond timestamp mapping in cache snapshot");
		for (Map.Entry<String, JsonNode> entry : times.properties())
		{
			Path path = root.resolve(entry.getKey());
			Path resolved = SourceInputPaths.resolve(path);
			if (!resolved.startsWith(root))
				throw new IOException("Source-input cache path escapes checkout: " + entry.getKey());
			if (!changed.contains(entry.getKey()) && Files.exists(path))
				setTimes(path, SourceInputTimes.fileTime(entry.getValue()));
		}
	}

	/**
	 * Marks changed files and all existing lexical ancestor directories within the checkout.
	 *
	 * @param root the checkout root
	 * @param changed changed source names
	 * @param now the current clock value
	 * @throws IOException if paths escape the checkout or timestamp writes fail
	 */
	private static void mark(Path root, Set<String> changed, FileTime now) throws IOException
	{
		Set<Path> directories = new LinkedHashSet<>();
		for (String name : changed)
		{
			Path path = root.resolve(name);
			Path resolved = SourceInputPaths.resolve(path);
			if (resolved.equals(root) || !resolved.startsWith(root))
				throw new IOException("Source-input cache path escapes checkout: " + name);
			if (Files.isRegularFile(path))
				setTimes(path, now);
			Path parent = path.getParent();
			while (parent != null && !parent.equals(root.getParent()))
			{
				if (Files.isDirectory(parent))
					directories.add(parent);
				parent = parent.getParent();
			}
		}
		for (Path directory : directories)
			setTimes(directory, now);
	}

	/**
	 * Restores or marks access and modification time together without altering creation time.
	 *
	 * @param path the file or directory
	 * @param time its required timestamp
	 * @throws IOException if the filesystem update fails
	 */
	private static void setTimes(Path path, FileTime time) throws IOException
	{
		SourceInputTimes.setTimes(path, time);
	}
}
