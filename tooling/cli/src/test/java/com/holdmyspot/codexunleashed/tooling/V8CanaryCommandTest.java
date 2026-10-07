package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies manual canary dispatch and real divergent Git comparison through the maintained CLI.
 */
public final class V8CanaryCommandTest
{
	/**
	 * Creates the command tests.
	 */
	public V8CanaryCommandTest()
	{
	}

	/**
	 * Forces both matrices without reading Git, even when no comparison range exists.
	 */
	@Test
	public void forcesWithoutGit()
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"v8-canary-changes", "missing checkout", "--force"},
				new ByteArrayInputStream(new byte[0]), stream, stream, _ ->
				{
					throw new AssertionError("Forced dispatch must not read Git");
				}), 0);
		}
		assertEquals(output.toString(StandardCharsets.UTF_8), ("canary_required=true\n" +
			"canary_reason=manual workflow dispatch\nwindows_source_required=true\n" +
			"windows_source_reason=manual workflow dispatch\n").replace("\n", System.lineSeparator()));
	}

	/**
	 * Rejects incomplete comparison ranges before invoking Git.
	 */
	@Test
	public void rejectsMissingRanges()
	{
		for (String[] args : new String[][]{{"v8-canary-changes", "."},
			{"v8-canary-changes", ".", "--base", "main"}, {"v8-canary-changes", ".", "--head", "HEAD"}})
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8))
			{
				assertEquals(Main.run(args, new ByteArrayInputStream(new byte[0]), stream, stream, _ ->
				{
					throw new AssertionError("Missing ranges must not read Git");
				}), 1);
			}
			assertEquals(output.toString(StandardCharsets.UTF_8),
				"--base and --head are required unless --force is set" + System.lineSeparator());
		}
	}

	/**
	 * Excludes a base-only relevant path and V8 upgrade from the feature branch's three-dot comparison.
	 *
	 * @throws IOException if fixture access or a Git command fails
	 */
	@Test
	public void retainsMergeBaseSemantics() throws IOException
	{
		Path root = Files.createTempDirectory("canary-git-");
		try
		{
			Path configuration = Files.writeString(root.resolve("empty-git-config"), "");
			Path template = Files.createDirectory(root.resolve("empty-git-template"));
			Path checkout = Files.createDirectory(root.resolve("checkout"));
			CommandRunner runner = command -> runGit(command, root, configuration, template);
			git(runner, checkout, "init", "--initial-branch=main");
			Path lockfile = Files.createDirectories(checkout.resolve("codex-rs")).resolve("Cargo.lock");
			Files.writeString(lockfile, "[[package]]\nname='v8'\nversion='149.2.0'\n");
			commit(runner, checkout, "common");
			git(runner, checkout, "branch", "feature");
			Files.writeString(lockfile, "[[package]]\nname='v8'\nversion='150.0.0'\n");
			Path relevant = Files.createDirectories(checkout.resolve(".github/actions/setup-ci")).resolve("action.yml");
			Files.writeString(relevant, "base-only relevant change");
			commit(runner, checkout, "base-only");
			String base = git(runner, checkout, "rev-parse", "HEAD").strip();
			git(runner, checkout, "switch", "feature");
			Files.writeString(checkout.resolve("feature-only.txt"), "feature change");
			commit(runner, checkout, "feature-only");
			String head = git(runner, checkout, "rev-parse", "HEAD").strip();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream stream = new PrintStream(output, false, StandardCharsets.UTF_8))
			{
				assertEquals(Main.run(new String[]{"v8-canary-changes", checkout.toString(), "--base", base,
					"--head", head}, new ByteArrayInputStream(new byte[0]), stream, stream, runner), 0);
			}
			assertEquals(output.toString(StandardCharsets.UTF_8), ("canary_required=false\n" +
				"canary_reason=no relevant changes\nwindows_source_required=false\n" +
				"windows_source_reason=no relevant changes\n").replace("\n", System.lineSeparator()));
		}
		finally
		{
			PathUtils.deleteDirectory(root, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}

	/**
	 * Commits the fixture's current files with explicit identity and signing policy.
	 *
	 * @param runner the isolated Git boundary
	 * @param checkout the fixture checkout
	 * @param message the commit message
	 * @throws IOException if Git fails
	 */
	private static void commit(CommandRunner runner, Path checkout, String message) throws IOException
	{
		git(runner, checkout, "add", ".");
		git(runner, checkout, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
			"-c", "commit.gpgsign=false", "commit", "-qm", message);
	}

	/**
	 * Executes Git arguments against the explicit fixture checkout.
	 *
	 * @param runner the isolated Git boundary
	 * @param checkout the fixture checkout
	 * @param arguments the Git arguments
	 * @return the Git output
	 * @throws IOException if Git fails
	 */
	private static String git(CommandRunner runner, Path checkout, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git", "-C", checkout.toString()));
		command.addAll(Arrays.asList(arguments));
		return runner.run(command);
	}

	/**
	 * Captures real Git with fixture-owned configuration and template directories.
	 *
	 * @param command the complete Git command
	 * @param root the fixture root
	 * @param configuration the empty Git configuration file
	 * @param template the empty Git template directory
	 * @return the completed command output
	 * @throws IOException if startup, waiting, or Git fails
	 */
	private static String runGit(List<String> command, Path root, Path configuration, Path template) throws IOException
	{
		Path log = root.resolve("git.log");
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
		builder.environment().put("GIT_CONFIG_GLOBAL", configuration.toString());
		builder.environment().put("GIT_TEMPLATE_DIR", template.toString());
		try (Process process = builder.start())
		{
			assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Git fixture command timed out");
			if (process.exitValue() != 0)
				throw new IOException("Git fixture failed: " + Files.readString(log));
			return Files.readString(log);
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Git fixture wait interrupted", failure);
		}
	}
}
