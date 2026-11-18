package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the README character verification command through the public CLI.
 */
public final class VerifyReadmeCharactersTest
{
	/**
	 * Creates the test class.
	 */
	public VerifyReadmeCharactersTest()
	{
	}

	/**
	 * Accepts the allowed ASCII, LF, and sparkles input.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void acceptsAllowedReadme() throws IOException
	{
		try (Fixture fixture = new Fixture("# Heading\n✨\n"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(0);
			requireThat(result.out, "output").isEmpty();
			requireThat(result.err, "diagnostics").isEmpty();
		}
	}

	/**
	 * Reports controls, CRLF, and Python split-line boundaries in source order.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void rejectsControlsAndCrLf() throws IOException
	{
		try (Fixture fixture = new Fixture("A\tB\r\nC\u007f\u0000\u001c\u001d\u001e\u001f\u0085\u2028\u2029"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.out, "invalid_character_diagnostics").isEqualTo(
				"Invalid character at line 1, column 2: U+0009 (\\t)\n" +
				"Invalid character at line 1, column 4: U+000D (\\r)\n" +
				"Invalid character at line 2, column 2: U+007F (\\x7f)\n" +
				"Invalid character at line 2, column 3: U+0000 (\\x00)\n" +
				"Invalid character at line 2, column 4: U+001C (\\x1c)\n" +
				"Invalid character at line 3, column 1: U+001D (\\x1d)\n" +
				"Invalid character at line 4, column 1: U+001E (\\x1e)\n" +
				"Invalid character at line 5, column 1: U+001F (\\x1f)\n" +
				"Invalid character at line 5, column 2: U+0085 (\\x85)\n" +
				"Invalid character at line 6, column 1: U+2028 (\\u2028)\n" +
				"Invalid character at line 7, column 1: U+2029 (\\u2029)\n");
			requireThat(result.err, "diagnostics_stream").isEmpty();
		}
	}

	/**
	 * Counts supplementary characters as one code-point column.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void countsSupplementaryColumns() throws IOException
	{
		try (Fixture fixture = new Fixture("🚀\t✨\n🚀x"))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.out, "supplementary_diagnostics").isEqualTo(
				"Invalid character at line 1, column 1: U+1F680 (🚀)\n" +
				"Invalid character at line 1, column 2: U+0009 (\\t)\n" +
				"Invalid character at line 2, column 1: U+1F680 (🚀)\n");
		}
	}

	/**
	 * Matches Python repr escapes and printable Unicode in diagnostics.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void reportsPythonEscapes() throws IOException
	{
		int[] codePoints = {0x5c, 0x00, 0x08, 0x0b, 0x0c, 0x1b, 0x7f, 0x85, 0xa0, 0x200b, 0x2728, 0xe000, 0xffff};
		String text = new String(codePoints, 0, codePoints.length) + new String(Character.toChars(0xf0000));
		try (Fixture fixture = new Fixture(text))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.out, "Python_repr_diagnostics").isEqualTo(
				"Invalid character at line 1, column 2: U+0000 (\\x00)\n" +
				"Invalid character at line 1, column 3: U+0008 (\\x08)\n" +
				"Invalid character at line 1, column 4: U+000B (\\x0b)\n" +
				"Invalid character at line 2, column 1: U+000C (\\x0c)\n" +
				"Invalid character at line 3, column 1: U+001B (\\x1b)\n" +
				"Invalid character at line 3, column 2: U+007F (\\x7f)\n" +
				"Invalid character at line 3, column 3: U+0085 (\\x85)\n" +
				"Invalid character at line 4, column 1: U+00A0 (\\xa0)\n" +
				"Invalid character at line 4, column 2: U+200B (\\u200b)\n" +
				"Invalid character at line 4, column 4: U+E000 (\\ue000)\n" +
				"Invalid character at line 4, column 5: U+FFFF (\\uffff)\n" +
				"Invalid character at line 4, column 6: U+F0000 (\\U000f0000)\n");
		}
	}

	/**
	 * Reports the decoder-selected byte offset and Python-compatible malformed UTF-8 reason.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void reportsDecodeByteLocation() throws IOException
	{
		try (Fixture fixture = new Fixture(new byte[]{(byte) 0xc3, (byte) 0xa9, '\n', 'A', (byte) 0xe2, '(',
			(byte) 0xa1}))
		{
			Result result = invoke(fixture.path.toString());
			requireThat(result.status, "status").isEqualTo(1);
			requireThat(result.out, "decode_diagnostic").isEqualTo("UTF-8 decoding error:\n" +
				"  byte offset: 4\n  reason: invalid continuation byte\n  location: line 2, column 2\n");
			requireThat(result.err, "diagnostics_stream").isEmpty();
		}
	}

	/**
	 * Rejects missing and extra arguments and unsupported fix flags with status two.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void rejectsSyntaxAndFix() throws IOException
	{
		Result missing = invoke();
		Result extra = invoke("README.md", "extra");
		Result fix = invoke("--fix", "README.md");
		for (Result result : new Result[]{missing, extra, fix})
		{
			requireThat(result.status, "status").isEqualTo(2);
			requireThat(result.err, "usage").isEqualTo(
				"Usage: codex-tooling verify-readme-characters <file>\n");
			requireThat(result.out, "output").isEmpty();
		}
	}

	/**
	 * Reports directory and absent-file reads as operational errors.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void reportsReadFailures() throws IOException
	{
		Path directory = Files.createTempDirectory("readme directory ");
		Path missing = directory.resolve("missing file.md");
		Path unreadable = directory.resolve("unreadable.md");
		Files.writeString(unreadable, "readable before chmod");
		// A file that is unreadable by mode needs POSIX permissions; the directory and missing-file cases run everywhere.
		boolean posix = Files.getFileStore(unreadable).supportsFileAttributeView("posix");
		try
		{
			if (posix)
				Files.setPosixFilePermissions(unreadable, Set.of());
			Result directoryResult = invoke(directory.toString());
			Result missingResult = invoke(missing.toString());
			requireThat(directoryResult.status, "directory_status").isEqualTo(1);
			requireThat(directoryResult.err, "directory_read_failure").contains(directory.toString());
			requireThat(missingResult.status, "missing_status").isEqualTo(1);
			requireThat(missingResult.err, "missing_read_failure").contains(missing.toString());
			if (posix)
			{
				Result unreadableResult = invoke(unreadable.toString());
				requireThat(unreadableResult.status, "unreadable_status").isEqualTo(1);
				requireThat(unreadableResult.err, "unreadable_read_failure").contains(unreadable.toString());
			}
			requireThat(directoryResult.out, "policy_findings").isEmpty();
		}
		finally
		{
			if (posix)
			{
				Files.setPosixFilePermissions(unreadable,
					Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
			}
			Files.deleteIfExists(unreadable);
			Files.deleteIfExists(directory);
		}
	}

	/**
	 * Keeps caller streams open and preserves input bytes and permissions on policy failure.
	 *
	 * @throws IOException if temporary fixture creation, writing, or deletion fails
	 */
	@Test
	public void leavesStreamsAndInputUnchanged() throws IOException
	{
		try (Fixture fixture = new Fixture("bad\ttext"))
		{
			byte[] original = Files.readAllBytes(fixture.path);
			// Windows has no POSIX attribute view: compare permissions only where the file system has one.
			boolean posix = Files.getFileStore(fixture.path).supportsFileAttributeView("posix");
			Set<PosixFilePermission> permissions = null;
			if (posix)
				permissions = Files.getPosixFilePermissions(fixture.path);
			ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
			ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errorBytes, true, StandardCharsets.UTF_8))
			{
				int status = Main.run(new String[]{"verify-readme-characters", fixture.path.toString()},
					new ByteArrayInputStream(new byte[0]), out, err, Map.of());
				out.print("retained");
				err.print("retained");
				requireThat(status, "status").isEqualTo(1);
				requireThat(outputBytes.toString(StandardCharsets.UTF_8), "caller_output").endsWith("retained");
				requireThat(errorBytes.toString(StandardCharsets.UTF_8), "caller_diagnostics").isEqualTo("retained");
			}
			requireThat(Arrays.equals(Files.readAllBytes(fixture.path), original), "file_bytes").isEqualTo(true);
			if (posix)
				requireThat(Files.getPosixFilePermissions(fixture.path), "file_mode").isEqualTo(permissions);
		}
	}

	/**
	 * Runs the {@code verify-readme-characters} command with isolated caller-owned streams.
	 *
	 * @param arguments the command arguments after the command name
	 * @return the status and captured streams of the invocation
	 */
	private static Result invoke(String... arguments)
	{
		ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(concat(new String[]{"verify-readme-characters"}, arguments),
				new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			return new Result(status, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Concatenates two argument arrays.
	 *
	 * @param first the leading elements
	 * @param rest  the trailing elements
	 * @return a new array holding {@code first} followed by {@code rest}
	 */
	private static String[] concat(String[] first, String[] rest)
	{
		String[] result = new String[first.length + rest.length];
		System.arraycopy(first, 0, result, 0, first.length);
		System.arraycopy(rest, 0, result, first.length, rest.length);
		return result;
	}

	/**
	 * Captures one CLI invocation.
	 *
	 * @param status the process status
	 * @param out    the standard output
	 * @param err    the standard error
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
		 * The temporary README path.
		 */
		private final Path path;

		/**
		 * Creates a UTF-8 fixture.
		 *
		 * @param text the fixture text
		 * @throws IOException if creation or writing fails
		 */
		Fixture(String text) throws IOException
		{
			this(text.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Creates a fixture from exact bytes.
		 *
		 * @param contents the fixture bytes
		 * @throws IOException if creation or writing fails
		 */
		Fixture(byte[] contents) throws IOException
		{
			path = Files.createTempFile("readme fixture ", ".md");
			Files.write(path, contents);
		}

		/**
		 * Deletes the temporary README.
		 *
		 * @throws IOException if deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			Files.deleteIfExists(path);
		}
	}
}
