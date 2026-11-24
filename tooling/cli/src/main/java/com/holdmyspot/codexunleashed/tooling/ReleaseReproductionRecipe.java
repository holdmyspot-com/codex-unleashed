package com.holdmyspot.codexunleashed.tooling;

import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Invokes the authenticated shared native recipe without replaying diagnostic recorded commands. */
public final class ReleaseReproductionRecipe
{
	/** Long running operation boundary. */
	@FunctionalInterface
	public interface Execute
	{
		/**
		 * Runs a literal operation with file-backed diagnostics.
		 *
		 * @param command literal argument vector
		 * @param directory current working directory
		 * @param environment complete explicit child environment
		 * @param log owned diagnostic log
		 * @return process exit status
		 * @throws IOException if the process cannot start or finish
		 */
		int run(List<String> command, Path directory, Map<String, String> environment, Path log) throws IOException;
	}

	/** Native-inputs document in the attempt scratch directory and in a compile handoff. */
	private static final String NATIVE_INPUTS = "native-inputs.json";
	/** Archive of the handed-over executables in a compile handoff. */
	private static final String COMPILED_OUTPUTS = "compiled-outputs.tar";

	/**
	 * Values that the operations after {@link #prepare} share.
	 *
	 * @param scratch    owned attempt scratch directory
	 * @param operations recorded shared package operations script
	 * @param voice      recorded voice runtime script
	 * @param launcher   recorded fork tooling launcher
	 * @param selected   space-separated selected binaries
	 * @param variables  mutable explicit process environment
	 */
	private record Prepared(Path scratch, Path operations, Path voice, Path launcher, String selected,
		Map<String, String> variables)
	{
	}

	/** Borrowed bounded JSON services. */
	private final ToolingScope scope;
	/** Small output process boundary. */
	private final SystemCommands.Capture capture;
	/** Streaming long process boundary. */
	private final Execute execute;
	/** Current complete process environment. */
	private final Map<String, String> environment;
	/** Current native runner OS. */
	private final String runnerOs;

	/**
	 * Retains explicit current process settings.
	 *
	 * @param scope borrowed JSON services
	 * @param capture bounded small-process boundary
	 * @param execute long-running operation boundary
	 * @param environment complete current environment
	 * @param runnerOs Linux, macOS or Windows
	 */
	public ReleaseReproductionRecipe(ToolingScope scope, SystemCommands.Capture capture, Execute execute,
		Map<String, String> environment, String runnerOs)
	{
		this.scope = scope;
		this.capture = capture;
		this.execute = execute;
		this.environment = Map.copyOf(environment);
		this.runnerOs = runnerOs;
	}

	/**
	 * Compiles the executables of a target whose release compiled them on a separate host, and hands them over.
	 * <p>
	 * The handoff directory receives {@code native-inputs.json}, which describes this compile host, and
	 * {@code compiled-outputs.tar}, which holds the pre-strip executables and their dSYM bundles. Pass the directory to
	 * {@link #build} on the packaging host.
	 *
	 * @param release independently authenticated recipe
	 * @param state   exclusively owned fresh attempt
	 * @param source  verified current source directory
	 * @param handoff directory to create for the handoff
	 * @throws IOException if a required operation is missing or fails
	 */
	public void compile(ReleaseReproductionSource.Release release, ReleaseReproductionState state, Path source,
		Path handoff) throws IOException
	{
		Prepared prepared = prepare(release, state, source);
		Path root = state.root();
		run("build-binaries", buildBinaries(release, prepared, root, null), source.resolve("codex-rs"),
			prepared.variables(), prepared.scratch());
		Files.createDirectory(handoff);
		List<String> recorder = new ArrayList<>(helper(prepared.launcher(), "release-native-inputs", "--build-result",
			root.resolve("build_result").toString(), "--target", release.target(), "--output",
			handoff.resolve(NATIVE_INPUTS).toString()));
		recorder.addAll(observations(prepared.variables(), true));
		run("native-inputs", recorder, source, prepared.variables(), prepared.scratch());
		List<String> capture = new ArrayList<>(helper(prepared.launcher(), "compiled-output-handoff", "capture",
			"--release-dir", root.resolve("cargo_target").resolve(release.target()).resolve("release").toString(),
			"--target", release.target(), "--source-commit", release.sourceCommit(), "--output",
			handoff.resolve(COMPILED_OUTPUTS).toString(), "--binaries"));
		capture.addAll(List.of(prepared.selected().split(" ")));
		run("compiled-output-handoff", capture, source, prepared.variables(), prepared.scratch());
		required(handoff.resolve(NATIVE_INPUTS));
		required(handoff.resolve(COMPILED_OUTPUTS));
	}

