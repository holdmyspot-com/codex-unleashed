package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Owns the relocatable, exclusively allocated reproduction attempt. */
public final class ReleaseReproductionState
{
	/** Project-relative attempt namespace. */
	private static final String PREFIX = ".cat/reproduction_attempts/";
	/** Fixed internal directory names. */
	private static final List<String> DIRECTORIES = List.of("fork_checkout", "cargo_target", "build_result",
		"archive_result", "scratch");
	/** Current explicit root, never serialized. */
	private final Path projectRoot;
	/** Random ownership identity. */
	private final String attemptId;
	/** Authenticated manifest byte identity. */
	private final String manifestDigest;
	/** Selected supported target. */
	private final String target;

	/**
	 * Retains validated attempt identity.
	 *
	 * @param projectRoot current project root
	 * @param attemptId random attempt identifier
	 * @param manifestDigest authenticated manifest digest
	 * @param target supported target
	 */
	private ReleaseReproductionState(Path projectRoot, String attemptId, String manifestDigest, String target)
	{
		this.projectRoot = projectRoot.toAbsolutePath().normalize();
		this.attemptId = attemptId;
		this.manifestDigest = manifestDigest;
		this.target = target;
	}

	/**
	 * Allocates one fixed layout and publishes its ownership before any source work.
	 *
	 * @param scope JSON services
	 * @param projectRoot current explicit project root
	 * @param manifestDigest authenticated digest
	 * @param target selected target
	 * @return exclusively owned attempt
	 * @throws IOException if allocation or publication fails
	 */
	public static ReleaseReproductionState allocate(ToolingScope scope, Path projectRoot, String manifestDigest,
		String target) throws IOException
	{
		if (!manifestDigest.matches("[0-9a-f]{64}") || !ReleaseManifestSchema.TARGETS.contains(target))
			throw new IllegalArgumentException("Invalid authenticated attempt identity");
		ReleaseReproductionState state = new ReleaseReproductionState(projectRoot,
			UUID.randomUUID().toString().replace("-", ""), manifestDigest, target);
		Path parent = state.root().getParent();
		state.noSymlinks(parent);
		Files.createDirectories(parent);
		Files.createDirectory(state.root());
		try
		{
			for (String directory : DIRECTORIES)
				Files.createDirectory(state.root().resolve(directory));
			atomicWrite(scope, state.root().resolve("owner.json"), state.owner(scope));
			ObjectNode value = state.owner(scope);
			value.put("target", target);
			value.put("path_base", "project_root");
			value.put("attempt_root", PREFIX + state.attemptId);
			atomicWrite(scope, state.root().resolve("state.json"), value);
			return state;
		}
		catch (IOException | RuntimeException failure)
		{
			try
			{
				deleteTree(state.root());
			}
			catch (IOException cleanup)
			{
				failure.addSuppressed(cleanup);
			}
			throw failure;
		}
	}

	/**
	 * Loads only a canonical state name against the caller's current project root.
	 *
	 * @param scope JSON services
	 * @param projectRoot current project root
	 * @param name project-relative POSIX state name
	 * @return validated attempt
	 * @throws IOException if the state or owner cannot be read
	 */
	public static ReleaseReproductionState load(ToolingScope scope, Path projectRoot, String name)
		throws IOException
	{
		if (!name.matches("\\.cat/reproduction_attempts/[0-9a-f]{32}/state\\.json"))
			throw new IllegalArgumentException("State must be a canonical project-relative attempt state");
		String id = name.substring(PREFIX.length(), PREFIX.length() + 32);
		ReleaseReproductionState candidate = new ReleaseReproductionState(projectRoot, id, "", "");
		candidate.noSymlinks(candidate.root().resolve("state.json"));
		JsonNode value = ReleaseManifestSchema.read(scope, candidate.root().resolve("state.json"));
		ReleaseManifestSchema.fields(value, "reproduction state", "format_version", "target",
			"authenticated_manifest_sha256", "attempt_id", "path_base", "attempt_root");
		String digest = ReleaseManifestSchema.text(value.path("authenticated_manifest_sha256"));
		String target = ReleaseManifestSchema.text(value.path("target"));
		if (!value.path("format_version").isIntegralNumber() || !value.path("format_version").bigIntegerValue().
			equals(java.math.BigInteger.ONE) || !id.equals(ReleaseManifestSchema.text(value.path("attempt_id"))) ||
			!"project_root".equals(ReleaseManifestSchema.text(value.path("path_base"))) ||
			!(PREFIX + id).equals(ReleaseManifestSchema.text(value.path("attempt_root"))) ||
			!digest.matches("[0-9a-f]{64}") || !ReleaseManifestSchema.TARGETS.contains(target))
			throw new IllegalArgumentException("Invalid reproduction state identity");
		ReleaseReproductionState state = new ReleaseReproductionState(projectRoot, id, digest, target);
		state.checkMarker(scope, state.root().resolve("owner.json"));
		try (Stream<Path> children = Files.list(state.root()))
		{
			for (Path child : children.toList())
			{
				String filename = child.getFileName().toString();
				if (!DIRECTORIES.contains(filename) && !filename.equals("owner.json") && !filename.equals("state.json"))
					throw new IOException("Unexpected owned attempt layout entry: " + filename);
			}
		}
		for (String directory : DIRECTORIES)
		{
			Path path = state.root().resolve(directory);
			state.noSymlinks(path);
			if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Missing owned attempt directory: " + directory);
		}
		return state;
	}

