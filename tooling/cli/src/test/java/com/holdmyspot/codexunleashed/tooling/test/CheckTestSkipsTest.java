package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CheckTestSkips;
import com.holdmyspot.codexunleashed.tooling.TestReports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies first-applicable skip policy and report stream collection. */
public final class CheckTestSkipsTest
{
	/** Creates method-owned fixtures. */
	public CheckTestSkipsTest()
	{
	}

	/** Rejects unknown skips and applies platform, runner and environment scopes exactly. */
	@Test public void classifiesScopedAndUnknownSkips()
	{
		var remote = new CheckTestSkips.Skip(Path.of("junit.xml"), "test",
			"Skipping test because it requires a remote test environment.");
		requireThat(CheckTestSkips.classify(remote, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.REMOTE).verdict(), "remoteLinux").isEqualTo(CheckTestSkips.Verdict.MISSING_SETUP);
		requireThat(CheckTestSkips.classify(remote, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.LOCAL).verdict(), "localLinux").isEqualTo(CheckTestSkips.Verdict.ALLOWED);
		requireThat(CheckTestSkips.classify(remote, CheckTestSkips.Platform.WINDOWS, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.REMOTE).verdict(), "windows").isEqualTo(CheckTestSkips.Verdict.ALLOWED);
		var newSkip = new CheckTestSkips.Skip(Path.of("junit.xml"), "test", "skipping something new: frobnicator missing");
		requireThat(CheckTestSkips.classify(newSkip, CheckTestSkips.Platform.LINUX,
			CheckTestSkips.Runner.NEXTEST, CheckTestSkips.Environment.REMOTE).verdict(),
			"newSkipFailsClosed").isEqualTo(CheckTestSkips.Verdict.UNCLASSIFIED);
		for (String line : List.of("skipping bwrap test: bubblewrap is unavailable",
			"skipping private tmp test: user/mount namespaces are unavailable", "python3 not found in PATH, skipping test."))
			requireThat(CheckTestSkips.classify(new CheckTestSkips.Skip(Path.of("junit.xml"), "test", line),
				CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST, CheckTestSkips.Environment.LOCAL).verdict(),
				"linuxMissingSetup").isEqualTo(CheckTestSkips.Verdict.MISSING_SETUP);
		var wsl = new CheckTestSkips.Skip(Path.of("junit.xml"), "test",
			"skipping WSL root test: bwrap sandbox prerequisites are unavailable");
		requireThat(CheckTestSkips.classify(wsl, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.REMOTE).verdict(), "wslPrecedesBwrap").isEqualTo(CheckTestSkips.Verdict.ALLOWED);
	}

	/** The checker rejects missing Windows tools despite skip wording naming ConPTY. */
	@Test public void rejectsMissingWindowsTools()
	{
		for (String line : List.of("python not found; skipping ConPTY input test",
			"PowerShell 7 is not installed; skipping test"))
		{
			var skip = new CheckTestSkips.Skip(Path.of("junit.xml"), "test", line);
			requireThat(CheckTestSkips.classify(skip, CheckTestSkips.Platform.WINDOWS,
				CheckTestSkips.Runner.NEXTEST, CheckTestSkips.Environment.REMOTE).verdict(), "windowsSetup").
				isEqualTo(CheckTestSkips.Verdict.MISSING_SETUP);
		}
	}

	/** The checker rejects bind failures while preserving a genuine group limitation. */
	@Test public void rejectsUnavailableBindResources()
	{
		for (String line : List.of("address already in use; no alternate supplementary group is available",
			"port is already in use", "failed to bind unix socket", "WSL test skipped because failed to bind unix socket"))
		{
			var skip = new CheckTestSkips.Skip(Path.of("junit.xml"), "test", line);
			requireThat(CheckTestSkips.classify(skip, CheckTestSkips.Platform.LINUX,
				CheckTestSkips.Runner.NEXTEST, CheckTestSkips.Environment.REMOTE).verdict(), "bindSetup").
				isEqualTo(CheckTestSkips.Verdict.MISSING_SETUP);
		}
		var supplementalGroup = new CheckTestSkips.Skip(Path.of("junit.xml"), "test",
			"no alternate supplementary group is available");
		requireThat(CheckTestSkips.classify(supplementalGroup, CheckTestSkips.Platform.LINUX,
			CheckTestSkips.Runner.NEXTEST, CheckTestSkips.Environment.REMOTE).verdict(), "supplementalGroup").
			isEqualTo(CheckTestSkips.Verdict.ALLOWED);
	}