	/**
	 * Runs the recorded fork runtime and fresh native operations in production order.
	 * <p>
	 * With a handoff from {@link #compile}, the build operation installs the handed-over executables instead of
	 * compiling them, and the recorded native inputs embed the compile host's observations.
	 *
	 * @param release  independently authenticated recipe
	 * @param state    exclusively owned fresh attempt
	 * @param source   verified current source directory
	 * @param compiled handoff directory from the compile host, or {@code null} to compile on this host
	 * @return finalized compressed CLI archive
	 * @throws IOException if a required operation is missing or fails
	 */
	public Path build(ReleaseReproductionSource.Release release, ReleaseReproductionState state, Path source,
		Path compiled) throws IOException
	{
		Prepared prepared = prepare(release, state, source);
		Path root = state.root();
		Path scratch = prepared.scratch();
		Path fork = root.resolve("fork_checkout");
		Path launcher = prepared.launcher();
		Map<String, String> variables = prepared.variables();
		Path voiceBuild = scratch.resolve("voice-build");
		run("voice-build", List.of("bash", prepared.voice().toString(), "build", "--upstream-root", source.toString(),
			"--target", release.target(), "--build-commit", release.sourceCommit(), "--result-dir",
			voiceBuild.toString()), source, variables, scratch);
		run("build-binaries", buildBinaries(release, prepared, root, compiled), source.resolve("codex-rs"), variables,
			scratch);
		Path staging = scratch.resolve("staging");
		run("archive-packages", List.of("bash", prepared.operations().toString(), "archive-packages", "--target",
			release.target(), "--runner-os", runnerOs, "--binaries", prepared.selected(), "--target-dir",
			root.resolve("cargo_target").toString(), "--source-root", source.toString(), "--workspace",
			source.toString(), "--runner-temp", staging.toString(), "--package-version", release.version(),
			"--result-dir", root.resolve("archive_result").toString(), "--voice-release-dir",
			voiceBuild.resolve("release").toString(), "--voice-build-commit", release.sourceCommit(),
			"--tooling-launcher", launcher.toString()), source.resolve("codex-rs"), variables, scratch);
		Path archiveRoot = source.resolve("dist").resolve(release.target());
		String separator = ":";
		if (runnerOs.equals("Windows"))
			separator = ";";
		String pythonPath = source.resolve("scripts") + separator + source.resolve("sdk/python/src") +
			separator + source.resolve("sdk/python/tests");
		if (environment.containsKey("PYTHONPATH"))
			pythonPath += separator + environment.get("PYTHONPATH");
		variables.put("PYTHONPATH", pythonPath);
		for (String compression : List.of("gzip", "zstd"))
		{
			String extension = "zst";
			if (compression.equals("gzip"))
				extension = "gz";
			run("smoke-" + compression, List.of("uv", "run", "--frozen", "pytest", "-v", "--package-target",
				release.target(), "--symbols-archive", source.resolve("symbols-dist").resolve(release.target()).
					resolve("codex-symbols-" + release.target() + ".tar.gz").toString(), "--basetemp",
				scratch.resolve("package-smoke-" + compression).toString(), "--compression", compression,
				"--cli-archive", archiveRoot.resolve("codex-package-" + release.target() + ".tar." + extension).toString(),
				"--app-server-archive", archiveRoot.resolve("codex-app-server-package-" + release.target() + ".tar." +
					extension).toString(), "test_codex_package.py"), source.resolve("scripts/codex_package/smoke_tests"),
				variables, scratch);
		}
		run("legal-finalize", helper(launcher, "package-license", "finalize", "--metadata-root", fork.toString(),
			"--upstream-root", source.toString(), "--package-root", staging.toString(), "--archive-root",
			archiveRoot.toString(), "--target", release.target()), source, variables, scratch);
		Path archive = archiveRoot.resolve(ReleaseManifestSchema.packageName(release.target()));
		verifyVoice(release, source, launcher, voiceBuild, archive, variables, scratch);
		List<String> recorder = new ArrayList<>(helper(launcher, "release-native-inputs", "--build-result",
			root.resolve("build_result").toString(), "--archive-result", root.resolve("archive_result").toString(),
			"--target", release.target(), "--package-root", staging.toString(), "--output",
			scratch.resolve(NATIVE_INPUTS).toString()));
		if (compiled != null)
			recorder.addAll(List.of("--compile-inputs", compiled.resolve(NATIVE_INPUTS).toString()));
		recorder.addAll(observations(variables, compiled == null));
		run("native-inputs", recorder, source, variables, scratch);
		required(archive);
		return archive;
	}

