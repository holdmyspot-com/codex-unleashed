package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Hands the pre-strip Intel macOS release executables and their packed dSYM bundles from the compile host to the
 * packaging host.
 * <p>
 * {@code capture} writes an uncompressed tar whose first member, {@code manifest.json}, binds the target, the patched
 * source commit, the selected executables, and every file's mode, size and SHA-256. {@code install} verifies each
 * member against that manifest and the caller's expected identity before it publishes a new Cargo release directory
 * with one atomic rename. The class is stateless; concurrent invocations must use distinct destinations.
 */
public final class CompiledOutputHandoff
{
	/** Shared CLI command name. */
	public static final String NAME = "compiled-output-handoff";
	/** Targets whose release executables are compiled on a different host from the one that packages them. */
	private static final Set<String> TARGETS = Set.of("x86_64-apple-darwin");
	/** First archive member, which describes every later member. */
	private static final String MANIFEST = "manifest.json";
	/** Maximum files in one handoff. */
	private static final int MAX_FILES = 256;
	/** Maximum manifest size, in bytes. */
	private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
	/** Maximum size of one file, in bytes. */
	private static final long MAX_FILE_BYTES = 4L << 30;
	/** Maximum combined size of all files, in bytes. */
	private static final long MAX_CONTENT_BYTES = 16L << 30;
	/** Longest member name that a plain ustar header stores without an extension header. */
	private static final int MAX_NAME_BYTES = 100;
	/** One safe relative path component. */
	private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_+-][A-Za-z0-9._+-]*");
	/** One Cargo binary target name. */
	private static final Pattern BINARY = Pattern.compile("[a-z0-9][a-z0-9-]*");
	/** One full-length lowercase Git commit. */
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	/** One lowercase SHA-256 digest. */
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
	/** Mach-O 64-bit header prefix for an x86_64 executable: magic, CPU type, CPU subtype, file type. */
	private static final byte[] MACH_O_X86_64 = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0x07, 0x00, 0x00,
		0x01};
	/** Mach-O file type of a linked executable. */
	private static final byte[] MACH_O_EXECUTE = {0x02, 0x00, 0x00, 0x00};
	/** Usage text for syntax errors and help. */
	private static final String USAGE = "Usage: " + NAME + " capture --release-dir DIR --target TARGET " +
		"--source-commit SHA --output FILE --binaries NAME...\n       " + NAME + " install --archive FILE " +
		"--release-dir DIR --target TARGET --source-commit SHA --binaries NAME...";

	/**
	 * One file in the handoff.
	 *
	 * @param path       slash-separated path relative to the Cargo release directory
	 * @param source     file to read during capture, or {@code null} when the member comes from an archive
	 * @param executable whether the file is a selected executable rather than part of a dSYM bundle
	 * @param mode       permission bits
	 * @param size       size in bytes
	 * @param sha256     lowercase SHA-256 digest of the content
	 */
	private record Member(String path, Path source, boolean executable, int mode, long size, String sha256)
	{
	}

	/**
	 * The caller's expected handoff identity.
	 *
	 * @param target   Rust target triple
	 * @param commit   patched source commit
	 * @param binaries selected executable names in ascending order
	 */
	private record Identity(String target, String commit, List<String> binaries)
	{
	}

	/**
	 * Prevents construction.
	 */
	private CompiledOutputHandoff()
	{
	}

	/**
	 * Runs {@code capture} or {@code install}.
	 *
	 * @param scope     borrowed JSON services
	 * @param arguments command arguments without the command name
	 * @param out       completion output
	 * @param err       diagnostics
	 * @return zero on success, one if the inputs fail verification or a filesystem operation fails, or two for invalid
	 * 	arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (List.of(arguments).contains("--help") || List.of(arguments).contains("-h"))
		{
			out.println(USAGE);
			return 0;
		}

		String operation;
		Map<String, String> options;
		Identity identity;
		try
		{
			if (arguments.length == 0 || !Set.of("capture", "install").contains(arguments[0]))
				throw new IllegalArgumentException("Expected capture or install");
			operation = arguments[0];
			Set<String> expected = Set.of("--release-dir", "--target", "--source-commit", "--output");
			if (operation.equals("install"))
				expected = Set.of("--archive", "--release-dir", "--target", "--source-commit");
			int index = 1;
			options = new HashMap<>();
			while (index < arguments.length && !arguments[index].equals("--binaries"))
			{
				String name = arguments[index];
				if (!expected.contains(name) || index + 1 >= arguments.length || arguments[index + 1].isEmpty() ||
					options.putIfAbsent(name, arguments[index + 1]) != null)
					throw new IllegalArgumentException("Invalid or duplicate option: " + name);
				index += 2;
			}
			if (!options.keySet().equals(expected) || index >= arguments.length)
				throw new IllegalArgumentException("Required options: " + String.join(", ", expected) + ", --binaries");
			identity = identity(options.get("--target"), options.get("--source-commit"),
				Arrays.asList(arguments).subList(index + 1, arguments.length));
		}
		catch (IllegalArgumentException failure)
		{
			err.println(NAME + ": " + failure.getMessage());
			err.println(USAGE);
			return 2;
		}

		try
		{
			Path release = Path.of(options.get("--release-dir")).toAbsolutePath().normalize();
			if (operation.equals("capture"))
			{
				Path output = Path.of(options.get("--output")).toAbsolutePath().normalize();
				capture(scope, release, identity, output);
				out.println("Captured " + identity.binaries().size() + " compiled executables for " + identity.target());
			}
			else
			{
				Path archive = Path.of(options.get("--archive")).toAbsolutePath().normalize();
				install(scope, archive, release, identity);
				out.println("Installed " + identity.binaries().size() + " compiled executables for " + identity.target());
			}
			return 0;
		}
		catch (IOException | JacksonException failure)
		{
			err.println(NAME + ": " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Validates the caller's expected identity.
	 *
	 * @param target   Rust target triple
	 * @param commit   patched source commit
	 * @param binaries selected executable names
	 * @return the identity with sorted executable names
	 * @throws IllegalArgumentException if a value is unsupported or malformed
	 */
	private static Identity identity(String target, String commit, List<String> binaries)
	{
		if (!TARGETS.contains(target))
			throw new IllegalArgumentException("Unsupported compiled output target: " + target);
		if (!COMMIT.matcher(commit).matches())
			throw new IllegalArgumentException("Source commit must be a full lowercase Git commit");
		if (binaries.isEmpty() || binaries.size() > 32 || new HashSet<>(binaries).size() != binaries.size())
			throw new IllegalArgumentException("Expected 1 to 32 distinct executable names");
		for (String binary : binaries)
			if (!BINARY.matcher(binary).matches())
				throw new IllegalArgumentException("Invalid executable name: " + binary);
		return new Identity(target, commit, binaries.stream().sorted().toList());
	}

	/**
	 * Writes the handoff archive for a completed Cargo release directory.
	 *
	 * @param scope    borrowed JSON services
	 * @param release  Cargo release directory containing each executable and its dSYM bundle
	 * @param identity expected handoff identity
	 * @param output   archive to create
	 * @throws IOException if an input is missing, unsafe or changes while it is read, or if writing fails
	 */
	private static void capture(ToolingScope scope, Path release, Identity identity, Path output) throws IOException
	{
		require(Files.notExists(output, NOFOLLOW_LINKS), "Handoff archive already exists: " + output);
		List<Member> members = inventory(release, identity.binaries());
		byte[] manifest = scope.getJsonMapper().writeValueAsBytes(manifest(scope, identity, members));
		require(manifest.length <= MAX_MANIFEST_BYTES, "Handoff manifest exceeds 1 MiB");

		Path staged = Files.createTempFile(output.getParent(), ".compiled-outputs.", ".tmp");
		try
		{
			try (OutputStream file = Files.newOutputStream(staged, StandardOpenOption.TRUNCATE_EXISTING);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(new BufferedOutputStream(file, 1 << 16)))
			{
				putMember(tar, MANIFEST, manifest.length, 0644);
				tar.write(manifest);
				tar.closeArchiveEntry();
				for (Member member : members)
				{
					putMember(tar, member.path(), member.size(), member.mode());
					String digest;
					try (InputStream input = Files.newInputStream(member.source(), NOFOLLOW_LINKS))
					{
						digest = transfer(input, tar, member.size(), member.executable());
					}
					require(digest.equals(member.sha256()), "File changed while it was captured: " + member.path());
					tar.closeArchiveEntry();
				}
			}
			Files.move(staged, output, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(staged);
		}
	}

	/**
	 * Lists each selected executable and every regular file of its dSYM bundle.
	 * <p>
	 * Cargo links {@code NAME.dSYM} in the release directory to the bundle under {@code deps}, so the bundle root may be
	 * a symbolic link; nothing inside the bundle may be one.
	 *
	 * @param release  Cargo release directory
	 * @param binaries selected executable names in ascending order
	 * @return members in ascending path order
	 * @throws IOException if a required file is missing or unsafe, or if reading fails
	 */
	private static List<Member> inventory(Path release, List<String> binaries) throws IOException
	{
		List<Member> members = new ArrayList<>();
		long total = 0;
		for (String binary : binaries)
		{
			members.add(describe(release.resolve(binary), binary, true));
			Path bundle = release.resolve(binary + ".dSYM");
			require(Files.isDirectory(bundle), "Missing packed dSYM bundle: " + bundle);
			Path root = bundle.toRealPath();
			List<Path> files = new ArrayList<>();
			try (Stream<Path> tree = Files.walk(root))
			{
				for (Path path : (Iterable<Path>) tree::iterator)
				{
					require(!Files.isSymbolicLink(path), "Symbolic link inside dSYM bundle: " + path);
					if (Files.isDirectory(path, NOFOLLOW_LINKS))
						continue;
					require(Files.isRegularFile(path, NOFOLLOW_LINKS), "Unsupported file type in dSYM bundle: " + path);
					files.add(path);
					require(members.size() + files.size() <= MAX_FILES, "Handoff exceeds " + MAX_FILES + " files");
				}
			}
			require(!files.isEmpty(), "Empty dSYM bundle: " + bundle);
			for (Path file : files)
			{
				List<String> components = new ArrayList<>(List.of(binary + ".dSYM"));
				for (Path component : root.relativize(file))
					components.add(component.toString());
				members.add(describe(file, String.join("/", components), false));
			}
		}
		for (Member member : members)
		{
			validatePath(member.path(), binaries);
			total += member.size();
		}
		require(total <= MAX_CONTENT_BYTES, "Handoff contents exceed 16 GiB");
		members.sort(Comparator.comparing(Member::path));
		return members;
	}

	/**
	 * Reads one file's mode, size and digest.
	 *
	 * @param file       regular file
	 * @param path       relative member path
	 * @param executable whether the file must be an x86_64 Mach-O executable
	 * @return the file's member description
	 * @throws IOException if the file is not a safe regular file, or if reading fails
	 */
	private static Member describe(Path file, String path, boolean executable) throws IOException
	{
		require(Files.isRegularFile(file, NOFOLLOW_LINKS), "Expected a regular file: " + file);
		int mode = (Integer) Files.getAttribute(file, "unix:mode", NOFOLLOW_LINKS) & 07777;
		long size = Files.size(file);
		require(allowedMode(mode, executable) && size <= MAX_FILE_BYTES, "Unsupported file mode or size: " + file);
		String digest;
		try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS))
		{
			digest = transfer(input, OutputStream.nullOutputStream(), size, executable);
		}
		return new Member(path, file, executable, mode, size, digest);
	}

	/**
	 * Builds the manifest that binds the identity to the member inventory.
	 *
	 * @param scope    borrowed JSON services
	 * @param identity handoff identity
	 * @param members  members in archive order
	 * @return manifest document
	 */
	private static ObjectNode manifest(ToolingScope scope, Identity identity, List<Member> members)
	{
		ObjectNode manifest = scope.getJsonMapper().createObjectNode();
		manifest.put("format_version", 1);
		manifest.put("target", identity.target());
		manifest.put("source_commit", identity.commit());
		ArrayNode binaries = manifest.putArray("binaries");
		for (String binary : identity.binaries())
			binaries.add(binary);
		ArrayNode files = manifest.putArray("files");
		for (Member member : members)
		{
			files.addObject().put("path", member.path()).put("mode", member.mode()).put("size", member.size()).
				put("sha256", member.sha256());
		}
		return manifest;
	}

	/**
	 * Verifies a handoff archive and publishes its files as a new Cargo release directory.
	 *
	 * @param scope    borrowed JSON services
	 * @param archive  handoff archive
	 * @param release  Cargo release directory to create
	 * @param identity expected handoff identity
	 * @throws IOException if the archive differs from its manifest or the expected identity, or if a filesystem
	 *                     operation fails
	 */
	private static void install(ToolingScope scope, Path archive, Path release, Identity identity) throws IOException
	{
		require(Files.notExists(release, NOFOLLOW_LINKS), "Release directory already exists: " + release);
		require(Files.isRegularFile(archive, NOFOLLOW_LINKS), "Handoff archive must be a regular file: " + archive);

		Path scratch = Files.createTempDirectory(release.getParent(), ".compiled-outputs.");
		try
		{
			try (InputStream file = Files.newInputStream(archive, NOFOLLOW_LINKS);
				TarArchiveInputStream tar = new TarArchiveInputStream(new BufferedInputStream(file, 1 << 16)))
			{
				TarArchiveEntry entry = tar.getNextEntry();
				require(regularMember(entry, MANIFEST) && entry.getSize() <= MAX_MANIFEST_BYTES,
					"Handoff archive must start with its manifest");
				byte[] bytes = tar.readNBytes((int) entry.getSize());
				require(bytes.length == entry.getSize(), "Truncated handoff manifest");
				JsonNode manifest = scope.getJsonMapper().reader().
					with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).
					with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).
					readTree(new String(bytes, StandardCharsets.UTF_8));
				for (Member member : members(manifest, identity))
				{
					entry = tar.getNextEntry();
					require(regularMember(entry, member.path()) && entry.getSize() == member.size() &&
						(entry.getMode() & 07777) == member.mode(), "Unexpected handoff member: " + member.path());
					Path destination = scratch.resolve(member.path());
					Files.createDirectories(destination.getParent());
					try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW))
					{
						String digest = transfer(tar, output, member.size(), member.executable());
						require(digest.equals(member.sha256()), "Handoff member checksum mismatch: " + member.path());
					}
					Files.setAttribute(destination, "unix:mode", member.mode(), NOFOLLOW_LINKS);
				}
				require(tar.getNextEntry() == null, "Unexpected member after the handoff inventory");
			}
			Files.move(scratch, release, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			if (Files.exists(scratch, NOFOLLOW_LINKS))
				deleteTree(scratch);
		}
	}

	/**
	 * Validates a parsed manifest against the expected identity and returns its member inventory.
	 *
	 * @param manifest parsed manifest
	 * @param identity expected handoff identity
	 * @return members in archive order
	 * @throws IOException if the manifest is malformed or records a different identity
	 */
	private static List<Member> members(JsonNode manifest, Identity identity) throws IOException
	{
		require(manifest.isObject() && new HashSet<>(manifest.propertyNames()).equals(Set.of("format_version", "target",
			"source_commit", "binaries", "files")), "Unexpected handoff manifest fields");
		JsonNode format = manifest.get("format_version");
		require(format.isIntegralNumber() && format.asLong() == 1, "Unsupported handoff format_version");
		require(text(manifest.get("target")).equals(identity.target()), "Handoff target differs from " +
			identity.target());
		require(text(manifest.get("source_commit")).equals(identity.commit()), "Handoff source commit differs from " +
			identity.commit());
		List<String> binaries = new ArrayList<>();
		for (JsonNode binary : manifest.get("binaries"))
			binaries.add(text(binary));
		require(manifest.get("binaries").isArray() && binaries.equals(identity.binaries()),
			"Handoff executables differ from " + String.join(" ", identity.binaries()));

		JsonNode files = manifest.get("files");
		require(files.isArray() && files.size() <= MAX_FILES, "Handoff requires at most " + MAX_FILES + " files");
		List<Member> members = new ArrayList<>();
		Set<String> executables = new HashSet<>();
		Set<String> bundles = new HashSet<>();
		long total = 0;
		String previous = "";
		for (JsonNode file : files)
		{
			require(file.isObject() && new HashSet<>(file.propertyNames()).equals(Set.of("path", "mode", "size",
				"sha256")), "Unexpected handoff file fields");
			String path = text(file.get("path"));
			validatePath(path, identity.binaries());
			require(path.compareTo(previous) > 0, "Handoff files must be unique and sorted by path");
			previous = path;
			boolean executable = path.indexOf('/') < 0;
			if (executable)
				executables.add(path);
			else
				bundles.add(path.substring(0, path.indexOf('/')));
			JsonNode mode = file.get("mode");
			JsonNode size = file.get("size");
			require(mode.isIntegralNumber() && mode.canConvertToInt() && allowedMode(mode.intValue(), executable) &&
				size.isIntegralNumber() && size.canConvertToLong() && size.longValue() >= 0 &&
				size.longValue() <= MAX_FILE_BYTES, "Unsupported handoff file mode or size: " + path);
			String digest = text(file.get("sha256"));
			require(DIGEST.matcher(digest).matches(), "Invalid handoff file SHA-256: " + path);
			total += size.longValue();
			members.add(new Member(path, null, executable, mode.intValue(), size.longValue(), digest));
		}
		require(total <= MAX_CONTENT_BYTES, "Handoff contents exceed 16 GiB");
		require(executables.equals(Set.copyOf(identity.binaries())), "Handoff lacks a selected executable");
		Set<String> expectedBundles = new HashSet<>();
		for (String binary : identity.binaries())
			expectedBundles.add(binary + ".dSYM");
		require(bundles.equals(expectedBundles), "Handoff lacks a selected executable's dSYM bundle");
		return members;
	}

	/**
	 * Requires a canonical relative path that names a selected executable or a file inside its dSYM bundle.
	 *
	 * @param path     slash-separated member path
	 * @param binaries selected executable names
	 * @throws IOException if the path is unsafe or names another file
	 */
	private static void validatePath(String path, List<String> binaries) throws IOException
	{
		require(path.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES, "Handoff path exceeds " +
			MAX_NAME_BYTES + " bytes: " + path);
		String[] components = path.split("/", -1);
		for (String component : components)
			require(COMPONENT.matcher(component).matches(), "Unsafe handoff path: " + path);
		boolean known;
		if (components.length == 1)
			known = binaries.contains(path);
		else
			known = components[0].endsWith(".dSYM") &&
				binaries.contains(components[0].substring(0, components[0].length() - ".dSYM".length()));
		require(known, "Handoff path does not belong to a selected executable: " + path);
	}

	/**
	 * Copies exactly {@code size} bytes while hashing them, checking the executable header first when required.
	 *
	 * @param input      source positioned at the content
	 * @param output     destination
	 * @param size       expected content size
	 * @param executable whether the content must be an x86_64 Mach-O executable
	 * @return lowercase SHA-256 digest of the copied content
	 * @throws IOException if the content is truncated, longer than expected, not the required executable type, or if
	 *                     copying fails
	 */
	private static String transfer(InputStream input, OutputStream output, long size, boolean executable)
		throws IOException
	{
		MessageDigest hash;
		try
		{
			hash = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable", failure);
		}
		long remaining = size;
		if (executable)
		{
			byte[] header = input.readNBytes(16);
			require(header.length == 16 && Arrays.equals(header, 0, 8, MACH_O_X86_64, 0, 8) &&
				Arrays.equals(header, 12, 16, MACH_O_EXECUTE, 0, 4), "Expected an x86_64 Mach-O executable");
			hash.update(header);
			output.write(header);
			remaining -= header.length;
		}
		byte[] block = new byte[1 << 16];
		while (remaining > 0)
		{
			int count = input.readNBytes(block, 0, (int) Math.min(remaining, block.length));
			require(count > 0, "Truncated handoff content");
			hash.update(block, 0, count);
			output.write(block, 0, count);
			remaining -= count;
		}
		if (!(input instanceof TarArchiveInputStream))
			require(input.read() == -1, "File grew while it was read");
		return HexFormat.of().formatHex(hash.digest());
	}

	/**
	 * Tests whether a tar entry is a plain regular file with the expected name and no extension headers.
	 *
	 * @param entry tar entry, or {@code null} at the end of the archive
	 * @param name  expected member name
	 * @return whether the entry matches
	 */
	private static boolean regularMember(TarArchiveEntry entry, String name)
	{
		return entry != null && entry.isCheckSumOK() && entry.getLinkFlag() == TarConstants.LF_NORMAL &&
			!entry.isSparse() && entry.getExtraPaxHeaders().isEmpty() && entry.getName().equals(name);
	}

	/**
	 * Starts one regular-file tar member with fixed ownership and time metadata.
	 *
	 * @param tar  archive
	 * @param name member name
	 * @param size content size
	 * @param mode permission bits
	 * @throws IOException if writing fails
	 */
	private static void putMember(TarArchiveOutputStream tar, String name, long size, int mode) throws IOException
	{
		TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_NORMAL);
		entry.setSize(size);
		entry.setMode(mode);
		tar.putArchiveEntry(entry);
	}

	/**
	 * Tests whether permission bits are safe for a handoff file.
	 *
	 * @param mode       permission bits
	 * @param executable whether the owner must be able to execute the file
	 * @return whether the mode is allowed
	 */
	private static boolean allowedMode(int mode, boolean executable)
	{
		return mode >= 0 && mode <= 0777 && (mode & 0022) == 0 && (mode & 0400) != 0 &&
			(!executable || (mode & 0100) != 0);
	}

	/**
	 * Returns a JSON string value.
	 *
	 * @param value JSON node, or {@code null}
	 * @return the string, or an empty string for any other node
	 */
	private static String text(JsonNode value)
	{
		if (value == null || !value.isString())
			return "";
		return value.stringValue();
	}

	/**
	 * Deletes a scratch tree without following symbolic links.
	 *
	 * @param root owned scratch directory
	 * @throws IOException if deletion fails
	 */
	private static void deleteTree(Path root) throws IOException
	{
		List<Path> paths;
		try (Stream<Path> tree = Files.walk(root))
		{
			paths = tree.toList();
		}
		for (Path path : paths.reversed())
			Files.delete(path);
	}

	/**
	 * Reports a failed verification.
	 *
	 * @param condition whether the requirement holds
	 * @param message   diagnostic naming the failed requirement
	 * @throws IOException if the condition is false
	 */
	private static void require(boolean condition, String message) throws IOException
	{
		if (!condition)
			throw new IOException(message);
	}
}
