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
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies the retained textual TUI boundary policy through real manifests and Rust source files. */
public final class TuiBoundaryPolicyTest
{
	/** Creates TUI boundary tests. */
	public TuiBoundaryPolicyTest()
	{
	}

	/**
	 * Accepts client boundaries and retains the dependency-key policy for renamed packages.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void acceptsClientBoundary() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.manifest("""
				[dependencies]
				codex-app-server-client = "1"
				renamed = { package = "codex-core", version = "1" }
				""");
			fixture.source("lib.rs", "codex_app_server_client::legacy_core::start();\n" +
				"use codex_core_extra;\nxcodex_core::allowed();\nCodex_core::allowed();\n");
			assertEquals(fixture.check(), 0);
			assertEquals(fixture.output(), "");
		}
	}

	/**
	 * Exposes the TUI policy through the public command dispatcher.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void dispatchesBoundaryPolicy() throws IOException
	{
		try (Fixture fixture = new Fixture(); PrintStream out = new PrintStream(fixture.output, true,
			StandardCharsets.UTF_8))
		{
			assertEquals(Main.run(new String[]{"verify-tui-core-boundary", fixture.root.toString()},
				java.io.InputStream.nullInputStream(), out, out), 0);
			assertEquals(fixture.output(), "");
		}
	}

	/**
	 * Reports root and target dependencies before ordered source errors, with at most one error per source line.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void reportsDependenciesAndImports() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.manifest("""
				[dependencies]
				codex-core = "1"
				[dev-dependencies]
				codex-core = "1"
				[build-dependencies]
				codex-core = "1"
				[target.'cfg(unix)'.dependencies]
				codex-core = "1"
				""");
			fixture.source("z.rs", "extern crate codex_core;\n");
			fixture.source("nested/a.rs", "// codex_core::still_checked\nuse codex_core; codex_core::also_checked();\n");
			assertEquals(fixture.check(), 1);
			String output = fixture.output();
			assertTrue(output.startsWith("codex-tui must not depend on or import codex-core directly.\n"));
			for (String section : new String[]{"dependencies", "dev-dependencies", "build-dependencies",
				"target.cfg(unix).dependencies"})
				assertTrue(output.contains("declares `codex-core` in `[" + section + "]`"));
			String first = "codex-rs/tui/nested/a.rs:1 imports `codex_core`";
			String second = "codex-rs/tui/nested/a.rs:2 imports `codex_core`";
			String last = "codex-rs/tui/z.rs:1 imports `codex_core`";
			assertTrue(output.indexOf(first) < output.indexOf(second));
			assertTrue(output.indexOf(second) < output.indexOf(last));
			assertEquals(output.split("imports `codex_core`", -1).length - 1, 3);
		}
	}

	/**
	 * Retains Python Unicode word boundaries, whitespace, and source line splitting instead of Java defaults.
	 *
	 * @throws IOException if fixture access, validation, or cleanup fails
	 */
	@Test
	public void preservesUnicodeMatching() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.source("unicode.rs", "²codex_core::allowed\ńcodex_core::blocked\n" +
				"use\u00a0codex_core;\u2028extern\tcrate codex_core;\nuse codex_core²;\n".
				replace("\\u00a0", "\u00a0").replace("\\u2028", "\u2028"));
			assertEquals(fixture.check(), 1);
			String output = fixture.output();
			for (int line : new int[]{2, 3, 4})
				assertTrue(output.contains("unicode.rs:" + line + " imports `codex_core`"));
			assertFalse(output.contains("unicode.rs:1 imports"));
			assertFalse(output.contains("unicode.rs:5 imports"));
		}
	}

	/**
	 * Rejects malformed manifests and non-object target tables without emitting a partial policy report.
	 *
	 * @throws IOException if fixture access or cleanup fails
	 */
	@Test
	public void rejectsInvalidManifestInputs() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.manifest("[dependencies\n");
			expectThrows(IOException.class, fixture::check);
			assertEquals(fixture.output(), "");
			fixture.manifest("target = true\n");
			expectThrows(IOException.class, fixture::check);
			assertEquals(fixture.output(), "");
		}
	}

	/** Owns the isolated repository, manifests, sources, and report capture. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path root = Files.createTempDirectory("tui-boundary-policy-");
		private final ByteArrayOutputStream output = new ByteArrayOutputStream();

		/**
		 * Initializes an empty, valid TUI manifest.
		 *
		 * @throws IOException if initialization fails
		 */
		private Fixture() throws IOException
		{
			Files.createDirectories(root.resolve("codex-rs/tui"));
			manifest("");
		}

		/**
		 * Writes the exact TUI manifest text.
		 *
		 * @param text the TOML contents
		 * @throws IOException if writing fails
		 */
		private void manifest(String text) throws IOException
		{
			Files.writeString(root.resolve("codex-rs/tui/Cargo.toml"), text);
		}

		/**
		 * Writes a Rust source beneath the TUI source tree.
		 *
		 * @param name the source path
		 * @param text the raw source text
		 * @throws IOException if writing fails
		 */
		private void source(String name, String text) throws IOException
		{
			Path file = root.resolve("codex-rs/tui").resolve(name);
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		}

		/**
		 * Runs the validator with a fresh UTF-8 report capture.
		 *
		 * @return the policy status
		 * @throws IOException if input access or parsing fails
		 */
		private int check() throws IOException
		{
			output.reset();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				return TuiBoundaryPolicy.check(root, out);
			}
		}

		/**
		 * Returns the report text.
		 *
		 * @return the UTF-8 report
		 */
		private String output()
		{
			return output.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Removes all fixture files without following directory links.
		 *
		 * @throws IOException if cleanup fails
		 */
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
