package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.DirectDependencyLints;
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
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks direct dependency selection and lint argument policy.
 */
public final class DirectDependencyLintsTest
{
	/**
	 * Creates direct dependency lint behavior checks.
	 */
	public DirectDependencyLintsTest()
	{
	}

	/**
	 * Lists unique direct non-workspace packages while excluding workspace and transitive-only packages.
	 */
	@Test
	public void listSelectionUsesCargoMetadata()
	{
		String metadata = """
			{
			  "workspace_members": ["workspace"],
			  "packages": [
			    {"id": "workspace", "name": "workspace-root", "version": "0.1.0"},
			    {"id": "serde-1", "name": "serde", "version": "1.0.0"},
			    {"id": "serde-2", "name": "serde", "version": "2.0.0"},
			    {"id": "anyhow", "name": "anyhow", "version": "1.0.0"},
			    {"id": "time", "name": "time", "version": "0.3.0"}
			  ],
			  "resolve": {"nodes": [
			    {"id": "workspace", "deps": [
			      {"pkg": "workspace"}, {"pkg": "serde-2"}, {"pkg": "anyhow"}, {"pkg": "serde-1"}
			    ]},
			    {"id": "serde-2", "deps": [{"pkg": "time"}]},
			    {"id": "anyhow", "deps": []},
			    {"id": "serde-1", "deps": []},
			    {"id": "time", "deps": []}
			  ]}
			}
			""";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
			PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
		{
			int status = Main.run(new String[]{"direct-dependency-lints", "list"},
				new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)), out, err, Map.of());

