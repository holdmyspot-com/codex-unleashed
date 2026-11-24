package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionRecipe;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionSource;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionState;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises literal recipe composition through in-memory process collaborators. */
public final class ReleaseReproductionRecipeTest
{
	/** Target whose release compiles on a separate host. */
	private static final String DARWIN = "x86_64-apple-darwin";

	/** Creates recipe checks. */
	public ReleaseReproductionRecipeTest()
	{
	}

	/**
	 * Executes the unchanged symbolic recipe despite different observed host facts.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void freshLiteralProductionOrder() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-recipe-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Path source = Files.createDirectory(root.resolve("current source with spaces"));
			input(source.resolve(".github/scripts/codex-package-operations.sh"));
			input(source.resolve(".github/scripts/codex-voice-runtime.sh"));
			input(state.root().resolve("fork_checkout/tooling/cli/pom.xml"));
			input(state.root().resolve("fork_checkout/.github/scripts/verify-voice-installer.mjs"));
			List<String> stages = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope,
				(command, directory, temporary, environment) ->
				{
					commands.add(List.copyOf(command));
					String response = "[\"zstd\"]\n";
					if (command.contains("select-binaries"))
						response = "codex codex-app-server\n";
					else if (command.contains("profile-env"))
						response = "CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=off\n";
					return new SystemCommands.Result(0, response, "optional observation unavailable");
				}, (command, directory, environment, log) ->
				{
					commands.add(List.copyOf(command));
					String stage = log.getFileName().toString().replace(".log", "");
					stages.add(stage);
					requireThat(environment.get("CODEX_UNLEASHED_BUILD_NUMBER"), "decimalBuild").isEqualTo(
						"999999999999999999999999999999");
					requireThat(environment.get("EMPTY_INPUT"), "emptyInput").isEqualTo("");
					requireThat(environment.containsKey("ABSENT_INPUT"), "absentInput").isFalse();
					requireThat(environment.get("CARGO_TARGET_DIR"), "freshTarget").isEqualTo(
						state.root().resolve("cargo_target").toString());
					if (stage.equals("recorded-runtime"))
						input(state.root().resolve("scratch/tooling/build/runtime/bin/codex-tooling"));
					if (stage.equals("archive-packages"))
						input(source.resolve("dist/x86_64-unknown-linux-musl/codex-package-x86_64-unknown-linux-musl.tar.gz"));
					return 0;
				}, Map.of("EMPTY_INPUT", "", "ImageOS", "", "ImageVersion", "different-image"), "Linux");
			Path archive = recipe.build(release(), state, source, null);
			List<String> recorder = commands.getLast();
			requireThat(recorder.contains("--image-os"), "presentEmptyImageRecorded").isTrue();
			requireThat(recorder.get(recorder.indexOf("--image-os") + 1), "emptyImageValue").isEqualTo("");
			requireThat(Files.isRegularFile(archive), "archivePresent").isTrue();
			requireThat(stages, "productionStages").isEqualTo(List.of("recorded-runtime", "source-identity",
				"voice-build", "build-binaries", "archive-packages", "smoke-gzip", "smoke-zstd", "legal-finalize",
				"extract-final-gzip", "decompress-final-zstd", "extract-final-zstd", "voice-final-gzip",
				"voice-final-zstd", "installer-verification", "voice-final-installed", "native-inputs"));
			List<String> build = commands.stream().filter(command -> command.contains("build-binaries")).
				findFirst().orElseThrow();
			requireThat(build.get(1), "authenticatedSharedOperation").isEqualTo(
				source.resolve(".github/scripts/codex-package-operations.sh").toString());
			requireThat(build.get(build.indexOf("--target-dir") + 1), "currentTargetPath").isEqualTo(
				state.root().resolve("cargo_target").toString());
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Reports an actual missing recorded operation before any native process is requested.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void missingOperationIsPrerequisiteFailure() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-missing-recipe-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Path source = Files.createDirectory(root.resolve("source"));
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope,
				(command, directory, temporary, environment) ->
				{
					throw new AssertionError("Unexpected captured process");
				},
				(command, directory, environment, log) ->
				{
					throw new AssertionError("Unexpected native process");
				},
				Map.of(), "Linux");
			String diagnostic = "";
			try
			{
				recipe.build(release(), state, source, null);
			}
			catch (IOException expected)
			{
				diagnostic = expected.getMessage();
			}
			requireThat(diagnostic, "realMissingPrerequisite").contains("prerequisite_unavailable:").contains("pom.xml");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Rejects preseeded target binaries before any recorded operation may run.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void rejectsPreseededCargoTarget() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-preseed-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64),
				"x86_64-unknown-linux-musl");
			Files.writeString(state.root().resolve("cargo_target/completed-binary"), "untrusted");
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope,
				(command, directory, temporary, environment) ->
				{
					throw new AssertionError("Preseeded input reached an operation");
				}, (command, directory, environment, log) ->
				{
					throw new AssertionError("Preseeded input reached native execution");
				}, Map.of(), "Linux");
			String detail = "";
			try
			{
				recipe.build(release(), state, root.resolve("source"), null);
			}
			catch (IOException failure)
			{
				detail = failure.getMessage();
			}
			requireThat(detail, "freshTargetRequired").contains("fresh output required: cargo_target");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Compiles on the compile host and hands over the executables and the compile host's native inputs.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void compileHandsOverExecutables() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-compile-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64), DARWIN);
			Path source = Files.createDirectory(root.resolve("source"));
			Path handoff = root.resolve("handoff");
			List<String> stages = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope, capture(),
				(command, directory, environment, log) ->
				{
					commands.add(List.copyOf(command));
					String stage = log.getFileName().toString().replace(".log", "");
					stages.add(stage);
					if (stage.equals("recorded-runtime"))
						input(state.root().resolve("scratch/tooling/build/runtime/bin/codex-tooling"));
					if (stage.equals("native-inputs"))
						input(handoff.resolve("native-inputs.json"));
					if (stage.equals("compiled-output-handoff"))
						input(handoff.resolve("compiled-outputs.tar"));
					return 0;
				}, Map.of("RUNNER_ARCH", "ARM64", "RUSTY_V8_ARCHIVE", "/v8/archive.a"), "macOS");
			inputs(source, state);
			recipe.compile(release(DARWIN), state, source, handoff);
			requireThat(stages, "compileStages").isEqualTo(List.of("recorded-runtime", "source-identity",
				"build-binaries", "native-inputs", "compiled-output-handoff"));
			List<String> recorder = commands.get(3);
			requireThat(recorder, "compileRecorder").doesNotContain("--archive-result").doesNotContain("--package-root").
				contains("--configured-v8-archive");
			requireThat(recorder.get(recorder.indexOf("--output") + 1), "compileInputs").isEqualTo(
				handoff.resolve("native-inputs.json").toString());
			List<String> capture = commands.getLast();
			requireThat(capture.subList(capture.indexOf("--binaries") + 1, capture.size()), "handedOverBinaries").
				isEqualTo(List.of("codex", "codex-app-server"));
			requireThat(capture.get(capture.indexOf("--release-dir") + 1), "compiledReleaseDirectory").isEqualTo(
				state.root().resolve("cargo_target").resolve(DARWIN).resolve("release").toString());
			requireThat(commands.get(2), "compileBuild").doesNotContain("--restored-final-outputs");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Installs handed-over executables on the packaging host and records the compile host's native inputs.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void packagingInstallsHandoff() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-handoff-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			ReleaseReproductionState state = ReleaseReproductionState.allocate(scope, root, "a".repeat(64), DARWIN);
			Path source = Files.createDirectory(root.resolve("source"));
			Path compiled = Files.createDirectory(root.resolve("compiled"));
			List<List<String>> commands = new ArrayList<>();
			ReleaseReproductionRecipe recipe = new ReleaseReproductionRecipe(scope, capture(),
				(command, directory, environment, log) ->
				{
					commands.add(List.copyOf(command));
					String stage = log.getFileName().toString().replace(".log", "");
					if (stage.equals("recorded-runtime"))
						input(state.root().resolve("scratch/tooling/build/runtime/bin/codex-tooling"));
					if (stage.equals("archive-packages"))
						input(source.resolve("dist").resolve(DARWIN).resolve(ReleaseManifestSchema.packageName(DARWIN)));
					return 0;
				}, Map.of("RUNNER_ARCH", "X64", "RUSTY_V8_ARCHIVE", "/v8/archive.a"), "macOS");
			inputs(source, state);
			recipe.build(release(DARWIN), state, source, compiled);
			List<String> build = commands.stream().filter(command -> command.contains("build-binaries")).
				findFirst().orElseThrow();
			requireThat(build.get(build.indexOf("--restored-final-outputs") + 1), "handoffInstalled").isEqualTo(
				compiled.toString());
			List<String> recorder = commands.getLast();
			requireThat(recorder.get(recorder.indexOf("--compile-inputs") + 1), "compileHostInputs").isEqualTo(
				compiled.resolve("native-inputs.json").toString());
			requireThat(recorder, "packagingRecorder").contains("--archive-result").
				doesNotContain("--configured-v8-archive");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Creates the recorded scripts and fork checkout that every recipe phase requires.
	 *
	 * @param source current source directory
	 * @param state  owned attempt
	 * @throws IOException if the required operation cannot complete
	 */
	private static void inputs(Path source, ReleaseReproductionState state) throws IOException
	{
		input(source.resolve(".github/scripts/codex-package-operations.sh"));
		input(source.resolve(".github/scripts/codex-voice-runtime.sh"));
		input(state.root().resolve("fork_checkout/tooling/cli/pom.xml"));
		input(state.root().resolve("fork_checkout/.github/scripts/verify-voice-installer.mjs"));
	}

