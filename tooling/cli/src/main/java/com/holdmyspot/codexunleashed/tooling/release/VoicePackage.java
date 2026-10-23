package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds a prepared public voice runtime to a canonical primary Codex package before archiving.
 */
final class VoicePackage
{
	private static final List<String> PLUGINS = List.of("app", "audioconvert", "audioresample", "coreelements",
		"opus", "rtp", "rtpmanager");
	private static final List<String> LEGAL_FILES = List.of("NOTICE.md", "sources.json", "licenses/LGPL-2.1.txt",
		"licenses/Opus.txt", "licenses/PCRE2.md", "licenses/libffi.txt", "licenses/proxy-libintl.txt",
		"licenses/sljit.txt", "licenses/zlib.txt");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
	/**
	 * Bounds whole-document parsing to the upstream runtime receipt's one MiB limit.
	 */
	private static final int DOCUMENT_LIMIT = 1024 * 1024;

	/**
	 * Prevents construction.
	 */
	private VoicePackage()
	{
	}

	/**
	 * Indicates whether a build identity is a full lowercase Git object name.
	 *
	 * @param buildCommit the supplied build identity
	 * @return true for exactly 40 lowercase hexadecimal characters
	 */
	static boolean isBuildCommit(String buildCommit)
	{
		return COMMIT.matcher(buildCommit).matches();
	}

