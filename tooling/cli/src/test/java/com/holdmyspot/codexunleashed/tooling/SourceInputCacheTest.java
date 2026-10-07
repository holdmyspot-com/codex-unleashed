package com.holdmyspot.codexunleashed.tooling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Verifies actual Cargo freshness through source-cache preparation and successful-build recording. */
public final class SourceInputCacheTest
{
	/** Creates Cargo cache tests. */
	public SourceInputCacheTest()
	{
	}

	/**
	 * Reuses identical libraries, rebuilds same-mtime content changes and refuses post-build changes when recording.
	 *
	 * @throws IOException if actual Cargo, fixture operations, or cleanup fail
	 */
	@Test
	public void reusesAndRebuildsActualCargoLibraries() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.prepare();
			assertTrue(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())));
			assertFalse(Files.exists(fixture.snapshot()));
			assertFalse(fixture.libraryFresh(fixture.build()));
			fixture.record();
			assertTrue(Files.isRegularFile(fixture.snapshot()));
			assertFalse(Files.exists(SourceInputSnapshots.pending(fixture.snapshot())));

			Path source = fixture.workspace.resolve("src/lib.rs");
			FileTime before = Files.getLastModifiedTime(source);
			Files.setLastModifiedTime(source, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare();
			assertFalse(Files.exists(fixture.binary()));
			assertTrue(fixture.libraryFresh(fixture.build()));
			fixture.record();

			Files.writeString(source, "pub fn value() -> u8 { 9 }\n");
			Files.setLastModifiedTime(source, before);
			fixture.prepare();
			assertFalse(fixture.libraryFresh(fixture.build()));
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "9");
			fixture.record();

			String valid = Files.readString(source);
			FileTime successfulTime = Files.getLastModifiedTime(source);
			Files.writeString(source, "compile_error!(\"interrupted build\");\n");
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			expectThrows(IOException.class, fixture::build);
			Files.writeString(source, valid);
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			fixture.build();
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "9");
			fixture.record();

			Path included = fixture.workspace.resolve("src/included.rs");
			Files.writeString(included, "pub fn value() -> u8 { 13 }\n");
			Files.writeString(source, "include!(\"included.rs\");\n");
			fixture.prepare();
			assertFalse(fixture.libraryFresh(fixture.build()));
			fixture.record();
			FileTime includedTime = Files.getLastModifiedTime(included);
			Files.writeString(included, "pub fn value() -> u8 { 17 }\n");
			Files.setLastModifiedTime(included, includedTime);
			fixture.prepare();
			assertFalse(fixture.libraryFresh(fixture.build()));
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "17");
			fixture.record();

			fixture.prepare();
			fixture.build();
			String successful = Files.readString(fixture.snapshot());
			Files.writeString(source, "pub fn value() -> u8 { 11 }\n");
			IOException failure = expectThrows(IOException.class, fixture::record);
			assertTrue(failure.getMessage().contains("Source inputs changed during the build"));
			assertEquals(Files.readString(fixture.snapshot()), successful);
			assertTrue(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())));
		}
	}

	/**
	 * Retains an external path dependency when a missing snapshot forces workspace compilation.
	 *
	 * @throws IOException if actual Cargo or fixture operations fail
	 */
	@Test
	public void coldSnapshotRetainsExternalDependency() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			Path dependency = fixture.base.resolve("dependency");
			Files.createDirectories(dependency.resolve("src"));
			Files.writeString(dependency.resolve("Cargo.toml"), """
				[package]
				name = "external-dependency"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				""");
			Files.writeString(dependency.resolve("src/lib.rs"), "pub fn value() -> u8 { 2 }\n");
			Files.writeString(fixture.workspace.resolve("Cargo.toml"), """
				[package]
				name = "cache-demo"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				[dependencies]
				external-dependency = { path = "../dependency" }
				""");
			Path source = fixture.workspace.resolve("src/lib.rs");
			Files.writeString(source, "pub fn value() -> u8 { external_dependency::value() + 5 }\n");
			fixture.build();
			FileTime original = Files.getLastModifiedTime(source);
			Files.writeString(source, "pub fn value() -> u8 { external_dependency::value() + 7 }\n");
			Files.setLastModifiedTime(source, original);
			fixture.prepare();
			assertFalse(Files.exists(fixture.snapshot()));
			String output = fixture.build();
			assertTrue(fixture.libraryFresh(output, "external_dependency"));
			assertFalse(fixture.libraryFresh(output));
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "9");
			fixture.record();
			fixture.prepare();
			output = fixture.build();
			assertTrue(fixture.libraryFresh(output, "external_dependency"));
			assertTrue(fixture.libraryFresh(output));
			fixture.record();
			FileTime successfulTime = Files.getLastModifiedTime(source);
			Files.writeString(source, "pub fn value() -> u8 { external_dependency::value() + 9 }\n");
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			output = fixture.build();
			assertTrue(fixture.libraryFresh(output, "external_dependency"));
			assertFalse(fixture.libraryFresh(output));
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "11");
		}
	}

	/**
	 * Preserves libraries and sibling binaries when invalidating a binary owned by a library package.
	 *
	 * @throws IOException if actual Cargo or fixture operations fail
	 */
	@Test
	public void retainsSiblingBinaryAndLibrary() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.addWorkspaceLibrary();
			fixture.prepare();
			fixture.build();
			fixture.record();
			fixture.prepare(Set.of("side-demo"));
			String output = fixture.build();
			assertTrue(fixture.libraryFresh(output, "side_demo"));
			assertTrue(fixture.artifactFresh(output, fixture.siblingBinary, "bin"));
			assertFalse(fixture.artifactFresh(output, "side-demo", "bin"));
			assertTrue(fixture.artifactFresh(output, "cache-demo", "bin"));
		}
	}

	/**
	 * Rebuilds reverted library inputs after a failed build has already cached changed library output.
	 *
	 * @throws IOException if actual Cargo or fixture operations fail
	 */
	@Test
	public void restoresPartiallyBuiltRevertedInputs() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.addWorkspaceLibrary();
			fixture.prepare();
			fixture.build();
			fixture.record();
			Path side = fixture.workspace.resolve("side/src/lib.rs");
			Path root = fixture.workspace.resolve("src/lib.rs");
			FileTime sideTime = Files.getLastModifiedTime(side);
			FileTime rootTime = Files.getLastModifiedTime(root);
			String sideText = Files.readString(side);
			String rootText = Files.readString(root);
			Files.writeString(side, "pub fn value() -> u8 { 13 }\n");
			Files.writeString(root, "compile_error!(\"fail after changed library compilation\");\n");
			fixture.prepare();
			fixture.command(List.of("cargo", "build", "--offline", "--release", "--target", fixture.target,
				"--package", "side-demo"));
			expectThrows(IOException.class, fixture::build);
			Files.writeString(side, sideText);
			Files.setLastModifiedTime(side, sideTime);
			Files.writeString(root, rootText);
			Files.setLastModifiedTime(root, rootTime);
			fixture.prepare();
			String output = fixture.build();
			assertFalse(fixture.libraryFresh(output, "side_demo"));
			assertEquals(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "7");
			fixture.record();
		}
	}

	/**
	 * Preserves pending inputs and refuses successful state when cold cleanup fails on repeated attempts.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void failedColdCleanNeverRecordsSuccess() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			String metadata = fixture.command(List.of("cargo", "metadata", "--no-deps", "--format-version", "1")).
				stdout();
			fixture.useCargoFixture(metadata, 7);
			for (int attempt = 0; attempt < 2; attempt += 1)
			{
				IOException failure = expectThrows(IOException.class, fixture::prepare);
				assertTrue(failure.getMessage().contains("Cargo clean failed with status 7"));
				assertFalse(Files.exists(fixture.snapshot()));
				assertTrue(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())));
			}
		}
	}

	/**
	 * Cleans binary-only owners in sorted order without consuming fields unnecessary for warm preparation.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void cleansSortedBinaryOnlyOwners() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.prepare();
			fixture.build();
			fixture.record();
			String metadata = """
				{"packages":[
				  {"name":"proxy-owner","targets":[{"name":"proxy","kind":["bin"]}]},
				  {"name":"cli-owner","targets":[{"name":"codex","kind":["bin"]}]},
				  {"name":"shared-owner","targets":[
				    {"name":"helper","kind":["bin"]},{"name":"library","kind":["lib"]}]}]}
				""";
			fixture.useCargoFixture(metadata, 0);
			fixture.prepare(Set.of("codex", "helper", "proxy"));
			List<String> calls = Files.readAllLines(fixture.base.resolve("cargo-calls.jsonl"));
			assertEquals(calls.size(), 2);
			JsonNode clean = JsonMapper.builder().build().readTree(calls.get(1));
			assertEquals(clean, JsonMapper.builder().build().valueToTree(List.of("clean", "--release", "--target",
				fixture.target, "--manifest-path", fixture.workspace.resolve("Cargo.toml").toString(),
				"-p", "cli-owner", "-p", "proxy-owner")));
		}
	}

	/**
	 * Refuses missing requested binaries before cleanup even when metadata lacks workspace-member fields.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void missingBinaryRefusesClean() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.useCargoFixture("{\"packages\":[]}", 0);
			IOException failure = expectThrows(IOException.class, fixture::prepare);
			assertTrue(failure.getMessage().contains("Release binaries absent from Cargo metadata: cache-demo"));
			assertEquals(Files.readAllLines(fixture.base.resolve("cargo-calls.jsonl")).size(), 1);
			assertFalse(Files.exists(fixture.snapshot()));
			assertTrue(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())));
		}
	}

	/** Owns a dependency-free Cargo workspace and all compiler, capture and dependency storage. */
	private static final class Fixture implements AutoCloseable
	{
		private final Path base;
		private final Path workspace;
		private final Path temporary;
		private final Map<String, String> environment;
		private String target;
		private String siblingBinary = "side_demo";

		/**
		 * Defines paths before any operation that can fail after allocation.
		 *
		 * @param base the owned root
		 */
		private Fixture(Path base)
		{
			this.base = base;
			if (base.getFileSystem().getSeparator().equals("\\"))
				siblingBinary = "side_companion";
			workspace = base.resolve("workspace");
			temporary = base.resolve("captures");
			environment = new HashMap<>(System.getenv());
			environment.put("CARGO_HOME", base.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", base.resolve("target").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", base.resolve("xdg").toString());
			environment.put("TMPDIR", base.resolve("tmp").toString());
			environment.put("GIT_CEILING_DIRECTORIES", base.toString());
		}

		/**
		 * Allocates only the owned root, which callers immediately protect with try-with-resources.
		 *
		 * @return the fixture
		 * @throws IOException if allocation fails
		 */
		private static Fixture create() throws IOException
		{
			return new Fixture(Files.createTempDirectory("source-input-cache-"));
		}

		/**
		 * Writes a native-host Cargo package and creates every selected cache directory.
		 *
		 * @throws IOException if writing or host discovery fails
		 */
		private void initialize() throws IOException
		{
			Files.createDirectories(workspace.resolve("src"));
			for (String name : List.of("captures", "cargo-home", "target", "xdg", "tmp"))
				Files.createDirectories(base.resolve(name));
			Files.writeString(workspace.resolve("Cargo.toml"), """
				[package]
				name = "cache-demo"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				""");
			Files.writeString(workspace.resolve("src/lib.rs"), "pub fn value() -> u8 { 7 }\n");
			Files.writeString(workspace.resolve("src/main.rs"), "fn main() { println!(\"{}\", cache_demo::value()); }\n");
			target = command(List.of("rustc", "-vV")).stdout().lines().filter(line -> line.startsWith("host: ")).
				findFirst().orElseThrow(() -> new IOException("rustc did not report its host target")).substring(6);
			command(List.of("git", "init", "--quiet"));
			command(List.of("git", "add", "Cargo.toml", "src"));
		}

		/**
		 * Adds a workspace library with two differently spelled executable names.
		 *
		 * @throws IOException if writing fails
		 */
		private void addWorkspaceLibrary() throws IOException
		{
			Files.createDirectories(workspace.resolve("side/src/bin"));
			Files.writeString(workspace.resolve("side/Cargo.toml"), """
				[package]
				name = "side-demo"
				version = "0.1.0"
				edition = "2021"
				""");
			Files.writeString(workspace.resolve("side/src/lib.rs"), "pub fn value() -> u8 { 3 }\n");
			String main = "fn main() { println!(\"{}\", side_demo::value()); }\n";
			Files.writeString(workspace.resolve("side/src/main.rs"), main);
			Files.writeString(workspace.resolve("side/src/bin/" + siblingBinary + ".rs"), main);
			Files.writeString(workspace.resolve("Cargo.toml"), """
				[package]
				name = "cache-demo"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				members = ["side"]
				[dependencies]
				side-demo = { path = "side" }
				""");
			Files.writeString(workspace.resolve("src/lib.rs"), "pub fn value() -> u8 { side_demo::value() + 4 }\n");
		}

		/**
		 * Supplies controlled Cargo responses through Node's native executable and a preload file.
		 *
		 * @param metadata the exact metadata document
		 * @param cleanStatus cleanup's exit status
		 * @throws IOException if writing or discovering the executable fails
		 */
		private void useCargoFixture(String metadata, int cleanStatus) throws IOException
		{
			JsonMapper mapper = JsonMapper.builder().build();
			String node = command(List.of("node", "-p", "process.execPath")).stdout().strip();
			Path preload = base.resolve("cargo-fixture.cjs");
			Files.writeString(preload, """
				const fs = require('node:fs');
				const args = process.argv.slice(1);
				args[0] = require('node:path').basename(args[0]);
				fs.appendFileSync(%s, JSON.stringify(args) + '\\n');
				if (args[0] === 'metadata') {
				  process.stdout.write(%s);
				  process.exit(0);
				}
				if (args[0] === 'clean') process.exit(%d);
				process.exit(99);
				""".formatted(mapper.writeValueAsString(base.resolve("cargo-calls.jsonl").toString()),
				mapper.writeValueAsString(metadata), cleanStatus));
			environment.put("CARGO", node);
			environment.put("NODE_OPTIONS", "--require=" + mapper.writeValueAsString(preload.toString()));
		}

		/**
		 * Supplies the explicit operation context.
		 *
		 * @return its directory, storage, environment and clock
		 */
		private SourceInputCache.Context context()
		{
			return new SourceInputCache.Context(workspace, temporary, environment, Clock.systemUTC());
		}

		/**
		 * Supplies the requested executable and target.
		 *
		 * @return the cache request
		 */
		private SourceInputCache.Request request()
		{
			return new SourceInputCache.Request(workspace, target, Set.of("cache-demo"));
		}

		/**
		 * Runs preparation with locally owned diagnostic streams.
		 *
		 * @throws IOException if preparation fails
		 */
		private void prepare() throws IOException
		{
			prepare(request().binaries());
		}

		/**
		 * Prepares a selected executable set using real Cargo metadata.
		 *
		 * @param binaries requested executable names
		 * @throws IOException if preparation fails
		 */
		private void prepare(Set<String> binaries) throws IOException
		{
			try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream errors = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				SourceInputCache.prepare(new SourceInputCache.Request(workspace, target, binaries), context(), output, errors);
			}
		}

		/**
		 * Runs successful-build recording with locally owned diagnostics.
		 *
		 * @throws IOException if source inputs no longer match or recording fails
		 */
		private void record() throws IOException
		{
			try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream errors = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				SourceInputCache.record(request(), context(), output, errors);
			}
		}

		/**
		 * Compiles actual native-host release artifacts offline.
		 *
		 * @return Cargo's artifact JSON
		 * @throws IOException if compilation fails
		 */
		private String build() throws IOException
		{
			return command(List.of("cargo", "build", "--workspace", "--offline", "--release", "--target", target,
				"--message-format=json")).stdout();
		}

		/**
		 * Reads Cargo's library freshness report rather than inferring reuse from file presence.
		 *
		 * @param output actual Cargo JSON output
		 * @return the library's freshness flag
		 * @throws IOException if no library artifact is reported
		 */
		private boolean libraryFresh(String output) throws IOException
		{
			return libraryFresh(output, "cache_demo");
		}

		/**
		 * Reads freshness for a selected library from Cargo's actual artifact stream.
		 *
		 * @param output actual Cargo output
		 * @param name the library target name
		 * @return its freshness flag
		 * @throws IOException if its artifact is absent or repeated
		 */
		private boolean libraryFresh(String output, String name) throws IOException
		{
			return artifactFresh(output, name, "lib");
		}

		/**
		 * Selects one Cargo artifact by exact target name and kind.
		 *
		 * @param output actual Cargo output
		 * @param name the target name
		 * @param kind the target kind
		 * @return its freshness flag
		 * @throws IOException if its artifact is absent or repeated
		 */
		private boolean artifactFresh(String output, String name, String kind) throws IOException
		{
			List<JsonNode> artifacts = new ArrayList<>();
			for (String line : output.lines().toList())
			{
				JsonNode value = JsonMapper.builder().build().readTree(line);
				if (value.path("reason").asString().equals("compiler-artifact") &&
					value.path("target").path("name").asString().equals(name) &&
					value.path("target").path("kind").path(0).asString().equals(kind))
					artifacts.add(value);
			}
			if (artifacts.size() != 1)
				throw new IOException("Cargo did not report exactly one " + name + " library artifact: " + output);
			return artifacts.getFirst().path("fresh").booleanValue();
		}

		/**
		 * Runs a real subprocess inside the fixture's explicit environment.
		 *
		 * @param arguments its direct argv
		 * @return completed output
		 * @throws IOException if startup or execution fails
		 */
		private SystemCommands.Result command(List<String> arguments) throws IOException
		{
			SystemCommands.Result result = SystemCommands.capture(arguments, workspace, temporary, environment);
			if (result.status() != 0)
				throw new IOException("Fixture command failed: " + arguments + "\n" + result.stdout() + result.stderr());
			return result;
		}

		/**
		 * Locates the successful snapshot.
		 *
		 * @return its cache filename
		 */
		private Path snapshot()
		{
			return base.resolve("target/.codex-source-inputs/" + target + ".json");
		}

		/**
		 * Locates the host executable.
		 *
		 * @return its native filename
		 */
		private Path binary()
		{
			String name = "cache-demo";
			if (workspace.getFileSystem().getSeparator().equals("\\"))
				name += ".exe";
			return base.resolve("target").resolve(target).resolve("release").resolve(name);
		}

		@Override
		public void close() throws IOException
		{
			PathUtils.deleteDirectory(base, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}
}
