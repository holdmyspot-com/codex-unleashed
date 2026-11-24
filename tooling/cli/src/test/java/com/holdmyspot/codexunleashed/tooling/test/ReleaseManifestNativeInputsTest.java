package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestNative;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestNativeInputs;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.DataProvider;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises completed diagnostic records, explicit resource bytes and fixed current-boundary tool observations.
 */
public final class ReleaseManifestNativeInputsTest
{
	/** Supported fixture target. */
	private static final String TARGET = "x86_64-unknown-linux-musl";

	/**
	 * Creates independent test instances.
	 */
	public ReleaseManifestNativeInputsTest()
	{
	}

	/**
	 * Converts completed records without losing empty strings, embedded lines or operation order.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void convertsCompletedOperationRecords() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.invoke(0);
			JsonNode value = fixture.read();
			ReleaseManifestNative.validate(value, TARGET);
			requireThat(value.path("operations").get(0).path("commands").get(0).get(1).asString(), "emptyArgument").
				isEqualTo("");
			requireThat(value.path("operations").get(0).path("commands").get(0).get(2).asString(), "lineArgument").
				isEqualTo("space and\nline\rvalue");
			requireThat(value.path("operations").get(0).path("environment").path("RUSTFLAGS").asString(), "emptyValue").
				isEqualTo("");
			requireThat(value.path("operations").get(0).path("environment").has("SDKROOT"), "absentValue").isFalse();
			requireThat(value.path("operations").get(1).path("operation").asString(), "archiveOperation").
				isEqualTo("archive_packages");
			requireThat(value.path("runner").path("arch").isNull(), "omittedArchitecture").isTrue();
			fixture.arguments.addAll(List.of("--runner-arch", "", "--image-os", "", "--image-version", ""));
			fixture.invoke(0);
			requireThat(fixture.read().path("runner").path("arch").asString(), "suppliedEmptyArchitecture").isEqualTo("");
		}
	}

	/**
	 * Supplies independently malformed raw record and resource cases.
	 *
	 * @return malformed fixture mutations
	 */
	@DataProvider(name = "malformedRecords")
	public Object[][] malformedRecords()
	{
		List<String> names = List.of("missing_completion", "false_completion", "completion_crlf", "wrong_format",
			"wrong_operation", "wrong_target", "wrong_runner", "different_binaries", "empty_binaries", "metadata_lines",
			"metadata_no_lf", "gapped_commands", "unexpected_command", "empty_commands", "empty_vector", "truncated_nul",
			"invalid_utf8", "odd_environment", "duplicate_environment", "unapproved_environment", "long_argument",
			"long_environment", "argument_count", "command_count", "total_command_count", "environment_count",
			"total_environment_count", "raw_budget", "json_budget", "missing_resource_directory", "resource_mismatch",
			"symbolic_record", "symbolic_root", "symbolic_commands", "symbolic_resource", "symbolic_output",
			"missing_v8", "symbolic_v8", "invalid_resource_path");
		Object[][] result = new Object[names.size()][1];
		for (int index = 0; index < names.size(); ++index)
			result[index][0] = names.get(index);
		return result;
	}

	/**
	 * Rejects malformed inputs and preserves earlier output bytes.
	 *
	 * @param mutation one malformed input case
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "malformedRecords")
	public void rejectsMalformedOperationRecords(String mutation) throws IOException
	{
		if (mutation.equals("invalid_resource_path") &&
			FileSystems.getDefault().getSeparator().equals("\\"))
		{
			throw new SkipException("Backslash separates path components on this filesystem, so the exact filename " +
				"cannot exist; backslash_resource_path covers rejection on every platform.");
		}
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.output, "previous output");
			fixture.mutate(mutation);
			fixture.invoke(1);
			requireThat(Files.readString(fixture.output), "previousOutput").isEqualTo("previous output");
		}
	}

	/**
	 * Supplies syntax failures which must not touch inputs or processes.
	 *
	 * @return malformed option tokens
	 */
	@DataProvider(name = "invalidOptions")
	public Object[][] invalidOptions()
	{
		return new Object[][]{{new String[0]}, {new String[]{"--unknown", "value"}},
			{new String[]{"--target"}}, {new String[]{"--target", ""}},
			{new String[]{"--target", TARGET, "--target", TARGET}}, {new String[]{"--target=bad"}},
			{new String[]{"positional"}}, {new String[]{"--output", "\0"}},
			{new String[]{"--build-result", "build", "--target", TARGET, "--output", "out", "--archive-result", "a"}},
			{new String[]{"--build-result", "build", "--target", TARGET, "--output", "out", "--compile-inputs", "c"}}};
	}

