package com.holdmyspot.codexunleashed.tooling.release;

import com.holdmyspot.codexunleashed.tooling.ArtifactJson;
import com.holdmyspot.codexunleashed.tooling.PayloadDirectories;
import com.holdmyspot.codexunleashed.tooling.PayloadFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles public and early-access npm package families from the six complete release package trees.
 */
public final class NpmPackages
{
	private static final List<String> LEGAL_FILES = List.of("LICENSE.md", "docs/LICENSE.html", "docs/terms.html",
		"docs/privacy.html");
	private static final Set<PosixFilePermission> LAUNCHER_PERMISSIONS = PosixFilePermissions.fromString("rwxr-xr-x");
	private static final List<Family> FAMILIES = List.of(new Family("public", "codex-unleashed", "public"),
		new Family("ea", "codex-unleashed-ea", "restricted"));

	/**
	 * Prevents construction.
	 */
	private NpmPackages()
	{
	}

	/**
	 * Supplies extracted inputs, a new package output directory, and exact publication metadata.
	 *
	 * @param sources the target-to-extracted-package mapping
	 * @param packagesDirectory the new output directory whose parent already exists
	 * @param scope the exact at-prefixed npm scope
	 * @param version the already converted npm version
	 * @param registry the supplied npm registry
	 */
	public record Request(Map<String, Path> sources, Path packagesDirectory, String scope, String version,
		String registry)
	{
		/**
		 * Retains required inputs without normalizing version, scope, or registry text.
		 *
		 * @param sources the target-to-extracted-package mapping
		 * @param packagesDirectory the new output directory
		 * @param scope the exact npm scope
		 * @param version the converted npm version
		 * @param registry the supplied registry
		 * @throws NullPointerException if an argument, source key, or source path is null
		 */
		public Request
		{
			sources = Map.copyOf(sources);
			Objects.requireNonNull(packagesDirectory, "packagesDirectory");
			Objects.requireNonNull(scope, "scope");
			Objects.requireNonNull(version, "version");
			Objects.requireNonNull(registry, "registry");
		}
	}

	/**
	 * Identifies one assembled package and its required publication access.
	 *
	 * @param path the assembled package directory
	 * @param access the exact npm access argument
	 */
	public record Directory(Path path, String access)
	{
		/**
		 * Retains the package location and access value.
		 *
		 * @param path the assembled package directory
		 * @param access the npm access argument
		 * @throws NullPointerException if an argument is null
		 */
		public Directory
		{
			Objects.requireNonNull(path, "path");
			Objects.requireNonNull(access, "access");
		}
	}

	/**
	 * Creates six native packages and one selector per family in their original publication order.
	 * The caller owns the output tree, including partial artifacts after failure.
	 *
	 * @param request the extracted sources and publication metadata
	 * @return fourteen immutable package directory/access entries, with each family's selector last
	 * @throws NullPointerException if {@code request} is null
	 * @throws IllegalArgumentException if the scope has no at-prefix or a required target is absent
	 * @throws IOException if the output exists or file, legal-material, resource, or attribute access fails
	 */
	public static List<Directory> assemble(Request request) throws IOException
	{
		Objects.requireNonNull(request, "request");
		if (!request.scope().startsWith("@"))
			throw new IllegalArgumentException("--scope must include the @ prefix");
		for (NpmPlatform platform : NpmPlatform.values())
			if (!request.sources().containsKey(platform.target()))
				throw new IllegalArgumentException("Missing extracted npm target: " + platform.target());
		Files.createDirectory(request.packagesDirectory());
		List<Directory> result = new ArrayList<>();
		for (Family family : FAMILIES)
		{
			Map<String, String> optional = new LinkedHashMap<>();
			for (NpmPlatform platform : NpmPlatform.values())
			{
				String name = request.scope() + "/" + family.base() + "-" + platform.packageSuffix();
				Path directory = request.packagesDirectory().resolve(family.directory()).resolve(platform.packageSuffix());
				Path vendor = directory.resolve("vendor").resolve(platform.target());
				Files.createDirectories(vendor.getParent());
				Path source = request.sources().get(platform.target());
				PayloadDirectories.copy(source, vendor);
				copyLegalFiles(source, directory);
				Map<String, Object> metadata = commonMetadata(name, request.version(),
					"Codex Unleashed native binaries for " + platform.packageSuffix() + ".");
				metadata.put("os", List.of(platform.operatingSystem()));
				metadata.put("cpu", List.of(platform.architecture()));
				metadata.put("files", packageFiles("vendor"));
				writeMetadata(directory, metadata);
				result.add(new Directory(directory, family.access()));
				optional.put(name, request.version());
			}
			Path main = request.packagesDirectory().resolve(family.directory()).resolve("main");
			Files.createDirectories(main.resolve("bin"));
			writeLauncher(main.resolve("bin/codex.js"), request.scope() + "/" + family.base() + "-");
			copyLegalFiles(request.sources().get(NpmPlatform.values()[0].target()), main);
			String name = request.scope() + "/" + family.base();
			Map<String, Object> metadata = commonMetadata(name, request.version(),
				"Codex CLI distributed by Codex Unleashed.");
			metadata.put("type", "module");
			metadata.put("bin", Map.of("codex", "bin/codex.js"));
			metadata.put("files", packageFiles("bin"));
			metadata.put("optionalDependencies", optional);
			metadata.put("publishConfig", Map.of("registry", request.registry()));
			writeMetadata(main, metadata);
			Files.writeString(main.resolve("README.md"), "# " + name + "\n\nCodex Unleashed build " + request.version() +
				".\n\nLicense: [Codex Unleashed product license](LICENSE.md); upstream and third-party materials " +
				"retain their " +
				"licenses under [licenses/](licenses/), including [Apache License 2.0]" +
				"(licenses/openai-codex/LICENSE-APACHE-2.0).\n");
			result.add(new Directory(main, family.access()));
		}
		return List.copyOf(result);
	}

