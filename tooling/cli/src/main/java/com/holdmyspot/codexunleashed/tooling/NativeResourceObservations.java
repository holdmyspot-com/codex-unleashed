package com.holdmyspot.codexunleashed.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hashes explicitly located finalized resources without resolving historical diagnostic values.
 */
public final class NativeResourceObservations
{
	/** Conservative bound on resource inventory state before final serialized-size validation. */
	private static final int MAX_RESOURCES = ReleaseManifestSchema.MAX_DOCUMENT_BYTES / 64;

	/**
	 * Prevents construction.
	 */
	private NativeResourceObservations()
	{
	}

	/**
	 * Collects both finalized package resource trees and an optional explicit V8 file.
	 *
	 * @param scope borrowed JSON services
	 * @param packageRoot explicit parent of finalized package variants
	 * @param target selected release target
	 * @param v8 optional explicitly configured local archive
	 * @return resources sorted by role then Unicode scalar path
	 * @throws IOException if enumeration or hashing fails
	 * @throws IllegalArgumentException if resources are unsafe or inconsistent
	 */
	public static JsonNode collect(ToolingScope scope, Path packageRoot, String target, Path v8) throws IOException
	{
		requireDirectory(packageRoot);
		Map<String, JsonNode> packaged = new TreeMap<>(NativeResourceObservations::compareUnicode);
		long inventoryBytes = 0;
		for (String variant : List.of("codex", "codex-app-server"))
		{
			Path directory = packageRoot.resolve(variant + "-package-" + target);
			requireDirectory(directory);
			Path resources = directory.resolve("codex-resources");
			requireDirectory(resources);
			try (Stream<Path> tree = Files.walk(resources))
			{
				java.util.Iterator<Path> paths = tree.iterator();
				int count = 0;
				while (paths.hasNext())
				{
					Path path = paths.next();
					ReleaseManifestSchema.require(!Files.isSymbolicLink(path), "Symbolic resource path: " + path);
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
						continue;
					++count;
					ReleaseManifestSchema.require(count <= MAX_RESOURCES, "Too many resource entries: " + resources);
					List<String> components = new ArrayList<>();
					for (Path component : directory.relativize(path))
						components.add(component.toString());
					String name = String.join("/", components);
					JsonNode resource = hash(scope, path, "packaged_resource", name);
					JsonNode previous = packaged.putIfAbsent(name, resource);
					ReleaseManifestSchema.require(previous == null || previous.equals(resource),
						"Package variants contain unequal resource bytes: " + name);
					if (previous == null)
						inventoryBytes += name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 64L;
					ReleaseManifestSchema.require(inventoryBytes <= ReleaseManifestSchema.MAX_DOCUMENT_BYTES,
						"Resource inventory exceeds 4 MiB: " + resources);
				}
			}
		}
		ArrayNode resources = scope.getJsonMapper().createArrayNode();
		if (v8 != null)
			resources.add(hash(scope, v8, "configured_v8_archive", v8.getFileName().toString()));
		for (JsonNode resource : packaged.values())
			resources.add(resource);
		return resources;
	}

	/**
	 * Requires an explicit nonsymbolic resource directory.
	 *
	 * @param path explicit directory
	 */
	private static void requireDirectory(Path path)
	{
		ReleaseManifestSchema.require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
			"Resource directory is missing or symbolic: " + path);
	}

	/**
	 * Derives size and SHA256 from the same bounded-buffer read.
	 *
	 * @param scope borrowed JSON services
	 * @param path explicit regular input file
	 * @param role resource role
	 * @param name persisted relative name
	 * @return resource identity
	 * @throws IOException if reading or SHA256 initialization fails
	 */
	private static JsonNode hash(ToolingScope scope, Path path, String role, String name) throws IOException
	{
		ReleaseManifestSchema.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
			"Resource must be a regular nonsymbolic file: " + path);
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException failure)
		{
			throw new IOException("SHA-256 is unavailable", failure);
		}
		long size = 0;
		try (InputStream input = Files.newInputStream(path))
		{
			byte[] buffer = new byte[8192];
			for (int count = input.read(buffer); count != -1; count = input.read(buffer))
			{
				digest.update(buffer, 0, count);
				size = Math.addExact(size, count);
			}
		}
		ObjectNode resource = scope.getJsonMapper().createObjectNode();
		return resource.put("role", role).put("path", name).put("size", size).
			put("sha256", HexFormat.of().formatHex(digest.digest()));
	}

	/**
	 * Orders strings by Unicode scalar values, matching the accepted schema authority.
	 *
	 * @param left first path
	 * @param right second path
	 * @return comparison result
	 */
	private static int compareUnicode(String left, String right)
	{
		java.util.PrimitiveIterator.OfInt leftPoints = left.codePoints().iterator();
		java.util.PrimitiveIterator.OfInt rightPoints = right.codePoints().iterator();
		while (leftPoints.hasNext() && rightPoints.hasNext())
		{
			int compared = Integer.compare(leftPoints.nextInt(), rightPoints.nextInt());
			if (compared != 0)
				return compared;
		}
		return Boolean.compare(leftPoints.hasNext(), rightPoints.hasNext());
	}
}