	/**
	 * Writes the same classified sections to stdout and the explicitly supplied fenced summary file.
	 *
	 * @throws IOException if fixture or summary file IO fails
	 */
	@Test public void commandWritesExplicitSummary() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path xml = fixture.write(fixture.root.resolve("junit.xml"),
				"<testsuites><testsuite><testcase name='a' classname='bin'>" +
				"<system-out>skipping zsh test: /bin/zsh is unavailable</system-out></testcase></testsuite></testsuites>");
			Path summary = fixture.root.resolve("summary.md");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				int status = CheckTestSkips.run(new String[]{"--platform", "linux", xml.toString()}, summary, out, err);
				requireThat(status, "missingSetupFails").isEqualTo(1);
			}
			requireThat(output.toString(StandardCharsets.UTF_8), "legacyStdout").contains(
				"Scanned 1 report file(s); found 1 skip message(s)." + System.lineSeparator() + "[missing setup] x1");
			requireThat(Files.readString(summary), "explicitSummary").isEqualTo("### Test skip check\n\n```\n" +
				"Scanned 1 report file(s); found 1 skip message(s).\n" +
				"[missing setup] x1 skipping zsh test: /bin/zsh is unavailable\n" +
				"    reason: zsh is not installed\n" +
				"    e.g. bin::a\n" +
				"1 skip message(s) point to missing setup or are unclassified.\n```\n");
		}
	}

	/**
	 * Ignores tracing/status text, deduplicates Bazel XML/log repeats, and retains testcase locations.
	 *
	 * @throws IOException if fixture report IO fails
	 */
	@Test public void scansAndDeduplicatesBazelSkipStreams() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String skip = "skipping zsh test: /bin/zsh is unavailable";
			Path target = Files.createDirectories(fixture.root.resolve("testlogs/pkg/app/runner"));
			fixture.write(target.resolve("test.xml"),
				"<testsuites><testsuite><testcase name='one' classname='suite'>" +
				"<system-out>WARN codex: skipping product message\n" + skip +
				"</system-out></testcase></testsuite></testsuites>");
			fixture.write(target.resolve("test.log"), skip + "\n");
			CheckTestSkips.Result result = CheckTestSkips.evaluate(List.of(fixture.root.resolve("testlogs")),
				TestReports.Runner.BAZEL, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.BAZEL,
				CheckTestSkips.Environment.REMOTE);
			requireThat(result.files().size(), "bothReports").isEqualTo(2);
			requireThat(result.skips().size(), "deduplicated").isEqualTo(1);
			requireThat(result.skips().getFirst().skip().test(), "testLocation").isEqualTo("suite::one");
			requireThat(CheckTestSkips.render(result).toString(), "missingSetupReceipt").contains("[missing setup] x1");
		}
	}

	/** A skip-only clean report remains a nonfailure when explicitly allowed. */
	@Test public void allowedSkipDoesNotFailVerdict()
	{
		var skip = new CheckTestSkips.Skip(Path.of("junit.xml"), "test",
			"Skipping test: Landlock is not enforceable on this host.");
		requireThat(CheckTestSkips.classify(skip, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.REMOTE).verdict(), "allowedByKernelPolicy").isEqualTo(CheckTestSkips.Verdict.ALLOWED);
	}

	/** Only the namespace-UID-0 pass of the root metadata test may skip on a nested UID-map refusal. */
	@Test public void rootIdentityUidMapSkipIsAllowedButWholeTestSkipIsNot()
	{
		var rootPass = new CheckTestSkips.Skip(Path.of("junit.xml"), "test", "skipping root metadata test as " +
			"namespace UID 0: nested UID map refused: bwrap: setting up uid map: Operation not permitted");
		requireThat(CheckTestSkips.classify(rootPass, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.LOCAL).verdict(), "rootPass").isEqualTo(CheckTestSkips.Verdict.ALLOWED);
		var wholeTest = new CheckTestSkips.Skip(Path.of("junit.xml"), "test",
			"skipping root metadata test: bwrap: setting up uid map: Operation not permitted");
		requireThat(CheckTestSkips.classify(wholeTest, CheckTestSkips.Platform.LINUX, CheckTestSkips.Runner.NEXTEST,
			CheckTestSkips.Environment.LOCAL).verdict(), "wholeTest").isEqualTo(CheckTestSkips.Verdict.MISSING_SETUP);
	}

	/** Owns temporary XML and log reports. */
	private static final class Fixture implements AutoCloseable
	{
		/** Directory containing this test's reports. */
		private final Path root;
		/**
		 * Creates a fixture-owned temporary directory.
		 *
		 * @throws IOException if temporary storage cannot be created
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("skip-reports-");
		}
		/**
		 * Writes UTF-8 fixture content to a report path.
		 *
		 * @param path report file path
		 * @param content report file content
		 * @return path of the written report
		 * @throws IOException if the report cannot be written
		 */
		private Path write(Path path, String content) throws IOException
		{
			Files.createDirectories(path.getParent());
			return Files.writeString(path, content);
		}
		/**
		 * Removes fixture files in reverse path order.
		 *
		 * @throws IOException if a fixture file cannot be deleted
		 */
		@Override public void close() throws IOException
		{
			try (java.util.stream.Stream<Path> files = Files.walk(root))
			{
				for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.deleteIfExists(path);
			}
		}
	}
}
