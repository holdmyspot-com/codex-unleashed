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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.apache.commons.io.function.IORunnable;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.holdmyspot.codexunleashed.scheduler.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies actual Cargo freshness through source-cache preparation and successful-build recording.
 */
public final class SourceInputCacheTest
{
	/**
	 * Creates Cargo cache tests.
	 */
	public SourceInputCacheTest()
	{
	}

	/**
	 * Reuses ci-test outputs across unchanged inputs and failed builds, invalidates changed bytes and preserves release state.
	 *
	 * @throws IOException if Cargo, fixture operations or cleanup fail
	 */
	@Test
	public void reusesTestProfileWithoutCleaningRelease() throws IOException
	{
		try (Fixture fixture = Fixture.create("reusesTestProfileWithoutCleaningRelease"))
		{
			// Real Cargo: the ci-test libraryFresh checks and the unchanged release binary digest need actual outputs.
			fixture.initialize();
			Path manifest = fixture.workspace.resolve("Cargo.toml");
			Files.writeString(manifest, Files.readString(manifest) + "[profile.ci-test]\ninherits = \"dev\"\n");
			fixture.prepare();
			fixture.build();
			fixture.record();
			String releaseSnapshot = Files.readString(fixture.snapshot());
			String releaseDigest = Sha256.digest(fixture.binary());
			SourceInputCache.Request request = new SourceInputCache.Request(fixture.workspace, fixture.target,
				Set.of("cache-demo"), CargoCacheProfile.CI_TEST);
			List<String> build = List.of("cargo", "build", "--offline", "--profile", "ci-test", "--target",
				fixture.target, "--message-format=json");

			fixture.prepare(request, fixture.context());
			requireThat(fixture.libraryFresh(fixture.command(build).stdout()), "fixture").isFalse();
			fixture.record(request);

			Path source = fixture.workspace.resolve("src/lib.rs");
			FileTime before = Files.getLastModifiedTime(source);
			Files.setLastModifiedTime(source, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare(request, fixture.context());
			String unchanged = fixture.command(build).stdout();
			requireThat(fixture.libraryFresh(unchanged), "fixture.libraryFresh(unchanged)").isTrue();
			requireThat(fixture.artifactFresh(unchanged, "cache-demo", "bin"), "fixture").isTrue();
			fixture.record(request);

			Files.writeString(source, "pub fn value() -> u8 { 9 }\n");
			Files.setLastModifiedTime(source, before);
			fixture.prepare(request, fixture.context());
			requireThat(fixture.libraryFresh(fixture.command(build).stdout()), "fixture").isFalse();
			Path executable = fixture.base.resolve("target").resolve(fixture.target).resolve("ci-test").
				resolve(fixture.binary().getFileName());
			requireThat(fixture.command(List.of(executable.toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record(request);

			Path main = fixture.workspace.resolve("src/main.rs");
			String validMain = Files.readString(main);
			Files.writeString(main, "compile_error!(\"controlled ci-test failure\");\nfn main() {}\n");
			fixture.prepare(request, fixture.context());
			requireException(IOException.class, () -> fixture.command(build));
			Files.writeString(main, validMain);
			fixture.prepare(request, fixture.context());
			requireThat(fixture.libraryFresh(fixture.command(build).stdout()), "fixture").isTrue();
			requireThat(fixture.command(List.of(executable.toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record(request);

			requireThat(Files.readString(fixture.snapshot()), "Files.readString(fixture.snapshot())")
				.isEqualTo(releaseSnapshot);
			requireThat(Sha256.digest(fixture.binary()), "Sha256.digest(fixture.binary())").isEqualTo(releaseDigest);
		}
	}

	/**
	 * Reuses identical libraries, rebuilds same-mtime content changes and refuses post-build changes when recording.
	 *
	 * @throws IOException if actual Cargo, fixture operations, or cleanup fail
	 */
	@Test
	public void reusesAndRebuildsActualCargoLibraries() throws IOException
	{
		try (Fixture fixture = Fixture.create("reusesAndRebuildsActualCargoLibraries"))
		{
			// Real Cargo: libraryFresh after same-mtime and include!() changes, and the binary printing 9 and 17.
			fixture.initialize();
			fixture.prepare();
			requireThat(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())), "Files").isTrue();
			requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())").isFalse();
			fixture.record();
			requireThat(Files.isRegularFile(fixture.snapshot()), "Files.isRegularFile(fixture.snapshot())").isTrue();
			requireThat(Files.exists(SourceInputSnapshots.pending(fixture.snapshot())), "Files").isFalse();

			Path source = fixture.workspace.resolve("src/lib.rs");
			FileTime before = Files.getLastModifiedTime(source);
			Files.setLastModifiedTime(source, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare();
			String unchanged = fixture.build();
			requireThat(fixture.libraryFresh(unchanged), "fixture.libraryFresh(unchanged)").isTrue();
			requireThat(fixture.artifactFresh(unchanged, "cache-demo", "bin"), "fixture")
				.withContext("unchanged source must retain completed executable compilation", "diagnostic")
				.isTrue();
			fixture.record();

			Files.writeString(source, "pub fn value() -> u8 { 9 }\n");
			Files.setLastModifiedTime(source, before);
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();

			String valid = Files.readString(source);
			FileTime successfulTime = Files.getLastModifiedTime(source);
			Files.writeString(source, "compile_error!(\"interrupted build\");\n");
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			requireException(IOException.class, fixture::build);
			Files.writeString(source, valid);
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			fixture.build();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();

			Path included = fixture.workspace.resolve("src/included.rs");
			Files.writeString(included, "pub fn value() -> u8 { 13 }\n");
			Files.writeString(source, "include!(\"included.rs\");\n");
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())").isFalse();
			fixture.record();
			FileTime includedTime = Files.getLastModifiedTime(included);
			Files.writeString(included, "pub fn value() -> u8 { 17 }\n");
			Files.setLastModifiedTime(included, includedTime);
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("17");
			fixture.record();

			fixture.prepare();
			String successful = Files.readString(fixture.snapshot());
			Files.writeString(source, "pub fn value() -> u8 { 11 }\n");
			IOException failure = requireException(IOException.class, fixture::record);
			requireThat(failure.getMessage().contains("Source inputs changed during the build"), "failure").isTrue();
			requireThat(Files.readString(fixture.snapshot()), "Files.readString(fixture.snapshot())").isEqualTo(successful);
			requireThat(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())), "Files").isTrue();
		}
	}

	/**
	 * Resumes completed library work after a cold executable failure without claiming successful compilation.
	 *
	 * @throws IOException if Cargo, fixture operations, or cleanup fail
	 */
	@Test
	public void resumesColdPartialLibraries() throws IOException
	{
		try (Fixture fixture = Fixture.create("resumesColdPartialLibraries"))
		{
			// Real Cargo: the libcache_demo rlib left by the failed build and the retry's libraryFresh check.
			fixture.initialize();
			Path executableSource = fixture.workspace.resolve("src/main.rs");
			String validExecutable = Files.readString(executableSource);
			Files.writeString(executableSource, "compile_error!(\"controlled executable failure\");\nfn main() {}\n");
			fixture.prepare();
			requireException(IOException.class, fixture::build);
			Path dependencies = fixture.base.resolve("target").resolve(fixture.target).resolve("release/deps");
			try (var outputs = Files.list(dependencies))
			{
				requireThat(outputs.anyMatch(path -> path.getFileName().toString().startsWith("libcache_demo-") &&
					path.toString().endsWith(".rlib")), "actual").withContext("library compilation must finish before the controlled failure", "diagnostic").isTrue();
			}
			requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();

			Files.writeString(executableSource, validExecutable);
			Path librarySource = fixture.workspace.resolve("src/lib.rs");
			FileTime before = Files.getLastModifiedTime(librarySource);
			Files.setLastModifiedTime(librarySource, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())")
				.withContext("retry must retain unaffected partial library work", "diagnostic")
				.isTrue();
			requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())")
				.withContext("resumption must not claim successful recording", "diagnostic")
				.isFalse();
			fixture.record();
			requireThat(Files.isRegularFile(fixture.snapshot()), "Files.isRegularFile(fixture.snapshot())").isTrue();
		}
	}

	/**
	 * Rebuilds changed host build scripts and their executable when the preparation clock moves backward.
	 *
	 * @throws IOException if Cargo, fixture access or cleanup fails
	 */
	@Test
	public void rebuildsChangedExecutableAfterClockRollback() throws IOException
	{
		try (Fixture fixture = Fixture.create("rebuildsChangedExecutableAfterClockRollback"))
		{
			// Real Cargo: the rebuilt binary printing 9, because future output timestamps come from actual compilation.
			fixture.initialize();
			Path source = fixture.workspace.resolve("build.rs");
			Files.writeString(source, "fn main() { println!(\"cargo:rustc-env=CACHE_VALUE=7\"); }\n");
			Files.writeString(fixture.workspace.resolve("src/main.rs"),
				"fn main() { println!(\"{}\", env!(\"CACHE_VALUE\")); }\n");
			fixture.command(List.of("git", "add", "build.rs"));
			fixture.prepare();
			fixture.build();
			fixture.record();
			FileTime before = Files.getLastModifiedTime(source);
			Files.writeString(source, "fn main() { println!(\"cargo:rustc-env=CACHE_VALUE=9\"); }\n");
			Files.setLastModifiedTime(source, before);

			SourceInputCache.Context context = new SourceInputCache.Context(fixture.scope, fixture.workspace, fixture.temporary,
				fixture.environment, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
			String preparation = fixture.prepare(fixture.request(), context);
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture")
				.withContext("content changes must rebuild even when the preparation clock moves backward: " +
					preparation, "diagnostic").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isTrue();
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
		try (Fixture fixture = Fixture.create("coldSnapshotRetainsExternalDependency"))
		{
			// Real Cargo: libraryFresh for external_dependency after the cold clean, and the binary printing 9 and 11.
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
			requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();
			String output = fixture.build();
			requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
			requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();
			fixture.prepare();
			output = fixture.build();
			requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
			requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isTrue();
			fixture.record();
			FileTime successfulTime = Files.getLastModifiedTime(source);
			Files.writeString(source, "pub fn value() -> u8 { external_dependency::value() + 9 }\n");
			Files.setLastModifiedTime(source, successfulTime);
			fixture.prepare();
			output = fixture.build();
			requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
			requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("11");
		}
	}

	/**
	 * Preserves libraries and every unchanged binary when preparing a selected executable.
	 *
	 * @throws IOException if actual Cargo or fixture operations fail
	 */
	@Test
	public void retainsSiblingBinaryAndLibrary() throws IOException
	{
		try (Fixture fixture = Fixture.create("retainsSiblingBinaryAndLibrary"))
		{
			// Real Cargo: artifactFresh for the sibling binary, both package binaries and the side_demo library.
			fixture.initialize();
			fixture.addWorkspaceLibrary();
			fixture.prepare();
			fixture.build();
			fixture.record();
			fixture.prepare(Set.of("side-demo"));
			String output = fixture.build();
			requireThat(fixture.libraryFresh(output, "side_demo"), "fixture").isTrue();
			requireThat(fixture.artifactFresh(output, fixture.siblingBinary, "bin"), "fixture").isTrue();
			requireThat(fixture.artifactFresh(output, "side-demo", "bin"), "fixture").isTrue();
			requireThat(fixture.artifactFresh(output, "cache-demo", "bin"), "fixture").isTrue();
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
		try (Fixture fixture = Fixture.create("restoresPartiallyBuiltRevertedInputs"))
		{
			// Real Cargo: side_demo libraryFresh being false after the reverted inputs, and the binary printing 7.
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
			requireException(IOException.class, fixture::build);
			Files.writeString(side, sideText);
			Files.setLastModifiedTime(side, sideTime);
			Files.writeString(root, rootText);
			Files.setLastModifiedTime(root, rootTime);
			fixture.prepare();
			String output = fixture.build();
			requireThat(fixture.libraryFresh(output, "side_demo"), "fixture").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("7");
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
		try (Fixture fixture = Fixture.create("failedColdCleanNeverRecordsSuccess"))
		{
			fixture.initializeSources();
			// The root package in the shape of `cargo metadata --no-deps --format-version 1`, limited to the fields
			// that preparation reads, so the cold path selects its workspace member for cleanup.
			String metadata = """
				{"packages":[{"name":"cache-demo","id":"path+file:///workspace#cache-demo@0.1.0","targets":[
				  {"name":"cache_demo","kind":["lib"]},{"name":"cache-demo","kind":["bin"]}]}],
				 "workspace_members":["path+file:///workspace#cache-demo@0.1.0"]}
				""";
			fixture.useCargoFixture(metadata, 7);
			for (int attempt = 0; attempt < 2; attempt += 1)
			{
				IOException failure = requireException(IOException.class, fixture::prepare);
				requireThat(failure.getMessage().contains("Cargo clean failed with status 7"), "failure").isTrue();
				requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();
				requireThat(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())), "Files").isTrue();
			}
		}
	}

	/**
	 * Reuses binary-only packages and rebuilds changed executable source even with its original timestamp.
	 *
	 * @throws IOException if fixture operations fail
	 */
	@Test
	public void reusesAndRebuildsBinaryOnlyPackage() throws IOException
	{
		try (Fixture fixture = Fixture.create("reusesAndRebuildsBinaryOnlyPackage"))
		{
			// Real Cargo: artifactFresh for the binary-only package, and the rebuilt binary printing 9.
			fixture.initialize();
			Files.delete(fixture.workspace.resolve("src/lib.rs"));
			Path source = fixture.workspace.resolve("src/main.rs");
			Files.writeString(source, "fn main() { println!(\"7\"); }\n");
			fixture.prepare();
			fixture.build();
			fixture.record();

			FileTime before = Files.getLastModifiedTime(source);
			Files.setLastModifiedTime(source, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isTrue();
			fixture.record();

			Files.writeString(source, "fn main() { println!(\"9\"); }\n");
			Files.setLastModifiedTime(source, before);
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
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
		try (Fixture fixture = Fixture.create("missingBinaryRefusesClean"))
		{
			fixture.initializeSources();
			fixture.useCargoFixture("{\"packages\":[]}", 0);
			IOException failure = requireException(IOException.class, fixture::prepare);
			requireThat(failure.getMessage().contains("Binaries absent from Cargo metadata: cache-demo"), "failure").isTrue();
			requireThat(Files.readAllLines(fixture.base.resolve("cargo-calls.jsonl")).size(), "Files").isEqualTo(1);
			requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();
			requireThat(Files.isRegularFile(SourceInputSnapshots.pending(fixture.snapshot())), "Files").isTrue();
		}
	}

	/**
	 * Models downloaded V8 bindings with an external path dependency that includes the declared file.
	 * Preparation reuses identical inputs, rebuilds changed bytes and refuses drift during compilation.
	 *
	 * @throws IOException if real Cargo, fixture access or cleanup fails
	 */
	@Test
	public void reusesAndInvalidatesExternalBindingWithActualCargo() throws IOException
	{
		try (Fixture fixture = Fixture.create("reusesAndInvalidatesExternalBindingWithActualCargo"))
		{
			// Real Cargo: libraryFresh across binding mtime and content changes, and the binary printing 9.
			fixture.initialize();
			Path binding = fixture.base.resolve("downloaded-binding.rs");
			Files.writeString(binding, "pub fn value() -> u8 { 7 }\n");
			fixture.environment.put("RUSTY_V8_SRC_BINDING_PATH", binding.toString());
			Path dependency = fixture.base.resolve("v8");
			Files.createDirectories(dependency.resolve("src"));
			Files.writeString(dependency.resolve("Cargo.toml"),
				"[package]\nname = \"v8\"\nversion = \"0.0.1\"\nedition = \"2021\"\n");
			Files.writeString(dependency.resolve("src/lib.rs"),
				"include!(env!(\"RUSTY_V8_SRC_BINDING_PATH\"));\n");
			Files.writeString(fixture.workspace.resolve("Cargo.toml"),
				Files.readString(fixture.workspace.resolve("Cargo.toml")) +
				"[dependencies]\nv8 = { path = \"../v8\" }\n");
			Files.writeString(fixture.workspace.resolve("src/lib.rs"), "pub fn value() -> u8 { v8::value() }\n");
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())").isFalse();
			fixture.record();

			FileTime original = Files.getLastModifiedTime(binding);
			Files.setLastModifiedTime(binding, FileTime.from(original.toInstant().plusSeconds(100)));
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())")
				.withContext("identical downloaded binding must reuse the library", "diagnostic")
				.isTrue();
			requireThat(Files.getLastModifiedTime(binding), "Files.getLastModifiedTime(binding)").isEqualTo(original);
			fixture.record();

			Files.writeString(binding, "pub fn value() -> u8 { 9 }\n");
			Files.setLastModifiedTime(binding, original);
			fixture.prepare();
			requireThat(fixture.libraryFresh(fixture.build()), "fixture.libraryFresh(fixture.build())")
				.withContext("changed binding bytes must rebuild despite the old mtime", "diagnostic")
				.isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();

			fixture.prepare();
			Files.writeString(binding, "pub fn value() -> u8 { 11 }\n");
			requireException(IOException.class, fixture::record);
		}
	}

	/**
	 * Owns a Cargo test workspace and all compiler, subprocess-capture, and dependency storage.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final ToolingScope scope = new DefaultToolingScope();
		private final long createdNanos = System.nanoTime();
		private final List<String> operationTimes = new ArrayList<>();
		private final String test;
		private final Path base;
		private final Path workspace;
		private final Path temporary;
		private final Map<String, String> environment;
		private String target;
		private String siblingBinary = "side_demo";

		/**
		 * Defines paths before any operation that can fail after allocation.
		 *
		 * @param test the test method whose operation times are reported on close
		 * @param base the owned root
		 */
		private Fixture(String test, Path base)
		{
			this.test = test;
			this.base = base;
			if (base.getFileSystem().getSeparator().equals("\\"))
				siblingBinary = "side_companion";
			workspace = base.resolve("workspace");
			temporary = base.resolve("captures");
			environment = new HashMap<>(System.getenv());
			environment.remove("RUSTY_V8_SRC_BINDING_PATH");
			environment.put("CARGO_HOME", base.resolve("cargo-home").toString());
			environment.put("CARGO_TARGET_DIR", base.resolve("target").toString());
			environment.put("CARGO_NET_OFFLINE", "true");
			environment.put("XDG_CACHE_HOME", base.resolve("xdg").toString());
			environment.put("TMPDIR", base.resolve("tmp").toString());
			environment.put("GIT_CEILING_DIRECTORIES", base.toString());
			// Git for Windows takes about nine seconds to start on hosted runners when it inherits the test JVM's
			// stdin, and every preparation starts Git six times; null-device stdin removes that delay.
			environment.put("GIT_REDIRECT_STDIN", "NUL");
		}

		/**
		 * Allocates only the owned root, which callers immediately protect with try-with-resources.
		 *
		 * @param test the test method whose operation times are reported on close
		 * @return the fixture
		 * @throws IOException if allocation fails
		 */
		private static Fixture create(String test) throws IOException
		{
			return new Fixture(test, Files.createTempDirectory("source-input-cache-"));
		}

		/**
		 * Writes the Cargo package, creates every selected cache directory and selects the native host target.
		 *
		 * @throws IOException if writing, Git or host discovery fails
		 */
		private void initialize() throws IOException
		{
			initializeSources();
			target = command(List.of("rustc", "-vV")).stdout().lines().filter(line -> line.startsWith("host: ")).
				findFirst().orElseThrow(() -> new IOException("rustc did not report its host target")).substring(6);
		}

		/**
		 * Writes the Cargo package into a new Git repository and creates every selected cache directory, without
		 * selecting a target.
		 *
		 * @throws IOException if writing or Git fails
		 */
		private void initializeSources() throws IOException
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
		 * Supplies controlled Cargo responses through Node's native executable and a preload file. Selects a fixed
		 * target name when none is selected, because the fake Cargo never compiles for it.
		 *
		 * @param metadata    the exact metadata document
		 * @param cleanStatus cleanup's exit status
		 * @throws IOException if writing or discovering the executable fails
		 */
		private void useCargoFixture(String metadata, int cleanStatus) throws IOException
		{
			if (target == null)
				target = "x86_64-unknown-linux-gnu";
			JsonMapper mapper = scope.getLeaderJsonMapper();
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
			return new SourceInputCache.Context(scope, workspace, temporary, environment, Clock.systemUTC());
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
		 * Prepares a selected executable set using the configured Cargo's metadata.
		 *
		 * @param binaries requested executable names
		 * @throws IOException if preparation fails
		 */
		private void prepare(Set<String> binaries) throws IOException
		{
			prepare(new SourceInputCache.Request(workspace, target, binaries), context());
		}

		/**
		 * Runs and times preparation with locally owned diagnostic streams.
		 *
		 * @param request the cache request
		 * @param context the operation context
		 * @return the preparation's combined progress and diagnostic output
		 * @throws IOException if preparation fails
		 */
		private String prepare(SourceInputCache.Request request, SourceInputCache.Context context) throws IOException
		{
			ByteArrayOutputStream preparation = new ByteArrayOutputStream();
			try (PrintStream output = new PrintStream(preparation, true, StandardCharsets.UTF_8);
				PrintStream errors = new PrintStream(preparation, true, StandardCharsets.UTF_8))
			{
				time("SourceInputCache.prepare " + request.profile().cargoName(),
					() -> SourceInputCache.prepare(request, context, output, errors));
			}
			return preparation.toString(StandardCharsets.UTF_8);
		}

		/**
		 * Runs successful-build recording of the root executable with locally owned diagnostics.
		 *
		 * @throws IOException if source inputs no longer match or recording fails
		 */
		private void record() throws IOException
		{
			record(request());
		}

		/**
		 * Runs and times successful-build recording with locally owned diagnostics.
		 *
		 * @param request the cache request
		 * @throws IOException if source inputs no longer match or recording fails
		 */
		private void record(SourceInputCache.Request request) throws IOException
		{
			try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
				PrintStream errors = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
			{
				time("SourceInputCache.record " + request.profile().cargoName(),
					() -> SourceInputCache.record(request, context(), output, errors));
			}
		}

		/**
		 * Runs a cache operation and adds its wall time and outcome to the report printed on close.
		 *
		 * @param operation a description of the operation
		 * @param action    the operation
		 * @throws IOException if the operation fails
		 */
		private void time(String operation, IORunnable action) throws IOException
		{
			long start = System.nanoTime();
			String outcome = "failed";
			try
			{
				action.run();
				outcome = "ok";
			}
			finally
			{
				addTime(operation, start, outcome);
			}
		}

		/**
		 * Adds one operation's wall time and outcome to the report printed on close.
		 *
		 * @param operation  a description of the operation
		 * @param startNanos the {@link System#nanoTime()} value when the operation started
		 * @param outcome    the operation's exit status or result
		 */
		private void addTime(String operation, long startNanos, String outcome)
		{
			Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
			operationTimes.add(elapsed.toMillis() + " ms, " + outcome + ": " + operation);
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
		 * Indicates whether Cargo reports the {@code cache_demo} library artifact as fresh.
		 *
		 * @param output actual Cargo JSON output
		 * @return the library's freshness flag
		 * @throws IOException if the selected library artifact is absent or repeated
		 */
		private boolean libraryFresh(String output) throws IOException
		{
			return libraryFresh(output, "cache_demo");
		}

		/**
		 * Indicates whether Cargo reports the named library artifact as fresh.
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
		 * Indicates whether Cargo reports an artifact as fresh after selecting exactly one by target name
		 * and its first target-kind entry.
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
				JsonNode value = scope.getLeaderJsonMapper().readTree(line);
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
			long start = System.nanoTime();
			String outcome = "not started";
			SystemCommands.Result result;
			try
			{
				result = SystemCommands.capture(arguments, workspace, temporary, environment);
				outcome = "status " + result.status();
			}
			finally
			{
				addTime(arguments.toString(), start, outcome);
			}
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
		 * Locates the root package's release executable for the fixture's native target.
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

		/**
		 * Prints the wall time of every timed operation, so a slow platform's test output names the operation that
		 * costs the time, then deletes the fixture root and all files and directories it owns.
		 *
		 * @throws IOException if a fixture entry cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			StringBuilder report = new StringBuilder();
			report.append("SourceInputCacheTest.").append(test).append(" operation times, ").
				append(Duration.ofNanos(System.nanoTime() - createdNanos).toMillis()).append(" ms in total:\n");
			for (String line : operationTimes)
				report.append("  ").append(line).append('\n');
			System.out.print(report);
			scope.close();
			PathUtils.deleteDirectory(base, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
				StandardDeleteOption.OVERRIDE_READ_ONLY);
		}
	}
}
