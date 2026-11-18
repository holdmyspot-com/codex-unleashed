package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads completed package operation records as inert diagnostic data with bounded parsing state.
 */
public final class NativeOperationRecords
{
	/** Maximum entries in either cumulative diagnostic inventory. */
	private static final int MAX_ENTRIES = 256;
	/** Maximum UTF-8 bytes in one recorded string. */
	private static final int MAX_STRING_BYTES = 64 * 1024;
	/** Bytes read across both operations. */
	private int bytesRead;
	/** Total recorded commands. */
	private int commandCount;
	/** Total recorded environment entries. */
	private int environmentCount;

	/**
	 * Creates one invocation's bounded record reader.
	 */
	private NativeOperationRecords()
	{
	}

	/**
	 * Reads and cross-checks the completed build and archive records.
	 *
	 * @param scope borrowed JSON services
	 * @param build build result root
	 * @param archive archive result root
	 * @param target expected target
	 * @param runnerOs explicitly resolved producer host
	 * @return ordered build and archive diagnostic objects
	 * @throws IOException if an explicit record cannot be read
	 * @throws IllegalArgumentException if records are incomplete, malformed or oversized
	 */
	public static JsonNode operations(ToolingScope scope, Path build, Path archive, String target, String runnerOs)
		throws IOException
	{
		NativeOperationRecords reader = new NativeOperationRecords();
		ArrayNode operations = scope.getJsonMapper().createArrayNode();
		String buildBinaries = reader.metadata(build, "build_binaries", target, runnerOs);
		String archiveBinaries = reader.metadata(archive, "archive_packages", target, runnerOs);
		check(buildBinaries.equals(archiveBinaries), "Operation binaries metadata differs: " + archive);
		operations.add(reader.operation(scope, build, "build_binaries"));
		operations.add(reader.operation(scope, archive, "archive_packages"));
		return operations;
	}

	/**
	 * Validates completion and cross-operation metadata without interpreting auxiliary files.
	 *
	 * @param root explicit operation root
	 * @param operation expected operation name
	 * @param target expected target
	 * @param runnerOs expected runner OS
	 * @return nonempty selected-binaries metadata
	 * @throws IOException if reading fails
	 */
	private String metadata(Path root, String operation, String target, String runnerOs) throws IOException
	{
		check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Operation root must be a directory: " + root);
		check(line(root.resolve("completed")).equals("true"), "Operation is not completed: " + root);
		check(line(root.resolve("format-version")).equals("1"), "Unsupported operation format: " + root);
		check(line(root.resolve("operation")).equals(operation), "Unexpected operation metadata: " + root);
		check(line(root.resolve("target")).equals(target), "Operation target differs: " + root);
		check(line(root.resolve("runner-os")).equals(runnerOs), "Operation runner OS differs: " + root);
		String binaries = line(root.resolve("binaries"));
		check(!binaries.isEmpty(), "Empty binaries metadata: " + root);
		return binaries;
	}

	/**
	 * Decodes one exact single-line LF-terminated metadata record.
	 *
	 * @param path explicit metadata path
	 * @return text without the final LF
	 * @throws IOException if reading or strict decoding fails
	 */
	private String line(Path path) throws IOException
	{
		String value = decode(read(path), path);
		check(value.endsWith("\n"), "Missing metadata LF: " + path);
		String result = value.substring(0, value.length() - 1);
		check(result.indexOf('\n') < 0 && result.indexOf('\r') < 0 && result.indexOf('\0') < 0,
			"Metadata must contain one line: " + path);
		check(result.getBytes(StandardCharsets.UTF_8).length <= MAX_STRING_BYTES, "Metadata exceeds 64 KiB: " + path);
		return result;
	}

