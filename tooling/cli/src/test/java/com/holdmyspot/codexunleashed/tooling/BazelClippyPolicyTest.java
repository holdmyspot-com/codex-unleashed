package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/** Verifies Cargo lint authority, accepted Bazel flag forms, and read-only diagnostics. */
public final class BazelClippyPolicyTest
{
	/** Creates policy tests. */
	public BazelClippyPolicyTest()
	{
	}

	/**
	 * Accepts normalized Cargo levels and both supported flag forms.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void acceptsMatchingLevels() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.cargo("a = '\u00a0ALLOW\u00a0'\nb = 'warn'\nc = 'deny'\nd = 'forbid'\n");
			fixture.bazel("--allow=clippy::a\n-Wclippy::b\n--deny=clippy::c\n-Fclippy::d\n-Dwarnings\n--warn=rustc_lint\n");
			requireThat(fixture.check(), "status").isEqualTo(0);
			requireThat(fixture.output.toString(StandardCharsets.UTF_8), "output").isEqualTo(
				"Bazel clippy flags in .bazelrc match codex-rs/Cargo.toml [workspace.lints.clippy]." + System.lineSeparator());
			requireThat(fixture.errors.size(), "stderrSize").isEqualTo(0);
		}
	}

	/**
	 * Reports ordered missing, mismatched, and extra flags with an opt-in example.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void reportsSortedDifferences() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.cargo("z = 'deny'\na = 'allow'\nb = 'warn'\n");
			fixture.bazel("--forbid=clippy::b\n--deny=clippy::extra\n");
			Path member = Files.createDirectories(fixture.root.resolve("codex-rs/member"));
			Files.writeString(member.resolve("Cargo.toml"), "[lints]\nworkspace = true\n");
			requireThat(fixture.check(), "status").isEqualTo(1);
			String report = fixture.errors.toString(StandardCharsets.UTF_8);
			requireThat(report, "report").contains("for example codex-rs/member/Cargo.toml.");
			requireThat(report.indexOf("--allow=clippy::a"), "allowPosition").isLessThan(
				report.indexOf("--deny=clippy::z"));
			requireThat(report, "report").contains("clippy::b: Cargo has warn, Bazel has forbid");
			requireThat(report, "report").contains("expected: " + Fixture.PREFIX + "--warn=clippy::b");
			requireThat(report, "report").contains("Extra Bazel entries with no Cargo counterpart:");
			requireThat(fixture.output.size(), "stdoutSize").isEqualTo(0);
		}
	}

	/**
	 * Rejects duplicate recognized flags with retained Unicode line numbers.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsDuplicatesWithLineNumbers() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.cargo("a = 'allow'\n");
			Files.writeString(fixture.bazel, "# first\u2028" + Fixture.PREFIX + "-Aclippy::a\r\n" +
				Fixture.PREFIX + "--deny=clippy::a\n");
			IOException failure = expectThrows(IOException.class, fixture::check);
			requireThat(failure.getMessage(), "message").contains("clippy::a");
			requireThat(failure.getMessage(), "message").contains(fixture.bazel + ":2 and " + fixture.bazel + ":3");
			requireThat(fixture.output.size(), "stdoutSize").isEqualTo(0);
			requireThat(fixture.errors.size(), "stderrSize").isEqualTo(0);
		}
	}

	/**
	 * Rejects non-string, unsupported, and absent Cargo lint levels.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsInvalidCargoLevels() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			for (String level : new String[]{"true", "2000-01-01", "{ level = 'deny' }", "'invalid'"})
			{
				fixture.cargo("a = " + level + "\n");
				expectThrows(IOException.class, fixture::check);
				requireThat(fixture.output.size(), "stdoutSize").isEqualTo(0);
				requireThat(fixture.errors.size(), "stderrSize").isEqualTo(0);
			}
			Files.writeString(fixture.cargo, "[workspace]\n");
			expectThrows(IOException.class, fixture::check);
		}
	}

	/**
	 * Ignores indented, uppercase, and commented flag forms.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void ignoresUnrecognizedBazelForms() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.cargo("a = 'warn'\n");
			Files.writeString(fixture.bazel, " " + Fixture.PREFIX + "--warn=clippy::extra\n" +
				Fixture.PREFIX + "--warn=clippy::A\n" + Fixture.PREFIX + "--warn=clippy::a # comment\n" +
				Fixture.PREFIX + "--warn=clippy::a\n");
			requireThat(fixture.check(), "status").isEqualTo(0);
		}
	}

	/**
	 * Dispatches defaults, caller-selected paths, help, and invalid options through the public CLI.
	 *
	 * @throws IOException if fixture writing or cleanup fails
	 */
	@Test
	public void dispatchesPublicCommand() throws IOException
	{
		try (Fixture fixture = new Fixture();
			PrintStream out = new PrintStream(fixture.output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(fixture.errors, true, StandardCharsets.UTF_8))
		{
			fixture.cargo("a = 'warn'\n");
			fixture.bazel("--warn=clippy::a\n");
			String command = "verify-bazel-clippy-lints";
			requireThat(Main.run(new String[]{command, fixture.root.toString()},
				java.io.InputStream.nullInputStream(), out, err), "status").isEqualTo(0);
			Path override = fixture.root.resolve("override.toml");
			Files.writeString(override, "[workspace.lints.clippy]\na = 'deny'\n");
			fixture.bazel("--deny=clippy::a\n");
			requireThat(Main.run(new String[]{command, fixture.root.toString(), "--cargo-toml", "missing",
				"--cargo-toml", override.toString(), "--bazelrc", fixture.bazel.toString()},
				java.io.InputStream.nullInputStream(), out, err), "status").isEqualTo(0);
			requireThat(Main.run(new String[]{command, "--help"},
				java.io.InputStream.nullInputStream(), out, err), "status").isEqualTo(0);
			requireThat(Main.run(new String[]{command}, java.io.InputStream.nullInputStream(), out, err),
				"status").isEqualTo(2);
			requireThat(Main.run(new String[]{command, fixture.root.toString(), "--unknown"},
				java.io.InputStream.nullInputStream(), out, err), "status").isEqualTo(2);
			requireThat(Main.run(new String[]{command, fixture.root.toString(), "--cargo-toml", "missing"},
				java.io.InputStream.nullInputStream(), out, err), "status").isEqualTo(1);
		}
	}

	/** Owns complete policy inputs and captured reports beneath the maintained Maven temporary root. */
	private static final class Fixture implements AutoCloseable
	{
		private static final String PREFIX = "build:clippy --@rules_rust//rust/settings:clippy_flag=";
		private final Path root = Files.createTempDirectory("bazel-clippy-policy-");
		private final Path cargo;
		private final Path bazel = root.resolve(".bazelrc");
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();
		private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

		/**
		 * Creates isolated input files.
		 *
		 * @throws IOException if inputs cannot be created
		 */
		private Fixture() throws IOException
		{
			cargo = Files.createDirectories(root.resolve("codex-rs")).resolve("Cargo.toml");
			Files.writeString(bazel, "");
		}

		/**
		 * Writes workspace lint definitions.
		 *
		 * @param lints exact lint table entries
		 * @throws IOException if writing fails
		 */
		private void cargo(String lints) throws IOException
		{
			Files.writeString(cargo, "[workspace.lints.clippy]\n" + lints);
		}

		/**
		 * Writes exact prefixed configuration entries.
		 *
		 * @param flags newline-delimited flags
		 * @throws IOException if writing fails
		 */
		private void bazel(String flags) throws IOException
		{
			Files.writeString(bazel, flags.lines().map(line -> PREFIX + line + "\n").reduce("", String::concat));
		}

		/**
		 * Captures both policy report streams.
		 *
		 * @return policy status
		 * @throws IOException if reading or validation fails
		 */
		private int check() throws IOException
		{
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				return BazelClippyPolicy.check(root, cargo, bazel, out, err);
			}
		}

		@Override
		public void close() throws IOException
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path file : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(file);
			}
		}
	}
}
