package com.holdmyspot.codexunleashed.tooling;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records and verifies release manifests, source identities and fixed-policy attestations.
 */
public final class ReleaseManifest
{
	/** Published manifest basename. */
	private static final String MANIFEST = "release-manifest.json";
	/** Published checksum basename. */
	private static final String CHECKSUMS = "SHA256SUMS";
	/** Borrowed mapper services. */
	private final ToolingScope scope;
	/** Captured process boundary. */
	private final SystemCommands.Capture capture;
	/** Explicitly observed settings. */
	private final Environment environment;

	/**
	 * Contains externally acquired process settings. Variables override the inherited child environment.
	 *
	 * @param temporaryDirectory existing parent for owned temporary directories
	 * @param workingDirectory existing child working directory
	 * @param host observed host description recorded in receipts
	 * @param variables explicit child environment overrides
	 */
	public record Environment(Path temporaryDirectory, Path workingDirectory, String host,
		Map<String, String> variables)
	{
		/**
		 * Validates and retains immutable settings.
		 *
		 * @param temporaryDirectory existing temporary parent
		 * @param workingDirectory existing child working directory
		 * @param host observed host description
		 * @param variables child environment overrides
		 * @throws NullPointerException if an argument is null
		 */
		public Environment
		{
			Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
			Objects.requireNonNull(workingDirectory, "workingDirectory");
			Objects.requireNonNull(host, "host");
			variables = Map.copyOf(variables);
		}
	}