	/**
	 * Separates command syntax rejection from content rejection.
	 *
	 * @param arguments malformed option tokens
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "invalidOptions")
	public void rejectsInvalidOptions(String[] arguments) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.arguments.clear();
			fixture.arguments.addAll(List.of(arguments));
			fixture.invoke(2);
			requireThat(fixture.commands, "probeEffects").isEmpty();
			requireThat(Files.exists(fixture.output), "publishedOutput").isFalse();
		}
	}

	/**
	 * Records a host that only compiled the executables, then embeds its document in the packaging host's document.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void embedsCompileHost() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path v8 = Files.writeString(fixture.root.resolve("librusty_v8.a"), "v8 bytes");
			Path compiled = fixture.root.resolve("compile-inputs.json");
			List<String> packaging = List.copyOf(fixture.arguments);
			fixture.arguments.clear();
			fixture.arguments.addAll(List.of("--build-result", fixture.build.toString(), "--target", TARGET, "--output",
				compiled.toString(), "--runner-arch", "ARM64", "--configured-v8-archive", v8.toString()));
			fixture.invoke(0);
			JsonNode compile = ReleaseManifestSchema.read(fixture.scope, compiled);
			ReleaseManifestNative.validateCompile(compile, TARGET);
			requireThat(compile.path("operations").size(), "compileOperations").isEqualTo(1);
			requireThat(compile.path("resources").get(0).path("role").asString(), "compileResource").
				isEqualTo("configured_v8_archive");

			fixture.arguments.clear();
			fixture.arguments.addAll(packaging);
			fixture.arguments.addAll(List.of("--runner-arch", "X64", "--compile-inputs", compiled.toString()));
			fixture.invoke(0);
			JsonNode value = fixture.read();
			ReleaseManifestNative.validate(value, TARGET);
			requireThat(value.path("runner").path("arch").asString(), "packagingArchitecture").isEqualTo("X64");
			requireThat(value.path("compile_host").path("runner").path("arch").asString(), "compileArchitecture").
				isEqualTo("ARM64");
			requireThat(ReleaseManifestSchema.same(value.path("compile_host").path("tools"), compile.path("tools")),
				"compileTools").isTrue();
			requireThat(value.path("operations").size(), "packagingOperations").isEqualTo(2);

			Path packaged = fixture.root.resolve("packaged-inputs.json");
			Files.copy(fixture.output, packaged);
			Files.delete(fixture.output);
			fixture.arguments.set(fixture.arguments.indexOf(compiled.toString()), packaged.toString());
			fixture.invoke(1);
			requireThat(Files.exists(fixture.output), "packagingAsCompileHost").isFalse();
		}
	}

	/**
	 * Keeps hostile historical text inert while observing only the fixed executable names.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsRecordedCommandsInert() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String hostile = "$(touch should-not-exist); echo private";
			nul(fixture.build.resolve("commands/0001.argv"), "sh", "-c", hostile);
			nul(fixture.build.resolve("environment.nul"), "RUSTY_V8_ARCHIVE", hostile, "RUSTC", hostile);
			fixture.invoke(0);
			requireThat(fixture.read().path("operations").get(0).path("commands").get(0).get(2).asString(),
				"historicalArgument").isEqualTo(hostile);
			for (List<String> command : fixture.commands)
			{
				requireThat(command.size(), "probeArity").isEqualTo(5);
				requireThat(command.get(2), "fixedLookup").isEqualTo("type -P -- \"$1\"");
				requireThat(command.contains(hostile), "historicalProbeInput").isFalse();
			}
			requireThat(Files.exists(fixture.root.resolve("should-not-exist")), "historicalExecution").isFalse();
		}
	}

	/**
	 * Preserves fresh tool distinctions, fixed Windows mappings and version output semantics.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void recordsCurrentToolObservations() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.capture = (command, working, temporary, variables) ->
			{
				fixture.commands.add(command);
				if (command.get(4).equals("zig"))
					return new SystemCommands.Result(1, "", "");
				if (command.get(2).startsWith("type"))
					return new SystemCommands.Result(0, "/tools with spaces/" + command.get(4) + "\n", "");
				if (command.get(4).equals("cl"))
					return new SystemCommands.Result(2, "", "not a version");
				if (command.get(4).equals("link"))
					return new SystemCommands.Result(0, "", "link version\r\n");
				return new SystemCommands.Result(0, "  version\nsecond line \r\n", "ignored");
			};
			fixture.runner("Windows");
			fixture.invoke(0);
			JsonNode tools = fixture.read().path("tools");
			requireThat(tools.get(0).path("version").asString(), "versionWhitespace").
				isEqualTo("  version\nsecond line ");
			requireThat(tools.get(3).path("status").asString(), "unavailableZig").isEqualTo("unavailable");
			requireThat(tools.get(4).path("version").isNull(), "nonzeroVersion").isTrue();
			requireThat(tools.get(4).path("path").asString(), "observedCompiler").contains("cl");
			requireThat(tools.get(6).path("version").asString(), "stderrVersion").isEqualTo("link version");
			requireThat(tools.get(9).path("status").asString(), "inapplicableDsymutil").isEqualTo("not_applicable");
			requireThat(tools.get(10).path("name").asString(), "pythonRole").isEqualTo("python");
			requireThat(fixture.commands.stream().anyMatch(command -> command.get(4).equals("python3")),
				"pythonExecutable").isTrue();
			fixture.runner("macOS");
			fixture.invoke(0);
			requireThat(fixture.read().path("tools").get(9).path("status").asString(), "macDsymutil").isEqualTo("observed");
			requireThat(fixture.commands.stream().anyMatch(command -> command.get(4).equals("c++")),
				"unixCxxExecutable").isTrue();
		}
	}

	/**
	 * Supplies infrastructure failures that must not be mistaken for unavailable tools.
	 *
	 * @return captured lookup failure modes
	 */
	@DataProvider(name = "probeFailures")
	public Object[][] probeFailures()
	{
		return new Object[][]{{"startup"}, {"status"}, {"multiline"}, {"empty"}, {"error_output"}, {"path_on_failure"}};
	}

