package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Verifies committed blob sizes, binary decisions, allowlists, threshold boundaries, and reports through real Git.
 */
public final class BlobSizeCommandTest
{
	/** Creates blob-size tests. */
	public BlobSizeCommandTest()
	{
	}

	/** Checks that the public dispatcher exposes this policy command. */
	@Test
	public void dispatchesPolicyCommand()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{BlobSizeCommand.NAME, "--help"},
				java.io.InputStream.nullInputStream(), out, out), 0);
			assertTrue(output.toString(StandardCharsets.UTF_8).contains("--allowlist"));
		}
	}

	/**
	 * Checks added and modified objects, treats renamed objects as additions, and excludes deleted objects.
	 *
	 * @throws IOException if Git, fixture access, or cleanup fails
	 */
	@Test
	public void checksCommittedBlobPolicy() throws IOException
	{
		try (Fixture fixture = new Fixture(); ByteArrayOutputStream output = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			Files.writeString(fixture.repository.resolve("at-limit.txt"), "original\n");
			Files.writeString(fixture.repository.resolve("deleted.txt"), "d".repeat(1024));
			Files.writeString(fixture.repository.resolve("old-name.txt"), "x");
			String base = fixture.commit();
			Files.writeString(fixture.repository.resolve("at-limit.txt"), "a".repeat(256));
			Files.delete(fixture.repository.resolve("deleted.txt"));
			Files.move(fixture.repository.resolve("old-name.txt"), fixture.repository.resolve("new-name.txt"));
			Files.writeString(fixture.repository.resolve("blocked 雪.txt"), "b".repeat(257));
			Files.write(fixture.repository.resolve("allowed.bin"), new byte[257]);
			String head = fixture.commit();
			Path allowlist = Files.writeString(fixture.root.resolve("allowlist"), "\u00a0allowed.bin\u00a0 # explanation\n");
			Path summary = fixture.root.resolve("summary");
			String before = fixture.git("status", "--porcelain");
			assertEquals(BlobSizeCommand.run(new String[]{"--repo", fixture.repository.toString(), "--base", base,
				"--head", head, "--max-bytes", "٢٥٦", "--allowlist", allowlist.toString()}, out, out,
				Map.of("GITHUB_STEP_SUMMARY", summary.toString())), 1);
			String report = output.toString(StandardCharsets.UTF_8);
			assertTrue(report.contains("Checked 4 changed file(s) against the 256-byte limit."));
			assertTrue(report.contains("at-limit.txt: 256 bytes (0.2 KiB) [non-binary, ok]"));
			assertTrue(report.contains("allowed.bin: 257 bytes (0.3 KiB) [binary, allowlisted]"));
			assertTrue(report.contains("blocked 雪.txt: 257 bytes (0.3 KiB) [non-binary, blocked]"));
			assertFalse(report.contains("deleted.txt"));
			assertFalse(report.contains("old-name.txt"));
			String markdown = Files.readString(summary);
			assertTrue(markdown.startsWith("## Blob Size Policy\n\nDefault max: `256` bytes (0.2 KiB)\n"));
			assertTrue(markdown.contains("Changed files checked: `4`\nViolations: `1`\n"));
			assertTrue(markdown.contains("| `blocked 雪.txt` | non-binary | `257` bytes (0.3 KiB) | blocked |"));
			assertEquals(fixture.git("status", "--porcelain"), before);
		}
	}

	/**
	 * Accepts huge integer limits without unused report formatting and reports an empty comparison.
	 *
	 * @throws IOException if Git, fixture access, or cleanup fails
	 */
	@Test
	public void preservesEmptyAndLargeLimits() throws IOException
	{
		try (Fixture fixture = new Fixture(); ByteArrayOutputStream output = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			Files.writeString(fixture.repository.resolve("source.txt"), "source\n");
			String base = fixture.commit();
			Files.writeString(fixture.repository.resolve("source.txt"), "changed\n");
			String head = fixture.commit();
			Path allowlist = Files.writeString(fixture.root.resolve("allowlist"), "# empty\n");
			assertEquals(BlobSizeCommand.run(new String[]{"--repo", fixture.repository.toString(), "--base", base,
				"--head", head, "--max-bytes", "+" + "9".repeat(400), "--allowlist", allowlist.toString()}, out, out,
				Map.of()), 0);
			output.reset();
			Path summary = fixture.root.resolve("summary");
			assertEquals(BlobSizeCommand.run(new String[]{"--repo", fixture.repository.toString(), "--base", head,
				"--head", head, "--allowlist", allowlist.toString()}, out, out,
				Map.of("GITHUB_STEP_SUMMARY", summary.toString())), 0);
			assertEquals(output.toString(StandardCharsets.UTF_8),
				"No changed files were detected." + System.lineSeparator());
			assertTrue(Files.readString(summary).contains("Default max: `512000` bytes (500.0 KiB)"));
			assertTrue(Files.readString(summary).endsWith("No changed files were detected.\n"));
		}
	}

	/**
	 * Rejects invalid options and Git revisions without overwriting existing summary evidence.
	 *
	 * @throws IOException if Git, fixture access, or cleanup fails
	 */
	@Test
	public void preservesReportsOnInputFailure() throws IOException
	{
		try (Fixture fixture = new Fixture(); ByteArrayOutputStream output = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
		{
			Files.writeString(fixture.repository.resolve("source.txt"), "source\n");
			String head = fixture.commit();
			Path allowlist = Files.writeString(fixture.root.resolve("allowlist"), "");
			Path summary = Files.writeString(fixture.root.resolve("summary"), "existing\n");
			assertEquals(BlobSizeCommand.run(new String[]{"--repo", fixture.repository.toString(), "--base", head,
				"--head", head, "--max-bytes", "1__0", "--allowlist", allowlist.toString()}, out, out,
				Map.of("GITHUB_STEP_SUMMARY", summary.toString())), 2);
			assertEquals(Files.readString(summary), "existing\n");
			IOException failure = org.testng.Assert.expectThrows(IOException.class, () -> BlobSizeCommand.run(
				new String[]{"--repo", fixture.repository.toString(), "--base", "missing-reference", "--head", head,
					"--allowlist", allowlist.toString()}, out, out, Map.of("GITHUB_STEP_SUMMARY", summary.toString())));
			assertTrue(failure.getMessage().contains("Git"));
			assertEquals(Files.readString(summary), "existing\n");
		}
	}

	/** Owns an isolated real Git repository and command captures. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root = Files.createTempDirectory("blob-size-");
		private final Path repository;

		/**
		 * Initializes repository-local identity with no user Git templates.
		 *
		 * @throws IOException if fixture initialization fails
		 */
		private Fixture() throws IOException
		{
			repository = Files.createDirectory(root.resolve("repository"));
			git("init", "-q", "--template=");
			git("config", "user.name", "Fixture");
			git("config", "user.email", "fixture@example.invalid");
			git("config", "commit.gpgsign", "false");
		}

		/**
		 * Commits all fixture paths and returns the resulting object identity.
		 *
		 * @return the commit SHA
		 * @throws IOException if Git fails
		 */
		private String commit() throws IOException
		{
			git("add", "-A");
			git("commit", "-qm", "fixture");
			return git("rev-parse", "HEAD").strip();
		}

		/**
		 * Executes fixture Git without user-level configuration.
		 *
		 * @param arguments the literal Git arguments
		 * @return standard output
		 * @throws IOException if Git fails
		 */
		private String git(String... arguments) throws IOException
		{
			List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
			command.addAll(List.of(arguments));
			SystemCommands.Result result = SystemCommands.capture(command, root, root, Map.of("GIT_CONFIG_NOSYSTEM", "1",
				"GIT_CONFIG_GLOBAL", root.resolve("absent-config").toString()));
			if (result.status() != 0)
				throw new IOException("Fixture Git failed: " + result.stderr());
			return result.stdout();
		}

		/**
		 * Removes all owned fixture paths without following links.
		 *
		 * @throws IOException if cleanup fails
		 */
		@Override
		public void close() throws IOException
		{
			PathUtils.deleteDirectory(root, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}
}
