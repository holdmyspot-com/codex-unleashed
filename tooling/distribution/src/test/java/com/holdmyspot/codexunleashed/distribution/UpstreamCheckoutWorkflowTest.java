package com.holdmyspot.codexunleashed.distribution;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the release-tag checkout helper used by the maintained release workflow.
 */
public final class UpstreamCheckoutWorkflowTest
{
	/**
	 * Creates the workflow tests.
	 */
	public UpstreamCheckoutWorkflowTest()
	{
	}

	/**
	 * Checks out annotated and lightweight release tags without warnings or branch-name ambiguity.
	 * Failed fetches remove their incomplete checkout and preserve an existing destination.
	 *
	 * @throws IOException if fixture access or a native command fails
	 * @throws InterruptedException if a native command wait is interrupted
	 */
	@Test
	public void checksOutReleaseTag() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("upstream-checkout-workflow-");
		try
		{
			Files.createDirectory(root.resolve("templates"));
			Files.writeString(root.resolve("git-config"), "");
			Path source = root.resolve("source with spaces");
			run(root, 0, "git", "init", "--initial-branch=main", source.toString());
			run(root, 0, "git", "-C", source.toString(), "config", "user.name", "Fixture");
			run(root, 0, "git", "-C", source.toString(), "config", "user.email", "fixture@example.invalid");
			run(root, 0, "git", "-C", source.toString(), "commit", "--allow-empty", "-m", "Release commit");
			String expected = run(root, 0, "git", "-C", source.toString(), "rev-parse", "HEAD").strip();
			for (String tag : new String[]{"annotated", "collision"})
				run(root, 0, "git", "-C", source.toString(), "tag", "-a", tag, "-m", "Annotated release");
			run(root, 0, "git", "-C", source.toString(), "tag", "lightweight");
			run(root, 0, "git", "-C", source.toString(), "commit", "--allow-empty", "-m", "Newer branch commit");
			run(root, 0, "git", "-C", source.toString(), "branch", "collision");
			Path workflow = Path.of(System.getProperty("tooling.release.workflow"));
			Path script = workflow.getParent().getParent().resolve("scripts/checkout-upstream-tag.sh");
			String filename = script.toString().replace(File.separatorChar, '/');
			String remote = source.toUri().toString();
			for (String tag : new String[]{"annotated", "lightweight", "collision"})
			{
				String destination = tag + " checkout";
				String output = run(root, 0, "bash", filename, remote, tag, destination);
				assertFalse(output.toLowerCase(Locale.ROOT).contains("warning:"), output);
				assertEquals(run(root, 0, "git", "-C", destination, "rev-parse", "HEAD").strip(), expected, tag);
				assertEquals(run(root, 0, "git", "-C", destination, "rev-list", "--count", "HEAD").strip(), "1");
				assertEquals(run(root, 0, "git", "-C", destination, "rev-parse", "--is-shallow-repository").strip(),
					"true");
				run(root, 1, "git", "-C", destination, "symbolic-ref", "--quiet", "HEAD");
				assertEquals(run(root, 0, "git", "-C", destination, "remote", "get-url", "origin").strip(), remote);
			}
			run(root, 128, "bash", filename, remote, "missing", "incomplete");
			assertFalse(Files.exists(root.resolve("incomplete")));
			Path existing = Files.createDirectory(root.resolve("existing"));
			Files.writeString(existing.resolve("keep"), "untouched");
			run(root, 128, "bash", filename, remote, "annotated", "existing");
			assertEquals(Files.readString(existing.resolve("keep")), "untouched");
			assertTrue(WorkflowCommands.readStepCommand("prepare", "Determine upstream release").
				contains("bash .github/scripts/checkout-upstream-tag.sh"));
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}

	/**
	 * Runs a command with isolated Git configuration and returns its captured output.
	 *
	 * @param root the fixture directory
	 * @param expectedStatus the expected exit status
	 * @param command the executable and literal arguments
	 * @return the combined standard output and error
	 * @throws IOException if process startup or log access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(Path root, int expectedStatus, String... command)
		throws IOException, InterruptedException
	{
		Path log = root.resolve("command.log");
		ProcessBuilder builder = NativeCommands.createBuilder(command);
		builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().put("TMPDIR", root.toString());
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", root.resolve("git-config").toString());
		builder.environment().put("GIT_TEMPLATE_DIR", root.resolve("templates").toString());
		try (Process process = builder.start())
		{
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Upstream checkout command timed out");
			String output = Files.readString(log);
			assertEquals(process.exitValue(), expectedStatus, output);
			return output;
		}
	}
}