			requireThat(status, "status").isEqualTo(0);
			requireThat(output.toString(StandardCharsets.UTF_8), "selectedPackages").
				isEqualTo("anyhow 1.0.0\nserde 1.0.0\nserde 2.0.0\n");
		}
	}

	/**
	 * Writes line-feed terminated records even when the output stream's println uses a carriage return and line feed,
	 * as the console of a Windows host does.
	 *
	 * @throws IOException if warning fixtures cannot be written
	 */
	@Test
	public void writesLineFeedRecordsOnWindowsStyleStreams() throws IOException
	{
		Path root = Files.createTempDirectory("direct-dependency-line-feeds-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			String metadata = """
				{
				  "workspace_members": ["workspace"],
				  "packages": [
				    {"id": "workspace", "name": "workspace-root", "version": "0.1.0"},
				    {"id": "anyhow", "name": "anyhow", "version": "1.0.0"}
				  ],
				  "resolve": {"nodes": [
				    {"id": "workspace", "deps": [{"pkg": "anyhow"}]},
				    {"id": "anyhow", "deps": []}
				  ]}
				}
				""";
			ByteArrayOutputStream listed = new ByteArrayOutputStream();
			try (PrintStream out = new CarriageReturnPrintStream(listed))
			{
				int status = DirectDependencyLints.run(scope, new String[]{"list"},
					new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)), Map.of(), out,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
				requireThat(status, "listStatus").isEqualTo(0);
			}
			requireThat(listed.toString(StandardCharsets.UTF_8), "list").isEqualTo("anyhow 1.0.0\n");

			Path listing = root.resolve("direct.txt");
			Path target = root.resolve("target");
			Files.writeString(listing, "ring 0.17.0\n", StandardCharsets.UTF_8);
			writeOutput(target, "ring-4567ef01", "cargo::warning=missing assembler\n");
			ByteArrayOutputStream warned = new ByteArrayOutputStream();
			try (PrintStream out = new CarriageReturnPrintStream(warned))
			{
				int status = DirectDependencyLints.run(scope, new String[]{"build-warnings", listing.toString(),
					target.toString()}, new ByteArrayInputStream(new byte[0]), Map.of(), out,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
				requireThat(status, "warningStatus").isEqualTo(1);
			}
			requireThat(warned.toString(StandardCharsets.UTF_8), "warnings").isEqualTo("ring: missing assembler\n");
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Replaces every cap once, applies package lint flags in Cargo order, and leaves nonmatching identities untouched.
	 *
	 * @throws IOException if the temporary manifest cannot be written
	 */
	@Test
	public void rewritesDirectPackageLintFlags() throws IOException
	{
		Path root = Files.createTempDirectory("direct-dependency-lints-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path listing = root.resolve("direct.txt");
			Path manifest = root.resolve("Cargo.toml");
			Files.writeString(listing, "serde 1.0.0\nrand 0.9.0\n", StandardCharsets.UTF_8);
			Files.writeString(manifest, """
				[lints.rust]
				unsafe_code = "deny"
				rust_2018_idioms = { level = "warn", priority = -1 }
				[lints.rust.unexpected_cfgs]
				level = "warn"
				check-cfg = ["cfg(tokio_unstable)", "cfg(loom)"]
				[lints.clippy]
				all = "allow"
				[lints.cargo]
				implicit_features = "warn"
				""", StandardCharsets.UTF_8);
			Map<String, String> environment = Map.of(
				"CODEX_DIRECT_DEPENDENCIES", listing.toString(),
				"CARGO_PKG_NAME", "serde",
				"CARGO_PKG_VERSION", "1.0.0",
				"CARGO_MANIFEST_PATH", manifest.toString());
			List<String> command = new ArrayList<>();
			DirectDependencyLints.Compiler compiler = (arguments, workingDirectory, childEnvironment) ->
			{
				command.addAll(arguments);
				requireThat(workingDirectory.isAbsolute(), "workingDirectory").isEqualTo(true);
				requireThat(childEnvironment, "childEnvironment").isEqualTo(environment);
				return 7;
			};
			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			try (PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8))
			{
				int status = DirectDependencyLints.run(scope,
					new String[]{"rustc with spaces", "--cap-lints", "allow", "-Dwarnings", "--cap-lints=warn", "src/lib.rs"},
					new ByteArrayInputStream(new byte[0]), environment,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), err, compiler);

				requireThat(status, "childStatus").isEqualTo(7);
				requireThat(errors.toString(StandardCharsets.UTF_8), "diagnostics").isEmpty();
			}
			requireThat(command, "compilerArguments").isEqualTo(List.of("rustc with spaces", "--warn=rust_2018_idioms",
				"--deny=unsafe_code", "--warn=unexpected_cfgs", "--allow=clippy::all", "--check-cfg",
				"cfg(tokio_unstable)", "--check-cfg", "cfg(loom)", "-Dwarnings", "src/lib.rs"));

			List<String> unchanged = new ArrayList<>();
			int status = DirectDependencyLints.run(scope, new String[]{"rustc", "--cap-lints=allow", "-vV"},
				new ByteArrayInputStream(new byte[0]), Map.of("CODEX_DIRECT_DEPENDENCIES", listing.toString(),
					"CARGO_PKG_NAME", "rand", "CARGO_PKG_VERSION", "0.8.5"),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
				(arguments, workingDirectory, childEnvironment) ->
				{
					unchanged.addAll(arguments);
					return 0;
				});
			requireThat(status, "unselectedStatus").isEqualTo(0);
			requireThat(unchanged, "unselectedArguments").isEqualTo(List.of("rustc", "--cap-lints=allow", "-vV"));
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Reports both build-script warning spellings in sorted path and source order, with status one only when found.
	 *
	 * @throws IOException if warning fixtures cannot be written
	 */
	@Test
	public void reportsBuildWarnings() throws IOException
	{
		Path root = Files.createTempDirectory("direct-dependency-warnings-");
		try (DefaultToolingScope scope = new DefaultToolingScope())
		{
			Path listing = root.resolve("direct.txt");
			Path target = root.resolve("target");
			Files.writeString(listing, "aws-lc-rs 1.0.0\nring 0.17.0\n", StandardCharsets.UTF_8);
			writeOutput(target, "ring-4567ef01", "cargo::warning=missing assembler\n");
			writeOutput(target, "aws-lc-rs-0123abcd", "cargo:rustc-link-lib=crypto\ncargo:warning=old compiler\n");
			writeOutput(target, "aws-lc-sys-89abcdef", "cargo:warning=transitive\n");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8))
			{
				int status = DirectDependencyLints.run(scope, new String[]{"build-warnings", listing.toString(),
					target.toString()}, new ByteArrayInputStream(new byte[0]), Map.of(), out,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
				requireThat(status, "warningStatus").isEqualTo(1);
				requireThat(output.toString(StandardCharsets.UTF_8), "warnings").
					isEqualTo("aws-lc-rs: old compiler\nring: missing assembler\n");
				output.reset();
				writeOutput(target, "ring-4567ef01", "");
				writeOutput(target, "aws-lc-rs-0123abcd", "");
				status = DirectDependencyLints.run(scope, new String[]{"build-warnings", listing.toString(),
					target.toString()}, new ByteArrayInputStream(new byte[0]), Map.of(), out,
					new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
				requireThat(status, "warningFreeStatus").isEqualTo(0);
				requireThat(output.toString(StandardCharsets.UTF_8), "warningFreeOutput").isEmpty();
			}
		}
		finally
		{
			deleteTree(root);
		}
	}

	/**
	 * Writes one build-script output fixture.
	 *
	 * @param target Cargo target directory
	 * @param packageDirectory hashed build-script directory
	 * @param content output contents
	 * @throws IOException if fixture files cannot be written
	 */
	private static void writeOutput(Path target, String packageDirectory, String content) throws IOException
	{
		Path output = target.resolve("x86_64-unknown-linux-musl/debug/build").resolve(packageDirectory).resolve("output");
		Files.createDirectories(output.getParent());
		Files.writeString(output, content, StandardCharsets.UTF_8);
	}

	/**
	 * Deletes one test-owned temporary tree.
	 *
	 * @param root temporary tree root
	 * @throws IOException if a fixture cannot be deleted
	 */
	private static void deleteTree(Path root) throws IOException
	{
		if (Files.exists(root))
			try (Stream<Path> paths = Files.walk(root))
			{
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
	}

	/**
	 * Print stream whose line terminator is a carriage return and line feed.
	 */
	private static final class CarriageReturnPrintStream extends PrintStream
	{
		/**
		 * Creates a stream that writes UTF-8 to the supplied sink.
		 *
		 * @param sink destination bytes
		 */
		private CarriageReturnPrintStream(ByteArrayOutputStream sink)
		{
			super(sink, true, StandardCharsets.UTF_8);
		}

		@Override
		public void println(String value)
		{
			print(value + "\r\n");
		}
	}
}
