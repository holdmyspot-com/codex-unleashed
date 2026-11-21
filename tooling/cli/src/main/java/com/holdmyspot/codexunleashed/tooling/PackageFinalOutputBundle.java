package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reads and writes the exact pre-strip Linux x64 executable inventory. */
final class PackageFinalOutputBundle
{
	private static final String TARGET = "x86_64-unknown-linux-musl";
	private static final String TAG_PREFIX = "cargo-v3-" + TARGET + "-pkg-";
	private static final String MANIFEST = "manifest.json";
	private static final String PROVENANCE = "provenance.json";
	private static final long MAX_FILE = 2L << 30;
	private static final long MAX_CONTENTS = 8L << 30;
	private static final int MAX_METADATA = 64 * 1024;
	private static final JsonMapper MAPPER = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	private PackageFinalOutputBundle()
	{
	}

	/**
	 * Captures, restores, or installs a bundle using the caller's arguments and environment.
	 *
	 * @throws IOException if inputs are invalid or any filesystem operation fails
	 */
	static void execute(String[] args, Map<String, String> environment) throws IOException
	{
		require(args.length > 0, "Expected capture, restore, or install");
		String repository = environment.get("GITHUB_REPOSITORY");
		switch (args[0])
		{
			case "capture", "restore" ->
			{
				boolean capturing = args[0].equals("capture");
				int firstBinary = capturing ? 3 : 4;
				require(args.length > firstBinary, "Expected archive, directory, and executable names");
				require(TARGET.equals(environment.get("TARGET")), "Unsupported target");
				ObjectNode identity = identity(environment.get("CACHE_TAG"), environment.get("PACKAGE_FINAL_SOURCE"),
					repository);
				List<String> names = names(Arrays.asList(args).subList(firstBinary, args.length));
				Path archive = Path.of(args[1]).toAbsolutePath();
				Path directory = Path.of(args[2]).toAbsolutePath();
				if (capturing)
					capture(archive, directory, identity, names, environment);
				else
					restore(archive, directory, identity, names, args[3]);
			}
			case "install" ->
			{
				Set<String> expected = Set.of("--restored-dir", "--release-dir", "--dependency-tag", "--source-commit");
				Map<String, String> options = new HashMap<>();
				int i = 1;
				while (i < args.length && !args[i].equals("--binaries"))
				{
					require(expected.contains(args[i]) && i + 1 < args.length &&
						options.putIfAbsent(args[i], args[i + 1]) == null, "Invalid install options");
					i += 2;
				}
				require(options.keySet().equals(expected) && i < args.length, "Missing install options");
				ObjectNode identity = identity(options.get("--dependency-tag"), options.get("--source-commit"), repository);
				install(Path.of(options.get("--restored-dir")), Path.of(options.get("--release-dir")).toAbsolutePath(),
					identity, names(Arrays.asList(args).subList(i + 1, args.length)));
			}
			default -> throw new IOException("Expected capture, restore, or install");
		}
	}

	private static ObjectNode identity(String tag, String commit, String repository) throws IOException
	{
		require(matches(tag, TAG_PREFIX + "[0-9a-f]{64}"), "Invalid dependency tag");
		require(matches(commit, "[0-9a-f]{40}"), "Invalid source commit");
		require(matches(repository, "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"), "Invalid repository");
		ObjectNode result = MAPPER.createObjectNode();
		result.put("format_version", 1);
		result.put("target", TARGET);
		result.put("dependency_tag", tag);
		result.put("cache_tag", TAG_PREFIX + "final-v1-" + tag.substring(TAG_PREFIX.length()));
		result.put("source_commit", commit);
		result.put("producer_repository", repository);
		return result;
	}

	private static List<String> names(List<String> names) throws IOException
	{
		require(names.size() >= 2 && names.size() <= 32 && names.contains("bwrap") &&
			new HashSet<>(names).size() == names.size(), "Invalid executable list");
		for (String name : names)
			require(matches(name, "[a-z0-9][a-z0-9-]*"), "Invalid executable name");
		return names.stream().sorted().toList();
	}

