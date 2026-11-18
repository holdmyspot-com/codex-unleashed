package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifest;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestArchive;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestNative;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.DataProvider;
import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the release manifest consumer's byte, source and fixed attestation policy decisions.
 */
public final class ReleaseManifestTest
{
	/** Test fork identity, independent of machine-local paths. */
	private static final String FORK = "a".repeat(40);
	/** Test upstream identity. */
	private static final String UPSTREAM = "b".repeat(40);
	/** Patched source commit. */
	private static final String SOURCE = "c".repeat(40);
	/** Independently replayed source tree. */
	private static final String TREE = "d".repeat(40);

	/**
	 * Creates an independent TestNG test instance.
	 */
	public ReleaseManifestTest()
	{
	}

	/**
	 * Rejects a malformed manifest through the public authentication operation.
	 *
	 * @throws IOException if test-owned fixture creation or cleanup fails
	 */
	@Test
	public void invalidManifestDoesNotAuthenticate() throws IOException
	{
		Path manifest = Files.createTempFile("release-manifest-test-", ".json");
		try (DefaultToolingScope scope = new DefaultToolingScope();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			Files.writeString(manifest, "{}");
			int status = ReleaseManifest.run(scope,
				new String[]{"authenticate", "--manifest", manifest.toString()}, out, err);
			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").contains("Invalid manifest fields");
		}
		finally
		{
			Files.deleteIfExists(manifest);
		}
	}

	/**
	 * Supplies invalid command shapes which must remain argument failures, without content or process effects.
	 *
	 * @return invalid public invocations
	 */
	@DataProvider(name = "invalidArguments")
	public Object[][] invalidArguments()
	{
		return new Object[][]{
			{new String[]{}},
			{new String[]{"unknown"}},
			{new String[]{"authenticate"}},
			{new String[]{"authenticate", "--manifest"}},
			{new String[]{"authenticate", "--unknown", "ignored"}},
			{new String[]{"verify", "--verify-attestations=true"}}};
	}

