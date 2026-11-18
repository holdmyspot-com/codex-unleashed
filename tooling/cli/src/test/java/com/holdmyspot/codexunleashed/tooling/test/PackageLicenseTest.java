package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.PackageLicense;
import com.holdmyspot.codexunleashed.tooling.PackageLicense.ArchiveProcesses;
import com.holdmyspot.codexunleashed.tooling.PackageLicense.CommandResult;
import com.holdmyspot.codexunleashed.tooling.PackageZstd.Executables;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies legal-source staging, streaming archive checks and canonical writer handoff.
 */
public final class PackageLicenseTest
{
	/**
	 * Required downstream legal document paths.
	 *
	 */
	private static final List<String> DOWNSTREAM_DOCUMENTS = List.of("LICENSE.md", "docs/LICENSE.html", "docs/terms.html",
		"docs/privacy.html", "licenses/openai-codex/LICENSE-APACHE-2.0");
	/**
	 * Required upstream legal notice paths.
	 *
	 */
	private static final List<String> UPSTREAM_NOTICES = List.of("LICENSE", "NOTICE",
		"codex-rs/skills/src/assets/samples/imagegen/LICENSE.txt",
		"codex-rs/skills/src/assets/samples/openai-docs/LICENSE.txt",
		"codex-rs/skills/src/assets/samples/skill-creator/license.txt",
		"codex-rs/skills/src/assets/samples/skill-installer/LICENSE.txt", "codex-rs/vendor/bubblewrap/COPYING",
		"third_party/voice/NOTICE.md", "third_party/wezterm/LICENSE");
	/**
	 * Expected bytes stored in the golden archive.
	 *
	 */
	private static final byte[] PAYLOAD = "payload\n".getBytes(StandardCharsets.UTF_8);

	/**
	 * Creates legal command tests.
	 *
	 */
	public PackageLicenseTest()
	{
	}

