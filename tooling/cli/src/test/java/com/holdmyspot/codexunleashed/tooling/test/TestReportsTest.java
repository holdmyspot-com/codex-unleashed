package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.TestReports;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies bounded, secure collection of nextest and Bazel reports. */
public final class TestReportsTest
{
	/** Creates method-owned report fixtures. */
	public TestReportsTest()
	{
	}

	/**
	 * Classifies only failure/error element types as failed or timed out and excludes skipped and flaky records.
	 *
	 * @throws IOException if fixture XML cannot be written or parsed
	 */
	@Test public void collectsNextestVerdictsAndStreams() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path xml = fixture.write("junit.xml",
				"<testsuites><testsuite name='suite'><testcase name='pass' classname='bin'>" +
				"<system-out>skipping zsh</system-out></testcase><testcase name='timeout' classname='bin'>" +
				"<failure message='ordinary error' type='test timeout'/></testcase>" +
				"<testcase name='skip' classname='bin'><skipped/>" +
				"</testcase><testcase name='flaky' classname='bin'><flakyFailure/></testcase></testsuite></testsuites>");
			TestReports.Report report = TestReports.collect(List.of(xml), TestReports.Runner.NEXTEST);
			requireThat(report.executed(), "executed").isEqualTo(java.util.Set.of("bin pass", "bin timeout", "bin flaky"));
			requireThat(report.timedOut(), "timeoutType").isEqualTo(java.util.Set.of("bin timeout"));
			requireThat(report.failed(), "notMessage").isEmpty();
			requireThat(report.streams().getFirst().text(), "stream").isEqualTo("skipping zsh");
		}
	}

	/**
	 * Rejects malformed XML and external entities with the originating path in diagnostics.
	 *
	 * @throws IOException if fixture XML cannot be written
	 */
	@Test public void rejectsMalformedAndExternalEntityXml() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path malformed = fixture.write("bad.xml", "<testsuites><testcase></testsuites>");
			Path entity = fixture.write("entity.xml",
				"<!DOCTYPE report [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" +
				"<testsuites><testcase name='&xxe;'/></testsuites>");
			for (Path path : List.of(malformed, entity))
				try
				{
					TestReports.collect(List.of(path), TestReports.Runner.NEXTEST);
					throw new AssertionError("expected secure parser failure");
				}
				catch (IOException expected)
				{
					requireThat(expected.getMessage(), "pathDiagnostic").contains(path.toString());
				}
		}
	}

	/**
	 * Derives stable target IDs across shard directories and preserves ANSI-stripped libtest cases separately.
	 *
	 * @throws IOException if the Bazel report fixtures cannot be read or written
	 */
	@Test public void collectsBazelTargetAndCaseFailures() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path root = Files.createDirectories(fixture.root.resolve("bazel-testlogs/pkg/app/runner/shard_1_of_2"));
			Path xml = fixture.write(root.resolve("test.xml"),
				"<testsuites><testsuite><testcase name='case'><failure type='test failure'/>" +
				"</testcase></testsuite></testsuites>");
			fixture.write(root.resolve("test.log"),
				"test suite::one ... ok\ntest suite::two ... \u001b[31mFAILED\u001b[0m\n");
			TestReports.Report report = TestReports.collect(List.of(fixture.root.resolve("bazel-testlogs")),
				TestReports.Runner.BAZEL);
			requireThat(report.executed(), "targetExecuted").contains("//pkg/app:runner");
			requireThat(report.failed(), "separateTargetAndCase").containsAll(List.of("//pkg/app:runner",
				"//pkg/app:runner suite::two"));
			requireThat(xml, "explicitFixture").isNotNull();
		}
	}

	/**
	 * Uses a failure's type, never an unrelated timeout phrase in its message, to select the verdict.
	 *
	 * @throws IOException if fixture XML cannot be written or parsed
	 */
	@Test public void failureMessageDoesNotClassifyTimeout() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path xml = fixture.write("ordinary.xml",
				"<testsuites><testcase name='a'><failure message='request timed out' " +
				"type='test failure'/></testcase></testsuites>");
			TestReports.Report report = TestReports.collect(List.of(xml), TestReports.Runner.NEXTEST);
			requireThat(report.failed(), "failureTypeControlsVerdict").isEqualTo(java.util.Set.of("a"));
			requireThat(report.timedOut(), "messageIsNotTimeout").isEmpty();
		}
	}

	/** Owns all temporary report paths. */
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
			root = Files.createTempDirectory("reports-");
		}
		/**
		 * Writes a named report under the fixture directory.
		 *
		 * @param name report basename
		 * @param content report contents
		 * @return written report path
		 * @throws IOException if the report cannot be written
		 */
		private Path write(String name, String content) throws IOException
		{
			return write(root.resolve(name), content);
		}
		/**
		 * Writes report content after creating its parent directory.
		 *
		 * @param path report destination
		 * @param content report contents
		 * @return written report path
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
