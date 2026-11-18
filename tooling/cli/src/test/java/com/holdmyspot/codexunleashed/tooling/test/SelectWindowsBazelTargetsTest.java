package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.SelectWindowsBazelTargets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the {@code select-windows-bazel-targets} command against selections and diagnostics that the frozen
 * Python selector produced for the same inputs.
 */
public final class SelectWindowsBazelTargetsTest
{
	/** The command name. */
	private static final String COMMAND = "select-windows-bazel-targets";
	/** The usage line that every failure diagnostic starts with. */
	private static final String USAGE = "Usage: codex-tooling " + COMMAND +
		" --shard N --shard-count N --durations PATH\n";
	/** Durations that give A, B, C and D the weights 5, 4, 3 and 2. */
	private static final String WEIGHTS = "A\t5\nB\t4\nC\t3\nD\t2\n";

	/** Constructs independent fixtures for each check. */
	public SelectWindowsBazelTargetsTest()
	{
	}

	/**
	 * Places equally weighted labels in label order on the lightest shard, with ties going to the lowest shard.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void balancesEqualWeightTargets() throws IOException
	{
		Result first = run("A\nB\nC\n", "", 1, 2);
		Result second = run("A\nB\nC\n", "", 2, 2);

		requireThat(first.status(), "status").isEqualTo(0);
		requireThat(first.stdout(), "stdout").isEqualTo("A\nC\n");
		requireThat(first.stderr(), "stderr").isEqualTo("Windows Bazel shards: targets=[2, 1], " +
			"estimated test-seconds=[2, 1], default weights=3, unused weights=0. Selected shard 1/2.\n");
		requireThat(second.status(), "status").isEqualTo(0);
		requireThat(second.stdout(), "stdout").isEqualTo("B\n");
		requireThat(second.stderr(), "stderr").isEqualTo("Windows Bazel shards: targets=[2, 1], " +
			"estimated test-seconds=[2, 1], default weights=3, unused weights=0. Selected shard 2/2.\n");
	}

	/**
	 * Places the heaviest label first so that both shards carry the same load.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void balancesWeightedTargets() throws IOException
	{
		Result first = run("A\nB\nC\nD\n", WEIGHTS, 1, 2);
		Result second = run("A\nB\nC\nD\n", WEIGHTS, 2, 2);

		requireThat(first.status(), "status").isEqualTo(0);
		requireThat(first.stdout(), "selected_targets").isEqualTo("A\nD\n");
		requireThat(first.stderr(), "loads").isEqualTo("Windows Bazel shards: targets=[2, 2], " +
			"estimated test-seconds=[7, 7], default weights=0, unused weights=0. Selected shard 1/2.\n");
		requireThat(second.status(), "status").isEqualTo(0);
		requireThat(second.stdout(), "selected_targets").isEqualTo("B\nC\n");
		requireThat(second.stderr(), "loads").isEqualTo("Windows Bazel shards: targets=[2, 2], " +
			"estimated test-seconds=[7, 7], default weights=0, unused weights=0. Selected shard 2/2.\n");
	}

	/**
	 * Selects the same labels and reports the same loads whatever the order of the input labels.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesInputPermutation() throws IOException
	{
		for (String input : List.of("D\nA\nC\nB\n", "C\nD\nB\nA\n", "B\nA\nD\nC\n", "A\nB\nC\nD"))
		{
			Result first = run(input, WEIGHTS, 1, 2);
			Result second = run(input, WEIGHTS, 2, 2);

			requireThat(first.stdout(), "selected_targets").isEqualTo("A\nD\n");
			requireThat(second.stdout(), "selected_targets").isEqualTo("B\nC\n");
			requireThat(first.stderr(), "loads").contains("estimated test-seconds=[7, 7]");
		}
	}

	/**
	 * Counts the supplied labels that lack a duration and the durations that no supplied label uses, each once.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsDefaultAndUnusedWeights() throws IOException
	{
		Result result = run("A\nB\n", "A\t5\nUNUSED\t9\n", 1, 1);

		requireThat(result.status(), "status").isEqualTo(0);
		requireThat(result.stdout(), "stdout").isEqualTo("A\nB\n");
		requireThat(result.stderr(), "stderr").isEqualTo("Windows Bazel shards: targets=[2], " +
			"estimated test-seconds=[6], default weights=1, unused weights=1. Selected shard 1/1.\n");
	}

	/**
	 * Reads decimal digits of any script, including supplementary-plane digits, and sums durations beyond 64 bits
	 * without loss.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void acceptsUnicodeAndLargeDurations() throws IOException
	{
		String durations = "A\t١٢\nB\t𝟙\nC\t9223372036854775808\nD\t1٢\n# comment\n\n \t\n";
		Result first = run("A\nB\nC\nD\n", durations, 1, 2);
		Result second = run("A\nB\nC\nD\n", durations, 2, 2);

		requireThat(first.status(), "status").isEqualTo(0);
		requireThat(first.stdout(), "selected_targets").isEqualTo("C\n");
		requireThat(first.stderr(), "loads").isEqualTo("Windows Bazel shards: targets=[1, 3], " +
			"estimated test-seconds=[9223372036854775808, 25], default weights=0, unused weights=0. " +
			"Selected shard 1/2.\n");
		requireThat(second.stdout(), "selected_targets").isEqualTo("A\nB\nD\n");

		Result large = run("A\nB\nC\nD\nE\n", "A\t18446744073709551616\nB\t18446744073709551615\n" +
			"C\t9223372036854775807\nD\t9223372036854775808\n", 1, 3);
		requireThat(large.stdout(), "selected_targets").isEqualTo("A\n");
		requireThat(large.stderr(), "loads").isEqualTo("Windows Bazel shards: targets=[1, 2, 2], " +
			"estimated test-seconds=[18446744073709551616, 18446744073709551616, 18446744073709551615], " +
			"default weights=1, unused weights=0. Selected shard 1/3.\n");

		for (String spelling : List.of("１", "१", "01", "1٢", "𝟙𝟚"))
		{
			Result accepted = run("A\n", "A\t" + spelling + "\n", 1, 1);

			requireThat(accepted.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Rejects each spelling that is not a nonempty run of decimal digits, a zero duration, and a record without
	 * exactly two fields, naming the file and line.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsMalformedDurations() throws IOException
	{
		String malformed = "expected target<TAB>positive integer";
		for (String record : List.of("A\t+1", "A\t1_0", "A\t1.0", "A\t1 ", "A\t-1", "A\t", "A", "A\t1\t2", "\t1",
			"A\t1\t", "A\t²", "A\t 1"))
		{
			try (TestDirectory fixture = new TestDirectory("select-windows-"))
			{
				Path durations = fixture.write("durations.tsv", "# heading\n\n" + record + "\n");

				Result result = runWith("A\n", durations, 1, 1);

				requireThat(result.status(), "status").isEqualTo(2);
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").startsWith(USAGE).
					endsWith(": error: " + durations + ":3: " + malformed + "\n");
			}
		}
		for (String record : List.of("A\t0", "A\t00", "A\t٠"))
		{
			try (TestDirectory fixture = new TestDirectory("select-windows-"))
			{
				Path durations = fixture.write("durations.tsv", "A\t1\n" + record + "\n");

				Result result = runWith("A\n", durations, 1, 1);

				requireThat(result.status(), "status").isEqualTo(2);
				requireThat(result.stderr(), "stderr").endsWith(": error: " + durations + ":2: duration must be positive\n");
			}
		}
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path durations = fixture.write("durations.tsv", "A\t1\nB\tx\n");

			Result result = runWith("A\n", durations, 1, 1);

			requireThat(result.stderr(), "stderr").endsWith(": error: " + durations + ":2: " + malformed + "\n");
		}
	}

	/**
	 * Rejects a repeated timing record at its second line, after any malformed-field or nonpositive failure of that
	 * line.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsDuplicateDurationTargets() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path durations = fixture.write("durations.tsv", "# c\n\nA\t1\nA\t2\n");

			Result result = runWith("A\n", durations, 1, 1);

			requireThat(result.status(), "status").isEqualTo(2);
			requireThat(result.stdout(), "stdout").isEmpty();
			requireThat(result.stderr(), "stderr").startsWith(USAGE).
				endsWith(": error: " + durations + ":4: duplicate target A\n");
		}
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path durations = fixture.write("durations.tsv", "A\t1\r\nA\t2\r\n");

			Result result = runWith("A\n", durations, 1, 1);

			requireThat(result.stderr(), "stderr").endsWith(": error: " + durations + ":2: duplicate target A\n");
		}
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path durations = fixture.write("durations.tsv", "A\t1\nA\t0\n");

			Result result = runWith("A\n", durations, 1, 1);

			requireThat(result.stderr(), "stderr").endsWith(": error: " + durations + ":2: duration must be positive\n");
		}
	}

	/**
	 * Rejects an empty population, an empty or whitespace-only label, and a repeated label, in that order.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsEmptyOrDuplicateTargets() throws IOException
	{
		String[][] cases =
		{
			{"", "no Bazel test targets were provided"},
			{"\n\n", "Bazel test target labels must not be empty"},
			{"A\n \n", "Bazel test target labels must not be empty"},
			{"A\n\u001f\n", "Bazel test target labels must not be empty"},
			{"A\n \n", "Bazel test target labels must not be empty"},
			{"A\nA\n", "duplicate Bazel test target labels were provided"},
			{"A\nA\n \n", "Bazel test target labels must not be empty"}
		};
		for (String[] testCase : cases)
		{
			Result result = run(testCase[0], "", 1, 1);

			requireThat(result.status(), "status").isEqualTo(2);
			requireThat(result.stdout(), "stdout").isEmpty();
			requireThat(result.stderr(), "stderr").startsWith(USAGE).endsWith(": error: " + testCase[1] + "\n");
		}
		Result zeroWidth = run("​\n", "", 1, 1);

		requireThat(zeroWidth.status(), "status").isEqualTo(0);
		requireThat(zeroWidth.stdout(), "stdout").isEqualTo("​\n");
	}

	/**
	 * Rejects a shard outside one through the shard count, including a nonpositive shard count, before reading any
	 * input.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsInvalidShardBounds() throws IOException
	{
		for (int[] bounds : new int[][]{{0, 2}, {3, 2}, {1, 0}, {-1, 2}, {0, 0}})
		{
			try (TestDirectory fixture = new TestDirectory("select-windows-"))
			{
				Path missing = fixture.getPath().resolve("missing.tsv");

				Result result = runWith("A\n", missing, bounds[0], bounds[1]);

				requireThat(result.status(), "status").isEqualTo(2);
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").startsWith(USAGE).
					endsWith(": error: shard must be between 1 and shard count\n");
			}
		}
		Result invalid = runArguments("A\n", "--shard", "x", "--shard-count", "2", "--durations", "unused");
		requireThat(invalid.status(), "status").isEqualTo(2);
		requireThat(invalid.stdout(), "stdout").isEmpty();
		requireThat(invalid.stderr(), "stderr").startsWith(USAGE).contains("invalid integer value");
		for (String[] arguments : new String[][]{{}, {"--shard", "1"}, {"--shard", "1", "--shard-count", "1"},
			{"--shard", "1", "--shard-count", "1", "--durations"}, {"--shard", "1", "--shard", "1", "--shard-count", "1",
			"--durations", "x"}, {"--help", "--shard", "1"}, {"--unknown", "1"}})
		{
			Result result = runArguments("A\n", arguments);

			requireThat(result.status(), "status").isEqualTo(2);
			requireThat(result.stdout(), "stdout").isEmpty();
			requireThat(result.stderr(), "stderr").startsWith(USAGE);
		}
	}

	/**
	 * Rejects a shard that receives no label, naming the shard and the shard count.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsEmptySelectedShard() throws IOException
	{
		Result result = run("A\n", "", 2, 2);

		requireThat(result.status(), "status").isEqualTo(2);
		requireThat(result.stdout(), "stdout").isEmpty();
		requireThat(result.stderr(), "stderr").startsWith(USAGE).
			endsWith(": error: no Bazel test targets selected for shard 2/2\n");

		Result third = run("A\nB\n", "", 3, 3);

		requireThat(third.status(), "status").isEqualTo(2);
		requireThat(third.stderr(), "stderr").endsWith(": error: no Bazel test targets selected for shard 3/3\n");
	}

	/**
	 * Keeps whitespace around a label as part of the label and splits lines only at Python's line boundaries.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesRawTargetLabels() throws IOException
	{
		String input = "//a:t\r\n //a:t \u000b//b\f//c\u001c//d\u001d//e\u001e//f\u0085//g //h //i ​//j\n";

		Result result = run(input, "", 1, 1);

		requireThat(result.status(), "status").isEqualTo(0);
		requireThat(result.stdout(), "selected_targets").isEqualTo(
			" //a:t \n//a:t\n//b\n//c\n//d\n//e\n//f\n//g\n//h\n//i ​//j\n");
		requireThat(result.stderr(), "stderr").isEqualTo("Windows Bazel shards: targets=[10], " +
			"estimated test-seconds=[10], default weights=10, unused weights=0. Selected shard 1/1.\n");
		requireThat(run("//a:t\n", " //a:t\t1\n", 1, 1).stderr(), "stderr").contains("default weights=1, unused weights=1");
	}

	/**
	 * Leaves the caller's streams open, reads no standard input after a rejected duration table, and ignores a byte
	 * order mark as ordinary label text.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesBorrowedStreams() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-");
			ObservedInput input = new ObservedInput("A\nB\n".getBytes(StandardCharsets.UTF_8));
			ObservedInput unread = new ObservedInput("A\n".getBytes(StandardCharsets.UTF_8));
			ObservedOutput output = new ObservedOutput();
			ObservedOutput error = new ObservedOutput();
			PrintStream out = new PrintStream(output, false, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, false, StandardCharsets.UTF_8))
		{
			Path durations = fixture.write("durations.tsv", "");
			Path rejected = fixture.write("rejected.tsv", "A\n");

			int success = SelectWindowsBazelTargets.run(new String[]{"--shard", "1", "--shard-count", "1", "--durations",
				durations.toString()}, input, out, err);
			int failure = SelectWindowsBazelTargets.run(new String[]{"--shard", "1", "--shard-count", "1", "--durations",
				rejected.toString()}, unread, out, err);
			int invalid = SelectWindowsBazelTargets.run(new String[]{"--shard"}, unread, out, err);
			int help = SelectWindowsBazelTargets.run(new String[]{"--help"}, unread, out, err);

			requireThat(success, "success_status").isEqualTo(0);
			requireThat(failure, "failure_status").isEqualTo(2);
			requireThat(invalid, "invalid_status").isEqualTo(2);
			requireThat(help, "help_status").isEqualTo(0);
			requireThat(input.closed, "stdin_closed").isFalse();
			requireThat(unread.closed, "unread_closed").isFalse();
			requireThat(unread.reads, "unread_reads").isEqualTo(0);
			requireThat(output.closed, "stdout_closed").isFalse();
			requireThat(error.closed, "stderr_closed").isFalse();
			requireThat(out.checkError(), "stdout_failed").isFalse();
			requireThat(err.checkError(), "stderr_failed").isFalse();
			requireThat(output.toString(StandardCharsets.UTF_8), "stdout").
				startsWith("A\nB\nUsage: codex-tooling " + COMMAND);
			requireThat(error.toString(StandardCharsets.UTF_8), "stderr").startsWith("Windows Bazel shards: targets=[2]");
		}
	}

	/**
	 * Writes labels as UTF-8 with one LF after each, whatever the charset of the caller's stream and the line
	 * separators of the input.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void writesUtf8LfTargets() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-");
			ObservedOutput output = new ObservedOutput();
			ObservedOutput error = new ObservedOutput();
			PrintStream out = new PrintStream(output, false, StandardCharsets.ISO_8859_1);
			PrintStream err = new PrintStream(error, false, StandardCharsets.ISO_8859_1))
		{
			Path durations = fixture.write("durations.tsv", "");
			byte[] input = "€\r\né\r\n😀\r".getBytes(StandardCharsets.UTF_8);

			int status = SelectWindowsBazelTargets.run(new String[]{"--shard", "1", "--shard-count", "1", "--durations",
				durations.toString()}, new ByteArrayInputStream(input), out, err);

			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toByteArray(), "stdout").isEqualTo("é\n€\n😀\n".
				getBytes(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Reports a durations file that cannot be opened with the wording of Python's {@code OSError}, and shows the path
	 * as {@code str(Path(text))} does.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsPythonFileErrors() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path missing = fixture.getPath().resolve("missing.tsv");
			Result absent = runWith("A\n", missing, 1, 1);

			requireThat(absent.status(), "status").isEqualTo(2);
			requireThat(absent.stdout(), "stdout").isEmpty();
			requireThat(absent.stderr(), "stderr").startsWith(USAGE).
				endsWith(": error: [Errno 2] No such file or directory: '" + pythonEscaped(missing) + "'\n");

			Path directory = fixture.createDirectories("table");
			Result folder = runWith("A\n", directory, 1, 1);

			requireThat(folder.status(), "status").isEqualTo(2);
			requireThat(folder.stderr(), "stderr").
				endsWith(": error: [Errno 21] Is a directory: '" + pythonEscaped(directory) + "'\n");

			Path table = fixture.write("durations.tsv", "A\tx\n");
			String shown = fixture.getPath() + "/durations.tsv";
			Result dotted = runArguments("A\n", "--shard", "1", "--shard-count", "1", "--durations",
				fixture.getPath() + "//./durations.tsv");

			requireThat(dotted.stderr(), "stderr").
				endsWith(": error: " + shown + ":1: expected target<TAB>positive integer\n");

			Path quoted = fixture.createDirectories("it's");
			Result apostrophe = runWith("A\n", quoted.resolve("missing.tsv"), 1, 1);

			requireThat(apostrophe.stderr(), "stderr").
				endsWith(": error: [Errno 2] No such file or directory: \"" +
					pythonEscaped(quoted.resolve("missing.tsv")) + "\"\n");
			requireThat(Files.exists(table), "table_exists").isTrue();
		}
	}

	/**
	 * Spells a path the way Python's {@code repr} does inside quotes, which doubles each backslash of a Windows path.
	 *
	 * @param path the path to spell
	 * @return the path text with every backslash doubled
	 */
	private static String pythonEscaped(Path path)
	{
		return path.toString().replace("\\", "\\\\");
	}

