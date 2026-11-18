package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.VerifyCodexCacheInvalidation;
import com.holdmyspot.codexunleashed.tooling.VerifyCodexCacheInvalidation.Processes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the cache-invalidation check's decisions on scripted Cargo and doctor results. The real Cargo behavior is
 * verified in {@link VerifyCodexCacheInvalidationCargoTest}.
 */
public final class VerifyCodexCacheInvalidationTest
{
	/**
	 * The commit that {@code git rev-parse HEAD} reports.
	 */
	private static final String COMMIT = "0123abc";
	/**
	 * The changed value of {@code CODEX_BUILD_COMMIT}.
	 */
	private static final String MARKER = "cache-invalidation-" + COMMIT;

	/**
	 * Creates the tests.
	 */
	public VerifyCodexCacheInvalidationTest()
	{
	}

	/**
	 * The three builds, the doctor report and the receipt are accepted when the CLI alone rebuilds for the changed input
	 * and reports it.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void acceptsCliOnlyRebuildOfChangedCommit() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			scenario.builds.add(scenario.build(false, false));
			Output output = scenario.run();

			requireThat(output.err, "err").isEqualTo("");
			requireThat(output.status, "status").isEqualTo(0);
			String receipt = "{\"target\": \"fixture-target\", \"original_build_commit\": null, " +
				"\"changed_provenance\": \"commit: " + MARKER + "\", \"non_cli_rebuilds\": 0, " +
				"\"changed_warm_rebuilds\": 0, \"status\": \"passed\"}";
			requireThat(output.out, "out").isEqualTo(String.join(System.lineSeparator(), "Checking original-warm",
				"Checking changed", "Checking changed-warm", receipt) + System.lineSeparator());
			requireThat(Files.readString(scenario.evidence().resolve("verification.json")), "receiptFile").isEqualTo("""
				{
				  "target": "fixture-target",
				  "original_build_commit": null,
				  "changed_provenance": "commit: %s",
				  "non_cli_rebuilds": 0,
				  "changed_warm_rebuilds": 0,
				  "status": "passed"
				}
				""".formatted(MARKER));
			requireThat(scenario.processes.environments.get(0).containsKey("CODEX_BUILD_COMMIT"), "originalCommitUnset").
				isFalse();
			requireThat(scenario.processes.environments.get(1).get("CODEX_BUILD_COMMIT"), "changedCommit").
				isEqualTo(MARKER);
			requireThat(scenario.processes.environments.get(1).get("STABLE_GIT_COMMIT"), "stableCommit").
				isEqualTo(COMMIT);
			requireThat(scenario.processes.environments.get(1).get("CODEX_BWRAP_SHA256"), "helperDigest").
				isEqualTo(Sha256.digest(scenario.helper));
			requireThat(scenario.processes.doctorHomeExisted, "doctorHome").isTrue();
			requireThat(Files.exists(scenario.processes.doctorHome), "doctorHomeRemoved").isFalse();
			requireThat(Files.exists(scenario.evidence().resolve("changed-doctor.json")), "doctorReport").isTrue();
		}
	}

	/**
	 * An original commit that was already set is passed to the first build and recorded in the receipt.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void recordsAnOriginalBuildCommit() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.environment.put("CODEX_BUILD_COMMIT", "original");
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			scenario.builds.add(scenario.build(false, false));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(0);
			requireThat(scenario.processes.environments.get(0).get("CODEX_BUILD_COMMIT"), "originalCommit").
				isEqualTo("original");
			requireThat(output.out, "out").contains("\"original_build_commit\": \"original\"");
		}
	}

	/**
	 * A rebuilt CLI that reports another commit than the changed input is rejected, and no receipt is written.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsStaleRuntimeProvenance() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.processes.doctorCommit = "unknown";
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("The running changed CLI does not report the diagnostic commit");
			requireThat(Files.exists(scenario.evidence().resolve("verification.json")), "receipt").isFalse();
		}
	}

	/**
	 * A rebuilt dependency of the CLI is rejected by name.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsRebuiltNonCliCompilations() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(true, true));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("changed: non-CLI compilations were rebuilt: [dependency]");
		}
	}

	/**
	 * Inputs that still rebuild the CLI are not warm enough to test invalidation.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsOriginalInputsThatAreNotWarm() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(scenario.build(false, true));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Original inputs are not warm");
			requireThat(scenario.processes.environments.size(), "builds").isEqualTo(1);
		}
	}

	/**
	 * A changed commit that leaves the CLI fresh is rejected.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsChangedInputWithoutCliRebuild() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, false));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Changing CODEX_BUILD_COMMIT did not rebuild the CLI");
		}
	}

	/**
	 * Repeating the changed input must not rebuild the CLI again.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsAWarmChangedBuildThatRebuilds() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			scenario.builds.add(scenario.build(false, true));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Repeating the changed input rebuilt CLI artifacts");
			requireThat(Files.exists(scenario.evidence().resolve("verification.json")), "receipt").isFalse();
		}
	}

	/**
	 * A doctor status other than zero or one is rejected and its log is retained.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsAnUnexpectedDoctorStatus() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.processes.doctorStatus = 2;
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("changed: doctor exited 2; inspect its retained log");
			requireThat(Files.exists(scenario.evidence().resolve("changed-doctor.log")), "log").isTrue();
		}
	}

	/**
	 * A doctor status of one, which reports a failed check, still gives a provenance report to read.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void acceptsDoctorStatusOne() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.processes.doctorStatus = 1;
			scenario.builds.add(scenario.build(false, false));
			scenario.builds.add(scenario.build(false, true));
			scenario.builds.add(scenario.build(false, false));
			requireThat(scenario.run().status, "status").isEqualTo(0);
		}
	}

	/**
	 * A failed Cargo build is reported with its retained log.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsAFailedCargoBuild() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(new Build(101, ""));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("original-warm: cargo build exited 101; inspect its retained log");
		}
	}

	/**
	 * A build that reports no Codex artifact is rejected.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsABuildWithoutACodexArtifact() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.builds.add(new Build(0, "{\"reason\":\"build-finished\",\"success\":true}\n"));
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("original-warm: expected one codex artifact, found 0");
		}
	}

	/**
	 * A diagnostic value that equals the original commit would prove nothing and is rejected before any build.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsOriginalCommitEqualToDiagnostic() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.environment.put("CODEX_BUILD_COMMIT", MARKER);
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("CODEX_BUILD_COMMIT already equals the diagnostic value");
			requireThat(scenario.processes.environments.size(), "builds").isEqualTo(0);
		}
	}

	/**
	 * Evidence from an earlier run is never overwritten.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsExistingEvidence() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			Files.createDirectories(scenario.evidence());
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("ERROR: ");
			requireThat(scenario.processes.environments.size(), "builds").isEqualTo(0);
		}
	}

	/**
	 * A missing required variable is named.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsAMissingTarget() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			scenario.environment.remove("TARGET");
			Output output = scenario.run();

			requireThat(output.status, "status").isEqualTo(1);
			requireThat(output.err, "err").contains("Environment variable TARGET is not set");
		}
	}

	/**
	 * The command takes no arguments.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void rejectsArguments() throws IOException
	{
		try (Scenario scenario = new Scenario())
		{
			Output output = scenario.run("unexpected");

			requireThat(output.status, "status").isEqualTo(2);
			requireThat(output.err, "err").contains("Usage: codex-tooling verify-codex-cache-invalidation");
		}
	}

	/**
	 * A command's status and printed streams.
	 *
	 * @param status the exit status
	 * @param out    the standard output
	 * @param err    the standard error
	 */
	private record Output(int status, String out, String err)
	{
	}