	/**
	 * Copies only receipt-verified runtime inputs, the helper, and upstream legal materials.
	 * On failure, removes only the voice directory exclusively created by this operation.
	 *
	 * @param directory   the validated canonical package directory
	 * @param workspace   the source checkout containing third_party/voice legal materials
	 * @param release     the prepared release directory containing runtime and the helper
	 * @param target      the application target
	 * @param version     the exact resolved application version, including its vendor suffix
	 * @param buildCommit the full lowercase source commit matching the public runtime receipt
	 * @throws IOException if package metadata or inputs are invalid, copying fails, or cleanup fails
	 */
	static void assemble(Path directory, Path workspace, Path release, PackageTarget target, String version,
		String buildCommit) throws IOException
	{
		if (!isBuildCommit(buildCommit))
			throw new IOException("Voice build commit must contain exactly 40 lowercase hexadecimal characters");
		directory = directory.toAbsolutePath().normalize();
		release = release.toAbsolutePath().normalize();
		requireDirectory(directory);
		requireDirectory(release);
		if (release.startsWith(directory) || directory.startsWith(release))
			throw new IOException("Voice release inputs and package output must be outside one another");
		String entrypoint = "bin/codex" + target.executableSuffix();
		validatePackage(directory, target, version, entrypoint);
		Path destination = directory.resolve("codex-resources/voice");
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Package already contains voice resources: " + destination);
		Path runtime = release.resolve("runtime");
		requireDirectory(runtime);
		Path legal = workspace.resolve("third_party/voice").toAbsolutePath().normalize();
		requireDirectory(legal);
		String voiceTarget = target.triple().replace("linux-musl", "linux-gnu");
		Map<String, String> runtimeFiles = runtimeFiles(runtime, legal, target, voiceTarget, buildCommit);
		String helperName = "bin/codex-voice-host" + target.executableSuffix();
		Path helper = release.resolve("codex-voice-host" + target.executableSuffix());
		requireFile(helper);
		if (!target.isWindows())
			ExecutableInputs.resolve(helper, "Voice helper");
		String helperDigest = Sha256.digest(helper);
		List<String> legalNames = new ArrayList<>(LEGAL_FILES);
		if (target.isWindows())
			legalNames.add("windows-crt.json");
		Map<String, String> legalFiles = new LinkedHashMap<>();
		for (String name : legalNames)
		{
			Path source = legal.resolve(name);
			requireFile(source);
			legalFiles.put(name, Sha256.digest(source));
		}

		Files.createDirectory(destination);
		try
		{
			Map<String, String> hashes = new LinkedHashMap<>();
			hashes.put(entrypoint, Sha256.digest(directory.resolve(entrypoint)));
			copyVerified(helper, destination.resolve(helperName), helperDigest);
			hashes.put("codex-resources/voice/" + helperName, helperDigest);
			for (Map.Entry<String, String> entry : runtimeFiles.entrySet())
			{
				copyVerified(runtime.resolve(entry.getKey()), destination.resolve(entry.getKey()), entry.getValue());
				hashes.put("codex-resources/voice/" + entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, String> entry : legalFiles.entrySet())
			{
				copyVerified(legal.resolve(entry.getKey()), destination.resolve(entry.getKey()), entry.getValue());
				hashes.put("codex-resources/voice/" + entry.getKey(), entry.getValue());
			}
			Map<String, Object> manifest = new LinkedHashMap<>();
			manifest.put("schemaVersion", 1);
			manifest.put("buildCommit", buildCommit);
			manifest.put("appTarget", target.triple());
			manifest.put("voiceTarget", voiceTarget);
			manifest.put("appVersion", version);
			manifest.put("sha256", hashes);
			Files.writeString(destination.resolve("manifest.json"), ArtifactJson.formatPreservingKeyOrder(manifest));
		}
		catch (IOException | RuntimeException failure)
		{
			try
			{
				deleteVoice(destination);
			}
			catch (IOException cleanupFailure)
			{
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Rejects noncanonical metadata and any links or special entries in the base package.
	 *
	 * @param directory  the base package
	 * @param target     the required app target
	 * @param version    the resolved version
	 * @param entrypoint the primary executable's package-relative name
	 * @throws IOException if the metadata or base tree is invalid
	 */
	private static void validatePackage(Path directory, PackageTarget target, String version, String entrypoint)
		throws IOException
	{
		JsonNode metadata = readDocument(directory.resolve("codex-package.json"));
		requireSchema(metadata, "layoutVersion");
		Map<String, String> fields = Map.of("version", version, "target", target.triple(), "variant", "codex",
			"entrypoint", entrypoint, "resourcesDir", "codex-resources", "pathDir", "codex-path");
		for (Map.Entry<String, String> field : fields.entrySet())
			if (!text(metadata, field.getKey()).equals(field.getValue()))
				throw new IOException("Invalid canonical package field: " + field.getKey());
		requireFile(directory.resolve(entrypoint));
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
					!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
				{
					throw new IOException("Package inputs must be regular files or directories without symlinks: " + path);
				}
			}
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}

	/**
	 * Validates a schema-one public receipt and its complete required inventory.
	 *
	 * @param runtime     the prepared runtime
	 * @param legal       the pinned upstream voice source directory
	 * @param target      the application target
	 * @param voiceTarget the compatible native runtime target
	 * @param buildCommit the required full source commit
	 * @return runtime-relative files and their verified digests, including the unchanged receipt
	 * @throws IOException if any receipt field, inventory path, or file digest is invalid
	 */
	private static Map<String, String> runtimeFiles(Path runtime, Path legal, PackageTarget target, String voiceTarget,
		String buildCommit) throws IOException
	{
		Path receipt = runtime.resolve("runtime.json");
		byte[] data = readDocumentBytes(receipt);
		JsonNode document = parseDocument(data, receipt);
		requireSchema(document, "schemaVersion");
		JsonNode development = document.path("developmentOnly");
		if (!development.isBoolean() || development.booleanValue() ||
			!text(document, "distribution").equals("publicRelease"))
		{
			throw new IOException("Voice runtime receipt must declare developmentOnly=false and distribution=publicRelease");
		}
		if (!text(document, "target").equals(voiceTarget))
			throw new IOException("Voice runtime target must match " + voiceTarget);
		if (!text(document, "sourceCommit").equals(buildCommit))
			throw new IOException("Voice runtime sourceCommit must match build commit " + buildCommit);
		requireFile(legal.resolve("sources.json"));
		if (!text(document, "sourceManifestSha256").equals(Sha256.digest(legal.resolve("sources.json"))))
			throw new IOException("Voice runtime sourceManifestSha256 does not match upstream sources.json");
		JsonNode libraries = document.path("libraries");
		if (!libraries.isArray() || libraries.isEmpty() || libraries.size() > 128)
			throw new IOException("Voice runtime libraries must contain between 1 and 128 records");
		String pattern;
		String pluginPattern;
		String required;
		switch (target)
		{
			case MACOS_ARM, MACOS_X86 ->
			{
				pattern = "(?:lib|plugins)/[A-Za-z0-9_+.-]+\\.dylib";
				pluginPattern = "plugins/libgst%s.dylib";
				required = "lib/libgio-2.0.0.dylib";
			}
			case WINDOWS_ARM, WINDOWS_X86 ->
			{
				pattern = "bin/[A-Za-z0-9_+.-]+\\.[dD][lL][lL]";
				pluginPattern = "bin/gst%s.dll";
				required = "bin/gio-2.0-0.dll";
			}
			default ->
			{
				pattern = "lib/(?:gstreamer-1\\.0/)?[A-Za-z0-9_+.-]+\\.so(?:\\.[0-9]+)*";
				pluginPattern = "lib/gstreamer-1.0/libgst%s.so";
				required = "lib/libgio-2.0.so.0";
			}
		}
		Pattern libraryPath = Pattern.compile(pattern);
		Map<String, String> files = new LinkedHashMap<>();
		Set<String> names = new HashSet<>();
		for (JsonNode record : libraries)
		{
			String name = text(record, "path");
			if (!libraryPath.matcher(name).matches() || !names.add(name.toLowerCase(Locale.ROOT)))
				throw new IOException("Invalid or colliding voice runtime path: " + name);
			Path file = runtime.resolve(name);
			if (!file.normalize().startsWith(runtime))
				throw new IOException("Voice runtime path escapes its input: " + name);
			requireFile(file);
			String digest = text(record, "sha256");
			if (!DIGEST.matcher(digest).matches() || !Sha256.digest(file).equals(digest))
				throw new IOException("Voice runtime file digest mismatch: " + name);
			files.put(name, digest);
		}
		List<String> expectedPlugins = new ArrayList<>();
		for (String plugin : PLUGINS)
			expectedPlugins.add(pluginPattern.formatted(plugin));
		JsonNode plugins = document.path("plugins");
		if (!plugins.isArray() || plugins.size() != expectedPlugins.size())
			throw new IOException("Voice runtime must include exactly the selected plugins");
		List<String> actualPlugins = new ArrayList<>();
		for (JsonNode plugin : plugins)
		{
			if (!plugin.isString())
				throw new IOException("Voice runtime plugin names must be strings");
			actualPlugins.add(plugin.stringValue());
		}
		if (!actualPlugins.stream().sorted().toList().equals(expectedPlugins.stream().sorted().toList()) ||
			!files.keySet().containsAll(expectedPlugins) || !files.containsKey(required))
		{
			throw new IOException("Voice runtime is missing required libraries or selected plugins");
		}
		files.put("runtime.json", digestBytes(data));
		return files;
	}

	/**
	 * Requires schema one, using layoutVersion for app metadata and schemaVersion for runtime receipts.
	 *
	 * @param document the parsed object
	 * @param field    the required schema field
	 * @throws IOException if the document has an invalid schema number
	 */
	private static void requireSchema(JsonNode document, String field) throws IOException
	{
		JsonNode schema = document.path(field);
		if (!schema.isIntegralNumber() || !schema.bigIntegerValue().equals(BigInteger.ONE))
			throw new IOException("Voice package input requires " + field + "=1");
	}

	/**
	 * Reads one bounded JSON object.
	 *
	 * @param file document path
	 * @return parsed object
	 * @throws IOException if reading or JSON parsing fails
	 */
	private static JsonNode readDocument(Path file) throws IOException
	{
		return parseDocument(readDocumentBytes(file), file);
	}

	/**
	 * Reads at most one MiB of receipt or package metadata without following input symlinks.
	 *
	 * @param file document path
	 * @return raw document bytes
	 * @throws IOException if the input is unsafe, unreadable, or oversized
	 */
	private static byte[] readDocumentBytes(Path file) throws IOException
	{
		requireFile(file);
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] data = input.readNBytes(DOCUMENT_LIMIT + 1);
			if (data.length > DOCUMENT_LIMIT)
				throw new IOException("Voice input JSON exceeds one MiB: " + file);
			return data;
		}
	}

	/**
	 * Parses an object with strict duplicate-key detection, retaining scalar types.
	 *
	 * @param data raw document bytes
	 * @param file diagnostic path
	 * @return parsed JSON object
	 * @throws IOException if JSON syntax or the root shape is invalid
	 */
	private static JsonNode parseDocument(byte[] data, Path file) throws IOException
	{
		try
		{
			JsonNode document = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().
				readTree(data);
			if (document == null || !document.isObject())
				throw new IOException("Voice input JSON must be an object: " + file);
			return document;
		}
		catch (JacksonException failure)
		{
			throw new IOException("Invalid voice input JSON: " + file, failure);
		}
	}

	/**
	 * Requires a string field without coercing missing or differently typed values.
	 *
	 * @param document containing object
	 * @param field    required field
	 * @return exact string value
	 * @throws IOException if the field is absent or not a string
	 */
	private static String text(JsonNode document, String field) throws IOException
	{
		JsonNode value = document.path(field);
		if (!value.isString())
			throw new IOException("Voice input field must be a string: " + field);
		return value.stringValue();
	}

	/**
	 * Requires a directory with no symbolic links in its path.
	 *
	 * @param directory input directory
	 * @throws IOException if the path is linked or not a directory
	 */
	private static void requireDirectory(Path directory) throws IOException
	{
		rejectLinks(directory);
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Voice input must be a directory: " + directory);
	}

	/**
	 * Requires a regular file with no symbolic links in its path.
	 *
	 * @param file input file
	 * @throws IOException if the path is linked or not a regular file
	 */
	private static void requireFile(Path file) throws IOException
	{
		rejectLinks(file);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Voice input must be a regular file: " + file);
	}

	/**
	 * Rejects symlinks in the file and every containing directory.
	 *
	 * @param path input path
	 * @throws IOException if a symlink is present
	 */
	private static void rejectLinks(Path path) throws IOException
	{
		for (Path part = path.toAbsolutePath(); part != null; part = part.getParent())
			if (Files.isSymbolicLink(part))
				throw new IOException("Voice input path must not contain symlinks: " + part);
	}

	/**
	 * Copies verified bytes and original attributes to a fresh destination and verifies the copied bytes.
	 *
	 * @param source      validated input
	 * @param destination fresh package file
	 * @param expected    verified lowercase SHA-256
	 * @throws IOException if an input becomes unsafe, copying fails, or bytes change
	 */
	private static void copyVerified(Path source, Path destination, String expected) throws IOException
	{
		requireFile(source);
		Files.createDirectories(destination.getParent());
		Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !Sha256.digest(destination).equals(expected))
			throw new IOException("Voice input changed during copying: " + source);
	}

	/**
	 * Hashes the exact bounded receipt bytes that were parsed, so later receipt changes fail copy verification.
	 *
	 * @param data parsed receipt bytes
	 * @return lowercase SHA-256
	 */
	private static String digestBytes(byte[] data)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IllegalStateException("Java platform does not supply SHA-256", failure);
		}
	}

	/**
	 * Removes only the exclusively created voice output without following links.
	 *
	 * @param destination owned voice output
	 * @throws IOException if removal fails
	 */
	private static void deleteVoice(Path destination) throws IOException
	{
		try (Stream<Path> paths = Files.walk(destination))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
		catch (UncheckedIOException failure)
		{
			throw failure.getCause();
		}
	}
}
