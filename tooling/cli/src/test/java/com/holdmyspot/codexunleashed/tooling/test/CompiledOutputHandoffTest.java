package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CompiledOutputHandoff;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.file.PathUtils;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Exercises the Intel macOS compile handoff between the compile host and the packaging host. */
public final class CompiledOutputHandoffTest
{
	/** Handed-over target. */
	private static final String TARGET = "x86_64-apple-darwin";
	/** Patched source commit. */
	private static final String COMMIT = "a".repeat(40);
	/** Fixture DWARF content that occurs once in the archive. */
	private static final String DWARF = "dwarf-bytes-for-codex";

	/** Creates handoff checks. */
	public CompiledOutputHandoffTest()
	{
	}

	/**
	 * Installs exactly the captured executables and dSYM bundles, resolving a symlinked bundle root.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void roundTripsExecutablesAndBundles() throws IOException
	{
		Path root = Files.createTempDirectory("compiled-output-handoff-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path release = release(root);
			Path archive = root.resolve("compiled-outputs.tar");
			requireThat(invoke(scope, "capture", "--release-dir", release.toString(), "--target", TARGET,
				"--source-commit", COMMIT, "--output", archive.toString(), "--binaries", "codex-exec-server", "codex"),
				"captureStatus").isEqualTo(0);
			Path installed = root.resolve("installed/release");
			Files.createDirectories(installed.getParent());
			requireThat(install(scope, archive, installed, COMMIT), "installStatus").isEqualTo(0);
			for (String binary : List.of("codex", "codex-exec-server"))
			{
				requireThat(Arrays.equals(Files.readAllBytes(installed.resolve(binary)),
					Files.readAllBytes(release.resolve(binary))), binary).isTrue();
				requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(installed.resolve(binary))),
					binary + "Mode").isEqualTo("rwxr-xr-x");
				Path bundle = installed.resolve(binary + ".dSYM");
				requireThat(Files.isSymbolicLink(bundle), binary + "BundleLink").isFalse();
				requireThat(Files.readString(bundle.resolve("Contents/Resources/DWARF/" + binary + "-0123")),
					binary + "Dwarf").isEqualTo(DWARF.replace("codex", binary));
			}
			try (var listing = Files.list(installed))
			{
				requireThat(listing.map(path -> path.getFileName().toString()).sorted().toList(), "installed").
					isEqualTo(List.of("codex", "codex-exec-server", "codex-exec-server.dSYM", "codex.dSYM"));
			}
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Rejects altered content, a different source commit and a non-x86_64 executable without installing anything.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rejectsMismatchedHandoffs() throws IOException
	{
		Path root = Files.createTempDirectory("compiled-output-handoff-reject-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path release = release(root);
			Path archive = root.resolve("compiled-outputs.tar");
			requireThat(invoke(scope, "capture", "--release-dir", release.toString(), "--target", TARGET,
				"--source-commit", COMMIT, "--output", archive.toString(), "--binaries", "codex", "codex-exec-server"),
				"captureStatus").isEqualTo(0);
			Path installed = root.resolve("release");
			requireThat(install(scope, archive, installed, "b".repeat(40)), "wrongCommit").isEqualTo(1);
			requireThat(Files.exists(installed, LinkOption.NOFOLLOW_LINKS), "wrongCommitInstalled").isFalse();

			byte[] bytes = Files.readAllBytes(archive);
			byte[] marker = DWARF.getBytes(StandardCharsets.US_ASCII);
			int offset = indexOf(bytes, marker);
			requireThat(offset, "markerOffset").isNotNegative();
			bytes[offset] ^= 1;
			Path tampered = root.resolve("tampered.tar");
			Files.write(tampered, bytes);
			requireThat(install(scope, tampered, installed, COMMIT), "tampered").isEqualTo(1);
			requireThat(Files.exists(installed, LinkOption.NOFOLLOW_LINKS), "tamperedInstalled").isFalse();

			Files.write(release.resolve("codex"), new byte[]{(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0x0C,
				0x00, 0x00, 0x01, 0, 0, 0, 0, 0x02, 0, 0, 0});
			requireThat(invoke(scope, "capture", "--release-dir", release.toString(), "--target", TARGET,
				"--source-commit", COMMIT, "--output", root.resolve("arm64.tar").toString(), "--binaries", "codex"),
				"arm64Capture").isEqualTo(1);
			requireThat(invoke(scope, "capture", "--release-dir", release.toString(), "--target", "aarch64-apple-darwin",
				"--source-commit", COMMIT, "--output", root.resolve("other.tar").toString(), "--binaries", "codex"),
				"unsupportedTarget").isEqualTo(2);
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Creates a Cargo release directory whose dSYM bundle roots are symbolic links into {@code deps}.
	 *
	 * @param root fixture root
	 * @return release directory
	 * @throws IOException if fixture I/O fails
	 */
	private static Path release(Path root) throws IOException
	{
		Path release = Files.createDirectories(root.resolve("target/x86_64-apple-darwin/release"));
		for (String binary : List.of("codex", "codex-exec-server"))
		{
			byte[] header = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE, 0x07, 0x00, 0x00, 0x01, 0x03, 0x00,
				0x00, 0x00, 0x02, 0x00, 0x00, 0x00};
			byte[] content = Arrays.copyOf(header, header.length + binary.length());
			System.arraycopy(binary.getBytes(StandardCharsets.US_ASCII), 0, content, header.length, binary.length());
			Path executable = Files.write(release.resolve(binary), content);
			Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"));
			Path bundle = release.resolve("deps/" + binary + "-0123.dSYM");
			Path dwarf = Files.createDirectories(bundle.resolve("Contents/Resources/DWARF"));
			Files.writeString(dwarf.resolve(binary + "-0123"), DWARF.replace("codex", binary));
			Files.writeString(bundle.resolve("Contents/Info.plist"), "<plist/>");
			Files.createSymbolicLink(release.resolve(binary + ".dSYM"), Path.of("deps", binary + "-0123.dSYM"));
		}
		return release;
	}

	/**
	 * Installs a handoff for the fixture executables.
	 *
	 * @param scope   JSON services
	 * @param archive handoff archive
	 * @param release release directory to create
	 * @param commit  expected source commit
	 * @return command status
	 */
	private static int install(DefaultToolingScope scope, Path archive, Path release, String commit)
	{
		return invoke(scope, "install", "--archive", archive.toString(), "--release-dir", release.toString(),
			"--target", TARGET, "--source-commit", commit, "--binaries", "codex", "codex-exec-server");
	}

	/**
	 * Runs the command with discarded output.
	 *
	 * @param scope     JSON services
	 * @param arguments command arguments
	 * @return command status
	 */
	private static int invoke(DefaultToolingScope scope, String... arguments)
	{
		PrintStream discard = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		return CompiledOutputHandoff.run(scope, arguments, discard, discard);
	}

	/**
	 * Finds the first occurrence of a byte sequence.
	 *
	 * @param bytes  searched bytes
	 * @param marker sought sequence
	 * @return offset, or -1 if absent
	 */
	private static int indexOf(byte[] bytes, byte[] marker)
	{
		for (int index = 0; index + marker.length <= bytes.length; ++index)
			if (Arrays.equals(bytes, index, index + marker.length, marker, 0, marker.length))
				return index;
		return -1;
	}
}