	/**
	 * Reads command and environment arrays under one validated result root.
	 *
	 * @param scope borrowed JSON services
	 * @param root explicit operation root
	 * @param name expected operation name
	 * @return operation object
	 * @throws IOException if any record cannot be read
	 */
	private JsonNode operation(ToolingScope scope, Path root, String name) throws IOException
	{
		ObjectNode operation = scope.getJsonMapper().createObjectNode().put("operation", name);
		Path commands = root.resolve("commands");
		check(Files.isDirectory(commands, LinkOption.NOFOLLOW_LINKS), "Commands must be a directory: " + commands);
		List<Path> paths;
		try (Stream<Path> entries = Files.list(commands))
		{
			paths = entries.limit(MAX_ENTRIES + 1L).sorted(Comparator.comparing(path -> path.getFileName().toString())).
				toList();
		}
		check(!paths.isEmpty() && paths.size() <= MAX_ENTRIES, "Operation requires 1 to 256 commands: " + commands);
		commandCount += paths.size();
		check(commandCount <= MAX_ENTRIES, "Native inputs require at most 256 commands: " + commands);
		ArrayNode vectors = operation.putArray("commands");
		for (int index = 0; index < paths.size(); ++index)
		{
			Path path = paths.get(index);
			String expected = String.format(java.util.Locale.ROOT, "%04d.argv", index + 1);
			check(path.getFileName().toString().equals(expected), "Unexpected command record at index " +
				(index + 1) + ": " + path);
			List<String> arguments = strings(path);
			check(!arguments.isEmpty() && arguments.size() <= MAX_ENTRIES,
				"Command requires 1 to 256 arguments: " + path);
			ArrayNode vector = vectors.addArray();
			for (String argument : arguments)
				vector.add(argument);
		}
		Path environment = root.resolve("environment.nul");
		List<String> pairs = strings(environment);
		check(pairs.size() % 2 == 0, "Environment requires key/value pairs: " + environment);
		environmentCount += pairs.size() / 2;
		check(environmentCount <= MAX_ENTRIES, "Native inputs require at most 256 environment entries: " + environment);
		ObjectNode variables = operation.putObject("environment");
		Set<String> names = new HashSet<>();
		for (int index = 0; index < pairs.size(); index += 2)
		{
			String key = pairs.get(index);
			check(names.add(key), "Duplicate environment key at pair " + (index / 2 + 1) + ": " + environment);
			variables.put(key, pairs.get(index + 1));
		}
		return operation;
	}

	/**
	 * Decodes strict NUL-terminated diagnostic strings, retaining empty values.
	 *
	 * @param path explicit record path
	 * @return decoded strings in recorded order
	 * @throws IOException if reading or UTF-8 decoding fails
	 */
	private List<String> strings(Path path) throws IOException
	{
		byte[] content = read(path);
		check(content.length == 0 || content[content.length - 1] == 0, "Missing NUL terminator: " + path);
		List<String> result = new ArrayList<>();
		int start = 0;
		for (int index = 0; index < content.length; ++index)
		{
			if (content[index] != 0)
				continue;
			check(index - start <= MAX_STRING_BYTES, "Recorded string exceeds 64 KiB: " + path);
			result.add(decode(java.util.Arrays.copyOfRange(content, start, index), path));
			check(result.size() <= MAX_ENTRIES * 2, "Too many recorded strings: " + path);
			start = index + 1;
		}
		return result;
	}

	/**
	 * Reads a regular nonsymbolic input within the cumulative document byte budget.
	 *
	 * @param path explicit input path
	 * @return bounded bytes
	 * @throws IOException if reading fails
	 */
	private byte[] read(Path path) throws IOException
	{
		check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Record must be a regular nonsymbolic file: " + path);
		int remaining = ReleaseManifestSchema.MAX_DOCUMENT_BYTES - bytesRead;
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] content = input.readNBytes(remaining + 1);
			check(content.length <= remaining, "Native record inputs exceed 4 MiB: " + path);
			bytesRead += content.length;
			return content;
		}
	}

	/**
	 * Strictly decodes UTF-8 with a path-only error diagnostic.
	 *
	 * @param content bounded input bytes
	 * @param path source path for diagnostics
	 * @return decoded text
	 * @throws IOException if UTF-8 is invalid
	 */
	private static String decode(byte[] content, Path path) throws IOException
	{
		try
		{
			return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
		}
		catch (java.nio.charset.CharacterCodingException failure)
		{
			throw new IOException("Invalid UTF-8 in record: " + path, failure);
		}
	}

	/**
	 * Reports a record constraint violation.
	 *
	 * @param condition required constraint
	 * @param message path or index diagnostic
	 */
	private static void check(boolean condition, String message)
	{
		ReleaseManifestSchema.require(condition, message);
	}
}