	/**
	 * Builds the recorded fork runtime and runs the operations that precede compilation.
	 *
	 * @param release independently authenticated recipe
	 * @param state   exclusively owned fresh attempt
	 * @param source  verified current source directory
	 * @return values that the later operations share
	 * @throws IOException if a required operation is missing or fails
	 */
	private Prepared prepare(ReleaseReproductionSource.Release release, ReleaseReproductionState state, Path source)
		throws IOException
	{
		Path root = state.root();
		for (String directory : List.of("cargo_target", "build_result", "archive_result"))
			try (Stream<Path> children = Files.list(root.resolve(directory)))
			{
				if (children.findAny().isPresent())
					throw new IOException("build_failed: fresh output required: " + directory);
			}
		Path scratch = root.resolve("scratch");
		Path fork = root.resolve("fork_checkout");
		Path tooling = scratch.resolve("tooling");
		Files.createDirectory(tooling);
		Files.createDirectory(tooling.resolve("tmp"));
		Path pom = fork.resolve("tooling/cli/pom.xml");
		required(pom);
		Path operations = source.resolve(".github/scripts/codex-package-operations.sh");
		Path voice = source.resolve(".github/scripts/codex-voice-runtime.sh");
		required(operations);
		required(voice);
		Map<String, String> variables = new HashMap<>(environment);
		variables.put("CODEX_UNLEASHED_BUILD_NUMBER", release.buildNumber().toString());
		variables.put("CARGO_TARGET_DIR", root.resolve("cargo_target").toString());
		variables.put("TMPDIR", scratch.toString());
		variables.put("TMP", scratch.toString());
		variables.put("TEMP", scratch.toString());
		if (release.target().contains("apple-darwin"))
			variables.put("MACOSX_DEPLOYMENT_TARGET", "14.0");
		if (release.target().equals("x86_64-pc-windows-msvc"))
			variables.put("LIBSQLITE3_FLAGS", "SQLITE_DISABLE_INTRINSIC");
		Path cache = tooling.resolve("maven");
		String suppliedCache = environment.get("MAVEN_REPOSITORY");
		if (suppliedCache != null && !suppliedCache.isEmpty())
			cache = Path.of(suppliedCache).toAbsolutePath();
		run("recorded-runtime", List.of("mvn", "-q", "-B", "-ntp", "-f", pom.toString(), "-Pruntime",
			"-DskipTests", "-Dtooling.build.directory=" + tooling.resolve("build"),
			"-Dmaven.repo.local=" + cache, "-Djava.io.tmpdir=" + tooling.resolve("tmp"), "package"),
			fork, variables, scratch);
		Path launcher = tooling.resolve("build/runtime/bin/codex-tooling");
		required(launcher);
		variables.put("CODEX_TOOLING", launcher.toString());
		String selected = small(List.of("bash", operations.toString(), "select-binaries", "--runner-os",
			runnerOs, "--build-mode", "release", "--build-system", "cargo"), source, scratch, variables).strip();
		if (!selected.matches("[a-z0-9-]+(?: [a-z0-9-]+)*"))
			throw new IOException("Invalid shared binary selection");
		String profiles = small(List.of("bash", operations.toString(), "profile-env", "--target",
			release.target(), "--build-mode", "release"), source, scratch, variables);
		for (String line : profiles.lines().toList())
		{
			if (!line.matches("CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=(packed|off)"))
				throw new IOException("Unexpected shared release profile assignment");
			int delimiter = line.indexOf('=');
			variables.put(line.substring(0, delimiter), line.substring(delimiter + 1));
		}
		run("source-identity", helper(launcher, "package-identity", "source", "--source-root", source.toString(),
			"--version", release.version().split("\\+", -1)[0], "--build", release.buildNumber().toString()),
			source, variables, scratch);
		if (runnerOs.equals("Windows"))
		{
			for (String name : List.of("VOICE_WINDOWS_SYSTEM_ROOT", "VOICE_WINDOWS_HOST_ARCH"))
				if (!variables.containsKey(name) || variables.get(name).isEmpty())
					throw new IOException("prerequisite_unavailable: Windows voice setup did not supply " + name);
			variables.put("SystemRoot", variables.get("VOICE_WINDOWS_SYSTEM_ROOT"));
			variables.put("PROCESSOR_ARCHITECTURE", variables.get("VOICE_WINDOWS_HOST_ARCH"));
			variables.put("GIT_REDIRECT_STDIN", "NUL");
		}
		return new Prepared(scratch, operations, voice, launcher, selected, variables);
	}

