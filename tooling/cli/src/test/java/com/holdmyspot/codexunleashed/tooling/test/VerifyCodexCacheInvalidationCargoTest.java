package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.VerifyCodexCacheInvalidation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exercises the cache-invalidation check with real Cargo and an isolated diagnostic CLI.
 * <p>
 * The fixture models only the compile-time commit and the doctor JSON contract that the check inspects; running the
 * hosted Codex remains a separate required check.
 */
public final class VerifyCodexCacheInvalidationCargoTest
{
	/**
	 * Creates the tests.
	 */
	public VerifyCodexCacheInvalidationCargoTest()
	{
	}

	/**
	 * A changed input rebuilds the CLI alone, and the rebuilt CLI reports the changed commit.
	 *
	 * @throws IOException if the fixture or Cargo fails
	 */
	@Test
	public void acceptsChangedInputAndReusedDependencies() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope();
			TestDirectory directory = new TestDirectory("invalidation-check-"))
		{
			Fixture fixture = new Fixture(directory, true);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status = fixture.run(scope, out, err);
			requireThat(status, "status").isEqualTo(0);
			JsonNode receipt = scope.getJsonMapper().readTree(
				Files.readString(fixture.evidence.resolve("verification.json")));
			requireThat(receipt.path("original_build_commit").isNull(), "originalCommit").isTrue();
			requireThat(receipt.path("status").stringValue(), "receiptStatus").isEqualTo("passed");
		}
	}

	/**
	 * A rebuilt CLI that reports {@code unknown} instead of the diagnostic compile-time commit is rejected.
	 *
	 * @throws IOException if the fixture or Cargo fails
	 */
	@Test
	public void rejectsRebuiltCliWithStaleRuntimeValue() throws IOException
	{
		try (DefaultToolingScope scope = new DefaultToolingScope();
			TestDirectory directory = new TestDirectory("invalidation-check-"))
		{
			Fixture fixture = new Fixture(directory, false);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status = fixture.run(scope, out, err);
			requireThat(status, "status").isNotEqualTo(0);
			requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("does not report the diagnostic commit");
			requireThat(Files.exists(fixture.evidence.resolve("verification.json")), "receipt").isFalse();
		}
	}

	/**
	 * A Cargo workspace with a CLI package, a dependency and a warm release build.
	 */
	private static final class Fixture
	{
		/**
		 * The pristine workspace.
		 */
		private final Path workspace;
		/**
		 * The environment of the check.
		 */
		private final Map<String, String> environment = new HashMap<>(System.getenv());
		/**
		 * The directory that retains the check's reports.
		 */
		private final Path evidence;

		/**
		 * Writes the workspace, makes it a Git repository, and builds it warm.
		 *
		 * @param directory                the fixture's root
		 * @param reportsCompileTimeCommit whether the CLI reports its compile-time commit; if false it reports
		 *                                 {@code unknown} instead
		 * @throws IOException if writing or a command fails
		 */
		private Fixture(TestDirectory directory, boolean reportsCompileTimeCommit) throws IOException
		{
			workspace = directory.getPath().resolve("workspace");
			evidence = directory.getPath().resolve("runner/baseline-evidence/cache-invalidation");
			directory.write("workspace/Cargo.toml", """
				[workspace]
				members = ["cli", "dependency"]
				resolver = "2"
				""");
			directory.write("workspace/cli/Cargo.toml", """
				[package]
				name = "codex-cli"
				version = "0.1.0"
				edition = "2021"
				[[bin]]
				name = "codex"
				path = "src/main.rs"
				[dependencies]
				cache-dependency = { path = "../dependency" }
				""");
			directory.write("workspace/dependency/Cargo.toml", """
				[package]
				name = "cache-dependency"
				version = "0.1.0"
				edition = "2021"
				""");
			directory.write("workspace/dependency/src/lib.rs", "pub fn commit(value: &str) -> &str { value }\n");
			String value = "option_env!(\"CODEX_BUILD_COMMIT\").unwrap_or(\"unknown\")";
			if (!reportsCompileTimeCommit)
				value = "\"unknown\"";
			directory.write("workspace/cli/src/main.rs", """
				const _: Option<&str> = option_env!("CODEX_BUILD_COMMIT");
				fn main() { println!("{{\\"checks\\":{{\\"runtime.provenance\\":{{\
				\\"id\\":\\"runtime.provenance\\",\\"details\\":\
				{{\\"commit\\":\\"{}\\"}}}}}}}}", cache_dependency::commit(VALUE)); }
				""".replace("VALUE", value));
			environment.remove("CODEX_BUILD_COMMIT");
			environment.remove("RUSTY_V8_SRC_BINDING_PATH");
			environment.put("CARGO_HOME", directory.createDirectories("cargo-home").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", directory.createDirectories("xdg").toString());
			environment.put("TMPDIR", directory.createDirectories("tmp").toString());
			environment.put("GIT_CEILING_DIRECTORIES", directory.getPath().toString());
			Path temporary = directory.getPath().resolve("tmp");
			command(List.of("git", "init", "--quiet"), temporary);
			command(List.of("git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit",
				"--quiet", "--allow-empty", "-m", "Fixture"), temporary);
			String host = command(List.of("rustc", "-vV"), temporary);
			String target = host.lines().filter(line -> line.startsWith("host: ")).findFirst().
				orElseThrow(() -> new IOException("rustc did not report its host target")).substring(6);
			environment.put("TARGET", target);
			environment.put("CARGO_TARGET_DIR", directory.getPath().resolve("target").toString());
			environment.put("RUNNER_TEMP", directory.getPath().resolve("runner").toString());
			command(List.of("cargo", "build", "--offline", "--release", "--target", target, "--bin", "codex"), temporary);
			directory.write("target/" + target + "/release/bwrap", "fixture helper digest input");
		}

		/**
		 * Runs the check in the workspace.
		 *
		 * @param scope shared tooling values
		 * @param out   receives the check's output
		 * @param err   receives the check's diagnostics
		 * @return the check's exit status
		 */
		private int run(DefaultToolingScope scope, ByteArrayOutputStream out, ByteArrayOutputStream err)
		{
			try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				return VerifyCodexCacheInvalidation.run(scope, new String[0], environment, workspace, outStream,
					errStream);
			}
		}

		/**
		 * Runs a command that must succeed in the workspace.
		 *
		 * @param command   the executable followed by its arguments
		 * @param temporary the directory for captured output
		 * @return the command's standard output
		 * @throws IOException if the command fails
		 */
		private String command(List<String> command, Path temporary) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command, workspace, temporary, environment);
			if (result.status() != 0)
				throw new IOException(command + " exited with " + result.status() + ": " + result.stderr());
			return result.stdout();
		}
	}
}