	/**
	 * Streams the golden gzip tar fixture and validates the member bytes through the production reader.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void streamsGoldenGzipTarMember() throws Exception
	{
		try (InputStream archive = resource("golden.tar.gz"))
		{
			PackageLicense.checkArchive(archive, Map.of("payload.txt", PAYLOAD));
		}
	}

	/**
	 * Accepts a raw tar and rejects missing, changed, duplicate, unsafe and linked archive members.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void checksRawTarAndRejectsInvalidMembers() throws Exception
	{
		byte[] valid = tar(List.of(file("required.txt", bytes("expected"))));
		PackageLicense.checkArchive(new ByteArrayInputStream(valid), Map.of("required.txt", bytes("expected")));
		assertRejected(tar(List.of(file("required.txt", bytes("changed")))), Map.of("required.txt", bytes("expected")),
			"Legal document differs");
		assertRejected(tar(List.of(file("other.txt", bytes("other")))), Map.of("required.txt", bytes("expected")),
			"Missing legal documents");
		assertRejected(tar(List.of(file("../required.txt", bytes("expected")))),
			Map.of("required.txt", bytes("expected")), "Unsafe archive member path");
		assertRejected(tar(List.of(file("required.txt", bytes("expected")), file("required.txt", bytes("expected")))),
			Map.of("required.txt", bytes("expected")), "Duplicate archive member");
		TarArchiveEntry link = new TarArchiveEntry("required.txt", TarConstants.LF_SYMLINK);
		link.setLinkName("elsewhere");
		assertRejected(tar(List.of(new Entry(link, new byte[0]))), Map.of("required.txt", bytes("expected")),
			"not a regular file or directory");
	}

	/**
	 * Runs check against a gzip archive path and caller-owned stdin without closing stdin.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void checksCommandArchivePathAndStandardInput() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-check-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageDirectory = packageDirectory(root.resolve("package"));
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput staged = invoke(scope, stageArguments(fixture, packageDirectory), NO_PROCESSES);
				requireThat(staged.status(), "stage-status").isEqualTo(0);
				Path archive = root.resolve("package.tar.gz");
				writePackageArchive(packageDirectory, archive, true);
				String[] fileArguments = {"check", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
					fixture.upstreamRoot().toString(), "--archive", archive.toString()};
				requireThat(invoke(scope, fileArguments, NO_PROCESSES).status(), "archive-check-status").isEqualTo(0);
				String[] stdinArguments = {"check", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
					fixture.upstreamRoot().toString(), "--archive", "-"};
				try (InputStream input = Files.newInputStream(archive))
				{
					CommandOutput result = invoke(scope, stdinArguments, input, NO_PROCESSES);
					requireThat(result.status(), "stdin-check-status").isEqualTo(0);
					requireThat(input.read(), "stdin-remains-open").isEqualTo(-1);
				}
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Returns usage status two for command modes with incompatible options.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void rejectsIncompatibleModeOptions() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-usage-");
		try
		{
			Fixture fixture = fixture(root);
			String[] arguments = {"stage", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
				fixture.upstreamRoot().toString(), "--package-dir", root.resolve("package").toString(), "--archive", "unused"};
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, arguments, NO_PROCESSES);
				requireThat(result.status(), "usage-status").isEqualTo(2);
				requireThat(result.err(), "usage-diagnostic").contains("Stage requires --package-dir");
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Enforces the archive member cap before examining a larger archive.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void rejectsArchiveMemberLimit() throws Exception
	{
		List<Entry> members = new ArrayList<>();
		for (int index = 0; index <= 10_000; index = index + 1)
			members.add(file("member-" + index, new byte[0]));
		assertRejected(tar(members), Map.of("never-present", bytes("required")), "Archive exceeds 10000 members");
	}

	/**
	 * Stages all supplied source and upstream notices and accepts an equal-byte retry.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void stagesDocumentsIdempotently() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-stage-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageDirectory = root.resolve("codex-package-test-target");
			Files.createDirectories(packageDirectory);
			Files.writeString(packageDirectory.resolve("codex-package.json"), "{}\n");
			String[] arguments = stageArguments(fixture, packageDirectory);
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput first = invoke(scope, arguments, NO_PROCESSES);
				requireThat(first.status(), "first-status").isEqualTo(0);
				requireThat(first.out(), "first-output").contains("passed (14 documents)");
				CommandOutput retry = invoke(scope, arguments, NO_PROCESSES);
				requireThat(retry.status(), "retry-status").isEqualTo(0);
			}
			for (String relative : DOWNSTREAM_DOCUMENTS)
				requireThat(Files.readAllBytes(packageDirectory.resolve("codex-resources/legal").resolve(relative)),
					"staged-downstream").isEqualTo(sourceBytes(fixture.metadataRoot(), relative));
			for (String relative : UPSTREAM_NOTICES)
			{
				byte[] staged = Files.readAllBytes(packageDirectory.resolve("codex-resources/legal/licenses/upstream").
					resolve(relative));
				requireThat(staged, "staged-upstream").isEqualTo(sourceBytes(fixture.upstreamRoot(), relative));
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Detects every conflict and linked destination before writing any new legal file.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void rejectsConflictAndLinkedAncestor() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-conflict-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageDirectory = root.resolve("package");
			Files.createDirectories(packageDirectory.resolve("codex-resources/legal/docs"));
			Files.writeString(packageDirectory.resolve("codex-package.json"), "{}\n");
			Files.writeString(packageDirectory.resolve("codex-resources/legal/docs/terms.html"), "different\n");
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, stageArguments(fixture, packageDirectory), NO_PROCESSES);
				requireThat(result.status(), "conflict-status").isEqualTo(1);
				requireThat(Files.exists(packageDirectory.resolve("codex-resources/legal/LICENSE.md")), "partial-write").
						isFalse();
			}

			Path linkedPackage = root.resolve("linked-package");
			Files.createDirectories(linkedPackage);
			Files.writeString(linkedPackage.resolve("codex-package.json"), "{}\n");
			Path outside = root.resolve("outside");
			Files.createDirectories(outside);
			Files.createSymbolicLink(linkedPackage.resolve("codex-resources"), outside);
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, stageArguments(fixture, linkedPackage), NO_PROCESSES);
				requireThat(result.status(), "linked-status").isEqualTo(1);
				try (Stream<Path> paths = Files.list(outside))
				{
					requireThat(paths.count(), "outside-content-count").isEqualTo(0L);
				}
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Rejects absent, empty, oversized and conflicting source documents and source sets over 64 entries.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void rejectsInvalidSourcesAndSourceCount() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-sources-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageDirectory = packageDirectory(root.resolve("empty-package"));
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				Path missing = fixture.upstreamRoot().resolve("LICENSE");
				Files.delete(missing);
				CommandOutput result = invoke(scope, stageArguments(fixture, packageDirectory), NO_PROCESSES);
				requireThat(result.status(), "missing-status").isEqualTo(1);
				Files.write(missing, new byte[0]);
				CommandOutput empty = invoke(scope, stageArguments(fixture, packageDirectory), NO_PROCESSES);
				requireThat(empty.status(), "empty-status").isEqualTo(1);
				Files.write(fixture.metadataRoot().resolve("LICENSE.md"), new byte[2 * 1024 * 1024 + 1]);
				CommandOutput oversized = invoke(scope, stageArguments(fixture, packageDirectory), NO_PROCESSES);
				requireThat(oversized.status(), "oversize-status").isEqualTo(1);
			}

			Fixture conflicting = fixture(root.resolve("conflicting"));
			write(conflicting.metadataRoot().resolve("licenses/upstream/LICENSE"), "metadata notice\n");
			write(conflicting.upstreamRoot().resolve("LICENSE"), "upstream notice\n");
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				Path conflictPackage = packageDirectory(root.resolve("conflict-package"));
				CommandOutput result = invoke(scope, stageArguments(conflicting, conflictPackage), NO_PROCESSES);
				requireThat(result.status(), "source-conflict-status").isEqualTo(1);
			}

			Fixture excessive = fixture(root.resolve("excessive"));
			for (int index = 0; index < 50; index = index + 1)
				write(excessive.metadataRoot().resolve("licenses/extra/" + index + ".txt"), "extra\n");
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				Path excessivePackage = packageDirectory(root.resolve("excessive-package"));
				CommandOutput result = invoke(scope, stageArguments(excessive, excessivePackage), NO_PROCESSES);
				requireThat(result.status(), "source-count-status").isEqualTo(1);
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Uses the shared zstd selection and the upstream writer once per package/archive pair in canonical order.
	 *
	 * @throws Exception if a fixture operation or assertion fails
	 */
	@Test
	public void finalizesCanonicalArchives() throws Exception
	{
		Path root = Files.createTempDirectory("package-license-finalize-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageRoot = root.resolve("packages");
			Path archiveRoot = root.resolve("archives");
			packageDirectory(packageRoot.resolve("codex-package-target"));
			packageDirectory(packageRoot.resolve("codex-app-server-package-target"));
			RecordingProcesses processes = new RecordingProcesses();
			List<Path> searchDirectories = new ArrayList<>();
			String[] arguments = {"finalize", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
				fixture.upstreamRoot().toString(), "--package-root", packageRoot.toString(), "--archive-root",
				archiveRoot.toString(), "--target", "target"};
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, arguments, InputStream.nullInputStream(), processes, directory ->
				{
					searchDirectories.add(directory);
					return zstdOnly();
				});
				requireThat(result.status(), "finalize-status").withContext(result.err(), "diagnostics").isEqualTo(0);
				requireThat(result.out(), "finalize-output").contains("passed (14 documents)");
			}
			requireThat(searchDirectories, "search-directories").
				isEqualTo(List.of(fixture.upstreamRoot().resolve("scripts")));
			requireThat(processes.commands.size(), "writer-count").isEqualTo(4);
			List<List<String>> calls = processes.commands;
			List<String> expected = List.of(
				"codex-package-target.tar.gz", "codex-package-target.tar.zst",
				"codex-app-server-package-target.tar.gz", "codex-app-server-package-target.tar.zst");
			for (int index = 0; index < expected.size(); index = index + 1)
			{
				List<String> call = calls.get(index);
				requireThat(call.subList(0, 4), "writer-command").
					isEqualTo(List.of("python", "-m", "codex_package.archive", "write"));
				requireThat(call.getLast(), "archive-name").isEqualTo("--force");
				requireThat(Path.of(call.get(call.indexOf("--archive-output") + 1)).getFileName().toString(), "archive-order").
						isEqualTo(expected.get(index));
			}
			requireThat(processes.decompressCommands.size(), "zstd-check-count").isEqualTo(2);
			for (List<String> command : processes.decompressCommands)
				requireThat(command.subList(0, 2), "resolved-zstd-command").isEqualTo(List.of("selected-zstd", "-dc"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Decompresses with DotSlash and the repository wrapper when zstd is not on the search path.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void finalizesWithDotslashWrapper() throws IOException
	{
		Path root = Files.createTempDirectory("package-license-dotslash-");
		try
		{
			Fixture fixture = fixture(root);
			Path wrapper = fixture.upstreamRoot().resolve(".github/workflows/zstd");
			write(wrapper, "wrapper contents are not read\n");
			Path packageRoot = root.resolve("packages");
			Path archiveRoot = root.resolve("archives");
			packageDirectory(packageRoot.resolve("codex-package-target"));
			packageDirectory(packageRoot.resolve("codex-app-server-package-target"));
			RecordingProcesses processes = new RecordingProcesses();
			String[] arguments = {"finalize", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
				fixture.upstreamRoot().toString(), "--package-root", packageRoot.toString(), "--archive-root",
				archiveRoot.toString(), "--target", "target"};
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, arguments, InputStream.nullInputStream(), processes,
					_ -> name -> Optional.of("selected-dotslash").filter(_ -> name.equals("dotslash")));
				requireThat(result.status(), "finalize-status").withContext(result.err(), "diagnostics").isEqualTo(0);
			}
			requireThat(processes.decompressCommands.size(), "zstd-check-count").isEqualTo(2);
			for (List<String> command : processes.decompressCommands)
			{
				requireThat(command.subList(0, 3), "resolved-zstd-command").
					isEqualTo(List.of("selected-dotslash", wrapper.toAbsolutePath().normalize().toString(), "-dc"));
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Fails finalization before any archive is written when neither zstd nor DotSlash is available.
	 *
	 * @throws IOException if a fixture operation fails
	 */
	@Test
	public void rejectsFinalizeWithoutZstd() throws IOException
	{
		Path root = Files.createTempDirectory("package-license-no-zstd-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageRoot = root.resolve("packages");
			Path archiveRoot = root.resolve("archives");
			packageDirectory(packageRoot.resolve("codex-package-target"));
			packageDirectory(packageRoot.resolve("codex-app-server-package-target"));
			RecordingProcesses processes = new RecordingProcesses();
			String[] arguments = {"finalize", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
				fixture.upstreamRoot().toString(), "--package-root", packageRoot.toString(), "--archive-root",
				archiveRoot.toString(), "--target", "target"};
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, arguments, InputStream.nullInputStream(), processes,
					_ -> _ -> Optional.empty());
				requireThat(result.status(), "finalize-status").isEqualTo(1);
				requireThat(result.err(), "diagnostics").startsWith("Package legal finalization failed: zstd is " +
					"required to write .tar.zst archives. Install zstd, or install DotSlash so the repository wrapper " +
					"can run: ");
			}
			requireThat(processes.commands, "writer-commands").isEmpty();
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Preserves primary-only voice runtime and dedicated legal bytes in finalized gzip and zstd archive pairs.
	 *
	 * @throws IOException if archive fixtures cannot be created or read
	 */
	@Test
	public void preservesFinalizedVoiceArchives() throws IOException
	{
		Path root = Files.createTempDirectory("package-license-voice-finalize-");
		try
		{
			Fixture fixture = fixture(root);
			Path packageRoot = root.resolve("packages");
			Path archiveRoot = root.resolve("archives");
			Path primary = packageDirectory(packageRoot.resolve("codex-package-target"));
			Path appServer = packageDirectory(packageRoot.resolve("codex-app-server-package-target"));
			Map<String, byte[]> voiceFiles = Map.of(
				"codex-resources/voice/bin/codex-voice-host", bytes("voice helper bytes\n"),
				"codex-resources/voice/lib/libvoice-runtime.so", bytes("voice runtime bytes\n"),
				"codex-resources/voice/manifest.json", bytes("{\"format_version\":1}\n"));
			for (Map.Entry<String, byte[]> entry : voiceFiles.entrySet())
			{
				Path voiceFile = primary.resolve(entry.getKey());
				Files.createDirectories(voiceFile.getParent());
				Files.write(voiceFile, entry.getValue());
			}
			String voiceNotice = "codex-resources/legal/licenses/upstream/third_party/voice/NOTICE.md";
			byte[] noticeBytes = Files.readAllBytes(fixture.upstreamRoot().resolve("third_party/voice/NOTICE.md"));
			Files.createDirectories(primary.resolve(voiceNotice).getParent());
			Files.write(primary.resolve(voiceNotice), noticeBytes);
			RecordingProcesses processes = new RecordingProcesses();
			String[] arguments = {"finalize", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
				fixture.upstreamRoot().toString(), "--package-root", packageRoot.toString(), "--archive-root",
				archiveRoot.toString(), "--target", "target"};
			try (DefaultToolingScope scope = new DefaultToolingScope())
			{
				CommandOutput result = invoke(scope, arguments, processes);
				requireThat(result.status(), "finalizeStatus").withContext(result.err(), "diagnostics").isEqualTo(0);
			}
			for (String extension : List.of("gz", "zst"))
			{
				Map<String, byte[]> primaryMembers = readArchiveMembers(archiveRoot.resolve("codex-package-target.tar." +
					extension));
				for (Map.Entry<String, byte[]> entry : voiceFiles.entrySet())
					requireThat(primaryMembers.get(entry.getKey()), "finalVoiceBytes").isEqualTo(entry.getValue());
				requireThat(primaryMembers.get(voiceNotice), "finalVoiceLegalBytes").isEqualTo(noticeBytes);
				Map<String, byte[]> appServerMembers = readArchiveMembers(archiveRoot.resolve(
					"codex-app-server-package-target.tar." + extension));
				requireThat(appServerMembers.containsKey("codex-resources/voice/manifest.json"),
					"appServerVoiceFree").isFalse();
			}
			requireThat(Files.exists(appServer.resolve("codex-resources/voice")), "appServerFixtureVoiceFree").isFalse();
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Reads the small real tar fixture produced by the package finalizer.
	 *
	 * @param archivePath finalized gzip or fixture zstd archive
	 * @return member bytes keyed by archive-relative path
	 * @throws IOException if an archive member cannot be read
	 */
	private static Map<String, byte[]> readArchiveMembers(Path archivePath) throws IOException
	{
		Map<String, byte[]> result = new HashMap<>();
		try (TarArchiveInputStream archive = openArchiveInput(archivePath))
		{
			TarArchiveEntry entry = archive.getNextEntry();
			while (entry != null)
			{
				if (entry.isFile())
					result.put(entry.getName(), archive.readNBytes((int) entry.getSize()));
				entry = archive.getNextEntry();
			}
		}
		return result;
	}

	/**
	 * Opens a tar archive with the supported optional gzip wrapper.
	 *
	 * @param archivePath archive file
	 * @return owned archive stream
	 * @throws IOException if the archive cannot be opened
	 */
	private static TarArchiveInputStream openArchiveInput(Path archivePath) throws IOException
	{
		InputStream file = Files.newInputStream(archivePath);
		if (archivePath.toString().endsWith(".gz"))
			return new TarArchiveInputStream(new GzipCompressorInputStream(file));
		return new TarArchiveInputStream(file);
	}

	/**
	 * Opens a package-license classpath fixture.
	 *
	 * @param name fixture filename
	 * @return fixture stream
	 * @throws IllegalStateException if the fixture is missing
	 */
	private static InputStream resource(String name)
	{
		InputStream result = PackageLicenseTest.class.getResourceAsStream(
			"/com/holdmyspot/codexunleashed/tooling/test/package-license/" + name);
		if (result == null)
			throw new IllegalStateException("Missing package-license test resource: " + name);
		return result;
	}

	/**
	 * Verifies that an invalid archive fails with the expected diagnostic.
	 *
	 * @param archive tar bytes
	 * @param documents required legal bytes
	 * @param message expected diagnostic fragment
	 * @throws Exception if assertions or archive reading fail
	 */
	private static void assertRejected(byte[] archive, Map<String, byte[]> documents, String message) throws Exception
	{
		try
		{
			PackageLicense.checkArchive(new ByteArrayInputStream(archive), documents);
			throw new AssertionError("Expected package archive rejection containing: " + message);
		}
		catch (IOException failure)
		{
			if (!failure.getMessage().contains(message))
				throw new AssertionError("Expected message containing " + message + ", got: " + failure.getMessage());
		}
	}

	/**
	 * Creates metadata and upstream legal source fixtures.
	 *
	 * @param root fixture root
	 * @return source roots
	 * @throws IOException if fixture files cannot be created
	 */
	private static Fixture fixture(Path root) throws IOException
	{
		Path metadata = root.resolve("metadata");
		Path upstream = root.resolve("upstream");
		Files.createDirectories(upstream.resolve("scripts"));
		for (String relative : DOWNSTREAM_DOCUMENTS)
			write(metadata.resolve(relative), "metadata:" + relative + "\n");
		for (String relative : UPSTREAM_NOTICES)
			write(upstream.resolve(relative), "upstream:" + relative + "\n");
		return new Fixture(metadata, upstream);
	}

	/**
	 * Reads a fixture's original source bytes.
	 *
	 * @param root source root
	 * @param relative source-relative path
	 * @return source bytes
	 * @throws IOException if the source cannot be read
	 */
	private static byte[] sourceBytes(Path root, String relative) throws IOException
	{
		return Files.readAllBytes(root.resolve(relative));
	}

	/**
	 * Builds stage arguments for a fixture.
	 *
	 * @param fixture source roots
	 * @param packageDirectory package destination
	 * @return command arguments
	 */
	private static String[] stageArguments(Fixture fixture, Path packageDirectory)
	{
		return new String[]{"stage", "--metadata-root", fixture.metadataRoot().toString(), "--upstream-root",
			fixture.upstreamRoot().toString(), "--package-dir", packageDirectory.toString()};
	}

	/**
	 * Creates a package directory with canonical metadata.
	 *
	 * @param directory package path
	 * @return created package path
	 * @throws IOException if fixture metadata cannot be created
	 */
	private static Path packageDirectory(Path directory) throws IOException
	{
		Files.createDirectories(directory);
		Files.writeString(directory.resolve("codex-package.json"), "{}\n");
		return directory;
	}

	/**
	 * Creates a fixture file and its parent directories.
	 *
	 * @param path fixture path
	 * @param contents UTF-8 contents
	 * @throws IOException if the file cannot be written
	 */
	private static void write(Path path, String contents) throws IOException
	{
		Files.createDirectories(path.getParent());
		Files.writeString(path, contents);
	}

	/**
	 * Encodes fixture text as UTF-8.
	 *
	 * @param value fixture text
	 * @return UTF-8 bytes
	 */
	private static byte[] bytes(String value)
	{
		return value.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Creates a regular tar member fixture.
	 *
	 * @param name member path
	 * @param contents member bytes
	 * @return tar entry fixture
	 */
	private static Entry file(String name, byte[] contents)
	{
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(contents.length);
		return new Entry(entry, contents);
	}

	/**
	 * Encodes tar entries for reader tests.
	 *
	 * @param entries tar members
	 * @return raw tar bytes
	 * @throws IOException if tar encoding fails
	 */
	private static byte[] tar(List<Entry> entries) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
		{
			archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			for (Entry item : entries)
			{
				archive.putArchiveEntry(item.header());
				archive.write(item.contents());
				archive.closeArchiveEntry();
			}
			archive.finish();
		}
		return bytes.toByteArray();
	}

	/**
	 * Writes a test-only tar archive for command-path coverage.
	 *
	 * @param packageDirectory fixture package tree
	 * @param archivePath output archive path
	 * @param gzip whether to wrap tar bytes in gzip
	 * @throws IOException if fixture archive writing fails
	 */
	private static void writePackageArchive(Path packageDirectory, Path archivePath, boolean gzip) throws IOException
	{
		Files.createDirectories(archivePath.getParent());
		if (gzip)
		{
			try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(archivePath)))
			{
				writePackageTar(packageDirectory, output);
			}
		}
		else
		{
			try (OutputStream output = Files.newOutputStream(archivePath))
			{
				writePackageTar(packageDirectory, output);
			}
		}
	}

	/**
	 * Writes the package tree as raw tar bytes.
	 *
	 * @param packageDirectory fixture package tree
	 * @param output tar destination stream
	 * @throws IOException if the fixture archive cannot be written
	 */
	private static void writePackageTar(Path packageDirectory, OutputStream output) throws IOException
	{
		try (TarArchiveOutputStream archive = new TarArchiveOutputStream(output))
		{
			archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			try (Stream<Path> paths = Files.walk(packageDirectory))
			{
				for (Path path : paths.filter(path -> !path.equals(packageDirectory)).sorted().toList())
				{
					String name = packageDirectory.relativize(path).toString().
						replace(path.getFileSystem().getSeparator(), "/");
					if (Files.isDirectory(path))
					{
						TarArchiveEntry entry = new TarArchiveEntry(name + "/");
						archive.putArchiveEntry(entry);
						archive.closeArchiveEntry();
					}
					else
					{
						TarArchiveEntry entry = new TarArchiveEntry(name);
						entry.setSize(Files.size(path));
						archive.putArchiveEntry(entry);
						Files.copy(path, archive);
						archive.closeArchiveEntry();
					}
				}
			}
			archive.finish();
		}
	}

	/**
	 * Removes a test-owned temporary tree.
	 *
	 * @param root temporary root
	 * @throws IOException if a fixture cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		if (Files.exists(root))
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}

	/**
	 * Invokes the command with empty stdin.
	 *
	 * @param scope shared tooling scope
	 * @param arguments command arguments
	 * @param processes injected process boundary
	 * @return captured command result
	 */
	private static CommandOutput invoke(DefaultToolingScope scope, String[] arguments, ArchiveProcesses processes)
	{
		return invoke(scope, arguments, InputStream.nullInputStream(), processes);
	}

	/**
	 * Creates a search that finds only the recorded zstd executable.
	 *
	 * @return the executable search
	 */
	private static Executables zstdOnly()
	{
		return name -> Optional.of("selected-zstd").filter(_ -> name.equals("zstd"));
	}

	/**
	 * Invokes the command and captures both output streams.
	 *
	 * @param scope shared tooling scope
	 * @param arguments command arguments
	 * @param input supplied stdin
	 * @param processes injected process boundary
	 * @return captured command result
	 */
	private static CommandOutput invoke(DefaultToolingScope scope, String[] arguments, InputStream input,
		ArchiveProcesses processes)
	{
		return invoke(scope, arguments, input, processes, _ -> zstdOnly());
	}

	/**
	 * Invokes the command with a supplied executable search and captures both output streams.
	 *
	 * @param scope shared tooling scope
	 * @param arguments command arguments
	 * @param input supplied stdin
	 * @param processes injected process boundary
	 * @param executables creates the executable search for the checks' working directory
	 * @return captured command result
	 */
	private static CommandOutput invoke(DefaultToolingScope scope, String[] arguments, InputStream input,
		ArchiveProcesses processes, Function<Path, Executables> executables)
	{
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
		{
			int status = PackageLicense.run(scope, arguments, input, Map.of(), processes, executables, out, err);
			String outText = outBytes.toString(StandardCharsets.UTF_8);
			String errText = errBytes.toString(StandardCharsets.UTF_8);
			return new CommandOutput(status, outText, errText);
		}
	}

	/**
	 * Process adapter that fails if an unexpected command is invoked.
	 */
	private static final ArchiveProcesses NO_PROCESSES = new ArchiveProcesses()
	{
		@Override
		public CommandResult capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment)
		{
			throw new AssertionError("Unexpected external command: " + command);
		}

		@Override
		public void runToFile(List<String> command, Path workingDirectory, Map<String, String> environment,
			Path standardOutput)
		{
			throw new AssertionError("Unexpected external command: " + command);
		}
	};

	/**
	 * Records upstream calls and writes fixture archives for finalization tests.
	 */
	private static final class RecordingProcesses implements ArchiveProcesses
	{
		/** Captured writer commands. */
		private final List<List<String>> commands = new ArrayList<>();
		/** Captured zstd decompressor commands. */
		private final List<List<String>> decompressCommands = new ArrayList<>();
		/** Package directory associated with each generated archive. */
		private final Map<Path, Path> packageByArchive = new HashMap<>();

		/** Creates an empty command recorder. */
		private RecordingProcesses()
		{
		}

		@Override
		public CommandResult capture(List<String> command, Path workingDirectory, Path temporaryDirectory,
			Map<String, String> environment) throws IOException
		{
			commands.add(List.copyOf(command));
			Path packageDirectory = Path.of(command.get(command.indexOf("--package-dir") + 1));
			Path archive = Path.of(command.get(command.indexOf("--archive-output") + 1));
			packageByArchive.put(archive, packageDirectory);
			writePackageArchive(packageDirectory, archive, archive.toString().endsWith(".tar.gz"));
			return new CommandResult(0, "", "");
		}

		@Override
		public void runToFile(List<String> command, Path workingDirectory, Map<String, String> environment,
			Path standardOutput) throws IOException
		{
			decompressCommands.add(List.copyOf(command));
			Path archive = Path.of(command.getLast());
			writePackageArchive(packageByArchive.get(archive), standardOutput, false);
		}
	}

	/**
	 * Metadata and upstream fixture roots.
	 *
	 * @param metadataRoot metadata fixture root
	 * @param upstreamRoot upstream fixture root
	 */
	private record Fixture(Path metadataRoot, Path upstreamRoot)
	{
	}

	/**
	 * A tar header and its contents.
	 *
	 * @param header tar metadata
	 * @param contents tar data bytes
	 */
	private record Entry(TarArchiveEntry header, byte[] contents)
	{
	}

	/**
	 * Captured package-license command output.
	 */
	private static final class CommandOutput
	{
		/** Command exit status. */
		private final int status;
		/** Standard output. */
		private final String out;
		/** Standard error. */
		private final String err;

		/**
		 * Creates captured command output.
		 *
		 * @param status command exit status
		 * @param out standard output
		 * @param err standard error
		 */
		private CommandOutput(int status, String out, String err)
		{
			this.status = status;
			this.out = out;
			this.err = err;
		}

		/**
		 * Returns the command exit status.
		 *
		 * @return command exit status
		 */
		private int status()
		{
			return status;
		}

		/**
		 * Returns standard output.
		 *
		 * @return standard output
		 */
		private String out()
		{
			return out;
		}

		/**
		 * Returns standard error.
		 *
		 * @return standard error
		 */
		private String err()
		{
			return err;
		}
	}
}
