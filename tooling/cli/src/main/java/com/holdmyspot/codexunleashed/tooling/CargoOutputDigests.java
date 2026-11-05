package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * Reuses the content digest of a Cargo output file that has not changed since an earlier checkpoint hashed it.
 * <p>
 * The index lives in the output directory and is never archived. A recorded digest is reused only when the file's
 * device and inode, size, modification time and change time all match, and its change time is at least
 * {@link #SETTLED_NANOS} older than the moment the recording checkpoint began hashing. The kernel sets the change
 * time on every write and metadata change, and no unprivileged process can set it back; the margin covers the
 * kernel's coarse timestamp clock, so a write just after hashing cannot keep the recorded change time. A restored or
 * copied file gets a new inode and change time, so its first checkpoint hashes it again.
 * <p>
 * Restoring a cache rebuilds the index from the archived entry list of each part, which records every file's digest:
 * a restored file whose size and modification time match its archived entry is recorded under its new identity with
 * the archived digest, so the first checkpoint after a restore hashes only what the build changed since.
 * <p>
 * File systems without POSIX change times, such as Windows, keep no index and hash every file.
 */
final class CargoOutputDigests
{
	/** Target-relative index file, excluded from every collected group. */
	static final String INDEX = ".codex-cache-digests.json";
	/**
	 * Target-relative directory that holds each archived part's entry list while it is packed or restored; it is never
	 * collected and is removed once the transfer ends.
	 */
	static final String PART_ENTRIES = ".codex-cache-part-entries";
	/** Current index format. */
	private static final int FORMAT_VERSION = 1;
	/** Minimum age, in nanoseconds, of a recorded change time relative to its hashing before the digest is trusted. */
	private static final BigInteger SETTLED_NANOS = BigInteger.valueOf(1_000_000_000L);
	/** Fields of the index document. */
	private static final Set<String> DOCUMENT_FIELDS = Set.of("format_version", "hashed_at_nanos", "files");
	/** Fields of each file entry. */
	private static final Set<String> FILE_FIELDS = Set.of("file_key", "size", "modified_nanos", "changed_nanos", "digest");

	/** Index location inside the output directory. */
	private final Path index;
	/** Whether the file system reports POSIX change times. */
	private final boolean indexed;
	/** Recorded entries that may be reused, by target-relative path. */
	private final Map<String, Entry> recorded;
	/** Start of the recording checkpoint's hashing, in epoch nanoseconds. */
	private final BigInteger recordedAt;
	/** Start of this checkpoint's hashing, in epoch nanoseconds. */
	private final BigInteger hashedAt;
	/** Entries observed by this checkpoint, written as the next index. */
	private final Map<String, Entry> observed = new ConcurrentHashMap<>();

	/**
	 * Reads the output directory's index, if any, and starts a checkpoint's hashing.
	 * <p>
	 * The index holds one entry for each output file, the same paths that the checkpoint already holds in memory, and
	 * a lookup needs the whole document, so it is read in one piece.
	 *
	 * @param scope  borrowed JSON services
	 * @param target idle existing output directory
	 * @throws IOException if the index is unreadable or outside the recorded format
	 */
	CargoOutputDigests(ToolingScope scope, Path target) throws IOException
	{
		this.index = target.resolve(INDEX);
		this.indexed = target.getFileSystem().supportedFileAttributeViews().contains("unix");
		this.hashedAt = SourceInputTimes.nanos(Instant.now());
		Map<String, Entry> entries = new TreeMap<>();
		BigInteger recordedTime = BigInteger.ZERO;
		if (indexed && Files.exists(index, LinkOption.NOFOLLOW_LINKS))
		{
			JsonNode document = scope.getJsonMapper().readTree(Files.readString(index));
			requireFields(document, DOCUMENT_FIELDS, "document");
			if (document.get("format_version").asInt(0) != FORMAT_VERSION || !document.get("format_version").isInt())
				throw new IOException("Unsupported cached output digest index version");
			recordedTime = integer(document.get("hashed_at_nanos"), "hashed_at_nanos");
			JsonNode files = document.get("files");
			if (!files.isObject()) throw new IOException("Invalid cached output digest index files");
			for (var file : files.properties())
			{
				JsonNode value = file.getValue();
				requireFields(value, FILE_FIELDS, file.getKey());
				if (!value.get("file_key").isString() || !value.get("digest").isString() ||
					!value.get("digest").asString().matches("[0-9a-f]{64}"))
					throw new IOException("Invalid cached output digest entry: " + file.getKey());
				entries.put(file.getKey(), new Entry(value.get("file_key").asString(),
					integer(value.get("size"), "size").longValueExact(), integer(value.get("modified_nanos"), "modified_nanos"),
					integer(value.get("changed_nanos"), "changed_nanos"), value.get("digest").asString()));
			}
		}
		this.recorded = Map.copyOf(entries);
		this.recordedAt = recordedTime;
	}

	/**
	 * Returns a regular file's content digest, reusing the recorded one when the file is unchanged and settled.
	 * Safe to call from several threads for different paths.
	 *
	 * @param path       regular output file
	 * @param relative   its target-relative path with {@code /} separators
	 * @param attributes its attributes, read without following links before this call
	 * @param modified   its exact modification time, read before this call
	 * @return lowercase hexadecimal content digest
	 * @throws IOException if the file or its change time cannot be read
	 */
	String digest(Path path, String relative, BasicFileAttributes attributes, Instant modified) throws IOException
	{
		if (!indexed) return hash(path);
		// Read the change time before the bytes: a write during hashing then leaves a newer change time than recorded.
		FileTime changed = (FileTime) Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
		Entry current = new Entry(String.valueOf(attributes.fileKey()), attributes.size(), SourceInputTimes.nanos(modified),
			SourceInputTimes.nanos(changed.toInstant()), null);
		Entry prior = recorded.get(relative);
		String digest;
		if (prior != null && prior.sameFile(current) &&
			prior.changedNanos().compareTo(recordedAt.subtract(SETTLED_NANOS)) < 0)
			digest = prior.digest();
		else
			digest = hash(path);
		observed.put(relative, current.withDigest(digest));
		return digest;
	}

	/**
	 * Replaces the index with this checkpoint's observations. Does nothing on a file system without change times.
	 *
	 * @param scope borrowed JSON services
	 * @throws IOException if the index cannot be written
	 */
	void write(ToolingScope scope) throws IOException
	{
		if (!indexed) return;
		Map<String, Object> files = new TreeMap<>();
		for (var file : observed.entrySet())
		{
			Entry entry = file.getValue();
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("file_key", entry.fileKey());
			value.put("size", entry.size());
			value.put("modified_nanos", entry.modifiedNanos());
			value.put("changed_nanos", entry.changedNanos());
			value.put("digest", entry.digest());
			files.put(file.getKey(), value);
		}
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("format_version", FORMAT_VERSION);
		document.put("hashed_at_nanos", hashedAt);
		document.put("files", files);
		Path pending = Files.createTempFile(index.getParent(), ".cache-digests-", ".json");
		try
		{
			Files.write(pending, scope.getJsonMapper().writeValueAsString(document).getBytes(StandardCharsets.UTF_8));
			Files.move(pending, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(pending);
		}
	}

	/**
	 * Indexes the regular files that a restore just extracted, using the entry list archived with each part, then
	 * removes the entry lists. Entries that the earlier index already trusted are kept for files the restore did not
	 * replace. Call this only after every part is extracted and before anything else writes the output directory, so
	 * this checkpoint's hashing time follows every extracted change.
	 *
	 * @param scope      borrowed JSON and timestamp services
	 * @param target     output directory holding the extracted parts
	 * @param signatures each restored part's signature, the digest of its exact entry list, by saving step
	 * @throws IOException if an entry list matches its signature but is malformed, or the index cannot be written
	 */
	void restore(ToolingScope scope, Path target, Map<String, String> signatures) throws IOException
	{
		Path lists = target.resolve(PART_ENTRIES);
		try
		{
			if (!indexed) return;
			for (var prior : recorded.entrySet())
				if (prior.getValue().changedNanos().compareTo(recordedAt.subtract(SETTLED_NANOS)) < 0)
					observed.put(prior.getKey(), prior.getValue());
			for (var part : signatures.entrySet())
			{
				Path list = lists.resolve(part.getKey() + ".json");
				if (!Files.isRegularFile(list, LinkOption.NOFOLLOW_LINKS)) continue;
				// The signature covers the whole list, so it is verified and then parsed in one piece.
				byte[] bytes = Files.readAllBytes(list);
				if (!HexFormat.of().formatHex(CargoCacheKeys.digest().digest(bytes)).equals(part.getValue())) continue;
				JsonNode entries = scope.getJsonMapper().readTree(bytes);
				if (!entries.isArray()) throw new IOException("Invalid archived entry list: " + part.getKey());
				for (JsonNode entry : entries)
					adopt(scope, target, entry);
			}
			write(scope);
		}
		finally
		{
			if (Files.isDirectory(lists, LinkOption.NOFOLLOW_LINKS))
				try (var paths = Files.walk(lists))
				{
					for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
						Files.delete(path);
				}
		}
	}

	/**
	 * Records one archived entry's digest when it describes a regular file that the restore extracted unchanged.
	 *
	 * @param scope  borrowed timestamp services
	 * @param target output directory
	 * @param entry  archived {@code [path, mode, modified_nanos, size, content]} entry
	 * @throws IOException if the entry is malformed or the extracted file's attributes cannot be read
	 */
	private void adopt(ToolingScope scope, Path target, JsonNode entry) throws IOException
	{
		if (!entry.isArray() || entry.size() != 5 || !entry.get(0).isString() || !entry.get(1).isIntegralNumber() ||
			!entry.get(2).isIntegralNumber() || !entry.get(3).isIntegralNumber() || !entry.get(4).isString())
			throw new IOException("Invalid archived entry: " + entry);
		String relative = entry.get(0).asString();
		boolean regular = (entry.get(1).asInt() & 0170000) == 0100000;
		String digest = entry.get(4).asString();
		Path path = target.resolve(relative).normalize();
		if (!regular || !digest.matches("[0-9a-f]{64}") || !path.startsWith(target) || path.equals(target)) return;
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException absent)
		{
			return;
		}
		BigInteger modified = SourceInputTimes.nanos(SourceInputTimes.lastModified(scope, path, LinkOption.NOFOLLOW_LINKS));
		if (!attributes.isRegularFile() || attributes.size() != entry.get(3).longValue() ||
			!modified.equals(entry.get(2).bigIntegerValue()))
			return;
		FileTime changed = (FileTime) Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
		observed.put(relative, new Entry(String.valueOf(attributes.fileKey()), attributes.size(), modified,
			SourceInputTimes.nanos(changed.toInstant()), digest));
	}

	/**
	 * Hashes a file's bytes with the cache digest.
	 *
	 * @param path regular file
	 * @return lowercase hexadecimal digest
	 * @throws IOException if the file cannot be read
	 */
	static String hash(Path path) throws IOException
	{
		var digest = CargoCacheKeys.digest();
		try (var input = Files.newInputStream(path))
		{
			byte[] buffer = new byte[65536];
			int count;
			while ((count = input.read(buffer)) != -1)
				digest.update(buffer, 0, count);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * Requires an object with exactly the given fields.
	 *
	 * @param node   parsed value
	 * @param fields required field names
	 * @param name   value name for diagnostics
	 * @throws IOException if {@code node} is not an object with exactly {@code fields}
	 */
	private static void requireFields(JsonNode node, Set<String> fields, String name) throws IOException
	{
		if (node == null || !node.isObject() || !node.propertyNames().equals(fields))
			throw new IOException("Invalid cached output digest index " + name);
	}

	/**
	 * Reads a non-negative integral field.
	 *
	 * @param node parsed value
	 * @param name field name for diagnostics
	 * @return the value
	 * @throws IOException if {@code node} is not a non-negative integer
	 */
	private static BigInteger integer(JsonNode node, String name) throws IOException
	{
		if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0)
			throw new IOException("Invalid cached output digest index " + name);
		return node.bigIntegerValue();
	}

	/**
	 * One output file's identity, timestamps and digest.
	 *
	 * @param fileKey       file system identity of the file, such as its device and inode
	 * @param size          size in bytes
	 * @param modifiedNanos modification time in epoch nanoseconds
	 * @param changedNanos  change time in epoch nanoseconds
	 * @param digest        content digest, or null before hashing
	 */
	private record Entry(String fileKey, long size, BigInteger modifiedNanos, BigInteger changedNanos, String digest)
	{
		/**
		 * Compares every identity and timestamp field, ignoring digests.
		 *
		 * @param other observation of the same path
		 * @return whether both describe the same unchanged file
		 */
		boolean sameFile(Entry other)
		{
			return fileKey.equals(other.fileKey) && size == other.size && modifiedNanos.equals(other.modifiedNanos) &&
				changedNanos.equals(other.changedNanos);
		}

		/**
		 * Returns this observation with its digest.
		 *
		 * @param value content digest
		 * @return the completed entry
		 */
		Entry withDigest(String value)
		{
			return new Entry(fileKey, size, modifiedNanos, changedNanos, Objects.requireNonNull(value, "value"));
		}
	}
}
