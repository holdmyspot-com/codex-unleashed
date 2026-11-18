package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.io.file.PathUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Checks that a changed {@code CODEX_BUILD_COMMIT} rebuilds the Codex CLI after a successful cached release build.
 * <p>
 * The command runs from a pristine {@code codex-rs} directory with {@code TARGET}, {@code CARGO_TARGET_DIR} and
 * {@code RUNNER_TEMP} set, and needs warm Codex outputs and the Linux {@code bwrap} helper. It builds the original
 * inputs, which must rebuild nothing, then builds with a changed {@code CODEX_BUILD_COMMIT}, which must rebuild the CLI
 * and nothing else. The changed CLI's {@code doctor} report must name the changed commit, and repeating the changed
 * build must rebuild nothing. Reports remain under {@code baseline-evidence}. Changed outputs must never be published
 * to a shared cache. A failed check leaves diagnostic outputs in the owned Cargo directory.
 */
public final class VerifyCodexCacheInvalidation
{
	/**
	 * The command name.
	 */
	public static final String NAME = "verify-codex-cache-invalidation";

	/**
	 * The Cargo message that announces a compilation's artifact.
	 */
	private static final String ARTIFACT_REASON = "compiler-artifact";
	/**
	 * The name of the binary that the check builds.
	 */
	private static final String BINARY = "codex";

	/**
	 * Runs the processes that the check needs.
	 */
	public interface Processes
	{
		/**
		 * Runs a command to completion with a complete environment and with its output written to files.
		 *
		 * @param command          the executable followed by its arguments
		 * @param workingDirectory the command's working directory
		 * @param environment      the command's complete environment
		 * @param stdout           the file that receives standard output
		 * @param stderr           the file that receives standard error
		 * @return the command's exit status, including a nonzero one
		 * @throws NullPointerException if an argument or element is null
		 * @throws IOException          if the command cannot run or its output cannot be written
		 */
		int run(List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout,
			Path stderr) throws IOException;

		/**
		 * Runs a command that must succeed and returns its standard output.
		 *
		 * @param command          the executable followed by its arguments
		 * @param workingDirectory the command's working directory
		 * @return the standard output
		 * @throws NullPointerException if an argument or element is null
		 * @throws IOException          if the command cannot run or exits unsuccessfully
		 */
		String output(List<String> command, Path workingDirectory) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private VerifyCodexCacheInvalidation()
	{
	}

	/**
	 * Runs the check in the working directory with the real Cargo and the built CLI.
	 *
	 * @param scope       shared tooling values borrowed until the command finishes
	 * @param arguments   none
	 * @param environment the process environment, which must set {@code TARGET}, {@code CARGO_TARGET_DIR} and
	 *                    {@code RUNNER_TEMP}
	 * @param out         progress and the receipt
	 * @param err         diagnostics
	 * @return zero if every check passed, one if a check or a process failed, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment, PrintStream out,
		PrintStream err)
	{
		return run(scope, arguments, environment, Path.of("").toAbsolutePath(), out, err);
	}

	/**
	 * Runs the check in a directory with the real Cargo and the built CLI.
	 *
	 * @param scope            shared tooling values borrowed until the command finishes
	 * @param arguments        none
	 * @param environment      the process environment, which must set {@code TARGET}, {@code CARGO_TARGET_DIR} and
	 *                         {@code RUNNER_TEMP}
	 * @param workingDirectory the pristine {@code codex-rs} directory
	 * @param out              progress and the receipt
	 * @param err              diagnostics
	 * @return zero if every check passed, one if a check or a process failed, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment,
		Path workingDirectory, PrintStream out, PrintStream err)
	{
		return run(scope, arguments, environment, workingDirectory, new SystemProcesses(), out, err);
	}

	/**
	 * Runs the check.
	 *
	 * @param scope            shared tooling values borrowed until the command finishes
	 * @param arguments        none
	 * @param environment      the process environment, which must set {@code TARGET}, {@code CARGO_TARGET_DIR} and
	 *                         {@code RUNNER_TEMP}
	 * @param workingDirectory the pristine {@code codex-rs} directory
	 * @param processes        runs Cargo, Git and the built CLI
	 * @param out              progress and the receipt
	 * @param err              diagnostics
	 * @return zero if every check passed, one if a check or a process failed, or two for invalid command arguments
	 * @throws NullPointerException if any argument is null
	 */
	public static int run(ToolingScope scope, String[] arguments, Map<String, String> environment,
		Path workingDirectory, Processes processes, PrintStream out, PrintStream err)
	{
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(arguments, "arguments");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(workingDirectory, "workingDirectory");
		Objects.requireNonNull(processes, "processes");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");

		if (arguments.length != 0)
		{
			err.println("Usage: codex-tooling " + NAME);
			return 2;
		}

		try
		{
			verify(scope, environment, workingDirectory, processes, out);
			return 0;
		}
		catch (IOException | IllegalArgumentException | IllegalStateException | JacksonException failure)
		{
			err.println("ERROR: " + failure.getMessage());
			return 1;
		}
	}

