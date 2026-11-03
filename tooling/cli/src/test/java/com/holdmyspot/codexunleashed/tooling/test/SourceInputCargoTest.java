package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CargoCacheProfile;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.Sha256;
import com.holdmyspot.codexunleashed.tooling.SourceInputCache;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
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
import java.util.stream.Stream;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.apache.commons.io.function.IORunnable;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

import static com.holdmyspot.codexunleashed.scheduler.test.TestRequirements.requireException;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies actual Cargo freshness through source-cache preparation and successful-build recording.
 */
public final class SourceInputCargoTest
{
	/**
	 * Creates Cargo cache tests.
	 */
	public SourceInputCargoTest()
	{
	}

	/**
	 * Keeps ci-test freshness state apart from release state: a cold ci-test preparation rebuilds even though a
	 * release snapshot exists, a recorded ci-test snapshot lets unchanged inputs reuse ci-test outputs, and neither
	 * step changes the release snapshot or executable. Same-mtime content changes and failed builds take the same
	 * profile-independent path as release builds and are verified in {@link #reusesAndRebuildsCargoInputs} and
	 * {@link #restoresPartiallyBuiltRevertedInputs}.
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

			requireThat(Files.readString(fixture.snapshot()), "Files.readString(fixture.snapshot())")
				.isEqualTo(releaseSnapshot);
			requireThat(Sha256.digest(fixture.binary()), "Sha256.digest(fixture.binary())").isEqualTo(releaseDigest);
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
			try (Stream<Path> outputs = Files.list(dependencies))
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
	 * Reuses binary-only outputs, invalidates same-time executable changes, and rebuilds changed host build scripts
	 * and their executable after clock rollback, then reuses the rebuilt outputs.
	 *
	 * @throws IOException if actual Cargo, fixture access or cleanup fails
	 */
	@Test
	public void rebuildsBinaryAfterClockRollback() throws IOException
	{
		try (Fixture fixture = Fixture.create("rebuildsBinaryAfterClockRollback"))
		{
			fixture.initialize();
			Files.delete(fixture.workspace.resolve("src/lib.rs"));
			Path script = fixture.workspace.resolve("build.rs");
			Files.writeString(script, "fn main() { println!(\"cargo:rustc-env=CACHE_VALUE=7\"); }\n");
			Path source = fixture.workspace.resolve("src/main.rs");
			Files.writeString(source, "fn main() { println!(\"{}\", env!(\"CACHE_VALUE\")); }\n");
			fixture.prepare();
			fixture.build();
			fixture.record();

			FileTime before = Files.getLastModifiedTime(source);
			Files.setLastModifiedTime(source, FileTime.from(before.toInstant().plusSeconds(100)));
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isTrue();
			fixture.record();

			Files.writeString(source, "fn main() { println!(\"{}\", env!(\"CACHE_VALUE\").parse::<u8>().unwrap() + 2); }\n");
			Files.setLastModifiedTime(source, before);
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
			fixture.record();

			FileTime scriptTime = Files.getLastModifiedTime(script);
			Files.writeString(script, "fn main() { println!(\"cargo:rustc-env=CACHE_VALUE=9\"); }\n");
			Files.setLastModifiedTime(script, scriptTime);
			SourceInputCache.Context context = new SourceInputCache.Context(fixture.scope, fixture.workspace, fixture.temporary,
				fixture.environment, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), SystemCommands::capture);
			String preparation = fixture.prepare(fixture.request(), context);
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture")
				.withContext("clock rollback must rebuild the executable: " + preparation, "diagnostic").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("11");
			fixture.record();
			fixture.prepare();
			requireThat(fixture.artifactFresh(fixture.build(), "cache-demo", "bin"), "fixture").isTrue();
		}
	}

	/**
	 * Retains an external dependency through cold and warm preparation, reuses unchanged libraries and executables,
	 * and rebuilds same-time library, included-file and downloaded-binding changes.
	 *
	 * @throws IOException if actual Cargo, fixture access or cleanup fails
	 */
	@Test
	public void reusesAndRebuildsCargoInputs() throws IOException
	{
		try (Fixture fixture = Fixture.create("reusesAndRebuildsCargoInputs"))
		{
			fixture.initialize();
			Path binding = fixture.addExternalInputs();
			verifyColdDependencyRetention(fixture);
			verifyUnchangedCargoInputs(fixture, binding);
			verifyIncludedSourceChanges(fixture);
			verifyBindingInvalidation(fixture, binding);
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
			fixture.addSiblingBinaries();
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
			SystemCommands.Result failed = fixture.capture(fixture.buildArguments());
			requireThat(failed.status(), "failed.status()").isNotEqualTo(0);
			requireThat(fixture.libraryFresh(failed.stdout(), "side_demo"), "fixture")
				.withContext("the failed build must have completed the changed dependency", "diagnostic").isFalse();
			Files.writeString(side, sideText);
			Files.setLastModifiedTime(side, sideTime);
			Files.writeString(root, rootText);
			Files.setLastModifiedTime(root, rootTime);
			fixture.prepare();
			String output = fixture.build();
			requireThat(fixture.libraryFresh(output, "side_demo"), "fixture").isFalse();
			requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("7");
		}
	}

	/**
	 * Leaves compiled dependencies without a source snapshot, then verifies cold workspace cleanup retains the
	 * ordinary external dependency and produces an executable from the changed workspace source.
	 *
	 * @param fixture the workspace containing an external library and a binding-backed V8 library
	 * @throws IOException if actual Cargo or fixture access fails
	 */
	private void verifyColdDependencyRetention(Fixture fixture) throws IOException
	{
		fixture.build();
		Path source = fixture.workspace.resolve("src/lib.rs");
		FileTime original = Files.getLastModifiedTime(source);
		Files.writeString(source, "pub fn value() -> u8 { external_dependency::value() + v8::value() + 2 }\n");
		Files.setLastModifiedTime(source, original);
		fixture.prepare();
		requireThat(Files.exists(fixture.snapshot()), "Files.exists(fixture.snapshot())").isFalse();
		String output = fixture.build();
		requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
		requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
		requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
		fixture.record();
	}

	/**
	 * Verifies Cargo reuses the root library, both external libraries and the executable after timestamp-only changes.
	 *
	 * @param fixture the successfully recorded workspace
	 * @param binding the external binding included by V8
	 * @throws IOException if actual Cargo or fixture access fails
	 */
	private void verifyUnchangedCargoInputs(Fixture fixture, Path binding) throws IOException
	{
		Path source = fixture.workspace.resolve("src/lib.rs");
		FileTime sourceTime = Files.getLastModifiedTime(source);
		FileTime bindingTime = Files.getLastModifiedTime(binding);
		Files.setLastModifiedTime(source, FileTime.from(sourceTime.toInstant().plusSeconds(100)));
		Files.setLastModifiedTime(binding, FileTime.from(bindingTime.toInstant().plusSeconds(100)));
		fixture.prepare();
		String output = fixture.build();
		requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isTrue();
		requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
		requireThat(fixture.libraryFresh(output, "v8"), "fixture").isTrue();
		requireThat(fixture.artifactFresh(output, "cache-demo", "bin"), "fixture").isTrue();
		requireThat(Files.getLastModifiedTime(binding), "Files.getLastModifiedTime(binding)").isEqualTo(bindingTime);
		fixture.record();
	}

	/**
	 * Verifies same-time workspace and included-file changes rebuild the root library without rebuilding an external
	 * dependency, and that actual executable results reflect each source change.
	 *
	 * @param fixture the successfully recorded workspace
	 * @throws IOException if actual Cargo or fixture access fails
	 */
	private void verifyIncludedSourceChanges(Fixture fixture) throws IOException
	{
		Path source = fixture.workspace.resolve("src/lib.rs");
		FileTime original = Files.getLastModifiedTime(source);
		Path included = fixture.workspace.resolve("src/included.rs");
		Files.writeString(included, "pub fn value() -> u8 { external_dependency::value() + v8::value() + 2 }\n");
		Files.writeString(source, "include!(\"included.rs\");\n");
		Files.setLastModifiedTime(source, original);
		fixture.prepare();
		String output = fixture.build();
		requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
		requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
		requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("9");
		fixture.record();

		FileTime includedTime = Files.getLastModifiedTime(included);
		Files.writeString(included, "pub fn value() -> u8 { external_dependency::value() + v8::value() + 10 }\n");
		Files.setLastModifiedTime(included, includedTime);
		fixture.prepare();
		output = fixture.build();
		requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
		requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
		requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("17");
		fixture.record();
	}

	/**
	 * Verifies a same-time binding change rebuilds V8 and its consumers while retaining the ordinary dependency.
	 *
	 * @param fixture the successfully recorded workspace
	 * @param binding the external binding included by V8
	 * @throws IOException if actual Cargo or fixture access fails
	 */
	private void verifyBindingInvalidation(Fixture fixture, Path binding) throws IOException
	{
		FileTime original = Files.getLastModifiedTime(binding);
		Files.writeString(binding, "pub fn value() -> u8 { 7 }\n");
		Files.setLastModifiedTime(binding, original);
		fixture.prepare();
		String output = fixture.build();
		requireThat(fixture.libraryFresh(output, "v8"), "fixture").isFalse();
		requireThat(fixture.libraryFresh(output), "fixture.libraryFresh(output)").isFalse();
		requireThat(fixture.libraryFresh(output, "external_dependency"), "fixture").isTrue();
		requireThat(fixture.command(List.of(fixture.binary().toString())).stdout().strip(), "fixture").isEqualTo("19");
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
				this.siblingBinary = "side_companion";
			this.workspace = base.resolve("workspace");
			this.temporary = base.resolve("captures");
			this.environment = new HashMap<>(System.getenv());
			environment.remove("RUSTY_V8_SRC_BINDING_PATH");
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
		 * @throws IOException if writing or host discovery fails
		 */
		private void initialize() throws IOException
		{
			initializeSources();
			target = command(List.of("rustc", "-vV")).stdout().lines().filter(line -> line.startsWith("host: ")).
				findFirst().orElseThrow(() -> new IOException("rustc did not report its host target")).substring(6);
		}

		/**
		 * Writes a Cargo package outside a Git repository and creates every selected cache directory, without
		 * selecting a target.
		 *
		 * @throws IOException if writing fails
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
		}

		/**
		 * Adds an ordinary external dependency and a V8 package that includes the declared external binding.
		 *
		 * @return the downloaded binding path
		 * @throws IOException if fixture writing fails
		 */
		private Path addExternalInputs() throws IOException
		{
			Path dependency = base.resolve("dependency");
			Files.createDirectories(dependency.resolve("src"));
			Files.writeString(dependency.resolve("Cargo.toml"), """
				[package]
				name = "external-dependency"
				version = "0.1.0"
				edition = "2021"
				[workspace]
				""");
			Files.writeString(dependency.resolve("src/lib.rs"), "pub fn value() -> u8 { 2 }\n");
			Path binding = base.resolve("downloaded-binding.rs");
			Files.writeString(binding, "pub fn value() -> u8 { 5 }\n");
			environment.put("RUSTY_V8_SRC_BINDING_PATH", binding.toString());
			Path v8 = base.resolve("v8");
			Files.createDirectories(v8.resolve("src"));
			Files.writeString(v8.resolve("Cargo.toml"),
				"[package]\nname = \"v8\"\nversion = \"0.0.1\"\nedition = \"2021\"\n");
			Files.writeString(v8.resolve("src/lib.rs"), "include!(env!(\"RUSTY_V8_SRC_BINDING_PATH\"));\n");
			Files.writeString(workspace.resolve("Cargo.toml"), Files.readString(workspace.resolve("Cargo.toml")) +
				"[dependencies]\nexternal-dependency = { path = \"../dependency\" }\nv8 = { path = \"../v8\" }\n");
			Files.writeString(workspace.resolve("src/lib.rs"),
				"pub fn value() -> u8 { external_dependency::value() + v8::value() }\n");
			return binding;
		}

		/**
		 * Adds the workspace dependency library needed by recovery and sibling-output checks.
		 * @throws IOException if writing fails
		 */
		private void addWorkspaceLibrary() throws IOException
		{
			Files.createDirectories(workspace.resolve("side/src"));
			Files.writeString(workspace.resolve("side/Cargo.toml"), """
				[package]
				name = "side-demo"
				version = "0.1.0"
				edition = "2021"
				""");
			Files.writeString(workspace.resolve("side/src/lib.rs"), "pub fn value() -> u8 { 3 }\n");
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
		 * Adds the selected dependency executable and its differently spelled sibling.
		 *
		 * @throws IOException if writing fails
		 */
		private void addSiblingBinaries() throws IOException
		{
			Files.createDirectories(workspace.resolve("side/src/bin"));
			String main = "fn main() { println!(\"{}\", side_demo::value()); }\n";
			Files.writeString(workspace.resolve("side/src/main.rs"), main);
			Files.writeString(workspace.resolve("side/src/bin/" + siblingBinary + ".rs"), main);
		}

		/**
		 * Supplies the explicit operation context.
		 *
		 * @return its directory, storage, environment and clock
		 */
		private SourceInputCache.Context context()
		{
			return new SourceInputCache.Context(scope, workspace, temporary, environment, Clock.systemUTC(),
				SystemCommands::capture);
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
			return command(buildArguments()).stdout();
		}

		/**
		 * Supplies the real workspace build command for successful and deliberately failed compilations.
		 *
		 * @return direct Cargo build arguments
		 */
		private List<String> buildArguments()
		{
			return List.of("cargo", "build", "--workspace", "--offline", "--release", "--target", target,
				"--message-format=json");
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
			SystemCommands.Result result = capture(arguments);
			if (result.status() != 0)
				throw new IOException("Fixture command failed: " + arguments + "\n" + result.stdout() + result.stderr());
			return result;
		}

		/**
		 * Captures and times a real command, including an expected unsuccessful compilation.
		 *
		 * @param arguments direct executable and arguments
		 * @return ordinary status and complete captured output
		 * @throws IOException if startup or capture fails
		 */
		private SystemCommands.Result capture(List<String> arguments) throws IOException
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
			report.append("SourceInputCargoTest.").append(test).append(" operation times, ").
				append(Duration.ofNanos(System.nanoTime() - createdNanos).toMillis()).append(" ms in total:\n");
			for (String line : operationTimes)
				report.append("  ").append(line).append('\n');
			System.out.print(report);
			try
			{
				scope.close();
			}
			finally
			{
				PathUtils.deleteDirectory(base, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
					StandardDeleteOption.OVERRIDE_READ_ONLY);
			}
		}
	}
}
