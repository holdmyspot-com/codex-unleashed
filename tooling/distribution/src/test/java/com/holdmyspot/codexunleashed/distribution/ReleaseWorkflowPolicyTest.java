package com.holdmyspot.codexunleashed.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Preserves release workflow policies and executes their publication and tag-selection decisions locally. */
public final class ReleaseWorkflowPolicyTest
{
	/** Creates policy tests. */
	public ReleaseWorkflowPolicyTest()
	{
	}

	/**
	 * Evaluates the maintained publication expression for current and reused builds with all verification outcomes.
	 *
	 * @throws IOException if workflow or process access fails
	 * @throws InterruptedException if process waiting is interrupted
	 */
	@Test
	public void requiresSuccessfulVerification() throws IOException, InterruptedException
	{
		String condition = document().path("jobs").path("publish").path("if").asString().strip();
		condition = condition.substring(3, condition.length() - 2);
		for (boolean reused : new boolean[]{false, true})
		{
			String run = "current";
			if (reused)
				run = "previous";
			for (String result : List.of("success", "failure", "cancelled", "skipped"))
			{
				Map<String, Object> needs = Map.of("prepare", Map.of("outputs", Map.of("should_release", "true",
					"artifact_run_id", run)), "verify", Map.of("result", result), "build-unix", Map.of("result", "success"),
					"build-windows-package", Map.of("result", "success"));
				String script = "const needs = " + JsonMapper.builder().build().writeValueAsString(needs) + ";\n" +
					"const github = {run_id: 'current'};\n" +
					"const inputs = {verification_level: 'full', focus_target: '', focus_bundle: ''};\n" +
					"const always = () => true; const cancelled = () => false;\n" +
					"console.log(Boolean(" + condition + "));";
				assertEquals(run(List.of("node", "-e", script), System.getenv()).strip(),
					Boolean.toString(result.equals("success")));
			}
		}
	}

	/**
	 * Retains scoped read permissions and authentication on all four package cache writers.
	 *
	 * @throws IOException if workflow access fails
	 */
	@Test
	public void scopesTokensAndCacheAuthentication() throws IOException
	{
		JsonNode value = document();
		for (String job : List.of("verify", "build-windows-package"))
			assertEquals(value.path("jobs").path(job).path("permissions").path("contents").asString(), "read");
		for (String permission : List.of("actions", "contents", "packages", "id-token", "attestations"))
		{
			String expected = "write";
			if (permission.equals("actions"))
				expected = "read";
			assertEquals(value.path("permissions").path(permission).asString(), expected);
		}
		int writers = 0;
		for (JsonNode job : value.path("jobs"))
		{
			for (JsonNode step : job.path("steps"))
			{
				String name = step.path("name").asString();
				if (name.startsWith("Populate GHCR Cargo target overflow cache") ||
					name.startsWith("Prune old GHCR Cargo cache tags"))
				{
					assertEquals(step.path("env").path("GH_TOKEN").asString(), "${{ secrets.GITHUB_TOKEN }}");
					writers += 1;
				}
			}
		}
		assertEquals(writers, 4);
	}

	/**
	 * Retains normalization before cache restore and the complete Windows cache-audit sequencing contract.
	 *
	 * @throws IOException if workflow access fails
	 */
	@Test
	public void preservesNativeCacheAuditSequence() throws IOException
	{
		String source = Files.readString(workflow());
		for (String name : List.of("build-unix", "build-windows-binaries"))
		{
			String job = job(source, name);
			assertTrue(job.contains("normalize-source-timestamps upstream \"$SOURCE_DATE_EPOCH\""));
			assertTrue(job.indexOf("Normalize patched source timestamps") < job.indexOf("Restore GHCR Cargo target cache"));
		}
		String windows = job(source, "build-windows-binaries");
		assertTrue(windows.contains("cargo build --target"));
		assertTrue(windows.contains("--message-format=json-render-diagnostics"));
		assertEquals(windows.split("for binary in \\$\\{\\{ matrix.helper_binaries }}", -1).length - 1, 1);
		assertEquals(windows.split("scripts/run-cancellable-command.sh", -1).length - 1, 2);
		assertEquals(windows.split("LIBSQLITE3_FLAGS=SQLITE_DISABLE_INTRINSIC", -1).length - 1, 1);
		String environment = windows.split("Configure Windows Cargo build environment", 2)[1].
			split("\n      - name:", 2)[0];
		assertTrue(environment.contains("matrix.target == 'x86_64-pc-windows-msvc'"));
		assertTrue(windows.indexOf("Configure Windows Cargo build environment") <
			windows.indexOf("Build Windows sandbox helper binaries"));
		assertTrue(windows.contains("cargo-cache-reuse-${{ matrix.target }}-${{ matrix.bundle }}.jsonl"));
		assertTrue(windows.indexOf("Rebuild release binaries from cached dependencies") <
			windows.indexOf("Build Windows sandbox helper binaries"));
		assertTrue(windows.indexOf("Upload Windows library cache reuse report") <
			windows.indexOf("Cargo build (Windows binaries)"));
	}