	/**
	 * Preserves prior output when tool observation infrastructure fails.
	 *
	 * @param mode injected boundary failure
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "probeFailures")
	public void rejectsProbeInfrastructureFailure(String mode) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.output, "previous output");
			fixture.capture = (command, working, temporary, variables) -> switch (mode)
			{
				case "startup" -> throw new IOException("Injected Bash startup failure");
				case "status" -> new SystemCommands.Result(127, "", "");
				case "multiline" -> new SystemCommands.Result(0, "/one\n/two\n", "");
				case "empty" -> new SystemCommands.Result(0, "", "");
				case "error_output" -> new SystemCommands.Result(1, "", "Bash startup failed");
				case "path_on_failure" -> new SystemCommands.Result(1, "/one\n", "");
				default -> throw new IllegalArgumentException("Unknown fixture mode");
			};
			fixture.invoke(1);
			requireThat(Files.readString(fixture.output), "priorOutput").isEqualTo("previous output");
		}
	}

	/**
	 * Includes final legal bytes, equal resource deduplication, explicit V8 and Unicode scalar ordering.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void hashesFinalizedResources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path v8 = Files.writeString(fixture.root.resolve("v8 archive.a"), "configured archive");
			fixture.arguments.addAll(List.of("--configured-v8-archive", v8.toString()));
			Files.writeString(fixture.cliResources.resolve("\uE000"), "first");
			Files.writeString(fixture.cliResources.resolve("\uD800\uDC00"), "second");
			fixture.invoke(0);
			JsonNode resources = fixture.read().path("resources");
			requireThat(resources.size(), "resourceCount").isEqualTo(4);
			requireThat(resources.get(0).path("path").asString(), "v8Basename").isEqualTo("v8 archive.a");
			requireThat(resources.get(0).path("sha256").asString(), "v8Digest").isEqualTo(Sha256.digest(v8));
			requireThat(resources.get(1).path("path").asString(), "finalLegalPath").
				isEqualTo("codex-resources/legal/LICENSE.md");
			requireThat(resources.get(1).path("size").asLong(), "legalSize").isEqualTo(12L);
			requireThat(resources.get(2).path("path").asString(), "scalarFirst").isEqualTo("codex-resources/\uE000");
			requireThat(resources.get(3).path("path").asString(), "scalarSecond").
				isEqualTo("codex-resources/\uD800\uDC00");
		}
	}

	/**
	 * Includes final primary-only voice runtime resources once without requiring them in app-server.
	 *
	 * @throws IOException if fixture files or native input output cannot be read
	 */
	@Test
	public void includesPrimaryOnlyVoiceResources()
		throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path voiceManifest = Files.createDirectories(fixture.cliResources.resolve("voice")).resolve("manifest.json");
			byte[] voiceBytes = "{\"format_version\":1,\"target\":\"voice-fixture\"}\n".
				getBytes(StandardCharsets.UTF_8);
			Files.write(voiceManifest, voiceBytes);
			requireThat(Files.exists(fixture.serverResources.resolve("voice")), "appServerVoiceAbsent").isFalse();
			fixture.invoke(0);
			JsonNode resources = fixture.read().path("resources");
			int voiceManifestCount = 0;
			for (JsonNode resource : resources)
			{
				if (resource.path("path").asString().equals("codex-resources/voice/manifest.json"))
				{
					voiceManifestCount = voiceManifestCount + 1;
					requireThat(resource.path("sha256").asString(), "voiceDigest").isEqualTo(Sha256.digest(voiceManifest));
					requireThat(resource.path("size").asLong(), "voiceSize").isEqualTo((long) voiceBytes.length);
				}
			}
			requireThat(voiceManifestCount, "voiceResourceOccursOnce").isEqualTo(1);
		}
	}

	/**
	 * Accepts the exact cumulative entry and per-string limits, alongside an empty environment file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsExactRecordLimits() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Fixture.commands(fixture.build, 128);
			Fixture.commands(fixture.archive, 128);
			Fixture.environment(fixture.build, 128);
			Fixture.environment(fixture.archive, 128);
			String[] arguments = new String[256];
			Arrays.fill(arguments, "");
			arguments[0] = "x".repeat(65_536);
			nul(fixture.build.resolve("commands/0001.argv"), arguments);
			fixture.invoke(0);
			requireThat(fixture.read().path("operations").get(0).path("commands").size(), "buildCommands").isEqualTo(128);
			Files.writeString(fixture.build.resolve("environment.nul"), "");
			fixture.invoke(0);
			requireThat(fixture.read().path("operations").get(0).path("environment").isEmpty(), "emptyEnvironment").isTrue();
		}
	}

	/**
	 * Rejects a special local resource and retains earlier output.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsUnsafeResources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.output, "prior bytes");
			fixture.arguments.addAll(List.of("--configured-v8-archive", "/dev/null"));
			fixture.invoke(1);
			requireThat(Files.readString(fixture.output), "preservedSpecialInputOutput").isEqualTo("prior bytes");
		}
	}

	/**
	 * Preserves explicit Bash selection failures instead of replacing the selected executable.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsEmptyExplicitBash() throws IOException
	{
		try (Fixture fixture = new Fixture();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = ReleaseManifestNativeInputs.run(fixture.scope, fixture.arguments.toArray(String[]::new),
				Map.of("CODEX_TOOLING_BASH", "", "BASH", "replacement-must-not-be-used"), out, err);
			requireThat(status, "emptySelectionStatus").isEqualTo(1);
			requireThat(errors.toString(StandardCharsets.UTF_8), "selectionDiagnostic").contains("Bash selection");
			requireThat(Files.exists(fixture.output), "selectionOutput").isFalse();
		}
	}

	/**
	 * Produces real native-input JSON for the existing receipt integration fixture.
	 *
	 * @param scope borrowed JSON services
	 * @param root caller-owned fixture root
	 * @param target selected receipt target
	 * @param output explicit native-input output
	 * @return actual produced and validated diagnostic document
	 * @throws IOException if fixture I/O fails
	 */
	public static JsonNode produceForReceipt(ToolingScope scope, Path root, String target, Path output)
		throws IOException
	{
		Path fixture = Files.createDirectory(root.resolve("native-input-producer"));
		prepare(fixture, target);
		try (ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = ReleaseManifestNativeInputs.run(scope, arguments(fixture, target, output), out, err,
				(command, working, temporary, variables) -> new SystemCommands.Result(1, "", ""),
				new ReleaseManifestNativeInputs.Environment(fixture, fixture, "Linux", "explicit-bash", Map.of()));
			requireThat(status, "producerStatus").isEqualTo(0);
			requireThat(errors.toString(StandardCharsets.UTF_8), "producerDiagnostics").isEmpty();
		}
		return ReleaseManifestSchema.read(scope, output);
	}

	/**
	 * Creates the completed raw format and finalized legal resources used by all tests.
	 *
	 * @param root caller-owned root
	 * @param target selected target
	 * @throws IOException if fixture I/O fails
	 */
	private static void prepare(Path root, String target) throws IOException
	{
		for (String name : List.of("build", "archive"))
		{
			Path operation = Files.createDirectory(root.resolve(name));
			Files.createDirectory(operation.resolve("commands"));
			Files.writeString(operation.resolve("format-version"), "1\n");
			Files.writeString(operation.resolve("completed"), "true\n");
			String kind = "build_binaries";
			if (name.equals("archive"))
				kind = "archive_packages";
			Files.writeString(operation.resolve("operation"), kind + "\n");
			Files.writeString(operation.resolve("target"), target + "\n");
			Files.writeString(operation.resolve("runner-os"), "Linux\n");
			Files.writeString(operation.resolve("binaries"), "codex codex-app-server\n");
			nul(operation.resolve("commands/0001.argv"), "historical-executable", "", "space and\nline\rvalue");
			nul(operation.resolve("environment.nul"), "RUSTFLAGS", "", "PATH", "historical-only");
		}
		for (String variant : List.of("codex", "codex-app-server"))
		{
			Path legal = Files.createDirectories(root.resolve("packages/" + variant + "-package-" + target +
				"/codex-resources/legal"));
			Files.writeString(legal.resolve("LICENSE.md"), "legal bytes\n");
		}
	}

	/**
	 * Writes exact NUL records, including explicitly empty values.
	 *
	 * @param path explicit fixture record
	 * @param strings ordered diagnostic strings
	 * @throws IOException if writing fails
	 */
	private static void nul(Path path, String... strings) throws IOException
	{
		Files.writeString(path, String.join("\0", strings) + "\0");
	}

	/**
	 * Builds the public named option vector.
	 *
	 * @param root fixture root
	 * @param target selected release target
	 * @param output requested JSON path
	 * @return public option tokens
	 */
	private static String[] arguments(Path root, String target, Path output)
	{
		return new String[]{"--build-result", root.resolve("build").toString(), "--archive-result",
			root.resolve("archive").toString(), "--target", target, "--package-root", root.resolve("packages").toString(),
			"--output", output.toString()};
	}

	/**
	 * Owns raw operation and finalized resource fixture files.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/** Fixture filesystem root. */
		private final Path root;
		/** Build operation root. */
		private final Path build;
		/** Archive operation root. */
		private final Path archive;
		/** CLI finalized resources. */
		private final Path cliResources;
		/** Server finalized resources. */
		private final Path serverResources;
		/** Requested output. */
		private final Path output;
		/** Borrowed JSON service owner. */
		private final DefaultToolingScope scope = new DefaultToolingScope();
		/** Public option vector. */
		private final List<String> arguments;
		/** Captured fixed probe vectors. */
		private final List<List<String>> commands = new ArrayList<>();
		/** Explicit runner platform. */
		private String runnerOs = "Linux";
		/** Injected process boundary, unavailable by default. */
		private SystemCommands.Capture capture = (command, working, temporary, variables) ->
		{
			commands.add(command);
			return new SystemCommands.Result(1, "", "");
		};

		/**
		 * Creates a valid completed diagnostic input fixture.
		 *
		 * @throws IOException if fixture I/O fails
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("native inputs fixture ");
			prepare(root, TARGET);
			build = root.resolve("build");
			archive = root.resolve("archive");
			cliResources = root.resolve("packages/codex-package-" + TARGET + "/codex-resources");
			serverResources = root.resolve("packages/codex-app-server-package-" + TARGET + "/codex-resources");
			output = root.resolve("native-inputs.json");
			arguments = new ArrayList<>(List.of(arguments(root, TARGET, output)));
		}

		/**
		 * Changes both raw platform metadata and the explicit producer host.
		 *
		 * @param value normalized platform
		 * @throws IOException if fixture I/O fails
		 */
		private void runner(String value) throws IOException
		{
			runnerOs = value;
			Files.writeString(build.resolve("runner-os"), value + "\n");
			Files.writeString(archive.resolve("runner-os"), value + "\n");
		}

		/**
		 * Invokes the actual producer and checks the status with captured diagnostics.
		 *
		 * @param expected expected command status
		 * @throws IOException if stream cleanup fails
		 */
		private void invoke(int expected) throws IOException
		{
			try (ByteArrayOutputStream errors = new ByteArrayOutputStream();
				PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int actual = ReleaseManifestNativeInputs.run(scope, arguments.toArray(String[]::new), out, err, capture,
					new ReleaseManifestNativeInputs.Environment(root, root, runnerOs, "explicit-bash", Map.of()));
				requireThat(actual, "status").withContext(errors.toString(StandardCharsets.UTF_8), "diagnostics").
					isEqualTo(expected);
				if (expected == 0)
					requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
				else
					requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isNotEmpty();
			}
		}

		/**
		 * Reads the actual produced document.
		 *
		 * @return bounded parsed JSON
		 * @throws IOException if reading fails
		 */
		private JsonNode read() throws IOException
		{
			return ReleaseManifestSchema.read(scope, output);
		}

		/**
		 * Applies one independent malformed input mutation.
		 *
		 * @param name malformed scenario
		 * @throws IOException if fixture I/O fails
		 */
		private void mutate(String name) throws IOException
		{
			Path command = build.resolve("commands/0001.argv");
			Path environment = build.resolve("environment.nul");
			switch (name)
			{
				case "missing_completion" -> Files.delete(build.resolve("completed"));
				case "false_completion" -> Files.writeString(build.resolve("completed"), "false\n");
				case "completion_crlf" -> Files.writeString(build.resolve("completed"), "true\r\n");
				case "wrong_format" -> Files.writeString(build.resolve("format-version"), "2\n");
				case "wrong_operation" -> Files.writeString(build.resolve("operation"), "archive_packages\n");
				case "wrong_target" -> Files.writeString(build.resolve("target"), "other\n");
				case "wrong_runner" -> Files.writeString(build.resolve("runner-os"), "macOS\n");
				case "different_binaries" -> Files.writeString(archive.resolve("binaries"), "other\n");
				case "empty_binaries" -> Files.writeString(build.resolve("binaries"), "\n");
				case "metadata_lines" -> Files.writeString(build.resolve("binaries"), "one\ntwo\n");
				case "metadata_no_lf" -> Files.writeString(build.resolve("completed"), "true");
				case "gapped_commands" -> Files.move(command, command.resolveSibling("0002.argv"));
				case "unexpected_command" -> Files.writeString(command.resolveSibling("extra"), "text");
				case "empty_commands" -> Files.delete(command);
				case "empty_vector" -> Files.writeString(command, "");
				case "truncated_nul" -> Files.writeString(command, "no terminator");
				case "invalid_utf8" -> Files.write(command, new byte[]{(byte) 0xFF, 0});
				case "odd_environment" -> nul(environment, "PATH");
				case "duplicate_environment" -> nul(environment, "PATH", "one", "PATH", "two");
				case "unapproved_environment" -> nul(environment, "SECRET_TOKEN", "must not appear in diagnostics");
				case "long_argument" -> nul(command, "x".repeat(65_537));
				case "long_environment" -> nul(environment, "PATH", "x".repeat(65_537));
				case "argument_count" ->
				{
					String[] values = new String[257];
					Arrays.fill(values, "x");
					nul(command, values);
				}
				case "command_count" -> commands(build, 257);
				case "total_command_count" ->
				{
					commands(build, 128);
					commands(archive, 129);
				}
				case "environment_count" -> environment(build, 257);
				case "total_environment_count" ->
				{
					environment(build, 128);
					environment(archive, 129);
				}
				case "raw_budget" -> Files.write(command, new byte[ReleaseManifestSchema.MAX_DOCUMENT_BYTES + 1]);
				case "json_budget" ->
				{
					String[] values = new String[16];
					Arrays.fill(values, "\u0001".repeat(65_536));
					nul(command, values);
				}
				case "missing_resource_directory" -> PathUtils.deleteDirectory(serverResources);
				case "resource_mismatch" -> Files.writeString(serverResources.resolve("legal/LICENSE.md"), "other bytes");
				case "symbolic_record" ->
				{
					Files.delete(command);
					Files.createSymbolicLink(command, archive.resolve("commands/0001.argv"));
				}
				case "symbolic_root" ->
				{
					Path moved = Files.move(build, root.resolve("moved-build"));
					Files.createSymbolicLink(build, moved);
				}
				case "symbolic_commands" ->
				{
					Path moved = Files.move(build.resolve("commands"), root.resolve("moved-commands"));
					Files.createSymbolicLink(build.resolve("commands"), moved);
				}
				case "symbolic_resource" -> Files.createSymbolicLink(cliResources.resolve("link"), command);
				case "symbolic_output" ->
				{
					Path moved = Files.move(output, root.resolve("prior-output"));
					Files.createSymbolicLink(output, moved);
				}
				case "missing_v8" -> arguments.addAll(List.of("--configured-v8-archive", root.resolve("missing.a").toString()));
				case "symbolic_v8" ->
				{
					Path v8 = Files.createSymbolicLink(root.resolve("v8.a"), command);
					arguments.addAll(List.of("--configured-v8-archive", v8.toString()));
				}
				case "invalid_resource_path" -> Files.writeString(cliResources.resolve("back\\slash"), "bytes");
				default -> throw new IllegalArgumentException("Unknown fixture mutation");
			}
		}

		/**
		 * Writes a selected number of contiguous command records.
		 *
		 * @param operation explicit operation root
		 * @param count required command count
		 * @throws IOException if writing fails
		 */
		private static void commands(Path operation, int count) throws IOException
		{
			for (int index = 1; index <= count; ++index)
				nul(operation.resolve(String.format(java.util.Locale.ROOT, "commands/%04d.argv", index)), "diagnostic");
		}

		/**
		 * Writes unique approved diagnostic environment entries.
		 *
		 * @param operation explicit operation root
		 * @param count required entry count
		 * @throws IOException if writing fails
		 */
		private static void environment(Path operation, int count) throws IOException
		{
			List<String> values = new ArrayList<>();
			for (int index = 0; index < count; ++index)
			{
				values.add("CARGO_TARGET_FIXTURE_" + index);
				values.add("");
			}
			nul(operation.resolve("environment.nul"), values.toArray(String[]::new));
		}

		/**
		 * Removes owned fixtures without following symbolic links and closes owned services.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			try (scope)
			{
				PathUtils.deleteDirectory(root, StandardDeleteOption.OVERRIDE_READ_ONLY);
			}
		}
	}
}
