package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.BazelShardCoverage;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/** Verifies shard coverage checks and skipped-target recording. */
public final class BazelShardCoverageTest
{
	/** Creates a test instance. */
	public BazelShardCoverageTest()
	{
		super();
	}

	/**
	 * Accepts shards whose selections partition the identical candidate lists.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void acceptsCompletePartition() throws IOException
	{
		Path root = Files.createTempDirectory("coverage-");
		shard(root, "g", 1, 2, "//a\n//b\n//c\n", "//a\n//c\n", null, null);
		shard(root, "g", 2, 2, "//a\n//b\n//c\n", "//b\n", null, null);
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status = run(root, err, "g=2");
		requireThat(status, "status").isEqualTo(0);
		requireThat(err.toString(StandardCharsets.UTF_8), "err").isEqualTo("");
	}

	/**
	 * Rejects a missing shard, a differing candidate list, a duplicate selection and an uncovered candidate.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void rejectsEveryCoverageDefect() throws IOException
	{
		Path root = Files.createTempDirectory("coverage-");
		shard(root, "g", 1, 3, "//a\n//b\n//c\n", "//a\n//b\n", null, null);
		shard(root, "g", 2, 3, "//a\n//b\n", "//b\n", null, null);
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status = run(root, err, "g=3");
		String text = err.toString(StandardCharsets.UTF_8);
		requireThat(status, "status").isEqualTo(1);
		requireThat(text, "text").contains("shard 3 of 3 did not upload").contains("different candidate list").
			contains("target //b was selected by shards 1 and 2").contains("1 candidate(s) selected by no shard: //c");
	}

	/**
	 * Rejects a group with no uploads at all.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void rejectsWholeMissingGroup() throws IOException
	{
		Path root = Files.createTempDirectory("coverage-");
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		requireThat(run(root, err, "g=1"), "status").isEqualTo(1);
		requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("g shard 1 of 1 did not upload");
	}

	/**
	 * Prints skipped targets and rejects those missing from the allowlist.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void enforcesSkipAllowlist() throws IOException
	{
		Path root = Files.createTempDirectory("coverage-");
		shard(root, "g", 1, 1, "//a\n//b\n", "//a\n//b\n", "//a\n", "//b\n");
		Path allowlist = Files.writeString(Files.createTempDirectory("allow-").resolve("allow.txt"), "# none\n");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status = BazelShardCoverage.run(new DefaultToolingScope(), new String[]{"check", "--directory",
			root.toString(), "--expect", "g=1", "--allowlist", allowlist.toString()},
			new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
		requireThat(status, "status").isEqualTo(1);
		requireThat(out.toString(StandardCharsets.UTF_8), "out").contains("g //b");
		requireThat(err.toString(StandardCharsets.UTF_8), "err").contains("Skipped target //b");
		Files.writeString(allowlist, "//b\n");
		err.reset();
		status = BazelShardCoverage.run(new DefaultToolingScope(), new String[]{"check", "--directory",
			root.toString(), "--expect", "g=1", "--allowlist", allowlist.toString()},
			new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
		requireThat(status, "allowedStatus").isEqualTo(0);
	}

	/**
	 * Rejects a selected target that neither executed nor was skipped.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void rejectsSelectedTargetThatDidNotRun() throws IOException
	{
		Path root = Files.createTempDirectory("coverage-");
		shard(root, "g", 1, 1, "//a\n//b\n", "//a\n//b\n", "//a\n", null);
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		requireThat(run(root, err, "g=1"), "status").isEqualTo(1);
		requireThat(err.toString(StandardCharsets.UTF_8), "err").contains(
			"1 selected target(s) neither executed nor skipped: //b");
	}

	/**
	 * Extracts executed and skipped labels from build events.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void readsExecutedAndSkippedTargetsFromBuildEvents() throws IOException
	{
		Path bep = Files.writeString(Files.createTempFile("bep", ".json"),
			"{\"id\":{\"targetCompleted\":{\"label\":\"//x:t\"}},\"aborted\":{\"reason\":\"SKIPPED\"}}\n" +
			"{\"id\":{\"testSummary\":{\"label\":\"//y:t\"}},\"testSummary\":{\"overallStatus\":\"PASSED\"}}\n" +
			"{\"id\":{\"targetCompleted\":{\"label\":\"//z:t\"}},\"aborted\":{\"reason\":\"USER_INTERRUPTED\"}}\n");
		BazelShardCoverage.Events events = BazelShardCoverage.events(new DefaultToolingScope(), bep);
		requireThat(events.skipped(), "skipped").isEqualTo(Set.of("//x:t"));
		requireThat(events.executed(), "executed").isEqualTo(Set.of("//y:t"));
		Path executed = bep.resolveSibling(bep.getFileName() + ".executed");
		Path skipped = bep.resolveSibling(bep.getFileName() + ".skipped");
		PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		requireThat(BazelShardCoverage.run(new DefaultToolingScope(), new String[]{"record", "--bep", bep.toString(),
			"--executed", executed.toString(), "--skipped", skipped.toString()}, out, out), "status").isEqualTo(0);
		requireThat(Files.readString(executed), "executedFile").isEqualTo("//y:t\n");
		requireThat(Files.readString(skipped), "skippedFile").isEqualTo("//x:t\n");
	}

	/**
	 * Runs the check without an allowlist.
	 *
	 * @param root artifact directory
	 * @param err diagnostic sink
	 * @param expect one expectation
	 * @return the status
	 * @throws IOException if the command fails
	 */
	private static int run(Path root, ByteArrayOutputStream err, String expect) throws IOException
	{
		PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		return BazelShardCoverage.run(new DefaultToolingScope(), new String[]{"check", "--directory", root.toString(),
			"--expect", expect}, out, new PrintStream(err, true, StandardCharsets.UTF_8));
	}

	/**
	 * Writes one shard artifact.
	 *
	 * @param root artifact directory
	 * @param group group name
	 * @param number shard number
	 * @param count shard count
	 * @param candidates candidate list text
	 * @param selected selected list text
	 * @param executed executed list text, or null to execute every selected target
	 * @param skipped skipped list text or null
	 * @throws IOException if writing fails
	 */
	private static void shard(Path root, String group, int number, int count, String candidates, String selected,
		String executed, String skipped) throws IOException
	{
		Path directory = Files.createDirectories(root.resolve("art-" + group + "-" + number));
		Files.writeString(directory.resolve("group.txt"), group + "\n");
		Files.writeString(directory.resolve("shard.txt"), number + "\n");
		Files.writeString(directory.resolve("shard-count.txt"), count + "\n");
		Files.writeString(directory.resolve("candidates.txt"), candidates);
		Files.writeString(directory.resolve("selected.txt"), selected);
		Files.writeString(directory.resolve("executed.txt"), executed == null ? selected : executed);
		if (skipped != null) Files.writeString(directory.resolve("skipped.txt"), skipped);
	}
}
