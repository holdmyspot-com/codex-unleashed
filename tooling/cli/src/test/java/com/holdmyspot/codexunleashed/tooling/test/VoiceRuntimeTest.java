package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import com.holdmyspot.codexunleashed.tooling.VoiceRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises voice staging and native acceptance through the public runtime command. */
public final class VoiceRuntimeTest
{
	/** Linux voice target used by the isolated staging fixture. */
	private static final String TARGET = "x86_64-unknown-linux-musl";
	/** Fixed upstream commit for the isolated staging fixture. */
	private static final String COMMIT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	/** Package version for the isolated staging fixture. */
	private static final String VERSION = "1.2.3";

	/** Creates the voice runtime tests. */
	public VoiceRuntimeTest()
	{
	}

	/**
	 * Rejects malformed arguments before touching paths or invoking child processes.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void rejectsInvalidArgumentsBeforeMutation()	throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicProcess process = new AtomicProcess();
			int status = VoiceRuntime.run(fixture.scope,
				new String[] {"stage", "--package-dir", fixture.packageRoot.toString()},
				Map.of(), process, fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(2);
			requireThat(process.calls, "processCalls").isEqualTo(0);
			requireThat(Files.exists(fixture.packageRoot.resolve("codex-resources")), "resources").isEqualTo(false);
		}
	}

	/**
	 * Stages sealed runtime bytes, then validates the same package without rewriting caller-owned files.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void stagesAndChecksCompleteVoiceTree() throws IOException
	{
		Map<String, String> applicationTargets = Map.of(
			"x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnu",
			"aarch64-unknown-linux-musl", "aarch64-unknown-linux-gnu",
			"x86_64-apple-darwin", "x86_64-apple-darwin",
			"aarch64-apple-darwin", "aarch64-apple-darwin",
			"x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc",
			"aarch64-pc-windows-msvc", "aarch64-pc-windows-msvc");
		for (Map.Entry<String, String> applicationTarget : applicationTargets.entrySet())
		{
			try (Fixture fixture = new Fixture(applicationTarget.getKey(), applicationTarget.getValue()))
			{
				AtomicProcess process = new AtomicProcess();
				int staged = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
					fixture.out, fixture.err);
				String manifestBefore = Files.readString(fixture.packageRoot.resolve("codex-resources/voice/manifest.json"));
				int checked = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), Map.of(), process,
					fixture.out, fixture.err);

				requireThat(staged, "stageStatus").isEqualTo(0);
				requireThat(checked, "checkStatus").isEqualTo(0);
				requireThat(Files.readString(fixture.packageRoot.resolve("codex-resources/voice/manifest.json")),
					"manifest").isEqualTo(manifestBefore);
				requireThat(process.calls, "identityCalls").isEqualTo(2);
				requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
	}

	/**
	 * Replaces hostile inherited loader paths only for Linux release-helper identity checks.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void setsLinuxStageLoaderPath() throws IOException
	{
		Map<String, String> linuxTargets = Map.of(
			"x86_64-unknown-linux-musl", "x86_64-unknown-linux-gnu",
			"aarch64-unknown-linux-musl", "aarch64-unknown-linux-gnu");
		Map<String, String> callerEnvironment = Map.of(
			"LD_LIBRARY_PATH", "/hostile/inherited/lib:/another/untrusted/path",
			"VOICE_PARENT_INPUT", "preserve-me");
		for (Map.Entry<String, String> applicationTarget : linuxTargets.entrySet())
		{
			try (Fixture fixture = new Fixture(applicationTarget.getKey(), applicationTarget.getValue()))
			{
				AtomicProcess process = new AtomicProcess();
				int stageStatus = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), callerEnvironment, process,
					fixture.out, fixture.err);

				requireThat(stageStatus, "stageStatus").isEqualTo(0);
				Invocation stageInvocation = process.invocations.getFirst();
				requireThat(stageInvocation.command(), "stageHelperCommand").isEqualTo(List.of(
					fixture.releaseRoot.resolve("codex-voice-host").toString(), "--build-commit"));
				requireThat(stageInvocation.workingDirectory(), "stageHelperWorkingDirectory").isEqualTo(fixture.releaseRoot);
				requireThat(stageInvocation.environment(), "stageHelperEnvironment").isEqualTo(Map.of(
					"LD_LIBRARY_PATH", fixture.releaseRoot.resolve("runtime/lib").toAbsolutePath().normalize().toString(),
					"VOICE_PARENT_INPUT", "preserve-me"));
				requireThat(stageInvocation.removedEnvironment(), "removedInheritedEnvironment").isEmpty();
				requireThat(callerEnvironment, "callerEnvironment").isEqualTo(Map.of(
					"LD_LIBRARY_PATH", "/hostile/inherited/lib:/another/untrusted/path",
					"VOICE_PARENT_INPUT", "preserve-me"));

				int checkStatus = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), callerEnvironment, process,
					fixture.out, fixture.err);
				requireThat(checkStatus, "checkStatus").isEqualTo(0);
				requireThat(process.invocations.get(1).environment(), "installedCheckEnvironment").
					isEqualTo(callerEnvironment);
				requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
	}

	/**
	 * Gives the staged Windows helper only the release runtime's DLL directory on its search path, as the sealed build's
	 * own identity query does, because the helper sits in the release directory while its DLLs sit in runtime/bin.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void setsWindowsStageLoaderPath() throws IOException
	{
		try (Fixture fixture = new Fixture("x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc"))
		{
			Map<String, String> callerEnvironment = Map.of(
				"Path", "C:\\hostile\\inherited;C:\\another\\untrusted",
				"LD_LIBRARY_PATH", "/caller/loader/path",
				"VOICE_PARENT_INPUT", "preserve-me");
			AtomicProcess process = new AtomicProcess();
			int stageStatus = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), callerEnvironment, process,
				fixture.out, fixture.err);

			requireThat(stageStatus, "stageStatus").isEqualTo(0);
			Invocation stageInvocation = process.invocations.getFirst();
			requireThat(stageInvocation.command(), "stageHelperCommand").isEqualTo(List.of(
				fixture.releaseRoot.resolve("codex-voice-host.exe").toString(), "--build-commit"));
			requireThat(stageInvocation.workingDirectory(), "stageHelperWorkingDirectory").isEqualTo(fixture.releaseRoot);
			requireThat(stageInvocation.environment(), "stageHelperEnvironment").isEqualTo(Map.of(
				"PATH", fixture.releaseRoot.resolve("runtime/bin").toAbsolutePath().normalize().toString(),
				"LD_LIBRARY_PATH", "/caller/loader/path",
				"VOICE_PARENT_INPUT", "preserve-me"));
			requireThat(callerEnvironment.get("Path"), "callerPath").
				isEqualTo("C:\\hostile\\inherited;C:\\another\\untrusted");

			int checkStatus = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), callerEnvironment, process,
				fixture.out, fixture.err);
			requireThat(checkStatus, "checkStatus").isEqualTo(0);
			requireThat(process.invocations.get(1).environment(), "installedCheckEnvironment").
				isEqualTo(callerEnvironment);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
		}
	}

	/**
	 * Replaces hostile inherited loader paths for the staged macOS helper, which finds its dylibs in runtime/lib.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void setsMacOsStageLoaderPath() throws IOException
	{
		try (Fixture fixture = new Fixture("x86_64-apple-darwin", "x86_64-apple-darwin"))
		{
			Map<String, String> callerEnvironment = Map.of(
				"DYLD_LIBRARY_PATH", "/hostile/inherited/lib",
				"LD_LIBRARY_PATH", "/caller/loader/path",
				"VOICE_PARENT_INPUT", "preserve-me");
			AtomicProcess process = new AtomicProcess();
			int stageStatus = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), callerEnvironment, process,
				fixture.out, fixture.err);

			requireThat(stageStatus, "stageStatus").isEqualTo(0);
			requireThat(process.invocations.getFirst().environment(), "stageHelperEnvironment").isEqualTo(Map.of(
				"DYLD_LIBRARY_PATH", fixture.releaseRoot.resolve("runtime/lib").toAbsolutePath().normalize().toString(),
				"LD_LIBRARY_PATH", "/caller/loader/path",
				"VOICE_PARENT_INPUT", "preserve-me"));

			int checkStatus = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), callerEnvironment, process,
				fixture.out, fixture.err);
			requireThat(checkStatus, "checkStatus").isEqualTo(0);
			requireThat(process.invocations.get(1).environment(), "installedCheckEnvironment").
				isEqualTo(callerEnvironment);
		}
	}

	/**
	 * Runs the installed-package identity check of non-Linux targets in the package directory with the caller's
	 * environment, because the installed helper's libraries sit beside it.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void keepsNonLinuxCheckEnvironmentUnchanged() throws IOException
	{
		Map<String, String> nonLinuxTargets = Map.of(
			"x86_64-apple-darwin", "x86_64-apple-darwin",
			"x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc");
		Map<String, String> callerEnvironment = Map.of(
			"LD_LIBRARY_PATH", "/caller/loader/path",
			"VOICE_PARENT_INPUT", "preserve-me");
		for (Map.Entry<String, String> applicationTarget : nonLinuxTargets.entrySet())
		{
			try (Fixture fixture = new Fixture(applicationTarget.getKey(), applicationTarget.getValue()))
			{
				AtomicProcess process = new AtomicProcess();
				int stageStatus = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), callerEnvironment, process,
					fixture.out, fixture.err);
				int checkStatus = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), callerEnvironment, process,
					fixture.out, fixture.err);

				requireThat(stageStatus, "stageStatus").isEqualTo(0);
				requireThat(checkStatus, "checkStatus").isEqualTo(0);
				requireThat(process.invocations.size(), "identityCalls").isEqualTo(2);
				String executableSuffix = "";
				if (applicationTarget.getKey().contains("windows"))
					executableSuffix = ".exe";
				Invocation checkInvocation = process.invocations.get(1);
				requireThat(checkInvocation.command(), "helperCommand").isEqualTo(List.of(
					fixture.packageRoot.resolve("codex-resources/voice/bin/codex-voice-host" + executableSuffix).toString(),
					"--build-commit"));
				requireThat(checkInvocation.workingDirectory(), "helperWorkingDirectory").isEqualTo(fixture.packageRoot);
				requireThat(checkInvocation.environment(), "helperEnvironment").isEqualTo(callerEnvironment);
				requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
		}
	}

	/**
	 * Reports the helper's own output when its identity query crashes, without a configured diagnostics directory.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void reportsHelperOutputWhenIdentityFails() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			CrashingProcess process = new CrashingProcess("partial stdout\n", "dyld: Library not loaded: libgio\n");
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err);

			String diagnostics = fixture.errors.toString(StandardCharsets.UTF_8);
			requireThat(status, "status").isEqualTo(1);
			requireThat(diagnostics, "diagnostics").contains("voice helper identity failed with status 134").
				contains("partial stdout").contains("dyld: Library not loaded: libgio");
			requireThat(Files.exists(fixture.packageRoot.resolve("codex-resources")), "resources").isEqualTo(false);
		}
	}

	/**
	 * Bounds the helper output quoted in a diagnostic to its tail.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void quotesOnlyTheTailOfLargeHelperOutput() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String noise = "noise line that must be dropped\n".repeat(20_000);
			CrashingProcess process = new CrashingProcess("", noise + "final abort message\n");
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err);

			String diagnostics = fixture.errors.toString(StandardCharsets.UTF_8);
			requireThat(status, "status").isEqualTo(1);
			requireThat(diagnostics, "diagnostics").contains("final abort message");
			requireThat(diagnostics.length() < 20_000, "boundedDiagnostics").isEqualTo(true);
		}
	}

	/**
	 * Retains the failed helper's complete output files in the directory named by the environment.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void retainsHelperLogsInConfiguredDirectory() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path diagnosticsRoot = fixture.root.resolve("diagnostics/nested");
			String noise = "complete stderr line\n".repeat(20_000);
			CrashingProcess process = new CrashingProcess("complete stdout\n", noise);
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(),
				Map.of("CODEX_VOICE_DIAGNOSTICS_DIR", diagnosticsRoot.toString()), process, fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			List<Path> stdoutFiles;
			List<Path> stderrFiles;
			try (java.util.stream.Stream<Path> files = Files.walk(diagnosticsRoot))
			{
				stdoutFiles = files.filter(path -> path.getFileName().toString().equals("stdout")).toList();
			}
			try (java.util.stream.Stream<Path> files = Files.walk(diagnosticsRoot))
			{
				stderrFiles = files.filter(path -> path.getFileName().toString().equals("stderr")).toList();
			}
			requireThat(stdoutFiles.size(), "stdoutFiles").isEqualTo(1);
			requireThat(stderrFiles.size(), "stderrFiles").isEqualTo(1);
			requireThat(Files.readString(stdoutFiles.getFirst()), "stdout").isEqualTo("complete stdout\n");
			requireThat(Files.readString(stderrFiles.getFirst()), "stderr").isEqualTo(noise);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
				contains(stderrFiles.getFirst().getParent().toString());
		}
	}

	/**
	 * Lists the files next to the failed helper and in its loader directory, with the loader search path it ran with,
	 * so a missing DLL can be diagnosed from the retained files alone.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void retainsDependencyListingOfFailedHelper() throws IOException
	{
		try (Fixture fixture = new Fixture("x86_64-pc-windows-msvc", "x86_64-pc-windows-msvc"))
		{
			Path diagnosticsRoot = fixture.root.resolve("diagnostics");
			CrashingProcess process = new CrashingProcess("", "");
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(),
				Map.of("CODEX_VOICE_DIAGNOSTICS_DIR", diagnosticsRoot.toString(), "Path", "C:\\caller"), process,
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			List<Path> listings;
			try (java.util.stream.Stream<Path> files = Files.walk(diagnosticsRoot))
			{
				listings = files.filter(path -> path.getFileName().toString().equals("dependencies.txt")).toList();
			}
			requireThat(listings.size(), "listings").isEqualTo(1);
			String listing = Files.readString(listings.getFirst());
			Path loaderDirectory = fixture.releaseRoot.resolve("runtime/bin").toAbsolutePath().normalize();
			requireThat(listing, "listing").contains("helper: " + fixture.releaseRoot.resolve("codex-voice-host.exe")).
				contains("PATH=" + loaderDirectory).contains("directory " + fixture.releaseRoot).
				contains("runtime").contains("codex-voice-host.exe").contains("directory " + loaderDirectory).
				contains("vcruntime140.dll").contains("gio-2.0-0.dll");
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
				contains(listings.getFirst().getParent().toString());
		}
	}

	/**
	 * Leaves no diagnostics behind when the helper identity query succeeds.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void writesNoDiagnosticsWhenIdentitySucceeds() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path diagnosticsRoot = fixture.root.resolve("diagnostics");
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(),
				Map.of("CODEX_VOICE_DIAGNOSTICS_DIR", diagnosticsRoot.toString()), new AtomicProcess(), fixture.out,
				fixture.err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(Files.exists(diagnosticsRoot), "diagnosticsRoot").isEqualTo(false);
		}
	}

	/**
	 * Rejects an invalid shipped library before attempting helper identity execution.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void validatesLibrariesBeforeIdentity() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.writeString(fixture.releaseRoot.resolve("runtime/lib/gstreamer-1.0/libgstapp.so"),
				"invalid replacement bytes");
			Map<String, String> callerEnvironment = Map.of("LD_LIBRARY_PATH", "/hostile/path");
			AtomicProcess process = new AtomicProcess();
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), callerEnvironment, process,
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
				contains("runtime library hash mismatch");
			requireThat(process.calls, "processCalls").isEqualTo(0);
			requireThat(callerEnvironment.get("LD_LIBRARY_PATH"), "callerEnvironment").isEqualTo("/hostile/path");
		}
	}

	/**
	 * Rejects bare, wrong-target, traversing, duplicate, unknown, missing and unshipped plugin paths.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void rejectsInvalidPluginPaths() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicProcess process = new AtomicProcess();
			int stageStatus = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err);
			requireThat(stageStatus, "stageStatus").isEqualTo(0);
			Path receiptPath = fixture.packageRoot.resolve("codex-resources/voice/runtime.json");
			String receipt = Files.readString(receiptPath);
			int pluginsStart = receipt.indexOf("\"plugins\":[");
			int pluginsEnd = receipt.indexOf(']', pluginsStart) + 1;
			String pluginArray = receipt.substring(pluginsStart + "\"plugins\":".length(), pluginsEnd);
			String canonicalAppPath = "\"lib/gstreamer-1.0/libgstapp.so\"";
			String canonicalRtpmanagerPath = "\"lib/gstreamer-1.0/libgstrtpmanager.so\"";
			List<String> invalidPluginArrays = List.of(
				pluginArray.replace(canonicalAppPath, "\"app\""),
				pluginArray.replace(canonicalAppPath, "\"plugins/libgstapp.dylib\""),
				pluginArray.replace(canonicalAppPath, "\"../lib/gstreamer-1.0/libgstapp.so\""),
				pluginArray.replace(canonicalRtpmanagerPath, canonicalAppPath),
				pluginArray.replace(canonicalAppPath, "\"lib/gstreamer-1.0/libgstunknown.so\""),
				pluginArray.replace("," + canonicalRtpmanagerPath, ""));
			for (String invalidPluginArray : invalidPluginArrays)
			{
				Files.writeString(receiptPath, receipt.substring(0, pluginsStart + "\"plugins\":".length()) +
					invalidPluginArray + receipt.substring(pluginsEnd));
				fixture.errors.reset();
				int status = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), Map.of(), process,
					fixture.out, fixture.err);

				requireThat(status, "status").isEqualTo(1);
				requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
					contains("plugin path");
			}
			int appLibraryStart = receipt.indexOf("{\"path\":\"lib/gstreamer-1.0/libgstapp.so\"");
			int appLibraryComma = receipt.indexOf("},", appLibraryStart) + 1;
			String receiptWithoutAppLibrary = receipt.substring(0, appLibraryStart) +
				receipt.substring(appLibraryComma + 1);
			Files.writeString(receiptPath, receiptWithoutAppLibrary);
			fixture.errors.reset();
			int status = VoiceRuntime.run(fixture.scope, fixture.checkArguments(), Map.of(), process,
				fixture.out, fixture.err);

		requireThat(status, "status").isEqualTo(1);
		requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
			contains("plugin path is not listed in libraries");
		}
	}

	/**
	 * Rejects package metadata with an unknown field, wrong version or identity, wrong variant, or unsafe paths.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void rejectsWrongCodexPackageMetadata() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path metadataPath = fixture.packageRoot.resolve("codex-package.json");
			String canonicalManifest = Files.readString(metadataPath);
			Map<String, String> invalidManifestsToDiagnostics = Map.of(
				canonicalManifest.replace("\"variant\":\"codex\"", "\"variant\":\"other\""),
					"package variant must be codex",
				canonicalManifest.replace("\"layoutVersion\":1", "\"layoutVersion\":2"),
					"package layoutVersion must be 1",
				canonicalManifest.replace("\"version\":\"" + VERSION + "\"", "\"version\":\"9.9.9\""),
					"package target/version does not match requested identity",
				canonicalManifest.replace("\"target\":\"" + TARGET + "\"",
					"\"target\":\"aarch64-unknown-linux-musl\""),
					"package target/version does not match requested identity",
				canonicalManifest.replace("\"entrypoint\":\"bin/codex\"",
					"\"entrypoint\":\"bin/codex.exe\"").replace(
					"\"execServer\":\"bin/codex-exec-server\"",
					"\"execServer\":\"bin/codex-exec-server.exe\""),
					"package entrypoint and execServer must be",
				canonicalManifest.replace("\"entrypoint\":\"bin/codex\"",
					"\"entrypoint\":\"../bin/codex\""),
					"package entrypoint and execServer must be",
				canonicalManifest.replace("\"execServer\":\"bin/codex-exec-server\"",
					"\"execServer\":\"/bin/codex-exec-server\""),
					"package entrypoint and execServer must be",
				canonicalManifest.replace("\"variant\":\"codex\"",
					"\"variant\":\"codex\",\"platform\":\"linux\""),
					"codex-package.json contains unknown field platform");
			AtomicProcess process = new AtomicProcess();
			for (Map.Entry<String, String> invalidManifest : invalidManifestsToDiagnostics.entrySet())
			{
				Files.writeString(metadataPath, invalidManifest.getKey());
				int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
					fixture.out, fixture.err);

				requireThat(status, "status").isEqualTo(1);
				requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
					contains(invalidManifest.getValue());
				requireThat(process.calls, "processCalls").isEqualTo(0);
			}
		}
	}

	/**
	 * Rejects package staging when its canonical exec-server file is absent.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void rejectsPackageWithoutExecServer() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Files.delete(fixture.packageRoot.resolve("bin/codex-exec-server"));
			AtomicProcess process = new AtomicProcess();
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
				contains("package execServer must be a regular file");
			requireThat(process.calls, "processCalls").isEqualTo(0);
		}
	}

	/**
	 * Accepts the Windows executable suffix before a later runtime-target mismatch stops staging.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void acceptsWindowsExecutableSuffix() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String windowsTarget = "x86_64-pc-windows-msvc";
			Files.writeString(fixture.packageRoot.resolve("bin/codex.exe"), "inert Windows launcher fixture bytes\n");
			Files.writeString(fixture.packageRoot.resolve("bin/codex-exec-server.exe"),
				"inert Windows exec-server fixture bytes\n");
			Path metadataPath = fixture.packageRoot.resolve("codex-package.json");
			String windowsManifest = Files.readString(metadataPath).
				replace("\"target\":\"" + TARGET + "\"", "\"target\":\"" + windowsTarget + "\"").
				replace("\"entrypoint\":\"bin/codex\"", "\"entrypoint\":\"bin/codex.exe\"").
				replace("\"execServer\":\"bin/codex-exec-server\"",
					"\"execServer\":\"bin/codex-exec-server.exe\"");
			Files.writeString(metadataPath, windowsManifest);
			AtomicProcess process = new AtomicProcess();
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(windowsTarget), Map.of(), process,
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(fixture.errors.toString(StandardCharsets.UTF_8), "diagnostics").
				contains("runtime receipt target or source commit does not match the requested identity");
			requireThat(process.calls, "processCalls").isEqualTo(0);
		}
	}

	/**
	 * Rejects an existing voice destination without changing any bytes or deleting caller content.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void refusesToReplaceExistingVoiceResources() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path voice = fixture.packageRoot.resolve("codex-resources/voice");
			Files.createDirectories(voice);
			Path marker = voice.resolve("caller-owned.txt");
			Files.writeString(marker, "preserve");
			AtomicProcess process = new AtomicProcess();
			int status = VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err);

			requireThat(status, "status").isEqualTo(1);
			requireThat(Files.readString(marker), "marker").isEqualTo("preserve");
			requireThat(process.calls, "processCalls").isEqualTo(0);
		}
	}

	/**
	 * Runs both named native test contracts and writes the passing result only after successful runs.
	 *
	 * @throws IOException if the isolated fixture cannot be created
	 */
	@Test
	public void verifiesNativeTestPair() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicProcess process = new AtomicProcess();
			requireThat(VoiceRuntime.run(fixture.scope, fixture.stageArguments(), Map.of(), process,
				fixture.out, fixture.err), "stageStatus").isEqualTo(0);
			Files.writeString(fixture.root.resolve("packaged-test-bin"), "binary");
			Files.writeString(fixture.root.resolve("installed-test-bin"), "binary");
			Path evidence = fixture.root.resolve("evidence");
			int status = VoiceRuntime.run(fixture.scope, fixture.verifyArguments(evidence),
				Map.of("VOICE_PARENT_INPUT", "preserved"), process,
				fixture.out, fixture.err);

