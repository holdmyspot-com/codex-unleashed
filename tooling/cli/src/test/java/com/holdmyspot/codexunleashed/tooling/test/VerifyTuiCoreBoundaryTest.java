package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.Main;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies the packaged {@code verify-tui-core-boundary} command through {@link Main#run}.
 */
public final class VerifyTuiCoreBoundaryTest
{
	/** Policy statement that precedes every set of findings. */
	private static final String HEADER = "codex-tui must not depend on or import codex-core directly.\n" +
		"Use the app-server protocol/client boundary instead; temporary embedded startup gaps belong behind " +
		"codex_app_server_client::legacy_core.\n\n";
	/** Valid manifest that declares no forbidden dependency. */
	private static final String CLEAN_MANIFEST = "[package]\nname = \"codex-tui\"\n";
	/** Manifest location in diagnostics. */
	private static final String MANIFEST = "codex-rs/tui/Cargo.toml";
	/** Allowed Rust source. */
	private static final String CLEAN_SOURCE = "use codex_app_server_client::legacy_core;\nfn main() {}\n";

	/** Creates independent method fixtures. */
	public VerifyTuiCoreBoundaryTest()
	{
	}

	/**
	 * Accepts a repository whose manifest and sources are free of the forbidden dependency.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void acceptsBoundaryFreeRepository() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/lib.rs", CLEAN_SOURCE);
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
			requireThat(fixture.errors(), "errors").isEmpty();
		}
	}

	/**
	 * Reports the exact package key in each root dependency section.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsEachRootDependencySection() throws IOException
	{
		for (String section : List.of("dependencies", "dev-dependencies", "build-dependencies"))
		{
			try (Fixture fixture = new Fixture(CLEAN_MANIFEST + "[" + section + "]\ncodex-core = { path = \"../core\" }\n"))
			{
				requireThat(fixture.run(), section + "Status").isEqualTo(1);
				requireThat(fixture.output(), section + "Output").isEqualTo(HEADER + finding(section));
				requireThat(fixture.errors(), section + "Errors").isEmpty();
			}
		}
	}

	/**
	 * Reports the package key in each target-specific section, including quoted target names.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsEachTargetDependencySection() throws IOException
	{
		for (String section : List.of("dependencies", "dev-dependencies", "build-dependencies"))
		{
			String manifest = CLEAN_MANIFEST + "[target.'cfg(unix)'." + section + "]\ncodex-core = \"1\"\n" +
				"[target.x86_64-pc-windows-msvc." + section + "]\ncodex-core = \"1\"\n" +
				"[target.'a.b'." + section + "]\ncodex-core = \"1\"\n";
			try (Fixture fixture = new Fixture(manifest))
			{
				requireThat(fixture.run(), section + "Status").isEqualTo(1);
				requireThat(fixture.output(), section + "Output").isEqualTo(HEADER +
					finding("target.cfg(unix)." + section) + finding("target.x86_64-pc-windows-msvc." + section) +
					finding("target.a.b." + section));
			}
		}
	}

	/**
	 * Orders root sections before target sections, and each group by its fixed section order and declaration order.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void ordersManifestFindingsByLegacyPolicy() throws IOException
	{
		String manifest = "[target.'cfg(b)'.build-dependencies]\ncodex-core = \"1\"\n" +
			"[target.'cfg(a)'.dependencies]\ncodex-core = \"1\"\n" +
			"[target.'cfg(a)'.dev-dependencies]\ncodex-core = \"1\"\n" +
			"[build-dependencies]\ncodex-core = \"1\"\n" +
			"[dependencies]\ncodex-core = \"1\"\n";
		try (Fixture fixture = new Fixture(manifest))
		{
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER + finding("dependencies") +
				finding("build-dependencies") + finding("target.cfg(b).build-dependencies") +
				finding("target.cfg(a).dependencies") + finding("target.cfg(a).dev-dependencies"));
		}
	}

	/**
	 * Allows renamed packages and similar names, and ignores unrelated metadata.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void allowsRenamedAndUnrelatedDependencies() throws IOException
	{
		String manifest = CLEAN_MANIFEST + "[dependencies]\ncore = { package = \"codex-core\", path = \"../core\" }\n" +
			"codex-core-extra = \"1\"\nmycodex-core = \"1\"\n[package.metadata]\nnote = \"codex-core\"\n";
		try (Fixture fixture = new Fixture(manifest))
		{
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
			requireThat(fixture.errors(), "errors").isEmpty();
		}
	}

	/**
	 * Ignores dependency sections and target entries that are not tables.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void ignoresNonTableSections() throws IOException
	{
		String manifest = "dependencies = [\"codex-core\"]\nbuild-dependencies = \"codex-core\"\n" +
			"[target]\nplain = \"codex-core\"\n[target.'cfg(unix)']\ndependencies = \"codex-core\"\n" +
			"dev-dependencies = [\"codex-core\"]\n";
		try (Fixture fixture = new Fixture(manifest))
		{
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Scans nested and hidden Rust sources, and ignores other files and sources outside the crate.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void scansNestedAndHiddenRustSourcesOnly() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/a/b/deep.rs", "fn f() {}\nuse codex_core::x;\n");
			fixture.write(".hidden/h.rs", "fn g() { codex_core::y(); }\n");
			fixture.write("tests/t.rs", "extern crate codex_core;\n");
			fixture.write(".rs", "use codex_core;\n");
			fixture.write("src/notes.txt", "codex_core::ignored\n");
			fixture.write("src/rs", "codex_core::ignored\n");
			fixture.writeAtRoot("codex-rs/other/o.rs", "use codex_core::z;\n");
			fixture.writeAtRoot("codex-rs/tui2/o.rs", "use codex_core::z;\n");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER +
				"- codex-rs/tui/.hidden/h.rs:1 imports `codex_core`\n" +
				"- codex-rs/tui/.rs:1 imports `codex_core`\n" +
				"- codex-rs/tui/src/a/b/deep.rs:2 imports `codex_core`\n" +
				"- codex-rs/tui/tests/t.rs:1 imports `codex_core`\n");
		}
	}

	/**
	 * Sorts paths component by component, so a directory sorts before a sibling whose name extends it.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void sortsSourcePathsByComponent() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("a-b/c.rs", "use codex_core;\n");
			fixture.write("a/z.rs", "use codex_core;\n");
			fixture.write("a.rs", "use codex_core;\n");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER +
				"- codex-rs/tui/a/z.rs:1 imports `codex_core`\n" +
				"- codex-rs/tui/a-b/c.rs:1 imports `codex_core`\n" +
				"- codex-rs/tui/a.rs:1 imports `codex_core`\n");
		}
	}

	/**
	 * Does not traverse a directory symbolic link.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void doesNotTraverseDirectorySymbolicLinks() throws IOException
	{
		TestRequirements.requirePosixFileSystem();
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.writeAtRoot("outside/o.rs", "use codex_core;\n");
			Path link = fixture.root().resolve("codex-rs/tui/linked");
			Files.createSymbolicLink(link, fixture.root().resolve("outside"));
			requireThat(fixture.run(), "status").isEqualTo(0);
			requireThat(fixture.output(), "output").isEmpty();
		}
	}

	/**
	 * Matches each forbidden pattern independently and reports one finding for each matching line.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void reportsEachForbiddenPatternOncePerLine() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/lib.rs", "codex_core::foo();\nuse codex_core;\nextern crate codex_core;\n" +
				"use codex_core::x; extern crate codex_core; codex_core::y();\nuse\t codex_core\nextern   crate\tcodex_core\n");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER + sourceFinding("src/lib.rs", 1) +
				sourceFinding("src/lib.rs", 2) + sourceFinding("src/lib.rs", 3) + sourceFinding("src/lib.rs", 4) +
				sourceFinding("src/lib.rs", 5) + sourceFinding("src/lib.rs", 6));
		}
	}

	/**
	 * Matches raw text, so comments, strings and disabled code count like code.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void matchesRawTextInCommentsAndStrings() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/lib.rs",
				"// use codex_core::x;\nlet s = \"codex_core::y\";\n#[cfg(any())]\nuse codex_core;\n");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER + sourceFinding("src/lib.rs", 1) +
				sourceFinding("src/lib.rs", 2) + sourceFinding("src/lib.rs", 4));
		}
	}

	/**
	 * Preserves the word-boundary and whitespace decisions of the Unicode-aware legacy patterns.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void preservesUnicodeBoundaryDecisions() throws IOException
	{
		String allowed = "mycodex_core::x\n_codex_core::x\n9codex_core::x\nécodex_core::x\ncodex_core_extra::x\n" +
			"codex_core:x\nuse codex_core_extra;\nuse mycodex_core;\nuse codex_corex\nxuse codex_core;\n" +
			"éuse codex_core;\nuse codex_coreé;\nuse codex_core²;\nusecodex_core;\n" +
			"use​codex_core;\néextern crate codex_core;\nextern crate​codex_core;\n" +
			"extern crates codex_core;\nextern  codex_core;\n٣use codex_core;\n";
		String flagged = "—codex_core::x\nuse codex_core;\nuse　codex_core;\nuse codex_core\n" +
			"extern crate codex_core;\nuse codex_core—;\nuse codex_core.\né use codex_core;\n";
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/allowed.rs", allowed);
			requireThat(fixture.run(), "allowedStatus").isEqualTo(0);
			requireThat(fixture.output(), "allowedOutput").isEmpty();
		}
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/flagged.rs", flagged);
			requireThat(fixture.run(), "flaggedStatus").isEqualTo(1);
			StringBuilder expected = new StringBuilder(HEADER);
			for (int line = 1; line <= 8; ++line)
				expected.append(sourceFinding("src/flagged.rs", line));
			requireThat(fixture.output(), "flaggedOutput").isEqualTo(expected.toString());
		}
	}

	/**
	 * Counts lines the way Python's {@code splitlines()} does, including CRLF and the less common separators.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void numbersLinesLikeSplitLines() throws IOException
	{
		List<String> separators = List.of("\n", "\r\n", "\r", "\u000b", "\f", "\u001c", "\u001d", "\u001e", "\u0085",
			" ", " ");
		for (String separator : separators)
		{
			try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
			{
				fixture.write("src/lib.rs", "fn a() {}" + separator + "use codex_core;" + separator);
				requireThat(fixture.run(), "status").isEqualTo(1);
				requireThat(fixture.output(), "output").isEqualTo(HEADER + sourceFinding("src/lib.rs", 2));
			}
		}
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			fixture.write("src/lib.rs", "a\r\nb\r\n\r\nuse codex_core;\n\n\rcodex_core::x\u001fuse codex_core; end");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER + sourceFinding("src/lib.rs", 4) +
				sourceFinding("src/lib.rs", 7));
		}
	}

	/**
	 * Lists manifest findings first, then source findings in sorted path and ascending line order.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void ordersManifestBeforeSortedSourceFindings() throws IOException
	{
		try (Fixture fixture = new Fixture("[dependencies]\ncodex-core = \"1\"\n"))
		{
			fixture.write("src/z.rs", "use codex_core;\nuse codex_core;\n");
			fixture.write("src/b.rs", "x\nuse codex_core;\n\ncodex_core::y\n");
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEqualTo(HEADER + finding("dependencies") +
				sourceFinding("src/b.rs", 2) + sourceFinding("src/b.rs", 4) + sourceFinding("src/z.rs", 1) +
				sourceFinding("src/z.rs", 2));
			requireThat(fixture.errors(), "errors").isEmpty();
		}
	}

	/**
	 * Rejects a missing or extra argument with command usage.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void rejectsMissingAndExtraArguments() throws IOException
	{
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			String usage = "Usage: codex-tooling verify-tui-core-boundary <repository-root>\n";
			requireThat(fixture.runWith("verify-tui-core-boundary"), "missingStatus").isEqualTo(2);
			requireThat(fixture.errors(), "missingUsage").isEqualTo(usage);
			requireThat(fixture.output(), "missingOutput").isEmpty();
			requireThat(fixture.runWith("verify-tui-core-boundary", fixture.root().toString(), "extra"),
				"extraStatus").isEqualTo(2);
			requireThat(fixture.errors(), "extraUsage").isEqualTo(usage);
			requireThat(fixture.output(), "extraOutput").isEmpty();
			requireThat(fixture.runWith("unknown-command"), "unknownStatus").isEqualTo(2);
			requireThat(fixture.errors(), "topLevelUsage").contains(
				"codex-tooling verify-tui-core-boundary <repository-root>");
		}
	}

	/**
	 * Fails without a policy report when the manifest is missing, malformed, duplicated or not a table of targets.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void failsOperationallyOnUnusableManifest() throws IOException
	{
		for (String manifest : List.of("[dependencies\n", "[dependencies]\ncodex-core = \"1\"\ncodex-core = \"2\"\n",
			"target = 5\n"))
		{
			try (Fixture fixture = new Fixture(manifest))
			{
				requireThat(fixture.run(), "status").isEqualTo(1);
				requireThat(fixture.output(), "output").isEmpty();
				requireThat(fixture.errors(), "errors").startsWith("ERROR: " + MANIFEST + ": ").endsWith("\n");
			}
		}
		try (Fixture fixture = new Fixture(null))
		{
			requireThat(fixture.run(), "missingStatus").isEqualTo(1);
			requireThat(fixture.output(), "missingOutput").isEmpty();
			requireThat(fixture.errors(), "missingErrors").startsWith("ERROR: " + MANIFEST + ": cannot read file");
		}
	}

	/**
	 * Fails without a policy report when a selected source cannot be read as UTF-8 text.
	 *
	 * @throws IOException if fixture storage fails
	 */
	@Test
	public void failsOperationallyOnUnreadableSource() throws IOException
	{
		try (Fixture fixture = new Fixture("[dependencies]\ncodex-core = \"1\"\n"))
		{
			fixture.writeBytes("src/bad.rs", new byte[]{'u', 's', 'e', ' ', (byte) 0xff, '\n'});
			requireThat(fixture.run(), "status").isEqualTo(1);
			requireThat(fixture.output(), "output").isEmpty();
			requireThat(fixture.errors(), "errors").isEqualTo(
				"ERROR: codex-rs/tui/src/bad.rs: file is not valid UTF-8\n");
		}
		try (Fixture fixture = new Fixture(CLEAN_MANIFEST))
		{
			Files.createDirectories(fixture.root().resolve("codex-rs/tui/src/folder.rs"));
			requireThat(fixture.run(), "directoryStatus").isEqualTo(1);
			requireThat(fixture.output(), "directoryOutput").isEmpty();
			requireThat(fixture.errors(), "directoryErrors").startsWith("ERROR: codex-rs/tui/src/folder.rs: ");
		}
	}

	/**
	 * Formats a manifest finding.
	 *
	 * @param section dependency section label
	 * @return the finding line
	 */
	private static String finding(String section)
	{
		return "- " + MANIFEST + " declares `codex-core` in `[" + section + "]`\n";
	}

	/**
	 * Formats a source finding.
	 *
	 * @param path path below {@code codex-rs/tui}
	 * @param line one-based line number
	 * @return the finding line
	 */
	private static String sourceFinding(String path, int line)
	{
		return "- codex-rs/tui/" + path + ":" + line + " imports `codex_core`\n";
	}

	/** Owns one repository fixture and the streams of its command invocations. */
	private static final class Fixture implements AutoCloseable
	{
		/** Fixture root. */
		private final Path root;
		/** Captured standard output of the last invocation. */
		private ByteArrayOutputStream output = new ByteArrayOutputStream();
		/** Captured diagnostics of the last invocation. */
		private ByteArrayOutputStream errors = new ByteArrayOutputStream();

		/**
		 * Creates a repository with an optional crate manifest.
		 *
		 * @param manifest manifest text, or null to create none
		 * @throws IOException if storage fails
		 */
		private Fixture(String manifest) throws IOException
		{
			root = Files.createTempDirectory("verify-tui-core-boundary");
			Files.createDirectories(root.resolve("codex-rs/tui"));
			if (manifest != null)
				write("Cargo.toml", manifest);
		}

		/**
		 * Returns the repository root.
		 *
		 * @return the fixture root
		 */
		private Path root()
		{
			return root;
		}

		/**
		 * Writes a UTF-8 file below the crate directory.
		 *
		 * @param path path below {@code codex-rs/tui}
		 * @param text file content
		 * @throws IOException if storage fails
		 */
		private void write(String path, String text) throws IOException
		{
			writeBytes(path, text.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Writes bytes below the crate directory.
		 *
		 * @param path path below {@code codex-rs/tui}
		 * @param content file content
		 * @throws IOException if storage fails
		 */
		private void writeBytes(String path, byte[] content) throws IOException
		{
			Path target = root.resolve("codex-rs/tui").resolve(path);
			Files.createDirectories(target.getParent());
			Files.write(target, content);
		}

		/**
		 * Writes a UTF-8 file below the repository root.
		 *
		 * @param path path below the repository root
		 * @param text file content
		 * @throws IOException if storage fails
		 */
		private void writeAtRoot(String path, String text) throws IOException
		{
			Path target = root.resolve(path);
			Files.createDirectories(target.getParent());
			Files.writeString(target, text, StandardCharsets.UTF_8);
		}

		/**
		 * Runs the command against this repository.
		 *
		 * @return the command status
		 */
		private int run()
		{
			return runWith("verify-tui-core-boundary", root.toString());
		}

		/**
		 * Runs arbitrary command arguments with fresh captured streams.
		 *
		 * @param arguments command name followed by its arguments
		 * @return the command status
		 */
		private int runWith(String... arguments)
		{
			output = new ByteArrayOutputStream();
			errors = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
				PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				return Main.run(arguments, new ByteArrayInputStream(new byte[0]), out, err);
			}
		}

		/**
		 * Returns the captured standard output.
		 *
		 * @return the output text
		 */
		private String output()
		{
			return output.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Returns the captured diagnostics.
		 *
		 * @return the diagnostic text
		 */
		private String errors()
		{
			return errors.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Deletes the fixture.
		 *
		 * @throws IOException if deletion fails
		 */
		@Override
		public void close() throws IOException
		{
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
	}
}
