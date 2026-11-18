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
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the packaged {@code verify-bazel-clippy-lints} command through {@link Main#run}.
 */
public final class VerifyBazelClippyLintsTest
{
	/**
	 * Prevents construction by callers.
	 */
	public VerifyBazelClippyLintsTest()
	{
	}

	/**
	 * Accepts aligned workspace lints and Bazel flags.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void acceptsLongAndShortFlags() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "example_lint = \"\u2003 DENY \u2003\"\nother_lint = \"warn\"\n" +
				"allow_lint = \"allow\"\nforbid_lint = \"forbid\"\n");
			writeBazel(root, "build:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::example_lint\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=-Wclippy::other_lint\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=-Aclippy::allow_lint\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=--forbid=clippy::forbid_lint\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=-Dwarnings\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=not-a-clippy-setting\n");
			Result result = run(root.toString());
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.out(), "output").isEqualTo(
				"Bazel clippy flags in .bazelrc match codex-rs/Cargo.toml [workspace.lints.clippy]." +
					System.lineSeparator());
			requireThat(result.err(), "errors").isEmpty();
		});
	}

	/**
	 * Reports each difference category in stable order and points to a workspace opt-in example.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void ordersMismatchReport() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "z_missing = \"deny\"\na_mismatch = \"forbid\"\n");
			writeBazel(root, "build:clippy --@rules_rust//rust/settings:clippy_flag=--warn=clippy::a_mismatch\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=--allow=clippy::extra\n");
			Path nested = root.resolve("codex-rs/members/z/Cargo.toml");
			Files.createDirectories(nested.getParent());
			Files.writeString(nested, "[lints]\nworkspace = true\n");
			Result result = run(root.toString());
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.out(), "output").isEmpty();
			requireThat(result.err(), "diagnostics").isEqualTo(
				"ERROR: Bazel clippy flags are out of sync with Cargo workspace clippy lints.\n\n" +
				"Cargo defines the source of truth in codex-rs/Cargo.toml [workspace.lints.clippy].\n" +
				"Cargo applies those lint levels to member crates that opt into `[lints] workspace = true`, " +
				"for example codex-rs/members/z/Cargo.toml.\n" +
				"Bazel clippy does not ingest Cargo lint levels automatically, and `clippy.toml` can configure " +
				"lint behavior but cannot set allow/warn/deny/forbid.\n" +
				"Update .bazelrc so its `build:clippy` `clippy_flag` entries match Cargo.\n\n" +
				"Missing Bazel entries:\n" +
				"  build:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::z_missing\n\n" +
				"Mismatched lint levels:\n" +
				"  clippy::a_mismatch: Cargo has forbid, Bazel has warn\n" +
				"    expected: build:clippy --@rules_rust//rust/settings:clippy_flag=--forbid=clippy::a_mismatch\n\n" +
				"Extra Bazel entries with no Cargo counterpart:\n" +
				"  build:clippy --@rules_rust//rust/settings:clippy_flag=--allow=clippy::extra\n");
		});
	}

	/**
	 * Rejects non-string and unsupported TOML levels without coercion.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void rejectsInvalidCargoLevels() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "boolean_lint = true\n");
			writeBazel(root, "");
			Result booleanResult = run(root.toString());
			requireThat(booleanResult.status(), "boolean_status").isEqualTo(1);
			requireThat(booleanResult.err(), "boolean_error").isEqualTo(
				"expected string lint level for clippy::boolean_lint in " + root.resolve("codex-rs/Cargo.toml") +
					", got True\n");

			writeCargo(root, "date_lint = 1979-05-27\n");
			Result dateResult = run(root.toString());
			requireThat(dateResult.status(), "date_status").isEqualTo(1);
			requireThat(dateResult.err(), "date_error").isEqualTo(
				"expected string lint level for clippy::date_lint in " + root.resolve("codex-rs/Cargo.toml") +
				", got datetime.date(1979, 5, 27)\n");

			writeCargo(root, "bad_level = \"unsupported\"\n");
			Result levelResult = run(root.toString());
			requireThat(levelResult.status(), "level_status").isEqualTo(1);
			requireThat(levelResult.err(), "level_error").isEqualTo(
				"unsupported lint level 'unsupported' for clippy::bad_level in " +
					root.resolve("codex-rs/Cargo.toml") + "\n");

			writeCargo(root, "number_lint = 3\n");
			requireThat(run(root.toString()).err(), "number_error").isEqualTo(
				"expected string lint level for clippy::number_lint in " + root.resolve("codex-rs/Cargo.toml") +
					", got 3\n");
			writeCargo(root, "mapping_lint = { level = \"deny\" }\n");
			requireThat(run(root.toString()).err(), "mapping_error").isEqualTo(
				"expected string lint level for clippy::mapping_lint in " + root.resolve("codex-rs/Cargo.toml") +
					", got {'level': 'deny'}\n");
			writeCargo(root, "duplicate = \"deny\"\nduplicate = \"warn\"\n");
			requireThat(run(root.toString()).status(), "duplicate_toml_status").isEqualTo(1);
			writeCargo(root, "[workspace.lints.clippy\n");
			requireThat(run(root.toString()).status(), "malformed_toml_status").isEqualTo(1);
			Files.writeString(root.resolve("codex-rs/Cargo.toml"), "[workspace]\n");
			requireThat(run(root.toString()).status(), "missing_table_status").isEqualTo(1);
		});
	}

	/**
	 * Rejects duplicate recognized flags while preserving split-line source numbering.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void rejectsDuplicateRecognizedLint() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "duplicate = \"deny\"\n");
			writeBazel(root, "ignored\r\nbuild:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::duplicate" +
				"\r\n\u000b\f\u001c\u001d\u001e\u0085\u2028\u2029" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=-Dclippy::duplicate\u2028");
			Result result = run(root.toString());
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.err(), "diagnostic").isEqualTo("duplicate Bazel clippy entry for clippy::duplicate at " +
				root.resolve(".bazelrc") + ":2 and " + root.resolve(".bazelrc") + ":11\n");
		});
	}

	/**
	 * Parses overrides, supports unambiguous option prefixes, and emits help without reading files.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void honorsLastFileOverrides() throws IOException
	{
		withFixture(root ->
		{
			Path cargo = root.resolve("custom.toml");
			Path cargoFirst = root.resolve("first.toml");
			Path bazel = root.resolve("custom.bazelrc");
			Files.writeString(cargo, "[workspace.lints.clippy]\nflag = \"forbid\"\n");
			Files.writeString(cargoFirst, "[workspace.lints.clippy]\nwrong = \"deny\"\n");
			Files.writeString(bazel, "build:clippy --@rules_rust//rust/settings:clippy_flag=-Fclippy::flag\n");
			Result result = run(root.toString(), "--cargo", "first.toml", "--cargo-toml=custom.toml",
				"--bazel=custom.bazelrc");
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.err(), "errors").isEmpty();
			requireThat(result.out(), "output").isEqualTo(
				"Bazel clippy flags in custom.bazelrc match custom.toml [workspace.lints.clippy]." +
					System.lineSeparator());
			Result absolute = run("--cargo-toml", cargo.toString(), root.toString(), "--bazelrc=" + bazel);
			requireThat(absolute.status(), "absolute_override_status").isEqualTo(0);
			Result help = run("--help");
			requireThat(help.status(), "help_status").isEqualTo(0);
			requireThat(help.out(), "help").contains("Verify that Bazel clippy flags match Cargo workspace lint levels.");
			requireThat(help.err(), "help_errors").isEmpty();
			Result syntax = run(root.toString(), "--baz");
			requireThat(syntax.status(), "syntax_status").isEqualTo(2);
			requireThat(syntax.err(), "syntax_diagnostic").isEqualTo(
				"Usage: codex-tooling verify-bazel-clippy-lints <repository-root> [--cargo-toml PATH] [--bazelrc PATH]\n");
		});
	}

	/**
	 * Ignores unrelated, malformed, quoted and indented candidate flags.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void keepsUnrecognizedFlagsOut() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "lint = \"deny\"\n");
			writeBazel(root, "other:clippy --@rules_rust//rust/settings:clippy_flag=--allow=clippy::lint\n" +
				" build:clippy --@rules_rust//rust/settings:clippy_flag=--allow=clippy::lint\n" +
				"build:other --@rules_rust//rust/settings:clippy_flag=--allow=clippy::lint\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=--allow=clippy::lint # comment\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::UPPER\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=\"--allow=clippy::lint\"\n" +
				"build:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::lint\n");
			Result result = run(root.toString());
			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.err(), "errors").isEmpty();
		});
	}

	/**
	 * Selects the first literal boolean workspace opt-in in sorted descendant path order.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void selectsFirstOptedInExample() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "missing = \"deny\"\n");
			writeBazel(root, "");
			writeManifest(root, ".hidden/Cargo.toml", "false");
			writeManifest(root, "a/Cargo.toml", "1");
			writeManifest(root, "b/Cargo.toml", "\"true\"");
			writeManifest(root, "z/Cargo.toml", "true");
			Result result = run(root.toString());
			requireThat(result.status(), "status").isEqualTo(1);
			requireThat(result.err(), "first_example").contains(
				"for example codex-rs/z/Cargo.toml.\n");

			writeManifest(root, "a/Cargo.toml", "[broken\n");
			Result malformed = run(root.toString());
			requireThat(malformed.status(), "malformed_example_status").isEqualTo(1);
			requireThat(malformed.err(), "malformed_example_error").contains(
				root.resolve("codex-rs/a/Cargo.toml") + ": invalid TOML");
		});
	}

	/**
	 * Keeps caller streams open and leaves all repository input bytes and modes untouched.
	 *
	 * @throws IOException if the operation cannot complete
	 */
	@Test
	public void preservesBorrowedStreams() throws IOException
	{
		withFixture(root ->
		{
			writeCargo(root, "lint = \"deny\"\n");
			writeBazel(root, "build:clippy --@rules_rust//rust/settings:clippy_flag=--deny=clippy::lint\n");
			Path cargo = root.resolve("codex-rs/Cargo.toml");
			Path bazel = root.resolve(".bazelrc");
			byte[] cargoBytes = Files.readAllBytes(cargo);
			byte[] bazelBytes = Files.readAllBytes(bazel);
			Set<PosixFilePermission> cargoMode = Files.getPosixFilePermissions(cargo);
			Set<PosixFilePermission> bazelMode = Files.getPosixFilePermissions(bazel);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				TrackingInput input = new TrackingInput();
				int success = Main.run(joinCommand(new String[] {root.toString()}), input, out, err, Map.of());
				out.print("caller-output");
				err.print("caller-error");
				requireThat(success, "success_status").isEqualTo(0);
				requireThat(input.closed, "input_open").isEqualTo(false);
				requireThat(Arrays.equals(Files.readAllBytes(cargo), cargoBytes), "cargo_after_success").isEqualTo(true);
				requireThat(Arrays.equals(Files.readAllBytes(bazel), bazelBytes), "bazel_after_success").isEqualTo(true);
				writeBazel(root, "build:clippy --@rules_rust//rust/settings:clippy_flag=--warn=clippy::lint\n");
				byte[] mismatchBytes = Files.readAllBytes(bazel);
				TrackingInput secondInput = new TrackingInput();
				int failure = Main.run(joinCommand(new String[] {root.toString()}), secondInput, out, err, Map.of());
				requireThat(failure, "failure_status").isEqualTo(1);
				requireThat(secondInput.closed, "failure_input_open").isEqualTo(false);
				out.print("-after");
				err.print("-after");
				requireThat(output.toString(StandardCharsets.UTF_8), "output_stream_open").contains("caller-output-after");
				requireThat(errors.toString(StandardCharsets.UTF_8), "error_stream_open").contains("caller-error");
				requireThat(errors.toString(StandardCharsets.UTF_8), "error_stream_remains_writable").endsWith("-after");
				requireThat(Files.readAllBytes(cargo), "cargo_bytes").isEqualTo(cargoBytes);
				requireThat(Files.readAllBytes(bazel), "bazel_bytes").isEqualTo(mismatchBytes);
				requireThat(Files.getPosixFilePermissions(cargo), "cargo_mode").isEqualTo(cargoMode);
				requireThat(Files.getPosixFilePermissions(bazel), "bazel_mode").isEqualTo(bazelMode);
			}
		});
	}

	/**
	 * Executes Main with isolated streams and the explicit fixture root.
	 *
	 * @param arguments arguments value
	 * @return the computed value
	 */
	private static Result run(String... arguments)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			int status = Main.run(joinCommand(arguments), new ByteArrayInputStream(new byte[0]), out, err, Map.of());
			return new Result(status, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
		}
	}

	/**
	 * Adds the command name to arguments.
	 *
	 * @param arguments arguments value
	 * @return the computed value
	 */
	private static String[] joinCommand(String[] arguments)
	{
		String[] result = new String[arguments.length + 1];
		result[0] = "verify-bazel-clippy-lints";
		System.arraycopy(arguments, 0, result, 1, arguments.length);
		return result;
	}

	/**
	 * Writes the minimal workspace source of truth.
	 *
	 * @param root root value
	 * @param body body value
	 * @throws IOException if the operation cannot complete
	 */
	private static void writeCargo(Path root, String body) throws IOException
	{
		Path cargo = root.resolve("codex-rs/Cargo.toml");
		Files.createDirectories(cargo.getParent());
		Files.writeString(cargo, "[workspace.lints.clippy]\n" + body);
	}

	/**
	 * Writes the Bazel settings file.
	 *
	 * @param root root value
	 * @param body body value
	 * @throws IOException if the operation cannot complete
	 */
	private static void writeBazel(Path root, String body) throws IOException
	{
		Files.writeString(root.resolve(".bazelrc"), body);
	}

	/**
	 * Writes a member manifest with a selected TOML workspace value.
	 *
	 * @param root root value
	 * @param relativePath relativePath value
	 * @param value value value
	 * @throws IOException if the operation cannot complete
	 */
	private static void writeManifest(Path root, String relativePath, String value) throws IOException
	{
		Path manifest = root.resolve("codex-rs").resolve(relativePath);
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, "[lints]\nworkspace = " + value + "\n");
	}

	/**
	 * Runs a fixture and removes it after the assertion completes.
	 *
	 * @param action action value
	 * @throws IOException if the operation cannot complete
	 */
	private static void withFixture(FixtureAction action) throws IOException
	{
		Path root = Files.createTempDirectory("verify-bazel-clippy");
		try
		{
			action.run(root);
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Removes the invocation-owned fixture after the test.
	 *
	 * @param root root value
	 * @throws IOException if the operation cannot complete
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}

	/**
	 * One command result.
	 *
	 * @param status status value
	 * @param out out value
	 * @param err err value
	 */
	private record Result(int status, String out, String err)
	{
	}

	/**
	 * Records whether the command closes the caller-owned input stream.
	 */
	private static final class TrackingInput extends ByteArrayInputStream
	{
		/** Whether close has been called. */
		private boolean closed;

		/**
		 * Creates the tracked input stream.
		 */
		private TrackingInput()
		{
			super(new byte[0]);
		}

		/**
		 * Records and performs closure.
		 *
		 * @throws IOException if closure fails
		 */
		@Override
		public void close() throws IOException
		{
			closed = true;
			super.close();
		}
	}

	/**
	 * Creates one independent fixture.
	 */
	@FunctionalInterface
	private interface FixtureAction
	{
				/**
		 * Executes the fixture.
		 *
		 * @param root fixture root
		 * @throws IOException if fixture access fails
		 */
		void run(Path root) throws IOException;
	}
}
