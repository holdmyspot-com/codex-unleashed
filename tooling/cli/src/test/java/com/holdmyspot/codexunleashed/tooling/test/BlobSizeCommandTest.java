package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BlobSizeCommand;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks command parsing and Git argument construction without running Git.
 */
public final class BlobSizeCommandTest
{
	/**
	 * Creates command construction checks.
	 */
	public BlobSizeCommandTest()
	{
	}

	/**
	 * Requires both revisions and resolves a relative repository from the current directory.
	 */
	@Test
	public void parsesRequiredRevisionsAndRepo()
	{
		Path currentDirectory = Path.of("project").toAbsolutePath().normalize();
		BlobSizeCommand.Arguments parsed = BlobSizeCommand.parseArguments(new String[]{"--base", "A", "--head", "B",
			"--repo", "work"}, currentDirectory);
		requireThat(parsed.repository(), "repository").isEqualTo(currentDirectory.resolve("work"));
		requireThat(parsed.base(), "base").isEqualTo("A");
		requireThat(parsed.head(), "head").isEqualTo("B");
	}

	/**
	 * Rejects absent, repeated, unknown, and valueless options.
	 */
	@Test
	public void rejectsMalformedArguments()
	{
		assertInvalid(new String[]{});
		assertInvalid(new String[]{"--base", "A", "--head"});
		assertInvalid(new String[]{"--base", "A", "--base", "B", "--head", "C"});
		assertInvalid(new String[]{"--base", "A", "--head", "B", "--other", "C"});
	}

	/**
	 * Uses rev-list reachability exclusions and treats an all-zero base as full history.
	 */
	@Test
	public void buildsReachabilityArguments()
	{
		requireThat(BlobSizeCommand.revListArguments("HEAD", "000000", null), "allHistory").
			isEqualTo(List.of("git", "rev-list", "--objects", "--no-object-names", "HEAD"));
		requireThat(BlobSizeCommand.revListArguments("HEAD", "A", "resolved-A"), "excludingBase").
			isEqualTo(List.of("git", "rev-list", "--objects", "--no-object-names", "HEAD", "^resolved-A"));
	}

	/**
	 * Requires the parser to reject one malformed argument vector.
	 *
	 * @param arguments malformed command options
	 */
	private static void assertInvalid(String[] arguments)
	{
		try
		{
			BlobSizeCommand.parseArguments(arguments, Path.of("."));
			requireThat(false, "invalidArguments").isTrue();
		}
		catch (IllegalArgumentException expected)
		{
			requireThat(expected.getMessage(), "message").isNotNull();
		}
	}
}