	/**
	 * A scripted Cargo build.
	 *
	 * @param status   the exit status
	 * @param messages the JSON messages that Cargo prints
	 */
	private record Build(int status, String messages)
	{
	}

	/**
	 * A fixture workspace with scripted builds.
	 */
	private static final class Scenario implements AutoCloseable
	{
		/**
		 * The fixture's root.
		 */
		private final TestDirectory directory;
		/**
		 * The pristine {@code codex-rs} directory.
		 */
		private final Path workspace;
		/**
		 * The helper binary whose digest the builds receive.
		 */
		private final Path helper;
		/**
		 * The process environment.
		 */
		private final Map<String, String> environment = new HashMap<>();
		/**
		 * The scripted builds, in order.
		 */
		private final Deque<Build> builds = new ArrayDeque<>();
		/**
		 * The scripted processes.
		 */
		private final FakeProcesses processes = new FakeProcesses(builds);

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the fixture cannot be created
		 */
		private Scenario() throws IOException
		{
			directory = new TestDirectory("cache-invalidation-");
			workspace = directory.getPath().resolve("workspace");
			directory.write("workspace/cli/Cargo.toml", "[package]\n");
			helper = directory.write("target/fixture-target/release/bwrap", "helper");
			environment.put("TARGET", "fixture-target");
			environment.put("CARGO_TARGET_DIR", directory.getPath().resolve("target").toString());
			environment.put("RUNNER_TEMP", directory.getPath().resolve("runner").toString());
		}

