package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies voice assembly through {@link PackageCommand} and its archive consumer.
 */
public final class VoicePackageTest
{
	/**
	 * Creates independent voice assembly tests.
	 */
	public VoicePackageTest()
	{
	}

	/**
	 * Supplies every supported application target, including musl applications using GNU voice runtimes.
	 *
	 * @return supported targets
	 */
	@DataProvider
	public Object[][] targets()
	{
		return Stream.of(PackageTarget.values()).map(target -> new Object[]{target}).toArray(Object[][]::new);
	}

	/**
	 * Includes verified native files and exact source and version identity in the existing archive output.
	 *
	 * @param target the application target
	 * @throws IOException if fixture access, assembly, or archive reading fails
	 */
	@Test(dataProvider = "targets")
	public void archivesVerifiedVoice(PackageTarget target) throws IOException
	{
		try (Fixture fixture = Fixture.create(target))
		{
			requireThat(fixture.run(), "status").isEqualTo(0);
			try (ZipFile archive = ZipFile.builder().setPath(fixture.archive).get())
			{
				JsonNode manifest;
				try (InputStream input = archive.getInputStream(archive.getEntry("codex-resources/voice/manifest.json")))
				{
					manifest = JsonMapper.builder().build().readTree(input);
				}
				requireThat(manifest.path("appVersion").stringValue(), "appVersion").isEqualTo("0.160.0+41");
				requireThat(manifest.path("buildCommit").stringValue(), "buildCommit").isEqualTo(Fixture.COMMIT);
				requireThat(manifest.path("appTarget").stringValue(), "appTarget").isEqualTo(target.triple());
				requireThat(manifest.path("voiceTarget").stringValue(), "voiceTarget").isEqualTo(fixture.voiceTarget);
				for (Map.Entry<String, JsonNode> entry : manifest.path("sha256").properties())
				{
					try (InputStream input = archive.getInputStream(archive.getEntry(entry.getKey())))
					{
						requireThat(input.readAllBytes(), "archivedBytes").isEqualTo(
							Files.readAllBytes(fixture.output.resolve(entry.getKey())));
					}
					requireThat(Sha256.digest(fixture.output.resolve(entry.getKey())), "digest").isEqualTo(
						entry.getValue().stringValue());
				}
				requireThat(manifest.path("sha256").has("codex-resources/voice/runtime.json"), "runtimeReceipt").isTrue();
				requireThat(manifest.path("sha256").has("codex-resources/voice/licenses/Opus.txt"), "opusLicense").isTrue();
				requireThat(manifest.path("sha256").has("codex-resources/voice/windows-crt.json"), "windowsCrt").
					isEqualTo(target.isWindows());
				requireThat(archive.getEntry("codex-resources/voice/undeclared.txt"), "undeclaredInput").isNull();
			}
			requireThat(Files.readAllBytes(fixture.output.resolve("codex-resources/voice/runtime.json")), "receiptBytes").
				isEqualTo(Files.readAllBytes(fixture.runtime.resolve("runtime.json")));
		}
	}

	/**
	 * Supplies independently malformed receipt values and unsafe input changes.
	 *
	 * @return invalid input cases
	 */
	@DataProvider
	public Object[][] invalidInputs()
	{
		return Stream.of("schema", "schemaType", "schemaLayoutBypass", "development", "missingDevelopment",
			"stringDevelopment", "distribution", "target", "sourceCommit", "sourceManifest", "librariesType",
			"librariesEmpty", "missingLibrary", "missingPlugin", "duplicatePath", "collidingPath", "traversal",
			"absolutePath", "backslashPath", "digest", "digestType", "recordType", "pluginType", "pluginsDuplicate",
			"tamperedFile", "missingReceipt", "malformed", "duplicateKey", "trailingJson", "oversized",
			"missingLegal").map(name -> new Object[]{name}).toArray(Object[][]::new);
	}

