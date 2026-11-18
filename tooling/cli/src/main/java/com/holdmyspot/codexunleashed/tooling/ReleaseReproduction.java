package com.holdmyspot.codexunleashed.tooling;

import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.node.ObjectNode;

/** Reconstructs an authenticated release and compares its finalized compressed archive bytes. */
public final class ReleaseReproduction
{
	/** Shared CLI command name. */
	public static final String COMMAND = "release-reproduction";

	/** Prevents utility construction. */
	private ReleaseReproduction()
	{
	}

	/**
	 * Runs the public composition or one of its shared workflow operations.
	 *
	 * @param scope borrowed JSON services
	 * @param arguments command arguments
	 * @param variables explicit current environment
	 * @param out caller-owned success output
	 * @param err caller-owned diagnostic output
	 * @return zero only for successful operations, one for rejected execution, two for malformed input
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> variables, PrintStream out,
		PrintStream err)
	{
		return run(scope, arguments, variables, out, err, SystemCommands::capture, ReleaseReproduction::execute);
	}

	/**
	 * Runs the shared composition with explicit bounded and streaming process boundaries.
	 *
	 * @param scope borrowed JSON services
	 * @param arguments command arguments
	 * @param variables explicit current environment
	 * @param out caller-owned success output
	 * @param err caller-owned diagnostic output
	 * @param capture bounded identity and authentication process boundary
	 * @param execute streaming recorded recipe process boundary
	 * @return zero for success, one for execution failure, two for malformed input
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> variables, PrintStream out,
		PrintStream err, SystemCommands.Capture capture, ReleaseReproductionRecipe.Execute execute)
	{
		List<String> tokens = List.of(arguments);
		if (tokens.contains("--help") || tokens.contains("-h"))
		{
			out.println("usage: release-reproduction MANIFEST --target TARGET --output-dir OUTPUT --project-root ROOT");
			out.println("       release-reproduction {prepare,run-prepared,cleanup} --project-root ROOT ...");
			return 0;
		}
		ReleaseReproductionState state = null;
		boolean cleanup = false;
		Path boundOutput = null;
		String expectedArchive = null;
		String phase = "authentication";
		int status;
		try
		{
			if (tokens.isEmpty())
				throw new IllegalArgumentException("Missing manifest or operation");
			String operation = tokens.getFirst();
			boolean internal = Set.of("prepare", "run-prepared", "cleanup").contains(operation);
			Map<String, String> options = parse(tokens.subList(1, tokens.size()));
			Set<String> expected = switch (operation)
			{
				case "prepare" -> Set.of("--project-root", "--manifest", "--target", "--workspace", "--output-dir");
				case "run-prepared" -> Set.of("--project-root", "--state", "--release-dir", "--workspace", "--output-dir");
				case "cleanup" -> Set.of("--project-root", "--state");
				default -> Set.of("--project-root", "--target", "--output-dir");
			};
			if (!options.keySet().equals(expected))
				throw new IllegalArgumentException("Expected options: " + expected);
			Path root = Path.of(options.get("--project-root")).toAbsolutePath().normalize();
			if (!Files.isDirectory(root))
				throw new IllegalArgumentException("Project root must exist");
			if (operation.equals("cleanup"))
			{
				ReleaseReproductionState.load(scope, root, options.get("--state")).cleanup(scope);
				return 0;
			}
			Path temporary = Path.of(variables.getOrDefault("TMPDIR", System.getProperty("java.io.tmpdir")));
			ReleaseManifest.Environment settings = new ReleaseManifest.Environment(temporary, root,
				System.getProperty("os.name"), variables);
			ReleaseReproductionSource source = new ReleaseReproductionSource(scope, capture,
				settings, err);
			Path output = Path.of(options.get("--output-dir")).toAbsolutePath().normalize();
			Path workspace;
			Path releaseDirectory;
			if (operation.equals("run-prepared"))
			{
				state = ReleaseReproductionState.load(scope, root, options.get("--state"));
				state.output(scope, output, false);
				boundOutput = output;
				workspace = Path.of(options.get("--workspace")).toAbsolutePath().normalize();
				releaseDirectory = Path.of(options.get("--release-dir")).toAbsolutePath().normalize();
			}
			else
			{
				String manifestName = operation;
				if (internal)
					manifestName = options.get("--manifest");
				Path manifest = Path.of(manifestName).toAbsolutePath().normalize();
				ReleaseReproductionSource.Release release = source.authenticate(manifest, options.get("--target"));
				expectedArchive = release.archiveDigest();
				state = ReleaseReproductionState.allocate(scope, root, release.manifestDigest(), release.target());
				cleanup = true;
				workspace = state.root().resolve("scratch/upstream");
				if (internal)
					workspace = Path.of(options.get("--workspace")).toAbsolutePath().normalize();
				releaseDirectory = manifest.getParent();
				aliases(state, workspace, releaseDirectory, output);
				state.output(scope, output, true);
				boundOutput = output;
				phase = "source";
				source.reconstruct(release, state, manifest, workspace);
				phase = "release_verification";
				source.verifyPrepared(state, releaseDirectory, workspace);
				if (operation.equals("prepare"))
				{
					cleanup = false;
					out.println(state.stateName());
					return 0;
				}
			}
			aliases(state, workspace, releaseDirectory, output);
			phase = "release_verification";
			ReleaseReproductionSource.Release release = source.verifyPrepared(state, releaseDirectory, workspace);
			expectedArchive = release.archiveDigest();
			phase = "recipe";
			String runnerOs = "Linux";
			if (release.target().contains("windows"))
				runnerOs = "Windows";
			else if (release.target().contains("apple"))
				runnerOs = "macOS";
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope, capture,
				execute, variables, runnerOs);
			String actual = null;
			String result = "build_failed";
			String stage = "recipe";
			String diagnostic = null;
			try
			{
				Path archive = recipe.build(release, state, workspace);
				actual = Sha256.digest(archive);
				status = compare(scope, release, releaseDirectory.resolve(archive.getFileName()), archive, output);
				observations(scope, output, releaseDirectory.resolve(ReleaseManifestSchema.receiptName(release.target())),
					state.root().resolve("scratch/native-inputs.json"));
				actual = Sha256.digest(archive);
				Files.copy(archive, output.resolve(archive.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				result = "digest_mismatch";
				stage = "compressed_archive_sha256";
				diagnostic = "Final compressed archive SHA256 differs";
				if (release.archiveDigest().equals(actual))
				{
					result = "matched";
					stage = null;
					diagnostic = null;
					status = 0;
				}
			}
			catch (IOException failure)
			{
				status = 1;
				diagnostic = failure.getMessage();
				if (diagnostic.startsWith("prerequisite_unavailable:"))
					result = "prerequisite_unavailable";
				if (actual != null)
				{
					Path report = output.resolve("reproduction-result.json");
					if (Files.isRegularFile(report))
						failedResult(scope, state, output, release.archiveDigest(), "recipe", diagnostic);
					else
						writeResult(scope, output, release, actual, result, stage, diagnostic);
				}
			}
			finally
			{
				retain(state.root().resolve("build_result"), output.resolve("build_result"));
				retain(state.root().resolve("archive_result"), output.resolve("archive_result"));
				retain(state.root().resolve("scratch"), output.resolve("diagnostics"));
				if (actual == null)
					writeResult(scope, output, release, actual, result, stage, diagnostic);
			}
			if (diagnostic != null)
				err.println(COMMAND + ": " + diagnostic);
		}
		catch (IllegalArgumentException | tools.jackson.core.JacksonException failure)
		{
			err.println(COMMAND + ": " + failure.getMessage());
			status = 2;
		}
		catch (IOException failure)
		{
			err.println(COMMAND + ": " + failure.getMessage());
			status = 1;
			if (boundOutput != null && state != null)
				try
				{
					if (failure.getMessage().startsWith("source_mismatch:"))
						phase = "source";
					failedResult(scope, state, boundOutput, expectedArchive, phase, failure.getMessage());
					retain(state.root().resolve("build_result"), boundOutput.resolve("build_result"));
					retain(state.root().resolve("archive_result"), boundOutput.resolve("archive_result"));
				}
				catch (IOException retention)
				{
					err.println(COMMAND + ": evidence retention failed: " + retention.getMessage());
				}
		}
		finally
		{
			if (cleanup && state != null)
				try
				{
					state.cleanup(scope);
				}
				catch (IOException | IllegalArgumentException failure)
				{
					err.println(COMMAND + ": owned cleanup failed: " + failure.getMessage());
					status = 1;
					if (boundOutput != null)
						try
						{
							Path report = boundOutput.resolve("reproduction-result.json");
							ObjectNode value = (ObjectNode) ReleaseManifestSchema.read(scope, report);
							if (value.path("result").asString().equals("matched"))
							{
								value.put("result", "build_failed");
								value.put("failed_stage", "cleanup");
							}
							String detail = value.path("diagnostic").asString("") + " Owned cleanup failed: " + failure.getMessage();
							value.put("diagnostic", detail.substring(0, Math.min(8192, detail.length())));
							ReleaseReproductionState.atomicWrite(scope, report, value);
						}
						catch (IOException retention)
						{
							err.println(COMMAND + ": cleanup diagnostic retention failed: " + retention.getMessage());
						}
				}
		}
		return status;
	}

	/**
	 * Compares finalized compressed bytes and records binary details only as diagnostics.
	 *
	 * @param scope borrowed JSON services
	 * @param release authenticated expected identity
	 * @param expectedArchive authenticated published archive
	 * @param actualArchive freshly finalized archive
	 * @param output safely bound output directory
	 * @return zero only for equal compressed SHA256 digests
	 * @throws IOException if evidence cannot be read or retained
	 */
	public static int compare(ToolingScope scope, ReleaseReproductionSource.Release release, Path expectedArchive,
		Path actualArchive, Path output) throws IOException
	{
		String actual = Sha256.digest(actualArchive);
		boolean matched = release.archiveDigest().equals(actual);
		String outcome = "digest_mismatch";
		String stage = "compressed_archive_sha256";
		String diagnostic = "Final compressed archive SHA256 differs";
		if (matched)
		{
			outcome = "matched";
			stage = null;
			diagnostic = null;
		}
		ObjectNode value = result(scope, release, actual, outcome, stage, diagnostic);
		ArrayNode binaries = value.putArray("binaries");
		Map<String, String> expected = new HashMap<>();
		for (JsonNode binary : ReleaseManifestArchive.binaries(scope, expectedArchive))
			expected.put(ReleaseManifestSchema.text(binary.path("path")),
				ReleaseManifestSchema.text(binary.path("sha256")));
		for (JsonNode binary : ReleaseManifestArchive.binaries(scope, actualArchive))
		{
			String path = ReleaseManifestSchema.text(binary.path("path"));
			ObjectNode entry = binaries.addObject();
			entry.put("path", path);
			entry.put("expected_sha256", expected.get(path));
			entry.put("actual_sha256", ReleaseManifestSchema.text(binary.path("sha256")));
		}
		Files.copy(actualArchive, output.resolve(actualArchive.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		ReleaseReproductionState.atomicWrite(scope, output.resolve("reproduction-result.json"), value);
		if (matched)
			return 0;
		return 1;
	}

	/**
	 * Retains a safely bound failed-stage report without manufacturing unavailable archive hashes.
	 *
	 * @param scope borrowed JSON services
	 * @param state validated attempt ownership
	 * @param output bound evidence directory
	 * @param expected authenticated expected archive digest when available
	 * @param phase failed execution phase
	 * @param diagnostic bounded failure detail
	 * @throws IOException if the required operation cannot complete
	 */
	private static void failedResult(ToolingScope scope, ReleaseReproductionState state, Path output,
		String expected, String phase, String diagnostic) throws IOException
	{
		Path report = output.resolve("reproduction-result.json");
		if (Files.isRegularFile(report))
		{
			ObjectNode existing = (ObjectNode) ReleaseManifestSchema.read(scope, report);
			if (existing.path("result").asString().equals("matched"))
			{
				existing.put("result", "build_failed");
				existing.put("failed_stage", phase);
			}
			String detail = existing.path("diagnostic").asString("") + " Evidence retention failed: " + diagnostic;
			existing.put("diagnostic", detail.substring(0, Math.min(8192, detail.length())));
			ReleaseReproductionState.atomicWrite(scope, report, existing);
			return;
		}
		ObjectNode value = scope.getJsonMapper().createObjectNode();
		value.put("format_version", 1);
		value.put("target", state.target());
		value.put("authenticated_manifest_sha256", state.manifestDigest());
		value.put("expected_archive_sha256", expected);
		value.putNull("actual_archive_sha256");
		String outcome = "authentication_failed";
		if (phase.equals("source"))
			outcome = "source_mismatch";
		else if (phase.equals("recipe"))
			outcome = "build_failed";
		value.put("result", outcome);
		value.put("failed_stage", phase);
		value.put("diagnostic", diagnostic.substring(0, Math.min(8192, diagnostic.length())));
		value.putArray("observed_input_differences");
		value.putArray("binaries");
		ReleaseReproductionState.atomicWrite(scope, output.resolve("reproduction-result.json"), value);
	}

	/**
	 * Lists observed differences after building, without using diagnostic paths as locators or gates.
	 *
	 * @param scope borrowed JSON services
	 * @param output bound evidence directory
	 * @param receipt authenticated published receipt
	 * @param nativeInputs fresh recorded native inputs
	 * @throws IOException if the required operation cannot complete
	 */
	private static void observations(ToolingScope scope, Path output, Path receipt, Path nativeInputs)
		throws IOException
	{
		JsonNode original = ReleaseManifestSchema.read(scope, receipt).path("inputs").path("native_build");
		JsonNode rebuilt = ReleaseManifestSchema.read(scope, nativeInputs);
		Path report = output.resolve("reproduction-result.json");
		ObjectNode value = (ObjectNode) ReleaseManifestSchema.read(scope, report);
		ArrayNode differences = value.putArray("observed_input_differences");
		for (String field : List.of("runner", "operations", "tools", "resources"))
			if (!ReleaseManifestSchema.same(original.path(field), rebuilt.path(field)))
				differences.add("native_build." + field +
					" differs; see retained native-inputs.json and published receipt");
		ReleaseReproductionState.atomicWrite(scope, report, value);
	}

	/**
	 * Parses literal named options without accepting duplicates or empty values.
	 *
	 * @param tokens literal option tokens
	 * @return validated literal options
	 */
	private static Map<String, String> parse(List<String> tokens)
	{
		Map<String, String> options = new HashMap<>();
		if (tokens.size() % 2 != 0)
			throw new IllegalArgumentException("Expected option/value pairs");
		for (int index = 0; index < tokens.size(); index += 2)
		{
			String name = tokens.get(index);
			String value = tokens.get(index + 1);
			if (!name.startsWith("--") || value.isEmpty() || options.putIfAbsent(name, value) != null)
				throw new IllegalArgumentException("Invalid or duplicate option: " + name);
		}
		return options;
	}

	/**
	 * Rejects current input/output aliases before setup or native execution.
	 *
	 * @param state validated attempt ownership
	 * @param workspace current reconstructed source directory
	 * @param release authenticated release identity
	 * @param output bound evidence directory
	 * @throws IOException if the required operation cannot complete
	 */
	private static void aliases(ReleaseReproductionState state, Path workspace, Path release, Path output)
		throws IOException
	{
		List<Path> external = new ArrayList<>();
		for (Path argument : List.of(workspace, release, output))
		{
			Path ancestor = argument;
			while (!Files.exists(ancestor))
				ancestor = ancestor.getParent();
			external.add(ancestor.toRealPath().resolve(ancestor.relativize(argument)).normalize());
		}
		for (int first = 0; first < external.size(); ++first)
			for (int second = first + 1; second < external.size(); ++second)
			{
				Path left = external.get(first);
				Path right = external.get(second);
				if (left.startsWith(right) || right.startsWith(left) || Files.exists(left) && Files.exists(right) &&
					Files.isSameFile(left, right))
					throw new IOException("Release, workspace and output must be independent directories");
			}
		for (String directory : List.of("fork_checkout", "cargo_target", "build_result", "archive_result"))
			for (Path path : external)
				if (path.startsWith(state.root().resolve(directory)) || state.root().resolve(directory).startsWith(path))
					throw new IOException("External argument aliases internal recipe outputs");
	}

	/**
	 * Streams one native operation directly to an owned log file.
	 *
	 * @param command literal process argument vector
	 * @param directory current process working directory
	 * @param environment explicit process environment
	 * @param log owned operation log
	 * @return operation result
	 * @throws IOException if the required operation cannot complete
	 */
	private static int execute(List<String> command, Path directory, Map<String, String> environment, Path log)
		throws IOException
	{
		ProcessBuilder builder = SystemCommands.createBuilder(command).directory(directory.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		builder.redirectErrorStream(true);
		builder.redirectOutput(log.toFile());
		try (Process process = builder.start())
		{
			try
			{
				return process.waitFor();
			}
			catch (InterruptedException failure)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted required recipe operation", failure);
			}
		}
	}

	/**
	 * Retains partial operation files without following symbolic links.
	 *
	 * @param source owned source directory
	 * @param destination retained evidence destination
	 * @throws IOException if the required operation cannot complete
	 */
	private static void retain(Path source, Path destination) throws IOException
	{
		if (source.getFileName().toString().equals("scratch"))
		{
			try (Stream<Path> children = Files.list(source))
			{
				for (Path child : children.toList())
				{
					String name = child.getFileName().toString();
					if (Files.isSymbolicLink(child))
						continue;
					if (Files.isDirectory(child) && name.startsWith("voice-") && name.contains("evidence"))
						retain(child, destination.resolve(name));
					else if (Files.isRegularFile(child) && (name.endsWith(".log") || name.endsWith(".json")))
					{
						Files.createDirectories(destination);
						Files.copy(child, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			return;
		}
		try (Stream<Path> paths = Files.walk(source))
		{
			for (Path path : paths.toList())
			{
				if (Files.isSymbolicLink(path))
					continue;
				Path target = destination.resolve(source.relativize(path));
				if (Files.isDirectory(path))
					Files.createDirectories(target);
				else if (Files.isRegularFile(path) && (path.toString().endsWith(".log") ||
					path.toString().endsWith(".json") || !source.getFileName().toString().equals("scratch")))
					Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Writes the bounded final comparison independently of unpacked binary equivalence.
	 *
	 * @param scope borrowed JSON services
	 * @param output bound evidence directory
	 * @param release authenticated release identity
	 * @param actual fresh compressed archive digest when available
	 * @param result comparison or failure outcome
	 * @param stage failed recipe stage when available
	 * @param diagnostic bounded failure detail
	 * @throws IOException if the required operation cannot complete
	 */
	private static void writeResult(ToolingScope scope, Path output, ReleaseReproductionSource.Release release,
		String actual, String result, String stage, String diagnostic) throws IOException
	{
		ObjectNode value = result(scope, release, actual, result, stage, diagnostic);
		ReleaseReproductionState.atomicWrite(scope, output.resolve("reproduction-result.json"), value);
	}

	/**
	 * Builds bounded report fields shared by success and failed-stage evidence.
	 *
	 * @param scope borrowed JSON services
	 * @param release authenticated release identity
	 * @param actual fresh compressed archive digest when available
	 * @param result comparison or failure outcome
	 * @param stage failed recipe stage when available
	 * @param diagnostic bounded failure detail
	 * @return bounded reproduction report
	 */
	private static ObjectNode result(ToolingScope scope, ReleaseReproductionSource.Release release, String actual,
		String result, String stage, String diagnostic)
	{
		ObjectNode value = scope.getJsonMapper().createObjectNode();
		value.put("format_version", 1);
		value.put("target", release.target());
		value.put("authenticated_manifest_sha256", release.manifestDigest());
		value.put("expected_archive_sha256", release.archiveDigest());
		value.put("actual_archive_sha256", actual);
		value.put("result", result);
		value.put("failed_stage", stage);
		String detail = diagnostic;
		if (detail != null)
			detail = detail.substring(0, Math.min(8192, detail.length()));
		value.put("diagnostic", detail);
		value.putArray("observed_input_differences");
		value.putArray("binaries");
		return value;
	}
}