	/**
	 * Keeps one release entry point, tag-triggered npm publication and both deterministic-mode setup steps.
	 *
	 * @throws IOException if workflow or referenced policy access fails
	 */
	@Test
	public void retainsReleaseAndLintEntryPoints() throws IOException
	{
		Path root = workflow().getParent().getParent().getParent();
		assertTrue(Files.isRegularFile(root.resolve(".codespellignore")));
		assertTrue(Files.isRegularFile(root.resolve(".github/codespell-matcher.json")));
		assertTrue(Files.readAllLines(root.resolve(".codespellignore")).contains("ratatui"));
		assertFalse(Files.exists(workflow().resolveSibling("rust-release.yml")));
		JsonNode triggers = document().path("on");
		assertEquals(triggers.path("push").path("tags").get(0).asString(), "rust-v*.*.*");
		assertTrue(triggers.has("workflow_dispatch"));
		assertTrue(triggers.has("workflow_call"));
		assertTrue(Files.readString(workflow().resolveSibling("upstream-release-check.yml")).
			contains("gh workflow run build-release.yml"));
		String source = Files.readString(workflow());
		assertEquals(source.split(java.util.regex.Pattern.quote(
			"if: ${{ github.event_name == 'push' || inputs.publish_npm }}"), -1).length - 1, 2);
		assertFalse(source.contains("RUSTFLAGS: ${{ (inputs.reproducibility_mode || 'off') == " +
			"'deterministic' && '-Ccodegen-units=1 -Zno-parallel-backend' || '' }}"));
		assertFalse(source.contains("RUSTC_BOOTSTRAP: ${{ (inputs.reproducibility_mode || 'off') == " +
			"'deterministic' && '1' || '' }}"));
		assertEquals(source.split("- name: Configure deterministic compiler mode", -1).length - 1, 2);
	}

	/**
	 * Executes the maintained upstream tag decision for tag pushes, explicit inputs and branch builds.
	 *
	 * @throws IOException if workflow or process access fails
	 * @throws InterruptedException if waiting is interrupted
	 */
	@Test
	public void selectsUpstreamFromReleaseTrigger() throws IOException, InterruptedException
	{
		String source = Files.readString(workflow());
		int start = source.indexOf("          if [[ -n \"${INPUT_UPSTREAM_TAG:-}\" ]]");
		int end = source.indexOf("\n          if [[ -z \"${upstream_tag}\"", start);
		String script = "gh() { printf 'false\\n'; }\n" +
			"check_upstream_release() { printf '{\"tag_name\":\"rust-v0.159.2\",\"prerelease\":false," +
			"\"draft\":false}\\n'; }\n" +
			source.substring(start, end).replace("./scripts/check-upstream-release.sh", "check_upstream_release") +
			"\nprintf '%s' \"$upstream_tag\"";
		for (List<String> scenario : List.of(List.of("", "tag", "rust-v0.159.1+23", "rust-v0.159.1"),
			List.of("rust-v0.159.2", "tag", "rust-v0.159.1+23", "rust-v0.159.2"),
			List.of("", "branch", "main", "rust-v0.159.2")))
		{
			Map<String, String> environment = Map.of("INPUT_UPSTREAM_TAG", scenario.get(0),
				"GITHUB_REF_TYPE", scenario.get(1), "GITHUB_REF_NAME", scenario.get(2), "PATH", System.getenv("PATH"));
			assertEquals(run(List.of("bash", "-eu", "-c", script), environment), scenario.get(3));
		}
	}

	/**
	 * Returns the maintained workflow path supplied by the Maven build.
	 *
	 * @return release workflow
	 */
	private static Path workflow()
	{
		return Path.of(System.getProperty("tooling.release.workflow"));
	}

	/**
	 * Parses the maintained YAML using the project's standard YAML reader.
	 *
	 * @return complete workflow document
	 * @throws IOException if the workflow cannot be read
	 */
	private static JsonNode document() throws IOException
	{
		return YAMLMapper.builder().build().readTree(Files.readString(workflow()));
	}

	/**
	 * Selects the original indentation-bounded job policy text without rewriting it.
	 *
	 * @param source maintained workflow text
	 * @param name job identity
	 * @return job text
	 */
	private static String job(String source, String name)
	{
		String value = source.split("\n  " + name + ":\n", 2)[1];
		return value.split("\n  \\S", 2)[0];
	}

	/**
	 * Runs the maintained decision with independent EOF input and owned bounded captures.
	 *
	 * @param command executable and arguments
	 * @param environment complete process environment
	 * @return captured standard output
	 * @throws IOException if process or cleanup operations fail
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String run(List<String> command, Map<String, String> environment)
		throws IOException, InterruptedException
	{
		Path root = Files.createTempDirectory("release-policy-");
		try
		{
			Path output = root.resolve("stdout");
			Path error = root.resolve("stderr");
			Path input = Files.writeString(root.resolve("stdin"), "");
			ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectInput(input.toFile()).
				redirectOutput(output.toFile()).redirectError(error.toFile());
			builder.environment().clear();
			builder.environment().putAll(environment);
			builder.environment().put("TMPDIR", root.toString());
			try (Process process = builder.start())
			{
				try
				{
					assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Policy process timed out: " + command);
					assertEquals(process.exitValue(), 0, Files.readString(error));
					return Files.readString(output);
				}
				finally
				{
					if (process.isAlive())
						process.destroyForcibly().waitFor();
				}
			}
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
}
