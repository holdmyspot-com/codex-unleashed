package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Main;
import com.holdmyspot.codexunleashed.tooling.SelectBazelPackages;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the {@code select-bazel-packages} command against selections that the predecessor workflow scripts
 * produced for the same inputs.
 */
public final class SelectBazelPackagesTest
{
	/** The command name. */
	private static final String COMMAND = "select-bazel-packages";
	/** Candidate labels whose packages are //a, //b, //c, //d, //e/f and //g, with //a repeated. */
	private static final String CANDIDATES = "//a:x\n//a:y\n//b:z\n//c:t\n//d:t\n//e/f:t\n//g:t\n";
	/**
	 * The package-to-shard document for {@link #CANDIDATES} with two shards.
	 */
	private static final String TWO_SHARDS = "{\n  \"//a\": 2,\n  \"//b\": 1,\n  \"//c\": 1,\n  \"//d\": 2,\n" +
		"  \"//e/f\": 2,\n  \"//g\": 2\n}\n";
	/**
	 * The query over every package of {@link #CANDIDATES}.
	 */
	private static final String QUERY = "set(//a:all //b:all //c:all //d:all //e/f:all //g:all)\n";
	/** The maintained Clippy patterns followed by manual patterns in three packages, one of them twice. */
	private static final String CLIPPY = "//codex-rs/...\n-//codex-rs/v8-poc:all\n//g:one\n//b:two\n//a:three\n" +
		"//g:one\n";
	/** The maintained Clippy patterns alone. */
	private static final String CLIPPY_PREFIX = "//codex-rs/...\n-//codex-rs/v8-poc:all\n";

	/** Constructs independent fixtures for each check. */
	public SelectBazelPackagesTest()
	{
	}

