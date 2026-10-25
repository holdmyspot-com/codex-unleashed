package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Preserves content-checked timestamps for the verified V8 binding outside the source checkout.
 */
final class V8BindingCache
{
	/**
	 * Holds the target-specific cache record and the currently configured binding inventory.
	 *
	 * @param snapshot the successful snapshot beside compiled Cargo outputs
	 * @param current the declared binding and its parent timestamp, confined to that parent
	 */
	private record State(Path snapshot, SourceInputInventory.Snapshot current)
	{
	}

	/**
	 * Prevents construction.
	 */
	private V8BindingCache()
	{
	}

	/**
	 * Records pending inputs and restores unchanged binding timestamps. The caller must clean V8
	 * when neither successful nor completed-preparation state exists. Resumable state does not certify build success.
	 *
	 * @param request the target whose compiled outputs will be reused
	 * @param context the explicit binding configuration, timestamp clock and working directory
	 * @param targetDirectory compiled Cargo outputs containing successful and pending snapshots
	 * @param out progress output
	 * @return true when the caller must clean the cached V8 package before building
	 * @throws IOException if the configured input, snapshot or timestamp operation fails
	 */
	static boolean prepare(SourceInputCache.Request request, SourceInputCache.Context context,
		Path targetDirectory, PrintStream out) throws IOException
	{
		State state = load(request, context, targetDirectory);
		if (state == null)
			return false;
		Path pending = SourceInputSnapshots.pending(state.snapshot());
		Optional<JsonNode> interrupted = read(pending);
		Path resumable = SourceInputSnapshots.resumable(state.snapshot());
		Optional<JsonNode> previous = read(resumable);
		Files.deleteIfExists(resumable);
		if (previous.isEmpty())
			previous = read(state.snapshot());
		SourceInputSnapshots.write(pending, state.current());
		if (previous.isEmpty())
		{
			out.println("V8 binding snapshot absent; rebuilding its cached package.");
			return true;
		}
		int changed = SourceInputChanges.apply(state.current(), previous.orElseThrow(), interrupted,
			context.clock()).size();
		out.println("V8 binding content changes: " + changed + "; unchanged input timestamps restored.");
		return false;
	}

	/**
	 * Retains binding timestamps after complete preparation without recording build success.
	 *
	 * @param request the prepared target
	 * @param context the explicit binding configuration and process authority
	 * @param targetDirectory compiled output storage containing the pending binding inventory
	 * @throws IOException if content drifts, loading fails or checkpoint writing fails
	 */
	static void checkpoint(SourceInputCache.Request request, SourceInputCache.Context context,
		Path targetDirectory) throws IOException
	{
		State state = load(request, context, targetDirectory);
		if (state != null)
			SourceInputSnapshots.checkpoint(state.snapshot(), state.current());
	}

	/**
	 * Records the binding after successful compilation, refusing content drift since preparation.
	 * An absent binding configuration leaves the cache unchanged.
	 *
	 * @param request the compiled target
	 * @param context the explicit binding configuration and working directory
	 * @param targetDirectory compiled Cargo outputs containing the prepared snapshot
	 * @throws IOException if prepared inputs differ or snapshot access fails
	 */
	static void record(SourceInputCache.Request request, SourceInputCache.Context context,
		Path targetDirectory) throws IOException
	{
		State state = load(request, context, targetDirectory);
		if (state != null)
			SourceInputSnapshots.record(state.snapshot(), state.current());
	}

	/**
	 * Inventories only the declared regular binding file, without widening checkout path confinement.
	 *
	 * @param request the compiled target
	 * @param context the explicit binding configuration and working directory
	 * @param targetDirectory compiled Cargo output storage
	 * @return the binding inventory, or null when its environment variable is absent
	 * @throws IOException if the configured value is blank, not a regular file or inaccessible
	 */
	private static State load(SourceInputCache.Request request, SourceInputCache.Context context,
		Path targetDirectory) throws IOException
	{
		String configured = context.environment().get("RUSTY_V8_SRC_BINDING_PATH");
		if (configured == null)
			return null;
		if (configured.isBlank())
			throw new IOException("RUSTY_V8_SRC_BINDING_PATH must name the verified binding file when configured.");
		Path binding = context.workingDirectory().resolve(Path.of(configured)).toRealPath();
		if (!Files.isRegularFile(binding))
			throw new IOException("V8 binding input is not a regular file: " + binding);
		Path root = binding.getParent();
		String name = binding.getFileName().toString();
		SourceInputInventory.Snapshot current = new SourceInputInventory.Snapshot(root,
			Map.of(name, Sha256.digest(binding)),
			Map.of(name, SourceInputTimes.nanos(SourceInputTimes.lastModified(binding)),
				".", SourceInputTimes.nanos(SourceInputTimes.lastModified(root))));
		Path snapshot = targetDirectory.resolve(".codex-source-inputs").resolve("v8-binding-" +
			SourceCacheTargets.directoryName(request.target()) + ".json");
		return new State(snapshot, current);
	}

	/**
	 * Reads existing non-null state using the same snapshot contract as checkout inputs.
	 *
	 * @param path a successful or pending snapshot
	 * @return its document, or empty for an absent file or JSON null
	 * @throws IOException if reading or parsing fails
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
}
