package com.holdmyspot.codexunleashed.tooling.test;

import com.holdmyspot.codexunleashed.tooling.CargoCacheProfile;
import com.holdmyspot.codexunleashed.tooling.DefaultToolingScope;
import com.holdmyspot.codexunleashed.tooling.SourceInputCache;
import com.holdmyspot.codexunleashed.tooling.SourceInputInventory;
import com.holdmyspot.codexunleashed.tooling.SourceInputSnapshots;
import com.holdmyspot.codexunleashed.tooling.SystemCommands;
import com.holdmyspot.codexunleashed.tooling.ToolingScope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.holdmyspot.codexunleashed.tooling.test.TestRequirements.requireException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link SourceInputCache} decisions, timestamps and persisted state without launching external commands.
 * Actual Cargo freshness and executable results are verified by {@link SourceInputCargoTest}.
 */
public final class SourceInputCacheTest
{
	/** Recorded source time, separated from the controlled preparation clock. */
	private static final Instant SAVED = Instant.parse("2020-01-01T00:00:00Z");
	/** Preparation's content-change timestamp, independent of the wall clock. */
	private static final Instant NOW = SAVED.plusSeconds(1_000);

	/**
	 * Creates independent cache tests.
	 */
	public SourceInputCacheTest()
	{
	}

	/**
	 * Exercises the same decisions in both compiled-output profiles.
	 *
	 * @return release and ci-test requests
	 */
	@DataProvider
	public Object[][] profiles()
	{
		return new Object[][]{{CargoCacheProfile.RELEASE}, {CargoCacheProfile.CI_TEST}};
	}