	/**
	 * Runs the three builds and the doctor report, and records the receipt.
	 *
	 * @param scope            shared tooling values
	 * @param environment      the process environment
	 * @param workingDirectory the pristine {@code codex-rs} directory
	 * @param processes        runs Cargo, Git and the built CLI
	 * @param out              progress and the receipt
	 * @throws IllegalArgumentException if a required variable is unset
	 * @throws IllegalStateException    if a check fails
	 * @throws IOException              if a process fails or an output cannot be read or written
	 */
	private static void verify(ToolingScope scope, Map<String, String> environment, Path workingDirectory,
		Processes processes, PrintStream out) throws IOException
	{
		String target = required(environment, "TARGET");
		Path output = Path.of(required(environment, "CARGO_TARGET_DIR")).resolve(target).resolve("release");
		String helperDigest = Sha256.digest(output.resolve("bwrap"));
		Path evidence = Path.of(required(environment, "RUNNER_TEMP")).resolve("baseline-evidence").
			resolve("cache-invalidation");
		Files.createDirectories(evidence.getParent());
		Files.createDirectory(evidence);
		Path cliManifest = resolve(workingDirectory.resolve("cli").resolve("Cargo.toml"));
		Map<String, String> inputs = new HashMap<>(environment);
		inputs.put("CODEX_BWRAP_SHA256", helperDigest);
		String commit = processes.output(List.of("git", "rev-parse", "HEAD"), workingDirectory).strip();
		inputs.put("STABLE_GIT_COMMIT", commit);
		String originalCommit = environment.get("CODEX_BUILD_COMMIT");
		String marker = "cache-invalidation-" + commit;
		if (marker.equals(originalCommit))
			throw new IllegalStateException("CODEX_BUILD_COMMIT already equals the diagnostic value");

		Build builder = new Build(scope, processes, inputs, target, workingDirectory, cliManifest, evidence, out);
		if (builder.run("original-warm", originalCommit) != 0)
		{
			throw new IllegalStateException(
				"Original inputs are not warm; inspect original-warm artifacts before testing invalidation");
		}
		if (builder.run("changed", marker) == 0)
			throw new IllegalStateException("Changing CODEX_BUILD_COMMIT did not rebuild the CLI");
		if (!doctor("changed", processes, inputs, output, workingDirectory, evidence, scope).equals("commit: " + marker))
			throw new IllegalStateException("The running changed CLI does not report the diagnostic commit");
		if (builder.run("changed-warm", marker) != 0)
			throw new IllegalStateException("Repeating the changed input rebuilt CLI artifacts");

		Map<String, Object> receipt = new LinkedHashMap<>();
		receipt.put("target", target);
		receipt.put("original_build_commit", originalCommit);
		receipt.put("changed_provenance", "commit: " + marker);
		receipt.put("non_cli_rebuilds", 0);
		receipt.put("changed_warm_rebuilds", 0);
		receipt.put("status", "passed");
		Files.writeString(evidence.resolve("verification.json"), ArtifactJson.formatPreservingKeyOrder(scope, receipt));
		out.println(compactJson(scope, receipt));
	}

