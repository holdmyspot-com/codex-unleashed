package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Verifies authenticated artifact downloads through the bundled command and a native GitHub CLI fixture. */
public final class WorkflowArtifactDownloadTest
{
	/** Creates artifact download tests. */
	public WorkflowArtifactDownloadTest()
	{
	}

	/**
	 * Checks raw ZIP bytes, digest rejection, extraction containment, download failure, and temporary cleanup.
	 *
	 * @throws IOException if fixture access or process execution fails
	 * @throws InterruptedException if process waiting is interrupted
	 * @throws URISyntaxException if the native fixture class location is invalid
	 */
	@Test
	public void downloadsVerifiedArtifact() throws IOException, InterruptedException, URISyntaxException
	{
		Path root = Files.createTempDirectory("workflow-artifact-");
		try
		{
			Path adapters = Files.createDirectory(root.resolve("adapters"));
			JavaCommandFixtures.writeNativeLauncher(adapters.resolve("gh"), WorkflowArtifactGitHubFixture.class);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			byte[] payload = {0, (byte) 255, 7};
			for (String mode : List.of("valid", "digest", "traversal", "download-failure", "invalid-zip"))
			{
				Path work = Files.createDirectory(root.resolve(mode));
				Path captures = Files.createDirectory(work.resolve("captures"));
				Path archive = work.resolve("artifact.zip");
				String member = "directory with spaces/payload.bin";
				if (mode.equals("traversal"))
					member = "../escaped.bin";
				try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
				{
					zip.putNextEntry(new ZipEntry(member));
					zip.write(payload);
					zip.closeEntry();
				}
				if (mode.equals("invalid-zip"))
					Files.write(archive, payload);
				Path output = work.resolve("output with spaces");
				Path console = work.resolve("console.log");
				ProcessBuilder builder = NativeCommands.createBuilder(List.of(
					image.resolve("bin/codex-tooling").toString(), "download-workflow-artifact",
					"example/project", "123", "build bundle", output.toString())).directory(work.toFile()).
					redirectErrorStream(true).redirectOutput(console.toFile());
				builder.environment().put("PATH", adapters + File.pathSeparator + System.getenv("PATH"));
				builder.environment().put("TMPDIR", captures.toString());
				builder.environment().put("ARTIFACT_FIXTURE_ARCHIVE", archive.toString());
				builder.environment().put("ARTIFACT_FIXTURE_MODE", mode);
				try (Process process = builder.start())
				{
					process.getOutputStream().close();
					assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Artifact command timed out: " + mode);
					int expectedStatus = 1;
					if (mode.equals("valid"))
						expectedStatus = 0;
					assertEquals(process.exitValue(), expectedStatus, mode + ": " + Files.readString(console));
				}
				if (mode.equals("valid"))
					assertEquals(Files.readAllBytes(output.resolve(member)), payload);
				else
					assertFalse(Files.exists(output), Files.readString(console));
				assertFalse(Files.exists(work.resolve("escaped.bin")));
				if (mode.equals("download-failure"))
					assertTrue(Files.readString(console).contains("37"), Files.readString(console));
				try (Stream<Path> paths = Files.list(captures))
				{
					assertEquals(paths.count(), 0L, "Artifact temporary files must be removed");
				}
			}
		}
		finally
		{
			FixtureDirectories.deleteTree(root);
		}
	}
}