	/**
	 * Answers the recipe's bounded selection and profile queries.
	 *
	 * @return in-memory capture boundary
	 */
	private static SystemCommands.Capture capture()
	{
		return (command, directory, temporary, environment) ->
		{
			String response = "[\"zstd\"]\n";
			if (command.contains("select-binaries"))
				response = "codex codex-app-server\n";
			else if (command.contains("profile-env"))
				response = "CARGO_PROFILE_RELEASE_SPLIT_DEBUGINFO=packed\n";
			return new SystemCommands.Result(0, response, "");
		};
	}

	/**
	 * Creates an inert source/output fixture consumed only by in-memory collaborators.
	 *
	 * @param path current filesystem path
	 * @throws IOException if the required operation cannot complete
	 */
	private static void input(Path path) throws IOException
	{
		Files.createDirectories(path.getParent());
		Files.writeString(path, "fixture input, never executed");
	}

	/**
	 * Provides immutable authenticated recipe values without executable observations.
	 *
	 * @return authenticated fixture identity
	 */
	private static ReleaseReproductionSource.Release release()
	{
		return release("x86_64-unknown-linux-musl");
	}

	/**
	 * Provides immutable authenticated recipe values for a target without executable observations.
	 *
	 * @param target release target
	 * @return authenticated fixture identity
	 */
	private static ReleaseReproductionSource.Release release(String target)
	{
		return new ReleaseReproductionSource.Release("a".repeat(64), target, "b".repeat(40),
			"c".repeat(40), "rust-v1.2.3", "d".repeat(40), "1.2.3+999999999999999999999999999999",
			new BigInteger("999999999999999999999999999999"), "e".repeat(64), List.of("patches/1.patch"));
	}
}