	/**
	 * Runs the built CLI's doctor command with an isolated temporary {@code CODEX_HOME}.
	 * <p>
	 * The JSON report and standard error remain in the evidence directory under the label. The temporary home is removed.
	 *
	 * @param label            names the retained files
	 * @param processes        runs the CLI
	 * @param inputs           the build environment
	 * @param output           the directory that holds the built CLI
	 * @param workingDirectory the working directory
	 * @param evidence         the evidence directory
	 * @param scope            shared tooling values
	 * @return the runtime provenance's commit, prefixed with {@code commit: }
	 * @throws IllegalStateException if the command exits with a status other than zero or one
	 * @throws IOException           if the command cannot run or its report cannot be read or has no provenance commit
	 */
	private static String doctor(String label, Processes processes, Map<String, String> inputs, Path output,
		Path workingDirectory, Path evidence, ToolingScope scope) throws IOException
	{
		Path report = evidence.resolve(label + "-doctor.json");
		Path home = Files.createTempDirectory(evidence, "doctor-home-");
		int status;
		try
		{
			Map<String, String> doctorEnvironment = new HashMap<>(inputs);
			doctorEnvironment.put("CODEX_HOME", home.toString());
			status = processes.run(List.of(output.resolve(BINARY).toString(), "doctor", "--json"), workingDirectory,
				doctorEnvironment, report, evidence.resolve(label + "-doctor.log"));
		}
		finally
		{
			PathUtils.deleteDirectory(home);
		}
		if (status != 0 && status != 1)
			throw new IllegalStateException(label + ": doctor exited " + status + "; inspect its retained log");
		JsonNode commit = scope.getJsonMapper().readTree(Files.readString(report)).path("checks").
			path("runtime.provenance").path("details").path("commit");
		if (!commit.isString())
			throw new IOException(label + ": the doctor report has no runtime.provenance commit");
		return "commit: " + commit.stringValue();
	}

	/**
	 * Returns a required environment variable.
	 *
	 * @param environment the environment
	 * @param name        the variable
	 * @return the value
	 * @throws IllegalArgumentException if the variable is unset
	 */
	private static String required(Map<String, String> environment, String name)
	{
		String value = environment.get(name);
		if (value == null)
			throw new IllegalArgumentException("Environment variable " + name + " is not set");
		return value;
	}

	/**
	 * Resolves a path through symbolic links, or returns it absolute and normalized if it does not exist.
	 *
	 * @param path the path
	 * @return the resolved path
	 */
	private static Path resolve(Path path)
	{
		try
		{
			return path.toRealPath();
		}
		catch (IOException _)
		{
			return path.toAbsolutePath().normalize();
		}
	}

	/**
	 * Formats a flat document on one line with a space after each comma and colon.
	 *
	 * @param scope    shared tooling values
	 * @param document the document, whose keys are plain names
	 * @return the one-line JSON
	 */
	private static String compactJson(ToolingScope scope, Map<String, Object> document)
	{
		List<String> members = new ArrayList<>();
		for (Map.Entry<String, Object> member : document.entrySet())
		{
			members.add(scope.getArtifactJsonMapper().writeValueAsString(member.getKey()) + ": " +
				scope.getArtifactJsonMapper().writeValueAsString(member.getValue()));
		}
		return "{" + String.join(", ", members) + "}";
	}

	/**
	 * Builds the Codex binary with a compile-time commit and inspects Cargo's report.
	 */
	private static final class Build
	{
		/**
		 * Shared tooling values.
		 */
		private final ToolingScope scope;
		/**
		 * Runs Cargo.
		 */
		private final Processes processes;
		/**
		 * The build environment.
		 */
		private final Map<String, String> inputs;
		/**
		 * The Rust target triple.
		 */
		private final String target;
		/**
		 * The Cargo workspace.
		 */
		private final Path workingDirectory;
		/**
		 * The resolved manifest of the CLI package.
		 */
		private final Path cliManifest;
		/**
		 * The directory that retains each build's reports.
		 */
		private final Path evidence;
		/**
		 * Progress.
		 */
		private final PrintStream out;