	/**
	 * Returns current absolute attempt root.
	 *
	 * @return current absolute attempt root
	 */
	public Path root()
	{
		return projectRoot.resolve(PREFIX + attemptId);
	}

	/**
	 * Returns canonical relocatable state name.
	 *
	 * @return canonical relocatable state name
	 */
	public String stateName()
	{
		return PREFIX + attemptId + "/state.json";
	}

	/**
	 * Returns authenticated manifest byte digest.
	 *
	 * @return authenticated manifest byte digest
	 */
	public String manifestDigest()
	{
		return manifestDigest;
	}

	/**
	 * Returns selected target.
	 *
	 * @return selected target
	 */
	public String target()
	{
		return target;
	}

	/**
	 * Binds an initially empty output or checks a relocated output marker.
	 *
	 * @param scope JSON services
	 * @param output live output directory
	 * @param create whether an empty directory may be bound
	 * @throws IOException if output is unsafe or owned by another attempt
	 */
	public void output(ToolingScope scope, Path output, boolean create) throws IOException
	{
		Path path = output.toAbsolutePath().normalize();
		if (path.startsWith(root()) || root().startsWith(path) || Files.isSymbolicLink(path))
			throw new IOException("Output aliases the owned attempt");
		if (create)
		{
			Files.createDirectories(path);
			try (Stream<Path> children = Files.list(path))
			{
				if (children.findAny().isPresent())
					throw new IOException("Output must be new or empty");
			}
			atomicWrite(scope, path.resolve(".reproduction_owner.json"), owner(scope));
		}
		else
			checkMarker(scope, path.resolve(".reproduction_owner.json"));
	}

	/**
	 * Validates an external identity marker without granting deletion authority.
	 *
	 * @param scope JSON services
	 * @param marker live marker location
	 * @throws IOException if the marker cannot be read or differs
	 */
	public void checkMarker(ToolingScope scope, Path marker) throws IOException
	{
		if (Files.isSymbolicLink(marker))
			throw new IOException("Ownership marker is a symbolic link");
		JsonNode value = ReleaseManifestSchema.read(scope, marker);
		ReleaseManifestSchema.fields(value, "reproduction owner", "format_version", "attempt_id",
			"authenticated_manifest_sha256");
		if (!ReleaseManifestSchema.same(owner(scope), value))
			throw new IOException("Reproduction ownership marker differs");
	}

	/**
	 * Publishes an identity-only external marker.
	 *
	 * @param scope JSON services
	 * @param marker fresh metadata path
	 * @throws IOException if publication fails
	 */
	public void writeMarker(ToolingScope scope, Path marker) throws IOException
	{
		if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Ownership marker already exists");
		atomicWrite(scope, marker, owner(scope));
	}

	/**
	 * Removes only the validated owned attempt, without following descendant links.
	 *
	 * @param scope JSON services
	 * @throws IOException if validation or removal fails
	 */
	public void cleanup(ToolingScope scope) throws IOException
	{
		load(scope, projectRoot, stateName());
		deleteTree(root());
	}

	/**
	 * Writes JSON through a sibling temporary and atomic replacement.
	 *
	 * @param scope JSON services
	 * @param path destination
	 * @param value bounded JSON document
	 * @throws IOException if writing or atomic replacement fails
	 */
	public static void atomicWrite(ToolingScope scope, Path path, JsonNode value) throws IOException
	{
		if (Files.isSymbolicLink(path))
			throw new IOException("JSON destination is a symbolic link");
		Path temporary = Files.createTempFile(path.getParent(), ".reproduction-", ".json");
		try
		{
			ReleaseManifestSchema.write(scope, temporary, value);
			if (Files.size(temporary) > ReleaseManifestSchema.MAX_DOCUMENT_BYTES)
				throw new IOException("Reproduction document exceeds its bound");
			try
			{
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException failure)
			{
				throw new IOException("Reproduction state requires atomic publication", failure);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Builds the identity-only ownership document.
	 *
	 * @param scope borrowed JSON services
	 * @return ownership marker fields
	 */
	private ObjectNode owner(ToolingScope scope)
	{
		ObjectNode value = scope.getJsonMapper().createObjectNode();
		value.put("format_version", 1);
		value.put("attempt_id", attemptId);
		value.put("authenticated_manifest_sha256", manifestDigest);
		return value;
	}

	/**
	 * Rejects symlink ancestors below the explicit root.
	 *
	 * @param path current filesystem path
	 * @throws IOException if the required operation cannot complete
	 */
	private void noSymlinks(Path path) throws IOException
	{
		Path cursor = projectRoot;
		for (Path segment : projectRoot.relativize(path))
		{
			cursor = cursor.resolve(segment);
			if (Files.isSymbolicLink(cursor))
				throw new IOException("Symbolic link in owned attempt path: " + cursor);
		}
	}

	/**
	 * Unlinks descendants without following symbolic links.
	 *
	 * @param root owned tree root
	 * @throws IOException if the required operation cannot complete
	 */
	private static void deleteTree(Path root) throws IOException
	{
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
				Files.delete(path);
		}
	}
}