	/**
	 * Distinguishes invalid arguments from rejected release content.
	 *
	 * @param arguments malformed public invocation
	 * @throws IOException if output stream cleanup fails
	 */
	@Test(dataProvider = "invalidArguments")
	public void argumentStatus(String[] arguments) throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = ReleaseManifest.run(scope, arguments, err, err);
			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(StandardCharsets.UTF_8), "argumentDiagnostic").contains("release-manifest: error:");
		}
	}

	/**
	 * Accepts bounded observations and derives Python metadata only from an observed Python tool.
	 */
	@Test
	public void acceptsNativeObservations()
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ObjectNode value = nativeObservations(scope);
			ObjectNode environment = environment(value, 0);
			environment.put("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");
			environment.put("AWS_LC_SYS_NO_JITTER_ENTROPY", "1");
			environment.put("CC_unlisted_target", "/opt/cc");
			environment.put("PATH", "");
			environment.put("CARGO_TARGET_DIR", "/var/tmp/target");
			value.get("runner").asObject().putNull("os").putNull("arch").putNull("image_os");
			value.get("runner").asObject().put("image_version", "another image");
			value.get("operations").get(0).get("commands").get(0).asArray().add("/different/checkout/build");
			value.get("tools").get(0).asObject().put("path", "/opt/alternate/rustc").put("version", "another rustc");
			value.get("tools").get(1).asObject().put("status", "unavailable").putNull("path").putNull("version");
			ReleaseManifestNative.validate(value, "aarch64-apple-darwin");
			requireThat(ReleaseManifestNative.pythonVersion(value), "observedPython").isEqualTo("Python 3.14.0");

			ObjectNode python = value.get("tools").get(10).asObject();
			python.put("status", "unavailable");
			ReleaseManifestNative.validate(value, "aarch64-apple-darwin");
			requireThat(ReleaseManifestNative.pythonVersion(value), "unavailablePython").isEmpty();
			python.put("status", "observed").putNull("version");
			ReleaseManifestNative.validate(value, "aarch64-apple-darwin");
			requireThat(ReleaseManifestNative.pythonVersion(value), "unknownPythonVersion").isEmpty();
		}
	}

	/**
	 * Supplies archive parser cases requiring actual tar/gzip interoperability.
	 *
	 * @return archive member paths and whether the archive should be accepted
	 */
	@DataProvider(name = "archiveCases")
	public Object[][] archiveCases()
	{
		return new Object[][]{
			{List.of("./bin//codex"), true},
			{List.of("a/../readme"), false},
			{List.of("/readme"), false},
			{List.of("a\\readme"), false},
			{List.of("symlink"), false},
			{List.of("bin/codex", "./bin//codex"), false}};
	}

	/**
	 * Checks path normalization, bytes and link safety through the actual Commons Compress archive parser.
	 *
	 * @param names archive member names, with symlink selecting an executable symbolic link
	 * @param accepted whether the member inventory is safe
	 * @throws IOException if fixture creation or deletion fails
	 */
	@Test(dataProvider = "archiveCases")
	public void archiveInteroperability(List<String> names, boolean accepted) throws IOException
	{
		Path path = Files.createTempFile("manifest-archive-test-", ".tar.gz");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			if (names.equals(List.of("a\\readme")))
				writeRawBackslashArchive(path);
			else
			{
				try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path))))
				{
					for (String name : names)
					{
						TarArchiveEntry entry;
						if (name.equals("symlink"))
						{
							entry = new TarArchiveEntry("bin/codex", TarConstants.LF_SYMLINK, true);
							entry.setLinkName("target");
						}
						else
						{
							entry = new TarArchiveEntry(name, true);
							entry.setSize(3);
						}
						tar.putArchiveEntry(entry);
						if (!name.equals("symlink"))
							tar.write("abc".getBytes(StandardCharsets.UTF_8));
						tar.closeArchiveEntry();
					}
				}
			}
			JsonNode binaries = null;
			boolean rejected = false;
			try
			{
				binaries = ReleaseManifestArchive.binaries(scope, path);
			}
			catch (IllegalArgumentException _)
			{
				rejected = true;
			}
			requireThat(rejected, "rejected").isEqualTo(!accepted);
			if (accepted)
			{
				requireThat(binaries, "binaries").isNotNull();
				requireThat(binaries.size(), "binaryCount").isEqualTo(1);
				requireThat(binaries.get(0).path("path").asString(), "normalizedPath").isEqualTo("bin/codex");
				requireThat(binaries.get(0).path("size").asLong(), "memberSize").isEqualTo(3L);
				requireThat(binaries.get(0).path("sha256").asString(), "memberDigest").isEqualTo(
					"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
			}
		}
		finally
		{
			Files.deleteIfExists(path);
		}
	}

	/**
	 * Writes the raw archive whose member name contains an unnormalized backslash.
	 *
	 * @param path owned archive path
	 * @throws IOException if writing the archive fixture fails
	 */
	private static void writeRawBackslashArchive(Path path) throws IOException
	{
		Files.write(path, Base64.getDecoder().decode(
			"H4sIAAAAAAACA+3Nuw2DQBQEwFcKJYA4mWZIDkPohE//HESI3EiWZ5JdbbK5n6c8fqb4orp4pXRmcc+ivfRj71LTRlXHA7ZlzXO5jP+Uh3c" +
				"AAAAAAAAAAADwe3bzT75pACgAAA=="));
	}

	/**
	 * Supplies independent malformed native observations from the Python compatibility scenarios.
	 *
	 * @return one named invalid mutation per test invocation
	 */
	@DataProvider(name = "invalidNativeObservations")
	public Object[][] invalidNativeObservations()
	{
		List<String> cases = List.of("missing_runner_field", "operation_order", "oversized_argument", "nul_argument",
			"oversized_environment", "nul_environment", "command_count", "total_command_count", "argument_count",
			"environment_count", "total_environment_count", "extra_field", "wrong_target", "credential_key",
			"aws_lc_key", "aws_fips_key", "unknown_role", "unsafe_path", "duplicate_resource", "negative_size",
			"uppercase_digest", "unsorted_resources", "v8_subpath", "boolean_size", "duplicate_tool", "tool_status",
			"boolean_format", "empty_command", "empty_commands", "noncanonical_path", "bare_environment_prefix",
			"backslash_resource_path");
		Object[][] result = new Object[cases.size()][1];
		for (int index = 0; index < cases.size(); ++index)
			result[index][0] = cases.get(index);
		return result;
	}

	/**
	 * Rejects malformed observations through the native schema's public validation boundary.
	 *
	 * @param mutation named malformed input case
	 */
	@Test(dataProvider = "invalidNativeObservations", expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidNativeObservations(String mutation)
	{
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ObjectNode value = nativeObservations(scope);
			mutateNative(value, mutation);
			ReleaseManifestNative.validate(value, "aarch64-apple-darwin");
		}
	}

	/**
	 * Builds one complete independent diagnostic document with all required tools and both resource roles.
	 *
	 * @param scope test-owned JSON mapper scope
	 * @return valid mutable native observation document
	 */
	private static ObjectNode nativeObservations(DefaultToolingScope scope)
	{
		ObjectNode value = scope.getJsonMapper().createObjectNode();
		value.put("format_version", 1).put("target", "aarch64-apple-darwin");
		value.putObject("runner").put("os", "macOS").put("arch", "ARM64").put("image_os", "macos-15").
			put("image_version", "test image");
		ArrayNode operations = value.putArray("operations");
		for (String name : List.of("build_binaries", "archive_packages"))
		{
			ObjectNode operation = operations.addObject();
			operation.put("operation", name);
			operation.putArray("commands").addArray().add("cargo").add("build");
			operation.putObject("environment").put("PATH", "/usr/bin");
		}
		ArrayNode tools = value.putArray("tools");
		for (String name : List.of("rustc", "cargo", "git", "zig", "cc", "cxx", "linker", "strip", "objcopy",
			"dsymutil", "python", "zstd"))
		{
			ObjectNode tool = tools.addObject();
			tool.put("name", name).put("status", "observed").put("path", "/usr/bin/" + name);
			if (name.equals("python"))
				tool.put("version", "Python 3.14.0");
			else
				tool.put("version", name + " version");
		}
		ArrayNode resources = value.putArray("resources");
		resources.addObject().put("role", "configured_v8_archive").put("path", "libv8.a").put("size", 0).
			put("sha256", "b".repeat(64));
		resources.addObject().put("role", "packaged_resource").put("path", "codex-resources/bwrap").put("size", 13).
			put("sha256", "c".repeat(64));
		return value;
	}

	/**
	 * Selects one operation's mutable diagnostic environment.
	 *
	 * @param value native observation document
	 * @param index operation position
	 * @return that operation's environment object
	 */
	private static ObjectNode environment(ObjectNode value, int index)
	{
		return value.get("operations").get(index).get("environment").asObject();
	}

	/**
	 * Applies one independently invalid schema change to an otherwise valid observation.
	 *
	 * @param value    mutable native observation document
	 * @param mutation named invalid input case
	 */
	private static void mutateNative(ObjectNode value, String mutation)
	{
		ObjectNode operation = value.get("operations").get(0).asObject();
		ObjectNode resource = value.get("resources").get(0).asObject();
		switch (mutation)
		{
			case "missing_runner_field" -> value.get("runner").asObject().remove("arch");
			case "operation_order" -> operation.put("operation", "archive_packages");
			case "oversized_argument" -> operation.get("commands").get(0).asArray().set(0, "x".repeat(65_537));
			case "nul_argument" -> operation.get("commands").get(0).asArray().set(0, "cargo\0build");
			case "oversized_environment" -> environment(value, 0).put("PATH", "x".repeat(65_537));
			case "nul_environment" -> environment(value, 0).put("PATH", "x\0y");
			case "command_count" -> replaceCommands(value, 0, 257, 1);
			case "total_command_count" ->
			{
				replaceCommands(value, 0, 128, 1);
				replaceCommands(value, 1, 129, 1);
			}
			case "argument_count" -> replaceCommands(value, 0, 1, 257);
			case "environment_count" -> replaceEnvironment(value, 0, 257);
			case "total_environment_count" ->
			{
				replaceEnvironment(value, 0, 129);
				replaceEnvironment(value, 1, 128);
			}
			case "extra_field" -> value.put("extra", "outside contract");
			case "wrong_target" -> value.put("target", "aarch64-pc-windows-msvc");
			case "credential_key" -> environment(value, 0).put("AWS_SECRET_ACCESS_KEY", "secret");
			case "aws_lc_key" -> environment(value, 0).put("AWS_LC_SYS_UNAPPROVED", "value");
			case "aws_fips_key" -> environment(value, 0).put("AWS_LC_FIPS_SYS_UNAPPROVED", "value");
			case "unknown_role" -> resource.put("role", "linker_input");
			case "unsafe_path" -> resource.put("path", "../outside");
			case "duplicate_resource" -> value.get("resources").asArray().add(resource.deepCopy());
			case "negative_size" -> resource.put("size", -1);
			case "uppercase_digest" -> resource.put("sha256", "A".repeat(64));
			case "unsorted_resources" ->
			{
				ArrayNode resources = value.get("resources").asArray();
				resources.set(0, resources.get(1));
				resources.set(1, resource);
			}
			case "v8_subpath" -> resource.put("path", "subdir/v8.a");
			case "boolean_size" -> resource.put("size", true);
			case "duplicate_tool" -> value.get("tools").get(11).asObject().put("name", "rustc");
			case "tool_status" -> value.get("tools").get(0).asObject().put("status", "guessed");
			case "boolean_format" -> value.put("format_version", true);
			case "empty_command" -> replaceCommands(value, 0, 1, 0);
			case "empty_commands" -> replaceCommands(value, 0, 0, 0);
			case "noncanonical_path" -> resource.put("path", "a//b");
			case "bare_environment_prefix" -> environment(value, 0).put("CARGO_TARGET_", "value");
			case "backslash_resource_path" -> resource.put("path", "back\\slash");
			default -> throw new IllegalArgumentException("Unknown native mutation: " + mutation);
		}
	}

	/**
	 * Replaces a command list with a controlled number of command vectors and arguments.
	 *
	 * @param value         mutable native observation document
	 * @param index         operation position
	 * @param commandCount  number of vectors
	 * @param argumentCount arguments per vector
	 */
	private static void replaceCommands(ObjectNode value, int index, int commandCount, int argumentCount)
	{
		ArrayNode commands = value.get("operations").get(index).asObject().putArray("commands");
		for (int commandIndex = 0; commandIndex < commandCount; ++commandIndex)
		{
			ArrayNode command = commands.addArray();
			for (int argument = 0; argument < argumentCount; ++argument)
				command.add("cargo");
		}
	}

	/**
	 * Replaces a diagnostic environment with approved names at a controlled entry count.
	 *
	 * @param value mutable native observation document
	 * @param index operation position
	 * @param count number of approved environment entries
	 */
	private static void replaceEnvironment(ObjectNode value, int index, int count)
	{
		ObjectNode environment = value.get("operations").get(index).asObject().putObject("environment");
		for (int entry = 0; entry < count; ++entry)
			environment.put("CARGO_TARGET_" + entry, "");
	}

	/**
	 * Supplies the original command scenarios and later authentication and native-observation additions.
	 *
	 * @return one named independently mutated release per invocation
	 */
	@DataProvider(name = "releaseScenarios")
	public Object[][] releaseScenarios()
	{
		List<String> names = List.of("valid_moved_checkout", "mutated_package", "mutated_patch", "dirty_source",
			"other_source_commit", "forged_source_tree", "changed_manifest_source", "missing_receipt",
			"duplicate_receipt", "missing_payload", "missing_installer", "duplicate_asset", "unsafe_asset",
			"unsafe_patch", "server_missing_binary", "server_binary", "native_binding", "native_optional",
			"missing_native", "invalid_native_no_output", "observed_version", "wrong_repository", "wrong_workflow",
			"extra_payload", "receipt_disagreement", "duplicate_json", "fixed_attestations", "failed_attestation",
			"authenticate_valid", "authenticate_invalid", "authenticate_failure", "checksum_bound",
			"archive_binary_mismatch", "proof_mismatch", "unknown_json_field", "json_bound", "unicode_run_id",
			"utf16_json", "invalid_utf8", "argument_compatibility");
		Object[][] result = new Object[names.size()][1];
		for (int index = 0; index < names.size(); ++index)
			result[index][0] = names.get(index);
		return result;
	}

	/**
	 * Exercises content and policy decisions through the public command with explicit process observations.
	 *
	 * @param scenario independently named compatibility scenario
	 * @throws IOException if test-owned fixture operations fail
	 */
	@Test(dataProvider = "releaseScenarios")
	public void releaseScenario(String scenario) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.exercise(scenario);
		}
	}

	/**
	 * Consumes the actual producer's JSON through the accepted receipt boundary.
	 *
	 * @throws IOException if fixture production or receipt I/O fails
	 */
	@Test
	public void acceptsProducedNativeInputsInReceipt() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String target = "x86_64-unknown-linux-musl";
			JsonNode produced = ReleaseManifestNativeInputsTest.produceForReceipt(fixture.scope, fixture.root,
				target, fixture.root.resolve(target + ".json"));
			fixture.invoke(fixture.receiptArguments(target), 0, "Recorded package receipt");
			JsonNode receipt = fixture.read(fixture.root.resolve(target).resolve(ReleaseManifestSchema.receiptName(target)));
			requireThat(receipt.path("inputs").path("native_build"), "producedNativeInputs").isEqualTo(produced);
			requireThat(receipt.path("inputs").path("python").asString(), "unobservedPython").isEqualTo("");
		}
	}

	/**
	 * Consumes packaged-launcher output through the same accepted schema and actual receipt fixture.
	 *
	 * @param nativeInputs explicit packaged producer output supplied by the boundary harness
	 * @throws IOException if reading or receipt I/O fails
	 */
	public static void acceptsPackagedNativeInputsInReceipt(Path nativeInputs) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			JsonNode produced = ReleaseManifestSchema.read(fixture.scope, nativeInputs);
			String target = produced.path("target").asString();
			ReleaseManifestNative.validate(produced, target);
			Files.copy(nativeInputs, fixture.root.resolve(target + ".json"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			fixture.invoke(fixture.receiptArguments(target), 0, "Recorded package receipt");
			JsonNode receipt = fixture.read(fixture.root.resolve(target).resolve(ReleaseManifestSchema.receiptName(target)));
			requireThat(receipt.path("inputs").path("native_build"), "packagedNativeInputs").isEqualTo(produced);
		}
	}

	/**
	 * Owns a complete method-local release and controlled Git/GitHub process observations.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/** All fixture files and temporary process paths. */
		private final Path root;
		/** Test-owned mapper and tooling services. */
		private final DefaultToolingScope scope = new DefaultToolingScope();
		/** Package release directory. */
		private final Path assets;
		/** Pinned fork data, whose README is interpreted as data. */
		private Path patches;
		/** Patched source files. */
		private Path source;
		/** Raw patch bytes bound to the pinned fork. */
		private final String patchHash;
		/** Captured direct argument vectors. */
		private final List<List<String>> commands = new ArrayList<>();
		/** Independently observed source HEAD. */
		private String sourceCommit = SOURCE;
		/** Independently observed source tree. */
		private String sourceTree = TREE;
		/** Uncommitted source status observation. */
		private String sourceStatus = "";
		/** Verifier completion status observation. */
		private int verifierStatus;

		/**
		 * Creates six receipts through the public Java operation and then assembles the release.
		 *
		 * @throws IOException if fixture files cannot be created
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("manifest-command-test-");
			assets = Files.createDirectory(root.resolve("assets"));
			patches = Files.createDirectory(root.resolve("fork"));
			source = Files.createDirectory(root.resolve("source"));
			Files.createDirectories(patches.resolve("patches"));
			Files.createDirectories(source.resolve("codex-rs"));
			Files.writeString(patches.resolve("patches/README.md"), "1. [One](1.patch)\n");
			Files.writeString(patches.resolve("patches/1.patch"), "independent raw patch fixture\n");
			Files.writeString(source.resolve("codex-rs/Cargo.lock"), "version = 3\n");
			patchHash = Sha256.digest(patches.resolve("patches/1.patch")).substring(0, 40);
			try
			{
				for (String target : ReleaseManifestSchema.TARGETS)
				{
					Path lane = Files.createDirectory(root.resolve(target));
					String suffix = "";
					if (target.contains("windows"))
						suffix = ".exe";
					archive(lane.resolve(ReleaseManifestSchema.packageName(target)), "bin/codex" + suffix);
					archive(lane.resolve(ReleaseManifestSchema.symbolsName(target)), "debug/symbols");
					archive(lane.resolve("codex-app-server-package-" + target + ".tar.gz"), "bin/codex-app-server" + suffix);
					Files.writeString(lane.resolve(ReleaseManifestSchema.proofName(target)), "codex-cli 1.2.3+4\n");
					if (target.equals("x86_64-unknown-linux-musl"))
					{
						Files.writeString(lane.resolve("install.sh"), "installer\n");
						Files.writeString(lane.resolve("install.ps1"), "installer\n");
					}
					ObjectNode nativeBuild = nativeObservations(scope).put("target", target);
					ReleaseManifestSchema.write(scope, root.resolve(target + ".json"), nativeBuild);
					invoke(receiptArguments(target), 0, "Recorded package receipt for " + target);
					try (Stream<Path> files = Files.list(lane))
					{
						for (Path file : files.toList())
							Files.copy(file, assets.resolve(file.getFileName()));
					}
				}
				invoke(assemblyArguments(), 0, "Assembled six-target release manifest and SHA256SUMS");
				commands.clear();
			}
			catch (IOException | RuntimeException | Error failure)
			{
				close();
				throw failure;
			}
		}

		/**
		 * Creates a real gzip/tar archive, whose bytes cross the archive interoperability boundary.
		 *
		 * @param path output archive
		 * @param name executable or symbol member path
		 * @throws IOException if writing fails
		 */
		private static void archive(Path path, String name) throws IOException
		{
			byte[] bytes = "fixture executable bytes\n".getBytes(StandardCharsets.UTF_8);
			try (TarArchiveOutputStream output = new TarArchiveOutputStream(
				new GZIPOutputStream(Files.newOutputStream(path))))
			{
				TarArchiveEntry member = new TarArchiveEntry(name);
				member.setSize(bytes.length);
				output.putArchiveEntry(member);
				output.write(bytes);
				output.closeArchiveEntry();
			}
		}

		/**
		 * Supplies explicit receipts arguments for one owned native lane.
		 *
		 * @param target native target
		 * @return direct command arguments
		 */
		private String[] receiptArguments(String target)
		{
			Path lane = root.resolve(target);
			return new String[]{"receipt", "--source", source.toString(), "--patch-repo", patches.toString(), "--assets",
				lane.toString(), "--output", lane.resolve(ReleaseManifestSchema.receiptName(target)).toString(),
				"--target", target, "--version", "1.2.3+4", "--build-number", "4", "--upstream-tag", "rust-v1.2.3",
				"--upstream-commit", UPSTREAM, "--fork-commit", FORK, "--run-id", "123", "--run-attempt", "1",
				"--native-inputs", root.resolve(target + ".json").toString()};
		}

		/**
		 * Returns release assembly arguments.
		 *
		 * @return public assembly command
		 */
		private String[] assemblyArguments()
		{
			return new String[]{"assemble", "--assets", assets.toString(), "--receipts", assets.toString(),
				"--output", assets.resolve("release-manifest.json").toString()};
		}

		/**
		 * Returns verification arguments including optional hosted attestations.
		 *
		 * @param attest whether the verifier must be invoked
		 * @return public verification arguments
		 */
		private String[] verifyArguments(boolean attest)
		{
			List<String> result = new ArrayList<>(List.of("verify", "--release-dir", assets.toString(), "--patch-repo",
				patches.toString(), "--upstream-source", source.toString()));
			if (attest)
				result.add("--verify-attestations");
			return result.toArray(String[]::new);
		}

		/**
		 * Invokes the command with a controlled boundary and asserts status and completion/diagnostic text.
		 *
		 * @param arguments public operation arguments
		 * @param expectedStatus expected completion status
		 * @param expectedText required stream text
		 */
		private void invoke(String[] arguments, int expectedStatus, String expectedText)
		{
			try (ByteArrayOutputStream output = new ByteArrayOutputStream();
				ByteArrayOutputStream errors = new ByteArrayOutputStream();
				PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int status = ReleaseManifest.run(scope, arguments, out, err, this::capture,
					new ReleaseManifest.Environment(root, root, "fixture host", Map.of()));
				try
				{
					requireThat(status, "status").isEqualTo(expectedStatus);
				}
				catch (IllegalArgumentException failure)
				{
					throw new IllegalArgumentException("Unexpected command status: " +
						errors.toString(StandardCharsets.UTF_8), failure);
				}
				String actual = output.toString(StandardCharsets.UTF_8);
				if (expectedStatus != 0)
					actual = errors.toString(StandardCharsets.UTF_8);
				requireThat(actual, "operationText").contains(expectedText);
			}
			catch (IOException failure)
			{
				throw new IllegalStateException("Could not close test-owned output streams", failure);
			}
		}

		/**
		 * Returns controlled process observations rather than executing Git or an authenticated hosted verifier.
		 *
		 * @param command direct executable and arguments
		 * @param workingDirectory supplied child working directory
		 * @param temporaryDirectory supplied capture parent
		 * @param environment supplied child overrides
		 * @return controlled operation outcome
		 * @throws IOException if a test-owned hash cannot be read
		 */
		private SystemCommands.Result capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException
		{
			requireThat(workingDirectory, "workingDirectory").isEqualTo(root);
			requireThat(temporaryDirectory, "temporaryDirectory").isEqualTo(root);
			requireThat(environment, "environment").isEmpty();
			commands.add(List.copyOf(command));
			if (command.getFirst().equals("gh"))
				return new SystemCommands.Result(verifierStatus, "", "sensitive captured verifier diagnostic");
			String output;
			if (command.equals(List.of("git", "--version")))
				output = "git fixture version";
			else if (command.get(1).equals("clone"))
				output = "";
			else
			{
				List<String> operation = command.subList(3, command.size());
				output = switch (operation.getFirst())
				{
					case "rev-parse" ->
					{
						String query = operation.get(1);
						if (query.contains(":patches/"))
							yield patchHash;
						if (query.equals("HEAD^{tree}"))
							yield sourceTree;
						if (query.endsWith("^{commit}"))
							yield UPSTREAM;
						if (Path.of(command.get(2)).equals(patches))
							yield FORK;
						yield sourceCommit;
					}
					case "hash-object" -> Sha256.digest(Path.of(operation.get(1))).substring(0, 40);
					case "show" -> "1. [One](1.patch)";
					case "status" -> sourceStatus;
					case "rev-list" -> "1";
					case "write-tree" -> TREE;
					case "merge-base", "checkout", "-c" -> "";
					default -> throw new IllegalArgumentException("Unexpected fixture Git operation: " + operation);
				};
			}
			return new SystemCommands.Result(0, output + "\n", "");
		}

		/**
		 * Reads a mutable owned JSON document.
		 *
		 * @param path document path
		 * @return mutable object
		 * @throws IOException if reading fails
		 */
		private ObjectNode read(Path path) throws IOException
		{
			return ReleaseManifestSchema.read(scope, path).asObject();
		}

		/**
		 * Rewrites checksum bytes after a deliberate consistent metadata change.
		 *
		 * @throws IOException if reading or writing fails
		 */
		private void refreshChecksums() throws IOException
		{
			try (Stream<Path> files = Files.list(assets);
				BufferedWriter writer = Files.newBufferedWriter(assets.resolve("SHA256SUMS")))
			{
				for (Path path : files.sorted().toList())
				{
					if (!path.getFileName().toString().equals("SHA256SUMS"))
						writer.write(Sha256.digest(path) + "  " + path.getFileName() + "\n");
				}
			}
		}

		/**
		 * Rewrites an owned receipt and binds its changed bytes into the manifest for deeper boundary checks.
		 *
		 * @param target native receipt target
		 * @param receipt changed receipt
		 * @throws IOException if reading or writing fails
		 */
		private void rewriteReceipt(String target, ObjectNode receipt) throws IOException
		{
			Path path = assets.resolve(ReleaseManifestSchema.receiptName(target));
			ReleaseManifestSchema.write(scope, path, receipt);
			ObjectNode manifest = read(assets.resolve("release-manifest.json"));
			for (JsonNode entry : manifest.path("artifacts"))
			{
				if (entry.path("name").asString().equals(path.getFileName().toString()))
					entry.asObject().put("sha256", Sha256.digest(path)).put("size", Files.size(path));
			}
			ReleaseManifestSchema.write(scope, assets.resolve("release-manifest.json"), manifest);
			refreshChecksums();
		}

		/**
		 * Applies a named independent corruption or positive compatibility transformation.
		 *
		 * @param scenario named test case
		 * @throws IOException if fixture mutation fails
		 */
		private void exercise(String scenario) throws IOException
		{
			String target = ReleaseManifestSchema.TARGETS.getFirst();
			Path manifestPath = assets.resolve("release-manifest.json");
			ObjectNode manifest = read(manifestPath);
			ObjectNode receipt = read(assets.resolve(ReleaseManifestSchema.receiptName(target)));
			String diagnostic = mutate(scenario, target, manifest, receipt);
			if (Set.of("duplicate_asset", "unsafe_asset", "unsafe_patch", "changed_manifest_source", "wrong_repository",
				"wrong_workflow", "unknown_json_field").contains(scenario))
				ReleaseManifestSchema.write(scope, manifestPath, manifest);
			if (Set.of("missing_receipt", "duplicate_receipt", "missing_payload", "missing_installer",
				"receipt_disagreement").contains(scenario))
				invoke(assemblyArguments(), 1, diagnostic);
			else if (Set.of("server_missing_binary", "native_binding", "native_optional", "invalid_native_no_output",
				"unicode_run_id", "argument_compatibility").
				contains(scenario))
				receiptScenario(scenario, target, diagnostic);
			else if (scenario.startsWith("authenticate_"))
			{
				int status = 0;
				if (!scenario.equals("authenticate_valid"))
					status = 1;
				invoke(new String[]{"authenticate", "--manifest", manifestPath.toString()}, status, diagnostic);
				if (scenario.equals("authenticate_invalid"))
					requireThat(commands, "commandsBeforeInvalidSchema").isEmpty();
				else
					assertPolicy(1);
			}
			else
			{
				int status = 1;
				if (Set.of("valid_moved_checkout", "server_binary", "fixed_attestations").contains(scenario))
					status = 0;
				boolean attest = scenario.equals("fixed_attestations") || scenario.equals("failed_attestation");
				invoke(verifyArguments(attest), status, diagnostic);
				if (scenario.equals("fixed_attestations"))
					assertPolicy(manifest.path("artifacts").size() + 2);
			}
		}

		/**
		 * Checks fixed policy argument vectors independently of the production command builder.
		 *
		 * @param count required number of authenticated files
		 */
		private void assertPolicy(int count)
		{
			List<List<String>> attestations = commands.stream().filter(command -> command.getFirst().equals("gh")).toList();
			requireThat(attestations.size(), "attestationCount").isEqualTo(count);
			for (List<String> command : attestations)
			{
				requireThat(command.subList(0, 3), "attestationPrefix").isEqualTo(List.of("gh", "attestation", "verify"));
				requireThat(command.subList(4, command.size()), "fixedPolicy").isEqualTo(List.of("--repo",
					"holdmyspot-com/codex-unleashed", "--signer-workflow",
					"holdmyspot-com/codex-unleashed/.github/workflows/upstream-auto-release.yml"));
			}
		}

		/**
		 * Applies scenario-specific mutations and returns the required diagnostic or completion.
		 *
		 * @param scenario test case
		 * @param target first receipt target
		 * @param manifest mutable manifest
		 * @param receipt mutable receipt
		 * @return required operation output fragment
		 * @throws IOException if fixture mutation fails
		 */
		private String mutate(String scenario, String target, ObjectNode manifest, ObjectNode receipt) throws IOException
		{
			return switch (scenario)
			{
				case "valid_moved_checkout" ->
				{
					patches = Files.move(patches, root.resolve("moved fork"));
					source = Files.move(source, root.resolve("moved source"));
					yield "GitHub attestations were not checked";
				}
				case "mutated_package" ->
				{
					Files.writeString(assets.resolve(ReleaseManifestSchema.packageName(target)), "changed",
						StandardOpenOption.APPEND);
					yield "Asset digest or size mismatch";
				}
				case "mutated_patch" ->
				{
					Files.writeString(patches.resolve("patches/1.patch"), "changed");
					yield "Patch differs from fork commit";
				}
				case "dirty_source" ->
				{
					sourceStatus = " M source.txt";
					yield "Source checkout must be clean";
				}
				case "other_source_commit" ->
				{
					sourceCommit = "e".repeat(40);
					yield "Source HEAD, tree or Cargo.lock differs";
				}
				case "forged_source_tree" ->
				{
					forgeSource();
					yield "Source tree is not pinned upstream plus the ordered raw patches";
				}
				case "changed_manifest_source" ->
				{
					manifest.path("source").asObject().put("tree", "e".repeat(40));
					yield "SHA256SUMS mismatch";
				}
				case "missing_receipt" ->
				{
					Files.delete(assets.resolve(ReleaseManifestSchema.receiptName(target)));
					yield "Exactly six unique target receipts";
				}
				case "duplicate_receipt" ->
				{
					Files.copy(assets.resolve(ReleaseManifestSchema.receiptName(target)),
						assets.resolve("package-receipt-duplicate.json"));
					yield "Receipt filename differs from target";
				}
				case "missing_payload" ->
				{
					Files.delete(assets.resolve(ReleaseManifestSchema.packageName(target)));
					yield "Missing regular asset";
				}
				case "missing_installer" ->
				{
					String linux = "x86_64-unknown-linux-musl";
					Path path = assets.resolve(ReleaseManifestSchema.receiptName(linux));
					ObjectNode linuxReceipt = read(path);
					ArrayNode artifacts = linuxReceipt.path("artifacts").asArray();
					for (int index = artifacts.size() - 1; index >= 0; --index)
					{
						if (artifacts.get(index).path("kind").asString().equals("installer"))
							artifacts.remove(index);
					}
					ReleaseManifestSchema.write(scope, path, linuxReceipt);
					yield "Both installers are required";
				}
				case "duplicate_asset" ->
				{
					manifest.path("artifacts").asArray().add(manifest.path("artifacts").get(0).deepCopy());
					yield "Duplicate manifest asset name";
				}
				case "unsafe_asset" ->
				{
					manifest.path("artifacts").get(0).asObject().put("name", "../unsafe");
					yield "Unsafe asset name";
				}
				case "unsafe_patch" ->
				{
					manifest.path("patches").get(0).asObject().put("path", "patches/../escape.patch");
					yield "Unsafe patch path";
				}
				case "server_binary" -> "GitHub attestations were not checked";
				case "server_missing_binary", "native_binding", "native_optional", "invalid_native_no_output",
					"unicode_run_id", "argument_compatibility" -> scenario;
				case "missing_native" ->
				{
					receipt.path("inputs").asObject().remove("native_build");
					rewriteReceipt(target, receipt);
					yield "Invalid observed inputs fields";
				}
				case "observed_version" ->
				{
					receipt.put("observed_cli_version", "codex-cli 9.9.9+4");
					rewriteReceipt(target, receipt);
					yield "Packaged CLI version differs";
				}
				case "wrong_repository" ->
				{
					manifest.put("repository", "untrusted/repository");
					yield "Release repository violates consumer policy";
				}
				case "wrong_workflow" ->
				{
					manifest.put("signer_workflow", "untrusted/workflow.yml");
					yield "Signer workflow violates consumer policy";
				}
				case "extra_payload" ->
				{
					Files.writeString(assets.resolve("unexpected.txt"), "unexpected");
					yield "Release directory contains missing or uninventoried assets";
				}
				case "receipt_disagreement" ->
				{
					receipt.path("source").asObject().put("tree", "e".repeat(40));
					ReleaseManifestSchema.write(scope, assets.resolve(ReleaseManifestSchema.receiptName(target)), receipt);
					yield "Receipts disagree";
				}
				case "duplicate_json" ->
				{
					String json = Files.readString(assets.resolve("release-manifest.json"));
					Files.writeString(assets.resolve("release-manifest.json"),
						"{\"repository\":\"duplicate\"," + json.substring(1));
					yield "Duplicate";
				}
				case "fixed_attestations" -> "Verified checksums, reconstructed source and GitHub attestations";
				case "failed_attestation", "authenticate_failure" ->
				{
					verifierStatus = 9;
					yield "gh attestation failed (exit 9)";
				}
				case "authenticate_valid" -> "Authenticated release manifest";
				case "authenticate_invalid" ->
				{
					Files.writeString(assets.resolve("release-manifest.json"), "{}");
					yield "Invalid manifest fields";
				}
				case "checksum_bound" ->
				{
					Files.writeString(assets.resolve("SHA256SUMS"), "x".repeat(257));
					yield "Checksum record is too long";
				}
				case "archive_binary_mismatch" ->
				{
					receipt.path("binaries").get(0).asObject().put("sha256", "e".repeat(64));
					rewriteReceipt(target, receipt);
					yield "Archive binary hashes differ from receipt";
				}
				case "proof_mismatch" ->
				{
					Path proof = assets.resolve(ReleaseManifestSchema.proofName(target));
					Files.writeString(proof, "codex-cli 9.9.9+4\n");
					for (JsonNode entry : manifest.path("artifacts"))
					{
						if (entry.path("name").asString().equals(proof.getFileName().toString()))
							entry.asObject().put("sha256", Sha256.digest(proof));
					}
					ReleaseManifestSchema.write(scope, assets.resolve("release-manifest.json"), manifest);
					refreshChecksums();
					yield "Version proof differs from receipt";
				}
				case "unknown_json_field" ->
				{
					manifest.put("unexpected", true);
					yield "Invalid manifest fields";
				}
				case "json_bound" ->
				{
					Files.writeString(assets.resolve("release-manifest.json"), " ".repeat(4 * 1024 * 1024 + 1));
					yield "JSON document exceeds";
				}
				case "utf16_json" ->
				{
					Files.write(assets.resolve("release-manifest.json"), "{}".getBytes(StandardCharsets.UTF_16));
					yield "Release verification failed:";
				}
				case "invalid_utf8" ->
				{
					Files.write(assets.resolve("release-manifest.json"), new byte[]{(byte) 0xc0, (byte) 0xaf});
					yield "Release verification failed:";
				}
				default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
			};
		}

		/**
		 * Makes every receipt and the manifest agree on a forged source while retaining independent replay output.
		 *
		 * @throws IOException if metadata rewriting fails
		 */
		private void forgeSource() throws IOException
		{
			sourceTree = "f".repeat(40);
			sourceCommit = "e".repeat(40);
			for (String target : ReleaseManifestSchema.TARGETS)
			{
				ObjectNode receipt = read(assets.resolve(ReleaseManifestSchema.receiptName(target)));
				receipt.path("source").asObject().put("commit", sourceCommit).put("tree", sourceTree);
				rewriteReceipt(target, receipt);
			}
			ObjectNode manifest = read(assets.resolve("release-manifest.json"));
			manifest.path("source").asObject().put("commit", sourceCommit).put("tree", sourceTree);
			ReleaseManifestSchema.write(scope, assets.resolve("release-manifest.json"), manifest);
			refreshChecksums();
		}

		/**
		 * Exercises package recording, including atomic refusal to publish malformed native observations.
		 *
		 * @param scenario receipt test case
		 * @param target native target
		 * @param diagnostic expected test branch
		 * @throws IOException if fixture changes fail
		 */
		private void receiptScenario(String scenario, String target, String diagnostic) throws IOException
		{
			requireThat(diagnostic, "scenarioDiagnostic").isEqualTo(scenario);
			Path output = root.resolve(target).resolve(ReleaseManifestSchema.receiptName(target));
			Files.delete(output);
			if (scenario.equals("server_missing_binary"))
			{
				archive(root.resolve(target).resolve("codex-app-server-package-" + target + ".tar.gz"), "unrelated");
				invoke(receiptArguments(target), 1, "App-server archive contains no recorded codex-app-server executable");
				requireThat(Files.exists(output), "publishedInvalidReceipt").isFalse();
				return;
			}
			ObjectNode nativeBuild = read(root.resolve(target + ".json"));
			if (scenario.equals("invalid_native_no_output"))
				nativeBuild.remove("runner");
			else if (scenario.equals("native_optional"))
			{
				nativeBuild.path("tools").get(10).asObject().put("status", "not_applicable").putNull("version");
				nativeBuild.path("runner").asObject().putNull("image_os").putNull("image_version");
			}
			ReleaseManifestSchema.write(scope, root.resolve(target + ".json"), nativeBuild);
			if (scenario.equals("invalid_native_no_output"))
			{
				invoke(receiptArguments(target), 1, "Invalid native_build fields");
				requireThat(Files.exists(output), "publishedInvalidReceipt").isFalse();
				requireThat(commands, "effectsBeforeNativeValidation").isEmpty();
				return;
			}
			String[] arguments = receiptArguments(target);
			if (scenario.equals("unicode_run_id"))
			{
				int index = List.of(arguments).indexOf("--run-id");
				arguments[index + 1] = "²①";
			}
			if (scenario.equals("argument_compatibility"))
			{
				int buildIndex = List.of(arguments).indexOf("--build-number");
				arguments[buildIndex] = "--build-n";
				arguments[buildIndex + 1] = " +0_4 ";
			}
			invoke(arguments, 0, "Recorded package receipt");
			ObjectNode receipt = read(output);
			requireThat(receipt.path("inputs").path("native_build"), "boundNativeObservation").isEqualTo(nativeBuild);
			String expectedPython = "Python 3.14.0";
			if (scenario.equals("native_optional"))
				expectedPython = "";
			requireThat(receipt.path("inputs").path("python").asString(), "pythonObservation").isEqualTo(expectedPython);
		}

		/**
		 * Removes every method-owned fixture and closes its borrowed-service owner.
		 *
		 * @throws IOException if fixture deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			try (scope)
			{
				PathUtils.deleteDirectory(root, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
					StandardDeleteOption.OVERRIDE_READ_ONLY);
			}
		}
	}
}
