package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.io.file.StandardDeleteOption;
import tools.jackson.databind.JsonNode;

/**
 * Prepares Cargo cache reuse from source contents and records inputs only after successful compilation.
 */
public final class SourceInputCache
{
	/**
	 * Prevents construction.
	 */
	private SourceInputCache()
	{
	}

	/**
	 * Supplies the workspace, original Cargo target argument, requested executables and compiled-output profile.
	 *
	 * @param workspace the manifest workspace, relative to the context directory when not absolute
	 * @param target the native triple or custom JSON target filename
	 * @param binaries executable names, ignored by successful-build recording
	 * @param profile compiled-output profile whose freshness is prepared or recorded
	 */
	public record Request(Path workspace, String target, Set<String> binaries, CargoCacheProfile profile)
	{
		/**
		 * Selects the existing release-cache behavior for callers without an explicit profile.
		 *
		 * @param workspace the manifest workspace
		 * @param target the native triple or custom JSON target filename
		 * @param binaries requested executable names
		 * @throws NullPointerException if an argument or binary name is null
		 * @throws IllegalArgumentException if the target is not one confined filename
		 */
		public Request(Path workspace, String target, Set<String> binaries)
		{
			this(workspace, target, binaries, CargoCacheProfile.RELEASE);
		}

		/**
		 * Validates target confinement before any filesystem or Cargo operation.
		 *
		 * @param workspace the workspace
		 * @param target the original target argument
		 * @param binaries requested executable names
		 * @param profile the Cargo compiled-output profile
		 * @throws NullPointerException if an argument or binary name is null
		 * @throws IllegalArgumentException if the target is not one confined filename
		 */
		public Request
		{
			Objects.requireNonNull(workspace, "workspace");
			Objects.requireNonNull(profile, "profile");
			SourceCacheTargets.directoryName(target);
			binaries = Set.copyOf(binaries);
		}
	}

	/**
	 * Defines process and timestamp authorities without reading hidden configuration inside cache operations.
	 *
	 * @param workingDirectory the caller's process directory
	 * @param temporaryDirectory existing owned subprocess capture storage
	 * @param environment effective inherited environment with explicit caller overrides
	 * @param clock timestamp authority for content changes
	 */
	public record Context(Path workingDirectory, Path temporaryDirectory, Map<String, String> environment, Clock clock)
	{
		/**
		 * Copies the environment, makes the working directory absolute, and resolves a relative temporary directory against it.
		 *
		 * @param workingDirectory the caller directory
		 * @param temporaryDirectory capture storage
		 * @param environment the effective environment
		 * @param clock the timestamp authority
		 * @throws NullPointerException if an argument, environment key, or value is null
		 */
		public Context
		{
			workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath();
			temporaryDirectory = workingDirectory.resolve(Objects.requireNonNull(temporaryDirectory, "temporaryDirectory"));
			environment = Map.copyOf(environment);
			Objects.requireNonNull(clock, "clock");
		}
	}

	/**
	 * Contains inputs shared by preparation and successful-build recording.
	 *
	 * @param manifest the workspace manifest
	 * @param metadata Cargo package metadata
	 * @param targetDirectory Cargo output storage
	 * @param snapshot the successful snapshot filename
	 * @param current the current source inventory
	 */
	private record Inputs(Path manifest, SourceCacheMetadata metadata, Path targetDirectory, Path snapshot,
		SourceInputInventory.Snapshot current)
	{
	}

