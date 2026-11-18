package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the persisted version-one release contract before interpreting its identities or filenames.
 */
public final class ReleaseManifestSchema
{
	/** Fixed release repository. */
	public static final String REPOSITORY = "holdmyspot-com/codex-unleashed";
	/** Fixed attestation signer. */
	public static final String WORKFLOW = REPOSITORY + "/.github/workflows/upstream-auto-release.yml";
	/** Supported targets, in their persisted receipt order. */
	public static final List<String> TARGETS = List.of("aarch64-apple-darwin", "aarch64-pc-windows-msvc",
		"aarch64-unknown-linux-musl", "x86_64-apple-darwin", "x86_64-pc-windows-msvc",
		"x86_64-unknown-linux-musl");
	/** Bound on any untrusted configuration document. */
	public static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
	/** Maximum published inventory length. */
	public static final int MAX_ASSETS = 128;
	/** Safe names shared by JSON inventories and checksum records. */
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
	/** Numeric package version including its positive build number. */
	private static final Pattern VERSION = Pattern.compile(
		"(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\+([1-9][0-9]*)");
	/** Identity fields common to every target. */
	private static final List<String> IDENTITY_FIELDS = List.of("format_version", "repository", "signer_workflow",
		"release", "upstream", "fork_commit", "patches", "source");
	/** Unicode Numeric_Type=Digit ranges supplementing decimal digits, matching Python's str.isdigit contract. */
	private static final int[] NONDECIMAL_DIGITS = {0x00b2, 0x00b3, 0x00b9, 0x00b9, 0x1369, 0x1371,
		0x19da, 0x19da, 0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089, 0x2460, 0x2468, 0x2474, 0x247c,
		0x2488, 0x2490, 0x24ea, 0x24ea, 0x24f5, 0x24fd, 0x24ff, 0x24ff, 0x2776, 0x277e, 0x2780, 0x2788,
		0x278a, 0x2792, 0x10a40, 0x10a43, 0x10e60, 0x10e68, 0x11052, 0x1105a, 0x1f100, 0x1f10a};

	/** Prevents construction. */
	private ReleaseManifestSchema()
	{
	}

	/**
	 * Rejects an unsatisfied content invariant.
	 *
	 * @param condition required condition
	 * @param message safe diagnostic
	 * @throws IllegalArgumentException if the condition is false
	 */
	public static void require(boolean condition, String message)
	{
		if (!condition)
			throw new IllegalArgumentException(message);
	}