	private static ObjectNode verifyMetadata(byte[] bytes, ObjectNode identity, List<String> names, boolean staged)
		throws IOException
	{
		JsonNode metadata = MAPPER.readTree(bytes);
		Set<String> fields = new HashSet<>(identity.propertyNames());
		fields.addAll(Set.of("producer_run_id", "producer_run_attempt", "files"));
		if (staged)
			fields.add("oci_digest");
		require(metadata != null && metadata.isObject() && metadata.propertyNames().equals(fields),
			"Unexpected bundle metadata");
		for (String name : identity.propertyNames())
			require(identity.get(name).equals(metadata.get(name)), "Bundle identity mismatch");
		require(matches(metadata.path("producer_run_id").stringValue(), "[1-9][0-9]{0,19}") &&
			matches(metadata.path("producer_run_attempt").stringValue(), "[1-9][0-9]{0,19}"), "Invalid producer run");
		if (staged)
			require(matches(metadata.path("oci_digest").stringValue(), "sha256:[0-9a-f]{64}"), "Invalid OCI digest");
		JsonNode files = metadata.path("files");
		require(files.isObject() && files.propertyNames().equals(Set.copyOf(names)), "Unexpected executable inventory");
		long total = 0;
		for (String name : names)
		{
			JsonNode file = files.path(name);
			JsonNode mode = file.path("mode");
			JsonNode size = file.path("size");
			require(file.isObject() && file.propertyNames().equals(Set.of("mode", "size", "sha256")) &&
				mode.isIntegralNumber() && mode.canConvertToInt() && allowedMode(mode.intValue()) &&
				size.isIntegralNumber() && size.canConvertToLong() && allowedSize(size.longValue()) &&
				matches(file.path("sha256").stringValue(), "[0-9a-f]{64}"), "Invalid executable metadata");
			total += size.longValue();
		}
		require(total <= MAX_CONTENTS, "Bundle contents exceed size limit");
		return (ObjectNode) metadata;
	}

