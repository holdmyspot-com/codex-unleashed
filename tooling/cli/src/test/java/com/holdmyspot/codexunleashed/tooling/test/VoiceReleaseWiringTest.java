package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that the maintained package workflow wires the sealed voice runtime through final release consumers.
 */
public final class VoiceReleaseWiringTest
{
	/** Creates independent workflow contract checks. */
	public VoiceReleaseWiringTest()
	{
	}

	/**
	 * Reads the explicit build context, selects the package job's real run blocks, and requires the complete voice gate.
	 *
	 * @throws IOException if the explicit context or workflow cannot be read
	 */
	@Test
	public void wiresAndVerifiesVoiceRelease() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope();
			BufferedReader contextReader = Files.newBufferedReader(Path.of(contextPath())))
		{
			JsonNode context = scope.getJsonMapper().readTree(contextReader);
			requireThat(context.isObject(), "contextObject").isTrue();
			Path repository = Path.of(context.path("working_directory").asText()).toRealPath();
			Path temporary = Path.of(context.path("temporary_directory").asText()).toRealPath();
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : context.path("environment").properties())
				environment.put(entry.getKey(), entry.getValue().asText());
			Path workflow = repository.resolve(".github/workflows/upstream-auto-release.yml");
			JsonNode document = scope.getYamlMapper().readTree(Files.readString(workflow));
			JsonNode packageJob = document.path("jobs").path("package");
			requireThat(packageJob.isObject(), "packageJob").isTrue();
			Map<String, JsonNode> detectionSteps = packageSteps(document.path("jobs").path("detect").path("steps"));
			String matrixBody = requiredRun(detectionSteps, "mode").path("run").asText();
			for (String target : List.of("x86_64-unknown-linux-musl", "aarch64-unknown-linux-musl",
				"aarch64-apple-darwin", "x86_64-apple-darwin", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc"))
				requireThat(matrixBody, "preservedTargetMatrix").contains(target);
			Map<String, JsonNode> steps = packageSteps(packageJob.path("steps"));
			JsonNode build = requiredRun(steps, "voice-build");
			JsonNode archiveVerification = requiredRun(steps, "voice-final-verification");
			String buildBody = build.path("run").asText();
			String verifyBody = archiveVerification.path("run").asText();
			requireThat(buildBody, "producerCall").contains("codex-voice-runtime.sh build");
			requireThat(buildBody, "patchedCommit").contains("git -C \"$physical_source\" rev-parse HEAD");
			requireThat(buildBody, "resultDirectory").contains("test ! -e \"$result_dir\"");
			requireThat(buildBody, "windowsExecutableSuffix").contains("suffix='.exe'");
			requireThat(verifyBody, "gzipVerification").contains("tar -xzf");
			requireThat(verifyBody, "zstdResolver").contains("resolve-zstd");
			requireThat(verifyBody, "runtimeVerification").contains("voice-runtime verify");
			requireThat(verifyBody, "installerHandoff").contains("verify-voice-installer.mjs");
			requireThat(verifyBody, "installedRuntimeVerification").contains("--installed-package-output");
			writeShellBlocks(temporary, buildBody, verifyBody);
			requireActionInputs(steps.get("package"));
			requireTargetMatrix(packageJob);
			requireIntelVoiceHandoff(document.path("jobs"), buildBody, verifyBody);
			Path windowsWrapper = repository.resolve(".github/scripts/setup-baseline-windows-voice.ps1");
			requireWindowsWrapper(windowsWrapper);
			requireThat(environment.containsKey("PATH"), "actualEnvironment").isTrue();
		}
	}

	/**
	 * Runs the extracted producer and final-verification shell bodies against fake external command boundaries.
	 * <p>
	 * Skipped on Windows because the fixtures drive the bodies through {@code bash -c} and compare the paths that
	 * Bash prints ({@code pwd -P}, such as {@code /d/a/_temp/...}) with native Windows paths ({@code D:\a\_temp\...}).
	 * Java also does not escape the quotes in a multi-line {@code -c} argument on Windows, so Bash rejects the body
	 * ({@code unexpected EOF while looking for matching}). Both are properties of this fixture, not of the workflow, which
	 * runs the same bodies under Git Bash on Windows runners. The Windows-visible wiring contracts stay covered by
	 * {@link #wiresAndVerifiesVoiceRelease()} and {@link #verifiesNativeWindowsVoiceWrapper()}.
	 *
	 * @throws IOException if the explicit context or workflow cannot be read
	 */
	@Test
	public void runsVoiceShellBlocksAgainstFakeBoundaries() throws IOException
	{
		if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows"))
		{
			throw new SkipException("The Bash-body fixtures compare MSYS-style paths with native Windows paths and " +
				"depend on POSIX quoting of the bash -c argument");
		}
		try (DefaultToolingScope scope = new DefaultToolingScope();
			BufferedReader contextReader = Files.newBufferedReader(Path.of(contextPath())))
		{
			JsonNode context = scope.getJsonMapper().readTree(contextReader);
			Path repository = Path.of(context.path("working_directory").asText()).toRealPath();
			Path temporary = Path.of(context.path("temporary_directory").asText()).toRealPath();
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : context.path("environment").properties())
				environment.put(entry.getKey(), entry.getValue().asText());
			Path workflow = repository.resolve(".github/workflows/upstream-auto-release.yml");
			JsonNode document = scope.getYamlMapper().readTree(Files.readString(workflow));
			Map<String, JsonNode> steps = packageSteps(document.path("jobs").path("package").path("steps"));
			String buildBody = requiredRun(steps, "voice-build").path("run").asText();
			String verifyBody = requiredRun(steps, "voice-final-verification").path("run").asText();
			verifyProducerBlock(buildBody, temporary, environment);
			verifyArchiveBlock(verifyBody, temporary, environment);
		}
	}

	/**
	 * Parses and runs the Windows wrapper at its real PowerShell boundary when the native host is Windows.
	 *
	 * @throws IOException if the supplied context or native fixture cannot be read
	 */
	@Test
	public void verifiesNativeWindowsVoiceWrapper()
		throws IOException
	{
		if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows"))
			throw new SkipException("Native PowerShell parse and target forwarding require Windows");
		try (DefaultToolingScope scope = new DefaultToolingScope();
			BufferedReader reader = Files.newBufferedReader(Path.of(contextPath())))
		{
			JsonNode context = scope.getJsonMapper().readTree(reader);
			Path repository = Path.of(context.path("working_directory").asText()).toRealPath();
			Path temporary = Path.of(context.path("temporary_directory").asText()).toRealPath();
			Map<String, String> environment = new HashMap<>();
			for (Map.Entry<String, JsonNode> entry : context.path("environment").properties())
				environment.put(entry.getKey(), entry.getValue().asText());
			verifyWindowsWrapper(repository.resolve(".github/scripts/setup-baseline-windows-voice.ps1"),
				temporary, environment);
		}
	}

	/**
	 * Parses and exercises the real PowerShell wrapper on Windows; other hosts report this native boundary as pending.
	 *
	 * @param wrapper actual maintained Windows wrapper
	 * @param temporary explicit test-owned temporary directory
	 * @param inheritedEnvironment actual environment from test context
	 * @throws IOException if PowerShell fixtures cannot be written or executed
	 */
	private static void verifyWindowsWrapper(Path wrapper, Path temporary, Map<String, String> inheritedEnvironment)
		throws IOException
	{
		String parser = "$tokens=$null; $errors=$null; " +
			"[System.Management.Automation.Language.Parser]::ParseFile(" + powershellLiteral(wrapper.toString()) +
			",[ref]$tokens,[ref]$errors) | Out-Null; " +
			"if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Error $_ }; exit 1 }";
		ProcessResult parsed = runProcess(List.of("pwsh", "-NoProfile", "-Command", parser), temporary, Map.of());
		requireThat(parsed.status(), "nativePowerShellParse").withContext(parsed.output(), "diagnostics").isEqualTo(0);
		verifyPowerShellTarget(wrapper, temporary, inheritedEnvironment, "", "x86_64-pc-windows-msvc");
		verifyPowerShellTarget(wrapper, temporary, inheritedEnvironment, "aarch64-pc-windows-msvc",
			"aarch64-pc-windows-msvc");
		PowerShellFixture invalid = powershellFixture(
			wrapper, temporary, inheritedEnvironment, "unsupported", "unsupported");
		ProcessResult rejected = runProcess(invalid.command(), invalid.root(), invalid.environment());
		requireThat(rejected.status(), "unsupportedPowerShellTargetRejected").isNotEqualTo(0);
		requireThat(Files.exists(invalid.requestLog()), "unsupportedTargetRejectedBeforeNetwork").isFalse();
	}

	/**
	 * Checks default x64 and explicit ARM64 forwarding through the actual PowerShell script boundary.
	 *
	 * @param wrapper actual maintainer wrapper
	 * @param temporary explicit temp parent
	 * @param inheritedEnvironment actual environment
	 * @param requestedTarget optional explicit target
	 * @param expectedTarget forwarded target
	 * @throws IOException if fixture setup or process output fails
	 */
	private static void verifyPowerShellTarget(Path wrapper, Path temporary, Map<String, String> inheritedEnvironment,
		String requestedTarget, String expectedTarget) throws IOException
	{
		String fixtureName;
		if (requestedTarget.isEmpty())
			fixtureName = "default-x64";
		else
			fixtureName = "explicit-arm64";
		PowerShellFixture fixture = powershellFixture(
			wrapper, temporary, inheritedEnvironment, fixtureName, requestedTarget);
		ProcessResult result = runProcess(fixture.command(), fixture.root(), fixture.environment());
		requireThat(result.status(), "windowsVoiceWrapperStatus").withContext(result.output(), "diagnostics").isEqualTo(0);
		String setup = Files.readString(fixture.setupLog());
		requireThat(setup, "selectedTargetForwarded").contains(expectedTarget);
		requireThat(setup, "verifiedArchivePath").contains("baseline-windows-voice-downloads");
		requireThat(Files.readAllLines(fixture.requestLog()).size(), "twoPinnedDownloads").isEqualTo(2);
		String githubEnvironment = Files.readString(fixture.githubEnvironment());
		requireThat(githubEnvironment, "actualSystemRootExport").
			contains("VOICE_WINDOWS_SYSTEM_ROOT=" + environmentValue(inheritedEnvironment, "SystemRoot"));
		// The wrapper reports the architecture that PowerShell itself sees. The Bash environment that supplies the test
		// context can differ (an x64 Git Bash on a Windows ARM64 runner reports AMD64), so ask PowerShell, under the same
		// environment, for the value the wrapper is specified to read.
		ProcessResult probe = runProcess(List.of("pwsh", "-NoProfile", "-Command",
			"if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }"),
			fixture.root(), fixture.environment());
		requireThat(probe.status(), "hostArchitectureProbeStatus").withContext(probe.output(), "diagnostics").
			isEqualTo(0);
		String hostArchitecture = probe.output().strip();
		requireThat(hostArchitecture, "hostArchitecture").isNotBlank();
		requireThat(githubEnvironment, "actualHostArchitectureExport").
			contains("VOICE_WINDOWS_HOST_ARCH=" + hostArchitecture);
		requireThat(Files.exists(fixture.evidenceDirectory().resolve("cygwin-snapshot.json")),
			"manifestEvidenceCopied").isTrue();
	}

	/**
	 * Creates a fresh PowerShell script-boundary fixture with real archive size and digest checking.
	 *
	 * @param wrapper actual maintained wrapper
	 * @param temporary explicit temporary parent
	 * @param inheritedEnvironment actual environment map
	 * @param name fixture name
	 * @param requestedTarget explicit target, or empty to exercise the default
	 * @return fixture paths, command and process environment
	 * @throws IOException if fixture files cannot be created
	 */
	private static PowerShellFixture powershellFixture(Path wrapper, Path temporary,
		Map<String, String> inheritedEnvironment, String name, String requestedTarget) throws IOException
	{
		String rootName = "PowerShell voice fixture " + name + " " + UUID.randomUUID();
		Path root = Files.createDirectories(temporary.resolve(rootName));
		Path scriptDirectory = Files.createDirectories(root.resolve(".github/scripts"));
		byte[] archive = "fixture pinned archive bytes\n".getBytes(StandardCharsets.UTF_8);
		byte[] sourceArchive = "fixture pinned source bytes\n".getBytes(StandardCharsets.UTF_8);
		Path archiveFile = root.resolve("archive bytes with spaces");
		Path sourceArchiveFile = root.resolve("source archive bytes with spaces");
		Files.write(archiveFile, archive);
		Files.write(sourceArchiveFile, sourceArchive);
		String manifest = "{\"archive\":{\"tag\":\"rust-v0.1.0\",\"name\":\"voice.tar.zst\",\"bytes\":" +
			archive.length + ",\"sha256\":\"" + sha256(archiveFile) + "\"}," +
			"\"sourceArchive\":{\"name\":\"voice-src.tar.zst\",\"bytes\":" +
			sourceArchive.length + ",\"sha256\":\"" + sha256(sourceArchiveFile) + "\"}}\n";
		Files.writeString(scriptDirectory.resolve("voice-cygwin-snapshot.json"), manifest);
		Files.writeString(scriptDirectory.resolve("setup-voice-windows.ps1"),
			"param([string]$Target,[string]$SnapshotArchive)\n" +
			"Set-Content -NoNewline -Path $env:VOICE_SETUP_LOG -Value ($Target + '|' + $SnapshotArchive)\n" +
			"New-Item -ItemType Directory -Force -Path (Join-Path $env:RUNNER_TEMP " +
			"'voice-windows-build-tools/evidence') | Out-Null\n" +
			"Set-Content -Path (Join-Path $env:RUNNER_TEMP 'voice-windows-build-tools/evidence/tool.txt') -Value ready\n");
		Path temp = Files.createDirectories(root.resolve("runner temp with spaces"));
		Path evidence = root.resolve("evidence output");
		Path requestLog = root.resolve("web requests.txt");
		Path setupLog = root.resolve("setup argv.txt");
		Path githubEnvironment = root.resolve("github environment.txt");
		Path harness = root.resolve("invoke-wrapper.ps1");
		String script = "param([string]$Wrapper,[string]$Evidence,[string]$Target,[string]$RequestLog)\n" +
			"$ErrorActionPreference='Stop'\nfunction Invoke-WebRequest { param([string]$Uri,[string]$OutFile)\n" +
			"Add-Content -Path $RequestLog -Value $Uri\n" +
			"if ($Uri.EndsWith('voice.tar.zst')) { Copy-Item $env:VOICE_ARCHIVE_BYTES $OutFile } " +
			"else { Copy-Item $env:VOICE_SOURCE_ARCHIVE_BYTES $OutFile }\n}\n" +
			"$parameters=@{EvidenceDirectory=$Evidence}; if ($Target) { $parameters.Target=$Target }\n" +
			"& $Wrapper @parameters\n";
		Files.writeString(harness, script);
		List<String> command = new java.util.ArrayList<>(List.of("pwsh", "-NoProfile", "-File", harness.toString(),
			wrapper.toString(), evidence.toString(), requestedTarget, requestLog.toString()));
		Map<String, String> environment = new HashMap<>(inheritedEnvironment);
		environment.put("RUNNER_TEMP", temp.toString());
		environment.put("GITHUB_ENV", githubEnvironment.toString());
		environment.put("VOICE_ARCHIVE_BYTES", archiveFile.toString());
		environment.put("VOICE_SOURCE_ARCHIVE_BYTES", sourceArchiveFile.toString());
		environment.put("VOICE_SETUP_LOG", setupLog.toString());
		return new PowerShellFixture(root, evidence, requestLog, setupLog, githubEnvironment, List.copyOf(command),
			environment);
	}

	/**
	 * Looks up a Windows environment variable without regard to name case, because the Bash environment that supplies
	 * the test context spells names such as SystemRoot in upper case.
	 *
	 * @param environment environment captured in the test context
	 * @param name variable name
	 * @return the value, or null when no variable has that name in any case
	 */
	private static String environmentValue(Map<String, String> environment, String name)
	{
		for (Map.Entry<String, String> entry : environment.entrySet())
		{
			if (entry.getKey().equalsIgnoreCase(name))
				return entry.getValue();
		}
		return null;
	}

	/**
	 * Escapes a local path as a PowerShell single-quoted string literal.
	 *
	 * @param value path value
	 * @return escaped script literal
	 */
	private static String powershellLiteral(String value)
	{
		return "'" + value.replace("'", "''") + "'";
	}

	/**
	 * Selects actual package-job steps by their explicit stable identifiers.
	 *
	 * @param steps parsed package job steps
	 * @return run blocks keyed by step id
	 */
	private static Map<String, JsonNode> packageSteps(JsonNode steps)
	{
		Map<String, JsonNode> result = new HashMap<>();
		for (JsonNode step : steps)
		{
			String id = step.path("id").asText();
			if (!id.isEmpty())
				result.put(id, step);
		}
		return result;
	}

	/**
	 * Requires one selected package job run block.
	 *
	 * @param steps actual package job steps
	 * @param id stable step id
	 * @return selected run step
	 */
	private static JsonNode requiredRun(Map<String, JsonNode> steps, String id)
	{
		requireThat(steps.containsKey(id), "missingVoiceCallTripleOrVerificationGate").isTrue();
		JsonNode result = steps.get(id);
		requireThat(result.path("run").isTextual(), "selectedRunBlock").isTrue();
		return result;
	}

	/**
	 * Executes the actual YAML producer body with a fake producer at its external command boundary.
	 *
	 * @param body actual workflow shell body
	 * @param temporary context-owned temporary directory
	 * @param inheritedEnvironment actual test context environment
	 * @throws IOException if fixture files or process output cannot be handled
	 */
	private static void verifyProducerBlock(String body, Path temporary, Map<String, String> inheritedEnvironment)
		throws IOException
	{
		ProducerFixture success = producerFixture(temporary, inheritedEnvironment, "success");
		ProcessResult built = runShell(body, success.source(), success.environment());
		requireThat(built.status(), "producerSuccess").withContext(built.output(), "output").isEqualTo(0);
		String[] arguments = Files.readString(success.producerLog()).split("\0", -1);
		requireThat(arguments[0], "producerOperation").isEqualTo("build");
		requireThat(valueAfter(arguments, "--target"), "selectedArmTarget").
			isEqualTo("aarch64-pc-windows-msvc");
		requireThat(valueAfter(arguments, "--build-commit"), "patchedSourceCommit").
			isEqualTo(success.sourceCommit());
		requireThat(valueAfter(arguments, "--upstream-root"), "physicalSourcePath").
			isEqualTo(success.source().toRealPath().toString());
		requireThat(valueAfter(arguments, "--result-dir"), "freshResultPath").
			isEqualTo(success.resultDirectory().toString());
		requireThat(Files.isRegularFile(success.output()), "workflowStepOutputs").isTrue();
		String outputs = Files.readString(success.output());
		requireThat(outputs, "resultOutput").contains("result_dir=" + success.resultDirectory());
		requireThat(outputs, "releaseOutput").contains("release_dir=" + success.resultDirectory().resolve("release"));
		requireThat(outputs, "commitOutput").contains("build_commit=" + success.sourceCommit());
		requireThat(success.source().toString(), "sourcePathContainsSpace").contains(" ");

		ProducerFixture failed = producerFixture(temporary, inheritedEnvironment, "failure");
		ProcessResult failure = runShell(body, failed.source(), failed.environment());
		requireThat(failure.status(), "producerFailurePropagates").isEqualTo(23);
		requireThat(Files.exists(failed.output()), "failedProducerPublishesNoOutputs").isFalse();

		ProducerFixture mutated = producerFixture(temporary, inheritedEnvironment, "mutate");
		ProcessResult mutation = runShell(body, mutated.source(), mutated.environment());
		requireThat(mutation.status(), "sourceMutationRejected").isNotEqualTo(0);
		requireThat(Files.exists(mutated.output()), "mutatedSourcePublishesNoOutputs").isFalse();
	}

	/**
	 * Executes the parsed final-verification body with real tar bytes and controlled external command boundaries.
	 *
	 * @param body actual workflow shell body
	 * @param temporary explicit test-owned temporary parent
	 * @param inheritedEnvironment actual process environment from test context
	 * @throws IOException if fixtures or shell outputs cannot be read
	 */
	private static void verifyArchiveBlock(String body, Path temporary, Map<String, String> inheritedEnvironment)
		throws IOException
	{
		ArchiveFixture success = archiveFixture(temporary, inheritedEnvironment, "success");
		ProcessResult completed = runShell(body, success.source(), success.environment());
		requireThat(completed.status(), "archiveAndInstallerSuccess").
			withContext(completed.output(), "output").isEqualTo(0);
		List<String[]> verifies = recordedCommands(success.verifyLog());
		requireThat(verifies.size(), "threeVerificationConsumers").isEqualTo(3);
		requireThat(valueAfter(verifies.get(0), "--package-dir"), "gzipPhysicalPackage").
			contains("final primary gzip package");
		requireThat(valueAfter(verifies.get(1), "--package-dir"), "zstdPhysicalPackage").
			contains("final primary zstd package");
		requireThat(valueAfter(verifies.get(2), "--package-dir"), "installedPhysicalPackage").
			contains("installed voice package");
		for (String[] command : verifies)
		{
			requireThat(valueAfter(command, "--build-commit"), "verificationCommit").isEqualTo(success.sourceCommit());
			requireThat(valueAfter(command, "--target"), "verificationTarget").
				isEqualTo("aarch64-unknown-linux-musl");
			requireThat(valueAfter(command, "--package-version"), "verificationVersion").isEqualTo("9.8.7+41");
		}
		requireThat(Files.exists(success.runnerTemp().resolve("final primary gzip package aarch64-unknown-linux-musl")),
			"gzipPackageCleanedAfterConsumers").isFalse();
		requireThat(Files.exists(success.runnerTemp().resolve("final primary zstd package aarch64-unknown-linux-musl")),
			"zstdPackageCleanedAfterConsumers").isFalse();
		String[] installArguments = Files.readString(success.installerLog()).split("\n", -1);
		requireThat(valueAfter(installArguments, "--installed-package-output"), "installedOutputArgument").
			contains("installed voice package");
		requireThat(success.installerArchiveSha256(), "installerArchiveHash").
			isEqualTo(success.releaseArchiveSha256());
		requireThat(recordedArguments(success.zstdLog()), "zstdResolverArguments").
			isEqualTo(List.of("--fixture", "resolver argument with spaces", "-d", "-c"));
		requireThat(Files.exists(success.output()), "successfulVerificationOutput").isTrue();

		verifyArchiveFailure(body, temporary, inheritedEnvironment, "resolver-failure", "RESOLVER_FAIL", "1", 0);
		verifyArchiveFailure(body, temporary, inheritedEnvironment, "decompressor-failure", "ZSTD_FAIL", "1", 0);
		verifyArchiveFailure(body, temporary, inheritedEnvironment, "extractor-failure", "TAR_FAIL", "1", 0);
		verifyArchiveFailure(body, temporary, inheritedEnvironment, "gzip-verifier-failure", "VERIFY_FAIL_INDEX", "1", 1);
		verifyArchiveFailure(body, temporary, inheritedEnvironment, "zstd-verifier-failure", "VERIFY_FAIL_INDEX", "2", 2);
		verifyArchiveFailure(body, temporary, inheritedEnvironment, "installer-failure", "INSTALLER_FAIL", "1", 2);
		verifyArchiveFailure(body, temporary, inheritedEnvironment,
			"installed-verifier-failure", "VERIFY_FAIL_INDEX", "3", 3);
	}

	/**
	 * Requires a selected injected external failure to stop all later verification and success output.
	 *
	 * @param body actual package-job shell body
	 * @param temporary explicit temporary directory
	 * @param inheritedEnvironment actual inherited environment
	 * @param name scenario name
	 * @param variable injected boundary setting
	 * @param value failure selector
	 * @param expectedVerifyCount verifier calls before failure
	 * @throws IOException if fixture handling fails
	 */
	private static void verifyArchiveFailure(String body, Path temporary, Map<String, String> inheritedEnvironment,
		String name, String variable, String value, int expectedVerifyCount) throws IOException
	{
		ArchiveFixture fixture = archiveFixture(temporary, inheritedEnvironment, name);
		Map<String, String> environment = new HashMap<>(fixture.environment());
		environment.put(variable, value);
		ProcessResult result = runShell(body, fixture.source(), environment);
		Files.writeString(fixture.source().getParent().resolve("block.log"), result.output());
		requireThat(result.status(), "injectedExternalFailurePropagates").
			withContext(result.output(), "output").isNotEqualTo(0);
		requireThat(recordedCommands(fixture.verifyLog()).size(), "noLaterVerification").
			isEqualTo(expectedVerifyCount);
		requireThat(Files.exists(fixture.output()), "failureEmitsNoSuccessOutput").isFalse();
	}

	/**
	 * Builds real gzip and raw tar fixture archives plus explicit Python, zstd, tar, tooling and installer boundaries.
	 *
	 * @param temporary explicit test-owned temporary parent
	 * @param inheritedEnvironment actual inherited environment
	 * @param name scenario name
	 * @return isolated final verification fixture
	 * @throws IOException if fixture setup fails
	 */
	private static ArchiveFixture archiveFixture(Path temporary, Map<String, String> inheritedEnvironment, String name)
		throws IOException
	{
		Path root = Files.createDirectories(temporary.resolve("archive fixture " + name + "-" + UUID.randomUUID()));
		Path source = Files.createDirectories(root.resolve("patched source with spaces"));
		Files.createDirectories(source.resolve("scripts"));
		Path tracked = source.resolve("tracked-source.txt");
		Files.writeString(tracked, "patched source\n");
		git(source, "init", "-q");
		git(source, "add", "tracked-source.txt");
		git(source, "-c", "user.name=Voice fixture", "-c", "user.email=voice@example.invalid", "commit", "-qm",
			"patched source");
		String sourceCommit = git(source, "rev-parse", "HEAD");
		String target = "aarch64-unknown-linux-musl";
		Path dist = Files.createDirectories(source.resolve("dist/" + target));
		Path gzipArchive = dist.resolve("codex-package-" + target + ".tar.gz");
		Path zstdArchive = dist.resolve("codex-package-" + target + ".tar.zst");
		writeArchive(gzipArchive, true);
		writeArchive(zstdArchive, false);
		Path runnerTemp = Files.createDirectories(root.resolve("runner temp with spaces"));
		Path resultDirectory = Files.createDirectories(runnerTemp.resolve("voice build result"));
		Path verification = Files.createDirectories(resultDirectory.resolve("verification"));
		Files.writeString(verification.resolve("packaged-runtime-test-bin"), "packaged test");
		Files.writeString(verification.resolve("installed-package-runtime-test-bin"), "installed test");
		Path metadata = Files.createDirectories(runnerTemp.resolve("voice metadata with spaces"));
		Path installer = metadata.resolve("verify-voice-installer.mjs");
		Path installerLog = root.resolve("installer-argv.txt");
		String installerBody = "import fs from 'node:fs'; import crypto from 'node:crypto';\n" +
			"const a=process.argv.slice(2), at=n=>a[a.indexOf(n)+1];\n" +
			"const archive=at('--assets')+'/codex-package-' + at('--target') + '.tar.gz';\n" +
			"const digest=crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex');\n" +
			"const sums=fs.readFileSync(at('--assets')+'/SHA256SUMS','utf8');\n" +
			"if (!sums.includes(digest+'  codex-package-'+at('--target')+'.tar.gz')) process.exit(42);\n" +
			"fs.writeFileSync(process.env.VOICE_INSTALLER_LOG,a.join('\\n')+'\\n');\n" +
			"if (process.env.INSTALLER_FAIL) process.exit(43);\n" +
			"const out=at('--installed-package-output'); fs.mkdirSync(out);\n" +
			"const voice=out+'/codex-resources/voice'; fs.mkdirSync(voice,{recursive:true});\n" +
			"fs.writeFileSync(voice+'/manifest.json','fixture');\n" +
			"const evidence=at('--evidence-dir'); fs.mkdirSync(evidence,{recursive:true});\n" +
			"fs.writeFileSync(evidence+'/result.json','{}\\n');\n";
		Files.writeString(installer, installerBody);
		Path bin = Files.createDirectories(root.resolve("fixture-bin"));
		Path zstd = bin.resolve("zstd");
		Path zstdLog = root.resolve("zstd-argv.nul");
		Files.writeString(zstd, "#!/usr/bin/env bash\nset -euo pipefail\n" +
			"printf '%s\\0' \"$@\" >> \"$VOICE_ZSTD_LOG\"\n" +
			"if [[ \"${ZSTD_FAIL:-}\" == 1 ]]; then exit 32; fi\n" +
			"last=\"${!#}\"\ncat -- \"$last\"\n");
		zstd.toFile().setExecutable(true);
		Path tar = bin.resolve("tar");
		Files.writeString(tar, "#!/usr/bin/env bash\nset -euo pipefail\nprevious=''\n" +
			"for arg in \"$@\"; do\n" +
			"  if [[ \"$previous:$arg\" == '-xf:-' && \"${TAR_FAIL:-}\" == 1 ]]; then exit 33; fi\n" +
			"  previous=\"$arg\"\ndone\nexec /usr/bin/tar \"$@\"\n");
		tar.toFile().setExecutable(true);
		Path tooling = root.resolve("codex-tooling");
		Path verifyLog = root.resolve("verify-argv.nul");
		Path verifyCount = root.resolve("verify-count");
		String toolingBody = "#!/usr/bin/env bash\nset -euo pipefail\n" +
			"if [[ \"$1\" == resolve-zstd ]]; then\n" +
			"  if [[ \"${RESOLVER_FAIL:-}\" == 1 ]]; then exit 31; fi\n" +
			"  printf '%s\\n' '[\"zstd\",\"--fixture\",\"resolver argument with spaces\"]'; exit 0\n" +
			"fi\n" +
			"[[ \"$1:$2\" == voice-runtime:verify ]] || exit 70\n" +
			"count=0; if [[ -f \"$VOICE_VERIFY_COUNT\" ]]; then count=\"$(cat \"$VOICE_VERIFY_COUNT\")\"; fi\n" +
			"count=$((count + 1)); printf '%s\\n' \"$count\" > \"$VOICE_VERIFY_COUNT\"\n" +
			"printf '%s\\0' \"$@\" >> \"$VOICE_VERIFY_LOG\"; printf '\\0' >> \"$VOICE_VERIFY_LOG\"\n" +
			"evidence=''; previous=''; for arg in \"$@\"; do\n" +
			"if [[ \"$previous\" == --evidence-dir ]]; then evidence=\"$arg\"; fi; previous=\"$arg\"; done\n" +
			"mkdir -p \"$evidence\"\nif [[ \"${VERIFY_FAIL_INDEX:-}\" == \"$count\" ]]; then exit 40; fi\n";
		Files.writeString(tooling, toolingBody);
		tooling.toFile().setExecutable(true);
		Path output = root.resolve("step-output");
		Path fakeBin = bin;
		Map<String, String> environment = new HashMap<>(inheritedEnvironment);
		environment.put("PATH", fakeBin + ":" + inheritedEnvironment.get("PATH"));
		environment.put("SOURCE_ROOT", source.toString());
		environment.put("GITHUB_WORKSPACE", source.toString());
		environment.put("RUNNER_TEMP", runnerTemp.toString());
		environment.put("GITHUB_OUTPUT", output.toString());
		environment.put("RUNNER_OS", "Linux");
		environment.put("TARGET", target);
		environment.put("VOICE_RESULT", resultDirectory.toString());
		environment.put("VOICE_BUILD_COMMIT", sourceCommit);
		environment.put("PACKAGE_VERSION", "9.8.7+41");
		environment.put("VOICE_METADATA", metadata.toString());
		environment.put("CODEX_TOOLING", tooling.toString());
		environment.put("VOICE_INSTALLER_LOG", installerLog.toString());
		environment.put("VOICE_VERIFY_LOG", verifyLog.toString());
		environment.put("VOICE_VERIFY_COUNT", verifyCount.toString());
		environment.put("VOICE_ZSTD_LOG", zstdLog.toString());
		return new ArchiveFixture(source, sourceCommit, runnerTemp, output, installerLog, verifyLog, zstdLog,
			sha256(gzipArchive), sha256(gzipArchive), environment);
	}

	/**
	 * Writes a real small tar tree, optionally wrapped in gzip, for shell extraction tests.
	 *
	 * @param archive archive destination
	 * @param gzip whether to wrap the tar in gzip
	 * @throws IOException if fixture archive cannot be written
	 */
	private static void writeArchive(Path archive, boolean gzip) throws IOException
	{
		try (TarArchiveOutputStream tar = createArchiveOutput(archive, gzip))
		{
			byte[] voice = "voice resource\n".getBytes(StandardCharsets.UTF_8);
			TarArchiveEntry entry = new TarArchiveEntry("codex-resources/voice/manifest.json");
			entry.setSize(voice.length);
			tar.putArchiveEntry(entry);
			tar.write(voice);
			tar.closeArchiveEntry();
			tar.finish();
		}
	}

	/**
	 * Creates an archive writer over the requested physical format.
	 *
	 * @param archive archive destination
	 * @param gzip whether to wrap the tar stream in gzip
	 * @return owned archive writer
	 * @throws IOException if the destination cannot be opened
	 */
	private static TarArchiveOutputStream createArchiveOutput(Path archive, boolean gzip) throws IOException
	{
		OutputStream file = Files.newOutputStream(archive);
		if (gzip)
			return new TarArchiveOutputStream(new java.util.zip.GZIPOutputStream(file));
		return new TarArchiveOutputStream(file);
	}

	/**
	 * Hashes one small immutable fixture archive.
	 *
	 * @param file fixture archive
	 * @return lowercase SHA-256
	 * @throws IOException if fixture cannot be read
	 */
	private static String sha256(Path file) throws IOException
	{
		try
		{
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
			return java.util.HexFormat.of().formatHex(hash);
		}
		catch (java.security.NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable", failure);
		}
	}

	/**
	 * Parses the NUL-delimited command recordings written by the fixture command boundary.
	 *
	 * @param log command log path
	 * @return argument vectors
	 * @throws IOException if command output cannot be read
	 */
	private static List<String[]> recordedCommands(Path log) throws IOException
	{
		if (!Files.exists(log))
			return List.of();
		List<String[]> result = new java.util.ArrayList<>();
		for (String record : Files.readString(log).split("\0\0"))
			if (!record.isEmpty())
				result.add(record.split("\0"));
		return result;
	}

	/**
	 * Returns arguments recorded by the selected zstd fixture after preserving resolver parameters.
	 *
	 * @param log zstd command log
	 * @return NUL-delimited zstd argv
	 * @throws IOException if the command log cannot be read
	 */
	private static List<String> recordedArguments(Path log) throws IOException
	{
		String[] values = Files.readString(log).split("\0");
		return Arrays.stream(values).filter(value -> !value.isEmpty() && !value.endsWith(".tar.zst")).
			toList();
	}

	/**
	 * Creates a patched source fixture and a boundary producer for one workflow scenario.
	 *
	 * @param temporary test-owned temporary directory
	 * @param inheritedEnvironment actual process environment from test context
	 * @param scenario producer result behavior
	 * @return isolated workflow scenario
	 * @throws IOException if fixture setup fails
	 */
	private static ProducerFixture producerFixture(Path temporary, Map<String, String> inheritedEnvironment,
		String scenario) throws IOException
	{
		Path root = Files.createDirectories(temporary.resolve("voice fixture " + scenario + "-" + UUID.randomUUID()));
		Path source = Files.createDirectories(root.resolve("patched source with spaces"));
		Files.createDirectories(source.resolve(".github/scripts"));
		Path tracked = source.resolve("tracked-source.txt");
		Files.writeString(tracked, "patched source\n");
		git(source, "init", "-q");
		git(source, "add", "tracked-source.txt");
		git(source, "-c", "user.name=Voice fixture", "-c", "user.email=voice@example.invalid", "commit", "-qm",
			"patched source");
		String sourceCommit = git(source, "rev-parse", "HEAD");
		Path log = root.resolve("producer-argv.nul");
		String fake = "set -euo pipefail\nprintf '%s\\0' \"$@\" > \"$VOICE_PRODUCER_LOG\"\n" +
			"result_dir=''\nprevious=''\nfor value in \"$@\"; do\n" +
			"  if [[ \"$previous\" == --result-dir ]]; then result_dir=\"$value\"; fi\n" +
			"  previous=\"$value\"\ndone\n" +
			"if [[ \"$VOICE_SCENARIO\" == failure ]]; then exit 23; fi\n" +
			"mkdir -p \"$result_dir/release\" \"$result_dir/verification\" \"$result_dir/evidence\"\n" +
			"touch \"$result_dir/verification/packaged-runtime-test-bin\" " +
			"\"$result_dir/verification/installed-package-runtime-test-bin\"\n" +
			"if [[ \"$VOICE_SCENARIO\" == mutate ]]; then printf changed >> \"$VOICE_SOURCE_TRACKED\"; fi\n";
		Files.writeString(source.resolve(".github/scripts/codex-voice-runtime.sh"), fake);
		Path runnerTemp = Files.createDirectories(root.resolve("runner temp with spaces"));
		Path output = root.resolve("step-output");
		Path resultDirectory = runnerTemp.resolve("voice-build-aarch64-pc-windows-msvc");
		Map<String, String> environment = new HashMap<>(inheritedEnvironment);
		environment.put("SOURCE_ROOT", source.toString());
		environment.put("RUNNER_TEMP", runnerTemp.toString());
		environment.put("GITHUB_OUTPUT", output.toString());
		environment.put("TARGET", "aarch64-pc-windows-msvc");
		environment.put("RUNNER_OS", "Linux");
		environment.put("VOICE_WINDOWS_SYSTEM_ROOT", "");
		environment.put("VOICE_WINDOWS_HOST_ARCH", "");
		environment.put("VOICE_PRODUCER_LOG", log.toString());
		environment.put("VOICE_SCENARIO", scenario);
		environment.put("VOICE_SOURCE_TRACKED", tracked.toString());
		environment.put("CODEX_TOOLING", root.resolve("codex-tooling").toString());
		Files.writeString(root.resolve("codex-tooling"), "fixture launcher\n");
		return new ProducerFixture(source, sourceCommit, log, output, resultDirectory, environment);
	}

	/**
	 * Runs a fixed git command in the owned fixture repository.
	 *
	 * @param repository fixture repository
	 * @param arguments literal git arguments
	 * @return trimmed stdout
	 * @throws IOException if git cannot complete successfully
	 */
	private static String git(Path repository, String... arguments) throws IOException
	{
		List<String> command = new java.util.ArrayList<>();
		command.add("git");
		command.add("-C");
		command.add(repository.toString());
		command.addAll(List.of(arguments));
		ProcessResult result = runProcess(command, repository, Map.of());
		if (result.status() != 0)
			throw new IOException("Git fixture command failed: " + result.output());
		return result.output().trim();
	}

	/**
	 * Executes a shell block through the real Bash executable.
	 *
	 * @param body extracted workflow shell body
	 * @param workingDirectory fixture working directory
	 * @param environment inherited and fixture environment
	 * @return exit status and combined output
	 * @throws IOException if Bash cannot start or output cannot be read
	 */
	private static ProcessResult runShell(String body, Path workingDirectory, Map<String, String> environment)
		throws IOException
	{
		String bash = "bash";
		if (environment.containsKey("CODEX_TOOLING_BASH"))
			bash = environment.get("CODEX_TOOLING_BASH");
		return runProcess(List.of(bash, "-c", body), workingDirectory, environment);
	}

	/**
	 * Executes a bounded fixture command and collects its small output.
	 *
	 * @param command literal process argument vector
	 * @param workingDirectory fixture cwd
	 * @param environment environment values
	 * @return exit and output
	 * @throws IOException if the process cannot start or be read
	 */
	private static ProcessResult runProcess(List<String> command, Path workingDirectory,
		Map<String, String> environment) throws IOException
	{
		ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
		if (!environment.isEmpty())
		{
			builder.environment().clear();
			builder.environment().putAll(environment);
		}
		Process process = builder.start();
		String output;
		try (InputStream stream = process.getInputStream())
		{
			output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		try
		{
			return new ProcessResult(process.waitFor(), output);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the fixture process", failure);
		}
	}

	/**
	 * Returns an argument value immediately following an option.
	 *
	 * @param arguments producer vector
	 * @param option expected option
	 * @return following value
	 */
	private static String valueAfter(String[] arguments, String option)
	{
		for (int index = 0; index < arguments.length - 1; ++index)
		{
			if (arguments[index].equals(option))
				return arguments[index + 1];
		}
		return "";
	}

	/**
	 * Requires all producer values to be passed through the package action inputs.
	 *
	 * @param packageStep retained package action
	 */
	private static void requireActionInputs(JsonNode packageStep)
	{
		JsonNode inputs = packageStep.path("with");
		requireThat(inputs.path("voice-release-dir").asText(), "releaseDirectoryInput").contains("voice-build");
		requireThat(inputs.path("voice-build-commit").asText(), "buildCommitInput").contains("build_commit");
		requireThat(inputs.path("tooling-launcher").asText(), "toolingLauncherInput").contains("tooling_launcher");
	}

	/**
	 * Requires the package job to build every selected target that compiles and packages on one host.
	 *
	 * @param packageJob actual package job
	 */
	private static void requireTargetMatrix(JsonNode packageJob)
	{
		String matrix = packageJob.path("strategy").path("matrix").path("include").asText();
		requireThat(matrix, "matrixOutput").contains("needs.detect.outputs.same_host_package_matrix");
	}

	/**
	 * Requires the Intel macOS voice job to run the package job's producer body and the Intel macOS package job to run
	 * its final verification body on the restored producer outputs. Both jobs run on Apple silicon and route every later
	 * step through Rosetta 2, so their executables and native checks are x86_64.
	 *
	 * @param jobs actual workflow jobs
	 * @param buildBody the package job's producer body
	 * @param verifyBody the package job's archive and installer verification body
	 */
	private static void requireIntelVoiceHandoff(JsonNode jobs, String buildBody, String verifyBody)
	{
		JsonNode voiceJob = jobs.path("voice-intel");
		requireThat(voiceJob.path("runs-on").asText(), "voiceRunner").isEqualTo("macos-15");
		requireRosetta(voiceJob, "voiceRosetta");
		Map<String, JsonNode> voiceSteps = packageSteps(voiceJob.path("steps"));
		requireThat(requiredRun(voiceSteps, "voice-build").path("run").asText(), "intelProducerBody").
			isEqualTo(buildBody);
		JsonNode packageJob = jobs.path("package-intel");
		requireThat(packageJob.path("name").asText(), "intelPackageName").isEqualTo("Package - x86_64-apple-darwin");
		requireThat(packageJob.path("runs-on").asText(), "intelPackageRunner").isEqualTo("macos-15");
		requireRosetta(packageJob, "intelPackageRosetta");
		List<String> needs = new ArrayList<>();
		for (JsonNode need : packageJob.path("needs"))
			needs.add(need.asText());
		requireThat(needs, "intelPackageNeeds").containsAll(List.of("compile", "voice-intel"));
		Map<String, JsonNode> packageSteps = packageSteps(packageJob.path("steps"));
		JsonNode restore = requiredRun(packageSteps, "voice-build");
		requireThat(restore.path("env").path("ARCHIVE_SHA256").asText(), "intelHandoffDigest").
			contains("needs.voice-intel.outputs.archive_sha256");
		requireThat(restore.path("run").asText(), "intelRestore").contains("shasum -a 256 --check --strict");
		requireThat(requiredRun(packageSteps, "voice-final-verification").path("run").asText(), "intelVerifyBody").
			isEqualTo(verifyBody);
		requireActionInputs(packageSteps.get("package"));
	}

	/**
	 * Requires a job to route its later steps through Rosetta 2 with the repository's action.
	 *
	 * @param job actual workflow job
	 * @param name the name of the checked value
	 */
	private static void requireRosetta(JsonNode job, String name)
	{
		List<String> actions = new ArrayList<>();
		for (JsonNode step : job.path("steps"))
			actions.add(step.path("uses").asText());
		requireThat(actions, name).contains("./restart-metadata/.github/actions/rosetta-x86_64");
	}

	/**
	 * Retains the extracted Bash bodies so the configured syntax and ShellCheck gates inspect the actual YAML blocks.
	 *
	 * @param temporary explicit test-owned temporary parent
	 * @param buildBody actual producer body
	 * @param verifyBody actual archive and installer verification body
	 * @throws IOException if block files cannot be written
	 */
	private static void writeShellBlocks(Path temporary, String buildBody, String verifyBody) throws IOException
	{
		Path destination = Files.createDirectories(temporary.resolve("voice-release-shell-blocks"));
		Files.writeString(destination.resolve("voice-build.sh"), buildBody);
		Files.writeString(destination.resolve("voice-final-verification.sh"), verifyBody);
	}

	/**
	 * Requires the default and explicit Windows target forwarding to remain intact.
	 *
	 * @param wrapper selected Windows setup script
	 * @throws IOException if the wrapper cannot be read
	 */
	private static void requireWindowsWrapper(Path wrapper) throws IOException
	{
		String script = Files.readString(wrapper);
		requireThat(script, "defaultWindowsTarget").contains("$Target = \"x86_64-pc-windows-msvc\"");
		requireThat(script, "validatedArm64Target").contains("aarch64-pc-windows-msvc");
		requireThat(script, "forwardedWindowsTarget").contains("-Target $Target");
	}

	/**
	 * Windows PowerShell script boundary fixture.
	 *
	 * @param root fixture root
	 * @param evidenceDirectory evidence directory
	 * @param requestLog web request log
	 * @param setupLog setup script arguments log
	 * @param githubEnvironment workflow environment output
	 * @param command process argument vector
	 * @param environment process environment
	 */
	private record PowerShellFixture(Path root, Path evidenceDirectory, Path requestLog, Path setupLog,
		Path githubEnvironment, List<String> command, Map<String, String> environment)
	{
	}

	/**
	 * Final archive and installed-package shell fixture.
	 *
	 * @param source patched source directory
	 * @param sourceCommit patched source commit
	 * @param runnerTemp owned runner temp
	 * @param output step output file
	 * @param installerLog installer argv log
	 * @param verifyLog verifier argv log
	 * @param zstdLog decompressor argv log
	 * @param releaseArchiveSha256 finalized release archive digest
	 * @param installerArchiveSha256 installer fixture digest
	 * @param environment process environment
	 */
	private record ArchiveFixture(Path source, String sourceCommit, Path runnerTemp, Path output, Path installerLog,
		Path verifyLog, Path zstdLog, String releaseArchiveSha256, String installerArchiveSha256,
		Map<String, String> environment)
	{
	}

	/**
	 * Fixture producer invocation and identity.
	 *
	 * @param source patched source root
	 * @param sourceCommit patched source commit
	 * @param producerLog producer argv log
	 * @param output workflow output file
	 * @param resultDirectory fresh result directory
	 * @param environment producer process environment
	 */
	private record ProducerFixture(Path source, String sourceCommit, Path producerLog, Path output,
		Path resultDirectory, Map<String, String> environment)
	{
	}

	/**
	 * Small process result retained by fixture command boundaries.
	 *
	 * @param status process exit status
	 * @param output combined process output
	 */
	private record ProcessResult(int status, String output)
	{
	}

	/**
	 * Resolves the explicitly supplied four-field test context.
	 *
	 * @return context file path
	 */
	private static String contextPath()
	{
		String result = System.getProperty("quiet.build.test.context");
		requireThat(result, "contextPath").isNotBlank();
		return result;
	}
}