		/**
		 * Creates a builder.
		 *
		 * @param scope            shared tooling values
		 * @param processes        runs Cargo
		 * @param inputs           the build environment
		 * @param target           the Rust target triple
		 * @param workingDirectory the Cargo workspace
		 * @param cliManifest      the resolved manifest of the CLI package
		 * @param evidence         the directory that retains each build's reports
		 * @param out              progress
		 */
		private Build(ToolingScope scope, Processes processes, Map<String, String> inputs, String target,
			Path workingDirectory, Path cliManifest, Path evidence, PrintStream out)
		{
			this.scope = scope;
			this.processes = processes;
			this.inputs = inputs;
			this.target = target;
			this.workingDirectory = workingDirectory;
			this.cliManifest = cliManifest;
			this.evidence = evidence;
			this.out = out;
		}

		/**
		 * Builds Codex with the supplied compile-time commit and counts the rebuilt artifacts.
		 * <p>
		 * Cargo's JSON messages and standard error remain in the evidence directory under the label.
		 *
		 * @param label  names the retained files and the check in failure messages
		 * @param commit the value of {@code CODEX_BUILD_COMMIT}, or null to remove the variable
		 * @return the number of artifacts that Cargo rebuilt; all belong to the CLI package
		 * @throws IllegalStateException if Cargo does not report exactly one Codex artifact, or rebuilds an artifact
		 *                               outside the CLI package
		 * @throws IOException           if Cargo cannot run, exits unsuccessfully, or reports unreadable messages
		 */
		private int run(String label, String commit) throws IOException
		{
			Map<String, String> environment = new HashMap<>(inputs);
			if (commit == null)
				environment.remove("CODEX_BUILD_COMMIT");
			else
				environment.put("CODEX_BUILD_COMMIT", commit);
			List<String> command = List.of("cargo", "build", "--target", target, "--release", "--timings", "--bin",
				BINARY, "--message-format=json-render-diagnostics");
			out.println("Checking " + label);
			out.flush();
			Path messages = evidence.resolve(label + ".jsonl");
			int status = processes.run(command, workingDirectory, environment, messages, evidence.resolve(label + ".log"));
			if (status != 0)
				throw new IOException(label + ": cargo build exited " + status + "; inspect its retained log");

			int binaries = 0;
			int rebuiltCount = 0;
			List<String> dependencies = new ArrayList<>();
			for (String line : Files.readAllLines(messages))
			{
				JsonNode message = scope.getJsonMapper().readTree(line);
				if (!message.path("reason").isString() || !message.path("reason").stringValue().equals(ARTIFACT_REASON))
					continue;
				String name = message.path("target").path("name").asString("");
				if (name.equals(BINARY))
					++binaries;
				JsonNode fresh = message.path("fresh");
				if (fresh.isBoolean() && !fresh.booleanValue())
				{
					++rebuiltCount;
					if (!resolve(Path.of(message.path("manifest_path").asString(""))).equals(cliManifest))
						dependencies.add(name);
				}
			}
			if (binaries != 1)
				throw new IllegalStateException(label + ": expected one codex artifact, found " + binaries);
			if (!dependencies.isEmpty())
				throw new IllegalStateException(label + ": non-CLI compilations were rebuilt: " + dependencies);
			return rebuiltCount;
		}
	}

	/**
	 * Runs the real processes.
	 */
	private static final class SystemProcesses implements Processes
	{
		/**
		 * The parent of the directories that hold a command's captured output.
		 */
		private final Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));

		/**
		 * Creates the real processes.
		 */
		private SystemProcesses()
		{
		}

		@Override
		public int run(List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout,
			Path stderr) throws IOException
		{
			ProcessBuilder builder = SystemCommands.createBuilder(command).directory(workingDirectory.toFile()).
				redirectInput(ProcessBuilder.Redirect.from(ProcessBuilder.Redirect.DISCARD.file())).
				redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
			builder.environment().clear();
			builder.environment().putAll(environment);
			try (Process process = builder.start())
			{
				try
				{
					return process.waitFor();
				}
				catch (InterruptedException failure)
				{
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while waiting for " + command.getFirst(), failure);
				}
			}
		}

		@Override
		public String output(List<String> command, Path workingDirectory) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(command, workingDirectory, temporaryDirectory);
			if (result.status() != 0)
			{
				throw new IOException(command.getFirst() + " failed with status " + result.status() + ": " +
					result.stderr().strip());
			}
			return result.stdout();
		}
	}
}
