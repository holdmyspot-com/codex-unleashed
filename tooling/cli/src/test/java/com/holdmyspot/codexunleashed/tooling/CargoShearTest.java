package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Verifies Cargo Shear command execution and release-baseline decisions at the external process boundary.
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
	 * Reports baseline file failures through the CLI before invoking external commands.
	 *
	 * @throws IOException if fixture allocation or cleanup fails
	 */
	@Test
	public void reportsInvalidBaselineInput() throws IOException
	{
		Path root = Files.createTempDirectory("shear-cli-");
		Path baseline = root.resolve("baseline.json");
		try
		{
			for (boolean malformed : new boolean[]{false, true})
			{
				if (malformed)
					Files.write(baseline, new byte[]{(byte) 0xc3, (byte) 0x28});
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				ByteArrayOutputStream errors = new ByteArrayOutputStream();
				try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
					PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
				{
					int status = Main.run(new String[]{"check-cargo-shear", root.toString(), baseline.toString()},
						InputStream.nullInputStream(), out, err, _ ->
						{
							throw new AssertionError("Invalid baseline input must fail before Git runs");
						});
					assertEquals(status, 1, errors.toString(StandardCharsets.UTF_8));
				}
				assertEquals(output.toString(StandardCharsets.UTF_8), "");
				assertTrue(errors.toString(StandardCharsets.UTF_8).contains("baseline.json"));
			}
		}
		finally
		{
			Files.deleteIfExists(baseline);
			Files.delete(root);
		}
	}

	/**
	 * Accepts a tool's warning status only for matching baseline content and unchanged release sources.
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
			Path baseline = root.resolve("baseline.json");
			Files.writeString(baseline, "{\"source_sha\":\"" + "a".repeat(40) + "\",\"findings\":[" + finding +
				"],\"source_paths\":[\"codex-rs/source.txt\"]}");
			for (boolean changed : new boolean[]{false, true})
			{
				List<List<String>> commands = new ArrayList<>();
				CommandRunner git = command ->
				{
					commands.add(command);
					if (commands.size() == 1)
						return "a".repeat(40) + "\n";
					if (changed)
						return "codex-rs/source.txt\n";
					return "";
				};
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				int expectedStatus = 0;
				if (changed)
					expectedStatus = 1;
				try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
				{
					assertEquals(CargoShear.check(checkout, baseline,
						JavaFixtures.command(CommandFixture.class, "shear-warning", report), out, git, captures),
						expectedStatus);
				}
				String accepted = "";
				if (!changed)
					accepted = "Only verified, unchanged upstream release warnings remain." + System.lineSeparator();
				assertEquals(output.toString(StandardCharsets.UTF_8), "shear diagnostics\n" + report + accepted);
				assertEquals(commands, List.of(
					List.of("git", "-C", checkout.toString(), "rev-parse", "HEAD"),
					List.of("git", "-C", checkout.toString(), "diff", "HEAD", "--name-only", "--", "codex-rs/source.txt")));
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				assertEquals(CargoShear.check(checkout, baseline,
					JavaFixtures.command(CommandFixture.class, "fail", ""), out, _ -> "", captures), 7);
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