	/**
	 * Selects packages by the unsigned digest of their names, so that a digest whose first bit is set cannot select
	 * another shard.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void selectsHashedPackages() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");

			Result result = run(arguments(candidates, output, 2, 3));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 1 of 1 packages for shard 2/3\n");
			requireThat(result.stderr(), "stderr").isEmpty();
			requireThat(read(output, "selected-targets.txt"), "selected_targets").isEqualTo("abc:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").
				isEqualTo("{\n  \"abc\": 2\n}\n");
			requireThat(read(output, "all-package-patterns.query"), "query").isEqualTo("set(abc:all)\n");
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", CANDIDATES);

			Result result = run(arguments(candidates, output, 1, 2));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 2 of 6 packages for shard 1/2\n");
			requireThat(read(output, "selected-targets.txt"), "selected_targets").isEqualTo("//b:all\n//c:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").isEqualTo(TWO_SHARDS);
			requireThat(read(output, "all-package-patterns.query"), "query").isEqualTo(QUERY);
		}
	}

	/**
	 * Counts each package once however many candidate labels it has, and keeps the empty package that an empty line
	 * names.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void deduplicatesCandidatePackages() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", CANDIDATES + CANDIDATES);

			Result result = run(arguments(candidates, output, 2, 2));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 4 of 6 packages for shard 2/2\n");
			requireThat(read(output, "selected-targets.txt"), "selected_targets").
				isEqualTo("//a:all\n//d:all\n//e/f:all\n//g:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").isEqualTo(TWO_SHARDS);
			requireThat(read(output, "all-package-patterns.query"), "query").isEqualTo(QUERY);
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "no-colon\n\nwith:two:colons\n");

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(read(output, "selected-targets.txt"), "selected_targets").
				isEqualTo(":all\nno-colon:all\nwith:two:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").
				isEqualTo("{\n  \"\": 1,\n  \"no-colon\": 1,\n  \"with:two\": 1\n}\n");
		}
	}

	/**
	 * Orders packages by Unicode code point, not UTF-16 code unit and not canonical equivalence, and writes
	 * non-printable and non-ASCII names to the JSON file as lowercase ASCII escapes.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesCodePointOrder() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "//p/𐀀:t\n//p/:t\n");

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(read(output, "selected-targets.txt"), "selected_targets").
				isEqualTo("//p/:all\n//p/𐀀:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").
				isEqualTo("{\n  \"//p/\\ue000\": 1,\n  \"//p/\\ud800\\udc00\": 1\n}\n");
			requireThat(read(output, "all-package-patterns.query"), "query").
				isEqualTo("set(//p/:all //p/𐀀:all)\n");
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "//p/é:t\n//p/é:t\n");

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(read(output, "selected-targets.txt"), "selected_targets").
				isEqualTo("//p/é:all\n//p/é:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").
				isEqualTo("{\n  \"//p/e\\u0301\": 1,\n  \"//p/\\u00e9\": 1\n}\n");
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "//p/" + (char) 0x7f + ":t\n");

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(read(output, "package-shards.json"), "package_assignments").
				isEqualTo("{\n  \"//p/\\u007f\": 1\n}\n");
		}
	}

	/**
	 * Assigns every package to exactly one shard, whatever the order, line separators or repetition of the candidate
	 * labels.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void partitionsEveryPackage() throws IOException
	{
		String reversed = "//g:t\r//e/f:t\r\n//d:t //c:t\u000b//b:z\f//a:y\u0085//a:x\u001c";
		Set<String> union = new LinkedHashSet<>();
		int total = 0;
		for (int shard = 1; shard <= 2; shard += 1)
		{
			String forward;
			String permuted;
			try (TestDirectory fixture = new TestDirectory("select-packages-"))
			{
				Path output = fixture.createDirectories("out");
				Path candidates = fixture.write("candidates.txt", CANDIDATES);
				requireThat(run(arguments(candidates, output, shard, 2)).status(), "status").isEqualTo(0);
				forward = read(output, "selected-targets.txt");
			}
			try (TestDirectory fixture = new TestDirectory("select-packages-"))
			{
				Path output = fixture.createDirectories("out");
				Path candidates = fixture.write("candidates.txt", reversed);
				requireThat(run(arguments(candidates, output, shard, 2)).status(), "status").isEqualTo(0);
				permuted = read(output, "selected-targets.txt");
			}
			requireThat(permuted, "selected_targets").isEqualTo(forward);
			for (String pattern : forward.split("\n"))
			{
				union.add(pattern);
				total += 1;
			}
		}
		requireThat(total, "total").isEqualTo(6);
		requireThat(union, "package_assignments").isEqualTo(
			new LinkedHashSet<>(List.of("//a:all", "//b:all", "//c:all", "//d:all", "//e/f:all", "//g:all")));
	}

	/**
	 * Rejects a shard that owns no package before any output file exists.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsEmptyPackageSelection() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");

			Result result = run(arguments(candidates, output, 1, 3));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stdout(), "stdout").isEmpty();
			requireThat(result.stderr(), "stderr").contains("No packages selected for shard 1");
			requireThat(listing(output), "files").isEmpty();
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "");

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stderr(), "stderr").contains("No packages selected for shard 1");
			requireThat(listing(output), "files").isEmpty();
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "//b:z\n");
			Path clippy = fixture.write("clippy.txt", CLIPPY_PREFIX + "//b:one\n");

			Result result = run(clippyArguments(candidates, clippy, output, 2));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stderr(), "stderr").contains("No Clippy packages selected for shard 2");
			requireThat(listing(output), "files").isEmpty();
		}
	}

	/**
	 * Emits sorted packages, then the manual patterns of the shard in their original order with duplicates, then the
	 * exclusion, and counts every emitted pattern.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void retainsClippyManualOrder() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", CANDIDATES);
			Path clippy = fixture.write("clippy.txt", CLIPPY);

			Result result = run(clippyArguments(candidates, clippy, output, 2));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 8 Clippy patterns for package shard 2/2\n");
			requireThat(result.stderr(), "stderr").isEmpty();
			requireThat(read(output, "selected-targets.txt"), "selected_targets").isEqualTo(
				"//a:all\n//d:all\n//e/f:all\n//g:all\n//g:one\n//a:three\n//g:one\n-//codex-rs/v8-poc:all\n");
			requireThat(read(output, "package-shards.json"), "package_assignments").isEqualTo(TWO_SHARDS);
			requireThat(read(output, "all-package-patterns.query"), "query").isEqualTo(QUERY);
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", CANDIDATES);
			Path clippy = fixture.write("clippy.txt", CLIPPY);

			Result result = run(clippyArguments(candidates, clippy, output, 1));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 4 Clippy patterns for package shard 1/2\n");
			requireThat(read(output, "selected-targets.txt"), "selected_targets").
				isEqualTo("//b:all\n//c:all\n//b:two\n-//codex-rs/v8-poc:all\n");
		}
	}

	/**
	 * Rejects a pattern list whose first two patterns are not the maintained ones, in either position.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsChangedClippyScope() throws IOException
	{
		for (String patterns : List.of("//codex-rs/...\n", "", "-//codex-rs/v8-poc:all\n//codex-rs/...\n",
			"//codex-rs/...\n//g:one\n", " //codex-rs/...\n-//codex-rs/v8-poc:all\n"))
		{
			try (TestDirectory fixture = new TestDirectory("select-packages-"))
			{
				Path output = fixture.createDirectories("out");
				Path candidates = fixture.write("candidates.txt", CANDIDATES);
				Path clippy = fixture.write("clippy.txt", patterns);

				Result result = run(clippyArguments(candidates, clippy, output, 1));

				requireThat(result.status(), "status").isEqualTo(1);
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").contains("Maintained Clippy scope changed");
				requireThat(listing(output), "files").isEmpty();
			}
		}
	}

	/**
	 * Rejects a manual pattern that does not start with two slashes or has no colon, even when another shard owns it.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsMalformedManualPattern() throws IOException
	{
		for (String pattern : List.of("bad", "//x", "/b:one", "-//b:one", "\n//b:one"))
		{
			try (TestDirectory fixture = new TestDirectory("select-packages-"))
			{
				Path output = fixture.createDirectories("out");
				Path candidates = fixture.write("candidates.txt", CANDIDATES);
				Path clippy = fixture.write("clippy.txt", CLIPPY_PREFIX + pattern + "\n");

				Result result = run(clippyArguments(candidates, clippy, output, 1));

				requireThat(result.status(), "status").isEqualTo(1);
				requireThat(result.stderr(), "stderr").contains("Unexpected manual Clippy pattern");
				requireThat(listing(output), "files").isEmpty();
			}
		}
	}

	/**
	 * Rejects a manual pattern whose package no candidate label names, including a pattern that another shard owns.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsManualPackageOutsideCandidates() throws IOException
	{
		for (int shard = 1; shard <= 2; shard += 1)
		{
			try (TestDirectory fixture = new TestDirectory("select-packages-"))
			{
				Path output = fixture.createDirectories("out");
				Path candidates = fixture.write("candidates.txt", CANDIDATES);
				Path clippy = fixture.write("clippy.txt", CLIPPY_PREFIX + "//b:one\n//zz:one\n");

				Result result = run(clippyArguments(candidates, clippy, output, shard));

				requireThat(result.status(), "status").isEqualTo(1);
				requireThat(result.stderr(), "stderr").contains("Manual Clippy target outside package selection");
				requireThat(listing(output), "files").isEmpty();
			}
		}
	}

	/**
	 * Leaves the caller's output and error streams open and flushed, and reads no standard input, after a success, a
	 * policy rejection and invalid arguments.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesBorrowedStreams() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-");
			ToolingScope scope = new DefaultToolingScope();
			ClosureRecorder output = new ClosureRecorder();
			ClosureRecorder error = new ClosureRecorder();
			PrintStream out = new PrintStream(output, false, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, false, StandardCharsets.UTF_8))
		{
			Path directory = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");

			int success = SelectBazelPackages.run(scope, arguments(candidates, directory, 2, 3), out, err);
			int rejected = SelectBazelPackages.run(scope, arguments(candidates, directory, 1, 3), out, err);
			int invalid = SelectBazelPackages.run(scope, new String[]{"--shard"}, out, err);
			int help = SelectBazelPackages.run(scope, new String[]{"--help"}, out, err);

			requireThat(success, "success_status").isEqualTo(0);
			requireThat(rejected, "rejected_status").isEqualTo(1);
			requireThat(invalid, "invalid_status").isEqualTo(2);
			requireThat(help, "help_status").isEqualTo(0);
			requireThat(output.closed, "stdout_closed").isFalse();
			requireThat(error.closed, "stderr_closed").isFalse();
			requireThat(out.checkError(), "stdout_failed").isFalse();
			requireThat(err.checkError(), "stderr_failed").isFalse();
			requireThat(output.toString(StandardCharsets.UTF_8), "stdout").
				startsWith("Selected 1 of 1 packages for shard 2/3\nUsage: codex-tooling " + COMMAND);
			requireThat(error.toString(StandardCharsets.UTF_8), "stderr").
				contains("No packages selected for shard 1").contains("Option --shard needs a value");
		}
	}

	/**
	 * Reports a missing, undecodable or unwritable file with its path and status one, and invalid arguments with
	 * status two.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsInputAndOutputFailures() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path missing = fixture.getPath().resolve("missing.txt");

			Result result = run(arguments(missing, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stderr(), "stderr").contains("Cannot read " + missing);
			requireThat(listing(output), "files").isEmpty();
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.getPath().resolve("candidates.txt");
			Files.write(candidates, new byte[]{'/', '/', 'a', ':', 't', '\n', (byte) 0xc3, '\n'});

			Result result = run(arguments(candidates, output, 1, 1));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stderr(), "stderr").contains("Cannot read " + candidates);
			requireThat(listing(output), "files").isEmpty();
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path missing = fixture.getPath().resolve("missing");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");

			Result result = run(arguments(candidates, missing, 2, 3));

			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.stdout(), "stdout").isEmpty();
			requireThat(result.stderr(), "stderr").contains("Cannot write " + missing.resolve("selected-targets.txt"));
		}
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");
			String[] valid = arguments(candidates, output, 2, 3);
			for (String[] invalid : List.of(new String[0], new String[]{"--help", "--shard", "1"},
				new String[]{"--unknown", "1"}, new String[]{valid[0], valid[1]}, withValue(valid, "--shard", "x"),
				withValue(valid, "--shard", "-"), withValue(valid, "--shard", "1.0"),
				withValue(valid, "--shard-count", "99999999999"), withValue(valid, "--shard-count", "1_0"),
				concatenate(valid, "--shard", "1")))
			{
				Result result = run(invalid);

				requireThat(result.status(), "status").isEqualTo(2);
				requireThat(result.stdout(), "stdout").isEmpty();
				requireThat(result.stderr(), "stderr").startsWith("Usage: codex-tooling " + COMMAND);
				requireThat(listing(output), "files").isEmpty();
			}
			Result tooLarge = run(withValue(valid, "--shard", "4"));
			requireThat(tooLarge.status(), "status").isEqualTo(1);
			requireThat(tooLarge.stderr(), "stderr").contains("must be between 1 and the shard count 3");
			Result zeroCount = run(withValue(withValue(valid, "--shard", "1"), "--shard-count", "0"));
			requireThat(zeroCount.status(), "status").isEqualTo(1);
			requireThat(zeroCount.stderr(), "stderr").contains("Shard count must be positive");
			Result clippyCount = run(concatenate(valid, "--clippy-targets", candidates.toString()));
			requireThat(clippyCount.status(), "status").isEqualTo(1);
			requireThat(clippyCount.stderr(), "stderr").contains("requires shard count 2");
			Result signed = run(withValue(valid, "--shard", "+2"));
			requireThat(signed.status(), "status").isEqualTo(0);
			Result decimal = run(withValue(valid, "--shard", "٢"));
			requireThat(decimal.status(), "status").isEqualTo(0);
			Result supplementary = run(withValue(valid, "--shard", "𝟐"));
			requireThat(supplementary.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Runs the command through the registered entry point and prints its usage with the other commands' usage.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void runsRegisteredPackageCommand() throws IOException
	{
		try (TestDirectory fixture = new TestDirectory("select-packages-"))
		{
			Path output = fixture.createDirectories("out");
			Path candidates = fixture.write("candidates.txt", "abc:t\n");
			List<String> command = new ArrayList<>();
			command.add(COMMAND);
			command.addAll(List.of(arguments(candidates, output, 2, 3)));

			Result result = runRegistered(command.toArray(new String[0]));

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.stdout(), "stdout").isEqualTo("Selected 1 of 1 packages for shard 2/3\n");
			requireThat(result.stderr(), "stderr").isEmpty();
			requireThat(read(output, "selected-targets.txt"), "selected_targets").isEqualTo("abc:all\n");

			Result usage = runRegistered(new String[0]);

			requireThat(usage.status(), "status").isEqualTo(2);
			requireThat(usage.stderr(), "stderr").contains("codex-tooling " + COMMAND + " --candidates <file>");
		}
	}

	/**
	 * Builds the arguments of a baseline selection.
	 *
	 * @param candidates the candidate label file
	 * @param output     the output directory
	 * @param shard      the shard to select
	 * @param shardCount the number of shards
	 * @return the arguments after the command name
	 */
	private static String[] arguments(Path candidates, Path output, int shard, int shardCount)
	{
		return new String[]
		{
			"--candidates", candidates.toString(), "--output-directory", output.toString(), "--shard",
			Integer.toString(shard), "--shard-count", Integer.toString(shardCount)
		};
	}

