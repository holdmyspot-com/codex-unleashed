package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads V8 manifests and reconciles the selected version's Bazel module entries. */
public final class V8ModuleChecksums
{
	private static final String SPACE = "[\\s\\u001c-\\u001f]";
	private static final int LINE_FLAGS = Pattern.MULTILINE | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS;
	private static final Pattern BLOCK = Pattern.compile("^http_file\\(\\n.*?^\\)\\n?",
		LINE_FLAGS | Pattern.DOTALL);
	private static final Pattern VERSION = Pattern.compile("^rusty_v8_([0-9]+)_([0-9]+)_([0-9]+)_");
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern WHITESPACE = Pattern.compile(SPACE + "+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern EDGES = Pattern.compile("^" + SPACE + "+|" + SPACE + "+$",
		Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern SHA_LINE = Pattern.compile("^(" + SPACE + "*)sha256" + SPACE +
		"*=" + SPACE + "*\"[0-9a-f]+\"," + SPACE + "*$", LINE_FLAGS);
	private static final Pattern PATH_LINE = Pattern.compile("^(" + SPACE + "*)downloaded_file_path" + SPACE +
		"*=" + SPACE + "*\"[^\"]+\",\\n", LINE_FLAGS);
	private static final Comparator<String> CODE_POINT_ORDER =
		(left, right) -> Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());

	/** Prevents construction of this utility class. */
	private V8ModuleChecksums()
	{
	}

	/**
	 * Reads a manifest with one lowercase SHA-256 digest and filename per nonblank line.
	 *
	 * @param path manifest location
	 * @return checksums indexed by unique artifact filename
	 * @throws IOException if the manifest is absent or invalid
	 */
	public static Map<String, String> readManifest(Path path) throws IOException
	{
		Objects.requireNonNull(path, "path");
		String text;
		try
		{
			text = Files.readString(path);
		}
		catch (NoSuchFileException e)
		{
			throw new IOException("missing checksum manifest: " + path, e);
		}

		Map<String, String> result = new LinkedHashMap<>();
		int lineNumber = 0;
		for (String line : TextLines.split(text))
		{
			lineNumber += 1;
			String stripped = EDGES.matcher(line).replaceAll("");
			if (stripped.isEmpty())
				continue;

			String[] fields = WHITESPACE.split(stripped);
			if (fields.length != 2 || !DIGEST.matcher(fields[0]).matches())
				throw new IOException(path + ":" + lineNumber + ": invalid checksum entry");
			String filename = fields[1];
			if (filename.equals(".") || filename.equals("..") || filename.contains("/"))
				throw new IOException(path + ":" + lineNumber + ": invalid artifact filename: " + filename);
			if (result.putIfAbsent(filename, fields[0]) != null)
				throw new IOException(path + ":" + lineNumber + ": duplicate artifact filename: " + filename);
		}
		if (result.isEmpty())
			throw new IOException("empty checksum manifest: " + path);
		return Map.copyOf(result);
	}

	/**
	 * Lists distinct V8 asset versions in lexical order.
	 *
	 * @param text module source text
	 * @return versions recognized in unindented http_file blocks
	 */
	public static List<String> versions(String text)
	{
		Objects.requireNonNull(text, "text");
		var versions = new TreeSet<String>(CODE_POINT_ORDER);
		Matcher blocks = BLOCK.matcher(text);
		while (blocks.find())
		{
			String name = field(blocks.group(), "name");
			if (name == null)
				continue;
			Matcher version = VERSION.matcher(name);
			if (version.find())
				versions.add(version.group(1) + "." + version.group(2) + "." + version.group(3));
		}
		return List.copyOf(versions);
	}

	/**
	 * Requires matching coverage and checksums for the selected asset version.
	 *
	 * @param text module source text
	 * @param manifest artifact checksums
	 * @param version selected dotted V8 version
	 * @throws IOException if module coverage or checksums drift
	 */
	public static void check(String text, Map<String, String> manifest, String version) throws IOException
	{
		List<Entry> entries = entries(text, version);
		List<String> errors = coverage(entries, manifest, version);
		for (Entry entry : entries)
		{
			String expected = manifest.get(entry.filename());
			if (expected == null)
				continue;
			if (entry.sha256() == null)
				errors.add("MODULE.bazel " + entry.name() + " is missing sha256");
			else if (!entry.sha256().equals(expected))
				errors.add("MODULE.bazel " + entry.name() + " has sha256 " + entry.sha256() + ", expected " + expected);
		}
		refuse(errors, "rusty_v8 MODULE.bazel checksum drift:");
	}