			requireThat(status, "verifyStatus").isEqualTo(0);
			requireThat(Files.readString(evidence.resolve("result.json")), "result").contains("\"result\":\"passed\"");
			requireThat(process.commands.stream().filter(command -> command.contains("--list")).count(), "listedTests").
				isEqualTo(2L);
			requireThat(process.commands.stream().filter(command -> command.contains("--nocapture")).count(), "runTests").
				isEqualTo(2L);
			requireThat(Files.exists(evidence.resolve("installed_package_test_input")), "inputCleanup").isEqualTo(false);
			List<Invocation> installedRuns = process.invocations.stream().
				filter(invocation -> invocation.command().contains(
					"installed_package_initializes_closes_and_rejects_duplicate_initialization")).
				toList();
			requireThat(installedRuns.size(), "installedInvocations").isEqualTo(2);
			for (Invocation invocation : installedRuns)
			{
				requireThat(invocation.workingDirectory().getFileName().toString(), "inputWorkingDirectory").
					isEqualTo("installed_package_test_input");
				requireThat(invocation.packageInput(), "rawPackageInput").isEqualTo(fixture.packageRoot.toString());
				requireThat(invocation.commitInput(), "rawCommitInput").isEqualTo(COMMIT);
				requireThat(invocation.environment().get("VOICE_PARENT_INPUT"), "callerEnvironment").isEqualTo("preserved");
				requireThat(invocation.environment().keySet().stream().anyMatch(key -> key.startsWith("CODEX_TEST_")),
					"noNewEnvironmentInputs").isEqualTo(false);
				requireThat(invocation.environment().containsKey("CARGO_BIN_EXE_codex-voice-host"), "noSourceHelperInput").
					isEqualTo(false);
				requireThat(invocation.removedEnvironment(), "runfilesRemoval").
					contains("RUNFILES_MANIFEST_FILE").contains("JAVA_RUNFILES");
				requireThat(invocation.timeout(), "installedDeadline").isEqualTo(Duration.ofSeconds(60));
			}
		}
	}

	/**
	 * Captured process invocation and the installed-test input contents.
	 *
	 * @param command executed command arguments
	 * @param workingDirectory child working directory
	 * @param environment child environment additions
	 * @param removedEnvironment inherited environment variables removed
	 * @param timeout child process deadline
	 * @param packageInput raw installed-package input value
	 * @param commitInput raw expected-commit input value
	 */
	private record Invocation(List<String> command, Path workingDirectory, Map<String, String> environment,
		Set<String> removedEnvironment, Duration timeout, String packageInput, String commitInput)
	{
	}

	/**
	 * Deterministic process adapter that records commands and writes expected output fixtures.
	 *
	 * @see VoiceRuntime.Processes
	 */
	private static final class AtomicProcess implements VoiceRuntime.Processes
	{
		/** Number of child process calls. */
		private int calls;
		/** Commands received by the process adapter. */
		private final List<List<String>> commands = new ArrayList<>();
		/** Full invocation details received by the process adapter. */
		private final List<Invocation> invocations = new ArrayList<>();

		/** Creates an empty process recorder. */
		private AtomicProcess()
		{
		}

		@Override
		public int run(List<String> command, Path workingDirectory, Map<String, String> environment,
			Set<String> removedEnvironment, Duration timeout, Path stdout, Path stderr) throws IOException
		{
			calls += 1;
			commands.add(List.copyOf(command));
			Path packageInputPath = workingDirectory.resolve("installed_package_path.txt");
			Path commitInputPath = workingDirectory.resolve("expected_build_commit.txt");
			String packageInput = "";
			if (Files.exists(packageInputPath))
				packageInput = Files.readString(packageInputPath, StandardCharsets.UTF_8);
			String commitInput = "";
			if (Files.exists(commitInputPath))
				commitInput = Files.readString(commitInputPath, StandardCharsets.UTF_8);
			invocations.add(new Invocation(List.copyOf(command), workingDirectory, Map.copyOf(environment),
				Set.copyOf(removedEnvironment), timeout, packageInput, commitInput));
			Files.writeString(stderr, "", StandardOpenOption.CREATE_NEW);
			if (command.getLast().equals("--build-commit"))
				Files.writeString(stdout, COMMIT + "\n", StandardOpenOption.CREATE_NEW);
			else if (command.contains("--list"))
				Files.writeString(stdout, command.get(3) + ": test\n", StandardOpenOption.CREATE_NEW);
			else
				Files.writeString(stdout, "running 1 test\ntest passed\ntest result: ok. 1 passed; 0 failed; " +
					"0 ignored; 0 measured; 0 filtered out; finished in 0.00s\n", StandardOpenOption.CREATE_NEW);
			return 0;
		}
	}

	/** Process adapter whose helper aborts with status 134 after writing fixed output. */
	private static final class CrashingProcess implements VoiceRuntime.Processes
	{
		/** Text written to the helper's standard output. */
		private final String stdoutText;
		/** Text written to the helper's standard error. */
		private final String stderrText;

		/**
		 * Creates a crashing helper.
		 *
		 * @param stdoutText text written to standard output
		 * @param stderrText text written to standard error
		 */
		private CrashingProcess(String stdoutText, String stderrText)
		{
			this.stdoutText = stdoutText;
			this.stderrText = stderrText;
		}

		@Override
		public int run(List<String> command, Path workingDirectory, Map<String, String> environment,
			Set<String> removedEnvironment, Duration timeout, Path stdout, Path stderr) throws IOException
		{
			Files.writeString(stdout, stdoutText, StandardOpenOption.CREATE_NEW);
			Files.writeString(stderr, stderrText, StandardOpenOption.CREATE_NEW);
			return 134;
		}
	}

	/** Temporary package and release trees used by direct command tests. */
	private static final class Fixture implements AutoCloseable
	{
		/** Temporary root containing all fixture paths. */
		private final Path root;
		/** Upstream source fixture directory. */
		private final Path sourceRoot;
		/** Sealed release fixture directory. */
		private final Path releaseRoot;
		/** Caller-owned package fixture directory. */
		private final Path packageRoot;
		/** Application target staged by this fixture. */
		private final String target;
		/** Native target declared by this fixture's runtime receipt. */
		private final String voiceTarget;
		/** Tooling scope used by the command. */
		private final ToolingScope scope = new DefaultToolingScope();
		/** Captured error stream bytes. */
		private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
		/** Captured command output stream. */
		private final PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		/** Captured command error stream. */
		private final PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);

		/**
		 * Creates an isolated package fixture.
		 *
		 * @throws IOException if temporary fixture files cannot be created
		 */
		private Fixture() throws IOException
		{
			this(TARGET, "x86_64-unknown-linux-gnu");
		}

		/**
		 * Creates a package fixture for the selected application and native runtime targets.
		 *
		 * @param target application target for staging and checking
		 * @param voiceTarget native target recorded in the sealed runtime receipt
		 * @throws IOException if temporary fixture files cannot be created
		 */
		private Fixture(String target, String voiceTarget) throws IOException
		{
			this.target = target;
			this.voiceTarget = voiceTarget;
			root = Files.createTempDirectory("voice package with spaces ");
			sourceRoot = Files.createDirectory(root.resolve("source"));
			releaseRoot = Files.createDirectory(root.resolve("release"));
			packageRoot = Files.createDirectory(root.resolve("package"));
			createFixture();
		}

		/**
		 * Performs createFixture for the voice runtime.
		 *
		 * @throws IOException if the operation fails
		 */
		private void createFixture() throws IOException
		{
			Path voiceSource = sourceRoot.resolve("third_party/voice");
			Files.createDirectories(voiceSource.resolve("licenses"));
			Files.writeString(voiceSource.resolve("sources.json"), "{\"sources\":[\"fixture\"]}\n");
			for (String name : List.of("NOTICE.md", "licenses/LGPL-2.1.txt", "licenses/Opus.txt", "licenses/PCRE2.md",
				"licenses/libffi.txt", "licenses/proxy-libintl.txt", "licenses/sljit.txt",
				"licenses/zlib.txt"))
			{
				Path file = voiceSource.resolve(name);
				Files.createDirectories(file.getParent());
				Files.writeString(file, "fixture license " + name + "\n");
			}
			Files.createDirectories(packageRoot.resolve("bin"));
			Files.createDirectories(packageRoot.resolve("codex-path"));
			String executableSuffix = "";
			if (target.contains("windows"))
				executableSuffix = ".exe";
			Files.writeString(packageRoot.resolve("bin/codex" + executableSuffix),
				"inert package launcher fixture bytes\n");
			Files.writeString(packageRoot.resolve("bin/codex-exec-server" + executableSuffix),
				"inert exec-server fixture bytes\n");
			// Same fields, values and order as scripts/codex_package/layout.py writes for the codex variant
			Files.writeString(packageRoot.resolve("codex-package.json"),
				"{\"layoutVersion\":1,\"version\":\"" + VERSION + "\",\"target\":\"" + target + "\"," +
				"\"variant\":\"codex\",\"entrypoint\":\"bin/codex" + executableSuffix + "\"," +
				"\"execServer\":\"bin/codex-exec-server" + executableSuffix + "\"," +
				"\"resourcesDir\":\"codex-resources\",\"pathDir\":\"codex-path\"}\n");
			Path runtime = releaseRoot.resolve("runtime");
			List<String> pluginNames = List.of("app", "audioconvert", "audioresample", "coreelements", "opus", "rtp",
				"rtpmanager");
			boolean isWindowsTarget = target.contains("windows");
			boolean isMacOsTarget = target.contains("apple-darwin");
			String pluginPrefix = "lib/gstreamer-1.0/libgst";
			String pluginSuffix = ".so";
			String gioPath = "lib/libgio-2.0.so.0";
			if (isMacOsTarget)
			{
				pluginPrefix = "plugins/libgst";
				pluginSuffix = ".dylib";
				gioPath = "lib/libgio-2.0.0.dylib";
			}
			if (isWindowsTarget)
			{
				pluginPrefix = "bin/gst";
				pluginSuffix = ".dll";
				gioPath = "bin/gio-2.0-0.dll";
			}
			List<String> pluginPaths = new ArrayList<>();
			for (String pluginName : pluginNames)
				pluginPaths.add(pluginPrefix + pluginName + pluginSuffix);
			List<String> paths = new ArrayList<>(pluginPaths);
			paths.add(gioPath);
			if (isWindowsTarget)
				paths.add("bin/vcruntime140.dll");
			List<String> libraryJson = new ArrayList<>();
			for (String relative : paths)
			{
				Path library = runtime.resolve(relative);
				Files.createDirectories(library.getParent());
				Files.writeString(library, "native fixture " + relative);
				String digest = sha256(library);
				if (relative.equals("bin/vcruntime140.dll"))
					libraryJson.add("{\"path\":\"" + relative + "\",\"sha256\":\"" + digest + "\"}");
				else
					libraryJson.add("{\"path\":\"" + relative + "\",\"sha256\":\"" + digest +
						"\",\"sourcePath\":\"src/" + library.getFileName() + "\",\"sourceSha256\":\"" +
						"b".repeat(64) + "\",\"imports\":[]}");
			}
			String sourceDigest = sha256(voiceSource.resolve("sources.json"));
			String receipt = "{\"schemaVersion\":1,\"developmentOnly\":false,\"distribution\":\"publicRelease\"," +
				"\"target\":\"" + voiceTarget + "\",\"sourceCommit\":\"" + COMMIT + "\",\"sourceManifestSha256\":\"" +
				sourceDigest + "\",\"inventorySha256\":\"" + "c".repeat(64) + "\",\"libraries\":[" +
				String.join(",", libraryJson) + "],\"plugins\":[" +
				String.join(",", pluginPaths.stream().map(pluginPath -> "\"" + pluginPath + "\"").toList()) + "]}\n";
			Files.writeString(runtime.resolve("runtime.json"), receipt);
			if (isWindowsTarget)
				Files.writeString(voiceSource.resolve("windows-crt.json"), "fixture Windows CRT metadata\n");
			Path helper = releaseRoot.resolve("codex-voice-host" + executableSuffix);
			Files.writeString(helper, "inert release host fixture bytes\n");
		}

		/**
		 * Performs stageArguments for the voice runtime.
		 *
		 * @return the stageArguments result
		 */
		private String[] stageArguments()
		{
			return stageArguments(target);
		}

		/**
		 * Creates stage arguments for the supplied application target.
		 *
		 * @param target supported application target
		 * @return the stage operation arguments
		 */
		private String[] stageArguments(String target)
		{
			return new String[] {"stage", "--release-dir", releaseRoot.toString(), "--package-dir", packageRoot.toString(),
				"--upstream-root", sourceRoot.toString(), "--target", target, "--build-commit", COMMIT,
				"--package-version", VERSION};
		}

		/**
		 * Performs checkArguments for the voice runtime.
		 *
		 * @return the checkArguments result
		 */
		private String[] checkArguments()
		{
			return new String[] {"check", "--package-dir", packageRoot.toString(), "--upstream-root", sourceRoot.toString(),
				"--target", target, "--build-commit", COMMIT, "--package-version", VERSION};
		}

		/**
		 * Performs verifyArguments for the voice runtime.
		 *
		 * @param evidence supplied evidence value
		 * @return the verifyArguments result
		 */
		private String[] verifyArguments(Path evidence)
		{
			return new String[] {"verify", "--package-dir", packageRoot.toString(), "--upstream-root", sourceRoot.toString(),
				"--target", TARGET, "--build-commit", COMMIT, "--package-version", VERSION, "--test-binary",
				root.resolve("packaged-test-bin").toString(), "--installed-test-binary",
				root.resolve("installed-test-bin").toString(),
				"--evidence-dir", evidence.toString()};
		}

		@Override
		public void close() throws IOException
		{
			scope.close();
			out.close();
			err.close();
			try (java.util.stream.Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Performs sha256 for the voice runtime.
	 *
	 * @param path supplied path value
	 * @return the sha256 result
	 * @throws IOException if the operation fails
	 */
	private static String sha256(Path path) throws IOException
	{
		try
		{
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (java.security.NoSuchAlgorithmException impossible)
		{
			throw new AssertionError(impossible);
		}
	}
}