	private static void capture(Path archive, Path release, ObjectNode identity, List<String> names,
		Map<String, String> environment) throws IOException
	{
		Path scratch = Files.createTempDirectory(archive.getParent(), "capture.");
		try
		{
			ObjectNode metadata = identity.deepCopy();
			metadata.put("producer_run_id", environment.get("GITHUB_RUN_ID"));
			metadata.put("producer_run_attempt", environment.get("GITHUB_RUN_ATTEMPT"));
			ObjectNode files = metadata.putObject("files");
			for (String name : names)
				files.set(name, copyExecutable(release.resolve(name), scratch.resolve(name)));
			byte[] manifest = MAPPER.writeValueAsBytes(metadata);
			require(manifest.length <= MAX_METADATA, "Manifest exceeds size limit");
			verifyMetadata(manifest, identity, names, false);
			Path staged = scratch.resolve("bundle.tar.gz");
			GzipParameters parameters = new GzipParameters();
			parameters.setCompressionLevel(1);
			try (OutputStream file = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW);
				GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(file, parameters);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip))
			{
				putMember(tar, MANIFEST, manifest.length, 0644);
				tar.write(manifest);
				tar.closeArchiveEntry();
				for (String name : names)
				{
					JsonNode details = files.get(name);
					putMember(tar, "bin/" + name, details.path("size").longValue(), details.path("mode").intValue());
					Files.copy(scratch.resolve(name), tar);
					tar.closeArchiveEntry();
				}
			}
			atomicMove(staged, archive);
		}
		finally
		{
			deleteScratch(scratch);
		}
	}

	private static void restore(Path archive, Path output, ObjectNode identity, List<String> names, String digest)
		throws IOException
	{
		require(matches(digest, "sha256:[0-9a-f]{64}"), "Invalid OCI digest");
		Path scratch = Files.createTempDirectory(output.getParent(), "restore.");
		try
		{
			Path bin = Files.createDirectory(scratch.resolve("bin"));
			ObjectNode metadata;
			try (InputStream file = Files.newInputStream(archive, NOFOLLOW_LINKS);
				GZIPInputStream gzip = new GZIPInputStream(file, 64 * 1024);
				TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
			{
				TarArchiveEntry member = tar.getNextEntry();
				require(regularMember(member, MANIFEST) && member.getSize() > 0 && member.getSize() <= MAX_METADATA,
					"Expected bundle manifest");
				byte[] manifest = tar.readNBytes((int) member.getSize());
				require(manifest.length == member.getSize(), "Truncated bundle manifest");
				metadata = verifyMetadata(manifest, identity, names, false);
				for (String name : names)
				{
					member = tar.getNextEntry();
					JsonNode expected = metadata.path("files").path(name);
					long size = expected.path("size").longValue();
					int mode = expected.path("mode").intValue();
					require(regularMember(member, "bin/" + name) && member.getSize() == size && member.getMode() == mode,
						"Unexpected bundle member");
					Path binary = bin.resolve(name);
					try (OutputStream destination = Files.newOutputStream(binary, StandardOpenOption.CREATE_NEW))
					{
						require(transfer(tar, destination, size).equals(expected.path("sha256").stringValue()),
							"Executable checksum mismatch");
					}
					Files.setAttribute(binary, "unix:mode", mode, NOFOLLOW_LINKS);
				}
				require(tar.getNextEntry() == null, "Unexpected bundle member");
			}
			metadata.put("oci_digest", digest);
			Files.write(scratch.resolve(PROVENANCE), MAPPER.writeValueAsBytes(metadata), StandardOpenOption.CREATE_NEW);
			atomicMove(scratch, output);
		}
		finally
		{
			deleteScratch(scratch);
		}
	}

	private static void install(Path restored, Path output, ObjectNode identity, List<String> names) throws IOException
	{
		require(directoryNames(restored).equals(Set.of("bin", PROVENANCE)), "Unexpected restore directory");
		Path bin = restored.resolve("bin");
		Path provenance = restored.resolve(PROVENANCE);
		require(directoryNames(bin).equals(Set.copyOf(names)) && Files.isRegularFile(provenance, NOFOLLOW_LINKS),
			"Unexpected staged inventory");
		ObjectNode metadata;
		try (InputStream file = Files.newInputStream(provenance, NOFOLLOW_LINKS))
		{
			byte[] bytes = file.readNBytes(MAX_METADATA + 1);
			require(bytes.length > 0 && bytes.length <= MAX_METADATA, "Invalid provenance size");
			metadata = verifyMetadata(bytes, identity, names, true);
		}
		Path scratch = Files.createTempDirectory(output.getParent(), "install.");
		try
		{
			for (String name : names)
			{
				ObjectNode details = copyExecutable(bin.resolve(name), scratch.resolve(name));
				JsonNode expected = metadata.path("files").path(name);
				require(details.path("mode").intValue() == expected.path("mode").intValue() &&
					details.path("size").longValue() == expected.path("size").longValue() &&
					details.path("sha256").equals(expected.path("sha256")), "Staged executable changed");
			}
			atomicMove(scratch, output);
		}
		finally
		{
			deleteScratch(scratch);
		}
	}

	private static ObjectNode copyExecutable(Path source, Path destination) throws IOException
	{
		require(Files.isRegularFile(source, NOFOLLOW_LINKS), "Expected a regular executable");
		int mode = (Integer) Files.getAttribute(source, "unix:mode", NOFOLLOW_LINKS) & 07777;
		long size = Files.size(source);
		require(allowedMode(mode) && allowedSize(size), "Invalid executable file");
		String sha;
		try (InputStream input = Files.newInputStream(source, NOFOLLOW_LINKS);
			OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))
		{
			sha = transfer(input, output, size);
		}
		Files.setAttribute(destination, "unix:mode", mode, NOFOLLOW_LINKS);
		return MAPPER.createObjectNode().put("mode", mode).put("size", size).put("sha256", sha);
	}

	private static String transfer(InputStream input, OutputStream output, long size) throws IOException
	{
		byte[] header = input.readNBytes(64);
		require(header.length == 64 && Arrays.equals(header, 0, 7,
			new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1}, 0, 7), "Expected little-endian ELF64 executable");
		ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int type = elf.getShort(16);
		require((type == 2 || type == 3) && elf.getShort(18) == 62 && elf.getInt(20) == 1, "Expected Linux x64 ELF");
		MessageDigest hash;
		try
		{
			hash = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
		hash.update(header);
		output.write(header);
		byte[] block = new byte[256 * 1024];
		long remaining = size - header.length;
		while (remaining > 0)
		{
			int count = input.readNBytes(block, 0, (int) Math.min(remaining, block.length));
			require(count > 0, "Truncated executable");
			hash.update(block, 0, count);
			output.write(block, 0, count);
			remaining -= count;
		}
		require(input.read() == -1, "Unexpected data after executable");
		return HexFormat.of().formatHex(hash.digest());
	}

	private static boolean regularMember(TarArchiveEntry member, String name)
	{
		return member != null && member.isCheckSumOK() && member.getLinkFlag() == TarConstants.LF_NORMAL &&
			!member.isSparse() && member.getExtraPaxHeaders().isEmpty() && member.getName().equals(name);
	}

	private static void putMember(TarArchiveOutputStream tar, String name, long size, int mode) throws IOException
	{
		TarArchiveEntry item = new TarArchiveEntry(name, TarConstants.LF_NORMAL);
		item.setSize(size);
		item.setMode(mode);
		tar.putArchiveEntry(item);
	}

	private static Set<String> directoryNames(Path directory) throws IOException
	{
		require(Files.isDirectory(directory, NOFOLLOW_LINKS), "Expected a directory");
		try (var paths = Files.newDirectoryStream(directory))
		{
			Set<String> result = new HashSet<>();
			for (Path path : paths)
			{
				require(result.size() < 32, "Staged directory exceeds inventory limit");
				result.add(path.getFileName().toString());
			}
			return result;
		}
	}

	private static void atomicMove(Path source, Path target) throws IOException
	{
		require(Files.notExists(target, NOFOLLOW_LINKS), "Destination exists");
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void deleteScratch(Path scratch) throws IOException
	{
		if (Files.notExists(scratch, NOFOLLOW_LINKS))
			return;
		List<Path> paths = new ArrayList<>();
		try (var tree = Files.walk(scratch))
		{
			tree.forEach(paths::add);
		}
		for (Path path : paths.reversed())
			Files.delete(path);
	}

	private static boolean allowedSize(long size)
	{
		return size >= 64 && size <= MAX_FILE;
	}

	private static boolean allowedMode(int mode)
	{
		return mode >= 0 && mode <= 0777 && (mode & 0100) != 0 && (mode & 0022) == 0;
	}

	private static boolean matches(String value, String regex)
	{
		return value != null && value.matches(regex);
	}

	private static void require(boolean valid, String message) throws IOException
	{
		if (!valid)
			throw new IOException(message);
	}
}