	/**
	 * Updates only the selected version's checksum fields after validating coverage.
	 *
	 * @param text module source text
	 * @param manifest artifact checksums
	 * @param version selected dotted V8 version
	 * @return source with selected checksums updated
	 * @throws IOException if coverage or a required field is invalid
	 */
	public static String update(String text, Map<String, String> manifest, String version) throws IOException
	{
		List<Entry> entries = entries(text, version);
		refuse(coverage(entries, manifest, version), "cannot update rusty_v8 MODULE.bazel checksums:");
		var result = new StringBuilder();
		int previous = 0;
		for (Entry entry : entries)
		{
			result.append(text, previous, entry.start()).
				append(replaceChecksum(entry.block(), manifest.get(entry.filename())));
			previous = entry.end();
		}
		return result.append(text, previous, text.length()).toString();
	}

	/**
	 * Reads the first quoted field using the module helper's source grammar.
	 *
	 * @param block source block
	 * @param name field name
	 * @return value, or null when absent
	 */
	private static String field(String block, String name)
	{
		Pattern pattern = Pattern.compile("^" + SPACE + "*" + Pattern.quote(name) + SPACE + "*=" + SPACE +
			"*\"([^\"]+)\"," + SPACE + "*$", LINE_FLAGS);
		Matcher match = pattern.matcher(block);
		if (match.find())
			return match.group(1);
		return null;
	}

	/**
	 * Selects source blocks belonging to one version.
	 *
	 * @param text module source
	 * @param version dotted version
	 * @return selected blocks in source order
	 * @throws IOException if a selected block has no artifact path
	 */
	private static List<Entry> entries(String text, String version) throws IOException
	{
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(version, "version");
		String prefix = "rusty_v8_" + version.replace('.', '_') + "_";
		List<Entry> result = new ArrayList<>();
		Matcher blocks = BLOCK.matcher(text);
		while (blocks.find())
		{
			String block = blocks.group();
			String name = field(block, "name");
			if (name == null || !name.startsWith(prefix))
				continue;
			String filename = field(block, "downloaded_file_path");
			if (filename == null)
				throw new IOException("MODULE.bazel " + name + " is missing downloaded_file_path");
			result.add(new Entry(blocks.start(), blocks.end(), block, name, filename, field(block, "sha256")));
		}
		return result;
	}

	/**
	 * Collects coverage errors without discarding duplicate entries.
	 *
	 * @param entries selected source entries
	 * @param manifest artifact checksums
	 * @param version selected version
	 * @return mutable ordered diagnostics
	 */
	private static List<String> coverage(List<Entry> entries, Map<String, String> manifest, String version)
	{
		Objects.requireNonNull(manifest, "manifest");
		List<String> errors = new ArrayList<>();
		if (entries.isEmpty())
		{
			errors.add("MODULE.bazel has no rusty_v8 http_file entries for " + version);
			return errors;
		}

		Map<String, Entry> byFilename = new LinkedHashMap<>();
		var duplicates = new TreeSet<String>(CODE_POINT_ORDER);
		for (Entry entry : entries)
			if (byFilename.put(entry.filename(), entry) != null)
				duplicates.add(entry.filename());
		for (String filename : duplicates)
			errors.add("MODULE.bazel has duplicate http_file entries for " + filename);
		for (String filename : byFilename.keySet().stream().sorted(CODE_POINT_ORDER).toList())
			if (!manifest.containsKey(filename))
				errors.add("MODULE.bazel " + byFilename.get(filename).name() + " has no checksum in the manifest");
		for (String filename : manifest.keySet().stream().sorted(CODE_POINT_ORDER).toList())
			if (!byFilename.containsKey(filename))
				errors.add("manifest has " + filename + ", but MODULE.bazel has no http_file");
		return errors;
	}

	/**
	 * Replaces the first recognized checksum or inserts one after the artifact path.
	 *
	 * @param block original source block
	 * @param digest expected checksum
	 * @return edited block
	 * @throws IOException if checksum insertion has no recognized path line
	 */
	private static String replaceChecksum(String block, String digest) throws IOException
	{
		Matcher sha = SHA_LINE.matcher(block);
		if (sha.find())
			return block.substring(0, sha.start()) + sha.group(1) + "sha256 = \"" + digest + "\"," +
				block.substring(sha.end());

		Matcher path = PATH_LINE.matcher(block);
		if (!path.find())
			throw new IOException("http_file block is missing downloaded_file_path");
		return block.substring(0, path.end()) + path.group(1) + "sha256 = \"" + digest + "\",\n" +
			block.substring(path.end());
	}

	/**
	 * Refuses an operation with every collected diagnostic.
	 *
	 * @param errors ordered diagnostics
	 * @param heading operation description
	 * @throws IOException if errors exist
	 */
	private static void refuse(List<String> errors, String heading) throws IOException
	{
		if (!errors.isEmpty())
			throw new IOException(heading + "\n- " + String.join("\n- ", errors));
	}

	/**
	 * A selected module entry with source offsets for lossless stitching.
	 *
	 * @param start first source offset
	 * @param end exclusive last source offset
	 * @param block original block text
	 * @param name Bazel repository name
	 * @param filename downloaded artifact filename
	 * @param sha256 declared checksum, or null when missing
	 */
	private record Entry(int start, int end, String block, String name, String filename, String sha256)
	{
	}
}