	/**
	 * Decodes the whole durations file before examining any record and reports the first invalid byte with the
	 * wording of Python's {@code UnicodeDecodeError}.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsPythonDecodeErrors() throws IOException
	{
		String prefix = "'utf-8' codec can't decode ";
		String[][] cases =
		{
			{"A\tx\n\u00ff", "byte 0xff in position 4: invalid start byte"},
			{"\u00e2\u0082", "bytes in position 0-1: unexpected end of data"},
			{"\u00e2\u0082A", "bytes in position 0-1: invalid continuation byte"},
			{"\u00ed\u00a0\u0080", "byte 0xed in position 0: invalid continuation byte"},
			{"\u00c3", "byte 0xc3 in position 0: unexpected end of data"},
			{"\u00f0\u0090\u0080", "bytes in position 0-2: unexpected end of data"},
			{"\u00f4\u0090\u0080\u0080", "byte 0xf4 in position 0: invalid continuation byte"},
			{"\u00c0\u0080", "byte 0xc0 in position 0: invalid start byte"},
			{"\u00e0\u0080\u0080", "byte 0xe0 in position 0: invalid continuation byte"},
			{"\u00f1\u0080\u0080A", "bytes in position 0-2: invalid continuation byte"},
			{"A\n\u0080\n", "byte 0x80 in position 2: invalid start byte"}
		};
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path table = fixture.getPath().resolve("durations.tsv");
			for (String[] testCase : cases)
			{
				Files.write(table, testCase[0].getBytes(StandardCharsets.ISO_8859_1));

				Result result = runWith("A\n", table, 1, 1);

				requireThat(result.status(), "status").isEqualTo(2);
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").startsWith(USAGE).endsWith(": error: " + prefix + testCase[1] + "\n");
			}
			Files.write(table, "A\t1\n\uffff\t2\n".getBytes(StandardCharsets.UTF_8));

			Result noncharacter = runWith("A\n", table, 1, 1);

			requireThat(noncharacter.status(), "status").isEqualTo(0);
			Files.write(table, new byte[0]);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream error = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
			{
				int status = SelectWindowsBazelTargets.run(new String[]{"--shard", "1", "--shard-count", "1",
					"--durations", table.toString()}, new ByteArrayInputStream(new byte[]{'A', '\n', (byte) 0xc3, '\n'}),
					out, err);

				requireThat(status, "status").isEqualTo(2);
			}
			requireThat(output.size(), "stdout_bytes").isEqualTo(0);
			requireThat(error.toString(StandardCharsets.UTF_8), "stderr").
				endsWith(": error: " + prefix + "byte 0xc3 in position 2: invalid continuation byte\n");
		}
	}

	/**
	 * Runs the command through the registered entry point.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void runsRegisteredWindowsCommand() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path durations = fixture.write("durations.tsv", "");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream error = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{COMMAND, "--shard", "1", "--shard-count", "2", "--durations",
					durations.toString()}, new ByteArrayInputStream("A\nB\nC\n".getBytes(StandardCharsets.UTF_8)), out, err,
					Map.of());
				int rejected = Main.run(new String[]{COMMAND, "--shard", "3", "--shard-count", "2", "--durations",
					durations.toString()}, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				int usage = Main.run(new String[0], new ByteArrayInputStream(new byte[0]), out, err, Map.of());

				requireThat(status, "status").isEqualTo(0);
				requireThat(rejected, "rejected_status").isEqualTo(2);
				requireThat(usage, "usage_status").isEqualTo(2);
			}
			requireThat(output.toString(StandardCharsets.UTF_8), "stdout").isEqualTo("A\nC\n");
			requireThat(error.toString(StandardCharsets.UTF_8), "stderr").
				contains("Selected shard 1/2.\n" + USAGE + COMMAND + ": error: shard must be between 1 and shard count\n").
				contains("codex-tooling " + COMMAND + " --shard <number> --shard-count <number> --durations <file>");
		}
	}

	/**
	 * Runs the command with a durations table written to a fresh directory.
	 *
	 * @param stdin     the target labels
	 * @param durations the durations table
	 * @param shard     the shard to select
	 * @param shardCount the number of shards
	 * @return the status and the bytes written to each stream
	 * @throws IOException if the table cannot be written
	 */
	private static Result run(String stdin, String durations, int shard, int shardCount) throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-windows-"))
		{
			Path table = fixture.write("durations.tsv", durations);
			return runWith(stdin, table, shard, shardCount);
		}
	}

	/**
	 * Runs the command against an existing durations path.
	 *
	 * @param stdin      the target labels
	 * @param durations  the durations path
	 * @param shard      the shard to select
	 * @param shardCount the number of shards
	 * @return the status and the text written to each stream
	 */
	private static Result runWith(String stdin, Path durations, int shard, int shardCount)
	{
		return runArguments(stdin, "--shard", Integer.toString(shard), "--shard-count", Integer.toString(shardCount),
			"--durations", durations.toString());
	}

	/**
	 * Runs the command with the given arguments and fresh streams that this method closes.
	 *
	 * @param stdin     the target labels
	 * @param arguments the arguments after the command name
	 * @return the status and the text written to each stream
	 */
	private static Result runArguments(String stdin, String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int status = SelectWindowsBazelTargets.run(arguments,
				new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), out, err);
			return new Result(status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * The outcome of one invocation.
	 *
	 * @param status exit status
	 * @param stdout text written to standard output
	 * @param stderr text written to standard error
	 */
	private record Result(int status, String stdout, String stderr)
	{
	}

	/** An input stream that records whether it was closed and how often it was read. */
	private static final class ObservedInput extends ByteArrayInputStream
	{
		/** Whether the stream was closed. */
		private boolean closed;
		/** The number of read calls. */
		private int reads;

		/**
		 * Constructs an open stream.
		 *
		 * @param content the bytes that the stream supplies
		 */
		private ObservedInput(byte[] content)
		{
			super(content);
		}

		@Override
		public int read()
		{
			reads += 1;
			return super.read();
		}

		@Override
		public int read(byte[] buffer, int offset, int length)
		{
			reads += 1;
			return super.read(buffer, offset, length);
		}

		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}

	/** An output stream that records whether it was closed. */
	private static final class ObservedOutput extends ByteArrayOutputStream
	{
		/** Whether the stream was closed. */
		private boolean closed;

		/** Constructs an open stream. */
		private ObservedOutput()
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