		/**
		 * Returns the directory that retains the check's reports.
		 *
		 * @return the directory
		 */
		private Path evidence()
		{
			return directory.getPath().resolve("runner/baseline-evidence/cache-invalidation");
		}

		/**
		 * Describes a build.
		 *
		 * @param dependencyRebuilt whether Cargo rebuilt a dependency of the CLI
		 * @param cliRebuilt        whether Cargo rebuilt the CLI
		 * @return the build
		 * @throws IOException if the CLI manifest's real path cannot be read
		 */
		private Build build(boolean dependencyRebuilt, boolean cliRebuilt) throws IOException
		{
			String manifest = workspace.resolve("cli/Cargo.toml").toRealPath().toString();
			String dependencyManifest = workspace.resolve("dependency/Cargo.toml").toString();
			return new Build(0, artifact("dependency", dependencyManifest, !dependencyRebuilt) + "\n" +
				"{\"reason\":\"build-script-executed\",\"package_id\":\"x\"}\n" +
				artifact("codex", manifest, !cliRebuilt) + "\n{\"reason\":\"build-finished\",\"success\":true}\n");
		}

		/**
		 * Runs the command.
		 *
		 * @param arguments the command arguments
		 * @return the status and the printed streams
		 */
		private Output run(String... arguments)
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			int status;
			try (DefaultToolingScope scope = new DefaultToolingScope();
				PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
				PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8))
			{
				status = VerifyCodexCacheInvalidation.run(scope, arguments, environment, workspace, processes, outStream,
					errStream);
			}
			return new Output(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
		}

		/**
		 * Describes one Cargo artifact message.
		 *
		 * @param name     the target name
		 * @param manifest the package's manifest path
		 * @param fresh    whether Cargo reused the artifact
		 * @return the JSON line
		 */
		private static String artifact(String name, String manifest, boolean fresh)
		{
			return "{\"reason\":\"compiler-artifact\",\"target\":{\"name\":\"" + name + "\"},\"manifest_path\":\"" +
				manifest.replace("\\", "\\\\") + "\",\"fresh\":" + fresh + "}";
		}

		@Override
		public void close() throws IOException
		{
			directory.close();
		}
	}

	/**
	 * Answers Cargo, Git and the CLI with scripted results.
	 */
	private static final class FakeProcesses implements Processes
	{
		/**
		 * The scripted builds, in order.
		 */
		private final Deque<Build> builds;
		/**
		 * The environment of each Cargo build, in order.
		 */
		private final List<Map<String, String>> environments = new ArrayList<>();
		/**
		 * The commit that the doctor report names.
		 */
		private String doctorCommit = MARKER;
		/**
		 * The doctor command's exit status.
		 */
		private int doctorStatus;
		/**
		 * The doctor command's {@code CODEX_HOME}.
		 */
		private Path doctorHome;
		/**
		 * Whether the doctor command's {@code CODEX_HOME} existed while it ran.
		 */
		private boolean doctorHomeExisted;

		/**
		 * Creates processes.
		 *
		 * @param builds the scripted builds
		 */
		private FakeProcesses(Deque<Build> builds)
		{
			this.builds = builds;
		}

		@Override
		public int run(List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout,
			Path stderr) throws IOException
		{
			if (command.getFirst().equals("cargo"))
			{
				environments.add(environment);
				Build build = builds.removeFirst();
				Files.writeString(stdout, build.messages());
				Files.writeString(stderr, "");
				return build.status();
			}
			doctorHome = Path.of(environment.get("CODEX_HOME"));
			doctorHomeExisted = Files.isDirectory(doctorHome);
			Files.writeString(stdout, "{\"checks\":{\"runtime.provenance\":{\"details\":{\"commit\":\"" + doctorCommit +
				"\"}}}}");
			Files.writeString(stderr, "doctor log");
			return doctorStatus;
		}

		@Override
		public String output(List<String> command, Path workingDirectory)
		{
			return COMMIT + "\n";
		}
	}
}
