package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict typed representation and parser for the native voice runtime receipt.
 *
 * @param schemaVersion receipt schema version
 * @param developmentOnly whether content is development-only
 * @param distribution distribution identity
 * @param target mapped voice target
 * @param sourceCommit upstream source commit
 * @param sourceManifestSha256 source manifest digest
 * @param inventorySha256 receipt inventory digest
 * @param libraries shipped native library declarations
 * @param plugins shipped plugin paths
 */
record VoiceRuntimeReceipt(int schemaVersion, boolean developmentOnly, String distribution, String target,
	String sourceCommit, String sourceManifestSha256, String inventorySha256, List<Library> libraries,
	List<String> plugins)
{
	private static final int MAX_BYTES = 1024 * 1024;
	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Set<String> PLUGIN_NAMES = Set.of("app", "audioconvert", "audioresample", "coreelements", "opus",
		"rtp", "rtpmanager");
	private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "developmentOnly", "distribution", "target",
		"sourceCommit", "sourceManifestSha256", "inventorySha256", "libraries", "plugins");
	private static final Set<String> LIBRARY_FIELDS = Set.of("path", "sha256", "sourcePath", "sourceSha256", "imports");

	/**
	 * One validated shipped library and its matching upstream source entry.
	 *
	 * @param path shipped runtime path
	 * @param sha256 shipped library digest
	 * @param sourcePath upstream source path
	 * @param sourceSha256 upstream source digest
	 * @param imports required native imports
	 */
	record Library(String path, String sha256, String sourcePath, String sourceSha256, List<String> imports)
	{
	}

	/**
	 * Performs read for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param path supplied path value
	 * @return the read result
	 * @throws IOException if the operation fails
	 */
	static VoiceRuntimeReceipt read(ToolingScope scope, Path path) throws IOException
	{
		byte[] bytes;
		try (InputStream input = Files.newInputStream(path))
		{
			bytes = input.readNBytes(MAX_BYTES + 1);
		}
		if (bytes.length > MAX_BYTES)
			throw new IOException(path + " exceeds the 1 MiB receipt limit");
		JsonNode root = parse(scope, bytes, path.toString());
		objectFields(root, ROOT_FIELDS, path.toString());
		int schemaVersion = integer(root, "schemaVersion", path.toString());
		boolean developmentOnly = bool(root, "developmentOnly", path.toString());
		String distribution = string(root, "distribution", path.toString());
		String target = string(root, "target", path.toString());
		String sourceCommit = string(root, "sourceCommit", path.toString());
		String sourceManifestSha256 = string(root, "sourceManifestSha256", path.toString());
		String inventorySha256 = string(root, "inventorySha256", path.toString());
		if (schemaVersion != 1 || developmentOnly || !distribution.equals("publicRelease"))
			throw new IOException(path + " must describe sealed schemaVersion 1 publicRelease content");
		if (!COMMIT.matcher(sourceCommit).matches())
			throw new IOException(path + " sourceCommit must be 40 lowercase hexadecimal characters");
		checkHash(sourceManifestSha256, path + " sourceManifestSha256");
		checkHash(inventorySha256, path + " inventorySha256");
		JsonNode libraryNodes = required(root, "libraries", path.toString());
		if (!libraryNodes.isArray() || libraryNodes.size() < 1 || libraryNodes.size() > 128)
			throw new IOException(path + " libraries must contain 1 through 128 records");
		List<Library> libraries = new ArrayList<>();
		Set<String> paths = new HashSet<>();
		Set<String> libraryPaths = new HashSet<>();
		for (JsonNode node : libraryNodes)
		{
			objectFields(node, LIBRARY_FIELDS, path + " libraries[]");
			String libraryPath = string(node, "path", path.toString());
			String sha256 = string(node, "sha256", path.toString());
			checkHash(sha256, path + " library sha256");
			String folded = libraryPath.toLowerCase(java.util.Locale.ROOT);
			if (!paths.add(folded))
				throw new IOException(path + " contains duplicate library path " + libraryPath);
			String sourcePath = optionalString(node, "sourcePath", path.toString());
			String sourceSha256 = optionalString(node, "sourceSha256", path.toString());
			Optional<List<String>> imports = optionalStrings(node.get("imports"), path + " imports");
			boolean specialWindowsRuntime = libraryPath.equals("bin/vcruntime140.dll");
			if (specialWindowsRuntime)
			{
				if (sourcePath != null || sourceSha256 != null || node.has("imports"))
					throw new IOException(path + " vcruntime140.dll must omit source fields");
			}
			else
			{
				if (sourcePath == null || sourceSha256 == null || imports.isEmpty())
					throw new IOException(path + " ordinary libraries require sourcePath, sourceSha256, and imports");
				validateRelative(sourcePath, path + " sourcePath");
				checkHash(sourceSha256, path + " sourceSha256");
			}
			validateRelative(libraryPath, path + " library path");
			libraryPaths.add(libraryPath);
			libraries.add(new Library(libraryPath, sha256, sourcePath, sourceSha256,
				imports.orElse(List.of())));
		}
		JsonNode pluginNodes = required(root, "plugins", path.toString());
		if (!pluginNodes.isArray() || pluginNodes.size() > PLUGIN_NAMES.size())
			throw new IOException(path + " plugins must contain at most seven paths");
		Set<String> expectedPlugins = pluginPaths(target);
		List<String> plugins = new ArrayList<>();
		Set<String> uniquePlugins = new HashSet<>();
		for (JsonNode plugin : pluginNodes)
		{
			if (!plugin.isString())
				throw new IOException(path + " plugin paths must be strings");
			String pluginPath = plugin.asString();
			if (!expectedPlugins.contains(pluginPath) || !uniquePlugins.add(pluginPath))
				throw new IOException(path + " has an unknown or duplicate plugin path " + pluginPath);
			plugins.add(pluginPath);
		}
		for (String expectedPlugin : expectedPlugins)
			if (!uniquePlugins.contains(expectedPlugin))
				throw new IOException(path + " runtime receipt omits plugin path " + expectedPlugin);
		for (String pluginPath : plugins)
			if (!libraryPaths.contains(pluginPath))
				throw new IOException(path + " plugin path is not listed in libraries: " + pluginPath);
		return new VoiceRuntimeReceipt(schemaVersion, developmentOnly, distribution, target, sourceCommit,
			sourceManifestSha256, inventorySha256, List.copyOf(libraries), List.copyOf(plugins));
	}

	/**
	 * Derives the canonical shipped plugin paths for the receipt's native target.
	 *
	 * @param target native target recorded by the receipt
	 * @return canonical paths for all required plugins
	 * @throws IOException if the target does not identify a supported plugin platform
	 */
	private static Set<String> pluginPaths(String target) throws IOException
	{
		String prefix;
		String suffix;
		if (target.contains("windows"))
		{
			prefix = "bin/gst";
			suffix = ".dll";
		}
		else if (target.contains("apple-darwin"))
		{
			prefix = "plugins/libgst";
			suffix = ".dylib";
		}
		else if (target.contains("linux-gnu"))
		{
			prefix = "lib/gstreamer-1.0/libgst";
			suffix = ".so";
		}
		else
			throw new IOException("runtime receipt target does not identify a supported plugin platform: " + target);
		Set<String> result = new HashSet<>();
		for (String name : PLUGIN_NAMES)
			result.add(prefix + name + suffix);
		return Set.copyOf(result);
	}

	/**
	 * Performs parse for the voice runtime.
	 *
	 * @param scope supplied scope value
	 * @param bytes supplied bytes value
	 * @param label supplied label value
	 * @return the parse result
	 * @throws IOException if the operation fails
	 */
	static JsonNode parse(ToolingScope scope, byte[] bytes, String label) throws IOException
	{
		try
		{
			JsonMapper shared = scope.getJsonMapper();
				JsonFactory strict = shared.tokenStreamFactory().rebuild().
					enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
			JsonMapper mapper = JsonMapper.builder(strict).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
			JsonNode node = mapper.readTree(bytes);
			if (node == null)
				throw new IOException(label + " must contain a JSON object");
			return node;
		}
		catch (JacksonException failure)
		{
			throw new IOException("invalid JSON in " + label + ": " + failure.getOriginalMessage(), failure);
		}
	}

	/**
	 * Performs objectFields for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param fields supplied fields value
	 * @param label supplied label value
	 * @throws IOException if the operation fails
	 */
	static void objectFields(JsonNode node, Set<String> fields, String label) throws IOException
	{
		if (!node.isObject())
			throw new IOException(label + " must be a JSON object");
		for (String name : node.propertyNames())
			if (!fields.contains(name))
				throw new IOException(label + " contains unknown field " + name);
	}

	/**
	 * Performs required for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param field supplied field value
	 * @param label supplied label value
	 * @return the required result
	 * @throws IOException if the operation fails
	 */
	static JsonNode required(JsonNode node, String field, String label) throws IOException
	{
		JsonNode value = node.get(field);
		if (value == null || value.isNull())
			throw new IOException(label + " is missing required field " + field);
		return value;
	}

	/**
	 * Performs string for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param field supplied field value
	 * @param label supplied label value
	 * @return the string result
	 * @throws IOException if the operation fails
	 */
	static String string(JsonNode node, String field, String label) throws IOException
	{
		JsonNode value = required(node, field, label);
		if (!value.isString() || value.asString().isEmpty())
			throw new IOException(label + " field " + field + " must be a nonempty string");
		return value.asString();
	}

	/**
	 * Performs optionalString for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param field supplied field value
	 * @param label supplied label value
	 * @return the optionalString result
	 * @throws IOException if the operation fails
	 */
	static String optionalString(JsonNode node, String field, String label) throws IOException
	{
		JsonNode value = node.get(field);
		if (value == null)
			return null;
		if (!value.isString() || value.asString().isEmpty())
			throw new IOException(label + " field " + field + " must be a nonempty string");
		return value.asString();
	}

	/**
	 * Performs integer for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param field supplied field value
	 * @param label supplied label value
	 * @return the integer result
	 * @throws IOException if the operation fails
	 */
	static int integer(JsonNode node, String field, String label) throws IOException
	{
		JsonNode value = required(node, field, label);
		if (!value.isIntegralNumber() || !value.canConvertToInt())
			throw new IOException(label + " field " + field + " must be an integer");
		return value.intValue();
	}

	/**
	 * Performs bool for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param field supplied field value
	 * @param label supplied label value
	 * @return the bool result
	 * @throws IOException if the operation fails
	 */
	static boolean bool(JsonNode node, String field, String label) throws IOException
	{
		JsonNode value = required(node, field, label);
		if (!value.isBoolean())
			throw new IOException(label + " field " + field + " must be a boolean");
		return value.booleanValue();
	}

	/**
	 * Performs optionalStrings for the voice runtime.
	 *
	 * @param node supplied node value
	 * @param label supplied label value
	 * @return the optionalStrings result
	 * @throws IOException if the operation fails
	 */
	private static Optional<List<String>> optionalStrings(JsonNode node, String label) throws IOException
	{
		if (node == null)
			return Optional.empty();
		if (!node.isArray() || node.size() > 128)
			throw new IOException(label + " must be an array of at most 128 strings");
		List<String> values = new ArrayList<>();
		for (JsonNode value : node)
		{
			if (!value.isString() || value.asString().isEmpty())
				throw new IOException(label + " entries must be nonempty strings");
			values.add(value.asString());
		}
		return Optional.of(values);
	}

	/**
	 * Performs checkHash for the voice runtime.
	 *
	 * @param value supplied value value
	 * @param label supplied label value
	 * @throws IOException if the operation fails
	 */
	static void checkHash(String value, String label) throws IOException
	{
		if (!HASH.matcher(value).matches())
			throw new IOException(label + " must be a lowercase SHA256 digest");
	}

	/**
	 * Performs validateRelative for the voice runtime.
	 *
	 * @param value supplied value value
	 * @param label supplied label value
	 * @throws IOException if the operation fails
	 */
	static void validateRelative(String value, String label) throws IOException
	{
		if (value.startsWith("/") || value.contains("\\") || value.isEmpty())
			throw new IOException(label + " must be a relative slash-separated path");
		for (String component : value.split("/", -1))
			if (component.isEmpty() || component.equals(".") || component.equals(".."))
				throw new IOException(label + " contains an invalid path component");
	}
}