	/**
	 * Creates the common metadata fields in their maintained key order.
	 *
	 * @param name the package name
	 * @param version the npm version
	 * @param description the package description
	 * @return the mutable ordered metadata
	 */
	private static Map<String, Object> commonMetadata(String name, String version, String description)
	{
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", name);
		metadata.put("version", version);
		metadata.put("description", description);
		metadata.put("license", "See LICENSE.md");
		return metadata;
	}

	/**
	 * Lists the executable root and the retained legal payload paths.
	 *
	 * @param executableRoot vendor or bin
	 * @return the ordered npm files inventory
	 */
	private static List<String> packageFiles(String executableRoot)
	{
		List<String> files = new ArrayList<>(List.of(executableRoot, "licenses"));
		files.addAll(LEGAL_FILES);
		return List.copyOf(files);
	}

	/**
	 * Copies exact product documents and the complete generated third-party license tree.
	 *
	 * @param source the extracted platform package
	 * @param destination the npm package directory
	 * @throws IOException if source materials or copying fail
	 */
	private static void copyLegalFiles(Path source, Path destination) throws IOException
	{
		for (String name : LEGAL_FILES)
		{
			Path output = destination.resolve(name);
			Files.createDirectories(output.getParent());
			PayloadFiles.copy(source.resolve(name), output);
		}
		Path licenses = source.resolve("licenses");
		if (!Files.isDirectory(licenses))
			throw new IOException("Missing generated third-party licenses: " + licenses);
		PayloadDirectories.copy(licenses, destination.resolve("licenses"));
	}

	/**
	 * Writes the original launcher template with the selected family's platform-package prefix.
	 *
	 * @param output the launcher output file
	 * @param prefix the exact platform package prefix
	 * @throws IOException if the bundled template or output cannot be accessed
	 */
	private static void writeLauncher(Path output, String prefix) throws IOException
	{
		try (InputStream resource = NpmPackages.class.getResourceAsStream("npm-launcher.js"))
		{
			if (resource == null)
				throw new IOException("Missing bundled npm launcher template");
			String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
			Files.writeString(output, template.replace("__PLATFORM_PACKAGE_PREFIX__", prefix));
		}
		if (Files.getFileAttributeView(output, PosixFileAttributeView.class) != null)
			Files.setPosixFilePermissions(output, LAUNCHER_PERMISSIONS);
	}

	/**
	 * Writes maintained ASCII JSON formatting and insertion-ordered metadata fields.
	 *
	 * @param directory the npm package directory
	 * @param metadata the package metadata
	 * @throws IOException if output writing fails
	 */
	private static void writeMetadata(Path directory, Map<String, Object> metadata) throws IOException
	{
		Files.writeString(directory.resolve("package.json"), ArtifactJson.formatPreservingKeyOrder(metadata));
	}

	/**
	 * Associates a package family with its directory, published base name, and required access.
	 *
	 * @param directory the family directory
	 * @param base the published package base name
	 * @param access the npm publication access
	 */
	private record Family(String directory, String base, String access)
	{
	}
}
