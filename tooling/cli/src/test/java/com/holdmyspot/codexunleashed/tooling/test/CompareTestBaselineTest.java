package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CompareTestBaseline;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.TestReports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies deterministic baseline matching and the report comparison protocol. */
public final class CompareTestBaselineTest
{
	/** Creates a test instance. */
	public CompareTestBaselineTest()
	{
		super();
	}

	/**
	 * Separates new, fixed, unchanged and not-run failures while normalizing generated case numbers.
	 *
	 * @throws IOException if a fixture report cannot be written
	 */
	@Test public void comparesAllFourSections() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path xml = fixture.write("<testsuites><testsuite>" +
				"<testcase name='direct::test_case_1_direct' classname='bin'><failure type='failure'/></testcase>" +
				"<testcase name='new' classname='bin'><error type='exec'/></testcase>" +
				"<testcase name='fixed' classname='bin'/><testcase name='skipped' classname='bin'><skipped/>" +
				"</testcase></testsuite></testsuites>");
			TestReports.Report report = TestReports.collect(List.of(xml), TestReports.Runner.NEXTEST);
			CompareTestBaseline.Sections sections = CompareTestBaseline.compare(
				Set.of("bin direct::direct", "bin fixed", "bin missing"), report, Set.of());
			requireThat(sections.newFailures(), "new").isEqualTo(List.of("bin new"));
			requireThat(sections.fixed(), "fixed").isEqualTo(List.of("bin fixed"));
			requireThat(sections.unchanged(), "unchanged").isEqualTo(List.of("bin direct::test_case_1_direct"));
			requireThat(sections.notRun(), "notRun").isEqualTo(List.of("bin missing"));
		}
	}

	/**
	 * A baseline-failing Bazel case that passes in a passing target is fixed; one absent from the log is not run.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void bazelPassingCaseCountsAsFixedButAbsentCaseDoesNot() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path root = Files.createDirectories(fixture.root.resolve("bazel-testlogs/pkg/app/runner"));
			Files.writeString(root.resolve("test.xml"), "<testsuites><testsuite><testcase name='t'/></testsuite></testsuites>");
			Files.writeString(root.resolve("test.log"), "test suite::passes ... ok\n");
			TestReports.Report report = TestReports.collect(List.of(fixture.root.resolve("bazel-testlogs")),
				TestReports.Runner.BAZEL);
			CompareTestBaseline.Sections sections = CompareTestBaseline.compare(
				Set.of("//pkg/app:runner suite::passes", "//pkg/app:runner suite::absent"), report, Set.of());
			requireThat(sections.fixed(), "fixed").isEqualTo(List.of("//pkg/app:runner suite::passes"));
			requireThat(sections.notRun(), "notRun").isEqualTo(List.of("//pkg/app:runner suite::absent"));
			requireThat(report.executed().size(), "targetCount").isEqualTo(1);
		}
	}

	/**
	 * Shard results merge into one comparison against the union of the shard baselines, so a target that moved
	 * between shards is neither new nor not run, and write-baseline output stays readable by the merged reader.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void mergedShardResultsCompareAgainstUnionOfShardBaselines() throws IOException
	{
		try (Fixture fixture = new Fixture(); var scope = new DefaultToolingScope())
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
			Path baselines = fixture.root.resolve("baselines");
			// Baseline run: target //p:a fails in shard 1, //p:b passes in shard 2.
			Path logs1 = Files.createDirectories(fixture.root.resolve("b1/bazel-testlogs/p/a"));
			Files.writeString(logs1.resolve("test.xml"), "<testsuites><testcase name='t'><failure type='x'/></testcase></testsuites>");
			Path logs2 = Files.createDirectories(fixture.root.resolve("b2/bazel-testlogs/p/b"));
			Files.writeString(logs2.resolve("test.xml"), "<testsuites><testcase name='t'/></testsuites>");
			CompareTestBaseline.run(scope, new String[]{"--platform", "g-shard1", "--runner", "bazel", "--upstream-commit",
				"c", "--baseline-dir", baselines.toString(), "--write-baseline", fixture.root.resolve("b1").toString()},
				null, out, err);
			CompareTestBaseline.run(scope, new String[]{"--platform", "g-shard2", "--runner", "bazel", "--upstream-commit",
				"c", "--baseline-dir", baselines.toString(), "--write-baseline", fixture.root.resolve("b2").toString()},
				null, out, err);
			// Patched run with a different assignment: //p:a is now in shard 2 and still fails.
			Path r1 = Files.createDirectories(fixture.root.resolve("p1/bazel-testlogs/p/b"));
			Files.writeString(r1.resolve("test.xml"), "<testsuites><testcase name='t'/></testsuites>");
			Path r2 = Files.createDirectories(fixture.root.resolve("p2/bazel-testlogs/p/a"));
			Files.writeString(r2.resolve("test.xml"), "<testsuites><testcase name='t'><failure type='x'/></testcase></testsuites>");
			for (String shard : List.of("1", "2"))
				requireThat(CompareTestBaseline.run(scope, new String[]{"--runner", "bazel", "--write-results",
					fixture.root.resolve("results/shard-" + shard + "/results.json").toString(),
					fixture.root.resolve("p" + shard).toString()}, null, out, err), "shard" + shard).isEqualTo(0);
			int status = CompareTestBaseline.run(scope, new String[]{"--runner", "bazel", "--upstream-commit", "c",
				"--baseline-dir", baselines.toString(), "--baseline-prefix", "g-shard", "--merge-results",
				fixture.root.resolve("results").toString()}, null, out, err);
			requireThat(status, "mergedStatus").isEqualTo(0);
			String text = output.toString(StandardCharsets.UTF_8);
			requireThat(text, "text").contains("[new (fail the run)] 0").contains("[unchanged baseline failures] 1").
				contains("[in the baseline but not run] 0").contains("Executed tests: 2 in this run, 2 in the baseline.");
		}
	}

	/**
	 * Related failures are known without adding them to the primary baseline's not-run section.
	 *
	 * @throws IOException if a fixture report cannot be written
	 */
	@Test public void relatedFailuresDoNotBecomeNewOrNotRun() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path xml = fixture.write("<testsuites><testcase name='b' classname='bin'><failure/></testcase>" +
				"</testsuites>");
			TestReports.Report report = TestReports.collect(List.of(xml), TestReports.Runner.NEXTEST);
			CompareTestBaseline.Sections sections = CompareTestBaseline.compare(Set.of("bin a"), report,
				Set.of("bin b", "bin c"));
			requireThat(sections.newFailures(), "relatedKnown").isEmpty();
			requireThat(sections.unchanged(), "relatedUnchanged").isEqualTo(List.of("bin b"));
			requireThat(sections.notRun(), "primaryOnlyNotRun").isEqualTo(List.of("bin a"));
		}
	}

	/** Drops case number prefixes and leading underscores at every generated segment. */
	@Test public void normalizesEveryCaseSegment()
	{
		requireThat(CompareTestBaseline.matchingKey("bin::test_case_7__direct::test_case_3__child"),
			"normalized").isEqualTo("bin::direct::child");
	}

	/**
	 * Retains the exact baseline schema, key order, indentation, sorted arrays and final newline.
	 *
	 * @throws IOException if fixture or baseline IO fails
	 */
	@Test public void writesCanonicalBaselineBytes() throws IOException
	{
		try (Fixture fixture = new Fixture(); var scope = new DefaultToolingScope())
		{
			Path xml = fixture.write("<testsuites><testcase name='z' classname='bin'><failure type='test failure'/>" +
				"</testcase><testcase name='a' classname='bin'><failure type='test timeout'/>" +
				"</testcase></testsuites>");
			TestReports.Report report = TestReports.collect(List.of(xml), TestReports.Runner.NEXTEST);
			Path target = fixture.root.resolve("out/baseline.json");
			CompareTestBaseline.writeBaseline(scope, target, "commit", "linux", "nextest", report);
			String expected = """
				{
				  "failed": [
				    "bin z"
				  ],
				  "platform": "linux",
				  "runner": "nextest",
				  "tests_executed": 2,
				  "timed_out": [
				    "bin a"
				  ],
				  "upstream_commit": "commit"
				}
				""";
			requireThat(Files.readString(target), "goldenBaseline").isEqualTo(expected);
		}
	}

	/**
	 * Preserves write/compare command statuses and appends the same fenced comparison sections.
	 *
	 * @throws IOException if fixture or summary IO fails
	 */
	@Test public void commandComparesAndWritesExplicitSummary() throws IOException
	{
		try (Fixture fixture = new Fixture(); var scope = new DefaultToolingScope())
		{
			Path original = fixture.write("original.xml", "<testsuites><testcase name='old' classname='bin'>" +
				"<failure type='test failure'/></testcase></testsuites>");
			Path patched = fixture.write("patched.xml", "<testsuites><testcase name='old' classname='bin'/>" +
				"<testcase name='new' classname='bin'><failure/></testcase></testsuites>");
			Path baselines = fixture.root.resolve("baselines");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				int status = CompareTestBaseline.run(scope, new String[]{"--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), "--write-baseline", original.toString()}, null, out, err);
				requireThat(status, "writeStatus").isEqualTo(0);
			}
			Path summary = fixture.root.resolve("summary.md");
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				int status = CompareTestBaseline.run(scope, new String[]{"--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), patched.toString()}, summary, out, err);
				requireThat(status, "newFailureStatus").isEqualTo(1);
			}
			requireThat(output.toString(StandardCharsets.UTF_8), "sections").contains(
				"[new (fail the run)] 1" + System.lineSeparator() + "    bin new");
			requireThat(Files.readString(summary), "summarySections").isEqualTo(
				"### Test baseline comparison\n\n```\n" +
				"Scanned 1 report file(s): 2 test(s) executed, 1 failing or timed out.\n" +
				"[new (fail the run)] 1\n" +
				"    bin new\n" +
				"[fixed relative to the baseline] 1\n" +
				"    bin old\n" +
				"[unchanged baseline failures] 0\n" +
				"[in the baseline but not run] 0\n" +
				"Executed tests: 2 in this run, 1 in the baseline.\n```\n");
		}
	}

	/** Fails a patched run that executed fewer than the threshold share of the baseline's tests. */
	@Test public void failsWhenFarFewerTestsExecuted()
	{
		requireThat(CompareTestBaseline.checkExecutedCount(100, 98, 98.0).isPresent(), "atThreshold").isFalse();
		requireThat(CompareTestBaseline.checkExecutedCount(100, 97, 98.0).orElseThrow(), "below").contains("97").
			contains("100");
		requireThat(CompareTestBaseline.checkExecutedCount(100, 50, 40.0).isPresent(), "configurable").isFalse();
	}

	/**
	 * The command exits nonzero and prints both counts when the executed count falls below the threshold.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void commandFailsOnShortfallAndHonorsThreshold() throws IOException
	{
		try (Fixture fixture = new Fixture(); var scope = new DefaultToolingScope())
		{
			Path original = fixture.write("original.xml", "<testsuites><testcase name='a' classname='bin'/>" +
				"<testcase name='b' classname='bin'/><testcase name='c' classname='bin'/></testsuites>");
			Path patched = fixture.write("patched.xml", "<testsuites><testcase name='a' classname='bin'/>" +
				"</testsuites>");
			Path baselines = fixture.root.resolve("baselines");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				CompareTestBaseline.run(scope, new String[]{"--platform", "linux", "--upstream-commit", "commit",
					"--baseline-dir", baselines.toString(), "--write-baseline", original.toString()}, null, out, err);
				int status = CompareTestBaseline.run(scope, new String[]{"--platform", "linux", "--upstream-commit",
					"commit", "--baseline-dir", baselines.toString(), patched.toString()}, null, out, err);
				requireThat(status, "shortfallStatus").isEqualTo(1);
				requireThat(output.toString(StandardCharsets.UTF_8), "counts").contains(
					"Executed tests: 1 in this run, 3 in the baseline.");
				status = CompareTestBaseline.run(scope, new String[]{"--platform", "linux", "--upstream-commit",
					"commit", "--baseline-dir", baselines.toString(), "--min-executed-percent", "30",
					patched.toString()}, null, out, err);
				requireThat(status, "relaxedStatus").isEqualTo(0);
			}
		}
	}

	/** Owns one temporary report. */
	private static final class Fixture implements AutoCloseable
	{
		/** Directory containing this test's reports and baselines. */
		private final Path root;
		/**
		 * Creates a fixture-owned temporary directory.
		 *
		 * @throws IOException if temporary storage cannot be created
		 */
		private Fixture() throws IOException
		{
			root = Files.createTempDirectory("baseline-");
		}
		/**
		 * Writes the default JUnit report.
		 *
		 * @param value report XML
		 * @return written report path
		 * @throws IOException if the report cannot be written
		 */
		private Path write(String value) throws IOException
		{
			return Files.writeString(root.resolve("junit.xml"), value);
		}
		/**
		 * Writes fixture content under the supplied name.
		 *
		 * @param name fixture filename
		 * @param value report or baseline content
		 * @return written file path
		 * @throws IOException if the file cannot be written
		 */
		private Path write(String name, String value) throws IOException
		{
			return Files.writeString(root.resolve(name), value);
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
				for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}
}