	/**
	 * Restores unchanged source timestamps and records success only when explicitly requested.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void restoresRecordedSourceTimes(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			FileTime recorded = Files.getLastModifiedTime(fixture.source);
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(100)));
			requireThat(Files.getLastModifiedTime(fixture.source), "changedTimestamp").isNotEqualTo(recorded);

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(fixture.source), "restoredTimestamp").isEqualTo(recorded);
			requireThat(Files.readString(fixture.source), "sourceContents").isEqualTo("pub fn value() -> u8 { 7 }\n");
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
			requireThat(SourceInputSnapshots.pending(fixture.snapshot(profile)), "pendingInputs").isRegularFile();
			requireThat(SourceInputSnapshots.resumable(fixture.snapshot(profile)), "completedPreparation").isRegularFile();
			fixture.record(profile);
			requireThat(Files.exists(SourceInputSnapshots.pending(fixture.snapshot(profile))), "pendingInputs").isFalse();
			requireThat(Files.exists(SourceInputSnapshots.resumable(fixture.snapshot(profile))), "completedPreparation").isFalse();
		}
	}

	/**
	 * Detects changed included bytes despite the original timestamp and restores an unchanged library timestamp.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void marksSameTimeIncludedChanges(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.write(fixture.source, "include!(\"included.rs\");\n", SAVED);
			Path included = fixture.write(fixture.workspace.resolve("src/included.rs"), "original", SAVED);
			fixture.seedSuccess(profile);
			FileTime recorded = Files.getLastModifiedTime(included);
			fixture.write(included, "changed", SAVED);
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(100)));
			requireThat(Files.getLastModifiedTime(included), "unchangedTimestamp").isEqualTo(recorded);

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(included).toInstant(), "changedInputTimestamp").isEqualTo(NOW);
			requireThat(Files.getLastModifiedTime(fixture.source).toInstant(), "unchangedLibraryTimestamp").isEqualTo(SAVED);
			requireThat(Files.readString(included), "changedContents").isEqualTo("changed");
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
		}
	}

	/**
	 * Invalidates reverted inputs against interrupted preparation without replacing the successful snapshot.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void invalidatesRevertedPartialInputs(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			String successful = Files.readString(fixture.snapshot(profile));
			String original = Files.readString(fixture.source);
			fixture.write(fixture.source, "partially compiled version", SAVED);
			// Pending inputs model an interrupted build; success still describes the version being restored.
			SourceInputSnapshots.write(fixture.scope, SourceInputSnapshots.pending(fixture.snapshot(profile)),
				fixture.inventory());
			fixture.write(fixture.source, original, SAVED);

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(fixture.source).toInstant(), "revertedInputTimestamp").isEqualTo(NOW);
			requireThat(Files.readString(fixture.snapshot(profile)), "successfulInputs").isEqualTo(successful);
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
			fixture.record(profile);
			requireThat(Files.exists(SourceInputSnapshots.pending(fixture.snapshot(profile))), "pendingInputs").isFalse();
		}
	}

	/**
	 * Reuses completed cold preparation after an unrecorded build and keeps success absent until recording.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void resumesColdPreparation(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.prepare(profile);
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(100)));

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(fixture.source).toInstant(), "resumedTimestamp").isEqualTo(SAVED);
			requireThat(fixture.cleanCalls().size(), "coldCleanCount").isEqualTo(1);
			requireThat(Files.exists(fixture.snapshot(profile)), "successfulSnapshot").isFalse();
			fixture.record(profile);
			requireThat(fixture.snapshot(profile), "recordedSuccess").isRegularFile();
		}
	}

	/**
	 * Selects every workspace package for cold cleanup, excluding the external dependency.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void cleansOnlyWorkspacePackages(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();

			fixture.prepare(profile);

			requireThat(fixture.cleanCalls(), "coldCleanupArguments").isEqualTo(List.of(List.of("fixture-cargo",
				"clean", "--profile", profile.cargoName(), "--target", "test-target", "--manifest-path",
				fixture.workspace.resolve("Cargo.toml").toString(), "-p", "cache-demo", "-p", "side-demo")));
			requireThat(Files.exists(fixture.snapshot(profile)), "successfulSnapshot").isFalse();
			requireThat(SourceInputSnapshots.pending(fixture.snapshot(profile)), "pendingInputs").isRegularFile();
		}
	}

	/**
	 * Repeated failed cleanup preserves pending inputs without producing resumable or successful state.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void failedColdCleanNeverRecordsSuccess(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.cleanStatus = 7;
			for (int attempt = 0; attempt < 2; attempt += 1)
			{
				IOException failure = requireException(IOException.class, () -> fixture.prepare(profile));
				requireThat(failure.getMessage(), "cleanupFailure").contains("Cargo clean failed with status 7");
				requireThat(Files.exists(fixture.snapshot(profile)), "successfulSnapshot").isFalse();
				requireThat(Files.exists(SourceInputSnapshots.resumable(fixture.snapshot(profile))), "resumableState").isFalse();
				requireThat(SourceInputSnapshots.pending(fixture.snapshot(profile)), "pendingInputs").isRegularFile();
			}
			requireThat(fixture.cleanCalls().size(), "cleanupAttempts").isEqualTo(2);
		}
	}

	/**
	 * Refuses a missing executable before cleanup even when metadata omits workspace membership.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void missingBinaryRefusesClean(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.metadata = "{\"packages\":[]}";

			IOException failure = requireException(IOException.class, () -> fixture.prepare(profile));

			requireThat(failure.getMessage(), "missingExecutable").contains("Binaries absent from Cargo metadata: cache-demo");
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
			requireThat(fixture.cargoCalls.size(), "metadataRequests").isEqualTo(1);
			requireThat(Files.exists(fixture.snapshot(profile)), "successfulSnapshot").isFalse();
			requireThat(SourceInputSnapshots.pending(fixture.snapshot(profile)), "pendingInputs").isRegularFile();
		}
	}

	/**
	 * Refuses recording source drift without replacing success or discarding the pending inputs.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void refusesPostPreparationDrift(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			String successful = Files.readString(fixture.snapshot(profile));
			fixture.prepare(profile);
			fixture.write(fixture.source, "changed during compilation", SAVED);

			IOException failure = requireException(IOException.class, () -> fixture.record(profile));

			requireThat(failure.getMessage(), "recordingFailure").contains("Source inputs changed during the build");
			requireThat(Files.readString(fixture.snapshot(profile)), "successfulInputs").isEqualTo(successful);
			requireThat(SourceInputSnapshots.pending(fixture.snapshot(profile)), "pendingInputs").isRegularFile();
		}
	}

	/**
	 * Cold ci-test preparation and recording use independent snapshots even when release success already exists.
	 *
	 * @throws IOException if cache or fixture access fails
	 */
	@Test
	public void separatesProfileSnapshots() throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(CargoCacheProfile.RELEASE);
			String release = Files.readString(fixture.snapshot(CargoCacheProfile.RELEASE));
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(50)));
			fixture.prepare(CargoCacheProfile.CI_TEST);
			requireThat(fixture.cleanCalls().size(), "ci-testColdCleanup").isEqualTo(1);
			requireThat(fixture.cleanCalls().getFirst().get(3), "cleanupProfile").isEqualTo("ci-test");
			requireThat(Files.exists(fixture.snapshot(CargoCacheProfile.CI_TEST)), "ci-testSuccess").isFalse();
			fixture.record(CargoCacheProfile.CI_TEST);
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(100)));

			fixture.prepare(CargoCacheProfile.CI_TEST);

			requireThat(Files.getLastModifiedTime(fixture.source).toInstant(), "ci-testRecordedTimestamp")
				.isEqualTo(SAVED.plusSeconds(50));
			fixture.record(CargoCacheProfile.CI_TEST);
			requireThat(Files.readString(fixture.snapshot(CargoCacheProfile.RELEASE)), "releaseInputs").isEqualTo(release);
			requireThat(fixture.snapshot(CargoCacheProfile.CI_TEST), "ci-testInputs").isRegularFile();
		}
	}

	/**
	 * Clock rollback deletes host and target outputs only in the selected profile without restoring source times.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void removesOnlyFutureProfileOutputs(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			CargoCacheProfile other = CargoCacheProfile.CI_TEST;
			if (profile == CargoCacheProfile.CI_TEST)
				other = CargoCacheProfile.RELEASE;
			Path host = fixture.write(fixture.target.resolve(profile.cargoName() + "/build/script"), "host", NOW.plusSeconds(1));
			Path target = fixture.write(fixture.target.resolve("test-target/" + profile.cargoName() + "/cache-demo"), "target", NOW);
			Path retainedHost = fixture.write(fixture.target.resolve(other.cargoName() + "/build/script"), "keep host", SAVED);
			Path retainedTarget = fixture.write(fixture.target.resolve("test-target/" + other.cargoName() + "/cache-demo"), "keep target", SAVED);
			FileTime changedTime = FileTime.from(SAVED.plusSeconds(100));
			Files.setLastModifiedTime(fixture.source, changedTime);

			fixture.prepare(profile);

			requireThat(Files.exists(host.getParent().getParent()), "selectedHostProfile").isFalse();
			requireThat(Files.exists(target.getParent()), "selectedTargetProfile").isFalse();
			requireThat(Files.readString(retainedHost), "otherHostProfile").isEqualTo("keep host");
			requireThat(Files.readString(retainedTarget), "otherTargetProfile").isEqualTo("keep target");
			requireThat(Files.getLastModifiedTime(fixture.source), "unrestoredSourceTime").isEqualTo(changedTime);
			requireThat(fixture.cleanCalls(), "packageCleanup").isEmpty();
			requireThat(SourceInputSnapshots.resumable(fixture.snapshot(profile)), "completedPreparation").isRegularFile();
		}
	}

	/**
	 * Binding preparation restores identical bytes, invalidates same-time changes and refuses recording drift.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void restoresAndInvalidatesBinding(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			Path binding = fixture.write(fixture.base.resolve("downloaded-binding.rs"), "original binding", SAVED);
			fixture.environment.put("RUSTY_V8_SRC_BINDING_PATH", binding.toString());
			fixture.prepare(profile);
			requireThat(fixture.cleanCalls().getFirst(), "bindingCleanup").isEqualTo(List.of("fixture-cargo", "clean",
				"--profile", profile.cargoName(), "--target", "test-target", "--manifest-path",
				fixture.workspace.resolve("Cargo.toml").toString(), "-p", "v8"));
			fixture.record(profile);
			FileTime original = Files.getLastModifiedTime(binding);
			Files.setLastModifiedTime(binding, FileTime.from(SAVED.plusSeconds(100)));

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(binding), "restoredBindingTime").isEqualTo(original);
			fixture.write(binding, "changed binding", SAVED);
			fixture.prepare(profile);
			requireThat(Files.getLastModifiedTime(binding).toInstant(), "changedBindingTime").isEqualTo(NOW);
			requireThat(Files.readString(binding), "bindingContents").isEqualTo("changed binding");
			fixture.record(profile);
			Path snapshot = profile.snapshotDirectory(fixture.target).resolve("v8-binding-test-target.json");
			String successful = Files.readString(snapshot);
			fixture.prepare(profile);
			fixture.write(binding, "drifted binding", SAVED);

			IOException failure = requireException(IOException.class, () -> fixture.record(profile));

			requireThat(failure.getMessage(), "bindingDrift").contains("Source inputs changed during the build");
			requireThat(Files.readString(snapshot), "successfulBindingInputs").isEqualTo(successful);
			requireThat(SourceInputSnapshots.pending(snapshot), "pendingBindingInputs").isRegularFile();
			requireThat(fixture.cleanCalls().size(), "cold-onlyCleanup").isEqualTo(2);
		}
	}

	/**
	 * Restores and invalidates binary-only source inputs without assuming a library target exists.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void preparesBinaryOnlySources(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			Files.delete(fixture.source);
			Path main = fixture.write(fixture.workspace.resolve("src/main.rs"), "original binary", SAVED);
			fixture.metadata = """
				{"packages":[{"name":"cache-demo","id":"root","targets":[{"name":"cache-demo","kind":["bin"]}]}],
				 "workspace_members":["root"]}
				""";
			fixture.seedSuccess(profile);
			Files.setLastModifiedTime(main, FileTime.from(SAVED.plusSeconds(100)));

			fixture.prepare(profile);

			requireThat(Files.getLastModifiedTime(main).toInstant(), "restoredBinaryTime").isEqualTo(SAVED);
			fixture.write(main, "changed binary", SAVED);
			fixture.prepare(profile);
			requireThat(Files.getLastModifiedTime(main).toInstant(), "changedBinaryTime").isEqualTo(NOW);
			requireThat(Files.readString(main), "binaryContents").isEqualTo("changed binary");
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
		}
	}

	/**
	 * Outputs exactly at the preparation clock remain reusable and allow source timestamp restoration.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void retainsOutputsAtPreparationTime(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			Path host = fixture.write(fixture.target.resolve(profile.cargoName() + "/build/script"), "host", NOW);
			Path target = fixture.write(fixture.target.resolve("test-target/" + profile.cargoName() + "/cache-demo"), "target", NOW);
			Files.setLastModifiedTime(fixture.source, FileTime.from(SAVED.plusSeconds(100)));

			fixture.prepare(profile);

			requireThat(Files.readString(host), "hostOutput").isEqualTo("host");
			requireThat(Files.readString(target), "targetOutput").isEqualTo("target");
			requireThat(Files.getLastModifiedTime(fixture.source).toInstant(), "restoredSourceTime").isEqualTo(SAVED);
			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
		}
	}

	/**
	 * Selecting one executable with unchanged inputs does not request package cleanup or delete cached siblings.
	 *
	 * @param profile compiled-output profile
	 * @throws IOException if cache or fixture access fails
	 */
	@Test(dataProvider = "profiles")
	public void retainsUnchangedSelectedOutputs(CargoCacheProfile profile) throws IOException
	{
		try (Fixture fixture = Fixture.create())
		{
			fixture.initialize();
			fixture.seedSuccess(profile);
			Path selected = fixture.write(fixture.target.resolve("test-target/" + profile.cargoName() + "/side-demo"), "selected", SAVED);
			Path sibling = fixture.write(selected.resolveSibling("side_companion"), "sibling", SAVED);
			Path library = fixture.write(selected.resolveSibling("deps/libside_demo.rlib"), "library", SAVED);
			SourceInputCache.Request request = new SourceInputCache.Request(fixture.workspace, "test-target",
				Set.of("side-demo"), profile);

			fixture.prepare(request);

			requireThat(fixture.cleanCalls(), "cleanRequests").isEmpty();
			requireThat(Files.readString(selected), "selectedOutput").isEqualTo("selected");
			requireThat(Files.readString(sibling), "siblingOutput").isEqualTo("sibling");
			requireThat(Files.readString(library), "libraryOutput").isEqualTo("library");
		}
	}

	/**
	 * Owns real source and snapshot files and supplies only external command results to {@link SourceInputCache}.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final ToolingScope scope = new DefaultToolingScope();
		private final Path base;
		private final Path workspace;
		private final Path source;
		private final Path target;
		private final Map<String, String> environment = new HashMap<>();
		private final List<List<String>> cargoCalls = new ArrayList<>();
		/** Exit status supplied by the external cleanup collaborator. */
		private int cleanStatus;
		/** Cargo metadata field shapes used by SourceCacheMetadataTest, with two workspace members and one external library. */
		private String metadata = """
			{"packages":[
			 {"name":"cache-demo","id":"root","targets":[{"name":"cache_demo","kind":["lib"]},{"name":"cache-demo","kind":["bin"]}]},
			 {"name":"side-demo","id":"side","targets":[{"name":"side_demo","kind":["lib"]},{"name":"side-demo","kind":["bin"]}]},
			 {"name":"external-dependency","id":"external","targets":[{"name":"external_dependency","kind":["lib"]}]}],
			 "workspace_members":["root","side"]}
			""";

		/**
		 * Defines owned paths without performing fallible fixture setup.
		 *
		 * @param base the new owned temporary root
		 */
		private Fixture(Path base)
		{
			this.base = base;
			this.workspace = base.resolve("workspace");
			this.source = workspace.resolve("src/lib.rs");
			this.target = base.resolve("target");
		}

		/**
		 * Allocates a root that the caller immediately owns through try-with-resources.
		 *
		 * @return the uninitialized fixture
		 * @throws IOException if allocation fails
		 */
		private static Fixture create() throws IOException
		{
			Path base = Files.createTempDirectory("source-input-unit-");
			try
			{
				return new Fixture(base.toRealPath());
			}
			catch (IOException failure)
			{
				try
				{
					PathUtils.deleteDirectory(base);
				}
				catch (IOException cleanupFailure)
				{
					failure.addSuppressed(cleanupFailure);
				}
				throw failure;
			}
		}

		/**
		 * Creates the minimal source inventory and explicitly supplies every command environment value.
		 *
		 * @throws IOException if writing fails
		 */
		private void initialize() throws IOException
		{
			Files.createDirectories(base.resolve("captures"));
			write(workspace.resolve("Cargo.toml"), "[package]\nname = \"cache-demo\"\nversion = \"0.1.0\"\n", SAVED);
			write(source, "pub fn value() -> u8 { 7 }\n", SAVED);
			environment.put("CARGO", "fixture-cargo");
			environment.put("CARGO_TARGET_DIR", target.toString());
		}

		/**
		 * Writes controlled source or cached-output bytes and their modification time.
		 *
		 * @param path the owned file
		 * @param text file contents
		 * @param time modification time
		 * @return the file path
		 * @throws IOException if writing fails
		 */
		private Path write(Path path, String text, Instant time) throws IOException
		{
			Files.createDirectories(path.getParent());
			Files.writeString(path, text);
			Files.setLastModifiedTime(path, FileTime.from(time));
			return path;
		}

		/**
		 * Supplies the controlled clock, process environment and external command collaborator.
		 *
		 * @return the cache operation context
		 */
		private SourceInputCache.Context context()
		{
			return new SourceInputCache.Context(scope, workspace, base.resolve("captures"), environment,
				Clock.fixed(NOW, ZoneOffset.UTC), this::capture);
		}

		/**
		 * Supplies the selected profile and root executable.
		 *
		 * @param profile compiled-output profile
		 * @return the preparation or recording request
		 */
		private SourceInputCache.Request request(CargoCacheProfile profile)
		{
			return new SourceInputCache.Request(workspace, "test-target", Set.of("cache-demo"), profile);
		}

		/**
		 * Locates one profile's successful snapshot.
		 *
		 * @param profile compiled-output profile
		 * @return the snapshot path
		 */
		private Path snapshot(CargoCacheProfile profile)
		{
			return profile.snapshotDirectory(target).resolve("test-target.json");
		}

		/**
		 * Seeds prior success directly, avoiding unrelated compilation and preparation workflows.
		 *
		 * @param profile compiled-output profile
		 * @throws IOException if inventory or snapshot writing fails
		 */
		private void seedSuccess(CargoCacheProfile profile) throws IOException
		{
			SourceInputSnapshots.write(scope, snapshot(profile), inventory());
		}

		/**
		 * Inventories actual fixture bytes and timestamps with controlled Git discovery.
		 *
		 * @return current source inputs
		 * @throws IOException if inventory fails
		 */
		private SourceInputInventory.Snapshot inventory() throws IOException
		{
			return SourceInputInventory.read(scope, workspace, target, Optional.empty(),
				base.resolve("captures"), environment, this::capture);
		}

		/**
		 * Prepares the requested profile through the production coordinator.
		 *
		 * @param profile compiled-output profile
		 * @throws IOException if preparation fails
		 */
		private void prepare(CargoCacheProfile profile) throws IOException
		{
			prepare(request(profile));
		}

		/**
		 * Prepares explicit executable selection through the production coordinator.
		 *
		 * @param request executable selection and profile
		 * @throws IOException if preparation fails
		 */
		private void prepare(SourceInputCache.Request request) throws IOException
		{
			try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
			{
				SourceInputCache.prepare(request, context(), output, output);
			}
		}

		/**
		 * Records success through the production coordinator.
		 *
		 * @param profile compiled-output profile
		 * @throws IOException if recording fails
		 */
		private void record(CargoCacheProfile profile) throws IOException
		{
			try (PrintStream output = new PrintStream(new ByteArrayOutputStream(), true, UTF_8))
			{
				SourceInputCache.record(request(profile), context(), output, output);
			}
		}

		/**
		 * Supplies only external discovery and cleanup results; it never changes source, outputs or snapshots.
		 * Git's unsuccessful root discovery selects the real filesystem inventory. Cargo metadata follows the
		 * documented metadata fields; cleanup returns an ordinary configured process status.
		 *
		 * @param command executable and arguments emitted by production code
		 * @param directory child working directory
		 * @param temporary owned capture directory
		 * @param overrides explicit child environment
		 * @return controlled external status and output
		 * @throws IOException if production requests an unexpected command
		 */
		private SystemCommands.Result capture(List<String> command, Path directory, Path temporary,
			Map<String, String> overrides) throws IOException
		{
			requireThat(directory, "commandWorkingDirectory").isEqualTo(workspace);
			requireThat(temporary, "commandTemporaryDirectory").isEqualTo(base.resolve("captures"));
			requireThat(overrides, "commandEnvironment").isEqualTo(environment);
			if (command.equals(List.of("git", "-C", workspace.toString(), "rev-parse", "--show-toplevel")))
				return new SystemCommands.Result(1, "", "outsideAGitRepository");
			if (command.getFirst().equals("fixture-cargo"))
			{
				cargoCalls.add(List.copyOf(command));
				if (command.get(1).equals("metadata"))
				{
					requireThat(command, "metadataCommand").isEqualTo(List.of("fixture-cargo", "metadata", "--no-deps",
						"--format-version", "1", "--manifest-path", workspace.resolve("Cargo.toml").toString()));
					return new SystemCommands.Result(0, metadata, "");
				}
				if (command.get(1).equals("clean"))
					return new SystemCommands.Result(cleanStatus, "", "controlledCleanupResult");
			}
			throw new IOException("Unexpected fixture command: " + command);
		}

		/**
		 * Selects cleanup invocations from captured Cargo requests, preserving their order.
		 *
		 * @return the emitted cleanup argument lists, empty when preparation requested none
		 */
		private List<List<String>> cleanCalls()
		{
			return cargoCalls.stream().filter(command -> command.get(1).equals("clean")).toList();
		}

		@Override
		public void close() throws IOException
		{
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