	/**
	 * Creates an invocation with borrowed services.
	 *
	 * @param scope JSON services
	 * @param capture process boundary
	 * @param environment observed settings
	 */
	private ReleaseManifest(ToolingScope scope, SystemCommands.Capture capture, Environment environment)
	{
		this.scope = Objects.requireNonNull(scope, "scope");
		this.capture = Objects.requireNonNull(capture, "capture");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	/**
	 * Runs the selected release manifest operation.
	 *
	 * @param scope     borrowed tooling services
	 * @param arguments subcommand and its options
	 * @param out       success output
	 * @param err       diagnostics
	 * @return zero on success, one on rejected content, or two on invalid arguments
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err)
	{
		Environment settings = new Environment(Path.of(System.getProperty("java.io.tmpdir")),
			Path.of("").toAbsolutePath(), System.getProperty("os.name") + "-" + System.getProperty("os.version") +
			"-" + System.getProperty("os.arch"), Map.of());
		return run(scope, arguments, out, err, SystemCommands::capture, settings);
	}

	/**
	 * Runs an operation through explicit process and environment boundaries.
	 *
	 * @param scope borrowed JSON services
	 * @param arguments operation and named options
	 * @param out completion output
	 * @param err diagnostics
	 * @param capture captured Git and GitHub CLI boundary
	 * @param environment observed process and host settings
	 * @return zero on success, one on rejected content, or two on invalid arguments
	 * @throws NullPointerException if a reference or argument element is null
	 */
	public static int run(ToolingScope scope, String[] arguments, PrintStream out, PrintStream err,
		SystemCommands.Capture capture, Environment environment)
	{
		ReleaseManifest operation = new ReleaseManifest(scope, capture, environment);
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		List<String> tokens = List.of(arguments);
		if (tokens.contains("--help") || tokens.contains("-h"))
		{
			out.println("usage: release-manifest {receipt,assemble,verify,authenticate} ...");
			return 0;
		}
		Map<String, String> options;
		try
		{
			options = parse(tokens);
		}
		catch (IllegalArgumentException failure)
		{
			err.println("release-manifest: error: " + failure.getMessage());
			return 2;
		}
		try
		{
			String completion = switch (tokens.getFirst())
			{
				case "receipt" -> operation.receipt(options);
				case "assemble" -> operation.assemble(options);
				case "authenticate" -> operation.authenticate(options);
				case "verify" -> operation.verify(options);
				default -> throw new IllegalStateException("Validated operation disappeared");
			};
			out.println(completion);
			return 0;
		}
		catch (IOException | IllegalArgumentException | JacksonException failure)
		{
			err.println("Release verification failed: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Parses operations without reading content or performing effects.
	 *
	 * @param tokens complete command arguments
	 * @return option values
	 */
	private static Map<String, String> parse(List<String> tokens)
	{
		ReleaseManifestSchema.require(!tokens.isEmpty(), "a command is required");
		String command = tokens.getFirst();
		Set<String> required = switch (command)
		{
			case "receipt" -> Set.of("source", "patch-repo", "assets", "output", "target", "version", "upstream-tag",
				"upstream-commit", "fork-commit", "run-id", "build-number", "run-attempt", "native-inputs");
			case "assemble" -> Set.of("receipts", "assets", "output");
			case "verify" -> Set.of("release-dir", "patch-repo", "upstream-source");
			case "authenticate" -> Set.of("manifest");
			default -> throw new IllegalArgumentException("invalid command: " + command);
		};
		Map<String, String> result = new HashMap<>();
		for (int index = 1; index < tokens.size(); ++index)
		{
			String token = tokens.get(index);
			ReleaseManifestSchema.require(token.startsWith("--"), "unrecognized argument: " + token);
			String[] split = token.substring(2).split("=", 2);
			Set<String> allowed = new HashSet<>(required);
			if (command.equals("verify"))
				allowed.add("verify-attestations");
			String name = optionName(split[0], allowed);
			if (command.equals("verify") && name.equals("verify-attestations"))
			{
				ReleaseManifestSchema.require(split.length == 1, "--verify-attestations does not accept a value");
				result.put(name, "true");
				continue;
			}
			ReleaseManifestSchema.require(required.contains(name), "unrecognized argument: " + token);
			String value;
			if (split.length == 2)
				value = split[1];
			else
			{
				ReleaseManifestSchema.require(index + 1 < tokens.size() && !tokens.get(index + 1).startsWith("--"),
					"expected one argument: " + token);
				++index;
				value = tokens.get(index);
			}
			result.put(name, value);
		}
		ReleaseManifestSchema.require(result.keySet().containsAll(required), "required options: " +
			String.join(", ", new TreeSet<>(required)));
		if (command.equals("receipt"))
		{
			ReleaseManifestSchema.require(ReleaseManifestSchema.TARGETS.contains(result.get("target")), "invalid target");
			for (String name : List.of("build-number", "run-attempt"))
			{
				String value = result.get(name).strip();
				ReleaseManifestSchema.require(value.matches("[+-]?\\p{Nd}(?:_?\\p{Nd})*"), "invalid integer: --" + name);
				result.put(name, new BigInteger(value.replace("_", "")).toString());
			}
		}
		return result;
	}

	/**
	 * Resolves exact names and unambiguous long-option abbreviations supported by the previous command parser.
	 *
	 * @param name supplied long-option name
	 * @param allowed allowed long-option names
	 * @return complete option name
	 */
	private static String optionName(String name, Set<String> allowed)
	{
		if (allowed.contains(name))
			return name;
		List<String> matches = allowed.stream().filter(option -> option.startsWith(name)).toList();
		ReleaseManifestSchema.require(matches.size() == 1, "unrecognized or ambiguous argument: --" + name);
		return matches.getFirst();
	}

	/**
	 * Executes a command without exposing captured diagnostics or credential-bearing argument values.
	 *
	 * @param command executable and arguments
	 * @return stripped stdout
	 * @throws IOException if execution fails
	 */
	private String command(List<String> command) throws IOException
	{
		SystemCommands.Result result = capture.capture(command, environment.workingDirectory(),
			environment.temporaryDirectory(), environment.variables());
		ReleaseManifestSchema.require(result.status() == 0, command.getFirst() + " " + command.get(1) +
			" failed (exit " + result.status() + "); inspect the supplied checkout or verifier");
		return result.stdout().strip();
	}

	/**
	 * Hashes regular nonsymlinked payload bytes.
	 *
	 * @param path file to hash
	 * @return lowercase SHA-256
	 * @throws IOException if reading fails
	 */
	private static String digest(Path path) throws IOException
	{
		ReleaseManifestSchema.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
			"Expected a regular file: " + path.getFileName());
		return Sha256.digest(path);
	}

	/**
	 * Describes exact published file bytes.
	 *
	 * @param path payload file
	 * @param kind semantic kind
	 * @param target native target
	 * @return artifact record
	 * @throws IOException if reading fails
	 */
	private ObjectNode artifact(Path path, String kind, String target) throws IOException
	{
		return scope.getJsonMapper().createObjectNode().put("name",
			ReleaseManifestSchema.basename(path.getFileName().toString())).put("kind", kind).put("target", target).
			put("size", Files.size(path)).put("sha256", digest(path));
	}

	/**
	 * Lists bounded directory entries in filename order.
	 *
	 * @param root directory root
	 * @return sorted entries
	 * @throws IOException if listing fails
	 */
	private static List<Path> entries(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.list(root))
		{
			List<Path> result = paths.limit(ReleaseManifestSchema.MAX_ASSETS + 3L).toList();
			ReleaseManifestSchema.require(result.size() <= ReleaseManifestSchema.MAX_ASSETS + 2,
				"Too many release directory entries");
			return result.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
		}
	}

	/**
	 * Reads observed CLI stdout without executing an archive member.
	 *
	 * @param path version proof
	 * @return proof with trailing CR/LF removed
	 * @throws IOException if reading fails
	 */
	private static String observedVersion(Path path) throws IOException
	{
		ReleaseManifestSchema.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 4096,
			"Version proof must be a regular file of at most 4096 bytes");
		try (BufferedReader reader = Files.newBufferedReader(path))
		{
			char[] characters = new char[4097];
			int count = 0;
			while (count < characters.length)
			{
				int read = reader.read(characters, count, characters.length - count);
				if (read == -1)
					break;
				count += read;
			}
			ReleaseManifestSchema.require(count <= 4096, "Version proof must be a regular file of at most 4096 bytes");
			return new String(characters, 0, count).replaceFirst("[\\r\\n]+$", "");
		}
	}

	/**
	 * Reads a string field without coercion.
	 *
	 * @param value object
	 * @param name field name
	 * @return field text
	 */
	private static String text(JsonNode value, String name)
	{
		return ReleaseManifestSchema.text(value.path(name));
	}

	/**
	 * Creates one receipt after validating source and package inputs.
	 *
	 * @param options parsed receipt options
	 * @return completion message
	 * @throws IOException if inputs, processes or output fail
	 */
	private String receipt(Map<String, String> options) throws IOException
	{
		String target = options.get("target");
		JsonNode nativeBuild = ReleaseManifestSchema.read(scope, Path.of(options.get("native-inputs")));
		ReleaseManifestNative.validate(nativeBuild, target);
		Path patchRepository = Path.of(options.get("patch-repo"));
		Path source = Path.of(options.get("source"));
		Path assets = Path.of(options.get("assets"));
		Path output = Path.of(options.get("output"));
		String version = options.get("version");
		ReleaseManifestSource sources = new ReleaseManifestSource(scope, capture, environment);
		ObjectNode value = scope.getJsonMapper().createObjectNode().put("format_version", 1).
			put("repository", ReleaseManifestSchema.REPOSITORY).put("signer_workflow", ReleaseManifestSchema.WORKFLOW).
			put("fork_commit", options.get("fork-commit")).put("target", target).
			put("observed_cli_version", observedVersion(assets.resolve(ReleaseManifestSchema.proofName(target))));
		value.set("release", scope.getJsonMapper().createObjectNode().put("tag", "rust-v" + version).
			put("version", version).put("build_number", new BigInteger(options.get("build-number"))));
		value.set("upstream", scope.getJsonMapper().createObjectNode().put("repository", "openai/codex").
			put("tag", options.get("upstream-tag")).put("commit", options.get("upstream-commit")));
		value.set("patches", sources.patches(patchRepository, options.get("fork-commit")));
		value.set("source", sources.identity(source));
		value.set("run", scope.getJsonMapper().createObjectNode().put("id", options.get("run-id")).
			put("attempt", new BigInteger(options.get("run-attempt"))));
		ObjectNode inputs = scope.getJsonMapper().createObjectNode().put("receipt_operation", "receipt").
			put("python", ReleaseManifestNative.pythonVersion(nativeBuild)).put("host", environment.host()).
			put("git", command(List.of("git", "--version")));
		inputs.set("native_build", nativeBuild);
		value.set("inputs", inputs);
		ArrayNode artifacts = value.putArray("artifacts");
		ArrayNode binaries = value.putArray("binaries");
		for (Path path : entries(assets))
		{
			if (path.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
				continue;
			String kind = ReleaseManifestSchema.payloadKind(path.getFileName().toString(), target);
			artifacts.add(artifact(path, kind, target));
			if (kind.equals("cli_archive") || kind.equals("app_server_archive"))
				binaries.addAll(ReleaseManifestArchive.binaries(scope, path).asArray());
		}
		ReleaseManifestSchema.receipt(value);
		sources.validate(value, patchRepository, source);
		ReleaseManifestSchema.write(scope, output, value);
		return "Recorded package receipt for " + target;
	}

	/**
	 * Checks regular inventoried asset bytes without executing them.
	 *
	 * @param root release directory
	 * @param entry validated artifact
	 * @throws IOException if reading fails
	 */
	private static void verifyFile(Path root, JsonNode entry) throws IOException
	{
		String name = text(entry, "name");
		Path path = root.resolve(name);
		ReleaseManifestSchema.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
			"Missing regular asset: " + name);
		ReleaseManifestSchema.require(BigInteger.valueOf(Files.size(path)).equals(entry.path("size").bigIntegerValue()) &&
			digest(path).equals(text(entry, "sha256")), "Asset digest or size mismatch: " + name);
	}

	/**
	 * Assembles six agreeing native receipts and writes the final checksum inventory.
	 *
	 * @param options parsed assembly options
	 * @return completion message
	 * @throws IOException if reading or writing fails
	 */
	private String assemble(Map<String, String> options) throws IOException
	{
		Path assets = Path.of(options.get("assets"));
		Path output = Path.of(options.get("output"));
		Map<Path, JsonNode> receipts = new LinkedHashMap<>();
		Set<String> targets = new HashSet<>();
		for (Path path : entries(Path.of(options.get("receipts"))))
		{
			String name = path.getFileName().toString();
			if (!name.startsWith("package-receipt-") || !name.endsWith(".json"))
				continue;
			JsonNode receipt = ReleaseManifestSchema.read(scope, path);
			ReleaseManifestSchema.receipt(receipt);
			ReleaseManifestSchema.require(name.equals(ReleaseManifestSchema.receiptName(text(receipt, "target"))),
				"Receipt filename differs from target");
			receipts.put(path, receipt);
			targets.add(text(receipt, "target"));
		}
		ReleaseManifestSchema.require(receipts.size() == ReleaseManifestSchema.TARGETS.size() &&
			targets.equals(new HashSet<>(ReleaseManifestSchema.TARGETS)), "Exactly six unique target receipts are required");
		ObjectNode value = ReleaseManifestSchema.shared(scope, receipts.values().iterator().next());
		List<JsonNode> artifacts = new ArrayList<>();
		for (Map.Entry<Path, JsonNode> entry : receipts.entrySet())
		{
			JsonNode receipt = entry.getValue();
			ReleaseManifestSchema.require(ReleaseManifestSchema.shared(scope, receipt).equals(value),
				"Receipts disagree on release, fork, source or ordered patches");
			receipt.path("artifacts").forEach(artifacts::add);
			ObjectNode record = artifact(entry.getKey(), "package_receipt", text(receipt, "target"));
			verifyFile(assets, record);
			artifacts.add(record);
		}
		Set<String> names = new HashSet<>();
		for (JsonNode entry : artifacts)
			ReleaseManifestSchema.require(names.add(text(entry, "name")), "Duplicate published asset name");
		ReleaseManifestSchema.require(names.containsAll(Set.of("install.sh", "install.ps1")),
			"Both installers are required");
		ArrayNode receiptNames = value.putArray("receipts");
		for (String target : ReleaseManifestSchema.TARGETS)
			receiptNames.add(ReleaseManifestSchema.receiptName(target));
		artifacts.sort(Comparator.comparing(entry -> text(entry, "name")));
		value.putArray("artifacts").addAll(artifacts);
		ReleaseManifestSchema.require(output.getFileName().toString().equals(MANIFEST) &&
			output.toAbsolutePath().normalize().getParent().equals(assets.toAbsolutePath().normalize()),
			"Manifest output must be assets/" + MANIFEST);
		ReleaseManifestSchema.manifest(value);
		for (JsonNode entry : artifacts)
			verifyFile(assets, entry);
		Set<String> actual = filenames(assets);
		actual.removeAll(Set.of(MANIFEST, CHECKSUMS));
		ReleaseManifestSchema.require(actual.equals(names), "Release assets contain missing or uninventoried payloads");
		ReleaseManifestSchema.require(!Files.isSymbolicLink(assets.resolve(CHECKSUMS)),
			"Checksum output must not be a symbolic link");
		ReleaseManifestSchema.write(scope, output, value);
		names.add(MANIFEST);
		try (BufferedWriter writer = Files.newBufferedWriter(assets.resolve(CHECKSUMS)))
		{
			for (String name : new TreeSet<>(names))
				writer.write(digest(assets.resolve(name)) + "  " + name + "\n");
		}
		return "Assembled six-target release manifest and SHA256SUMS";
	}

	/**
	 * Returns bounded directory entry names.
	 *
	 * @param root directory
	 * @return mutable basename set
	 * @throws IOException if listing fails
	 */
	private static Set<String> filenames(Path root) throws IOException
	{
		Set<String> result = new HashSet<>();
		for (Path path : entries(root))
			result.add(path.getFileName().toString());
		return result;
	}

	/**
	 * Verifies one asset using immutable consumer trust policy.
	 *
	 * @param path asset to authenticate
	 * @throws IOException if verifier execution fails
	 */
	private void attest(Path path) throws IOException
	{
		command(List.of("gh", "attestation", "verify", path.toString(), "--repo", ReleaseManifestSchema.REPOSITORY,
			"--signer-workflow", ReleaseManifestSchema.WORKFLOW));
	}

	/**
	 * Authenticates a schema-validated manifest before callers trust its recorded source identities.
	 *
	 * @param options parsed authentication options
	 * @return completion message
	 * @throws IOException if input or verifier fails
	 */
	private String authenticate(Map<String, String> options) throws IOException
	{
		Path path = Path.of(options.get("manifest"));
		ReleaseManifestSchema.manifest(ReleaseManifestSchema.read(scope, path));
		attest(path);
		return "Authenticated release manifest";
	}

	/**
	 * Reads checksums while bounding records before growing either a line or the inventory.
	 *
	 * @param path checksum file
	 * @return digest by published basename
	 * @throws IOException if reading fails
	 */
	private static Map<String, String> checksums(Path path) throws IOException
	{
		Map<String, String> result = new TreeMap<>();
		try (BufferedReader reader = Files.newBufferedReader(path))
		{
			StringBuilder line = new StringBuilder();
			for (int character = reader.read(); character != -1; character = reader.read())
			{
				line.append((char) character);
				ReleaseManifestSchema.require(line.length() <= 256, "Checksum record is too long");
				if (character == '\n')
				{
					checksum(result, line.substring(0, line.length() - 1));
					line.setLength(0);
				}
			}
			if (!line.isEmpty())
				checksum(result, line.toString());
		}
		return result;
	}

	/**
	 * Validates and appends one bounded checksum record.
	 *
	 * @param values existing inventory
	 * @param line record without trailing LF
	 */
	private static void checksum(Map<String, String> values, String line)
	{
		ReleaseManifestSchema.require(values.size() <= ReleaseManifestSchema.MAX_ASSETS, "Too many checksum records");
		String[] parts = line.split("  ", -1);
		ReleaseManifestSchema.require(parts.length == 2, "Malformed SHA256SUMS record");
		String name = ReleaseManifestSchema.basename(parts[1]);
		ReleaseManifestSchema.require(parts[0].matches("[0-9a-f]{64}"), "Invalid checksum SHA256");
		ReleaseManifestSchema.require(values.putIfAbsent(name, parts[0]) == null, "Duplicate checksum asset");
	}

	/**
	 * Verifies release bytes, receipt agreement, source reconstruction and optionally every attestation.
	 *
	 * @param options parsed verification options
	 * @return completion message
	 * @throws IOException if an input, process or cleanup fails
	 */
	private String verify(Map<String, String> options) throws IOException
	{
		Path root = Path.of(options.get("release-dir"));
		JsonNode value = ReleaseManifestSchema.read(scope, root.resolve(MANIFEST));
		ReleaseManifestSchema.manifest(value);
		Set<String> names = new HashSet<>();
		for (JsonNode entry : value.path("artifacts"))
			names.add(text(entry, "name"));
		Set<String> expected = new HashSet<>(names);
		expected.addAll(Set.of(MANIFEST, CHECKSUMS));
		ReleaseManifestSchema.require(filenames(root).equals(expected),
			"Release directory contains missing or uninventoried assets");
		for (JsonNode entry : value.path("artifacts"))
			verifyFile(root, entry);
		expected.remove(CHECKSUMS);
		Map<String, String> sums = checksums(root.resolve(CHECKSUMS));
		ReleaseManifestSchema.require(sums.keySet().equals(expected), "SHA256SUMS inventory differs from release manifest");
		for (Map.Entry<String, String> entry : sums.entrySet())
			ReleaseManifestSchema.require(digest(root.resolve(entry.getKey())).equals(entry.getValue()),
				"SHA256SUMS mismatch: " + entry.getKey());
		List<JsonNode> receiptArtifacts = new ArrayList<>();
		for (JsonNode name : value.path("receipts"))
		{
			JsonNode receipt = ReleaseManifestSchema.read(scope, root.resolve(name.asString()));
			ReleaseManifestSchema.receipt(receipt);
			ReleaseManifestSchema.require(ReleaseManifestSchema.shared(scope, receipt).
				equals(ReleaseManifestSchema.shared(scope, value)), "Receipt identity differs from release manifest");
			ReleaseManifestSchema.require(ReleaseManifestSchema.receiptName(text(receipt, "target")).equals(name.asString()),
				"Receipt target differs from filename");
			Path proof = root.resolve(ReleaseManifestSchema.proofName(text(receipt, "target")));
			ReleaseManifestSchema.require(observedVersion(proof).equals(text(receipt, "observed_cli_version")),
				"Version proof differs from receipt");
			ArrayNode binaries = scope.getJsonMapper().createArrayNode();
			for (JsonNode entry : receipt.path("artifacts"))
			{
				String kind = text(entry, "kind");
				if (kind.equals("cli_archive") || kind.equals("app_server_archive"))
					binaries.addAll(ReleaseManifestArchive.binaries(scope, root.resolve(text(entry, "name"))).asArray());
				receiptArtifacts.add(entry);
			}
			ReleaseManifestSchema.require(ReleaseManifestSchema.same(binaries, receipt.path("binaries")),
				"Archive binary hashes differ from receipt");
		}
		receiptArtifacts.sort(Comparator.comparing(entry -> text(entry, "name")));
		List<JsonNode> payloads = new ArrayList<>();
		for (JsonNode entry : value.path("artifacts"))
		{
			if (!text(entry, "kind").equals("package_receipt"))
				payloads.add(entry);
		}
		ReleaseManifestSchema.require(receiptArtifacts.equals(payloads), "Manifest payloads differ from receipts");
		new ReleaseManifestSource(scope, capture, environment).validate(value, Path.of(options.get("patch-repo")),
			Path.of(options.get("upstream-source")));
		if (options.containsKey("verify-attestations"))
		{
			expected.add(CHECKSUMS);
			for (String name : new TreeSet<>(expected))
				attest(root.resolve(name));
			return "Verified checksums, reconstructed source and GitHub attestations";
		}
		return "Verified checksums and reconstructed source only; GitHub attestations were not checked";
	}
}