	/**
	 * Returns the shared build operation's argument vector.
	 *
	 * @param release  independently authenticated recipe
	 * @param prepared values from {@link #prepare}
	 * @param root     owned attempt directory
	 * @param compiled handoff directory from the compile host, or {@code null} to compile on this host
	 * @return literal argument vector
	 */
	private List<String> buildBinaries(ReleaseReproductionSource.Release release, Prepared prepared, Path root,
		Path compiled)
	{
		List<String> command = new ArrayList<>(List.of("bash", prepared.operations().toString(), "build-binaries",
			"--target", release.target(), "--runner-os", runnerOs, "--build-mode", "release", "--binaries",
			prepared.selected(), "--target-dir", root.resolve("cargo_target").toString(), "--result-dir",
			root.resolve("build_result").toString()));
		if (compiled != null)
			command.addAll(List.of("--restored-final-outputs", compiled.toString()));
		return command;
	}

	/**
	 * Returns the runner and V8 options that the native-inputs recorder observes from the current environment.
	 *
	 * @param variables explicit process environment
	 * @param v8        whether to pass the configured V8 archive
	 * @return option tokens
	 */
	private static List<String> observations(Map<String, String> variables, boolean v8)
	{
		List<String> options = new ArrayList<>();
		for (Map.Entry<String, String> option : Map.of("RUNNER_ARCH", "--runner-arch", "ImageOS", "--image-os",
			"ImageVersion", "--image-version").entrySet())
			if (variables.containsKey(option.getKey()))
				options.addAll(List.of(option.getValue(), variables.get(option.getKey())));
		String archive = variables.get("RUSTY_V8_ARCHIVE");
		if (v8 && archive != null && !archive.isEmpty())
			options.addAll(List.of("--configured-v8-archive", archive));
		return options;
	}

	/**
	 * Runs the existing finalized primary archive voice verification boundary.
	 *
	 * @param release authenticated release identity
	 * @param source owned source directory
	 * @param launcher recorded fork tooling launcher
	 * @param voiceBuild fresh voice build output
	 * @param archive finalized compressed package
	 * @param variables explicit process environment
	 * @param scratch owned attempt scratch directory
	 * @throws IOException if the required operation cannot complete
	 */
	private void verifyVoice(ReleaseReproductionSource.Release release, Path source, Path launcher, Path voiceBuild,
		Path archive, Map<String, String> variables, Path scratch) throws IOException
	{
		Path gzipPackage = scratch.resolve("final-primary-gzip");
		Path zstdPackage = scratch.resolve("final-primary-zstd");
		Files.createDirectory(gzipPackage);
		Files.createDirectory(zstdPackage);
		run("extract-final-gzip", List.of("tar", "-xzf", archive.toString(), "-C", gzipPackage.toString()), source,
			variables, scratch);
		String resolved = small(List.of("python", "-m", "codex_package.archive", "resolve-zstd"), source,
			scratch, variables);
		if (resolved.length() > ReleaseManifestSchema.MAX_DOCUMENT_BYTES)
			throw new IOException("Resolved zstd argument vector exceeds the document bound");
		JsonNode vector = scope.getJsonMapper().readTree(resolved);
		if (!vector.isArray() || vector.isEmpty() || vector.size() > 64)
			throw new IOException("Invalid recorded zstd resolver response");
		List<String> decompressor = new ArrayList<>();
		for (JsonNode argument : vector)
		{
			if (!argument.isString() || argument.asString().indexOf('\0') >= 0)
				throw new IOException("Invalid recorded zstd resolver argument");
			decompressor.add(argument.asString());
		}
		Path tar = scratch.resolve("final-primary.tar");
		decompressor.addAll(List.of("-d", "-o", tar.toString(), archive.getParent().resolve(
			"codex-package-" + release.target() + ".tar.zst").toString()));
		run("decompress-final-zstd", decompressor, source, variables, scratch);
		run("extract-final-zstd", List.of("tar", "-xf", tar.toString(), "-C", zstdPackage.toString()), source,
			variables, scratch);
		verifyPackage(release, source, launcher, voiceBuild, gzipPackage, variables, scratch, "gzip");
		verifyPackage(release, source, launcher, voiceBuild, zstdPackage, variables, scratch, "zstd");
		Path assets = Files.createDirectory(scratch.resolve("installer-assets"));
		Files.copy(archive, assets.resolve(archive.getFileName()));
		Files.writeString(assets.resolve("SHA256SUMS"), Sha256.digest(archive) + "  " + archive.getFileName() + "\n");
		Path installed = scratch.resolve("installed-package");
		Path installer = scratch.getParent().resolve("fork_checkout/.github/scripts/verify-voice-installer.mjs");
		required(installer);
		run("installer-verification", List.of("node", installer.toString(), "--upstream-root", source.toString(),
			"--assets", assets.toString(), "--target", release.target(), "--package-version", release.version(),
			"--scratch-root", scratch.toString(), "--installed-package-output", installed.toString(),
			"--evidence-dir", scratch.resolve("voice-installer-evidence").toString()), source, variables, scratch);
		verifyPackage(release, source, launcher, voiceBuild, installed, variables, scratch, "installed");
	}

