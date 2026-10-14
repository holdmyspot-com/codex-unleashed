package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies Cargo Shear warning rejection and failure propagation at the external process boundary.
 */
public final class CargoShearTest
{
	/**
	 * Creates the command tests.
	 */
	public CargoShearTest()
	{
	}

	/**
	 * Rejects the retired baseline argument before invoking an external command.
	 */
	@Test
	public void rejectsBaselineArgument()
	{
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		try (PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"check-cargo-shear", ".", "baseline.json"},
				InputStream.nullInputStream(), System.out, err, _ ->
				{
					throw new AssertionError("Invalid arguments must fail before a command runs");
				}), 2);
		}
		assertTrue(errors.toString(StandardCharsets.UTF_8).contains("check-cargo-shear <checkout>"));
	}

	/**
	 * Rejects a tool's warning status and preserves unexpected failure statuses.
	 *
	 * @throws IOException if a fixture cannot be created, executed, or removed
	 * @throws URISyntaxException if the Java fixture class location is invalid
	 */
	@Test
	public void checksWarningReports() throws IOException, URISyntaxException
	{
		Path root = Files.createTempDirectory("cargo-shear-");
		try
		{
			Path checkout = Files.createDirectory(root.resolve("checkout"));
			Path cargo = Files.createDirectory(checkout.resolve("codex-rs"));
			Files.writeString(cargo.resolve("Cargo.toml"), "fixture");
			Path captures = Files.createDirectory(root.resolve("captures"));
			String finding = "{\"severity\":\"warning\",\"message\":\"known orphan\"}";
			String report = "{\"summary\":{\"errors\":0,\"warnings\":1},\"findings\":[" + finding + "]}\n";
			SystemCommands.Result fixture = SystemCommands.capture(
				JavaFixtures.command(CommandFixture.class, "echo", report), root, captures);
			assertEquals(fixture.status(), 0, fixture.stdout() + fixture.stderr());
			assertEquals(fixture.stdout(), report, fixture.stderr());
			ByteArrayOutputStream warnings = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(warnings, true, StandardCharsets.UTF_8))
			{
				assertEquals(CargoShear.check(checkout,
					JavaFixtures.command(CommandFixture.class, "shear-warning", report), out, captures), 1);
			}
			assertEquals(warnings.toString(StandardCharsets.UTF_8), "shear diagnostics\n" + report);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				assertEquals(CargoShear.check(checkout,
					JavaFixtures.command(CommandFixture.class, "fail", ""), out, captures), 7);
			}
			try (Stream<Path> files = Files.list(captures))
			{
				assertEquals(files.count(), 0L);
			}
		}
		finally
		{
			try (Stream<Path> files = Files.walk(root))
			{
				for (Path path : files.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
