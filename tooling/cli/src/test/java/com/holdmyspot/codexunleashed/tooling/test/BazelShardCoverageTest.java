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
		shard(root, "g", 1, 2, "//a\n//b\n//c\n", "//a\n//c\n", null);
		shard(root, "g", 2, 2, "//a\n//b\n//c\n", "//b\n", null);
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
		shard(root, "g", 1, 3, "//a\n//b\n//c\n", "//a\n//b\n", null);
		shard(root, "g", 2, 3, "//a\n//b\n", "//b\n", null);
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		int status = run(root, err, "g=3");
		String text = err.toString(StandardCharsets.UTF_8);
		requireThat(status, "status").isEqualTo(1);
		requireThat(text, "text").contains("shard 3 of 3 did not upload").contains("different candidate list").
			contains("target //b was selected by shards 1 and 2").contains("candidate //c was selected by no shard");
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
		shard(root, "g", 1, 1, "//a\n//b\n", "//a\n//b\n", "//b\n");
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
	 * Extracts skipped labels from build events.
	 *
	 * @throws IOException if fixture IO fails
	 */
	@Test public void readsSkippedTargetsFromBuildEvents() throws IOException
	{
		Path bep = Files.writeString(Files.createTempFile("bep", ".json"),
			"{\"id\":{\"targetCompleted\":{\"label\":\"//x:t\"}},\"aborted\":{\"reason\":\"SKIPPED\"}}\n" +
			"{\"id\":{\"targetCompleted\":{\"label\":\"//y:t\"}},\"completed\":{\"success\":true}}\n" +
			"{\"id\":{\"targetCompleted\":{\"label\":\"//z:t\"}},\"aborted\":{\"reason\":\"USER_INTERRUPTED\"}}\n");
		requireThat(BazelShardCoverage.skippedTargets(new DefaultToolingScope(), bep), "skipped").
			isEqualTo(Set.of("//x:t"));
		Path output = bep.resolveSibling(bep.getFileName() + ".out");
		PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		requireThat(BazelShardCoverage.run(new DefaultToolingScope(), new String[]{"skipped", "--bep", bep.toString(),
			"--output", output.toString()}, out, out), "status").isEqualTo(0);
		requireThat(Files.readString(output), "file").isEqualTo("//x:t\n");
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
	 * @param skipped skipped list text or null
	 * @throws IOException if writing fails
	 */
	private static void shard(Path root, String group, int number, int count, String candidates, String selected,
		String skipped) throws IOException
	{
		Path directory = Files.createDirectories(root.resolve("art-" + group + "-" + number));
		Files.writeString(directory.resolve("group.txt"), group + "\n");
		Files.writeString(directory.resolve("shard.txt"), number + "\n");
		Files.writeString(directory.resolve("shard-count.txt"), count + "\n");
		Files.writeString(directory.resolve("candidates.txt"), candidates);
		Files.writeString(directory.resolve("selected.txt"), selected);
		if (skipped != null) Files.writeString(directory.resolve("skipped.txt"), skipped);
	}
}
