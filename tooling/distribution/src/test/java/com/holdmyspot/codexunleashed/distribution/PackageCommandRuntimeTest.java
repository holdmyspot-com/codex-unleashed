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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies standalone package production and native archive consumption without external Java or Python commands.
 */
public final class PackageCommandRuntimeTest
{
	/**
	 * Creates package runtime tests.
	 */
	public PackageCommandRuntimeTest()
	{
	}

	/**
	 * Runs the maintained release wrapper with rejected Python commands and verifies archive output and staging cleanup.
	 *
	 * @throws IOException if fixture access, linking, command execution, or cleanup fails
	 * @throws URISyntaxException if rejected command fixture locations cannot be represented as URIs
	 * @throws InterruptedException if a child process is interrupted
	 * @throws NoSuchAlgorithmException if the required SHA-256 implementation is unavailable
	 */
	@Test
	public void buildsReleaseWrapperArchives()
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
			for (String name : List.of("codex-app-server", "codex-x86_64-unknown-linux-gnu", "codex-code-mode-host", "bwrap",
				"codex-x86_64-pc-windows-msvc.exe", "codex-code-mode-host.exe", "codex-command-runner.exe",
				"codex-windows-sandbox-setup.exe"))
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
			for (List<String> selection : List.of(List.of("app-server", "x86_64-unknown-linux-gnu"),
				List.of("primary", "x86_64-unknown-linux-gnu"), List.of("primary", "x86_64-pc-windows-msvc")))
			{
				List<String> command = new ArrayList<>(List.of("bash",
					repository.resolve(".github/scripts/build-codex-package-archive.sh").toString(), "--target", selection.get(1),
					"--bundle", selection.get(0), "--entrypoint-dir", binaries.toString(), "--archive-dir", archives.toString(),
					"--rg-manifest", rg.toString(), "--zsh-manifest", zsh.toString()));
				if (selection.getFirst().equals("primary"))
					command.add("--target-suffixed-entrypoint");
				assertEquals(run(command, root, environment, stdout, stderr), 0, Files.readString(stderr));
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
						consumer = List.of("tar", "--zstd", "-xOf", archive.toString(), "bin/" + binary);
					else
						consumer = List.of("tar", "-xOzf", archive.toString(), "bin/" + binary);
					assertEquals(run(consumer, root, environment, stdout, stderr), 0, Files.readString(stderr));
					assertEquals(Files.readAllBytes(stdout), payload);
				}
				try (Stream<Path> paths = Files.list(temporary))
				{
					assertEquals(paths.count(), 0L, "Release staging remains after success");
				}
				Files.delete(notices);
				assertEquals(run(command, root, environment, stdout, stderr), 1);
				try (Stream<Path> paths = Files.list(temporary))
				{
					assertEquals(paths.count(), 0L, "Release staging remains after failure");
				}
				Files.writeString(notices, "prepared notices\n");
			}
			assertFalse(Files.exists(root.resolve("cargo-home")));
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Produces all supported archive formats with a fresh bundled image and verifies their extracted binary payloads.
	 *
	 * @throws IOException if fixture access, linking, command execution, or cleanup fails
	 * @throws URISyntaxException if rejected command fixture locations cannot be represented as URIs
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
			List<String> command = new ArrayList<>(List.of(runtime.resolve("bin/codex-tooling").toString(),
				"build-codex-package", "--repo", repository.toString(), "--workspace", workspace.toString(),
				"--cache-root", cache.toString(), "--target", "x86_64-unknown-linux-gnu", "--variant", "codex-app-server",
				"--entrypoint-bin", binary.toString(), "--code-mode-host-bin", binary.toString(), "--bwrap-bin",
				binary.toString(), "--rg-bin", binary.toString(), "--zsh-manifest", zsh.toString(), "--package-dir",
				directory.toString()));
			List<Path> archives = new ArrayList<>();
			for (String suffix : List.of("tar.gz", "tgz", "tar.zst", "zip"))
			{
				Path archive = root.resolve("package." + suffix);
				archives.add(archive);
				command.addAll(List.of("--archive-output", archive.toString()));
			}
			Path stdout = root.resolve("stdout");
			Path stderr = root.resolve("stderr");
			assertEquals(run(command, root, environment, stdout, stderr), 0, Files.readString(stderr));
			assertTrue(Files.readString(directory.resolve("codex-package.json")).contains("\"version\": \"0.160.0+42\""));
			assertEquals(Files.readAllBytes(directory.resolve("bin/codex-app-server")), payload);
			assertTrue(Files.isRegularFile(directory.resolve("licenses/rust/THIRD_PARTY_NOTICES.md")));
			assertFalse(Files.exists(workspace.resolve("scripts/get_codex_package_version.py")));
			assertFalse(Files.exists(root.resolve("cargo-home")));
			for (Path archive : archives)
			{
				List<String> consumer;
				String name = archive.getFileName().toString();
				if (name.endsWith(".zip"))
					consumer = List.of("unzip", "-p", archive.toString(), "bin/codex-app-server");
				else if (name.endsWith(".tar.zst"))
					consumer = List.of("tar", "--zstd", "-xOf", archive.toString(), "bin/codex-app-server");
				else
					consumer = List.of("tar", "-xOzf", archive.toString(), "bin/codex-app-server");
				assertEquals(run(consumer, root, environment, stdout, stderr), 0, Files.readString(stderr));
				assertEquals(Files.readAllBytes(stdout), payload);
			}
			try (Stream<Path> paths = Files.list(cache.resolve("archive-temp")))
			{
				assertEquals(paths.count(), 0L);
			}
			try (Stream<Path> paths = Files.list(temporary))
			{
				assertEquals(paths.count(), 0L);
			}
		}
		finally
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}

	/**
	 * Runs the actual image or native consumers with owned output capture and explicit cache overrides.
	 *
	 * @param command the direct command arguments
	 * @param working the selected working directory
	 * @param environment the explicit storage and command-path overrides
	 * @param stdout the raw standard-output capture
	 * @param stderr the diagnostic capture
	 * @return the ordinary exit status
	 * @throws IOException if process startup fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static int run(List<String> command, Path working, Map<String, String> environment, Path stdout, Path stderr)
		throws IOException, InterruptedException
	{
		ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectOutput(stdout.toFile()).
			redirectError(stderr.toFile());
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			return process.waitFor();
		}
	}
}