	/**
	 * Reads a bounded JSON document without accepting duplicate fields or trailing content.
	 *
	 * @param scope borrowed JSON service
	 * @param path document path
	 * @return parsed document
	 * @throws IOException if reading fails
	 * @throws IllegalArgumentException if the document exceeds its bound
	 */
	public static JsonNode read(ToolingScope scope, Path path) throws IOException
	{
		require(Files.size(path) <= MAX_DOCUMENT_BYTES,
			"JSON document exceeds " + MAX_DOCUMENT_BYTES + " bytes: " + path.getFileName());
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] content = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
			require(content.length <= MAX_DOCUMENT_BYTES, "JSON document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
			String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
			return scope.getJsonMapper().reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).
				readTree(document);
		}
	}

	/**
	 * Writes sorted ASCII JSON after rejecting a symbolic output path.
	 *
	 * @param scope borrowed JSON service
	 * @param path explicit output
	 * @param value validated document
	 * @throws IOException if writing fails
	 */
	public static void write(ToolingScope scope, Path path, JsonNode value) throws IOException
	{
		require(!Files.isSymbolicLink(path), "Output must not be a symbolic link: " + path.getFileName());
		Files.writeString(path, ArtifactJson.format(scope, javaValue(value)));
	}

	/**
	 * Converts JSON to supported artifact formatter values without unchecked map casts.
	 *
	 * @param value JSON node
	 * @return equivalent maps, lists or scalar
	 */
	private static Object javaValue(JsonNode value)
	{
		if (value.isObject())
		{
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> entry : value.properties())
				result.put(entry.getKey(), javaValue(entry.getValue()));
			return result;
		}
		if (value.isArray())
		{
			List<Object> result = new ArrayList<>();
			for (JsonNode entry : value)
				result.add(javaValue(entry));
			return result;
		}
		if (value.isString())
			return value.asString();
		if (value.isNumber())
			return value.numberValue();
		if (value.isBoolean())
			return value.booleanValue();
		return null;
	}

	/**
	 * Requires an exact set of object fields.
	 *
	 * @param value candidate object
	 * @param subject diagnostic subject
	 * @param names required names
	 */
	public static void fields(JsonNode value, String subject, String... names)
	{
		require(value != null && value.isObject() && new HashSet<>(value.propertyNames()).equals(Set.of(names)),
			"Invalid " + subject + " fields; expected " + String.join(", ", names));
	}

	/**
	 * Returns a string value without coercing numeric or missing nodes.
	 *
	 * @param value candidate string
	 * @return string, or an empty value for a different JSON type
	 */
	public static String text(JsonNode value)
	{
		if (value != null && value.isString())
			return value.asString();
		return "";
	}

	/**
	 * Compares validated JSON values independently of Jackson's integer storage width.
	 *
	 * @param first first validated value
	 * @param second second validated value
	 * @return whether the JSON values agree
	 */
	public static boolean same(JsonNode first, JsonNode second)
	{
		return first.equals((left, right) ->
		{
			if (left.isIntegralNumber() && right.isIntegralNumber())
				return left.bigIntegerValue().compareTo(right.bigIntegerValue());
			if (left.equals(right))
				return 0;
			return 1;
		}, second);
	}

	/**
	 * Requires an exact lowercase digest identity.
	 *
	 * @param value candidate string
	 * @param length required hexadecimal length
	 * @param subject identity subject
	 */
	public static void hash(JsonNode value, int length, String subject)
	{
		require(text(value).matches("[0-9a-f]{" + length + "}"), "Invalid " + subject);
	}

	/**
	 * Validates a published basename.
	 *
	 * @param name candidate name
	 * @return the safe name
	 */
	public static String basename(String name)
	{
		require(SAFE_NAME.matcher(name).matches(), "Unsafe asset name: " + name);
		return name;
	}

	/**
	 * Validates the slash-separated persisted patch namespace.
	 *
	 * @param name relative patch path
	 * @return validated path
	 */
	public static String patchPath(String name)
	{
		String[] parts = name.split("/", -1);
		boolean safe = parts.length >= 2 && parts[0].equals("patches") && !name.contains("\\");
		for (String part : parts)
			safe &= SAFE_NAME.matcher(part).matches();
		require(safe, "Unsafe patch path: " + name);
		require(name.endsWith(".patch"), "Patch path must end in .patch: " + name);
		return name;
	}

	/**
	 * Returns the archive name for the target CLI.
	 *
	 * @param target native target
	 * @return archive basename
	 */
	public static String packageName(String target)
	{
		return "codex-package-" + target + ".tar.gz";
	}

	/**
	 * Returns the debug archive name.
	 *
	 * @param target native target
	 * @return archive basename
	 */
	public static String symbolsName(String target)
	{
		return "codex-symbols-" + target + ".tar.gz";
	}

	/**
	 * Returns the observed version proof name.
	 *
	 * @param target native target
	 * @return proof basename
	 */
	public static String proofName(String target)
	{
		return "codex-version-" + target + ".txt";
	}

	/**
	 * Returns the target receipt name.
	 *
	 * @param target native target
	 * @return receipt basename
	 */
	public static String receiptName(String target)
	{
		return "package-receipt-" + target + ".json";
	}

	/**
	 * Classifies an exact known payload filename.
	 *
	 * @param name safe published name
	 * @param target receipt target
	 * @return semantic payload kind
	 */
	public static String payloadKind(String name, String target)
	{
		if (name.equals(packageName(target)))
			return "cli_archive";
		if (name.equals(symbolsName(target)))
			return "symbols_archive";
		if (name.equals(proofName(target)))
			return "version_proof";
		if (name.equals("install.sh") || name.equals("install.ps1"))
		{
			require(target.equals("x86_64-unknown-linux-musl"),
				"Installers must be supplied by the x86_64 Linux receipt");
			return "installer";
		}
		if (name.equals("codex-app-server-package-" + target + ".tar.gz"))
			return "app_server_archive";
		throw new IllegalArgumentException("Unsupported release payload: " + name);
	}

	/**
	 * Returns common identity fields for exact receipt agreement.
	 *
	 * @param scope borrowed JSON service
	 * @param value manifest or receipt
	 * @return independent identity object
	 */
	public static ObjectNode shared(ToolingScope scope, JsonNode value)
	{
		ObjectNode result = scope.getJsonMapper().createObjectNode();
		for (String name : IDENTITY_FIELDS)
			result.set(name, value.get(name));
		return result;
	}

	/**
	 * Validates shared source, release and immutable trust identities.
	 *
	 * @param value manifest or receipt with checked outer fields
	 */
	private static void identity(JsonNode value)
	{
		require(integer(value.path("format_version"), BigInteger.ONE), "Unsupported manifest format_version");
		require(text(value.path("repository")).equals(REPOSITORY), "Release repository violates consumer policy");
		require(text(value.path("signer_workflow")).equals(WORKFLOW), "Signer workflow violates consumer policy");
		JsonNode release = value.path("release");
		fields(release, "release", "tag", "version", "build_number");
		require(release.path("version").isString(), "Release version must be text");
		String version = text(release.path("version"));
		Matcher match = VERSION.matcher(version);
		require(match.matches(), "Release version must be numeric X.Y.Z+BUILD");
		require(integer(release.path("build_number"), new BigInteger(match.group(4))),
			"Build number differs from release version");
		require(text(release.path("tag")).equals("rust-v" + version), "Release tag differs from release version");
		JsonNode upstream = value.path("upstream");
		fields(upstream, "upstream", "repository", "tag", "commit");
		require(text(upstream.path("repository")).equals("openai/codex"), "Unexpected upstream repository");
		require(text(upstream.path("tag")).equals("rust-v" + version.split("\\+", -1)[0]),
			"Upstream tag differs from release version");
		hash(upstream.path("commit"), 40, "upstream commit");
		hash(value.path("fork_commit"), 40, "fork commit");
		JsonNode source = value.path("source");
		fields(source, "source", "commit", "tree", "cargo_lock_sha256");
		hash(source.path("commit"), 40, "source commit");
		hash(source.path("tree"), 40, "source tree");
		hash(source.path("cargo_lock_sha256"), 64, "Cargo.lock SHA256");
		JsonNode patches = value.path("patches");
		require(patches.isArray() && !patches.isEmpty() && patches.size() <= 512, "Invalid patch inventory");
		Set<String> seen = new HashSet<>();
		for (JsonNode patch : patches)
		{
			fields(patch, "patch", "path", "sha256");
			String path = patchPath(text(patch.path("path")));
			require(seen.add(path), "Duplicate patch path");
			hash(patch.path("sha256"), 64, "patch SHA256");
		}
	}

	/**
	 * Compares an exact JSON integer without boolean or decimal coercion.
	 *
	 * @param value candidate number
	 * @param expected expected integer
	 * @return whether the exact integer matches
	 */
	private static boolean integer(JsonNode value, BigInteger expected)
	{
		return value.isIntegralNumber() && value.bigIntegerValue().equals(expected);
	}

	/**
	 * Recognizes a nonnegative JSON integer of arbitrary size.
	 *
	 * @param value candidate number
	 * @return whether the number is an exact nonnegative integer
	 */
	private static boolean nonnegative(JsonNode value)
	{
		return value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0;
	}

	/**
	 * Validates an inventory record including exact filename/kind agreement.
	 *
	 * @param value artifact record
	 */
	private static void artifact(JsonNode value)
	{
		fields(value, "artifact", "name", "kind", "target", "size", "sha256");
		String name = basename(text(value.path("name")));
		require(nonnegative(value.path("size")), "Artifact size must be a nonnegative integer");
		hash(value.path("sha256"), 64, "artifact SHA256");
		String target = text(value.path("target"));
		require(TARGETS.contains(target) || value.path("target").isNull(), "Unsupported artifact target");
		if (text(value.path("kind")).equals("package_receipt"))
			require(TARGETS.contains(target) && name.equals(receiptName(target)), "Invalid package receipt asset");
		else
		{
			require(TARGETS.contains(target), "Payload target is required");
			require(payloadKind(name, target).equals(text(value.path("kind"))), "Payload name and kind disagree");
		}
	}

	/**
	 * Validates a six-target manifest before trusting any recorded identity.
	 *
	 * @param value candidate manifest
	 */
	public static void manifest(JsonNode value)
	{
		fields(value, "manifest", "format_version", "repository", "signer_workflow", "release", "upstream",
			"fork_commit", "patches", "source", "receipts", "artifacts");
		identity(value);
		JsonNode receipts = value.path("receipts");
		require(receipts.isArray() && receipts.size() == TARGETS.size(),
			"Manifest must name exactly six unique target receipts");
		for (int index = 0; index < TARGETS.size(); ++index)
			require(text(receipts.path(index)).equals(receiptName(TARGETS.get(index))),
				"Manifest must name exactly six unique target receipts");
		JsonNode artifacts = value.path("artifacts");
		require(artifacts.isArray() && artifacts.size() <= MAX_ASSETS, "Invalid manifest asset inventory");
		Set<String> names = new HashSet<>();
		for (JsonNode entry : artifacts)
		{
			artifact(entry);
			require(names.add(text(entry.path("name"))), "Duplicate manifest asset name");
		}
		Set<String> expected = new HashSet<>(Set.of("install.sh", "install.ps1"));
		for (String target : TARGETS)
			expected.addAll(List.of(receiptName(target), packageName(target), symbolsName(target), proofName(target)));
		require(names.containsAll(expected), "Manifest is missing a required target asset or installer");
	}

	/**
	 * Validates a target receipt and its bounded native observations and binary inventory.
	 *
	 * @param value candidate receipt
	 */
	public static void receipt(JsonNode value)
	{
		fields(value, "receipt", "format_version", "repository", "signer_workflow", "release", "upstream",
			"fork_commit", "patches", "source", "target", "run", "observed_cli_version", "inputs", "artifacts", "binaries");
		identity(value);
		String target = text(value.path("target"));
		require(TARGETS.contains(target), "Unsupported receipt target");
		require(text(value.path("observed_cli_version")).equals("codex-cli " + text(value.path("release").path("version"))),
			"Packaged CLI version differs from expected numeric release version");
		fields(value.path("run"), "run", "id", "attempt");
		String runId = text(value.path("run").path("id"));
		require(!runId.isEmpty() && runId.codePoints().allMatch(ReleaseManifestSchema::digit),
			"Run id must be numeric text");
		JsonNode attempt = value.path("run").path("attempt");
		require(nonnegative(attempt) && attempt.bigIntegerValue().signum() > 0, "Run attempt must be positive");
		JsonNode inputs = value.path("inputs");
		fields(inputs, "observed inputs", "receipt_operation", "python", "host", "git", "native_build");
		require(text(inputs.path("receipt_operation")).equals("receipt"), "Invalid receipt operation");
		for (String name : List.of("python", "host", "git"))
			require(inputs.path(name).isString(), "Invalid observed tool inputs");
		ReleaseManifestNative.validate(inputs.path("native_build"), target);
		JsonNode artifacts = value.path("artifacts");
		require(artifacts.isArray() && artifacts.size() <= MAX_ASSETS, "Invalid receipt artifact inventory");
		Set<String> names = new HashSet<>();
		for (JsonNode entry : artifacts)
		{
			artifact(entry);
			require(text(entry.path("target")).equals(target) &&
				!text(entry.path("kind")).equals("package_receipt"), "Receipt payload target differs from receipt");
			require(names.add(text(entry.path("name"))), "Duplicate receipt asset");
		}
		require(names.containsAll(List.of(packageName(target), symbolsName(target), proofName(target))),
			"Receipt is missing CLI archive, symbols or version proof");
		JsonNode binaries = value.path("binaries");
		require(binaries.isArray() && !binaries.isEmpty() && binaries.size() <= 16,
			"Receipt requires archive binary hashes");
		Set<List<String>> binaryNames = new HashSet<>();
		for (JsonNode binary : binaries)
		{
			fields(binary, "binary", "archive", "path", "size", "sha256");
			String archive = text(binary.path("archive"));
			require(names.contains(archive), "Binary archive is absent from receipt assets");
			require(binary.path("path").isString(), "Binary path must be text");
			String path = text(binary.path("path"));
			require(!path.startsWith("/") && !List.of(path.split("/", -1)).contains("..") && !path.contains("\\"),
				"Unsafe binary member path");
			require(nonnegative(binary.path("size")), "Invalid binary size");
			hash(binary.path("sha256"), 64, "binary SHA256");
			require(binaryNames.add(List.of(archive, path)), "Duplicate binary archive member");
		}
		require(hasExecutable(binaries, packageName(target), "codex"),
			"CLI package contains no recorded codex executable");
		for (JsonNode entry : artifacts)
		{
			if (text(entry.path("kind")).equals("app_server_archive"))
				require(hasExecutable(binaries, text(entry.path("name")), "codex-app-server"),
					"App-server archive contains no recorded codex-app-server executable");
		}
	}

	/**
	 * Finds a required executable in its own archive, including Windows package names.
	 *
	 * @param binaries recorded binaries
	 * @param archive required archive
	 * @param executable executable basename without suffix
	 * @return whether a matching recorded member exists
	 */
	private static boolean hasExecutable(JsonNode binaries, String archive, String executable)
	{
		for (JsonNode binary : binaries)
		{
			String path = text(binary.path("path"));
			List<String> parts = new ArrayList<>();
			for (String part : path.split("/", -1))
			{
				if (!part.isEmpty() && !part.equals("."))
					parts.add(part);
			}
			if (!parts.isEmpty() && text(binary.path("archive")).equals(archive))
			{
				String name = parts.getLast();
				if (name.equals(executable) || name.equals(executable + ".exe"))
					return true;
			}
		}
		return false;
	}

	/**
	 * Recognizes decimal and compatibility digit scalars without accepting general numeric characters.
	 *
	 * @param codePoint Unicode scalar
	 * @return whether Python's numeric text validation accepts the scalar as a digit
	 */
	private static boolean digit(int codePoint)
	{
		if (Character.isDigit(codePoint))
			return true;
		for (int index = 0; index < NONDECIMAL_DIGITS.length; index += 2)
		{
			if (codePoint >= NONDECIMAL_DIGITS[index] && codePoint <= NONDECIMAL_DIGITS[index + 1])
				return true;
		}
		return false;
	}
}