	/**
	 * Rejects malformed or tampered inputs before the archive writer runs, retaining caller-owned output.
	 *
	 * @param invalidInput the invalid input case
	 * @throws IOException for the expected assembly rejection
	 */
	@Test(dataProvider = "invalidInputs", expectedExceptions = IOException.class)
	public void rejectsInvalidVoice(String invalidInput) throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.LINUX_X86_MUSL))
		{
			fixture.invalidate(invalidInput);
			try
			{
				fixture.run();
			}
			finally
			{
				requireThat(Files.exists(fixture.archive), "archiveExists").isFalse();
				requireThat(fixture.output, "callerOwnedOutput").isDirectory();
				requireThat(Files.exists(fixture.output.resolve("codex-resources/voice")), "partialVoiceExists").isFalse();
			}
		}
	}

	/**
	 * Requires paired voice options, a full lowercase build commit, and the primary executable variant.
	 *
	 * @throws IOException if argument validation unexpectedly accesses a missing repository
	 */
	@Test
	public void rejectsInvalidOptions() throws IOException
	{
		List<List<String>> cases = List.of(List.of("--voice-release-dir", "release"),
			List.of("--build-commit", Fixture.COMMIT),
			List.of("--voice-release-dir", "release", "--build-commit", "short"),
			List.of("--voice-release-dir", "release", "--build-commit", Fixture.COMMIT.toUpperCase(Locale.ROOT)),
			List.of("--voice-release-dir", "release", "--build-commit", Fixture.COMMIT, "--variant", "codex-app-server"));
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			for (List<String> options : cases)
			{
				List<String> arguments = new ArrayList<>(List.of("--repo", "missing-repository"));
				arguments.addAll(options);
				requireThat(PackageCommand.run(arguments.toArray(String[]::new), stream, stream, Map.of(), Clock.systemUTC()),
					"status").isEqualTo(2);
			}
			requireThat(bytes.toString(StandardCharsets.UTF_8), "diagnostics").contains("ERROR:");
		}
	}

	/**
	 * Supplies linked input locations that must be rejected even when their target bytes are valid.
	 *
	 * @return linked input cases
	 */
	@DataProvider
	public Object[][] linkedInputs()
	{
		return Stream.of("library", "plugins", "receipt", "helper", "legal").map(name -> new Object[]{name}).
			toArray(Object[][]::new);
	}

	/**
	 * Rejects symlinked files and containing directories before archiving.
	 *
	 * @param linkedInput the linked input location
	 * @throws IOException for the expected validation rejection
	 */
	@Test(dataProvider = "linkedInputs", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*symlinks.*")
	public void rejectsLinkedInputs(String linkedInput) throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.LINUX_X86_GNU))
		{
			Path input = switch (linkedInput)
			{
				case "library" -> fixture.runtime.resolve("lib/libgio-2.0.so.0");
				case "plugins" -> fixture.runtime.resolve("lib/gstreamer-1.0");
				case "receipt" -> fixture.runtime.resolve("runtime.json");
				case "helper" -> fixture.runtime.getParent().resolve("codex-voice-host");
				default -> fixture.workspace.resolve("third_party/voice/licenses/Opus.txt");
			};
			Path outside = fixture.root.resolve("linked-target");
			Files.move(input, outside);
			Files.createSymbolicLink(input, outside);
			try
			{
				fixture.run();
			}
			finally
			{
				requireThat(Files.exists(fixture.archive), "archiveExists").isFalse();
				requireThat(Files.exists(fixture.output.resolve("codex-resources/voice")), "voiceExists").isFalse();
			}
		}
	}

	/**
	 * Rejects a Unix helper without any execute bits before copying voice inputs.
	 *
	 * @throws IOException for the expected executable rejection
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*not executable.*")
	public void rejectsNonExecutableHelper() throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.LINUX_X86_GNU))
		{
			Files.setPosixFilePermissions(fixture.runtime.getParent().resolve("codex-voice-host"),
				PosixFilePermissions.fromString("rw-r--r--"));
			fixture.run();
		}
	}

	/**
	 * Retains ordinary package assembly when neither voice option is supplied.
	 *
	 * @throws IOException if ordinary package assembly fails
	 */
	@Test
	public void buildsWithoutVoiceOptions() throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.WINDOWS_X86))
		{
			fixture.removeOption("--voice-release-dir");
			fixture.removeOption("--build-commit");
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.archive, "archive").isRegularFile();
			requireThat(Files.exists(fixture.output.resolve("codex-resources/voice")), "voiceExists").isFalse();
		}
	}

	/**
	 * Deletes an implicitly owned base package after voice validation fails.
	 *
	 * @throws IOException for the expected receipt rejection
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*sourceCommit.*")
	public void cleansFailedImplicitPackage() throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.LINUX_X86_GNU))
		{
			fixture.removeOption("--package-dir");
			fixture.invalidate("sourceCommit");
			try
			{
				fixture.run();
			}
			finally
			{
				try (Stream<Path> paths = Files.list(fixture.root.resolve("cache")))
				{
					requireThat(paths.anyMatch(path -> path.getFileName().toString().startsWith("codex-package-")),
						"implicitPackageExists").isFalse();
				}
				requireThat(Files.exists(fixture.archive), "archiveExists").isFalse();
			}
		}
	}

	/**
	 * Supplies canonical metadata fields that the assembler must verify independently of its command caller.
	 *
	 * @return altered metadata fields
	 */
	@DataProvider
	public Object[][] metadataFields()
	{
		return Stream.of("layoutVersion", "version", "target", "variant", "entrypoint", "resourcesDir", "pathDir").
			map(name -> new Object[]{name}).toArray(Object[][]::new);
	}

	/**
	 * Rejects package metadata that differs from the resolved canonical identity.
	 *
	 * @param field the altered metadata field
	 * @throws IOException for the expected metadata rejection
	 */
	@Test(dataProvider = "metadataFields", expectedExceptions = IOException.class)
	public void rejectsNoncanonicalMetadata(String field) throws IOException
	{
		try (Fixture fixture = Fixture.create(PackageTarget.WINDOWS_X86))
		{
			fixture.removeOption("--voice-release-dir");
			fixture.removeOption("--build-commit");
			requireThat(fixture.run(), "status").isEqualTo(0);
			Map<String, Object> metadata = new LinkedHashMap<>(Map.of("layoutVersion", 1, "version", "0.160.0+41",
				"target", PackageTarget.WINDOWS_X86.triple(), "variant", "codex", "entrypoint", "bin/codex.exe",
				"resourcesDir", "codex-resources", "pathDir", "codex-path"));
			metadata.put(field, "invalid");
			Files.writeString(fixture.output.resolve("codex-package.json"), ArtifactJson.format(metadata));
			VoicePackage.assemble(fixture.output, fixture.workspace, fixture.runtime.getParent(), PackageTarget.WINDOWS_X86,
				"0.160.0+41", Fixture.COMMIT);
		}
	}

	/**
	 * Owns isolated base package inputs and independent public native receipts.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root;
		private final Path workspace;
		private final Path runtime;
		private final Path output;
		private final Path archive;
		private final String voiceTarget;
		private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
		private final List<String> arguments = new ArrayList<>();
		private final Map<String, Object> receipt = new LinkedHashMap<>();

		/**
		 * Creates package and public voice inputs.
		 *
		 * @param root   the owned root
		 * @param target the application target
		 * @throws IOException if fixture creation fails
		 */
		private Fixture(Path root, PackageTarget target) throws IOException
		{
			this.root = root;
			if (!target.isWindows() && Files.getFileAttributeView(root, PosixFileAttributeView.class) == null)
				throw new SkipException("Unix package assembly requires a POSIX filesystem");
			voiceTarget = target.triple().replace("linux-musl", "linux-gnu");
			workspace = Files.createDirectories(root.resolve("workspace/codex-rs")).getParent();
			Files.writeString(workspace.resolve("codex-rs/Cargo.toml"), "[workspace.package]\nversion = \"0.160.0\"\n");
			Path repository = Files.createDirectory(root.resolve("repository"));
			for (String name : List.of("LICENSE.md", "licenses/openai-codex/LICENSE-APACHE-2.0", "docs/LICENSE.html",
				"docs/terms.html", "docs/privacy.html"))
				write(repository.resolve(name), "base legal " + name);
			Path sources = workspace.resolve("third_party/voice");
			for (String name : List.of("NOTICE.md", "sources.json", "windows-crt.json", "licenses/LGPL-2.1.txt",
				"licenses/Opus.txt", "licenses/PCRE2.md", "licenses/libffi.txt", "licenses/proxy-libintl.txt",
				"licenses/sljit.txt", "licenses/zlib.txt"))
				write(sources.resolve(name), "independent source document " + name);
			Path binary = write(root.resolve("prebuilt"), "base executable");
			executable(binary);
			Path release = Files.createDirectory(root.resolve("release"));
			executable(write(release.resolve("codex-voice-host" + target.executableSuffix()), "voice helper"));
			runtime = Files.createDirectory(release.resolve("runtime"));
			List<String> plugins = new ArrayList<>();
			for (String name : List.of("app", "audioconvert", "audioresample", "coreelements", "opus", "rtp", "rtpmanager"))
			{
				if (target.isWindows())
					plugins.add("bin/gst" + name + ".dll");
				else if (target.isLinux())
					plugins.add("lib/gstreamer-1.0/libgst" + name + ".so");
				else
					plugins.add("plugins/libgst" + name + ".dylib");
			}
			List<String> names = new ArrayList<>(plugins);
			if (target.isWindows())
				names.add("bin/gio-2.0-0.dll");
			else if (target.isLinux())
				names.add("lib/libgio-2.0.so.0");
			else
				names.add("lib/libgio-2.0.0.dylib");
			List<Map<String, String>> libraries = new ArrayList<>();
			for (String name : names)
			{
				Path file = write(runtime.resolve(name), "native bytes " + name);
				libraries.add(Map.of("path", name, "sha256", Sha256.digest(file)));
			}
			receipt.put("schemaVersion", 1);
			receipt.put("developmentOnly", false);
			receipt.put("distribution", "publicRelease");
			receipt.put("target", voiceTarget);
			receipt.put("sourceCommit", COMMIT);
			receipt.put("sourceManifestSha256", Sha256.digest(sources.resolve("sources.json")));
			receipt.put("libraries", libraries);
			receipt.put("plugins", plugins);
			writeReceipt();
			write(runtime.resolve("undeclared.txt"), "must not copy");
			Path licenses = Files.createDirectory(root.resolve("licenses"));
			write(licenses.resolve("THIRD_PARTY_NOTICES.md"), "rust notices");
			Path zsh = write(root.resolve("zsh.json"), "{\"platforms\":{}}");
			output = root.resolve("package");
			archive = root.resolve("package.zip");
			arguments.addAll(List.of("--repo", repository.toString(), "--workspace", workspace.toString(), "--cache-root",
				root.resolve("cache").toString(), "--target", target.triple(), "--entrypoint-bin", binary.toString(),
				"--code-mode-host-bin", binary.toString(), "--rg-bin", binary.toString(), "--zsh-manifest", zsh.toString(),
				"--package-dir", output.toString(), "--archive-output", archive.toString(), "--voice-release-dir",
				release.toString(), "--build-commit", COMMIT));
			if (target.isLinux())
				arguments.addAll(List.of("--bwrap-bin", binary.toString()));
			if (target.isWindows())
			{
				arguments.addAll(List.of("--codex-command-runner-bin", binary.toString(),
					"--codex-windows-sandbox-setup-bin", binary.toString(), "--codex-windows-sandbox-service-bin",
					binary.toString()));
			}
		}

		/**
		 * Allocates a fixture with cleanup for partial setup.
		 *
		 * @param target the application target
		 * @return initialized fixture
		 * @throws IOException if fixture creation fails
		 */
		private static Fixture create(PackageTarget target) throws IOException
		{
			Path root = Files.createTempDirectory("voice-package-");
			try
			{
				return new Fixture(root, target);
			}
			catch (IOException | RuntimeException failure)
			{
				delete(root);
				throw failure;
			}
		}

		/**
		 * Runs the supported package command with fixture-owned input and output.
		 *
		 * @return command exit status
		 * @throws IOException if assembly fails
		 */
		private int run() throws IOException
		{
			try (PrintStream stream = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8))
			{
				return PackageCommand.run(arguments.toArray(String[]::new), stream, stream, Map.of(
					"CODEX_UNLEASHED_BUILD_NUMBER", "41", "CODEX_PACKAGE_RUST_LICENSES_DIR",
					root.resolve("licenses").toString()), Clock.systemUTC());
			}
		}

		/**
		 * Removes one option and its associated value from the fixture command.
		 *
		 * @param option option to remove
		 */
		private void removeOption(String option)
		{
			int index = arguments.indexOf(option);
			arguments.subList(index, index + 2).clear();
		}

		/**
		 * Changes one independently prepared input to violate its public contract.
		 *
		 * @param invalidInput the invalid input case
		 * @throws IOException if fixture mutation fails
		 */
		private void invalidate(String invalidInput) throws IOException
		{
			String path = "lib/libgio-2.0.so.0";
			String digest = Sha256.digest(runtime.resolve(path));
			switch (invalidInput)
			{
				case "schema" -> receipt.put("schemaVersion", 2);
				case "schemaType" -> receipt.put("schemaVersion", true);
				case "schemaLayoutBypass" ->
				{
					receipt.put("schemaVersion", 2);
					receipt.put("layoutVersion", 1);
				}
				case "development" -> receipt.put("developmentOnly", true);
				case "missingDevelopment" -> receipt.remove("developmentOnly");
				case "stringDevelopment" -> receipt.put("developmentOnly", "false");
				case "distribution" -> receipt.put("distribution", "development");
				case "target" -> receipt.put("target", "aarch64-apple-darwin");
				case "sourceCommit" -> receipt.put("sourceCommit", "f".repeat(40));
				case "sourceManifest" -> receipt.put("sourceManifestSha256", "0".repeat(64));
				case "librariesType" -> receipt.put("libraries", "libraries");
				case "librariesEmpty" -> receipt.put("libraries", List.of());
				case "missingLibrary" -> Files.delete(runtime.resolve(path));
				case "missingPlugin" -> receipt.put("plugins", List.of());
				case "duplicatePath", "collidingPath" ->
				{
					String other = path;
					if (invalidInput.equals("collidingPath"))
						other = "lib/LIBGIO-2.0.so.0";
					receipt.put("libraries", List.of(Map.of("path", path, "sha256", digest),
						Map.of("path", other, "sha256", digest)));
				}
				case "traversal", "absolutePath", "backslashPath" ->
				{
					String invalidPath = switch (invalidInput)
					{
						case "traversal" -> "lib/../../outside.so";
						case "absolutePath" -> "/outside.so";
						default -> "lib\\outside.so";
					};
					receipt.put("libraries", List.of(Map.of("path", invalidPath, "sha256", digest)));
				}
				case "digest" -> receipt.put("libraries", List.of(Map.of("path", path, "sha256", "0".repeat(64))));
				case "digestType" -> receipt.put("libraries", List.of(Map.of("path", path, "sha256", false)));
				case "recordType" -> receipt.put("libraries", List.of(false));
				case "pluginType" -> receipt.put("plugins", List.of(false));
				case "pluginsDuplicate" -> receipt.put("plugins", List.of("lib/gstreamer-1.0/libgstapp.so",
					"lib/gstreamer-1.0/libgstapp.so", "a", "b", "c", "d", "e"));
				case "tamperedFile" -> Files.writeString(runtime.resolve(path), "tampered bytes");
				case "missingLegal" -> Files.delete(workspace.resolve("third_party/voice/licenses/Opus.txt"));
				default ->
				{
				}
			}
			writeReceipt();
			Path manifest = runtime.resolve("runtime.json");
			switch (invalidInput)
			{
				case "missingReceipt" -> Files.delete(manifest);
				case "malformed" -> Files.writeString(manifest, "{");
				case "duplicateKey" -> Files.writeString(manifest, "{\"schemaVersion\":1,\"schemaVersion\":1}");
				case "trailingJson" -> Files.writeString(manifest, Files.readString(manifest) + "{}");
				case "oversized" -> Files.writeString(manifest, " ".repeat(1024 * 1024 + 1));
				default ->
				{
				}
			}
		}

		/**
		 * Writes the independently constructed runtime receipt.
		 *
		 * @throws IOException if writing fails
		 */
		private void writeReceipt() throws IOException
		{
			Files.writeString(runtime.resolve("runtime.json"), ArtifactJson.format(receipt));
		}

		/**
		 * Writes one fixture input and creates its parents.
		 *
		 * @param file  destination
		 * @param bytes text bytes
		 * @return written path
		 * @throws IOException if writing fails
		 */
		private static Path write(Path file, String bytes) throws IOException
		{
			Files.createDirectories(file.getParent());
			return Files.writeString(file, bytes);
		}

		/**
		 * Adds Unix executable permissions when supported by the fixture filesystem.
		 *
		 * @param file the executable fixture
		 * @throws IOException if permission updates fail
		 */
		private static void executable(Path file) throws IOException
		{
			if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null)
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
		}

		@Override
		public void close() throws IOException
		{
			delete(root);
		}

		/**
		 * Deletes only the owned fixture tree.
		 *
		 * @param root owned root
		 * @throws IOException if cleanup fails
		 */
		private static void delete(Path root) throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
