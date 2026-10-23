package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies standalone package production and native archive consumption without external Java or Python commands.
 */
public final class PackageCommandRuntimeTest
{
	private static final String BUILD_COMMIT = "0123456789abcdef0123456789abcdef01234567";

	/**
	 * Creates package runtime tests.
	 */
	public PackageCommandRuntimeTest()
	{
	}

	/**
	 * Supplies ordinary and prepared-voice archive wrapper invocations.
	 *
	 * @return voice selection modes
	 */
	@DataProvider
	public Object[][] voiceSelections()
	{
		return new Object[][]{{false}, {true}};
	}

	/**
	 * Runs the maintained release wrapper with rejected Python commands and verifies archive output and staging cleanup.
	 *
	 * @param includeVoice indicates whether to include a prepared public voice runtime
	 * @throws IOException              if fixture access, linking, command execution, or cleanup fails
	 * @throws URISyntaxException       if rejected command fixture locations cannot be represented as URIs
	 * @throws InterruptedException     if a child process is interrupted
	 * @throws NoSuchAlgorithmException if the required SHA-256 implementation is unavailable
	 */
	@Test(dataProvider = "voiceSelections")
	public void buildsReleaseWrapperArchives(boolean includeVoice)
		throws IOException, URISyntaxException, InterruptedException, NoSuchAlgorithmException
	{
		Path root = Files.createTempDirectory("package-wrapper-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Files.createDirectory(workspace.resolve("codex-rs"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"), "[workspace.package]\nversion = \"0.160.0\"\n");
			byte[] payload = {0, (byte) 255, 1};
			Path binaries = Files.createDirectory(root.resolve("binaries"));
			for (String name : List.of("codex-app-server", "codex-app-server.exe", "codex-x86_64-unknown-linux-gnu",
				"codex-code-mode-host", "bwrap",
				"codex-x86_64-pc-windows-msvc.exe", "codex-code-mode-host.exe", "codex-command-runner.exe",
				"codex-windows-sandbox-setup.exe", "codex-windows-sandbox-service.exe"))
			{
				Path binary = Files.write(binaries.resolve(name), payload);
				if (Files.getFileAttributeView(binary, PosixFileAttributeView.class) != null)
					Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
			}
			Path rgArchive = root.resolve("ripgrep.zip");
			try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(rgArchive)))
			{
				for (String name : List.of("rg", "rg.exe"))
				{
					archive.putNextEntry(new ZipEntry(name));
					archive.write(payload);
					archive.closeEntry();
				}
			}
			String resource = "\"size\":" + Files.size(rgArchive) + ",\"hash\":\"sha256\",\"digest\":\"" +
				HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(rgArchive))) +
				"\",\"format\":\"zip\",\"providers\":[{\"url\":\"" + rgArchive.toUri() + "\"}]";
			Path rg = Files.writeString(root.resolve("rg-manifest"), "{\"platforms\":{\"linux-x86_64\":{\"path\":\"rg\"," +
				resource + "},\"windows-x86_64\":{\"path\":\"rg.exe\"," + resource + "}}}");
			Path zsh = Files.writeString(root.resolve("zsh-manifest"), "{\"platforms\":{}}");
			Path licenses = Files.createDirectory(root.resolve("licenses"));
			Path notices = Files.writeString(licenses.resolve("THIRD_PARTY_NOTICES.md"), "prepared notices\n");
			Path rejected = Files.createDirectory(root.resolve("rejected-commands"));
			for (String name : List.of("java", "python", "python3", "cargo", "codex"))
				JavaCommandFixtures.writeLauncher(rejected.resolve(name), RejectedCommandFixture.class);
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path repository = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", rejected + java.io.File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_TOOLING", runtime.resolve("bin/codex-tooling").toString());
			environment.put("GITHUB_WORKSPACE", repository.toString());
			environment.put("CODEX_PACKAGE_WORKSPACE_ROOT", workspace.toString());
			environment.put("CODEX_UNLEASHED_BUILD_NUMBER", "43");
			environment.put("CODEX_PACKAGE_RUST_LICENSES_DIR", licenses.toString());
			environment.put("CARGO_HOME", root.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", root.resolve("cargo-target").toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			environment.put("RUNNER_TEMP", temporary.toString());
			environment.put("TMPDIR", temporary.toString());
			Path archives = Files.createDirectory(root.resolve("archives"));
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			String hostTarget = "x86_64-unknown-linux-gnu";
			if (root.getFileSystem().getSeparator().equals("\\"))
				hostTarget = "x86_64-pc-windows-msvc";
			List<List<String>> selections = new ArrayList<>(List.of(List.of("app-server", hostTarget),
				List.of("primary", hostTarget)));
			if (!hostTarget.equals("x86_64-pc-windows-msvc"))
				selections.add(List.of("primary", "x86_64-pc-windows-msvc"));
			if (includeVoice)
				selections.removeIf(selection -> selection.getFirst().equals("app-server"));
			for (List<String> selection : selections)
			{
				List<String> command = new ArrayList<>(List.of(
					repository.resolve(".github/scripts/build-codex-package-archive.sh").toString(), "--target", selection.get(1),
					"--bundle", selection.get(0), "--entrypoint-dir", binaries.toString(), "--archive-dir", archives.toString(),
					"--rg-manifest", rg.toString(), "--zsh-manifest", zsh.toString()));
				if (selection.getFirst().equals("primary"))
					command.add("--target-suffixed-entrypoint");
				Path voiceRelease = root.resolve("voice release " + selection.get(1));
				if (includeVoice)
				{
					prepareVoiceRelease(voiceRelease, workspace, selection.get(1));
					command.addAll(List.of("--voice-release-dir", voiceRelease.toString(), "--build-commit", BUILD_COMMIT));
				}
				requireThat(runScript(command, root, environment, stdout, stderr),
					"wrapperStatus").withContext(Files.readString(stdout), "stdout").
					withContext(Files.readString(stderr), "stderr").isEqualTo(0);
				String stem = "codex-app-server-package";
				String binary = "codex-app-server";
				if (selection.getFirst().equals("primary"))
				{
					stem = "codex-package";
					binary = "codex";
				}
				if (selection.get(1).contains("windows"))
					binary = binary.concat(".exe");
				for (String suffix : List.of("tar.gz", "tar.zst"))
				{
					Path archive = archives.resolve(stem + "-" + selection.get(1) + "." + suffix);
					List<String> consumer;
					if (suffix.equals("tar.zst"))
						consumer = List.of(NativeUtilities.tarExecutable(), "--zstd", "-xOf", archive.toString(), "bin/" + binary);
					else
						consumer = List.of(NativeUtilities.tarExecutable(), "-xOzf", archive.toString(), "bin/" + binary);
					requireThat(run(consumer, root, environment, stdout, stderr), "consumerStatus").
						withContext(Files.readString(stderr), "stderr").isEqualTo(0);
					requireThat(Files.readAllBytes(stdout), "entrypointBytes").isEqualTo(payload);
					if (includeVoice)
					{
						verifyArchivedVoice(archive, suffix, selection.get(1), voiceRelease, workspace, root, environment,
							stdout, stderr);
					}
				}
				try (Stream<Path> paths = Files.list(temporary))
				{
					requireThat(paths.count(), "stagingEntriesAfterSuccess").isEqualTo(0L);
				}
				Files.delete(notices);
				requireThat(runScript(command, root, environment, stdout, stderr), "wrapperStatusWithoutNotices").isEqualTo(1);
				try (Stream<Path> paths = Files.list(temporary))
				{
					requireThat(paths.count(), "stagingEntriesAfterFailure").isEqualTo(0L);
				}
				Files.writeString(notices, "prepared notices\n");
			}
			requireThat(Files.exists(root.resolve("cargo-home")), "cargoHomeExists").isFalse();
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Produces all supported archive formats with a fresh bundled image and verifies their extracted binary payloads.
	 *
	 * @throws IOException          if fixture access, linking, command execution, or cleanup fails
	 * @throws URISyntaxException   if rejected command fixture locations cannot be represented as URIs
	 * @throws InterruptedException if a child process is interrupted
	 */
	@Test
	public void buildsStandalonePackageArchives() throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("package-runtime-");
		try
		{
			Path workspace = Files.createDirectory(root.resolve("workspace"));
			Files.createDirectory(workspace.resolve("codex-rs"));
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"), "[workspace.package]\nversion = \"0.160.0\"\n");
			byte[] payload = {0, (byte) 255, 1};
			Path binary = Files.write(root.resolve("prebuilt.exe"), payload);
			if (Files.getFileAttributeView(binary, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
			Path zsh = Files.writeString(root.resolve("zsh-manifest"), "{\"platforms\":{}}");
			Path licenses = Files.createDirectory(root.resolve("licenses"));
			Files.writeString(licenses.resolve("THIRD_PARTY_NOTICES.md"), "prepared notices\n");
			Path rejected = Files.createDirectory(root.resolve("rejected-commands"));
			for (String name : List.of("java", "python", "python3", "cargo", "codex"))
				JavaCommandFixtures.writeLauncher(rejected.resolve(name), RejectedCommandFixture.class);
			Path runtime = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), runtime.toString()});
			Path cache = root.resolve("cache");
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", rejected + java.io.File.pathSeparator + System.getenv("PATH"));
			environment.put("CODEX_UNLEASHED_BUILD_NUMBER", "42");
			environment.put("CODEX_PACKAGE_RUST_LICENSES_DIR", licenses.toString());
			environment.put("CARGO_HOME", root.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", root.resolve("cargo-target").toString());
			environment.put("XDG_CACHE_HOME", root.resolve("xdg").toString());
			environment.put("TMPDIR", temporary.toString());
			Path directory = root.resolve("package");
			Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
			Path repository = workflow.getParent().getParent().getParent();
			String hostTarget = "x86_64-unknown-linux-gnu";
			String entrypoint = "codex-app-server";
			if (root.getFileSystem().getSeparator().equals("\\"))
			{
				hostTarget = "x86_64-pc-windows-msvc";
				entrypoint += ".exe";
			}
			List<String> command = new ArrayList<>(List.of(runtime.resolve("bin/codex-tooling").toString(),
				"build-codex-package", "--repo", repository.toString(), "--workspace", workspace.toString(),
				"--cache-root", cache.toString(), "--target", hostTarget, "--variant", "codex-app-server",
				"--entrypoint-bin", binary.toString(), "--code-mode-host-bin", binary.toString(),
				"--rg-bin", binary.toString(), "--zsh-manifest", zsh.toString(), "--package-dir",
				directory.toString()));
			if (hostTarget.equals("x86_64-pc-windows-msvc"))
				command.addAll(List.of("--codex-command-runner-bin", binary.toString(),
					"--codex-windows-sandbox-setup-bin", binary.toString(),
					"--codex-windows-sandbox-service-bin", binary.toString()));
			else
				command.addAll(List.of("--bwrap-bin", binary.toString()));
			List<Path> archives = new ArrayList<>();
			for (String suffix : List.of("tar.gz", "tgz", "tar.zst", "zip"))
			{
				Path archive = root.resolve("package." + suffix);
				archives.add(archive);
				command.addAll(List.of("--archive-output", archive.toString()));
			}
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			requireThat(runScript(command, root, environment, stdout, stderr),
				"toolingStatus").withContext(Files.readString(stdout), "stdout").
				withContext(Files.readString(stderr), "stderr").isEqualTo(0);
			requireThat(Files.readString(directory.resolve("codex-package.json")), "packageMetadata").
				contains("\"version\": \"0.160.0+42\"");
			requireThat(Files.readAllBytes(directory.resolve("bin").resolve(entrypoint)), "entrypointBytes").
				isEqualTo(payload);
			requireThat(directory.resolve("licenses/rust/THIRD_PARTY_NOTICES.md"), "rustNotices").isRegularFile();
			requireThat(Files.exists(workspace.resolve("scripts/get_codex_package_version.py")), "pythonVersionHelperExists").
				isFalse();
			requireThat(Files.exists(root.resolve("cargo-home")), "cargoHomeExists").isFalse();
			for (Path archive : archives)
			{
				List<String> consumer;
				String name = archive.getFileName().toString();
				if (name.endsWith(".zip"))
					consumer = List.of("unzip", "-p", archive.toString(), "bin/" + entrypoint);
				else if (name.endsWith(".tar.zst"))
					consumer = List.of(NativeUtilities.tarExecutable(), "--zstd", "-xOf", archive.toString(),
						"bin/" + entrypoint);
				else
					consumer = List.of(NativeUtilities.tarExecutable(), "-xOzf", archive.toString(), "bin/" + entrypoint);
				requireThat(run(consumer, root, environment, stdout, stderr), "consumerStatus").
					withContext(Files.readString(stderr), "stderr").
					isEqualTo(0);
				requireThat(Files.readAllBytes(stdout), "archivedBytes").isEqualTo(payload);
			}
			try (Stream<Path> paths = Files.list(cache.resolve("archive-temp")))
			{
				requireThat(paths.count(), "temporaryEntries").isEqualTo(0L);
			}
			try (Stream<Path> paths = Files.list(temporary))
			{
				requireThat(paths.count(), "temporaryEntries").isEqualTo(0L);
			}
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Supplies malformed voice selections that must fail before invoking Java tooling.
	 *
	 * @return option lists and their required diagnostics
	 */
	@DataProvider
	public Object[][] invalidVoiceSelections()
	{
		return new Object[][]{
			{List.of("--voice-release-dir", "voice release"), "must be supplied together"},
			{List.of("--build-commit", BUILD_COMMIT), "must be supplied together"},
			{List.of("--voice-release-dir", "voice release", "--build-commit", "short"), "40 lowercase"},
			{List.of("--voice-release-dir", "voice release", "--build-commit",
				"ABCDEF0123456789ABCDEF0123456789ABCDEF01"), "40 lowercase"},
			{List.of("--voice-release-dir", "voice release", "--build-commit", BUILD_COMMIT, "--bundle", "app-server"),
				"Voice resources require --bundle primary"}
		};
	}

	/**
	 * Rejects invalid voice selections before tooling invocation or archive staging.
	 *
	 * @param options    invalid voice options
	 * @param diagnostic required validation diagnostic
	 * @throws IOException          if fixture creation, process capture, or cleanup fails
	 * @throws URISyntaxException   if the rejecting Java fixture location cannot be represented as a URI
	 * @throws InterruptedException if the wrapper process is interrupted
	 */
	@Test(dataProvider = "invalidVoiceSelections")
	public void rejectsInvalidWrapperVoice(List<String> options, String diagnostic)
		throws IOException, URISyntaxException, InterruptedException
	{
		Path root = Files.createTempDirectory("invalid-wrapper-voice-");
		try
		{
			Path rejected = root.resolve("rejected-tooling");
			JavaCommandFixtures.writeLauncher(rejected, RejectedCommandFixture.class);
			Path repository = Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
			Path temporary = Files.createDirectory(root.resolve("temporary"));
			Path archive = root.resolve("archives");
			List<String> command = new ArrayList<>(List.of(
				repository.resolve(".github/scripts/build-codex-package-archive.sh").toString(), "--target",
				"x86_64-unknown-linux-gnu", "--bundle", "primary", "--entrypoint-dir", root.resolve("binaries").toString(),
				"--archive-dir", archive.toString()));
			command.addAll(options);
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			requireThat(runScript(command, root, Map.of("CODEX_UNLEASHED_TOOLING", rejected.toString(),
				"RUNNER_TEMP", temporary.toString(), "TMPDIR", temporary.toString()), stdout, stderr), "status").isEqualTo(1);
			requireThat(Files.readString(stderr), "diagnostic").contains(diagnostic);
			requireThat(Files.exists(archive), "archiveDirectoryExists").isFalse();
			try (Stream<Path> children = Files.list(temporary))
			{
				requireThat(children.count(), "stagingEntries").isEqualTo(0L);
			}
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}

	/**
	 * Writes independent public voice inputs for the actual bundled Java assembler.
	 *
	 * @param release   prepared runtime and helper directory
	 * @param workspace upstream source checkout supplying voice legal materials
	 * @param target    application target
	 * @throws IOException              if fixture writing fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static void prepareVoiceRelease(Path release, Path workspace, String target)
		throws IOException, NoSuchAlgorithmException
	{
		boolean windows = target.endsWith("windows-msvc");
		Path legal = workspace.resolve("third_party/voice");
		for (String name : List.of("NOTICE.md", "sources.json", "windows-crt.json", "licenses/LGPL-2.1.txt",
			"licenses/Opus.txt", "licenses/PCRE2.md", "licenses/libffi.txt", "licenses/proxy-libintl.txt",
			"licenses/sljit.txt", "licenses/zlib.txt"))
		{
			Path file = legal.resolve(name);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "upstream voice legal bytes " + name);
		}
		Files.createDirectories(release);
		String helperName = "codex-voice-host";
		if (windows)
			helperName += ".exe";
		Path helper = release.resolve(helperName);
		Files.writeString(helper, "prepared voice helper");
		if (Files.getFileAttributeView(helper, PosixFileAttributeView.class) != null)
			Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
		Path runtime = Files.createDirectory(release.resolve("runtime"));
		List<String> plugins = new ArrayList<>();
		for (String name : List.of("app", "audioconvert", "audioresample", "coreelements", "opus", "rtp", "rtpmanager"))
		{
			if (windows)
				plugins.add("bin/gst" + name + ".dll");
			else
				plugins.add("lib/gstreamer-1.0/libgst" + name + ".so");
		}
		List<String> names = new ArrayList<>(plugins);
		if (windows)
			names.add("bin/gio-2.0-0.dll");
		else
			names.add("lib/libgio-2.0.so.0");
		List<Map<String, String>> libraries = new ArrayList<>();
		for (String name : names)
		{
			Path file = runtime.resolve(name);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "prepared native bytes " + name);
			libraries.add(Map.of("path", name, "sha256", sha256(file)));
		}
		Map<String, Object> receipt = Map.of("schemaVersion", 1, "developmentOnly", false, "distribution", "publicRelease",
			"sourceCommit", BUILD_COMMIT, "sourceManifestSha256", sha256(legal.resolve("sources.json")),
			"target", target.replace("linux-musl", "linux-gnu"), "libraries", libraries, "plugins", plugins);
		Files.writeString(runtime.resolve("runtime.json"), JsonMapper.builder().build().writeValueAsString(receipt));
	}

	/**
	 * Verifies source identity, version, native inputs, and legal bytes through the native archive consumer.
	 *
	 * @param archive     produced archive
	 * @param compression tar.gz or tar.zst
	 * @param target      application target
	 * @param release     prepared runtime and helper inputs
	 * @param workspace   upstream source checkout
	 * @param working     isolated process working directory
	 * @param environment process environment overrides
	 * @param stdout      extraction output capture
	 * @param stderr      extraction diagnostic capture
	 * @throws IOException          if extraction or file access fails
	 * @throws InterruptedException if extraction is interrupted
	 */
	private static void verifyArchivedVoice(Path archive, String compression, String target, Path release,
		Path workspace, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		List<String> consumer = new ArrayList<>(List.of(NativeUtilities.tarExecutable()));
		if (compression.equals("tar.zst"))
			consumer.addAll(List.of("--zstd", "-xOf"));
		else
			consumer.add("-xOzf");
		consumer.add(archive.toString());
		consumer.add("codex-resources/voice/manifest.json");
		requireThat(run(consumer, working, environment, stdout, stderr), "manifestExtractionStatus").isEqualTo(0);
		JsonNode manifest = JsonMapper.builder().build().readTree(Files.readString(stdout));
		requireThat(manifest.path("buildCommit").stringValue(), "buildCommit").isEqualTo(BUILD_COMMIT);
		requireThat(manifest.path("appVersion").stringValue(), "appVersion").isEqualTo("0.160.0+43");
		requireThat(manifest.path("appTarget").stringValue(), "appTarget").isEqualTo(target);
		requireThat(manifest.path("voiceTarget").stringValue(), "voiceTarget").isEqualTo(
			target.replace("linux-musl", "linux-gnu"));
		boolean windows = target.endsWith("windows-msvc");
		String helperName = "codex-voice-host";
		if (windows)
			helperName += ".exe";
		String helper = "bin/" + helperName;
		String library = "lib/libgio-2.0.so.0";
		if (windows)
			library = "bin/gio-2.0-0.dll";
		Map<String, Path> sources = Map.of(helper, release.resolve(helperName),
			library, release.resolve("runtime").resolve(library), "runtime.json", release.resolve("runtime/runtime.json"),
			"sources.json", workspace.resolve("third_party/voice/sources.json"),
			"licenses/Opus.txt", workspace.resolve("third_party/voice/licenses/Opus.txt"));
		for (Map.Entry<String, Path> entry : sources.entrySet())
		{
			consumer.set(consumer.size() - 1, "codex-resources/voice/" + entry.getKey());
			requireThat(run(consumer, working, environment, stdout, stderr), "voiceExtractionStatus").isEqualTo(0);
			requireThat(Files.readAllBytes(stdout), "archivedVoiceBytes").isEqualTo(Files.readAllBytes(entry.getValue()));
		}
	}

	/**
	 * Hashes bounded fixture files for independently generated native receipts.
	 *
	 * @param file fixture input
	 * @return lowercase SHA-256
	 * @throws IOException              if fixture reading fails
	 * @throws NoSuchAlgorithmException if SHA-256 is unavailable
	 */
	private static String sha256(Path file) throws IOException, NoSuchAlgorithmException
	{
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}

	/**
	 * Runs the actual image or native consumers with owned output capture and explicit cache overrides.
	 *
	 * @param command     the direct command arguments
	 * @param working     the selected working directory
	 * @param environment the explicit storage and command-path overrides
	 * @param stdout      the raw standard-output capture
	 * @param stderr      the diagnostic capture
	 * @return the ordinary exit status
	 * @throws IOException          if process startup fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(List<String> command, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		return run(NativeCommands.createBuilder(command), working, environment, stdout, stderr);
	}

	/**
	 * Runs a maintained POSIX launcher through the explicitly selected native Bash.
	 *
	 * @param command     the script filename and its literal arguments
	 * @param working     the selected working directory
	 * @param environment the explicit storage and command-path overrides
	 * @param stdout      the raw standard-output capture
	 * @param stderr      the diagnostic capture
	 * @return the ordinary exit status
	 * @throws IOException          if process startup fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int runScript(List<String> command, Path working, Map<String, String> environment,
		Path stdout, Path stderr) throws IOException, InterruptedException
	{
		return run(NativeCommands.scriptBuilder(Path.of(command.getFirst()),
			command.subList(1, command.size()).toArray(String[]::new)), working, environment, stdout, stderr);
	}

	/**
	 * Executes a prepared native process with owned captures and explicit cache overrides.
	 *
	 * @param command     the prepared native process
	 * @param working     the selected working directory
	 * @param environment the explicit storage and command-path overrides
	 * @param stdout      the raw standard-output capture
	 * @param stderr      the diagnostic capture
	 * @return the ordinary exit status
	 * @throws IOException          if process startup fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(ProcessBuilder command, Path working, Map<String, String> environment,
		Path stdout, Path stderr) throws IOException, InterruptedException
	{
		ProcessBuilder builder = command.directory(working.toFile()).redirectOutput(stdout.toFile()).
			redirectError(stderr.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			return process.waitFor();
		}
	}
}
