package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the README ToC verification command through the public CLI.
 */
public final class VerifyReadmeTocTest
{
	/**
	 * Creates the test class.
	 */
	public VerifyReadmeTocTest()
	{
	}

	/**
	 * Pattern for unified-diff hunk headers.
	 */
	private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
	/**
	 * Exact mismatch summary emitted by the CLI.
	 */
	private static final String SUMMARY = "ERROR: README ToC is out of date. Diff between existing and generated ToC:\n";

	/**
	 * Accepts a matching ToC for an explicit README path.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void acceptsMatchingToc() throws IOException
	{
		try (Fixture fixture = new Fixture("## Home\n### Child\n<!-- Begin ToC -->\n- [Home](#home)\n" +
			"  - [Child](#child)\n<!-- End ToC -->\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.out, "output").isEmpty();
			requireThat(result.err, "diagnostics").isEmpty();
		}
	}

	/**
	 * Skips the check when either ToC marker is absent.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void skipsMissingMarkers() throws IOException
	{
		try (Fixture fixture = new Fixture("## Heading\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.out, "skip_note").isEqualTo(
				"Note: Skipping ToC check; no markers found in " + fixture.path + ".\n");
			requireThat(result.err, "diagnostics").isEmpty();
		}
	}

	/**
	 * Preserves first-marker selection and literal slicing for duplicate or reversed markers.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void preservesFirstMarkerBehavior() throws IOException
	{
		try (Fixture duplicate = new Fixture("## Before\n<!-- Begin ToC -->\n- [Before](#before)\n" +
			"<!-- Begin ToC -->\n## Inside\n<!-- End ToC -->\n## After\n");
			Fixture reversed = new Fixture("## Before\n<!-- End ToC -->\n- [old](#old)\n" +
				"<!-- Begin ToC -->\n## After\n"))
		{
			Result duplicateResult = invoke(duplicate.path.toString());
			Result reversedResult = invoke(reversed.path.toString());
			requireThat(duplicateResult.out, "duplicate_markers").contains("+- [After](#after)");
			requireThat(reversedResult.out, "reversed_markers").contains("+- [Before](#before)").
				contains("+- [After](#after)");
		}
	}

	/**
	 * Toggles only backtick fences and retains headings under tilde fences.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void ignoresBacktickFenceHeadings() throws IOException
	{
		try (Fixture fixture = new Fixture("## Before\n```lang\n## Hidden\n  ``` trailing\n## After\n" +
			"~~~\n## TildeVisible\n~~~\n<!-- Begin ToC -->\n<!-- End ToC -->\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.out, "generated_headings").contains("+- [Before](#before)").
				contains("+- [After](#after)").contains("+- [TildeVisible](#tildevisible)").
				doesNotContain("Hidden");
		}
	}

	/**
	 * Retains column-zero heading rules, levels, Unicode whitespace, markup, and duplicates.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void preservesRawHeadingRules() throws IOException
	{
		String headings = " ## Indented\n##No separator\n####### Too deep\n##\u00a0NBSP separator\n" +
			"##  Word \n## Duplicate\n## Duplicate\n";
		try (Fixture fixture = new Fixture(headings + "<!-- Begin ToC -->\n<!-- End ToC -->\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.out, "raw_heading_lines").contains("+- [NBSP separator](#nbsp-separator)").
				contains("+- [Word](#word)").contains("+- [Duplicate](#duplicate)");
			String duplicateEntry = "+- [Duplicate](#duplicate)";
			requireThat(count(result.out, duplicateEntry), "duplicate_headings").isEqualTo(2);
			requireThat(result.out, "ignored_headings").doesNotContain("Indented").doesNotContain("Too deep");
		}
	}

	/**
	 * Preserves the selected Unicode lowercase, punctuation, whitespace, and repeated-space slug rules.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void preservesSlugCharacters() throws IOException
	{
		String headings = "## A B‑C–D—E\n### Straße K K\n#### dotted İ é\n" +
			"##### raw `x` & closing ###\n###### repeat  a\t b\n";
		try (Fixture fixture = new Fixture(headings + "<!-- Begin ToC -->\n<!-- End ToC -->\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.out, "legacy_slug_output").contains("+- [A B‑C–D—E](#a-b-c-d-e)").
				contains("+  - [Straße K K](#strae-k-k)").
				contains("+    - [dotted İ é](#dotted-i-e)").
				contains("+      - [raw `x` & closing ###](#raw-x--closing)");
			requireThat(result.out, "tab_and_repeated-space_slug").contains("- [repeat  a\t b](#repeat--a\t-b)");
		}
	}

	/**
	 * Counts indented list lines and trailing spaces while ignoring other ToC block prose.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void preservesCurrentListLines() throws IOException
	{
		try (Fixture fixture = new Fixture("## Alpha\n<!-- Begin ToC -->\n prose\n- [Alpha](#alpha)  \n" +
			"    - [not generated](#x)\n* [ignored](#ignored)\n<!-- End ToC -->\n"))
		{
			Result result = invoke(fixture.path.toString());
			assertPatch(result, List.of("- [Alpha](#alpha)  ", "    - [not generated](#x)"),
				List.of("- [Alpha](#alpha)"));
		}
	}

	/**
	 * Applies emitted unified diffs for insert, delete, replace, empty, repeated-line, and context cases.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void emitsApplicableUnifiedDiff() throws IOException
	{
		assertDiff(List.of(), List.of("- [A](#a)", "- [B](#b)"), "## A\n## B\n");
		assertDiff(List.of("- [A](#a)", "- [B](#b)"), List.of("- [A](#a)"), "## A\n");
		assertDiff(List.of("- [A](#a)"), List.of("- [A](#a)", "- [B](#b)"), "## A\n## B\n");
		assertDiff(List.of("- [A](#a)", "- [B](#b)"), List.of("- [A](#a)", "- [C](#c)"), "## A\n## C\n");
		assertDiff(List.of("- [A](#a)", "- [B](#b)", "- [A](#a)", "- [B](#b)", "- [A](#a)"),
			List.of("- [A](#a)", "- [A](#a)", "- [B](#b)", "- [A](#a)"),
			"## A\n## A\n## B\n## A\n");
		StringBuilder headings = new StringBuilder();
		List<String> current = new ArrayList<>();
		List<String> expected = new ArrayList<>();
		for (int index = 0; index < 11; index += 1)
		{
			headings.append("## H").append(index).append('\n');
			expected.add("- [H" + index + "](#h" + index + ")");
			if (index == 4)
			{
				current.add("- [Old](#old)");
			}
			else
			{
				current.add(expected.get(index));
			}
		}
		assertDiff(current, expected, headings.toString());
	}

	/**
	 * Rejects malformed UTF-8 and missing or directory paths without success reports.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void rejectsMalformedUtf8AndReadFailure() throws IOException
	{
		Path directory = Files.createTempDirectory("readme toc directory ");
		try (Fixture malformed = new Fixture(new byte[]{'#', '#', ' ', (byte) 0xff, '\n'}))
		{
			Result malformedResult = invoke(malformed.path.toString());
			Result directoryResult = invoke(directory.toString());
			Result missingResult = invoke(directory.resolve("missing file.md").toString());
			requireThat(malformedResult.status, "decode_status").isEqualTo(1);
			requireThat(malformedResult.out, "decode_output").isEmpty();
			requireThat(malformedResult.err, "decode_failure").isEqualTo("Error: failed to decode " + malformed.path +
				": 'utf-8' codec can't decode byte 0xff in position 3: invalid start byte\n");
			requireThat(directoryResult.status, "directory_status").isEqualTo(1);
			requireThat(directoryResult.err, "directory_message").isEqualTo(
				"Error: file not found: " + directory + "\n");
			requireThat(missingResult.err, "missing_path_spelling").isEqualTo(
				"Error: file not found: " + directory.resolve("missing file.md") + "\n");
		}
		finally
		{
			Files.deleteIfExists(directory);
		}
	}

	/**
	 * Names the path, the malformed byte or byte range, its zero-based position and the reason in the decode failure,
	 * like CPython's {@code UnicodeDecodeError}, without a mismatch report.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void reportsMalformedUtf8Cause() throws IOException
	{
		assertDecodeFailure(new byte[]{'#', '#', ' ', (byte) 0xe2, '(', (byte) 0xa1},
			"byte 0xe2 in position 3: invalid continuation byte");
		assertDecodeFailure(new byte[]{'#', '#', ' ', (byte) 0xe2, (byte) 0x82, '('},
			"bytes in position 3-4: invalid continuation byte");
		assertDecodeFailure(new byte[]{'#', '#', ' ', 'A', '\n', (byte) 0xe2, (byte) 0x82},
			"bytes in position 5-6: unexpected end of data");
		assertDecodeFailure(new byte[]{(byte) 0xc0, (byte) 0x80}, "byte 0xc0 in position 0: invalid start byte");
	}

	/**
	 * Rejects unsupported syntax and keeps caller streams and input bytes unchanged.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void rejectsSyntaxAndPreservesInput() throws IOException
	{
		Result missing = invoke();
		Result extra = invoke("README.md", "extra");
		Result fix = invoke("--fix");
		for (Result result : new Result[]{missing, extra, fix})
		{
			requireThat(result.status, "status").isEqualTo(2);
			requireThat(result.err, "usage").isEqualTo("Usage: codex-tooling verify-readme-toc <file>\n");
			requireThat(result.out, "output").isEmpty();
		}
		try (Fixture fixture = new Fixture("## A\n<!-- Begin ToC -->\n<!-- End ToC -->\n"))
		{
			byte[] original = Files.readAllBytes(fixture.path);
			ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"verify-readme-toc", fixture.path.toString()},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				out.print("retained");
				err.print("retained");
				requireThat(status, "status").isEqualTo(1);
				requireThat(outBytes.toString(StandardCharsets.UTF_8), "caller_output").endsWith("retained");
				requireThat(errBytes.toString(StandardCharsets.UTF_8), "caller_diagnostics").isEqualTo("retained");
			}
			requireThat(Files.readAllBytes(fixture.path).length, "input_length").isEqualTo(original.length);
			requireThat(Files.readString(fixture.path), "input_text").isEqualTo(
				"## A\n<!-- Begin ToC -->\n<!-- End ToC -->\n");
		}
	}

	/**
	 * Verifies that malformed UTF-8 fails with status one, empty stdout and a stderr line naming the path and cause.
	 *
	 * @param contents the README bytes to verify
	 * @param cause    the expected cause that follows the codec name, such as {@code byte 0xff in position 0: ...}
	 * @throws IOException if the temporary fixture cannot be created or deleted
	 */
	private static void assertDecodeFailure(byte[] contents, String cause) throws IOException
	{
		try (Fixture fixture = new Fixture(contents))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "decode_status").isEqualTo(1);
			requireThat(result.out, "decode_output").isEmpty();
			requireThat(result.err, "decode_failure").isEqualTo("Error: failed to decode " + fixture.path +
				": 'utf-8' codec can't decode " + cause + "\n");
		}
	}

	/**
	 * Verifies that a mismatch diff reconstructs the generated ToC.
	 *
	 * @param current current ToC lines
	 * @param expected expected generated ToC lines
	 * @param headings README headings before the ToC block
	 * @throws IOException if the temporary fixture cannot be created or read
	 */
	private static void assertDiff(List<String> current, List<String> expected, String headings) throws IOException
	{
		StringBuilder content = new StringBuilder(headings).append("<!-- Begin ToC -->\n");
		for (String line : current)
		{
			content.append(line).append('\n');
		}
		content.append("<!-- End ToC -->\n");
		try (Fixture fixture = new Fixture(content.toString()))
		{
			assertPatch(invoke(fixture.path.toString()), current, expected);
		}
	}

	/**
	 * Checks mismatch output and applies its diff to the current ToC.
	 *
	 * @param result CLI result to verify
	 * @param current current ToC lines
	 * @param expected expected generated ToC lines
	 */
	private static void assertPatch(Result result, List<String> current, List<String> expected)
	{
		requireThat(result.status, "mismatch_status").isEqualTo(1);
		requireThat(result.out, "mismatch_summary").startsWith(SUMMARY);
		requireThat(result.err, "mismatch_diagnostics").isEmpty();
		List<String> diff = result.out.lines().skip(1).toList();
		requireThat(applyUnifiedDiff(current, diff), "reconstructed_ToC").isEqualTo(expected);
	}

	/**
	 * Applies unified-diff hunks to current ToC lines for verification.
	 *
	 * @param current original ToC lines
	 * @param diff unified diff lines
	 * @return reconstructed ToC lines
	 * @throws IllegalArgumentException if a diff hunk or line is malformed
	 */
	private static List<String> applyUnifiedDiff(List<String> current, List<String> diff)
	{
		List<String> result = new ArrayList<>();
		int sourceIndex = 0;
		for (int index = 2; index < diff.size();)
		{
			String hunkHeader = diff.get(index);
			index += 1;
			Matcher hunk = HUNK.matcher(hunkHeader);
			if (!hunk.matches())
			{
				throw new IllegalArgumentException("Invalid unified diff hunk header");
			}
			int oldStart = Integer.parseInt(hunk.group(1));
			int oldCount;
			if (hunk.group(2) == null)
			{
				oldCount = 1;
			}
			else
			{
				oldCount = Integer.parseInt(hunk.group(2));
			}
			int hunkStart;
			if (oldCount == 0)
			{
				hunkStart = Math.min(oldStart, current.size());
			}
			else
			{
				hunkStart = oldStart - 1;
			}
			while (sourceIndex < hunkStart)
			{
				result.add(current.get(sourceIndex));
				sourceIndex += 1;
			}
			int consumed = 0;
			while (index < diff.size() && !diff.get(index).startsWith("@@ "))
			{
				String line = diff.get(index);
				index += 1;
				if (line.startsWith(" "))
				{
					String existing = current.get(sourceIndex);
					sourceIndex += 1;
					consumed += 1;
					requireThat(existing, "context_line").isEqualTo(line.substring(1));
					result.add(existing);
				}
				else if (line.startsWith("-"))
				{
					String existing = current.get(sourceIndex);
					sourceIndex += 1;
					consumed += 1;
					requireThat(existing, "deleted_line").isEqualTo(line.substring(1));
				}
				else if (line.startsWith("+"))
				{
					result.add(line.substring(1));
				}
				else if (!line.startsWith("\\"))
				{
					throw new IllegalArgumentException("Invalid unified diff line");
				}
			}
			requireThat(consumed, "old_hunk_line_count").isEqualTo(oldCount);
		}
		while (sourceIndex < current.size())
		{
			result.add(current.get(sourceIndex));
				sourceIndex += 1;
		}
		return result;
	}

	/**
	 * Invokes the public CLI with isolated caller-owned streams.
	 *
	 * @param arguments command arguments
	 * @return captured process status and streams
	 */
	private static Result invoke(String... arguments)
	{
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		String[] invocation = new String[arguments.length + 1];
		invocation[0] = "verify-readme-toc";
		System.arraycopy(arguments, 0, invocation, 1, arguments.length);
		try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(invocation, new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			return new Result(status, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Captures status and streams returned by the CLI.
	 *
	 * @param status process status
	 * @param out standard output
	 * @param err standard error
	 */
	private record Result(int status, String out, String err)
	{
	}

	/**
	 * Owns a temporary README file used by one test.
	 */
	private static final class Fixture implements AutoCloseable
	{
		/**
		 * Temporary README path.
		 */
		private final Path path;

		/**
		 * Creates a UTF-8 README fixture.
		 *
		 * @param text fixture text
		 * @throws IOException if fixture creation or writing fails
		 */
		Fixture(String text) throws IOException
		{
			this(text.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Creates a byte-exact README fixture.
		 *
		 * @param contents fixture bytes
		 * @throws IOException if fixture creation or writing fails
		 */
		Fixture(byte[] contents) throws IOException
		{
			path = Files.createTempFile("toc fixture ", ".md");
			Files.write(path, contents);
		}

		/**
		 * Deletes the temporary README fixture.
		 *
		 * @throws IOException if deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			Files.deleteIfExists(path);
		}
	}

	/**
	 * Counts non-overlapping occurrences of a substring.
	 *
	 * @param source text to search
	 * @param term substring to count
	 * @return number of non-overlapping matches
	 */
	private static int count(String source, String term)
	{
		int count = 0;
		int offset = 0;
		int next = source.indexOf(term, offset);
		while (next >= 0)
		{
			count += 1;
			offset = next + term.length();
			next = source.indexOf(term, offset);
		}
		return count;
	}
}
