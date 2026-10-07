package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/** Prepares Cargo cache reuse from source contents and records inputs only after successful compilation. */
public final class SourceInputCache
{
	/** Prevents construction. */
	private SourceInputCache()
	{
	}

	/**
	 * Supplies the workspace, original Cargo target argument and requested executable names.
	 *
	 * @param workspace the manifest workspace, relative to the context directory when not absolute
	 * @param target the native triple or custom JSON target filename
	 * @param binaries executable names, ignored by successful-build recording
	 */
	public record Request(Path workspace, String target, Set<String> binaries)
	{
		/**
		 * Validates target confinement before any filesystem or Cargo operation.
		 *
		 * @param workspace the workspace
		 * @param target the original target argument
		 * @param binaries requested executable names
		 * @throws NullPointerException if an argument or binary name is null
		 * @throws IllegalArgumentException if the target is not one confined filename
		 */
		public Request
		{
			Objects.requireNonNull(workspace, "workspace");
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
		 * Copies the environment and anchors relative paths to the caller directory.
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
	 * Writes pending inputs, restores unchanged sources and invalidates only requested executable units.
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
			throw new IllegalArgumentException("At least one release binary is required");
		Inputs inputs = load(request, context, err);
		Path pending = SourceInputSnapshots.pending(inputs.snapshot());
		Optional<JsonNode> interrupted = read(pending);
		SourceInputSnapshots.write(pending, inputs.current());
		Map<String, String> owners = inputs.metadata().binaryOwners(request.binaries());
		Set<String> missing = new TreeSet<>(request.binaries());
		missing.removeAll(owners.keySet());
		if (!missing.isEmpty())
			throw new IOException("Release binaries absent from Cargo metadata: " + String.join(", ", missing));
		Optional<JsonNode> previous = read(inputs.snapshot());
		if (previous.isEmpty())
		{
			List<String> packages = inputs.metadata().workspacePackageNames();
			clean(request, context, inputs.manifest(), packages, out, err);
			out.println("Source snapshot absent; rebuilding " + packages.size() + " workspace packages; " +
				"external dependencies retained.");
			return;
		}

		Set<String> changed = SourceInputChanges.apply(inputs.current(), previous.orElseThrow(), interrupted,
			context.clock());
		long unchanged = inputs.current().files().keySet().stream().filter(name -> !changed.contains(name)).count();
		out.println("Source contents changed in " + changed.size() + " files; Cargo determines affected units; " +
			unchanged + " unchanged files retained.");
		Set<String> libraries = inputs.metadata().libraryOwners();
		Set<String> binaryOnly = new TreeSet<>(owners.values());
		binaryOnly.removeAll(libraries);
		if (!binaryOnly.isEmpty())
			clean(request, context, inputs.manifest(), List.copyOf(binaryOnly), out, err);
		Path release = inputs.targetDirectory().resolve(SourceCacheTargets.directoryName(request.target())).
			resolve("release");
		for (String binary : new TreeSet<>(request.binaries()))
		{
			if (libraries.contains(owners.get(binary)))
			{
				int count = CachedBinaryOutputs.invalidate(release, binary);
				out.println("Invalidated " + count + " cached binary fingerprints for " + binary +
					"; library outputs retained.");
			}
		}
	}

	/**
	 * Records current inputs only when they match the prepared build, apart from Cargo-generated lockfiles.
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
		Path snapshot = target.resolve(".codex-source-inputs").
			resolve(SourceCacheTargets.directoryName(request.target()) + ".json");
		Optional<Path> cargoHome = Optional.ofNullable(context.environment().get("CARGO_HOME")).
			filter(value -> !value.isEmpty()).map(value -> context.workingDirectory().resolve(value));
		SourceInputInventory.Snapshot current = SourceInputInventory.read(workspace, target, cargoHome,
			context.temporaryDirectory(), context.environment());
		return new Inputs(manifest, metadata, target, snapshot, current);
	}

	/**
	 * Retains the original absent-or-null snapshot behavior without consuming malformed non-null documents early.
	 *
	 * @param path the snapshot or pending filename
	 * @return its non-null document when it is a regular file
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
		List<String> arguments = new ArrayList<>(List.of("clean", "--release", "--target", request.target(),
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