	/**
	 * Builds the arguments of a Clippy selection.
	 *
	 * @param candidates the candidate label file
	 * @param clippy     the Clippy pattern file
	 * @param output     the output directory
	 * @param shard      the shard to select
	 * @return the arguments after the command name
	 */
	private static String[] clippyArguments(Path candidates, Path clippy, Path output, int shard)
	{
		return concatenate(arguments(candidates, output, shard, 2), "--clippy-targets", clippy.toString());
	}

	/**
	 * Appends an option and its value.
	 *
	 * @param arguments the original arguments
	 * @param option    the option to append
	 * @param value     the option's value
	 * @return the new arguments
	 */
	private static String[] concatenate(String[] arguments, String option, String value)
	{
		String[] result = Arrays.copyOf(arguments, arguments.length + 2);
		result[arguments.length] = option;
		result[arguments.length + 1] = value;
		return result;
	}

	/**
	 * Replaces an option's value.
	 *
	 * @param arguments the original arguments
	 * @param option    the option whose value changes
	 * @param value     the new value
	 * @return the new arguments
	 */
	private static String[] withValue(String[] arguments, String option, String value)
	{
		String[] result = arguments.clone();
		for (int index = 0; index + 1 < result.length; index += 2)
		{
			if (result[index].equals(option))
				result[index + 1] = value;
		}
		return result;
	}

	/**
	 * Reads one output file.
	 *
	 * @param directory the output directory
	 * @param name      the file name
	 * @return the file's UTF-8 content
	 * @throws IOException if the file cannot be read
	 */
	private static String read(Path directory, String name) throws IOException
	{
		return Files.readString(directory.resolve(name));
	}

	/**
	 * Lists the names in a directory.
	 *
	 * @param directory the directory
	 * @return the entry names
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<String> listing(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Runs the operation with fresh streams that this method closes.
	 *
	 * @param arguments the arguments after the command name
	 * @return the status and the text written to each stream
	 */
	private static Result run(String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (ToolingScope scope = new DefaultToolingScope();
			PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int status = SelectBazelPackages.run(scope, arguments, out, err);
			return new Result(status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Runs the registered entry point with fresh streams that this method closes.
	 *
	 * @param arguments the command and its arguments
	 * @return the status and the text written to each stream
	 */
	private static Result runRegistered(String[] arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
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

	/** A byte sink that records whether it was closed. */
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
