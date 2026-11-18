package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the {@code verify-workspace-lock-versions} command through {@link Main#run}, with a real Git repository as
 * the source of the baseline.
 */
public final class WorkspaceLockVersionsCommandTest
{
	/** The command name. */
	private static final String COMMAND = "verify-workspace-lock-versions";
	/** The complete usage diagnostic, without its line terminator. */
	private static final String USAGE = "Usage: codex-tooling " + COMMAND + " <repository-root>";
	/** Workspace manifest whose release version is 1.2.3. */
	private static final String MANIFEST =
		"[workspace]\nmembers = [\"alpha\"]\n\n[workspace.package]\nversion = \"1.2.3\"\n";
	/** The lockfile at the development version. */
	private static final String BEFORE_LOCK = lock("0.0.0", "00");
	/** The lockfile at the release version. */
	private static final String AFTER_LOCK = lock("1.2.3", "00");

	/** Constructs independent fixtures for each check. */
	public WorkspaceLockVersionsCommandTest()
	{
	}

	/**
	 * Accepts a working tree whose lockfile differs from the committed one only in the workspace version, printing
	 * one success line and nothing on standard error.
	 *
	 * @throws IOException if fixture storage or Git fails
	 */
	@Test
	public void acceptsWorkspaceVersionChanges() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-command-"))
		{
			writeRepository(fixture, AFTER_LOCK);

			CommandResult result = run(COMMAND, fixture.getPath().toString());

			requireThat(result.status(), "actualStatus").isEqualTo(0);
			requireThat(result.output(), "actualOutput").isEqualTo(
				"Verified 1 workspace versions; external dependencies unchanged" + System.lineSeparator());
			requireThat(result.error(), "actualError").isEmpty();
		}
	}

	/**
	 * Prints the one diagnostic of the difference and nothing on standard output.
	 *
	 * @param name       the case
	 * @param afterLock  the working-tree lockfile
	 * @param diagnostic the expected diagnostic
	 * @throws IOException if fixture storage or Git fails
	 */
	@Test(dataProvider = "differences")
	public void reportsPolicyDifference(String name, String afterLock, String diagnostic) throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-command-"))
		{
			writeRepository(fixture, afterLock);

			CommandResult result = run(COMMAND, fixture.getPath().toString());

			requireThat(result.status(), name + "Status").isEqualTo(1);
			requireThat(result.output(), name + "Output").isEmpty();
			requireThat(result.error(), name + "Error").isEqualTo(diagnostic + System.lineSeparator());
		}
	}

	/**
	 * Supplies working-tree lockfiles that differ in each way.
	 *
	 * @return the case names, lockfiles and diagnostics
	 */
	@DataProvider(name = "differences")
	public Object[][] differences()
	{
		return new Object[][]
		{
			{"metadata", AFTER_LOCK.replace("version = 4", "version = 3"),
				"Cargo changed lockfile metadata beyond workspace versions"},
			{"external", lock("1.2.3", "11"), "Cargo changed external dependency records"},
			{"workspace", lock("9.9.9", "00"), "Cargo changed workspace records beyond their release version"}
		};
	}

	/**
	 * Fails with the command name, the Git operation, its numeric status and its standard error when Git cannot read
	 * the baseline, without printing a success line.
	 *
	 * @throws IOException if fixture storage or Git fails
	 */
	@Test
	public void reportsGitFailure() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-command-"))
		{
			fixture.write("codex-rs/Cargo.toml", MANIFEST);
			fixture.write("codex-rs/Cargo.lock", AFTER_LOCK);
			git(fixture, "init", "-q");

			CommandResult result = run(COMMAND, fixture.getPath().toString());

			requireThat(result.status(), "actualStatus").isEqualTo(1);
			requireThat(result.output(), "actualOutput").isEmpty();
			requireThat(result.error(), "actualError").startsWith(COMMAND + ": git show HEAD:codex-rs/Cargo.lock").
				contains("status 128");
		}
	}

	/**
	 * Rejects a missing argument, a second argument and the help options with the usage line and status two.
	 *
	 * @param arguments the command arguments after the command name
	 */
	@Test(dataProvider = "usageArguments")
	public void rejectsCommandUsage(List<String> arguments)
	{
		List<String> command = new ArrayList<>();
		command.add(COMMAND);
		command.addAll(arguments);

		CommandResult result = run(command.toArray(new String[0]));

		requireThat(result.status(), "actualStatus").isEqualTo(2);
		requireThat(result.output(), "actualOutput").isEmpty();
		requireThat(result.error(), "actualError").isEqualTo(USAGE + System.lineSeparator());
	}

	/**
	 * Supplies arguments that the command rejects as invalid usage.
	 *
	 * @return the arguments after the command name
	 */
	@DataProvider(name = "usageArguments")
	public Object[][] usageArguments()
	{
		return new Object[][]
		{
			{List.of()},
			{List.of("-h")},
			{List.of("--help")},
			{List.of("first", "second")},
			{List.of("--help", "root")}
		};
	}

	/**
	 * Leaves the caller's output and error streams open, with nothing buffered in them, after a success, a policy
	 * difference and a usage error.
	 *
	 * @throws IOException if fixture storage or Git fails
	 */
	@Test
	public void keepsBorrowedStreamsOpen() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("lock-versions-command-");
			ClosureRecorder output = new ClosureRecorder();
			ClosureRecorder error = new ClosureRecorder();
			PrintStream out = new PrintStream(output, false, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, false, StandardCharsets.UTF_8))
		{
			writeRepository(fixture, AFTER_LOCK);
			Path root = fixture.getPath();

			int successStatus = Main.run(new String[]{COMMAND, root.toString()}, new ByteArrayInputStream(new byte[0]),
				out, err, Map.of());
			int usageStatus = Main.run(new String[]{COMMAND}, new ByteArrayInputStream(new byte[0]), out, err, Map.of());

			requireThat(successStatus, "successStatus").isEqualTo(0);
			requireThat(usageStatus, "usageStatus").isEqualTo(2);
			requireThat(output.closed, "outputClosed").isFalse();
			requireThat(error.closed, "errorClosed").isFalse();
			requireThat(out.checkError(), "outputFailed").isFalse();
			requireThat(err.checkError(), "errorFailed").isFalse();
			out.flush();
			err.flush();
			requireThat(output.toString(StandardCharsets.UTF_8), "actualOutput").isEqualTo(
				"Verified 1 workspace versions; external dependencies unchanged" + System.lineSeparator());
			requireThat(error.toString(StandardCharsets.UTF_8), "actualError").
				isEqualTo(USAGE + System.lineSeparator());
		}
	}

	/**
	 * Builds a lockfile with one workspace package and one external package.
	 *
	 * @param version  the workspace package version
	 * @param checksum the external package checksum
	 * @return the lockfile
	 */
	private static String lock(String version, String checksum)
	{
		return "version = 4\n\n[[package]]\nname = \"alpha\"\nversion = \"" + version + "\"\n\n" +
			"[[package]]\nname = \"serde\"\nversion = \"1.0.0\"\n" +
			"source = \"registry+https://github.com/rust-lang/crates.io-index\"\nchecksum = \"" + checksum + "\"\n";
	}

	/**
	 * Creates a repository whose HEAD commit holds the development lockfile and the manifest, then writes the
	 * candidate lockfile into the working tree.
	 *
	 * @param fixture   the repository directory
	 * @param afterLock the working-tree lockfile
	 * @throws IOException if a file cannot be written or Git fails
	 */
	private static void writeRepository(TestDirectory fixture, String afterLock) throws IOException
	{
		fixture.write("codex-rs/Cargo.toml", MANIFEST);
		fixture.write("codex-rs/Cargo.lock", BEFORE_LOCK);
		git(fixture, "init", "-q");
		git(fixture, "add", "-A");
		git(fixture, "-c", "user.name=Queue Author", "-c", "user.email=queue@example.com", "-c", "commit.gpgsign=false",
			"commit", "-q", "-m", "queue");
		fixture.write("codex-rs/Cargo.lock", afterLock);
	}

	/**
	 * Runs Git in the fixture.
	 *
	 * @param fixture   the working directory
	 * @param arguments Git arguments
	 * @throws IOException if Git cannot run or fails
	 */
	private static void git(TestDirectory fixture, String... arguments) throws IOException
	{
		List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		SystemCommands.Result result = SystemCommands.capture(command, fixture.getPath(),
			Path.of(System.getProperty("java.io.tmpdir")));
		if (result.status() != 0)
			throw new IOException("git " + String.join(" ", arguments) + " failed: " + result.stderr());
	}

	/**
	 * Runs the command entry point with fresh streams that this method closes.
	 *
	 * @param arguments the command and its arguments
	 * @return the status and the text written to each stream
	 */
	private static CommandResult run(String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			return new CommandResult(status, output.toString(StandardCharsets.UTF_8),
				error.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * The outcome of one command invocation.
	 *
	 * @param status exit status
	 * @param output text written to standard output
	 * @param error  text written to standard error
	 */
	private record CommandResult(int status, String output, String error)
	{
	}

	/**
	 * A byte sink that records whether it was closed.
	 */
	private static final class ClosureRecorder extends ByteArrayOutputStream
	{
		/** Whether the sink was closed. */
		private boolean closed;

		/** Constructs an open sink. */
		private ClosureRecorder()
		{
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}
}