	/**
	 * Invokes the recorded final voice verifier for an extracted or installed package.
	 *
	 * @param release authenticated release identity
	 * @param source owned source directory
	 * @param launcher recorded fork tooling launcher
	 * @param voiceBuild fresh voice build output
	 * @param packageRoot extracted package directory
	 * @param variables explicit process environment
	 * @param scratch owned attempt scratch directory
	 * @param name operation evidence name
	 * @throws IOException if the required operation cannot complete
	 */
	private void verifyPackage(ReleaseReproductionSource.Release release, Path source, Path launcher, Path voiceBuild,
		Path packageRoot, Map<String, String> variables, Path scratch, String name) throws IOException
	{
		String suffix = "";
		if (runnerOs.equals("Windows"))
			suffix = ".exe";
		run("voice-final-" + name, helper(launcher, "voice-runtime", "verify", "--package-dir", packageRoot.toString(),
			"--upstream-root", source.toString(), "--target", release.target(), "--build-commit", release.sourceCommit(),
			"--package-version", release.version(), "--test-binary",
			voiceBuild.resolve("verification/packaged-runtime-test-bin" + suffix).toString(), "--installed-test-binary",
			voiceBuild.resolve("verification/installed-package-runtime-test-bin" + suffix).toString(), "--evidence-dir",
			scratch.resolve("voice-final-evidence-" + name).toString()), source, variables, scratch);
	}

	/**
	 * Runs one required stage and names failed or unavailable prerequisites.
	 *
	 * @param stage failed recipe stage when available
	 * @param command literal process argument vector
	 * @param directory current process working directory
	 * @param variables explicit process environment
	 * @param scratch owned attempt scratch directory
	 * @throws IOException if the required operation cannot complete
	 */
	private void run(String stage, List<String> command, Path directory, Map<String, String> variables, Path scratch)
		throws IOException
	{
		int status;
		try
		{
			status = execute.run(command, directory, Map.copyOf(variables), scratch.resolve(stage + ".log"));
		}
		catch (IOException failure)
		{
			throw new IOException("prerequisite_unavailable: " + stage + ": " + failure.getMessage(), failure);
		}
		if (status != 0)
		{
			String kind = "build_failed: ";
			if (status == 127)
				kind = "prerequisite_unavailable: ";
			throw new IOException(kind + stage + ": exit " + status);
		}
	}

	/**
	 * Captures a small shared operation's output.
	 *
	 * @param command literal process argument vector
	 * @param directory current process working directory
	 * @param scratch owned attempt scratch directory
	 * @param variables explicit process environment
	 * @return bounded successful command output
	 * @throws IOException if the required operation cannot complete
	 */
	private String small(List<String> command, Path directory, Path scratch, Map<String, String> variables)
		throws IOException
	{
		SystemCommands.Result result = capture.capture(command, directory, scratch, variables);
		if (result.status() != 0)
			throw new IOException("prerequisite_unavailable: shared recipe: " + result.stderr());
		return result.stdout();
	}

	/**
	 * Builds the existing recorded launcher vector literally.
	 *
	 * @param launcher recorded fork tooling launcher
	 * @param arguments literal operation arguments
	 * @return literal recorded launcher arguments
	 */
	private static List<String> helper(Path launcher, String... arguments)
	{
		List<String> command = new ArrayList<>(List.of("bash", launcher.toString()));
		command.addAll(List.of(arguments));
		return List.copyOf(command);
	}

	/**
	 * Requires a real recipe input at its use boundary.
	 *
	 * @param path current filesystem path
	 * @throws IOException if the required operation cannot complete
	 */
	private static void required(Path path) throws IOException
	{
		if (!Files.isRegularFile(path))
			throw new IOException("prerequisite_unavailable: missing recorded recipe input: " + path);
	}
}
