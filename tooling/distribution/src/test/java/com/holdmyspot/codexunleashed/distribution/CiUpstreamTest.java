package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/** Checks shared upstream selection and executes the maintained clone action with offline Git. */
public final class CiUpstreamTest
{
	/** Creates upstream policy tests. */
	public CiUpstreamTest()
	{
	}

	/**
	 * Requires the shared upstream pin to name a stable release.
	 *
	 * @throws IOException if the maintained pin cannot be read
	 */
	@Test
	public void requiresSharedStablePin() throws IOException
	{
		Path pin = projectRoot().resolve(".github/upstream-ref");
		assertTrue(Files.isRegularFile(pin));
		assertTrue(Pattern.compile("^rust-v\\d+\\.\\d+\\.\\d+$", Pattern.UNICODE_CHARACTER_CLASS).
			matcher(Files.readString(pin).strip()).matches());
	}

	/**
	 * Preserves explicit branch overrides and refuses missing pins or unexpected source commits.
	 *
	 * @throws IOException if fixture, runtime or process operations fail
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void executesOfflineCheckoutSelection() throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("ci-upstream-");
		try
		{
			Path origin = Files.createDirectory(root.resolve("origin"));
			Path checkout = root.resolve("checkout");
			Path pin = Files.createDirectory(root.resolve(".github")).resolve("upstream-ref");
			Files.writeString(pin, "rust-v0.159.2\n");
			Map<String, String> environment = new HashMap<>(System.getenv());
			environment.put("GIT_CONFIG_NOSYSTEM", "1");
			environment.put("GIT_CONFIG_GLOBAL", Files.writeString(root.resolve("gitconfig"), "").toString());
			environment.put("GIT_AUTHOR_NAME", "Fixture");
			environment.put("GIT_AUTHOR_EMAIL", "fixture@example.invalid");
			environment.put("GIT_COMMITTER_NAME", "Fixture");
			environment.put("GIT_COMMITTER_EMAIL", "fixture@example.invalid");
			environment.put("TMPDIR", Files.createDirectory(root.resolve("captures")).toString());
			environment.put("XDG_CACHE_HOME", Files.createDirectory(root.resolve("xdg")).toString());
			assertEquals(run(origin, environment, List.of("git", "init", "-q", "-b", "main")).status(), 0);
			Files.writeString(origin.resolve("selected"), "stable");
			assertEquals(run(origin, environment, List.of("git", "add", "selected")).status(), 0);
			assertEquals(run(origin, environment, List.of("git", "commit", "-qm", "stable")).status(), 0);
			String stable = run(origin, environment, List.of("git", "rev-parse", "HEAD")).output().strip();
			assertEquals(run(origin, environment, List.of("git", "tag", "rust-v0.159.2")).status(), 0);
			assertEquals(run(origin, environment, List.of("git", "checkout", "-qb", "my-test-branch")).status(), 0);
			Files.writeString(origin.resolve("selected"), "branch");
			assertEquals(run(origin, environment, List.of("git", "commit", "-qam", "branch")).status(), 0);
			Path image = root.resolve("runtime with spaces");
			DistributionMain.main(new String[]{System.getProperty("tooling.runtime.modules"), image.toString()});
			environment.put("CODEX_UNLEASHED_TOOLING", image.resolve("bin/codex-tooling").toString());
			environment.put("GIT_CONFIG_COUNT", "1");
			environment.put("GIT_CONFIG_KEY_0", "url." + origin.toUri() + ".insteadOf");
			environment.put("GIT_CONFIG_VALUE_0", "https://github.com/fixture/upstream.git");
			environment.put("GITHUB_WORKSPACE", root.toString());
			environment.put("UPSTREAM_REPO", "fixture/upstream");
			environment.put("CHECKOUT_PATH", checkout.toString());
			environment.put("FALLBACK_TO_DEFAULT_BRANCH", "false");
			Path outputs = Files.writeString(root.resolve("github-env"), "");
			environment.put("GITHUB_ENV", outputs.toString());
			Path script = projectRoot().resolve(".github/actions/prepare-patched-upstream/clone.sh");
			for (String reference : List.of("", "my-test-branch"))
			{
				environment.put("UPSTREAM_REF", reference);
				environment.put("EXPECTED_UPSTREAM_COMMIT", "");
				Result result = run(root, environment, List.of("bash", script.toString()));
				assertEquals(result.status(), 0, result.output());
				String expected = "branch";
				if (reference.isEmpty())
					expected = "stable";
				assertEquals(Files.readString(checkout.resolve("selected")), expected);
			}
			assertEquals(Files.readAllLines(outputs).size(), 2);
			assertTrue(Files.readString(outputs).contains("CACHE_UPSTREAM_PREFIX="));
			String before = Files.readString(outputs);
			Files.delete(pin);
			environment.put("UPSTREAM_REF", "");
			assertNotEquals(run(root, environment, List.of("bash", script.toString())).status(), 0);
			assertEquals(Files.readString(checkout.resolve("selected")), "branch");
			assertEquals(Files.readString(outputs), before);
			environment.put("UPSTREAM_REF", "my-test-branch");
			environment.put("EXPECTED_UPSTREAM_COMMIT", stable);
			Result mismatch = run(root, environment, List.of("bash", script.toString()));
			assertNotEquals(mismatch.status(), 0);
			assertTrue(mismatch.output().contains("expected " + stable), mismatch.output());
			assertEquals(Files.readString(outputs), before);
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
	 * Refuses stale fixed commits, default main selection and silent default-branch fallback in CI callers.
	 *
	 * @throws IOException if maintained workflows cannot be read
	 */
	@Test
	public void checksWorkflowSelectionPolicy() throws IOException
	{
		try (Stream<Path> workflows = Files.list(projectRoot().resolve(".github/workflows")))
		{
			for (Path workflow : workflows.filter(path -> path.getFileName().toString().endsWith(".yml")).toList())
			{
				String text = Files.readString(workflow);
				for (String forbidden : List.of("848b3845884e3aaf3359867047751dfff12dc448", "default: main",
					"fallback-to-default-branch: \"true\""))
					assertFalse(text.contains(forbidden), workflow.toString());
			}
		}
	}

	/**
	 * Locates the maintained project from the configured release workflow.
	 *
	 * @return project root
	 */
	private static Path projectRoot()
	{
		return Path.of(System.getProperty("tooling.release.workflow")).getParent().getParent().getParent();
	}

	/**
	 * Executes a real child with complete environment, EOF input and fixture-owned capture files.
	 *
	 * @param root process directory
	 * @param environment complete child environment
	 * @param command executable and arguments
	 * @return actual status and combined output
	 * @throws IOException if process or output access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static Result run(Path root, Map<String, String> environment, List<String> command)
		throws IOException, InterruptedException
	{
		Path log = root.resolve("console.log");
		Path input = Files.writeString(root.resolve("stdin"), "");
		ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectInput(input.toFile()).
			redirectErrorStream(true).redirectOutput(log.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		try (Process process = builder.start())
		{
			try
			{
				assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Offline checkout timed out: " + command);
				return new Result(process.exitValue(), Files.readString(log));
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly().waitFor();
			}
		}
	}

	/**
	 * Captures an actual command outcome.
	 *
	 * @param status native exit status
	 * @param output combined process output
	 */
	private record Result(int status, String output)
	{
	}
}
