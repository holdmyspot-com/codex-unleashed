package com.holdmyspot.codexunleashed.tooling.test;

import tools.jackson.databind.JsonNode;
import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproduction;
import com.holdmyspot.codexunleashed.tooling.ReleaseReproductionSource;
import com.holdmyspot.codexunleashed.tooling.ReleaseManifestSchema;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.file.PathUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Checks executable release reproduction decisions. */
public final class ReleaseReproductionTest
{
	/** Creates command boundary checks. */
	public ReleaseReproductionTest()
	{
	}

	/** Recognizes the reproduction command before touching release inputs. */
	@Test
	public void commandHelp()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-reproduction", "--help"},
				new ByteArrayInputStream(new byte[0]), stream, stream);
			requireThat(status, "status").isEqualTo(0);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "help").contains("run-prepared").contains("run-compile");
		}
	}

	/** Accepts a compile handoff only on the packaging operation. */
	@Test
	public void compiledOutputsOnlyForPackaging()
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"release-reproduction", "run-compile", "--project-root", "root",
				"--state", "state", "--release-dir", "release", "--workspace", "workspace", "--output-dir", "output",
				"--compiled-outputs", "compiled"}, new ByteArrayInputStream(new byte[0]), stream, stream);
			requireThat(status, "status").isEqualTo(2);
			requireThat(bytes.toString(StandardCharsets.UTF_8), "diagnostic").contains("Expected options");
		}
	}

	/**
	 * Rejects a compressed mismatch even when all recognized executable bytes match.
	 *
	 * @throws IOException if fixtures or retained evidence cannot be written
	 */
	@Test
	public void compressedMismatchDespiteBinaryMatch() throws IOException
	{
		Path root = Files.createTempDirectory("reproduction-compressed-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path expected = root.resolve("expected.tar.gz");
			Path actual = root.resolve("actual.tar.gz");
			archive(expected, 0);
			archive(actual, 1000);
			Path output = Files.createDirectory(root.resolve("output"));
			ReleaseReproductionSource.Release release = new ReleaseReproductionSource.Release("a".repeat(64),
				"x86_64-unknown-linux-musl", "b".repeat(40), "c".repeat(40), "rust-v1.2.3", "d".repeat(40),
				"1.2.3+999999999999999999999999999999", new BigInteger("999999999999999999999999999999"),
				Sha256.digest(expected), List.of("patches/1.patch"));
			requireThat(ReleaseReproduction.compare(scope, release, expected, actual, output), "status").isEqualTo(1);
			JsonNode report = ReleaseManifestSchema.read(scope, output.resolve("reproduction-result.json"));
			requireThat(report.path("result").asString(), "result").isEqualTo("digest_mismatch");
			requireThat(report.path("expected_archive_sha256").asString(), "expectedDigest").
				isEqualTo(Sha256.digest(expected));
			requireThat(report.path("actual_archive_sha256").asString(), "actualDigest").
				isEqualTo(Sha256.digest(actual));
			requireThat(report.path("binaries").path(0).path("actual_sha256").asString(), "binaryPayload").isEqualTo(
				report.path("binaries").path(0).path("expected_sha256").asString());
			requireThat(ReleaseReproduction.compare(scope, release, expected, expected, output), "matchedStatus").
				isEqualTo(0);
			requireThat(ReleaseManifestSchema.read(scope, output.resolve("reproduction-result.json")).
				path("result").asString(),
				"matchedResult").isEqualTo("matched");
		}
		finally
		{
			PathUtils.deleteDirectory(root);
		}
	}

	/**
	 * Writes an actual gzip/tar fixture with a controlled metadata distinction.
	 *
	 * @param path current filesystem path
	 * @param modified tar entry modification time
	 * @throws IOException if the required operation cannot complete
	 */
	private static void archive(Path path, long modified) throws IOException
	{
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(Files.newOutputStream(path))))
		{
			TarArchiveEntry entry = new TarArchiveEntry("bin/codex", true);
			entry.setSize(3);
			entry.setModTime(modified);
			tar.putArchiveEntry(entry);
			tar.write(new byte[]{1, 2, 3});
			tar.closeArchiveEntry();
		}
	}
}
