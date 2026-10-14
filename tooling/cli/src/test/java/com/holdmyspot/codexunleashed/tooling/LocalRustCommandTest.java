package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Exercises checkout isolation through the public command and real child processes.
 */
public final class LocalRustCommandTest
{
	/**
	 * Creates the command tests.
	 */
	public LocalRustCommandTest()
	{
	}

	/**
	 * Rejects an escaping cache name before creating cache directories or starting a child.
	 *
	 * @throws IOException if fixture creation or cleanup fails
	 */
	@Test
	public void rejectsInvalidCacheName() throws IOException
	{
		Path project = Files.createTempDirectory("local-rust-");
		try (PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"run-local-rust", project.toString(), project.toString(), "../outside",
				"--", "must-not-start"}, InputStream.nullInputStream(), err, err), 2);
			assertFalse(Files.exists(project.resolve(".cat")));
		}
		finally
		{
			delete(project);
		}
	}

	/**
	 * Separates compiler outputs while sharing downloads and preserving the child's working directory and status.
	 *
	 * @throws IOException if fixture creation, execution, or cleanup fails
	 */
	@Test
	public void isolatesCheckouts() throws IOException
	{
		Path project = Files.createTempDirectory("local-rust-");
		try
		{
			Path baseline = Files.createDirectory(project.resolve("baseline"));
			Path patched = Files.createDirectory(project.resolve("patched"));
			Path first = project.resolve("first");
			Path second = project.resolve("second");
			assertEquals(run(project, baseline, first), 7);
			assertEquals(run(project, patched, second), 7);
			List<String> baselinePaths = Files.readAllLines(first);
			List<String> patchedPaths = Files.readAllLines(second);
			assertEquals(baselinePaths.get(0), baseline.toRealPath().toString());
			assertNotEquals(baselinePaths.get(1), patchedPaths.get(1));
			assertEquals(baselinePaths.subList(2, 6), patchedPaths.subList(2, 6));
			Path cacheRoot = project.toRealPath().resolve(".cat/work/temp/build-caches");
			for (String path : baselinePaths.subList(1, 6))
				assertTrue(Path.of(path).startsWith(cacheRoot), path);
			Files.writeString(Path.of(baselinePaths.get(1)).resolve("compiled-output"), "keep");
			assertEquals(run(project, baseline, first), 7);
			assertEquals(Files.readString(Path.of(baselinePaths.get(1)).resolve("compiled-output")), "keep");
		}
		finally
		{
			delete(project);
		}
	}

	/**
	 * Rejects copied foreign outputs and unowned outputs before the child can create its marker.
	 *
	 * @throws IOException if fixture creation, execution, or cleanup fails
	 */
	@Test
	public void rejectsForeignAndUnownedOutputs() throws IOException
	{
		Path project = Files.createTempDirectory("local-rust-");
		try
		{
			Path checkout = Files.createDirectory(project.resolve("patched"));
			Path foreign = Files.createDirectory(project.resolve("baseline"));
			Path output = project.resolve("child-started");
			assertEquals(run(project, checkout, output), 7);
			Path owner = Path.of(Files.readAllLines(output).get(1)).resolve(".checkout-owner");
			Files.writeString(owner, foreign.toRealPath().toString());
			Files.delete(output);
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			assertEquals(run(project, checkout, output, diagnostics), 1);
			assertFalse(Files.exists(output));
			String message = diagnostics.toString(StandardCharsets.UTF_8);
			assertTrue(message.contains(foreign.toRealPath().toString()), message);
			assertTrue(message.contains(checkout.toRealPath().toString()), message);
			Files.delete(owner);
			assertEquals(run(project, checkout, output), 1);
			assertFalse(Files.exists(output));
		}
		finally
		{
			delete(project);
		}
	}

	/**
	 * Runs the fixture while discarding command diagnostics.
	 *
	 * @param project the cache-owning project
	 * @param checkout the child working directory
	 * @param output the child's observable output file
	 * @return the command status
	 */
	private static int run(Path project, Path checkout, Path output)
	{
		return run(project, checkout, output, new ByteArrayOutputStream());
	}

	/**
	 * Invokes the maintained dispatcher with a portable child that records its effective build environment.
	 *
	 * @param project the cache-owning project
	 * @param checkout the child working directory
	 * @param output the child's observable output file
	 * @param diagnostics the command's diagnostic capture
	 * @return the command status
	 */
	private static int run(Path project, Path checkout, Path output, ByteArrayOutputStream diagnostics)
	{
		String script = "require('fs').writeFileSync(process.argv[1], [process.cwd(), " +
			"...['CARGO_TARGET_DIR','CARGO_HOME','UV_CACHE_DIR','XDG_CACHE_HOME','DOTSLASH_CACHE']" +
			".map(key => process.env[key])].join('\\n')); process.exit(7)";
		try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8))
		{
			return Main.run(new String[]{"run-local-rust", project.toString(), checkout.toString(), "verification",
				"--", "node", "-e", script, output.toString()}, InputStream.nullInputStream(), err, err);
		}
	}

	/**
	 * Removes the fixture after its last child exits, including on assertion failure.
	 *
	 * @param root the fixture-owned tree
	 * @throws IOException if cleanup fails
	 */
	private static void delete(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