	/**
	 * Prepares source timestamps and profile outputs for Cargo cache reuse, and writes pending input state.
	 * Restores stored timestamps for unchanged sources when reusable state exists. Without reusable state,
	 * cleans workspace packages. If cached profile outputs are newer than the clock, deletes host and target
	 * profile outputs instead of restoring source timestamps. Preparation may change snapshots, timestamps,
	 * or cached outputs before reporting a failure; Cargo decides which compilation units are reusable.
	 *
	 * @param request the workspace, target and binaries
	 * @param context process and timestamp authorities
	 * @param out progress and Cargo output
	 * @param err Cargo diagnostics
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if no executable is requested
	 * @throws IOException if inventory, snapshots, Cargo, confinement or invalidation fails
	 */
	public static void prepare(Request request, Context context, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		if (request.binaries().isEmpty())
			throw new IllegalArgumentException("At least one binary is required");
		Inputs inputs = load(request, context, err);
		Path pending = SourceInputSnapshots.pending(inputs.snapshot());
		Optional<JsonNode> interrupted = read(pending);
		Path resumable = SourceInputSnapshots.resumable(inputs.snapshot());
		Optional<JsonNode> previous = read(resumable);
		Files.deleteIfExists(resumable);
		if (previous.isEmpty())
			previous = read(inputs.snapshot());
		SourceInputSnapshots.write(pending, inputs.current());
		Map<String, String> owners = inputs.metadata().binaryOwners(request.binaries());
		Set<String> missing = new TreeSet<>(request.binaries());
		missing.removeAll(owners.keySet());
		if (!missing.isEmpty())
			throw new IOException("Binaries absent from Cargo metadata: " + String.join(", ", missing));
		if (V8BindingCache.prepare(request, context, inputs.targetDirectory(), out))
			clean(request, context, inputs.manifest(), List.of("v8"), out, err);
		if (hasFutureOutputs(request, context, inputs))
		{
			out.println("Cached " + request.profile().cargoName() +
				" output timestamps exceed the preparation clock; rebuilding profile outputs " +
				"without restoring source timestamps. Dependency downloads retained.");
			for (Path release : outputDirectories(request, inputs))
			{
				if (Files.exists(release))
					PathUtils.deleteDirectory(release, new LinkOption[]{LinkOption.NOFOLLOW_LINKS},
						StandardDeleteOption.OVERRIDE_READ_ONLY);
			}
			checkpoint(request, context, inputs);
			return;
		}
		if (previous.isEmpty())
		{
			List<String> packages = inputs.metadata().workspacePackageNames();
			clean(request, context, inputs.manifest(), packages, out, err);
			out.println("Source snapshot absent; rebuilding " + packages.size() + " workspace packages; " +
				"external dependencies retained.");
			checkpoint(request, context, inputs);
			return;
		}

		Set<String> changed = SourceInputChanges.apply(inputs.current(), previous.orElseThrow(), interrupted,
			context.clock());
		long unchanged = inputs.current().files().keySet().stream().filter(name -> !changed.contains(name)).count();
		out.println("Source contents changed in " + changed.size() + " files; Cargo determines affected units; " +
			unchanged + " unchanged files retained.");
		checkpoint(request, context, inputs);
	}

	/**
	 * Indicates whether cached target or host outputs in the selected profile are newer than the preparation clock.
	 * Changed source timestamps cannot invalidate those outputs reliably after a clock rollback.
	 *
	 * @param request the selected target
	 * @param context the timestamp authority
	 * @param inputs the compiled output location
	 * @return true when a regular cached profile output has a future modification time
	 * @throws IOException if cached output timestamps cannot be read
	 */
	private static boolean hasFutureOutputs(Request request, Context context, Inputs inputs) throws IOException
	{
		Instant now = context.clock().instant();
		for (Path release : outputDirectories(request, inputs))
		{
			if (Files.isDirectory(release))
			{
				try (var outputs = Files.find(release, Integer.MAX_VALUE,
					(path, attributes) -> attributes.isRegularFile() && attributes.lastModifiedTime().toInstant().isAfter(now)))
				{
					if (outputs.findAny().isPresent())
						return true;
				}
				catch (UncheckedIOException failure)
				{
					throw failure.getCause();
				}
			}
		}
		return false;
	}

	/**
	 * Supplies the host and selected-target output directories for the selected Cargo profile.
	 *
	 * @param request the selected target
	 * @param inputs the compiled output root
	 * @return the two owned profile output paths
	 */
	private static List<Path> outputDirectories(Request request, Inputs inputs)
	{
		return List.of(inputs.targetDirectory().resolve(request.profile().cargoName()), inputs.targetDirectory().
			resolve(SourceCacheTargets.directoryName(request.target())).resolve(request.profile().cargoName()));
	}

	/**
	 * Persists effective timestamps only after every preparation operation succeeds.
	 *
	 * @param request the prepared workspace and target
	 * @param context process and storage authorities
	 * @param inputs locations and metadata retained from the preparation being completed
	 * @throws IOException if content drifts, inventory fails or checkpoint writing fails
	 */
	private static void checkpoint(Request request, Context context, Inputs inputs) throws IOException
	{
		SourceInputInventory.Snapshot current = inventory(inputs.current().root(), inputs.targetDirectory(), context);
		V8BindingCache.checkpoint(request, context, inputs.targetDirectory());
		SourceInputSnapshots.checkpoint(inputs.snapshot(), current);
	}

	/**
	 * Records current inputs only when they match the prepared build, excluding paths named {@code Cargo.lock} at any depth.
	 * The caller must invoke this only after successful compilation; this method checks input consistency,
	 * not the build result. Binding state may be recorded before a later checkout-recording failure.
	 *
	 * @param request the workspace and target; executable names do not affect recording
	 * @param context process and storage authorities
	 * @param out the successful recording report
	 * @param err Cargo metadata diagnostics
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if inputs changed, were not prepared, or cannot be recorded
	 */
	public static void record(Request request, Context context, PrintStream out, PrintStream err) throws IOException
	{
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Inputs inputs = load(request, context, err);
		V8BindingCache.record(request, context, inputs.targetDirectory());
		int count = SourceInputSnapshots.record(inputs.snapshot(), inputs.current());
		out.println("Recorded " + count + " source file digests after successful compilation.");
	}

	/**
	 * Reads only metadata and sources required by both operations.
	 *
	 * @param request the cache request
	 * @param context its process authority
	 * @param err metadata diagnostics
	 * @return current source and cache locations
	 * @throws IOException if metadata or inventory fails
	 */
	private static Inputs load(Request request, Context context, PrintStream err) throws IOException
	{
		Path workspace = context.workingDirectory().resolve(request.workspace()).toRealPath();
		Path manifest = workspace.resolve("Cargo.toml");
		SystemCommands.Result result = run(context, List.of("metadata", "--no-deps", "--format-version", "1",
			"--manifest-path", manifest.toString()));
		err.print(result.stderr());
		if (result.status() != 0)
			throw new IOException("Cargo metadata failed with status " + result.status() + ": " + result.stderr().strip());
		SourceCacheMetadata metadata = SourceCacheMetadata.parse(result.stdout());
		Path target = context.workingDirectory().resolve(metadata.targetDirectory(workspace, context.environment()));
		Path snapshot = request.profile().snapshotDirectory(target).
			resolve(SourceCacheTargets.directoryName(request.target()) + ".json");
		SourceInputInventory.Snapshot current = inventory(workspace, target, context);
		return new Inputs(manifest, metadata, target, snapshot, current);
	}

	/**
	 * Collects current contents and effective timestamps within the declared storage boundaries.
	 *
	 * @param workspace the canonical source workspace
	 * @param target compiled output storage to exclude
	 * @param context temporary storage and effective environment
	 * @return the source inventory
	 * @throws IOException if Git, paths, hashing or timestamps cannot be read
	 */
	private static SourceInputInventory.Snapshot inventory(Path workspace, Path target, Context context)
		throws IOException
	{
		Optional<Path> cargoHome = Optional.ofNullable(context.environment().get("CARGO_HOME")).
			filter(value -> !value.isEmpty()).map(value -> context.workingDirectory().resolve(value));
		return SourceInputInventory.read(workspace, target, cargoHome,
			context.temporaryDirectory(), context.environment());
	}

	/**
	 * Reads a snapshot without validating its schema or source mappings.
	 *
	 * @param path the snapshot or pending filename
	 * @return the parsed non-null document, or empty if the path is not a regular file or contains JSON null
	 * @throws IOException if reading or JSON parsing fails
	 */
	private static Optional<JsonNode> read(Path path) throws IOException
	{
		if (!Files.isRegularFile(path))
			return Optional.empty();
		JsonNode document = SourceInputSnapshots.read(path);
		if (document.isNull())
			return Optional.empty();
		return Optional.of(document);
	}

	/**
	 * Cleans selected package owners while preserving all other cached dependency outputs.
	 *
	 * @param request original target argument
	 * @param context the process authority
	 * @param manifest the absolute manifest
	 * @param packages sorted package selectors
	 * @param out Cargo output
	 * @param err Cargo diagnostics
	 * @throws IOException if cleanup fails
	 */
	private static void clean(Request request, Context context, Path manifest, List<String> packages,
		PrintStream out, PrintStream err) throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of("clean", "--profile", request.profile().cargoName(),
			"--target", request.target(),
			"--manifest-path", manifest.toString()));
		for (String name : packages)
		{
			arguments.add("-p");
			arguments.add(name);
		}
		SystemCommands.Result result = run(context, arguments);
		out.print(result.stdout());
		err.print(result.stderr());
		if (result.status() != 0)
			throw new IOException("Cargo clean failed with status " + result.status() + ": " + result.stderr().strip());
	}

	/**
	 * Executes Cargo with the caller's command selection, directory and managed capture storage.
	 *
	 * @param context the explicit process authority
	 * @param arguments arguments following Cargo's executable
	 * @return the ordinary exit status and complete captured output
	 * @throws IOException if process startup, capture or waiting fails
	 */
	private static SystemCommands.Result run(Context context, List<String> arguments) throws IOException
	{
		List<String> command = new ArrayList<>();
		command.add(context.environment().getOrDefault("CARGO", "cargo"));
		command.addAll(arguments);
		return SystemCommands.capture(command, context.workingDirectory(), context.temporaryDirectory(),
			context.environment());
	}
}
